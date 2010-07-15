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

// Log debug messages about input focus tracking
#define DEBUG_FOCUS 0

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

// Window flags from WindowManager.LayoutParams
enum {
    FLAG_ALLOW_LOCK_WHILE_SCREEN_ON     = 0x00000001,
    FLAG_DIM_BEHIND        = 0x00000002,
    FLAG_BLUR_BEHIND        = 0x00000004,
    FLAG_NOT_FOCUSABLE      = 0x00000008,
    FLAG_NOT_TOUCHABLE      = 0x00000010,
    FLAG_NOT_TOUCH_MODAL    = 0x00000020,
    FLAG_TOUCHABLE_WHEN_WAKING = 0x00000040,
    FLAG_KEEP_SCREEN_ON     = 0x00000080,
    FLAG_LAYOUT_IN_SCREEN   = 0x00000100,
    FLAG_LAYOUT_NO_LIMITS   = 0x00000200,
    FLAG_FULLSCREEN      = 0x00000400,
    FLAG_FORCE_NOT_FULLSCREEN   = 0x00000800,
    FLAG_DITHER             = 0x00001000,
    FLAG_SECURE             = 0x00002000,
    FLAG_SCALED             = 0x00004000,
    FLAG_IGNORE_CHEEK_PRESSES    = 0x00008000,
    FLAG_LAYOUT_INSET_DECOR = 0x00010000,
    FLAG_ALT_FOCUSABLE_IM = 0x00020000,
    FLAG_WATCH_OUTSIDE_TOUCH = 0x00040000,
    FLAG_SHOW_WHEN_LOCKED = 0x00080000,
    FLAG_SHOW_WALLPAPER = 0x00100000,
    FLAG_TURN_SCREEN_ON = 0x00200000,
    FLAG_DISMISS_KEYGUARD = 0x00400000,
    FLAG_IMMERSIVE = 0x00800000,
    FLAG_KEEP_SURFACE_WHILE_ANIMATING = 0x10000000,
    FLAG_COMPATIBLE_WINDOW = 0x20000000,
    FLAG_SYSTEM_ERROR = 0x40000000,
};

// Window types from WindowManager.LayoutParams
enum {
    FIRST_APPLICATION_WINDOW = 1,
    TYPE_BASE_APPLICATION   = 1,
    TYPE_APPLICATION        = 2,
    TYPE_APPLICATION_STARTING = 3,
    LAST_APPLICATION_WINDOW = 99,
    FIRST_SUB_WINDOW        = 1000,
    TYPE_APPLICATION_PANEL  = FIRST_SUB_WINDOW,
    TYPE_APPLICATION_MEDIA  = FIRST_SUB_WINDOW+1,
    TYPE_APPLICATION_SUB_PANEL = FIRST_SUB_WINDOW+2,
    TYPE_APPLICATION_ATTACHED_DIALOG = FIRST_SUB_WINDOW+3,
    TYPE_APPLICATION_MEDIA_OVERLAY  = FIRST_SUB_WINDOW+4,
    LAST_SUB_WINDOW         = 1999,
    FIRST_SYSTEM_WINDOW     = 2000,
    TYPE_STATUS_BAR         = FIRST_SYSTEM_WINDOW,
    TYPE_SEARCH_BAR         = FIRST_SYSTEM_WINDOW+1,
    TYPE_PHONE              = FIRST_SYSTEM_WINDOW+2,
    TYPE_SYSTEM_ALERT       = FIRST_SYSTEM_WINDOW+3,
    TYPE_KEYGUARD           = FIRST_SYSTEM_WINDOW+4,
    TYPE_TOAST              = FIRST_SYSTEM_WINDOW+5,
    TYPE_SYSTEM_OVERLAY     = FIRST_SYSTEM_WINDOW+6,
    TYPE_PRIORITY_PHONE     = FIRST_SYSTEM_WINDOW+7,
    TYPE_SYSTEM_DIALOG      = FIRST_SYSTEM_WINDOW+8,
    TYPE_KEYGUARD_DIALOG    = FIRST_SYSTEM_WINDOW+9,
    TYPE_SYSTEM_ERROR       = FIRST_SYSTEM_WINDOW+10,
    TYPE_INPUT_METHOD       = FIRST_SYSTEM_WINDOW+11,
    TYPE_INPUT_METHOD_DIALOG= FIRST_SYSTEM_WINDOW+12,
    TYPE_WALLPAPER          = FIRST_SYSTEM_WINDOW+13,
    TYPE_STATUS_BAR_PANEL   = FIRST_SYSTEM_WINDOW+14,
    LAST_SYSTEM_WINDOW      = 2999,
};

// Delay between reporting long touch events to the power manager.
const nsecs_t EVENT_IGNORE_DURATION = 300 * 1000000LL; // 300 ms

// Default input dispatching timeout if there is no focused application or paused window
// from which to determine an appropriate dispatching timeout.
const nsecs_t DEFAULT_INPUT_DISPATCHING_TIMEOUT = 5000 * 1000000LL; // 5 sec

// Minimum amount of time to provide to the input dispatcher for delivery of an event
// regardless of how long the application window was paused.
const nsecs_t MIN_INPUT_DISPATCHING_TIMEOUT = 1000 * 1000000LL; // 1 sec

// ----------------------------------------------------------------------------

static struct {
    jclass clazz;

    jmethodID notifyConfigurationChanged;
    jmethodID notifyLidSwitchChanged;
    jmethodID notifyInputChannelBroken;
    jmethodID notifyInputChannelANR;
    jmethodID notifyInputChannelRecoveredFromANR;
    jmethodID notifyANR;
    jmethodID virtualKeyDownFeedback;
    jmethodID interceptKeyBeforeQueueing;
    jmethodID interceptKeyBeforeDispatching;
    jmethodID checkInjectEventsPermission;
    jmethodID notifyAppSwitchComing;
    jmethodID filterTouchEvents;
    jmethodID filterJumpyTouchEvents;
    jmethodID getVirtualKeyDefinitions;
    jmethodID getExcludedDeviceNames;
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

    jfieldID inputChannel;
    jfieldID layoutParamsFlags;
    jfieldID layoutParamsType;
    jfieldID dispatchingTimeoutNanos;
    jfieldID frameLeft;
    jfieldID frameTop;
    jfieldID touchableAreaLeft;
    jfieldID touchableAreaTop;
    jfieldID touchableAreaRight;
    jfieldID touchableAreaBottom;
    jfieldID visible;
    jfieldID hasFocus;
    jfieldID hasWallpaper;
    jfieldID paused;
    jfieldID ownerPid;
    jfieldID ownerUid;
} gInputWindowClassInfo;

static struct {
    jclass clazz;

    jfieldID name;
    jfieldID dispatchingTimeoutNanos;
    jfieldID token;
} gInputApplicationClassInfo;

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

    void setDisplaySize(int32_t displayId, int32_t width, int32_t height);
    void setDisplayOrientation(int32_t displayId, int32_t orientation);

    status_t registerInputChannel(JNIEnv* env, const sp<InputChannel>& inputChannel,
            jweak inputChannelObjWeak);
    status_t unregisterInputChannel(JNIEnv* env, const sp<InputChannel>& inputChannel);

    void setInputWindows(JNIEnv* env, jobjectArray windowObjArray);
    void setFocusedApplication(JNIEnv* env, jobject applicationObj);
    void setInputDispatchMode(bool enabled, bool frozen);
    void preemptInputDispatch();

    /* --- InputReaderPolicyInterface implementation --- */

    virtual bool getDisplayInfo(int32_t displayId,
            int32_t* width, int32_t* height, int32_t* orientation);
    virtual void virtualKeyDownFeedback();
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
    virtual bool notifyInputChannelANR(const sp<InputChannel>& inputChannel,
            nsecs_t& outNewTimeout);
    virtual void notifyInputChannelRecoveredFromANR(const sp<InputChannel>& inputChannel);
    virtual nsecs_t getKeyRepeatTimeout();
    virtual int32_t waitForKeyEventTargets(KeyEvent* keyEvent, uint32_t policyFlags,
            int32_t injectorPid, int32_t injectorUid, Vector<InputTarget>& outTargets);
    virtual int32_t waitForMotionEventTargets(MotionEvent* motionEvent, uint32_t policyFlags,
            int32_t injectorPid, int32_t injectorUid, Vector<InputTarget>& outTargets);

