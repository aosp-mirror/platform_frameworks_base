LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=       \
	stagefright.cpp \
	SineSource.cpp

LOCAL_SHARED_LIBRARIES := \
	libstagefright libmedia libutils libbinder

LOCAL_C_INCLUDES:= \
	$(JNI_H_INCLUDE) \
	frameworks/base/media/libstagefright \
	frameworks/base/media/libstagefright/include \
	$(TOP)/external/opencore/extern_libs_v2/khronos/openmax/include

LOCAL_CFLAGS += -Wno-multichar

LOCAL_MODULE_TAGS := debug

LOCAL_MODULE:= stagefright

include $(BUILD_EXECUTABLE)

################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=         \
        SineSource.cpp    \
        record.cpp

LOCAL_SHARED_LIBRARIES := \
	libstagefright liblog libutils libbinder

LOCAL_C_INCLUDES:= \
	$(JNI_H_INCLUDE) \
	frameworks/base/media/libstagefright \
	$(TOP)/external/opencore/extern_libs_v2/khronos/openmax/include

LOCAL_CFLAGS += -Wno-multichar

LOCAL_MODULE_TAGS := debug

LOCAL_MODULE:= record

include $(BUILD_EXECUTABLE)

################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=         \
        SineSource.cpp    \
        audioloop.cpp

LOCAL_SHARED_LIBRARIES := \
	libstagefright liblog libutils libbinder

LOCAL_C_INCLUDES:= \
	$(JNI_H_INCLUDE) \
	frameworks/base/media/libstagefright \
	$(TOP)/external/opencore/extern_libs_v2/khronos/openmax/include

LOCAL_CFLAGS += -Wno-multichar

LOCAL_MODULE_TAGS := debug

LOCAL_MODULE:= audioloop

include $(BUILD_EXECUTABLE)
