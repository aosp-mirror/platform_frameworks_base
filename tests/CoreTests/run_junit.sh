# runs unit tests over adb shell using dalvikvm.  The value added is setting the classpath for you
# and pointing to the junit textui test runner.
#
# the normal usage might be:
# (make MoreJavaTests)
# $ adb sync
# $ java/tests/run_junit.sh android.util.MyTest

adb shell exec dalvikvm -cp system/app/MoreTests.apk junit.textui.TestRunner $*
