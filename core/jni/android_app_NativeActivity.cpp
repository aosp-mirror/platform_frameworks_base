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

#define LOG_TAG "NativeActivity"
#include <utils/Log.h>

#include <poll.h>
#include <dlfcn.h>
#include <fcntl.h>

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_Surface.h>
#include <android_runtime/android_app_NativeActivity.h>
#include <android_runtime/android_util_AssetManager.h>
#include <surfaceflinger/Surface.h>
#include <ui/egl/android_natives.h>
#include <ui/InputTransport.h>
#include <utils/Looper.h>

#include "JNIHelp.h"
#include "android_os_MessageQueue.h"
#include "android_view_InputChannel.h"
#include "android_view_KeyEvent.h"

#define LOG_TRACE(...)
//#define LOG_TRACE(...) ALOG(LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace android
{

static struct {
    jmethodID dispatchUnhandledKeyEvent;
    jmethodID preDispatchKeyEvent;
    jmethodID finish;
    jmethodID setWindowFlags;
    jmethodID setWindowFormat;
    jmethodID showIme;
    jmethodID hideIme;
} gNativeActivityClassInfo;

// ------------------------------------------------------------------------

struct ActivityWork {
    int32_t cmd;
    int32_t arg1;
    int32_t arg2;
};

enum {
    CMD_DEF_KEY = 1,
    CMD_FINISH,
    CMD_SET_WINDOW_FORMAT,
    CMD_SET_WINDOW_FLAGS,
    CMD_SHOW_SOFT_INPUT,
    CMD_HIDE_SOFT_INPUT,
};

static void write_work(int fd, int32_t cmd, int32_t arg1=0, int32_t arg2=0) {
    ActivityWork work;
    work.cmd = cmd;
    work.arg1 = arg1;
    work.arg2 = arg2;
    
    LOG_TRACE("write_work: cmd=%d", cmd);

restart:
    int res = write(fd, &work, sizeof(work));
    if (res < 0 && errno == EINTR) {
        goto restart;
    }
    
    if (res == sizeof(work)) return;
    
    if (res < 0) ALOGW("Failed writing to work fd: %s", strerror(errno));
    else ALOGW("Truncated writing to work fd: %d", res);
}

static bool read_work(int fd, ActivityWork* outWork) {
    int res = read(fd, outWork, sizeof(ActivityWork));
    // no need to worry about EINTR, poll loop will just come back again.
    if (res == sizeof(ActivityWork)) return true;
    
    if (res < 0) ALOGW("Failed reading work fd: %s", strerror(errno));
    else ALOGW("Truncated reading work fd: %d", res);
    return false;
}

// ------------------------------------------------------------------------

} // namespace android

using namespace android;

AInputQueue::AInputQueue(const sp<InputChannel>& channel, int workWrite) :
        mWorkWrite(workWrite), mConsumer(channel), mSeq(0) {
    int msgpipe[2];
    if (pipe(msgpipe)) {
        ALOGW("could not create pipe: %s", strerror(errno));
        mDispatchKeyRead = mDispatchKeyWrite = -1;
    } else {
        mDispatchKeyRead = msgpipe[0];
        mDispatchKeyWrite = msgpipe[1];
        int result = fcntl(mDispatchKeyRead, F_SETFL, O_NONBLOCK);
        SLOGW_IF(result != 0, "Could not make AInputQueue read pipe "
                "non-blocking: %s", strerror(errno));
        result = fcntl(mDispatchKeyWrite, F_SETFL, O_NONBLOCK);
        SLOGW_IF(result != 0, "Could not make AInputQueue write pipe "
                "non-blocking: %s", strerror(errno));
    }
}

AInputQueue::~AInputQueue() {
    close(mDispatchKeyRead);
    close(mDispatchKeyWrite);
}

void AInputQueue::attachLooper(ALooper* looper, int ident,
        ALooper_callbackFunc callback, void* data) {
    mLooper = static_cast<android::Looper*>(looper);
    mLooper->addFd(mConsumer.getChannel()->getReceivePipeFd(),
            ident, ALOOPER_EVENT_INPUT, callback, data);
    mLooper->addFd(mDispatchKeyRead,
            ident, ALOOPER_EVENT_INPUT, callback, data);
}

void AInputQueue::detachLooper() {
    mLooper->removeFd(mConsumer.getChannel()->getReceivePipeFd());
    mLooper->removeFd(mDispatchKeyRead);
}

