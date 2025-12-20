# Changelog

## Unreleased

### Added

### Changed

### Deprecated

### Removed

### Fixed

### Security

## 0.5.4 - 2025-12-20

### Fixed

- setup task in multimodule project [#59](https://github.com/ghostflyby/SpotlessDaemon/pull/59)

## 0.5.3 - 2025-12-19

### Changed

- downgrade ktor to 3.2.0 for Gradle 8.* compatibility

## 0.5.2 - 2025-12-19

### Changed

- refactor: sorted formatter to use subproject configs first [#53](https://github.com/ghostflyby/SpotlessDaemon/pull/53)

## 0.5.1 - 2025-12-19

### Fixed

- init formatters with dependencies on task thread [#51](https://github.com/ghostflyby/SpotlessDaemon/pull/51)

## 0.5.0 - 2025-12-18

### Changed

- use WorkerAction to run formatters [#47](https://github.com/ghostflyby/SpotlessDaemon/pull/47)
- shadow ktor and utilize simple shared state based
  concurrency [#49](https://github.com/ghostflyby/SpotlessDaemon/pull/49)

## 0.4.0 - 2025-12-12

### Added

- add encoding endpoint with error handling and response
  types [#44](https://github.com/ghostflyby/SpotlessDaemon/pull/44)

## 0.3.0 - 2025-12-11

### Changed

- only register task in rootProject [#41](https://github.com/ghostflyby/SpotlessDaemon/pull/41)

## 0.2.7 - 2025-12-02

### Changed

- remove unused properties from SpotlessDaemon [#39](https://github.com/ghostflyby/SpotlessDaemon/pull/39)

## 0.2.6 - 2025-12-02

### Changed

- grab spotless config in TaskAction [#37](https://github.com/ghostflyby/SpotlessDaemon/pull/37)

## 0.2.5 - 2025-12-02

### Changed

- evaluate the plugin after project configuration [#35](https://github.com/ghostflyby/SpotlessDaemon/pull/35)

## 0.2.4 - 2025-12-01

### Changed

- remove targets pre-check [#33](https://github.com/ghostflyby/SpotlessDaemon/pull/33)

## 0.2.3 - 2025-12-01

### Changed

- change main logging level to info [#31](https://github.com/ghostflyby/SpotlessDaemon/pull/31)

## 0.2.2 - 2025-11-30

### Added

- add logging for debugging purposes [#29](https://github.com/ghostflyby/SpotlessDaemon/pull/29)

## 0.2.1 - 2025-11-25

### Added

- stop endpoint to gracefully shut down the Spotless Daemon [#21](https://github.com/ghostflyby/SpotlessDaemon/pull/21)
- `dryrun` parameter to check if a file is covered by Spotless without formatting

## 0.0.1 - 2025-11-05

### Added

- documenting usage in README.md [#12](https://github.com/ghostflyby/SpotlessDaemon/pull/12)
