# Wifi Unit Tests
This package contains unit tests for the android wifi framework APIs based on the
[Android Testing Support Library](http://developer.android.com/tools/testing-support-library/index.html).
The test cases are built using the [JUnit](http://junit.org/) and [Mockito](http://mockito.org/)
libraries.

## Running Tests
The easiest way to run tests is simply run

```
frameworks/base/wifi/tests/runtests.sh
```

`runtests.sh` will build the test project and all of its dependencies and push the APK to the
connected device. It will then run the tests on the device.

To pick up changes in framework/base, you will need to:
1. rebuild the framework library 'make -j32'
2. sync over the updated library to the device 'adb sync'
3. restart framework on the device 'adb shell stop' then 'adb shell start'

To enable syncing data to the device for first time after clean reflash:
1. adb disable-verity
2. adb reboot
3. adb remount

See below for a few example of options to limit which tests are run.
See the
[AndroidJUnitRunner Documentation](https://developer.android.com/reference/android/support/test/runner/AndroidJUnitRunner.html)
for more details on the supported options.

```
runtests.sh -e package android.net.wifi
runtests.sh -e class android.net.wifi.WifiScannerTest
```

If you manually build and push the test APK to the device you can run tests using

```
adb shell am instrument -w 'android.net.wifi.test/androidx.test.runner.AndroidJUnitRunner'
```

## Adding Tests
Tests can be added by adding classes to the src directory. JUnit4 style test cases can
be written by simply annotating test methods with `org.junit.Test`.

## Debugging Tests
If you are trying to debug why tests are not doing what you expected, you can add android log
statements and use logcat to view them. The beginning and end of every tests is automatically logged
with the tag `TestRunner`.
