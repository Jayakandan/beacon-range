BEACON RANGE SCANNER  (Android)
================================

WHAT IT DOES
  Scans BLE beacons like nRF Connect and shows, live for each beacon:
    RSSI (dBm), distance in metres AND feet, and VALIDATE / OUT.

EDITABLE FIELDS (top of screen)
  Beacon Tx (dBm)  -> the beacon radio Tx power, e.g. -4 .. +4
  Loss @1m (dB)    -> ~1 m path loss used to turn Tx power into the 1 m RSSI
                      (default 59; tune it by calibration, see below)
  Env factor       -> your configTxPower (you said 20)
  Ref dist (m)     -> configDistance (usually 1.0)
  Validate (ft)    -> ticket validates when a beacon is within this many feet

  The app shows:  Measured @1m = Tx - Loss     (e.g. 0 - 59 = -59 dBm)
                  Validates up to X ft -> RSSI >= cutoff dBm

FORMULA (identical to your Kotlin)
  measuredAt1m = beaconTx - loss1m
  power        = (|rssi| - |measuredAt1m|) / (envFactor * 2.0)
  distance     = 10 ^ power * refDistance      (metres)
  feet         = distance * 3.28084

TWO WAYS TO GET THE APK
--------------------------------------------------------------------
A) BUILD IN ANDROID STUDIO (easiest to test)
   1. Install Android Studio.
   2. File > Open... and choose this "BeaconRange" folder.
   3. Let Gradle sync (needs internet first time).
   4. Plug in a real Android phone (BLE does NOT work on the emulator).
   5. Press Run, grant Bluetooth + Location, tap START SCAN.

B) LET GITHUB BUILD THE APK FOR YOU (no PC setup)
   1. Create a new GitHub repo.
   2. Upload ALL files in this folder (keep the folder structure,
      including the hidden .github folder).
   3. GitHub Actions runs automatically on push.
   4. When it finishes (green tick), download the APK from either:
        - the Actions run page  ->  Artifacts  ->  "app-debug", OR
        - the repo Releases page ->  "Latest debug build" -> app-debug.apk
        Direct link:  https://github.com/<you>/<repo>/releases/latest
   5. Copy the APK to your phone and install it
      (enable "Install unknown apps" for your browser/file manager).
   NOTE: it is a DEBUG apk (unsigned) - fine for testing, not for the Play Store.

CALIBRATION (do this once per beacon for accuracy)
   - Set "Beacon Tx" to your beacon's real Tx power (-4..+4).
   - Stand exactly 1 m away and read the RSSI the app shows for it.
   - Adjust "Loss @1m" until "Measured @1m" matches that reading.
   BLE RSSI is noisy, so expect the distance to wobble +/- a foot or two.
