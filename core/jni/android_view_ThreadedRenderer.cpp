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
#define ATRACE_TAG ATRACE_TAG_VIEW

#include <algorithm>
#include <atomic>
#include <inttypes.h>

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"
#include <GraphicsJNI.h>
#include <nativehelper/ScopedPrimitiveArray.h>

#include <gui/BufferItemConsumer.h>
#include <gui/BufferQueue.h>
#include <gui/Surface.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <private/EGL/cache.h>

#include <utils/Looper.h>
#include <utils/RefBase.h>
#include <utils/StrongPointer.h>
#include <utils/Timers.h>
#include <utils/TraceUtils.h>
#include <android_runtime/android_view_Surface.h>
#include <system/window.h>

#include "android_os_MessageQueue.h"

#include <Animator.h>
#include <AnimationContext.h>
#include <FrameInfo.h>
#include <FrameMetricsObserver.h>
#include <IContextFactory.h>
#include <Properties.h>
#include <PropertyValuesAnimatorSet.h>
#include <RenderNode.h>
#include <renderthread/CanvasContext.h>
#include <renderthread/RenderProxy.h>
#include <renderthread/RenderTask.h>
#include <renderthread/RenderThread.h>
#include <pipeline/skia/ShaderCache.h>

namespace android {

using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;

struct {
    jfieldID frameMetrics;
    jfieldID timingDataBuffer;
    jfieldID messageQueue;
    jmethodID callback;
} gFrameMetricsObserverClassInfo;

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

class OnFinishedEvent {
public:
    OnFinishedEvent(BaseRenderNodeAnimator* animator, AnimationListener* listener)
            : animator(animator), listener(listener) {}
    sp<BaseRenderNodeAnimator> animator;
    sp<AnimationListener> listener;
};

class InvokeAnimationListeners : public MessageHandler {
public:
    explicit InvokeAnimationListeners(std::vector<OnFinishedEvent>& events) {
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

class FinishAndInvokeListener : public MessageHandler {
public:
    explicit FinishAndInvokeListener(PropertyValuesAnimatorSet* anim)
            : mAnimator(anim) {
        mListener = anim->getOneShotListener();
        mRequestId = anim->getRequestId();
    }

    virtual void handleMessage(const Message& message) {
        if (mAnimator->getRequestId() == mRequestId) {
            // Request Id has not changed, meaning there's no animation lifecyle change since the
            // message is posted, so go ahead and call finish to make sure the PlayState is properly
            // updated. This is needed because before the next frame comes in from UI thread to
            // trigger an animation update, there could be reverse/cancel etc. So we need to update
            // the playstate in time to ensure all the subsequent events get chained properly.
            mAnimator->end();
        }
        mListener->onAnimationFinished(nullptr);
    }
private:
    sp<PropertyValuesAnimatorSet> mAnimator;
    sp<AnimationListener> mListener;
    uint32_t mRequestId;
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

class FrameCompleteWrapper : public MessageHandler {
public:
    FrameCompleteWrapper(JNIEnv* env, jobject jobject) {
        mLooper = Looper::getForThread();
        LOG_ALWAYS_FATAL_IF(!mLooper.get(), "Must create runnable on a Looper thread!");
        env->GetJavaVM(&mVm);
        mObject = env->NewGlobalRef(jobject);
        LOG_ALWAYS_FATAL_IF(!mObject, "Failed to make global ref");
    }

    virtual ~FrameCompleteWrapper() {
        releaseObject();
    }

    void postFrameComplete(int64_t frameNr) {
        if (mObject) {
            mFrameNr = frameNr;
            mLooper->sendMessage(this, 0);
        }
    }

    virtual void handleMessage(const Message&) {
        if (mObject) {
            ATRACE_FORMAT("frameComplete %" PRId64, mFrameNr);
            getenv(mVm)->CallVoidMethod(mObject, gFrameCompleteCallback.onFrameComplete, mFrameNr);
            releaseObject();
        }
    }

private:
    JavaVM* mVm;
    jobject mObject;
    sp<Looper> mLooper;
    int64_t mFrameNr = -1;

    void releaseObject() {
        if (mObject) {
            getenv(mVm)->DeleteGlobalRef(mObject);
            mObject = nullptr;
        }
    }
};

class RootRenderNode : public RenderNode, ErrorHandler {
public:
    explicit RootRenderNode(JNIEnv* env) : RenderNode() {
        mLooper = Looper::getForThread();
        LOG_ALWAYS_FATAL_IF(!mLooper.get(),
                "Must create RootRenderNode on a thread with a looper!");
        env->GetJavaVM(&mVm);
    }

    virtual ~RootRenderNode() {}

    virtual void onError(const std::string& message) override {
        mLooper->sendMessage(new RenderingException(mVm, message), 0);
    }

    virtual void prepareTree(TreeInfo& info) override {
        info.errorHandler = this;

        for (auto& anim : mRunningVDAnimators) {
            // Assume that the property change in VD from the animators will not be consumed. Mark
            // otherwise if the VDs are found in the display list tree. For VDs that are not in
            // the display list tree, we stop providing animation pulses by 1) removing them from
            // the animation list, 2) post a delayed message to end them at end time so their
            // listeners can receive the corresponding callbacks.
            anim->getVectorDrawable()->setPropertyChangeWillBeConsumed(false);
            // Mark the VD dirty so it will damage itself during prepareTree.
            anim->getVectorDrawable()->markDirty();
        }
        if (info.mode == TreeInfo::MODE_FULL) {
            for (auto &anim : mPausedVDAnimators) {
                anim->getVectorDrawable()->setPropertyChangeWillBeConsumed(false);
                anim->getVectorDrawable()->markDirty();
            }
        }
        // TODO: This is hacky
        info.updateWindowPositions = true;
        RenderNode::prepareTree(info);
        info.updateWindowPositions = false;
        info.errorHandler = nullptr;
    }

    void sendMessage(const sp<MessageHandler>& handler) {
        mLooper->sendMessage(handler, 0);
    }

    void sendMessageDelayed(const sp<MessageHandler>& handler, nsecs_t delayInMs) {
        mLooper->sendMessageDelayed(ms2ns(delayInMs), handler, 0);
    }

    void attachAnimatingNode(RenderNode* animatingNode) {
        mPendingAnimatingRenderNodes.push_back(animatingNode);
    }

    void attachPendingVectorDrawableAnimators() {
        mRunningVDAnimators.insert(mPendingVectorDrawableAnimators.begin(),
                mPendingVectorDrawableAnimators.end());
        mPendingVectorDrawableAnimators.clear();
    }

    void detachAnimators() {
        // Remove animators from the list and post a delayed message in future to end the animator
        // For infinite animators, remove the listener so we no longer hold a global ref to the AVD
        // java object, and therefore the AVD objects in both native and Java can be properly
        // released.
        for (auto& anim : mRunningVDAnimators) {
            detachVectorDrawableAnimator(anim.get());
            anim->clearOneShotListener();
        }
        for (auto& anim : mPausedVDAnimators) {
            anim->clearOneShotListener();
        }
        mRunningVDAnimators.clear();
        mPausedVDAnimators.clear();
    }

    // Move all the animators to the paused list, and send a delayed message to notify the finished
    // listener.
    void pauseAnimators() {
        mPausedVDAnimators.insert(mRunningVDAnimators.begin(), mRunningVDAnimators.end());
        for (auto& anim : mRunningVDAnimators) {
            detachVectorDrawableAnimator(anim.get());
        }
        mRunningVDAnimators.clear();
    }

    void doAttachAnimatingNodes(AnimationContext* context) {
        for (size_t i = 0; i < mPendingAnimatingRenderNodes.size(); i++) {
            RenderNode* node = mPendingAnimatingRenderNodes[i].get();
            context->addAnimatingRenderNode(*node);
        }
        mPendingAnimatingRenderNodes.clear();
    }

    // Run VectorDrawable animators after prepareTree.
    void runVectorDrawableAnimators(AnimationContext* context, TreeInfo& info) {
        // Push staging.
        if (info.mode == TreeInfo::MODE_FULL) {
            pushStagingVectorDrawableAnimators(context);
        }

        // Run the animators in the running list.
        for (auto it = mRunningVDAnimators.begin(); it != mRunningVDAnimators.end();) {
            if ((*it)->animate(*context)) {
                it = mRunningVDAnimators.erase(it);
            } else {
                it++;
            }
        }

        // Run the animators in paused list during full sync.
        if (info.mode == TreeInfo::MODE_FULL) {
            // During full sync we also need to pulse paused animators, in case their targets
            // have been added back to the display list. All the animators that passed the
            // scheduled finish time will be removed from the paused list.
            for (auto it = mPausedVDAnimators.begin(); it != mPausedVDAnimators.end();) {
                if ((*it)->animate(*context)) {
                    // Animator has finished, remove from the list.
                    it = mPausedVDAnimators.erase(it);
                } else {
                    it++;
                }
            }
        }

        // Move the animators with a target not in DisplayList to paused list.
        for (auto it = mRunningVDAnimators.begin(); it != mRunningVDAnimators.end();) {
            if (!(*it)->getVectorDrawable()->getPropertyChangeWillBeConsumed()) {
                // Vector Drawable is not in the display list, we should remove this animator from
                // the list, put it in the paused list, and post a delayed message to end the
                // animator.
                detachVectorDrawableAnimator(it->get());
                mPausedVDAnimators.insert(*it);
                it = mRunningVDAnimators.erase(it);
            } else {
                it++;
            }
        }

        // Move the animators with a target in DisplayList from paused list to running list, and
        // trim paused list.
        if (info.mode == TreeInfo::MODE_FULL) {
            // Check whether any paused animator's target is back in Display List. If so, put the
            // animator back in the running list.
            for (auto it = mPausedVDAnimators.begin(); it != mPausedVDAnimators.end();) {
                if ((*it)->getVectorDrawable()->getPropertyChangeWillBeConsumed()) {
                    mRunningVDAnimators.insert(*it);
                    it = mPausedVDAnimators.erase(it);
                } else {
                    it++;
                }
            }
            // Trim paused VD animators at full sync, so that when Java loses reference to an
            // animator, we know we won't be requested to animate it any more, then we remove such
            // animators from the paused list so they can be properly freed. We also remove the
            // animators from paused list when the time elapsed since start has exceeded duration.
            trimPausedVDAnimators(context);
        }

        info.out.hasAnimations |= !mRunningVDAnimators.empty();
    }

    void trimPausedVDAnimators(AnimationContext* context) {
        // Trim paused vector drawable animator list.
        for (auto it = mPausedVDAnimators.begin(); it != mPausedVDAnimators.end();) {
            // Remove paused VD animator if no one else is referencing it. Note that animators that
            // have passed scheduled finish time are removed from list when they are being pulsed
            // before prepare tree.
            // TODO: this is a bit hacky, need to figure out a better way to track when the paused
            // animators should be freed.
            if ((*it)->getStrongCount() == 1) {
                it = mPausedVDAnimators.erase(it);
            } else {
                it++;
            }
        }
    }

    void pushStagingVectorDrawableAnimators(AnimationContext* context) {
        for (auto& anim : mRunningVDAnimators) {
            anim->pushStaging(*context);
        }
    }

    void destroy() {
        for (auto& renderNode : mPendingAnimatingRenderNodes) {
            renderNode->animators().endAllStagingAnimators();
        }
        mPendingAnimatingRenderNodes.clear();
        mPendingVectorDrawableAnimators.clear();
    }

    void addVectorDrawableAnimator(PropertyValuesAnimatorSet* anim) {
        mPendingVectorDrawableAnimators.insert(anim);
    }

private:
    sp<Looper> mLooper;
    JavaVM* mVm;
    std::vector< sp<RenderNode> > mPendingAnimatingRenderNodes;
    std::set< sp<PropertyValuesAnimatorSet> > mPendingVectorDrawableAnimators;
    std::set< sp<PropertyValuesAnimatorSet> > mRunningVDAnimators;
    // mPausedVDAnimators stores a list of animators that have not yet passed the finish time, but
    // their VectorDrawable targets are no longer in the DisplayList. We skip these animators when
    // render thread runs animators independent of UI thread (i.e. RT_ONLY mode). These animators
    // need to be re-activated once their VD target is added back into DisplayList. Since that could
    // only happen when we do a full sync, we need to make sure to pulse these paused animators at
    // full sync. If any animator's VD target is found in DisplayList during a full sync, we move
    // the animator back to the running list.
    std::set< sp<PropertyValuesAnimatorSet> > mPausedVDAnimators;
    void detachVectorDrawableAnimator(PropertyValuesAnimatorSet* anim) {
        if (anim->isInfinite() || !anim->isRunning()) {
            // Do not need to post anything if the animation is infinite (i.e. no meaningful
            // end listener action), or if the animation has already ended.
            return;
        }
        nsecs_t remainingTimeInMs = anim->getRemainingPlayTime();
        // Post a delayed onFinished event that is scheduled to be handled when the animator ends.
        if (anim->getOneShotListener()) {
            // VectorDrawable's oneshot listener is updated when there are user triggered animation
            // lifecycle changes, such as start(), end(), etc. By using checking and clearing
            // one shot listener, we ensure the same end listener event gets posted only once.
            // Therefore no duplicates. Another benefit of using one shot listener is that no
            // removal is necessary: the end time of animation will not change unless triggered by
            // user events, in which case the already posted listener's id will become stale, and
            // the onFinished callback will then be ignored.
            sp<FinishAndInvokeListener> message
                    = new FinishAndInvokeListener(anim);
            sendMessageDelayed(message, remainingTimeInMs);
            anim->clearOneShotListener();
        }
    }
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
            mRootNode->attachPendingVectorDrawableAnimators();
        }
        AnimationContext::startFrame(mode);
    }

    // Runs any animations still left in mCurrentFrameAnimations
    virtual void runRemainingAnimations(TreeInfo& info) {
        AnimationContext::runRemainingAnimations(info);
        mRootNode->runVectorDrawableAnimators(this, info);
        postOnFinishedEvents();
    }

    virtual void pauseAnimators() override {
        mRootNode->pauseAnimators();
    }

    virtual void callOnFinished(BaseRenderNodeAnimator* animator, AnimationListener* listener) {
        OnFinishedEvent event(animator, listener);
        mOnFinishedEvents.push_back(event);
    }

    virtual void destroy() {
        AnimationContext::destroy();
        mRootNode->detachAnimators();
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
    explicit ContextFactoryImpl(RootRenderNode* rootNode) : mRootNode(rootNode) {}

    virtual AnimationContext* createAnimationContext(renderthread::TimeLord& clock) {
        return new AnimationContextBridge(clock, mRootNode);
    }

private:
    RootRenderNode* mRootNode;
};

class ObserverProxy;

class NotifyHandler : public MessageHandler {
public:
    NotifyHandler(JavaVM* vm, ObserverProxy* observer) : mVm(vm), mObserver(observer) {}

    virtual void handleMessage(const Message& message);

private:
    JavaVM* const mVm;
    ObserverProxy* const mObserver;
};

static jlongArray get_metrics_buffer(JNIEnv* env, jobject observer) {
    jobject frameMetrics = env->GetObjectField(
            observer, gFrameMetricsObserverClassInfo.frameMetrics);
    LOG_ALWAYS_FATAL_IF(frameMetrics == nullptr, "unable to retrieve data sink object");
    jobject buffer = env->GetObjectField(
            frameMetrics, gFrameMetricsObserverClassInfo.timingDataBuffer);
    LOG_ALWAYS_FATAL_IF(buffer == nullptr, "unable to retrieve data sink buffer");
    return reinterpret_cast<jlongArray>(buffer);
}

/*
 * Implements JNI layer for hwui frame metrics reporting.
 */
class ObserverProxy : public FrameMetricsObserver {
public:
    ObserverProxy(JavaVM *vm, jobject observer) : mVm(vm) {
        JNIEnv* env = getenv(mVm);

        mObserverWeak = env->NewWeakGlobalRef(observer);
        LOG_ALWAYS_FATAL_IF(mObserverWeak == nullptr,
                "unable to create frame stats observer reference");

        jlongArray buffer = get_metrics_buffer(env, observer);
        jsize bufferSize = env->GetArrayLength(reinterpret_cast<jarray>(buffer));
        LOG_ALWAYS_FATAL_IF(bufferSize != kBufferSize,
                "Mismatched Java/Native FrameMetrics data format.");

        jobject messageQueueLocal = env->GetObjectField(
                observer, gFrameMetricsObserverClassInfo.messageQueue);
        mMessageQueue = android_os_MessageQueue_getMessageQueue(env, messageQueueLocal);
        LOG_ALWAYS_FATAL_IF(mMessageQueue == nullptr, "message queue not available");

        mMessageHandler = new NotifyHandler(mVm, this);
        LOG_ALWAYS_FATAL_IF(mMessageHandler == nullptr,
                "OOM: unable to allocate NotifyHandler");
    }

    ~ObserverProxy() {
        JNIEnv* env = getenv(mVm);
        env->DeleteWeakGlobalRef(mObserverWeak);
    }

    jweak getObserverReference() {
        return mObserverWeak;
    }

    bool getNextBuffer(JNIEnv* env, jlongArray sink, int* dropCount) {
        FrameMetricsNotification& elem = mRingBuffer[mNextInQueue];

        if (elem.hasData.load()) {
            env->SetLongArrayRegion(sink, 0, kBufferSize, elem.buffer);
            *dropCount = elem.dropCount;
            mNextInQueue = (mNextInQueue + 1) % kRingSize;
            elem.hasData = false;
            return true;
        }

        return false;
    }

    virtual void notify(const int64_t* stats) {
        FrameMetricsNotification& elem = mRingBuffer[mNextFree];

        if (!elem.hasData.load()) {
            memcpy(elem.buffer, stats, kBufferSize * sizeof(stats[0]));

            elem.dropCount = mDroppedReports;
            mDroppedReports = 0;

            incStrong(nullptr);
            mNextFree = (mNextFree + 1) % kRingSize;
            elem.hasData = true;

            mMessageQueue->getLooper()->sendMessage(mMessageHandler, mMessage);
        } else {
            mDroppedReports++;
        }
    }

private:
    static const int kBufferSize = static_cast<int>(FrameInfoIndex::NumIndexes);
    static constexpr int kRingSize = 3;

    class FrameMetricsNotification {
    public:
        FrameMetricsNotification() : hasData(false) {}

        std::atomic_bool hasData;
        int64_t buffer[kBufferSize];
        int dropCount = 0;
    };

    JavaVM* const mVm;
    jweak mObserverWeak;

    sp<MessageQueue> mMessageQueue;
    sp<NotifyHandler> mMessageHandler;
    Message mMessage;

    int mNextFree = 0;
    int mNextInQueue = 0;
    FrameMetricsNotification mRingBuffer[kRingSize];

    int mDroppedReports = 0;
};

void NotifyHandler::handleMessage(const Message& message) {
    JNIEnv* env = getenv(mVm);

    jobject target = env->NewLocalRef(mObserver->getObserverReference());

    if (target != nullptr) {
        jlongArray javaBuffer = get_metrics_buffer(env, target);
        int dropCount = 0;
        while (mObserver->getNextBuffer(env, javaBuffer, &dropCount)) {
            env->CallVoidMethod(target, gFrameMetricsObserverClassInfo.callback, dropCount);
        }
        env->DeleteLocalRef(target);
    }

    mObserver->decStrong(nullptr);
}

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
    sp<Surface> surface = android_view_Surface_getSurface(env, jsurface);
    proxy->initialize(surface);
}

static void android_view_ThreadedRenderer_updateSurface(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jobject jsurface) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    sp<Surface> surface;
    if (jsurface) {
        surface = android_view_Surface_getSurface(env, jsurface);
    }
    proxy->updateSurface(surface);
}

static jboolean android_view_ThreadedRenderer_pauseSurface(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jobject jsurface) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    sp<Surface> surface;
    if (jsurface) {
        surface = android_view_Surface_getSurface(env, jsurface);
    }
    return proxy->pauseSurface(surface);
}

