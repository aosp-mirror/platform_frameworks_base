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

#define ATRACE_TAG ATRACE_TAG_INPUT

//#define LOG_NDEBUG 0

// Log debug messages about InputReaderPolicy
#define DEBUG_INPUT_READER_POLICY 0

// Log debug messages about InputDispatcherPolicy
#define DEBUG_INPUT_DISPATCHER_POLICY 0


#include <nativehelper/JNIHelp.h>
#include "jni.h"
#include <atomic>
#include <cinttypes>
#include <limits.h>
#include <android-base/parseint.h>
#include <android-base/stringprintf.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>

#include <utils/Log.h>
#include <utils/Looper.h>
#include <utils/threads.h>
#include <utils/Trace.h>
#include <utils/SortedVector.h>

#include <binder/IServiceManager.h>

#include <input/PointerController.h>
#include <input/SpriteController.h>
#include <ui/Region.h>

#include <inputflinger/InputManager.h>

#include <android_os_MessageQueue.h>
#include <android_view_InputDevice.h>
#include <android_view_KeyEvent.h>
#include <android_view_MotionEvent.h>
#include <android_view_InputChannel.h>
#include <android_view_PointerIcon.h>
#include <android/graphics/GraphicsJNI.h>

#include <nativehelper/ScopedLocalFrame.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>

#include "com_android_server_power_PowerManagerService.h"
#include "android_hardware_input_InputApplicationHandle.h"
#include "android_hardware_input_InputWindowHandle.h"
#include "android_hardware_display_DisplayViewport.h"
#include "android_util_Binder.h"

#include <vector>

#define INDENT "  "

using android::base::ParseUint;
using android::base::StringPrintf;

