# incidentd

## How to build, deploy, unit test

For the first time, build the test and create an empty directly on device:

```
root$ make -j incidentd_test && adb shell mkdir /data/nativetest/incidentd_test
```

Run the test on a device manually

```
root$ mmm -j frameworks/base/cmds/incidentd && \
adb push $OUT/data/nativetest/incidentd_test/* /data/nativetest/ && \
adb shell /data/nativetest/incidentd_test 2>/dev/null
```

Run the test via AndroidTest.xml

```
root$ atest incidentd_test
```

Use clang-format to style the file

clang-format -style=file -i <file list>