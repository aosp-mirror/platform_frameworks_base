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
#include <atomic>
#include <cinttypes>
#include <limits.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>

#include <utils/Log.h>
#include <utils/Looper.h>
#include <utils/threads.h>

#include <input/PointerController.h>
#include <input/SpriteController.h>

#include <inputflinger/InputManager.h>

#include <android_os_MessageQueue.h>
#include <android_view_InputDevice.h>
#include <android_view_KeyEvent.h>
#include <android_view_MotionEvent.h>
#include <android_view_InputChannel.h>
#include <android_view_PointerIcon.h>
#include <android/graphics/GraphicsJNI.h>

#include <ScopedLocalRef.h>
#include <ScopedPrimitiveArray.h>
#include <ScopedUtfChars.h>

#include "com_android_server_power_PowerManagerService.h"
#include "com_android_server_input_InputApplicationHandle.h"
#include "com_android_server_input_InputWindowHandle.h"

#define INDENT "  "

namespace android {

// The exponent used to calculate the pointer speed scaling factor.
// The scaling factor is calculated as 2 ^ (speed * exponent),
// where the speed ranges from -7 to + 7 and is supplied by the user.
static const float POINTER_SPEED_EXPONENT = 1.0f / 4;

static struct {
    jmethodID notifyConfigurationChanged;
    jmethodID notifyInputDevicesChanged;
    jmethodID notifySwitch;
    jmethodID notifyInputChannelBroken;
    jmethodID notifyANR;
    jmethodID filterInputEvent;
    jmethodID interceptKeyBeforeQueueing;
    jmethodID interceptMotionBeforeQueueingNonInteractive;
    jmethodID interceptKeyBeforeDispatching;
    jmethodID dispatchUnhandledKey;
    jmethodID checkInjectEventsPermission;
    jmethodID getVirtualKeyQuietTimeMillis;
    jmethodID getExcludedDeviceNames;
    jmethodID getKeyRepeatTimeout;
    jmethodID getKeyRepeatDelay;
    jmethodID getHoverTapTimeout;
    jmethodID getHoverTapSlop;
    jmethodID getDoubleTapTimeout;
    jmethodID getLongPressTimeout;
    jmethodID getPointerLayer;
    jmethodID getPointerIcon;
    jmethodID getKeyboardLayoutOverlay;
    jmethodID getDeviceAlias;
    jmethodID getTouchCalibrationForInputDevice;
} gServiceClassInfo;

static struct {
    jclass clazz;
} gInputDeviceClassInfo;

static struct {
    jclass clazz;
} gKeyEventClassInfo;

static struct {
    jclass clazz;
} gMotionEventClassInfo;

static struct {
    jclass clazz;
    jmethodID constructor;
} gInputDeviceIdentifierInfo;

static struct {
    jclass clazz;
    jmethodID getAffineTransform;
} gTouchCalibrationClassInfo;



// --- Global functions ---

template<typename T>
inline static T min(const T& a, const T& b) {
    return a < b ? a : b;
}

template<typename T>
inline static T max(const T& a, const T& b) {
    return a > b ? a : b;
}

static inline const char* toString(bool value) {
    return value ? "true" : "false";
}

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

static void loadSystemIconAsSpriteWithPointerIcon(JNIEnv* env, jobject contextObj, int32_t style,
        PointerIcon* outPointerIcon, SpriteIcon* outSpriteIcon) {
    status_t status = android_view_PointerIcon_loadSystemIcon(env,
            contextObj, style, outPointerIcon);
    if (!status) {
        outPointerIcon->bitmap.copyTo(&outSpriteIcon->bitmap, kN32_SkColorType);
        outSpriteIcon->hotSpotX = outPointerIcon->hotSpotX;
        outSpriteIcon->hotSpotY = outPointerIcon->hotSpotY;
    }
}

static void loadSystemIconAsSprite(JNIEnv* env, jobject contextObj, int32_t style,
                                   SpriteIcon* outSpriteIcon) {
    PointerIcon pointerIcon;
    loadSystemIconAsSpriteWithPointerIcon(env, contextObj, style, &pointerIcon, outSpriteIcon);
}

enum {
    WM_ACTION_PASS_TO_USER = 1,
};


// --- NativeInputManager ---

class NativeInputManager : public virtual RefBase,
    public virtual InputReaderPolicyInterface,
    public virtual InputDispatcherPolicyInterface,
    public virtual PointerControllerPolicyInterface {
protected:
    virtual ~NativeInputManager();

public:
    NativeInputManager(jobject contextObj, jobject serviceObj, const sp<Looper>& looper);

    inline sp<InputManager> getInputManager() const { return mInputManager; }

    void dump(String8& dump);

    void setDisplayViewport(bool external, const DisplayViewport& viewport);

    status_t registerInputChannel(JNIEnv* env, const sp<InputChannel>& inputChannel,
            const sp<InputWindowHandle>& inputWindowHandle, bool monitor);
    status_t unregisterInputChannel(JNIEnv* env, const sp<InputChannel>& inputChannel);

    void setInputWindows(JNIEnv* env, jobjectArray windowHandleObjArray);
    void setFocusedApplication(JNIEnv* env, jobject applicationHandleObj);
    void setInputDispatchMode(bool enabled, bool frozen);
    void setSystemUiVisibility(int32_t visibility);
    void setPointerSpeed(int32_t speed);
    void setShowTouches(bool enabled);
    void setInteractive(bool interactive);
    void reloadCalibration();
    void setPointerIconType(int32_t iconId);
    void reloadPointerIcons();
    void setCustomPointerIcon(const SpriteIcon& icon);

    /* --- InputReaderPolicyInterface implementation --- */

    virtual void getReaderConfiguration(InputReaderConfiguration* outConfig);
    virtual sp<PointerControllerInterface> obtainPointerController(int32_t deviceId);
    virtual void notifyInputDevicesChanged(const Vector<InputDeviceInfo>& inputDevices);
    virtual sp<KeyCharacterMap> getKeyboardLayoutOverlay(const InputDeviceIdentifier& identifier);
    virtual String8 getDeviceAlias(const InputDeviceIdentifier& identifier);
    virtual TouchAffineTransformation getTouchAffineTransformation(JNIEnv *env,
            jfloatArray matrixArr);
    virtual TouchAffineTransformation getTouchAffineTransformation(
            const String8& inputDeviceDescriptor, int32_t surfaceRotation);

    /* --- InputDispatcherPolicyInterface implementation --- */

    virtual void notifySwitch(nsecs_t when, uint32_t switchValues, uint32_t switchMask,
            uint32_t policyFlags);
    virtual void notifyConfigurationChanged(nsecs_t when);
    virtual nsecs_t notifyANR(const sp<InputApplicationHandle>& inputApplicationHandle,
            const sp<InputWindowHandle>& inputWindowHandle,
            const String8& reason);
    virtual void notifyInputChannelBroken(const sp<InputWindowHandle>& inputWindowHandle);
    virtual bool filterInputEvent(const InputEvent* inputEvent, uint32_t policyFlags);
    virtual void getDispatcherConfiguration(InputDispatcherConfiguration* outConfig);
    virtual void interceptKeyBeforeQueueing(const KeyEvent* keyEvent, uint32_t& policyFlags);
    virtual void interceptMotionBeforeQueueing(nsecs_t when, uint32_t& policyFlags);
    virtual nsecs_t interceptKeyBeforeDispatching(
            const sp<InputWindowHandle>& inputWindowHandle,
            const KeyEvent* keyEvent, uint32_t policyFlags);
    virtual bool dispatchUnhandledKey(const sp<InputWindowHandle>& inputWindowHandle,
            const KeyEvent* keyEvent, uint32_t policyFlags, KeyEvent* outFallbackKeyEvent);
    virtual void pokeUserActivity(nsecs_t eventTime, int32_t eventType);
    virtual bool checkInjectEventsPermissionNonReentrant(
            int32_t injectorPid, int32_t injectorUid);

    /* --- PointerControllerPolicyInterface implementation --- */

    virtual void loadPointerIcon(SpriteIcon* icon);
    virtual void loadPointerResources(PointerResources* outResources);
    virtual void loadAdditionalMouseResources(std::map<int32_t, SpriteIcon>* outResources,
            std::map<int32_t, PointerAnimation>* outAnimationResources);
    virtual int32_t getDefaultPointerIconId();
    virtual int32_t getCustomPointerIconId();

private:
    sp<InputManager> mInputManager;

    jobject mContextObj;
    jobject mServiceObj;
    sp<Looper> mLooper;

    Mutex mLock;
    struct Locked {
        // Display size information.
        DisplayViewport internalViewport;
        DisplayViewport externalViewport;

        // System UI visibility.
        int32_t systemUiVisibility;

        // Pointer speed.
        int32_t pointerSpeed;

        // True if pointer gestures are enabled.
        bool pointerGesturesEnabled;

        // Show touches feature enable/disable.
        bool showTouches;

        // Sprite controller singleton, created on first use.
        sp<SpriteController> spriteController;

        // Pointer controller singleton, created and destroyed as needed.
        wp<PointerController> pointerController;
    } mLocked;

    std::atomic<bool> mInteractive;

    void updateInactivityTimeoutLocked(const sp<PointerController>& controller);
    void handleInterceptActions(jint wmActions, nsecs_t when, uint32_t& policyFlags);
    void ensureSpriteControllerLocked();

    static bool checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName);

