# MultiDeviceInput test app #

This demo app is for manual testing of the multi-device input feature.
It creates two windows - one on the left and one on the right. You can use different input devices
in these windows.

## Installation ##
Install this using:
```
APP=MultiDeviceInput; m $APP && adb install $ANDROID_PRODUCT_OUT/system/app/$APP/$APP.apk
```

## Features ##

* Touch in one window, use stylus in another window, at the same time
* Visualize hovering stylus
* Pinch zoom in one window to affect the line thickness in another window
* Check whether stylus rejects touch in the same window
* (in the future) Check stylus and touch operation in the same window
