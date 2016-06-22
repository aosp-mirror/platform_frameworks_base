 LOCAL_PATH := $(call my-dir)
 include $(CLEAR_VARS)

 # Build all java files in the java subdirectory
 LOCAL_SRC_FILES := $(call all-subdir-java-files)

 # Any libraries that this library depends on
 LOCAL_JAVA_LIBRARIES := android.test.runner

 # The name of the jar file to create
 LOCAL_MODULE := apct-perftests-utils

 # Build a static jar file.
 include $(BUILD_STATIC_JAVA_LIBRARY)