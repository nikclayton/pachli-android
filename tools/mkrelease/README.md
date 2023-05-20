# mkrelease

## SYNOPSIS

Automates much of the work of creating a new release.

- [x] Updating version strings in build files
- [x] Copying the changelog content to multiple places
- [x] Creating the release branch and associated pull request
- [x] Merging approved changes from `develop` to `main`
- [x] Tagging the release
- [x] Pushing to Github
- [ ] Creating a new Github release
- [ ] Moving the release through Google Play
- [ ] Attaching the built APK to the Github release
- [ ] Making the release "Beta" on Google Play
- [ ] Creating the F-Droid merge request

A given release will contain one or more beta versions, and then a final release version.

Each of those version is considered a "GitHub release". This tool automates creating multiple GitHub releases, up to and including the final release of a version.

## QUICK START

If this is your first time running the command you should:

- Create your own GitHub fork of the Tusky repository
- Create a directory the tool can use as its permanent work area
  - This should **not** be part of your existing Tusky checkout
- Create a GitHub Personal Access Token (PAT), and store it in the environment variable `GITHUB_TOKEN`

## USAGE

### General

The command operates like `git` and similar. There is the `mkrelease` command which takes some options, and then different subcommands that move the process further along.

TODO: mermaid diagram of the process

Each command may need one or more options to work. If they are not provided on the command line they will be prompted for interactively.

### Getting started

You will need:

- A fork of the Tusky repository on GitHub
- TODO: Describe the access rights



### `init`

> Note: Run this from the root of a checked out Tusky repository.

Creates the initial release workspace and saves key metadata.

Normally you only need to run this once -- not once per release, but once in total.

```shell
.\runtools mkrelease --verbose init
  --work-root c:\users\nik\projects\t2
  --repository https://github.com/nikclayton/Tusky
```

`--work-root`: Directory where `mkrelease` will store all the files it needs to operate. This will include clones of the Tusky and Fedilab repositories.

`--repository`: URL of the Tusky fork that you have already created.

Key operations this performs include:

- Create or empty the `--work-root`
- Clone the repository fork
- Create `mkrelease.json` in the current directory.

### `start`

> Note: Run this from the same directory you ran `init`

Starts the process to create a new release. Saves metadata about the in-progress release.

```shell
.\runtools mkrelease --verbose start
  --major-minor major
  --issue-url https://www.example.com/1234
```

`--major-minor`: `major` if this release increments the major version number. `minor` if this release increments the minor version number.

`--issue-url`: URL to the GitHub issue to use to track the work being performed for this release.

Key operations this performs include:

- Determine the next full version number
- Create `release-spec.json` in the current directory.

### `beta`

> Note: Run this from the same directory you ran `start`

Creates a new beta version and releases it.

```shell
.\runtools mkrelease --verbose beta
```

Key operations this performs include:

- Create the branch to perform the release work
- Set the new Android `versionCode` and `versionName`
- Create a skeleton section for this release in `CHANGELOG.md`
- Allow you edit `CHANGELOG.md` to complete the release information
- Copy information from `CHANGELOG.md` to the correct `fastlane` change log
- Store the pull request information

### `release`

TODO
