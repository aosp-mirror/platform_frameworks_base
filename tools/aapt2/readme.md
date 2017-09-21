# Android Asset Packaging Tool 2.0 (AAPT2) release notes

## Version 2.19
- Added navigation resource type.
- Fixed issue with resource deduplication. (bug 64397629)
- Added a daemon mode for issuing commands. This is invoked with `aapt2 daemon`.
  Command line arguments are separated by newlines, with an empty line signalling the
  end of a command. Sending `EOF (Ctrl+D)` to the daemon will exit.
- Fixed an issue where multiple permissions defined in AndroidManifest.xml would generate
  conflicting definitions for the same Java constant in Manifest.java. Changed the implementation
  to match that of `aapt`, which will take the last definition as the sole definition.
  A warning is logged if such a scenario occurs. (bug 64472942)
- Made improvements to handling of paths on Windows. This should resolve a lot of issues with
  Unicode paths. (bug 62336414, 63830502)

## Version 2.18
### `aapt2 ...`
- Fixed issue where enum values were interpreted as integers and range checked. (bug 62358540)
- Fixed issue where ints and floats with trailing whitespace would not be parsed. (bug 62902869)
- Fixed issue where `--custom-package` was not honored when writing Manifest.java. (bug 62826426)
- Add `<key-sets>` and its nested tags to the allowed set of XML tags in AndroidManifest.xml.
  (bug 62839863)
- Fixed issue where Java classes referenced from fragments and menus were not added to
  the set of Proguard keep rules. (bug 62216174)
- Fixed issue where escaped unicode characters would generate malformed UTF-8. (bug 62839202)
- Fixed issue where apostrophes or quotes used in XML attribute values were ignored.
  (bug 62840406, 62840718)

## Version 2.17
### `aapt2 ...`
- Fixed issue where symlinks would not be followed when compiling PNGs. (bug 62144459)
- Fixed issue where overlays that declared `<add-resource>` did not compile. (bug 38355988)
- Fixed issue where `%n` in a string resource was interpreted as a format argument. (bug 37132275)
- Allow empty resources to compile, giving them a value of `""` or `@null`, depending on the
  accepted formats. (bug 38425050)
- Resources declared via `<item>` with no format attribute were changed to accept all
  resource types. (bug 62260121)
- Allow `<layout>` element under `<activity>` in AndroidManifest.xml. (bug 62189611)
- Fix issue where `--no-version-vector` did not apply to `pathInterpolator` and `objectAnimator`.
  (bug 62211148)
- Fix issue where overlaid `<style>` would not be merged, and would replace the original resource
  instead. This fix brings behavior in-line with AAPT. (bug 38355988)

## Version 2.16
### `aapt2 link ...`
- Versioning of XML files is more intelligent, using a small set of rules to degrade
  specific newer attributes to backwards compatible versions of them.
  Ex: `android:paddingHorizontal` degrades to `android:paddingLeft` and `android:paddingRight`.

## Version 2.15
### `aapt2 compile ...`
- Add `--no-crunch` option to avoid processing PNGs during the compile phase. Note that this
  shouldn't be used as a performance optimization, as once the PNG is processed, its result is
  cached for incremental linking. This should only be used if the developer has specially
  pre-processed the PNG and wants it byte-for-byte identical to the input.
  NOTE: 9-patches will not be processed correctly with this flag set.

## Version 2.14
### `aapt2 link ...`
- If an app is building with a minSdkVersion < 26 and a --package-id XX where XX > 7F, aapt2
  will automatically convert any 'id' resource references from the resource ID 0xPPTTEEEE to
  0x7FPPEEEE.
- This is done to workaround a bug in previous versions of the platform that would validate
  a resource ID by assuming it is larger than 0. In Java, a resource ID with package ID greater
  than 0x7F is interpreted as a negative number, causing valid feature split IDs like 0x80010000
  to fail the check.
- '@id/foo' resources are just sentinel values and do not actually need to resolve to anything.
  Rewriting these resource IDs to use the package ID 7F while maintaining their definitions under
  the original package ID is safe. Collisions against the base APK are checked to ensure these
  rewritten IDs to not overlap with the base.

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
