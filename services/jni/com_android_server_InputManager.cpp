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
#define DEBUG_INPUT_READER_POLICY 1

// Log debug messages about InputDispatcherPolicy
#define DEBUG_INPUT_DISPATCHER_POLICY 1


#include "JNIHelp.h"
#include "jni.h"
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
#include "../../core/jni/android_view_InputTarget.h"

namespace android {

// ----------------------------------------------------------------------------

static struct {
    jclass clazz;

    jmethodID isScreenOn;
    jmethodID isScreenBright;
    jmethodID notifyConfigurationChanged;
    jmethodID notifyLidSwitchChanged;
    jmethodID virtualKeyFeedback;
    jmethodID hackInterceptKey;
    jmethodID goToSleep;
    jmethodID pokeUserActivityForKey;
    jmethodID notifyAppSwitchComing;
    jmethodID filterTouchEvents;
    jmethodID filterJumpyTouchEvents;
    jmethodID getVirtualKeyDefinitions;
    jmethodID getExcludedDeviceNames;
    jmethodID getKeyEventTargets;
    jmethodID getMotionEventTargets;
} gCallbacksClassInfo;

static struct {
    jclass clazz;

    jfieldID scanCode;
    jfieldID centerX;
    jfieldID centerY;
    jfieldID width;
    jfieldID height;
} gVirtualKeyDefinitionClassInfo;

// ----------------------------------------------------------------------------

class NativeInputManager : public virtual RefBase,
    public virtual InputReaderPolicyInterface,
    public virtual InputDispatcherPolicyInterface {
protected:
    virtual ~NativeInputManager();

public:
    NativeInputManager(jobject callbacksObj);

    inline sp<InputManager> getInputManager() const { return mInputManager; }

    void setDisplaySize(int32_t displayId, int32_t width, int32_t height);
    void setDisplayOrientation(int32_t displayId, int32_t orientation);

    /* --- InputReaderPolicyInterface implementation --- */

    virtual bool getDisplayInfo(int32_t displayId,
            int32_t* width, int32_t* height, int32_t* orientation);
    virtual void virtualKeyFeedback(nsecs_t when, int32_t deviceId,
            int32_t action, int32_t flags, int32_t keyCode,
            int32_t scanCode, int32_t metaState, nsecs_t downTime);
    virtual int32_t interceptKey(nsecs_t when, int32_t deviceId,
            bool down, int32_t keyCode, int32_t scanCode, uint32_t policyFlags);
    virtual int32_t interceptTrackball(nsecs_t when, bool buttonChanged, bool buttonDown,
            bool rolled);
    virtual int32_t interceptTouch(nsecs_t when);
    virtual int32_t interceptSwitch(nsecs_t when, int32_t switchCode, int32_t switchValue);
    virtual bool filterTouchEvents();
    virtual bool filterJumpyTouchEvents();
    virtual void getVirtualKeyDefinitions(const String8& deviceName,
            Vector<InputReaderPolicyInterface::VirtualKeyDefinition>& outVirtualKeyDefinitions);
    virtual void getExcludedDeviceNames(Vector<String8>& outExcludedDeviceNames);

    /* --- InputDispatcherPolicyInterface implementation --- */

    virtual void notifyConfigurationChanged(nsecs_t when);
    virtual void notifyInputChannelBroken(const sp<InputChannel>& inputChannel);
    virtual void notifyInputChannelANR(const sp<InputChannel>& inputChannel);
    virtual void notifyInputChannelRecoveredFromANR(const sp<InputChannel>& inputChannel);
    virtual nsecs_t getKeyRepeatTimeout();
    virtual void getKeyEventTargets(KeyEvent* keyEvent, uint32_t policyFlags,
            Vector<InputTarget>& outTargets);
    virtual void getMotionEventTargets(MotionEvent* motionEvent, uint32_t policyFlags,
            Vector<InputTarget>& outTargets);

private:
    sp<InputManager> mInputManager;

    jobject mCallbacksObj;

    // Cached filtering policies.
    int32_t mFilterTouchEvents;
    int32_t mFilterJumpyTouchEvents;

    // Cached display state.  (lock mDisplayLock)
    Mutex mDisplayLock;
    int32_t mDisplayWidth, mDisplayHeight;
    int32_t mDisplayOrientation;

    // Callbacks.
    bool isScreenOn();
    bool isScreenBright();

    static inline JNIEnv* jniEnv() {
        return AndroidRuntime::getJNIEnv();
    }

    static bool isAppSwitchKey(int32_t keyCode);
    static bool checkExceptionFromCallback(JNIEnv* env, const char* methodName);
};

// ----------------------------------------------------------------------------

NativeInputManager::NativeInputManager(jobject callbacksObj) :
    mFilterTouchEvents(-1), mFilterJumpyTouchEvents(-1),
    mDisplayWidth(-1), mDisplayHeight(-1), mDisplayOrientation(-1) {
    JNIEnv* env = jniEnv();

    mCallbacksObj = env->NewGlobalRef(callbacksObj);

    sp<EventHub> eventHub = new EventHub();
    mInputManager = new InputManager(eventHub, this, this);
}

NativeInputManager::~NativeInputManager() {
    JNIEnv* env = jniEnv();

    env->DeleteGlobalRef(mCallbacksObj);
}

bool NativeInputManager::isAppSwitchKey(int32_t keyCode) {
    return keyCode == KEYCODE_HOME || keyCode == KEYCODE_ENDCALL;
}

bool NativeInputManager::checkExceptionFromCallback(JNIEnv* env, const char* methodName) {
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

bool NativeInputManager::getDisplayInfo(int32_t displayId,
        int32_t* width, int32_t* height, int32_t* orientation) {
    bool result = false;
    if (displayId == 0) {
        AutoMutex _l(mDisplayLock);

        if (mDisplayWidth > 0) {
            *width = mDisplayWidth;
            *height = mDisplayHeight;
            *orientation = mDisplayOrientation;
            result = true;
        }
    }
    return result;
}

bool NativeInputManager::isScreenOn() {
    JNIEnv* env = jniEnv();

    jboolean result = env->CallBooleanMethod(mCallbacksObj, gCallbacksClassInfo.isScreenOn);
    if (checkExceptionFromCallback(env, "isScreenOn")) {
        return true;
    }
    return result;
}

bool NativeInputManager::isScreenBright() {
    JNIEnv* env = jniEnv();

    jboolean result = env->CallBooleanMethod(mCallbacksObj, gCallbacksClassInfo.isScreenBright);
    if (checkExceptionFromCallback(env, "isScreenBright")) {
        return true;
    }
    return result;
}

void NativeInputManager::virtualKeyFeedback(nsecs_t when, int32_t deviceId,
        int32_t action, int32_t flags, int32_t keyCode,
        int32_t scanCode, int32_t metaState, nsecs_t downTime) {
#if DEBUG_INPUT_READER_POLICY
    LOGD("virtualKeyFeedback - when=%lld, deviceId=%d, action=%d, flags=%d, keyCode=%d, "
            "scanCode=%d, metaState=%d, downTime=%lld",
            when, deviceId, action, flags, keyCode, scanCode, metaState, downTime);
#endif

    JNIEnv* env = jniEnv();

    env->CallVoidMethod(mCallbacksObj, gCallbacksClassInfo.virtualKeyFeedback,
            when, deviceId, action, flags, keyCode, scanCode, metaState, downTime);
    checkExceptionFromCallback(env, "virtualKeyFeedback");
}

int32_t NativeInputManager::interceptKey(nsecs_t when,
        int32_t deviceId, bool down, int32_t keyCode, int32_t scanCode, uint32_t policyFlags) {
#if DEBUG_INPUT_READER_POLICY
    LOGD("interceptKey - when=%lld, deviceId=%d, down=%d, keyCode=%d, scanCode=%d, "
            "policyFlags=%d",
            when, deviceId, down, keyCode, scanCode, policyFlags);
#endif

    const int32_t WM_ACTION_PASS_TO_USER = 1;
    const int32_t WM_ACTION_POKE_USER_ACTIVITY = 2;
    const int32_t WM_ACTION_GO_TO_SLEEP = 4;

    JNIEnv* env = jniEnv();

    bool isScreenOn = this->isScreenOn();
    bool isScreenBright = this->isScreenBright();

    jint wmActions = env->CallIntMethod(mCallbacksObj, gCallbacksClassInfo.hackInterceptKey,
            deviceId, EV_KEY, scanCode, keyCode, policyFlags, down ? 1 : 0, when, isScreenOn);
    if (checkExceptionFromCallback(env, "hackInterceptKey")) {
        wmActions = 0;
    }

    int32_t actions = InputReaderPolicyInterface::ACTION_NONE;
    if (! isScreenOn) {
        // Key presses and releases wake the device.
        actions |= InputReaderPolicyInterface::ACTION_WOKE_HERE;
    }

    if (! isScreenBright) {
        // Key presses and releases brighten the screen if dimmed.
        actions |= InputReaderPolicyInterface::ACTION_BRIGHT_HERE;
    }

    if (wmActions & WM_ACTION_GO_TO_SLEEP) {
        env->CallVoidMethod(mCallbacksObj, gCallbacksClassInfo.goToSleep, when);
        checkExceptionFromCallback(env, "goToSleep");
    }

    if (wmActions & WM_ACTION_POKE_USER_ACTIVITY) {
        env->CallVoidMethod(mCallbacksObj, gCallbacksClassInfo.pokeUserActivityForKey, when);
        checkExceptionFromCallback(env, "pokeUserActivityForKey");
    }

    if (wmActions & WM_ACTION_PASS_TO_USER) {
        actions |= InputReaderPolicyInterface::ACTION_DISPATCH;
    }

    if (! (wmActions & WM_ACTION_PASS_TO_USER)) {
        if (down && isAppSwitchKey(keyCode)) {
            env->CallVoidMethod(mCallbacksObj, gCallbacksClassInfo.notifyAppSwitchComing);
            checkExceptionFromCallback(env, "notifyAppSwitchComing");

            actions |= InputReaderPolicyInterface::ACTION_APP_SWITCH_COMING;
        }
    }
    return actions;
}

int32_t NativeInputManager::interceptTouch(nsecs_t when) {
#if DEBUG_INPUT_READER_POLICY
    LOGD("interceptTouch - when=%lld", when);
#endif

    if (! isScreenOn()) {
        // Touch events do not wake the device.
        return InputReaderPolicyInterface::ACTION_NONE;
    }

    return InputReaderPolicyInterface::ACTION_DISPATCH;
}

int32_t NativeInputManager::interceptTrackball(nsecs_t when,
        bool buttonChanged, bool buttonDown, bool rolled) {
#if DEBUG_INPUT_READER_POLICY
    LOGD("interceptTrackball - when=%lld, buttonChanged=%d, buttonDown=%d, rolled=%d",
            when, buttonChanged, buttonDown, rolled);
#endif

    if (! isScreenOn()) {
        // Trackball motions and button presses do not wake the device.
        return InputReaderPolicyInterface::ACTION_NONE;
    }

    return InputReaderPolicyInterface::ACTION_DISPATCH;
}

int32_t NativeInputManager::interceptSwitch(nsecs_t when, int32_t switchCode,
        int32_t switchValue) {
#if DEBUG_INPUT_READER_POLICY
    LOGD("interceptSwitch - when=%lld, switchCode=%d, switchValue=%d",
            when, switchCode, switchValue);
#endif

    JNIEnv* env = jniEnv();

    switch (switchCode) {
    case SW_LID:
        env->CallVoidMethod(mCallbacksObj, gCallbacksClassInfo.notifyLidSwitchChanged,
                when, switchValue == 0);
        checkExceptionFromCallback(env, "notifyLidSwitchChanged");
        break;
    }

    return InputReaderPolicyInterface::ACTION_NONE;
}

bool NativeInputManager::filterTouchEvents() {
    if (mFilterTouchEvents < 0) {
        JNIEnv* env = jniEnv();

        jboolean result = env->CallBooleanMethod(mCallbacksObj,
                gCallbacksClassInfo.filterTouchEvents);
        if (checkExceptionFromCallback(env, "filterTouchEvents")) {
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
        if (checkExceptionFromCallback(env, "filterJumpyTouchEvents")) {
            result = false;
        }

        mFilterJumpyTouchEvents = result ? 1 : 0;
    }
    return mFilterJumpyTouchEvents;
}

void NativeInputManager::getVirtualKeyDefinitions(const String8& deviceName,
        Vector<InputReaderPolicyInterface::VirtualKeyDefinition>& outVirtualKeyDefinitions) {
    JNIEnv* env = jniEnv();

    jstring deviceNameStr = env->NewStringUTF(deviceName.string());
    if (! checkExceptionFromCallback(env, "getVirtualKeyDefinitions")) {
        jobjectArray result = jobjectArray(env->CallObjectMethod(mCallbacksObj,
                gCallbacksClassInfo.getVirtualKeyDefinitions, deviceNameStr));
        if (! checkExceptionFromCallback(env, "getVirtualKeyDefinitions") && result) {
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

void NativeInputManager::getExcludedDeviceNames(Vector<String8>& outExcludedDeviceNames) {
    JNIEnv* env = jniEnv();

    jobjectArray result = jobjectArray(env->CallObjectMethod(mCallbacksObj,
            gCallbacksClassInfo.getExcludedDeviceNames));
    if (! checkExceptionFromCallback(env, "getExcludedDeviceNames") && result) {
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

void NativeInputManager::notifyConfigurationChanged(nsecs_t when) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("notifyConfigurationChanged - when=%lld", when);
#endif

    JNIEnv* env = jniEnv();

    InputConfiguration config;
    mInputManager->getInputConfiguration(& config);

    env->CallVoidMethod(mCallbacksObj, gCallbacksClassInfo.notifyConfigurationChanged,
            when, config.touchScreen, config.keyboard, config.navigation);
    checkExceptionFromCallback(env, "notifyConfigurationChanged");
}

void NativeInputManager::notifyInputChannelBroken(const sp<InputChannel>& inputChannel) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("notifyInputChannelBroken - inputChannel='%s'", inputChannel->getName().string());
#endif

    // TODO
}

void NativeInputManager::notifyInputChannelANR(const sp<InputChannel>& inputChannel) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("notifyInputChannelANR - inputChannel='%s'",
            inputChannel->getName().string());
#endif

    // TODO
}

void NativeInputManager::notifyInputChannelRecoveredFromANR(const sp<InputChannel>& inputChannel) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("notifyInputChannelRecoveredFromANR - inputChannel='%s'",
            inputChannel->getName().string());
#endif

    // TODO
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

void NativeInputManager::getKeyEventTargets(KeyEvent* keyEvent, uint32_t policyFlags,
        Vector<InputTarget>& outTargets) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("getKeyEventTargets - policyFlags=%d", policyFlags);
#endif

    JNIEnv* env = jniEnv();

    jobject keyEventObj = android_view_KeyEvent_fromNative(env, keyEvent);
    if (! keyEventObj) {
        LOGE("Could not obtain DVM KeyEvent object to get key event targets.");
    } else {
        jobjectArray result = jobjectArray(env->CallObjectMethod(mCallbacksObj,
                gCallbacksClassInfo.getKeyEventTargets,
                keyEventObj, jint(keyEvent->getNature()), jint(policyFlags)));
        if (! checkExceptionFromCallback(env, "getKeyEventTargets") && result) {
            jsize length = env->GetArrayLength(result);
            for (jsize i = 0; i < length; i++) {
                jobject item = env->GetObjectArrayElement(result, i);
                if (! item) {
                    break; // found null element indicating end of used portion of the array
                }

                outTargets.add();
                android_view_InputTarget_toNative(env, item, & outTargets.editTop());

                env->DeleteLocalRef(item);
            }
            env->DeleteLocalRef(result);
        }
        env->DeleteLocalRef(keyEventObj);
    }
}

void NativeInputManager::getMotionEventTargets(MotionEvent* motionEvent, uint32_t policyFlags,
        Vector<InputTarget>& outTargets) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("getMotionEventTargets - policyFlags=%d", policyFlags);
#endif

    JNIEnv* env = jniEnv();

    jobject motionEventObj = android_view_MotionEvent_fromNative(env, motionEvent);
    if (! motionEventObj) {
        LOGE("Could not obtain DVM MotionEvent object to get key event targets.");
    } else {
        jobjectArray result = jobjectArray(env->CallObjectMethod(mCallbacksObj,
                gCallbacksClassInfo.getMotionEventTargets,
                motionEventObj, jint(motionEvent->getNature()), jint(policyFlags)));
        if (! checkExceptionFromCallback(env, "getMotionEventTargets") && result) {
            jsize length = env->GetArrayLength(result);
            for (jsize i = 0; i < length; i++) {
                jobject item = env->GetObjectArrayElement(result, i);
                if (! item) {
                    break; // found null element indicating end of used portion of the array
                }

                outTargets.add();
                android_view_InputTarget_toNative(env, item, & outTargets.editTop());

                env->DeleteLocalRef(item);
            }
            env->DeleteLocalRef(result);
        }
        android_view_MotionEvent_recycle(env, motionEventObj);
        env->DeleteLocalRef(motionEventObj);
    }
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
        jint deviceId, jint deviceClasses, jint scanCode) {
    if (checkInputManagerUnitialized(env)) {
        return KEY_STATE_UNKNOWN;
    }

    return gNativeInputManager->getInputManager()->getScanCodeState(
            deviceId, deviceClasses, scanCode);
}

static jint android_server_InputManager_nativeGetKeyCodeState(JNIEnv* env, jclass clazz,
        jint deviceId, jint deviceClasses, jint keyCode) {
    if (checkInputManagerUnitialized(env)) {
        return KEY_STATE_UNKNOWN;
    }

    return gNativeInputManager->getInputManager()->getKeyCodeState(
            deviceId, deviceClasses, keyCode);
}

static jint android_server_InputManager_nativeGetSwitchState(JNIEnv* env, jclass clazz,
        jint deviceId, jint deviceClasses, jint sw) {
    if (checkInputManagerUnitialized(env)) {
        return KEY_STATE_UNKNOWN;
    }

    return gNativeInputManager->getInputManager()->getSwitchState(deviceId, deviceClasses, sw);
}

static jboolean android_server_InputManager_nativeHasKeys(JNIEnv* env, jclass clazz,
        jintArray keyCodes, jbooleanArray outFlags) {
    if (checkInputManagerUnitialized(env)) {
        return JNI_FALSE;
    }

    int32_t* codes = env->GetIntArrayElements(keyCodes, NULL);
    uint8_t* flags = env->GetBooleanArrayElements(outFlags, NULL);
    jsize numCodes = env->GetArrayLength(keyCodes);
    jboolean result;
    if (numCodes == env->GetArrayLength(outFlags)) {
        result = gNativeInputManager->getInputManager()->hasKeys(numCodes, codes, flags);
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
        gNativeInputManager->getInputManager()->unregisterInputChannel(inputChannel);
    }
}

static void android_server_InputManager_nativeRegisterInputChannel(JNIEnv* env, jclass clazz,
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

    status_t status = gNativeInputManager->getInputManager()->registerInputChannel(inputChannel);
    if (status) {
        jniThrowRuntimeException(env, "Failed to register input channel.  "
                "Check logs for details.");
        return;
    }

    android_view_InputChannel_setDisposeCallback(env, inputChannelObj,
            android_server_InputManager_handleInputChannelDisposed, NULL);
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

    status_t status = gNativeInputManager->getInputManager()->unregisterInputChannel(inputChannel);
    if (status) {
        jniThrowRuntimeException(env, "Failed to unregister input channel.  "
                "Check logs for details.");
    }
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
    { "nativeHasKeys", "([I[Z)Z",
            (void*) android_server_InputManager_nativeHasKeys },
    { "nativeRegisterInputChannel", "(Landroid/view/InputChannel;)V",
            (void*) android_server_InputManager_nativeRegisterInputChannel },
    { "nativeUnregisterInputChannel", "(Landroid/view/InputChannel;)V",
            (void*) android_server_InputManager_nativeUnregisterInputChannel }
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

    GET_METHOD_ID(gCallbacksClassInfo.isScreenOn, gCallbacksClassInfo.clazz,
            "isScreenOn", "()Z");

    GET_METHOD_ID(gCallbacksClassInfo.isScreenBright, gCallbacksClassInfo.clazz,
            "isScreenBright", "()Z");

    GET_METHOD_ID(gCallbacksClassInfo.notifyConfigurationChanged, gCallbacksClassInfo.clazz,
            "notifyConfigurationChanged", "(JIII)V");

    GET_METHOD_ID(gCallbacksClassInfo.notifyLidSwitchChanged, gCallbacksClassInfo.clazz,
            "notifyLidSwitchChanged", "(JZ)V");

    GET_METHOD_ID(gCallbacksClassInfo.virtualKeyFeedback, gCallbacksClassInfo.clazz,
            "virtualKeyFeedback", "(JIIIIIIJ)V");

    GET_METHOD_ID(gCallbacksClassInfo.hackInterceptKey, gCallbacksClassInfo.clazz,
            "hackInterceptKey", "(IIIIIIJZ)I");

    GET_METHOD_ID(gCallbacksClassInfo.goToSleep, gCallbacksClassInfo.clazz,
            "goToSleep", "(J)V");

    GET_METHOD_ID(gCallbacksClassInfo.pokeUserActivityForKey, gCallbacksClassInfo.clazz,
            "pokeUserActivityForKey", "(J)V");

    GET_METHOD_ID(gCallbacksClassInfo.notifyAppSwitchComing, gCallbacksClassInfo.clazz,
            "notifyAppSwitchComing", "()V");

    GET_METHOD_ID(gCallbacksClassInfo.filterTouchEvents, gCallbacksClassInfo.clazz,
            "filterTouchEvents", "()Z");

    GET_METHOD_ID(gCallbacksClassInfo.filterJumpyTouchEvents, gCallbacksClassInfo.clazz,
            "filterJumpyTouchEvents", "()Z");

    GET_METHOD_ID(gCallbacksClassInfo.getVirtualKeyDefinitions, gCallbacksClassInfo.clazz,
            "getVirtualKeyDefinitions",
            "(Ljava/lang/String;)[Lcom/android/server/InputManager$VirtualKeyDefinition;");

    GET_METHOD_ID(gCallbacksClassInfo.getExcludedDeviceNames, gCallbacksClassInfo.clazz,
            "getExcludedDeviceNames", "()[Ljava/lang/String;");

    GET_METHOD_ID(gCallbacksClassInfo.getKeyEventTargets, gCallbacksClassInfo.clazz,
            "getKeyEventTargets", "(Landroid/view/KeyEvent;II)[Landroid/view/InputTarget;");

    GET_METHOD_ID(gCallbacksClassInfo.getMotionEventTargets, gCallbacksClassInfo.clazz,
            "getMotionEventTargets", "(Landroid/view/MotionEvent;II)[Landroid/view/InputTarget;");

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

    return 0;
}

} /* namespace android */
