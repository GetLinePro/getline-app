# Engineering decisions

Decisions that get re-proposed. Read the relevant entry before reopening one.

Evidence for some entries lives under `docs/internal/`, which is git-ignored and
exists only in a local checkout — the decision itself stays here so it survives a
fresh clone.

---

## No `QUERY_ALL_PACKAGES`

**Decided:** 2026-08-08. **Status:** in effect.

The app does not hold `android.permission.QUERY_ALL_PACKAGES`. Package visibility
comes from the `<queries>` block in `app/src/main/AndroidManifest.xml`:
`MAIN+LAUNCHER`, `MAIN+LEANBACK_LAUNCHER`, and `VIEW https` (browsers).

The permission is still written in that manifest with `tools:node="remove"`. That is
deliberate, not leftover: the marker removes the declaration `:service` used to
contribute and blocks any future module or library from merging it back in. Deleting
the block would silently re-enable the permission if a dependency ever declares it.

### Why

Google restricts the permission and requires a declaration form arguing it is core
functionality. Two features were expected to need it — per-app routing, and applying
a subscription-delivered list of packages that must bypass the tunnel. Measurement
showed neither does.

### Measured coverage

Physical device, Xiaomi 24069PC21G (peridot), Android 14 / API 34, build without the
permission:

| Metric | Value |
| --- | --- |
| Third-party installed (`pm list packages -3`) | 229 |
| Third-party visible to the app | 221 (96.5%) |
| Packages in the shipped `ru-apps` rule-set installed on the device | 43 |
| …of those, visible | 43 (100%) |

The 8 invisible packages were `cn.wps.xiaomi.abroad.lite`,
`com.google.android.contactkeys` / `.ims` / `.safetycore`,
`com.miui.android.fashiongallery`, `com.miui.mediaeditor`, `com.preff.kb.xm`,
`com.yandex.preinstallsatellite`.

Every one of them lacks a resolvable launcher activity — checked individually, e.g.
`cmd package resolve-activity -a MAIN -c LAUNCHER cn.wps.xiaomi.abroad.lite` returns
`No activity found`. There is no case of a launcher-visible package being hidden, so
the mechanism has no holes; the invisible set is IMEs, background services and
preinstall stubs, none of which a user routes.

Visibility is per-UID, not per-process: the service process sees the same set. Its
`AppListCacheModule` reported 441 uid groups against 511 visible packages, and the
delta is `sharedUserId` collapsing (103 packages share 23 shared users on that
device), not lost visibility.

Browser auth was verified end-to-end on the same build — Google and Telegram sign-in
both completed. `BrowserAuthLauncher` resolves browsers through `CustomTabsClient`
and `resolveActivity`, which previously worked only because `QUERY_ALL_PACKAGES` was
present; the `VIEW https` entry is what keeps it working, and androidx.browser does
not contribute a `<queries>` block of its own.

### Known limitation

`PROCESS-NAME` rules depend on the uid→name map built from visible packages. If a
package with no launcher activity is ever added to a process rule-set, it stays
invisible, the rule silently does not match, and the traffic falls through to
`MATCH,PROXY` — through the tunnel, which is the opposite of what such a rule is for.
The failure produces no error.

This is accepted rather than guarded: the rule-sets currently in use contain consumer
apps, all of which have launcher activities, and a detector would cost more than the
failure at the current install base. Tracked in `docs/internal/security-register.md`.

### Do not

- Add the permission back to make a package list "just work" — measure first.
- Add an `android.view.InputMethod` query speculatively. It would make keyboards
  visible, but nothing needs that today.

---

## Release FGS is `TunService` / `systemExempted`, not `specialUse`

**Decided:** 2026-08-17. **Status:** in effect. Issue #39.

