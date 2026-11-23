# UnlockMethod — AOD / lockscreen notification fingerprint (v0.2.2 test)

LSPosed module for SystemUI: when the user taps a notification on AOD/pulsing display or the regular lock screen, request the ROM's genuine UDFPS alternate bouncer when possible. The notification's real pending action remains owned by SystemUI.

**Status:** Experimental; not proven compatible with all PixelOS / Android 17 builds. Requires the ROM's existing AlternateBouncerInteractor to be present. No simulated biometric success, no StrongAuth bypass.

## Recommended scope (new)

This **legacy Xposed** module now declares `xposedscope=com.android.systemui` in the Android manifest. On compatible LSPosed versions, **System UI (com.android.systemui)** should be preselected as the recommended scope when enabling the module. If already installed, check the scope after updating.

## Stable signing (new)

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
