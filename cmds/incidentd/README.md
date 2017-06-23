# incidentd

## How to build, deploy, unit test

For the first time, build the test and create an empty directly on device:

```
root$ make -j incidentd_test && adb shell mkdir /data/nativetest64/incidentd_test
```

Run the test on a device

```
root$ mmm -j frameworks/base/cmds/incidentd && \
adb push $OUT/data/nativetest64/incidentd_test/* /data/nativetest64/incidentd_test/ && \
adb shell /data/nativetest64/incidentd_test/incidentd_test 2>/dev/null
```
