# VoiceKB Release Runbook

This file explains how VoiceKB Android versions and GitHub releases work.

## Version Fields

Android uses two version fields in `app/build.gradle`:

```gradle
versionCode 2
versionName "0.1.1"
```

`versionCode` is the technical update number Android uses to decide whether one
APK can update another APK. It must increase for every APK that should install
over a previous release.

`versionName` is the human-readable version shown to users in the app and in
release notes. It does not control whether Android accepts an update.

## Required Rule

Increase `versionCode` for every public build:

- alpha
- beta
- release candidate
- stable release
- hotfix

Examples:

```gradle
versionCode 2
versionName "0.2.0-alpha.1"
```

```gradle
versionCode 3
versionName "0.2.0-alpha.2"
```

```gradle
versionCode 4
versionName "0.2.0-beta.1"
```

```gradle
versionCode 5
versionName "0.2.0"
```

`versionCode` always moves forward, even when `versionName` moves from a
pre-release name to a stable name.

## If You Forget A Field

If `versionCode` is not increased, Android may refuse to install the APK over an
already installed copy. This is the dangerous mistake.

If `versionName` is not changed, Android can still install the APK when
`versionCode` is higher, but users will see the old version name. This is not
usually install-breaking, but it is confusing.

Best practice: update both fields together.

## Tags And Changelog

GitHub Releases are created by pushing a tag that starts with `v`.

Recommended tag format:

```text
v0.2.0-alpha.1
v0.2.0-beta.1
v0.2.0
```

`CHANGELOG.md` should contain a matching heading:

```md
## v0.2.0-beta.1

- Fixed ...
- Added ...
```

The release workflow uses the tag name to find the matching changelog section.

## Release Checklist

1. Update `versionCode` and `versionName` in `app/build.gradle`.
2. Add a matching section to `CHANGELOG.md`.
3. Run local checks:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
.\gradlew.bat assembleRelease
git diff --check
```

4. Commit the changes.
5. Create a tag:

```powershell
git tag v0.2.0
```

6. Push the commit and tag:

```powershell
git push
git push origin v0.2.0
```

GitHub Actions will build the signed release APK and attach it to the GitHub
Release.

## Debug Builds

Debug builds use a separate package:

```text
com.idefant.voicekb.debug
```

Release builds use:

```text
com.idefant.voicekb
```

Because the package names differ, debug APKs can be installed next to the normal
release app on the same phone.