int32_t AInputQueue::hasEvents() {
    struct pollfd pfd[2];

    pfd[0].fd = mConsumer.getChannel()->getReceivePipeFd();
    pfd[0].events = POLLIN;
    pfd[0].revents = 0;
    pfd[1].fd = mDispatchKeyRead;
    pfd[1].events = POLLIN;
    pfd[1].revents = 0;
    
    int nfd = poll(pfd, 2, 0);
    if (nfd <= 0) return 0;
    return ((pfd[0].revents & POLLIN) || (pfd[1].revents & POLLIN)) ? 1 : -1;
}

int32_t AInputQueue::getEvent(AInputEvent** outEvent) {
    *outEvent = NULL;

    bool finishNow = false;

    char byteread;
    ssize_t nRead = read(mDispatchKeyRead, &byteread, 1);
    if (nRead == 1) {
        mLock.lock();
        if (mDispatchingKeys.size() > 0) {
            KeyEvent* kevent = mDispatchingKeys[0];
            *outEvent = kevent;
            mDispatchingKeys.removeAt(0);
            in_flight_event inflight;
            inflight.event = kevent;
            inflight.seq = -1;
            inflight.doFinish = false;
            mInFlightEvents.push(inflight);
        }
        if (mFinishPreDispatches.size() > 0) {
            finish_pre_dispatch finish(mFinishPreDispatches[0]);
            mFinishPreDispatches.removeAt(0);
            const size_t N = mInFlightEvents.size();
            for (size_t i=0; i<N; i++) {
                const in_flight_event& inflight(mInFlightEvents[i]);
                if (inflight.seq == finish.seq) {
                    *outEvent = inflight.event;
                    finishNow = finish.handled;
                }
            }
            if (*outEvent == NULL) {
                ALOGW("getEvent couldn't find inflight for seq %d", finish.seq);
            }
        }
        mLock.unlock();

        if (finishNow) {
            finishEvent(*outEvent, true, false);
            *outEvent = NULL;
            return -1;
        } else if (*outEvent != NULL) {
            return 0;
        }
    }
    
    int32_t res = mConsumer.receiveDispatchSignal();
    if (res != android::OK) {
        ALOGE("channel '%s' ~ Failed to receive dispatch signal.  status=%d",
                mConsumer.getChannel()->getName().string(), res);
        return -1;
    }

    InputEvent* myEvent = NULL;
    res = mConsumer.consume(this, &myEvent);
    if (res != android::OK) {
        ALOGW("channel '%s' ~ Failed to consume input event.  status=%d",
                mConsumer.getChannel()->getName().string(), res);
        mConsumer.sendFinishedSignal(false);
        return -1;
    }

    in_flight_event inflight;
    inflight.event = myEvent;
    inflight.seq = -1;
    inflight.doFinish = true;
    mInFlightEvents.push(inflight);

    *outEvent = myEvent;
    return 0;
}

bool AInputQueue::preDispatchEvent(AInputEvent* event) {
    if (((InputEvent*)event)->getType() != AINPUT_EVENT_TYPE_KEY) {
        // The IME only cares about key events.
        return false;
    }

    // For now we only send system keys to the IME...  this avoids having
    // critical keys like DPAD go through this path.  We really need to have
    // the IME report which keys it wants.
    if (!((KeyEvent*)event)->isSystemKey()) {
        return false;
    }

    return preDispatchKey((KeyEvent*)event);
}

void AInputQueue::finishEvent(AInputEvent* event, bool handled, bool didDefaultHandling) {
    LOG_TRACE("finishEvent: %p handled=%d, didDefaultHandling=%d", event,
            handled ? 1 : 0, didDefaultHandling ? 1 : 0);

    if (!handled && !didDefaultHandling
            && ((InputEvent*)event)->getType() == AINPUT_EVENT_TYPE_KEY
            && ((KeyEvent*)event)->hasDefaultAction()) {
        // The app didn't handle this, but it may have a default action
        // associated with it.  We need to hand this back to Java to be
        // executed.
        doUnhandledKey((KeyEvent*)event);
        return;
    }

    mLock.lock();
    const size_t N = mInFlightEvents.size();
    for (size_t i=0; i<N; i++) {
        const in_flight_event& inflight(mInFlightEvents[i]);
        if (inflight.event == event) {
            if (inflight.doFinish) {
                int32_t res = mConsumer.sendFinishedSignal(handled);
                if (res != android::OK) {
                    ALOGW("Failed to send finished signal on channel '%s'.  status=%d",
                            mConsumer.getChannel()->getName().string(), res);
                }
            }
            if (static_cast<InputEvent*>(event)->getType() == AINPUT_EVENT_TYPE_KEY) {
                mAvailKeyEvents.push(static_cast<KeyEvent*>(event));
            } else {
                mAvailMotionEvents.push(static_cast<MotionEvent*>(event));
            }
            mInFlightEvents.removeAt(i);
            mLock.unlock();
            return;
        }
    }
    mLock.unlock();
    
    ALOGW("finishEvent called for unknown event: %p", event);
}

