/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "InputEventReceiver"

//#define LOG_NDEBUG 0

#include <inttypes.h>

#include <nativehelper/JNIHelp.h>

#include <android-base/stringprintf.h>
#include <android_runtime/AndroidRuntime.h>
#include <input/InputTransport.h>
#include <log/log.h>
#include <utils/Looper.h>
#include <variant>
#include <vector>
#include "android_os_MessageQueue.h"
#include "android_view_InputChannel.h"
#include "android_view_KeyEvent.h"
#include "android_view_MotionEvent.h"

#include <nativehelper/ScopedLocalRef.h>

#include "core_jni_helpers.h"

namespace android {

static const bool kDebugDispatchCycle = false;

static const char* toString(bool value) {
    return value ? "true" : "false";
}

static struct {
    jclass clazz;

    jmethodID dispatchInputEvent;
    jmethodID onFocusEvent;
    jmethodID onPointerCaptureEvent;
    jmethodID onDragEvent;
    jmethodID onBatchedInputEventPending;
} gInputEventReceiverClassInfo;

// Add prefix to the beginning of each line in 'str'
static std::string addPrefix(std::string str, std::string_view prefix) {
    str.insert(0, prefix); // insert at the beginning of the first line
    const size_t prefixLength = prefix.length();
    size_t pos = prefixLength; // just inserted prefix. start at the end of it
    while (true) {             // process all newline characters in 'str'
        pos = str.find('\n', pos);
        if (pos == std::string::npos) {
            break;
        }
        str.insert(pos + 1, prefix); // insert prefix just after the '\n' character
        pos += prefixLength + 1;     // advance the position past the newly inserted prefix
    }
    return str;
}

class NativeInputEventReceiver : public LooperCallback {
public:
    NativeInputEventReceiver(JNIEnv* env, jobject receiverWeak,
                             const std::shared_ptr<InputChannel>& inputChannel,
                             const sp<MessageQueue>& messageQueue);

    status_t initialize();
    void dispose();
    status_t finishInputEvent(uint32_t seq, bool handled);
    status_t reportTimeline(int32_t inputEventId, nsecs_t gpuCompletedTime, nsecs_t presentTime);
    status_t consumeEvents(JNIEnv* env, bool consumeBatches, nsecs_t frameTime,
            bool* outConsumedBatch);
    std::string dump(const char* prefix);

protected:
    virtual ~NativeInputEventReceiver();

private:
    struct Finish {
        uint32_t seq;
        bool handled;
    };

    struct Timeline {
        int32_t inputEventId;
        std::array<nsecs_t, GraphicsTimeline::SIZE> timeline;
    };
    typedef std::variant<Finish, Timeline> OutboundEvent;

    jobject mReceiverWeakGlobal;
    InputConsumer mInputConsumer;
    sp<MessageQueue> mMessageQueue;
    PreallocatedInputEventFactory mInputEventFactory;
    bool mBatchedInputEventPending;
    int mFdEvents;
    std::vector<OutboundEvent> mOutboundQueue;

    void setFdEvents(int events);

    const std::string getInputChannelName() {
        return mInputConsumer.getChannel()->getName();
    }

    status_t processOutboundEvents();
    // From 'LooperCallback'
    int handleEvent(int receiveFd, int events, void* data) override;
};

NativeInputEventReceiver::NativeInputEventReceiver(
        JNIEnv* env, jobject receiverWeak, const std::shared_ptr<InputChannel>& inputChannel,
        const sp<MessageQueue>& messageQueue)
      : mReceiverWeakGlobal(env->NewGlobalRef(receiverWeak)),
        mInputConsumer(inputChannel),
        mMessageQueue(messageQueue),
        mBatchedInputEventPending(false),
        mFdEvents(0) {
    if (kDebugDispatchCycle) {
        ALOGD("channel '%s' ~ Initializing input event receiver.", getInputChannelName().c_str());
    }
}

NativeInputEventReceiver::~NativeInputEventReceiver() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(mReceiverWeakGlobal);
}

status_t NativeInputEventReceiver::initialize() {
    setFdEvents(ALOOPER_EVENT_INPUT);
    return OK;
}

