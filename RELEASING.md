# Building and releasing

LocalChatTranslate uses Java 25 and bundles a Windows x64 Vulkan runtime. The supported Minecraft targets and their dependencies are defined in [gradle/minecraft-versions.json](gradle/minecraft-versions.json). Minecraft 26.1.2 remains the default target.

Build a specific target after preparing the runtime described in [README.md](README.md):

```powershell
.\gradlew.bat build -Pminecraft_version=26.1
.\gradlew.bat build -Pminecraft_version=26.1.2
.\gradlew.bat build -Pminecraft_version=26.2
```

The JAR filename and mod metadata include both versions, for example `localchattranslate-0.5.0-alpha.2+26.2.jar`. Changing `minecraft_version` selects the matching dependencies and GUI compatibility sources automatically. The game tests use the same target selection:

```powershell
.\gradlew.bat runClientGameTest -Pminecraft_version=26.2
```

Game tests require Windows, a working Vulkan driver, and the local model described in the README. The GitHub Build workflow runs the standalone behavior, target-language, and model-download checks for every target on pull requests and pushes to main. It does not run Minecraft or the bundled Windows runtime on its Linux runners.

## Configure CurseForge

1. Create an author upload token under **API Tokens** at [CurseForge Authors](https://authors.curseforge.com). Use an upload token, not a CurseForge Core API key. See the [official Upload API documentation](https://support.curseforge.com/support/solutions/articles/9000197321-curseforge-api).
2. In the GitHub repository, open **Settings → Secrets and variables → Actions → Secrets**, and add `CURSEFORGE_API_TOKEN` as a repository secret.
3. On the **Variables** tab, add `CURSEFORGE_PROJECT_ID` with the numeric ID from the CurseForge project overview URL. This is a repository variable, not a secret.

Without the project ID, automatic CurseForge publishing is skipped. If the ID is configured but the token is missing, publishing fails with a setup message. Store the token only in GitHub Secrets.

## Publish a release

After merging and verifying the release commit, push a new mod version tag without a Minecraft suffix. For example:

```powershell
git tag v0.5.0-alpha.2
git push origin v0.5.0-alpha.2
```

The tag determines the mod version for every target. The Build workflow verifies and builds all targets, creates a GitHub release with generated release notes, and then calls the CurseForge workflow if configured. It calls the workflow directly because a release created with GitHub's workflow token does not trigger another ordinary release-event workflow.

Each CurseForge file receives its exact Minecraft version, the Fabric and Client tags, a required Fabric API dependency, and an optional Mod Menu dependency. The GitHub release body becomes the changelog. Tags containing `-alpha` publish as alpha; other prerelease suffixes publish as beta; versions without a suffix publish as release. CurseForge moderation may delay public availability.

## Validate or retry an existing release

Open **Actions → Publish to CurseForge → Run workflow** after the workflow is on the default branch. Enter an existing GitHub release tag. Leave `dry_run` enabled first: it validates the JARs and displays upload metadata without credentials or upload requests. Disable it to upload. The optional `minecraft` input selects one version; leaving it empty selects every release JAR.

```powershell
gh workflow run curseforge.yml -f tag=v0.5.0-alpha.2 -f dry_run=true
```

Each successful upload prints its new CurseForge file ID. There are no automatic upload retries. Check the project's existing files before rerunning after a partial failure; use `minecraft` to select only a missing version. A network failure can leave the upload result unknown.

Local upload-tool checks use a temporary HTTP server, including multipart authentication, failed responses, redirect blocking, and dry-run behavior. Live uploads still require validation with the project's credentials.
