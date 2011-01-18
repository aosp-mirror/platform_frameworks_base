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

#define LOG_TAG "InputManager-JNI"

//#define LOG_NDEBUG 0

// Log debug messages about InputReaderPolicy
#define DEBUG_INPUT_READER_POLICY 0

// Log debug messages about InputDispatcherPolicy
#define DEBUG_INPUT_DISPATCHER_POLICY 0

#include "JNIHelp.h"
#include "jni.h"
#include <limits.h>
#include <android_runtime/AndroidRuntime.h>
#include <ui/InputReader.h>
#include <ui/InputDispatcher.h>
#include <ui/InputManager.h>
#include <ui/InputTransport.h>
#include <utils/Log.h>
#include <utils/threads.h>
#include "../../core/jni/android_view_KeyEvent.h"
#include "../../core/jni/android_view_MotionEvent.h"
#include "../../core/jni/android_view_InputChannel.h"
#include "com_android_server_PowerManagerService.h"

namespace android {

// ----------------------------------------------------------------------------

static struct {
    jclass clazz;

    jmethodID notifyConfigurationChanged;
    jmethodID notifyLidSwitchChanged;
    jmethodID notifyInputChannelBroken;
    jmethodID notifyANR;
    jmethodID interceptKeyBeforeQueueing;
    jmethodID interceptKeyBeforeDispatching;
    jmethodID checkInjectEventsPermission;
    jmethodID filterTouchEvents;
    jmethodID filterJumpyTouchEvents;
    jmethodID getVirtualKeyQuietTimeMillis;
    jmethodID getVirtualKeyDefinitions;
    jmethodID getInputDeviceCalibration;
    jmethodID getExcludedDeviceNames;
    jmethodID getMaxEventsPerSecond;
} gCallbacksClassInfo;

static struct {
    jclass clazz;

    jfieldID scanCode;
    jfieldID centerX;
    jfieldID centerY;
    jfieldID width;
    jfieldID height;
} gVirtualKeyDefinitionClassInfo;

static struct {
    jclass clazz;

    jfieldID keys;
    jfieldID values;
} gInputDeviceCalibrationClassInfo;

static struct {
    jclass clazz;

    jfieldID inputChannel;
    jfieldID name;
    jfieldID layoutParamsFlags;
    jfieldID layoutParamsType;
    jfieldID dispatchingTimeoutNanos;
    jfieldID frameLeft;
    jfieldID frameTop;
    jfieldID frameRight;
    jfieldID frameBottom;
    jfieldID visibleFrameLeft;
    jfieldID visibleFrameTop;
    jfieldID visibleFrameRight;
    jfieldID visibleFrameBottom;
    jfieldID touchableAreaLeft;
    jfieldID touchableAreaTop;
    jfieldID touchableAreaRight;
    jfieldID touchableAreaBottom;
    jfieldID visible;
    jfieldID canReceiveKeys;
    jfieldID hasFocus;
    jfieldID hasWallpaper;
    jfieldID paused;
    jfieldID layer;
    jfieldID ownerPid;
    jfieldID ownerUid;
} gInputWindowClassInfo;

static struct {
    jclass clazz;

    jfieldID name;
    jfieldID dispatchingTimeoutNanos;
    jfieldID token;
} gInputApplicationClassInfo;

static struct {
    jclass clazz;
} gKeyEventClassInfo;

static struct {
    jclass clazz;
} gMotionEventClassInfo;

static struct {
    jclass clazz;

    jmethodID ctor;
    jmethodID addMotionRange;

    jfieldID mId;
    jfieldID mName;
    jfieldID mSources;
    jfieldID mKeyboardType;
    jfieldID mMotionRanges;
} gInputDeviceClassInfo;

static struct {
    jclass clazz;

    jfieldID touchscreen;
    jfieldID keyboard;
    jfieldID navigation;
} gConfigurationClassInfo;

// ----------------------------------------------------------------------------

static inline nsecs_t now() {
    return systemTime(SYSTEM_TIME_MONOTONIC);
}

// ----------------------------------------------------------------------------

class NativeInputManager : public virtual RefBase,
    public virtual InputReaderPolicyInterface,
    public virtual InputDispatcherPolicyInterface {
protected:
    virtual ~NativeInputManager();

public:
    NativeInputManager(jobject callbacksObj);

    inline sp<InputManager> getInputManager() const { return mInputManager; }

    void dump(String8& dump);

    void setDisplaySize(int32_t displayId, int32_t width, int32_t height);
    void setDisplayOrientation(int32_t displayId, int32_t orientation);

    status_t registerInputChannel(JNIEnv* env, const sp<InputChannel>& inputChannel,
            jweak inputChannelObjWeak, bool monitor);
    status_t unregisterInputChannel(JNIEnv* env, const sp<InputChannel>& inputChannel);

    void setInputWindows(JNIEnv* env, jobjectArray windowObjArray);
    void setFocusedApplication(JNIEnv* env, jobject applicationObj);
    void setInputDispatchMode(bool enabled, bool frozen);

    /* --- InputReaderPolicyInterface implementation --- */

    virtual bool getDisplayInfo(int32_t displayId,
            int32_t* width, int32_t* height, int32_t* orientation);
    virtual bool filterTouchEvents();
    virtual bool filterJumpyTouchEvents();
    virtual nsecs_t getVirtualKeyQuietTime();
    virtual void getVirtualKeyDefinitions(const String8& deviceName,
            Vector<VirtualKeyDefinition>& outVirtualKeyDefinitions);
    virtual void getInputDeviceCalibration(const String8& deviceName,
            InputDeviceCalibration& outCalibration);
    virtual void getExcludedDeviceNames(Vector<String8>& outExcludedDeviceNames);

    /* --- InputDispatcherPolicyInterface implementation --- */

    virtual void notifySwitch(nsecs_t when, int32_t switchCode, int32_t switchValue,
            uint32_t policyFlags);
    virtual void notifyConfigurationChanged(nsecs_t when);
    virtual nsecs_t notifyANR(const sp<InputApplicationHandle>& inputApplicationHandle,
            const sp<InputChannel>& inputChannel);
    virtual void notifyInputChannelBroken(const sp<InputChannel>& inputChannel);
    virtual nsecs_t getKeyRepeatTimeout();
    virtual nsecs_t getKeyRepeatDelay();
    virtual int32_t getMaxEventsPerSecond();
    virtual void interceptKeyBeforeQueueing(nsecs_t when, int32_t deviceId,
            int32_t action, int32_t& flags, int32_t keyCode, int32_t scanCode,
            uint32_t& policyFlags);
    virtual void interceptGenericBeforeQueueing(nsecs_t when, uint32_t& policyFlags);
    virtual bool interceptKeyBeforeDispatching(const sp<InputChannel>& inputChannel,
            const KeyEvent* keyEvent, uint32_t policyFlags);
    virtual void pokeUserActivity(nsecs_t eventTime, int32_t eventType);
    virtual bool checkInjectEventsPermissionNonReentrant(
            int32_t injectorPid, int32_t injectorUid);

private:
    class ApplicationToken : public InputApplicationHandle {
        jweak mTokenObjWeak;

    public:
        ApplicationToken(jweak tokenObjWeak) :
            mTokenObjWeak(tokenObjWeak) { }

        virtual ~ApplicationToken() {
            JNIEnv* env = NativeInputManager::jniEnv();
            env->DeleteWeakGlobalRef(mTokenObjWeak);
        }

        inline jweak getTokenObj() { return mTokenObjWeak; }
    };

    sp<InputManager> mInputManager;

    jobject mCallbacksObj;

    // Cached filtering policies.
    int32_t mFilterTouchEvents;
    int32_t mFilterJumpyTouchEvents;
    nsecs_t mVirtualKeyQuietTime;

    // Cached throttling policy.
    int32_t mMaxEventsPerSecond;

    // Cached display state.  (lock mDisplayLock)
    Mutex mDisplayLock;
    int32_t mDisplayWidth, mDisplayHeight;
    int32_t mDisplayOrientation;

    // Power manager interactions.
    bool isScreenOn();
    bool isScreenBright();

    // Weak references to all currently registered input channels by connection pointer.
    Mutex mInputChannelRegistryLock;
    KeyedVector<InputChannel*, jweak> mInputChannelObjWeakTable;

    jobject getInputChannelObjLocal(JNIEnv* env, const sp<InputChannel>& inputChannel);

    static bool populateWindow(JNIEnv* env, jobject windowObj, InputWindow& outWindow);

    static bool checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName);

