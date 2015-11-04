# getConfig in external/skia/include/core/SkBitmap.h is deprecated.
# Allow Gnu extension: in-class initializer of static 'const float' member.
# DeferredLayerUpdater.h: private field 'mRenderThread' is not used.
LOCAL_CLANG_CFLAGS += \
    -Wno-deprecated-declarations \
    -Wno-gnu-static-float-init \
    -Wno-unused-private-field

LOCAL_SRC_FILES := \
    font/CacheTexture.cpp \
    font/Font.cpp \
    renderstate/Blend.cpp \
    renderstate/MeshState.cpp \
    renderstate/PixelBufferState.cpp \
    renderstate/RenderState.cpp \
    renderstate/Scissor.cpp \
    renderstate/Stencil.cpp \
    renderstate/TextureState.cpp \
    renderthread/CanvasContext.cpp \
    renderthread/DrawFrameTask.cpp \
    renderthread/EglManager.cpp \
    renderthread/RenderProxy.cpp \
    renderthread/RenderTask.cpp \
    renderthread/RenderThread.cpp \
    renderthread/TimeLord.cpp \
    thread/TaskManager.cpp \
    utils/Blur.cpp \
    utils/GLUtils.cpp \
    utils/LinearAllocator.cpp \
    utils/SortedListImpl.cpp \
    AmbientShadow.cpp \
    AnimationContext.cpp \
    Animator.cpp \
    AnimatorManager.cpp \
    AssetAtlas.cpp \
    Caches.cpp \
    CanvasState.cpp \
    ClipArea.cpp \
    DamageAccumulator.cpp \
    DeferredDisplayList.cpp \
    DeferredLayerUpdater.cpp \
    DisplayList.cpp \
    DisplayListCanvas.cpp \
    Dither.cpp \
    Extensions.cpp \
    FboCache.cpp \
    FontRenderer.cpp \
    FrameInfo.cpp \
    FrameInfoVisualizer.cpp \
    GammaFontRenderer.cpp \
    GlopBuilder.cpp \
    GradientCache.cpp \
    Image.cpp \
    Interpolator.cpp \
    JankTracker.cpp \
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
    Properties.cpp \
    RenderBufferCache.cpp \
    RenderNode.cpp \
    RenderProperties.cpp \
    ResourceCache.cpp \
    ShadowTessellator.cpp \
    SkiaCanvas.cpp \
    SkiaCanvasProxy.cpp \
    SkiaShader.cpp \
    Snapshot.cpp \
    SpotShadow.cpp \
    TessellationCache.cpp \
    TextDropShadowCache.cpp \
    Texture.cpp \
    TextureCache.cpp

intermediates := $(call intermediates-dir-for,STATIC_LIBRARIES,libRS,TARGET,)

LOCAL_C_INCLUDES += \
    external/skia/src/core

LOCAL_CFLAGS += -DEGL_EGLEXT_PROTOTYPES -DGL_GLEXT_PROTOTYPES
LOCAL_SHARED_LIBRARIES := liblog libcutils libutils libEGL libGLESv2 libskia libui libgui

ifneq (false,$(ANDROID_ENABLE_RENDERSCRIPT))
    LOCAL_CFLAGS += -DANDROID_ENABLE_RENDERSCRIPT
    LOCAL_SHARED_LIBRARIES += libRS libRScpp
    LOCAL_C_INCLUDES += \
        $(intermediates) \
        frameworks/rs/cpp \
        frameworks/rs \

endif

ifndef HWUI_COMPILE_SYMBOLS
    LOCAL_CFLAGS += -fvisibility=hidden
endif

ifdef HWUI_COMPILE_FOR_PERF
    # TODO: Non-arm?
    LOCAL_CFLAGS += -fno-omit-frame-pointer -marm -mapcs
endif

ifeq (true, $(HWUI_NULL_GPU))
    LOCAL_SRC_FILES += \
        tests/nullegl.cpp \
        tests/nullgles.cpp

    LOCAL_CFLAGS += -DHWUI_NULL_GPU
endif

# Defaults for ATRACE_TAG and LOG_TAG for libhwui
LOCAL_CFLAGS += -DATRACE_TAG=ATRACE_TAG_VIEW -DLOG_TAG=\"OpenGLRenderer\"
LOCAL_CFLAGS += -Wall -Wno-unused-parameter -Wunreachable-code
LOCAL_CFLAGS += -ffast-math -O3

# b/21698669
ifneq ($(USE_CLANG_PLATFORM_BUILD),true)
    LOCAL_CFLAGS += -Werror
endif
