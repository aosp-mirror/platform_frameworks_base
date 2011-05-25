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

#include <utils/Log.h>
#include <utils/Looper.h>
#include <utils/threads.h>

#include <input/InputManager.h>
#include <input/PointerController.h>

#include <android_os_MessageQueue.h>
#include <android_view_KeyEvent.h>
#include <android_view_MotionEvent.h>
#include <android_view_InputChannel.h>
#include <android/graphics/GraphicsJNI.h>

#include "com_android_server_PowerManagerService.h"
#include "com_android_server_InputApplication.h"
#include "com_android_server_InputApplicationHandle.h"
#include "com_android_server_InputWindow.h"
#include "com_android_server_InputWindowHandle.h"

namespace android {

static struct {
    jclass clazz;

    jmethodID notifyConfigurationChanged;
    jmethodID notifyLidSwitchChanged;
    jmethodID notifyInputChannelBroken;
    jmethodID notifyANR;
    jmethodID interceptKeyBeforeQueueing;
    jmethodID interceptMotionBeforeQueueingWhenScreenOff;
    jmethodID interceptKeyBeforeDispatching;
    jmethodID dispatchUnhandledKey;
    jmethodID checkInjectEventsPermission;
    jmethodID filterTouchEvents;
    jmethodID filterJumpyTouchEvents;
    jmethodID getVirtualKeyQuietTimeMillis;
    jmethodID getExcludedDeviceNames;
    jmethodID getKeyRepeatTimeout;
    jmethodID getKeyRepeatDelay;
    jmethodID getMaxEventsPerSecond;
    jmethodID getPointerLayer;
    jmethodID getPointerIcon;
} gCallbacksClassInfo;

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
} gInputDeviceClassInfo;

static struct {
    jclass clazz;

    jfieldID touchscreen;
    jfieldID keyboard;
    jfieldID navigation;
} gConfigurationClassInfo;

static struct {
    jclass clazz;

    jfieldID bitmap;
    jfieldID hotSpotX;
    jfieldID hotSpotY;
} gPointerIconClassInfo;


// --- Global functions ---

static jobject getInputApplicationHandleObjLocalRef(JNIEnv* env,
        const sp<InputApplicationHandle>& inputApplicationHandle) {
    if (inputApplicationHandle == NULL) {
        return NULL;
    }
    return static_cast<NativeInputApplicationHandle*>(inputApplicationHandle.get())->
            getInputApplicationHandleObjLocalRef(env);
}

static jobject getInputWindowHandleObjLocalRef(JNIEnv* env,
        const sp<InputWindowHandle>& inputWindowHandle) {
    if (inputWindowHandle == NULL) {
        return NULL;
    }
    return static_cast<NativeInputWindowHandle*>(inputWindowHandle.get())->
            getInputWindowHandleObjLocalRef(env);
}


// --- NativeInputManager ---

