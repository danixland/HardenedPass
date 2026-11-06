# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Fixed

-   Release builds made by CI no longer run with debug logging and the strict VM policy on

## [0.3.2] - 2026-11-05

### Changed

-   About moved from Settings to the list's menu, and its links show that they open in the browser

### Fixed

-   The version, signing certificate and SSH public key are no longer hidden in the clipboard preview

## [0.3.1] - 2026-11-05

### Added

-   An about screen in Settings: version, documentation and project links, Ko-fi, the privacy statement, the signing certificate of the installed copy and the licenses

## [0.3.0] - 2026-11-05

### Changed

-   Android 12 or later is now required; older devices stay on 0.2.0
-   New HardenedPass launcher icon
-   The git dialogs and snackbars are now Compose; Material Components are gone
-   The dialog asking for a git password or SSH passphrase is hidden from screenshots and recents
-   A snackbar confirms a successful git operation
-   Updated the public suffix list

### Fixed

-   The clipboard is cleared after the 45 seconds Settings shows, not 60
-   Autofill save works with Firefox release and beta, and its window opens on top on Android 14+
-   Autofill asks again when a cached passphrase fails
-   An invalid `.gpg-id` shows as a snackbar in the entry view and the editor

## [0.2.0] - 2026-11-04

### Changed

-   New user interface built with Jetpack Compose and Material 3: the password list with breadcrumbs, search, pull to sync and multi-select; the entry view with an OTP countdown; the editor with a password generator sheet and folder picker; setup (welcome, git server and clone, SSH key, PGP keys); settings grouped by category; and the autofill picker, phishing warning and passphrase prompts
-   Setup merges the welcome screens; a new local store writes its `.gpg-id` with the key's long key ID, as `pass init` does
-   The "files first" and "type independent" sort orders were removed; a stored one becomes "folders first"
-   Autofill caches a PGP passphrase only when it was typed after a successful screen lock prompt, as the entry view does
-   The autofill picker and the phishing warning ignore taps made through another app's overlay, and "Re-enable Autofill" no longer sits under "Advanced information", so a double tap cannot re-enable it
-   Updated the public suffix list

### Fixed

-   Deleting the repository, an entry or a folder no longer follows symbolic links out of the store
-   "Clear saved HTTPS password" and "Clear saved SSH passphrase" now actually clear them
-   Proxy authentication read the user name from the wrong place and never worked; an invalid proxy port no longer crashes the app
-   Switches behind the screen lock are stored only after a successful prompt
-   SSH authentication no longer hangs after a screen lock prompt is closed, and sensor errors end the prompt
-   The app lock and the autofill passphrase prompt work on Android 14, where the system dismissed the prompt at once
-   Opening the app from a shortcut no longer starts a second copy of the main screen
-   The rebase conflict message points to the current settings screen, and the abort dialog names the branch it pushed
-   Turning autofill off in Settings shows the switch off at once
-   Other languages no longer call the app "Password Store"

## [0.1.5] - 2026-11-03

### Fixed

-   Clone and sync over SSH failed with "Don't know how to encode key" for servers with an Ed25519 host key (and with Ed25519 SSH keys) on Android 14 devices whose system crypto library was updated; Ed25519 keys now always come from Bouncy Castle
-   A typed entry or folder name such as `../store-evil/x` could pass the "inside the store" check, which compared paths as strings; it now compares whole path components

## [0.1.4] - 2026-11-02

### Changed

-   New entries write `username: name` with a space after the colon, as `pass` users and other clients expect
-   Revised the Italian translation: fixed mistranslated Git terms, the store called a "shop", a "/0" typo, and translated the strings that fell back to English
-   Autofill and biometric texts name HardenedPass instead of Password Store
-   The merge-failure message no longer shows a "TODO" to users
-   Removed the inactive "fill OTP from SMS" autofill code, which needed Google Play Services
-   Updated sshj to 0.41.2, which drops the unmaintained `net.i2p.crypto:eddsa` library; Ed25519 keys now go through Bouncy Castle. Existing generated Ed25519 keys keep working

## [0.1.3] - 2026-11-02

### Changed

-   Credentials, the passphrase cache and generated Ed25519 SSH keys are now encrypted with Android Keystore AES-GCM directly, replacing the deprecated androidx security-crypto library (and Tink). **Upgrading from 0.1.x deletes the old data once**: re-enter Git HTTPS and proxy credentials and SSH key passphrases, retype cached PGP passphrases, and regenerate a generated Ed25519 SSH key (hardware RSA/ECDSA keys and imported keys are kept). Password generator settings reset to defaults