    static inline JNIEnv* jniEnv() {
        return AndroidRuntime::getJNIEnv();
    }
};



NativeInputManager::NativeInputManager(jobject contextObj,
        jobject serviceObj, const sp<Looper>& looper) :
        mLooper(looper), mInteractive(true) {
    JNIEnv* env = jniEnv();

    mContextObj = env->NewGlobalRef(contextObj);
    mServiceObj = env->NewGlobalRef(serviceObj);

    {
        AutoMutex _l(mLock);
        mLocked.systemUiVisibility = ASYSTEM_UI_VISIBILITY_STATUS_BAR_VISIBLE;
        mLocked.pointerSpeed = 0;
        mLocked.pointerGesturesEnabled = true;
        mLocked.showTouches = false;
    }
    mInteractive = true;

    sp<EventHub> eventHub = new EventHub();
    mInputManager = new InputManager(eventHub, this, this);
}

NativeInputManager::~NativeInputManager() {
    JNIEnv* env = jniEnv();

    env->DeleteGlobalRef(mContextObj);
    env->DeleteGlobalRef(mServiceObj);
}

void NativeInputManager::dump(String8& dump) {
    dump.append("Input Manager State:\n");
    {
        dump.appendFormat(INDENT "Interactive: %s\n", toString(mInteractive.load()));
    }
    {
        AutoMutex _l(mLock);
        dump.appendFormat(INDENT "System UI Visibility: 0x%0" PRIx32 "\n",
                mLocked.systemUiVisibility);
        dump.appendFormat(INDENT "Pointer Speed: %" PRId32 "\n", mLocked.pointerSpeed);
        dump.appendFormat(INDENT "Pointer Gestures Enabled: %s\n",
                toString(mLocked.pointerGesturesEnabled));
        dump.appendFormat(INDENT "Show Touches: %s\n", toString(mLocked.showTouches));
    }
    dump.append("\n");

    mInputManager->getReader()->dump(dump);
    dump.append("\n");

    mInputManager->getDispatcher()->dump(dump);
    dump.append("\n");
}

bool NativeInputManager::checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        ALOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
        return true;
    }
    return false;
}

void NativeInputManager::setDisplayViewport(bool external, const DisplayViewport& viewport) {
    bool changed = false;
    {
        AutoMutex _l(mLock);

        DisplayViewport& v = external ? mLocked.externalViewport : mLocked.internalViewport;
        if (v != viewport) {
            changed = true;
            v = viewport;

            if (!external) {
                sp<PointerController> controller = mLocked.pointerController.promote();
                if (controller != NULL) {
                    controller->setDisplayViewport(
                            viewport.logicalRight - viewport.logicalLeft,
                            viewport.logicalBottom - viewport.logicalTop,
                            viewport.orientation);
                }
            }
        }
    }

    if (changed) {
        mInputManager->getReader()->requestRefreshConfiguration(
                InputReaderConfiguration::CHANGE_DISPLAY_INFO);
    }
}

status_t NativeInputManager::registerInputChannel(JNIEnv* /* env */,
        const sp<InputChannel>& inputChannel,
        const sp<InputWindowHandle>& inputWindowHandle, bool monitor) {
    return mInputManager->getDispatcher()->registerInputChannel(
            inputChannel, inputWindowHandle, monitor);
}

status_t NativeInputManager::unregisterInputChannel(JNIEnv* /* env */,
        const sp<InputChannel>& inputChannel) {
    return mInputManager->getDispatcher()->unregisterInputChannel(inputChannel);
}

void NativeInputManager::getReaderConfiguration(InputReaderConfiguration* outConfig) {
    JNIEnv* env = jniEnv();

    jint virtualKeyQuietTime = env->CallIntMethod(mServiceObj,
            gServiceClassInfo.getVirtualKeyQuietTimeMillis);
    if (!checkAndClearExceptionFromCallback(env, "getVirtualKeyQuietTimeMillis")) {
        outConfig->virtualKeyQuietTime = milliseconds_to_nanoseconds(virtualKeyQuietTime);
    }

    outConfig->excludedDeviceNames.clear();
    jobjectArray excludedDeviceNames = jobjectArray(env->CallObjectMethod(mServiceObj,
            gServiceClassInfo.getExcludedDeviceNames));
    if (!checkAndClearExceptionFromCallback(env, "getExcludedDeviceNames") && excludedDeviceNames) {
        jsize length = env->GetArrayLength(excludedDeviceNames);
        for (jsize i = 0; i < length; i++) {
            jstring item = jstring(env->GetObjectArrayElement(excludedDeviceNames, i));
            const char* deviceNameChars = env->GetStringUTFChars(item, NULL);
            outConfig->excludedDeviceNames.add(String8(deviceNameChars));
            env->ReleaseStringUTFChars(item, deviceNameChars);
            env->DeleteLocalRef(item);
        }
        env->DeleteLocalRef(excludedDeviceNames);
    }

    jint hoverTapTimeout = env->CallIntMethod(mServiceObj,
            gServiceClassInfo.getHoverTapTimeout);
    if (!checkAndClearExceptionFromCallback(env, "getHoverTapTimeout")) {
        jint doubleTapTimeout = env->CallIntMethod(mServiceObj,
                gServiceClassInfo.getDoubleTapTimeout);
        if (!checkAndClearExceptionFromCallback(env, "getDoubleTapTimeout")) {
            jint longPressTimeout = env->CallIntMethod(mServiceObj,
                    gServiceClassInfo.getLongPressTimeout);
            if (!checkAndClearExceptionFromCallback(env, "getLongPressTimeout")) {
                outConfig->pointerGestureTapInterval = milliseconds_to_nanoseconds(hoverTapTimeout);

                // We must ensure that the tap-drag interval is significantly shorter than
                // the long-press timeout because the tap is held down for the entire duration
                // of the double-tap timeout.
                jint tapDragInterval = max(min(longPressTimeout - 100,
                        doubleTapTimeout), hoverTapTimeout);
                outConfig->pointerGestureTapDragInterval =
                        milliseconds_to_nanoseconds(tapDragInterval);
            }
        }
    }

    jint hoverTapSlop = env->CallIntMethod(mServiceObj,
            gServiceClassInfo.getHoverTapSlop);
    if (!checkAndClearExceptionFromCallback(env, "getHoverTapSlop")) {
        outConfig->pointerGestureTapSlop = hoverTapSlop;
    }

    { // acquire lock
        AutoMutex _l(mLock);

        outConfig->pointerVelocityControlParameters.scale = exp2f(mLocked.pointerSpeed
                * POINTER_SPEED_EXPONENT);
        outConfig->pointerGesturesEnabled = mLocked.pointerGesturesEnabled;

        outConfig->showTouches = mLocked.showTouches;

        outConfig->setDisplayInfo(false /*external*/, mLocked.internalViewport);
        outConfig->setDisplayInfo(true /*external*/, mLocked.externalViewport);
    } // release lock
}

