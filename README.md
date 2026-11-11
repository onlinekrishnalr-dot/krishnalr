# KRISHNA LR — Native Android project

**Application ID:** `in.krishnalr.app`  
**Website loaded by the app:** `https://krishnalr.in/`  
**Stack:** Kotlin + XML, AndroidX, Material Components, Android WebView  
**Minimum Android:** Android 6.0 (API 23)  
**Compile / target SDK:** 35

## What is included

- Native Android application shell with KRISHNA LR branding.
- WebView loads the existing Krishna LR website.
- Hindi offline/error panel and retry action.
- Android back navigation.
- File chooser support for website upload fields.
- Download manager integration for website PDF/file downloads.
- Share current page, reload, external links, phone and email links.
- WebView cookies, JavaScript and DOM storage enabled.
- All uploaded brand icon sizes and mobile reference retained under `branding/`.
- GitHub Actions workflow builds a debug APK and uploads it as an artifact.

## Important backend boundary

This project connects to the **website frontend/backend through the website itself**. It does not invent or replace KrishnaLR.in APIs. Any feature that the website does not currently expose, or that requires a separately documented API, will not magically become a native screen in this WebView-based version. To build fully native LR, vehicle, driver, load, expense and report screens, API documentation and authentication details are required.

## Open in Android Studio

1. Extract the ZIP.
2. Open the `KRISHNA_LR_Android` folder in Android Studio.
3. Install Android SDK Platform 35 and JDK 17 if prompted.
4. Allow Gradle sync to complete.
5. Select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
6. Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

The project uses Android Gradle Plugin 8.7.3 and Gradle 8.9.

## Build APK using GitHub

1. Create a new empty GitHub repository, for example `krishna-lr-android`.
2. Upload/push the complete contents of this folder to the repository's `main` branch.
3. Open **Actions** in GitHub. The workflow `Build KRISHNA LR Android APK` runs on push.
4. Open the completed workflow run and download the `KRISHNA-LR-debug-apk` artifact.
5. For a later production release, configure a private release keystore and signing secrets. Never commit the keystore or passwords to GitHub.

## Android App Links / assetlinks.json

The uploaded `branding/assetlinks.json` belongs to a different package (`app.netlify.comfy_youtiao_787e6f.twa`) and a different signing certificate. It cannot verify `in.krishnalr.app`.

After creating a release signing key, obtain its SHA-256 certificate fingerprint and replace the placeholder in `assetlinks-template.json`. Publish the resulting file at:

`https://krishnalr.in/.well-known/assetlinks.json`

The package name and fingerprint must match the signed APK exactly. Debug APK uses a debug application ID suffix (`in.krishnalr.app.debug`), so the template is for the release application ID.

## Notes

- The app requires internet access because the business data and pages are served by the website.
- The first page and the website's upload/download workflows should be tested against the live site on a physical Android device.
- This is a source project plus automated build workflow; no APK is attached because this environment does not have the Android SDK/Gradle build environment installed.
