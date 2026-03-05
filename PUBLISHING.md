# Publishing Guide

This guide explains how to publish Roux to Maven Central via Sonatype.

## Prerequisites

1. **GPG Key** - You need a GPG key for signing artifacts
   ```bash
   # Generate a key if you don't have one
   gpg --gen-key
   
   # List keys to get the key ID
   gpg --list-keys
   
   # Export the key to a keyring file
   gpg --export-secret-keys -o ~/.gnupg/secring.gpg
   ```

2. **Sonatype Account** - You need access to the `com.cajunsystems` group on Sonatype

3. **Gradle Properties** - Add signing configuration to `~/.gradle/gradle.properties`:
   ```properties
   signing.keyId=<last 8 chars of your GPG key ID>
   signing.password=<your GPG key password>
   signing.secretKeyRingFile=/Users/<username>/.gnupg/secring.gpg
   ```

## Building the Bundle

### 1. Run Tests
```bash
./gradlew test
```

### 2. Create Bundle for Central Portal
```bash
./gradlew clean createCentralBundle
```

This creates a zip file at `lib/build/distributions/roux-0.2.2-bundle.zip` containing:
- Proper Maven directory structure (`com/cajunsystems/roux/0.2.2/`)
- `roux-0.2.2.jar` - Main library
- `roux-0.2.2-sources.jar` - Source code
- `roux-0.2.2-javadoc.jar` - Javadoc
- `roux-0.2.2.pom` - POM file
- `roux-0.2.2.module` - Gradle metadata
- `.asc` files - GPG signatures for all artifacts
- `.md5`, `.sha1`, `.sha256`, `.sha512` files - Checksums

## Uploading to Sonatype

### Manual Upload via Web UI

1. Go to https://s01.oss.sonatype.org/
2. Log in with your Sonatype credentials
3. Click "Staging Upload" in the left sidebar
4. Select "Artifact Bundle" as upload mode
5. Upload the bundle zip file: `lib/build/distributions/roux-0.2.2-bundle.zip`
6. Click "Upload Bundle"

### Verify and Release

1. Go to "Staging Repositories" in the left sidebar
2. Find your staging repository (usually `comcajunsystems-XXXX`)
3. Select it and click "Close"
4. Wait for validation to complete (check "Activity" tab)
5. Once validated, click "Release"
6. Artifacts will sync to Maven Central within ~30 minutes

## Version Management

Update the version in `lib/build.gradle.kts`:

```kotlin
version = "0.2.2"  // Change this for new releases
```

## Checklist Before Publishing

- [ ] All tests pass (`./gradlew test`)
- [ ] CHANGELOG.md is updated with release notes
- [ ] Version number is correct in `build.gradle.kts`
- [ ] README.md reflects current version
- [ ] Documentation is up to date
- [ ] GPG signing is configured
- [ ] Sonatype credentials are set up

## Troubleshooting

### "No signing key found"
Make sure your `~/.gradle/gradle.properties` has the correct signing configuration.

### "POM validation failed"
Check that all required POM elements are present:
- name, description, url
- licenses
- developers
- scm (source control)

### "Signature validation failed"
Ensure your GPG key is properly exported and the password is correct.

## Post-Release

1. Create a GitHub release with the same version tag
2. Update documentation site (if applicable)
3. Announce the release
4. Monitor for issues

## Resources

- [Sonatype OSSRH Guide](https://central.sonatype.org/publish/publish-guide/)
- [Maven Central Repository](https://search.maven.org/)
- [GPG Documentation](https://www.gnupg.org/documentation/)