sp<PointerControllerInterface> NativeInputManager::obtainPointerController(int32_t /* deviceId */) {
    AutoMutex _l(mLock);

    sp<PointerController> controller = mLocked.pointerController.promote();
    if (controller == NULL) {
        ensureSpriteControllerLocked();

        controller = new PointerController(this, mLooper, mLocked.spriteController);
        mLocked.pointerController = controller;

        DisplayViewport& v = mLocked.internalViewport;
        controller->setDisplayViewport(
                v.logicalRight - v.logicalLeft,
                v.logicalBottom - v.logicalTop,
                v.orientation);

        updateInactivityTimeoutLocked(controller);
    }
    return controller;
}

void NativeInputManager::ensureSpriteControllerLocked() {
    if (mLocked.spriteController == NULL) {
        JNIEnv* env = jniEnv();
        jint layer = env->CallIntMethod(mServiceObj, gServiceClassInfo.getPointerLayer);
        if (checkAndClearExceptionFromCallback(env, "getPointerLayer")) {
            layer = -1;
        }
        mLocked.spriteController = new SpriteController(mLooper, layer);
    }
}

void NativeInputManager::notifyInputDevicesChanged(const Vector<InputDeviceInfo>& inputDevices) {
    JNIEnv* env = jniEnv();

    size_t count = inputDevices.size();
    jobjectArray inputDevicesObjArray = env->NewObjectArray(
            count, gInputDeviceClassInfo.clazz, NULL);
    if (inputDevicesObjArray) {
        bool error = false;
        for (size_t i = 0; i < count; i++) {
            jobject inputDeviceObj = android_view_InputDevice_create(env, inputDevices.itemAt(i));
            if (!inputDeviceObj) {
                error = true;
                break;
            }

            env->SetObjectArrayElement(inputDevicesObjArray, i, inputDeviceObj);
            env->DeleteLocalRef(inputDeviceObj);
        }

        if (!error) {
            env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifyInputDevicesChanged,
                    inputDevicesObjArray);
        }

        env->DeleteLocalRef(inputDevicesObjArray);
    }

    checkAndClearExceptionFromCallback(env, "notifyInputDevicesChanged");
}

sp<KeyCharacterMap> NativeInputManager::getKeyboardLayoutOverlay(
        const InputDeviceIdentifier& identifier) {
    JNIEnv* env = jniEnv();

    sp<KeyCharacterMap> result;
    ScopedLocalRef<jstring> descriptor(env, env->NewStringUTF(identifier.descriptor.string()));
    ScopedLocalRef<jobject> identifierObj(env, env->NewObject(gInputDeviceIdentifierInfo.clazz,
            gInputDeviceIdentifierInfo.constructor, descriptor.get(),
            identifier.vendor, identifier.product));
    ScopedLocalRef<jobjectArray> arrayObj(env, jobjectArray(env->CallObjectMethod(mServiceObj,
                gServiceClassInfo.getKeyboardLayoutOverlay, identifierObj.get())));
    if (arrayObj.get()) {
        ScopedLocalRef<jstring> filenameObj(env,
                jstring(env->GetObjectArrayElement(arrayObj.get(), 0)));
        ScopedLocalRef<jstring> contentsObj(env,
                jstring(env->GetObjectArrayElement(arrayObj.get(), 1)));
        ScopedUtfChars filenameChars(env, filenameObj.get());
        ScopedUtfChars contentsChars(env, contentsObj.get());

        KeyCharacterMap::loadContents(String8(filenameChars.c_str()),
                String8(contentsChars.c_str()), KeyCharacterMap::FORMAT_OVERLAY, &result);
    }
    checkAndClearExceptionFromCallback(env, "getKeyboardLayoutOverlay");
    return result;
}

String8 NativeInputManager::getDeviceAlias(const InputDeviceIdentifier& identifier) {
    JNIEnv* env = jniEnv();

    ScopedLocalRef<jstring> uniqueIdObj(env, env->NewStringUTF(identifier.uniqueId.string()));
    ScopedLocalRef<jstring> aliasObj(env, jstring(env->CallObjectMethod(mServiceObj,
            gServiceClassInfo.getDeviceAlias, uniqueIdObj.get())));
    String8 result;
    if (aliasObj.get()) {
        ScopedUtfChars aliasChars(env, aliasObj.get());
        result.setTo(aliasChars.c_str());
    }
    checkAndClearExceptionFromCallback(env, "getDeviceAlias");
    return result;
}

void NativeInputManager::notifySwitch(nsecs_t when,
        uint32_t switchValues, uint32_t switchMask, uint32_t /* policyFlags */) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    ALOGD("notifySwitch - when=%lld, switchValues=0x%08x, switchMask=0x%08x, policyFlags=0x%x",
            when, switchValues, switchMask, policyFlags);
#endif

    JNIEnv* env = jniEnv();

    env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifySwitch,
            when, switchValues, switchMask);
    checkAndClearExceptionFromCallback(env, "notifySwitch");
}

void NativeInputManager::notifyConfigurationChanged(nsecs_t when) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    ALOGD("notifyConfigurationChanged - when=%lld", when);
#endif

    JNIEnv* env = jniEnv();

    env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifyConfigurationChanged, when);
    checkAndClearExceptionFromCallback(env, "notifyConfigurationChanged");
}

nsecs_t NativeInputManager::notifyANR(const sp<InputApplicationHandle>& inputApplicationHandle,
        const sp<InputWindowHandle>& inputWindowHandle, const String8& reason) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    ALOGD("notifyANR");
#endif

    JNIEnv* env = jniEnv();

    jobject inputApplicationHandleObj =
            getInputApplicationHandleObjLocalRef(env, inputApplicationHandle);
    jobject inputWindowHandleObj =
            getInputWindowHandleObjLocalRef(env, inputWindowHandle);
    jstring reasonObj = env->NewStringUTF(reason.string());

    jlong newTimeout = env->CallLongMethod(mServiceObj,
                gServiceClassInfo.notifyANR, inputApplicationHandleObj, inputWindowHandleObj,
                reasonObj);
    if (checkAndClearExceptionFromCallback(env, "notifyANR")) {
        newTimeout = 0; // abort dispatch
    } else {
        assert(newTimeout >= 0);
    }

    env->DeleteLocalRef(reasonObj);
    env->DeleteLocalRef(inputWindowHandleObj);
    env->DeleteLocalRef(inputApplicationHandleObj);
    return newTimeout;
}

void NativeInputManager::notifyInputChannelBroken(const sp<InputWindowHandle>& inputWindowHandle) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    ALOGD("notifyInputChannelBroken");
#endif

    JNIEnv* env = jniEnv();

    jobject inputWindowHandleObj =
            getInputWindowHandleObjLocalRef(env, inputWindowHandle);
    if (inputWindowHandleObj) {
        env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifyInputChannelBroken,
                inputWindowHandleObj);
        checkAndClearExceptionFromCallback(env, "notifyInputChannelBroken");

        env->DeleteLocalRef(inputWindowHandleObj);
    }
}

