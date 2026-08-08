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

## No detekt or equivalent Kotlin complexity gate

**Status:** in effect.

Measured against this repo's own review history and deliberately not integrated.
Evidence: `docs/internal/detekt-experiment/` (report and the command used).
Read it before reopening.