void NativeInputEventReceiver::dispose() {
    if (kDebugDispatchCycle) {
        ALOGD("channel '%s' ~ Disposing input event receiver.", getInputChannelName().c_str());
    }

    setFdEvents(0);
}

status_t NativeInputEventReceiver::finishInputEvent(uint32_t seq, bool handled) {
    if (kDebugDispatchCycle) {
        ALOGD("channel '%s' ~ Finished input event.", getInputChannelName().c_str());
    }

    Finish finish{
            .seq = seq,
            .handled = handled,
    };
    mOutboundQueue.push_back(finish);
    return processOutboundEvents();
}

status_t NativeInputEventReceiver::reportTimeline(int32_t inputEventId, nsecs_t gpuCompletedTime,
                                                  nsecs_t presentTime) {
    if (kDebugDispatchCycle) {
        ALOGD("channel '%s' ~ %s", getInputChannelName().c_str(), __func__);
    }
    std::array<nsecs_t, GraphicsTimeline::SIZE> graphicsTimeline;
    graphicsTimeline[GraphicsTimeline::GPU_COMPLETED_TIME] = gpuCompletedTime;
    graphicsTimeline[GraphicsTimeline::PRESENT_TIME] = presentTime;
    Timeline timeline{
            .inputEventId = inputEventId,
            .timeline = graphicsTimeline,
    };
    mOutboundQueue.push_back(timeline);
    return processOutboundEvents();
}

void NativeInputEventReceiver::setFdEvents(int events) {
    if (mFdEvents != events) {
        mFdEvents = events;
        int fd = mInputConsumer.getChannel()->getFd();
        if (events) {
            mMessageQueue->getLooper()->addFd(fd, 0, events, this, nullptr);
        } else {
            mMessageQueue->getLooper()->removeFd(fd);
        }
    }
}

/**
 * Receiver's primary role is to receive input events, but it has an additional duty of sending
 * 'ack' for events (using the call 'finishInputEvent') and reporting input event timeline.
 *
 * If we are looking at the communication between InputPublisher and InputConsumer, we can say that
 * from the InputConsumer's perspective, InputMessage's that are sent from publisher to consumer are
 * called 'inbound / incoming' events, and the InputMessage's sent from InputConsumer to
 * InputPublisher are 'outbound / outgoing' events.
 *
 * NativeInputEventReceiver owns (and acts like) an InputConsumer. So the finish events are outbound
 * from InputEventReceiver (and will be sent to the InputPublisher). Likewise, timeline events are
 * outbound events.
 *
 * In this function, send as many events from 'mOutboundQueue' as possible across the socket to the
 * InputPublisher. If no events are remaining, let the looper know so that it doesn't wake up
 * unnecessarily.
 */
status_t NativeInputEventReceiver::processOutboundEvents() {
    while (!mOutboundQueue.empty()) {
        OutboundEvent& outbound = *mOutboundQueue.begin();
        status_t status;

        if (std::holds_alternative<Finish>(outbound)) {
            const Finish& finish = std::get<Finish>(outbound);
            status = mInputConsumer.sendFinishedSignal(finish.seq, finish.handled);
        } else if (std::holds_alternative<Timeline>(outbound)) {
            const Timeline& timeline = std::get<Timeline>(outbound);
            status = mInputConsumer.sendTimeline(timeline.inputEventId, timeline.timeline);
        } else {
            LOG_ALWAYS_FATAL("Unexpected event type in std::variant");
            status = BAD_VALUE;
        }
        if (status == OK) {
            // Successful send. Erase the entry and keep trying to send more
            mOutboundQueue.erase(mOutboundQueue.begin());
            continue;
        }

        // Publisher is busy, try again later. Keep this entry (do not erase)
        if (status == WOULD_BLOCK) {
            if (kDebugDispatchCycle) {
                ALOGD("channel '%s' ~ Remaining outbound events: %zu.",
                      getInputChannelName().c_str(), mOutboundQueue.size());
            }
            setFdEvents(ALOOPER_EVENT_INPUT | ALOOPER_EVENT_OUTPUT);
            return WOULD_BLOCK; // try again later
        }

        // Some other error. Give up
        ALOGW("Failed to send outbound event on channel '%s'.  status=%s(%d)",
              getInputChannelName().c_str(), statusToString(status).c_str(), status);
        if (status != DEAD_OBJECT) {
            JNIEnv* env = AndroidRuntime::getJNIEnv();
            std::string message =
                    android::base::StringPrintf("Failed to send outbound event.  status=%s(%d)",
                                                statusToString(status).c_str(), status);
            jniThrowRuntimeException(env, message.c_str());
            mMessageQueue->raiseAndClearException(env, "finishInputEvent");
        }
        return status;
    }

    // The queue is now empty. Tell looper there's no more output to expect.
    setFdEvents(ALOOPER_EVENT_INPUT);
    return OK;
}