void AInputQueue::dispatchEvent(android::KeyEvent* event) {
    mLock.lock();
    LOG_TRACE("dispatchEvent: dispatching=%d write=%d\n", mDispatchingKeys.size(),
            mDispatchKeyWrite);
    mDispatchingKeys.add(event);
    wakeupDispatch();
    mLock.unlock();
}

void AInputQueue::finishPreDispatch(int seq, bool handled) {
    mLock.lock();
    LOG_TRACE("finishPreDispatch: seq=%d handled=%d\n", seq, handled ? 1 : 0);
    finish_pre_dispatch finish;
    finish.seq = seq;
    finish.handled = handled;
    mFinishPreDispatches.add(finish);
    wakeupDispatch();
    mLock.unlock();
}

KeyEvent* AInputQueue::consumeUnhandledEvent() {
    KeyEvent* event = NULL;

    mLock.lock();
    if (mUnhandledKeys.size() > 0) {
        event = mUnhandledKeys[0];
        mUnhandledKeys.removeAt(0);
    }
    mLock.unlock();

    LOG_TRACE("consumeUnhandledEvent: KeyEvent=%p", event);

    return event;
}

KeyEvent* AInputQueue::consumePreDispatchingEvent(int* outSeq) {
    KeyEvent* event = NULL;

    mLock.lock();
    if (mPreDispatchingKeys.size() > 0) {
        const in_flight_event& inflight(mPreDispatchingKeys[0]);
        event = static_cast<KeyEvent*>(inflight.event);
        *outSeq = inflight.seq;
        mPreDispatchingKeys.removeAt(0);
    }
    mLock.unlock();

    LOG_TRACE("consumePreDispatchingEvent: KeyEvent=%p", event);

    return event;
}

KeyEvent* AInputQueue::createKeyEvent() {
    mLock.lock();
    KeyEvent* event;
    if (mAvailKeyEvents.size() <= 0) {
        event = new KeyEvent();
    } else {
        event = mAvailKeyEvents.top();
        mAvailKeyEvents.pop();
    }
    mLock.unlock();
    return event;
}

MotionEvent* AInputQueue::createMotionEvent() {
    mLock.lock();
    MotionEvent* event;
    if (mAvailMotionEvents.size() <= 0) {
        event = new MotionEvent();
    } else {
        event = mAvailMotionEvents.top();
        mAvailMotionEvents.pop();
    }
    mLock.unlock();
    return event;
}

void AInputQueue::doUnhandledKey(KeyEvent* keyEvent) {
    mLock.lock();
    LOG_TRACE("Unhandled key: pending=%d write=%d\n", mUnhandledKeys.size(), mWorkWrite);
    if (mUnhandledKeys.size() <= 0 && mWorkWrite >= 0) {
        write_work(mWorkWrite, CMD_DEF_KEY);
    }
    mUnhandledKeys.add(keyEvent);
    mLock.unlock();
}

bool AInputQueue::preDispatchKey(KeyEvent* keyEvent) {
    mLock.lock();
    LOG_TRACE("preDispatch key: pending=%d write=%d\n", mPreDispatchingKeys.size(), mWorkWrite);
    const size_t N = mInFlightEvents.size();
    for (size_t i=0; i<N; i++) {
        in_flight_event& inflight(mInFlightEvents.editItemAt(i));
        if (inflight.event == keyEvent) {
            if (inflight.seq >= 0) {
                // This event has already been pre-dispatched!
                LOG_TRACE("Event already pre-dispatched!");
                mLock.unlock();
                return false;
            }
            mSeq++;
            if (mSeq < 0) mSeq = 1;
            inflight.seq = mSeq;

            if (mPreDispatchingKeys.size() <= 0 && mWorkWrite >= 0) {
                write_work(mWorkWrite, CMD_DEF_KEY);
            }
            mPreDispatchingKeys.add(inflight);
            mLock.unlock();
            return true;
        }
    }

    ALOGW("preDispatchKey called for unknown event: %p", keyEvent);
    return false;
}

void AInputQueue::wakeupDispatch() {
restart:
    char dummy = 0;
    int res = write(mDispatchKeyWrite, &dummy, sizeof(dummy));
    if (res < 0 && errno == EINTR) {
        goto restart;
    }

    if (res == sizeof(dummy)) return;

    if (res < 0) ALOGW("Failed writing to dispatch fd: %s", strerror(errno));
    else ALOGW("Truncated writing to dispatch fd: %d", res);
}

