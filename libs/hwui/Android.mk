LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# Only build libhwui when USE_OPENGL_RENDERER is
# defined in the current device/board configuration
ifeq ($(USE_OPENGL_RENDERER),true)
	LOCAL_SRC_FILES:= \
		FontRenderer.cpp \
		GradientCache.cpp \
		LayerCache.cpp \
		Matrix.cpp \
		OpenGLRenderer.cpp \
		Patch.cpp \
		PatchCache.cpp \
		PathCache.cpp \
		Program.cpp \
		ProgramCache.cpp \
		SkiaColorFilter.cpp \
		SkiaShader.cpp \
		TextureCache.cpp \
		TextDropShadowCache.cpp
	
	LOCAL_C_INCLUDES += \
		$(JNI_H_INCLUDE) \
		$(LOCAL_PATH)/../../include/utils \
		external/skia/include/core \
		external/skia/include/effects \
		external/skia/include/images \
		external/skia/src/ports \
		external/skia/include/utils

	LOCAL_CFLAGS += -DUSE_OPENGL_RENDERER
	LOCAL_MODULE_CLASS := SHARED_LIBRARIES
	LOCAL_SHARED_LIBRARIES := libcutils libutils libGLESv2 libskia
	LOCAL_MODULE := libhwui
	LOCAL_MODULE_TAGS := optional
	LOCAL_PRELINK_MODULE := false
	
	include $(BUILD_SHARED_LIBRARY)

    include $(call all-makefiles-under,$(LOCAL_PATH))
endif