    static inline JNIEnv* jniEnv() {
        return AndroidRuntime::getJNIEnv();
    }
};

// ----------------------------------------------------------------------------

NativeInputManager::NativeInputManager(jobject callbacksObj) :
    mFilterTouchEvents(-1), mFilterJumpyTouchEvents(-1), mVirtualKeyQuietTime(-1),
    mMaxEventsPerSecond(-1),
    mDisplayWidth(-1), mDisplayHeight(-1), mDisplayOrientation(ROTATION_0) {
    JNIEnv* env = jniEnv();

    mCallbacksObj = env->NewGlobalRef(callbacksObj);

    sp<EventHub> eventHub = new EventHub();
    mInputManager = new InputManager(eventHub, this, this);
}

NativeInputManager::~NativeInputManager() {
    JNIEnv* env = jniEnv();

    env->DeleteGlobalRef(mCallbacksObj);
}

void NativeInputManager::dump(String8& dump) {
    mInputManager->getReader()->dump(dump);
    dump.append("\n");

    mInputManager->getDispatcher()->dump(dump);
    dump.append("\n");
}

bool NativeInputManager::checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        LOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
        return true;
    }
    return false;
}

void NativeInputManager::setDisplaySize(int32_t displayId, int32_t width, int32_t height) {
    if (displayId == 0) {
        AutoMutex _l(mDisplayLock);

        mDisplayWidth = width;
        mDisplayHeight = height;
    }
}

void NativeInputManager::setDisplayOrientation(int32_t displayId, int32_t orientation) {
    if (displayId == 0) {
        AutoMutex _l(mDisplayLock);

        mDisplayOrientation = orientation;
    }
}

status_t NativeInputManager::registerInputChannel(JNIEnv* env,
        const sp<InputChannel>& inputChannel, jobject inputChannelObj, bool monitor) {
    jweak inputChannelObjWeak = env->NewWeakGlobalRef(inputChannelObj);
    if (! inputChannelObjWeak) {
        LOGE("Could not create weak reference for input channel.");
        LOGE_EX(env);
        return NO_MEMORY;
    }

    status_t status;
    {
        AutoMutex _l(mInputChannelRegistryLock);

        ssize_t index = mInputChannelObjWeakTable.indexOfKey(inputChannel.get());
        if (index >= 0) {
            LOGE("Input channel object '%s' has already been registered",
                    inputChannel->getName().string());
            status = INVALID_OPERATION;
            goto DeleteWeakRef;
        }

        mInputChannelObjWeakTable.add(inputChannel.get(), inputChannelObjWeak);
    }

    status = mInputManager->getDispatcher()->registerInputChannel(inputChannel, monitor);
    if (! status) {
        // Success.
        return OK;
    }

    // Failed!
    {
        AutoMutex _l(mInputChannelRegistryLock);
        mInputChannelObjWeakTable.removeItem(inputChannel.get());
    }

DeleteWeakRef:
    env->DeleteWeakGlobalRef(inputChannelObjWeak);
    return status;
}

status_t NativeInputManager::unregisterInputChannel(JNIEnv* env,
        const sp<InputChannel>& inputChannel) {
    jweak inputChannelObjWeak;
    {
        AutoMutex _l(mInputChannelRegistryLock);

        ssize_t index = mInputChannelObjWeakTable.indexOfKey(inputChannel.get());
        if (index < 0) {
            LOGE("Input channel object '%s' is not currently registered",
                    inputChannel->getName().string());
            return INVALID_OPERATION;
        }

        inputChannelObjWeak = mInputChannelObjWeakTable.valueAt(index);
        mInputChannelObjWeakTable.removeItemsAt(index);
    }

    env->DeleteWeakGlobalRef(inputChannelObjWeak);

    return mInputManager->getDispatcher()->unregisterInputChannel(inputChannel);
}

jobject NativeInputManager::getInputChannelObjLocal(JNIEnv* env,
        const sp<InputChannel>& inputChannel) {
    InputChannel* inputChannelPtr = inputChannel.get();
    if (! inputChannelPtr) {
        return NULL;
    }

    {
        AutoMutex _l(mInputChannelRegistryLock);

        ssize_t index = mInputChannelObjWeakTable.indexOfKey(inputChannelPtr);
        if (index < 0) {
            return NULL;
        }

        jweak inputChannelObjWeak = mInputChannelObjWeakTable.valueAt(index);
        return env->NewLocalRef(inputChannelObjWeak);
    }
}

bool NativeInputManager::getDisplayInfo(int32_t displayId,
        int32_t* width, int32_t* height, int32_t* orientation) {
    bool result = false;
    if (displayId == 0) {
        AutoMutex _l(mDisplayLock);

        if (mDisplayWidth > 0) {
            if (width) {
                *width = mDisplayWidth;
            }
            if (height) {
                *height = mDisplayHeight;
            }
            if (orientation) {
                *orientation = mDisplayOrientation;
            }
            result = true;
        }
    }
    return result;
}

bool NativeInputManager::filterTouchEvents() {
    if (mFilterTouchEvents < 0) {
        JNIEnv* env = jniEnv();

        jboolean result = env->CallBooleanMethod(mCallbacksObj,
                gCallbacksClassInfo.filterTouchEvents);
        if (checkAndClearExceptionFromCallback(env, "filterTouchEvents")) {
            result = false;
        }

        mFilterTouchEvents = result ? 1 : 0;
    }
    return mFilterTouchEvents;
}

bool NativeInputManager::filterJumpyTouchEvents() {
    if (mFilterJumpyTouchEvents < 0) {
        JNIEnv* env = jniEnv();

        jboolean result = env->CallBooleanMethod(mCallbacksObj,
                gCallbacksClassInfo.filterJumpyTouchEvents);
        if (checkAndClearExceptionFromCallback(env, "filterJumpyTouchEvents")) {
            result = false;
        }

        mFilterJumpyTouchEvents = result ? 1 : 0;
    }
    return mFilterJumpyTouchEvents;
}

nsecs_t NativeInputManager::getVirtualKeyQuietTime() {
    if (mVirtualKeyQuietTime < 0) {
        JNIEnv* env = jniEnv();

        jint result = env->CallIntMethod(mCallbacksObj,
                gCallbacksClassInfo.getVirtualKeyQuietTimeMillis);
        if (checkAndClearExceptionFromCallback(env, "getVirtualKeyQuietTimeMillis")) {
            result = 0;
        }
        if (result < 0) {
            result = 0;
        }

        mVirtualKeyQuietTime = milliseconds_to_nanoseconds(result);
    }
    return mVirtualKeyQuietTime;
}

