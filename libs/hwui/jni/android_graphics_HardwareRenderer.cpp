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

#define ATRACE_TAG ATRACE_TAG_VIEW

#include <FrameInfo.h>
#include <GraphicsJNI.h>
#include <Picture.h>
#include <Properties.h>
#include <RootRenderNode.h>
#include <SkBitmap.h>
#include <SkColorSpace.h>
#include <SkData.h>
#include <SkImage.h>
#ifdef __ANDROID__
#include <SkImageAndroid.h>
#else
#include <SkImagePriv.h>
#endif
#include <SkPicture.h>
#include <SkPixmap.h>
#include <SkSerialProcs.h>
#include <SkStream.h>
#include <SkTypeface.h>
#include <gui/TraceUtils.h>
#include <include/encode/SkPngEncoder.h>
#include <inttypes.h>
#include <log/log.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <nativehelper/JNIPlatformHelp.h>
#ifdef __ANDROID__
#include <pipeline/skia/ShaderCache.h>
#include <private/EGL/cache.h>
#endif
#include <renderthread/CanvasContext.h>
#include <renderthread/RenderProxy.h>
#include <renderthread/RenderTask.h>
#include <renderthread/RenderThread.h>
#include <src/image/SkImage_Base.h>
#include <thread/CommonPool.h>
#include <utils/Color.h>
#include <utils/RefBase.h>
#include <utils/StrongPointer.h>
#include <utils/Timers.h>

#include <algorithm>
#include <atomic>
#include <vector>

#include "JvmErrorReporter.h"
#include "android_graphics_HardwareRendererObserver.h"
#include "utils/ForceDark.h"
#include "utils/SharedLib.h"

namespace android {

using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;

struct {
    jclass clazz;
    jmethodID invokePictureCapturedCallback;
} gHardwareRenderer;

struct {
    jmethodID onMergeTransaction;
} gASurfaceTransactionCallback;

struct {
    jmethodID prepare;
} gPrepareSurfaceControlForWebviewCallback;

struct {
    jmethodID onFrameDraw;
} gFrameDrawingCallback;

struct {
    jmethodID onFrameCommit;
} gFrameCommitCallback;

struct {
    jmethodID onFrameComplete;
} gFrameCompleteCallback;

struct {
    jmethodID onCopyFinished;
    jmethodID getDestinationBitmap;
} gCopyRequest;

static JNIEnv* getenv(JavaVM* vm) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOG_ALWAYS_FATAL("Failed to get JNIEnv for JavaVM: %p", vm);
    }
    return env;
}

typedef ANativeWindow* (*ANW_fromSurface)(JNIEnv* env, jobject surface);
ANW_fromSurface fromSurface;

class FrameCommitWrapper : public LightRefBase<FrameCommitWrapper> {
public:
    explicit FrameCommitWrapper(JNIEnv* env, jobject jobject) {
        env->GetJavaVM(&mVm);
        mObject = env->NewGlobalRef(jobject);
        LOG_ALWAYS_FATAL_IF(!mObject, "Failed to make global ref");
    }

    ~FrameCommitWrapper() { releaseObject(); }

