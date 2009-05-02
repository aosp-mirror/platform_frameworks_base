One of the subdirectories {debug, ndebug} is included in framework.jar
by ../../Android.mk depending on the value of $(TARGET_BUILD_TYPE).

The sdk/ directory contains the files that are passed to the doc/API
tools regardless of $(TARGET_BUILD_TYPE).
