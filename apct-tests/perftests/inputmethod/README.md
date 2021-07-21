## IMF performance tests

These tests are adaptation of Window Manager perf tests (apct-tests/perftests/windowmanager).

### Precondition
To reduce the variance of the test, if `perf-setup` (platform_testing/scripts/perf-setup)
is available, it is better to use the following instructions to lock CPU and GPU frequencies.
```
m perf-setup
PERF_SETUP_PATH=/data/local/tmp/perf-setup.sh
adb push $OUT/$PERF_SETUP_PATH $PERF_SETUP_PATH
adb shell chmod +x $PERF_SETUP_PATH
adb shell $PERF_SETUP_PATH
```

### Example to run
Use `atest`
```
atest ImePerfTests:ImePerfTest -- \
      --module-arg ImePerfTests:instrumentation-arg:profiling-iterations:=20

```
Note: `instrumentation-arg:kill-bg:=true` is already defined in the AndroidText.xml

Use `am instrument`
```
adb shell am instrument -w -r -e class android.inputmethod.ImePerfTest \
          -e listener android.inputmethod.ImePerfRunPrecondition \
          -e kill-bg true \
          com.android.perftests.inputmethod/androidx.test.runner.AndroidJUnitRunner
```
* `kill-bg` is optional.

Test arguments
 - kill-bg
   * boolean: Kill background process before running test.
 - profiling-iterations
   * int: Run the extra iterations with enabling method profiling.
 - profiling-sampling
   * int: The interval (0=trace each method, default is 10) of sample profiling in microseconds.
