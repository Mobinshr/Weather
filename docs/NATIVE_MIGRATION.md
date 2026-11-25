# Qandil native migration

A clean Android application project is introduced in `native-android/` using Kotlin and Jetpack Compose. The former HTML/CSS/TypeScript + WebView implementation remains intact on this branch as a feature reference and rollback path.

## Native milestone 1
- Native Compose weather dashboard.
- Open-Meteo current conditions, hourly and seven-day forecasts, air-quality indicator and global city search.
- Persian/English city search, GPS via Android LocationManager, and persistent selected city.
- Local cache with a 15-minute freshness window, 24-hour expiry and a 12-city limit.
- Native Android rain notifications while the app is active.

## Not yet migrated
- Scheduled/background rain alerts, full multilingual UI and the full set of original chart/translation details remain follow-up work.
- The current package ID is set to `com.example.weather`, matching the Kotlin source package used by the existing Android project; verify install-over behavior against the current release before replacing it.
- Preview builds are signed with the existing release keystore and uploaded as artifacts only. No release is published from this branch.