private:
    struct InputWindow {
        sp<InputChannel> inputChannel;
        int32_t layoutParamsFlags;
        int32_t layoutParamsType;
        nsecs_t dispatchingTimeout;
        int32_t frameLeft;
        int32_t frameTop;
        int32_t touchableAreaLeft;
        int32_t touchableAreaTop;
        int32_t touchableAreaRight;
        int32_t touchableAreaBottom;
        bool visible;
        bool hasFocus;
        bool hasWallpaper;
        bool paused;
        int32_t ownerPid;
        int32_t ownerUid;

        inline bool touchableAreaContainsPoint(int32_t x, int32_t y) {
            return x >= touchableAreaLeft && x <= touchableAreaRight
                    && y >= touchableAreaTop && y <= touchableAreaBottom;
        }
    };

    struct InputApplication {
        String8 name;
        nsecs_t dispatchingTimeout;
        jweak tokenObjWeak;
    };

    class ANRTimer {
        enum Budget {
            SYSTEM = 0,
            APPLICATION = 1
        };

        Budget mBudget;
        nsecs_t mStartTime;
        bool mFrozen;
        InputWindow* mPausedWindow;

    public:
        ANRTimer();

        void dispatchFrozenBySystem();
        void dispatchPausedByApplication(InputWindow* pausedWindow);
        bool waitForDispatchStateChangeLd(NativeInputManager* inputManager);

        nsecs_t getTimeSpentWaitingForApplication() const;
    };

    sp<InputManager> mInputManager;

    jobject mCallbacksObj;

    // Cached filtering policies.
    int32_t mFilterTouchEvents;
    int32_t mFilterJumpyTouchEvents;

    // Cached display state.  (lock mDisplayLock)
    Mutex mDisplayLock;
    int32_t mDisplayWidth, mDisplayHeight;
    int32_t mDisplayOrientation;

    // Power manager interactions.
    bool isScreenOn();
    bool isScreenBright();

    // Weak references to all currently registered input channels by receive fd.
    Mutex mInputChannelRegistryLock;
    KeyedVector<int, jweak> mInputChannelObjWeakByReceiveFd;

    jobject getInputChannelObjLocal(JNIEnv* env, const sp<InputChannel>& inputChannel);

    // Input target and focus tracking.  (lock mDispatchLock)
    Mutex mDispatchLock;
    Condition mDispatchStateChanged;

    bool mDispatchEnabled;
    bool mDispatchFrozen;
    bool mWindowsReady;
    Vector<InputWindow> mWindows;
    Vector<InputWindow*> mWallpaperWindows;

    // Focus tracking for keys, trackball, etc.
    InputWindow* mFocusedWindow;

    // Focus tracking for touch.
    bool mTouchDown;
    InputWindow* mTouchedWindow;                   // primary target for current down
    Vector<InputWindow*> mTouchedWallpaperWindows; // wallpaper targets

    Vector<InputWindow*> mTempTouchedOutsideWindows; // temporary outside touch targets
    Vector<sp<InputChannel> > mTempTouchedWallpaperChannels; // temporary wallpaper targets

    // Focused application.
    InputApplication* mFocusedApplication;
    InputApplication mFocusedApplicationStorage; // preallocated storage for mFocusedApplication

    void dumpDispatchStateLd();

    bool notifyANR(jobject tokenObj, nsecs_t& outNewTimeout);
    void releaseFocusedApplicationLd(JNIEnv* env);

    int32_t waitForFocusedWindowLd(uint32_t policyFlags, int32_t injectorPid, int32_t injectorUid,
            Vector<InputTarget>& outTargets, InputWindow*& outFocusedWindow);
    int32_t waitForTouchedWindowLd(MotionEvent* motionEvent, uint32_t policyFlags,
            int32_t injectorPid, int32_t injectorUid,
            Vector<InputTarget>& outTargets, InputWindow*& outTouchedWindow);

    void releaseTouchedWindowLd();

    int32_t waitForNonTouchEventTargets(MotionEvent* motionEvent, uint32_t policyFlags,
            int32_t injectorPid, int32_t injectorUid, Vector<InputTarget>& outTargets);
    int32_t waitForTouchEventTargets(MotionEvent* motionEvent, uint32_t policyFlags,
            int32_t injectorPid, int32_t injectorUid, Vector<InputTarget>& outTargets);

    bool interceptKeyBeforeDispatching(const InputTarget& target,
            const KeyEvent* keyEvent, uint32_t policyFlags);

    void pokeUserActivityIfNeeded(int32_t windowType, int32_t eventType);
    void pokeUserActivity(nsecs_t eventTime, int32_t eventType);
    bool checkInjectionPermission(const InputWindow* window,
            int32_t injectorPid, int32_t injectorUid);

    static bool populateWindow(JNIEnv* env, jobject windowObj, InputWindow& outWindow);
    static void addTarget(const InputWindow* window, int32_t targetFlags,
            nsecs_t timeSpentWaitingForApplication, Vector<InputTarget>& outTargets);

    static inline JNIEnv* jniEnv() {
        return AndroidRuntime::getJNIEnv();
    }

    static bool isAppSwitchKey(int32_t keyCode);
    static bool isPolicyKey(int32_t keyCode, bool isScreenOn);
    static bool checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName);
};

// ----------------------------------------------------------------------------

NativeInputManager::NativeInputManager(jobject callbacksObj) :
    mFilterTouchEvents(-1), mFilterJumpyTouchEvents(-1),
    mDisplayWidth(-1), mDisplayHeight(-1), mDisplayOrientation(ROTATION_0),
    mDispatchEnabled(true), mDispatchFrozen(false), mWindowsReady(true),
    mFocusedWindow(NULL), mTouchDown(false), mTouchedWindow(NULL),
    mFocusedApplication(NULL) {
    JNIEnv* env = jniEnv();

    mCallbacksObj = env->NewGlobalRef(callbacksObj);

    sp<EventHub> eventHub = new EventHub();
    mInputManager = new InputManager(eventHub, this, this);
}

NativeInputManager::~NativeInputManager() {
    JNIEnv* env = jniEnv();

    env->DeleteGlobalRef(mCallbacksObj);

    releaseFocusedApplicationLd(env);
}

bool NativeInputManager::isAppSwitchKey(int32_t keyCode) {
    return keyCode == AKEYCODE_HOME || keyCode == AKEYCODE_ENDCALL;
}

bool NativeInputManager::isPolicyKey(int32_t keyCode, bool isScreenOn) {
    // Special keys that the WindowManagerPolicy might care about.
    switch (keyCode) {
    case AKEYCODE_VOLUME_UP:
    case AKEYCODE_VOLUME_DOWN:
    case AKEYCODE_ENDCALL:
    case AKEYCODE_POWER:
    case AKEYCODE_CALL:
    case AKEYCODE_HOME:
    case AKEYCODE_MENU:
    case AKEYCODE_SEARCH:
        // media keys
    case AKEYCODE_HEADSETHOOK:
    case AKEYCODE_MEDIA_PLAY_PAUSE:
    case AKEYCODE_MEDIA_STOP:
    case AKEYCODE_MEDIA_NEXT:
    case AKEYCODE_MEDIA_PREVIOUS:
    case AKEYCODE_MEDIA_REWIND:
    case AKEYCODE_MEDIA_FAST_FORWARD:
        return true;
    default:
        // We need to pass all keys to the policy in the following cases:
        // - screen is off
        // - keyguard is visible
        // - policy is performing key chording
        //return ! isScreenOn || keyguardVisible || chording;
        return true; // XXX stubbed out for now
    }
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
        const sp<InputChannel>& inputChannel, jobject inputChannelObj) {
    jweak inputChannelObjWeak = env->NewWeakGlobalRef(inputChannelObj);
    if (! inputChannelObjWeak) {
        LOGE("Could not create weak reference for input channel.");
        LOGE_EX(env);
        return NO_MEMORY;
    }

    status_t status;
    {
        AutoMutex _l(mInputChannelRegistryLock);

        ssize_t index = mInputChannelObjWeakByReceiveFd.indexOfKey(
                inputChannel->getReceivePipeFd());
        if (index >= 0) {
            LOGE("Input channel object '%s' has already been registered",
                    inputChannel->getName().string());
            status = INVALID_OPERATION;
            goto DeleteWeakRef;
        }

        mInputChannelObjWeakByReceiveFd.add(inputChannel->getReceivePipeFd(),
                inputChannelObjWeak);
    }

    status = mInputManager->registerInputChannel(inputChannel);
    if (! status) {
        return OK;
    }

    {
        AutoMutex _l(mInputChannelRegistryLock);
        mInputChannelObjWeakByReceiveFd.removeItem(inputChannel->getReceivePipeFd());
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

        ssize_t index = mInputChannelObjWeakByReceiveFd.indexOfKey(
                inputChannel->getReceivePipeFd());
        if (index < 0) {
            LOGE("Input channel object '%s' is not currently registered",
                    inputChannel->getName().string());
            return INVALID_OPERATION;
        }

        inputChannelObjWeak = mInputChannelObjWeakByReceiveFd.valueAt(index);
        mInputChannelObjWeakByReceiveFd.removeItemsAt(index);
    }

    env->DeleteWeakGlobalRef(inputChannelObjWeak);

    return mInputManager->unregisterInputChannel(inputChannel);
}

