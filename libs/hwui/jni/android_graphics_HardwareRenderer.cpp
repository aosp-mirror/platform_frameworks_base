/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#undef LOG_TAG
#define LOG_TAG "ThreadedRenderer"
#define ATRACE_TAG ATRACE_TAG_VIEW

#include <FrameInfo.h>
#include <GraphicsJNI.h>
#include <Picture.h>
#include <Properties.h>
#include <RootRenderNode.h>
#include <dlfcn.h>
#include <inttypes.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <nativehelper/JNIHelp.h>
#include <pipeline/skia/ShaderCache.h>
#include <private/EGL/cache.h>
#include <renderthread/CanvasContext.h>
#include <renderthread/RenderProxy.h>
#include <renderthread/RenderTask.h>
#include <renderthread/RenderThread.h>
#include <utils/Color.h>
#include <utils/RefBase.h>
#include <utils/StrongPointer.h>
#include <utils/Timers.h>
#include <utils/TraceUtils.h>

#include <algorithm>
#include <atomic>

#include "android_graphics_HardwareRendererObserver.h"

namespace android {

using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;

struct {
    jclass clazz;
    jmethodID invokePictureCapturedCallback;
} gHardwareRenderer;

struct {
    jmethodID onFrameDraw;
} gFrameDrawingCallback;

struct {
    jmethodID onFrameComplete;
} gFrameCompleteCallback;

static JNIEnv* getenv(JavaVM* vm) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOG_ALWAYS_FATAL("Failed to get JNIEnv for JavaVM: %p", vm);
    }
    return env;
}

typedef ANativeWindow* (*ANW_fromSurface)(JNIEnv* env, jobject surface);
ANW_fromSurface fromSurface;

class JvmErrorReporter : public ErrorHandler {
public:
    JvmErrorReporter(JNIEnv* env) {
        env->GetJavaVM(&mVm);
    }

    virtual void onError(const std::string& message) override {
        JNIEnv* env = getenv(mVm);
        jniThrowException(env, "java/lang/IllegalStateException", message.c_str());
    }
private:
    JavaVM* mVm;
};

class FrameCompleteWrapper : public LightRefBase<FrameCompleteWrapper> {
public:
    explicit FrameCompleteWrapper(JNIEnv* env, jobject jobject) {
        env->GetJavaVM(&mVm);
        mObject = env->NewGlobalRef(jobject);
        LOG_ALWAYS_FATAL_IF(!mObject, "Failed to make global ref");
    }

    ~FrameCompleteWrapper() {
        releaseObject();
    }

    void onFrameComplete(int64_t frameNr) {
        if (mObject) {
            ATRACE_FORMAT("frameComplete %" PRId64, frameNr);
            getenv(mVm)->CallVoidMethod(mObject, gFrameCompleteCallback.onFrameComplete, frameNr);
            releaseObject();
        }
    }

private:
    JavaVM* mVm;
    jobject mObject;

    void releaseObject() {
        if (mObject) {
            getenv(mVm)->DeleteGlobalRef(mObject);
            mObject = nullptr;
        }
    }
};

static void android_view_ThreadedRenderer_rotateProcessStatsBuffer(JNIEnv* env, jobject clazz) {
    RenderProxy::rotateProcessStatsBuffer();
}

static void android_view_ThreadedRenderer_setProcessStatsBuffer(JNIEnv* env, jobject clazz,
        jint fd) {
    RenderProxy::setProcessStatsBuffer(fd);
}

static jint android_view_ThreadedRenderer_getRenderThreadTid(JNIEnv* env, jobject clazz,
        jlong proxyPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    return proxy->getRenderThreadTid();
}

static jlong android_view_ThreadedRenderer_createRootRenderNode(JNIEnv* env, jobject clazz) {
    RootRenderNode* node = new RootRenderNode(std::make_unique<JvmErrorReporter>(env));
    node->incStrong(0);
    node->setName("RootRenderNode");
    return reinterpret_cast<jlong>(node);
}