namespace android {

// ------------------------------------------------------------------------

/*
 * Native state for interacting with the NativeActivity class.
 */
struct NativeCode : public ANativeActivity {
    NativeCode(void* _dlhandle, ANativeActivity_createFunc* _createFunc) {
        memset((ANativeActivity*)this, 0, sizeof(ANativeActivity));
        memset(&callbacks, 0, sizeof(callbacks));
        dlhandle = _dlhandle;
        createActivityFunc = _createFunc;
        nativeWindow = NULL;
        inputChannel = NULL;
        nativeInputQueue = NULL;
        mainWorkRead = mainWorkWrite = -1;
    }
    
    ~NativeCode() {
        if (callbacks.onDestroy != NULL) {
            callbacks.onDestroy(this);
        }
        if (env != NULL && clazz != NULL) {
            env->DeleteGlobalRef(clazz);
        }
        if (looper != NULL && mainWorkRead >= 0) {
            looper->removeFd(mainWorkRead);
        }
        if (nativeInputQueue != NULL) {
            nativeInputQueue->mWorkWrite = -1;
        }
        setSurface(NULL);
        setInputChannel(NULL);
        if (mainWorkRead >= 0) close(mainWorkRead);
        if (mainWorkWrite >= 0) close(mainWorkWrite);
        if (dlhandle != NULL) {
            // for now don't unload...  we probably should clean this
            // up and only keep one open dlhandle per proc, since there
            // is really no benefit to unloading the code.
            //dlclose(dlhandle);
        }
    }
    
    void setSurface(jobject _surface) {
        if (_surface != NULL) {
            nativeWindow = android_Surface_getNativeWindow(env, _surface);
        } else {
            nativeWindow = NULL;
        }
    }
    
    status_t setInputChannel(jobject _channel) {
        if (inputChannel != NULL) {
            delete nativeInputQueue;
            env->DeleteGlobalRef(inputChannel);
        }
        inputChannel = NULL;
        nativeInputQueue = NULL;
        if (_channel != NULL) {
            inputChannel = env->NewGlobalRef(_channel);
            sp<InputChannel> ic =
                    android_view_InputChannel_getInputChannel(env, _channel);
            if (ic != NULL) {
                nativeInputQueue = new AInputQueue(ic, mainWorkWrite);
                if (nativeInputQueue->getConsumer().initialize() != android::OK) {
                    delete nativeInputQueue;
                    nativeInputQueue = NULL;
                    return UNKNOWN_ERROR;
                }
            } else {
                return UNKNOWN_ERROR;
            }
        }
        return OK;
    }
    
    ANativeActivityCallbacks callbacks;
    
    void* dlhandle;
    ANativeActivity_createFunc* createActivityFunc;
    
    String8 internalDataPathObj;
    String8 externalDataPathObj;
    String8 obbPathObj;
    
    sp<ANativeWindow> nativeWindow;
    int32_t lastWindowWidth;
    int32_t lastWindowHeight;

    jobject inputChannel;
    struct AInputQueue* nativeInputQueue;
    
    // These are used to wake up the main thread to process work.
    int mainWorkRead;
    int mainWorkWrite;
    sp<Looper> looper;
};

void android_NativeActivity_finish(ANativeActivity* activity) {
    NativeCode* code = static_cast<NativeCode*>(activity);
    write_work(code->mainWorkWrite, CMD_FINISH, 0);
}

void android_NativeActivity_setWindowFormat(
        ANativeActivity* activity, int32_t format) {
    NativeCode* code = static_cast<NativeCode*>(activity);
    write_work(code->mainWorkWrite, CMD_SET_WINDOW_FORMAT, format);
}

void android_NativeActivity_setWindowFlags(
        ANativeActivity* activity, int32_t values, int32_t mask) {
    NativeCode* code = static_cast<NativeCode*>(activity);
    write_work(code->mainWorkWrite, CMD_SET_WINDOW_FLAGS, values, mask);
}

void android_NativeActivity_showSoftInput(
        ANativeActivity* activity, int32_t flags) {
    NativeCode* code = static_cast<NativeCode*>(activity);
    write_work(code->mainWorkWrite, CMD_SHOW_SOFT_INPUT, flags);
}

void android_NativeActivity_hideSoftInput(
        ANativeActivity* activity, int32_t flags) {
    NativeCode* code = static_cast<NativeCode*>(activity);
    write_work(code->mainWorkWrite, CMD_HIDE_SOFT_INPUT, flags);
}

// ------------------------------------------------------------------------

static bool checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
   if (env->ExceptionCheck()) {
       ALOGE("An exception was thrown by callback '%s'.", methodName);
       LOGE_EX(env);
       env->ExceptionClear();
       return true;
   }
   return false;
}

/*
 * Callback for handling native events on the application's main thread.
 */
