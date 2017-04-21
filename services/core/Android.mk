LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := services.core

LOCAL_AIDL_INCLUDES := system/netd/server/binder

LOCAL_SRC_FILES += \
    $(call all-java-files-under,java) \
    java/com/android/server/EventLogTags.logtags \
    java/com/android/server/am/EventLogTags.logtags \
    ../../../../system/netd/server/binder/android/net/INetd.aidl \
    ../../../../system/netd/server/binder/android/net/metrics/INetdEventListener.aidl \
    ../../../native/cmds/installd/binder/android/os/IInstalld.aidl \

LOCAL_AIDL_INCLUDES += \
    system/netd/server/binder

LOCAL_JAVA_LIBRARIES := \
    services.net \
    android.hardware.light-V2.0-java \
    android.hardware.power-V1.0-java \
    android.hardware.tv.cec-V1.0-java \
    android.hidl.manager-V1.0-java

LOCAL_STATIC_JAVA_LIBRARIES := \
    tzdata_shared2 \
    tzdata_update2 \
    android.hidl.base-V1.0-java-static \
    android.hardware.biometrics.fingerprint-V2.1-java-static \
    android.hardware.vibrator-V1.0-java-constants \

ifneq ($(INCREMENTAL_BUILDS),)
    LOCAL_PROGUARD_ENABLED := disabled
    LOCAL_JACK_ENABLED := incremental
endif

LOCAL_JACK_FLAGS := \
 -D jack.transformations.boost-locked-region-priority=true \
 -D jack.transformations.boost-locked-region-priority.classname=com.android.server.am.ActivityManagerService,com.android.server.wm.WindowHashMap \
 -D jack.transformations.boost-locked-region-priority.request=com.android.server.am.ActivityManagerService\#boostPriorityForLockedSection,com.android.server.wm.WindowManagerService\#boostPriorityForLockedSection \
 -D jack.transformations.boost-locked-region-priority.reset=com.android.server.am.ActivityManagerService\#resetPriorityAfterLockedSection,com.android.server.wm.WindowManagerService\#resetPriorityAfterLockedSection

include $(BUILD_STATIC_JAVA_LIBRARY)
