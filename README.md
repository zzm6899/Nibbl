# Nibbl

A cute food and drink diary Android app for caffeine, cafe, matcha, snack, and meal photo logs.

## Features

- Month, week, and day calendar views.
- Camera capture and gallery import.
- ML Kit subject segmentation to remove image backgrounds and save transparent PNG cutouts.
- Multiple entries in the same calendar day, shown left to right in chronological order.
- Animated cutout progress while ML Kit removes the background, including a visible original-photo peel effect.
- Before/after review so the user can compare the source image with the transparent cutout before saving.
- Entry metadata for cafe, location hint, category, caffeine amount, and cafe friends.
- Friend chips and avatars for shared food and drink logs.
- Friend/category filters, summary stats, details, repeat logging, and safe delete.
- Local JSON persistence in app-private storage.

## Android Notes

The background removal uses Google ML Kit Subject Segmentation, which is the Android/Google Play services subject-removal tool used here:

- Dependency: `com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1`
- Minimum SDK: 24
- The segmentation model is downloaded by Google Play services. First use can take longer while the model initializes.

Open the project in Android Studio, let it create `local.properties` with your SDK path, then run the `app` configuration.

Self-host defaults:

- Public app/domain: `https://nibbl.z2hs.au`
- Backend/admin/API: `https://api.nibbl.z2hs.au`

If building from a terminal on this machine, use a Java 17 or 21 JDK. The installed Java 25 runtime is too new for the current Kotlin/Gradle toolchain. A valid `local.properties` should look like:

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

Then run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```