static int mainWorkCallback(int fd, int events, void* data) {
    NativeCode* code = (NativeCode*)data;
    if ((events & POLLIN) == 0) {
        return 1;
    }
    
    ActivityWork work;
    if (!read_work(code->mainWorkRead, &work)) {
        return 1;
    }

    LOG_TRACE("mainWorkCallback: cmd=%d", work.cmd);

    switch (work.cmd) {
        case CMD_DEF_KEY: {
            KeyEvent* keyEvent;
            while ((keyEvent=code->nativeInputQueue->consumeUnhandledEvent()) != NULL) {
                jobject inputEventObj = android_view_KeyEvent_fromNative(
                        code->env, keyEvent);
                jboolean handled;
                if (inputEventObj) {
                    handled = code->env->CallBooleanMethod(code->clazz,
                            gNativeActivityClassInfo.dispatchUnhandledKeyEvent, inputEventObj);
                    checkAndClearExceptionFromCallback(code->env, "dispatchUnhandledKeyEvent");
                    code->env->DeleteLocalRef(inputEventObj);
                } else {
                    ALOGE("Failed to obtain key event for dispatchUnhandledKeyEvent.");
                    handled = false;
                }
                code->nativeInputQueue->finishEvent(keyEvent, handled, true);
            }
            int seq;
            while ((keyEvent=code->nativeInputQueue->consumePreDispatchingEvent(&seq)) != NULL) {
                jobject inputEventObj = android_view_KeyEvent_fromNative(
                        code->env, keyEvent);
                if (inputEventObj) {
                    code->env->CallVoidMethod(code->clazz,
                            gNativeActivityClassInfo.preDispatchKeyEvent, inputEventObj, seq);
                    checkAndClearExceptionFromCallback(code->env, "preDispatchKeyEvent");
                    code->env->DeleteLocalRef(inputEventObj);
                } else {
                    ALOGE("Failed to obtain key event for preDispatchKeyEvent.");
                }
            }
        } break;
        case CMD_FINISH: {
            code->env->CallVoidMethod(code->clazz, gNativeActivityClassInfo.finish);
            checkAndClearExceptionFromCallback(code->env, "finish");
        } break;
        case CMD_SET_WINDOW_FORMAT: {
            code->env->CallVoidMethod(code->clazz,
                    gNativeActivityClassInfo.setWindowFormat, work.arg1);
            checkAndClearExceptionFromCallback(code->env, "setWindowFormat");
        } break;
        case CMD_SET_WINDOW_FLAGS: {
            code->env->CallVoidMethod(code->clazz,
                    gNativeActivityClassInfo.setWindowFlags, work.arg1, work.arg2);
            checkAndClearExceptionFromCallback(code->env, "setWindowFlags");
        } break;
        case CMD_SHOW_SOFT_INPUT: {
            code->env->CallVoidMethod(code->clazz,
                    gNativeActivityClassInfo.showIme, work.arg1);
            checkAndClearExceptionFromCallback(code->env, "showIme");
        } break;
        case CMD_HIDE_SOFT_INPUT: {
            code->env->CallVoidMethod(code->clazz,
                    gNativeActivityClassInfo.hideIme, work.arg1);
            checkAndClearExceptionFromCallback(code->env, "hideIme");
        } break;
        default:
            ALOGW("Unknown work command: %d", work.cmd);
            break;
    }
    
    return 1;
}

// ------------------------------------------------------------------------