jobject NativeInputManager::getInputChannelObjLocal(JNIEnv* env,
        const sp<InputChannel>& inputChannel) {
    {
        AutoMutex _l(mInputChannelRegistryLock);

        ssize_t index = mInputChannelObjWeakByReceiveFd.indexOfKey(
                inputChannel->getReceivePipeFd());
        if (index < 0) {
            return NULL;
        }

        jweak inputChannelObjWeak = mInputChannelObjWeakByReceiveFd.valueAt(index);
        return env->NewLocalRef(inputChannelObjWeak);
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
    return android_server_PowerManagerService_isScreenOn();
}

bool NativeInputManager::isScreenBright() {
    return android_server_PowerManagerService_isScreenBright();
}

void NativeInputManager::virtualKeyDownFeedback() {
#if DEBUG_INPUT_READER_POLICY
    LOGD("virtualKeyDownFeedback");
#endif

    JNIEnv* env = jniEnv();

    env->CallVoidMethod(mCallbacksObj, gCallbacksClassInfo.virtualKeyDownFeedback);
    checkAndClearExceptionFromCallback(env, "virtualKeyDownFeedback");
}

int32_t NativeInputManager::interceptKey(nsecs_t when,
        int32_t deviceId, bool down, int32_t keyCode, int32_t scanCode, uint32_t policyFlags) {
#if DEBUG_INPUT_READER_POLICY
    LOGD("interceptKey - when=%lld, deviceId=%d, down=%d, keyCode=%d, scanCode=%d, "
            "policyFlags=0x%x",
            when, deviceId, down, keyCode, scanCode, policyFlags);
#endif

    const int32_t WM_ACTION_PASS_TO_USER = 1;
    const int32_t WM_ACTION_POKE_USER_ACTIVITY = 2;
    const int32_t WM_ACTION_GO_TO_SLEEP = 4;

    bool isScreenOn = this->isScreenOn();
    bool isScreenBright = this->isScreenBright();

    jint wmActions = 0;
    if (isPolicyKey(keyCode, isScreenOn)) {
        JNIEnv* env = jniEnv();

        wmActions = env->CallIntMethod(mCallbacksObj,
                gCallbacksClassInfo.interceptKeyBeforeQueueing,
                when, keyCode, down, policyFlags, isScreenOn);
        if (checkAndClearExceptionFromCallback(env, "interceptKeyBeforeQueueing")) {
            wmActions = 0;
        }
    } else {
        wmActions = WM_ACTION_PASS_TO_USER;
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
        android_server_PowerManagerService_goToSleep(when);
    }

    if (wmActions & WM_ACTION_POKE_USER_ACTIVITY) {
        pokeUserActivity(when, POWER_MANAGER_BUTTON_EVENT);
    }

    if (wmActions & WM_ACTION_PASS_TO_USER) {
        actions |= InputReaderPolicyInterface::ACTION_DISPATCH;

        if (down && isAppSwitchKey(keyCode)) {
            JNIEnv* env = jniEnv();

            env->CallVoidMethod(mCallbacksObj, gCallbacksClassInfo.notifyAppSwitchComing);
            checkAndClearExceptionFromCallback(env, "notifyAppSwitchComing");

            actions |= InputReaderPolicyInterface::ACTION_APP_SWITCH_COMING;
        }
    }

    return actions;
}

int32_t NativeInputManager::interceptTouch(nsecs_t when) {
#if DEBUG_INPUT_READER_POLICY
    LOGD("interceptTouch - when=%lld", when);
#endif

    int32_t actions = InputReaderPolicyInterface::ACTION_NONE;
    if (isScreenOn()) {
        // Only dispatch touch events when the device is awake.
        // Do not wake the device.
        actions |= InputReaderPolicyInterface::ACTION_DISPATCH;

        if (! isScreenBright()) {
            // Brighten the screen if dimmed.
            actions |= InputReaderPolicyInterface::ACTION_BRIGHT_HERE;
        }
    }

    return actions;
}

int32_t NativeInputManager::interceptTrackball(nsecs_t when,
        bool buttonChanged, bool buttonDown, bool rolled) {
#if DEBUG_INPUT_READER_POLICY
    LOGD("interceptTrackball - when=%lld, buttonChanged=%d, buttonDown=%d, rolled=%d",
            when, buttonChanged, buttonDown, rolled);
#endif

    int32_t actions = InputReaderPolicyInterface::ACTION_NONE;
    if (isScreenOn()) {
        // Only dispatch trackball events when the device is awake.
        // Do not wake the device.
        actions |= InputReaderPolicyInterface::ACTION_DISPATCH;

        if (! isScreenBright()) {
            // Brighten the screen if dimmed.
            actions |= InputReaderPolicyInterface::ACTION_BRIGHT_HERE;
        }
    }

    return actions;
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
        checkAndClearExceptionFromCallback(env, "notifyLidSwitchChanged");
        break;
    }

    return InputReaderPolicyInterface::ACTION_NONE;
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

void NativeInputManager::getVirtualKeyDefinitions(const String8& deviceName,
        Vector<InputReaderPolicyInterface::VirtualKeyDefinition>& outVirtualKeyDefinitions) {
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

void NativeInputManager::getExcludedDeviceNames(Vector<String8>& outExcludedDeviceNames) {
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

void NativeInputManager::notifyConfigurationChanged(nsecs_t when) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("notifyConfigurationChanged - when=%lld", when);
#endif

    JNIEnv* env = jniEnv();

    InputConfiguration config;
    mInputManager->getInputConfiguration(& config);

    env->CallVoidMethod(mCallbacksObj, gCallbacksClassInfo.notifyConfigurationChanged,
            when, config.touchScreen, config.keyboard, config.navigation);
    checkAndClearExceptionFromCallback(env, "notifyConfigurationChanged");
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

bool NativeInputManager::notifyInputChannelANR(const sp<InputChannel>& inputChannel,
        nsecs_t& outNewTimeout) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("notifyInputChannelANR - inputChannel='%s'",
            inputChannel->getName().string());
#endif

    JNIEnv* env = jniEnv();

    jlong newTimeout;
    jobject inputChannelObjLocal = getInputChannelObjLocal(env, inputChannel);
    if (inputChannelObjLocal) {
        newTimeout = env->CallLongMethod(mCallbacksObj,
                gCallbacksClassInfo.notifyInputChannelANR, inputChannelObjLocal);
        if (checkAndClearExceptionFromCallback(env, "notifyInputChannelANR")) {
            newTimeout = -2;
        }

        env->DeleteLocalRef(inputChannelObjLocal);
    } else {
        newTimeout = -2;
    }

    if (newTimeout == -2) {
        return false; // abort
    }

    outNewTimeout = newTimeout;
    return true; // resume
}

void NativeInputManager::notifyInputChannelRecoveredFromANR(const sp<InputChannel>& inputChannel) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("notifyInputChannelRecoveredFromANR - inputChannel='%s'",
            inputChannel->getName().string());
#endif

    JNIEnv* env = jniEnv();

    jobject inputChannelObjLocal = getInputChannelObjLocal(env, inputChannel);
    if (inputChannelObjLocal) {
        env->CallVoidMethod(mCallbacksObj, gCallbacksClassInfo.notifyInputChannelRecoveredFromANR,
                inputChannelObjLocal);
        checkAndClearExceptionFromCallback(env, "notifyInputChannelRecoveredFromANR");

        env->DeleteLocalRef(inputChannelObjLocal);
    }
}

bool NativeInputManager::notifyANR(jobject tokenObj, nsecs_t& outNewTimeout) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("notifyANR");
#endif

    JNIEnv* env = jniEnv();

    jlong newTimeout = env->CallLongMethod(mCallbacksObj,
            gCallbacksClassInfo.notifyANR, tokenObj);
    if (checkAndClearExceptionFromCallback(env, "notifyANR")) {
        newTimeout = -2;
    }

    if (newTimeout == -2) {
        return false; // abort
    }

    outNewTimeout = newTimeout;
    return true; // resume
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

void NativeInputManager::setInputWindows(JNIEnv* env, jobjectArray windowObjArray) {
#if DEBUG_FOCUS
    LOGD("setInputWindows");
#endif
    { // acquire lock
        AutoMutex _l(mDispatchLock);

        sp<InputChannel> touchedWindowChannel;
        if (mTouchedWindow) {
            touchedWindowChannel = mTouchedWindow->inputChannel;
            mTouchedWindow = NULL;
        }
        size_t numTouchedWallpapers = mTouchedWallpaperWindows.size();
        if (numTouchedWallpapers != 0) {
            for (size_t i = 0; i < numTouchedWallpapers; i++) {
                mTempTouchedWallpaperChannels.push(mTouchedWallpaperWindows[i]->inputChannel);
            }
            mTouchedWallpaperWindows.clear();
        }

        mWindows.clear();
        mFocusedWindow = NULL;
        mWallpaperWindows.clear();

        if (windowObjArray) {
            mWindowsReady = true;

            jsize length = env->GetArrayLength(windowObjArray);
            for (jsize i = 0; i < length; i++) {
                jobject inputTargetObj = env->GetObjectArrayElement(windowObjArray, i);
                if (! inputTargetObj) {
                    break; // found null element indicating end of used portion of the array
                }

                mWindows.push();
                InputWindow& window = mWindows.editTop();
                bool valid = populateWindow(env, inputTargetObj, window);
                if (! valid) {
                    mWindows.pop();
                }

                env->DeleteLocalRef(inputTargetObj);
            }

            size_t numWindows = mWindows.size();
            for (size_t i = 0; i < numWindows; i++) {
                InputWindow* window = & mWindows.editItemAt(i);
                if (window->hasFocus) {
                    mFocusedWindow = window;
                }

                if (window->layoutParamsType == TYPE_WALLPAPER) {
                    mWallpaperWindows.push(window);

                    for (size_t j = 0; j < numTouchedWallpapers; j++) {
                        if (window->inputChannel == mTempTouchedWallpaperChannels[i]) {
                            mTouchedWallpaperWindows.push(window);
                        }
                    }
                }

                if (window->inputChannel == touchedWindowChannel) {
                    mTouchedWindow = window;
                }
            }
        } else {
            mWindowsReady = false;
        }

        mTempTouchedWallpaperChannels.clear();

        mDispatchStateChanged.broadcast();

#if DEBUG_FOCUS
        dumpDispatchStateLd();
#endif
    } // release lock
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
            jboolean hasFocus = env->GetBooleanField(windowObj,
                    gInputWindowClassInfo.hasFocus);
            jboolean hasWallpaper = env->GetBooleanField(windowObj,
                    gInputWindowClassInfo.hasWallpaper);
            jboolean paused = env->GetBooleanField(windowObj,
                    gInputWindowClassInfo.paused);
            jint ownerPid = env->GetIntField(windowObj,
                    gInputWindowClassInfo.ownerPid);
            jint ownerUid = env->GetIntField(windowObj,
                    gInputWindowClassInfo.ownerUid);

            outWindow.inputChannel = inputChannel;
            outWindow.layoutParamsFlags = layoutParamsFlags;
            outWindow.layoutParamsType = layoutParamsType;
            outWindow.dispatchingTimeout = dispatchingTimeoutNanos;
            outWindow.frameLeft = frameLeft;
            outWindow.frameTop = frameTop;
            outWindow.touchableAreaLeft = touchableAreaLeft;
            outWindow.touchableAreaTop = touchableAreaTop;
            outWindow.touchableAreaRight = touchableAreaRight;
            outWindow.touchableAreaBottom = touchableAreaBottom;
            outWindow.visible = visible;
            outWindow.hasFocus = hasFocus;
            outWindow.hasWallpaper = hasWallpaper;
            outWindow.paused = paused;
            outWindow.ownerPid = ownerPid;
            outWindow.ownerUid = ownerUid;
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
#if DEBUG_FOCUS
    LOGD("setFocusedApplication");
#endif
    { // acquire lock
        AutoMutex _l(mDispatchLock);

        releaseFocusedApplicationLd(env);

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

            mFocusedApplication = & mFocusedApplicationStorage;

            if (nameObj) {
                const char* nameStr = env->GetStringUTFChars(nameObj, NULL);
                mFocusedApplication->name.setTo(nameStr);
                env->ReleaseStringUTFChars(nameObj, nameStr);
                env->DeleteLocalRef(nameObj);
            } else {
                LOGE("InputApplication.name should not be null.");
                mFocusedApplication->name.setTo("unknown");
            }

            mFocusedApplication->dispatchingTimeout = dispatchingTimeoutNanos;
            mFocusedApplication->tokenObjWeak = tokenObjWeak;
        }

        mDispatchStateChanged.broadcast();

#if DEBUG_FOCUS
        dumpDispatchStateLd();
#endif
    } // release lock
}