void NativeInputManager::getVirtualKeyDefinitions(const String8& deviceName,
        Vector<VirtualKeyDefinition>& outVirtualKeyDefinitions) {
    outVirtualKeyDefinitions.clear();

    JNIEnv* env = jniEnv();

    jstring deviceNameStr = env->NewStringUTF(deviceName.string());
    if (! checkAndClearExceptionFromCallback(env, "getVirtualKeyDefinitions")) {
        jobjectArray result = jobjectArray(env->CallObjectMethod(mCallbacksObj,
                gCallbacksClassInfo.getVirtualKeyDefinitions, deviceNameStr));
        if (! checkAndClearExceptionFromCallback(env, "getVirtualKeyDefinitions") && result) {
            jsize length = env->GetArrayLength(result);
            for (jsize i = 0; i < length; i++) {
                jobject item = env->GetObjectArrayElement(result, i);

                outVirtualKeyDefinitions.add();
                outVirtualKeyDefinitions.editTop().scanCode =
                        int32_t(env->GetIntField(item, gVirtualKeyDefinitionClassInfo.scanCode));
                outVirtualKeyDefinitions.editTop().centerX =
                        int32_t(env->GetIntField(item, gVirtualKeyDefinitionClassInfo.centerX));
                outVirtualKeyDefinitions.editTop().centerY =
                        int32_t(env->GetIntField(item, gVirtualKeyDefinitionClassInfo.centerY));
                outVirtualKeyDefinitions.editTop().width =
                        int32_t(env->GetIntField(item, gVirtualKeyDefinitionClassInfo.width));
                outVirtualKeyDefinitions.editTop().height =
                        int32_t(env->GetIntField(item, gVirtualKeyDefinitionClassInfo.height));

                env->DeleteLocalRef(item);
            }
            env->DeleteLocalRef(result);
        }
        env->DeleteLocalRef(deviceNameStr);
    }
}

void NativeInputManager::getInputDeviceCalibration(const String8& deviceName,
        InputDeviceCalibration& outCalibration) {
    outCalibration.clear();

    JNIEnv* env = jniEnv();

    jstring deviceNameStr = env->NewStringUTF(deviceName.string());
    if (! checkAndClearExceptionFromCallback(env, "getInputDeviceCalibration")) {
        jobject result = env->CallObjectMethod(mCallbacksObj,
                gCallbacksClassInfo.getInputDeviceCalibration, deviceNameStr);
        if (! checkAndClearExceptionFromCallback(env, "getInputDeviceCalibration") && result) {
            jobjectArray keys = jobjectArray(env->GetObjectField(result,
                    gInputDeviceCalibrationClassInfo.keys));
            jobjectArray values = jobjectArray(env->GetObjectField(result,
                    gInputDeviceCalibrationClassInfo.values));

            jsize length = env->GetArrayLength(keys);
            for (jsize i = 0; i < length; i++) {
                jstring keyStr = jstring(env->GetObjectArrayElement(keys, i));
                jstring valueStr = jstring(env->GetObjectArrayElement(values, i));

                const char* keyChars = env->GetStringUTFChars(keyStr, NULL);
                String8 key(keyChars);
                env->ReleaseStringUTFChars(keyStr, keyChars);

                const char* valueChars = env->GetStringUTFChars(valueStr, NULL);
                String8 value(valueChars);
                env->ReleaseStringUTFChars(valueStr, valueChars);

                outCalibration.addProperty(key, value);

                env->DeleteLocalRef(keyStr);
                env->DeleteLocalRef(valueStr);
            }
            env->DeleteLocalRef(keys);
            env->DeleteLocalRef(values);
            env->DeleteLocalRef(result);
        }
        env->DeleteLocalRef(deviceNameStr);
    }
}

void NativeInputManager::getExcludedDeviceNames(Vector<String8>& outExcludedDeviceNames) {
    outExcludedDeviceNames.clear();

    JNIEnv* env = jniEnv();

    jobjectArray result = jobjectArray(env->CallObjectMethod(mCallbacksObj,
            gCallbacksClassInfo.getExcludedDeviceNames));
    if (! checkAndClearExceptionFromCallback(env, "getExcludedDeviceNames") && result) {
        jsize length = env->GetArrayLength(result);
        for (jsize i = 0; i < length; i++) {
            jstring item = jstring(env->GetObjectArrayElement(result, i));

            const char* deviceNameChars = env->GetStringUTFChars(item, NULL);
            outExcludedDeviceNames.add(String8(deviceNameChars));
            env->ReleaseStringUTFChars(item, deviceNameChars);

            env->DeleteLocalRef(item);
        }
        env->DeleteLocalRef(result);
    }
}

void NativeInputManager::notifySwitch(nsecs_t when, int32_t switchCode,
        int32_t switchValue, uint32_t policyFlags) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("notifySwitch - when=%lld, switchCode=%d, switchValue=%d, policyFlags=0x%x",
            when, switchCode, switchValue, policyFlags);
#endif

    JNIEnv* env = jniEnv();

    switch (switchCode) {
    case SW_LID:
        env->CallVoidMethod(mCallbacksObj, gCallbacksClassInfo.notifyLidSwitchChanged,
                when, switchValue == 0);
        checkAndClearExceptionFromCallback(env, "notifyLidSwitchChanged");
        break;
    }
}

void NativeInputManager::notifyConfigurationChanged(nsecs_t when) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("notifyConfigurationChanged - when=%lld", when);
#endif

    JNIEnv* env = jniEnv();

    env->CallVoidMethod(mCallbacksObj, gCallbacksClassInfo.notifyConfigurationChanged, when);
    checkAndClearExceptionFromCallback(env, "notifyConfigurationChanged");
}

nsecs_t NativeInputManager::notifyANR(const sp<InputApplicationHandle>& inputApplicationHandle,
        const sp<InputChannel>& inputChannel) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("notifyANR");
#endif

    JNIEnv* env = jniEnv();

    jobject tokenObjLocal;
    if (inputApplicationHandle.get()) {
        ApplicationToken* token = static_cast<ApplicationToken*>(inputApplicationHandle.get());
        jweak tokenObjWeak = token->getTokenObj();
        tokenObjLocal = env->NewLocalRef(tokenObjWeak);
    } else {
        tokenObjLocal = NULL;
    }

    jobject inputChannelObjLocal = getInputChannelObjLocal(env, inputChannel);
    jlong newTimeout = env->CallLongMethod(mCallbacksObj,
                gCallbacksClassInfo.notifyANR, tokenObjLocal, inputChannelObjLocal);
    if (checkAndClearExceptionFromCallback(env, "notifyANR")) {
        newTimeout = 0; // abort dispatch
    } else {
        assert(newTimeout >= 0);
    }

    env->DeleteLocalRef(tokenObjLocal);
    env->DeleteLocalRef(inputChannelObjLocal);
    return newTimeout;
}

void NativeInputManager::notifyInputChannelBroken(const sp<InputChannel>& inputChannel) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("notifyInputChannelBroken - inputChannel='%s'", inputChannel->getName().string());
#endif

    JNIEnv* env = jniEnv();

    jobject inputChannelObjLocal = getInputChannelObjLocal(env, inputChannel);
    if (inputChannelObjLocal) {
        env->CallVoidMethod(mCallbacksObj, gCallbacksClassInfo.notifyInputChannelBroken,
                inputChannelObjLocal);
        checkAndClearExceptionFromCallback(env, "notifyInputChannelBroken");

        env->DeleteLocalRef(inputChannelObjLocal);
    }
}

nsecs_t NativeInputManager::getKeyRepeatTimeout() {
    if (! isScreenOn()) {
        // Disable key repeat when the screen is off.
        return -1;
    } else {
        // TODO use ViewConfiguration.getLongPressTimeout()
        return milliseconds_to_nanoseconds(500);
    }
}

nsecs_t NativeInputManager::getKeyRepeatDelay() {
    return milliseconds_to_nanoseconds(50);
}

int32_t NativeInputManager::getMaxEventsPerSecond() {
    if (mMaxEventsPerSecond < 0) {
        JNIEnv* env = jniEnv();

        jint result = env->CallIntMethod(mCallbacksObj,
                gCallbacksClassInfo.getMaxEventsPerSecond);
        if (checkAndClearExceptionFromCallback(env, "getMaxEventsPerSecond")) {
            result = 60;
        }

        mMaxEventsPerSecond = result;
    }
    return mMaxEventsPerSecond;
}

