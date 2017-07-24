# incident_helper

## How to build, deploy, unit test

For the first time, build the test and create an empty directly on device:

```
root$ make -j incident_helper_test && adb shell mkdir /data/nativetest64/incident_helper_test
```

Run the test on a device

```
root$ mmm -j frameworks/base/cmds/incident_helper && \
adb push $OUT/data/nativetest64/incident_helper_test/* /data/nativetest64/incident_helper_test/ && \
adb shell /data/nativetest64/incident_helper_test/incident_helper_test 2>/dev/null
```