void NativeInputManager::releaseFocusedApplicationLd(JNIEnv* env) {
    if (mFocusedApplication) {
        env->DeleteWeakGlobalRef(mFocusedApplication->tokenObjWeak);
        mFocusedApplication = NULL;
    }
}

void NativeInputManager::setInputDispatchMode(bool enabled, bool frozen) {
#if DEBUG_FOCUS
    LOGD("setInputDispatchMode: enabled=%d, frozen=%d", enabled, frozen);
#endif

    { // acquire lock
        AutoMutex _l(mDispatchLock);

        if (mDispatchEnabled != enabled || mDispatchFrozen != frozen) {
            mDispatchEnabled = enabled;
            mDispatchFrozen = frozen;

            mDispatchStateChanged.broadcast();
        }

#if DEBUG_FOCUS
        dumpDispatchStateLd();
#endif
    } // release lock
}

void NativeInputManager::preemptInputDispatch() {
#if DEBUG_FOCUS
    LOGD("preemptInputDispatch");
#endif

    mInputManager->preemptInputDispatch();
}

int32_t NativeInputManager::waitForFocusedWindowLd(uint32_t policyFlags,
        int32_t injectorPid, int32_t injectorUid, Vector<InputTarget>& outTargets,
        InputWindow*& outFocusedWindow) {

    int32_t injectionResult = INPUT_EVENT_INJECTION_SUCCEEDED;
    bool firstIteration = true;
    ANRTimer anrTimer;
    for (;;) {
        if (firstIteration) {
            firstIteration = false;
        } else {
            if (! anrTimer.waitForDispatchStateChangeLd(this)) {
                LOGW("Dropping event because the dispatcher timed out waiting to identify "
                        "the window that should receive it.");
                injectionResult = INPUT_EVENT_INJECTION_TIMED_OUT;
                break;
            }
        }

        // If dispatch is not enabled then fail.
        if (! mDispatchEnabled) {
            LOGI("Dropping event because input dispatch is disabled.");
            injectionResult = INPUT_EVENT_INJECTION_FAILED;
            break;
        }

        // If dispatch is frozen or we don't have valid window data yet then wait.
        if (mDispatchFrozen || ! mWindowsReady) {
#if DEBUG_FOCUS
            LOGD("Waiting because dispatch is frozen or windows are not ready.");
#endif
            anrTimer.dispatchFrozenBySystem();
            continue;
        }

        // If there is no currently focused window and no focused application
        // then drop the event.
        if (! mFocusedWindow) {
            if (mFocusedApplication) {
#if DEBUG_FOCUS
                LOGD("Waiting because there is no focused window but there is a "
                        "focused application that may yet introduce a new target: '%s'.",
                        mFocusedApplication->name.string());
#endif
                continue;
            }

            LOGI("Dropping event because there is no focused window or focused application.");
            injectionResult = INPUT_EVENT_INJECTION_FAILED;
            break;
        }

        // Check permissions.
        if (! checkInjectionPermission(mFocusedWindow, injectorPid, injectorUid)) {
            injectionResult = INPUT_EVENT_INJECTION_PERMISSION_DENIED;
            break;
        }

        // If the currently focused window is paused then keep waiting.
        if (mFocusedWindow->paused) {
#if DEBUG_FOCUS
            LOGD("Waiting because focused window is paused.");
#endif
            anrTimer.dispatchPausedByApplication(mFocusedWindow);
            continue;
        }

        // Success!
        break; // done waiting, exit loop
    }

    // Output targets.
    if (injectionResult == INPUT_EVENT_INJECTION_SUCCEEDED) {
        addTarget(mFocusedWindow, InputTarget::FLAG_SYNC,
                anrTimer.getTimeSpentWaitingForApplication(), outTargets);

        outFocusedWindow = mFocusedWindow;
    } else {
        outFocusedWindow = NULL;
    }

#if DEBUG_FOCUS
    LOGD("waitForFocusedWindow finished: injectionResult=%d",
            injectionResult);
    dumpDispatchStateLd();
#endif
    return injectionResult;
}

