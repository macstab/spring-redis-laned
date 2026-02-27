# Release Process

## First-Time Maven Central Setup

**Only needed ONCE for new namespace:**

1. **Register with Sonatype Central Portal:**
   - Go to: https://central.sonatype.com/
   - Create account (or login)

2. **Verify Namespace:**
   - Click **"Namespaces"** → **"Add Namespace"**
   - Enter: `com.macstab.oss`
   - Verify domain ownership:
     - Add TXT record to `macstab.com`
     - Value shown in portal (e.g., `sonatype-verify=XXXXX`)
     - Wait for DNS propagation (~5 min)
     - Click "Verify"

3. **Generate User Token:**
   - Click your username → **"View Account"**
   - Click **"Generate User Token"**
   - Copy username + password to `~/.gradle/gradle.properties`:
     ```properties
     ossrhUsername=<token-username>
     ossrhPassword=<token-password>
     ```

4. **Setup GPG Key:**
   ```bash
   # Generate key (if needed)
   gpg --gen-key
   
   # Export to keyserver
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   
   # Export secret keyring
   gpg --export-secret-keys <KEY_ID> > ~/.ssh/mavencentral_gpg_keyring.gpg
   ```

5. **Configure Gradle:**
   Add to `~/.gradle/gradle.properties`:
   ```properties
   signing.keyId=<last-8-chars-of-key-id>
   signing.password=<gpg-passphrase>
   signing.secretKeyRingFile=/Users/you/.ssh/mavencentral_gpg_keyring.gpg
   ```

**After this one-time setup, you can publish releases!**

---

## Publishing Overview and Steps

| Target              | Trigger               | Version          | Artifact  |
|---------------------|-----------------------|------------------|-----------|
| **GitHub Packages** | Push to `develop`     | `X.Y.Z-SNAPSHOT` | Automatic |
| **GitHub Packages** | Push tag `X.Y.Z`      | `X.Y.Z`          | Automatic |
| **Maven Central**   | Manual (checkout tag) | `X.Y.Z`          | Manual    |

---

## Development Workflow (SNAPSHOT Publishing)

### Push to Main → Automatic SNAPSHOT

```bash
# Make changes
git commit -m "feat: add new feature"
git push origin main
```

**What happens:**
1. GitHub Actions runs `.github/workflows/publish-snapshot.yml`
2. Builds project
3. Publishes `1.0.0-SNAPSHOT` to GitHub Packages (overwrites previous SNAPSHOT)

**Users can depend on SNAPSHOT:**
```xml
<dependency>
  <groupId>com.macstab.oss</groupId>
  <artifactId>redis-laned-spring-boot-3-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

**When to use a SNAPSHOT:**
- When you want to test unreleased features
- When you want to use the latest changes before a release
- When you want to share your work with others for feedback

---

## Release Workflow

### Step 1: Create Pull Request (PR) from `develop` to `main`

**What happens automatically:**
1. GitHub Actions runs `.github/workflows/build.yml`
2. Extracts version from tag (`1.0.0` → `1.0.0`)
3GitHub Actions runs `.github/workflows/release-snapshot.yml`
4Publishes `1.0.0` to **GitHub Packages**

### Step 2: Merge Pull Request (PR) from `develop` to `main`

**What happens automatically:**
1. GitHub Actions runs `.github/workflows/build.yml`
2. Validating the release GitHub Actions runs `.github/workflows/semantic-release.yml`
3. Extracts version from tag (`v1.0.0` → `1.0.0`)
4. Tag is created
5. Updates `gradle.properties` (removes SNAPSHOT)
6. GitHub Actions runs `.github/workflows/publish-release.yml`
7. Semantic-release generates `CHANGELOG.md`
8. Creates GitHub Release with release notes
9. Publishes `1.0.0` to **GitHub Packages**

**Result:**
- ✅ GitHub Release created
- ✅ CHANGELOG.md updated
- ✅ `1.0.0` available on GitHub Packages

### Step 2: Publish to Maven Central (Manual)

**Prerequisites:**

1. **Configure credentials** in `~/.gradle/gradle.properties`:
   ```properties
   ossrhUsername=<your-sonatype-token-username>
   ossrhPassword=<your-sonatype-token-password>
   signing.keyId=<gpg-key-id>
   signing.password=<gpg-key-password>
   signing.secretKeyRingFile=<path-to-keyring.gpg>
   ```

2. **Get Sonatype credentials:**
   - Go to: https://central.sonatype.com/
   - Login → Account → "Generate User Token"
   - Copy username + password to `gradle.properties`

**Publish to Maven Central Staging:**

```bash
# Checkout the release tag
git checkout 1.0.0

# Publish artifacts to staging
./gradlew clean build publish

