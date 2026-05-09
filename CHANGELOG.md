# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - 2026-05-09

### Added

- Additional test coverage.
- Display unit preferences for distance and speed.

### Changed

- Derive versionName and versionCode from git.
- Enable Gradle configuration cache
- Register notification channels eagerly.
- Refactoring to improve testability and maintainability.
- Reset backoff on validated connectivity recovery.

### Fixed

- Give notification channels distinct labels.
- Handle DataStore IOException at startup.
- Log several potential errors in SettingsRepository.
- Log NMEA logging failures.
- Refresh channel labels on locale change.
- Support versioning when building from a source tarball.
- Tolerate IOException in long-lived preference flows.
- Use two decimal places for altitude display.

## [0.1.0] - 2026-05-07

Initial tagged release of Calindora Follow for Android.

### Added

- Initial Android release of Calindora Follow for continuously sharing device
  location with a compatible service.
- Foreground GPS tracking with live status details, including current location
  data, last submission time, queue size, and sync state.
- Secure connection setup with HTTPS URL validation, device key configuration,
  and encrypted storage for the device secret.
- Reliable queued background submissions with automatic retry behavior when
  connectivity or service issues occur.
- Recovery tools for authentication and submission failures, including warning
  notifications plus retry, export, and delete actions for failed reports.

[0.1.0]: https://github.com/aexoden/calindora-follow-android/releases/tag/v0.1.0

[0.2.0]: https://github.com/aexoden/calindora-follow-android/releases/tag/v0.2.0