static void android_view_ThreadedRenderer_setStopped(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jboolean stopped) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->setStopped(stopped);
}

static void android_view_ThreadedRenderer_setup(JNIEnv* env, jobject clazz, jlong proxyPtr,
        jfloat lightRadius, jint ambientShadowAlpha, jint spotShadowAlpha) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->setup(lightRadius, ambientShadowAlpha, spotShadowAlpha);
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

static void android_view_ThreadedRenderer_serializeDisplayListTree(JNIEnv* env, jobject clazz,
        jlong proxyPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->serializeDisplayListTree();
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
            wrapper->postFrameComplete(frameNr);
        });
    }
}

static jint android_view_ThreadedRenderer_copySurfaceInto(JNIEnv* env,
        jobject clazz, jobject jsurface, jint left, jint top,
        jint right, jint bottom, jobject jbitmap) {
    SkBitmap bitmap;
    GraphicsJNI::getSkBitmap(env, jbitmap, &bitmap);
    sp<Surface> surface = android_view_Surface_getSurface(env, jsurface);
    return RenderProxy::copySurfaceInto(surface, left, top, right, bottom, &bitmap);
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

    // Create a Surface wired up to a BufferItemConsumer
    sp<IGraphicBufferProducer> producer;
    sp<IGraphicBufferConsumer> rawConsumer;
    BufferQueue::createBufferQueue(&producer, &rawConsumer);
    // We only need 1 buffer but some drivers have bugs so workaround it by setting max count to 2
    rawConsumer->setMaxBufferCount(2);
    sp<BufferItemConsumer> consumer = new BufferItemConsumer(rawConsumer,
            GRALLOC_USAGE_HW_TEXTURE | GRALLOC_USAGE_SW_READ_NEVER | GRALLOC_USAGE_SW_WRITE_NEVER);
    consumer->setDefaultBufferSize(width, height);
    sp<Surface> surface = new Surface(producer);

    // Render into the surface
    {
        ContextFactory factory;
        RenderProxy proxy{true, renderNode, &factory};
        proxy.loadSystemProperties();
        proxy.setSwapBehavior(SwapBehavior::kSwap_discardBuffer);
        proxy.initialize(surface);
        // Shadows can't be used via this interface, so just set the light source
        // to all 0s.
        proxy.setup(0, 0, 0);
        proxy.setLightCenter((Vector3){0, 0, 0});
        nsecs_t vsync = systemTime(CLOCK_MONOTONIC);
        UiFrameInfoBuilder(proxy.frameInfo())
                .setVsync(vsync, vsync)
                .addFlag(FrameInfoFlags::SurfaceCanvas);
        proxy.syncAndDrawFrame();
    }

    // Yank out the GraphicBuffer
    BufferItem bufferItem;
    status_t err;
    if ((err = consumer->acquireBuffer(&bufferItem, 0, true)) != OK) {
        ALOGW("Failed to acquireBuffer, error %d (%s)", err, strerror(-err));
        return nullptr;
    }
    sp<GraphicBuffer> buffer = bufferItem.mGraphicBuffer;
    // We don't really care if this fails or not since we're just going to destroy this anyway
    consumer->releaseBuffer(bufferItem);
    if (!buffer.get()) {
        ALOGW("GraphicBuffer is null?");
        return nullptr;
    }
    if (buffer->getWidth() != width || buffer->getHeight() != height) {
        ALOGW("GraphicBuffer size mismatch, got %dx%d expected %dx%d",
                buffer->getWidth(), buffer->getHeight(), width, height);
        // Continue I guess?
    }
    sk_sp<Bitmap> bitmap = Bitmap::createFrom(buffer);
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
        jlong proxyPtr, jobject jsurface) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    sp<Surface> surface = android_view_Surface_getSurface(env, jsurface);
    proxy->allocateBuffers(surface);
}

