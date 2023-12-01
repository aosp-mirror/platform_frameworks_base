# HostStubGen: (obsolete) real framework test

This directory contains tests against the actual framework.jar code. The tests were
copied from somewhere else in the android tree. We use this directory to quickly run existing
tests.

This directory was used during the prototype phase, but now that we have real ravenwood tests,
this directory is obsolete and should be deleted.

## How to run

- With `atest`. This is the proper way to run it, but it may fail due to atest's known problems.

```
$ atest HostStubGenTest-framework-all-test-host-test
```

- Advanced option: `run-test-without-atest.sh` runs the test without using `atest`

```
$ ./run-test-without-atest.sh
```