    void onFrameCommit(bool didProduceBuffer) {
        if (mObject) {
            ATRACE_FORMAT("frameCommit success=%d", didProduceBuffer);
            getenv(mVm)->CallVoidMethod(mObject, gFrameCommitCallback.onFrameCommit,
                                        didProduceBuffer);
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
        jboolean translucent, jlong rootRenderNodePtr) {
    RootRenderNode* rootRenderNode = reinterpret_cast<RootRenderNode*>(rootRenderNodePtr);
    ContextFactoryImpl factory(rootRenderNode);
    RenderProxy* proxy = new RenderProxy(translucent, rootRenderNode, &factory);
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
    if (window) {
        ANativeWindow_release(window);
    }
}

static void android_view_ThreadedRenderer_setSurfaceControl(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlong surfaceControlPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    ASurfaceControl* surfaceControl = reinterpret_cast<ASurfaceControl*>(surfaceControlPtr);
    proxy->setSurfaceControl(surfaceControl);
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

static jfloat android_view_ThreadedRenderer_setColorMode(JNIEnv* env, jobject clazz, jlong proxyPtr,
                                                         jint colorMode) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    return proxy->setColorMode(static_cast<ColorMode>(colorMode));
}

static void android_view_ThreadedRenderer_setTargetSdrHdrRatio(JNIEnv* env, jobject clazz,
                                                               jlong proxyPtr, jfloat ratio) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    return proxy->setRenderSdrHdrRatio(ratio);
}

static void android_view_ThreadedRenderer_setSdrWhitePoint(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jfloat sdrWhitePoint) {
    Properties::defaultSdrWhitePoint = sdrWhitePoint;
}

static void android_view_ThreadedRenderer_setIsHighEndGfx(JNIEnv* env, jobject clazz,
        jboolean jIsHighEndGfx) {
    Properties::setIsHighEndGfx(jIsHighEndGfx);
}

static void android_view_ThreadedRenderer_setIsLowRam(JNIEnv* env, jobject clazz,
                                                      jboolean isLowRam) {
    Properties::setIsLowRam(isLowRam);
}

static void android_view_ThreadedRenderer_setIsSystemOrPersistent(JNIEnv* env, jobject clazz,
                                                                  jboolean isSystemOrPersistent) {
    Properties::setIsSystemOrPersistent(isSystemOrPersistent);
}

static int android_view_ThreadedRenderer_syncAndDrawFrame(JNIEnv* env, jobject clazz,
                                                          jlong proxyPtr, jlongArray frameInfo,
                                                          jint frameInfoSize) {
    LOG_ALWAYS_FATAL_IF(frameInfoSize != UI_THREAD_FRAME_INFO_SIZE,
                        "Mismatched size expectations, given %d expected %zu", frameInfoSize,
                        UI_THREAD_FRAME_INFO_SIZE);
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

static void android_view_ThreadedRenderer_trimCaches(JNIEnv* env, jobject clazz, jint level) {
    RenderProxy::trimCaches(level);
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

static void android_view_ThreadedRenderer_dumpGlobalProfileInfo(JNIEnv* env, jobject clazz,
                                                                jobject javaFileDescriptor,
                                                                jint dumpFlags) {
    int fd = jniGetFDFromFileDescriptor(env, javaFileDescriptor);
    RenderProxy::dumpGraphicsMemory(fd, true, dumpFlags & DumpFlags::Reset);
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

static void android_view_ThreadedRenderer_forceDrawNextFrame(JNIEnv* env, jobject clazz,
                                                             jlong proxyPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->forceDrawNextFrame();
}

using TextureMap = std::unordered_map<uint32_t, sk_sp<SkImage>>;

struct PictureCaptureState {
    // Each frame we move from the active map to the previous map, essentially an LRU of 1 frame
    // This avoids repeated readbacks of the same image, but avoids artificially extending the
    // lifetime of any particular image.
    TextureMap mActiveMap;
    TextureMap mPreviousActiveMap;
};

// TODO: This & Multi-SKP & Single-SKP should all be de-duped into
// a single "make a SkPicture serializable-safe" utility somewhere
class PictureWrapper : public Picture {
public:
    PictureWrapper(sk_sp<SkPicture>&& src, const std::shared_ptr<PictureCaptureState>& state)
            : Picture(), mPicture(std::move(src)) {
        ATRACE_NAME("Preparing SKP for capture");
        // Move the active to previous active
        state->mPreviousActiveMap = std::move(state->mActiveMap);
        state->mActiveMap.clear();
        SkSerialProcs tempProc;
        tempProc.fImageCtx = state.get();
        tempProc.fImageProc = collectNonTextureImagesProc;
        auto ns = SkNullWStream();
        mPicture->serialize(&ns, &tempProc);
        state->mPreviousActiveMap.clear();

        // Now snapshot a copy of the active map so this PictureWrapper becomes self-sufficient
        mTextureMap = state->mActiveMap;
    }

    static sk_sp<SkImage> imageForCache(SkImage* img) {
        const SkBitmap* bitmap = as_IB(img)->onPeekBitmap();
        // This is a mutable bitmap pretending to be an immutable SkImage. As we're going to
        // actually cross thread boundaries here, make a copy so it's immutable proper
        if (bitmap && !bitmap->isImmutable()) {
            ATRACE_NAME("Copying mutable bitmap");
            return SkImages::RasterFromBitmap(*bitmap);
        }
        if (img->isTextureBacked()) {
            ATRACE_NAME("Readback of texture image");
            return img->makeNonTextureImage();
        }
        SkPixmap pm;
        if (img->isLazyGenerated() && !img->peekPixels(&pm)) {
            ATRACE_NAME("Readback of HW bitmap");
            // This is a hardware bitmap probably
            SkBitmap bm;
            if (!bm.tryAllocPixels(img->imageInfo())) {
                // Failed to allocate, just see what happens
                return sk_ref_sp(img);
            }
            if (RenderProxy::copyImageInto(sk_ref_sp(img), &bm)) {
                // Failed to readback
                return sk_ref_sp(img);
            }
            bm.setImmutable();
#ifdef __ANDROID__
            return SkImages::PinnableRasterFromBitmap(bm);
#else
            return SkMakeImageFromRasterBitmap(bm, kNever_SkCopyPixelsMode);
#endif
        }
        return sk_ref_sp(img);
    }

    static sk_sp<SkData> collectNonTextureImagesProc(SkImage* img, void* ctx) {
        PictureCaptureState* context = reinterpret_cast<PictureCaptureState*>(ctx);
        const uint32_t originalId = img->uniqueID();
        auto it = context->mActiveMap.find(originalId);
        if (it == context->mActiveMap.end()) {
            auto pit = context->mPreviousActiveMap.find(originalId);
            if (pit == context->mPreviousActiveMap.end()) {
                context->mActiveMap[originalId] = imageForCache(img);
            } else {
                context->mActiveMap[originalId] = pit->second;
            }
        }
        return SkData::MakeEmpty();
    }

    static sk_sp<SkData> serializeImage(SkImage* img, void* ctx) {
        PictureWrapper* context = reinterpret_cast<PictureWrapper*>(ctx);
        const uint32_t id = img->uniqueID();
        auto iter = context->mTextureMap.find(id);
        if (iter != context->mTextureMap.end()) {
            img = iter->second.get();
        }
        if (!img) {
            return nullptr;
        }
        // The following encode (specifically the pixel readback) will fail on a
        // texture-backed image. They should already be raster images, but on
        // the off-chance they aren't, we will just serialize it as nothing.
        if (img->isTextureBacked()) {
            return SkData::MakeEmpty();
        }
        return SkPngEncoder::Encode(nullptr, img, {});
    }

    void serialize(SkWStream* stream) const override {
        SkSerialProcs procs;
        procs.fImageProc = serializeImage;
        procs.fImageCtx = const_cast<PictureWrapper*>(this);
        procs.fTypefaceProc = [](SkTypeface* tf, void* ctx) {
            return tf->serialize(SkTypeface::SerializeBehavior::kDoIncludeData);
        };
        mPicture->serialize(stream, &procs);
    }

private:
    sk_sp<SkPicture> mPicture;
    TextureMap mTextureMap;
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
        auto pictureState = std::make_shared<PictureCaptureState>();
        proxy->setPictureCapturedCallback([globalCallbackRef,
                                           pictureState](sk_sp<SkPicture>&& picture) {
            Picture* wrapper = new PictureWrapper{std::move(picture), pictureState};
            globalCallbackRef->env()->CallStaticVoidMethod(
                    gHardwareRenderer.clazz, gHardwareRenderer.invokePictureCapturedCallback,
                    static_cast<jlong>(reinterpret_cast<intptr_t>(wrapper)),
                    globalCallbackRef->object());
        });
    }
}

static void android_view_ThreadedRenderer_setASurfaceTransactionCallback(
        JNIEnv* env, jobject clazz, jlong proxyPtr, jobject aSurfaceTransactionCallback) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    if (!aSurfaceTransactionCallback) {
        proxy->setASurfaceTransactionCallback(nullptr);
    } else {
        JavaVM* vm = nullptr;
        LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&vm) != JNI_OK, "Unable to get Java VM");
        auto globalCallbackRef = std::make_shared<JGlobalRefHolder>(
                vm, env->NewGlobalRef(aSurfaceTransactionCallback));
        proxy->setASurfaceTransactionCallback([globalCallbackRef](int64_t transObj, int64_t scObj,
                                                                  int64_t frameNr) -> bool {
            jboolean ret = globalCallbackRef->env()->CallBooleanMethod(
                    globalCallbackRef->object(), gASurfaceTransactionCallback.onMergeTransaction,
                    static_cast<jlong>(transObj), static_cast<jlong>(scObj),
                    static_cast<jlong>(frameNr));
            return ret;
        });
    }
}