void NativeInputManager::setInputWindows(JNIEnv* env, jobjectArray windowObjArray) {
    Vector<InputWindow> windows;

    jsize length = env->GetArrayLength(windowObjArray);
    for (jsize i = 0; i < length; i++) {
        jobject inputTargetObj = env->GetObjectArrayElement(windowObjArray, i);
        if (! inputTargetObj) {
            break; // found null element indicating end of used portion of the array
        }

        windows.push();
        InputWindow& window = windows.editTop();
        bool valid = populateWindow(env, inputTargetObj, window);
        if (! valid) {
            windows.pop();
        }

        env->DeleteLocalRef(inputTargetObj);
    }

    mInputManager->getDispatcher()->setInputWindows(windows);
}

bool NativeInputManager::populateWindow(JNIEnv* env, jobject windowObj,
        InputWindow& outWindow) {
    bool valid = false;

    jobject inputChannelObj = env->GetObjectField(windowObj,
            gInputWindowClassInfo.inputChannel);
    if (inputChannelObj) {
        sp<InputChannel> inputChannel =
                android_view_InputChannel_getInputChannel(env, inputChannelObj);
        if (inputChannel != NULL) {
            jstring name = jstring(env->GetObjectField(windowObj,
                    gInputWindowClassInfo.name));
            jint layoutParamsFlags = env->GetIntField(windowObj,
                    gInputWindowClassInfo.layoutParamsFlags);
            jint layoutParamsType = env->GetIntField(windowObj,
                    gInputWindowClassInfo.layoutParamsType);
            jlong dispatchingTimeoutNanos = env->GetLongField(windowObj,
                    gInputWindowClassInfo.dispatchingTimeoutNanos);
            jint frameLeft = env->GetIntField(windowObj,
                    gInputWindowClassInfo.frameLeft);
            jint frameTop = env->GetIntField(windowObj,
                    gInputWindowClassInfo.frameTop);
            jint frameRight = env->GetIntField(windowObj,
                    gInputWindowClassInfo.frameRight);
            jint frameBottom = env->GetIntField(windowObj,
                    gInputWindowClassInfo.frameBottom);
            jint visibleFrameLeft = env->GetIntField(windowObj,
                    gInputWindowClassInfo.visibleFrameLeft);
            jint visibleFrameTop = env->GetIntField(windowObj,
                    gInputWindowClassInfo.visibleFrameTop);
            jint visibleFrameRight = env->GetIntField(windowObj,
                    gInputWindowClassInfo.visibleFrameRight);
            jint visibleFrameBottom = env->GetIntField(windowObj,
                    gInputWindowClassInfo.visibleFrameBottom);
            jint touchableAreaLeft = env->GetIntField(windowObj,
                    gInputWindowClassInfo.touchableAreaLeft);
            jint touchableAreaTop = env->GetIntField(windowObj,
                    gInputWindowClassInfo.touchableAreaTop);
            jint touchableAreaRight = env->GetIntField(windowObj,
                    gInputWindowClassInfo.touchableAreaRight);
            jint touchableAreaBottom = env->GetIntField(windowObj,
                    gInputWindowClassInfo.touchableAreaBottom);
            jboolean visible = env->GetBooleanField(windowObj,
                    gInputWindowClassInfo.visible);
            jboolean canReceiveKeys = env->GetBooleanField(windowObj,
                    gInputWindowClassInfo.canReceiveKeys);
            jboolean hasFocus = env->GetBooleanField(windowObj,
                    gInputWindowClassInfo.hasFocus);
            jboolean hasWallpaper = env->GetBooleanField(windowObj,
                    gInputWindowClassInfo.hasWallpaper);
            jboolean paused = env->GetBooleanField(windowObj,
                    gInputWindowClassInfo.paused);
            jint layer = env->GetIntField(windowObj,
                    gInputWindowClassInfo.layer);
            jint ownerPid = env->GetIntField(windowObj,
                    gInputWindowClassInfo.ownerPid);
            jint ownerUid = env->GetIntField(windowObj,
                    gInputWindowClassInfo.ownerUid);

            const char* nameStr = env->GetStringUTFChars(name, NULL);

            outWindow.inputChannel = inputChannel;
            outWindow.name.setTo(nameStr);
            outWindow.layoutParamsFlags = layoutParamsFlags;
            outWindow.layoutParamsType = layoutParamsType;
            outWindow.dispatchingTimeout = dispatchingTimeoutNanos;
            outWindow.frameLeft = frameLeft;
            outWindow.frameTop = frameTop;
            outWindow.frameRight = frameRight;
            outWindow.frameBottom = frameBottom;
            outWindow.visibleFrameLeft = visibleFrameLeft;
            outWindow.visibleFrameTop = visibleFrameTop;
            outWindow.visibleFrameRight = visibleFrameRight;
            outWindow.visibleFrameBottom = visibleFrameBottom;
            outWindow.touchableAreaLeft = touchableAreaLeft;
            outWindow.touchableAreaTop = touchableAreaTop;
            outWindow.touchableAreaRight = touchableAreaRight;
            outWindow.touchableAreaBottom = touchableAreaBottom;
            outWindow.visible = visible;
            outWindow.canReceiveKeys = canReceiveKeys;
            outWindow.hasFocus = hasFocus;
            outWindow.hasWallpaper = hasWallpaper;
            outWindow.paused = paused;
            outWindow.layer = layer;
            outWindow.ownerPid = ownerPid;
            outWindow.ownerUid = ownerUid;

            env->ReleaseStringUTFChars(name, nameStr);
            valid = true;
        } else {
            LOGW("Dropping input target because its input channel is not initialized.");
        }

        env->DeleteLocalRef(inputChannelObj);
    } else {
        LOGW("Dropping input target because the input channel object was null.");
    }
    return valid;
}

void NativeInputManager::setFocusedApplication(JNIEnv* env, jobject applicationObj) {
    if (applicationObj) {
        jstring nameObj = jstring(env->GetObjectField(applicationObj,
                gInputApplicationClassInfo.name));
        jlong dispatchingTimeoutNanos = env->GetLongField(applicationObj,
                gInputApplicationClassInfo.dispatchingTimeoutNanos);
        jobject tokenObj = env->GetObjectField(applicationObj,
                gInputApplicationClassInfo.token);
        jweak tokenObjWeak = env->NewWeakGlobalRef(tokenObj);
        if (! tokenObjWeak) {
            LOGE("Could not create weak reference for application token.");
            LOGE_EX(env);
            env->ExceptionClear();
        }
        env->DeleteLocalRef(tokenObj);

        String8 name;
        if (nameObj) {
            const char* nameStr = env->GetStringUTFChars(nameObj, NULL);
            name.setTo(nameStr);
            env->ReleaseStringUTFChars(nameObj, nameStr);
            env->DeleteLocalRef(nameObj);
        } else {
            LOGE("InputApplication.name should not be null.");
            name.setTo("unknown");
        }

        InputApplication application;
        application.name = name;
        application.dispatchingTimeout = dispatchingTimeoutNanos;
        application.handle = new ApplicationToken(tokenObjWeak);
        mInputManager->getDispatcher()->setFocusedApplication(& application);
    } else {
        mInputManager->getDispatcher()->setFocusedApplication(NULL);
    }
}

void NativeInputManager::setInputDispatchMode(bool enabled, bool frozen) {
    mInputManager->getDispatcher()->setInputDispatchMode(enabled, frozen);
}

bool NativeInputManager::isScreenOn() {
    return android_server_PowerManagerService_isScreenOn();
}

