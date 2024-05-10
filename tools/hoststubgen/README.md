# HostStubGen

## Overview

HostStubGen is a tool built for ravenwood. It can read an Android framework jar file
(such as `framework-minus-apex.jar` or `framework-all.jar`) and
converts them, so that they can be used on the Ravenwood environment.

This directory contains the HostStubGen source code, tests and some library source files
used at runtime.

- HostStubGen itself is design to be agnostic to Android. It doesn't use any Android APIs
(hidden or not). But it may use Android specific knowledge -- e.g. as of now,
AndroidHeuristicsFilter has hardcoded heuristics to detect AIDL generated classes. 

- `test-tiny-framework/` contains basic tests that are agnostic to Android.

- More Android specific build files and code are stored in `frameworks/base/Ravenwood.bp`
  `frameworks/base/ravenwood`.

## Directories and files

- `hoststubgen/`
  Contains source code of the "hoststubgen" tool and relevant code

  - `src/`

    HostStubGen tool source code.

  - `annotations-src/` See `Android.bp`.
  - `helper-framework-buildtime-src/` See `Android.bp`.
  - `helper-framework-runtime-src/` See `Android.bp`.
  - `helper-runtime-src/` See `Android.bp`.

  - `test-tiny-framework/` See `README.md` in it.

  - `test-framework`
    This directory was used during the prototype phase, but now that we have real ravenwood tests,
    this directory is obsolete and should be deleted.


- `scripts`
  - `dump-jar.sh`

    A script to dump the content of `*.class` and `*.jar` files.

  - `run-all-tests.sh`

    Run all tests. Many tests may fail, but at least this should run til the end.
    (It should print `run-all-tests.sh finished` at the end)

## Build and run

### Building `HostStubGen` binary

```
m hoststubgen
```

### Run the tests

- Run all relevant tests and test scripts. All of it is expected to pass, and it'll print
  "Ready to submit" at the end.

  However, because some of the script it executes depend on internal file paths to Soong's
  intermediate directory, some of it might fail when something changes in the build system.

  We need proper build system integration to fix them.
```
$ ./scripts/run-all-tests.sh
```

- See also `README.md` in `test-*` directories.

## TODOs, etc

 - Make sure the parent's visibility is not smaller than the member's.

- @HostSideTestNativeSubstitutionClass should automatically add class-keep to the substitute class.
  (or at least check it.)

 - The `HostStubGenTest-framework-test-host-test-lib` jar somehow contain all ASM classes? Figure out where the dependency is coming from.

- At some point, we can move or delete all Android specific code to `frameworks/base/ravenwood`.
  - `helper-framework-*-src` should be moved to `frameworks/base/ravenwood`
  - `test-framework` should be deleted.