static void android_view_ThreadedRenderer_setPrepareSurfaceControlForWebviewCallback(
        JNIEnv* env, jobject clazz, jlong proxyPtr, jobject callback) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    if (!callback) {
        proxy->setPrepareSurfaceControlForWebviewCallback(nullptr);
    } else {
        JavaVM* vm = nullptr;
        LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&vm) != JNI_OK, "Unable to get Java VM");
        auto globalCallbackRef =
                std::make_shared<JGlobalRefHolder>(vm, env->NewGlobalRef(callback));
        proxy->setPrepareSurfaceControlForWebviewCallback([globalCallbackRef]() {
            globalCallbackRef->env()->CallVoidMethod(
                    globalCallbackRef->object(), gPrepareSurfaceControlForWebviewCallback.prepare);
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
        proxy->setFrameCallback([globalCallbackRef](int32_t syncResult,
                                                    int64_t frameNr) -> std::function<void(bool)> {
            JNIEnv* env = globalCallbackRef->env();
            ScopedLocalRef<jobject> frameCommitCallback(
                    env, env->CallObjectMethod(
                                 globalCallbackRef->object(), gFrameDrawingCallback.onFrameDraw,
                                 static_cast<jint>(syncResult), static_cast<jlong>(frameNr)));
            if (frameCommitCallback == nullptr) {
                return nullptr;
            }
            sp<FrameCommitWrapper> wrapper =
                    sp<FrameCommitWrapper>::make(env, frameCommitCallback.get());
            return [wrapper](bool didProduceBuffer) { wrapper->onFrameCommit(didProduceBuffer); };
        });
    }
}