namespace android {

// The exponent used to calculate the pointer speed scaling factor.
// The scaling factor is calculated as 2 ^ (speed * exponent),
// where the speed ranges from -7 to + 7 and is supplied by the user.
static const float POINTER_SPEED_EXPONENT = 1.0f / 4;

static struct {
    jclass clazz;
    jmethodID notifyConfigurationChanged;
    jmethodID notifyInputDevicesChanged;
    jmethodID notifySwitch;
    jmethodID notifyInputChannelBroken;
    jmethodID notifyANR;
    jmethodID notifyFocusChanged;
    jmethodID filterInputEvent;
    jmethodID interceptKeyBeforeQueueing;
    jmethodID interceptMotionBeforeQueueingNonInteractive;
    jmethodID interceptKeyBeforeDispatching;
    jmethodID dispatchUnhandledKey;
    jmethodID checkInjectEventsPermission;
    jmethodID onPointerDownOutsideFocus;
    jmethodID getVirtualKeyQuietTimeMillis;
    jmethodID getExcludedDeviceNames;
    jmethodID getInputPortAssociations;
    jmethodID getKeyRepeatTimeout;
    jmethodID getKeyRepeatDelay;
    jmethodID getHoverTapTimeout;
    jmethodID getHoverTapSlop;
    jmethodID getDoubleTapTimeout;
    jmethodID getLongPressTimeout;
    jmethodID getPointerLayer;
    jmethodID getPointerIcon;
    jmethodID getPointerDisplayId;
    jmethodID getKeyboardLayoutOverlay;
    jmethodID getDeviceAlias;
    jmethodID getTouchCalibrationForInputDevice;
    jmethodID getContextForDisplay;
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

static void loadSystemIconAsSpriteWithPointerIcon(JNIEnv* env, jobject contextObj, int32_t style,
        PointerIcon* outPointerIcon, SpriteIcon* outSpriteIcon) {
    status_t status = android_view_PointerIcon_loadSystemIcon(env,
            contextObj, style, outPointerIcon);
    if (!status) {
        SkBitmap* bitmapCopy = &outSpriteIcon->bitmap;
        SkImageInfo bitmapCopyInfo = outPointerIcon->bitmap.info().makeColorType(kN32_SkColorType);
        if (bitmapCopy->tryAllocPixels(bitmapCopyInfo)) {
            outPointerIcon->bitmap.readPixels(bitmapCopy->info(), bitmapCopy->getPixels(),
                    bitmapCopy->rowBytes(), 0, 0);
        }
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

static std::string getStringElementFromJavaArray(JNIEnv* env, jobjectArray array, jsize index) {
    jstring item = jstring(env->GetObjectArrayElement(array, index));
    ScopedUtfChars chars(env, item);
    std::string result(chars.c_str());
    return result;
}

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

    void dump(std::string& dump);

    void setDisplayViewports(JNIEnv* env, jobjectArray viewportObjArray);

    status_t registerInputChannel(JNIEnv* env, const sp<InputChannel>& inputChannel,
            int32_t displayId);
    status_t registerInputMonitor(JNIEnv* env, const sp<InputChannel>& inputChannel,
            int32_t displayId, bool isGestureMonitor);
    status_t unregisterInputChannel(JNIEnv* env, const sp<InputChannel>& inputChannel);
    status_t pilferPointers(const sp<IBinder>& token);

    void setInputWindows(JNIEnv* env, jobjectArray windowHandleObjArray, int32_t displayId);
    void setFocusedApplication(JNIEnv* env, int32_t displayId, jobject applicationHandleObj);
    void setFocusedDisplay(JNIEnv* env, int32_t displayId);
    void setInputDispatchMode(bool enabled, bool frozen);
    void setSystemUiVisibility(int32_t visibility);
    void setPointerSpeed(int32_t speed);
    void setInputDeviceEnabled(uint32_t deviceId, bool enabled);
    void setShowTouches(bool enabled);
    void setInteractive(bool interactive);
    void reloadCalibration();
    void setPointerIconType(int32_t iconId);
    void reloadPointerIcons();
    void setCustomPointerIcon(const SpriteIcon& icon);
    void setPointerCapture(bool enabled);

    /* --- InputReaderPolicyInterface implementation --- */

    virtual void getReaderConfiguration(InputReaderConfiguration* outConfig);
    virtual sp<PointerControllerInterface> obtainPointerController(int32_t deviceId);
    virtual void notifyInputDevicesChanged(const std::vector<InputDeviceInfo>& inputDevices);
    virtual sp<KeyCharacterMap> getKeyboardLayoutOverlay(const InputDeviceIdentifier& identifier);
    virtual std::string getDeviceAlias(const InputDeviceIdentifier& identifier);
    virtual TouchAffineTransformation getTouchAffineTransformation(JNIEnv *env,
            jfloatArray matrixArr);
    virtual TouchAffineTransformation getTouchAffineTransformation(
            const std::string& inputDeviceDescriptor, int32_t surfaceRotation);

    /* --- InputDispatcherPolicyInterface implementation --- */

    virtual void notifySwitch(nsecs_t when, uint32_t switchValues, uint32_t switchMask,
            uint32_t policyFlags);
    virtual void notifyConfigurationChanged(nsecs_t when);
    virtual nsecs_t notifyANR(const sp<InputApplicationHandle>& inputApplicationHandle,
            const sp<IBinder>& token,
            const std::string& reason);
    virtual void notifyInputChannelBroken(const sp<IBinder>& token);
    virtual void notifyFocusChanged(const sp<IBinder>& oldToken, const sp<IBinder>& newToken);
    virtual bool filterInputEvent(const InputEvent* inputEvent, uint32_t policyFlags);
    virtual void getDispatcherConfiguration(InputDispatcherConfiguration* outConfig);
    virtual void interceptKeyBeforeQueueing(const KeyEvent* keyEvent, uint32_t& policyFlags);
    virtual void interceptMotionBeforeQueueing(const int32_t displayId, nsecs_t when,
            uint32_t& policyFlags);
    virtual nsecs_t interceptKeyBeforeDispatching(
            const sp<IBinder>& token,
            const KeyEvent* keyEvent, uint32_t policyFlags);
    virtual bool dispatchUnhandledKey(const sp<IBinder>& token,
            const KeyEvent* keyEvent, uint32_t policyFlags, KeyEvent* outFallbackKeyEvent);
    virtual void pokeUserActivity(nsecs_t eventTime, int32_t eventType, int32_t keyCode);
    virtual bool checkInjectEventsPermissionNonReentrant(
            int32_t injectorPid, int32_t injectorUid);
    virtual void onPointerDownOutsideFocus(const sp<IBinder>& touchedToken);

    /* --- PointerControllerPolicyInterface implementation --- */

    virtual void loadPointerIcon(SpriteIcon* icon, int32_t displayId);
    virtual void loadPointerResources(PointerResources* outResources, int32_t displayId);
    virtual void loadAdditionalMouseResources(std::map<int32_t, SpriteIcon>* outResources,
            std::map<int32_t, PointerAnimation>* outAnimationResources, int32_t displayId);
    virtual int32_t getDefaultPointerIconId();
    virtual int32_t getCustomPointerIconId();

private:
    sp<InputManager> mInputManager;

    jobject mServiceObj;
    sp<Looper> mLooper;

    Mutex mLock;
    struct Locked {
        // Display size information.
        std::vector<DisplayViewport> viewports;

        // System UI visibility.
        int32_t systemUiVisibility;

        // Pointer speed.
        int32_t pointerSpeed;

        // True if pointer gestures are enabled.
        bool pointerGesturesEnabled;

        // Show touches feature enable/disable.
        bool showTouches;

        // Pointer capture feature enable/disable.
        bool pointerCapture;

        // Sprite controller singleton, created on first use.
        sp<SpriteController> spriteController;

        // Pointer controller singleton, created and destroyed as needed.
        wp<PointerController> pointerController;

        // Input devices to be disabled
        SortedVector<int32_t> disabledInputDevices;

        // Associated Pointer controller display.
        int32_t pointerDisplayId;
    } mLocked GUARDED_BY(mLock);

    std::atomic<bool> mInteractive;

    void updateInactivityTimeoutLocked();
    void handleInterceptActions(jint wmActions, nsecs_t when, uint32_t& policyFlags);
    void ensureSpriteControllerLocked();
    const DisplayViewport* findDisplayViewportLocked(int32_t displayId);
    int32_t getPointerDisplayId();
    void updatePointerDisplayLocked();
    static bool checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName);

    static inline JNIEnv* jniEnv() {
        return AndroidRuntime::getJNIEnv();
    }
};



NativeInputManager::NativeInputManager(jobject contextObj,
        jobject serviceObj, const sp<Looper>& looper) :
        mLooper(looper), mInteractive(true) {
    JNIEnv* env = jniEnv();

    mServiceObj = env->NewGlobalRef(serviceObj);

    {
        AutoMutex _l(mLock);
        mLocked.systemUiVisibility = ASYSTEM_UI_VISIBILITY_STATUS_BAR_VISIBLE;
        mLocked.pointerSpeed = 0;
        mLocked.pointerGesturesEnabled = true;
        mLocked.showTouches = false;
        mLocked.pointerCapture = false;
        mLocked.pointerDisplayId = ADISPLAY_ID_DEFAULT;
    }
    mInteractive = true;

    mInputManager = new InputManager(this, this);
    defaultServiceManager()->addService(String16("inputflinger"),
            mInputManager, false);
}

NativeInputManager::~NativeInputManager() {
    JNIEnv* env = jniEnv();

    env->DeleteGlobalRef(mServiceObj);
}

void NativeInputManager::dump(std::string& dump) {
    dump += "Input Manager State:\n";
    {
        dump += StringPrintf(INDENT "Interactive: %s\n", toString(mInteractive.load()));
    }
    {
        AutoMutex _l(mLock);
        dump += StringPrintf(INDENT "System UI Visibility: 0x%0" PRIx32 "\n",
                mLocked.systemUiVisibility);
        dump += StringPrintf(INDENT "Pointer Speed: %" PRId32 "\n", mLocked.pointerSpeed);
        dump += StringPrintf(INDENT "Pointer Gestures Enabled: %s\n",
                toString(mLocked.pointerGesturesEnabled));
        dump += StringPrintf(INDENT "Show Touches: %s\n", toString(mLocked.showTouches));
        dump += StringPrintf(INDENT "Pointer Capture Enabled: %s\n", toString(mLocked.pointerCapture));
    }
    dump += "\n";

    mInputManager->getReader()->dump(dump);
    dump += "\n";

    mInputManager->getClassifier()->dump(dump);
    dump += "\n";

    mInputManager->getDispatcher()->dump(dump);
    dump += "\n";
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

const DisplayViewport* NativeInputManager::findDisplayViewportLocked(int32_t displayId)
        REQUIRES(mLock) {
    for (const DisplayViewport& v : mLocked.viewports) {
        if (v.displayId == displayId) {
            return &v;
        }
    }
    return nullptr;
}

void NativeInputManager::setDisplayViewports(JNIEnv* env, jobjectArray viewportObjArray) {
    std::vector<DisplayViewport> viewports;

    if (viewportObjArray) {
        jsize length = env->GetArrayLength(viewportObjArray);
        for (jsize i = 0; i < length; i++) {
            jobject viewportObj = env->GetObjectArrayElement(viewportObjArray, i);
            if (! viewportObj) {
                break; // found null element indicating end of used portion of the array
            }

            DisplayViewport viewport;
            android_hardware_display_DisplayViewport_toNative(env, viewportObj, &viewport);
            ALOGI("Viewport [%d] to add: %s", (int) i, viewport.uniqueId.c_str());
            viewports.push_back(viewport);

            env->DeleteLocalRef(viewportObj);
        }
    }

    // Get the preferred pointer controller displayId.
    int32_t pointerDisplayId = getPointerDisplayId();

    { // acquire lock
        AutoMutex _l(mLock);
        mLocked.viewports = viewports;
        mLocked.pointerDisplayId = pointerDisplayId;
    } // release lock

    mInputManager->getReader()->requestRefreshConfiguration(
            InputReaderConfiguration::CHANGE_DISPLAY_INFO);
}

status_t NativeInputManager::registerInputChannel(JNIEnv* /* env */,
        const sp<InputChannel>& inputChannel, int32_t displayId) {
    ATRACE_CALL();
    return mInputManager->getDispatcher()->registerInputChannel(
            inputChannel, displayId);
}

status_t NativeInputManager::registerInputMonitor(JNIEnv* /* env */,
        const sp<InputChannel>& inputChannel, int32_t displayId, bool isGestureMonitor) {
    ATRACE_CALL();
    return mInputManager->getDispatcher()->registerInputMonitor(
            inputChannel, displayId, isGestureMonitor);
}

status_t NativeInputManager::unregisterInputChannel(JNIEnv* /* env */,
        const sp<InputChannel>& inputChannel) {
    ATRACE_CALL();
    return mInputManager->getDispatcher()->unregisterInputChannel(inputChannel);
}

status_t NativeInputManager::pilferPointers(const sp<IBinder>& token) {
    ATRACE_CALL();
    return mInputManager->getDispatcher()->pilferPointers(token);
}

void NativeInputManager::getReaderConfiguration(InputReaderConfiguration* outConfig) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();

    jint virtualKeyQuietTime = env->CallIntMethod(mServiceObj,
            gServiceClassInfo.getVirtualKeyQuietTimeMillis);
    if (!checkAndClearExceptionFromCallback(env, "getVirtualKeyQuietTimeMillis")) {
        outConfig->virtualKeyQuietTime = milliseconds_to_nanoseconds(virtualKeyQuietTime);
    }

    outConfig->excludedDeviceNames.clear();
    jobjectArray excludedDeviceNames = jobjectArray(env->CallStaticObjectMethod(
            gServiceClassInfo.clazz, gServiceClassInfo.getExcludedDeviceNames));
    if (!checkAndClearExceptionFromCallback(env, "getExcludedDeviceNames") && excludedDeviceNames) {
        jsize length = env->GetArrayLength(excludedDeviceNames);
        for (jsize i = 0; i < length; i++) {
            std::string deviceName = getStringElementFromJavaArray(env, excludedDeviceNames, i);
            outConfig->excludedDeviceNames.push_back(deviceName);
        }
        env->DeleteLocalRef(excludedDeviceNames);
    }

    // Associations between input ports and display ports
    // The java method packs the information in the following manner:
    // Original data: [{'inputPort1': '1'}, {'inputPort2': '2'}]
    // Received data: ['inputPort1', '1', 'inputPort2', '2']
    // So we unpack accordingly here.
    outConfig->portAssociations.clear();
    jobjectArray portAssociations = jobjectArray(env->CallStaticObjectMethod(
            gServiceClassInfo.clazz, gServiceClassInfo.getInputPortAssociations));
    if (!checkAndClearExceptionFromCallback(env, "getInputPortAssociations") && portAssociations) {
        jsize length = env->GetArrayLength(portAssociations);
        for (jsize i = 0; i < length / 2; i++) {
            std::string inputPort = getStringElementFromJavaArray(env, portAssociations, 2 * i);
            std::string displayPortStr =
                    getStringElementFromJavaArray(env, portAssociations, 2 * i + 1);
            uint8_t displayPort;
            // Should already have been validated earlier, but do it here for safety.
            bool success = ParseUint(displayPortStr, &displayPort);
            if (!success) {
                ALOGE("Could not parse entry in port configuration file, received: %s",
                    displayPortStr.c_str());
                continue;
            }
            outConfig->portAssociations.insert({inputPort, displayPort});
        }
        env->DeleteLocalRef(portAssociations);
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

        outConfig->pointerCapture = mLocked.pointerCapture;

        outConfig->setDisplayViewports(mLocked.viewports);

        outConfig->disabledDevices = mLocked.disabledInputDevices;
    } // release lock
}

sp<PointerControllerInterface> NativeInputManager::obtainPointerController(int32_t /* deviceId */) {
    ATRACE_CALL();
    AutoMutex _l(mLock);

    sp<PointerController> controller = mLocked.pointerController.promote();
    if (controller == nullptr) {
        ensureSpriteControllerLocked();

        controller = new PointerController(this, mLooper, mLocked.spriteController);
        mLocked.pointerController = controller;
        updateInactivityTimeoutLocked();
    }

    updatePointerDisplayLocked();

    return controller;
}

int32_t NativeInputManager::getPointerDisplayId() {
    JNIEnv* env = jniEnv();
    jint pointerDisplayId = env->CallIntMethod(mServiceObj,
            gServiceClassInfo.getPointerDisplayId);
    if (checkAndClearExceptionFromCallback(env, "getPointerDisplayId")) {
        pointerDisplayId = ADISPLAY_ID_DEFAULT;
    }

    return pointerDisplayId;
}

void NativeInputManager::updatePointerDisplayLocked() REQUIRES(mLock) {
    ATRACE_CALL();

    sp<PointerController> controller = mLocked.pointerController.promote();
    if (controller != nullptr) {
        const DisplayViewport* viewport = findDisplayViewportLocked(mLocked.pointerDisplayId);
        if (viewport == nullptr) {
            ALOGW("Can't find pointer display viewport, fallback to default display.");
            viewport = findDisplayViewportLocked(ADISPLAY_ID_DEFAULT);
        }

        if (viewport != nullptr) {
            controller->setDisplayViewport(*viewport);
        }
    }
}

void NativeInputManager::ensureSpriteControllerLocked() REQUIRES(mLock) {
    if (mLocked.spriteController == nullptr) {
        JNIEnv* env = jniEnv();
        jint layer = env->CallIntMethod(mServiceObj, gServiceClassInfo.getPointerLayer);
        if (checkAndClearExceptionFromCallback(env, "getPointerLayer")) {
            layer = -1;
        }
        mLocked.spriteController = new SpriteController(mLooper, layer);
    }
}

void NativeInputManager::notifyInputDevicesChanged(const std::vector<InputDeviceInfo>& inputDevices) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();