static jlong android_view_ThreadedRenderer_createProxy(JNIEnv* env, jobject clazz,
        jboolean translucent, jboolean isWideGamut, jlong rootRenderNodePtr) {
    RootRenderNode* rootRenderNode = reinterpret_cast<RootRenderNode*>(rootRenderNodePtr);
    ContextFactoryImpl factory(rootRenderNode);
    RenderProxy* proxy = new RenderProxy(translucent, rootRenderNode, &factory);
    proxy->setWideGamut(isWideGamut);
    return (jlong) proxy;
}

static void android_view_ThreadedRenderer_deleteProxy(JNIEnv* env, jobject clazz,
        jlong proxyPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    delete proxy;
}

static jboolean android_view_ThreadedRenderer_loadSystemProperties(JNIEnv* env, jobject clazz,
        jlong proxyPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    return proxy->loadSystemProperties();
}

static void android_view_ThreadedRenderer_setName(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jstring jname) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    const char* name = env->GetStringUTFChars(jname, NULL);
    proxy->setName(name);
    env->ReleaseStringUTFChars(jname, name);
}

static void android_view_ThreadedRenderer_setSurface(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jobject jsurface, jboolean discardBuffer) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    ANativeWindow* window = nullptr;
    if (jsurface) {
        window = fromSurface(env, jsurface);
    }
    bool enableTimeout = true;
    if (discardBuffer) {
        // Currently only Surface#lockHardwareCanvas takes this path
        enableTimeout = false;
        proxy->setSwapBehavior(SwapBehavior::kSwap_discardBuffer);
    }
    proxy->setSurface(window, enableTimeout);
    ANativeWindow_release(window);
}

static jboolean android_view_ThreadedRenderer_pause(JNIEnv* env, jobject clazz,
        jlong proxyPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    return proxy->pause();
}

static void android_view_ThreadedRenderer_setStopped(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jboolean stopped) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->setStopped(stopped);
}

static void android_view_ThreadedRenderer_setLightAlpha(JNIEnv* env, jobject clazz, jlong proxyPtr,
        jfloat ambientShadowAlpha, jfloat spotShadowAlpha) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->setLightAlpha((uint8_t) (255 * ambientShadowAlpha), (uint8_t) (255 * spotShadowAlpha));
}

static void android_view_ThreadedRenderer_setLightGeometry(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jfloat lightX, jfloat lightY, jfloat lightZ, jfloat lightRadius) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->setLightGeometry((Vector3){lightX, lightY, lightZ}, lightRadius);
}

static void android_view_ThreadedRenderer_setOpaque(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jboolean opaque) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->setOpaque(opaque);
}

static void android_view_ThreadedRenderer_setWideGamut(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jboolean wideGamut) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->setWideGamut(wideGamut);
}

static int android_view_ThreadedRenderer_syncAndDrawFrame(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlongArray frameInfo, jint frameInfoSize) {
    LOG_ALWAYS_FATAL_IF(frameInfoSize != UI_THREAD_FRAME_INFO_SIZE,
            "Mismatched size expectations, given %d expected %d",
            frameInfoSize, UI_THREAD_FRAME_INFO_SIZE);
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    env->GetLongArrayRegion(frameInfo, 0, frameInfoSize, proxy->frameInfo());
    return proxy->syncAndDrawFrame();
}

static void android_view_ThreadedRenderer_destroy(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlong rootNodePtr) {
    RootRenderNode* rootRenderNode = reinterpret_cast<RootRenderNode*>(rootNodePtr);
    rootRenderNode->destroy();
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->destroy();
}

static void android_view_ThreadedRenderer_registerAnimatingRenderNode(JNIEnv* env, jobject clazz,
        jlong rootNodePtr, jlong animatingNodePtr) {
    RootRenderNode* rootRenderNode = reinterpret_cast<RootRenderNode*>(rootNodePtr);
    RenderNode* animatingNode = reinterpret_cast<RenderNode*>(animatingNodePtr);
    rootRenderNode->attachAnimatingNode(animatingNode);
}

