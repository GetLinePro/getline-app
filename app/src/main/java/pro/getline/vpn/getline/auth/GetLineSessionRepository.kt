package pro.getline.vpn.getline.auth

import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pro.getline.vpn.GetLineControlPlaneHostPolicy
import pro.getline.vpn.getline.refresh.ManagedProfileRefresh
import java.io.IOException

/**
 * Owns native session lifecycle. Raw tokens stay inside this layer except when
 * a caller must pass a short-lived access token into authenticated API calls.
 */
class GetLineSessionRepository(
    private val api: GetLineAuthApi,
    private val store: GetLineSessionStore,
) {
    private val sessionRefreshMutex = Mutex()
    private val sessionWriteLock = Any()
    private var sessionWriteGeneration = 0

    fun hasSession(): Boolean = store.hasRefreshToken()

    fun managedBindingSnapshot(): ManagedBindingSnapshot = store.managedBindingSnapshot()

    fun logout() {
        abandonSessionWrites { store.clearAccountState() }
        ManagedProfileRefresh.cancel(store.appContext)
    }

    /** Drop tokens/customer but keep link-only managed profile binding. */
    fun discardSessionKeepingSubscription() {
        abandonSessionWrites { store.clearSessionKeepingBinding() }
    }

    private fun invalidateRejectedSession() {
        abandonSessionWrites { store.clearRejectedSessionKeepingBindingShape() }
    }

    /**
     * Blocking [GetLineAuthApi.refresh] does not see coroutine cancellation.
     * Bump generation under the same lock as [persistRefreshedSession] so an
     * in-flight refresh cannot write tokens after logout/discard.
     */
    private fun abandonSessionWrites(clear: () -> Unit) {
        synchronized(sessionWriteLock) {
            sessionWriteGeneration++
            clear()
        }
    }

    private fun currentSessionWriteGeneration(): Int =
        synchronized(sessionWriteLock) { sessionWriteGeneration }

    private fun persistRefreshedSession(
        session: NativeSession,
        generation: Int,
    ): NativeSession {
        synchronized(sessionWriteLock) {
            if (generation != sessionWriteGeneration) {
                throw CancellationException("Session abandoned")
            }
            store.saveSession(session)
        }
        return session
    }

    suspend fun establishFromWebToken(webToken: String): NativeSession {
        // Optional identity probe; failure is non-fatal for handoff.
        val user = runCatching { api.getCurrentUser(webToken) }.getOrNull()
        if (user?.customerId != null) {
            store.customerId = user.customerId
        }

        val deviceKey = api.generateDeviceKey(webToken)
        val session = api.exchangeDeviceKey(deviceKey.value)
        return persistEstablishedSession(session)
    }

    /**
     * Native PKCE path: exchange one-time code + verifier, then persist session.
     * Does not use device-key endpoints.
     */
    suspend fun establishFromNativeCode(code: String, verifier: String): NativeSession {
        val session = api.exchangeNativeCode(code, verifier)
        return persistEstablishedSession(session)
    }

    private fun persistEstablishedSession(session: NativeSession): NativeSession {
        store.saveSession(session)
        // Bug 3 diagnostic: one boolean after establish — never log token values.
        val hasRefresh = store.hasRefreshToken()
        Log.i(
            "session_established has_refresh=$hasRefresh " +
                "binding=${!store.managedProfileUuid.isNullOrBlank()}",
        )
        if (!hasRefresh) {
            // Do not throw: callers map Exception → AuthFailed and lose the signal.
            Log.e("session_established saveSession did not persist refresh token")
        }
        return session
    }

    suspend fun validAccessToken(): String {
        if (store.isAccessTokenValid()) {
            return store.accessToken
                ?: throw GetLineAuthException.Protocol("Missing access token")
        }
        return refreshSession().accessToken
    }

    suspend fun refreshSession(): NativeSession {
        return sessionRefreshMutex.withLock {
            val generation = currentSessionWriteGeneration()
            // Another waiter may have already refreshed while we queued.
            if (store.isAccessTokenValid()) {
                val access = store.accessToken
                val refresh = store.refreshToken
                if (access != null && refresh != null) {
                    return@withLock NativeSession(
                        accessToken = access,
                        refreshToken = refresh,
                        expiresInSeconds = 0L,
                    )
                }
            }
            val refreshToken = store.refreshToken
                ?: throw GetLineAuthException.Protocol("Missing refresh token")
            val session = api.refresh(refreshToken)
            persistRefreshedSession(session, generation)
        }
    }

    /**
     * Preferred subscription plus the full list from the same round-trip.
     */
    data class PreferredSubscriptionLoad(
        val preferred: SubscriptionItem,
        val all: List<SubscriptionItem>,
    )

    /**
     * Outcome of explicit trial activation (user confirmed).
     * [loadSubscriptionAccountSignal] never enters this path.
     */
    sealed class TrialActivationResult {
        data class Ready(val load: PreferredSubscriptionLoad) : TrialActivationResult()

        /** Dashboard ran; free trial is not available to activate. */
        data class Unavailable(val dashboard: DashboardInfo) : TrialActivationResult()
    }

    /**
     * Loads preferred subscription for profile import.
     * Does not persist the selected ID so callers can compare against
     * [rememberedSubscriptionId] before deciding profile reuse.
     *
     * [SubscriptionItem.subscriptionLink] must pass
     * [GetLineControlPlaneHostPolicy.requireSubscriptionUrl] so e2e never imports
     * a production URL. That check is environment-scoped, not the control-plane
     * allowlist: the link host is RWP's, not ours.
     * Does not restrict manual user-entered import URLs outside this path.
     *
     * Does **not** call dashboard or activate trial. Empty importable list throws
     * [GetLineAuthException.NoSubscription] so the UI can offer explicit activation.
     */
    suspend fun loadPreferredSubscription(): SubscriptionItem {
        return loadPreferredSubscriptionWithList().preferred
    }

    /**
     * Same as [loadPreferredSubscription] but returns the full subscriptions list
     * from the successful read. List items are not host-validated — only
     * [PreferredSubscriptionLoad.preferred] is checked before import.
     */
    suspend fun loadPreferredSubscriptionWithList(): PreferredSubscriptionLoad {
        val response = getSubscriptionsAuthenticated()
        return preferredLoadFrom(response)
    }

    /**
     * User-confirmed trial path: GET dashboard (may auto-activate on current prod),
     * then optional POST /api/dashboard/trial when still available, then reread
     * subscriptions for import.
     */
    suspend fun activateTrialAndLoadPreferred(): TrialActivationResult {
        val info = api.getDashboard(validAccessToken())
        Log.i(
            "trial_activate auto_activated=${info.trialAutoActivated} " +
                "available=${info.trialAvailable} enabled=${info.trialEnabled} " +
                "paid=${info.trialPaid} recurring_only=${info.trialRecurringOnly}",
        )
        if (info.trialAutoActivated) {
            return TrialActivationResult.Ready(loadPreferredSubscriptionWithList())
        }
        if (canPostFreeTrial(info)) {
            api.activateTrial(validAccessToken())
            Log.i("trial_activate post_trial ok")
            return TrialActivationResult.Ready(loadPreferredSubscriptionWithList())
        }
        return TrialActivationResult.Unavailable(info)
    }

    private fun canPostFreeTrial(info: DashboardInfo): Boolean {
        return info.trialEnabled &&
            info.trialAvailable &&
            !info.trialPaid &&
            !info.trialRecurringOnly
    }

    private fun preferredLoadFrom(response: SubscriptionsResponse): PreferredSubscriptionLoad {
        val selected = response.selectPreferred(IMPORTABLE_SUBSCRIPTION)
            ?: throwNoImportableSubscription(response)
        // Enforcement point: selection only pre-filters, the allowlist is applied here.
        GetLineControlPlaneHostPolicy.requireSubscriptionUrl(selected.subscriptionLink)
        return PreferredSubscriptionLoad(
            preferred = selected,
            all = response.subscriptions,
        )
    }

    /**
     * Best-effort preferred subscription (import path).
     * Returns null when there is no session, the API fails, or no importable item.
     */
    suspend fun loadPreferredSubscriptionOrNull(): SubscriptionItem? {
        if (!hasSession()) return null
        return runCatching { loadPreferredSubscription() }.getOrNull()
    }

    /**
     * Optional account signal: authenticated GET /api/subscriptions with single
     * 401 recovery. An active preferred item may ask Home to refresh the managed
     * config, but no response or failure from this method is card presentation data.
     *
     * Does not create trial or mutate VPN/profile state. Rejected credentials may
     * still discard the dead native session through the normal refresh lifecycle.
     */
    suspend fun loadSubscriptionAccountSignal(): SubscriptionAccountSignal {
        if (!hasSession()) {
            return SubscriptionAccountSignal.NoRefresh
        }
        return try {
            val response = getSubscriptionsAuthenticated()
            if (response.selectPreferred()?.isActive == true) {
                SubscriptionAccountSignal.RefreshManagedProfile
            } else {
                SubscriptionAccountSignal.NoRefresh
            }
        } catch (e: CancellationException) {
            // Lifecycle cancellation must not be mapped to a best-effort signal.
            throw e
        } catch (e: GetLineAuthException.HttpFailure) {
            // Refresh recovery may already have invalidated the session. A 403 from
            // subscriptions itself is endpoint policy, not refresh-token rejection.
            if (!hasSession()) {
                SubscriptionAccountSignal.NoRefresh
            } else if (e.code == 401) {
                invalidateRejectedSession()
                SubscriptionAccountSignal.NoRefresh
            } else {
                Log.w("subscription_account_signal http_failure code=${e.code}")
                SubscriptionAccountSignal.Unavailable
            }
        } catch (e: GetLineAuthException) {
            // Malformed/transient refresh failures keep the last persisted session.
            if (!hasSession()) {
                SubscriptionAccountSignal.NoRefresh
            } else {
                Log.w("subscription_account_signal auth_failure kind=${e::class.simpleName}")
                SubscriptionAccountSignal.Unavailable
            }
        } catch (e: Exception) {
            if (!hasSession()) {
                SubscriptionAccountSignal.NoRefresh
            } else {
                // Common when VPN is up and control-plane is routed via broken tunnel.
                Log.w("subscription_account_signal network_failure kind=${e::class.simpleName}")
                SubscriptionAccountSignal.Unavailable
            }
        }
    }

    /**
     * GET /api/subscriptions with Bearer token.
     * On 401: force session refresh once, then retry the request exactly once.
     * Concurrent 401 recoveries share [sessionRefreshMutex].
     */
    suspend fun getSubscriptionsAuthenticated(): SubscriptionsResponse {
        val accessToken = accessTokenForSubscriptions()
        val rejectedRefreshToken = store.refreshToken
        return try {
            api.getSubscriptions(accessToken)
        } catch (first: GetLineAuthException.HttpFailure) {
            if (first.code != 401) throw first
            Log.i("session_recovery stage=first_401")
            val recovered = try {
                forceRefreshSession(
                    rejectedAccessToken = accessToken,
                    rejectedRefreshToken = rejectedRefreshToken,
                ).also {
                    Log.i("session_refresh outcome=ok code=na")
                }
            } catch (cancelled: CancellationException) {
                // Activity destroy/recreate mid-refresh — keep persisted session.
                throw cancelled
            } catch (refreshError: Exception) {
                throwRefreshFailure(refreshError)
            }
            try {
                api.getSubscriptions(recovered.accessToken).also {
                    Log.i("session_recovery stage=retry outcome=ok code=na")
                }
            } catch (second: GetLineAuthException.HttpFailure) {
                // Second 401 after a forced refresh → session is dead.
                // 403 is not session-invalidation; keep tokens and surface as error.
                if (second.code == 401) {
                    Log.w("session_recovery stage=retry outcome=unauthorized code=401")
                    invalidateRejectedSession()
                } else {
                    Log.w("session_recovery stage=retry outcome=http_failure code=${second.code}")
                }
                throw second
            }
        }
    }

    /** Access token for subscriptions, including observable cold-start refresh. */
    private suspend fun accessTokenForSubscriptions(): String {
        if (store.isAccessTokenValid()) {
            return store.accessToken
                ?: throw GetLineAuthException.Protocol("Missing access token")
        }
        Log.i("session_recovery stage=access_expired")
        return try {
            refreshSession().accessToken.also {
                Log.i("session_refresh outcome=ok code=na")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refreshError: Exception) {
            throwRefreshFailure(refreshError)
        }
    }

    /**
     * Refresh a rejected access token once. If another waiter already rotated
     * either token while this caller queued, reuse that persisted session.
     */
    private suspend fun forceRefreshSession(
        rejectedAccessToken: String,
        rejectedRefreshToken: String?,
    ): NativeSession {
        return sessionRefreshMutex.withLock {
            val generation = currentSessionWriteGeneration()
            val currentAccessToken = store.accessToken
            val currentRefreshToken = store.refreshToken
            val sessionChanged =
                currentAccessToken != rejectedAccessToken ||
                    currentRefreshToken != rejectedRefreshToken
            if (
                sessionChanged &&
                store.isAccessTokenValid() &&
                currentAccessToken != null &&
                currentRefreshToken != null
            ) {
                return@withLock NativeSession(
                    accessToken = currentAccessToken,
                    refreshToken = currentRefreshToken,
                    expiresInSeconds = 0L,
                )
            }
            val refreshToken = store.refreshToken
                ?: throw GetLineAuthException.Protocol("Missing refresh token")
            val session = api.refresh(refreshToken)
            persistRefreshedSession(session, generation)
        }
    }

    private fun throwRefreshFailure(error: Exception): Nothing {
        when (error) {
            is GetLineAuthException.HttpFailure -> {
                val rejected = isRejectedRefresh(error.code, error.message)
                if (rejected) {
                    Log.w("session_refresh outcome=rejected code=${error.code}")
                    invalidateRejectedSession()
                } else {
                    Log.w("session_refresh outcome=http_failure code=${error.code}")
                }
            }
            is IOException -> Log.w("session_refresh outcome=network_failure code=na")
            is GetLineAuthException ->
                Log.w("session_refresh outcome=protocol_failure code=na")
            else -> Log.w("session_refresh outcome=unexpected_failure code=na")
        }
        throw error
    }

    /**
     * Persist the GetLine-managed CMFA profile binding for this device.
     * Survives process death and APK update (same applicationId/signature).
     * Cleared only with account state on logout.
     *
     * @param source subscription URL when known (login preferred link or URL import);
     *   enables remote repair without re-login for URL-import users.
     */
    fun rememberManagedProfile(uuid: String, source: String? = null) {
        store.managedProfileUuid = uuid
        val src = source?.takeIf { it.isNotBlank() }
        if (src != null) {
            store.managedProfileSource = src
        }
        ManagedProfileRefresh.ensure(store.appContext)
    }

    fun rememberSubscription(id: String?) {
        store.subscriptionId = id?.takeIf { it.isNotBlank() }
    }

    fun managedProfileUuid(): String? = store.managedProfileUuid

    fun managedProfileSource(): String? = store.managedProfileSource?.takeIf { it.isNotBlank() }

    fun rememberPendingProfileCleanup(uuid: String) {
        store.rememberPendingProfileCleanup(uuid)
    }

    fun pendingProfileCleanupUuids(): Set<String> = store.pendingProfileCleanupUuids()

    fun clearPendingProfileCleanup(expectedUuid: String) {
        store.clearPendingProfileCleanup(expectedUuid)
    }

    fun rememberedSubscriptionId(): String? = store.subscriptionId

    /**
     * Session + still-link-only binding: post-login subscription step incomplete.
     * See [ManagedBindingSnapshot.needsPostLoginSubscriptionStep].
     */
    fun needsPostLoginSubscriptionStep(): Boolean =
        managedBindingSnapshot().needsPostLoginSubscriptionStep

    /**
     * Local session vs binding classification for logs / e2e (no token values).
     */
    fun consistencyVerdict(): SessionSubscriptionConsistency.Verdict {
        val snapshot = managedBindingSnapshot()
        return SessionSubscriptionConsistency.classify(snapshot)
    }

    /** True when Retry can attempt remote re-provision after local prove-absent. */
    fun canRemoteRepair(): Boolean = managedBindingSnapshot().canRemoteRepair
}

/**
 * Whether a failed refresh proves the backend *rejected* the session, as opposed
 * to failing for any other reason. Only `true` may delete the user's tokens.
 *
 * `401` is the verdict itself. On `400` the discriminator is the documented
 * `invalid_grant` error code — matched exactly, not as a substring: [message] is
 * whatever [RwpGetLineAuthApi.errorMessageOf] could salvage, and for a gateway
 * HTML page or an unrelated error that mentions the token state, a substring hit
 * would sign the user out over a transient failure. Everything else keeps the
 * session so a later retry can still recover it.
 */
internal fun isRejectedRefresh(code: Int, message: String?): Boolean {
    if (code == 401) return true
    if (code != 400) return false
    // Unwrap `{"error":"..."}` with the same reader the API layer uses, so a raw
    // body reaching this point is classified like an already-unwrapped one.
    val discriminator = RwpGetLineAuthApi.errorMessageOf(message.orEmpty()).trim()
    return discriminator.equals("invalid_grant", ignoreCase = true)
}

/** A candidate is importable only when its link passes the environment allowlist. */
private val IMPORTABLE_SUBSCRIPTION: (SubscriptionItem) -> Boolean = {
    GetLineControlPlaneHostPolicy.isAllowedSubscriptionUrl(it.subscriptionLink)
}

/**
 * Nothing survived the allowlist. Report *why* when there was a link to reject:
 * "host not allowed for this environment" is what separates a stage link on a
 * prod build from an account that genuinely has no subscription, and that
 * distinction is the whole content of the failure the user gets to send back.
 */
private fun throwNoImportableSubscription(response: SubscriptionsResponse): Nothing {
    val rejected = response.selectPreferred()
    if (rejected != null) {
        // Has a link but failed the environment allowlist — keep the host reason.
        GetLineControlPlaneHostPolicy.requireSubscriptionUrl(rejected.subscriptionLink)
    }
    throw GetLineAuthException.NoSubscription()
}
