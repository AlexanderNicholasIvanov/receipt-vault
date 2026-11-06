# Receipt Vault: notes for agents

- Android app, single module `:app`, Kotlin + Jetpack Compose + Room. AGP 9 has built-in Kotlin, so there is no `org.jetbrains.kotlin.android` plugin.
- Verify with `./gradlew lintDebug testDebugUnitTest assembleDebug`. CI runs exactly this. Lint errors fail the build.
- Logic belongs in `domain/` (pure Kotlin, JVM unit tests). Parser changes need a `ReceiptParserTest` case built from the receipt text that motivated them.
- Money is always `Long` minor units (cents). Never use Float or Double for amounts.
- Room schema changes: bump the version in `AppDatabase`, add a `Migration`, and commit the exported JSON under `app/schemas/`. Add a `MigrationTest` case that builds the previous version from its JSON and opens it with the current `AppDatabase`. Users' receipts live only on their phones, so destructive migrations are not acceptable.
- Every push to `main` publishes a signed release (`release.yml`). Never commit keystores or passwords. Signing comes from the `RV_*` Actions secrets.
- `targetSdk` is pinned to 36 (Android 16, One UI 8). Raising it is a deliberate, tested change.
