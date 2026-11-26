# OnlyHeat

An unofficial client for **TTO Thermotechnik "TTO Advance"** underfloor heating controllers, built with Kotlin
Multiplatform and Compose Multiplatform for Android, iOS and macOS/desktop.

It talks to the controller two ways: through the vendor cloud (`api.smart-heating.co`), or directly on the home
network via the controller's local dashboard, so it keeps working when the cloud is slow or down.

## Features

- Rooms at a glance with current and target temperatures, outside weather, and animated room states
- Set temperatures, plus Away / I'm back for the whole home
- History charts with outside temperature
- Local access: finds the controller on your Wi-Fi and logs in with its API PIN
- Background alerts for rooms that heat slowly, overheat, or have heating and AC running at the same time
- Gree / Sinclair air conditioner control on the home network, including setting up the Wi-Fi of a reset unit
- Home-screen widget (Android), notices when the controller's firmware updates
- English and Croatian

## Building

Requires JDK 17+ and Android Studio (or just Gradle for Android and desktop).

| Target  | Command |
|---------|---------|
| Android | `./gradlew :app:installDebug` (minSdk 30) |
| Desktop | `./gradlew :shared:run`, or `./gradlew :shared:packageDmg` for a macOS `.dmg` |
| iOS     | `cd iosApp && xcodegen && open OnlyHeat.xcodeproj`, then run from Xcode (iOS 16+, needs [XcodeGen](https://github.com/yonaskolb/XcodeGen)) |
| Tests   | `./gradlew :shared:desktopTest` |

## Releasing

Push a tag (`git tag v1.2.0 && git push origin v1.2.0`). [`release.yml`](.github/workflows/release.yml) builds a
signed APK and a `.dmg` and publishes a GitHub release. Release builds check for it on launch (Android also in the
background worker) and offer to install it; Android 12+ updates without asking after the first self-install.
Signing needs the `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` secrets; locally, `onlyheat.keystore` and
`onlyheat.keystorePassword` in `~/.gradle/gradle.properties`.

## Project layout

- `shared/` – all app code and UI (`commonMain`), with platform bits in `androidMain`, `iosMain`, `desktopMain`
  - `data/` – cloud and local-dashboard API clients, controller discovery
  - `ac/` – Gree / Sinclair AC protocol
  - `feature/`, `ui/` – screens and theme
- `app/` – Android application shell
- `iosApp/` – iOS host app (XcodeGen `project.yml`)
- `docs/` – write-ups of the reverse-engineered APIs, one file per firmware / app version:
  - [`dashboard-api-1.0.7.0.8.md`](docs/dashboard-api-1.0.7.0.8.md), [`dashboard-api-1.0.9.2.0.md`](docs/dashboard-api-1.0.9.2.0.md) – the controller's local web dashboard
  - [`cloud-api-1.0.3.md`](docs/cloud-api-1.0.3.md) – the cloud API used by the official TTO Advance app 1.0.3

The raw captures the docs refer to (`docs/*-snapshot-*/`) are vendor code and are kept out of the repository.

## Disclaimer

OnlyHeat is a personal project and is not affiliated with or endorsed by TTO Thermotechnik, Gree or Sinclair.
The APIs it uses are undocumented and can change with any firmware or app update. Use at your own risk.

## License

[MIT](LICENSE)
