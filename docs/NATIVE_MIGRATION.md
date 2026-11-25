# Qandil Native migration

## Native classic-layout restoration — v3.6.1
The Android UI remains fully native (Kotlin + Jetpack Compose). The previous Qandil HTML screen was used as the visual and feature reference; its layout and weather details have been rebuilt using native components.

Restored sections:
- Compact search toolbar with online state, GPS, rain-alert toggle and refresh.
- Dynamic day/night and weather-condition sky gradients.
- Centered city/temperature hero.
- 24-hour temperature and precipitation charts.
- 24-hour hourly forecast and seven-day temperature-range rows.
- UV, sunset/sunrise, wind, feels-like, humidity, daily precipitation, air quality and pressure cards.
- Rain banner and native notifications, update countdown, cache/offline status.

The native data layer now retrieves and caches `is_day`, current local time, sunrise, sunset, daily UV and daily precipitation probability. A new cache namespace prevents 3.6.0 snapshots from masking the restored fields.

The HTML source remains in the repository as a historical reference; it is not used to render the Native application screen.
