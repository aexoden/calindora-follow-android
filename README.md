# Calindora Follow Android

Calindora Follow Android is an Android application that continuously uploads the
device's current location to a compliant web service. It is currently intended
for narrow, personal use. The author's running service is private, but the
server-side code is open source at [aexoden/com.calindora.follow](https://github.com/aexoden/com.calindora.follow),
so an enterprising user could run their own. However, as a personal project, it
is not particularly polished or user-friendly. (There is no onboarding flow or
admin panel, so adding devices requires direct database access.)

## Features

- Foreground GPS tracking with a live status display
- Persistent queue of location reports with automatic retry and backoff
- HMAC-signed submissions over HTTPS
- Optional NMEA logging feature for debugging or archival
- Material 3 UI with dynamic color on Android 12+.

## Installation

Install the APK on an Android 13 (API 33) or newer device. Open the settings
screen and enter the service URL, device key, and device secret. Return to the
main screen and start the service.

## Building

Debug builds need only the Android SDK and a JDK 21. Release builds additionally
need a signing keystore. Full instructions, including configuration via
`keystore.properties` or environment variables and the source tarball flow, are
in [BUILDING.md](BUILDING.md).

## Architecture

Location data flows through three components, glued together by the Room
database and WorkManager:

- `FollowService` is a bound foreground service that subscribes to
  `LocationManager` and writes an entry to the Room database on each fix while
  tracking is on. It enqueues `SubmissionWorker` as unique work.
- `SubmissionWorker` drains the queue in batches, signs each report with
  HMAC-SHA256 and posts it to the configured service.

## License

MIT. See [LICENSE](LICENSE) for details.