bool NativeInputManager::isScreenBright() {
    return android_server_PowerManagerService_isScreenBright();
}

void NativeInputManager::interceptKeyBeforeQueueing(nsecs_t when,
        int32_t deviceId, int32_t action, int32_t &flags,
        int32_t keyCode, int32_t scanCode, uint32_t& policyFlags) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("interceptKeyBeforeQueueing - when=%lld, deviceId=%d, action=%d, flags=%d, "
            "keyCode=%d, scanCode=%d, policyFlags=0x%x",
            when, deviceId, action, flags, keyCode, scanCode, policyFlags);
#endif

    if ((policyFlags & POLICY_FLAG_VIRTUAL) || (flags & AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY)) {
        policyFlags |= POLICY_FLAG_VIRTUAL;
        flags |= AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY;
    }

    // Policy:
    // - Ignore untrusted events and pass them along.
    // - Ask the window manager what to do with normal events and trusted injected events.
    // - For normal events wake and brighten the screen if currently off or dim.
    if ((policyFlags & POLICY_FLAG_TRUSTED)) {
        const int32_t WM_ACTION_PASS_TO_USER = 1;
        const int32_t WM_ACTION_POKE_USER_ACTIVITY = 2;
        const int32_t WM_ACTION_GO_TO_SLEEP = 4;

        bool isScreenOn = this->isScreenOn();
        bool isScreenBright = this->isScreenBright();

        JNIEnv* env = jniEnv();
        jint wmActions = env->CallIntMethod(mCallbacksObj,
                gCallbacksClassInfo.interceptKeyBeforeQueueing,
                when, action, flags, keyCode, scanCode, policyFlags, isScreenOn);
        if (checkAndClearExceptionFromCallback(env, "interceptKeyBeforeQueueing")) {
            wmActions = 0;
        }

        if (!(flags & POLICY_FLAG_INJECTED)) {
            if (!isScreenOn) {
                policyFlags |= POLICY_FLAG_WOKE_HERE;
                flags |= AKEY_EVENT_FLAG_WOKE_HERE;
            }

            if (!isScreenBright) {
                policyFlags |= POLICY_FLAG_BRIGHT_HERE;
            }
        }

        if (wmActions & WM_ACTION_GO_TO_SLEEP) {
            android_server_PowerManagerService_goToSleep(when);
        }

        if (wmActions & WM_ACTION_POKE_USER_ACTIVITY) {
            android_server_PowerManagerService_userActivity(when, POWER_MANAGER_BUTTON_EVENT);
        }

        if (wmActions & WM_ACTION_PASS_TO_USER) {
            policyFlags |= POLICY_FLAG_PASS_TO_USER;
        }
    } else {
        policyFlags |= POLICY_FLAG_PASS_TO_USER;
    }
}

void NativeInputManager::interceptGenericBeforeQueueing(nsecs_t when, uint32_t& policyFlags) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("interceptGenericBeforeQueueing - when=%lld, policyFlags=0x%x", when, policyFlags);
#endif

    // Policy:
    // - Ignore untrusted events and pass them along.
    // - No special filtering for injected events required at this time.
    // - Filter normal events based on screen state.
    // - For normal events brighten (but do not wake) the screen if currently dim.
    if ((policyFlags & POLICY_FLAG_TRUSTED) && !(policyFlags & POLICY_FLAG_INJECTED)) {
        if (isScreenOn()) {
            policyFlags |= POLICY_FLAG_PASS_TO_USER;

            if (!isScreenBright()) {
                policyFlags |= POLICY_FLAG_BRIGHT_HERE;
            }
        }
    } else {
        policyFlags |= POLICY_FLAG_PASS_TO_USER;
    }
}

bool NativeInputManager::interceptKeyBeforeDispatching(const sp<InputChannel>& inputChannel,
        const KeyEvent* keyEvent, uint32_t policyFlags) {
    // Policy:
    // - Ignore untrusted events and pass them along.
    // - Filter normal events and trusted injected events through the window manager policy to
    //   handle the HOME key and the like.
    if (policyFlags & POLICY_FLAG_TRUSTED) {
        JNIEnv* env = jniEnv();

        // Note: inputChannel may be null.
        jobject inputChannelObj = getInputChannelObjLocal(env, inputChannel);
        jboolean consumed = env->CallBooleanMethod(mCallbacksObj,
                gCallbacksClassInfo.interceptKeyBeforeDispatching,
                inputChannelObj, keyEvent->getAction(), keyEvent->getFlags(),
                keyEvent->getKeyCode(), keyEvent->getScanCode(), keyEvent->getMetaState(),
                keyEvent->getRepeatCount(), policyFlags);
        bool error = checkAndClearExceptionFromCallback(env, "interceptKeyBeforeDispatching");

        env->DeleteLocalRef(inputChannelObj);
        return consumed && ! error;
    } else {
        return false;
    }
}

void NativeInputManager::pokeUserActivity(nsecs_t eventTime, int32_t eventType) {
    android_server_PowerManagerService_userActivity(eventTime, eventType);
}


bool NativeInputManager::checkInjectEventsPermissionNonReentrant(
        int32_t injectorPid, int32_t injectorUid) {
    JNIEnv* env = jniEnv();
    jboolean result = env->CallBooleanMethod(mCallbacksObj,
            gCallbacksClassInfo.checkInjectEventsPermission, injectorPid, injectorUid);
    checkAndClearExceptionFromCallback(env, "checkInjectEventsPermission");
    return result;
}

// ----------------------------------------------------------------------------

static sp<NativeInputManager> gNativeInputManager;

static bool checkInputManagerUnitialized(JNIEnv* env) {
    if (gNativeInputManager == NULL) {
        LOGE("Input manager not initialized.");
        jniThrowRuntimeException(env, "Input manager not initialized.");
        return true;
    }
    return false;
}

static void android_server_InputManager_nativeInit(JNIEnv* env, jclass clazz,
        jobject callbacks) {
    if (gNativeInputManager == NULL) {
        gNativeInputManager = new NativeInputManager(callbacks);
    } else {
        LOGE("Input manager already initialized.");
        jniThrowRuntimeException(env, "Input manager already initialized.");
    }
}

static void android_server_InputManager_nativeStart(JNIEnv* env, jclass clazz) {
    if (checkInputManagerUnitialized(env)) {
        return;
    }

    status_t result = gNativeInputManager->getInputManager()->start();
    if (result) {
        jniThrowRuntimeException(env, "Input manager could not be started.");
    }
}

static void android_server_InputManager_nativeSetDisplaySize(JNIEnv* env, jclass clazz,
        jint displayId, jint width, jint height) {
    if (checkInputManagerUnitialized(env)) {
        return;
    }

    // XXX we could get this from the SurfaceFlinger directly instead of requiring it
    // to be passed in like this, not sure which is better but leaving it like this
    // keeps the window manager in direct control of when display transitions propagate down
    // to the input dispatcher
    gNativeInputManager->setDisplaySize(displayId, width, height);
}

static void android_server_InputManager_nativeSetDisplayOrientation(JNIEnv* env, jclass clazz,
        jint displayId, jint orientation) {
    if (checkInputManagerUnitialized(env)) {
        return;
    }

    gNativeInputManager->setDisplayOrientation(displayId, orientation);
}

static jint android_server_InputManager_nativeGetScanCodeState(JNIEnv* env, jclass clazz,
        jint deviceId, jint sourceMask, jint scanCode) {
    if (checkInputManagerUnitialized(env)) {
        return AKEY_STATE_UNKNOWN;
    }

    return gNativeInputManager->getInputManager()->getReader()->getScanCodeState(
            deviceId, uint32_t(sourceMask), scanCode);
}