# Expected output:
# > Task :redis-laned-core:signMavenPublication
# > Task :redis-laned-core:publishMavenPublicationToOSSRHRepository
# BUILD SUCCESSFUL

# Show release instructions
./gradlew releaseToCentral
```

**Manual Release via Central Portal:**

1. **Login to Central Portal:**
   - Go to: https://central.sonatype.com/
   - Login with your Sonatype account

2. **Find Deployment:**
   - Navigate to: **"Deployments"** (left sidebar)
   - Search for: `com.macstab.oss`
   - Find your version: `1.0.0`

3. **Review Artifacts:**
   - Click on the deployment
   - Verify all artifacts uploaded correctly:
     - JARs (main, sources, javadoc)
     - POMs
     - Signatures (.asc files)

4. **Publish to Maven Central:**
   - Click **"Publish"** button
   - Confirm publication
   - Artifacts are immediately sent to Maven Central

5. **Verify Publication:**
   - Wait 10-30 minutes for sync
   - Check: https://central.sonatype.com/artifact/com.macstab.oss/redis-laned-core
   - Check: https://repo1.maven.org/maven2/com/macstab/oss/redis-laned-core/

```bash
# Return to main
git checkout main
```
---

## Commit Message Format (Conventional Commits)

Semantic-release analyzes commit messages to determine version bump:

| Type     | Version Bump          | Example                         |
|----------|-----------------------|---------------------------------|
| `feat:`  | MINOR (1.0.0 → 1.1.0) | `feat: add cluster support`     |
| `fix:`   | PATCH (1.0.0 → 1.0.1) | `fix: connection leak`          |
| `perf:`  | PATCH (1.0.0 → 1.0.1) | `perf: optimize lane selection` |
| `docs:`  | NONE                  | `docs: update README`           |
| `feat!:` | MAJOR (1.0.0 → 2.0.0) | `feat!: remove deprecated API`  |
| `chore:` | PATCH (1.0.0 → 1.0.1) | `chore: update dependencies`    |

**Format:**
```
<type>: <description>

[optional body]

[optional footer]
```

**Examples:**
```bash
git commit -m "feat: add metrics support"
git commit -m "fix: resolve connection timeout issue"
git commit -m "docs: improve README examples"
```

---

## Quick Reference

### Publish SNAPSHOT (Development)
```bash
git commit -m "feat: your change"
git push origin main
# → 1.0.0-SNAPSHOT published to GitHub Packages
```

### Release to GitHub Packages
```bash
git tag v1.0.0
git push origin v1.0.0
# → 1.0.0 published to GitHub Packages
# → GitHub Release created
# → CHANGELOG.md updated
```

### Release to Maven Central
```bash
git checkout v1.0.0
./gradlew clean build publish
./gradlew releaseToCentral  # Shows manual release instructions
# → Go to central.sonatype.com → Deployments → Publish
git checkout main
```

### Bump Version
```bash
# Edit build.gradle.kts: version = "1.1.0-SNAPSHOT"
git commit -am "chore: bump to 1.1.0-SNAPSHOT"
git push
```

---

## Troubleshooting

### SNAPSHOT Not Publishing
- Check GitHub Actions logs: https://github.com/macstab/spring-redis-laned/actions
- Verify `version` ends with `-SNAPSHOT` in `build.gradle.kts`

### Release Tag Not Triggering Workflow
- Verify tag format: `v1.0.0` (not `1.0.0`)
- Check `.github/workflows/release.yml` exists
- Check GitHub Actions logs

### Maven Central Publishing Fails

**HTTP 401 Unauthorized:**
- Verify `~/.gradle/gradle.properties` has correct credentials
- Regenerate token at: https://central.sonatype.com/ → Account → Generate User Token
- **Important:** New Central Portal uses different credentials than old JIRA!

**HTTP 405 Not Allowed / HTTP 402 Payment Required:**
- Namespace not verified in Central Portal
- Go to: https://central.sonatype.com/ → Namespaces
- Add namespace: `com.macstab.oss` (or your groupId)
- Verify domain ownership (TXT record on `macstab.com`)

**Signing Fails:**
- Verify GPG key configured: `signing.keyId`, `signing.password`, `signing.secretKeyRingFile`
- Check GPG key uploaded to keyserver: `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`
- Test signing: `gpg --sign --armor test.txt`

### Build Fails
```bash
# Clean build
./gradlew clean build

# Check formatting
./gradlew spotlessCheck

# Auto-fix formatting
./gradlew spotlessApply
```

---

## Version History Example

```
1.0.0-SNAPSHOT  (main branch, continuous development)
    ↓ git tag v1.0.0
1.0.0           (GitHub Packages + Maven Central)
    ↓ bump version
1.1.0-SNAPSHOT  (main branch, next development cycle)
    ↓ git tag v1.1.0
1.1.0           (GitHub Packages + Maven Central)
```