    size_t count = inputDevices.size();
    jobjectArray inputDevicesObjArray = env->NewObjectArray(
            count, gInputDeviceClassInfo.clazz, nullptr);
    if (inputDevicesObjArray) {
        bool error = false;
        for (size_t i = 0; i < count; i++) {
            jobject inputDeviceObj = android_view_InputDevice_create(env, inputDevices[i]);
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
    ATRACE_CALL();
    JNIEnv* env = jniEnv();

    sp<KeyCharacterMap> result;
    ScopedLocalRef<jstring> descriptor(env, env->NewStringUTF(identifier.descriptor.c_str()));
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

        KeyCharacterMap::loadContents(filenameChars.c_str(),
                contentsChars.c_str(), KeyCharacterMap::FORMAT_OVERLAY, &result);
    }
    checkAndClearExceptionFromCallback(env, "getKeyboardLayoutOverlay");
    return result;
}

std::string NativeInputManager::getDeviceAlias(const InputDeviceIdentifier& identifier) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();

    ScopedLocalRef<jstring> uniqueIdObj(env, env->NewStringUTF(identifier.uniqueId.c_str()));
    ScopedLocalRef<jstring> aliasObj(env, jstring(env->CallObjectMethod(mServiceObj,
            gServiceClassInfo.getDeviceAlias, uniqueIdObj.get())));
    std::string result;
    if (aliasObj.get()) {
        ScopedUtfChars aliasChars(env, aliasObj.get());
        result = aliasChars.c_str();
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
    ATRACE_CALL();

    JNIEnv* env = jniEnv();

    env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifySwitch,
            when, switchValues, switchMask);
    checkAndClearExceptionFromCallback(env, "notifySwitch");
}

void NativeInputManager::notifyConfigurationChanged(nsecs_t when) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    ALOGD("notifyConfigurationChanged - when=%lld", when);
#endif
    ATRACE_CALL();

