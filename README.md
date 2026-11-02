# MviCryptoWallet

A sample Android wallet dashboard built with Jetpack Compose. It demonstrates an MVI-style state flow, a biometric unlock demo, encrypted preference storage with a fallback, and an OkHttp token refresh/retry flow. Wallet data and network responses are mocked locally; this app does not connect to a bank or handle real financial data.

## Features

- Starts in a locked state and unlocks through a coroutine-based biometric simulation.
- Displays a sample account balance and transaction list, with a control to hide or reveal the balance.
- Refreshes wallet data through an OkHttp request. The mock API responds with `401` for an expired token; an `Authenticator` synchronously calls a mock refresh endpoint, saves the replacement token, and retries the wallet request.
- Uses `EncryptedSharedPreferences` when available and falls back to standard `SharedPreferences` if encrypted storage cannot be initialized.
- Uses a manual `AppContainer` to construct the repository, OkHttp client, and ViewModel without Hilt.

## Tech stack

| Area | Technology | Use in this project |
| --- | --- | --- |
| Language | Kotlin 2.2.10 | App and Gradle build logic |
| Build | Android Gradle Plugin 9.3.3, Gradle Kotlin DSL | Android app build and dependency configuration |
| UI | Jetpack Compose, Material 3, Compose BOM | Wallet screens and interactive components |
| App entry | AndroidX Activity Compose 1.13.0 | `ComponentActivity` hosting Compose content |
| State and lifecycle | AndroidX Lifecycle 2.9.4, Kotlin Coroutines and Flow | ViewModel, `StateFlow` screen state, `SharedFlow` one-off effects, lifecycle-aware collection |
| Networking | OkHttp 4.12.0 | Auth header interceptor, mock wallet backend, token authenticator, synchronous refresh request |
| Local secrets | AndroidX Security Crypto 1.1.0-alpha06 | Encrypted preferences with a standard preferences fallback |
| Biometrics | AndroidX Biometric 1.1.0 | Biometric library dependency; the current demo uses a one-second simulated success instead of showing a platform prompt |
| Tests | JUnit 4, AndroidX Test, Espresso, Compose UI Test | Test dependencies configured in Gradle |

Versions are declared in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). The app uses `minSdk 24`, `compileSdk 36.1`, and `targetSdk 36`.

## Architecture

The implementation is in [`MainActivity.kt`](app/src/main/java/com/example/mvicryptowallet/MainActivity.kt):

1. `WalletScreen` collects `WalletViewModel.state` and renders the locked, loading, success, or error screen.
2. UI actions dispatch `LaunchAuth`, `RefreshData`, or `ToggleBalanceVisibility` intents to the ViewModel.
3. The ViewModel updates a `StateFlow<UserState>` and emits one-time `ShowToast` effects through a `SharedFlow<UserEffect>`.
4. `WalletRepository` makes a request through the configured OkHttp client. `AuthHeaderInterceptor` adds the stored bearer token, `MockWalletBackendInterceptor` simulates the API, and `TokenAuthenticator` performs the mock refresh and retry after a 401 response.
5. `AppContainer` manually wires storage, networking, the repository, and the ViewModel factory.

## Build and run

Requirements: Android Studio or a local Android SDK with API 36 installed, and a Java version supported by the configured Android Gradle Plugin.

Build a debug APK:

```bash
./gradlew :app:assembleDebug
```

Install and launch on a connected device or emulator (with Android Debug Bridge available on `PATH`):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.mvicryptowallet/.MainActivity
```

The launcher package is `com.example.mvicryptowallet`.

## Demo and security notes

This repository is an educational UI and flow demonstration. Biometric verification always succeeds after a delay, transaction and balance values are hardcoded, and the “API” is implemented by OkHttp interceptors with a mock refresh endpoint. The token and encrypted preference handling demonstrate integration points; they do not make this mock app suitable for real accounts or production financial use. Replace the biometric simulation, mock backend, sample data, and demo token lifecycle with a real identity provider and backend before using the app with sensitive data.