int NativeInputEventReceiver::handleEvent(int receiveFd, int events, void* data) {
    // Allowed return values of this function as documented in LooperCallback::handleEvent
    constexpr int REMOVE_CALLBACK = 0;
    constexpr int KEEP_CALLBACK = 1;

    if (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP)) {
        // This error typically occurs when the publisher has closed the input channel
        // as part of removing a window or finishing an IME session, in which case
        // the consumer will soon be disposed as well.
        if (kDebugDispatchCycle) {
            ALOGD("channel '%s' ~ Publisher closed input channel or an error occurred. events=0x%x",
                  getInputChannelName().c_str(), events);
        }
        return REMOVE_CALLBACK;
    }

    if (events & ALOOPER_EVENT_INPUT) {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        status_t status = consumeEvents(env, false /*consumeBatches*/, -1, nullptr);
        mMessageQueue->raiseAndClearException(env, "handleReceiveCallback");
        return status == OK || status == NO_MEMORY ? KEEP_CALLBACK : REMOVE_CALLBACK;
    }

    if (events & ALOOPER_EVENT_OUTPUT) {
        const status_t status = processOutboundEvents();
        if (status == OK || status == WOULD_BLOCK) {
            return KEEP_CALLBACK;
        } else {
            return REMOVE_CALLBACK;
        }
    }

    ALOGW("channel '%s' ~ Received spurious callback for unhandled poll event.  events=0x%x",
          getInputChannelName().c_str(), events);
    return KEEP_CALLBACK;
}