    JNIEnv* env = jniEnv();

    env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifyConfigurationChanged, when);
    checkAndClearExceptionFromCallback(env, "notifyConfigurationChanged");
}

nsecs_t NativeInputManager::notifyANR(const sp<InputApplicationHandle>& inputApplicationHandle,
        const sp<IBinder>& token, const std::string& reason) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    ALOGD("notifyANR");
#endif
    ATRACE_CALL();

    JNIEnv* env = jniEnv();
    ScopedLocalFrame localFrame(env);

    jobject tokenObj = javaObjectForIBinder(env, token);
    jstring reasonObj = env->NewStringUTF(reason.c_str());

    jlong newTimeout = env->CallLongMethod(mServiceObj,
                gServiceClassInfo.notifyANR, tokenObj,
                reasonObj);
    if (checkAndClearExceptionFromCallback(env, "notifyANR")) {
        newTimeout = 0; // abort dispatch
    } else {
        assert(newTimeout >= 0);
    }
    return newTimeout;
}

void NativeInputManager::notifyInputChannelBroken(const sp<IBinder>& token) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    ALOGD("notifyInputChannelBroken");
#endif
    ATRACE_CALL();

    JNIEnv* env = jniEnv();
    ScopedLocalFrame localFrame(env);

    jobject tokenObj = javaObjectForIBinder(env, token);
    if (tokenObj) {
        env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifyInputChannelBroken,
                tokenObj);
        checkAndClearExceptionFromCallback(env, "notifyInputChannelBroken");
    }
}

void NativeInputManager::notifyFocusChanged(const sp<IBinder>& oldToken,
        const sp<IBinder>& newToken) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    ALOGD("notifyFocusChanged");
#endif
    ATRACE_CALL();

    JNIEnv* env = jniEnv();
    ScopedLocalFrame localFrame(env);

    jobject oldTokenObj = javaObjectForIBinder(env, oldToken);
    jobject newTokenObj = javaObjectForIBinder(env, newToken);
    env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifyFocusChanged,
            oldTokenObj, newTokenObj);
    checkAndClearExceptionFromCallback(env, "notifyFocusChanged");
}

