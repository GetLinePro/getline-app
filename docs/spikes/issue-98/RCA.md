# #98 fail capture 2026-08-06 ~18:17

## Smoking gun
```
ActivityManager: Unable to launch app pro.getline.vpn.alpha/10562 for service
Intent { cmp=.../RemoteService }: process is bad
ProcessStarter: proc frequent died! proc = pro.getline.vpn.alpha:background
```
No `:background` Start proc after this. Main only. bind → destroyService immediately.

## Cause (not a product crash)

`:background` did **not** die from an app crash/native abort in this capture.
MIUI killed it repeatedly during **dev reinstall thrash** and recents swipe:

- `SwipeUpClean` (user/system recents)
- `stop … due to installPackageLI` / `deletePackageX` (reinstall churn)

Then `ProcessStarter: proc frequent died!` blacklists the process name; the next
bind fails with `process is bad`. Quarantine is temporary (time / reboot / cool-down).
Swipe-clean is a real user action on MIUI, so the product still needs routing +
diagnostics; the 5× reinstall loop (uid 10555→10562 in ~45 min) was the amplifier
in this experiment, not a latent RemoteService crash.

## Open: does MIUI return `bindService == false`?

Product fail-fast assumes quarantine surfaces as `bindService` → **false**
(`kind=bind_rejected` in app log). The capture also has
`ConnectionRecord{… CR DEAD …}`, which is compatible with both false and
true-then-never-connect. If MIUI returns **true** and never starts `:background`,
`wasBindRejected` stays false and the old ~8s `callProfileBackend` timeout remains.

Next device capture: look for `profile_backend op=bind … kind=bind_rejected` on
the fail path. That line is the proof; without it, treat fail-fast as partial.

## App routing consequence (~8s later = PROFILE_OPERATION_TIMEOUT at capture time)
```
startup_route dest=home reason=backend_unavailable
store=ok session=0 managed=0 pending_import=0 imported=na backend=unavailable
```

## Side effect
```
Unknown authority pro.getline.vpn.alpha.status
```
(status ContentProvider lives in `:background`)

## Process state when captured
- main: `pro.getline.vpn.alpha` (32630) running
- `:background`: absent
- ConnectionRecord CR DEAD for RemoteService

## Package
0.5.0.Alpha (2016), firstInstall 18:16:53, uid 10562