static void android_view_ThreadedRenderer_registerVectorDrawableAnimator(JNIEnv* env, jobject clazz,
        jlong rootNodePtr, jlong animatorPtr) {
    RootRenderNode* rootRenderNode = reinterpret_cast<RootRenderNode*>(rootNodePtr);
    PropertyValuesAnimatorSet* animator = reinterpret_cast<PropertyValuesAnimatorSet*>(animatorPtr);
    rootRenderNode->addVectorDrawableAnimator(animator);
}

static void android_view_ThreadedRenderer_invokeFunctor(JNIEnv* env, jobject clazz,
        jlong functorPtr, jboolean waitForCompletion) {
    Functor* functor = reinterpret_cast<Functor*>(functorPtr);
    RenderProxy::invokeFunctor(functor, waitForCompletion);
}

static jlong android_view_ThreadedRenderer_createTextureLayer(JNIEnv* env, jobject clazz,
        jlong proxyPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    DeferredLayerUpdater* layer = proxy->createTextureLayer();
    return reinterpret_cast<jlong>(layer);
}

static void android_view_ThreadedRenderer_buildLayer(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlong nodePtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    RenderNode* node = reinterpret_cast<RenderNode*>(nodePtr);
    proxy->buildLayer(node);
}

static jboolean android_view_ThreadedRenderer_copyLayerInto(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlong layerPtr, jlong bitmapPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(layerPtr);
    SkBitmap bitmap;
    bitmap::toBitmap(bitmapPtr).getSkBitmap(&bitmap);
    return proxy->copyLayerInto(layer, bitmap);
}

static void android_view_ThreadedRenderer_pushLayerUpdate(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlong layerPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(layerPtr);
    proxy->pushLayerUpdate(layer);
}

static void android_view_ThreadedRenderer_cancelLayerUpdate(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlong layerPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(layerPtr);
    proxy->cancelLayerUpdate(layer);
}

static void android_view_ThreadedRenderer_detachSurfaceTexture(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlong layerPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(layerPtr);
    proxy->detachSurfaceTexture(layer);
}

static void android_view_ThreadedRenderer_destroyHardwareResources(JNIEnv* env, jobject clazz,
        jlong proxyPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->destroyHardwareResources();
}

static void android_view_ThreadedRenderer_trimMemory(JNIEnv* env, jobject clazz,
        jint level) {
    RenderProxy::trimMemory(level);
}

static void android_view_ThreadedRenderer_overrideProperty(JNIEnv* env, jobject clazz,
        jstring name, jstring value) {
    const char* nameCharArray = env->GetStringUTFChars(name, NULL);
    const char* valueCharArray = env->GetStringUTFChars(value, NULL);
    RenderProxy::overrideProperty(nameCharArray, valueCharArray);
    env->ReleaseStringUTFChars(name, nameCharArray);
    env->ReleaseStringUTFChars(name, valueCharArray);
}

static void android_view_ThreadedRenderer_fence(JNIEnv* env, jobject clazz,
        jlong proxyPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->fence();
}

static void android_view_ThreadedRenderer_stopDrawing(JNIEnv* env, jobject clazz,
        jlong proxyPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->stopDrawing();
}

static void android_view_ThreadedRenderer_notifyFramePending(JNIEnv* env, jobject clazz,
        jlong proxyPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->notifyFramePending();
}

static void android_view_ThreadedRenderer_dumpProfileInfo(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jobject javaFileDescriptor, jint dumpFlags) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    int fd = jniGetFDFromFileDescriptor(env, javaFileDescriptor);
    proxy->dumpProfileInfo(fd, dumpFlags);
}

static void android_view_ThreadedRenderer_addRenderNode(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlong renderNodePtr, jboolean placeFront) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    proxy->addRenderNode(renderNode, placeFront);
}

static void android_view_ThreadedRenderer_removeRenderNode(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlong renderNodePtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    proxy->removeRenderNode(renderNode);
}