void NativeInputManager::getDispatcherConfiguration(InputDispatcherConfiguration* outConfig) {
    ATRACE_CALL();
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

void NativeInputManager::setInputWindows(JNIEnv* env, jobjectArray windowHandleObjArray,
         int32_t displayId) {
    std::vector<sp<InputWindowHandle> > windowHandles;

    if (windowHandleObjArray) {
        jsize length = env->GetArrayLength(windowHandleObjArray);
        for (jsize i = 0; i < length; i++) {
            jobject windowHandleObj = env->GetObjectArrayElement(windowHandleObjArray, i);
            if (! windowHandleObj) {
                break; // found null element indicating end of used portion of the array
            }

            sp<InputWindowHandle> windowHandle =
                    android_view_InputWindowHandle_getHandle(env, windowHandleObj);
            if (windowHandle != nullptr) {
                windowHandles.push_back(windowHandle);
            }
            env->DeleteLocalRef(windowHandleObj);
        }
    }

    mInputManager->getDispatcher()->setInputWindows(windowHandles, displayId);

    // Do this after the dispatcher has updated the window handle state.
    bool newPointerGesturesEnabled = true;
    size_t numWindows = windowHandles.size();
    for (size_t i = 0; i < numWindows; i++) {
        const sp<InputWindowHandle>& windowHandle = windowHandles[i];
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

void NativeInputManager::setFocusedApplication(JNIEnv* env, int32_t displayId,
        jobject applicationHandleObj) {
    sp<InputApplicationHandle> applicationHandle =
            android_view_InputApplicationHandle_getHandle(env, applicationHandleObj);
    mInputManager->getDispatcher()->setFocusedApplication(displayId, applicationHandle);
}

void NativeInputManager::setFocusedDisplay(JNIEnv* env, int32_t displayId) {
    mInputManager->getDispatcher()->setFocusedDisplay(displayId);
}

void NativeInputManager::setInputDispatchMode(bool enabled, bool frozen) {
    mInputManager->getDispatcher()->setInputDispatchMode(enabled, frozen);
}

void NativeInputManager::setSystemUiVisibility(int32_t visibility) {
    AutoMutex _l(mLock);

    if (mLocked.systemUiVisibility != visibility) {
        mLocked.systemUiVisibility = visibility;
        updateInactivityTimeoutLocked();
    }
}

void NativeInputManager::updateInactivityTimeoutLocked() REQUIRES(mLock) {
    sp<PointerController> controller = mLocked.pointerController.promote();
    if (controller == nullptr) {
        return;
    }

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

void NativeInputManager::setInputDeviceEnabled(uint32_t deviceId, bool enabled) {
    { // acquire lock
        AutoMutex _l(mLock);

        ssize_t index = mLocked.disabledInputDevices.indexOf(deviceId);
        bool currentlyEnabled = index < 0;
        if (!enabled && currentlyEnabled) {
            mLocked.disabledInputDevices.add(deviceId);
        }
        if (enabled && !currentlyEnabled) {
            mLocked.disabledInputDevices.remove(deviceId);
        }
    } // release lock

    mInputManager->getReader()->requestRefreshConfiguration(
            InputReaderConfiguration::CHANGE_ENABLED_STATE);
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

void NativeInputManager::setPointerCapture(bool enabled) {
    { // acquire lock
        AutoMutex _l(mLock);

        if (mLocked.pointerCapture == enabled) {
            return;
        }

        ALOGI("Setting pointer capture to %s.", enabled ? "enabled" : "disabled");
        mLocked.pointerCapture = enabled;
    } // release lock

    mInputManager->getReader()->requestRefreshConfiguration(
            InputReaderConfiguration::CHANGE_POINTER_CAPTURE);
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
    if (controller != nullptr) {
        controller->updatePointerIcon(iconId);
    }
}

void NativeInputManager::reloadPointerIcons() {
    AutoMutex _l(mLock);
    sp<PointerController> controller = mLocked.pointerController.promote();
    if (controller != nullptr) {
        controller->reloadPointerResources();
    }
}

void NativeInputManager::setCustomPointerIcon(const SpriteIcon& icon) {
    AutoMutex _l(mLock);
    sp<PointerController> controller = mLocked.pointerController.promote();
    if (controller != nullptr) {
        controller->setCustomPointerIcon(icon);
    }
}

TouchAffineTransformation NativeInputManager::getTouchAffineTransformation(
        JNIEnv *env, jfloatArray matrixArr) {
    ATRACE_CALL();
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
        const std::string& inputDeviceDescriptor, int32_t surfaceRotation) {
    JNIEnv* env = jniEnv();

    ScopedLocalRef<jstring> descriptorObj(env, env->NewStringUTF(inputDeviceDescriptor.c_str()));

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
    ATRACE_CALL();
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
    ATRACE_CALL();
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

void NativeInputManager::interceptMotionBeforeQueueing(const int32_t displayId, nsecs_t when,
        uint32_t& policyFlags) {
    ATRACE_CALL();
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
                        displayId, when, policyFlags);
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
        const sp<IBinder>& token,
        const KeyEvent* keyEvent, uint32_t policyFlags) {
    ATRACE_CALL();
    // Policy:
    // - Ignore untrusted events and pass them along.
    // - Filter normal events and trusted injected events through the window manager policy to
    //   handle the HOME key and the like.
    nsecs_t result = 0;
    if (policyFlags & POLICY_FLAG_TRUSTED) {
        JNIEnv* env = jniEnv();
        ScopedLocalFrame localFrame(env);

        // Token may be null
        jobject tokenObj = javaObjectForIBinder(env, token);

        jobject keyEventObj = android_view_KeyEvent_fromNative(env, keyEvent);
        if (keyEventObj) {
            jlong delayMillis = env->CallLongMethod(mServiceObj,
                    gServiceClassInfo.interceptKeyBeforeDispatching,
                    tokenObj, keyEventObj, policyFlags);
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
    }
    return result;
}

bool NativeInputManager::dispatchUnhandledKey(const sp<IBinder>& token,
        const KeyEvent* keyEvent, uint32_t policyFlags, KeyEvent* outFallbackKeyEvent) {
    ATRACE_CALL();
    // Policy:
    // - Ignore untrusted events and do not perform default handling.
    bool result = false;
    if (policyFlags & POLICY_FLAG_TRUSTED) {
        JNIEnv* env = jniEnv();
        ScopedLocalFrame localFrame(env);

        // Note: tokenObj may be null.
        jobject tokenObj = javaObjectForIBinder(env, token);
        jobject keyEventObj = android_view_KeyEvent_fromNative(env, keyEvent);
        if (keyEventObj) {
            jobject fallbackKeyEventObj = env->CallObjectMethod(mServiceObj,
                    gServiceClassInfo.dispatchUnhandledKey,
                    tokenObj, keyEventObj, policyFlags);
            if (checkAndClearExceptionFromCallback(env, "dispatchUnhandledKey")) {
                fallbackKeyEventObj = nullptr;
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
    }
    return result;
}

void NativeInputManager::pokeUserActivity(nsecs_t eventTime, int32_t eventType, int32_t keyCode) {
    ATRACE_CALL();
    android_server_PowerManagerService_userActivity(eventTime, eventType, keyCode);
}


bool NativeInputManager::checkInjectEventsPermissionNonReentrant(
        int32_t injectorPid, int32_t injectorUid) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();
    jboolean result = env->CallBooleanMethod(mServiceObj,
            gServiceClassInfo.checkInjectEventsPermission, injectorPid, injectorUid);
    if (checkAndClearExceptionFromCallback(env, "checkInjectEventsPermission")) {
        result = false;
    }
    return result;
}

void NativeInputManager::onPointerDownOutsideFocus(const sp<IBinder>& touchedToken) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();
    ScopedLocalFrame localFrame(env);

    jobject touchedTokenObj = javaObjectForIBinder(env, touchedToken);
    env->CallVoidMethod(mServiceObj, gServiceClassInfo.onPointerDownOutsideFocus, touchedTokenObj);
    checkAndClearExceptionFromCallback(env, "onPointerDownOutsideFocus");
}

void NativeInputManager::loadPointerIcon(SpriteIcon* icon, int32_t displayId) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();

    ScopedLocalRef<jobject> pointerIconObj(env, env->CallObjectMethod(
            mServiceObj, gServiceClassInfo.getPointerIcon, displayId));
    if (checkAndClearExceptionFromCallback(env, "getPointerIcon")) {
        return;
    }

    ScopedLocalRef<jobject> displayContext(env, env->CallObjectMethod(
            mServiceObj, gServiceClassInfo.getContextForDisplay, displayId));

    PointerIcon pointerIcon;
    status_t status = android_view_PointerIcon_load(env, pointerIconObj.get(),
            displayContext.get(), &pointerIcon);
    if (!status && !pointerIcon.isNullIcon()) {
        *icon = SpriteIcon(pointerIcon.bitmap, pointerIcon.hotSpotX, pointerIcon.hotSpotY);
    } else {
        *icon = SpriteIcon();
    }
}

void NativeInputManager::loadPointerResources(PointerResources* outResources, int32_t displayId) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();

    ScopedLocalRef<jobject> displayContext(env, env->CallObjectMethod(
            mServiceObj, gServiceClassInfo.getContextForDisplay, displayId));

    loadSystemIconAsSprite(env, displayContext.get(), POINTER_ICON_STYLE_SPOT_HOVER,
            &outResources->spotHover);
    loadSystemIconAsSprite(env, displayContext.get(), POINTER_ICON_STYLE_SPOT_TOUCH,
            &outResources->spotTouch);
    loadSystemIconAsSprite(env, displayContext.get(), POINTER_ICON_STYLE_SPOT_ANCHOR,
            &outResources->spotAnchor);
}

void NativeInputManager::loadAdditionalMouseResources(std::map<int32_t, SpriteIcon>* outResources,
        std::map<int32_t, PointerAnimation>* outAnimationResources, int32_t displayId) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();

    ScopedLocalRef<jobject> displayContext(env, env->CallObjectMethod(
            mServiceObj, gServiceClassInfo.getContextForDisplay, displayId));

    for (int iconId = POINTER_ICON_STYLE_CONTEXT_MENU; iconId <= POINTER_ICON_STYLE_GRABBING;
             ++iconId) {
        PointerIcon pointerIcon;
        loadSystemIconAsSpriteWithPointerIcon(
                env, displayContext.get(), iconId, &pointerIcon, &((*outResources)[iconId]));
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
    loadSystemIconAsSprite(env, displayContext.get(), POINTER_ICON_STYLE_NULL,
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
    if (messageQueue == nullptr) {
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

static void nativeSetDisplayViewports(JNIEnv* env, jclass /* clazz */, jlong ptr,
        jobjectArray viewportObjArray) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);
    im->setDisplayViewports(env, viewportObjArray);
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

    int32_t* codes = env->GetIntArrayElements(keyCodes, nullptr);
    uint8_t* flags = env->GetBooleanArrayElements(outFlags, nullptr);
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
            "the input manager!", inputChannel->getName().c_str());
    im->unregisterInputChannel(env, inputChannel);
}

static void nativeRegisterInputChannel(JNIEnv* env, jclass /* clazz */,
        jlong ptr, jobject inputChannelObj, jint displayId) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    sp<InputChannel> inputChannel = android_view_InputChannel_getInputChannel(env,
            inputChannelObj);
    if (inputChannel == nullptr) {
        throwInputChannelNotInitialized(env);
        return;
    }

    status_t status = im->registerInputChannel(env, inputChannel, displayId);

    if (status) {
        std::string message;
        message += StringPrintf("Failed to register input channel.  status=%d", status);
        jniThrowRuntimeException(env, message.c_str());
        return;
    }

    android_view_InputChannel_setDisposeCallback(env, inputChannelObj,
            handleInputChannelDisposed, im);
}

static void nativeRegisterInputMonitor(JNIEnv* env, jclass /* clazz */,
        jlong ptr, jobject inputChannelObj, jint displayId, jboolean isGestureMonitor) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    sp<InputChannel> inputChannel = android_view_InputChannel_getInputChannel(env,
            inputChannelObj);
    if (inputChannel == nullptr) {
        throwInputChannelNotInitialized(env);
        return;
    }

    if (displayId == ADISPLAY_ID_NONE) {
        std::string message = "InputChannel used as a monitor must be associated with a display";
        jniThrowRuntimeException(env, message.c_str());
        return;
    }

    status_t status = im->registerInputMonitor(env, inputChannel, displayId, isGestureMonitor);

    if (status) {
        std::string message = StringPrintf("Failed to register input channel.  status=%d", status);
        jniThrowRuntimeException(env, message.c_str());
        return;
    }
}

static void nativeUnregisterInputChannel(JNIEnv* env, jclass /* clazz */,
        jlong ptr, jobject inputChannelObj) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    sp<InputChannel> inputChannel = android_view_InputChannel_getInputChannel(env,
            inputChannelObj);
    if (inputChannel == nullptr) {
        throwInputChannelNotInitialized(env);
        return;
    }

    android_view_InputChannel_setDisposeCallback(env, inputChannelObj, nullptr, nullptr);

    status_t status = im->unregisterInputChannel(env, inputChannel);
    if (status && status != BAD_VALUE) { // ignore already unregistered channel
        std::string message;
        message += StringPrintf("Failed to unregister input channel.  status=%d", status);
        jniThrowRuntimeException(env, message.c_str());
    }
}

static void nativePilferPointers(JNIEnv* env, jclass /* clazz */, jlong ptr, jobject tokenObj) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);
    sp<IBinder> token = ibinderForJavaObject(env, tokenObj);
    im->pilferPointers(token);
}


