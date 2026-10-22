# am-lyrics for Echo

An [Echo](https://github.com/brahmkshatriya/echo) **lyrics extension** built on the data layer of
[binimum/am-lyrics](https://github.com/binimum/am-lyrics): word-synced lyrics from LyricsPlus
(the KPoe backend), with LRCLIB as a fallback.

Echo draws lyrics itself, so the `<am-lyrics>` web component (DOM/CSS) is not used - only its
lookup chain is.

## Results offered (Echo selects the first by default)
1. LyricsPlus, line-synced
2. LRCLIB, line-synced or plain
3. LyricsPlus, word-by-word - the current echo-nightly player flattens this type and does not
   highlight it, so it is listed last for players that do render it

## Build the `.eapk`
An `.eapk` is the `app` module's APK renamed.

### Option A - GitHub Actions (no local setup)
1. Push this project to a new GitHub repo (branch `main`).
2. Edit `gradle.properties`: set `extAuthor`, `extRepoUrl`, `extUpdateUrl` (replace `YOUR_USER`).
3. Repo Settings > Actions > General > Workflow permissions > **Read and write**.
4. Make a signing key (any machine with a JDK), alias must be `key0`:
   ```
   keytool -genkeypair -v -keystore signing-key.jks -alias key0 -keyalg RSA -keysize 2048 -validity 10000
   base64 -w0 signing-key.jks
   ```
5. Repo Settings > Secrets > Actions: `KEYSTORE_B64` = the base64 output, `PASSWORD` = the key/store password.
6. Push. The workflow publishes `am_lyrics-<hash>.eapk` on the repo's Releases page.

### Option B - locally (JDK 17 + Android SDK)
```
git init && git add -A && git commit -m init     # the build reads the git hash
./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk am_lyrics.eapk
```

Install the `.eapk` in Echo's extension manager.

## Notes
- No extra Gradle dependencies: OkHttp, coroutines and kotlinx-serialization come from Echo's common library.
- LyricsPlus JSON field names come from secondary sources and are parsed defensively. To compare with a live response:
  `curl "https://lyricsplus.prjktla.my.id/v2/lyrics/get?title=Uptown+Funk&artist=Mark+Ronson"`
- Tests: `./gradlew :ext:test` (offline).
