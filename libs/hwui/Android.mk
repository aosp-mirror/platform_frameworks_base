LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# Only build libhwui when USE_OPENGL_RENDERER is
# defined in the current device/board configuration
ifeq ($(USE_OPENGL_RENDERER),true)
	LOCAL_SRC_FILES:= \
		utils/Blur.cpp \
		utils/SortedListImpl.cpp \
		thread/TaskManager.cpp \
		font/CacheTexture.cpp \
		font/Font.cpp \
		AssetAtlas.cpp \
		FontRenderer.cpp \
		GammaFontRenderer.cpp \
		Caches.cpp \
		DisplayList.cpp \
		DeferredDisplayList.cpp \
		DisplayListLogBuffer.cpp \
		DisplayListRenderer.cpp \
		Dither.cpp \
		Extensions.cpp \
		FboCache.cpp \
		GradientCache.cpp \
		Image.cpp \
		Layer.cpp \
		LayerCache.cpp \
		LayerRenderer.cpp \
		Matrix.cpp \
		OpenGLRenderer.cpp \
		Patch.cpp \
		PatchCache.cpp \
		PathCache.cpp \
		PathTessellator.cpp \
		PixelBuffer.cpp \
		Program.cpp \
		ProgramCache.cpp \
		RenderBufferCache.cpp \
		ResourceCache.cpp \
		SkiaColorFilter.cpp \
		SkiaShader.cpp \
		Snapshot.cpp \
		Stencil.cpp \
		Texture.cpp \
		TextureCache.cpp \
		TextDropShadowCache.cpp

	intermediates := $(call intermediates-dir-for,STATIC_LIBRARIES,libRS,TARGET,)

	LOCAL_C_INCLUDES += \
		$(JNI_H_INCLUDE) \
		$(LOCAL_PATH)/../../include/utils \
		external/skia/include/core \
		external/skia/include/effects \
		external/skia/include/images \
		external/skia/src/core \
		external/skia/src/ports \
		external/skia/include/utils

	LOCAL_CFLAGS += -DUSE_OPENGL_RENDERER -DEGL_EGLEXT_PROTOTYPES -DGL_GLEXT_PROTOTYPES
	LOCAL_MODULE_CLASS := SHARED_LIBRARIES
	LOCAL_SHARED_LIBRARIES := liblog libcutils libutils libEGL libGLESv2 libskia libui
	LOCAL_MODULE := libhwui
	LOCAL_MODULE_TAGS := optional

	ifneq (false,$(ANDROID_ENABLE_RENDERSCRIPT))
		LOCAL_CFLAGS += -DANDROID_ENABLE_RENDERSCRIPT
		LOCAL_SHARED_LIBRARIES += libRS libRScpp libstlport
		LOCAL_C_INCLUDES += \
			$(intermediates) \
			frameworks/rs/cpp \
			frameworks/rs \
			external/stlport/stlport \
			bionic/ \
			bionic/libstdc++/include
	endif

	ifndef HWUI_COMPILE_SYMBOLS
		LOCAL_CFLAGS += -fvisibility=hidden
	endif

	ifdef HWUI_COMPILE_FOR_PERF
		LOCAL_CFLAGS += -fno-omit-frame-pointer -marm -mapcs
	endif

	include $(BUILD_SHARED_LIBRARY)

	include $(call all-makefiles-under,$(LOCAL_PATH))
endif
