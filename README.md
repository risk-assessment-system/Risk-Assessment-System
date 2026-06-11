# AVSEC QA Unit — GitHub Pages + Android WebView app

This repository contains:

- `docs/index.html`: the AVSEC QA web workspace, ready for GitHub Pages.
- `docs/.nojekyll`: publishes the static HTML directly without Jekyll processing.
- `app/`: a minimal Android app that loads the deployed HTTPS GitHub Pages URL.
- `.github/workflows/deploy-pages.yml`: deploys the website after changes under `docs/`.
- `.github/workflows/build-apk.yml`: builds a debug APK as a downloadable GitHub Actions artifact.

## What stays synchronized

1. **Website code/UI updates**: edit `docs/index.html`, commit, and push to `main`. GitHub Pages deploys the new website. The Android app loads the Pages URL again on launch, after returning from the background for more than 60 seconds, or when **Refresh web app** is selected from the app menu.
2. **Firebase records**: the supplied HTML already stores year-wise AVSEC QA data in Firestore. The same remote page runs inside Android, so the browser workspace and Android app use the same Firebase collections.
3. **Viewer live updates**: `docs/index.html` includes an additional viewer-only Firestore listener. When Firebase data changes, an open Viewer workspace refreshes from cloud automatically. Admin sessions are not overwritten by polling while editing.

## First-time GitHub setup

1. Create a GitHub repository and upload this entire folder.
2. Push the default branch as `main`.
3. Open **Settings → Pages** and choose **GitHub Actions** as the source.
4. Open **Actions**, run **Deploy AVSEC web app to GitHub Pages**, and copy the deployed HTTPS URL.
5. Open **Actions**, run **Build Android debug APK**, then download the `AVSEC-QA-Unit-debug-apk` artifact.
6. Install the APK on an Android device. On first launch, paste the GitHub Pages URL. It is saved locally and can be changed later from **Set web app URL**.

Expected URL format:

```text
https://YOUR_GITHUB_USERNAME.github.io/YOUR_REPOSITORY_NAME/
```

## Production security checklist

The uploaded HTML is a client-side web application. Before publishing it publicly:

- Replace client-side administrator credential checks with Firebase Authentication.
- Review and lock down Firestore Security Rules. A Firebase web configuration object is not a substitute for Firestore authorization rules.
- Do not commit private operational data, JSON backups, or exported spreadsheets into the public GitHub repository.
- Use an HTTPS Pages URL only. The Android wrapper blocks cleartext HTTP and prevents untrusted top-level pages from opening inside the WebView.

## Android wrapper features

- JavaScript and DOM storage for the AVSEC web workspace.
- Firebase-compatible online loading.
- File upload picker for JSON / Excel restore and import operations.
- Blob export bridge for generated PDF / Excel / JSON downloads.
- Native Android print dialog for the current WebView page.
- Cache-busting refresh when loading the deployed website.
- External links open in the device browser instead of inside the app.

## Local Android Studio build

Open the repository folder in Android Studio and run the `app` configuration. The project uses:

- Android Gradle Plugin `8.13.2`
- Gradle `8.13`
- Java `17`
- `compileSdk = 36`
- `minSdk = 26`

The GitHub Actions workflow performs the build without requiring a Gradle wrapper in the repository.
