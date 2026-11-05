# TODO

Open work after 0.2.0. Remove an item in the commit that finishes it.

## Features and UI

- Icon overhaul: the launcher icon is still Password Store's. Design a HardenedPass icon
  (adaptive icon, monochrome layer for themed icons) and replace it everywhere it appears.
- Git dialogs to Compose: the git operations' View dialogs (`BaseGitActivity`, `GitOperation`,
  `CredentialFinder`, `BreakOutOfDetached`, `StoreActions`) and the `snackbar` helper still use
  Material Components (Views). Moving them lets that dependency go.