class NativeInputManager : public virtual RefBase,
    public virtual InputReaderPolicyInterface,
    public virtual InputDispatcherPolicyInterface {
protected:
    virtual ~NativeInputManager();

public:
    NativeInputManager(jobject callbacksObj, const sp<Looper>& looper);

    inline sp<InputManager> getInputManager() const { return mInputManager; }

    void dump(String8& dump);

    void setDisplaySize(int32_t displayId, int32_t width, int32_t height);
    void setDisplayOrientation(int32_t displayId, int32_t orientation);

    status_t registerInputChannel(JNIEnv* env, const sp<InputChannel>& inputChannel,
            const sp<InputWindowHandle>& inputWindowHandle, bool monitor);
    status_t unregisterInputChannel(JNIEnv* env, const sp<InputChannel>& inputChannel);

    void setInputWindows(JNIEnv* env, jobjectArray windowObjArray);
    void setFocusedApplication(JNIEnv* env, jobject applicationObj);
    void setInputDispatchMode(bool enabled, bool frozen);
    void setSystemUiVisibility(int32_t visibility);

    /* --- InputReaderPolicyInterface implementation --- */

    virtual bool getDisplayInfo(int32_t displayId,
            int32_t* width, int32_t* height, int32_t* orientation);
    virtual bool filterTouchEvents();
    virtual bool filterJumpyTouchEvents();
    virtual nsecs_t getVirtualKeyQuietTime();
    virtual void getExcludedDeviceNames(Vector<String8>& outExcludedDeviceNames);
    virtual sp<PointerControllerInterface> obtainPointerController(int32_t deviceId);

    /* --- InputDispatcherPolicyInterface implementation --- */

    virtual void notifySwitch(nsecs_t when, int32_t switchCode, int32_t switchValue,
            uint32_t policyFlags);
    virtual void notifyConfigurationChanged(nsecs_t when);
    virtual nsecs_t notifyANR(const sp<InputApplicationHandle>& inputApplicationHandle,
            const sp<InputWindowHandle>& inputWindowHandle);
    virtual void notifyInputChannelBroken(const sp<InputWindowHandle>& inputWindowHandle);
    virtual nsecs_t getKeyRepeatTimeout();
    virtual nsecs_t getKeyRepeatDelay();
    virtual int32_t getMaxEventsPerSecond();
    virtual void interceptKeyBeforeQueueing(const KeyEvent* keyEvent, uint32_t& policyFlags);
    virtual void interceptMotionBeforeQueueing(nsecs_t when, uint32_t& policyFlags);
    virtual bool interceptKeyBeforeDispatching(const sp<InputWindowHandle>& inputWindowHandle,
            const KeyEvent* keyEvent, uint32_t policyFlags);
    virtual bool dispatchUnhandledKey(const sp<InputWindowHandle>& inputWindowHandle,
            const KeyEvent* keyEvent, uint32_t policyFlags, KeyEvent* outFallbackKeyEvent);
    virtual void pokeUserActivity(nsecs_t eventTime, int32_t eventType);
    virtual bool checkInjectEventsPermissionNonReentrant(
            int32_t injectorPid, int32_t injectorUid);

private:
    sp<InputManager> mInputManager;

    jobject mCallbacksObj;
    sp<Looper> mLooper;

    // Cached filtering policies.
    int32_t mFilterTouchEvents;
    int32_t mFilterJumpyTouchEvents;
    nsecs_t mVirtualKeyQuietTime;

    // Cached key repeat policy.
    nsecs_t mKeyRepeatTimeout;
    nsecs_t mKeyRepeatDelay;

    // Cached throttling policy.
    int32_t mMaxEventsPerSecond;

    Mutex mLock;
    struct Locked {
        // Display size information.
        int32_t displayWidth, displayHeight; // -1 when initialized
        int32_t displayOrientation;

        // System UI visibility.
        int32_t systemUiVisibility;

        // Pointer controller singleton, created and destroyed as needed.
        wp<PointerController> pointerController;
    } mLocked;

    void updateInactivityFadeDelayLocked(const sp<PointerController>& controller);
    void handleInterceptActions(jint wmActions, nsecs_t when, uint32_t& policyFlags);

    // Power manager interactions.
    bool isScreenOn();
    bool isScreenBright();

    static bool checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName);

    static inline JNIEnv* jniEnv() {
        return AndroidRuntime::getJNIEnv();
    }
};



NativeInputManager::NativeInputManager(jobject callbacksObj, const sp<Looper>& looper) :
        mLooper(looper),
        mFilterTouchEvents(-1), mFilterJumpyTouchEvents(-1), mVirtualKeyQuietTime(-1),
        mKeyRepeatTimeout(-1), mKeyRepeatDelay(-1),
        mMaxEventsPerSecond(-1) {
    JNIEnv* env = jniEnv();

    mCallbacksObj = env->NewGlobalRef(callbacksObj);

    {
        AutoMutex _l(mLock);
        mLocked.displayWidth = -1;
        mLocked.displayHeight = -1;
        mLocked.displayOrientation = ROTATION_0;

        mLocked.systemUiVisibility = ASYSTEM_UI_VISIBILITY_STATUS_BAR_VISIBLE;
    }

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
        AutoMutex _l(mLock);

        if (mLocked.displayWidth != width || mLocked.displayHeight != height) {
            mLocked.displayWidth = width;
            mLocked.displayHeight = height;

            sp<PointerController> controller = mLocked.pointerController.promote();
            if (controller != NULL) {
                controller->setDisplaySize(width, height);
            }
        }
    }
}

void NativeInputManager::setDisplayOrientation(int32_t displayId, int32_t orientation) {
    if (displayId == 0) {
        AutoMutex _l(mLock);

        if (mLocked.displayOrientation != orientation) {
            mLocked.displayOrientation = orientation;

            sp<PointerController> controller = mLocked.pointerController.promote();
            if (controller != NULL) {
                controller->setDisplayOrientation(orientation);
            }
        }
    }
}