static void android_view_ThreadedRenderer_setFrameCommitCallback(JNIEnv* env, jobject clazz,
                                                                 jlong proxyPtr, jobject callback) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    if (!callback) {
        proxy->setFrameCommitCallback(nullptr);
    } else {
        sp<FrameCommitWrapper> wrapper = sp<FrameCommitWrapper>::make(env, callback);
        proxy->setFrameCommitCallback(
                [wrapper](bool didProduceBuffer) { wrapper->onFrameCommit(didProduceBuffer); });
    }
}

static void android_view_ThreadedRenderer_setFrameCompleteCallback(JNIEnv* env,
        jobject clazz, jlong proxyPtr, jobject callback) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    if (!callback) {
        proxy->setFrameCompleteCallback(nullptr);
    } else {
        RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
        JavaVM* vm = nullptr;
        LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&vm) != JNI_OK, "Unable to get Java VM");
        auto globalCallbackRef =
                std::make_shared<JGlobalRefHolder>(vm, env->NewGlobalRef(callback));
        proxy->setFrameCompleteCallback([globalCallbackRef]() {
            globalCallbackRef->env()->CallVoidMethod(globalCallbackRef->object(),
                                                     gFrameCompleteCallback.onFrameComplete);
        });
    }
}

class CopyRequestAdapter : public CopyRequest {
public:
    CopyRequestAdapter(JavaVM* vm, jobject jCopyRequest, Rect srcRect)
            : CopyRequest(srcRect), mRefHolder(vm, jCopyRequest) {}

    virtual SkBitmap getDestinationBitmap(int srcWidth, int srcHeight) override {
        jlong bitmapPtr = mRefHolder.env()->CallLongMethod(
                mRefHolder.object(), gCopyRequest.getDestinationBitmap, srcWidth, srcHeight);
        SkBitmap bitmap;
        bitmap::toBitmap(bitmapPtr).getSkBitmap(&bitmap);
        return bitmap;
    }

    virtual void onCopyFinished(CopyResult result) override {
        mRefHolder.env()->CallVoidMethod(mRefHolder.object(), gCopyRequest.onCopyFinished,
                                         static_cast<jint>(result));
    }

private:
    JGlobalRefHolder mRefHolder;
};

static void android_view_ThreadedRenderer_copySurfaceInto(JNIEnv* env, jobject clazz,
                                                          jobject jsurface, jint left, jint top,
                                                          jint right, jint bottom,
                                                          jobject jCopyRequest) {
    JavaVM* vm = nullptr;
    LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&vm) != JNI_OK, "Unable to get Java VM");
    auto copyRequest = std::make_shared<CopyRequestAdapter>(vm, env->NewGlobalRef(jCopyRequest),
                                                            Rect(left, top, right, bottom));
    ANativeWindow* window = fromSurface(env, jsurface);
    RenderProxy::copySurfaceInto(window, std::move(copyRequest));
    ANativeWindow_release(window);
}