// ----------------------------------------------------------------------------
// FrameMetricsObserver
// ----------------------------------------------------------------------------

static jlong android_view_ThreadedRenderer_addFrameMetricsObserver(JNIEnv* env,
        jclass clazz, jlong proxyPtr, jobject fso) {
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK) {
        LOG_ALWAYS_FATAL("Unable to get Java VM");
        return 0;
    }

    renderthread::RenderProxy* renderProxy =
            reinterpret_cast<renderthread::RenderProxy*>(proxyPtr);

    FrameMetricsObserver* observer = new ObserverProxy(vm, fso);
    renderProxy->addFrameMetricsObserver(observer);
    return reinterpret_cast<jlong>(observer);
}

static void android_view_ThreadedRenderer_removeFrameMetricsObserver(JNIEnv* env, jclass clazz,
        jlong proxyPtr, jlong observerPtr) {
    FrameMetricsObserver* observer = reinterpret_cast<FrameMetricsObserver*>(observerPtr);
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

const char* const kClassPathName = "android/view/ThreadedRenderer";

static const JNINativeMethod gMethods[] = {
    { "nRotateProcessStatsBuffer", "()V", (void*) android_view_ThreadedRenderer_rotateProcessStatsBuffer },
    { "nSetProcessStatsBuffer", "(I)V", (void*) android_view_ThreadedRenderer_setProcessStatsBuffer },
    { "nGetRenderThreadTid", "(J)I", (void*) android_view_ThreadedRenderer_getRenderThreadTid },
    { "nCreateRootRenderNode", "()J", (void*) android_view_ThreadedRenderer_createRootRenderNode },
    { "nCreateProxy", "(ZJ)J", (void*) android_view_ThreadedRenderer_createProxy },
    { "nDeleteProxy", "(J)V", (void*) android_view_ThreadedRenderer_deleteProxy },
    { "nLoadSystemProperties", "(J)Z", (void*) android_view_ThreadedRenderer_loadSystemProperties },
    { "nSetName", "(JLjava/lang/String;)V", (void*) android_view_ThreadedRenderer_setName },
    { "nInitialize", "(JLandroid/view/Surface;)V", (void*) android_view_ThreadedRenderer_initialize },
    { "nUpdateSurface", "(JLandroid/view/Surface;)V", (void*) android_view_ThreadedRenderer_updateSurface },
    { "nPauseSurface", "(JLandroid/view/Surface;)Z", (void*) android_view_ThreadedRenderer_pauseSurface },
    { "nSetStopped", "(JZ)V", (void*) android_view_ThreadedRenderer_setStopped },
    { "nSetup", "(JFII)V", (void*) android_view_ThreadedRenderer_setup },
    { "nSetLightCenter", "(JFFF)V", (void*) android_view_ThreadedRenderer_setLightCenter },
    { "nSetOpaque", "(JZ)V", (void*) android_view_ThreadedRenderer_setOpaque },
    { "nSetWideGamut", "(JZ)V", (void*) android_view_ThreadedRenderer_setWideGamut },
    { "nSyncAndDrawFrame", "(J[JI)I", (void*) android_view_ThreadedRenderer_syncAndDrawFrame },
    { "nDestroy", "(JJ)V", (void*) android_view_ThreadedRenderer_destroy },
    { "nRegisterAnimatingRenderNode", "(JJ)V", (void*) android_view_ThreadedRenderer_registerAnimatingRenderNode },
    { "nRegisterVectorDrawableAnimator", "(JJ)V", (void*) android_view_ThreadedRenderer_registerVectorDrawableAnimator },
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
    { "nSerializeDisplayListTree", "(J)V", (void*) android_view_ThreadedRenderer_serializeDisplayListTree },
    { "nDumpProfileInfo", "(JLjava/io/FileDescriptor;I)V", (void*) android_view_ThreadedRenderer_dumpProfileInfo },
    { "setupShadersDiskCache", "(Ljava/lang/String;Ljava/lang/String;)V",
                (void*) android_view_ThreadedRenderer_setupShadersDiskCache },
    { "nAddRenderNode", "(JJZ)V", (void*) android_view_ThreadedRenderer_addRenderNode},
    { "nRemoveRenderNode", "(JJ)V", (void*) android_view_ThreadedRenderer_removeRenderNode},
    { "nDrawRenderNode", "(JJ)V", (void*) android_view_ThreadedRendererd_drawRenderNode},
    { "nSetContentDrawBounds", "(JIIII)V", (void*)android_view_ThreadedRenderer_setContentDrawBounds},
    { "nSetFrameCallback", "(JLandroid/view/ThreadedRenderer$FrameDrawingCallback;)V",
            (void*)android_view_ThreadedRenderer_setFrameCallback},
    { "nSetFrameCompleteCallback", "(JLandroid/view/ThreadedRenderer$FrameCompleteCallback;)V",
            (void*)android_view_ThreadedRenderer_setFrameCompleteCallback },
    { "nAddFrameMetricsObserver",
            "(JLandroid/view/FrameMetricsObserver;)J",
            (void*)android_view_ThreadedRenderer_addFrameMetricsObserver },
    { "nRemoveFrameMetricsObserver",
            "(JJ)V",
            (void*)android_view_ThreadedRenderer_removeFrameMetricsObserver },
    { "nCopySurfaceInto", "(Landroid/view/Surface;IIIILandroid/graphics/Bitmap;)I",
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
    { "nAllocateBuffers", "(JLandroid/view/Surface;)V", (void*)android_view_ThreadedRenderer_allocateBuffers },
};

static JavaVM* mJvm = nullptr;

static void attachRenderThreadToJvm() {
    LOG_ALWAYS_FATAL_IF(!mJvm, "No jvm but we set the hook??");

    JavaVMAttachArgs args;
    args.version = JNI_VERSION_1_4;
    args.name = (char*) "RenderThread";
    args.group = NULL;
    JNIEnv* env;
    mJvm->AttachCurrentThreadAsDaemon(&env, (void*) &args);
}

int register_android_view_ThreadedRenderer(JNIEnv* env) {
    env->GetJavaVM(&mJvm);
    RenderThread::setOnStartHook(&attachRenderThreadToJvm);
    jclass observerClass = FindClassOrDie(env, "android/view/FrameMetricsObserver");
    gFrameMetricsObserverClassInfo.frameMetrics = GetFieldIDOrDie(
            env, observerClass, "mFrameMetrics", "Landroid/view/FrameMetrics;");
    gFrameMetricsObserverClassInfo.messageQueue = GetFieldIDOrDie(
            env, observerClass, "mMessageQueue", "Landroid/os/MessageQueue;");
    gFrameMetricsObserverClassInfo.callback = GetMethodIDOrDie(
            env, observerClass, "notifyDataAvailable", "(I)V");

    jclass metricsClass = FindClassOrDie(env, "android/view/FrameMetrics");
    gFrameMetricsObserverClassInfo.timingDataBuffer = GetFieldIDOrDie(
            env, metricsClass, "mTimingData", "[J");

    jclass frameCallbackClass = FindClassOrDie(env,
            "android/view/ThreadedRenderer$FrameDrawingCallback");
    gFrameDrawingCallback.onFrameDraw = GetMethodIDOrDie(env, frameCallbackClass,
            "onFrameDraw", "(J)V");

    jclass frameCompleteClass = FindClassOrDie(env,
            "android/view/ThreadedRenderer$FrameCompleteCallback");
    gFrameCompleteCallback.onFrameComplete = GetMethodIDOrDie(env, frameCompleteClass,
            "onFrameComplete", "(J)V");

    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}

}; // namespace android
