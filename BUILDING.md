# Building Calindora Follow

## Debug Builds

```bash
./gradlew assembleDebug
```

No setup is required. Debug APKs are signed with the auto-generated debug
keystore at `~/.android/debug.keystore` and use the `.debug` application ID
suffix, so they coexist with release installs.

## Release Builds

Release APKs must be signed with a stable key so updates can install over
existing copies without uninstalling first.

### One-time keystore setup

Generate a keystore. Pick a passphrase you'll remember (or store it in a
password manager) and a long validity. If the certificate expires, every user
has to uninstall and reinstall.

```bash
keytool -genkeypair -v \
  -keystore follow-release.p12 \
  -storetype PKCS12 \
  -alias follow-release \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Store the resulting `follow-release.p12` file somewhere outside the repository
and back it up. Losing this file means losing the ability to update existing
installations.

### Configuring the build

Create `keystore.properties` at the repository root:

```properties
storeFile=/absolute/path/to/follow-release.p12
storePassword=...
keyAlias=follow-release
keyPassword=...
```

Or, equivalent, set the following environment variables:

```bash
FOLLOW_KEYSTORE_PATH=/absolute/path/to/follow-release.p12
FOLLOW_KEYSTORE_PASSWORD=...
FOLLOW_KEY_ALIAS=follow-release
FOLLOW_KEY_PASSWORD=...
```

The properties file takes precedence when both are present.

### Building

```bash
./gradlew assembleRelease
```

The signed APK is written to `app/build/outputs/apk/release/app-release.apk`.

If signing material is missing, the build still produces
`app-release-unsigned.apk`, which can be signed manually with `apksigner`.
