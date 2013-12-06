LOCAL_PATH:= $(call my-dir)

# Build services.jar
# ============================================================
include $(CLEAR_VARS)

LOCAL_MODULE:= services
LOCAL_MODULE_CLASS := JAVA_LIBRARIES

LOCAL_SRC_FILES := \
		$(call all-subdir-java-files) \
		com/android/server/EventLogTags.logtags \
		com/android/server/am/EventLogTags.logtags

LOCAL_JAVA_LIBRARIES := android.policy conscrypt telephony-common

include $(BUILD_JAVA_LIBRARY)
include $(BUILD_DROIDDOC)