void NativeInputManager::getDispatcherConfiguration(InputDispatcherConfiguration* outConfig) {
    JNIEnv* env = jniEnv();

    jint keyRepeatTimeout = env->CallIntMethod(mServiceObj,
            gServiceClassInfo.getKeyRepeatTimeout);
    if (!checkAndClearExceptionFromCallback(env, "getKeyRepeatTimeout")) {
        outConfig->keyRepeatTimeout = milliseconds_to_nanoseconds(keyRepeatTimeout);
    }

    jint keyRepeatDelay = env->CallIntMethod(mServiceObj,
            gServiceClassInfo.getKeyRepeatDelay);
    if (!checkAndClearExceptionFromCallback(env, "getKeyRepeatDelay")) {
        outConfig->keyRepeatDelay = milliseconds_to_nanoseconds(keyRepeatDelay);
    }
}

void NativeInputManager::setInputWindows(JNIEnv* env, jobjectArray windowHandleObjArray) {
    Vector<sp<InputWindowHandle> > windowHandles;

    if (windowHandleObjArray) {
        jsize length = env->GetArrayLength(windowHandleObjArray);
        for (jsize i = 0; i < length; i++) {
            jobject windowHandleObj = env->GetObjectArrayElement(windowHandleObjArray, i);
            if (! windowHandleObj) {
                break; // found null element indicating end of used portion of the array
            }

            sp<InputWindowHandle> windowHandle =
                    android_server_InputWindowHandle_getHandle(env, windowHandleObj);
            if (windowHandle != NULL) {
                windowHandles.push(windowHandle);
            }
            env->DeleteLocalRef(windowHandleObj);
        }
    }

    mInputManager->getDispatcher()->setInputWindows(windowHandles);

    // Do this after the dispatcher has updated the window handle state.
    bool newPointerGesturesEnabled = true;
    size_t numWindows = windowHandles.size();
    for (size_t i = 0; i < numWindows; i++) {
        const sp<InputWindowHandle>& windowHandle = windowHandles.itemAt(i);
        const InputWindowInfo* windowInfo = windowHandle->getInfo();
        if (windowInfo && windowInfo->hasFocus && (windowInfo->inputFeatures
                & InputWindowInfo::INPUT_FEATURE_DISABLE_TOUCH_PAD_GESTURES)) {
            newPointerGesturesEnabled = false;
        }
    }

    uint32_t changes = 0;
    { // acquire lock
        AutoMutex _l(mLock);

        if (mLocked.pointerGesturesEnabled != newPointerGesturesEnabled) {
            mLocked.pointerGesturesEnabled = newPointerGesturesEnabled;
            changes |= InputReaderConfiguration::CHANGE_POINTER_GESTURE_ENABLEMENT;
        }
    } // release lock

    if (changes) {
        mInputManager->getReader()->requestRefreshConfiguration(changes);
    }
}

void NativeInputManager::setFocusedApplication(JNIEnv* env, jobject applicationHandleObj) {
    sp<InputApplicationHandle> applicationHandle =
            android_server_InputApplicationHandle_getHandle(env, applicationHandleObj);
    mInputManager->getDispatcher()->setFocusedApplication(applicationHandle);
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
            updateInactivityTimeoutLocked(controller);
        }
    }
}

void NativeInputManager::updateInactivityTimeoutLocked(const sp<PointerController>& controller) {
    bool lightsOut = mLocked.systemUiVisibility & ASYSTEM_UI_VISIBILITY_STATUS_BAR_HIDDEN;
    controller->setInactivityTimeout(lightsOut
            ? PointerController::INACTIVITY_TIMEOUT_SHORT
            : PointerController::INACTIVITY_TIMEOUT_NORMAL);
}

void NativeInputManager::setPointerSpeed(int32_t speed) {
    { // acquire lock
        AutoMutex _l(mLock);

        if (mLocked.pointerSpeed == speed) {
            return;
        }

        ALOGI("Setting pointer speed to %d.", speed);
        mLocked.pointerSpeed = speed;
    } // release lock

    mInputManager->getReader()->requestRefreshConfiguration(
            InputReaderConfiguration::CHANGE_POINTER_SPEED);
}

void NativeInputManager::setShowTouches(bool enabled) {
    { // acquire lock
        AutoMutex _l(mLock);

        if (mLocked.showTouches == enabled) {
            return;
        }

        ALOGI("Setting show touches feature to %s.", enabled ? "enabled" : "disabled");
        mLocked.showTouches = enabled;
    } // release lock

    mInputManager->getReader()->requestRefreshConfiguration(
            InputReaderConfiguration::CHANGE_SHOW_TOUCHES);
}

void NativeInputManager::setInteractive(bool interactive) {
    mInteractive = interactive;
}

void NativeInputManager::reloadCalibration() {
    mInputManager->getReader()->requestRefreshConfiguration(
            InputReaderConfiguration::CHANGE_TOUCH_AFFINE_TRANSFORMATION);
}

void NativeInputManager::setPointerIconType(int32_t iconId) {
    AutoMutex _l(mLock);
    sp<PointerController> controller = mLocked.pointerController.promote();
    if (controller != NULL) {
        controller->updatePointerIcon(iconId);
    }
}

void NativeInputManager::reloadPointerIcons() {
    AutoMutex _l(mLock);
    sp<PointerController> controller = mLocked.pointerController.promote();
    if (controller != NULL) {
        controller->reloadPointerResources();
    }
}

void NativeInputManager::setCustomPointerIcon(const SpriteIcon& icon) {
    AutoMutex _l(mLock);
    sp<PointerController> controller = mLocked.pointerController.promote();
    if (controller != NULL) {
        controller->setCustomPointerIcon(icon);
    }
}

TouchAffineTransformation NativeInputManager::getTouchAffineTransformation(
        JNIEnv *env, jfloatArray matrixArr) {
    ScopedFloatArrayRO matrix(env, matrixArr);
    assert(matrix.size() == 6);

    TouchAffineTransformation transform;
    transform.x_scale  = matrix[0];
    transform.x_ymix   = matrix[1];
    transform.x_offset = matrix[2];
    transform.y_xmix   = matrix[3];
    transform.y_scale  = matrix[4];
    transform.y_offset = matrix[5];

    return transform;
}

TouchAffineTransformation NativeInputManager::getTouchAffineTransformation(
        const String8& inputDeviceDescriptor, int32_t surfaceRotation) {
    JNIEnv* env = jniEnv();

    ScopedLocalRef<jstring> descriptorObj(env, env->NewStringUTF(inputDeviceDescriptor.string()));

    jobject cal = env->CallObjectMethod(mServiceObj,
            gServiceClassInfo.getTouchCalibrationForInputDevice, descriptorObj.get(),
            surfaceRotation);

    jfloatArray matrixArr = jfloatArray(env->CallObjectMethod(cal,
            gTouchCalibrationClassInfo.getAffineTransform));

    TouchAffineTransformation transform = getTouchAffineTransformation(env, matrixArr);

    env->DeleteLocalRef(matrixArr);
    env->DeleteLocalRef(cal);

    return transform;
}

bool NativeInputManager::filterInputEvent(const InputEvent* inputEvent, uint32_t policyFlags) {
    jobject inputEventObj;

    JNIEnv* env = jniEnv();
    switch (inputEvent->getType()) {
    case AINPUT_EVENT_TYPE_KEY:
        inputEventObj = android_view_KeyEvent_fromNative(env,
                static_cast<const KeyEvent*>(inputEvent));
        break;
    case AINPUT_EVENT_TYPE_MOTION:
        inputEventObj = android_view_MotionEvent_obtainAsCopy(env,
                static_cast<const MotionEvent*>(inputEvent));
        break;
    default:
        return true; // dispatch the event normally
    }

    if (!inputEventObj) {
        ALOGE("Failed to obtain input event object for filterInputEvent.");
        return true; // dispatch the event normally
    }

    // The callee is responsible for recycling the event.
    jboolean pass = env->CallBooleanMethod(mServiceObj, gServiceClassInfo.filterInputEvent,
            inputEventObj, policyFlags);
    if (checkAndClearExceptionFromCallback(env, "filterInputEvent")) {
        pass = true;
    }
    env->DeleteLocalRef(inputEventObj);
    return pass;
}

