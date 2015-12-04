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

#define LOG_TAG "ThreadedRenderer"

#include <algorithm>

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"
#include <GraphicsJNI.h>
#include <ScopedPrimitiveArray.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <EGL/egl_cache.h>

#include <utils/StrongPointer.h>
#include <android_runtime/android_view_Surface.h>
#include <system/window.h>

#include "android_view_GraphicBuffer.h"

#include <Animator.h>
#include <AnimationContext.h>
#include <IContextFactory.h>
#include <JankTracker.h>
#include <RenderNode.h>
#include <renderthread/CanvasContext.h>
#include <renderthread/RenderProxy.h>
#include <renderthread/RenderTask.h>
#include <renderthread/RenderThread.h>
#include <Vector.h>

namespace android {

using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;

static JNIEnv* getenv(JavaVM* vm) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOG_ALWAYS_FATAL("Failed to get JNIEnv for JavaVM: %p", vm);
    }
    return env;
}

class OnFinishedEvent {
public:
    OnFinishedEvent(BaseRenderNodeAnimator* animator, AnimationListener* listener)
            : animator(animator), listener(listener) {}
    sp<BaseRenderNodeAnimator> animator;
    sp<AnimationListener> listener;
};

class InvokeAnimationListeners : public MessageHandler {
public:
    InvokeAnimationListeners(std::vector<OnFinishedEvent>& events) {
        mOnFinishedEvents.swap(events);
    }

    static void callOnFinished(OnFinishedEvent& event) {
        event.listener->onAnimationFinished(event.animator.get());
    }

    virtual void handleMessage(const Message& message) {
        std::for_each(mOnFinishedEvents.begin(), mOnFinishedEvents.end(), callOnFinished);
        mOnFinishedEvents.clear();
    }

private:
    std::vector<OnFinishedEvent> mOnFinishedEvents;
};

class RenderingException : public MessageHandler {
public:
    RenderingException(JavaVM* vm, const std::string& message)
            : mVm(vm)
            , mMessage(message) {
    }

    virtual void handleMessage(const Message&) {
        throwException(mVm, mMessage);
    }

    static void throwException(JavaVM* vm, const std::string& message) {
        JNIEnv* env = getenv(vm);
        jniThrowException(env, "java/lang/IllegalStateException", message.c_str());
    }

private:
    JavaVM* mVm;
    std::string mMessage;
};

class RootRenderNode : public RenderNode, ErrorHandler {
public:
    RootRenderNode(JNIEnv* env) : RenderNode() {
        mLooper = Looper::getForThread();
        LOG_ALWAYS_FATAL_IF(!mLooper.get(),
                "Must create RootRenderNode on a thread with a looper!");
        env->GetJavaVM(&mVm);
    }

    virtual ~RootRenderNode() {}

    virtual void onError(const std::string& message) {
        mLooper->sendMessage(new RenderingException(mVm, message), 0);
    }

    virtual void prepareTree(TreeInfo& info) {
        info.errorHandler = this;
        RenderNode::prepareTree(info);
        info.errorHandler = NULL;
    }

    void sendMessage(const sp<MessageHandler>& handler) {
        mLooper->sendMessage(handler, 0);
    }

    void attachAnimatingNode(RenderNode* animatingNode) {
        mPendingAnimatingRenderNodes.push_back(animatingNode);
    }

    void doAttachAnimatingNodes(AnimationContext* context) {
        for (size_t i = 0; i < mPendingAnimatingRenderNodes.size(); i++) {
            RenderNode* node = mPendingAnimatingRenderNodes[i].get();
            context->addAnimatingRenderNode(*node);
        }
        mPendingAnimatingRenderNodes.clear();
    }

private:
    sp<Looper> mLooper;
    JavaVM* mVm;
    std::vector< sp<RenderNode> > mPendingAnimatingRenderNodes;
};

class AnimationContextBridge : public AnimationContext {
public:
    AnimationContextBridge(renderthread::TimeLord& clock, RootRenderNode* rootNode)
            : AnimationContext(clock), mRootNode(rootNode) {
    }

    virtual ~AnimationContextBridge() {}

    // Marks the start of a frame, which will update the frame time and move all
    // next frame animations into the current frame
    virtual void startFrame(TreeInfo::TraversalMode mode) {
        if (mode == TreeInfo::MODE_FULL) {
            mRootNode->doAttachAnimatingNodes(this);
        }
        AnimationContext::startFrame(mode);
    }