static void android_view_ThreadedRendererd_drawRenderNode(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlong renderNodePtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    proxy->drawRenderNode(renderNode);
}

static void android_view_ThreadedRenderer_setContentDrawBounds(JNIEnv* env,
        jobject clazz, jlong proxyPtr, jint left, jint top, jint right, jint bottom) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->setContentDrawBounds(left, top, right, bottom);
}

class JGlobalRefHolder {
public:
    JGlobalRefHolder(JavaVM* vm, jobject object) : mVm(vm), mObject(object) {}

    virtual ~JGlobalRefHolder() {
        getenv(mVm)->DeleteGlobalRef(mObject);
        mObject = nullptr;
    }

    jobject object() { return mObject; }
    JavaVM* vm() { return mVm; }

private:
    JGlobalRefHolder(const JGlobalRefHolder&) = delete;
    void operator=(const JGlobalRefHolder&) = delete;

    JavaVM* mVm;
    jobject mObject;
};

static void android_view_ThreadedRenderer_setPictureCapturedCallbackJNI(JNIEnv* env,
        jobject clazz, jlong proxyPtr, jobject pictureCallback) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    if (!pictureCallback) {
        proxy->setPictureCapturedCallback(nullptr);
    } else {
        JavaVM* vm = nullptr;
        LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&vm) != JNI_OK, "Unable to get Java VM");
        auto globalCallbackRef = std::make_shared<JGlobalRefHolder>(vm,
                env->NewGlobalRef(pictureCallback));
        proxy->setPictureCapturedCallback([globalCallbackRef](sk_sp<SkPicture>&& picture) {
            JNIEnv* env = getenv(globalCallbackRef->vm());
            Picture* wrapper = new Picture{std::move(picture)};
            env->CallStaticVoidMethod(gHardwareRenderer.clazz,
                    gHardwareRenderer.invokePictureCapturedCallback,
                    static_cast<jlong>(reinterpret_cast<intptr_t>(wrapper)),
                    globalCallbackRef->object());
        });
    }
}

static void android_view_ThreadedRenderer_setFrameCallback(JNIEnv* env,
        jobject clazz, jlong proxyPtr, jobject frameCallback) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    if (!frameCallback) {
        proxy->setFrameCallback(nullptr);
    } else {
        JavaVM* vm = nullptr;
        LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&vm) != JNI_OK, "Unable to get Java VM");
        auto globalCallbackRef = std::make_shared<JGlobalRefHolder>(vm,
                env->NewGlobalRef(frameCallback));
        proxy->setFrameCallback([globalCallbackRef](int64_t frameNr) {
            JNIEnv* env = getenv(globalCallbackRef->vm());
            env->CallVoidMethod(globalCallbackRef->object(), gFrameDrawingCallback.onFrameDraw,
                    static_cast<jlong>(frameNr));
        });
    }
}

static void android_view_ThreadedRenderer_setFrameCompleteCallback(JNIEnv* env,
        jobject clazz, jlong proxyPtr, jobject callback) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    if (!callback) {
        proxy->setFrameCompleteCallback(nullptr);
    } else {
        sp<FrameCompleteWrapper> wrapper = new FrameCompleteWrapper{env, callback};
        proxy->setFrameCompleteCallback([wrapper](int64_t frameNr) {
            wrapper->onFrameComplete(frameNr);
        });
    }
}

static jint android_view_ThreadedRenderer_copySurfaceInto(JNIEnv* env,
        jobject clazz, jobject jsurface, jint left, jint top,
        jint right, jint bottom, jlong bitmapPtr) {
    SkBitmap bitmap;
    bitmap::toBitmap(bitmapPtr).getSkBitmap(&bitmap);
    ANativeWindow* window = fromSurface(env, jsurface);
    jint result = RenderProxy::copySurfaceInto(window, left, top, right, bottom, &bitmap);
    ANativeWindow_release(window);
    return result;
}