void NativeInputManager::interceptKeyBeforeQueueing(const KeyEvent* keyEvent,
        uint32_t& policyFlags) {
    // Policy:
    // - Ignore untrusted events and pass them along.
    // - Ask the window manager what to do with normal events and trusted injected events.
    // - For normal events wake and brighten the screen if currently off or dim.
    bool interactive = mInteractive.load();
    if (interactive) {
        policyFlags |= POLICY_FLAG_INTERACTIVE;
    }
    if ((policyFlags & POLICY_FLAG_TRUSTED)) {
        nsecs_t when = keyEvent->getEventTime();
        JNIEnv* env = jniEnv();
        jobject keyEventObj = android_view_KeyEvent_fromNative(env, keyEvent);
        jint wmActions;
        if (keyEventObj) {
            wmActions = env->CallIntMethod(mServiceObj,
                    gServiceClassInfo.interceptKeyBeforeQueueing,
                    keyEventObj, policyFlags);
            if (checkAndClearExceptionFromCallback(env, "interceptKeyBeforeQueueing")) {
                wmActions = 0;
            }
            android_view_KeyEvent_recycle(env, keyEventObj);
            env->DeleteLocalRef(keyEventObj);
        } else {
            ALOGE("Failed to obtain key event object for interceptKeyBeforeQueueing.");
            wmActions = 0;
        }

        handleInterceptActions(wmActions, when, /*byref*/ policyFlags);
    } else {
        if (interactive) {
            policyFlags |= POLICY_FLAG_PASS_TO_USER;
        }
    }
}

void NativeInputManager::interceptMotionBeforeQueueing(nsecs_t when, uint32_t& policyFlags) {
    // Policy:
    // - Ignore untrusted events and pass them along.
    // - No special filtering for injected events required at this time.
    // - Filter normal events based on screen state.
    // - For normal events brighten (but do not wake) the screen if currently dim.
    bool interactive = mInteractive.load();
    if (interactive) {
        policyFlags |= POLICY_FLAG_INTERACTIVE;
    }
    if ((policyFlags & POLICY_FLAG_TRUSTED) && !(policyFlags & POLICY_FLAG_INJECTED)) {
        if (policyFlags & POLICY_FLAG_INTERACTIVE) {
            policyFlags |= POLICY_FLAG_PASS_TO_USER;
        } else {
            JNIEnv* env = jniEnv();
            jint wmActions = env->CallIntMethod(mServiceObj,
                        gServiceClassInfo.interceptMotionBeforeQueueingNonInteractive,
                        when, policyFlags);
            if (checkAndClearExceptionFromCallback(env,
                    "interceptMotionBeforeQueueingNonInteractive")) {
                wmActions = 0;
            }

            handleInterceptActions(wmActions, when, /*byref*/ policyFlags);
        }
    } else {
        if (interactive) {
            policyFlags |= POLICY_FLAG_PASS_TO_USER;
        }
    }
}

void NativeInputManager::handleInterceptActions(jint wmActions, nsecs_t when,
        uint32_t& policyFlags) {
    if (wmActions & WM_ACTION_PASS_TO_USER) {
        policyFlags |= POLICY_FLAG_PASS_TO_USER;
    } else {
#if DEBUG_INPUT_DISPATCHER_POLICY
        ALOGD("handleInterceptActions: Not passing key to user.");
#endif
    }
}

nsecs_t NativeInputManager::interceptKeyBeforeDispatching(
        const sp<InputWindowHandle>& inputWindowHandle,
        const KeyEvent* keyEvent, uint32_t policyFlags) {
    // Policy:
    // - Ignore untrusted events and pass them along.
    // - Filter normal events and trusted injected events through the window manager policy to
    //   handle the HOME key and the like.
    nsecs_t result = 0;
    if (policyFlags & POLICY_FLAG_TRUSTED) {
        JNIEnv* env = jniEnv();

        // Note: inputWindowHandle may be null.
        jobject inputWindowHandleObj = getInputWindowHandleObjLocalRef(env, inputWindowHandle);
        jobject keyEventObj = android_view_KeyEvent_fromNative(env, keyEvent);
        if (keyEventObj) {
            jlong delayMillis = env->CallLongMethod(mServiceObj,
                    gServiceClassInfo.interceptKeyBeforeDispatching,
                    inputWindowHandleObj, keyEventObj, policyFlags);
            bool error = checkAndClearExceptionFromCallback(env, "interceptKeyBeforeDispatching");
            android_view_KeyEvent_recycle(env, keyEventObj);
            env->DeleteLocalRef(keyEventObj);
            if (!error) {
                if (delayMillis < 0) {
                    result = -1;
                } else if (delayMillis > 0) {
                    result = milliseconds_to_nanoseconds(delayMillis);
                }
            }
        } else {
            ALOGE("Failed to obtain key event object for interceptKeyBeforeDispatching.");
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
            jobject fallbackKeyEventObj = env->CallObjectMethod(mServiceObj,
                    gServiceClassInfo.dispatchUnhandledKey,
                    inputWindowHandleObj, keyEventObj, policyFlags);
            if (checkAndClearExceptionFromCallback(env, "dispatchUnhandledKey")) {
                fallbackKeyEventObj = NULL;
            }
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
            ALOGE("Failed to obtain key event object for dispatchUnhandledKey.");
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
    jboolean result = env->CallBooleanMethod(mServiceObj,
            gServiceClassInfo.checkInjectEventsPermission, injectorPid, injectorUid);
    if (checkAndClearExceptionFromCallback(env, "checkInjectEventsPermission")) {
        result = false;
    }
    return result;
}

void NativeInputManager::loadPointerIcon(SpriteIcon* icon) {
    JNIEnv* env = jniEnv();

    ScopedLocalRef<jobject> pointerIconObj(env, env->CallObjectMethod(
            mServiceObj, gServiceClassInfo.getPointerIcon));
    if (checkAndClearExceptionFromCallback(env, "getPointerIcon")) {
        return;
    }

    PointerIcon pointerIcon;
    status_t status = android_view_PointerIcon_load(env, pointerIconObj.get(),
                                                    mContextObj, &pointerIcon);
    if (!status && !pointerIcon.isNullIcon()) {
        *icon = SpriteIcon(pointerIcon.bitmap, pointerIcon.hotSpotX, pointerIcon.hotSpotY);
    } else {
        *icon = SpriteIcon();
    }
}

void NativeInputManager::loadPointerResources(PointerResources* outResources) {
    JNIEnv* env = jniEnv();

    loadSystemIconAsSprite(env, mContextObj, POINTER_ICON_STYLE_SPOT_HOVER,
            &outResources->spotHover);
    loadSystemIconAsSprite(env, mContextObj, POINTER_ICON_STYLE_SPOT_TOUCH,
            &outResources->spotTouch);
    loadSystemIconAsSprite(env, mContextObj, POINTER_ICON_STYLE_SPOT_ANCHOR,
            &outResources->spotAnchor);
}

void NativeInputManager::loadAdditionalMouseResources(std::map<int32_t, SpriteIcon>* outResources,
        std::map<int32_t, PointerAnimation>* outAnimationResources) {
    JNIEnv* env = jniEnv();

    for (int iconId = POINTER_ICON_STYLE_CONTEXT_MENU; iconId <= POINTER_ICON_STYLE_GRABBING;
             ++iconId) {
        PointerIcon pointerIcon;
        loadSystemIconAsSpriteWithPointerIcon(
                env, mContextObj, iconId, &pointerIcon, &((*outResources)[iconId]));
        if (!pointerIcon.bitmapFrames.empty()) {
            PointerAnimation& animationData = (*outAnimationResources)[iconId];
            size_t numFrames = pointerIcon.bitmapFrames.size() + 1;
            animationData.durationPerFrame =
                    milliseconds_to_nanoseconds(pointerIcon.durationPerFrame);
            animationData.animationFrames.reserve(numFrames);
            animationData.animationFrames.push_back(SpriteIcon(
                    pointerIcon.bitmap, pointerIcon.hotSpotX, pointerIcon.hotSpotY));
            for (size_t i = 0; i < numFrames - 1; ++i) {
              animationData.animationFrames.push_back(SpriteIcon(
                      pointerIcon.bitmapFrames[i], pointerIcon.hotSpotX, pointerIcon.hotSpotY));
            }
        }
    }
    loadSystemIconAsSprite(env, mContextObj, POINTER_ICON_STYLE_NULL,
            &((*outResources)[POINTER_ICON_STYLE_NULL]));
}

int32_t NativeInputManager::getDefaultPointerIconId() {
    return POINTER_ICON_STYLE_ARROW;
}

int32_t NativeInputManager::getCustomPointerIconId() {
    return POINTER_ICON_STYLE_CUSTOM;
}

// ----------------------------------------------------------------------------

static jlong nativeInit(JNIEnv* env, jclass /* clazz */,
        jobject serviceObj, jobject contextObj, jobject messageQueueObj) {
    sp<MessageQueue> messageQueue = android_os_MessageQueue_getMessageQueue(env, messageQueueObj);
    if (messageQueue == NULL) {
        jniThrowRuntimeException(env, "MessageQueue is not initialized.");
        return 0;
    }

    NativeInputManager* im = new NativeInputManager(contextObj, serviceObj,
            messageQueue->getLooper());
    im->incStrong(0);
    return reinterpret_cast<jlong>(im);
}

static void nativeStart(JNIEnv* env, jclass /* clazz */, jlong ptr) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    status_t result = im->getInputManager()->start();
    if (result) {
        jniThrowRuntimeException(env, "Input manager could not be started.");
    }
}

static void nativeSetDisplayViewport(JNIEnv* /* env */, jclass /* clazz */, jlong ptr,
        jboolean external, jint displayId, jint orientation,
        jint logicalLeft, jint logicalTop, jint logicalRight, jint logicalBottom,
        jint physicalLeft, jint physicalTop, jint physicalRight, jint physicalBottom,
        jint deviceWidth, jint deviceHeight) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    DisplayViewport v;
    v.displayId = displayId;
    v.orientation = orientation;
    v.logicalLeft = logicalLeft;
    v.logicalTop = logicalTop;
    v.logicalRight = logicalRight;
    v.logicalBottom = logicalBottom;
    v.physicalLeft = physicalLeft;
    v.physicalTop = physicalTop;
    v.physicalRight = physicalRight;
    v.physicalBottom = physicalBottom;
    v.deviceWidth = deviceWidth;
    v.deviceHeight = deviceHeight;
    im->setDisplayViewport(external, v);
}

