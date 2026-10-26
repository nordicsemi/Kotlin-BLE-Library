# Releasing Kotlin BLE Library

A release is made by the **Release** GitHub Action. It generates the documentation, creates the tag,
and starts the deployment to Maven Central. The version of the release is the name of the tag, e.g.
`2.0.0-beta08`.

## How to release

1. Merge everything that should be released to the release branch (`version/2.0`) and make sure
   the **Unit tests** workflow passed on the last pull request.
2. Open **Actions → Release → Run workflow**.
3. Choose the release branch in **Use workflow from**, e.g. `version/2.0`.
4. Enter the **version**, e.g. `2.0.0-beta08`, and run the workflow.

The workflow does the following:

1. Checks that it runs on a branch, that the version looks like `2.0.0` or `2.0.0-beta08`, and that
   the tag does not exist yet.
2. Runs the unit tests (`./gradlew test jvmTest`). The merge commit of a pull request was not
   tested by the **Unit tests** workflow, as that one runs on pull requests.
3. Generates the documentation with `./gradlew dokkaGenerate` for the given version.
   It is written to `docs/html`, which is published by GitHub Pages.
4. Commits the documentation to the branch as `Docs for <version>`.
5. Creates the tag `<version>` on that commit and pushes it.
6. Starts the **Deploy to Nexus** workflow on the tag. It publishes all the libraries and releases
   the staging repository.

The tag has to be pushed by the workflow, and the deployment started explicitly: a tag pushed using
the default `GITHUB_TOKEN` does not trigger other workflows.

After the workflow finished:

* check that the **Deploy to Nexus** run succeeded,
* the artifacts appear on [Maven Central](https://central.sonatype.com/search?q=no.nordicsemi.kotlin.ble)
  within a few minutes to an hour,
* create a release on GitHub from the tag, with the release notes.

## How the version is set

The Nordic Gradle plugins use the version name from (in this order):

1. the `releaseVersion` Gradle property, e.g. `-PreleaseVersion=2.0.0-beta08`,
2. the `RELEASE_VERSION` environment variable,
3. the latest git tag,
4. `0.0.0` if none of the above is available.

The release workflows set `RELEASE_VERSION`, so they do not need git tags or history.
The **Unit tests** workflow builds without a tag, and so the version is `0.0.0`.
See the [Nordic Gradle Plugins](https://github.com/nordicsemi/Nordic-Gradle-Plugins/blob/main/PLUGINS.md)
documentation for the details.

## Releasing manually

If the workflow can not be used, do the same steps locally. The GPG key and the Sonatype
credentials are needed to publish: the environment variables `GPG_SIGNING_KEY`, `GPG_PASSWORD`,
`OSSR_USERNAME`, `OSSR_PASSWORD` and `SONATYPE_STAGING_PROFILE_ID`, and the `sec.gpg` file
(see `.github/workflows/deploy-to-nexus.yml`).

```bash
# 1. Generate the documentation, and commit it.
./gradlew dokkaGenerate -PreleaseVersion=2.0.0-beta08
git add docs
git commit -m "Docs for 2.0.0-beta08"

# 2. Tag the commit, and push both. Pushing the tag starts the "Deploy to Nexus" workflow.
git tag 2.0.0-beta08
git push origin version/2.0 2.0.0-beta08
```

To publish from the computer instead of the workflow:

```bash
./gradlew publish -PreleaseVersion=2.0.0-beta08
./gradlew releaseStagingRepositories -PreleaseVersion=2.0.0-beta08
```

## Troubleshooting

* **The tag already exists.** The version was released, or the workflow failed after it created
  the tag. Delete the tag if nothing was published (`git push origin :refs/tags/<version>`), or use
  a new version.
* **The push of the documentation was rejected.** The branch changed during the release. Run the
  workflow again.
* **The deployment failed.** Run **Deploy to Nexus** again: **Use workflow from** the tag.
  It refuses to run on a branch.
* **The documentation has a wrong version.** Generate it again with
  `./gradlew dokkaGenerate -PreleaseVersion=<version>`. Without the property, and without a tag,
  the version is `0.0.0`.
