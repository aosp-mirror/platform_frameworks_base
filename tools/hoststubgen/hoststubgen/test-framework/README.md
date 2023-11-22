# HostStubGen: real framework test

This directory contains tests against the actual framework.jar code. The tests were
copied from somewhere else in the android tree. We use this directory to quickly run existing
tests.

## How to run

- With `atest`. This is the proper way to run it, but it may fail due to atest's known problems.

  See the top level README.md on why `--no-bazel-mode` is needed (for now).

```
$ atest --no-bazel-mode HostStubGenTest-framework-test-host-test
```

- Advanced option: `run-test-without-atest.sh` runs the test without using `atest` or `run-ravenwood-test`

```
$ ./run-test-without-atest.sh
```