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

#include "JNIHelp.h"
#include "jni.h"
#include <android_runtime/AndroidRuntime.h>
#include <ui/InputManager.h>
#include <ui/InputTransport.h>
#include <utils/Log.h>
#include <utils/threads.h>
#include "../../core/jni/android_view_KeyEvent.h"
#include "../../core/jni/android_view_MotionEvent.h"
#include "../../core/jni/android_view_InputChannel.h"
#include "../../core/jni/android_view_InputTarget.h"

namespace android {

class InputDispatchPolicy : public InputDispatchPolicyInterface {
public:
    InputDispatchPolicy(JNIEnv* env, jobject callbacks);
    virtual ~InputDispatchPolicy();

    void setDisplaySize(int32_t displayId, int32_t width, int32_t height);
    void setDisplayOrientation(int32_t displayId, int32_t orientation);

    virtual bool getDisplayInfo(int32_t displayId,
            int32_t* width, int32_t* height, int32_t* orientation);

    virtual void notifyConfigurationChanged(nsecs_t when,
            int32_t touchScreenConfig, int32_t keyboardConfig, int32_t navigationConfig);

    virtual void notifyLidSwitchChanged(nsecs_t when, bool lidOpen);

    virtual void virtualKeyFeedback(nsecs_t when, int32_t deviceId,
            int32_t action, int32_t flags, int32_t keyCode,
            int32_t scanCode, int32_t metaState, nsecs_t downTime);

    virtual int32_t interceptKey(nsecs_t when, int32_t deviceId,
            bool down, int32_t keyCode, int32_t scanCode, uint32_t policyFlags);
    virtual int32_t interceptTrackball(nsecs_t when, bool buttonChanged, bool buttonDown,
            bool rolled);
    virtual int32_t interceptTouch(nsecs_t when);

    virtual bool filterTouchEvents();
    virtual bool filterJumpyTouchEvents();
    virtual void getVirtualKeyDefinitions(const String8& deviceName,
            Vector<VirtualKeyDefinition>& outVirtualKeyDefinitions);
    virtual void getExcludedDeviceNames(Vector<String8>& outExcludedDeviceNames);

    virtual bool allowKeyRepeat();
    virtual nsecs_t getKeyRepeatTimeout();

    virtual void getKeyEventTargets(KeyEvent* keyEvent, uint32_t policyFlags,
            Vector<InputTarget>& outTargets);
    virtual void getMotionEventTargets(MotionEvent* motionEvent, uint32_t policyFlags,
            Vector<InputTarget>& outTargets);

private:
    bool isScreenOn();
    bool isScreenBright();

private:
    jobject mCallbacks;

    int32_t mFilterTouchEvents;
    int32_t mFilterJumpyTouchEvents;

    Mutex mDisplayLock;
    int32_t mDisplayWidth, mDisplayHeight;
    int32_t mDisplayOrientation;