status_t NativeInputEventReceiver::consumeEvents(JNIEnv* env,
        bool consumeBatches, nsecs_t frameTime, bool* outConsumedBatch) {
    if (kDebugDispatchCycle) {
        ALOGD("channel '%s' ~ Consuming input events, consumeBatches=%s, frameTime=%" PRId64,
              getInputChannelName().c_str(), toString(consumeBatches), frameTime);
    }

    if (consumeBatches) {
        mBatchedInputEventPending = false;
    }
    if (outConsumedBatch) {
        *outConsumedBatch = false;
    }

    ScopedLocalRef<jobject> receiverObj(env, nullptr);
    bool skipCallbacks = false;
    for (;;) {
        uint32_t seq;
        InputEvent* inputEvent;

        status_t status = mInputConsumer.consume(&mInputEventFactory,
                consumeBatches, frameTime, &seq, &inputEvent);
        if (status != OK && status != WOULD_BLOCK) {
            ALOGE("channel '%s' ~ Failed to consume input event.  status=%s(%d)",
                  getInputChannelName().c_str(), statusToString(status).c_str(), status);
            return status;
        }

        if (status == WOULD_BLOCK) {
            if (!skipCallbacks && !mBatchedInputEventPending && mInputConsumer.hasPendingBatch()) {
                // There is a pending batch.  Come back later.
                if (!receiverObj.get()) {
                    receiverObj.reset(jniGetReferent(env, mReceiverWeakGlobal));
                    if (!receiverObj.get()) {
                        ALOGW("channel '%s' ~ Receiver object was finalized "
                              "without being disposed.",
                              getInputChannelName().c_str());
                        return DEAD_OBJECT;
                    }
                }

                mBatchedInputEventPending = true;
                if (kDebugDispatchCycle) {
                    ALOGD("channel '%s' ~ Dispatching batched input event pending notification.",
                          getInputChannelName().c_str());
                }

                env->CallVoidMethod(receiverObj.get(),
                                    gInputEventReceiverClassInfo.onBatchedInputEventPending,
                                    mInputConsumer.getPendingBatchSource());
                if (env->ExceptionCheck()) {
                    ALOGE("Exception dispatching batched input events.");
                    mBatchedInputEventPending = false; // try again later
                }
            }
            return OK;
        }
        assert(inputEvent);

        if (!skipCallbacks) {
            if (!receiverObj.get()) {
                receiverObj.reset(jniGetReferent(env, mReceiverWeakGlobal));
                if (!receiverObj.get()) {
                    ALOGW("channel '%s' ~ Receiver object was finalized "
                            "without being disposed.", getInputChannelName().c_str());
                    return DEAD_OBJECT;
                }
            }

            jobject inputEventObj;
            switch (inputEvent->getType()) {
            case AINPUT_EVENT_TYPE_KEY:
                if (kDebugDispatchCycle) {
                    ALOGD("channel '%s' ~ Received key event.", getInputChannelName().c_str());
                }
                inputEventObj = android_view_KeyEvent_fromNative(env,
                        static_cast<KeyEvent*>(inputEvent));
                break;

            case AINPUT_EVENT_TYPE_MOTION: {
                if (kDebugDispatchCycle) {
                    ALOGD("channel '%s' ~ Received motion event.", getInputChannelName().c_str());
                }
                MotionEvent* motionEvent = static_cast<MotionEvent*>(inputEvent);
                if ((motionEvent->getAction() & AMOTION_EVENT_ACTION_MOVE) && outConsumedBatch) {
                    *outConsumedBatch = true;
                }
                inputEventObj = android_view_MotionEvent_obtainAsCopy(env, motionEvent);
                break;
            }
            case AINPUT_EVENT_TYPE_FOCUS: {
                FocusEvent* focusEvent = static_cast<FocusEvent*>(inputEvent);
                if (kDebugDispatchCycle) {
                    ALOGD("channel '%s' ~ Received focus event: hasFocus=%s, inTouchMode=%s.",
                          getInputChannelName().c_str(), toString(focusEvent->getHasFocus()),
                          toString(focusEvent->getInTouchMode()));
                }
                env->CallVoidMethod(receiverObj.get(), gInputEventReceiverClassInfo.onFocusEvent,
                                    jboolean(focusEvent->getHasFocus()),
                                    jboolean(focusEvent->getInTouchMode()));
                finishInputEvent(seq, true /* handled */);
                continue;
            }
            case AINPUT_EVENT_TYPE_CAPTURE: {
                const CaptureEvent* captureEvent = static_cast<CaptureEvent*>(inputEvent);
                if (kDebugDispatchCycle) {
                    ALOGD("channel '%s' ~ Received capture event: pointerCaptureEnabled=%s",
                          getInputChannelName().c_str(),
                          toString(captureEvent->getPointerCaptureEnabled()));
                }
                env->CallVoidMethod(receiverObj.get(),
                                    gInputEventReceiverClassInfo.onPointerCaptureEvent,
                                    jboolean(captureEvent->getPointerCaptureEnabled()));
                finishInputEvent(seq, true /* handled */);
                continue;
            }
            case AINPUT_EVENT_TYPE_DRAG: {
                const DragEvent* dragEvent = static_cast<DragEvent*>(inputEvent);
                if (kDebugDispatchCycle) {
                    ALOGD("channel '%s' ~ Received drag event: isExiting=%s",
                          getInputChannelName().c_str(), toString(dragEvent->isExiting()));
                }
                env->CallVoidMethod(receiverObj.get(), gInputEventReceiverClassInfo.onDragEvent,
                                    jboolean(dragEvent->isExiting()), dragEvent->getX(),
                                    dragEvent->getY());
                finishInputEvent(seq, true /* handled */);
                continue;
            }

            default:
                assert(false); // InputConsumer should prevent this from ever happening
                inputEventObj = nullptr;
            }

            if (inputEventObj) {
                if (kDebugDispatchCycle) {
                    ALOGD("channel '%s' ~ Dispatching input event.", getInputChannelName().c_str());
                }
                env->CallVoidMethod(receiverObj.get(),
                        gInputEventReceiverClassInfo.dispatchInputEvent, seq, inputEventObj);
                if (env->ExceptionCheck()) {
                    ALOGE("Exception dispatching input event.");
                    skipCallbacks = true;
                }
                env->DeleteLocalRef(inputEventObj);
            } else {
                ALOGW("channel '%s' ~ Failed to obtain event object.",
                        getInputChannelName().c_str());
                skipCallbacks = true;
            }
        }

        if (skipCallbacks) {
            mInputConsumer.sendFinishedSignal(seq, false);
        }
    }
}