static jint
loadNativeCode_native(JNIEnv* env, jobject clazz, jstring path, jstring funcName,
        jobject messageQueue, jstring internalDataDir, jstring obbDir,
        jstring externalDataDir, int sdkVersion,
        jobject jAssetMgr, jbyteArray savedState)
{
    LOG_TRACE("loadNativeCode_native");

    const char* pathStr = env->GetStringUTFChars(path, NULL);
    NativeCode* code = NULL;
    
    void* handle = dlopen(pathStr, RTLD_LAZY);
    
    env->ReleaseStringUTFChars(path, pathStr);
    
    if (handle != NULL) {
        const char* funcStr = env->GetStringUTFChars(funcName, NULL);
        code = new NativeCode(handle, (ANativeActivity_createFunc*)
                dlsym(handle, funcStr));
        env->ReleaseStringUTFChars(funcName, funcStr);
        
        if (code->createActivityFunc == NULL) {
            ALOGW("ANativeActivity_onCreate not found");
            delete code;
            return 0;
        }
        
        code->looper = android_os_MessageQueue_getLooper(env, messageQueue);
        if (code->looper == NULL) {
            ALOGW("Unable to retrieve MessageQueue's Looper");
            delete code;
            return 0;
        }
        
        int msgpipe[2];
        if (pipe(msgpipe)) {
            ALOGW("could not create pipe: %s", strerror(errno));
            delete code;
            return 0;
        }
        code->mainWorkRead = msgpipe[0];
        code->mainWorkWrite = msgpipe[1];
        int result = fcntl(code->mainWorkRead, F_SETFL, O_NONBLOCK);
        SLOGW_IF(result != 0, "Could not make main work read pipe "
                "non-blocking: %s", strerror(errno));
        result = fcntl(code->mainWorkWrite, F_SETFL, O_NONBLOCK);
        SLOGW_IF(result != 0, "Could not make main work write pipe "
                "non-blocking: %s", strerror(errno));
        code->looper->addFd(code->mainWorkRead, 0, ALOOPER_EVENT_INPUT, mainWorkCallback, code);
        
        code->ANativeActivity::callbacks = &code->callbacks;
        if (env->GetJavaVM(&code->vm) < 0) {
            ALOGW("NativeActivity GetJavaVM failed");
            delete code;
            return 0;
        }
        code->env = env;
        code->clazz = env->NewGlobalRef(clazz);

        const char* dirStr = env->GetStringUTFChars(internalDataDir, NULL);
        code->internalDataPathObj = dirStr;
        code->internalDataPath = code->internalDataPathObj.string();
        env->ReleaseStringUTFChars(internalDataDir, dirStr);
    
        dirStr = env->GetStringUTFChars(externalDataDir, NULL);
        code->externalDataPathObj = dirStr;
        code->externalDataPath = code->externalDataPathObj.string();
        env->ReleaseStringUTFChars(externalDataDir, dirStr);

        code->sdkVersion = sdkVersion;
        
        code->assetManager = assetManagerForJavaObject(env, jAssetMgr);

        dirStr = env->GetStringUTFChars(obbDir, NULL);
        code->obbPathObj = dirStr;
        code->obbPath = code->obbPathObj.string();
        env->ReleaseStringUTFChars(obbDir, dirStr);

        jbyte* rawSavedState = NULL;
        jsize rawSavedSize = 0;
        if (savedState != NULL) {
            rawSavedState = env->GetByteArrayElements(savedState, NULL);
            rawSavedSize = env->GetArrayLength(savedState);
        }

        code->createActivityFunc(code, rawSavedState, rawSavedSize);

        if (rawSavedState != NULL) {
            env->ReleaseByteArrayElements(savedState, rawSavedState, 0);
        }
    }
    
    return (jint)code;
}

static void
unloadNativeCode_native(JNIEnv* env, jobject clazz, jint handle)
{
    LOG_TRACE("unloadNativeCode_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        delete code;
    }
}

static void
onStart_native(JNIEnv* env, jobject clazz, jint handle)
{
    LOG_TRACE("onStart_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onStart != NULL) {
            code->callbacks.onStart(code);
        }
    }
}

static void
onResume_native(JNIEnv* env, jobject clazz, jint handle)
{
    LOG_TRACE("onResume_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onResume != NULL) {
            code->callbacks.onResume(code);
        }
    }
}

static jbyteArray
onSaveInstanceState_native(JNIEnv* env, jobject clazz, jint handle)
{
    LOG_TRACE("onSaveInstanceState_native");

    jbyteArray array = NULL;

    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onSaveInstanceState != NULL) {
            size_t len = 0;
            jbyte* state = (jbyte*)code->callbacks.onSaveInstanceState(code, &len);
            if (len > 0) {
                array = env->NewByteArray(len);
                if (array != NULL) {
                    env->SetByteArrayRegion(array, 0, len, state);
                }
            }
            if (state != NULL) {
                free(state);
            }
        }
    }

    return array;
}

static void
onPause_native(JNIEnv* env, jobject clazz, jint handle)
{
    LOG_TRACE("onPause_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onPause != NULL) {
            code->callbacks.onPause(code);
        }
    }
}

static void
onStop_native(JNIEnv* env, jobject clazz, jint handle)
{
    LOG_TRACE("onStop_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onStop != NULL) {
            code->callbacks.onStop(code);
        }
    }
}

static void
onConfigurationChanged_native(JNIEnv* env, jobject clazz, jint handle)
{
    LOG_TRACE("onConfigurationChanged_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onConfigurationChanged != NULL) {
            code->callbacks.onConfigurationChanged(code);
        }
    }
}

static void
onLowMemory_native(JNIEnv* env, jobject clazz, jint handle)
{
    LOG_TRACE("onLowMemory_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onLowMemory != NULL) {
            code->callbacks.onLowMemory(code);
        }
    }
}