    inline JNIEnv* threadEnv() const {
        return AndroidRuntime::getJNIEnv();
    }
};


// globals

static sp<EventHub> gEventHub;
static sp<InputDispatchPolicy> gInputDispatchPolicy;
static sp<InputManager> gInputManager;

// JNI

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

static bool checkInputManagerUnitialized(JNIEnv* env) {
    if (gInputManager == NULL) {
        LOGE("Input manager not initialized.");
        jniThrowRuntimeException(env, "Input manager not initialized.");
        return true;
    }
    return false;
}

static void android_server_InputManager_nativeInit(JNIEnv* env, jclass clazz,
        jobject callbacks) {
    if (gEventHub == NULL) {
        gEventHub = new EventHub();
    }

    if (gInputDispatchPolicy == NULL) {
        gInputDispatchPolicy = new InputDispatchPolicy(env, callbacks);
    }

    if (gInputManager == NULL) {
        gInputManager = new InputManager(gEventHub, gInputDispatchPolicy);
    }
}

static void android_server_InputManager_nativeStart(JNIEnv* env, jclass clazz) {
    if (checkInputManagerUnitialized(env)) {
        return;
    }

    status_t result = gInputManager->start();
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
    gInputDispatchPolicy->setDisplaySize(displayId, width, height);
}

static void android_server_InputManager_nativeSetDisplayOrientation(JNIEnv* env, jclass clazz,
        jint displayId, jint orientation) {
    if (checkInputManagerUnitialized(env)) {
        return;
    }

    gInputDispatchPolicy->setDisplayOrientation(displayId, orientation);
}

static jint android_server_InputManager_nativeGetScanCodeState(JNIEnv* env, jclass clazz,
        jint deviceId, jint deviceClasses, jint scanCode) {
    if (checkInputManagerUnitialized(env)) {
        return KEY_STATE_UNKNOWN;
    }

    return gInputManager->getScanCodeState(deviceId, deviceClasses, scanCode);
}

static jint android_server_InputManager_nativeGetKeyCodeState(JNIEnv* env, jclass clazz,
        jint deviceId, jint deviceClasses, jint keyCode) {
    if (checkInputManagerUnitialized(env)) {
        return KEY_STATE_UNKNOWN;
    }

    return gInputManager->getKeyCodeState(deviceId, deviceClasses, keyCode);
}

static jint android_server_InputManager_nativeGetSwitchState(JNIEnv* env, jclass clazz,
        jint deviceId, jint deviceClasses, jint sw) {
    if (checkInputManagerUnitialized(env)) {
        return KEY_STATE_UNKNOWN;
    }

    return gInputManager->getSwitchState(deviceId, deviceClasses, sw);
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
        result = gInputManager->hasKeys(numCodes, codes, flags);
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

    gInputManager->unregisterInputChannel(inputChannel);
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

    status_t status = gInputManager->registerInputChannel(inputChannel);
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

    status_t status = gInputManager->unregisterInputChannel(inputChannel);
    if (status) {
        jniThrowRuntimeException(env, "Failed to unregister input channel.  "
                "Check logs for details.");
    }
}

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

