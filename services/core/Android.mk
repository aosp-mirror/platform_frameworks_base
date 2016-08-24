LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := services.core

LOCAL_AIDL_INCLUDES := system/netd/server/binder

LOCAL_SRC_FILES += \
    $(call all-java-files-under,java) \
    java/com/android/server/EventLogTags.logtags \
    java/com/android/server/am/EventLogTags.logtags \
    ../../../../system/netd/server/binder/android/net/INetd.aidl \
    ../../../../system/netd/server/binder/android/net/metrics/IDnsEventListener.aidl \

LOCAL_AIDL_INCLUDES += \
    system/netd/server/binder

LOCAL_JAVA_LIBRARIES := services.net telephony-common
LOCAL_STATIC_JAVA_LIBRARIES := tzdata_update

ifneq ($(INCREMENTAL_BUILDS),)
    LOCAL_PROGUARD_ENABLED := disabled
    LOCAL_JACK_ENABLED := incremental
endif

LOCAL_JACK_FLAGS := \
 -D jack.transformations.boost-locked-region-priority=true \
 -D jack.transformations.boost-locked-region-priority.classname=com.android.server.am.ActivityManagerService \
 -D jack.transformations.boost-locked-region-priority.request=com.android.server.am.ActivityManagerService\#boostPriorityForLockedSection \
 -D jack.transformations.boost-locked-region-priority.reset=com.android.server.am.ActivityManagerService\#resetPriorityAfterLockedSection

include $(BUILD_STATIC_JAVA_LIBRARY)