static jint android_server_InputManager_nativeGetKeyCodeState(JNIEnv* env, jclass clazz,
        jint deviceId, jint sourceMask, jint keyCode) {
    if (checkInputManagerUnitialized(env)) {
        return AKEY_STATE_UNKNOWN;
    }

    return gNativeInputManager->getInputManager()->getReader()->getKeyCodeState(
            deviceId, uint32_t(sourceMask), keyCode);
}

static jint android_server_InputManager_nativeGetSwitchState(JNIEnv* env, jclass clazz,
        jint deviceId, jint sourceMask, jint sw) {
    if (checkInputManagerUnitialized(env)) {
        return AKEY_STATE_UNKNOWN;
    }

    return gNativeInputManager->getInputManager()->getReader()->getSwitchState(
            deviceId, uint32_t(sourceMask), sw);
}

static jboolean android_server_InputManager_nativeHasKeys(JNIEnv* env, jclass clazz,
        jint deviceId, jint sourceMask, jintArray keyCodes, jbooleanArray outFlags) {
    if (checkInputManagerUnitialized(env)) {
        return JNI_FALSE;
    }

    int32_t* codes = env->GetIntArrayElements(keyCodes, NULL);
    uint8_t* flags = env->GetBooleanArrayElements(outFlags, NULL);
    jsize numCodes = env->GetArrayLength(keyCodes);
    jboolean result;
    if (numCodes == env->GetArrayLength(keyCodes)) {
        result = gNativeInputManager->getInputManager()->getReader()->hasKeys(
                deviceId, uint32_t(sourceMask), numCodes, codes, flags);
    } else {
        result = JNI_FALSE;
    }

    env->ReleaseBooleanArrayElements(outFlags, flags, 0);
    env->ReleaseIntArrayElements(keyCodes, codes, 0);
    return result;
}

static void throwInputChannelNotInitialized(JNIEnv* env) {
    jniThrowException(env, "java/lang/IllegalStateException",
             "inputChannel is not initialized");
}

static void android_server_InputManager_handleInputChannelDisposed(JNIEnv* env,
        jobject inputChannelObj, const sp<InputChannel>& inputChannel, void* data) {
    LOGW("Input channel object '%s' was disposed without first being unregistered with "
            "the input manager!", inputChannel->getName().string());

    if (gNativeInputManager != NULL) {
        gNativeInputManager->unregisterInputChannel(env, inputChannel);
    }
}

static void android_server_InputManager_nativeRegisterInputChannel(JNIEnv* env, jclass clazz,
        jobject inputChannelObj, jboolean monitor) {
    if (checkInputManagerUnitialized(env)) {
        return;
    }

    sp<InputChannel> inputChannel = android_view_InputChannel_getInputChannel(env,
            inputChannelObj);
    if (inputChannel == NULL) {
        throwInputChannelNotInitialized(env);
        return;
    }


    status_t status = gNativeInputManager->registerInputChannel(
            env, inputChannel, inputChannelObj, monitor);
    if (status) {
        jniThrowRuntimeException(env, "Failed to register input channel.  "
                "Check logs for details.");
        return;
    }

    if (! monitor) {
        android_view_InputChannel_setDisposeCallback(env, inputChannelObj,
                android_server_InputManager_handleInputChannelDisposed, NULL);
    }
}

static void android_server_InputManager_nativeUnregisterInputChannel(JNIEnv* env, jclass clazz,
        jobject inputChannelObj) {
    if (checkInputManagerUnitialized(env)) {
        return;
    }

    sp<InputChannel> inputChannel = android_view_InputChannel_getInputChannel(env,
            inputChannelObj);
    if (inputChannel == NULL) {
        throwInputChannelNotInitialized(env);
        return;
    }

    android_view_InputChannel_setDisposeCallback(env, inputChannelObj, NULL, NULL);

    status_t status = gNativeInputManager->unregisterInputChannel(env, inputChannel);
    if (status) {
        jniThrowRuntimeException(env, "Failed to unregister input channel.  "
                "Check logs for details.");
    }
}

static jint android_server_InputManager_nativeInjectInputEvent(JNIEnv* env, jclass clazz,
        jobject inputEventObj, jint injectorPid, jint injectorUid,
        jint syncMode, jint timeoutMillis) {
    if (checkInputManagerUnitialized(env)) {
        return INPUT_EVENT_INJECTION_FAILED;
    }

    if (env->IsInstanceOf(inputEventObj, gKeyEventClassInfo.clazz)) {
        KeyEvent keyEvent;
        android_view_KeyEvent_toNative(env, inputEventObj, & keyEvent);

        return gNativeInputManager->getInputManager()->getDispatcher()->injectInputEvent(
                & keyEvent, injectorPid, injectorUid, syncMode, timeoutMillis);
    } else if (env->IsInstanceOf(inputEventObj, gMotionEventClassInfo.clazz)) {
        MotionEvent motionEvent;
        android_view_MotionEvent_toNative(env, inputEventObj, & motionEvent);

        return gNativeInputManager->getInputManager()->getDispatcher()->injectInputEvent(
                & motionEvent, injectorPid, injectorUid, syncMode, timeoutMillis);
    } else {
        jniThrowRuntimeException(env, "Invalid input event type.");
        return INPUT_EVENT_INJECTION_FAILED;
    }
}

static void android_server_InputManager_nativeSetInputWindows(JNIEnv* env, jclass clazz,
        jobjectArray windowObjArray) {
    if (checkInputManagerUnitialized(env)) {
        return;
    }

    gNativeInputManager->setInputWindows(env, windowObjArray);
}

static void android_server_InputManager_nativeSetFocusedApplication(JNIEnv* env, jclass clazz,
        jobject applicationObj) {
    if (checkInputManagerUnitialized(env)) {
        return;
    }

    gNativeInputManager->setFocusedApplication(env, applicationObj);
}

static void android_server_InputManager_nativeSetInputDispatchMode(JNIEnv* env,
        jclass clazz, jboolean enabled, jboolean frozen) {
    if (checkInputManagerUnitialized(env)) {
        return;
    }

    gNativeInputManager->setInputDispatchMode(enabled, frozen);
}

static jobject android_server_InputManager_nativeGetInputDevice(JNIEnv* env,
        jclass clazz, jint deviceId) {
    if (checkInputManagerUnitialized(env)) {
        return NULL;
    }

    InputDeviceInfo deviceInfo;
    status_t status = gNativeInputManager->getInputManager()->getReader()->getInputDeviceInfo(
            deviceId, & deviceInfo);
    if (status) {
        return NULL;
    }

    jobject deviceObj = env->NewObject(gInputDeviceClassInfo.clazz, gInputDeviceClassInfo.ctor);
    if (! deviceObj) {
        return NULL;
    }

    jstring deviceNameObj = env->NewStringUTF(deviceInfo.getName().string());
    if (! deviceNameObj) {
        return NULL;
    }

    env->SetIntField(deviceObj, gInputDeviceClassInfo.mId, deviceInfo.getId());
    env->SetObjectField(deviceObj, gInputDeviceClassInfo.mName, deviceNameObj);
    env->SetIntField(deviceObj, gInputDeviceClassInfo.mSources, deviceInfo.getSources());
    env->SetIntField(deviceObj, gInputDeviceClassInfo.mKeyboardType, deviceInfo.getKeyboardType());

    const KeyedVector<int, InputDeviceInfo::MotionRange>& ranges = deviceInfo.getMotionRanges();
    for (size_t i = 0; i < ranges.size(); i++) {
        int rangeType = ranges.keyAt(i);
        const InputDeviceInfo::MotionRange& range = ranges.valueAt(i);
        env->CallVoidMethod(deviceObj, gInputDeviceClassInfo.addMotionRange,
                rangeType, range.min, range.max, range.flat, range.fuzz);
        if (env->ExceptionCheck()) {
            return NULL;
        }
    }

    return deviceObj;
}

