MediaTranscodingTest/
    Uses instrumentation and so can be run with runtest.
    It assumes /sdcard/media_api/ has been populated.

contents/media_api/
    Push to /sdcard/media_api/ for use with MediaTranscodingTest:
    adb shell mkdir /sdcard/media_api
    adb push contents/media_api/ /sdcard/media_api/