enum InjectionPermission {
    INJECTION_PERMISSION_UNKNOWN,
    INJECTION_PERMISSION_GRANTED,
    INJECTION_PERMISSION_DENIED
};

int32_t NativeInputManager::waitForTouchedWindowLd(MotionEvent* motionEvent, uint32_t policyFlags,
        int32_t injectorPid, int32_t injectorUid, Vector<InputTarget>& outTargets,
        InputWindow*& outTouchedWindow) {
    nsecs_t startTime = now();

    // For security reasons, we defer updating the touch state until we are sure that
    // event injection will be allowed.
    //
    // FIXME In the original code, screenWasOff could never be set to true.
    //       The reason is that the POLICY_FLAG_WOKE_HERE
    //       and POLICY_FLAG_BRIGHT_HERE flags were set only when preprocessing raw
    //       EV_KEY, EV_REL and EV_ABS events.  As it happens, the touch event was
    //       actually enqueued using the policyFlags that appeared in the final EV_SYN
    //       events upon which no preprocessing took place.  So policyFlags was always 0.
    //       In the new native input dispatcher we're a bit more careful about event
    //       preprocessing so the touches we receive can actually have non-zero policyFlags.
    //       Unfortunately we obtain undesirable behavior.
    //
    //       Here's what happens:
    //
    //       When the device dims in anticipation of going to sleep, touches
    //       in windows which have FLAG_TOUCHABLE_WHEN_WAKING cause
    //       the device to brighten and reset the user activity timer.
    //       Touches on other windows (such as the launcher window)
    //       are dropped.  Then after a moment, the device goes to sleep.  Oops.
    //
    //       Also notice how screenWasOff was being initialized using POLICY_FLAG_BRIGHT_HERE
    //       instead of POLICY_FLAG_WOKE_HERE...
    //
    bool screenWasOff = false; // original policy: policyFlags & POLICY_FLAG_BRIGHT_HERE;

    int32_t action = motionEvent->getAction();

    bool firstIteration = true;
    ANRTimer anrTimer;
    int32_t injectionResult;
    InjectionPermission injectionPermission;
    for (;;) {
        if (firstIteration) {
            firstIteration = false;
        } else {
            if (! anrTimer.waitForDispatchStateChangeLd(this)) {
                LOGW("Dropping event because the dispatcher timed out waiting to identify "
                        "the window that should receive it.");
                injectionResult = INPUT_EVENT_INJECTION_TIMED_OUT;
                injectionPermission = INJECTION_PERMISSION_UNKNOWN;
                break; // timed out, exit wait loop
            }
        }

        // If dispatch is not enabled then fail.
        if (! mDispatchEnabled) {
            LOGI("Dropping event because input dispatch is disabled.");
            injectionResult = INPUT_EVENT_INJECTION_FAILED;
            injectionPermission = INJECTION_PERMISSION_UNKNOWN;
            break; // failed, exit wait loop
        }

        // If dispatch is frozen or we don't have valid window data yet then wait.
        if (mDispatchFrozen || ! mWindowsReady) {
#if DEBUG_INPUT_DISPATCHER_POLICY
            LOGD("Waiting because dispatch is frozen or windows are not ready.");
#endif
            anrTimer.dispatchFrozenBySystem();
            continue;
        }

        // Update the touch state as needed based on the properties of the touch event.
        if (action == AMOTION_EVENT_ACTION_DOWN) {
            /* Case 1: ACTION_DOWN */

            InputWindow* newTouchedWindow = NULL;
            mTempTouchedOutsideWindows.clear();

            int32_t x = int32_t(motionEvent->getX(0));
            int32_t y = int32_t(motionEvent->getY(0));
            InputWindow* topErrorWindow = NULL;

            // Traverse windows from front to back to find touched window and outside targets.
            size_t numWindows = mWindows.size();
            for (size_t i = 0; i < numWindows; i++) {
                InputWindow* window = & mWindows.editItemAt(i);
                int32_t flags = window->layoutParamsFlags;

                if (flags & FLAG_SYSTEM_ERROR) {
                    if (! topErrorWindow) {
                        topErrorWindow = window;
                    }
                }

                if (window->visible) {
                    if (! (flags & FLAG_NOT_TOUCHABLE)) {
                        bool isTouchModal = (flags &
                                (FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL)) == 0;
                        if (isTouchModal || window->touchableAreaContainsPoint(x, y)) {
                            if (! screenWasOff || flags & FLAG_TOUCHABLE_WHEN_WAKING) {
                                newTouchedWindow = window;
                            }
                            break; // found touched window, exit window loop
                        }
                    }

                    if (flags & FLAG_WATCH_OUTSIDE_TOUCH) {
                        mTempTouchedOutsideWindows.push(window);
                    }
                }
            }

            // If there is an error window but it is not taking focus (typically because
            // it is invisible) then wait for it.  Any other focused window may in
            // fact be in ANR state.
            if (topErrorWindow && newTouchedWindow != topErrorWindow) {
#if DEBUG_INPUT_DISPATCHER_POLICY
                LOGD("Waiting because system error window is pending.");
#endif
                anrTimer.dispatchFrozenBySystem();
                continue; // wait some more
            }

            // If we did not find a touched window then fail.
            if (! newTouchedWindow) {
                if (mFocusedApplication) {
#if DEBUG_FOCUS
                    LOGD("Waiting because there is no focused window but there is a "
                            "focused application that may yet introduce a new target: '%s'.",
                            mFocusedApplication->name.string());
#endif
                    continue;
                }

                LOGI("Dropping event because there is no touched window or focused application.");
                injectionResult = INPUT_EVENT_INJECTION_FAILED;
                injectionPermission = INJECTION_PERMISSION_UNKNOWN;
                break; // failed, exit wait loop
            }

            // Check permissions.
            if (! checkInjectionPermission(newTouchedWindow, injectorPid, injectorUid)) {
                injectionResult = INPUT_EVENT_INJECTION_PERMISSION_DENIED;
                injectionPermission = INJECTION_PERMISSION_DENIED;
                break; // failed, exit wait loop
            }

            // If the touched window is paused then keep waiting.
            if (newTouchedWindow->paused) {
#if DEBUG_INPUT_DISPATCHER_POLICY
                LOGD("Waiting because touched window is paused.");
#endif
                anrTimer.dispatchPausedByApplication(newTouchedWindow);
                continue; // wait some more
            }

            // Success!  Update the touch dispatch state for real.
            releaseTouchedWindowLd();

            mTouchedWindow = newTouchedWindow;

            if (newTouchedWindow->hasWallpaper) {
                mTouchedWallpaperWindows.appendVector(mWallpaperWindows);
            }

            injectionResult = INPUT_EVENT_INJECTION_SUCCEEDED;
            injectionPermission = INJECTION_PERMISSION_GRANTED;
            break; // done
        } else {
            /* Case 2: Everything but ACTION_DOWN */

            // Check permissions.
            if (! checkInjectionPermission(mTouchedWindow, injectorPid, injectorUid)) {
                injectionResult = INPUT_EVENT_INJECTION_PERMISSION_DENIED;
                injectionPermission = INJECTION_PERMISSION_DENIED;
                break; // failed, exit wait loop
            }

            // If the pointer is not currently down, then ignore the event.
            if (! mTouchDown) {
                LOGI("Dropping event because the pointer is not down.");
                injectionResult = INPUT_EVENT_INJECTION_FAILED;
                injectionPermission = INJECTION_PERMISSION_GRANTED;
                break; // failed, exit wait loop
            }

            // If there is no currently touched window then fail.
            if (! mTouchedWindow) {
                LOGW("Dropping event because there is no touched window to receive it.");
                injectionResult = INPUT_EVENT_INJECTION_FAILED;
                injectionPermission = INJECTION_PERMISSION_GRANTED;
                break; // failed, exit wait loop
            }

            // If the touched window is paused then keep waiting.
            if (mTouchedWindow->paused) {
#if DEBUG_INPUT_DISPATCHER_POLICY
                LOGD("Waiting because touched window is paused.");
#endif
                anrTimer.dispatchPausedByApplication(mTouchedWindow);
                continue; // wait some more
            }

            // Success!
            injectionResult = INPUT_EVENT_INJECTION_SUCCEEDED;
            injectionPermission = INJECTION_PERMISSION_GRANTED;
            break; // done
        }
    }

    // Output targets.
    if (injectionResult == INPUT_EVENT_INJECTION_SUCCEEDED) {
        size_t numWallpaperWindows = mTouchedWallpaperWindows.size();
        for (size_t i = 0; i < numWallpaperWindows; i++) {
            addTarget(mTouchedWallpaperWindows[i], 0, 0, outTargets);
        }

        size_t numOutsideWindows = mTempTouchedOutsideWindows.size();
        for (size_t i = 0; i < numOutsideWindows; i++) {
            addTarget(mTempTouchedOutsideWindows[i], InputTarget::FLAG_OUTSIDE, 0, outTargets);
        }

        addTarget(mTouchedWindow, InputTarget::FLAG_SYNC,
                anrTimer.getTimeSpentWaitingForApplication(), outTargets);
        outTouchedWindow = mTouchedWindow;
    } else {
        outTouchedWindow = NULL;
    }
    mTempTouchedOutsideWindows.clear();

    // Check injection permission once and for all.
    if (injectionPermission == INJECTION_PERMISSION_UNKNOWN) {
        if (checkInjectionPermission(action == AMOTION_EVENT_ACTION_DOWN ? NULL : mTouchedWindow,
                injectorPid, injectorUid)) {
            injectionPermission = INJECTION_PERMISSION_GRANTED;
        } else {
            injectionPermission = INJECTION_PERMISSION_DENIED;
        }
    }

    // Update final pieces of touch state if the injector had permission.
    if (injectionPermission == INJECTION_PERMISSION_GRANTED) {
        if (action == AMOTION_EVENT_ACTION_DOWN) {
            if (mTouchDown) {
                // This is weird.  We got a down but we thought it was already down!
                LOGW("Pointer down received while already down.");
            } else {
                mTouchDown = true;
            }

            if (injectionResult != INPUT_EVENT_INJECTION_SUCCEEDED) {
                // Since we failed to identify a target for this touch down, we may still
                // be holding on to an earlier target from a previous touch down.  Release it.
                releaseTouchedWindowLd();
            }
        } else if (action == AMOTION_EVENT_ACTION_UP) {
            mTouchDown = false;
            releaseTouchedWindowLd();
        }
    } else {
        LOGW("Not updating touch focus because injection was denied.");
    }

#if DEBUG_FOCUS
    LOGD("waitForTouchedWindow finished: injectionResult=%d",
            injectionResult);
    dumpDispatchStateLd();
#endif
    return injectionResult;
}