## [0.1.2] - 2026-11-02

### Fixed

-   Saving an entry whose `.gpg-id` names a key the app does not have silently encrypted it to the remaining keys only, so the missing key's owner could no longer decrypt it. Saving now fails with an error naming the missing key; import that key (a public key is enough) to save
-   A failed encryption no longer writes an empty file over the entry and commits it
-   Commit messages name HardenedPass instead of Android Password Store, and the Italian "remove" message no longer calls the store a shop

## [0.1.1] - 2026-11-02

### Fixed

-   An SSH key generated with "Protect with screen lock credential" could only be unlocked with the PIN or password: unlocking it with a fingerprint passed the prompt, then failed with "Socket closed". Keys generated from now on accept a strong (Class 3) biometric too; regenerate an existing protected key to use your fingerprint with it
-   The unlock prompt no longer offers weak (Class 2) biometrics on Android 11 and later, since they cannot unlock Keystore keys

## [0.1.0] - 2026-11-02

First release of HardenedPass, a maintained fork of the archived Android Password Store.
It installs next to upstream's app (`app.passwordstore`) and never updates over it.
Also includes upstream's unreleased 2.0 work, listed in the next section.

### Changed

-   **BREAKING**: The app is now HardenedPass, with application ID `xyz.danix.hardenedpass`, signed with the fork's own release key
-   Removed Sentry crash reporting and every Google Play Services dependency; there is a single build with no proprietary code
-   Updated Bouncy Castle to 1.86 (fixes GHSA-574f-3g2m-x479, GHSA-9pwp-9qqc-pr26, GHSA-c3fc-8qff-9hwx, GHSA-qp49-qgx5-5m26), PGPainless to 1.7.8 and JGit to 5.13.5
-   Updated the build to Gradle 9, AGP 9 and Kotlin 2.4

### Fixed

-   Messages encrypted by GnuPG 2.3 or later (OCB/AEAD) can now be decrypted
-   Importing a key without an encryption subkey was accepted instead of rejected
-   Conflict recovery pushed to a branch named `conflicting-null-<time>` instead of the current branch
-   `DecryptActivity` is no longer exported, and decrypt paths from outside the app must resolve inside the store

## [Upstream unreleased 2.0 changes]

### Added