std::string NativeInputEventReceiver::dump(const char* prefix) {
    std::string out;
    std::string consumerDump = addPrefix(mInputConsumer.dump(), "  ");
    out = out + "mInputConsumer:\n" + consumerDump + "\n";

    out += android::base::StringPrintf("mBatchedInputEventPending: %s\n",
                                       toString(mBatchedInputEventPending));
    out = out + "mOutboundQueue:\n";
    for (const OutboundEvent& outbound : mOutboundQueue) {
        if (std::holds_alternative<Finish>(outbound)) {
            const Finish& finish = std::get<Finish>(outbound);
            out += android::base::StringPrintf("  Finish: seq=%" PRIu32 " handled=%s\n", finish.seq,
                                               toString(finish.handled));
        } else if (std::holds_alternative<Timeline>(outbound)) {
            const Timeline& timeline = std::get<Timeline>(outbound);
            out += android::base::
                    StringPrintf("  Timeline: inputEventId=%" PRId32 " gpuCompletedTime=%" PRId64
                                 ", presentTime=%" PRId64 "\n",
                                 timeline.inputEventId,
                                 timeline.timeline[GraphicsTimeline::GPU_COMPLETED_TIME],
                                 timeline.timeline[GraphicsTimeline::PRESENT_TIME]);
        }
    }
    if (mOutboundQueue.empty()) {
        out = out + "  <empty>\n";
    }
    return addPrefix(out, prefix);
}

static jlong nativeInit(JNIEnv* env, jclass clazz, jobject receiverWeak,
        jobject inputChannelObj, jobject messageQueueObj) {
    std::shared_ptr<InputChannel> inputChannel =
            android_view_InputChannel_getInputChannel(env, inputChannelObj);
    if (inputChannel == nullptr) {
        jniThrowRuntimeException(env, "InputChannel is not initialized.");
        return 0;
    }

    sp<MessageQueue> messageQueue = android_os_MessageQueue_getMessageQueue(env, messageQueueObj);
    if (messageQueue == nullptr) {
        jniThrowRuntimeException(env, "MessageQueue is not initialized.");
        return 0;
    }

    sp<NativeInputEventReceiver> receiver = new NativeInputEventReceiver(env,
            receiverWeak, inputChannel, messageQueue);
    status_t status = receiver->initialize();
    if (status) {
        std::string message = android::base::
                StringPrintf("Failed to initialize input event receiver.  status=%s(%d)",
                             statusToString(status).c_str(), status);
        jniThrowRuntimeException(env, message.c_str());
        return 0;
    }

    receiver->incStrong(gInputEventReceiverClassInfo.clazz); // retain a reference for the object
    return reinterpret_cast<jlong>(receiver.get());
}

static void nativeDispose(JNIEnv* env, jclass clazz, jlong receiverPtr) {
    sp<NativeInputEventReceiver> receiver =
            reinterpret_cast<NativeInputEventReceiver*>(receiverPtr);
    receiver->dispose();
    receiver->decStrong(gInputEventReceiverClassInfo.clazz); // drop reference held by the object
}

static void nativeFinishInputEvent(JNIEnv* env, jclass clazz, jlong receiverPtr,
        jint seq, jboolean handled) {
    sp<NativeInputEventReceiver> receiver =
            reinterpret_cast<NativeInputEventReceiver*>(receiverPtr);
    status_t status = receiver->finishInputEvent(seq, handled);
    if (status == OK || status == WOULD_BLOCK) {
        return; // normal operation
    }
    if (status != DEAD_OBJECT) {
        std::string message =
                android::base::StringPrintf("Failed to finish input event.  status=%s(%d)",
                                            statusToString(status).c_str(), status);
        jniThrowRuntimeException(env, message.c_str());
    }
}

