LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under,src)

# To connect to devices (and take hprof dumps).
LOCAL_STATIC_JAVA_LIBRARIES := ddmlib-prebuilt tools-common-prebuilt

# To process hprof dumps.
LOCAL_STATIC_JAVA_LIBRARIES += perflib-prebuilt trove-prebuilt guavalib

# For JDWP access we use the framework in the JDWP tests from Apache Harmony, for
# convenience (and to not depend on internal JDK APIs).
LOCAL_STATIC_JAVA_LIBRARIES += apache-harmony-jdwp-tests-host junit-host

LOCAL_MODULE:= preload2

include $(BUILD_HOST_JAVA_LIBRARY)
# Copy to build artifacts
$(call dist-for-goals,dist_files,$(LOCAL_BUILT_MODULE):$(LOCAL_MODULE).jar)

# Copy the preload-tool shell script to the host's bin directory.
include $(CLEAR_VARS)
LOCAL_IS_HOST_MODULE := true
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE := preload-tool
LOCAL_SRC_FILES := preload-tool
LOCAL_REQUIRED_MODULES := preload2
include $(BUILD_PREBUILT)
