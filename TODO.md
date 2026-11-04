# TODO

Open work after 0.2.0. Remove an item in the commit that finishes it.

## Features and UI

- Icon overhaul: the launcher icon is still Password Store's. Design a HardenedPass icon
  (adaptive icon, monochrome layer for themed icons) and replace it everywhere it appears.
- Feedback after git operations: pull to sync, pull, push and sync from the menu show nothing
  on success. Show a short "done" snackbar, as hard reset and garbage collection do.
- Git dialogs to Compose: the git operations' View dialogs (`BaseGitActivity`, `GitOperation`,
  `CredentialFinder`, `BreakOutOfDetached`, `StoreActions`) and the `snackbar` helper still use
  Material Components (Views). Moving them lets that dependency go.

## Fixes

- Firefox release (`org.mozilla.firefox`) is not in the autofill save list, so saving from it
  does not work (fill and generate do). First check that it sends save requests.
- A stale cached passphrase (the key's passphrase changed) makes every autofill fail until the
  cache is cleared; the entry view asks for the passphrase instead. Do the same in autofill.
- A bad `.gpg-id` shows a Toast in the entry view and editor, where their other errors use a
  snackbar. Return the reason from `getPGPIdentifiers` and let each caller show it.