void NativeInputManager::releaseTouchedWindowLd() {
    mTouchedWindow = NULL;
    mTouchedWallpaperWindows.clear();
}

void NativeInputManager::addTarget(const InputWindow* window, int32_t targetFlags,
        nsecs_t timeSpentWaitingForApplication, Vector<InputTarget>& outTargets) {
    nsecs_t timeout = window->dispatchingTimeout - timeSpentWaitingForApplication;
    if (timeout < MIN_INPUT_DISPATCHING_TIMEOUT) {
        timeout = MIN_INPUT_DISPATCHING_TIMEOUT;
    }

    outTargets.push();

    InputTarget& target = outTargets.editTop();
    target.inputChannel = window->inputChannel;
    target.flags = targetFlags;
    target.timeout = timeout;
    target.xOffset = - window->frameLeft;
    target.yOffset = - window->frameTop;
}

bool NativeInputManager::checkInjectionPermission(const InputWindow* window,
        int32_t injectorPid, int32_t injectorUid) {
    if (injectorUid > 0 && (window == NULL || window->ownerUid != injectorUid)) {
        JNIEnv* env = jniEnv();
        jboolean result = env->CallBooleanMethod(mCallbacksObj,
                gCallbacksClassInfo.checkInjectEventsPermission, injectorPid, injectorUid);
        checkAndClearExceptionFromCallback(env, "checkInjectEventsPermission");

        if (! result) {
            if (window) {
                LOGW("Permission denied: injecting event from pid %d uid %d to window "
                        "with input channel %s owned by uid %d",
                        injectorPid, injectorUid, window->inputChannel->getName().string(),
                        window->ownerUid);
            } else {
                LOGW("Permission denied: injecting event from pid %d uid %d",
                        injectorPid, injectorUid);
            }
            return false;
        }
    }

    return true;
}

int32_t NativeInputManager::waitForKeyEventTargets(KeyEvent* keyEvent, uint32_t policyFlags,
        int32_t injectorPid, int32_t injectorUid, Vector<InputTarget>& outTargets) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("waitForKeyEventTargets - policyFlags=%d, injectorPid=%d, injectorUid=%d",
            policyFlags, injectorPid, injectorUid);
#endif

    int32_t windowType;
    { // acquire lock
        AutoMutex _l(mDispatchLock);

        InputWindow* focusedWindow;
        int32_t injectionResult = waitForFocusedWindowLd(policyFlags,
                injectorPid, injectorUid, outTargets, /*out*/ focusedWindow);
        if (injectionResult != INPUT_EVENT_INJECTION_SUCCEEDED) {
            return injectionResult;
        }

        windowType = focusedWindow->layoutParamsType;
    } // release lock

    if (isPolicyKey(keyEvent->getKeyCode(), isScreenOn())) {
        const InputTarget& target = outTargets.top();
        bool consumed = interceptKeyBeforeDispatching(target, keyEvent, policyFlags);
        if (consumed) {
            outTargets.clear();
            return INPUT_EVENT_INJECTION_SUCCEEDED;
        }
    }

    pokeUserActivityIfNeeded(windowType, POWER_MANAGER_BUTTON_EVENT);
    return INPUT_EVENT_INJECTION_SUCCEEDED;
}

int32_t NativeInputManager::waitForMotionEventTargets(MotionEvent* motionEvent,
        uint32_t policyFlags, int32_t injectorPid, int32_t injectorUid,
        Vector<InputTarget>& outTargets) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("waitForMotionEventTargets - policyFlags=%d, injectorPid=%d, injectorUid=%d",
            policyFlags, injectorPid, injectorUid);
