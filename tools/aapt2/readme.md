# Android Asset Packaging Tool 2.0 (AAPT2) release notes

## Version 2.13
### `aapt2 optimize ...`
- aapt2 optimize can now split a binary APK with the same --split parameters as the link
  phase.

## Version 2.12
### `aapt2 optimize ...`
- aapt2 optimize now understands map (complex) values under the type `id`. It ignores their
  contents and interprets them as a sentinel `id` type. This was added to support existing
  apps that build with their `id` types as map values.
  AAPT and AAPT2 always generate a simple value for the type `ID`, so it is unclear how some
  these apps are encoded.

## Version 2.11
### `aapt2 link ...`
- Adds the ability to specify assets directories with the -A parameter. Assets work just like
  assets in the original AAPT. It is not recommended to package assets with aapt2, however,
  since the resulting APK is post-processed by other tools anyways. Assets do not get processed
  by AAPT2, just copied, so incremental building gets slower if they are included early on.

## Version 2.10
### `aapt2 link ...`
- Add ability to specify package ID to compile with for regular apps (not shared or static libs).
  This package ID is limited to the range 0x7f-0xff inclusive. Specified with the --package-id
  flag.
- Fixed issue with <plurals> resources being stripped for locales and other configuration.
- Fixed issue with escaping strings in XML resources.

## Version 2.9
### `aapt2 link ...`
- Added sparse resource type encoding, which encodes resource entries that are sparse with
  a binary search tree representation. Only available when minSdkVersion >= API O or resource
  qualifier of resource types is >= v26 (or whatever API level O becomes). Enabled with
  `--enable-sparse-encoding` flag.
### `aapt2 optimize ...`
- Adds an optimization pass that supports:
    - stripping out any density assets that do not match the `--target-densities` list of
      densities.
    - resource deduping when the resources are dominated and identical (already happens during
      `link` phase but this covers apps built with `aapt`).
    - new sparse resource type encoding with the `--enable-sparse-encoding` flag if possible
      (minSdkVersion >= O or resource qualifier >= v26).

## Version 2.8
### `aapt2 link ...`
- Adds shared library support. Build a shared library with the `--shared-lib` flag.
  Build a client of a shared library by simply including it via `-I`.

## Version 2.7
### `aapt2 compile ...`
- Fixes bug where psuedolocalization auto-translated strings marked 'translateable="false"'.

## Version 2.6
### `aapt2`
- Support legacy `configVarying` resource type.
- Support `<bag>` tag and treat as `<style>` regardless of type.
- Add `<feature-group>` manifest tag verification.
- Add `<meta-data>` tag support to `<instrumentation>`.

## Version 2.5
### `aapt2 link ...`
- Transition XML versioning: Adds a new flag `--no-version-transitions` to disable automatic
  versioning of Transition XML resources.

## Version 2.4
### `aapt2 link ...`
- Supports `<meta-data>` tags in `<manifest>`.

## Version 2.3
### `aapt2`
- Support new `font` resource type.

## Version 2.2
### `aapt2 compile ...`
- Added support for inline complex XML resources. See
  https://developer.android.com/guide/topics/resources/complex-xml-resources.html
### `aapt link ...`
- Duplicate resource filtering: removes duplicate resources in dominated configurations
  that are always identical when selected at runtime. This can be disabled with
  `--no-resource-deduping`.

## Version 2.1
### `aapt2 link ...`
- Configuration Split APK support: supports splitting resources that match a set of
  configurations to a separate APK which can be loaded alongside the base APK on
  API 21+ devices. This is done using the flag
  `--split path/to/split.apk:<config1>[,<config2>,...]`.
- SDK version resource filtering: Resources with an SDK version qualifier that is unreachable
  at runtime due to the minimum SDK level declared by the AndroidManifest.xml are stripped.

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