class ContextFactory : public IContextFactory {
public:
    virtual AnimationContext* createAnimationContext(renderthread::TimeLord& clock) {
        return new AnimationContext(clock);
    }
};

static jobject android_view_ThreadedRenderer_createHardwareBitmapFromRenderNode(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jint jwidth, jint jheight) {
#ifdef __ANDROID__
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    if (jwidth <= 0 || jheight <= 0) {
        ALOGW("Invalid width %d or height %d", jwidth, jheight);
        return nullptr;
    }

    uint32_t width = jwidth;
    uint32_t height = jheight;

    // Create an ImageReader wired up to a BufferItemConsumer
    AImageReader* rawReader;
    constexpr auto usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                           AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER |
                           AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
    media_status_t result =
            AImageReader_newWithUsage(width, height, AIMAGE_FORMAT_RGBA_8888, usage, 2, &rawReader);
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
                .setVsync(vsync, vsync, UiFrameInfoBuilder::INVALID_VSYNC_ID,
                    UiFrameInfoBuilder::UNKNOWN_DEADLINE,
                    UiFrameInfoBuilder::UNKNOWN_FRAME_INTERVAL)
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
#else
    return nullptr;
#endif
}

static void android_view_ThreadedRenderer_disableVsync(JNIEnv*, jclass) {
    RenderProxy::disableVsync();
}

static void android_view_ThreadedRenderer_setHighContrastText(JNIEnv*, jclass, jboolean enable) {
    Properties::enableHighContrastText = enable;
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

static void android_view_ThreadedRenderer_setForceDark(JNIEnv* env, jobject clazz, jlong proxyPtr,
                                                       jint type) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->setForceDark(static_cast<ForceDarkType>(type));
}

static void android_view_ThreadedRenderer_preload(JNIEnv*, jclass) {
    RenderProxy::preload();
}

static void android_view_ThreadedRenderer_setRtAnimationsEnabled(JNIEnv* env, jobject clazz,
                                                                 jboolean enabled) {
    RenderProxy::setRtAnimationsEnabled(enabled);
}

static void android_view_ThreadedRenderer_notifyCallbackPending(JNIEnv*, jclass, jlong proxyPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->notifyCallbackPending();
}

static void android_view_ThreadedRenderer_notifyExpensiveFrame(JNIEnv*, jclass, jlong proxyPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->notifyExpensiveFrame();
}

// Plumbs the display density down to DeviceInfo.
static void android_view_ThreadedRenderer_setDisplayDensityDpi(JNIEnv*, jclass, jint densityDpi) {
    // Convert from dpi to density-independent pixels.
    const float density = densityDpi / 160.0;
    DeviceInfo::setDensity(density);
}

static void android_view_ThreadedRenderer_initDisplayInfo(
        JNIEnv* env, jclass, jint physicalWidth, jint physicalHeight, jfloat refreshRate,
        jint wideColorDataspace, jlong appVsyncOffsetNanos, jlong presentationDeadlineNanos,
        jboolean supportFp16ForHdr, jboolean supportRgba10101010ForHdr,
        jboolean supportMixedColorSpaces) {
    DeviceInfo::setWidth(physicalWidth);
    DeviceInfo::setHeight(physicalHeight);
    DeviceInfo::setRefreshRate(refreshRate);
    DeviceInfo::setWideColorDataspace(static_cast<ADataSpace>(wideColorDataspace));
    DeviceInfo::setAppVsyncOffsetNanos(appVsyncOffsetNanos);
    DeviceInfo::setPresentationDeadlineNanos(presentationDeadlineNanos);
    DeviceInfo::setSupportFp16ForHdr(supportFp16ForHdr);
    DeviceInfo::setSupportRgba10101010ForHdr(supportRgba10101010ForHdr);
    DeviceInfo::setSupportMixedColorSpaces(supportMixedColorSpaces);
}

static void android_view_ThreadedRenderer_setDrawingEnabled(JNIEnv*, jclass, jboolean enabled) {
    Properties::setDrawingEnabled(enabled);
}

