# Android Asset Packaging Tool 2.0 (AAPT2) release notes

## Version 2.0
### `aapt2 compile ...`
- Pseudo-localization: generates pseudolocalized versions of default strings when the
  `--pseudo-localize` option is specified.
- Legacy mode: treats some class of errors as warnings in order to be more compatible
  with AAPT when `--legacy` is specified.
- Compile directory: treats the input file as a directory when `--dir` is
  specified. This will emit a zip of compiled files, one for each file in the directory.
  The directory must follow the Android resource directory structure
  (res/values-[qualifiers]/file.ext).

### `aapt2 link ...`
- Automatic attribute versioning: adds version qualifiers to resources that use attributes
  introduced in a later SDK level. This can be disabled with `--no-auto-version`.
- Min SDK resource filtering: removes resources that can't possibly be selected at runtime due
  to the application's minimum supported SDK level.