static void
onWindowFocusChanged_native(JNIEnv* env, jobject clazz, jint handle, jboolean focused)
{
    LOG_TRACE("onWindowFocusChanged_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onWindowFocusChanged != NULL) {
            code->callbacks.onWindowFocusChanged(code, focused ? 1 : 0);
        }
    }
}

static void
onSurfaceCreated_native(JNIEnv* env, jobject clazz, jint handle, jobject surface)
{
    LOG_TRACE("onSurfaceCreated_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        code->setSurface(surface);
        if (code->nativeWindow != NULL && code->callbacks.onNativeWindowCreated != NULL) {
            code->callbacks.onNativeWindowCreated(code,
                    code->nativeWindow.get());
        }
    }
}

static int32_t getWindowProp(ANativeWindow* window, int what) {
    int value;
    int res = window->query(window, what, &value);
    return res < 0 ? res : value;
}

static void
onSurfaceChanged_native(JNIEnv* env, jobject clazz, jint handle, jobject surface,
        jint format, jint width, jint height)
{
    LOG_TRACE("onSurfaceChanged_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        sp<ANativeWindow> oldNativeWindow = code->nativeWindow;
        code->setSurface(surface);
        if (oldNativeWindow != code->nativeWindow) {
            if (oldNativeWindow != NULL && code->callbacks.onNativeWindowDestroyed != NULL) {
                code->callbacks.onNativeWindowDestroyed(code,
                        oldNativeWindow.get());
            }
            if (code->nativeWindow != NULL) {
                if (code->callbacks.onNativeWindowCreated != NULL) {
                    code->callbacks.onNativeWindowCreated(code,
                            code->nativeWindow.get());
                }
                code->lastWindowWidth = getWindowProp(code->nativeWindow.get(),
                        NATIVE_WINDOW_WIDTH);
                code->lastWindowHeight = getWindowProp(code->nativeWindow.get(),
                        NATIVE_WINDOW_HEIGHT);
            }
        } else {
            // Maybe it resized?
            int32_t newWidth = getWindowProp(code->nativeWindow.get(),
                    NATIVE_WINDOW_WIDTH);
            int32_t newHeight = getWindowProp(code->nativeWindow.get(),
                    NATIVE_WINDOW_HEIGHT);
            if (newWidth != code->lastWindowWidth
                    || newHeight != code->lastWindowHeight) {
                if (code->callbacks.onNativeWindowResized != NULL) {
                    code->callbacks.onNativeWindowResized(code,
                            code->nativeWindow.get());
                }
            }
        }
    }
}

static void
onSurfaceRedrawNeeded_native(JNIEnv* env, jobject clazz, jint handle)
{
    LOG_TRACE("onSurfaceRedrawNeeded_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->nativeWindow != NULL && code->callbacks.onNativeWindowRedrawNeeded != NULL) {
            code->callbacks.onNativeWindowRedrawNeeded(code, code->nativeWindow.get());
        }
    }
}

static void
onSurfaceDestroyed_native(JNIEnv* env, jobject clazz, jint handle, jobject surface)
{
    LOG_TRACE("onSurfaceDestroyed_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->nativeWindow != NULL && code->callbacks.onNativeWindowDestroyed != NULL) {
            code->callbacks.onNativeWindowDestroyed(code,
                    code->nativeWindow.get());
        }
        code->setSurface(NULL);
    }
}

static void
onInputChannelCreated_native(JNIEnv* env, jobject clazz, jint handle, jobject channel)
{
    LOG_TRACE("onInputChannelCreated_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        status_t err = code->setInputChannel(channel);
        if (err != OK) {
            jniThrowException(env, "java/lang/IllegalStateException",
                    "Error setting input channel");
            return;
        }
        if (code->callbacks.onInputQueueCreated != NULL) {
            code->callbacks.onInputQueueCreated(code,
                    code->nativeInputQueue);
        }
    }
}

static void
onInputChannelDestroyed_native(JNIEnv* env, jobject clazz, jint handle, jobject channel)
{
    LOG_TRACE("onInputChannelDestroyed_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->nativeInputQueue != NULL
                && code->callbacks.onInputQueueDestroyed != NULL) {
            code->callbacks.onInputQueueDestroyed(code,
                    code->nativeInputQueue);
        }
        code->setInputChannel(NULL);
    }
}

static void
onContentRectChanged_native(JNIEnv* env, jobject clazz, jint handle,
        jint x, jint y, jint w, jint h)
{
    LOG_TRACE("onContentRectChanged_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onContentRectChanged != NULL) {
            ARect rect;
            rect.left = x;
            rect.top = y;
            rect.right = x+w;
            rect.bottom = y+h;
            code->callbacks.onContentRectChanged(code, &rect);
        }
    }
}