static jboolean android_view_ThreadedRenderer_isDrawingEnabled(JNIEnv*, jclass) {
    return Properties::isDrawingEnabled();
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
#ifdef __ANDROID__
    const char* cacheArray = env->GetStringUTFChars(diskCachePath, NULL);
    android::egl_set_cache_filename(cacheArray);
    env->ReleaseStringUTFChars(diskCachePath, cacheArray);

    const char* skiaCacheArray = env->GetStringUTFChars(skiaDiskCachePath, NULL);
    uirenderer::skiapipeline::ShaderCache::get().setFilename(skiaCacheArray);
    env->ReleaseStringUTFChars(skiaDiskCachePath, skiaCacheArray);
#endif
}

static jboolean android_view_ThreadedRenderer_isWebViewOverlaysEnabled(JNIEnv* env, jobject clazz) {
    // this value is valid only after loadSystemProperties() is called
    return Properties::enableWebViewOverlays;
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/graphics/HardwareRenderer";

static const JNINativeMethod gMethods[] = {
        {"nRotateProcessStatsBuffer", "()V",
         (void*)android_view_ThreadedRenderer_rotateProcessStatsBuffer},
        {"nSetProcessStatsBuffer", "(I)V",
         (void*)android_view_ThreadedRenderer_setProcessStatsBuffer},
        {"nGetRenderThreadTid", "(J)I", (void*)android_view_ThreadedRenderer_getRenderThreadTid},
        {"nCreateRootRenderNode", "()J", (void*)android_view_ThreadedRenderer_createRootRenderNode},
        {"nCreateProxy", "(ZJ)J", (void*)android_view_ThreadedRenderer_createProxy},
        {"nDeleteProxy", "(J)V", (void*)android_view_ThreadedRenderer_deleteProxy},
        {"nLoadSystemProperties", "(J)Z",
         (void*)android_view_ThreadedRenderer_loadSystemProperties},
        {"nSetName", "(JLjava/lang/String;)V", (void*)android_view_ThreadedRenderer_setName},
        {"nSetSurface", "(JLandroid/view/Surface;Z)V",
         (void*)android_view_ThreadedRenderer_setSurface},
        {"nSetSurfaceControl", "(JJ)V", (void*)android_view_ThreadedRenderer_setSurfaceControl},
        {"nPause", "(J)Z", (void*)android_view_ThreadedRenderer_pause},
        {"nSetStopped", "(JZ)V", (void*)android_view_ThreadedRenderer_setStopped},
        {"nSetLightAlpha", "(JFF)V", (void*)android_view_ThreadedRenderer_setLightAlpha},
        {"nSetLightGeometry", "(JFFFF)V", (void*)android_view_ThreadedRenderer_setLightGeometry},
        {"nSetOpaque", "(JZ)V", (void*)android_view_ThreadedRenderer_setOpaque},
        {"nSetColorMode", "(JI)F", (void*)android_view_ThreadedRenderer_setColorMode},
        {"nSetTargetSdrHdrRatio", "(JF)V",
         (void*)android_view_ThreadedRenderer_setTargetSdrHdrRatio},
        {"nSetSdrWhitePoint", "(JF)V", (void*)android_view_ThreadedRenderer_setSdrWhitePoint},
        {"nSetIsHighEndGfx", "(Z)V", (void*)android_view_ThreadedRenderer_setIsHighEndGfx},
        {"nSetIsLowRam", "(Z)V", (void*)android_view_ThreadedRenderer_setIsLowRam},
        {"nSetIsSystemOrPersistent", "(Z)V",
         (void*)android_view_ThreadedRenderer_setIsSystemOrPersistent},
        {"nSyncAndDrawFrame", "(J[JI)I", (void*)android_view_ThreadedRenderer_syncAndDrawFrame},
        {"nDestroy", "(JJ)V", (void*)android_view_ThreadedRenderer_destroy},
        {"nRegisterAnimatingRenderNode", "(JJ)V",
         (void*)android_view_ThreadedRenderer_registerAnimatingRenderNode},
        {"nRegisterVectorDrawableAnimator", "(JJ)V",
         (void*)android_view_ThreadedRenderer_registerVectorDrawableAnimator},
        {"nCreateTextureLayer", "(J)J", (void*)android_view_ThreadedRenderer_createTextureLayer},
        {"nBuildLayer", "(JJ)V", (void*)android_view_ThreadedRenderer_buildLayer},
        {"nCopyLayerInto", "(JJJ)Z", (void*)android_view_ThreadedRenderer_copyLayerInto},
        {"nPushLayerUpdate", "(JJ)V", (void*)android_view_ThreadedRenderer_pushLayerUpdate},
        {"nCancelLayerUpdate", "(JJ)V", (void*)android_view_ThreadedRenderer_cancelLayerUpdate},
        {"nDetachSurfaceTexture", "(JJ)V",
         (void*)android_view_ThreadedRenderer_detachSurfaceTexture},
        {"nDestroyHardwareResources", "(J)V",
         (void*)android_view_ThreadedRenderer_destroyHardwareResources},
        {"nTrimMemory", "(I)V", (void*)android_view_ThreadedRenderer_trimMemory},
        {"nOverrideProperty", "(Ljava/lang/String;Ljava/lang/String;)V",
         (void*)android_view_ThreadedRenderer_overrideProperty},
        {"nFence", "(J)V", (void*)android_view_ThreadedRenderer_fence},
        {"nStopDrawing", "(J)V", (void*)android_view_ThreadedRenderer_stopDrawing},
        {"nNotifyFramePending", "(J)V", (void*)android_view_ThreadedRenderer_notifyFramePending},
        {"nDumpProfileInfo", "(JLjava/io/FileDescriptor;I)V",
         (void*)android_view_ThreadedRenderer_dumpProfileInfo},
        {"nDumpGlobalProfileInfo", "(Ljava/io/FileDescriptor;I)V",
         (void*)android_view_ThreadedRenderer_dumpGlobalProfileInfo},
        {"setupShadersDiskCache", "(Ljava/lang/String;Ljava/lang/String;)V",
         (void*)android_view_ThreadedRenderer_setupShadersDiskCache},
        {"nAddRenderNode", "(JJZ)V", (void*)android_view_ThreadedRenderer_addRenderNode},
        {"nRemoveRenderNode", "(JJ)V", (void*)android_view_ThreadedRenderer_removeRenderNode},
        {"nDrawRenderNode", "(JJ)V", (void*)android_view_ThreadedRendererd_drawRenderNode},
        {"nSetContentDrawBounds", "(JIIII)V",
         (void*)android_view_ThreadedRenderer_setContentDrawBounds},
        {"nForceDrawNextFrame", "(J)V", (void*)android_view_ThreadedRenderer_forceDrawNextFrame},
        {"nSetPictureCaptureCallback",
         "(JLandroid/graphics/HardwareRenderer$PictureCapturedCallback;)V",
         (void*)android_view_ThreadedRenderer_setPictureCapturedCallbackJNI},
        {"nSetASurfaceTransactionCallback",
         "(JLandroid/graphics/HardwareRenderer$ASurfaceTransactionCallback;)V",
         (void*)android_view_ThreadedRenderer_setASurfaceTransactionCallback},
        {"nSetPrepareSurfaceControlForWebviewCallback",
         "(JLandroid/graphics/HardwareRenderer$PrepareSurfaceControlForWebviewCallback;)V",
         (void*)android_view_ThreadedRenderer_setPrepareSurfaceControlForWebviewCallback},
        {"nSetFrameCallback", "(JLandroid/graphics/HardwareRenderer$FrameDrawingCallback;)V",
         (void*)android_view_ThreadedRenderer_setFrameCallback},
        {"nSetFrameCommitCallback", "(JLandroid/graphics/HardwareRenderer$FrameCommitCallback;)V",
         (void*)android_view_ThreadedRenderer_setFrameCommitCallback},
        {"nSetFrameCompleteCallback",
         "(JLandroid/graphics/HardwareRenderer$FrameCompleteCallback;)V",
         (void*)android_view_ThreadedRenderer_setFrameCompleteCallback},
        {"nAddObserver", "(JJ)V", (void*)android_view_ThreadedRenderer_addObserver},
        {"nRemoveObserver", "(JJ)V", (void*)android_view_ThreadedRenderer_removeObserver},
        {"nCopySurfaceInto",
         "(Landroid/view/Surface;IIIILandroid/graphics/HardwareRenderer$CopyRequest;)V",
         (void*)android_view_ThreadedRenderer_copySurfaceInto},
        {"nCreateHardwareBitmap", "(JII)Landroid/graphics/Bitmap;",
         (void*)android_view_ThreadedRenderer_createHardwareBitmapFromRenderNode},
        {"disableVsync", "()V", (void*)android_view_ThreadedRenderer_disableVsync},
        {"nSetHighContrastText", "(Z)V", (void*)android_view_ThreadedRenderer_setHighContrastText},
        {"nSetDebuggingEnabled", "(Z)V", (void*)android_view_ThreadedRenderer_setDebuggingEnabled},
        {"nSetIsolatedProcess", "(Z)V", (void*)android_view_ThreadedRenderer_setIsolatedProcess},
        {"nSetContextPriority", "(I)V", (void*)android_view_ThreadedRenderer_setContextPriority},
        {"nAllocateBuffers", "(J)V", (void*)android_view_ThreadedRenderer_allocateBuffers},
        {"nSetForceDark", "(JI)V", (void*)android_view_ThreadedRenderer_setForceDark},
        {"nSetDisplayDensityDpi", "(I)V",
         (void*)android_view_ThreadedRenderer_setDisplayDensityDpi},
        {"nInitDisplayInfo", "(IIFIJJZZZ)V", (void*)android_view_ThreadedRenderer_initDisplayInfo},
        {"preload", "()V", (void*)android_view_ThreadedRenderer_preload},
        {"isWebViewOverlaysEnabled", "()Z",
         (void*)android_view_ThreadedRenderer_isWebViewOverlaysEnabled},
        {"nSetDrawingEnabled", "(Z)V", (void*)android_view_ThreadedRenderer_setDrawingEnabled},
        {"nIsDrawingEnabled", "()Z", (void*)android_view_ThreadedRenderer_isDrawingEnabled},
        {"nSetRtAnimationsEnabled", "(Z)V",
         (void*)android_view_ThreadedRenderer_setRtAnimationsEnabled},
        {"nNotifyCallbackPending", "(J)V",
         (void*)android_view_ThreadedRenderer_notifyCallbackPending},
        {"nNotifyExpensiveFrame", "(J)V",
         (void*)android_view_ThreadedRenderer_notifyExpensiveFrame},
        {"nTrimCaches", "(I)V", (void*)android_view_ThreadedRenderer_trimCaches},
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

    jclass aSurfaceTransactionCallbackClass =
            FindClassOrDie(env, "android/graphics/HardwareRenderer$ASurfaceTransactionCallback");
    gASurfaceTransactionCallback.onMergeTransaction =
            GetMethodIDOrDie(env, aSurfaceTransactionCallbackClass, "onMergeTransaction", "(JJJ)Z");

    jclass prepareSurfaceControlForWebviewCallbackClass = FindClassOrDie(
            env, "android/graphics/HardwareRenderer$PrepareSurfaceControlForWebviewCallback");
    gPrepareSurfaceControlForWebviewCallback.prepare =
            GetMethodIDOrDie(env, prepareSurfaceControlForWebviewCallbackClass, "prepare", "()V");

    jclass frameCallbackClass = FindClassOrDie(env,
            "android/graphics/HardwareRenderer$FrameDrawingCallback");
    gFrameDrawingCallback.onFrameDraw =
            GetMethodIDOrDie(env, frameCallbackClass, "onFrameDraw",
                             "(IJ)Landroid/graphics/HardwareRenderer$FrameCommitCallback;");

    jclass frameCommitClass =
            FindClassOrDie(env, "android/graphics/HardwareRenderer$FrameCommitCallback");
    gFrameCommitCallback.onFrameCommit =
            GetMethodIDOrDie(env, frameCommitClass, "onFrameCommit", "(Z)V");

    jclass frameCompleteClass = FindClassOrDie(env,
            "android/graphics/HardwareRenderer$FrameCompleteCallback");
    gFrameCompleteCallback.onFrameComplete =
            GetMethodIDOrDie(env, frameCompleteClass, "onFrameComplete", "()V");

    jclass copyRequest = FindClassOrDie(env, "android/graphics/HardwareRenderer$CopyRequest");
    gCopyRequest.onCopyFinished = GetMethodIDOrDie(env, copyRequest, "onCopyFinished", "(I)V");
    gCopyRequest.getDestinationBitmap =
            GetMethodIDOrDie(env, copyRequest, "getDestinationBitmap", "(II)J");

#ifdef __ANDROID__
    void* handle_ = SharedLib::openSharedLib("libandroid");
#else
    void* handle_ = SharedLib::openSharedLib("libandroid_runtime");
#endif
    fromSurface = (ANW_fromSurface)SharedLib::getSymbol(handle_, "ANativeWindow_fromSurface");
    LOG_ALWAYS_FATAL_IF(fromSurface == nullptr,
                        "Failed to find required symbol ANativeWindow_fromSurface!");

    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}

}; // namespace android
