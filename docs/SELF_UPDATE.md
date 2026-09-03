# ClipForge self-update

ClipForge follows the XFiles update model.

- The app checks the GitHub `debug-latest` Release at startup, at most once every 24 hours.
- The update button can run a manual check at any time.
- A newer APK is downloaded into app cache, checked for package name and versionCode, then installed with Android `PackageInstaller`.
- Android's unknown-app-source permission is requested only when installation is needed.
- After a successful update, ClipForge is launched again.

## Stable signing is required

Android only accepts an in-place update when the new APK is signed by the same key as the installed APK. Configure these repository secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

`.github/workflows/signed-debug-release.yml` uses those secrets to build the stable-signed APK and replaces the rolling `debug-latest` Release on every `main` update.

If the secrets are not configured, normal CI still succeeds, but the self-update Release step is skipped.