status_t NativeInputManager::registerInputChannel(JNIEnv* env,
        const sp<InputChannel>& inputChannel,
        const sp<InputWindowHandle>& inputWindowHandle, bool monitor) {
    return mInputManager->getDispatcher()->registerInputChannel(
            inputChannel, inputWindowHandle, monitor);
}

status_t NativeInputManager::unregisterInputChannel(JNIEnv* env,
        const sp<InputChannel>& inputChannel) {
    return mInputManager->getDispatcher()->unregisterInputChannel(inputChannel);
}

bool NativeInputManager::getDisplayInfo(int32_t displayId,
        int32_t* width, int32_t* height, int32_t* orientation) {
    bool result = false;
    if (displayId == 0) {
        AutoMutex _l(mLock);

        if (mLocked.displayWidth > 0 && mLocked.displayHeight > 0) {
            if (width) {
                *width = mLocked.displayWidth;
            }
            if (height) {
                *height = mLocked.displayHeight;
            }
            if (orientation) {
                *orientation = mLocked.displayOrientation;
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

sp<PointerControllerInterface> NativeInputManager::obtainPointerController(int32_t deviceId) {
    AutoMutex _l(mLock);

    sp<PointerController> controller = mLocked.pointerController.promote();
    if (controller == NULL) {
        JNIEnv* env = jniEnv();
        jint layer = env->CallIntMethod(mCallbacksObj, gCallbacksClassInfo.getPointerLayer);
        if (checkAndClearExceptionFromCallback(env, "getPointerLayer")) {
            layer = -1;
        }

        controller = new PointerController(mLooper, layer);
        mLocked.pointerController = controller;

        controller->setDisplaySize(mLocked.displayWidth, mLocked.displayHeight);
        controller->setDisplayOrientation(mLocked.displayOrientation);

        jobject iconObj = env->CallObjectMethod(mCallbacksObj, gCallbacksClassInfo.getPointerIcon);
        if (!checkAndClearExceptionFromCallback(env, "getPointerIcon") && iconObj) {
            jfloat iconHotSpotX = env->GetFloatField(iconObj, gPointerIconClassInfo.hotSpotX);
            jfloat iconHotSpotY = env->GetFloatField(iconObj, gPointerIconClassInfo.hotSpotY);
            jobject iconBitmapObj = env->GetObjectField(iconObj, gPointerIconClassInfo.bitmap);
            if (iconBitmapObj) {
                SkBitmap* iconBitmap = GraphicsJNI::getNativeBitmap(env, iconBitmapObj);
                if (iconBitmap) {
                    controller->setPointerIcon(iconBitmap, iconHotSpotX, iconHotSpotY);
                }
                env->DeleteLocalRef(iconBitmapObj);
            }
            env->DeleteLocalRef(iconObj);
        }

        updateInactivityFadeDelayLocked(controller);
    }
    return controller;
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
        const sp<InputWindowHandle>& inputWindowHandle) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("notifyANR");
#endif

    JNIEnv* env = jniEnv();

    jobject inputApplicationHandleObj =
            getInputApplicationHandleObjLocalRef(env, inputApplicationHandle);
    jobject inputWindowHandleObj =
            getInputWindowHandleObjLocalRef(env, inputWindowHandle);

    jlong newTimeout = env->CallLongMethod(mCallbacksObj,
                gCallbacksClassInfo.notifyANR, inputApplicationHandleObj, inputWindowHandleObj);
    if (checkAndClearExceptionFromCallback(env, "notifyANR")) {
        newTimeout = 0; // abort dispatch
    } else {
        assert(newTimeout >= 0);
    }

    env->DeleteLocalRef(inputWindowHandleObj);
    env->DeleteLocalRef(inputApplicationHandleObj);
    return newTimeout;
}

void NativeInputManager::notifyInputChannelBroken(const sp<InputWindowHandle>& inputWindowHandle) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("notifyInputChannelBroken");
#endif

    JNIEnv* env = jniEnv();

    jobject inputWindowHandleObj =
            getInputWindowHandleObjLocalRef(env, inputWindowHandle);
    if (inputWindowHandleObj) {
        env->CallVoidMethod(mCallbacksObj, gCallbacksClassInfo.notifyInputChannelBroken,
                inputWindowHandleObj);
        checkAndClearExceptionFromCallback(env, "notifyInputChannelBroken");

        env->DeleteLocalRef(inputWindowHandleObj);
    }
}

nsecs_t NativeInputManager::getKeyRepeatTimeout() {
    if (! isScreenOn()) {
        // Disable key repeat when the screen is off.
        return -1;
    } else {
        if (mKeyRepeatTimeout < 0) {
            JNIEnv* env = jniEnv();

            jint result = env->CallIntMethod(mCallbacksObj,
                    gCallbacksClassInfo.getKeyRepeatTimeout);
            if (checkAndClearExceptionFromCallback(env, "getKeyRepeatTimeout")) {
                result = 500;
            }

            mKeyRepeatTimeout = milliseconds_to_nanoseconds(result);
        }
        return mKeyRepeatTimeout;
    }
}

nsecs_t NativeInputManager::getKeyRepeatDelay() {
    if (mKeyRepeatDelay < 0) {
        JNIEnv* env = jniEnv();

        jint result = env->CallIntMethod(mCallbacksObj,
                gCallbacksClassInfo.getKeyRepeatDelay);
        if (checkAndClearExceptionFromCallback(env, "getKeyRepeatDelay")) {
            result = 50;
        }

        mKeyRepeatDelay = milliseconds_to_nanoseconds(result);
    }
    return mKeyRepeatDelay;
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
        jobject windowObj = env->GetObjectArrayElement(windowObjArray, i);
        if (! windowObj) {
            break; // found null element indicating end of used portion of the array
        }

        windows.push();
        InputWindow& window = windows.editTop();
        android_server_InputWindow_toNative(env, windowObj, &window);
        if (window.inputChannel == NULL) {
            windows.pop();
        }
        env->DeleteLocalRef(windowObj);
    }

    mInputManager->getDispatcher()->setInputWindows(windows);
}

void NativeInputManager::setFocusedApplication(JNIEnv* env, jobject applicationObj) {
    if (applicationObj) {
        InputApplication application;
        android_server_InputApplication_toNative(env, applicationObj, &application);
        if (application.inputApplicationHandle != NULL) {
            mInputManager->getDispatcher()->setFocusedApplication(&application);
            return;
        }
    }
    mInputManager->getDispatcher()->setFocusedApplication(NULL);
}

void NativeInputManager::setInputDispatchMode(bool enabled, bool frozen) {
    mInputManager->getDispatcher()->setInputDispatchMode(enabled, frozen);
}

void NativeInputManager::setSystemUiVisibility(int32_t visibility) {
    AutoMutex _l(mLock);

    if (mLocked.systemUiVisibility != visibility) {
        mLocked.systemUiVisibility = visibility;

        sp<PointerController> controller = mLocked.pointerController.promote();
        if (controller != NULL) {
            updateInactivityFadeDelayLocked(controller);
        }
    }
}

void NativeInputManager::updateInactivityFadeDelayLocked(const sp<PointerController>& controller) {
    bool lightsOut = mLocked.systemUiVisibility & ASYSTEM_UI_VISIBILITY_STATUS_BAR_HIDDEN;
    controller->setInactivityFadeDelay(lightsOut
            ? PointerController::INACTIVITY_FADE_DELAY_SHORT
            : PointerController::INACTIVITY_FADE_DELAY_NORMAL);
}

bool NativeInputManager::isScreenOn() {
    return android_server_PowerManagerService_isScreenOn();
}

bool NativeInputManager::isScreenBright() {
    return android_server_PowerManagerService_isScreenBright();
}

void NativeInputManager::interceptKeyBeforeQueueing(const KeyEvent* keyEvent,
        uint32_t& policyFlags) {
    // Policy:
    // - Ignore untrusted events and pass them along.
    // - Ask the window manager what to do with normal events and trusted injected events.
    // - For normal events wake and brighten the screen if currently off or dim.
    if ((policyFlags & POLICY_FLAG_TRUSTED)) {
        nsecs_t when = keyEvent->getEventTime();
        bool isScreenOn = this->isScreenOn();
        bool isScreenBright = this->isScreenBright();

        JNIEnv* env = jniEnv();
        jobject keyEventObj = android_view_KeyEvent_fromNative(env, keyEvent);
        jint wmActions;
        if (keyEventObj) {
            wmActions = env->CallIntMethod(mCallbacksObj,
                    gCallbacksClassInfo.interceptKeyBeforeQueueing,
                    keyEventObj, policyFlags, isScreenOn);
            if (checkAndClearExceptionFromCallback(env, "interceptKeyBeforeQueueing")) {
                wmActions = 0;
            }
            android_view_KeyEvent_recycle(env, keyEventObj);
            env->DeleteLocalRef(keyEventObj);
        } else {
            LOGE("Failed to obtain key event object for interceptKeyBeforeQueueing.");
            wmActions = 0;
        }

        if (!(policyFlags & POLICY_FLAG_INJECTED)) {
            if (!isScreenOn) {
                policyFlags |= POLICY_FLAG_WOKE_HERE;
            }

            if (!isScreenBright) {
                policyFlags |= POLICY_FLAG_BRIGHT_HERE;
            }
        }

        handleInterceptActions(wmActions, when, /*byref*/ policyFlags);
    } else {
        policyFlags |= POLICY_FLAG_PASS_TO_USER;
    }
}

void NativeInputManager::interceptMotionBeforeQueueing(nsecs_t when, uint32_t& policyFlags) {
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
        } else {
            JNIEnv* env = jniEnv();
            jint wmActions = env->CallIntMethod(mCallbacksObj,
                        gCallbacksClassInfo.interceptMotionBeforeQueueingWhenScreenOff,
                        policyFlags);
            if (checkAndClearExceptionFromCallback(env,
                    "interceptMotionBeforeQueueingWhenScreenOff")) {
                wmActions = 0;
            }

            policyFlags |= POLICY_FLAG_WOKE_HERE | POLICY_FLAG_BRIGHT_HERE;
            handleInterceptActions(wmActions, when, /*byref*/ policyFlags);
        }
    } else {
        policyFlags |= POLICY_FLAG_PASS_TO_USER;
    }
}

