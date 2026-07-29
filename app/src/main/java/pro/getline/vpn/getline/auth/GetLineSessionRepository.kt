package pro.getline.vpn.getline.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pro.getline.vpn.GetLineControlPlaneHostPolicy

/**
 * Owns native session lifecycle. Raw tokens stay inside this layer except when
 * a caller must pass a short-lived access token into authenticated API calls.
 */
class GetLineSessionRepository(
    private val api: GetLineAuthApi,
    private val store: GetLineSessionStore,
) {
    private val sessionRefreshMutex = Mutex()

    fun hasSession(): Boolean = store.hasRefreshToken()

    fun logout() {
        store.clearAccountState()
    }

    suspend fun establishFromWebToken(webToken: String): NativeSession {
        // Optional identity probe; failure is non-fatal for handoff.
        val user = runCatching { api.getCurrentUser(webToken) }.getOrNull()
        if (user?.customerId != null) {
            store.customerId = user.customerId
        }

        val deviceKey = api.generateDeviceKey(webToken)
        val session = api.exchangeDeviceKey(deviceKey.value)
        store.saveSession(session)
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
            store.saveSession(session)
            session
        }
    }

    /**
     * Loads preferred subscription for profile import.
     * Does not persist the selected ID so callers can compare against
     * [rememberedSubscriptionId] before deciding profile reuse.
     *
     * [SubscriptionItem.subscriptionLink] must pass
     * [GetLineControlPlaneHostPolicy] so e2e never imports a production URL.
     * Does not restrict manual user-entered import URLs outside this path.
     */
    suspend fun loadPreferredSubscription(): SubscriptionItem {
        val response = getSubscriptionsAuthenticated()
        val preferred = response.selectPreferred()
            ?: throw GetLineAuthException.Protocol("No subscription with import URL")
        GetLineControlPlaneHostPolicy.requireProductHttpsUrl(
            preferred.subscriptionLink,
            "subscription_link",
        )
        return preferred
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
     * Subscription destination load: authenticated GET /api/subscriptions with
     * single 401 recovery, then [SubscriptionsResponse.selectPreferred].
     *
     * Does not create trial or mutate VPN/profile state.
     */
    suspend fun loadSubscriptionForUi(): SubscriptionLoadResult {
        if (!hasSession()) {
            return SubscriptionLoadResult.SignedOut
        }
        return try {
            val response = getSubscriptionsAuthenticated()
            SubscriptionLoadResult.Success(preferred = response.selectPreferred())
        } catch (e: CancellationException) {
            // Lifecycle cancellation must not be mapped to UI failure states.
            throw e
        } catch (e: GetLineAuthException.HttpFailure) {
            // Recovery may already have cleared the store; prefer SignedOut then.
            // Only 401 means "session invalid" after recovery. 403 is not treated as
            // global session death (may be permission/policy for this endpoint).
            if (!hasSession()) {
                SubscriptionLoadResult.SignedOut
            } else if (e.code == 401) {
                logout()
                SubscriptionLoadResult.SignedOut
            } else {
                SubscriptionLoadResult.TransientFailure
            }
        } catch (_: GetLineAuthException) {
            // e.g. Protocol/malformed refresh after 401 recovery logged the user out.
            if (!hasSession()) {
                SubscriptionLoadResult.SignedOut
            } else {
                SubscriptionLoadResult.TransientFailure
            }
        } catch (_: Exception) {
            if (!hasSession()) {
                SubscriptionLoadResult.SignedOut
            } else {
                SubscriptionLoadResult.TransientFailure
            }
        }
    }

    /**
     * GET /api/subscriptions with Bearer token.
     * On 401: force session refresh once, then retry the request exactly once.
     * Concurrent 401 recoveries share [sessionRefreshMutex].
     */
    suspend fun getSubscriptionsAuthenticated(): SubscriptionsResponse {
        val accessToken = validAccessToken()
        return try {
            api.getSubscriptions(accessToken)
        } catch (first: GetLineAuthException.HttpFailure) {
            if (first.code != 401) throw first
            val recovered = try {
                forceRefreshSession()
            } catch (cancelled: CancellationException) {
                // Activity destroy/recreate mid-refresh — keep persisted session.
                throw cancelled
            } catch (refreshError: Exception) {
                logout()
                throw refreshError
            }
            try {
                api.getSubscriptions(recovered.accessToken)
            } catch (second: GetLineAuthException.HttpFailure) {
                // Second 401 after a forced refresh → session is dead.
                // 403 is not session-invalidation; keep tokens and surface as error.
                if (second.code == 401) {
                    logout()
                }
                throw second
            }
        }
    }

    /** Refresh even if access token looks valid (401 recovery). */
    private suspend fun forceRefreshSession(): NativeSession {
        return sessionRefreshMutex.withLock {
            val refreshToken = store.refreshToken
                ?: throw GetLineAuthException.Protocol("Missing refresh token")
            val session = api.refresh(refreshToken)
            store.saveSession(session)
            session
        }
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
    }

    fun rememberSubscription(id: String?) {
        store.subscriptionId = id?.takeIf { it.isNotBlank() }
    }

    fun managedProfileUuid(): String? = store.managedProfileUuid

    fun managedProfileSource(): String? = store.managedProfileSource?.takeIf { it.isNotBlank() }

    fun rememberedSubscriptionId(): String? = store.subscriptionId

    /** True when Retry can attempt remote re-provision after local prove-absent. */
    fun canRemoteRepair(): Boolean = hasSession() || managedProfileSource() != null
}