static void nativeSetInputFilterEnabled(JNIEnv* /* env */, jclass /* clazz */,
        jlong ptr, jboolean enabled) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->getInputManager()->getDispatcher()->setInputFilterEnabled(enabled);
}

static jint nativeInjectInputEvent(JNIEnv* env, jclass /* clazz */,
        jlong ptr, jobject inputEventObj, jint injectorPid, jint injectorUid,
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
                & keyEvent, injectorPid, injectorUid, syncMode, timeoutMillis,
                uint32_t(policyFlags));
    } else if (env->IsInstanceOf(inputEventObj, gMotionEventClassInfo.clazz)) {
        const MotionEvent* motionEvent = android_view_MotionEvent_getNativePtr(env, inputEventObj);
        if (!motionEvent) {
            jniThrowRuntimeException(env, "Could not read contents of MotionEvent object.");
            return INPUT_EVENT_INJECTION_FAILED;
        }

        return (jint) im->getInputManager()->getDispatcher()->injectInputEvent(
                motionEvent, injectorPid, injectorUid, syncMode, timeoutMillis,
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
        jlong ptr, jobjectArray windowHandleObjArray, jint displayId) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->setInputWindows(env, windowHandleObjArray, displayId);
}

static void nativeSetFocusedApplication(JNIEnv* env, jclass /* clazz */,
        jlong ptr, jint displayId, jobject applicationHandleObj) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->setFocusedApplication(env, displayId, applicationHandleObj);
}