#endif

    int32_t source = motionEvent->getSource();
    if (source & AINPUT_SOURCE_CLASS_POINTER) {
        return waitForTouchEventTargets(motionEvent, policyFlags, injectorPid, injectorUid,
                outTargets);
    } else {
        return waitForNonTouchEventTargets(motionEvent, policyFlags, injectorPid, injectorUid,
                outTargets);
    }
}

int32_t NativeInputManager::waitForNonTouchEventTargets(MotionEvent* motionEvent,
        uint32_t policyFlags, int32_t injectorPid, int32_t injectorUid,
        Vector<InputTarget>& outTargets) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("waitForNonTouchEventTargets - policyFlags=%d, injectorPid=%d, injectorUid=%d",
            policyFlags, injectorPid, injectorUid);
#endif

    int32_t windowType;
    { // acquire lock
        AutoMutex _l(mDispatchLock);

        InputWindow* focusedWindow;
        int32_t injectionResult = waitForFocusedWindowLd(policyFlags,
                injectorPid, injectorUid, outTargets, /*out*/ focusedWindow);
        if (injectionResult != INPUT_EVENT_INJECTION_SUCCEEDED) {
            return injectionResult;
        }

        windowType = focusedWindow->layoutParamsType;
    } // release lock

    pokeUserActivityIfNeeded(windowType, POWER_MANAGER_BUTTON_EVENT);
    return INPUT_EVENT_INJECTION_SUCCEEDED;
}

int32_t NativeInputManager::waitForTouchEventTargets(MotionEvent* motionEvent,
        uint32_t policyFlags, int32_t injectorPid, int32_t injectorUid,
        Vector<InputTarget>& outTargets) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    LOGD("waitForTouchEventTargets - policyFlags=%d, injectorPid=%d, injectorUid=%d",
            policyFlags, injectorPid, injectorUid);
#endif

    int32_t windowType;
    { // acquire lock
        AutoMutex _l(mDispatchLock);

        InputWindow* touchedWindow;
        int32_t injectionResult = waitForTouchedWindowLd(motionEvent, policyFlags,
                injectorPid, injectorUid, outTargets, /*out*/ touchedWindow);
        if (injectionResult != INPUT_EVENT_INJECTION_SUCCEEDED) {
            return injectionResult;
        }

        windowType = touchedWindow->layoutParamsType;
    } // release lock

    int32_t eventType;
    switch (motionEvent->getAction()) {
    case AMOTION_EVENT_ACTION_DOWN:
        eventType = POWER_MANAGER_TOUCH_EVENT;
        break;
    case AMOTION_EVENT_ACTION_UP:
        eventType = POWER_MANAGER_TOUCH_UP_EVENT;
        break;
    default:
        if (motionEvent->getEventTime() - motionEvent->getDownTime()
                >= EVENT_IGNORE_DURATION) {
            eventType = POWER_MANAGER_TOUCH_EVENT;
        } else {
            eventType = POWER_MANAGER_LONG_TOUCH_EVENT;
        }
        break;
    }
    pokeUserActivityIfNeeded(windowType, eventType);
    return INPUT_EVENT_INJECTION_SUCCEEDED;
}

bool NativeInputManager::interceptKeyBeforeDispatching(const InputTarget& target,
        const KeyEvent* keyEvent, uint32_t policyFlags) {
    JNIEnv* env = jniEnv();

    jobject inputChannelObj = getInputChannelObjLocal(env, target.inputChannel);
    if (inputChannelObj) {
        jboolean consumed = env->CallBooleanMethod(mCallbacksObj,
                gCallbacksClassInfo.interceptKeyBeforeDispatching,
                inputChannelObj, keyEvent->getAction(), keyEvent->getFlags(),
                keyEvent->getKeyCode(), keyEvent->getMetaState(),
                keyEvent->getRepeatCount(), policyFlags);
        bool error = checkAndClearExceptionFromCallback(env, "interceptKeyBeforeDispatching");

        env->DeleteLocalRef(inputChannelObj);

        return consumed && ! error;
    } else {
        LOGW("Could not apply key dispatch policy because input channel '%s' is "
                "no longer valid.", target.inputChannel->getName().string());
        return false;
    }
}

void NativeInputManager::pokeUserActivityIfNeeded(int32_t windowType, int32_t eventType) {
    if (windowType != TYPE_KEYGUARD) {
        nsecs_t eventTime = now();
        pokeUserActivity(eventTime, eventType);
    }
}

void NativeInputManager::pokeUserActivity(nsecs_t eventTime, int32_t eventType) {
    android_server_PowerManagerService_userActivity(eventTime, eventType);
}

void NativeInputManager::dumpDispatchStateLd() {
#if DEBUG_FOCUS
    LOGD("  dispatcherState: dispatchEnabled=%d, dispatchFrozen=%d, windowsReady=%d",
            mDispatchEnabled, mDispatchFrozen, mWindowsReady);
    if (mFocusedApplication) {
        LOGD("  focusedApplication: name='%s', dispatchingTimeout=%0.3fms",
                mFocusedApplication->name.string(),
                mFocusedApplication->dispatchingTimeout / 1000000.0);
    } else {
        LOGD("  focusedApplication: <null>");
    }
    LOGD("  focusedWindow: '%s'",
            mFocusedWindow != NULL ? mFocusedWindow->inputChannel->getName().string() : "<null>");
    LOGD("  touchedWindow: '%s', touchDown=%d",
            mTouchedWindow != NULL ? mTouchedWindow->inputChannel->getName().string() : "<null>",
            mTouchDown);
    for (size_t i = 0; i < mTouchedWallpaperWindows.size(); i++) {
        LOGD("  touchedWallpaperWindows[%d]: '%s'",
                i, mTouchedWallpaperWindows[i]->inputChannel->getName().string());
    }
    for (size_t i = 0; i < mWindows.size(); i++) {
        LOGD("  windows[%d]: '%s', paused=%d, hasFocus=%d, hasWallpaper=%d, visible=%d, "
                "flags=0x%08x, type=0x%08x, "
                "frame=[%d,%d], touchableArea=[%d,%d][%d,%d], "
                "ownerPid=%d, ownerUid=%d, dispatchingTimeout=%0.3fms",
                i, mWindows[i].inputChannel->getName().string(),
                mWindows[i].paused, mWindows[i].hasFocus, mWindows[i].hasWallpaper,
                mWindows[i].visible, mWindows[i].layoutParamsFlags, mWindows[i].layoutParamsType,
                mWindows[i].frameLeft, mWindows[i].frameTop,
                mWindows[i].touchableAreaLeft, mWindows[i].touchableAreaTop,
                mWindows[i].touchableAreaRight, mWindows[i].touchableAreaBottom,
                mWindows[i].ownerPid, mWindows[i].ownerUid,
                mWindows[i].dispatchingTimeout / 1000000.0);
    }
#endif
}

// ----------------------------------------------------------------------------

NativeInputManager::ANRTimer::ANRTimer() :
        mBudget(APPLICATION), mStartTime(now()), mFrozen(false), mPausedWindow(NULL) {
}

void NativeInputManager::ANRTimer::dispatchFrozenBySystem() {
    mFrozen = true;
}

void NativeInputManager::ANRTimer::dispatchPausedByApplication(InputWindow* pausedWindow) {
    mPausedWindow = pausedWindow;
}

