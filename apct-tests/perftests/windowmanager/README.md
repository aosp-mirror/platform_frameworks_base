## Window manager performance tests

### Precondition
To reduce the variance of the test, if `perf-setup.sh` (platform_testing/scripts/perf-setup)
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
atest WmPerfTests:RelayoutPerfTest -- \
      --module-arg WmPerfTests:instrumentation-arg:kill-bg:=true
```
Use `am instrument`
```
adb shell am instrument -w -r -e class android.wm.RelayoutPerfTest \
          -e listener android.wm.WmPerfRunListener \
          -e kill-bg true \
          com.android.perftests.wm/androidx.test.runner.AndroidJUnitRunner
```
* `kill-bg` is optional.

Test arguments
 - kill-bg
   * boolean: Kill background process before running test.
 - profiling-iterations
   * int: Run the extra iterations with enabling method profiling.
 - profiling-sampling
   * int: The interval (0=trace each method, default is 10) of sample profiling in microseconds.
