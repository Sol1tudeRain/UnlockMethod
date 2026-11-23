# UnlockMethod — AOD / lockscreen notification fingerprint (v0.4 direct-click test)

LSPosed module for SystemUI: when the user taps a notification on AOD/pulsing display or the regular lock screen, request the ROM's genuine UDFPS alternate bouncer when possible. The notification's real pending action remains owned by SystemUI.

**Status:** Experimental; not proven compatible with all PixelOS / Android 17 builds. Requires the ROM's existing AlternateBouncerInteractor to be present. No simulated biometric success, no StrongAuth bypass.

## Recommended scope

This **legacy Xposed** module now declares `xposedscope=com.android.systemui` in the Android manifest. On compatible LSPosed versions, **System UI (com.android.systemui)** should be preselected as the recommended scope when enabling the module. If already installed, check the scope after updating.

## Stable signing

The repo is **PUBLIC**. Do **not** add a keystore, password or base64 key to GitHub source code, Issues, pull requests, Actions logs or public artifacts.

APK updates are only accepted by Android if the certificate matches. Earlier v0.1–v0.2.1 releases were signed with throwaway GitHub Runner debug keys, which were not retained. They **cannot be upgraded in place with the new key**. Uninstall the old module APK once, install the new fixed-signature `app-release.apk`, reenable it in LSPosed, and restart.

From v0.2.2 onward, all release builds use the same keystore stored in **GitHub Actions Secrets**, so later release APKs with this keystore can update in place. The first successful signed build requires a one-time secret setup using the **PRIVATE signing bundle provided separately to the owner**.

Set these four GitHub repository secrets at:
[Actions secrets](https://github.com/Sol1tudeRain/UnlockMethod/settings/secrets/actions)

| GitHub Action secret | Value |
|---|---|
| `UNLOCK_SIGNING_KEYSTORE_B64` | Entire text of `keystore-base64.txt` from the private bundle |
| `UNLOCK_SIGNING_STORE_PASSWORD` | Entire text of `keystore-password.txt` from the private bundle |
| `UNLOCK_SIGNING_KEY_ALIAS` | `unlockmethod` |
| `UNLOCK_SIGNING_KEY_PASSWORD` | Same as `UNLOCK_SIGNING_STORE_PASSWORD` |

Alternatively use `gh auth login`, then the bundle's `setup-github-secrets.sh` script on Linux/macOS/Termux/WSL. Keep the `.p12` and password safely backed up; GitHub Secrets are write-only and cannot serve as a keystore backup.

After configuring secrets, go to [Actions](https://github.com/Sol1tudeRain/UnlockMethod/actions/workflows/build.yml), choose **Run workflow**, download `UnlockMethod-stable-signed-APK`, install `app-release.apk`.

CI validates secrets before producing an APK; it does not quietly fall back to an unstable debug signature. Use `gradle :app:assembleDebug` only to test compilation locally.

## Installation & tests

1. Install the stable APK, enable in LSPosed, and make sure recommended scope **System UI** is checked.
2. Reboot.
3. Lock screen and tap a notification -> fingerprint unlock -> notification app.
4. With screen off, receive notification to pulse AOD and tap **once** -> real UDFPS unlock -> app.
5. Following a reboot, **before first pattern unlock**, the system must still require your pattern. Same for fingerprint lockout.

If notifications do not open, SystemUI crashes, or the notification still asks for pattern, disable the module and reboot. Search LSPosed log for `NotifFingerprint:` and attach matching lines for diagnosis.

**Certificate SHA-256 for the new signing key:** `d5c80d09dbb77948e3462d55155af87161f2004b97640e6f8cccf5e30fdea0a4`.

## v0.3 AOD diagnostics and changes

v0.3 adds explicit logging of the AOD->keyguard dismissal path and tries both SystemUI versions of the real fingerprint alternate bouncer: modern `forceShow()` and legacy `show()`. It also observes the new `SceneInteractor.changeScene(Scenes.Bouncer,...)` path used by some SceneContainer ROMs. **The platform's own fingerprint eligibility gate must explicitly return true**: if the device requires a pattern after boot/lockout, it will still show the pattern.

Test by waking the device from a notification pulse in AOD, **tapping the notification once**, and looking for `NotifFingerprint` in LSPosed logs or rooted `logcat`. An important diagnostic pattern is:
- `CLICK notification on AOD/pulse`: click captured
- `PATH dismissWithAction`: pending notification action tracked
- `PRIMARY` or `SCENE`: which credential route was requested
- `ALT_SKIPPED`, `ALT_NOT_VISIBLE` or `ALT_ERROR`: why the fingerprint UI could not be shown
- `ALT_SHOWN`: the system fingerprint UI is visible and the pattern route was suppressed

If no `CLICK` line appears, that SystemUI version may use a different click handler. **Still experimental; successful compilation is not a functional test.**

## v0.4 direct notification click capture

On the tested ROM, LSPosed logged `AOD_CLICK_HINT before wake` but neither the notification's `onNotificationClicked` callback nor the bouncer hooks. We now record notification clicks **in the already-confirmed `NotificationClicker.onClick` hook, before waking AOD**, provided the clicked view is a real `ExpandableNotificationRow`. We also hook `StatusBarKeyguardViewManager.showBouncer` (the caller of the primary/interactor path), preserving the notification's existing pending dismiss action. More trace logs are emitted for actual scene transitions.

This is **not a fingerprint bypass**: it calls the ROM's own alternate fingerprint UI only when `canShowAlternateBouncerForFingerprint()` returns true. If the alternative UI does not become visible, the normal pattern remains.

Use `su -c 'grep -ih "NotifFingerprint" /data/adb/lspd/log/modules_*.log | tail -n 80'` to check `CLICK_ENTRY`, `PRIMARY`, `ALT_*`, and `SCENE_REQUEST` markers. If `CLICK_ENTRY` exists without `PRIMARY` or `SCENE_REQUEST`, the ROM uses a separate credential entrypoint.