bool NativeInputManager::ANRTimer::waitForDispatchStateChangeLd(NativeInputManager* inputManager) {
    nsecs_t currentTime = now();

    Budget newBudget;
    nsecs_t dispatchingTimeout;
    sp<InputChannel> pausedChannel = NULL;
    jobject tokenObj = NULL;
    if (mFrozen) {
        newBudget = SYSTEM;
        dispatchingTimeout = DEFAULT_INPUT_DISPATCHING_TIMEOUT;
        mFrozen = false;
    } else if (mPausedWindow) {
        newBudget = APPLICATION;
        dispatchingTimeout = mPausedWindow->dispatchingTimeout;
        pausedChannel = mPausedWindow->inputChannel;
        mPausedWindow = NULL;
    } else if (inputManager->mFocusedApplication) {
        newBudget = APPLICATION;
        dispatchingTimeout = inputManager->mFocusedApplication->dispatchingTimeout;
        tokenObj = jniEnv()->NewLocalRef(inputManager->mFocusedApplication->tokenObjWeak);
    } else {
        newBudget = APPLICATION;
        dispatchingTimeout = DEFAULT_INPUT_DISPATCHING_TIMEOUT;
    }

    if (mBudget != newBudget) {
        mBudget = newBudget;
        mStartTime = currentTime;
    }

    bool result = false;
    nsecs_t timeoutRemaining = mStartTime + dispatchingTimeout - currentTime;
    if (timeoutRemaining > 0
            && inputManager->mDispatchStateChanged.waitRelative(inputManager->mDispatchLock,
                    timeoutRemaining) == OK) {
        result = true;
    } else {
        if (pausedChannel != NULL || tokenObj != NULL) {
            bool resumed;
            nsecs_t newTimeout = 0;

            inputManager->mDispatchLock.unlock(); // release lock
            if (pausedChannel != NULL) {
                resumed = inputManager->notifyInputChannelANR(pausedChannel, /*out*/ newTimeout);
            } else {
                resumed = inputManager->notifyANR(tokenObj, /*out*/ newTimeout);
            }
            inputManager->mDispatchLock.lock(); // re-acquire lock

            if (resumed) {
                mStartTime = now() - dispatchingTimeout + newTimeout;
                result = true;
            }
        }
    }

    if (tokenObj) {
        jniEnv()->DeleteLocalRef(tokenObj);
    }

    return result;
}

nsecs_t NativeInputManager::ANRTimer::getTimeSpentWaitingForApplication() const {
    return mBudget == APPLICATION ? now() - mStartTime : 0;
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
        return AKEY_STATE_UNKNOWN;
    }

    return gNativeInputManager->getInputManager()->getScanCodeState(
            deviceId, deviceClasses, scanCode);
}

static jint android_server_InputManager_nativeGetKeyCodeState(JNIEnv* env, jclass clazz,
        jint deviceId, jint deviceClasses, jint keyCode) {
    if (checkInputManagerUnitialized(env)) {
        return AKEY_STATE_UNKNOWN;
    }

    return gNativeInputManager->getInputManager()->getKeyCodeState(
            deviceId, deviceClasses, keyCode);
}

static jint android_server_InputManager_nativeGetSwitchState(JNIEnv* env, jclass clazz,
        jint deviceId, jint deviceClasses, jint sw) {
    if (checkInputManagerUnitialized(env)) {
        return AKEY_STATE_UNKNOWN;
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
        gNativeInputManager->unregisterInputChannel(env, inputChannel);
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


    status_t status = gNativeInputManager->registerInputChannel(
            env, inputChannel, inputChannelObj);
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

    status_t status = gNativeInputManager->unregisterInputChannel(env, inputChannel);
    if (status) {
        jniThrowRuntimeException(env, "Failed to unregister input channel.  "
                "Check logs for details.");
    }
}

static jint android_server_InputManager_nativeInjectKeyEvent(JNIEnv* env, jclass clazz,
        jobject keyEventObj, jint injectorPid, jint injectorUid,
        jboolean sync, jint timeoutMillis) {
    if (checkInputManagerUnitialized(env)) {
        return INPUT_EVENT_INJECTION_FAILED;
    }

    KeyEvent keyEvent;
    android_view_KeyEvent_toNative(env, keyEventObj, & keyEvent);

    return gNativeInputManager->getInputManager()->injectInputEvent(& keyEvent,
            injectorPid, injectorUid, sync, timeoutMillis);
}

static jint android_server_InputManager_nativeInjectMotionEvent(JNIEnv* env, jclass clazz,
        jobject motionEventObj, jint injectorPid, jint injectorUid,
        jboolean sync, jint timeoutMillis) {
    if (checkInputManagerUnitialized(env)) {
        return INPUT_EVENT_INJECTION_FAILED;
    }

    MotionEvent motionEvent;
    android_view_MotionEvent_toNative(env, motionEventObj, & motionEvent);

    return gNativeInputManager->getInputManager()->injectInputEvent(& motionEvent,
            injectorPid, injectorUid, sync, timeoutMillis);
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

static void android_server_InputManager_nativePreemptInputDispatch(JNIEnv* env,
        jclass clazz) {
    if (checkInputManagerUnitialized(env)) {
        return;
    }

    gNativeInputManager->preemptInputDispatch();
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
            (void*) android_server_InputManager_nativeUnregisterInputChannel },
    { "nativeInjectKeyEvent", "(Landroid/view/KeyEvent;IIZI)I",
            (void*) android_server_InputManager_nativeInjectKeyEvent },
    { "nativeInjectMotionEvent", "(Landroid/view/MotionEvent;IIZI)I",
            (void*) android_server_InputManager_nativeInjectMotionEvent },
    { "nativeSetInputWindows", "([Lcom/android/server/InputWindow;)V",
            (void*) android_server_InputManager_nativeSetInputWindows },
    { "nativeSetFocusedApplication", "(Lcom/android/server/InputApplication;)V",
            (void*) android_server_InputManager_nativeSetFocusedApplication },
    { "nativeSetInputDispatchMode", "(ZZ)V",
            (void*) android_server_InputManager_nativeSetInputDispatchMode },
    { "nativePreemptInputDispatch", "()V",
            (void*) android_server_InputManager_nativePreemptInputDispatch }
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
            "notifyConfigurationChanged", "(JIII)V");

    GET_METHOD_ID(gCallbacksClassInfo.notifyLidSwitchChanged, gCallbacksClassInfo.clazz,
            "notifyLidSwitchChanged", "(JZ)V");

    GET_METHOD_ID(gCallbacksClassInfo.notifyInputChannelBroken, gCallbacksClassInfo.clazz,
            "notifyInputChannelBroken", "(Landroid/view/InputChannel;)V");

    GET_METHOD_ID(gCallbacksClassInfo.notifyInputChannelANR, gCallbacksClassInfo.clazz,
            "notifyInputChannelANR", "(Landroid/view/InputChannel;)J");

    GET_METHOD_ID(gCallbacksClassInfo.notifyInputChannelRecoveredFromANR, gCallbacksClassInfo.clazz,
            "notifyInputChannelRecoveredFromANR", "(Landroid/view/InputChannel;)V");

    GET_METHOD_ID(gCallbacksClassInfo.notifyANR, gCallbacksClassInfo.clazz,
            "notifyANR", "(Ljava/lang/Object;)J");

    GET_METHOD_ID(gCallbacksClassInfo.virtualKeyDownFeedback, gCallbacksClassInfo.clazz,
            "virtualKeyDownFeedback", "()V");

    GET_METHOD_ID(gCallbacksClassInfo.interceptKeyBeforeQueueing, gCallbacksClassInfo.clazz,
            "interceptKeyBeforeQueueing", "(JIZIZ)I");

    GET_METHOD_ID(gCallbacksClassInfo.interceptKeyBeforeDispatching, gCallbacksClassInfo.clazz,
            "interceptKeyBeforeDispatching", "(Landroid/view/InputChannel;IIIIII)Z");

    GET_METHOD_ID(gCallbacksClassInfo.checkInjectEventsPermission, gCallbacksClassInfo.clazz,
            "checkInjectEventsPermission", "(II)Z");

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

    // InputWindow

    FIND_CLASS(gInputWindowClassInfo.clazz, "com/android/server/InputWindow");

    GET_FIELD_ID(gInputWindowClassInfo.inputChannel, gInputWindowClassInfo.clazz,
            "inputChannel", "Landroid/view/InputChannel;");

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

    GET_FIELD_ID(gInputWindowClassInfo.hasFocus, gInputWindowClassInfo.clazz,
            "hasFocus", "Z");

    GET_FIELD_ID(gInputWindowClassInfo.hasWallpaper, gInputWindowClassInfo.clazz,
            "hasWallpaper", "Z");

    GET_FIELD_ID(gInputWindowClassInfo.paused, gInputWindowClassInfo.clazz,
            "paused", "Z");

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

    return 0;
}

} /* namespace android */
