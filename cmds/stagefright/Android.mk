LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=       \
	stagefright.cpp \
	SineSource.cpp

LOCAL_SHARED_LIBRARIES := \
	libstagefright libmedia libutils libbinder libstagefright_foundation \
        libskia libgui

LOCAL_C_INCLUDES:= \
	$(JNI_H_INCLUDE) \
	frameworks/base/media/libstagefright \
	frameworks/base/media/libstagefright/include \
	$(TOP)/frameworks/base/include/media/stagefright/openmax \
        external/skia/include/core \
        external/skia/include/images \

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
	$(TOP)/frameworks/base/include/media/stagefright/openmax

LOCAL_CFLAGS += -Wno-multichar

LOCAL_MODULE_TAGS := debug

LOCAL_MODULE:= record

include $(BUILD_EXECUTABLE)

################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=         \
        SineSource.cpp    \
        recordvideo.cpp

LOCAL_SHARED_LIBRARIES := \
	libstagefright liblog libutils libbinder

LOCAL_C_INCLUDES:= \
	$(JNI_H_INCLUDE) \
	frameworks/base/media/libstagefright \
	$(TOP)/frameworks/base/include/media/stagefright/openmax

LOCAL_CFLAGS += -Wno-multichar

LOCAL_MODULE_TAGS := debug

LOCAL_MODULE:= recordvideo

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
	$(TOP)/frameworks/base/include/media/stagefright/openmax

LOCAL_CFLAGS += -Wno-multichar

LOCAL_MODULE_TAGS := debug

LOCAL_MODULE:= audioloop

include $(BUILD_EXECUTABLE)

################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=         \
        stream.cpp    \

LOCAL_SHARED_LIBRARIES := \
	libstagefright liblog libutils libbinder libgui \
        libstagefright_foundation libmedia

LOCAL_C_INCLUDES:= \
	$(JNI_H_INCLUDE) \
	frameworks/base/media/libstagefright \
	$(TOP)/frameworks/base/include/media/stagefright/openmax

LOCAL_CFLAGS += -Wno-multichar

LOCAL_MODULE_TAGS := debug

LOCAL_MODULE:= stream

include $(BUILD_EXECUTABLE)

################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=         \
        sf2.cpp    \

LOCAL_SHARED_LIBRARIES := \
	libstagefright liblog libutils libbinder libstagefright_foundation \
        libmedia libgui libcutils libui

LOCAL_C_INCLUDES:= \
	$(JNI_H_INCLUDE) \
	frameworks/base/media/libstagefright \
	$(TOP)/frameworks/base/include/media/stagefright/openmax

LOCAL_CFLAGS += -Wno-multichar

LOCAL_MODULE_TAGS := debug

LOCAL_MODULE:= sf2

include $(BUILD_EXECUTABLE)


