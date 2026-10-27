# Continuous Integration
Continuous integration is set up with GitHub Actions.  The workflows are defined in this repo under [/.github/workflows](../.github/workflows).

Workflows exist for:
 * Pull request - On pull request, static analysis and testing is performed.
 * Snapshot deployment - On push to a `release/**` branch, a snapshot release is deployed to Maven Central.  The workflow can also be invoked manually.  Concurrency limits are in place, to ensure that only one snapshot deployment can happen at a time.
 * Release deployment - Manually invoked workflow to deploy to Maven Central.  Concurrency limits are in place, to ensure that only one release deployment can happen at a time.
 
## Setup
When forking this repository, some vars/secrets need to be defined to set up new continuous integration builds.

The vars/secrets passed to GitHub Actions then map to Gradle properties set up within our build scripts.  Necessary secrets are documented at the top of each GitHub workflow yml file, as well as reiterated here.

### Pull request
No secrets are required. Library instrumentation tests run on a Gradle Managed Device emulator inside the runner. The workflow also runs weekly on `main` (and can be triggered manually) to catch toolchain or runner-image breakage between pull requests.

Note: Pull requests do not currently run darkside tests.  See #361.

### Snapshot deployment
* Secrets
    * `MAVEN_CENTRAL_USERNAME` — Username for Maven Central, which maps to the Gradle property `mavenCentralUsername`.
    * `MAVEN_CENTRAL_PASSWORD` — Password for Maven Central, which maps to the Gradle property `mavenCentralPassword`.
    * `MAVEN_SIGNING_KEY_ASCII` — Passwordless GPG key, ASCII-armored and then base64 encoded.  Maps to Gradle property `ZCASH_ASCII_GPG_KEY`.

Note: Snapshot deployments are signed too, so the GPG key is required.  Both deployment workflows skip the deploy job entirely if any of the three secrets above is empty, which is why a fork without them silently does nothing rather than failing.

Note: For documentation on the Gradle properties for Maven deployment, see [Gradle Maven Publish Plugin](https://github.com/vanniktech/gradle-maven-publish-plugin).

Note: Snapshot builds are configured with a Gradle property `IS_SNAPSHOT`.  The workflow automatically sets this property to true for snapshot deployments.  This will suffix the version with `-SNAPSHOT` and will upload to the snapshot repository.

### Release deployment
* Secrets
    * `MAVEN_CENTRAL_USERNAME` — Username for Maven Central, which maps to the Gradle property `mavenCentralUsername`.
    * `MAVEN_CENTRAL_PASSWORD` — Password for Maven Central, which maps to the Gradle property `mavenCentralPassword`.
    * `MAVEN_SIGNING_KEY_ASCII` — Passwordless GPG key, ASCII-armored and then base64 encoded.  Maps to Gradle property `ZCASH_ASCII_GPG_KEY`.

Note: For documentation on the Gradle properties for Maven deployment, see [Gradle Maven Publish Plugin](https://github.com/vanniktech/gradle-maven-publish-plugin).

Note: Snapshot builds are configured with a Gradle property `IS_SNAPSHOT`.  The workflow automatically sets this property to false for release deployments.

Note: The release workflow only uploads a deployment to the Central Portal; publishing it is a manual step.  See [PUBLISHING.md](PUBLISHING.md).
