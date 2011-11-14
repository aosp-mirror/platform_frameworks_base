LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# Only build libhwui when USE_OPENGL_RENDERER is
# defined in the current device/board configuration
ifeq ($(USE_OPENGL_RENDERER),true)
	LOCAL_SRC_FILES:= \
		utils/SortedListImpl.cpp \
		FontRenderer.cpp \
		GammaFontRenderer.cpp \
		Caches.cpp \
		DisplayListLogBuffer.cpp \
		DisplayListRenderer.cpp \
		FboCache.cpp \
		GradientCache.cpp \
		LayerCache.cpp \
		LayerRenderer.cpp \
		Matrix.cpp \
		OpenGLRenderer.cpp \
		Patch.cpp \
		PatchCache.cpp \
		PathCache.cpp \
		Program.cpp \
		ProgramCache.cpp \
		ResourceCache.cpp \
		ShapeCache.cpp \
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
	LOCAL_CFLAGS += -fvisibility=hidden
	LOCAL_MODULE_CLASS := SHARED_LIBRARIES
	LOCAL_SHARED_LIBRARIES := libcutils libutils libGLESv2 libskia libui
	LOCAL_MODULE := libhwui
	LOCAL_MODULE_TAGS := optional
	
	include $(BUILD_SHARED_LIBRARY)

    include $(call all-makefiles-under,$(LOCAL_PATH))
endif