static jintArray android_server_InputManager_nativeGetInputDeviceIds(JNIEnv* env,
        jclass clazz) {
    if (checkInputManagerUnitialized(env)) {
        return NULL;
    }

    Vector<int> deviceIds;
    gNativeInputManager->getInputManager()->getReader()->getInputDeviceIds(deviceIds);

    jintArray deviceIdsObj = env->NewIntArray(deviceIds.size());
    if (! deviceIdsObj) {
        return NULL;
    }

    env->SetIntArrayRegion(deviceIdsObj, 0, deviceIds.size(), deviceIds.array());
    return deviceIdsObj;
}

static void android_server_InputManager_nativeGetInputConfiguration(JNIEnv* env,
        jclass clazz, jobject configObj) {
    if (checkInputManagerUnitialized(env)) {
        return;
    }

    InputConfiguration config;
    gNativeInputManager->getInputManager()->getReader()->getInputConfiguration(& config);

    env->SetIntField(configObj, gConfigurationClassInfo.touchscreen, config.touchScreen);
    env->SetIntField(configObj, gConfigurationClassInfo.keyboard, config.keyboard);
    env->SetIntField(configObj, gConfigurationClassInfo.navigation, config.navigation);
}

static jstring android_server_InputManager_nativeDump(JNIEnv* env, jclass clazz) {
    if (checkInputManagerUnitialized(env)) {
        return NULL;
    }

    String8 dump;
    gNativeInputManager->dump(dump);
    return env->NewStringUTF(dump.string());
}

// ----------------------------------------------------------------------------

static JNINativeMethod gInputManagerMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit", "(Lcom/android/server/InputManager$Callbacks;)V",
            (void*) android_server_InputManager_nativeInit },
    { "nativeStart", "()V",
            (void*) android_server_InputManager_nativeStart },
    { "nativeSetDisplaySize", "(III)V",
            (void*) android_server_InputManager_nativeSetDisplaySize },
    { "nativeSetDisplayOrientation", "(II)V",
            (void*) android_server_InputManager_nativeSetDisplayOrientation },
    { "nativeGetScanCodeState", "(III)I",
            (void*) android_server_InputManager_nativeGetScanCodeState },
    { "nativeGetKeyCodeState", "(III)I",
            (void*) android_server_InputManager_nativeGetKeyCodeState },
    { "nativeGetSwitchState", "(III)I",
            (void*) android_server_InputManager_nativeGetSwitchState },
    { "nativeHasKeys", "(II[I[Z)Z",
            (void*) android_server_InputManager_nativeHasKeys },
    { "nativeRegisterInputChannel", "(Landroid/view/InputChannel;Z)V",
            (void*) android_server_InputManager_nativeRegisterInputChannel },
    { "nativeUnregisterInputChannel", "(Landroid/view/InputChannel;)V",
            (void*) android_server_InputManager_nativeUnregisterInputChannel },
    { "nativeInjectInputEvent", "(Landroid/view/InputEvent;IIII)I",
            (void*) android_server_InputManager_nativeInjectInputEvent },
    { "nativeSetInputWindows", "([Lcom/android/server/InputWindow;)V",
            (void*) android_server_InputManager_nativeSetInputWindows },
    { "nativeSetFocusedApplication", "(Lcom/android/server/InputApplication;)V",
            (void*) android_server_InputManager_nativeSetFocusedApplication },
    { "nativeSetInputDispatchMode", "(ZZ)V",
            (void*) android_server_InputManager_nativeSetInputDispatchMode },
    { "nativeGetInputDevice", "(I)Landroid/view/InputDevice;",
            (void*) android_server_InputManager_nativeGetInputDevice },
    { "nativeGetInputDeviceIds", "()[I",
            (void*) android_server_InputManager_nativeGetInputDeviceIds },
    { "nativeGetInputConfiguration", "(Landroid/content/res/Configuration;)V",
            (void*) android_server_InputManager_nativeGetInputConfiguration },
    { "nativeDump", "()Ljava/lang/String;",
            (void*) android_server_InputManager_nativeDump },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className); \
        var = jclass(env->NewGlobalRef(var));