    // Policy

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

// static functions

static bool isAppSwitchKey(int32_t keyCode) {
    return keyCode == KEYCODE_HOME || keyCode == KEYCODE_ENDCALL;
}

static bool checkException(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        LOGE("An exception was thrown by an InputDispatchPolicy callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
        return true;
    }
    return false;
}


// InputDispatchPolicy implementation

InputDispatchPolicy::InputDispatchPolicy(JNIEnv* env, jobject callbacks) :
        mFilterTouchEvents(-1), mFilterJumpyTouchEvents(-1),
        mDisplayWidth(-1), mDisplayHeight(-1), mDisplayOrientation(-1) {
    mCallbacks = env->NewGlobalRef(callbacks);
}

InputDispatchPolicy::~InputDispatchPolicy() {
    JNIEnv* env = threadEnv();

    env->DeleteGlobalRef(mCallbacks);
}

void InputDispatchPolicy::setDisplaySize(int32_t displayId, int32_t width, int32_t height) {
    if (displayId == 0) {
        AutoMutex _l(mDisplayLock);

        mDisplayWidth = width;
        mDisplayHeight = height;
    }
}

void InputDispatchPolicy::setDisplayOrientation(int32_t displayId, int32_t orientation) {
    if (displayId == 0) {
        AutoMutex _l(mDisplayLock);

        mDisplayOrientation = orientation;
    }
}

bool InputDispatchPolicy::getDisplayInfo(int32_t displayId,
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

bool InputDispatchPolicy::isScreenOn() {
    JNIEnv* env = threadEnv();

    jboolean result = env->CallBooleanMethod(mCallbacks, gCallbacksClassInfo.isScreenOn);
    if (checkException(env, "isScreenOn")) {
        return true;
    }
    return result;
}

bool InputDispatchPolicy::isScreenBright() {
    JNIEnv* env = threadEnv();

    jboolean result = env->CallBooleanMethod(mCallbacks, gCallbacksClassInfo.isScreenBright);
    if (checkException(env, "isScreenBright")) {
        return true;
    }
    return result;
}

void InputDispatchPolicy::notifyConfigurationChanged(nsecs_t when,
        int32_t touchScreenConfig, int32_t keyboardConfig, int32_t navigationConfig) {
    JNIEnv* env = threadEnv();

    env->CallVoidMethod(mCallbacks, gCallbacksClassInfo.notifyConfigurationChanged,
            when, touchScreenConfig, keyboardConfig, navigationConfig);
    checkException(env, "notifyConfigurationChanged");
}

void InputDispatchPolicy::notifyLidSwitchChanged(nsecs_t when, bool lidOpen) {
    JNIEnv* env = threadEnv();
    env->CallVoidMethod(mCallbacks, gCallbacksClassInfo.notifyLidSwitchChanged,
            when, lidOpen);
    checkException(env, "notifyLidSwitchChanged");
}

void InputDispatchPolicy::virtualKeyFeedback(nsecs_t when, int32_t deviceId,
        int32_t action, int32_t flags, int32_t keyCode,
        int32_t scanCode, int32_t metaState, nsecs_t downTime) {
    JNIEnv* env = threadEnv();

    env->CallVoidMethod(mCallbacks, gCallbacksClassInfo.virtualKeyFeedback,
            when, deviceId, action, flags, keyCode, scanCode, metaState, downTime);
    checkException(env, "virtualKeyFeedback");
}

int32_t InputDispatchPolicy::interceptKey(nsecs_t when,
        int32_t deviceId, bool down, int32_t keyCode, int32_t scanCode, uint32_t policyFlags) {
    const int32_t WM_ACTION_PASS_TO_USER = 1;
    const int32_t WM_ACTION_POKE_USER_ACTIVITY = 2;
    const int32_t WM_ACTION_GO_TO_SLEEP = 4;

    JNIEnv* env = threadEnv();

    bool isScreenOn = this->isScreenOn();
    bool isScreenBright = this->isScreenBright();

    jint wmActions = env->CallIntMethod(mCallbacks, gCallbacksClassInfo.hackInterceptKey,
            deviceId, EV_KEY, scanCode, keyCode, policyFlags, down ? 1 : 0, when, isScreenOn);
    if (checkException(env, "hackInterceptKey")) {
        wmActions = 0;
    }

    int32_t actions = ACTION_NONE;
    if (! isScreenOn) {
        // Key presses and releases wake the device.
        actions |= ACTION_WOKE_HERE;
    }

    if (! isScreenBright) {
        // Key presses and releases brighten the screen if dimmed.
        actions |= ACTION_BRIGHT_HERE;
    }

    if (wmActions & WM_ACTION_GO_TO_SLEEP) {
        env->CallVoidMethod(mCallbacks, gCallbacksClassInfo.goToSleep, when);
        checkException(env, "goToSleep");
    }

    if (wmActions & WM_ACTION_POKE_USER_ACTIVITY) {
        env->CallVoidMethod(mCallbacks, gCallbacksClassInfo.pokeUserActivityForKey, when);
        checkException(env, "pokeUserActivityForKey");
    }

    if (wmActions & WM_ACTION_PASS_TO_USER) {
        actions |= ACTION_DISPATCH;
    }

    if (! (wmActions & WM_ACTION_PASS_TO_USER)) {
        if (down && isAppSwitchKey(keyCode)) {
            env->CallVoidMethod(mCallbacks, gCallbacksClassInfo.notifyAppSwitchComing);
            checkException(env, "notifyAppSwitchComing");

            actions |= ACTION_APP_SWITCH_COMING;
        }
    }
    return actions;
}

int32_t InputDispatchPolicy::interceptTouch(nsecs_t when) {
    if (! isScreenOn()) {
        // Touch events do not wake the device.
        return ACTION_NONE;
    }

    return ACTION_DISPATCH;
}

int32_t InputDispatchPolicy::interceptTrackball(nsecs_t when,
        bool buttonChanged, bool buttonDown, bool rolled) {
    if (! isScreenOn()) {
        // Trackball motions and button presses do not wake the device.
        return ACTION_NONE;
    }

    return ACTION_DISPATCH;
}

bool InputDispatchPolicy::filterTouchEvents() {
    if (mFilterTouchEvents < 0) {
        JNIEnv* env = threadEnv();

        jboolean result = env->CallBooleanMethod(mCallbacks,
                gCallbacksClassInfo.filterTouchEvents);
        if (checkException(env, "filterTouchEvents")) {
            result = false;
        }

        mFilterTouchEvents = result ? 1 : 0;
    }
    return mFilterTouchEvents;
}

bool InputDispatchPolicy::filterJumpyTouchEvents() {
    if (mFilterJumpyTouchEvents < 0) {
        JNIEnv* env = threadEnv();

        jboolean result = env->CallBooleanMethod(mCallbacks,
                gCallbacksClassInfo.filterJumpyTouchEvents);
        if (checkException(env, "filterJumpyTouchEvents")) {
            result = false;
        }

        mFilterJumpyTouchEvents = result ? 1 : 0;
    }
    return mFilterJumpyTouchEvents;
}

void InputDispatchPolicy::getVirtualKeyDefinitions(const String8& deviceName,
        Vector<VirtualKeyDefinition>& outVirtualKeyDefinitions) {
    JNIEnv* env = threadEnv();

    jstring deviceNameStr = env->NewStringUTF(deviceName.string());
    if (! checkException(env, "getVirtualKeyDefinitions")) {
        jobjectArray result = jobjectArray(env->CallObjectMethod(mCallbacks,
                gCallbacksClassInfo.getVirtualKeyDefinitions, deviceNameStr));
        if (! checkException(env, "getVirtualKeyDefinitions") && result) {
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

void InputDispatchPolicy::getExcludedDeviceNames(Vector<String8>& outExcludedDeviceNames) {
    JNIEnv* env = threadEnv();

    jobjectArray result = jobjectArray(env->CallObjectMethod(mCallbacks,
            gCallbacksClassInfo.getExcludedDeviceNames));
    if (! checkException(env, "getExcludedDeviceNames") && result) {
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

bool InputDispatchPolicy::allowKeyRepeat() {
    // Disable key repeat when the screen is off.
    return isScreenOn();
}

nsecs_t InputDispatchPolicy::getKeyRepeatTimeout() {
    // TODO use ViewConfiguration.getLongPressTimeout()
    return milliseconds_to_nanoseconds(500);
}

void InputDispatchPolicy::getKeyEventTargets(KeyEvent* keyEvent, uint32_t policyFlags,
        Vector<InputTarget>& outTargets) {
    JNIEnv* env = threadEnv();

    jobject keyEventObj = android_view_KeyEvent_fromNative(env, keyEvent);
    if (! keyEventObj) {
        LOGE("Could not obtain DVM KeyEvent object to get key event targets.");
    } else {
        jobjectArray result = jobjectArray(env->CallObjectMethod(mCallbacks,
                gCallbacksClassInfo.getKeyEventTargets,
                keyEventObj, jint(keyEvent->getNature()), jint(policyFlags)));
        if (! checkException(env, "getKeyEventTargets") && result) {
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

void InputDispatchPolicy::getMotionEventTargets(MotionEvent* motionEvent, uint32_t policyFlags,
        Vector<InputTarget>& outTargets) {
    JNIEnv* env = threadEnv();

    jobject motionEventObj = android_view_MotionEvent_fromNative(env, motionEvent);
    if (! motionEventObj) {
        LOGE("Could not obtain DVM MotionEvent object to get key event targets.");
    } else {
        jobjectArray result = jobjectArray(env->CallObjectMethod(mCallbacks,
                gCallbacksClassInfo.getMotionEventTargets,
                motionEventObj, jint(motionEvent->getNature()), jint(policyFlags)));
        if (! checkException(env, "getMotionEventTargets") && result) {
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

} /* namespace android */
