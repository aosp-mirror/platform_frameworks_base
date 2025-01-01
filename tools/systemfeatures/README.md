# Build-time system feature support

## Overview

System features exposed from `PackageManager` are defined and aggregated as
`<feature>` xml attributes across various partitions, and are currently queried
at runtime through the framework. This directory contains tooling that supports
*build-time* queries of select system features, enabling optimizations
like code stripping and conditionally dependencies when so configured.

### System Feature Codegen

As not all system features can be fully specified or defined at build time (e.g.
updatable partitisions and apex modules can change/remove such features), we
use a conditional, build flag approach that allows a given device to customize
the subset of build-time defined system features that are immutable and cannot
be updated.

#### Build Flags

System features that can be fixed at build-time are declared in a common
location, `build/release/flag_declarations/`. These have the form
`RELEASE_SYSTEM_FEATURE_${X}`, where `${X}` corresponds to a feature defined in
`PackageManager`, e.g., `TELEVISION` or `WATCH`.

Build flag values can then be defined per device (or form factor), where such
values either indicate the existence/version of the system feature, or that the
feature is unavailable, e.g., for TV, we could define these build flag values:
```
name: "RELEASE_SYSTEM_FEATURE_TELEVISION"
value: {
  string_value: "0"  # Feature version = 0
}
```
```
name: "RELEASE_SYSTEM_FEATURE_WATCH"
value: {
  string_value: "UNAVAILABLE"
}
```

See also [SystemFeaturesGenerator](src/com/android/systemfeatures/SystemFeaturesGenerator.kt)
for more details.

#### Runtime Queries

Each declared build flag system feature is routed into codegen, generating a
getter API in the internal class, `com.android.internal.pm.RoSystemFeatures`:
```
class RoSystemFeatures {
    ...
    public static boolean hasFeatureX(Context context);
    ...
}
```
By default, these queries simply fall back to the usual
`PackageManager.hasSystemFeature(...)` runtime queries. However, if a device
defines these features via build flags, the generated code will add annotations
indicating fixed value for this query, and adjust the generated code to return
the value directly. This in turn enables build-time stripping and optimization.

> **_NOTE:_** Any build-time defined system features will also be implicitly
used to accelerate calls to `PackageManager.hasSystemFeature(...)` for the
feature, avoiding binder calls when possible.

#### Lint

A new `ErrorProne` rule is introduced to assist with migration and maintenance
of codegen APIs for build-time defined system features. This is defined in the
`systemfeatures-errorprone` build rule, which can be added to any Java target's
`plugins` list.

// TODO(b/203143243): Add plugin to key system targets after initial migration.

1) Add the plugin dependency to a given `${TARGET}`:
```
java_library {
    name: "${TARGET}",
    plugins: ["systemfeatures-errorprone"],
}
```
2) Run locally:
```
RUN_ERROR_PRONE=true m ${TARGET}
```
3) (Optional) Update the target rule to generate in-place patch files:
```
java_library {
    name: "${TARGET}",
    plugins: ["systemfeatures-errorprone"],
    // DO NOT SUBMIT: GENERATE IN-PLACE PATCH FILES
    errorprone: {
        javacflags: [
            "-XepPatchChecks:RoSystemFeaturesChecker",
            "-XepPatchLocation:IN_PLACE",
        ],
    }
    ...
}
```
```
RUN_ERROR_PRONE=true m ${TARGET}
```

See also [RoSystemFeaturesChecker](errorprone/java/com/android/systemfeatures/errorprone/RoSystemFeaturesChecker.java)
for more details.

> **_NOTE:_** Not all system feature queries or targets need or should be
migrated. Only system features that are explicitly declared with build flags,
and only targets that are built with the platform (i.e., not updatable), are
candidates for this linting and migration, e.g., SystemUI, System Server, etc...

// TODO(b/203143243): Wrap the in-place lint updates with a simple script for convenience.
