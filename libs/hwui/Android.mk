LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# Only build libhwui when USE_OPENGL_RENDERER is
# defined in the current device/board configuration
ifeq ($(USE_OPENGL_RENDERER),true)
	LOCAL_SRC_FILES:= \
		utils/SortedListImpl.cpp \
		font/CacheTexture.cpp \
		font/Font.cpp \
		FontRenderer.cpp \
		GammaFontRenderer.cpp \
		Caches.cpp \
		DisplayListLogBuffer.cpp \
		DisplayListRenderer.cpp \
		Dither.cpp \
		FboCache.cpp \
		GradientCache.cpp \
		Layer.cpp \
		LayerCache.cpp \
		LayerRenderer.cpp \
		Matrix.cpp \
		OpenGLRenderer.cpp \
		PathRenderer.cpp \
		Patch.cpp \
		PatchCache.cpp \
		PathCache.cpp \
		Program.cpp \
		ProgramCache.cpp \
		ResourceCache.cpp \
		ShapeCache.cpp \
		SkiaColorFilter.cpp \
		SkiaShader.cpp \
		Snapshot.cpp \
		Stencil.cpp \
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

	LOCAL_CFLAGS += -DUSE_OPENGL_RENDERER -DGL_GLEXT_PROTOTYPES
	LOCAL_MODULE_CLASS := SHARED_LIBRARIES
	LOCAL_SHARED_LIBRARIES := libcutils libutils libGLESv2 libskia libui
	LOCAL_MODULE := libhwui
	LOCAL_MODULE_TAGS := optional

	ifndef HWUI_COMPILE_SYMBOLS
		LOCAL_CFLAGS += -fvisibility=hidden
	endif

	ifdef HWUI_COMPILE_FOR_PERF
		LOCAL_CFLAGS += -fno-omit-frame-pointer -marm -mapcs
	endif

	include $(BUILD_SHARED_LIBRARY)

    include $(call all-makefiles-under,$(LOCAL_PATH))
endif
