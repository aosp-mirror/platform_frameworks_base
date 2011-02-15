LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    TestPlugin.cpp \
    ../src/WVMLogging.cpp

LOCAL_C_INCLUDES+= \
    bionic \
    vendor/widevine/proprietary/include \
    external/stlport/stlport \
    frameworks/base/drm/libdrmframework/include \
    frameworks/base/drm/libdrmframework/plugins/common/include \
    frameworks/base/drm/libdrmframework/plugins/widevine/include

LOCAL_SHARED_LIBRARIES := \
    libstlport            \
    libwvdrm              \
    liblog                \
    libutils              \
    libz                  \
    libdl

LOCAL_STATIC_LIBRARIES := \
    libdrmframeworkcommon

LOCAL_MODULE:=test-wvdrmplugin

LOCAL_MODULE_TAGS := tests

include $(BUILD_EXECUTABLE)