    // Runs any animations still left in mCurrentFrameAnimations
    virtual void runRemainingAnimations(TreeInfo& info) {
        AnimationContext::runRemainingAnimations(info);
        postOnFinishedEvents();
    }

    virtual void callOnFinished(BaseRenderNodeAnimator* animator, AnimationListener* listener) {
        OnFinishedEvent event(animator, listener);
        mOnFinishedEvents.push_back(event);
    }

    virtual void destroy() {
        AnimationContext::destroy();
        postOnFinishedEvents();
    }

private:
    sp<RootRenderNode> mRootNode;
    std::vector<OnFinishedEvent> mOnFinishedEvents;

    void postOnFinishedEvents() {
        if (mOnFinishedEvents.size()) {
            sp<InvokeAnimationListeners> message
                    = new InvokeAnimationListeners(mOnFinishedEvents);
            mRootNode->sendMessage(message);
        }
    }
};

class ContextFactoryImpl : public IContextFactory {
public:
    ContextFactoryImpl(RootRenderNode* rootNode) : mRootNode(rootNode) {}

    virtual AnimationContext* createAnimationContext(renderthread::TimeLord& clock) {
        return new AnimationContextBridge(clock, mRootNode);
    }

private:
    RootRenderNode* mRootNode;
};

static void android_view_ThreadedRenderer_setAtlas(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jobject graphicBuffer, jlongArray atlasMapArray) {
    sp<GraphicBuffer> buffer = graphicBufferForJavaObject(env, graphicBuffer);
    jsize len = env->GetArrayLength(atlasMapArray);
    if (len <= 0) {
        ALOGW("Failed to initialize atlas, invalid map length: %d", len);
        return;
    }
    int64_t* map = new int64_t[len];
    env->GetLongArrayRegion(atlasMapArray, 0, len, map);

    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->setTextureAtlas(buffer, map, len);
}

static void android_view_ThreadedRenderer_setProcessStatsBuffer(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jint fd) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->setProcessStatsBuffer(fd);
}

static jlong android_view_ThreadedRenderer_createRootRenderNode(JNIEnv* env, jobject clazz) {
    RootRenderNode* node = new RootRenderNode(env);
    node->incStrong(0);
    node->setName("RootRenderNode");
    return reinterpret_cast<jlong>(node);
}

static jlong android_view_ThreadedRenderer_createProxy(JNIEnv* env, jobject clazz,
        jboolean translucent, jlong rootRenderNodePtr) {
    RootRenderNode* rootRenderNode = reinterpret_cast<RootRenderNode*>(rootRenderNodePtr);
    ContextFactoryImpl factory(rootRenderNode);
    return (jlong) new RenderProxy(translucent, rootRenderNode, &factory);
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

static void android_view_ThreadedRenderer_initialize(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jobject jsurface) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    sp<ANativeWindow> window = android_view_Surface_getNativeWindow(env, jsurface);
    proxy->initialize(window);
}

static void android_view_ThreadedRenderer_updateSurface(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jobject jsurface) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    sp<ANativeWindow> window;
    if (jsurface) {
        window = android_view_Surface_getNativeWindow(env, jsurface);
    }
    proxy->updateSurface(window);
}

static jboolean android_view_ThreadedRenderer_pauseSurface(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jobject jsurface) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    sp<ANativeWindow> window;
    if (jsurface) {
        window = android_view_Surface_getNativeWindow(env, jsurface);
    }
    return proxy->pauseSurface(window);
}

static void android_view_ThreadedRenderer_setup(JNIEnv* env, jobject clazz, jlong proxyPtr,
        jint width, jint height, jfloat lightRadius, jint ambientShadowAlpha, jint spotShadowAlpha) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->setup(width, height, lightRadius, ambientShadowAlpha, spotShadowAlpha);
}

static void android_view_ThreadedRenderer_setLightCenter(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jfloat lightX, jfloat lightY, jfloat lightZ) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->setLightCenter((Vector3){lightX, lightY, lightZ});
}

static void android_view_ThreadedRenderer_setOpaque(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jboolean opaque) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->setOpaque(opaque);
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
        jlong proxyPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->destroy();
}

