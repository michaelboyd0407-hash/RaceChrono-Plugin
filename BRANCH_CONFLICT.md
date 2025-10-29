# Branch conflict explanation

The `work` branch introduces a brand new `RaceChrono_v1_5` module that contains
fresh copies of all plugin sources, including `src/main/java/com/mboyd/racechrono/RaceChrono.java`.
On the upstream branch, maintainers have already committed their own `RaceChrono_v1_5`
implementation. Because both branches create the same paths independently,
Git cannot fast-forward or auto-merge the histories; it reports a branch conflict
as soon as the pull request is opened.

To publish the PR you need to reconcile your files with the upstream version—either
by rebasing onto the latest upstream commit and resolving the conflicting files, or by
removing the duplicate module before pushing and re-applying your changes on top of the
maintainers' copy. Once the duplicate additions are resolved, the conflict will disappear.