static jint nativeGetScanCodeState(JNIEnv* /* env */, jclass /* clazz */,
        jlong ptr, jint deviceId, jint sourceMask, jint scanCode) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    return (jint) im->getInputManager()->getReader()->getScanCodeState(
            deviceId, uint32_t(sourceMask), scanCode);
}

static jint nativeGetKeyCodeState(JNIEnv* /* env */, jclass /* clazz */,
        jlong ptr, jint deviceId, jint sourceMask, jint keyCode) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    return (jint) im->getInputManager()->getReader()->getKeyCodeState(
            deviceId, uint32_t(sourceMask), keyCode);
}

static jint nativeGetSwitchState(JNIEnv* /* env */, jclass /* clazz */,
        jlong ptr, jint deviceId, jint sourceMask, jint sw) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    return (jint) im->getInputManager()->getReader()->getSwitchState(
            deviceId, uint32_t(sourceMask), sw);
}

static jboolean nativeHasKeys(JNIEnv* env, jclass /* clazz */,
        jlong ptr, jint deviceId, jint sourceMask, jintArray keyCodes, jbooleanArray outFlags) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    int32_t* codes = env->GetIntArrayElements(keyCodes, NULL);
    uint8_t* flags = env->GetBooleanArrayElements(outFlags, NULL);
    jsize numCodes = env->GetArrayLength(keyCodes);
    jboolean result;
    if (numCodes == env->GetArrayLength(keyCodes)) {
        if (im->getInputManager()->getReader()->hasKeys(
                deviceId, uint32_t(sourceMask), numCodes, codes, flags)) {
            result = JNI_TRUE;
        } else {
            result = JNI_FALSE;
        }
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

static void handleInputChannelDisposed(JNIEnv* env,
        jobject /* inputChannelObj */, const sp<InputChannel>& inputChannel, void* data) {
    NativeInputManager* im = static_cast<NativeInputManager*>(data);

    ALOGW("Input channel object '%s' was disposed without first being unregistered with "
            "the input manager!", inputChannel->getName().string());
    im->unregisterInputChannel(env, inputChannel);
}

static void nativeRegisterInputChannel(JNIEnv* env, jclass /* clazz */,
        jlong ptr, jobject inputChannelObj, jobject inputWindowHandleObj, jboolean monitor) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    sp<InputChannel> inputChannel = android_view_InputChannel_getInputChannel(env,
            inputChannelObj);
    if (inputChannel == NULL) {
        throwInputChannelNotInitialized(env);
        return;
    }

    sp<InputWindowHandle> inputWindowHandle =
            android_server_InputWindowHandle_getHandle(env, inputWindowHandleObj);

    status_t status = im->registerInputChannel(
            env, inputChannel, inputWindowHandle, monitor);
    if (status) {
        String8 message;
        message.appendFormat("Failed to register input channel.  status=%d", status);
        jniThrowRuntimeException(env, message.string());
        return;
    }

    if (! monitor) {
        android_view_InputChannel_setDisposeCallback(env, inputChannelObj,
                handleInputChannelDisposed, im);
    }
}

static void nativeUnregisterInputChannel(JNIEnv* env, jclass /* clazz */,
        jlong ptr, jobject inputChannelObj) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    sp<InputChannel> inputChannel = android_view_InputChannel_getInputChannel(env,
            inputChannelObj);
    if (inputChannel == NULL) {
        throwInputChannelNotInitialized(env);
        return;
    }

    android_view_InputChannel_setDisposeCallback(env, inputChannelObj, NULL, NULL);

    status_t status = im->unregisterInputChannel(env, inputChannel);
    if (status && status != BAD_VALUE) { // ignore already unregistered channel
        String8 message;
        message.appendFormat("Failed to unregister input channel.  status=%d", status);
        jniThrowRuntimeException(env, message.string());
    }
}

static void nativeSetInputFilterEnabled(JNIEnv* /* env */, jclass /* clazz */,
        jlong ptr, jboolean enabled) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->getInputManager()->getDispatcher()->setInputFilterEnabled(enabled);
}

static jint nativeInjectInputEvent(JNIEnv* env, jclass /* clazz */,
        jlong ptr, jobject inputEventObj, jint displayId, jint injectorPid, jint injectorUid,
        jint syncMode, jint timeoutMillis, jint policyFlags) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    if (env->IsInstanceOf(inputEventObj, gKeyEventClassInfo.clazz)) {
        KeyEvent keyEvent;
        status_t status = android_view_KeyEvent_toNative(env, inputEventObj, & keyEvent);
        if (status) {
            jniThrowRuntimeException(env, "Could not read contents of KeyEvent object.");
            return INPUT_EVENT_INJECTION_FAILED;
        }

        return (jint) im->getInputManager()->getDispatcher()->injectInputEvent(
                & keyEvent, displayId, injectorPid, injectorUid, syncMode, timeoutMillis,
                uint32_t(policyFlags));
    } else if (env->IsInstanceOf(inputEventObj, gMotionEventClassInfo.clazz)) {
        const MotionEvent* motionEvent = android_view_MotionEvent_getNativePtr(env, inputEventObj);
        if (!motionEvent) {
            jniThrowRuntimeException(env, "Could not read contents of MotionEvent object.");
            return INPUT_EVENT_INJECTION_FAILED;
        }

        return (jint) im->getInputManager()->getDispatcher()->injectInputEvent(
                motionEvent, displayId, injectorPid, injectorUid, syncMode, timeoutMillis,
                uint32_t(policyFlags));
    } else {
        jniThrowRuntimeException(env, "Invalid input event type.");
        return INPUT_EVENT_INJECTION_FAILED;
    }
}

