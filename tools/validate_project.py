from pathlib import Path

root = Path.cwd()
manifest_path = root / "app/src/main/AndroidManifest.xml"
activity_path = root / "app/src/main/java/com/example/weather/MainActivity.kt"
html_path = root / "app/src/main/assets/weather.html"
gradle_path = root / "app/build.gradle.kts"
icon_path = root / "app/src/main/res/drawable/ic_launcher.xml"
icon_image_path = root / "app/src/main/res/drawable-nodpi/ic_launcher_asset.webp"
required = [manifest_path, activity_path, html_path, gradle_path, icon_path, icon_image_path]
missing = [str(p.relative_to(root)) for p in required if not p.is_file()]
if missing:
    raise SystemExit("Required Android project files are missing: " + ", ".join(missing))

html = html_path.read_text(encoding="utf-8")
manifest = manifest_path.read_text(encoding="utf-8")
activity = activity_path.read_text(encoding="utf-8")
gradle = gradle_path.read_text(encoding="utf-8")
checks = {
    "System language detection and RTL/LTR switching are present": "navigator.languages" in html and "document.documentElement.dir" in html and "SYSTEM_LANGS" in html,
    "Qandil app branding is present": "آب‌وهوای قندیل" in html and 'android:label="آب‌وهوای قندیل"' in manifest,
    "Rain probability forecast is present": "precipitation_probability" in html and "precipitation_probability_max" in html,
    "Global city search has no country restriction": "countryCode=IR" not in html and "geocoding-api.open-meteo.com/v1/search" in html,
    "City search has a floating scrollable dropdown with up to 100 results": "search-area{position:relative" in html and "#sug[hidden]{display:none!important}" in html and "count=100" in html and "const appLang = 'fa'" in html and 'dir="auto"' in html,
    "GPS location flow is present": "navigator.geolocation.getCurrentPosition" in html,
    "WebView GPS permission handling is present": "setGeolocationEnabled(true)" in activity and "onGeolocationPermissionsShowPrompt" in activity and "requestCode == 1002" in activity,
    "weather uses selected coordinates": "api.open-meteo.com/v1/forecast?latitude=" in html,
    "hourly and daily rainfall data are requested": "precipitation_probability" in html and "precipitation_probability_max" in html,
    "notification controls from supplied interface are present": 'id="bell"' in html and "Notification.requestPermission" in html,
    "Huawei native GPS fallback is present": "requestNativeLocation" in html and "onQandilNativeLocation" in html,
    "Online translation fallback is available": "translate.googleapis.com" in html and "MutationObserver" in html,
    "Persian, English, and Arabic built-in translations exist": all(x in html for x in ["en:{","ar:{","SYSTEM_LANGS"]),
    "Android internet permission is retained": "android.permission.INTERNET" in manifest,
    "Android location permission is declared": "android.permission.ACCESS_FINE_LOCATION" in manifest or "android.permission.ACCESS_COARSE_LOCATION" in manifest,
    "Android notification permission is declared": "android.permission.POST_NOTIFICATIONS" in manifest,
    "Qandil launcher icon is installed": 'android:icon="@drawable/ic_launcher"' in manifest and icon_image_path.stat().st_size > 0,
    "existing Android application ID is retained": 'applicationId = "com.example.weather"' in gradle,
}
for label, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'}: {label}")
failed = [label for label, passed in checks.items() if not passed]
if failed:
    raise SystemExit("Replacement project validation failed: " + "; ".join(failed))
print("Replacement weather app validation passed.")