static void
dispatchKeyEvent_native(JNIEnv* env, jobject clazz, jint handle, jobject eventObj)
{
    LOG_TRACE("dispatchKeyEvent_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->nativeInputQueue != NULL) {
            KeyEvent* event = code->nativeInputQueue->createKeyEvent();
            status_t status = android_view_KeyEvent_toNative(env, eventObj, event);
            if (status) {
                delete event;
                jniThrowRuntimeException(env, "Could not read contents of KeyEvent object.");
                return;
            }
            code->nativeInputQueue->dispatchEvent(event);
        }
    }
}

static void
finishPreDispatchKeyEvent_native(JNIEnv* env, jobject clazz, jint handle,
        jint seq, jboolean handled)
{
    LOG_TRACE("finishPreDispatchKeyEvent_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->nativeInputQueue != NULL) {
            code->nativeInputQueue->finishPreDispatch(seq, handled ? true : false);
        }
    }
}

static const JNINativeMethod g_methods[] = {
    { "loadNativeCode", "(Ljava/lang/String;Ljava/lang/String;Landroid/os/MessageQueue;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILandroid/content/res/AssetManager;[B)I",
            (void*)loadNativeCode_native },
    { "unloadNativeCode", "(I)V", (void*)unloadNativeCode_native },
    { "onStartNative", "(I)V", (void*)onStart_native },
    { "onResumeNative", "(I)V", (void*)onResume_native },
    { "onSaveInstanceStateNative", "(I)[B", (void*)onSaveInstanceState_native },
    { "onPauseNative", "(I)V", (void*)onPause_native },
    { "onStopNative", "(I)V", (void*)onStop_native },
    { "onConfigurationChangedNative", "(I)V", (void*)onConfigurationChanged_native },
    { "onLowMemoryNative", "(I)V", (void*)onLowMemory_native },
    { "onWindowFocusChangedNative", "(IZ)V", (void*)onWindowFocusChanged_native },
    { "onSurfaceCreatedNative", "(ILandroid/view/Surface;)V", (void*)onSurfaceCreated_native },
    { "onSurfaceChangedNative", "(ILandroid/view/Surface;III)V", (void*)onSurfaceChanged_native },
    { "onSurfaceRedrawNeededNative", "(ILandroid/view/Surface;)V", (void*)onSurfaceRedrawNeeded_native },
    { "onSurfaceDestroyedNative", "(I)V", (void*)onSurfaceDestroyed_native },
    { "onInputChannelCreatedNative", "(ILandroid/view/InputChannel;)V", (void*)onInputChannelCreated_native },
    { "onInputChannelDestroyedNative", "(ILandroid/view/InputChannel;)V", (void*)onInputChannelDestroyed_native },
    { "onContentRectChangedNative", "(IIIII)V", (void*)onContentRectChanged_native },
    { "dispatchKeyEventNative", "(ILandroid/view/KeyEvent;)V", (void*)dispatchKeyEvent_native },
    { "finishPreDispatchKeyEventNative", "(IIZ)V", (void*)finishPreDispatchKeyEvent_native },
};

static const char* const kNativeActivityPathName = "android/app/NativeActivity";

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class %s", className);

#define GET_METHOD_ID(var, clazz, methodName, fieldDescriptor) \
        var = env->GetMethodID(clazz, methodName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method" methodName);
        
int register_android_app_NativeActivity(JNIEnv* env)
{
    //ALOGD("register_android_app_NativeActivity");
    jclass clazz;
    FIND_CLASS(clazz, kNativeActivityPathName);

    GET_METHOD_ID(gNativeActivityClassInfo.dispatchUnhandledKeyEvent,
            clazz,
            "dispatchUnhandledKeyEvent", "(Landroid/view/KeyEvent;)Z");
    GET_METHOD_ID(gNativeActivityClassInfo.preDispatchKeyEvent,
            clazz,
            "preDispatchKeyEvent", "(Landroid/view/KeyEvent;I)V");

    GET_METHOD_ID(gNativeActivityClassInfo.finish,
            clazz,
            "finish", "()V");
    GET_METHOD_ID(gNativeActivityClassInfo.setWindowFlags,
            clazz,
            "setWindowFlags", "(II)V");
    GET_METHOD_ID(gNativeActivityClassInfo.setWindowFormat,
            clazz,
            "setWindowFormat", "(I)V");
    GET_METHOD_ID(gNativeActivityClassInfo.showIme,
            clazz,
            "showIme", "(I)V");
    GET_METHOD_ID(gNativeActivityClassInfo.hideIme,
            clazz,
            "hideIme", "(I)V");

    return AndroidRuntime::registerNativeMethods(
        env, kNativeActivityPathName,
        g_methods, NELEM(g_methods));
}

} // namespace android