class ContextFactory : public IContextFactory {
public:
    virtual AnimationContext* createAnimationContext(renderthread::TimeLord& clock) {
        return new AnimationContext(clock);
    }
};

static jobject android_view_ThreadedRenderer_createHardwareBitmapFromRenderNode(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jint jwidth, jint jheight) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    if (jwidth <= 0 || jheight <= 0) {
        ALOGW("Invalid width %d or height %d", jwidth, jheight);
        return nullptr;
    }

    uint32_t width = jwidth;
    uint32_t height = jheight;

    // Create an ImageReader wired up to a BufferItemConsumer
    AImageReader* rawReader;
    media_status_t result =
            AImageReader_newWithUsage(width, height, AIMAGE_FORMAT_RGBA_8888,
                                      AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, 2, &rawReader);
    std::unique_ptr<AImageReader, decltype(&AImageReader_delete)> reader(rawReader,
                                                                         AImageReader_delete);

    if (result != AMEDIA_OK) {
        ALOGW("Error creating image reader!");
        return nullptr;
    }

    // Note that ownership of this window is maintained by AImageReader, so we
    // shouldn't need to wrap around a smart pointer.
    ANativeWindow* window;
    result = AImageReader_getWindow(rawReader, &window);

    if (result != AMEDIA_OK) {
        ALOGW("Error retrieving the native window!");
        return nullptr;
    }

    // Render into the surface
    {
        ContextFactory factory;
        RenderProxy proxy{true, renderNode, &factory};
        proxy.setSwapBehavior(SwapBehavior::kSwap_discardBuffer);
        proxy.setSurface(window);
        // Shadows can't be used via this interface, so just set the light source
        // to all 0s.
        proxy.setLightAlpha(0, 0);
        proxy.setLightGeometry((Vector3){0, 0, 0}, 0);
        nsecs_t vsync = systemTime(SYSTEM_TIME_MONOTONIC);
        UiFrameInfoBuilder(proxy.frameInfo())
                .setVsync(vsync, vsync)
                .addFlag(FrameInfoFlags::SurfaceCanvas);
        proxy.syncAndDrawFrame();
    }

    AImage* rawImage;
    result = AImageReader_acquireNextImage(rawReader, &rawImage);
    std::unique_ptr<AImage, decltype(&AImage_delete)> image(rawImage, AImage_delete);
    if (result != AMEDIA_OK) {
        ALOGW("Error reading image: %d!", result);
        return nullptr;
    }

    AHardwareBuffer* buffer;
    result = AImage_getHardwareBuffer(rawImage, &buffer);

    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);

    if (desc.width != width || desc.height != height) {
        ALOGW("AHardwareBuffer size mismatch, got %dx%d expected %dx%d", desc.width, desc.height,
              width, height);
        // Continue I guess?
    }

    sk_sp<SkColorSpace> cs = uirenderer::DataSpaceToColorSpace(
            static_cast<android_dataspace>(ANativeWindow_getBuffersDataSpace(window)));
    if (cs == nullptr) {
        // nullptr is treated as SRGB in Skia, thus explicitly use SRGB in order to make sure
        // the returned bitmap has a color space.
        cs = SkColorSpace::MakeSRGB();
    }
    sk_sp<Bitmap> bitmap = Bitmap::createFrom(buffer, cs);
    return bitmap::createBitmap(env, bitmap.release(),
            android::bitmap::kBitmapCreateFlag_Premultiplied);
}

static void android_view_ThreadedRenderer_disableVsync(JNIEnv*, jclass) {
    RenderProxy::disableVsync();
}

static void android_view_ThreadedRenderer_setHighContrastText(JNIEnv*, jclass, jboolean enable) {
    Properties::enableHighContrastText = enable;
}

static void android_view_ThreadedRenderer_hackySetRTAnimationsEnabled(JNIEnv*, jclass,
        jboolean enable) {
    Properties::enableRTAnimations = enable;
}