void NativeInputManager::handleInterceptActions(jint wmActions, nsecs_t when,
        uint32_t& policyFlags) {
    enum {
        WM_ACTION_PASS_TO_USER = 1,
        WM_ACTION_POKE_USER_ACTIVITY = 2,
        WM_ACTION_GO_TO_SLEEP = 4,
    };

    if (wmActions & WM_ACTION_GO_TO_SLEEP) {
#if DEBUG_INPUT_DISPATCHER_POLICY
        LOGD("handleInterceptActions: Going to sleep.");
#endif
        android_server_PowerManagerService_goToSleep(when);
    }

    if (wmActions & WM_ACTION_POKE_USER_ACTIVITY) {
#if DEBUG_INPUT_DISPATCHER_POLICY
        LOGD("handleInterceptActions: Poking user activity.");
#endif
        android_server_PowerManagerService_userActivity(when, POWER_MANAGER_BUTTON_EVENT);
    }

    if (wmActions & WM_ACTION_PASS_TO_USER) {
        policyFlags |= POLICY_FLAG_PASS_TO_USER;
    } else {
#if DEBUG_INPUT_DISPATCHER_POLICY
        LOGD("handleInterceptActions: Not passing key to user.");
#endif
    }
}

bool NativeInputManager::interceptKeyBeforeDispatching(
        const sp<InputWindowHandle>& inputWindowHandle,
        const KeyEvent* keyEvent, uint32_t policyFlags) {
    // Policy:
    // - Ignore untrusted events and pass them along.
    // - Filter normal events and trusted injected events through the window manager policy to
    //   handle the HOME key and the like.
    bool result = false;
    if (policyFlags & POLICY_FLAG_TRUSTED) {
        JNIEnv* env = jniEnv();

        // Note: inputWindowHandle may be null.
        jobject inputWindowHandleObj = getInputWindowHandleObjLocalRef(env, inputWindowHandle);
        jobject keyEventObj = android_view_KeyEvent_fromNative(env, keyEvent);
        if (keyEventObj) {
            jboolean consumed = env->CallBooleanMethod(mCallbacksObj,
                    gCallbacksClassInfo.interceptKeyBeforeDispatching,
                    inputWindowHandleObj, keyEventObj, policyFlags);
            bool error = checkAndClearExceptionFromCallback(env, "interceptKeyBeforeDispatching");
            android_view_KeyEvent_recycle(env, keyEventObj);
            env->DeleteLocalRef(keyEventObj);
            result = consumed && !error;
        } else {
            LOGE("Failed to obtain key event object for interceptKeyBeforeDispatching.");
        }
        env->DeleteLocalRef(inputWindowHandleObj);
    }
    return result;
}