static void nativeReportTimeline(JNIEnv* env, jclass clazz, jlong receiverPtr, jint inputEventId,
                                 jlong gpuCompletedTime, jlong presentTime) {
    if (IdGenerator::getSource(inputEventId) != IdGenerator::Source::INPUT_READER) {
        // skip this event, it did not originate from hardware
        return;
    }
    sp<NativeInputEventReceiver> receiver =
            reinterpret_cast<NativeInputEventReceiver*>(receiverPtr);
    status_t status = receiver->reportTimeline(inputEventId, gpuCompletedTime, presentTime);
    if (status == OK || status == WOULD_BLOCK) {
        return; // normal operation
    }
    if (status != DEAD_OBJECT) {
        std::string message = android::base::StringPrintf("Failed to send timeline.  status=%s(%d)",
                                                          strerror(-status), status);
        jniThrowRuntimeException(env, message.c_str());
    }
}

static jboolean nativeConsumeBatchedInputEvents(JNIEnv* env, jclass clazz, jlong receiverPtr,
        jlong frameTimeNanos) {
    sp<NativeInputEventReceiver> receiver =
            reinterpret_cast<NativeInputEventReceiver*>(receiverPtr);
    bool consumedBatch;
    status_t status = receiver->consumeEvents(env, true /*consumeBatches*/, frameTimeNanos,
            &consumedBatch);
    if (status && status != DEAD_OBJECT && !env->ExceptionCheck()) {
        std::string message =
                android::base::StringPrintf("Failed to consume batched input event.  status=%s(%d)",
                                            statusToString(status).c_str(), status);
        jniThrowRuntimeException(env, message.c_str());
        return JNI_FALSE;
    }
    return consumedBatch ? JNI_TRUE : JNI_FALSE;
}

static jstring nativeDump(JNIEnv* env, jclass clazz, jlong receiverPtr, jstring prefix) {
    sp<NativeInputEventReceiver> receiver =
            reinterpret_cast<NativeInputEventReceiver*>(receiverPtr);
    ScopedUtfChars prefixChars(env, prefix);
    return env->NewStringUTF(receiver->dump(prefixChars.c_str()).c_str());
}

static const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        {"nativeInit",
         "(Ljava/lang/ref/WeakReference;Landroid/view/InputChannel;Landroid/os/MessageQueue;)J",
         (void*)nativeInit},
        {"nativeDispose", "(J)V", (void*)nativeDispose},
        {"nativeFinishInputEvent", "(JIZ)V", (void*)nativeFinishInputEvent},
        {"nativeReportTimeline", "(JIJJ)V", (void*)nativeReportTimeline},
        {"nativeConsumeBatchedInputEvents", "(JJ)Z", (void*)nativeConsumeBatchedInputEvents},
        {"nativeDump", "(JLjava/lang/String;)Ljava/lang/String;", (void*)nativeDump},
};

int register_android_view_InputEventReceiver(JNIEnv* env) {
    int res = RegisterMethodsOrDie(env, "android/view/InputEventReceiver",
            gMethods, NELEM(gMethods));

    jclass clazz = FindClassOrDie(env, "android/view/InputEventReceiver");
    gInputEventReceiverClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);

    gInputEventReceiverClassInfo.dispatchInputEvent = GetMethodIDOrDie(env,
            gInputEventReceiverClassInfo.clazz,
            "dispatchInputEvent", "(ILandroid/view/InputEvent;)V");
    gInputEventReceiverClassInfo.onFocusEvent =
            GetMethodIDOrDie(env, gInputEventReceiverClassInfo.clazz, "onFocusEvent", "(ZZ)V");
    gInputEventReceiverClassInfo.onPointerCaptureEvent =
            GetMethodIDOrDie(env, gInputEventReceiverClassInfo.clazz, "onPointerCaptureEvent",
                             "(Z)V");
    gInputEventReceiverClassInfo.onDragEvent =
            GetMethodIDOrDie(env, gInputEventReceiverClassInfo.clazz, "onDragEvent", "(ZFF)V");
    gInputEventReceiverClassInfo.onBatchedInputEventPending =
            GetMethodIDOrDie(env, gInputEventReceiverClassInfo.clazz, "onBatchedInputEventPending",
                             "(I)V");

    return res;
}

} // namespace android