static void android_view_ThreadedRenderer_setDebuggingEnabled(JNIEnv*, jclass, jboolean enable) {
    Properties::debuggingEnabled = enable;
}

static void android_view_ThreadedRenderer_setIsolatedProcess(JNIEnv*, jclass, jboolean isolated) {
    Properties::isolatedProcess = isolated;
}

static void android_view_ThreadedRenderer_setContextPriority(JNIEnv*, jclass,
        jint contextPriority) {
    Properties::contextPriority = contextPriority;
}

static void android_view_ThreadedRenderer_allocateBuffers(JNIEnv* env, jobject clazz,
        jlong proxyPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->allocateBuffers();
}

static void android_view_ThreadedRenderer_setForceDark(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jboolean enable) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->setForceDark(enable);
}

static void android_view_ThreadedRenderer_preload(JNIEnv*, jclass) {
    RenderProxy::preload();
}

// ----------------------------------------------------------------------------
// HardwareRendererObserver
// ----------------------------------------------------------------------------

static void android_view_ThreadedRenderer_addObserver(JNIEnv* env, jclass clazz,
        jlong proxyPtr, jlong observerPtr) {
    HardwareRendererObserver* observer = reinterpret_cast<HardwareRendererObserver*>(observerPtr);
    renderthread::RenderProxy* renderProxy =
            reinterpret_cast<renderthread::RenderProxy*>(proxyPtr);

    renderProxy->addFrameMetricsObserver(observer);
}

static void android_view_ThreadedRenderer_removeObserver(JNIEnv* env, jclass clazz,
        jlong proxyPtr, jlong observerPtr) {
    HardwareRendererObserver* observer = reinterpret_cast<HardwareRendererObserver*>(observerPtr);
    renderthread::RenderProxy* renderProxy =
            reinterpret_cast<renderthread::RenderProxy*>(proxyPtr);

    renderProxy->removeFrameMetricsObserver(observer);
}

// ----------------------------------------------------------------------------
// Shaders
// ----------------------------------------------------------------------------

