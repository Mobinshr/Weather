from pathlib import Path

source = Path(__file__).with_name("weather-ios.html")
target = Path("app/src/main/assets/weather.html")
if not source.is_file():
    raise SystemExit("The replacement HTML source weather-ios.html is missing.")
if not target.parent.is_dir():
    raise SystemExit(f"Android assets directory is missing: {target.parent}")
page = source.read_text(encoding="utf-8")
required = [
    'lang="fa"',
    'SYSTEM_LANGS',
    'search-area{position:relative',
    '#sug[hidden]{display:none!important}',
    'count=100',
    'Promise.allSettled',
    'alternateNames',
    'dir="auto"',
    'navigator.geolocation.getCurrentPosition',
    'https://api.open-meteo.com/v1/forecast',
]
missing = [item for item in required if item not in page]
if 'countryCode=IR' in page:
    missing.append('global city search (countryCode=IR must be absent)')
if missing:
    raise SystemExit("Replacement HTML is incomplete: " + ", ".join(missing))
target.write_text(page, encoding="utf-8")
print(f"Installed the supplied weather interface: {target} ({len(page)} characters)")
