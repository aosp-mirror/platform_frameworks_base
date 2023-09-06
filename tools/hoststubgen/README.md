# HostStubGen

## Overview

This directory contains tools / sample code / investigation for host side test support.


## Directories and files

- `hoststubgen/`
  Contains source code of the "hoststubgen" tool and relevant code

  - `framework-policy-override.txt`
    This file contains "policy overrides", which allows to control what goes to stub/impl without
    having to touch the target java files. This allows quicker iteration, because you can skip
    having to rebuild framework.jar.

  - `src/`

    HostStubGen tool source code.

  - `annotations-src/` See `Android.bp`.
  - `helper-framework-buildtime-src/` See `Android.bp`.
  - `helper-framework-runtime-src/` See `Android.bp`.
  - `helper-runtime-src/` See `Android.bp`.

  - `test-tiny-framework/` See `README.md` in it.

  - `test-framework` See `README.md` in it.

- `scripts`
  - `run-host-test.sh`

    Run a host side test. Use it instead of `atest` for now. (`atest` works "mostly" but it has
    problems.)

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

- Run all relevant tests and test scripts. Some of thests are still expected to fail,
  but this should print "finished, with no unexpected failures" at the end.

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
