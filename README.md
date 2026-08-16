# simplecast

simplecast is a minimal Android weather app designed for the Light Phone III Android layer.

It uses Open-Meteo for weather and geocoding, keeps the interface black-and-white, and avoids ads, accounts, analytics, and unnecessary UI.

![simplecast home screen](screenshots/home.png)

## Features

- Current conditions with animated monochrome glyphs
- Scrollable hourly forecast strip
- Scrollable 10-day forecast with per-day hourly detail
- Place search using Open-Meteo geocoding
- Configurable home location
- Optional 10-minute background weather checks
- True AMOLED black background
- Native Android app built with Jetpack Compose

## Gestures

- Pull down on home: refresh (follows your finger)
- Swipe up from home: 10-day forecast slides in
- Swipe right from 10-day: home slides back
- Scroll the hourly strip on home
- Tap a day: that day's hourly forecast slides in
- Swipe right from day detail: back to 10-day
- Swipe right from home: search slides in
- Swipe left from home: settings slides in

## Build

Requires JDK 17 or newer and the Android SDK.

```powershell
.\gradlew.bat assembleRelease
```

The release APK is generated at:

```text
app/build/outputs/apk/release/app-release.apk
```