static void nativeToggleCapsLock(JNIEnv* env, jclass /* clazz */,
         jlong ptr, jint deviceId) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);
    im->getInputManager()->getReader()->toggleCapsLockState(deviceId);
}

static void nativeSetInputWindows(JNIEnv* env, jclass /* clazz */,
        jlong ptr, jobjectArray windowHandleObjArray) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->setInputWindows(env, windowHandleObjArray);
}

static void nativeSetFocusedApplication(JNIEnv* env, jclass /* clazz */,
        jlong ptr, jobject applicationHandleObj) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->setFocusedApplication(env, applicationHandleObj);
}

static void nativeSetInputDispatchMode(JNIEnv* /* env */,
        jclass /* clazz */, jlong ptr, jboolean enabled, jboolean frozen) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->setInputDispatchMode(enabled, frozen);
}

static void nativeSetSystemUiVisibility(JNIEnv* /* env */,
        jclass /* clazz */, jlong ptr, jint visibility) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->setSystemUiVisibility(visibility);
}

static jboolean nativeTransferTouchFocus(JNIEnv* env,
        jclass /* clazz */, jlong ptr, jobject fromChannelObj, jobject toChannelObj) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    sp<InputChannel> fromChannel =
            android_view_InputChannel_getInputChannel(env, fromChannelObj);
    sp<InputChannel> toChannel =
            android_view_InputChannel_getInputChannel(env, toChannelObj);

    if (fromChannel == NULL || toChannel == NULL) {
        return JNI_FALSE;
    }

    if (im->getInputManager()->getDispatcher()->
            transferTouchFocus(fromChannel, toChannel)) {
        return JNI_TRUE;
    } else {
        return JNI_FALSE;
    }
}

static void nativeSetPointerSpeed(JNIEnv* /* env */,
        jclass /* clazz */, jlong ptr, jint speed) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->setPointerSpeed(speed);
}

static void nativeSetShowTouches(JNIEnv* /* env */,
        jclass /* clazz */, jlong ptr, jboolean enabled) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->setShowTouches(enabled);
}

static void nativeSetInteractive(JNIEnv* env,
        jclass clazz, jlong ptr, jboolean interactive) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->setInteractive(interactive);
}

static void nativeReloadCalibration(JNIEnv* env, jclass clazz, jlong ptr) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);
    im->reloadCalibration();
}

static void nativeVibrate(JNIEnv* env,
        jclass /* clazz */, jlong ptr, jint deviceId, jlongArray patternObj,
        jint repeat, jint token) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    size_t patternSize = env->GetArrayLength(patternObj);
    if (patternSize > MAX_VIBRATE_PATTERN_SIZE) {
        ALOGI("Skipped requested vibration because the pattern size is %zu "
                "which is more than the maximum supported size of %d.",
                patternSize, MAX_VIBRATE_PATTERN_SIZE);
        return; // limit to reasonable size
    }

    jlong* patternMillis = static_cast<jlong*>(env->GetPrimitiveArrayCritical(
            patternObj, NULL));
    nsecs_t pattern[patternSize];
    for (size_t i = 0; i < patternSize; i++) {
        pattern[i] = max(jlong(0), min(patternMillis[i],
                (jlong)(MAX_VIBRATE_PATTERN_DELAY_NSECS / 1000000LL))) * 1000000LL;
    }
    env->ReleasePrimitiveArrayCritical(patternObj, patternMillis, JNI_ABORT);

    im->getInputManager()->getReader()->vibrate(deviceId, pattern, patternSize, repeat, token);
}

static void nativeCancelVibrate(JNIEnv* /* env */,
        jclass /* clazz */, jlong ptr, jint deviceId, jint token) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->getInputManager()->getReader()->cancelVibrate(deviceId, token);
}

static void nativeReloadKeyboardLayouts(JNIEnv* /* env */,
        jclass /* clazz */, jlong ptr) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->getInputManager()->getReader()->requestRefreshConfiguration(
            InputReaderConfiguration::CHANGE_KEYBOARD_LAYOUTS);
}

static void nativeReloadDeviceAliases(JNIEnv* /* env */,
        jclass /* clazz */, jlong ptr) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->getInputManager()->getReader()->requestRefreshConfiguration(
            InputReaderConfiguration::CHANGE_DEVICE_ALIAS);
}

static jstring nativeDump(JNIEnv* env, jclass /* clazz */, jlong ptr) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    String8 dump;
    im->dump(dump);
    return env->NewStringUTF(dump.string());
}

static void nativeMonitor(JNIEnv* /* env */, jclass /* clazz */, jlong ptr) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->getInputManager()->getReader()->monitor();
    im->getInputManager()->getDispatcher()->monitor();
}

static void nativeSetPointerIconType(JNIEnv* /* env */, jclass /* clazz */, jlong ptr, jint iconId) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);
    im->setPointerIconType(iconId);
}

static void nativeReloadPointerIcons(JNIEnv* /* env */, jclass /* clazz */, jlong ptr) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);
    im->reloadPointerIcons();
}