#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method " methodName);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_server_InputManager(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/InputManager",
            gInputManagerMethods, NELEM(gInputManagerMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    // Callbacks

    FIND_CLASS(gCallbacksClassInfo.clazz, "com/android/server/InputManager$Callbacks");

    GET_METHOD_ID(gCallbacksClassInfo.notifyConfigurationChanged, gCallbacksClassInfo.clazz,
            "notifyConfigurationChanged", "(J)V");

    GET_METHOD_ID(gCallbacksClassInfo.notifyLidSwitchChanged, gCallbacksClassInfo.clazz,
            "notifyLidSwitchChanged", "(JZ)V");

    GET_METHOD_ID(gCallbacksClassInfo.notifyInputChannelBroken, gCallbacksClassInfo.clazz,
            "notifyInputChannelBroken", "(Landroid/view/InputChannel;)V");

    GET_METHOD_ID(gCallbacksClassInfo.notifyANR, gCallbacksClassInfo.clazz,
            "notifyANR", "(Ljava/lang/Object;Landroid/view/InputChannel;)J");

    GET_METHOD_ID(gCallbacksClassInfo.interceptKeyBeforeQueueing, gCallbacksClassInfo.clazz,
            "interceptKeyBeforeQueueing", "(JIIIIIZ)I");

    GET_METHOD_ID(gCallbacksClassInfo.interceptKeyBeforeDispatching, gCallbacksClassInfo.clazz,
            "interceptKeyBeforeDispatching", "(Landroid/view/InputChannel;IIIIIII)Z");

    GET_METHOD_ID(gCallbacksClassInfo.checkInjectEventsPermission, gCallbacksClassInfo.clazz,
            "checkInjectEventsPermission", "(II)Z");

    GET_METHOD_ID(gCallbacksClassInfo.filterTouchEvents, gCallbacksClassInfo.clazz,
            "filterTouchEvents", "()Z");

    GET_METHOD_ID(gCallbacksClassInfo.filterJumpyTouchEvents, gCallbacksClassInfo.clazz,
            "filterJumpyTouchEvents", "()Z");

    GET_METHOD_ID(gCallbacksClassInfo.getVirtualKeyQuietTimeMillis, gCallbacksClassInfo.clazz,
            "getVirtualKeyQuietTimeMillis", "()I");

    GET_METHOD_ID(gCallbacksClassInfo.getVirtualKeyDefinitions, gCallbacksClassInfo.clazz,
            "getVirtualKeyDefinitions",
            "(Ljava/lang/String;)[Lcom/android/server/InputManager$VirtualKeyDefinition;");

    GET_METHOD_ID(gCallbacksClassInfo.getInputDeviceCalibration, gCallbacksClassInfo.clazz,
            "getInputDeviceCalibration",
            "(Ljava/lang/String;)Lcom/android/server/InputManager$InputDeviceCalibration;");

    GET_METHOD_ID(gCallbacksClassInfo.getExcludedDeviceNames, gCallbacksClassInfo.clazz,
            "getExcludedDeviceNames", "()[Ljava/lang/String;");

    GET_METHOD_ID(gCallbacksClassInfo.getMaxEventsPerSecond, gCallbacksClassInfo.clazz,
            "getMaxEventsPerSecond", "()I");

    // VirtualKeyDefinition

    FIND_CLASS(gVirtualKeyDefinitionClassInfo.clazz,
            "com/android/server/InputManager$VirtualKeyDefinition");

    GET_FIELD_ID(gVirtualKeyDefinitionClassInfo.scanCode, gVirtualKeyDefinitionClassInfo.clazz,
            "scanCode", "I");

    GET_FIELD_ID(gVirtualKeyDefinitionClassInfo.centerX, gVirtualKeyDefinitionClassInfo.clazz,
            "centerX", "I");

    GET_FIELD_ID(gVirtualKeyDefinitionClassInfo.centerY, gVirtualKeyDefinitionClassInfo.clazz,
            "centerY", "I");

    GET_FIELD_ID(gVirtualKeyDefinitionClassInfo.width, gVirtualKeyDefinitionClassInfo.clazz,
            "width", "I");

    GET_FIELD_ID(gVirtualKeyDefinitionClassInfo.height, gVirtualKeyDefinitionClassInfo.clazz,
            "height", "I");

    // InputDeviceCalibration

    FIND_CLASS(gInputDeviceCalibrationClassInfo.clazz,
            "com/android/server/InputManager$InputDeviceCalibration");

    GET_FIELD_ID(gInputDeviceCalibrationClassInfo.keys, gInputDeviceCalibrationClassInfo.clazz,
            "keys", "[Ljava/lang/String;");

    GET_FIELD_ID(gInputDeviceCalibrationClassInfo.values, gInputDeviceCalibrationClassInfo.clazz,
            "values", "[Ljava/lang/String;");

    // InputWindow

    FIND_CLASS(gInputWindowClassInfo.clazz, "com/android/server/InputWindow");

    GET_FIELD_ID(gInputWindowClassInfo.inputChannel, gInputWindowClassInfo.clazz,
            "inputChannel", "Landroid/view/InputChannel;");

    GET_FIELD_ID(gInputWindowClassInfo.name, gInputWindowClassInfo.clazz,
            "name", "Ljava/lang/String;");

    GET_FIELD_ID(gInputWindowClassInfo.layoutParamsFlags, gInputWindowClassInfo.clazz,
            "layoutParamsFlags", "I");

    GET_FIELD_ID(gInputWindowClassInfo.layoutParamsType, gInputWindowClassInfo.clazz,
            "layoutParamsType", "I");

    GET_FIELD_ID(gInputWindowClassInfo.dispatchingTimeoutNanos, gInputWindowClassInfo.clazz,
            "dispatchingTimeoutNanos", "J");

    GET_FIELD_ID(gInputWindowClassInfo.frameLeft, gInputWindowClassInfo.clazz,
            "frameLeft", "I");

    GET_FIELD_ID(gInputWindowClassInfo.frameTop, gInputWindowClassInfo.clazz,
            "frameTop", "I");

    GET_FIELD_ID(gInputWindowClassInfo.frameRight, gInputWindowClassInfo.clazz,
            "frameRight", "I");

    GET_FIELD_ID(gInputWindowClassInfo.frameBottom, gInputWindowClassInfo.clazz,
            "frameBottom", "I");

    GET_FIELD_ID(gInputWindowClassInfo.visibleFrameLeft, gInputWindowClassInfo.clazz,
            "visibleFrameLeft", "I");

    GET_FIELD_ID(gInputWindowClassInfo.visibleFrameTop, gInputWindowClassInfo.clazz,
            "visibleFrameTop", "I");

    GET_FIELD_ID(gInputWindowClassInfo.visibleFrameRight, gInputWindowClassInfo.clazz,
            "visibleFrameRight", "I");

    GET_FIELD_ID(gInputWindowClassInfo.visibleFrameBottom, gInputWindowClassInfo.clazz,
            "visibleFrameBottom", "I");

    GET_FIELD_ID(gInputWindowClassInfo.touchableAreaLeft, gInputWindowClassInfo.clazz,
            "touchableAreaLeft", "I");

    GET_FIELD_ID(gInputWindowClassInfo.touchableAreaTop, gInputWindowClassInfo.clazz,
            "touchableAreaTop", "I");

    GET_FIELD_ID(gInputWindowClassInfo.touchableAreaRight, gInputWindowClassInfo.clazz,
            "touchableAreaRight", "I");

    GET_FIELD_ID(gInputWindowClassInfo.touchableAreaBottom, gInputWindowClassInfo.clazz,
            "touchableAreaBottom", "I");

    GET_FIELD_ID(gInputWindowClassInfo.visible, gInputWindowClassInfo.clazz,
            "visible", "Z");

    GET_FIELD_ID(gInputWindowClassInfo.canReceiveKeys, gInputWindowClassInfo.clazz,
            "canReceiveKeys", "Z");

    GET_FIELD_ID(gInputWindowClassInfo.hasFocus, gInputWindowClassInfo.clazz,
            "hasFocus", "Z");

    GET_FIELD_ID(gInputWindowClassInfo.hasWallpaper, gInputWindowClassInfo.clazz,
            "hasWallpaper", "Z");

    GET_FIELD_ID(gInputWindowClassInfo.paused, gInputWindowClassInfo.clazz,
            "paused", "Z");

    GET_FIELD_ID(gInputWindowClassInfo.layer, gInputWindowClassInfo.clazz,
            "layer", "I");

    GET_FIELD_ID(gInputWindowClassInfo.ownerPid, gInputWindowClassInfo.clazz,
            "ownerPid", "I");

    GET_FIELD_ID(gInputWindowClassInfo.ownerUid, gInputWindowClassInfo.clazz,
            "ownerUid", "I");

    // InputApplication

    FIND_CLASS(gInputApplicationClassInfo.clazz, "com/android/server/InputApplication");

    GET_FIELD_ID(gInputApplicationClassInfo.name, gInputApplicationClassInfo.clazz,
            "name", "Ljava/lang/String;");

    GET_FIELD_ID(gInputApplicationClassInfo.dispatchingTimeoutNanos,
            gInputApplicationClassInfo.clazz,
            "dispatchingTimeoutNanos", "J");

    GET_FIELD_ID(gInputApplicationClassInfo.token, gInputApplicationClassInfo.clazz,
            "token", "Ljava/lang/Object;");

    // KeyEvent

    FIND_CLASS(gKeyEventClassInfo.clazz, "android/view/KeyEvent");

    // MotionEvent

    FIND_CLASS(gMotionEventClassInfo.clazz, "android/view/MotionEvent");

    // InputDevice

    FIND_CLASS(gInputDeviceClassInfo.clazz, "android/view/InputDevice");

    GET_METHOD_ID(gInputDeviceClassInfo.ctor, gInputDeviceClassInfo.clazz,
            "<init>", "()V");

    GET_METHOD_ID(gInputDeviceClassInfo.addMotionRange, gInputDeviceClassInfo.clazz,
            "addMotionRange", "(IFFFF)V");

    GET_FIELD_ID(gInputDeviceClassInfo.mId, gInputDeviceClassInfo.clazz,
            "mId", "I");

    GET_FIELD_ID(gInputDeviceClassInfo.mName, gInputDeviceClassInfo.clazz,
            "mName", "Ljava/lang/String;");

    GET_FIELD_ID(gInputDeviceClassInfo.mSources, gInputDeviceClassInfo.clazz,
            "mSources", "I");

    GET_FIELD_ID(gInputDeviceClassInfo.mKeyboardType, gInputDeviceClassInfo.clazz,
            "mKeyboardType", "I");

    GET_FIELD_ID(gInputDeviceClassInfo.mMotionRanges, gInputDeviceClassInfo.clazz,
            "mMotionRanges", "[Landroid/view/InputDevice$MotionRange;");

    // Configuration

    FIND_CLASS(gConfigurationClassInfo.clazz, "android/content/res/Configuration");

    GET_FIELD_ID(gConfigurationClassInfo.touchscreen, gConfigurationClassInfo.clazz,
            "touchscreen", "I");

    GET_FIELD_ID(gConfigurationClassInfo.keyboard, gConfigurationClassInfo.clazz,
            "keyboard", "I");

    GET_FIELD_ID(gConfigurationClassInfo.navigation, gConfigurationClassInfo.clazz,
            "navigation", "I");

    return 0;
}

} /* namespace android */
