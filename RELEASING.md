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

The workflow runs in five stages. Each one is a job that depends on the previous one, so the graph of
the run shows which stage failed:

1. **Check the version.** Checks that it runs on a branch, that the version looks like `2.0.0` or
   `2.0.0-beta08`, and that the tag does not exist yet.
2. **Test.** Runs the unit tests (`./gradlew test jvmTest`). The merge commit of a pull request was
   not tested by the **Unit tests** workflow, as that one runs on pull requests.
3. **Generate documentation.** Runs `./gradlew dokkaGenerate` for the given version, and commits the
   result to the branch as `Docs for <version>`. The documentation is written to `docs/html`, which
   is published by GitHub Pages.
4. **Tag.** Creates the tag `<version>` on the commit with the documentation, and pushes it.
5. **Publish to Maven Central.** Builds the tag, publishes all the libraries and releases the
   staging repository.

Pushing a tag does not start any workflow. Publishing is a stage of the **Release** workflow, so the
only way to release is to run it. Creating a GitHub Release does not publish anything either.

After the workflow finished:

* check that the last stage, **Publish to Maven Central**, succeeded,
* the artifacts appear on [Maven Central](https://central.sonatype.com/search?q=no.nordicsemi.kotlin.ble)
  within a few minutes to an hour,
* create a release on GitHub from the existing tag, with the release notes.

## How the version is set

The Nordic Gradle plugins use the version name from (in this order):

1. the `releaseVersion` Gradle property, e.g. `-PreleaseVersion=2.0.0-beta08`,
2. the `RELEASE_VERSION` environment variable,
3. the latest git tag,
4. `0.0.0` if none of the above is available.

The **Release** workflow sets `RELEASE_VERSION`, so it does not need git tags or history.
The **Unit tests** workflow builds without a tag, and so the version is `0.0.0`.
See the [Nordic Gradle Plugins](https://github.com/nordicsemi/Nordic-Gradle-Plugins/blob/main/PLUGINS.md)
documentation for the details.

## Releasing manually

If the workflow can not be used, do the same stages locally. The GPG key and the Sonatype
credentials are needed to publish: the environment variables `GPG_SIGNING_KEY`, `GPG_PASSWORD`,
`OSSR_USERNAME`, `OSSR_PASSWORD` and `SONATYPE_STAGING_PROFILE_ID`, and the `sec.gpg` file
(see the `publish` job in `.github/workflows/release.yml`).

```bash
# 2. Test.
./gradlew test jvmTest --continue

# 3. Generate the documentation, and commit it.
./gradlew dokkaGenerate -PreleaseVersion=2.0.0-beta08
git add docs
git commit -m "Docs for 2.0.0-beta08"

# 4. Tag the commit, and push both.
git tag 2.0.0-beta08
git push origin version/2.0 2.0.0-beta08

# 5. Publish to Maven Central.
./gradlew publish -PreleaseVersion=2.0.0-beta08
./gradlew releaseStagingRepositories -PreleaseVersion=2.0.0-beta08
```

## Troubleshooting

* **A stage failed.** Open the run, fix the cause (e.g. a failing test needs a pull request first),
  and use **Re-run failed jobs**. The stages that succeeded are not repeated: the tag of the
  version, and the documentation commit, stay. Only the failed stage and the following ones run.
  If the cause needs a change in the code, it is simpler to delete the tag and the documentation
  commit, if they were created, and run the workflow again.
* **Check the version: the tag already exists.** The version was released, or a previous run
  failed after it created the tag. Delete the tag if nothing was published
  (`git push origin :refs/tags/<version>`), or use a new version.
* **Generate documentation: the push was rejected.** The branch changed during the release. Run the
  workflow again.
* **Publish to Maven Central failed.** Use **Re-run failed jobs**. The tag is built again.
* **The documentation has a wrong version.** Generate it again with
  `./gradlew dokkaGenerate -PreleaseVersion=<version>`. Without the property, and without a tag,
  the version is `0.0.0`.