static void nativeSetCustomPointerIcon(JNIEnv* env, jclass /* clazz */,
                                       jlong ptr, jobject iconObj) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    PointerIcon pointerIcon;
    android_view_PointerIcon_getLoadedIcon(env, iconObj, &pointerIcon);

    SpriteIcon spriteIcon;
    pointerIcon.bitmap.copyTo(&spriteIcon.bitmap, kN32_SkColorType);
    spriteIcon.hotSpotX = pointerIcon.hotSpotX;
    spriteIcon.hotSpotY = pointerIcon.hotSpotY;
    im->setCustomPointerIcon(spriteIcon);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gInputManagerMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit",
            "(Lcom/android/server/input/InputManagerService;Landroid/content/Context;Landroid/os/MessageQueue;)J",
            (void*) nativeInit },
    { "nativeStart", "(J)V",
            (void*) nativeStart },
    { "nativeSetDisplayViewport", "(JZIIIIIIIIIIII)V",
            (void*) nativeSetDisplayViewport },
    { "nativeGetScanCodeState", "(JIII)I",
            (void*) nativeGetScanCodeState },
    { "nativeGetKeyCodeState", "(JIII)I",
            (void*) nativeGetKeyCodeState },
    { "nativeGetSwitchState", "(JIII)I",
            (void*) nativeGetSwitchState },
    { "nativeHasKeys", "(JII[I[Z)Z",
            (void*) nativeHasKeys },
    { "nativeRegisterInputChannel",
            "(JLandroid/view/InputChannel;Lcom/android/server/input/InputWindowHandle;Z)V",
            (void*) nativeRegisterInputChannel },
    { "nativeUnregisterInputChannel", "(JLandroid/view/InputChannel;)V",
            (void*) nativeUnregisterInputChannel },
    { "nativeSetInputFilterEnabled", "(JZ)V",
            (void*) nativeSetInputFilterEnabled },
    { "nativeInjectInputEvent", "(JLandroid/view/InputEvent;IIIIII)I",
            (void*) nativeInjectInputEvent },
    { "nativeToggleCapsLock", "(JI)V",
            (void*) nativeToggleCapsLock },
    { "nativeSetInputWindows", "(J[Lcom/android/server/input/InputWindowHandle;)V",
            (void*) nativeSetInputWindows },
    { "nativeSetFocusedApplication", "(JLcom/android/server/input/InputApplicationHandle;)V",
            (void*) nativeSetFocusedApplication },
    { "nativeSetInputDispatchMode", "(JZZ)V",
            (void*) nativeSetInputDispatchMode },
    { "nativeSetSystemUiVisibility", "(JI)V",
            (void*) nativeSetSystemUiVisibility },
    { "nativeTransferTouchFocus", "(JLandroid/view/InputChannel;Landroid/view/InputChannel;)Z",
            (void*) nativeTransferTouchFocus },
    { "nativeSetPointerSpeed", "(JI)V",
            (void*) nativeSetPointerSpeed },
    { "nativeSetShowTouches", "(JZ)V",
            (void*) nativeSetShowTouches },
    { "nativeSetInteractive", "(JZ)V",
            (void*) nativeSetInteractive },
    { "nativeReloadCalibration", "(J)V",
            (void*) nativeReloadCalibration },
    { "nativeVibrate", "(JI[JII)V",
            (void*) nativeVibrate },
    { "nativeCancelVibrate", "(JII)V",
            (void*) nativeCancelVibrate },
    { "nativeReloadKeyboardLayouts", "(J)V",
            (void*) nativeReloadKeyboardLayouts },
    { "nativeReloadDeviceAliases", "(J)V",
            (void*) nativeReloadDeviceAliases },
    { "nativeDump", "(J)Ljava/lang/String;",
            (void*) nativeDump },
    { "nativeMonitor", "(J)V",
            (void*) nativeMonitor },
    { "nativeSetPointerIconType", "(JI)V",
            (void*) nativeSetPointerIconType },
    { "nativeReloadPointerIcons", "(J)V",
            (void*) nativeReloadPointerIcons },
    { "nativeSetCustomPointerIcon", "(JLandroid/view/PointerIcon;)V",
            (void*) nativeSetCustomPointerIcon },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method " methodName);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_server_InputManager(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/input/InputManagerService",
            gInputManagerMethods, NELEM(gInputManagerMethods));
    (void) res;  // Faked use when LOG_NDEBUG.
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    // Callbacks

    jclass clazz;
    FIND_CLASS(clazz, "com/android/server/input/InputManagerService");

    GET_METHOD_ID(gServiceClassInfo.notifyConfigurationChanged, clazz,
            "notifyConfigurationChanged", "(J)V");

    GET_METHOD_ID(gServiceClassInfo.notifyInputDevicesChanged, clazz,
            "notifyInputDevicesChanged", "([Landroid/view/InputDevice;)V");

    GET_METHOD_ID(gServiceClassInfo.notifySwitch, clazz,
            "notifySwitch", "(JII)V");

    GET_METHOD_ID(gServiceClassInfo.notifyInputChannelBroken, clazz,
            "notifyInputChannelBroken", "(Lcom/android/server/input/InputWindowHandle;)V");

    GET_METHOD_ID(gServiceClassInfo.notifyANR, clazz,
            "notifyANR",
            "(Lcom/android/server/input/InputApplicationHandle;Lcom/android/server/input/InputWindowHandle;Ljava/lang/String;)J");

    GET_METHOD_ID(gServiceClassInfo.filterInputEvent, clazz,
            "filterInputEvent", "(Landroid/view/InputEvent;I)Z");

    GET_METHOD_ID(gServiceClassInfo.interceptKeyBeforeQueueing, clazz,
            "interceptKeyBeforeQueueing", "(Landroid/view/KeyEvent;I)I");

    GET_METHOD_ID(gServiceClassInfo.interceptMotionBeforeQueueingNonInteractive, clazz,
            "interceptMotionBeforeQueueingNonInteractive", "(JI)I");

    GET_METHOD_ID(gServiceClassInfo.interceptKeyBeforeDispatching, clazz,
            "interceptKeyBeforeDispatching",
            "(Lcom/android/server/input/InputWindowHandle;Landroid/view/KeyEvent;I)J");

    GET_METHOD_ID(gServiceClassInfo.dispatchUnhandledKey, clazz,
            "dispatchUnhandledKey",
            "(Lcom/android/server/input/InputWindowHandle;Landroid/view/KeyEvent;I)Landroid/view/KeyEvent;");

    GET_METHOD_ID(gServiceClassInfo.checkInjectEventsPermission, clazz,
            "checkInjectEventsPermission", "(II)Z");

    GET_METHOD_ID(gServiceClassInfo.getVirtualKeyQuietTimeMillis, clazz,
            "getVirtualKeyQuietTimeMillis", "()I");

    GET_METHOD_ID(gServiceClassInfo.getExcludedDeviceNames, clazz,
            "getExcludedDeviceNames", "()[Ljava/lang/String;");

    GET_METHOD_ID(gServiceClassInfo.getKeyRepeatTimeout, clazz,
            "getKeyRepeatTimeout", "()I");

    GET_METHOD_ID(gServiceClassInfo.getKeyRepeatDelay, clazz,
            "getKeyRepeatDelay", "()I");

    GET_METHOD_ID(gServiceClassInfo.getHoverTapTimeout, clazz,
            "getHoverTapTimeout", "()I");

    GET_METHOD_ID(gServiceClassInfo.getHoverTapSlop, clazz,
            "getHoverTapSlop", "()I");

    GET_METHOD_ID(gServiceClassInfo.getDoubleTapTimeout, clazz,
            "getDoubleTapTimeout", "()I");

    GET_METHOD_ID(gServiceClassInfo.getLongPressTimeout, clazz,
            "getLongPressTimeout", "()I");

    GET_METHOD_ID(gServiceClassInfo.getPointerLayer, clazz,
            "getPointerLayer", "()I");

    GET_METHOD_ID(gServiceClassInfo.getPointerIcon, clazz,
            "getPointerIcon", "()Landroid/view/PointerIcon;");

    GET_METHOD_ID(gServiceClassInfo.getKeyboardLayoutOverlay, clazz,
            "getKeyboardLayoutOverlay",
            "(Landroid/hardware/input/InputDeviceIdentifier;)[Ljava/lang/String;");

    GET_METHOD_ID(gServiceClassInfo.getDeviceAlias, clazz,
            "getDeviceAlias", "(Ljava/lang/String;)Ljava/lang/String;");

    GET_METHOD_ID(gServiceClassInfo.getTouchCalibrationForInputDevice, clazz,
            "getTouchCalibrationForInputDevice",
            "(Ljava/lang/String;I)Landroid/hardware/input/TouchCalibration;");

    // InputDevice

    FIND_CLASS(gInputDeviceClassInfo.clazz, "android/view/InputDevice");
    gInputDeviceClassInfo.clazz = jclass(env->NewGlobalRef(gInputDeviceClassInfo.clazz));

    // KeyEvent

    FIND_CLASS(gKeyEventClassInfo.clazz, "android/view/KeyEvent");
    gKeyEventClassInfo.clazz = jclass(env->NewGlobalRef(gKeyEventClassInfo.clazz));

    // MotionEvent

    FIND_CLASS(gMotionEventClassInfo.clazz, "android/view/MotionEvent");
    gMotionEventClassInfo.clazz = jclass(env->NewGlobalRef(gMotionEventClassInfo.clazz));

    // InputDeviceIdentifier

    FIND_CLASS(gInputDeviceIdentifierInfo.clazz, "android/hardware/input/InputDeviceIdentifier");
    gInputDeviceIdentifierInfo.clazz = jclass(env->NewGlobalRef(gInputDeviceIdentifierInfo.clazz));
    GET_METHOD_ID(gInputDeviceIdentifierInfo.constructor, gInputDeviceIdentifierInfo.clazz,
            "<init>", "(Ljava/lang/String;II)V");

    // TouchCalibration

    FIND_CLASS(gTouchCalibrationClassInfo.clazz, "android/hardware/input/TouchCalibration");
    gTouchCalibrationClassInfo.clazz = jclass(env->NewGlobalRef(gTouchCalibrationClassInfo.clazz));

    GET_METHOD_ID(gTouchCalibrationClassInfo.getAffineTransform, gTouchCalibrationClassInfo.clazz,
            "getAffineTransform", "()[F");

    return 0;
}

} /* namespace android */