static void android_view_ThreadedRenderer_setupShadersDiskCache(JNIEnv* env, jobject clazz,
        jstring diskCachePath, jstring skiaDiskCachePath) {
    const char* cacheArray = env->GetStringUTFChars(diskCachePath, NULL);
    android::egl_set_cache_filename(cacheArray);
    env->ReleaseStringUTFChars(diskCachePath, cacheArray);

    const char* skiaCacheArray = env->GetStringUTFChars(skiaDiskCachePath, NULL);
    uirenderer::skiapipeline::ShaderCache::get().setFilename(skiaCacheArray);
    env->ReleaseStringUTFChars(skiaDiskCachePath, skiaCacheArray);
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/graphics/HardwareRenderer";

static const JNINativeMethod gMethods[] = {
    { "nRotateProcessStatsBuffer", "()V", (void*) android_view_ThreadedRenderer_rotateProcessStatsBuffer },
    { "nSetProcessStatsBuffer", "(I)V", (void*) android_view_ThreadedRenderer_setProcessStatsBuffer },
    { "nGetRenderThreadTid", "(J)I", (void*) android_view_ThreadedRenderer_getRenderThreadTid },
    { "nCreateRootRenderNode", "()J", (void*) android_view_ThreadedRenderer_createRootRenderNode },
    { "nCreateProxy", "(ZZJ)J", (void*) android_view_ThreadedRenderer_createProxy },
    { "nDeleteProxy", "(J)V", (void*) android_view_ThreadedRenderer_deleteProxy },
    { "nLoadSystemProperties", "(J)Z", (void*) android_view_ThreadedRenderer_loadSystemProperties },
    { "nSetName", "(JLjava/lang/String;)V", (void*) android_view_ThreadedRenderer_setName },
    { "nSetSurface", "(JLandroid/view/Surface;Z)V", (void*) android_view_ThreadedRenderer_setSurface },
    { "nPause", "(J)Z", (void*) android_view_ThreadedRenderer_pause },
    { "nSetStopped", "(JZ)V", (void*) android_view_ThreadedRenderer_setStopped },
    { "nSetLightAlpha", "(JFF)V", (void*) android_view_ThreadedRenderer_setLightAlpha },
    { "nSetLightGeometry", "(JFFFF)V", (void*) android_view_ThreadedRenderer_setLightGeometry },
    { "nSetOpaque", "(JZ)V", (void*) android_view_ThreadedRenderer_setOpaque },
    { "nSetWideGamut", "(JZ)V", (void*) android_view_ThreadedRenderer_setWideGamut },
    { "nSyncAndDrawFrame", "(J[JI)I", (void*) android_view_ThreadedRenderer_syncAndDrawFrame },
    { "nDestroy", "(JJ)V", (void*) android_view_ThreadedRenderer_destroy },
    { "nRegisterAnimatingRenderNode", "(JJ)V", (void*) android_view_ThreadedRenderer_registerAnimatingRenderNode },
    { "nRegisterVectorDrawableAnimator", "(JJ)V", (void*) android_view_ThreadedRenderer_registerVectorDrawableAnimator },
    { "nInvokeFunctor", "(JZ)V", (void*) android_view_ThreadedRenderer_invokeFunctor },
    { "nCreateTextureLayer", "(J)J", (void*) android_view_ThreadedRenderer_createTextureLayer },
    { "nBuildLayer", "(JJ)V", (void*) android_view_ThreadedRenderer_buildLayer },
    { "nCopyLayerInto", "(JJJ)Z", (void*) android_view_ThreadedRenderer_copyLayerInto },
    { "nPushLayerUpdate", "(JJ)V", (void*) android_view_ThreadedRenderer_pushLayerUpdate },
    { "nCancelLayerUpdate", "(JJ)V", (void*) android_view_ThreadedRenderer_cancelLayerUpdate },
    { "nDetachSurfaceTexture", "(JJ)V", (void*) android_view_ThreadedRenderer_detachSurfaceTexture },
    { "nDestroyHardwareResources", "(J)V", (void*) android_view_ThreadedRenderer_destroyHardwareResources },
    { "nTrimMemory", "(I)V", (void*) android_view_ThreadedRenderer_trimMemory },
    { "nOverrideProperty", "(Ljava/lang/String;Ljava/lang/String;)V",  (void*) android_view_ThreadedRenderer_overrideProperty },
    { "nFence", "(J)V", (void*) android_view_ThreadedRenderer_fence },
    { "nStopDrawing", "(J)V", (void*) android_view_ThreadedRenderer_stopDrawing },
    { "nNotifyFramePending", "(J)V", (void*) android_view_ThreadedRenderer_notifyFramePending },
    { "nDumpProfileInfo", "(JLjava/io/FileDescriptor;I)V", (void*) android_view_ThreadedRenderer_dumpProfileInfo },
    { "setupShadersDiskCache", "(Ljava/lang/String;Ljava/lang/String;)V",
                (void*) android_view_ThreadedRenderer_setupShadersDiskCache },
    { "nAddRenderNode", "(JJZ)V", (void*) android_view_ThreadedRenderer_addRenderNode},
    { "nRemoveRenderNode", "(JJ)V", (void*) android_view_ThreadedRenderer_removeRenderNode},
    { "nDrawRenderNode", "(JJ)V", (void*) android_view_ThreadedRendererd_drawRenderNode},
    { "nSetContentDrawBounds", "(JIIII)V", (void*)android_view_ThreadedRenderer_setContentDrawBounds},
    { "nSetPictureCaptureCallback", "(JLandroid/graphics/HardwareRenderer$PictureCapturedCallback;)V",
            (void*) android_view_ThreadedRenderer_setPictureCapturedCallbackJNI },
    { "nSetFrameCallback", "(JLandroid/graphics/HardwareRenderer$FrameDrawingCallback;)V",
            (void*)android_view_ThreadedRenderer_setFrameCallback},
    { "nSetFrameCompleteCallback", "(JLandroid/graphics/HardwareRenderer$FrameCompleteCallback;)V",
            (void*)android_view_ThreadedRenderer_setFrameCompleteCallback },
    { "nAddObserver", "(JJ)V", (void*)android_view_ThreadedRenderer_addObserver },
    { "nRemoveObserver", "(JJ)V", (void*)android_view_ThreadedRenderer_removeObserver },
    { "nCopySurfaceInto", "(Landroid/view/Surface;IIIIJ)I",
                (void*)android_view_ThreadedRenderer_copySurfaceInto },
    { "nCreateHardwareBitmap", "(JII)Landroid/graphics/Bitmap;",
            (void*)android_view_ThreadedRenderer_createHardwareBitmapFromRenderNode },
    { "disableVsync", "()V", (void*)android_view_ThreadedRenderer_disableVsync },
    { "nSetHighContrastText", "(Z)V", (void*)android_view_ThreadedRenderer_setHighContrastText },
    { "nHackySetRTAnimationsEnabled", "(Z)V",
            (void*)android_view_ThreadedRenderer_hackySetRTAnimationsEnabled },
    { "nSetDebuggingEnabled", "(Z)V", (void*)android_view_ThreadedRenderer_setDebuggingEnabled },
    { "nSetIsolatedProcess", "(Z)V", (void*)android_view_ThreadedRenderer_setIsolatedProcess },
    { "nSetContextPriority", "(I)V", (void*)android_view_ThreadedRenderer_setContextPriority },
    { "nAllocateBuffers", "(J)V", (void*)android_view_ThreadedRenderer_allocateBuffers },
    { "nSetForceDark", "(JZ)V", (void*)android_view_ThreadedRenderer_setForceDark },
    { "preload", "()V", (void*)android_view_ThreadedRenderer_preload },
};

static JavaVM* mJvm = nullptr;

static void attachRenderThreadToJvm(const char* name) {
    LOG_ALWAYS_FATAL_IF(!mJvm, "No jvm but we set the hook??");

    JavaVMAttachArgs args;
    args.version = JNI_VERSION_1_4;
    args.name = name;
    args.group = NULL;
    JNIEnv* env;
    mJvm->AttachCurrentThreadAsDaemon(&env, (void*) &args);
}

int register_android_view_ThreadedRenderer(JNIEnv* env) {
    env->GetJavaVM(&mJvm);
    RenderThread::setOnStartHook(&attachRenderThreadToJvm);

    jclass hardwareRenderer = FindClassOrDie(env,
            "android/graphics/HardwareRenderer");
    gHardwareRenderer.clazz = reinterpret_cast<jclass>(env->NewGlobalRef(hardwareRenderer));
    gHardwareRenderer.invokePictureCapturedCallback = GetStaticMethodIDOrDie(env, hardwareRenderer,
            "invokePictureCapturedCallback",
            "(JLandroid/graphics/HardwareRenderer$PictureCapturedCallback;)V");

    jclass frameCallbackClass = FindClassOrDie(env,
            "android/graphics/HardwareRenderer$FrameDrawingCallback");
    gFrameDrawingCallback.onFrameDraw = GetMethodIDOrDie(env, frameCallbackClass,
            "onFrameDraw", "(J)V");

    jclass frameCompleteClass = FindClassOrDie(env,
            "android/graphics/HardwareRenderer$FrameCompleteCallback");
    gFrameCompleteCallback.onFrameComplete = GetMethodIDOrDie(env, frameCompleteClass,
            "onFrameComplete", "(J)V");

    void* handle_ = dlopen("libandroid.so", RTLD_NOW | RTLD_NODELETE);
    fromSurface = (ANW_fromSurface)dlsym(handle_, "ANativeWindow_fromSurface");
    LOG_ALWAYS_FATAL_IF(fromSurface == nullptr,
                        "Failed to find required symbol ANativeWindow_fromSurface!");

    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}

}; // namespace android
