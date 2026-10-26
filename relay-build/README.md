# HeatWatch Relay

A browser and Android relay for the Bangle.js 2 heat-stress monitor. It pulls the watch's
1-minute log over Bluetooth Low Energy (BLE), averages it into 10-minute records,
computes core temperature and Heat Index, and shows a Heat Stress Risk Score (HSRS,
0 to 10) with a risk tier. Everything runs on the client; there is no backend.

HSRS is computed directly from the closed-form rubric (`0.5·CT + 0.3·HR + 0.2·HI` component
scores). The relay runs no machine-learning model.

```
relay-build/
├── web/       heatwatch_relay.html
├── android/   Kotlin / Jetpack Compose app (with JVM parity tests in app/src/test)
└── model/     research record of the SVR score-surrogate export; not used by either client:
               export_svr_tflite.py, svr_heatwatch_params.json, golden_vectors.json,
               svr_heatwatch_fp32.tflite, svr_heatwatch_tf/
```

## Pipeline

```
Bangle.js 2 (BLE, Nordic UART) -> 1-minute JSON lines -> 10-minute buckets
   -> core temperature (EKF) + Heat Index (Rothfusz)
   -> HSRS (closed-form rubric) -> risk tier
```

Input is one JSON object per line with `ts` (`yyyy-MM-dd HH:mm:ss`), `hr`, `temp` and
`w_hum`. The web client also loads saved `.log`, `.txt` and `.csv` files (CSV columns:
`hr, temp, w_hum, CT, HI_Rothfusz, Final_Three_HSRS`; the HSRS shown is recomputed from
`hr`, `CT` and `HI_Rothfusz`, so it can differ slightly from the stored column).

The same pipeline is implemented in both clients: bucketing, EKF, Heat Index and HSRS rubric
live in `web/heatwatch_relay.html` and in
`android/app/src/main/java/com/kuwait/heatwatch/model/`. The reference implementations are
`compute_ct_kalman.py`, `compute_rothfusz.py` and `compute_hsrs.py` in `relay-planning/reference/`.

CT and Heat Index are rounded to 2 decimals before scoring, as in the stored training columns.

## Model folder (`model/`)

An earlier version of the relay ran an RBF SVR (StandardScaler folded into one TFLite graph)
to produce the HSRS. It was an approximation of the rubric and could not do better than the
formula, so the clients no longer use it. The folder is kept as the record of the surrogate
study: `export_svr_tflite.py` rebuilds the graph from `svr_heatwatch_params.json`, converts it
to fp32 TFLite, and compares it with `golden_vectors.json` (requires TensorFlow):

```bash
cd model
python3 export_svr_tflite.py
```

## Web client (`web/`)

Open `heatwatch_relay.html` in Chrome or Edge with an internet connection (the Puck.js
library is loaded from a CDN) and Bluetooth on. It is a single file.

- BLE: Puck.js library over the Nordic UART Service.

## Android app (`android/`)

Open `android/` in Android Studio (Giraffe or newer), sync Gradle, and run on a physical
device. `minSdk` 26, `targetSdk` 34.

- `ScoringPipeline.kt` chains the pipeline, `FeatureEngine.kt` holds bucketing, EKF and
  Heat Index, `Hsrs.kt` holds the tiers and rubric, `BangleBleClient.kt` handles BLE.
- The app scans for a device named `Bangle…`, connects, and requests the last 60 minutes
  of `upload.log`. It needs `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` on Android 12+.
- Build and tests (no device needed): `./gradlew assembleDebug testDebugUnitTest`.
  `RubricParityTest` checks the rubric and the full pipeline against the Python reference
  values stored in `app/src/test/resources`.
