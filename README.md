# Word Book

Offline Android vocabulary notebook with flashcard revision, daily auto-backup to `Download/WordBook/`, and in-app updates from this repo's GitHub Releases.

## Install
Download the `.apk` from the [latest release](../../releases/latest) on the phone and open it.

## Releasing an update
1. In `app/build.gradle.kts` bump `versionCode` (+1) and `versionName` (e.g. `1.1`).
2. Build: `./gradlew assembleRelease` (needs `keystore.properties` + the signing key, which are NOT in this repo).
3. Publish: `gh release create v1.1 app/build/outputs/apk/release/app-release.apk --title "v1.1" --notes "What changed"`

The app shows the release notes in its update popup. Always sign with the same key, or updates won't install over the old app.