static void nativeSetFocusedDisplay(JNIEnv* env, jclass /* clazz */,
        jlong ptr, jint displayId) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->setFocusedDisplay(env, displayId);
}

static void nativeSetPointerCapture(JNIEnv* env, jclass /* clazz */, jlong ptr,
        jboolean enabled) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->setPointerCapture(enabled);
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

    if (fromChannel == nullptr || toChannel == nullptr) {
        return JNI_FALSE;
    }

    if (im->getInputManager()->getDispatcher()->
            transferTouchFocus(fromChannel->getToken(), toChannel->getToken())) {
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
            patternObj, nullptr));
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

    std::string dump;
    im->dump(dump);
    return env->NewStringUTF(dump.c_str());
}

static void nativeMonitor(JNIEnv* /* env */, jclass /* clazz */, jlong ptr) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->getInputManager()->getReader()->monitor();
    im->getInputManager()->getDispatcher()->monitor();
}

static jboolean nativeIsInputDeviceEnabled(JNIEnv* env /* env */,
        jclass /* clazz */, jlong ptr, jint deviceId) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    return im->getInputManager()->getReader()->isInputDeviceEnabled(deviceId);
}

static void nativeEnableInputDevice(JNIEnv* /* env */,
        jclass /* clazz */, jlong ptr, jint deviceId) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->setInputDeviceEnabled(deviceId, true);
}

static void nativeDisableInputDevice(JNIEnv* /* env */,
        jclass /* clazz */, jlong ptr, jint deviceId) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->setInputDeviceEnabled(deviceId, false);
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
    status_t result = android_view_PointerIcon_getLoadedIcon(env, iconObj, &pointerIcon);
    if (result) {
        jniThrowRuntimeException(env, "Failed to load custom pointer icon.");
        return;
    }

    SpriteIcon spriteIcon;
    SkImageInfo spriteInfo = pointerIcon.bitmap.info().makeColorType(kN32_SkColorType);
    if (spriteIcon.bitmap.tryAllocPixels(spriteInfo)) {
        pointerIcon.bitmap.readPixels(spriteInfo, spriteIcon.bitmap.getPixels(),
                spriteIcon.bitmap.rowBytes(), 0, 0);
    }
    spriteIcon.hotSpotX = pointerIcon.hotSpotX;
    spriteIcon.hotSpotY = pointerIcon.hotSpotY;
    im->setCustomPointerIcon(spriteIcon);
}

static jboolean nativeCanDispatchToDisplay(JNIEnv* env, jclass /* clazz */, jlong ptr,
        jint deviceId, jint displayId) {

    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);
    return im->getInputManager()->getReader()->canDispatchToDisplay(deviceId, displayId);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gInputManagerMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit",
            "(Lcom/android/server/input/InputManagerService;Landroid/content/Context;Landroid/os/MessageQueue;)J",
            (void*) nativeInit },
    { "nativeStart", "(J)V",
            (void*) nativeStart },
    { "nativeSetDisplayViewports", "(J[Landroid/hardware/display/DisplayViewport;)V",
            (void*) nativeSetDisplayViewports },
    { "nativeGetScanCodeState", "(JIII)I",
            (void*) nativeGetScanCodeState },
    { "nativeGetKeyCodeState", "(JIII)I",
            (void*) nativeGetKeyCodeState },
    { "nativeGetSwitchState", "(JIII)I",
            (void*) nativeGetSwitchState },
    { "nativeHasKeys", "(JII[I[Z)Z",
            (void*) nativeHasKeys },
    { "nativeRegisterInputChannel",
            "(JLandroid/view/InputChannel;I)V",
            (void*) nativeRegisterInputChannel },
    { "nativeRegisterInputMonitor",
            "(JLandroid/view/InputChannel;IZ)V",
            (void*) nativeRegisterInputMonitor},
    { "nativeUnregisterInputChannel", "(JLandroid/view/InputChannel;)V",
            (void*) nativeUnregisterInputChannel },
    { "nativePilferPointers", "(JLandroid/os/IBinder;)V",
            (void*) nativePilferPointers },
    { "nativeSetInputFilterEnabled", "(JZ)V",
            (void*) nativeSetInputFilterEnabled },
    { "nativeInjectInputEvent", "(JLandroid/view/InputEvent;IIIII)I",
            (void*) nativeInjectInputEvent },
    { "nativeToggleCapsLock", "(JI)V",
            (void*) nativeToggleCapsLock },
    { "nativeSetInputWindows", "(J[Landroid/view/InputWindowHandle;I)V",
            (void*) nativeSetInputWindows },
    { "nativeSetFocusedApplication", "(JILandroid/view/InputApplicationHandle;)V",
            (void*) nativeSetFocusedApplication },
    { "nativeSetFocusedDisplay", "(JI)V",
            (void*) nativeSetFocusedDisplay },
    { "nativeSetPointerCapture", "(JZ)V",
            (void*) nativeSetPointerCapture },
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
    { "nativeIsInputDeviceEnabled", "(JI)Z",
            (void*) nativeIsInputDeviceEnabled },
    { "nativeEnableInputDevice", "(JI)V",
            (void*) nativeEnableInputDevice },
    { "nativeDisableInputDevice", "(JI)V",
            (void*) nativeDisableInputDevice },
    { "nativeSetPointerIconType", "(JI)V",
            (void*) nativeSetPointerIconType },
    { "nativeReloadPointerIcons", "(J)V",
            (void*) nativeReloadPointerIcons },
    { "nativeSetCustomPointerIcon", "(JLandroid/view/PointerIcon;)V",
            (void*) nativeSetCustomPointerIcon },
    { "nativeCanDispatchToDisplay", "(JII)Z",
            (void*) nativeCanDispatchToDisplay },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! (var), "Unable to find class " className);

