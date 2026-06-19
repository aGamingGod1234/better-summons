## [2026-03-10] - Build mod jar and replace installed Minecraft mod
### What Was Implemented
- Built the Fabric mod with Gradle using `.\gradlew.bat build`
- Replaced the installed mod jar at `%APPDATA%\.minecraft\mods\better-summon-1.0.0.jar`
- Verified the copied jar matches the built artifact via SHA-256 hash

### Files Modified
- `PROJECT_LOG.md` - Added a record of the build and deployment step

### Assumptions Made (flag these for review)
- Assumed the intended target mod was `%APPDATA%\.minecraft\mods\better-summon-1.0.0.jar` based on the project archive name and existing installed jar

### Known Issues / Deferred
- None

### Suggested Next Steps
- Launch Minecraft and confirm the updated mod loads without runtime errors

## [2026-03-10] - Rewrite README for end users
### What Was Implemented
- Rewrote `README.md` for players and server admins instead of developers
- Reorganized the document around installation, command usage, examples, and behavior
- Simplified technical wording and removed developer-focused build details from the main read path

### Files Modified
- `README.md` - Replaced the developer-oriented readme with a user-friendly guide
- `PROJECT_LOG.md` - Added a log entry for the README rewrite

### Assumptions Made (flag these for review)
- Assumed the README should target end users who already have or can obtain the built mod jar
- Assumed keeping the install steps generic was better than documenting a release workflow that is not currently described in the repository

### Known Issues / Deferred
- No screenshots or release-download instructions were added

### Suggested Next Steps
- If you plan to publish the mod, add a direct download section once you have a release page or distribution source

## [2026-03-10] - Bump mod version to 1.0.1 and rebuild jar
### What Was Implemented
- Updated the mod version from `1.0.0` to `1.0.1` in the Gradle properties
- Rebuilt the project so the output artifact uses the new version number

### Files Modified
- `gradle.properties` - Updated `mod_version` to `1.0.1`
- `PROJECT_LOG.md` - Added a log entry for the version bump and rebuild

### Assumptions Made (flag these for review)
- Assumed `mod_version` in `gradle.properties` is the only version value that needed to change for this release bump
- Assumed you wanted the jar rebuilt in `build/libs` only, without replacing the installed jar automatically

### Known Issues / Deferred
- None

### Suggested Next Steps
- Replace any installed `1.0.0` jar with the new `1.0.1` jar before launching Minecraft

## [2026-03-10] - Replace installed mod jar with version 1.0.1
### What Was Implemented
- Replaced the installed Better Summon mod in `%APPDATA%\.minecraft\mods` with the freshly built `1.0.1` jar
- Renamed the installed file so the mods folder now contains `better-summon-1.0.1.jar`
- Verified the installed jar matches the built artifact via SHA-256 hash

### Files Modified
- `PROJECT_LOG.md` - Added a record of the installed mod replacement

### Assumptions Made (flag these for review)
- Assumed the existing installed `better-summon-1.0.0.jar` should be replaced in place with the new `1.0.1` build

### Known Issues / Deferred
- None

### Suggested Next Steps
- Launch Minecraft and confirm Fabric loads `Better /summon` version `1.0.1` without duplicate-mod warnings