bool NativeInputManager::dispatchUnhandledKey(const sp<InputWindowHandle>& inputWindowHandle,
        const KeyEvent* keyEvent, uint32_t policyFlags, KeyEvent* outFallbackKeyEvent) {
    // Policy:
    // - Ignore untrusted events and do not perform default handling.
    bool result = false;
    if (policyFlags & POLICY_FLAG_TRUSTED) {
        JNIEnv* env = jniEnv();

        // Note: inputWindowHandle may be null.
        jobject inputWindowHandleObj = getInputWindowHandleObjLocalRef(env, inputWindowHandle);
        jobject keyEventObj = android_view_KeyEvent_fromNative(env, keyEvent);
        if (keyEventObj) {
            jobject fallbackKeyEventObj = env->CallObjectMethod(mCallbacksObj,
                    gCallbacksClassInfo.dispatchUnhandledKey,
                    inputWindowHandleObj, keyEventObj, policyFlags);
            checkAndClearExceptionFromCallback(env, "dispatchUnhandledKey");
            android_view_KeyEvent_recycle(env, keyEventObj);
            env->DeleteLocalRef(keyEventObj);

            if (fallbackKeyEventObj) {
                // Note: outFallbackKeyEvent may be the same object as keyEvent.
                if (!android_view_KeyEvent_toNative(env, fallbackKeyEventObj,
                        outFallbackKeyEvent)) {
                    result = true;
                }
                android_view_KeyEvent_recycle(env, fallbackKeyEventObj);
                env->DeleteLocalRef(fallbackKeyEventObj);
            }
        } else {
            LOGE("Failed to obtain key event object for dispatchUnhandledKey.");
        }
        env->DeleteLocalRef(inputWindowHandleObj);
    }
    return result;
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
        jobject callbacks, jobject messageQueueObj) {
    if (gNativeInputManager == NULL) {
        sp<Looper> looper = android_os_MessageQueue_getLooper(env, messageQueueObj);
        gNativeInputManager = new NativeInputManager(callbacks, looper);
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
        jobject inputChannelObj, jobject inputWindowHandleObj, jboolean monitor) {
    if (checkInputManagerUnitialized(env)) {
        return;
    }

    sp<InputChannel> inputChannel = android_view_InputChannel_getInputChannel(env,
            inputChannelObj);
    if (inputChannel == NULL) {
        throwInputChannelNotInitialized(env);
        return;
    }

    sp<InputWindowHandle> inputWindowHandle =
            android_server_InputWindowHandle_getHandle(env, inputWindowHandleObj);

    status_t status = gNativeInputManager->registerInputChannel(
            env, inputChannel, inputWindowHandle, monitor);
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
        status_t status = android_view_KeyEvent_toNative(env, inputEventObj, & keyEvent);
        if (status) {
            jniThrowRuntimeException(env, "Could not read contents of KeyEvent object.");
            return INPUT_EVENT_INJECTION_FAILED;
        }

        return gNativeInputManager->getInputManager()->getDispatcher()->injectInputEvent(
                & keyEvent, injectorPid, injectorUid, syncMode, timeoutMillis);
    } else if (env->IsInstanceOf(inputEventObj, gMotionEventClassInfo.clazz)) {
        const MotionEvent* motionEvent = android_view_MotionEvent_getNativePtr(env, inputEventObj);
        if (!motionEvent) {
            jniThrowRuntimeException(env, "Could not read contents of MotionEvent object.");
            return INPUT_EVENT_INJECTION_FAILED;
        }

        return gNativeInputManager->getInputManager()->getDispatcher()->injectInputEvent(
                motionEvent, injectorPid, injectorUid, syncMode, timeoutMillis);
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

static void android_server_InputManager_nativeSetSystemUiVisibility(JNIEnv* env,
        jclass clazz, jint visibility) {
    if (checkInputManagerUnitialized(env)) {
        return;
    }

    gNativeInputManager->setSystemUiVisibility(visibility);
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

    const Vector<InputDeviceInfo::MotionRange>& ranges = deviceInfo.getMotionRanges();
    for (size_t i = 0; i < ranges.size(); i++) {
        const InputDeviceInfo::MotionRange& range = ranges.itemAt(i);
        env->CallVoidMethod(deviceObj, gInputDeviceClassInfo.addMotionRange,
                range.axis, range.source, range.min, range.max, range.flat, range.fuzz);
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

static jboolean android_server_InputManager_nativeTransferTouchFocus(JNIEnv* env,
        jclass clazz, jobject fromChannelObj, jobject toChannelObj) {
    if (checkInputManagerUnitialized(env)) {
        return false;
    }

    sp<InputChannel> fromChannel =
            android_view_InputChannel_getInputChannel(env, fromChannelObj);
    sp<InputChannel> toChannel =
            android_view_InputChannel_getInputChannel(env, toChannelObj);

    if (fromChannel == NULL || toChannel == NULL) {
        return false;
    }

    return gNativeInputManager->getInputManager()->getDispatcher()->
            transferTouchFocus(fromChannel, toChannel);
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
    { "nativeInit", "(Lcom/android/server/wm/InputManager$Callbacks;Landroid/os/MessageQueue;)V",
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
    { "nativeRegisterInputChannel",
            "(Landroid/view/InputChannel;Lcom/android/server/wm/InputWindowHandle;Z)V",
            (void*) android_server_InputManager_nativeRegisterInputChannel },
    { "nativeUnregisterInputChannel", "(Landroid/view/InputChannel;)V",
            (void*) android_server_InputManager_nativeUnregisterInputChannel },
    { "nativeInjectInputEvent", "(Landroid/view/InputEvent;IIII)I",
            (void*) android_server_InputManager_nativeInjectInputEvent },
    { "nativeSetInputWindows", "([Lcom/android/server/wm/InputWindow;)V",
            (void*) android_server_InputManager_nativeSetInputWindows },
    { "nativeSetFocusedApplication", "(Lcom/android/server/wm/InputApplication;)V",
            (void*) android_server_InputManager_nativeSetFocusedApplication },
    { "nativeSetInputDispatchMode", "(ZZ)V",
            (void*) android_server_InputManager_nativeSetInputDispatchMode },
    { "nativeSetSystemUiVisibility", "(I)V",
            (void*) android_server_InputManager_nativeSetSystemUiVisibility },
    { "nativeGetInputDevice", "(I)Landroid/view/InputDevice;",
            (void*) android_server_InputManager_nativeGetInputDevice },
    { "nativeGetInputDeviceIds", "()[I",
            (void*) android_server_InputManager_nativeGetInputDeviceIds },
    { "nativeGetInputConfiguration", "(Landroid/content/res/Configuration;)V",
            (void*) android_server_InputManager_nativeGetInputConfiguration },
    { "nativeTransferTouchFocus", "(Landroid/view/InputChannel;Landroid/view/InputChannel;)Z",
            (void*) android_server_InputManager_nativeTransferTouchFocus },
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
    int res = jniRegisterNativeMethods(env, "com/android/server/wm/InputManager",
            gInputManagerMethods, NELEM(gInputManagerMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    // Callbacks

    FIND_CLASS(gCallbacksClassInfo.clazz, "com/android/server/wm/InputManager$Callbacks");

    GET_METHOD_ID(gCallbacksClassInfo.notifyConfigurationChanged, gCallbacksClassInfo.clazz,
            "notifyConfigurationChanged", "(J)V");

    GET_METHOD_ID(gCallbacksClassInfo.notifyLidSwitchChanged, gCallbacksClassInfo.clazz,
            "notifyLidSwitchChanged", "(JZ)V");

    GET_METHOD_ID(gCallbacksClassInfo.notifyInputChannelBroken, gCallbacksClassInfo.clazz,
            "notifyInputChannelBroken", "(Lcom/android/server/wm/InputWindowHandle;)V");

    GET_METHOD_ID(gCallbacksClassInfo.notifyANR, gCallbacksClassInfo.clazz,
            "notifyANR",
            "(Lcom/android/server/wm/InputApplicationHandle;Lcom/android/server/wm/InputWindowHandle;)J");

    GET_METHOD_ID(gCallbacksClassInfo.interceptKeyBeforeQueueing, gCallbacksClassInfo.clazz,
            "interceptKeyBeforeQueueing", "(Landroid/view/KeyEvent;IZ)I");

    GET_METHOD_ID(gCallbacksClassInfo.interceptMotionBeforeQueueingWhenScreenOff,
            gCallbacksClassInfo.clazz,
            "interceptMotionBeforeQueueingWhenScreenOff", "(I)I");

    GET_METHOD_ID(gCallbacksClassInfo.interceptKeyBeforeDispatching, gCallbacksClassInfo.clazz,
            "interceptKeyBeforeDispatching",
            "(Lcom/android/server/wm/InputWindowHandle;Landroid/view/KeyEvent;I)Z");

    GET_METHOD_ID(gCallbacksClassInfo.dispatchUnhandledKey, gCallbacksClassInfo.clazz,
            "dispatchUnhandledKey",
            "(Lcom/android/server/wm/InputWindowHandle;Landroid/view/KeyEvent;I)Landroid/view/KeyEvent;");

    GET_METHOD_ID(gCallbacksClassInfo.checkInjectEventsPermission, gCallbacksClassInfo.clazz,
            "checkInjectEventsPermission", "(II)Z");

    GET_METHOD_ID(gCallbacksClassInfo.filterTouchEvents, gCallbacksClassInfo.clazz,
            "filterTouchEvents", "()Z");

    GET_METHOD_ID(gCallbacksClassInfo.filterJumpyTouchEvents, gCallbacksClassInfo.clazz,
            "filterJumpyTouchEvents", "()Z");

    GET_METHOD_ID(gCallbacksClassInfo.getVirtualKeyQuietTimeMillis, gCallbacksClassInfo.clazz,
            "getVirtualKeyQuietTimeMillis", "()I");

    GET_METHOD_ID(gCallbacksClassInfo.getExcludedDeviceNames, gCallbacksClassInfo.clazz,
            "getExcludedDeviceNames", "()[Ljava/lang/String;");

    GET_METHOD_ID(gCallbacksClassInfo.getKeyRepeatTimeout, gCallbacksClassInfo.clazz,
            "getKeyRepeatTimeout", "()I");

    GET_METHOD_ID(gCallbacksClassInfo.getKeyRepeatDelay, gCallbacksClassInfo.clazz,
            "getKeyRepeatDelay", "()I");

    GET_METHOD_ID(gCallbacksClassInfo.getMaxEventsPerSecond, gCallbacksClassInfo.clazz,
            "getMaxEventsPerSecond", "()I");

    GET_METHOD_ID(gCallbacksClassInfo.getPointerLayer, gCallbacksClassInfo.clazz,
            "getPointerLayer", "()I");

    GET_METHOD_ID(gCallbacksClassInfo.getPointerIcon, gCallbacksClassInfo.clazz,
            "getPointerIcon", "()Lcom/android/server/wm/InputManager$PointerIcon;");

    // KeyEvent

    FIND_CLASS(gKeyEventClassInfo.clazz, "android/view/KeyEvent");

    // MotionEvent

    FIND_CLASS(gMotionEventClassInfo.clazz, "android/view/MotionEvent");

    // InputDevice

    FIND_CLASS(gInputDeviceClassInfo.clazz, "android/view/InputDevice");

    GET_METHOD_ID(gInputDeviceClassInfo.ctor, gInputDeviceClassInfo.clazz,
            "<init>", "()V");

    GET_METHOD_ID(gInputDeviceClassInfo.addMotionRange, gInputDeviceClassInfo.clazz,
            "addMotionRange", "(IIFFFF)V");

    GET_FIELD_ID(gInputDeviceClassInfo.mId, gInputDeviceClassInfo.clazz,
            "mId", "I");

    GET_FIELD_ID(gInputDeviceClassInfo.mName, gInputDeviceClassInfo.clazz,
            "mName", "Ljava/lang/String;");

    GET_FIELD_ID(gInputDeviceClassInfo.mSources, gInputDeviceClassInfo.clazz,
            "mSources", "I");

    GET_FIELD_ID(gInputDeviceClassInfo.mKeyboardType, gInputDeviceClassInfo.clazz,
            "mKeyboardType", "I");

    // Configuration

    FIND_CLASS(gConfigurationClassInfo.clazz, "android/content/res/Configuration");

    GET_FIELD_ID(gConfigurationClassInfo.touchscreen, gConfigurationClassInfo.clazz,
            "touchscreen", "I");

    GET_FIELD_ID(gConfigurationClassInfo.keyboard, gConfigurationClassInfo.clazz,
            "keyboard", "I");

    GET_FIELD_ID(gConfigurationClassInfo.navigation, gConfigurationClassInfo.clazz,
            "navigation", "I");

    // PointerIcon

    FIND_CLASS(gPointerIconClassInfo.clazz, "com/android/server/wm/InputManager$PointerIcon");

    GET_FIELD_ID(gPointerIconClassInfo.bitmap, gPointerIconClassInfo.clazz,
            "bitmap", "Landroid/graphics/Bitmap;");

    GET_FIELD_ID(gPointerIconClassInfo.hotSpotX, gPointerIconClassInfo.clazz,
            "hotSpotX", "F");

    GET_FIELD_ID(gPointerIconClassInfo.hotSpotY, gPointerIconClassInfo.clazz,
            "hotSpotY", "F");

    return 0;
}

} /* namespace android */