-   On Android 11, Autofill will use the new [inline autofill](https://developer.android.com/guide/topics/text/ime-autofill#configure-provider) UI that integrates Autofill results into your keyboard app.
-   Allow doing a merge instead of a rebase when pulling or syncing
-   Add support for manually providing TOTP parameters
-   Parse extra content as individual fields
-   Improve search result filtering logic
-   Allow pinning shortcuts directly to the launcher home screen
-   Another workaround for SteamGuard's non-standard OTP format
-   Allow importing QR code from images
-   Add the ability to run garbage collection on the internal Git repository
-   Introduce crash reporting backed by Sentry
-   TOTP field now shows the remaining time for which it is valid
-   Allow customizing the timeout for decrypted password screen
-   Allow toggling ASCII armored output

### Fixed

-   Pressing the back button in the navigation bar and the one in the toolbar behaved differently
-   App shortcuts would never update once the first 4 were set
-   Clipboard history now attempts to flush through 35 times rather than 20 to combat increased clipboard history item count in Samsung devices
-   .gpg-id file generated by APS did not work with pass CLI
-   All but the latest launcher shortcut would have an empty icon
-   When prompted to select a GPG key during onboarding, the app would crash if the user did not make a selection in OpenKeychain
-   Biometric authentication prompts no longer inexplicably dismiss when an incorrect biometric is entered

### Changed

-   **BREAKING**: The app's package name has been changed to `app.passwordstore` so users are aware that this is a new project with no compatibility guarantees with Password Store 1.x.y.
-   **BREAKING**: Introduce a new PGP backend powered by [PGPainless](https://github.com/pgpainless/pgpainless) which completely replaces OpenKeychain
-   **BREAKING**: Accessibility autofill has been removed completely due to being buggy, insecure and lacking in features. Upgrade to Android 8 or preferably later to gain access to our advanced Autofill implementation.
-   **BREAKING**: Support for stores outside the hidden app directory has been removed due to technical restrictions, see [this issue](https://github.com/Android-Password-Store/Android-Password-Store/issues/1849) for details.
-   **BREAKING**: The app's minimum supported Android version has been raised to Android Oreo (API level 26).
-   The settings UI has been completely re-done to dramatically improve discoverability and navigation for users
-   Using the `git://` protocol in the server URL now presents an explicit discouragement rather than a generic error
-   Encrypted data is no longer ASCII armored, bringing it in line with `pass`
-   Changing password generator parameters now automatically updates the password without needing to press the 'Generate' button again
-   The app UI was reskinned to match Google's Material You guidelines
-   Using HTTPS without authentication is now fully supported, and no longer asks for a username
-   Enabling 'Show hidden files and folders' no longer shows Git-related files and folders
-   XkPasswd password generator has been removed in favor of one backed by [Diceware](https://theworld.com/~reinhold/diceware.html)
-   The app no longer prompts for a branch during clone and instead always uses the default one. Use the hard reset option in Git utilities to switch to the desired branch after cloning.

## [1.13.5] - 2021-07-28

### Fixed

-   When prompted to select a GPG key during onboarding, the app would crash if the user did not make a selection in OpenKeychain
-   Certain apps had incorrect Autofill hints which would crash the app

## [1.13.4] - 2021-03-20

-  Fix support for ECDSA SSH keys and support AES-GCM
-  Fix a couple issues with Autofill

## [1.13.3] - 2021-03-06

### Fixed

-   Autofill now works much more reliably in Chrome 89 and later, including support for saving passwords if no accessibility service is enabled.
-   Editing a password allowed accidentally overwriting an existing one

## [1.13.2] - 2020-12-21

### Added

-   Invalid `.gpg-id` files can now be fixed automatically by deleting them and then trying to create a new password.
-   Suggest users to re-clone repository when it is deemed to be broken
-   A new browser support level is added for Chrome Canary and Chrome Dev which are shipping fixes developed by Password Store maintainer Fabian to improve the Autofill experience on Chromium browsers

### Changed

-   Synced localisations with Crowdin. This adds Galician and Italian translations while getting rid of incomplete Arabic, Chinese Simplified, Chinese Traditional, Czech, Japanese and Spanish.

### Fixed

-   Cancelling the Autofill "Generate password" action now correctly returns you to the original app.
-   If multiple username fields exist in the password, we now ensure the later ones are not dropped from extra content.
-   Icons in Autofill suggestions are no longer black on almost black in dark mode.
-   Decrypt screen would stay in memory infinitely, allowing passwords to be seen without re-auth
-   Git commits in the store would wrongly use the 'default' committer as opposed to the user's configured one
-   Connection attempts now use a reasonable 10 second timeout as opposed to the default of 30 seconds
-   A change to the remote host key for a server would prevent the user from being able to connect to it

## [1.13.1] - 2020-10-23

### Fixed

-   OpenKeychain authentication would fail with `LifecycleOwner com.zeapo.pwdstore.git.GitServerConfigActivity@f578da1 is attempting to register while current state is RESUMED. LifecycleOwners must call register before they are STARTED.`

### Added

-   Add support for domain-level autofill in DuckDuckGo's F-Droid builds.
-   Support gopass MIME secret encoding

### Changed

-   The newly added automatic synchronisation feature has been rolled back due to multiple issues with its implementation.

## [1.13.0] - 2020-10-22

### Fixed

-   Some classes of errors would be swallowed by an unhelpful 'Invalid remote: origin' message
-   Repositories created within APS would contain invalid `.gpg-id` files with no ability to fix them from the app
-   Button labels were invisible in Autofill phishing warning screen
-   Unsupported authentication modes would appear briefly in the server config screen

### Added

-   Add GPG key selection step to onboarding flow
-   Allow configuring an app-wide HTTP(S) proxy
-   Add option to automatically sync repository on app launch
-   Add a quickfix for invalid HTTPS URLs that contain a custom port

## [1.12.1] - 2020-10-13

### Fixed

-   Certain operations like folder creation with GPG keys would fail with `java.lang.IllegalStateException`.
-   ECDSA key exchanges failed resulting in users being unable to clone repositories.

## [1.12.0] - 2020-09-24

### Added

-   Allow sorting by recently used
-   Add [Bromite](https://www.bromite.org/), [Ungoogled Chromium](https://git.droidware.info/wchen342/ungoogled-chromium-android) and [Kiwi](https://kiwibrowser.com/) to supported browsers list for Autofill
-   Add ability to view the Git commit log
-   Allow generating ECDSA and ED25519 keys for SSH
-   Add support for multiple/fallback authentication methods for SSH
-   Add warning when the custom SSH port in a URL could potentially be ignored

### Changed

-   A descriptive error message is shown if no username is specified in the Git server settings
-   Remove explicit protocol choice from Git server settings, it is now inferred from your URL
-   'Show hidden folders' is now 'Show hidden files and folders'
-   Generated SSH keys are now stored in the Android Keystore if available, and encrypted at rest otherwise
-   Allow using device's screen lock credentials to secure generated SSH key
-   Update onboarding UI
-   Update translations

### Fixed

-   Git server protocol and authentication mode are only updated when explicitly saved
-   Remember HTTPS password during a sync operation
-   Unable to use show/hide password option for password/passphrase after first attempt was wrong
-   TOTP values shown might some times be stale and considered invalid by sites
-   Symlinks are no longer clobbered by the app (only available on Android 8 and above)
-   Workaround lack of SSH connection reuse capabilities on some Git hosts like Bitbucket

## [1.11.3] - 2020-08-27

### Fixed

-   Delete stored HTTPS password on connection errors (such as failed authentication)

## [1.11.2] - 2020-08-24

### Fixed

-   Saving a password after creating it fails to finish commit operation
-   HTTPS authentication did not prompt users for password

## [1.11.1] - 2020-08-21

### Fixed

-   App failed to start on Android 7 and below

## [1.11.0] - 2020-08-18

### Added

-   Allow changing the branch used for Git operations
-   Allow setting a subdirectory key when creating folders
-   Allow adding digits/symbols in XkPasswd generated passwords using a mask-like value (`dds` gives you two digits and a symbol, and so on)

### Changed

-   The Git repository URL can now be specified directly
-   Slightly reduce APK size
-   Always show the parent path in entries
-   Passwords will no longer be copied to the clipboard by default
-   Notify user if there was nothing to push

### Fixed

-   Allow creating nested directories directly
-   I keep saying this but for real: error message for wrong SSH/HTTPS password is properly fixed now
-   Fix crash when OpenKeychain is not installed
-   Clone operation won't leave user on an empty password list upon failure
-   Cloning a new repository to external storage wouldn't work
-   UI froze for some people when deleting existing files from the external directory

## [1.10.3] - 2020-07-30

### Fixed

-   Worked around a dependency bug that would crash the Autofill service when triggered on an OTP field

## [1.10.2] - 2020-07-30

### Fixed

-   Properly handle cases where files contain only TOTP secrets and no password
-   Correctly hide TOTP import button when TOTP secret/OTPAUTH URL is already present in extra content
-   SMS OTP Autofill no longer crashes when invoked and correctly asks for the required permission on first use

## [1.10.1] - 2020-07-23

### Fixed

-   Using long key IDs in .gpg-id no longer leads to a crash
-   Long key IDs and fingerprints are now correctly forwarded to OpenKeychain

### Added

-   Support for multiple GPG IDs in .gpg-id
-   Creating an entry in an empty store now lets you select keys to initialize .gpg-id with

## [1.10.0] - 2020-07-22

### Changed

-   A brand new icon to go with our biggest update ever!
-   Light theme is now a consistent white across the board with ample contrast
-   XkPassword generator is now easier to use with less configuration options
-   Edit screen now has better protection and guidance for invalid names
-   Improved biometric authentication UX on app start
-   Improved password list UI

### Fixed

-   Folder names that were very long did not look right
-   Error message for wrong SSH/HTTPS password now looks cleaner
-   Fix authentication failure with usernames that contain the `@` character
-   Text input boxes were illegible on dark theme
-   Top-level password names had inconsistent top margin making them look askew
-   Password Store no longer ignores the selected OpenKeychain key
-   Password export now happens in a separate process, preventing possible freezes

### Added

-   TOTP support is reintroduced by popular demand. HOTP continues to be unsupported and heavily discouraged.
-   Initial support for detecting and filling OTP fields with Autofill
-   OTP codes can be automatically filled from SMS (requires Android P+ and Google Play Services)
-   Importing TOTP secrets using QR codes
-   Support for ed25519/ECDSA SSH keys
-   Navigate into newly created folders and scroll to newly created passwords
-   Support per-directory keys
-   Full pt-BR localization

## [1.9.2] - 2020-06-30

### Fixed

-   App crashes upon launching the app for the first time

## [1.9.1] - 2020-06-28

### Fixed

-   Remember passphrase option did not work with old-style keys (generated either before 2019 or by passing `-m PEM` to new versions of OpenSSH)

### Added

-   Add GNU IceCatMobile to the list of supported browsers for Autofill

## [1.9.0] - 2020-06-21

### Fixed

-   'Draw over other apps' permission dialog opens when attempting to use Oreo Autofill
-   Old app shortcuts are now removed when the local repository is deleted

### Added

-   Completely revamped decypted password view
-   Add support for better, more secure Keyex's and MACs with a brand new SSH backend
-   Allow manually marking domains for subdomain-level association. This will allow you to keep separate passwords for `site1.example.com` and `site2.example.com` and have them show as such in Autofill.
-   Provide better messages for OpenKeychain errors
-   Rename passwords and categories

### Changed

-   **BREAKING**: Remove support for HOTP/TOTP secrets - Please use FIDO keys or a dedicated app like [Aegis](https://github.com/beemdevelopment/Aegis) or [andOTP](https://github.com/andOTP/andOTP)
-   Reduce Autofill false positives on username fields by removing "name" from list of heuristic terms
-   Reduced app size
-   Improve IME experience with server config screen
-   Removed edit password option from long-press menu.
-   Batch deletion now does not require manually confirming for each password
-   Better commit messages on password deletion

## [1.8.1] - 2020-05-24

### Fixed

-   Don't strip leading slash from repository paths

## [1.8.0] - 2020-05-23

### Added

-   Allow user to abort password move when it is replacing an existing file
-   Allow setting a default username for Autofill
-   Add no authentication mode for working with public repositories

### Changed

-   More UI related tweaks, changes and improvements
-   Improved error messages and internal logic for server configuration

### Fixed

-   Add the following fields to encrypted username detection: user, account, email, name, handle, id, identity.
-   Improved detection of broken or incomplete git repositories
-   Better UX flow for storage permissions

## [1.7.2] - 2020-04-29

### Added

-   Settings option to enable debug logging

### Changed

-   SSH Keygen UI was improved
-   Default key length for SSH Keygen is now 4096 bits
-   Settings items were rearranged and cleaned up
-   Autofill icons in dark mode are now more legible

### Fixed

-   Failure to detect if repository was not cloned which broke Git operations
-   Search results were inaccurate if root directory's name started with a dot (.)
-   Saving git username and email did not provide user-facing confirmation

## [1.7.1] - 2020-04-23

### Fixed

-   Autofill message does not show OK button when many browsers are installed
-   Autofill message does not get marked as shown when dismissed
-   App crashes when using type-independent sort
-   Storage permission not requested when using existing external repository

## [1.7.0] - 2020-04-21

### Added

-   Oreo Autofill support
-   Securely remember HTTPS password/SSH key passphrase

### Fixed

-   Text input box theming
-   Password repository held in non-hidden storage no longer fails
-   Remove ambiguous and confusing URL field in server config menu
    and heavily improve UI for ease of use.

## [1.6.0] - 2020-03-20

### Added

-   Copy implicit username (password filename) by long pressing
-   Create xkpasswd style passwords
-   Swipe on password list to synchronize repository

### Fixed

-   Resolve memory leaks on password decryption
-   Can't delete folders containing a password

## [1.5.0] - 2020-02-21

### Added

-   Fast scroller with alphabetic hints
-   UI button to create new folders
-   Option to directly start searching when opening the app
-   Option to always search from root folder regardless of the currently open folder

### Changed

-   Logging is now enabled in release builds
-   Searching now shows folders as well as the passwords inside them

### Fixed

-   OpenKeychain errors cause app crash

## [1.4.0] - 2020-01-24

### Added

-   Add save-and-copy button
-   Dark theme
-   Setting to save OpenKeychain auth id
-   Add number of passwords to folders

### Changed

-   Updated UI design and iconograph
-   Biometric authentication
-   Use new OpenKeychain integration library

### Fixed

-   Snackbars showing behind keyboards

## [1.3.2] - 2018-12-23

### Changed

-   Improve French translation.

### Fixed

-   Extra field is multi-line.

## [1.3.1] - 2018-10-18

### Fixed

-   Fix default sort order bug.

## [1.3.0] - 2018-10-16

### Added

-   Allow app to be installed on external media (SD card).
-   Change password sort order.
-   Display HOTP code if present.
-   Open search view on keyboard press.

### Changed

-   Use adaptive icon.
-   Password entry is more secure.
-   Clean paths on password list view.
-   Improve Chinese translation.
-   Don't show hidden files and directories.

### Fixed

-   Fix clipboard clearing.
-   Wrap long passwords.

## 1.2.0.75 - 2018-05-31

### Added

-   Add Arabic translation.
-   Warn user that remembering SSH passphrase is currently insecure.

### Changed

-   Update Japanese assets.

### Fixed

-   Fix elements overlapping.

[Unreleased]: https://github.com/android-password-store/Android-Password-Store/compare/v1.13.5...release-1.13

[1.13.5]: https://github.com/android-password-store/Android-Password-Store/compare/v1.13.4...v1.13.5

[1.13.4]: https://github.com/android-password-store/Android-Password-Store/compare/v1.13.3...v1.13.4

[1.13.3]: https://github.com/android-password-store/Android-Password-Store/compare/v1.13.2...v1.13.3

[1.13.2]: https://github.com/android-password-store/Android-Password-Store/compare/v1.13.1...v1.13.2

[1.13.1]: https://github.com/android-password-store/Android-Password-Store/compare/v1.13.0...v1.13.1

[1.13.0]: https://github.com/android-password-store/Android-Password-Store/compare/v1.12.1...v1.13.0

[1.12.1]: https://github.com/android-password-store/Android-Password-Store/compare/v1.12.0...v1.12.1

[1.12.0]: https://github.com/android-password-store/Android-Password-Store/compare/v1.11.3...v1.12.0

[1.11.3]: https://github.com/android-password-store/Android-Password-Store/compare/v1.11.2...v1.11.3

[1.11.2]: https://github.com/android-password-store/Android-Password-Store/compare/v1.11.1...v1.11.2

[1.11.1]: https://github.com/android-password-store/Android-Password-Store/compare/v1.11.0...v1.11.1

[1.11.0]: https://github.com/android-password-store/Android-Password-Store/compare/v1.10.3...v1.11.0

[1.10.3]: https://github.com/android-password-store/Android-Password-Store/compare/v1.10.2...v1.10.3

[1.10.2]: https://github.com/android-password-store/Android-Password-Store/compare/v1.10.1...v1.10.2

[1.10.1]: https://github.com/android-password-store/Android-Password-Store/compare/v1.10.0...v1.10.1

[1.10.0]: https://github.com/android-password-store/Android-Password-Store/compare/v1.9.2...v1.10.0

[1.9.2]: https://github.com/android-password-store/Android-Password-Store/compare/v1.9.1...v1.9.2

[1.9.1]: https://github.com/android-password-store/Android-Password-Store/compare/v1.9.0...v1.9.1

[1.9.0]: https://github.com/android-password-store/Android-Password-Store/compare/v1.8.1...v1.9.0

[1.8.1]: https://github.com/android-password-store/Android-Password-Store/compare/v1.8.0..v1.8.1

[1.8.0]: https://github.com/android-password-store/Android-Password-Store/compare/v1.7.2..v1.8.0

[1.7.2]: https://github.com/android-password-store/Android-Password-Store/compare/v1.7.1..v1.7.2

[1.7.1]: https://github.com/android-password-store/Android-Password-Store/compare/v1.7.0..v1.7.1

[1.7.0]: https://github.com/android-password-store/Android-Password-Store/compare/v1.6.0..v1.7.0

[1.6.0]: https://github.com/android-password-store/Android-Password-Store/compare/v1.5.0..v1.6.0

[1.5.0]: https://github.com/android-password-store/Android-Password-Store/compare/v1.4.0...v1.5.0

[1.4.0]: https://github.com/android-password-store/Android-Password-Store/compare/v1.3.0...v1.4.0

[1.3.2]: https://github.com/android-password-store/Android-Password-Store/compare/v1.3.1...v1.3.2

[1.3.1]: https://github.com/android-password-store/Android-Password-Store/compare/v1.3.0...v1.3.1

[1.3.0]: https://github.com/android-password-store/Android-Password-Store/compare/v1.2.0.75...v1.3.0