Release does not declare `specialUse`, `FOREGROUND_SERVICE_SPECIAL_USE`, or
`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. `TunService` is the only GetLine-owned
service with a `foregroundServiceType`, and that type is `systemExempted`.
Debug `LogcatService` is declared only in `app/src/debug/AndroidManifest.xml`
with `specialUse` and `FOREGROUND_SERVICE_SPECIAL_USE`. It never enters a
release merge.

`startForegroundCompat()` takes the runtime type as a parameter. A global
constant would declare debug `LogcatService` as `systemExempted` against its
manifest and crash the start on API 34+.

`androidx.work` `SystemForegroundService` without a type is library
infrastructure. `ProfileRefreshWorker` does not call `setForeground()` /
`setForegroundAsync()`. Do not set `enable_system_foreground_service_default`
to false.

### Do not

- Write `specialUse` justifications for Play. That posing was cancelled.
- Swap the helper's constant globally instead of passing the type.
- Treat WorkManager's untyped `SystemForegroundService` as a GetLine FGS.

---

## The service notification does not set `ONGOING` itself

**Decided:** 2026-08-18. **Status:** in effect. Issue #154.

The status notification is posted through `startForeground()`. The system owns
its lifetime from there: it adds `FLAG_FOREGROUND_SERVICE` (`0x40`) and, on the
devices measured, `FLAG_NO_CLEAR` (`0x20`), and it drops both when the service
is gone. Our own `setOngoing(true)` adds nothing while the service lives, and
outlives it when the process is killed from outside.

Measured on HyperOS (Android 14, API 34), `alphaProdRelease`, dynamic
notification, before and after removing `setOngoing(true)` from
`StaticNotificationModule` (two call sites) and `DynamicNotificationModule`:

| | live VPN | after HyperOS `OneKeyClean` |
|---|---|---|
| with `setOngoing(true)` | `flags=0x6a` / `originalFlags=0x4a` | record survives with `flags=0x0a`, shows a dead tunnel as protected |
| without it | `flags=0x68` / `originalFlags=0x48` | record gone (verified twice) |

So the system does **not** put `FLAG_ONGOING_EVENT` back on its own, and with no
app-set flag left, nothing holds the record once the system flags are dropped.

The notification was already swipe-dismissible while the VPN ran (API 34 allows
that, and `NO_CLEAR` did not prevent it here); the dynamic module reposts it
within a second. The one behaviour change while running is that "Clear all" now
removes it too — measured, and the dynamic module brought it back within a
second. `StaticNotificationModule` reposts only on
`ACTION_PROFILE_LOADED`, so there a dismissal lasts until the tunnel restarts —
accepted, because release strips `SettingsActivity` / `AppSettingsActivity`
(GL-22 / #76), leaving the `dynamic_notification` switch unreachable and the
default (`true`) in force.

### Do not

- Restore `setOngoing(true)` as "obviously correct" when merging from CMFA.
- Add a start-time `NotificationManager.cancel(R.id.nf_clash_status)` sweep for
  an orphaned record; measurement shows there is no orphan left to clean.
- Try to survive `OneKeyClean` by keeping the process alive.

---

## Split tunnelling warns about lockdown, it does not detect it

**Status:** in effect from 2026-08-13. Alpha scope, revisit if it bites.

Excluding an app from the tunnel sends it to the ordinary system network. If the
user has Android's "block connections without VPN" on, that app gets no network
instead. Issue #21 asks the client to detect the conflict and preferably refuse
the two selective modes while lockdown is active.

Not built. `VpnService.isLockdownEnabled()` is API 29+ **and an instance method on
the running service**, so the UI process cannot ask: it would take a new method
across `IClashManager`/`IRemoteService` plus a state for "VPN is down, so the
answer is unknown". And while the tunnel is down the answer genuinely does not
exist, which is exactly when the mode is chosen — the preferred behaviour is not
reachable, only a post-hoc one.

What ships instead is the minimum the issue also allows: a warning under the mode
row, shown in the two selective modes only, phrased as a condition ("if Android is
set to block connections without VPN…"). The app claims no knowledge it does not
have.

### Do not

- Word the warning as if the state had been detected.
- Add the IPC for this alone. If a lockdown query lands for another reason, wiring
  this to it is cheap; building it for a warning is not.

---

## No detekt or equivalent Kotlin complexity gate

**Status:** in effect.

Measured against this repo's own review history and deliberately not integrated.
Evidence: `docs/internal/detekt-experiment/` (report and the command used).
Read it before reopening.

---

## An Activity connects, it does not decide

**Status:** in effect from 2026-08-12. Direction for new code.

`GetLineHomeActivity` and `GetLineOnboardingActivity` are the orchestrators and the
only place wired to every dependency, so new work lands there by default. Measured
line counts:

| date | `GetLineHomeActivity` | `GetLineOnboardingActivity` | `GetLineHomeDesign` |
|---|---:|---:|---:|
| 2026-07-28 | 1358 | 809 | — |
| 2026-08-04 | 1841 | 1515 | 1211 |
| 2026-08-12 | 1899 | 1712 | 1303 |
| 2026-08-13, after #132–#134 | 1621 | 1712 | 1303 |

Both Activities sit near 0% unit coverage (`docs/coverage-audit-2026-08-12.md`).
After #132–#134 the three extracted flows together have 85% line and 65% branch
coverage; the report records the per-flow spread. Growth is the problem, not the
current size: the first extraction removed ~250 lines while the same files gained
~1500 in two weeks.

So new behaviour goes into an existing flow/policy/coordinator, or a new class
beside them. What legitimately stays in an Activity: lifecycle, permission and
`ActivityResult` plumbing, and wiring a design to a flow.

When a flow needs something only the Activity can do, express it as a narrow port
(`BrowserAuthFlow.Host`) instead of moving the logic back. A port past ~15 members
means the boundary was drawn wrong.

Existing bulk is reduced by agreed slices, one at a time, each with tests — not
opportunistically while doing something else.

**Rejected:** a CI line-count ratchet on those files. It freezes size without
improving structure, and the escape hatch (raising the baseline) is the same
action as the failure it prevents.

---

## Profile contract is versioned by GetLine User-Agent

**Decided:** 2026-08-11, restated 2026-08-13. **Status:** in effect.

First import and refresh send `User-Agent: GetLineVPN/<versionName>`. The panel
uses that token to pick a compatible Mihomo template. The token is format
selection, not authentication.

Missing or unexpected `x-getline-profile` / `x-getline-schema` is a log line, not
a reason to reject the update. Only unparseable YAML, a transport failure, or a
core apply failure keep the last committed profile.

New capabilities roll out backend first: a new install has nothing to fall back
to. Contract: `docs/subscription-profile-contract.md`.

### Do not

- Fail closed on a missing GetLine marker.
- Bump the schema and serve it to clients that cannot apply the new proxy type.

---

## TUN policy is reconciled in-process, not by restarting FGS

**Decided:** 2026-08-25. **Status:** in effect.

A running `TunService` applies the latest desired `AccessControlPlan` by
rebuilding the Android VPN interface and attaching it to native. The foreground
service is not stopped and started. `AccessControlActivity` writes the store and
sends a self-broadcast; it does not wait for `clashRunning`.

Requests are service-owned and conflated. The receiver is registered before the
initial reconcile request. The request carries no plan snapshot — apply reads
`ServiceStore` at execution time. An equal plan is a no-op. A later request
after a fatal apply is not executed.

The HTTP proxy listener is created once per `TunService` lifetime. Every rebuilt
`VpnService.Builder` reuses that address.

`Builder.establish()` stays a `ParcelFileDescriptor` until the native handoff.
Builder parameters are computed first; `detachFd()` happens inside
`TunModule.attach` as the argument to `Clash.startTun`. Native consumes that fd
on every return path: parse errors before `sing_tun.New` close it in
`native/tun`; `sing_tun.New` closes it on error before `tunNew` (product patch
`0004-close-tun-fd-before-tunnew.patch`); after `tunNew`, `Listener.Close` owns
it. `startTun` returns success/failure through JNI; Kotlin treats failure as an
exception.

`establish()` is already destructive. There is no rollback to the previous native
TUN. A failed handoff records the reason and takes the existing
`finally` / `stopSelf` path.

### Do not

- Restart `TunService` to apply app-routing changes.
- Call `listenHttp()` again inside a rebuild.
- Promise that the old VPN survives after `establish()`.
- Put reconcile events on `Module`'s unlimited queue.