#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! (var), "Unable to find method " methodName);

#define GET_STATIC_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetStaticMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! (var), "Unable to find static method " methodName);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! (var), "Unable to find field " fieldName);

int register_android_server_InputManager(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/input/InputManagerService",
            gInputManagerMethods, NELEM(gInputManagerMethods));
    (void) res;  // Faked use when LOG_NDEBUG.
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    // Callbacks

    jclass clazz;
    FIND_CLASS(clazz, "com/android/server/input/InputManagerService");
    gServiceClassInfo.clazz = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));

    GET_METHOD_ID(gServiceClassInfo.notifyConfigurationChanged, clazz,
            "notifyConfigurationChanged", "(J)V");

    GET_METHOD_ID(gServiceClassInfo.notifyInputDevicesChanged, clazz,
            "notifyInputDevicesChanged", "([Landroid/view/InputDevice;)V");

    GET_METHOD_ID(gServiceClassInfo.notifySwitch, clazz,
            "notifySwitch", "(JII)V");

    GET_METHOD_ID(gServiceClassInfo.notifyInputChannelBroken, clazz,
            "notifyInputChannelBroken", "(Landroid/os/IBinder;)V");

    GET_METHOD_ID(gServiceClassInfo.notifyFocusChanged, clazz,
            "notifyFocusChanged", "(Landroid/os/IBinder;Landroid/os/IBinder;)V");

    GET_METHOD_ID(gServiceClassInfo.notifyANR, clazz,
            "notifyANR",
            "(Landroid/os/IBinder;Ljava/lang/String;)J");

    GET_METHOD_ID(gServiceClassInfo.filterInputEvent, clazz,
            "filterInputEvent", "(Landroid/view/InputEvent;I)Z");

    GET_METHOD_ID(gServiceClassInfo.interceptKeyBeforeQueueing, clazz,
            "interceptKeyBeforeQueueing", "(Landroid/view/KeyEvent;I)I");

    GET_METHOD_ID(gServiceClassInfo.interceptMotionBeforeQueueingNonInteractive, clazz,
            "interceptMotionBeforeQueueingNonInteractive", "(IJI)I");

    GET_METHOD_ID(gServiceClassInfo.interceptKeyBeforeDispatching, clazz,
            "interceptKeyBeforeDispatching",
            "(Landroid/os/IBinder;Landroid/view/KeyEvent;I)J");

    GET_METHOD_ID(gServiceClassInfo.dispatchUnhandledKey, clazz,
            "dispatchUnhandledKey",
            "(Landroid/os/IBinder;Landroid/view/KeyEvent;I)Landroid/view/KeyEvent;");

    GET_METHOD_ID(gServiceClassInfo.checkInjectEventsPermission, clazz,
            "checkInjectEventsPermission", "(II)Z");

    GET_METHOD_ID(gServiceClassInfo.onPointerDownOutsideFocus, clazz,
            "onPointerDownOutsideFocus", "(Landroid/os/IBinder;)V");

    GET_METHOD_ID(gServiceClassInfo.getVirtualKeyQuietTimeMillis, clazz,
            "getVirtualKeyQuietTimeMillis", "()I");

    GET_STATIC_METHOD_ID(gServiceClassInfo.getExcludedDeviceNames, clazz,
            "getExcludedDeviceNames", "()[Ljava/lang/String;");

    GET_STATIC_METHOD_ID(gServiceClassInfo.getInputPortAssociations, clazz,
            "getInputPortAssociations", "()[Ljava/lang/String;");

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
            "getPointerIcon", "(I)Landroid/view/PointerIcon;");

    GET_METHOD_ID(gServiceClassInfo.getPointerDisplayId, clazz,
            "getPointerDisplayId", "()I");

    GET_METHOD_ID(gServiceClassInfo.getKeyboardLayoutOverlay, clazz,
            "getKeyboardLayoutOverlay",
            "(Landroid/hardware/input/InputDeviceIdentifier;)[Ljava/lang/String;");

    GET_METHOD_ID(gServiceClassInfo.getDeviceAlias, clazz,
            "getDeviceAlias", "(Ljava/lang/String;)Ljava/lang/String;");

    GET_METHOD_ID(gServiceClassInfo.getTouchCalibrationForInputDevice, clazz,
            "getTouchCalibrationForInputDevice",
            "(Ljava/lang/String;I)Landroid/hardware/input/TouchCalibration;");

    GET_METHOD_ID(gServiceClassInfo.getContextForDisplay, clazz,
            "getContextForDisplay",
            "(I)Landroid/content/Context;")

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
