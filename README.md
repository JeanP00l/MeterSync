# MeterSync

Android app scaffold for automating data collection from `https://meter.printecs.com/` with offline Room storage.

Status: Initial scaffold with Room, Compose screens, Navigation, WebView manager, encrypted credentials, and JS assets.

Build: Open project in Android Studio and run the `app` configuration.

Notes:
- INTERNET permission added.
- JS scripts are under `app/src/main/assets/web/scripts/`.
- Further work: wire WebView automation in `MeterViewModel` using `WebViewManager`.
