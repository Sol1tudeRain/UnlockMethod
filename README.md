# UnlockMethod: AOD + lockscreen notification fingerprint (v0.2, experimental)

A one-tap notification on the pulsing Always On Display (AOD), or the regular lockscreen, should request **the ROM's own fingerprint alternate bouncer** where eligible instead of immediately forcing the pattern/PIN/password bouncer.

## What changed in v0.2

- Recognizes notification taps on AOD/doze, including the wakeup-deferred pending action path.
- Extends the tracked tap window to 8 seconds for an AOD tap (2.5 seconds on a normal lockscreen).
- Replaces v0.1's blind bouncer suppression with a conditional request for **AlternateBouncerInteractor.forceShow**, after **canShowAlternateBouncerForFingerprint** approves and the display is no longer dozing.
- Calls the ROM's own `updateAlternateBouncerShowing` to update the SystemUI state.
- Keeps the actual notification launch/OnDismissAction inside SystemUI. We do not send PendingIntent, bypass the lockscreen, simulate biometric success, or relax StrongAuth.
- For unsupported ROM versions, or when fingerprints are unavailable/locked out/StrongAuth is required, leave the original credential UI intact.

## Install

1. Download `app-debug.apk` from the newest successful **Actions > Build LSPosed APK** run's `UnlockMethod-test-APK` artifact.
2. Install APK; enable LSPosed module and select **System UI (com.android.systemui)** as scope.
3. Restart the phone and test both paths:
   - Normal lockscreen notification -> one tap -> fingerprint -> corresponding app.
   - Screen off, notification pulses AOD -> one tap -> device wakes -> fingerprint -> corresponding app.
4. Repeat after reboot before the *first* pattern unlock, and after fingerprint lockout: system should insist on credential input.

### Updating from v0.1

GitHub Actions makes a different temporary debug signing key on fresh runners, so updating the previously installed APK may produce `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall the old module APK, install the new APK, re-enable it in LSPosed, then reboot. The **repository is public**, and does not contain any private signing keystore.

## Diagnostics

LSPosed logs: filter for `NotifFingerprint:`. Expected diagnostics include
`notification tapped on AOD/pulse`, `AOD wakeup`, and
`used UDFPS alternate bouncer instead of pattern`. If the hook reports
`... unavailable`, the system class/method is different.

### Known limitations

**Not yet verified on the user's exact PixelOS / Android build.** SceneContainer/Compose SystemUI variants may use a different path or not provide the legacy manager fields. AOD's low-power display may not accept touch in exactly the same way on all devices. This module intentionally fails closed and does not suppress the credential screen if the platform's alternate bouncer is ineligible.

If notification navigation breaks or SystemUI crashes, disable module in LSPosed and restart. For the next iteration attach the logs and `dumpsys activity service com.android.systemui/.SystemUIService` if available.
