# Release Process

## Publishing Overview and Steps

| Target              | Trigger                     | Version          | Artifact  |
|---------------------|-----------------------------|------------------|-----------|
| **GitHub Packages** | Push to `main` or ``develop | `X.Y.Z-SNAPSHOT` | Automatic |
| **GitHub Packages** | Push tag `vX.Y.Z`           | `X.Y.Z`          | Automatic |
| **Maven Central**   | Manual (checkout tag)       | `X.Y.Z`          | Manual    |

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
  <groupId>com.macstab.oss.redis.laned</groupId>
  <artifactId>redis-laned-spring-boot-3-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

---

## Release Workflow

### Step 1: Create & Push Tag

```bash
# Ensure you're on main with latest changes
git checkout main
git pull

# Create release tag
git tag v1.0.0

# Push tag to GitHub
git push origin v1.0.0
```

**What happens automatically:**
1. GitHub Actions runs `.github/workflows/release.yml`
2. Extracts version from tag (`v1.0.0` → `1.0.0`)
3. Updates `build.gradle.kts` (removes SNAPSHOT)
4. Semantic-release generates `CHANGELOG.md`
5. Creates GitHub Release with release notes
6. Publishes `1.0.0` to **GitHub Packages**

**Result:**
- ✅ GitHub Release created
- ✅ CHANGELOG.md updated
- ✅ `1.0.0` available on GitHub Packages

### Step 2: Publish to Maven Central (Optional)

```bash
# Checkout the release tag
git checkout v1.0.0

# Publish to Maven Central staging
./gradlew clean build publish

# Expected output:
# > Task :redis-laned-core:signMavenPublication
# > Task :redis-laned-core:publishMavenPublicationToOSSRHRepository
# BUILD SUCCESSFUL
```

**Go to:** https://central.sonatype.com/

1. Navigate to **"Deployments"**
2. Find: `com.macstab.oss:redis-laned-core:1.0.0`
3. Review artifacts
4. Click **"Publish"**

**Wait:** 10-30 min for sync to Maven Central.

```bash
# Return to main
git checkout main
```

### Step 3: Bump Version for Next Development Cycle

```bash
# Edit build.gradle.kts
# Change: version = "1.0.0-SNAPSHOT"
# To:     version = "1.1.0-SNAPSHOT"

git add build.gradle.kts
git commit -m "chore: bump to 1.1.0-SNAPSHOT"
git push
```

**Next push to `main` will publish `1.1.0-SNAPSHOT`.**

---

## Commit Message Format (Conventional Commits)

Semantic-release analyzes commit messages to determine version bump:

| Type | Version Bump | Example |
|------|--------------|---------|
| `feat:` | MINOR (1.0.0 → 1.1.0) | `feat: add cluster support` |
| `fix:` | PATCH (1.0.0 → 1.0.1) | `fix: connection leak` |
| `perf:` | PATCH | `perf: optimize lane selection` |
| `docs:` | PATCH | `docs: update README` |
| `feat!:` | MAJOR (1.0.0 → 2.0.0) | `feat!: remove deprecated API` |
| `chore:` | NO RELEASE | `chore: update dependencies` |

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
# → Go to central.sonatype.com → Publish
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
- Verify `~/.gradle/gradle.properties` has `ossrhUsername` + `ossrhPassword`
- Verify GPG key configured: `signing.keyId`, `signing.password`, `signing.secretKeyRingFile`
- Check GPG key uploaded to keyserver: `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`

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
