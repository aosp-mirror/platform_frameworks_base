LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_PREBUILT_STATIC_JAVA_LIBRARIES := \
    trail-drawing:trail-core-lib-1.2.6-SNAPSHOT.jar \
    rebound:rebound-0.3.8.jar

include $(BUILD_MULTI_PREBUILT)

