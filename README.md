# Receipt Vault

An Android app for Samsung Galaxy phones on **One UI 8 (Android 16)** and newer that stores your receipts and tracks what you spend. Take a photo of a receipt and the app reads it for you:

- **Store**: merchant name, address and phone
- **When**: purchase date and time
- **Amounts**: subtotal, discount, tax, tip (with tip %), total, currency
- **Line items**: name, quantity and price
- **Where**: the place you scanned it (optional GPS plus a street address), with *Open in Maps*
- **Category**: dining, groceries, fuel and others, guessed automatically

You review and correct everything before saving. A warning appears if subtotal − discount + tax + tip doesn't add up to the total.

The home screen shows what you spent this month, last month, this year or all time, with tips and tax totals and a search box. **Insights** adds a monthly spending chart, a category breakdown, top merchants and your average tip. **Settings** can export everything to CSV for Excel or Google Sheets.

Text recognition runs **on the phone** (ML Kit), so receipts and photos never leave the device. The app only goes online to check GitHub for updates and to look up addresses on the map.

## Install on your phone

1. On the phone, open **https://github.com/AlexanderNicholasIvanov/receipt-vault/releases/latest/download/ReceiptVault.apk**. You can also go to the [Releases page](https://github.com/AlexanderNicholasIvanov/receipt-vault/releases/latest) and tap `ReceiptVault.apk`.
2. Open the downloaded file. If One UI asks, allow your browser (Samsung Internet or Chrome) to **Install unknown apps**.
3. Tap **Install**.

### Updating

Open **Settings → Updates → Check for updates**, or wait for the banner on the home screen (the app checks once per launch; turn this off with *Check automatically*). Tap **Download & install**: the app downloads the APK from the latest GitHub Release and verifies it, then opens Android's installer. The checks are: the size and SHA-256 match what GitHub published, it is this app, it is a newer version, and it is signed with the same key. The first time, Android asks you to allow *Install unknown apps* for Receipt Vault. Turn it on, come back, and tap **Install**.

Updates install over the old version and keep your receipts. If the installed copy is a debug build (signed with a different key), the app says so instead of failing: export to CSV, uninstall, then install the release.

> On some One UI builds, **Auto Blocker** (Settings → Security and privacy → Auto Blocker) blocks apps from outside the Galaxy Store and Play Store. Turn it off while you install, then turn it back on.

## Using it

- Tap **Add receipt** and choose one of these:
  - **Scan receipt**: Google's document scanner auto-crops and flattens the paper. Gives the best OCR results.
  - **Take a photo**: uses the regular camera app.
  - **Choose from gallery**: imports an existing photo or screenshot.
  - **Enter manually**: no photo, just the numbers.
- You can also **Share** a photo from Gallery to *Receipt Vault*.
- Tap a receipt to see the photo (pinch to zoom), the breakdown, location and items. You can edit, share or delete it from there.

## Development

Requirements: JDK 21 and the Android SDK (platform 37). Android Studio works, or the command line:

```sh
./gradlew lintDebug testDebugUnitTest assembleDebug   # what CI runs
./gradlew installDebug                                  # onto a connected phone/emulator
```

Layout:

| Path | What |
|---|---|
| `app/src/main/java/.../domain/` | Pure Kotlin, unit tested. `ReceiptParser` (OCR text → amounts, date, merchant…), `LineAssembler` (joins label/price columns), `Money`, `Insights`, `CsvExport` |
| `.../scan/` | ML Kit OCR, photo storage, location and geocoding |
| `.../data/` | Room database (`receipts`, `line_items`) and DataStore settings |
| `.../ui/` | Jetpack Compose screens: home, review/edit, detail, insights, settings |
| `.../update/` | GitHub Releases check, in-app APK download + verification, installer hand-off |

The receipt parser is heuristic. If a real receipt is misread, add its text as a case in `ReceiptParserTest` and fix the parser against it.

## Releases and signing

- `ci.yml` runs lint, unit tests and a debug build on every push and PR.
- `release.yml` runs on every push to `main`. It builds a release APK signed with the release key, tags it `v1.0.<run number>` and publishes it as a GitHub Release with the asset `ReceiptVault.apk`.
- The signing key lives in the repository's Actions secrets: `RV_KEYSTORE_B64`, `RV_KEYSTORE_PASSWORD`, `RV_KEY_ALIAS` and `RV_KEY_PASSWORD`. **Keep an offline backup of the keystore.** If it is lost, future builds cannot update existing installs, and users would have to uninstall, which deletes their receipts. Export to CSV first.

**Roll back a bad release:** delete the bad GitHub Release so the previous one becomes *latest*. Phones that already updated need the older APK installed after uninstalling, because Android blocks downgrades. Usually it's better to revert the commit on `main`, which publishes a fixed, higher version.
