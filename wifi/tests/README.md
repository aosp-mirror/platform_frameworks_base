# Wifi Non-Updatable Framework Unit Tests
This package contains unit tests for the non-updatable part (i.e. outside the Wifi module) of the
Android Wifi framework APIs based on the
[Android Testing Support Library](http://developer.android.com/tools/testing-support-library/index.html).
The test cases are built using the [JUnit](http://junit.org/) and [Mockito](http://mockito.org/)
libraries.

## Running Tests
The easiest way to run tests is simply run

```
atest android.net.wifi
```

To pick up changes in framework/base, you will need to:
1. rebuild the framework library 'make -j32'
2. sync over the updated library to the device 'adb sync'
3. restart framework on the device 'adb shell stop' then 'adb shell start'

To enable syncing data to the device for first time after clean reflash:
1. adb disable-verity
2. adb reboot
3. adb remount

## Adding Tests
Tests can be added by adding classes to the src directory. JUnit4 style test cases can
be written by simply annotating test methods with `org.junit.Test`.

## Debugging Tests
If you are trying to debug why tests are not doing what you expected, you can add android log
statements and use logcat to view them. The beginning and end of every tests is automatically logged
with the tag `TestRunner`.