static void android_view_ThreadedRenderer_registerAnimatingRenderNode(JNIEnv* env, jobject clazz,
        jlong rootNodePtr, jlong animatingNodePtr) {
    RootRenderNode* rootRenderNode = reinterpret_cast<RootRenderNode*>(rootNodePtr);
    RenderNode* animatingNode = reinterpret_cast<RenderNode*>(animatingNodePtr);
    rootRenderNode->attachAnimatingNode(animatingNode);
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
        jlong proxyPtr, jlong layerPtr, jobject jbitmap) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(layerPtr);
    SkBitmap bitmap;
    GraphicsJNI::getSkBitmap(env, jbitmap, &bitmap);
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

static void android_view_ThreadedRenderer_dumpProfileData(JNIEnv* env, jobject clazz,
        jbyteArray jdata, jobject javaFileDescriptor) {
    int fd = jniGetFDFromFileDescriptor(env, javaFileDescriptor);
    ScopedByteArrayRO buffer(env, jdata);
    if (buffer.get()) {
        JankTracker::dumpBuffer(buffer.get(), buffer.size(), fd);
    }
}


// ----------------------------------------------------------------------------
// Shaders
// ----------------------------------------------------------------------------

static void android_view_ThreadedRenderer_setupShadersDiskCache(JNIEnv* env, jobject clazz,
        jstring diskCachePath) {

    const char* cacheArray = env->GetStringUTFChars(diskCachePath, NULL);
    egl_cache_t::get()->setCacheFilename(cacheArray);
    env->ReleaseStringUTFChars(diskCachePath, cacheArray);
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/ThreadedRenderer";

static JNINativeMethod gMethods[] = {
    { "nSetAtlas", "(JLandroid/view/GraphicBuffer;[J)V",   (void*) android_view_ThreadedRenderer_setAtlas },
    { "nSetProcessStatsBuffer", "(JI)V", (void*) android_view_ThreadedRenderer_setProcessStatsBuffer },
    { "nCreateRootRenderNode", "()J", (void*) android_view_ThreadedRenderer_createRootRenderNode },
    { "nCreateProxy", "(ZJ)J", (void*) android_view_ThreadedRenderer_createProxy },
    { "nDeleteProxy", "(J)V", (void*) android_view_ThreadedRenderer_deleteProxy },
    { "nLoadSystemProperties", "(J)Z", (void*) android_view_ThreadedRenderer_loadSystemProperties },
    { "nSetName", "(JLjava/lang/String;)V", (void*) android_view_ThreadedRenderer_setName },
    { "nInitialize", "(JLandroid/view/Surface;)V", (void*) android_view_ThreadedRenderer_initialize },
    { "nUpdateSurface", "(JLandroid/view/Surface;)V", (void*) android_view_ThreadedRenderer_updateSurface },
    { "nPauseSurface", "(JLandroid/view/Surface;)Z", (void*) android_view_ThreadedRenderer_pauseSurface },
    { "nSetup", "(JIIFII)V", (void*) android_view_ThreadedRenderer_setup },
    { "nSetLightCenter", "(JFFF)V", (void*) android_view_ThreadedRenderer_setLightCenter },
    { "nSetOpaque", "(JZ)V", (void*) android_view_ThreadedRenderer_setOpaque },
    { "nSyncAndDrawFrame", "(J[JI)I", (void*) android_view_ThreadedRenderer_syncAndDrawFrame },
    { "nDestroy", "(J)V", (void*) android_view_ThreadedRenderer_destroy },
    { "nRegisterAnimatingRenderNode", "(JJ)V", (void*) android_view_ThreadedRenderer_registerAnimatingRenderNode },
    { "nInvokeFunctor", "(JZ)V", (void*) android_view_ThreadedRenderer_invokeFunctor },
    { "nCreateTextureLayer", "(J)J", (void*) android_view_ThreadedRenderer_createTextureLayer },
    { "nBuildLayer", "(JJ)V", (void*) android_view_ThreadedRenderer_buildLayer },
    { "nCopyLayerInto", "(JJLandroid/graphics/Bitmap;)Z", (void*) android_view_ThreadedRenderer_copyLayerInto },
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
    { "nDumpProfileData", "([BLjava/io/FileDescriptor;)V", (void*) android_view_ThreadedRenderer_dumpProfileData },
    { "setupShadersDiskCache", "(Ljava/lang/String;)V",
                (void*) android_view_ThreadedRenderer_setupShadersDiskCache },
};

int register_android_view_ThreadedRenderer(JNIEnv* env) {
    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}

}; // namespace android
