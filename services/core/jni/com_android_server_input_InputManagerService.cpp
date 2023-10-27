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

#include <android-base/parseint.h>
#include <android-base/stringprintf.h>
#include <android/os/IInputConstants.h>
#include <android/sysprop/InputProperties.sysprop.h>
#include <android_os_MessageQueue.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <android_view_InputChannel.h>
#include <android_view_InputDevice.h>
#include <android_view_KeyEvent.h>
#include <android_view_MotionEvent.h>
#include <android_view_PointerIcon.h>
#include <android_view_VerifiedKeyEvent.h>
#include <android_view_VerifiedMotionEvent.h>
#include <batteryservice/include/batteryservice/BatteryServiceConstants.h>
#include <binder/IServiceManager.h>
#include <com_android_input_flags.h>
#include <input/Input.h>
#include <input/PointerController.h>
#include <input/SpriteController.h>
#include <inputflinger/InputManager.h>
#include <limits.h>
#include <nativehelper/ScopedLocalFrame.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include <server_configurable_flags/get_flags.h>
#include <ui/Region.h>
#include <utils/Log.h>
#include <utils/Looper.h>
#include <utils/Trace.h>
#include <utils/threads.h>

#include <atomic>
#include <cinttypes>
#include <vector>

#include "android_hardware_display_DisplayViewport.h"
#include "android_hardware_input_InputApplicationHandle.h"
#include "android_hardware_input_InputWindowHandle.h"
#include "android_util_Binder.h"
#include "com_android_server_power_PowerManagerService.h"

#define INDENT "  "

using android::base::ParseUint;
using android::base::StringPrintf;
using android::os::InputEventInjectionResult;
using android::os::InputEventInjectionSync;

// Maximum allowable delay value in a vibration pattern before
// which the delay will be truncated.
static constexpr std::chrono::duration MAX_VIBRATE_PATTERN_DELAY = 100s;
static constexpr std::chrono::milliseconds MAX_VIBRATE_PATTERN_DELAY_MILLIS =
        std::chrono::duration_cast<std::chrono::milliseconds>(MAX_VIBRATE_PATTERN_DELAY);

namespace input_flags = com::android::input::flags;

namespace android {

static const bool ENABLE_POINTER_CHOREOGRAPHER = input_flags::enable_pointer_choreographer();

// The exponent used to calculate the pointer speed scaling factor.
// The scaling factor is calculated as 2 ^ (speed * exponent),
// where the speed ranges from -7 to + 7 and is supplied by the user.
static const float POINTER_SPEED_EXPONENT = 1.0f / 4;

// Category (=namespace) name for the input settings that are applied at boot time
static const char* INPUT_NATIVE_BOOT = "input_native_boot";
/**
 * Feature flag name. This flag determines which VelocityTracker strategy is used by default.
 */
static const char* VELOCITYTRACKER_STRATEGY = "velocitytracker_strategy";

static struct {
    jclass clazz;
    jmethodID notifyConfigurationChanged;
    jmethodID notifyInputDevicesChanged;
    jmethodID notifySwitch;
    jmethodID notifyInputChannelBroken;
    jmethodID notifyNoFocusedWindowAnr;
    jmethodID notifyWindowUnresponsive;
    jmethodID notifyWindowResponsive;
    jmethodID notifyFocusChanged;
    jmethodID notifySensorEvent;
    jmethodID notifySensorAccuracy;
    jmethodID notifyStylusGestureStarted;
    jmethodID isInputMethodConnectionActive;
    jmethodID notifyVibratorState;
    jmethodID filterInputEvent;
    jmethodID interceptKeyBeforeQueueing;
    jmethodID interceptMotionBeforeQueueingNonInteractive;
    jmethodID interceptKeyBeforeDispatching;
    jmethodID dispatchUnhandledKey;
    jmethodID onPointerDisplayIdChanged;
    jmethodID onPointerDownOutsideFocus;
    jmethodID getVirtualKeyQuietTimeMillis;
    jmethodID getExcludedDeviceNames;
    jmethodID getInputPortAssociations;
    jmethodID getInputUniqueIdAssociations;
    jmethodID getDeviceTypeAssociations;
    jmethodID getKeyboardLayoutAssociations;
    jmethodID getHoverTapTimeout;
    jmethodID getHoverTapSlop;
    jmethodID getDoubleTapTimeout;
    jmethodID getLongPressTimeout;
    jmethodID getPointerLayer;
    jmethodID getPointerIcon;
    jmethodID getKeyboardLayoutOverlay;
    jmethodID getDeviceAlias;
    jmethodID getTouchCalibrationForInputDevice;
    jmethodID getContextForDisplay;
    jmethodID notifyDropWindow;
    jmethodID getParentSurfaceForPointers;
} gServiceClassInfo;

static struct {
    jclass clazz;
    jfieldID mPtr;
} gNativeInputManagerServiceImpl;

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

static struct {
    jclass clazz;
    jmethodID constructor;
    jfieldID lightTypeInput;
    jfieldID lightTypePlayerId;
    jfieldID lightTypeKeyboardBacklight;
    jfieldID lightCapabilityBrightness;
    jfieldID lightCapabilityColorRgb;
} gLightClassInfo;

static struct {
    jclass clazz;
    jmethodID constructor;
    jmethodID add;
} gArrayListClassInfo;

static struct {
    jclass clazz;
    jmethodID constructor;
    jmethodID keyAt;
    jmethodID valueAt;
    jmethodID size;
} gSparseArrayClassInfo;

static struct InputSensorInfoOffsets {
    jclass clazz;
    // fields
    jfieldID name;
    jfieldID vendor;
    jfieldID version;
    jfieldID handle;
    jfieldID maxRange;
    jfieldID resolution;
    jfieldID power;
    jfieldID minDelay;
    jfieldID fifoReservedEventCount;
    jfieldID fifoMaxEventCount;
    jfieldID stringType;
    jfieldID requiredPermission;
    jfieldID maxDelay;
    jfieldID flags;
    jfieldID type;
    jfieldID id;
    // methods
    jmethodID init;
} gInputSensorInfo;

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

static void loadSystemIconAsSpriteWithPointerIcon(JNIEnv* env, jobject contextObj,
                                                  PointerIconStyle style,
                                                  PointerIcon* outPointerIcon,
                                                  SpriteIcon* outSpriteIcon) {
    status_t status = android_view_PointerIcon_loadSystemIcon(env,
            contextObj, style, outPointerIcon);
    if (!status) {
        outSpriteIcon->bitmap = outPointerIcon->bitmap.copy(ANDROID_BITMAP_FORMAT_RGBA_8888);
        outSpriteIcon->style = outPointerIcon->style;
        outSpriteIcon->hotSpotX = outPointerIcon->hotSpotX;
        outSpriteIcon->hotSpotY = outPointerIcon->hotSpotY;
    }
}

static void loadSystemIconAsSprite(JNIEnv* env, jobject contextObj, PointerIconStyle style,
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

class NativeInputManager : public virtual InputReaderPolicyInterface,
                           public virtual InputDispatcherPolicyInterface,
                           public virtual PointerControllerPolicyInterface,
                           public virtual PointerChoreographerPolicyInterface {
protected:
    virtual ~NativeInputManager();

public:
    NativeInputManager(jobject serviceObj, const sp<Looper>& looper);

    inline sp<InputManagerInterface> getInputManager() const { return mInputManager; }

    void dump(std::string& dump);

    void setDisplayViewports(JNIEnv* env, jobjectArray viewportObjArray);

    base::Result<std::unique_ptr<InputChannel>> createInputChannel(const std::string& name);
    base::Result<std::unique_ptr<InputChannel>> createInputMonitor(int32_t displayId,
                                                                   const std::string& name,
                                                                   gui::Pid pid);
    status_t removeInputChannel(const sp<IBinder>& connectionToken);
    status_t pilferPointers(const sp<IBinder>& token);

    void displayRemoved(JNIEnv* env, int32_t displayId);
    void setFocusedApplication(JNIEnv* env, int32_t displayId, jobject applicationHandleObj);
    void setFocusedDisplay(int32_t displayId);
    void setInputDispatchMode(bool enabled, bool frozen);
    void setSystemUiLightsOut(bool lightsOut);
    void setPointerDisplayId(int32_t displayId);
    void setPointerSpeed(int32_t speed);
    void setPointerAcceleration(float acceleration);
    void setTouchpadPointerSpeed(int32_t speed);
    void setTouchpadNaturalScrollingEnabled(bool enabled);
    void setTouchpadTapToClickEnabled(bool enabled);
    void setTouchpadRightClickZoneEnabled(bool enabled);
    void setInputDeviceEnabled(uint32_t deviceId, bool enabled);
    void setShowTouches(bool enabled);
    void setInteractive(bool interactive);
    void reloadCalibration();
    void setPointerIconType(PointerIconStyle iconId);
    void reloadPointerIcons();
    void requestPointerCapture(const sp<IBinder>& windowToken, bool enabled);
    void setCustomPointerIcon(const SpriteIcon& icon);
    void setMotionClassifierEnabled(bool enabled);
    std::optional<std::string> getBluetoothAddress(int32_t deviceId);
    void setStylusButtonMotionEventsEnabled(bool enabled);
    FloatPoint getMouseCursorPosition();
    void setStylusPointerIconEnabled(bool enabled);

    /* --- InputReaderPolicyInterface implementation --- */

    void getReaderConfiguration(InputReaderConfiguration* outConfig) override;
    std::shared_ptr<PointerControllerInterface> obtainPointerController(int32_t deviceId) override;
    void notifyInputDevicesChanged(const std::vector<InputDeviceInfo>& inputDevices) override;
    std::shared_ptr<KeyCharacterMap> getKeyboardLayoutOverlay(
            const InputDeviceIdentifier& identifier,
            const std::optional<KeyboardLayoutInfo> keyboardLayoutInfo) override;
    std::string getDeviceAlias(const InputDeviceIdentifier& identifier) override;
    TouchAffineTransformation getTouchAffineTransformation(const std::string& inputDeviceDescriptor,
                                                           ui::Rotation surfaceRotation) override;

    TouchAffineTransformation getTouchAffineTransformation(JNIEnv* env, jfloatArray matrixArr);
    void notifyStylusGestureStarted(int32_t deviceId, nsecs_t eventTime) override;
    bool isInputMethodConnectionActive() override;
    std::optional<DisplayViewport> getPointerViewportForAssociatedDisplay(
            int32_t associatedDisplayId) override;

    /* --- InputDispatcherPolicyInterface implementation --- */

    void notifySwitch(nsecs_t when, uint32_t switchValues, uint32_t switchMask,
                      uint32_t policyFlags) override;
    void notifyConfigurationChanged(nsecs_t when) override;
    // ANR-related callbacks -- start
    void notifyNoFocusedWindowAnr(const std::shared_ptr<InputApplicationHandle>& handle) override;
    void notifyWindowUnresponsive(const sp<IBinder>& token, std::optional<gui::Pid> pid,
                                  const std::string& reason) override;
    void notifyWindowResponsive(const sp<IBinder>& token, std::optional<gui::Pid> pid) override;
    // ANR-related callbacks -- end
    void notifyInputChannelBroken(const sp<IBinder>& token) override;
    void notifyFocusChanged(const sp<IBinder>& oldToken, const sp<IBinder>& newToken) override;
    void notifySensorEvent(int32_t deviceId, InputDeviceSensorType sensorType,
                           InputDeviceSensorAccuracy accuracy, nsecs_t timestamp,
                           const std::vector<float>& values) override;
    void notifySensorAccuracy(int32_t deviceId, InputDeviceSensorType sensorType,
                              InputDeviceSensorAccuracy accuracy) override;
    void notifyVibratorState(int32_t deviceId, bool isOn) override;
    bool filterInputEvent(const InputEvent& inputEvent, uint32_t policyFlags) override;
    void interceptKeyBeforeQueueing(const KeyEvent& keyEvent, uint32_t& policyFlags) override;
    void interceptMotionBeforeQueueing(int32_t displayId, nsecs_t when,
                                       uint32_t& policyFlags) override;
    nsecs_t interceptKeyBeforeDispatching(const sp<IBinder>& token, const KeyEvent& keyEvent,
                                          uint32_t policyFlags) override;
    std::optional<KeyEvent> dispatchUnhandledKey(const sp<IBinder>& token, const KeyEvent& keyEvent,
                                                 uint32_t policyFlags) override;
    void pokeUserActivity(nsecs_t eventTime, int32_t eventType, int32_t displayId) override;
    void onPointerDownOutsideFocus(const sp<IBinder>& touchedToken) override;
    void setPointerCapture(const PointerCaptureRequest& request) override;
    void notifyDropWindow(const sp<IBinder>& token, float x, float y) override;
    void notifyDeviceInteraction(int32_t deviceId, nsecs_t timestamp,
                                 const std::set<gui::Uid>& uids) override;

    /* --- PointerControllerPolicyInterface implementation --- */

    virtual void loadPointerIcon(SpriteIcon* icon, int32_t displayId);
    virtual void loadPointerResources(PointerResources* outResources, int32_t displayId);
    virtual void loadAdditionalMouseResources(
            std::map<PointerIconStyle, SpriteIcon>* outResources,
            std::map<PointerIconStyle, PointerAnimation>* outAnimationResources, int32_t displayId);
    virtual PointerIconStyle getDefaultPointerIconId();
    virtual PointerIconStyle getDefaultStylusIconId();
    virtual PointerIconStyle getCustomPointerIconId();
    virtual void onPointerDisplayIdChanged(int32_t displayId, const FloatPoint& position);

    /* --- PointerChoreographerPolicyInterface implementation --- */
    std::shared_ptr<PointerControllerInterface> createPointerController(
            PointerControllerInterface::ControllerType type) override;
    void notifyPointerDisplayIdChanged(int32_t displayId, const FloatPoint& position) override;

private:
    sp<InputManagerInterface> mInputManager;

    jobject mServiceObj;
    sp<Looper> mLooper;

    std::mutex mLock;
    struct Locked {
        // Display size information.
        std::vector<DisplayViewport> viewports{};

        // True if System UI is less noticeable.
        bool systemUiLightsOut{false};

        // Pointer speed.
        int32_t pointerSpeed{0};

        // Pointer acceleration.
        float pointerAcceleration{android::os::IInputConstants::DEFAULT_POINTER_ACCELERATION};

        // True if pointer gestures are enabled.
        bool pointerGesturesEnabled{true};

        // Show touches feature enable/disable.
        bool showTouches{false};

        // The latest request to enable or disable Pointer Capture.
        PointerCaptureRequest pointerCaptureRequest{};

        // Sprite controller singleton, created on first use.
        std::shared_ptr<SpriteController> spriteController{};

        // TODO(b/293587049): Remove when the PointerChoreographer refactoring is complete.
        // Pointer controller singleton, created and destroyed as needed.
        std::weak_ptr<PointerController> legacyPointerController{};

        // The list of PointerControllers created and managed by the PointerChoreographer.
        std::list<std::weak_ptr<PointerController>> pointerControllers{};

        // Input devices to be disabled
        std::set<int32_t> disabledInputDevices{};

        // Associated Pointer controller display.
        int32_t pointerDisplayId{ADISPLAY_ID_DEFAULT};

        // True if stylus button reporting through motion events is enabled.
        bool stylusButtonMotionEventsEnabled{true};

        // The touchpad pointer speed, as a number from -7 (slowest) to 7 (fastest).
        int32_t touchpadPointerSpeed{0};

        // True to invert the touchpad scrolling direction, so that moving two fingers downwards on
        // the touchpad scrolls the content upwards.
        bool touchpadNaturalScrollingEnabled{true};

        // True to enable tap-to-click on touchpads.
        bool touchpadTapToClickEnabled{true};

        // True to enable a zone on the right-hand side of touchpads where clicks will be turned
        // into context (a.k.a. "right") clicks.
        bool touchpadRightClickZoneEnabled{false};

        // True if a pointer icon should be shown for stylus pointers.
        bool stylusPointerIconEnabled{false};
    } mLocked GUARDED_BY(mLock);

    std::atomic<bool> mInteractive;
    void updateInactivityTimeoutLocked();
    void handleInterceptActions(jint wmActions, nsecs_t when, uint32_t& policyFlags);
    void ensureSpriteControllerLocked();
    sp<SurfaceControl> getParentSurfaceForPointers(int displayId);
    static bool checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName);
    template <typename T>
    std::unordered_map<std::string, T> readMapFromInterleavedJavaArray(
            jmethodID method, const char* methodName,
            std::function<T(std::string)> opOnValue = [](auto&& v) { return std::move(v); });

    void forEachPointerControllerLocked(std::function<void(PointerController&)> apply)
            REQUIRES(mLock);

    static inline JNIEnv* jniEnv() { return AndroidRuntime::getJNIEnv(); }
};

NativeInputManager::NativeInputManager(jobject serviceObj, const sp<Looper>& looper)
      : mLooper(looper), mInteractive(true) {
    JNIEnv* env = jniEnv();

    mServiceObj = env->NewGlobalRef(serviceObj);

    InputManager* im = new InputManager(this, *this, *this);
    mInputManager = im;
    defaultServiceManager()->addService(String16("inputflinger"), im);
}

NativeInputManager::~NativeInputManager() {
    JNIEnv* env = jniEnv();

    env->DeleteGlobalRef(mServiceObj);
}

void NativeInputManager::dump(std::string& dump) {
    dump += "Input Manager State:\n";
    dump += StringPrintf(INDENT "Interactive: %s\n", toString(mInteractive.load()));
    { // acquire lock
        std::scoped_lock _l(mLock);
        dump += StringPrintf(INDENT "System UI Lights Out: %s\n",
                             toString(mLocked.systemUiLightsOut));
        dump += StringPrintf(INDENT "Pointer Speed: %" PRId32 "\n", mLocked.pointerSpeed);
        dump += StringPrintf(INDENT "Pointer Acceleration: %0.3f\n", mLocked.pointerAcceleration);
        dump += StringPrintf(INDENT "Pointer Gestures Enabled: %s\n",
                toString(mLocked.pointerGesturesEnabled));
        dump += StringPrintf(INDENT "Show Touches: %s\n", toString(mLocked.showTouches));
        dump += StringPrintf(INDENT "Pointer Capture: %s, seq=%" PRIu32 "\n",
                             mLocked.pointerCaptureRequest.enable ? "Enabled" : "Disabled",
                             mLocked.pointerCaptureRequest.seq);
        if (auto pc = mLocked.legacyPointerController.lock(); pc) {
            dump += pc->dump();
        }
    } // release lock
    dump += "\n";

    mInputManager->dump(dump);
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
            ALOGI("Viewport [%d] to add: %s, isActive: %s", (int)i, viewport.uniqueId.c_str(),
                  toString(viewport.isActive));
            viewports.push_back(viewport);

            env->DeleteLocalRef(viewportObj);
        }
    }

    { // acquire lock
        std::scoped_lock _l(mLock);
        mLocked.viewports = viewports;
        forEachPointerControllerLocked(
                [&viewports](PointerController& pc) { pc.onDisplayViewportsUpdated(viewports); });
    } // release lock

    if (ENABLE_POINTER_CHOREOGRAPHER) {
        mInputManager->getChoreographer().setDisplayViewports(viewports);
    }
    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::DISPLAY_INFO);
}

base::Result<std::unique_ptr<InputChannel>> NativeInputManager::createInputChannel(
        const std::string& name) {
    ATRACE_CALL();
    return mInputManager->getDispatcher().createInputChannel(name);
}

base::Result<std::unique_ptr<InputChannel>> NativeInputManager::createInputMonitor(
        int32_t displayId, const std::string& name, gui::Pid pid) {
    ATRACE_CALL();
    return mInputManager->getDispatcher().createInputMonitor(displayId, name, pid);
}

status_t NativeInputManager::removeInputChannel(const sp<IBinder>& connectionToken) {
    ATRACE_CALL();
    return mInputManager->getDispatcher().removeInputChannel(connectionToken);
}

status_t NativeInputManager::pilferPointers(const sp<IBinder>& token) {
    ATRACE_CALL();
    return mInputManager->getDispatcher().pilferPointers(token);
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
    jobjectArray portAssociations = jobjectArray(env->CallObjectMethod(mServiceObj,
            gServiceClassInfo.getInputPortAssociations));
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

    outConfig->uniqueIdAssociations =
            readMapFromInterleavedJavaArray<std::string>(gServiceClassInfo
                                                                 .getInputUniqueIdAssociations,
                                                         "getInputUniqueIdAssociations");

    outConfig->deviceTypeAssociations =
            readMapFromInterleavedJavaArray<std::string>(gServiceClassInfo
                                                                 .getDeviceTypeAssociations,
                                                         "getDeviceTypeAssociations");
    outConfig->keyboardLayoutAssociations = readMapFromInterleavedJavaArray<
            KeyboardLayoutInfo>(gServiceClassInfo.getKeyboardLayoutAssociations,
                                "getKeyboardLayoutAssociations", [](auto&& layoutIdentifier) {
                                    size_t commaPos = layoutIdentifier.find(',');
                                    std::string languageTag = layoutIdentifier.substr(0, commaPos);
                                    std::string layoutType = layoutIdentifier.substr(commaPos + 1);
                                    return KeyboardLayoutInfo(std::move(languageTag),
                                                              std::move(layoutType));
                                });

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
        std::scoped_lock _l(mLock);

        outConfig->pointerVelocityControlParameters.scale = exp2f(mLocked.pointerSpeed
                * POINTER_SPEED_EXPONENT);
        outConfig->pointerVelocityControlParameters.acceleration = mLocked.pointerAcceleration;
        outConfig->pointerGesturesEnabled = mLocked.pointerGesturesEnabled;

        outConfig->showTouches = mLocked.showTouches;

        outConfig->pointerCaptureRequest = mLocked.pointerCaptureRequest;

        outConfig->setDisplayViewports(mLocked.viewports);

        outConfig->defaultPointerDisplayId = mLocked.pointerDisplayId;

        outConfig->touchpadPointerSpeed = mLocked.touchpadPointerSpeed;
        outConfig->touchpadNaturalScrollingEnabled = mLocked.touchpadNaturalScrollingEnabled;
        outConfig->touchpadTapToClickEnabled = mLocked.touchpadTapToClickEnabled;
        outConfig->touchpadRightClickZoneEnabled = mLocked.touchpadRightClickZoneEnabled;

        outConfig->disabledDevices = mLocked.disabledInputDevices;

        outConfig->stylusButtonMotionEventsEnabled = mLocked.stylusButtonMotionEventsEnabled;

        outConfig->stylusPointerIconEnabled = mLocked.stylusPointerIconEnabled;
    } // release lock
}

template <typename T>
std::unordered_map<std::string, T> NativeInputManager::readMapFromInterleavedJavaArray(
        jmethodID method, const char* methodName, std::function<T(std::string)> opOnValue) {
    JNIEnv* env = jniEnv();
    jobjectArray javaArray = jobjectArray(env->CallObjectMethod(mServiceObj, method));
    std::unordered_map<std::string, T> map;
    if (!checkAndClearExceptionFromCallback(env, methodName) && javaArray) {
        jsize length = env->GetArrayLength(javaArray);
        for (jsize i = 0; i < length / 2; i++) {
            std::string key = getStringElementFromJavaArray(env, javaArray, 2 * i);
            T value =
                    opOnValue(std::move(getStringElementFromJavaArray(env, javaArray, 2 * i + 1)));
            map.insert({key, value});
        }
    }
    env->DeleteLocalRef(javaArray);
    return map;
}

void NativeInputManager::forEachPointerControllerLocked(
        std::function<void(PointerController&)> apply) {
    if (auto pc = mLocked.legacyPointerController.lock(); pc) {
        apply(*pc);
    }

    auto it = mLocked.pointerControllers.begin();
    while (it != mLocked.pointerControllers.end()) {
        auto pc = it->lock();
        if (!pc) {
            it = mLocked.pointerControllers.erase(it);
            continue;
        }
        apply(*pc);
        it++;
    }
}

// TODO(b/293587049): Remove the old way of obtaining PointerController when the
//  PointerChoreographer refactoring is complete.
std::shared_ptr<PointerControllerInterface> NativeInputManager::obtainPointerController(
        int32_t /* deviceId */) {
    ATRACE_CALL();
    std::scoped_lock _l(mLock);

    std::shared_ptr<PointerController> controller = mLocked.legacyPointerController.lock();
    if (controller == nullptr) {
        ensureSpriteControllerLocked();

        // Disable the functionality of the legacy PointerController if PointerChoreographer is
        // enabled.
        controller = PointerController::create(this, mLooper, *mLocked.spriteController,
                                               /*enabled=*/!ENABLE_POINTER_CHOREOGRAPHER);
        mLocked.legacyPointerController = controller;
        updateInactivityTimeoutLocked();
    }

    return controller;
}

std::shared_ptr<PointerControllerInterface> NativeInputManager::createPointerController(
        PointerControllerInterface::ControllerType type) {
    std::scoped_lock _l(mLock);
    ensureSpriteControllerLocked();
    std::shared_ptr<PointerController> pc =
            PointerController::create(this, mLooper, *mLocked.spriteController, /*enabled=*/true,
                                      type);
    mLocked.pointerControllers.emplace_back(pc);
    return pc;
}

void NativeInputManager::onPointerDisplayIdChanged(int32_t pointerDisplayId,
                                                   const FloatPoint& position) {
    if (ENABLE_POINTER_CHOREOGRAPHER) {
        return;
    }
    JNIEnv* env = jniEnv();
    env->CallVoidMethod(mServiceObj, gServiceClassInfo.onPointerDisplayIdChanged, pointerDisplayId,
                        position.x, position.y);
    checkAndClearExceptionFromCallback(env, "onPointerDisplayIdChanged");
}

void NativeInputManager::notifyPointerDisplayIdChanged(int32_t pointerDisplayId,
                                                       const FloatPoint& position) {
    // Notify the Reader so that devices can be reconfigured.
    { // acquire lock
        std::scoped_lock _l(mLock);
        if (mLocked.pointerDisplayId == pointerDisplayId) {
            return;
        }
        mLocked.pointerDisplayId = pointerDisplayId;
        ALOGI("%s: pointer displayId set to: %d", __func__, pointerDisplayId);
    } // release lock
    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::DISPLAY_INFO);

    // Notify the system.
    JNIEnv* env = jniEnv();
    env->CallVoidMethod(mServiceObj, gServiceClassInfo.onPointerDisplayIdChanged, pointerDisplayId,
                        position.x, position.y);
    checkAndClearExceptionFromCallback(env, "onPointerDisplayIdChanged");
}

sp<SurfaceControl> NativeInputManager::getParentSurfaceForPointers(int displayId) {
    JNIEnv* env = jniEnv();
    jlong nativeSurfaceControlPtr =
            env->CallLongMethod(mServiceObj, gServiceClassInfo.getParentSurfaceForPointers,
                                displayId);
    if (checkAndClearExceptionFromCallback(env, "getParentSurfaceForPointers")) {
        return nullptr;
    }

    return reinterpret_cast<SurfaceControl*>(nativeSurfaceControlPtr);
}

void NativeInputManager::ensureSpriteControllerLocked() REQUIRES(mLock) {
    if (mLocked.spriteController) {
        return;
    }
    JNIEnv* env = jniEnv();
    jint layer = env->CallIntMethod(mServiceObj, gServiceClassInfo.getPointerLayer);
    if (checkAndClearExceptionFromCallback(env, "getPointerLayer")) {
        layer = -1;
    }
    mLocked.spriteController =
            std::make_shared<SpriteController>(mLooper, layer, [this](int displayId) {
                return getParentSurfaceForPointers(displayId);
            });
    // The SpriteController needs to be shared pointer because the handler callback needs to hold
    // a weak reference so that we can avoid racy conditions when the controller is being destroyed.
    mLocked.spriteController->setHandlerController(mLocked.spriteController);
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

std::shared_ptr<KeyCharacterMap> NativeInputManager::getKeyboardLayoutOverlay(
        const InputDeviceIdentifier& identifier,
        const std::optional<KeyboardLayoutInfo> keyboardLayoutInfo) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();

    std::shared_ptr<KeyCharacterMap> result;
    ScopedLocalRef<jstring> descriptor(env, env->NewStringUTF(identifier.descriptor.c_str()));
    ScopedLocalRef<jstring> languageTag(env,
                                        keyboardLayoutInfo
                                                ? env->NewStringUTF(
                                                          keyboardLayoutInfo->languageTag.c_str())
                                                : nullptr);
    ScopedLocalRef<jstring> layoutType(env,
                                       keyboardLayoutInfo
                                               ? env->NewStringUTF(
                                                         keyboardLayoutInfo->layoutType.c_str())
                                               : nullptr);
    ScopedLocalRef<jobject> identifierObj(env, env->NewObject(gInputDeviceIdentifierInfo.clazz,
            gInputDeviceIdentifierInfo.constructor, descriptor.get(),
            identifier.vendor, identifier.product));
    ScopedLocalRef<jobjectArray>
            arrayObj(env,
                     jobjectArray(env->CallObjectMethod(mServiceObj,
                                                        gServiceClassInfo.getKeyboardLayoutOverlay,
                                                        identifierObj.get(), languageTag.get(),
                                                        layoutType.get())));
    if (arrayObj.get()) {
        ScopedLocalRef<jstring> filenameObj(env,
                jstring(env->GetObjectArrayElement(arrayObj.get(), 0)));
        ScopedLocalRef<jstring> contentsObj(env,
                jstring(env->GetObjectArrayElement(arrayObj.get(), 1)));
        ScopedUtfChars filenameChars(env, filenameObj.get());
        ScopedUtfChars contentsChars(env, contentsObj.get());

        base::Result<std::shared_ptr<KeyCharacterMap>> ret =
                KeyCharacterMap::loadContents(filenameChars.c_str(), contentsChars.c_str(),
                                              KeyCharacterMap::Format::OVERLAY);
        if (ret.ok()) {
            result = *ret;
        }
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

static jobject getInputApplicationHandleObjLocalRef(
        JNIEnv* env, const std::shared_ptr<InputApplicationHandle>& inputApplicationHandle) {
    if (inputApplicationHandle == nullptr) {
        return nullptr;
    }
    NativeInputApplicationHandle* handle =
            static_cast<NativeInputApplicationHandle*>(inputApplicationHandle.get());

    return handle->getInputApplicationHandleObjLocalRef(env);
}

void NativeInputManager::notifyNoFocusedWindowAnr(
        const std::shared_ptr<InputApplicationHandle>& inputApplicationHandle) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    ALOGD("notifyNoFocusedWindowAnr");
#endif
    ATRACE_CALL();

    JNIEnv* env = jniEnv();
    ScopedLocalFrame localFrame(env);

    jobject inputApplicationHandleObj =
            getInputApplicationHandleObjLocalRef(env, inputApplicationHandle);

    env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifyNoFocusedWindowAnr,
                        inputApplicationHandleObj);
    checkAndClearExceptionFromCallback(env, "notifyNoFocusedWindowAnr");
}

void NativeInputManager::notifyWindowUnresponsive(const sp<IBinder>& token,
                                                  std::optional<gui::Pid> pid,
                                                  const std::string& reason) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    ALOGD("notifyWindowUnresponsive");
#endif
    ATRACE_CALL();

    JNIEnv* env = jniEnv();
    ScopedLocalFrame localFrame(env);

    jobject tokenObj = javaObjectForIBinder(env, token);
    ScopedLocalRef<jstring> reasonObj(env, env->NewStringUTF(reason.c_str()));

    env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifyWindowUnresponsive, tokenObj,
                        pid.value_or(gui::Pid{0}).val(), pid.has_value(), reasonObj.get());
    checkAndClearExceptionFromCallback(env, "notifyWindowUnresponsive");
}

void NativeInputManager::notifyWindowResponsive(const sp<IBinder>& token,
                                                std::optional<gui::Pid> pid) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    ALOGD("notifyWindowResponsive");
#endif
    ATRACE_CALL();

    JNIEnv* env = jniEnv();
    ScopedLocalFrame localFrame(env);

    jobject tokenObj = javaObjectForIBinder(env, token);

    env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifyWindowResponsive, tokenObj,
                        pid.value_or(gui::Pid{0}).val(), pid.has_value());
    checkAndClearExceptionFromCallback(env, "notifyWindowResponsive");
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

void NativeInputManager::notifyDropWindow(const sp<IBinder>& token, float x, float y) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    ALOGD("notifyDropWindow");
#endif
    ATRACE_CALL();

    JNIEnv* env = jniEnv();
    ScopedLocalFrame localFrame(env);

    jobject tokenObj = javaObjectForIBinder(env, token);
    env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifyDropWindow, tokenObj, x, y);
    checkAndClearExceptionFromCallback(env, "notifyDropWindow");
}

void NativeInputManager::notifyDeviceInteraction(int32_t deviceId, nsecs_t timestamp,
                                                 const std::set<gui::Uid>& uids) {
    static const bool ENABLE_INPUT_DEVICE_USAGE_METRICS =
            sysprop::InputProperties::enable_input_device_usage_metrics().value_or(true);
    if (!ENABLE_INPUT_DEVICE_USAGE_METRICS) return;

    mInputManager->getMetricsCollector().notifyDeviceInteraction(deviceId, timestamp, uids);
}

void NativeInputManager::notifySensorEvent(int32_t deviceId, InputDeviceSensorType sensorType,
                                           InputDeviceSensorAccuracy accuracy, nsecs_t timestamp,
                                           const std::vector<float>& values) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    ALOGD("notifySensorEvent");
#endif
    ATRACE_CALL();
    JNIEnv* env = jniEnv();
    ScopedLocalFrame localFrame(env);
    jfloatArray arr = env->NewFloatArray(values.size());
    env->SetFloatArrayRegion(arr, 0, values.size(), values.data());
    env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifySensorEvent, deviceId,
                        static_cast<jint>(sensorType), accuracy, timestamp, arr);
    checkAndClearExceptionFromCallback(env, "notifySensorEvent");
}

void NativeInputManager::notifySensorAccuracy(int32_t deviceId, InputDeviceSensorType sensorType,
                                              InputDeviceSensorAccuracy accuracy) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    ALOGD("notifySensorAccuracy");
#endif
    ATRACE_CALL();
    JNIEnv* env = jniEnv();
    ScopedLocalFrame localFrame(env);
    env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifySensorAccuracy, deviceId,
                        static_cast<jint>(sensorType), accuracy);
    checkAndClearExceptionFromCallback(env, "notifySensorAccuracy");
}

void NativeInputManager::notifyVibratorState(int32_t deviceId, bool isOn) {
#if DEBUG_INPUT_DISPATCHER_POLICY
    ALOGD("notifyVibratorState isOn:%d", isOn);
#endif
    ATRACE_CALL();
    JNIEnv* env = jniEnv();
    ScopedLocalFrame localFrame(env);
    env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifyVibratorState,
                        static_cast<jint>(deviceId), static_cast<jboolean>(isOn));
    checkAndClearExceptionFromCallback(env, "notifyVibratorState");
}

void NativeInputManager::displayRemoved(JNIEnv* env, int32_t displayId) {
    mInputManager->getDispatcher().displayRemoved(displayId);
}

void NativeInputManager::setFocusedApplication(JNIEnv* env, int32_t displayId,
        jobject applicationHandleObj) {
    if (!applicationHandleObj) {
        return;
    }
    std::shared_ptr<InputApplicationHandle> applicationHandle =
            android_view_InputApplicationHandle_getHandle(env, applicationHandleObj);
    applicationHandle->updateInfo();
    mInputManager->getDispatcher().setFocusedApplication(displayId, applicationHandle);
}

void NativeInputManager::setFocusedDisplay(int32_t displayId) {
    mInputManager->getDispatcher().setFocusedDisplay(displayId);
}

void NativeInputManager::setInputDispatchMode(bool enabled, bool frozen) {
    mInputManager->getDispatcher().setInputDispatchMode(enabled, frozen);
}

void NativeInputManager::setSystemUiLightsOut(bool lightsOut) {
    std::scoped_lock _l(mLock);

    if (mLocked.systemUiLightsOut != lightsOut) {
        mLocked.systemUiLightsOut = lightsOut;
        updateInactivityTimeoutLocked();
    }
}

void NativeInputManager::updateInactivityTimeoutLocked() REQUIRES(mLock) {
    forEachPointerControllerLocked([lightsOut = mLocked.systemUiLightsOut](PointerController& pc) {
        pc.setInactivityTimeout(lightsOut ? InactivityTimeout::SHORT : InactivityTimeout::NORMAL);
    });
}

void NativeInputManager::setPointerDisplayId(int32_t displayId) {
    if (ENABLE_POINTER_CHOREOGRAPHER) {
        mInputManager->getChoreographer().setDefaultMouseDisplayId(displayId);
    } else {
        { // acquire lock
            std::scoped_lock _l(mLock);

            if (mLocked.pointerDisplayId == displayId) {
                return;
            }

            ALOGI("Setting pointer display id to %d.", displayId);
            mLocked.pointerDisplayId = displayId;
        } // release lock

        mInputManager->getReader().requestRefreshConfiguration(
                InputReaderConfiguration::Change::DISPLAY_INFO);
    }
}

void NativeInputManager::setPointerSpeed(int32_t speed) {
    { // acquire lock
        std::scoped_lock _l(mLock);

        if (mLocked.pointerSpeed == speed) {
            return;
        }

        ALOGI("Setting pointer speed to %d.", speed);
        mLocked.pointerSpeed = speed;
    } // release lock

    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::POINTER_SPEED);
}

void NativeInputManager::setPointerAcceleration(float acceleration) {
    { // acquire lock
        std::scoped_lock _l(mLock);

        if (mLocked.pointerAcceleration == acceleration) {
            return;
        }

        ALOGI("Setting pointer acceleration to %0.3f", acceleration);
        mLocked.pointerAcceleration = acceleration;
    } // release lock

    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::POINTER_SPEED);
}

void NativeInputManager::setTouchpadPointerSpeed(int32_t speed) {
    { // acquire lock
        std::scoped_lock _l(mLock);

        if (mLocked.touchpadPointerSpeed == speed) {
            return;
        }

        ALOGI("Setting touchpad pointer speed to %d.", speed);
        mLocked.touchpadPointerSpeed = speed;
    } // release lock

    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::TOUCHPAD_SETTINGS);
}

void NativeInputManager::setTouchpadNaturalScrollingEnabled(bool enabled) {
    { // acquire lock
        std::scoped_lock _l(mLock);

        if (mLocked.touchpadNaturalScrollingEnabled == enabled) {
            return;
        }

        ALOGI("Setting touchpad natural scrolling to %s.", toString(enabled));
        mLocked.touchpadNaturalScrollingEnabled = enabled;
    } // release lock

    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::TOUCHPAD_SETTINGS);
}

void NativeInputManager::setTouchpadTapToClickEnabled(bool enabled) {
    { // acquire lock
        std::scoped_lock _l(mLock);

        if (mLocked.touchpadTapToClickEnabled == enabled) {
            return;
        }

        ALOGI("Setting touchpad tap to click to %s.", toString(enabled));
        mLocked.touchpadTapToClickEnabled = enabled;
    } // release lock

    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::TOUCHPAD_SETTINGS);
}

void NativeInputManager::setTouchpadRightClickZoneEnabled(bool enabled) {
    { // acquire lock
        std::scoped_lock _l(mLock);

        if (mLocked.touchpadRightClickZoneEnabled == enabled) {
            return;
        }

        ALOGI("Setting touchpad right click zone to %s.", toString(enabled));
        mLocked.touchpadRightClickZoneEnabled = enabled;
    } // release lock

    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::TOUCHPAD_SETTINGS);
}

void NativeInputManager::setInputDeviceEnabled(uint32_t deviceId, bool enabled) {
    bool refresh = false;

    { // acquire lock
        std::scoped_lock _l(mLock);

        auto it = mLocked.disabledInputDevices.find(deviceId);
        bool currentlyEnabled = it == mLocked.disabledInputDevices.end();
        if (!enabled && currentlyEnabled) {
            mLocked.disabledInputDevices.insert(deviceId);
            refresh = true;
        }
        if (enabled && !currentlyEnabled) {
            mLocked.disabledInputDevices.erase(deviceId);
            refresh = true;
        }
    } // release lock

    if (refresh) {
        mInputManager->getReader().requestRefreshConfiguration(
                InputReaderConfiguration::Change::ENABLED_STATE);
    }
}

void NativeInputManager::setShowTouches(bool enabled) {
    if (ENABLE_POINTER_CHOREOGRAPHER) {
        mInputManager->getChoreographer().setShowTouchesEnabled(enabled);
        return;
    }

    { // acquire lock
        std::scoped_lock _l(mLock);

        if (mLocked.showTouches == enabled) {
            return;
        }

        ALOGI("Setting show touches feature to %s.", enabled ? "enabled" : "disabled");
        mLocked.showTouches = enabled;
    } // release lock

    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::SHOW_TOUCHES);
}

void NativeInputManager::requestPointerCapture(const sp<IBinder>& windowToken, bool enabled) {
    mInputManager->getDispatcher().requestPointerCapture(windowToken, enabled);
}

void NativeInputManager::setInteractive(bool interactive) {
    mInteractive = interactive;
}

void NativeInputManager::reloadCalibration() {
    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::TOUCH_AFFINE_TRANSFORMATION);
}

void NativeInputManager::setPointerIconType(PointerIconStyle iconId) {
    std::scoped_lock _l(mLock);
    std::shared_ptr<PointerController> controller = mLocked.legacyPointerController.lock();
    if (controller != nullptr) {
        controller->updatePointerIcon(iconId);
    }
}

void NativeInputManager::reloadPointerIcons() {
    std::scoped_lock _l(mLock);
    forEachPointerControllerLocked([](PointerController& pc) { pc.reloadPointerResources(); });
}

void NativeInputManager::setCustomPointerIcon(const SpriteIcon& icon) {
    std::scoped_lock _l(mLock);
    std::shared_ptr<PointerController> controller = mLocked.legacyPointerController.lock();
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
        const std::string& inputDeviceDescriptor, ui::Rotation surfaceRotation) {
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

void NativeInputManager::notifyStylusGestureStarted(int32_t deviceId, nsecs_t eventTime) {
    JNIEnv* env = jniEnv();
    env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifyStylusGestureStarted, deviceId,
                        eventTime);
    checkAndClearExceptionFromCallback(env, "notifyStylusGestureStarted");
}

bool NativeInputManager::isInputMethodConnectionActive() {
    JNIEnv* env = jniEnv();
    const jboolean result =
            env->CallBooleanMethod(mServiceObj, gServiceClassInfo.isInputMethodConnectionActive);
    checkAndClearExceptionFromCallback(env, "isInputMethodConnectionActive");
    return result;
}

std::optional<DisplayViewport> NativeInputManager::getPointerViewportForAssociatedDisplay(
        int32_t associatedDisplayId) {
    return mInputManager->getChoreographer().getViewportForPointerDevice(associatedDisplayId);
}

bool NativeInputManager::filterInputEvent(const InputEvent& inputEvent, uint32_t policyFlags) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();

    ScopedLocalRef<jobject> inputEventObj(env);
    switch (inputEvent.getType()) {
        case InputEventType::KEY:
            inputEventObj.reset(
                    android_view_KeyEvent_fromNative(env,
                                                     static_cast<const KeyEvent&>(inputEvent)));
            break;
        case InputEventType::MOTION:
            inputEventObj.reset(
                    android_view_MotionEvent_obtainAsCopy(env,
                                                          static_cast<const MotionEvent&>(
                                                                  inputEvent)));
            break;
        default:
            return true; // dispatch the event normally
    }

    if (!inputEventObj.get()) {
        ALOGE("Failed to obtain input event object for filterInputEvent.");
        return true; // dispatch the event normally
    }

    // The callee is responsible for recycling the event.
    const jboolean continueEventDispatch =
            env->CallBooleanMethod(mServiceObj, gServiceClassInfo.filterInputEvent,
                                   inputEventObj.get(), policyFlags);
    if (checkAndClearExceptionFromCallback(env, "filterInputEvent")) {
        return true; // dispatch the event normally
    }
    return continueEventDispatch;
}

void NativeInputManager::interceptKeyBeforeQueueing(const KeyEvent& keyEvent,
                                                    uint32_t& policyFlags) {
    ATRACE_CALL();
    // Policy:
    // - Ignore untrusted events and pass them along.
    // - Ask the window manager what to do with normal events and trusted injected events.
    // - For normal events wake and brighten the screen if currently off or dim.
    const bool interactive = mInteractive.load();
    if (interactive) {
        policyFlags |= POLICY_FLAG_INTERACTIVE;
    }

    if ((policyFlags & POLICY_FLAG_TRUSTED) == 0) {
        if (interactive) {
            policyFlags |= POLICY_FLAG_PASS_TO_USER;
        }
        return;
    }

    const nsecs_t when = keyEvent.getEventTime();
    JNIEnv* env = jniEnv();
    ScopedLocalRef<jobject> keyEventObj(env, android_view_KeyEvent_fromNative(env, keyEvent));
    if (!keyEventObj.get()) {
        ALOGE("Failed to obtain key event object for interceptKeyBeforeQueueing.");
        return;
    }

    jint wmActions = env->CallIntMethod(mServiceObj, gServiceClassInfo.interceptKeyBeforeQueueing,
                                        keyEventObj.get(), policyFlags);
    if (checkAndClearExceptionFromCallback(env, "interceptKeyBeforeQueueing")) {
        wmActions = 0;
    }
    android_view_KeyEvent_recycle(env, keyEventObj.get());
    handleInterceptActions(wmActions, when, /*byref*/ policyFlags);
}

void NativeInputManager::interceptMotionBeforeQueueing(int32_t displayId, nsecs_t when,
                                                       uint32_t& policyFlags) {
    ATRACE_CALL();
    // Policy:
    // - Ignore untrusted events and pass them along.
    // - No special filtering for injected events required at this time.
    // - Filter normal events based on screen state.
    // - For normal events brighten (but do not wake) the screen if currently dim.
    const bool interactive = mInteractive.load();
    if (interactive) {
        policyFlags |= POLICY_FLAG_INTERACTIVE;
    }

    if ((policyFlags & POLICY_FLAG_TRUSTED) == 0 || (policyFlags & POLICY_FLAG_INJECTED)) {
        if (interactive) {
            policyFlags |= POLICY_FLAG_PASS_TO_USER;
        }
        return;
    }

    if (policyFlags & POLICY_FLAG_INTERACTIVE) {
        policyFlags |= POLICY_FLAG_PASS_TO_USER;
        return;
    }

    JNIEnv* env = jniEnv();
    const jint wmActions =
            env->CallIntMethod(mServiceObj,
                               gServiceClassInfo.interceptMotionBeforeQueueingNonInteractive,
                               displayId, when, policyFlags);
    if (checkAndClearExceptionFromCallback(env, "interceptMotionBeforeQueueingNonInteractive")) {
        return;
    }
    handleInterceptActions(wmActions, when, /*byref*/ policyFlags);
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

nsecs_t NativeInputManager::interceptKeyBeforeDispatching(const sp<IBinder>& token,
                                                          const KeyEvent& keyEvent,
                                                          uint32_t policyFlags) {
    ATRACE_CALL();
    // Policy:
    // - Ignore untrusted events and pass them along.
    // - Filter normal events and trusted injected events through the window manager policy to
    //   handle the HOME key and the like.
    if ((policyFlags & POLICY_FLAG_TRUSTED) == 0) {
        return 0;
    }

    JNIEnv* env = jniEnv();
    ScopedLocalFrame localFrame(env);

    // Token may be null
    ScopedLocalRef<jobject> tokenObj(env, javaObjectForIBinder(env, token));
    ScopedLocalRef<jobject> keyEventObj(env, android_view_KeyEvent_fromNative(env, keyEvent));
    if (!keyEventObj.get()) {
        ALOGE("Failed to obtain key event object for interceptKeyBeforeDispatching.");
        return 0;
    }

    const jlong delayMillis =
            env->CallLongMethod(mServiceObj, gServiceClassInfo.interceptKeyBeforeDispatching,
                                tokenObj.get(), keyEventObj.get(), policyFlags);
    android_view_KeyEvent_recycle(env, keyEventObj.get());
    if (checkAndClearExceptionFromCallback(env, "interceptKeyBeforeDispatching")) {
        return 0;
    }
    return delayMillis < 0 ? -1 : milliseconds_to_nanoseconds(delayMillis);
}

std::optional<KeyEvent> NativeInputManager::dispatchUnhandledKey(const sp<IBinder>& token,
                                                                 const KeyEvent& keyEvent,
                                                                 uint32_t policyFlags) {
    ATRACE_CALL();
    // Policy:
    // - Ignore untrusted events and do not perform default handling.
    if ((policyFlags & POLICY_FLAG_TRUSTED) == 0) {
        return {};
    }

    JNIEnv* env = jniEnv();
    ScopedLocalFrame localFrame(env);

    // Note: tokenObj may be null.
    ScopedLocalRef<jobject> tokenObj(env, javaObjectForIBinder(env, token));
    ScopedLocalRef<jobject> keyEventObj(env, android_view_KeyEvent_fromNative(env, keyEvent));
    if (!keyEventObj.get()) {
        ALOGE("Failed to obtain key event object for dispatchUnhandledKey.");
        return {};
    }

    ScopedLocalRef<jobject>
            fallbackKeyEventObj(env,
                                env->CallObjectMethod(mServiceObj,
                                                      gServiceClassInfo.dispatchUnhandledKey,
                                                      tokenObj.get(), keyEventObj.get(),
                                                      policyFlags));

    android_view_KeyEvent_recycle(env, keyEventObj.get());
    if (checkAndClearExceptionFromCallback(env, "dispatchUnhandledKey") ||
        !fallbackKeyEventObj.get()) {
        return {};
    }

    const KeyEvent fallbackEvent = android_view_KeyEvent_toNative(env, fallbackKeyEventObj.get());
    android_view_KeyEvent_recycle(env, fallbackKeyEventObj.get());
    return fallbackEvent;
}

void NativeInputManager::pokeUserActivity(nsecs_t eventTime, int32_t eventType, int32_t displayId) {
    ATRACE_CALL();
    android_server_PowerManagerService_userActivity(eventTime, eventType, displayId);
}

void NativeInputManager::onPointerDownOutsideFocus(const sp<IBinder>& touchedToken) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();
    ScopedLocalFrame localFrame(env);

    jobject touchedTokenObj = javaObjectForIBinder(env, touchedToken);
    env->CallVoidMethod(mServiceObj, gServiceClassInfo.onPointerDownOutsideFocus, touchedTokenObj);
    checkAndClearExceptionFromCallback(env, "onPointerDownOutsideFocus");
}

void NativeInputManager::setPointerCapture(const PointerCaptureRequest& request) {
    { // acquire lock
        std::scoped_lock _l(mLock);

        if (mLocked.pointerCaptureRequest == request) {
            return;
        }

        ALOGV("%s pointer capture.", request.enable ? "Enabling" : "Disabling");
        mLocked.pointerCaptureRequest = request;
    } // release lock

    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::POINTER_CAPTURE);
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
        *icon = SpriteIcon(
                pointerIcon.bitmap, pointerIcon.style, pointerIcon.hotSpotX, pointerIcon.hotSpotY);
    } else {
        *icon = SpriteIcon();
    }
}

void NativeInputManager::loadPointerResources(PointerResources* outResources, int32_t displayId) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();

    ScopedLocalRef<jobject> displayContext(env, env->CallObjectMethod(
            mServiceObj, gServiceClassInfo.getContextForDisplay, displayId));

    loadSystemIconAsSprite(env, displayContext.get(), PointerIconStyle::TYPE_SPOT_HOVER,
                           &outResources->spotHover);
    loadSystemIconAsSprite(env, displayContext.get(), PointerIconStyle::TYPE_SPOT_TOUCH,
                           &outResources->spotTouch);
    loadSystemIconAsSprite(env, displayContext.get(), PointerIconStyle::TYPE_SPOT_ANCHOR,
                           &outResources->spotAnchor);
}

void NativeInputManager::loadAdditionalMouseResources(
        std::map<PointerIconStyle, SpriteIcon>* outResources,
        std::map<PointerIconStyle, PointerAnimation>* outAnimationResources, int32_t displayId) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();

    ScopedLocalRef<jobject> displayContext(env, env->CallObjectMethod(
            mServiceObj, gServiceClassInfo.getContextForDisplay, displayId));

    for (int32_t iconId = static_cast<int32_t>(PointerIconStyle::TYPE_CONTEXT_MENU);
         iconId <= static_cast<int32_t>(PointerIconStyle::TYPE_HANDWRITING); ++iconId) {
        const PointerIconStyle pointerIconStyle = static_cast<PointerIconStyle>(iconId);
        PointerIcon pointerIcon;
        loadSystemIconAsSpriteWithPointerIcon(env, displayContext.get(), pointerIconStyle,
                                              &pointerIcon, &((*outResources)[pointerIconStyle]));
        if (!pointerIcon.bitmapFrames.empty()) {
            PointerAnimation& animationData = (*outAnimationResources)[pointerIconStyle];
            size_t numFrames = pointerIcon.bitmapFrames.size() + 1;
            animationData.durationPerFrame =
                    milliseconds_to_nanoseconds(pointerIcon.durationPerFrame);
            animationData.animationFrames.reserve(numFrames);
            animationData.animationFrames.push_back(SpriteIcon(
                    pointerIcon.bitmap, pointerIcon.style,
                    pointerIcon.hotSpotX, pointerIcon.hotSpotY));
            for (size_t i = 0; i < numFrames - 1; ++i) {
              animationData.animationFrames.push_back(SpriteIcon(
                      pointerIcon.bitmapFrames[i], pointerIcon.style,
                      pointerIcon.hotSpotX, pointerIcon.hotSpotY));
            }
        }
    }
    loadSystemIconAsSprite(env, displayContext.get(), PointerIconStyle::TYPE_NULL,
                           &((*outResources)[PointerIconStyle::TYPE_NULL]));
}

PointerIconStyle NativeInputManager::getDefaultPointerIconId() {
    return PointerIconStyle::TYPE_ARROW;
}

PointerIconStyle NativeInputManager::getDefaultStylusIconId() {
    // Use the empty icon as the default pointer icon for a hovering stylus.
    return PointerIconStyle::TYPE_NULL;
}

PointerIconStyle NativeInputManager::getCustomPointerIconId() {
    return PointerIconStyle::TYPE_CUSTOM;
}

void NativeInputManager::setMotionClassifierEnabled(bool enabled) {
    mInputManager->getProcessor().setMotionClassifierEnabled(enabled);
}

std::optional<std::string> NativeInputManager::getBluetoothAddress(int32_t deviceId) {
    return mInputManager->getReader().getBluetoothAddress(deviceId);
}

void NativeInputManager::setStylusButtonMotionEventsEnabled(bool enabled) {
    { // acquire lock
        std::scoped_lock _l(mLock);

        if (mLocked.stylusButtonMotionEventsEnabled == enabled) {
            return;
        }

        mLocked.stylusButtonMotionEventsEnabled = enabled;
    } // release lock

    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::STYLUS_BUTTON_REPORTING);
}

FloatPoint NativeInputManager::getMouseCursorPosition() {
    if (ENABLE_POINTER_CHOREOGRAPHER) {
        return mInputManager->getChoreographer().getMouseCursorPosition(ADISPLAY_ID_NONE);
    }
    std::scoped_lock _l(mLock);
    const auto pc = mLocked.legacyPointerController.lock();
    if (!pc) return {AMOTION_EVENT_INVALID_CURSOR_POSITION, AMOTION_EVENT_INVALID_CURSOR_POSITION};

    return pc->getPosition();
}

void NativeInputManager::setStylusPointerIconEnabled(bool enabled) {
    if (ENABLE_POINTER_CHOREOGRAPHER) {
        mInputManager->getChoreographer().setStylusPointerIconEnabled(enabled);
        return;
    }

    { // acquire lock
        std::scoped_lock _l(mLock);

        if (mLocked.stylusPointerIconEnabled == enabled) {
            return;
        }

        mLocked.stylusPointerIconEnabled = enabled;
    } // release lock

    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::DISPLAY_INFO);
}

// ----------------------------------------------------------------------------

static NativeInputManager* getNativeInputManager(JNIEnv* env, jobject clazz) {
    return reinterpret_cast<NativeInputManager*>(
            env->GetLongField(clazz, gNativeInputManagerServiceImpl.mPtr));
}

static jlong nativeInit(JNIEnv* env, jclass /* clazz */, jobject serviceObj,
                        jobject messageQueueObj) {
    sp<MessageQueue> messageQueue = android_os_MessageQueue_getMessageQueue(env, messageQueueObj);
    if (messageQueue == nullptr) {
        jniThrowRuntimeException(env, "MessageQueue is not initialized.");
        return 0;
    }

    static std::once_flag nativeInitialize;
    NativeInputManager* im = nullptr;
    std::call_once(nativeInitialize, [&]() {
        // Create the NativeInputManager, which should not be destroyed or deallocated for the
        // lifetime of the process.
        im = new NativeInputManager(serviceObj, messageQueue->getLooper());
    });
    LOG_ALWAYS_FATAL_IF(im == nullptr, "NativeInputManager was already initialized.");
    return reinterpret_cast<jlong>(im);
}

static void nativeStart(JNIEnv* env, jobject nativeImplObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    status_t result = im->getInputManager()->start();
    if (result) {
        jniThrowRuntimeException(env, "Input manager could not be started.");
    }
}

static void nativeSetDisplayViewports(JNIEnv* env, jobject nativeImplObj,
                                      jobjectArray viewportObjArray) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    im->setDisplayViewports(env, viewportObjArray);
}

static jint nativeGetScanCodeState(JNIEnv* env, jobject nativeImplObj, jint deviceId,
                                   jint sourceMask, jint scanCode) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    return (jint)im->getInputManager()->getReader().getScanCodeState(deviceId, uint32_t(sourceMask),
                                                                     scanCode);
}

static jint nativeGetKeyCodeState(JNIEnv* env, jobject nativeImplObj, jint deviceId,
                                  jint sourceMask, jint keyCode) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    return (jint)im->getInputManager()->getReader().getKeyCodeState(deviceId, uint32_t(sourceMask),
                                                                    keyCode);
}

static jint nativeGetSwitchState(JNIEnv* env, jobject nativeImplObj, jint deviceId, jint sourceMask,
                                 jint sw) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    return (jint)im->getInputManager()->getReader().getSwitchState(deviceId, uint32_t(sourceMask),
                                                                   sw);
}

static std::vector<int32_t> getIntArray(JNIEnv* env, jintArray arr) {
    int32_t* a = env->GetIntArrayElements(arr, nullptr);
    jsize size = env->GetArrayLength(arr);
    std::vector<int32_t> vec(a, a + size);
    env->ReleaseIntArrayElements(arr, a, 0);
    return vec;
}

static void nativeAddKeyRemapping(JNIEnv* env, jobject nativeImplObj, jint deviceId,
                                  jint fromKeyCode, jint toKeyCode) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    im->getInputManager()->getReader().addKeyRemapping(deviceId, fromKeyCode, toKeyCode);
}

static jboolean nativeHasKeys(JNIEnv* env, jobject nativeImplObj, jint deviceId, jint sourceMask,
                              jintArray keyCodes, jbooleanArray outFlags) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    const std::vector codes = getIntArray(env, keyCodes);
    uint8_t* flags = env->GetBooleanArrayElements(outFlags, nullptr);
    jsize numCodes = env->GetArrayLength(outFlags);
    jboolean result;
    if (numCodes != codes.size()) {
        return JNI_FALSE;
    }
    if (im->getInputManager()->getReader().hasKeys(deviceId, uint32_t(sourceMask), codes, flags)) {
        result = JNI_TRUE;
    } else {
        result = JNI_FALSE;
    }

    env->ReleaseBooleanArrayElements(outFlags, flags, 0);
    return result;
}

static jint nativeGetKeyCodeForKeyLocation(JNIEnv* env, jobject nativeImplObj, jint deviceId,
                                           jint locationKeyCode) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    return (jint)im->getInputManager()->getReader().getKeyCodeForKeyLocation(deviceId,
                                                                             locationKeyCode);
}

static void handleInputChannelDisposed(JNIEnv* env, jobject /* inputChannelObj */,
                                       const std::shared_ptr<InputChannel>& inputChannel,
                                       void* data) {
    NativeInputManager* im = static_cast<NativeInputManager*>(data);

    ALOGW("Input channel object '%s' was disposed without first being removed with "
          "the input manager!",
          inputChannel->getName().c_str());
    im->removeInputChannel(inputChannel->getConnectionToken());
}

static jobject nativeCreateInputChannel(JNIEnv* env, jobject nativeImplObj, jstring nameObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    ScopedUtfChars nameChars(env, nameObj);
    std::string name = nameChars.c_str();

    base::Result<std::unique_ptr<InputChannel>> inputChannel = im->createInputChannel(name);

    if (!inputChannel.ok()) {
        std::string message = inputChannel.error().message();
        message += StringPrintf(" Status=%d", static_cast<int>(inputChannel.error().code()));
        jniThrowRuntimeException(env, message.c_str());
        return nullptr;
    }

    jobject inputChannelObj =
            android_view_InputChannel_createJavaObject(env, std::move(*inputChannel));
    if (!inputChannelObj) {
        return nullptr;
    }

    android_view_InputChannel_setDisposeCallback(env, inputChannelObj,
            handleInputChannelDisposed, im);
    return inputChannelObj;
}

static jobject nativeCreateInputMonitor(JNIEnv* env, jobject nativeImplObj, jint displayId,
                                        jstring nameObj, jint pid) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    if (displayId == ADISPLAY_ID_NONE) {
        std::string message = "InputChannel used as a monitor must be associated with a display";
        jniThrowRuntimeException(env, message.c_str());
        return nullptr;
    }

    ScopedUtfChars nameChars(env, nameObj);
    std::string name = nameChars.c_str();

    base::Result<std::unique_ptr<InputChannel>> inputChannel =
            im->createInputMonitor(displayId, name, gui::Pid{pid});

    if (!inputChannel.ok()) {
        std::string message = inputChannel.error().message();
        message += StringPrintf(" Status=%d", static_cast<int>(inputChannel.error().code()));
        jniThrowRuntimeException(env, message.c_str());
        return nullptr;
    }

    jobject inputChannelObj =
            android_view_InputChannel_createJavaObject(env, std::move(*inputChannel));
    if (!inputChannelObj) {
        return nullptr;
    }
    return inputChannelObj;
}

static void nativeRemoveInputChannel(JNIEnv* env, jobject nativeImplObj, jobject tokenObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    sp<IBinder> token = ibinderForJavaObject(env, tokenObj);

    status_t status = im->removeInputChannel(token);
    if (status && status != BAD_VALUE) { // ignore already removed channel
        std::string message;
        message += StringPrintf("Failed to remove input channel.  status=%d", status);
        jniThrowRuntimeException(env, message.c_str());
    }
}

static void nativePilferPointers(JNIEnv* env, jobject nativeImplObj, jobject tokenObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    sp<IBinder> token = ibinderForJavaObject(env, tokenObj);
    im->pilferPointers(token);
}

static void nativeSetInputFilterEnabled(JNIEnv* env, jobject nativeImplObj, jboolean enabled) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->getInputManager()->getDispatcher().setInputFilterEnabled(enabled);
}

static jboolean nativeSetInTouchMode(JNIEnv* env, jobject nativeImplObj, jboolean inTouchMode,
                                     jint pid, jint uid, jboolean hasPermission, jint displayId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    return im->getInputManager()->getDispatcher().setInTouchMode(inTouchMode, gui::Pid{pid},
                                                                 gui::Uid{static_cast<uid_t>(uid)},
                                                                 hasPermission, displayId);
}

static void nativeSetMaximumObscuringOpacityForTouch(JNIEnv* env, jobject nativeImplObj,
                                                     jfloat opacity) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->getInputManager()->getDispatcher().setMaximumObscuringOpacityForTouch(opacity);
}

static jint nativeInjectInputEvent(JNIEnv* env, jobject nativeImplObj, jobject inputEventObj,
                                   jboolean injectIntoUid, jint uid, jint syncMode,
                                   jint timeoutMillis, jint policyFlags) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    const auto targetUid = injectIntoUid ? std::make_optional<gui::Uid>(uid) : std::nullopt;
    // static_cast is safe because the value was already checked at the Java layer
    InputEventInjectionSync mode = static_cast<InputEventInjectionSync>(syncMode);

    if (env->IsInstanceOf(inputEventObj, gKeyEventClassInfo.clazz)) {
        const KeyEvent keyEvent = android_view_KeyEvent_toNative(env, inputEventObj);
        const InputEventInjectionResult result =
                im->getInputManager()->getDispatcher().injectInputEvent(&keyEvent, targetUid, mode,
                                                                        std::chrono::milliseconds(
                                                                                timeoutMillis),
                                                                        uint32_t(policyFlags));
        return static_cast<jint>(result);
    } else if (env->IsInstanceOf(inputEventObj, gMotionEventClassInfo.clazz)) {
        const MotionEvent* motionEvent = android_view_MotionEvent_getNativePtr(env, inputEventObj);
        if (!motionEvent) {
            jniThrowRuntimeException(env, "Could not read contents of MotionEvent object.");
            return static_cast<jint>(InputEventInjectionResult::FAILED);
        }

        const InputEventInjectionResult result =
                im->getInputManager()->getDispatcher().injectInputEvent(motionEvent, targetUid,
                                                                        mode,
                                                                        std::chrono::milliseconds(
                                                                                timeoutMillis),
                                                                        uint32_t(policyFlags));
        return static_cast<jint>(result);
    } else {
        jniThrowRuntimeException(env, "Invalid input event type.");
        return static_cast<jint>(InputEventInjectionResult::FAILED);
    }
}

static jobject nativeVerifyInputEvent(JNIEnv* env, jobject nativeImplObj, jobject inputEventObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    if (env->IsInstanceOf(inputEventObj, gKeyEventClassInfo.clazz)) {
        const KeyEvent keyEvent = android_view_KeyEvent_toNative(env, inputEventObj);
        std::unique_ptr<VerifiedInputEvent> verifiedEvent =
                im->getInputManager()->getDispatcher().verifyInputEvent(keyEvent);
        if (verifiedEvent == nullptr) {
            return nullptr;
        }

        return android_view_VerifiedKeyEvent(env,
                                             static_cast<const VerifiedKeyEvent&>(*verifiedEvent));
    } else if (env->IsInstanceOf(inputEventObj, gMotionEventClassInfo.clazz)) {
        const MotionEvent* motionEvent = android_view_MotionEvent_getNativePtr(env, inputEventObj);
        if (!motionEvent) {
            jniThrowRuntimeException(env, "Could not read contents of MotionEvent object.");
            return nullptr;
        }

        std::unique_ptr<VerifiedInputEvent> verifiedEvent =
                im->getInputManager()->getDispatcher().verifyInputEvent(*motionEvent);

        if (verifiedEvent == nullptr) {
            return nullptr;
        }

        return android_view_VerifiedMotionEvent(env,
                                                static_cast<const VerifiedMotionEvent&>(
                                                        *verifiedEvent));
    } else {
        jniThrowRuntimeException(env, "Invalid input event type.");
        return nullptr;
    }
}

static void nativeToggleCapsLock(JNIEnv* env, jobject nativeImplObj, jint deviceId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->getInputManager()->getReader().toggleCapsLockState(deviceId);
}

static void nativeDisplayRemoved(JNIEnv* env, jobject nativeImplObj, jint displayId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->displayRemoved(env, displayId);
}

static void nativeSetFocusedApplication(JNIEnv* env, jobject nativeImplObj, jint displayId,
                                        jobject applicationHandleObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setFocusedApplication(env, displayId, applicationHandleObj);
}

static void nativeSetFocusedDisplay(JNIEnv* env, jobject nativeImplObj, jint displayId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setFocusedDisplay(displayId);
}

static void nativeRequestPointerCapture(JNIEnv* env, jobject nativeImplObj, jobject tokenObj,
                                        jboolean enabled) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    sp<IBinder> windowToken = ibinderForJavaObject(env, tokenObj);

    im->requestPointerCapture(windowToken, enabled);
}

static void nativeSetInputDispatchMode(JNIEnv* env, jobject nativeImplObj, jboolean enabled,
                                       jboolean frozen) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setInputDispatchMode(enabled, frozen);
}

static void nativeSetSystemUiLightsOut(JNIEnv* env, jobject nativeImplObj, jboolean lightsOut) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setSystemUiLightsOut(lightsOut);
}

static jboolean nativeTransferTouchFocus(JNIEnv* env, jobject nativeImplObj,
                                         jobject fromChannelTokenObj, jobject toChannelTokenObj,
                                         jboolean isDragDrop) {
    if (fromChannelTokenObj == nullptr || toChannelTokenObj == nullptr) {
        return JNI_FALSE;
    }

    sp<IBinder> fromChannelToken = ibinderForJavaObject(env, fromChannelTokenObj);
    sp<IBinder> toChannelToken = ibinderForJavaObject(env, toChannelTokenObj);

    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    if (im->getInputManager()->getDispatcher().transferTouchFocus(fromChannelToken, toChannelToken,
                                                                  isDragDrop)) {
        return JNI_TRUE;
    } else {
        return JNI_FALSE;
    }
}

static jboolean nativeTransferTouch(JNIEnv* env, jobject nativeImplObj, jobject destChannelTokenObj,
                                    jint displayId) {
    sp<IBinder> destChannelToken = ibinderForJavaObject(env, destChannelTokenObj);

    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    if (im->getInputManager()->getDispatcher().transferTouch(destChannelToken,
                                                             static_cast<int32_t>(displayId))) {
        return JNI_TRUE;
    } else {
        return JNI_FALSE;
    }
}

static void nativeSetPointerSpeed(JNIEnv* env, jobject nativeImplObj, jint speed) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setPointerSpeed(speed);
}

static void nativeSetPointerAcceleration(JNIEnv* env, jobject nativeImplObj, jfloat acceleration) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setPointerAcceleration(acceleration);
}

static void nativeSetTouchpadPointerSpeed(JNIEnv* env, jobject nativeImplObj, jint speed) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setTouchpadPointerSpeed(speed);
}

static void nativeSetTouchpadNaturalScrollingEnabled(JNIEnv* env, jobject nativeImplObj,
                                                     jboolean enabled) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setTouchpadNaturalScrollingEnabled(enabled);
}

static void nativeSetTouchpadTapToClickEnabled(JNIEnv* env, jobject nativeImplObj,
                                               jboolean enabled) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setTouchpadTapToClickEnabled(enabled);
}

static void nativeSetTouchpadRightClickZoneEnabled(JNIEnv* env, jobject nativeImplObj,
                                                   jboolean enabled) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setTouchpadRightClickZoneEnabled(enabled);
}

static void nativeSetShowTouches(JNIEnv* env, jobject nativeImplObj, jboolean enabled) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setShowTouches(enabled);
}

static void nativeSetInteractive(JNIEnv* env, jobject nativeImplObj, jboolean interactive) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setInteractive(interactive);
}

static void nativeReloadCalibration(JNIEnv* env, jobject nativeImplObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->reloadCalibration();
}

static void nativeVibrate(JNIEnv* env, jobject nativeImplObj, jint deviceId, jlongArray patternObj,
                          jintArray amplitudesObj, jint repeat, jint token) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    size_t patternSize = env->GetArrayLength(patternObj);
    if (patternSize > MAX_VIBRATE_PATTERN_SIZE) {
        ALOGI("Skipped requested vibration because the pattern size is %zu "
                "which is more than the maximum supported size of %d.",
                patternSize, MAX_VIBRATE_PATTERN_SIZE);
        return; // limit to reasonable size
    }

    jlong* patternMillis = static_cast<jlong*>(env->GetPrimitiveArrayCritical(
            patternObj, nullptr));
    jint* amplitudes = static_cast<jint*>(env->GetPrimitiveArrayCritical(amplitudesObj, nullptr));

    VibrationSequence sequence(patternSize);
    std::vector<int32_t> vibrators = im->getInputManager()->getReader().getVibratorIds(deviceId);
    for (size_t i = 0; i < patternSize; i++) {
        // VibrationEffect.validate guarantees duration > 0.
        std::chrono::milliseconds duration(patternMillis[i]);
        VibrationElement element(CHANNEL_SIZE);
        element.duration = std::min(duration, MAX_VIBRATE_PATTERN_DELAY_MILLIS);
        // Vibrate on both channels
        for (int32_t channel = 0; channel < vibrators.size(); channel++) {
            element.addChannel(vibrators[channel], static_cast<uint8_t>(amplitudes[i]));
        }
        sequence.addElement(element);
    }
    env->ReleasePrimitiveArrayCritical(patternObj, patternMillis, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(amplitudesObj, amplitudes, JNI_ABORT);

    im->getInputManager()->getReader().vibrate(deviceId, sequence, repeat, token);
}

static void nativeVibrateCombined(JNIEnv* env, jobject nativeImplObj, jint deviceId,
                                  jlongArray patternObj, jobject amplitudesObj, jint repeat,
                                  jint token) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    size_t patternSize = env->GetArrayLength(patternObj);

    if (patternSize > MAX_VIBRATE_PATTERN_SIZE) {
        ALOGI("Skipped requested vibration because the pattern size is %zu "
              "which is more than the maximum supported size of %d.",
              patternSize, MAX_VIBRATE_PATTERN_SIZE);
        return; // limit to reasonable size
    }
    const jlong* patternMillis = env->GetLongArrayElements(patternObj, nullptr);

    std::array<jint*, CHANNEL_SIZE> amplitudesArray;
    std::array<jint, CHANNEL_SIZE> vibratorIdArray;
    jint amplSize = env->CallIntMethod(amplitudesObj, gSparseArrayClassInfo.size);
    if (amplSize > CHANNEL_SIZE) {
        ALOGE("Can not fit into input device vibration element.");
        return;
    }

    for (int i = 0; i < amplSize; i++) {
        vibratorIdArray[i] = env->CallIntMethod(amplitudesObj, gSparseArrayClassInfo.keyAt, i);
        jintArray arr = static_cast<jintArray>(
                env->CallObjectMethod(amplitudesObj, gSparseArrayClassInfo.valueAt, i));
        amplitudesArray[i] = env->GetIntArrayElements(arr, nullptr);
        if (env->GetArrayLength(arr) != patternSize) {
            ALOGE("Amplitude length not equal to pattern length!");
            return;
        }
    }

    VibrationSequence sequence(patternSize);
    for (size_t i = 0; i < patternSize; i++) {
        VibrationElement element(CHANNEL_SIZE);
        // VibrationEffect.validate guarantees duration > 0.
        std::chrono::milliseconds duration(patternMillis[i]);
        element.duration = std::min(duration, MAX_VIBRATE_PATTERN_DELAY_MILLIS);
        for (int32_t channel = 0; channel < amplSize; channel++) {
            element.addChannel(vibratorIdArray[channel],
                               static_cast<uint8_t>(amplitudesArray[channel][i]));
        }
        sequence.addElement(element);
    }

    im->getInputManager()->getReader().vibrate(deviceId, sequence, repeat, token);
}

static void nativeCancelVibrate(JNIEnv* env, jobject nativeImplObj, jint deviceId, jint token) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->getInputManager()->getReader().cancelVibrate(deviceId, token);
}

static bool nativeIsVibrating(JNIEnv* env, jobject nativeImplObj, jint deviceId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    return im->getInputManager()->getReader().isVibrating(deviceId);
}

static jintArray nativeGetVibratorIds(JNIEnv* env, jobject nativeImplObj, jint deviceId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    std::vector<int32_t> vibrators = im->getInputManager()->getReader().getVibratorIds(deviceId);

    jintArray vibIdArray = env->NewIntArray(vibrators.size());
    if (vibIdArray != nullptr) {
        env->SetIntArrayRegion(vibIdArray, 0, vibrators.size(), vibrators.data());
    }
    return vibIdArray;
}

static jobject nativeGetLights(JNIEnv* env, jobject nativeImplObj, jint deviceId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    jobject jLights = env->NewObject(gArrayListClassInfo.clazz, gArrayListClassInfo.constructor);

    std::vector<InputDeviceLightInfo> lights =
            im->getInputManager()->getReader().getLights(deviceId);

    for (size_t i = 0; i < lights.size(); i++) {
        const InputDeviceLightInfo& lightInfo = lights[i];

        jint jTypeId =
                env->GetStaticIntField(gLightClassInfo.clazz, gLightClassInfo.lightTypeInput);
        if (lightInfo.type == InputDeviceLightType::INPUT) {
            jTypeId = env->GetStaticIntField(gLightClassInfo.clazz, gLightClassInfo.lightTypeInput);
        } else if (lightInfo.type == InputDeviceLightType::PLAYER_ID) {
            jTypeId = env->GetStaticIntField(gLightClassInfo.clazz,
                                                 gLightClassInfo.lightTypePlayerId);
        } else if (lightInfo.type == InputDeviceLightType::KEYBOARD_BACKLIGHT) {
            jTypeId = env->GetStaticIntField(gLightClassInfo.clazz,
                                             gLightClassInfo.lightTypeKeyboardBacklight);
        } else {
            ALOGW("Unknown light type %d", lightInfo.type);
            continue;
        }

        jint jCapability = 0;
        if (lightInfo.capabilityFlags.test(InputDeviceLightCapability::BRIGHTNESS)) {
            jCapability |= env->GetStaticIntField(gLightClassInfo.clazz,
                                                  gLightClassInfo.lightCapabilityBrightness);
        }
        if (lightInfo.capabilityFlags.test(InputDeviceLightCapability::RGB)) {
            jCapability |= env->GetStaticIntField(gLightClassInfo.clazz,
                                                  gLightClassInfo.lightCapabilityColorRgb);
        }

        ScopedLocalRef<jintArray> jPreferredBrightnessLevels{env};
        if (!lightInfo.preferredBrightnessLevels.empty()) {
            std::vector<int32_t> vec;
            for (auto it : lightInfo.preferredBrightnessLevels) {
              vec.push_back(ftl::to_underlying(it));
            }
            jPreferredBrightnessLevels.reset(env->NewIntArray(vec.size()));
            env->SetIntArrayRegion(jPreferredBrightnessLevels.get(), 0, vec.size(), vec.data());
        }

        ScopedLocalRef<jobject> lightObj(env,
                                         env->NewObject(gLightClassInfo.clazz,
                                                        gLightClassInfo.constructor,
                                                        static_cast<jint>(lightInfo.id),
                                                        env->NewStringUTF(lightInfo.name.c_str()),
                                                        static_cast<jint>(lightInfo.ordinal),
                                                        jTypeId, jCapability,
                                                        jPreferredBrightnessLevels.get()));
        // Add light object to list
        env->CallBooleanMethod(jLights, gArrayListClassInfo.add, lightObj.get());
    }

    return jLights;
}

static jint nativeGetLightPlayerId(JNIEnv* env, jobject nativeImplObj, jint deviceId,
                                   jint lightId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    std::optional<int32_t> ret =
            im->getInputManager()->getReader().getLightPlayerId(deviceId, lightId);

    return static_cast<jint>(ret.value_or(0));
}

static jint nativeGetLightColor(JNIEnv* env, jobject nativeImplObj, jint deviceId, jint lightId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    std::optional<int32_t> ret =
            im->getInputManager()->getReader().getLightColor(deviceId, lightId);
    return static_cast<jint>(ret.value_or(0));
}

static void nativeSetLightPlayerId(JNIEnv* env, jobject nativeImplObj, jint deviceId, jint lightId,
                                   jint playerId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->getInputManager()->getReader().setLightPlayerId(deviceId, lightId, playerId);
}

static void nativeSetLightColor(JNIEnv* env, jobject nativeImplObj, jint deviceId, jint lightId,
                                jint color) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->getInputManager()->getReader().setLightColor(deviceId, lightId, color);
}

static jint nativeGetBatteryCapacity(JNIEnv* env, jobject nativeImplObj, jint deviceId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    std::optional<int32_t> ret = im->getInputManager()->getReader().getBatteryCapacity(deviceId);
    return static_cast<jint>(ret.value_or(android::os::IInputConstants::INVALID_BATTERY_CAPACITY));
}

static jint nativeGetBatteryStatus(JNIEnv* env, jobject nativeImplObj, jint deviceId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    std::optional<int32_t> ret = im->getInputManager()->getReader().getBatteryStatus(deviceId);
    return static_cast<jint>(ret.value_or(BATTERY_STATUS_UNKNOWN));
}

static jstring nativeGetBatteryDevicePath(JNIEnv* env, jobject nativeImplObj, jint deviceId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    const std::optional<std::string> batteryPath =
            im->getInputManager()->getReader().getBatteryDevicePath(deviceId);
    return batteryPath ? env->NewStringUTF(batteryPath->c_str()) : nullptr;
}

static void nativeReloadKeyboardLayouts(JNIEnv* env, jobject nativeImplObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->getInputManager()->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::KEYBOARD_LAYOUTS);
}

static void nativeReloadDeviceAliases(JNIEnv* env, jobject nativeImplObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->getInputManager()->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::DEVICE_ALIAS);
}

static void nativeSysfsNodeChanged(JNIEnv* env, jobject nativeImplObj, jstring path) {
    ScopedUtfChars sysfsNodePathChars(env, path);
    const std::string sysfsNodePath = sysfsNodePathChars.c_str();

    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    im->getInputManager()->getReader().sysfsNodeChanged(sysfsNodePath);
}

static std::string dumpInputProperties() {
    std::string out = "Input properties:\n";
    const std::string strategy =
            server_configurable_flags::GetServerConfigurableFlag(INPUT_NATIVE_BOOT,
                                                                 VELOCITYTRACKER_STRATEGY,
                                                                 "default");
    out += "  velocitytracker_strategy (flag value) = " + strategy + "\n";
    out += "\n";
    return out;
}

static jstring nativeDump(JNIEnv* env, jobject nativeImplObj) {
    std::string dump = dumpInputProperties();

    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    im->dump(dump);

    return env->NewStringUTF(dump.c_str());
}

static void nativeMonitor(JNIEnv* env, jobject nativeImplObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->getInputManager()->getReader().monitor();
    im->getInputManager()->getDispatcher().monitor();
}

static jboolean nativeIsInputDeviceEnabled(JNIEnv* env, jobject nativeImplObj, jint deviceId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    return im->getInputManager()->getReader().isInputDeviceEnabled(deviceId);
}

static void nativeEnableInputDevice(JNIEnv* env, jobject nativeImplObj, jint deviceId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setInputDeviceEnabled(deviceId, true);
}

static void nativeDisableInputDevice(JNIEnv* env, jobject nativeImplObj, jint deviceId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setInputDeviceEnabled(deviceId, false);
}

static void nativeSetPointerIconType(JNIEnv* env, jobject nativeImplObj, jint iconId) {
    // iconId is set in java from from frameworks/base/core/java/android/view/PointerIcon.java,
    // where the definition in <input/Input.h> is duplicated as a sealed class (type safe enum
    // equivalent in Java).

    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setPointerIconType(static_cast<PointerIconStyle>(iconId));
}

static void nativeReloadPointerIcons(JNIEnv* env, jobject nativeImplObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->reloadPointerIcons();
}

static void nativeSetCustomPointerIcon(JNIEnv* env, jobject nativeImplObj, jobject iconObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    PointerIcon pointerIcon;
    status_t result = android_view_PointerIcon_getLoadedIcon(env, iconObj, &pointerIcon);
    if (result) {
        jniThrowRuntimeException(env, "Failed to load custom pointer icon.");
        return;
    }

    SpriteIcon spriteIcon(pointerIcon.bitmap.copy(ANDROID_BITMAP_FORMAT_RGBA_8888),
                          pointerIcon.style, pointerIcon.hotSpotX, pointerIcon.hotSpotY);
    im->setCustomPointerIcon(spriteIcon);
}

static jboolean nativeCanDispatchToDisplay(JNIEnv* env, jobject nativeImplObj, jint deviceId,
                                           jint displayId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    return im->getInputManager()->getReader().canDispatchToDisplay(deviceId, displayId);
}

static void nativeNotifyPortAssociationsChanged(JNIEnv* env, jobject nativeImplObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    im->getInputManager()->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::DISPLAY_INFO);
}

static void nativeSetDisplayEligibilityForPointerCapture(JNIEnv* env, jobject nativeImplObj,
                                                         jint displayId, jboolean isEligible) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    im->getInputManager()->getDispatcher().setDisplayEligibilityForPointerCapture(displayId,
                                                                                  isEligible);
}

static void nativeChangeUniqueIdAssociation(JNIEnv* env, jobject nativeImplObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    im->getInputManager()->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::DISPLAY_INFO);
}

static void nativeChangeTypeAssociation(JNIEnv* env, jobject nativeImplObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    im->getInputManager()->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::DEVICE_TYPE);
}

static void changeKeyboardLayoutAssociation(JNIEnv* env, jobject nativeImplObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    im->getInputManager()->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::KEYBOARD_LAYOUT_ASSOCIATION);
}

static void nativeSetMotionClassifierEnabled(JNIEnv* env, jobject nativeImplObj, jboolean enabled) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setMotionClassifierEnabled(enabled);
}

static void nativeSetKeyRepeatConfiguration(JNIEnv* env, jobject nativeImplObj, jint timeoutMs,
                                            jint delayMs) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    im->getInputManager()->getDispatcher().setKeyRepeatConfiguration(static_cast<nsecs_t>(
                                                                             timeoutMs) *
                                                                             1000000,
                                                                     static_cast<nsecs_t>(delayMs) *
                                                                             1000000);
}

static jobject createInputSensorInfo(JNIEnv* env, jstring name, jstring vendor, jint version,
                                     jint handle, jint type, jfloat maxRange, jfloat resolution,
                                     jfloat power, jfloat minDelay, jint fifoReservedEventCount,
                                     jint fifoMaxEventCount, jstring stringType,
                                     jstring requiredPermission, jint maxDelay, jint flags,
                                     jint id) {
    // SensorInfo sensorInfo = new Sensor();
    jobject sensorInfo = env->NewObject(gInputSensorInfo.clazz, gInputSensorInfo.init, "");

    if (sensorInfo != NULL) {
        env->SetObjectField(sensorInfo, gInputSensorInfo.name, name);
        env->SetObjectField(sensorInfo, gInputSensorInfo.vendor, vendor);
        env->SetIntField(sensorInfo, gInputSensorInfo.version, version);
        env->SetIntField(sensorInfo, gInputSensorInfo.handle, handle);
        env->SetFloatField(sensorInfo, gInputSensorInfo.maxRange, maxRange);
        env->SetFloatField(sensorInfo, gInputSensorInfo.resolution, resolution);
        env->SetFloatField(sensorInfo, gInputSensorInfo.power, power);
        env->SetIntField(sensorInfo, gInputSensorInfo.minDelay, minDelay);
        env->SetIntField(sensorInfo, gInputSensorInfo.fifoReservedEventCount,
                         fifoReservedEventCount);
        env->SetIntField(sensorInfo, gInputSensorInfo.fifoMaxEventCount, fifoMaxEventCount);
        env->SetObjectField(sensorInfo, gInputSensorInfo.requiredPermission, requiredPermission);
        env->SetIntField(sensorInfo, gInputSensorInfo.maxDelay, maxDelay);
        env->SetIntField(sensorInfo, gInputSensorInfo.flags, flags);
        env->SetObjectField(sensorInfo, gInputSensorInfo.stringType, stringType);
        env->SetIntField(sensorInfo, gInputSensorInfo.type, type);
        env->SetIntField(sensorInfo, gInputSensorInfo.id, id);
    }
    return sensorInfo;
}

static jobjectArray nativeGetSensorList(JNIEnv* env, jobject nativeImplObj, jint deviceId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    std::vector<InputDeviceSensorInfo> sensors =
            im->getInputManager()->getReader().getSensors(deviceId);

    jobjectArray arr = env->NewObjectArray(sensors.size(), gInputSensorInfo.clazz, nullptr);
    for (int i = 0; i < sensors.size(); i++) {
        const InputDeviceSensorInfo& sensorInfo = sensors[i];

        jobject info = createInputSensorInfo(env, env->NewStringUTF(sensorInfo.name.c_str()),
                                             env->NewStringUTF(sensorInfo.vendor.c_str()),
                                             static_cast<jint>(sensorInfo.version), 0 /* handle */,
                                             static_cast<jint>(sensorInfo.type),
                                             static_cast<jfloat>(sensorInfo.maxRange),
                                             static_cast<jfloat>(sensorInfo.resolution),
                                             static_cast<jfloat>(sensorInfo.power),
                                             static_cast<jfloat>(sensorInfo.minDelay),
                                             static_cast<jint>(sensorInfo.fifoReservedEventCount),
                                             static_cast<jint>(sensorInfo.fifoMaxEventCount),
                                             env->NewStringUTF(sensorInfo.stringType.c_str()),
                                             env->NewStringUTF("") /* requiredPermission */,
                                             static_cast<jint>(sensorInfo.maxDelay),
                                             static_cast<jint>(sensorInfo.flags),
                                             static_cast<jint>(sensorInfo.id));
        env->SetObjectArrayElement(arr, i, info);
        env->DeleteLocalRef(info);
    }
    return arr;
}

static jboolean nativeEnableSensor(JNIEnv* env, jobject nativeImplObj, jint deviceId,
                                   jint sensorType, jint samplingPeriodUs,
                                   jint maxBatchReportLatencyUs) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    return im->getInputManager()
            ->getReader()
            .enableSensor(deviceId, static_cast<InputDeviceSensorType>(sensorType),
                          std::chrono::microseconds(samplingPeriodUs),
                          std::chrono::microseconds(maxBatchReportLatencyUs));
}

static void nativeDisableSensor(JNIEnv* env, jobject nativeImplObj, jint deviceId,
                                jint sensorType) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->getInputManager()->getReader().disableSensor(deviceId,
                                                     static_cast<InputDeviceSensorType>(
                                                             sensorType));
}

static jboolean nativeFlushSensor(JNIEnv* env, jobject nativeImplObj, jint deviceId,
                                  jint sensorType) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->getInputManager()->getReader().flushSensor(deviceId,
                                                   static_cast<InputDeviceSensorType>(sensorType));
    return im->getInputManager()->getDispatcher().flushSensor(deviceId,
                                                              static_cast<InputDeviceSensorType>(
                                                                      sensorType));
}

static void nativeCancelCurrentTouch(JNIEnv* env, jobject nativeImplObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    im->getInputManager()->getDispatcher().cancelCurrentTouch();
}

static void nativeSetPointerDisplayId(JNIEnv* env, jobject nativeImplObj, jint displayId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    im->setPointerDisplayId(displayId);
}

static jstring nativeGetBluetoothAddress(JNIEnv* env, jobject nativeImplObj, jint deviceId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    const auto address = im->getBluetoothAddress(deviceId);
    return address ? env->NewStringUTF(address->c_str()) : nullptr;
}

static void nativeSetStylusButtonMotionEventsEnabled(JNIEnv* env, jobject nativeImplObj,
                                                     jboolean enabled) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    im->setStylusButtonMotionEventsEnabled(enabled);
}

static jfloatArray nativeGetMouseCursorPosition(JNIEnv* env, jobject nativeImplObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    const auto p = im->getMouseCursorPosition();
    const std::array<float, 2> arr = {{p.x, p.y}};
    jfloatArray outArr = env->NewFloatArray(2);
    env->SetFloatArrayRegion(outArr, 0, arr.size(), arr.data());
    return outArr;
}

static void nativeSetStylusPointerIconEnabled(JNIEnv* env, jobject nativeImplObj,
                                              jboolean enabled) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    im->setStylusPointerIconEnabled(enabled);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gInputManagerMethods[] = {
        /* name, signature, funcPtr */
        {"init",
         "(Lcom/android/server/input/InputManagerService;Landroid/os/"
         "MessageQueue;)J",
         (void*)nativeInit},
        {"start", "()V", (void*)nativeStart},
        {"setDisplayViewports", "([Landroid/hardware/display/DisplayViewport;)V",
         (void*)nativeSetDisplayViewports},
        {"getScanCodeState", "(III)I", (void*)nativeGetScanCodeState},
        {"getKeyCodeState", "(III)I", (void*)nativeGetKeyCodeState},
        {"getSwitchState", "(III)I", (void*)nativeGetSwitchState},
        {"addKeyRemapping", "(III)V", (void*)nativeAddKeyRemapping},
        {"hasKeys", "(II[I[Z)Z", (void*)nativeHasKeys},
        {"getKeyCodeForKeyLocation", "(II)I", (void*)nativeGetKeyCodeForKeyLocation},
        {"createInputChannel", "(Ljava/lang/String;)Landroid/view/InputChannel;",
         (void*)nativeCreateInputChannel},
        {"createInputMonitor", "(ILjava/lang/String;I)Landroid/view/InputChannel;",
         (void*)nativeCreateInputMonitor},
        {"removeInputChannel", "(Landroid/os/IBinder;)V", (void*)nativeRemoveInputChannel},
        {"pilferPointers", "(Landroid/os/IBinder;)V", (void*)nativePilferPointers},
        {"setInputFilterEnabled", "(Z)V", (void*)nativeSetInputFilterEnabled},
        {"setInTouchMode", "(ZIIZI)Z", (void*)nativeSetInTouchMode},
        {"setMaximumObscuringOpacityForTouch", "(F)V",
         (void*)nativeSetMaximumObscuringOpacityForTouch},
        {"injectInputEvent", "(Landroid/view/InputEvent;ZIIII)I", (void*)nativeInjectInputEvent},
        {"verifyInputEvent", "(Landroid/view/InputEvent;)Landroid/view/VerifiedInputEvent;",
         (void*)nativeVerifyInputEvent},
        {"toggleCapsLock", "(I)V", (void*)nativeToggleCapsLock},
        {"displayRemoved", "(I)V", (void*)nativeDisplayRemoved},
        {"setFocusedApplication", "(ILandroid/view/InputApplicationHandle;)V",
         (void*)nativeSetFocusedApplication},
        {"setFocusedDisplay", "(I)V", (void*)nativeSetFocusedDisplay},
        {"requestPointerCapture", "(Landroid/os/IBinder;Z)V", (void*)nativeRequestPointerCapture},
        {"setInputDispatchMode", "(ZZ)V", (void*)nativeSetInputDispatchMode},
        {"setSystemUiLightsOut", "(Z)V", (void*)nativeSetSystemUiLightsOut},
        {"transferTouchFocus", "(Landroid/os/IBinder;Landroid/os/IBinder;Z)Z",
         (void*)nativeTransferTouchFocus},
        {"transferTouch", "(Landroid/os/IBinder;I)Z", (void*)nativeTransferTouch},
        {"setPointerSpeed", "(I)V", (void*)nativeSetPointerSpeed},
        {"setPointerAcceleration", "(F)V", (void*)nativeSetPointerAcceleration},
        {"setTouchpadPointerSpeed", "(I)V", (void*)nativeSetTouchpadPointerSpeed},
        {"setTouchpadNaturalScrollingEnabled", "(Z)V",
         (void*)nativeSetTouchpadNaturalScrollingEnabled},
        {"setTouchpadTapToClickEnabled", "(Z)V", (void*)nativeSetTouchpadTapToClickEnabled},
        {"setTouchpadRightClickZoneEnabled", "(Z)V", (void*)nativeSetTouchpadRightClickZoneEnabled},
        {"setShowTouches", "(Z)V", (void*)nativeSetShowTouches},
        {"setInteractive", "(Z)V", (void*)nativeSetInteractive},
        {"reloadCalibration", "()V", (void*)nativeReloadCalibration},
        {"vibrate", "(I[J[III)V", (void*)nativeVibrate},
        {"vibrateCombined", "(I[JLandroid/util/SparseArray;II)V", (void*)nativeVibrateCombined},
        {"cancelVibrate", "(II)V", (void*)nativeCancelVibrate},
        {"isVibrating", "(I)Z", (void*)nativeIsVibrating},
        {"getVibratorIds", "(I)[I", (void*)nativeGetVibratorIds},
        {"getLights", "(I)Ljava/util/List;", (void*)nativeGetLights},
        {"getLightPlayerId", "(II)I", (void*)nativeGetLightPlayerId},
        {"getLightColor", "(II)I", (void*)nativeGetLightColor},
        {"setLightPlayerId", "(III)V", (void*)nativeSetLightPlayerId},
        {"setLightColor", "(III)V", (void*)nativeSetLightColor},
        {"getBatteryCapacity", "(I)I", (void*)nativeGetBatteryCapacity},
        {"getBatteryStatus", "(I)I", (void*)nativeGetBatteryStatus},
        {"getBatteryDevicePath", "(I)Ljava/lang/String;", (void*)nativeGetBatteryDevicePath},
        {"reloadKeyboardLayouts", "()V", (void*)nativeReloadKeyboardLayouts},
        {"reloadDeviceAliases", "()V", (void*)nativeReloadDeviceAliases},
        {"sysfsNodeChanged", "(Ljava/lang/String;)V", (void*)nativeSysfsNodeChanged},
        {"dump", "()Ljava/lang/String;", (void*)nativeDump},
        {"monitor", "()V", (void*)nativeMonitor},
        {"isInputDeviceEnabled", "(I)Z", (void*)nativeIsInputDeviceEnabled},
        {"enableInputDevice", "(I)V", (void*)nativeEnableInputDevice},
        {"disableInputDevice", "(I)V", (void*)nativeDisableInputDevice},
        {"setPointerIconType", "(I)V", (void*)nativeSetPointerIconType},
        {"reloadPointerIcons", "()V", (void*)nativeReloadPointerIcons},
        {"setCustomPointerIcon", "(Landroid/view/PointerIcon;)V",
         (void*)nativeSetCustomPointerIcon},
        {"canDispatchToDisplay", "(II)Z", (void*)nativeCanDispatchToDisplay},
        {"notifyPortAssociationsChanged", "()V", (void*)nativeNotifyPortAssociationsChanged},
        {"changeUniqueIdAssociation", "()V", (void*)nativeChangeUniqueIdAssociation},
        {"changeTypeAssociation", "()V", (void*)nativeChangeTypeAssociation},
        {"changeKeyboardLayoutAssociation", "()V", (void*)changeKeyboardLayoutAssociation},
        {"setDisplayEligibilityForPointerCapture", "(IZ)V",
         (void*)nativeSetDisplayEligibilityForPointerCapture},
        {"setMotionClassifierEnabled", "(Z)V", (void*)nativeSetMotionClassifierEnabled},
        {"setKeyRepeatConfiguration", "(II)V", (void*)nativeSetKeyRepeatConfiguration},
        {"getSensorList", "(I)[Landroid/hardware/input/InputSensorInfo;",
         (void*)nativeGetSensorList},
        {"enableSensor", "(IIII)Z", (void*)nativeEnableSensor},
        {"disableSensor", "(II)V", (void*)nativeDisableSensor},
        {"flushSensor", "(II)Z", (void*)nativeFlushSensor},
        {"cancelCurrentTouch", "()V", (void*)nativeCancelCurrentTouch},
        {"setPointerDisplayId", "(I)V", (void*)nativeSetPointerDisplayId},
        {"getBluetoothAddress", "(I)Ljava/lang/String;", (void*)nativeGetBluetoothAddress},
        {"setStylusButtonMotionEventsEnabled", "(Z)V",
         (void*)nativeSetStylusButtonMotionEventsEnabled},
        {"getMouseCursorPosition", "()[F", (void*)nativeGetMouseCursorPosition},
        {"setStylusPointerIconEnabled", "(Z)V", (void*)nativeSetStylusPointerIconEnabled},
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
    int res = jniRegisterNativeMethods(env,
                                       "com/android/server/input/"
                                       "NativeInputManagerService$NativeImpl",
                                       gInputManagerMethods, NELEM(gInputManagerMethods));
    (void)res; // Faked use when LOG_NDEBUG.
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    FIND_CLASS(gNativeInputManagerServiceImpl.clazz,
               "com/android/server/input/"
               "NativeInputManagerService$NativeImpl");
    gNativeInputManagerServiceImpl.clazz =
            jclass(env->NewGlobalRef(gNativeInputManagerServiceImpl.clazz));
    gNativeInputManagerServiceImpl.mPtr =
            env->GetFieldID(gNativeInputManagerServiceImpl.clazz, "mPtr", "J");

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
    GET_METHOD_ID(gServiceClassInfo.notifyDropWindow, clazz, "notifyDropWindow",
                  "(Landroid/os/IBinder;FF)V");

    GET_METHOD_ID(gServiceClassInfo.notifySensorEvent, clazz, "notifySensorEvent", "(IIIJ[F)V");

    GET_METHOD_ID(gServiceClassInfo.notifySensorAccuracy, clazz, "notifySensorAccuracy", "(III)V");

    GET_METHOD_ID(gServiceClassInfo.notifyStylusGestureStarted, clazz, "notifyStylusGestureStarted",
                  "(IJ)V");

    GET_METHOD_ID(gServiceClassInfo.isInputMethodConnectionActive, clazz,
                  "isInputMethodConnectionActive", "()Z");

    GET_METHOD_ID(gServiceClassInfo.notifyVibratorState, clazz, "notifyVibratorState", "(IZ)V");

    GET_METHOD_ID(gServiceClassInfo.notifyNoFocusedWindowAnr, clazz, "notifyNoFocusedWindowAnr",
                  "(Landroid/view/InputApplicationHandle;)V");

    GET_METHOD_ID(gServiceClassInfo.notifyWindowUnresponsive, clazz, "notifyWindowUnresponsive",
                  "(Landroid/os/IBinder;IZLjava/lang/String;)V");

    GET_METHOD_ID(gServiceClassInfo.notifyWindowResponsive, clazz, "notifyWindowResponsive",
                  "(Landroid/os/IBinder;IZ)V");

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

    GET_METHOD_ID(gServiceClassInfo.onPointerDisplayIdChanged, clazz, "onPointerDisplayIdChanged",
                  "(IFF)V");

    GET_METHOD_ID(gServiceClassInfo.onPointerDownOutsideFocus, clazz,
            "onPointerDownOutsideFocus", "(Landroid/os/IBinder;)V");

    GET_METHOD_ID(gServiceClassInfo.getVirtualKeyQuietTimeMillis, clazz,
            "getVirtualKeyQuietTimeMillis", "()I");

    GET_STATIC_METHOD_ID(gServiceClassInfo.getExcludedDeviceNames, clazz,
            "getExcludedDeviceNames", "()[Ljava/lang/String;");

    GET_METHOD_ID(gServiceClassInfo.getInputPortAssociations, clazz,
            "getInputPortAssociations", "()[Ljava/lang/String;");

    GET_METHOD_ID(gServiceClassInfo.getInputUniqueIdAssociations, clazz,
                  "getInputUniqueIdAssociations", "()[Ljava/lang/String;");

    GET_METHOD_ID(gServiceClassInfo.getDeviceTypeAssociations, clazz, "getDeviceTypeAssociations",
                  "()[Ljava/lang/String;");

    GET_METHOD_ID(gServiceClassInfo.getKeyboardLayoutAssociations, clazz,
                  "getKeyboardLayoutAssociations", "()[Ljava/lang/String;");

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

    GET_METHOD_ID(gServiceClassInfo.getKeyboardLayoutOverlay, clazz, "getKeyboardLayoutOverlay",
                  "(Landroid/hardware/input/InputDeviceIdentifier;Ljava/lang/String;Ljava/lang/"
                  "String;)[Ljava/lang/String;");

    GET_METHOD_ID(gServiceClassInfo.getDeviceAlias, clazz,
            "getDeviceAlias", "(Ljava/lang/String;)Ljava/lang/String;");

    GET_METHOD_ID(gServiceClassInfo.getTouchCalibrationForInputDevice, clazz,
            "getTouchCalibrationForInputDevice",
            "(Ljava/lang/String;I)Landroid/hardware/input/TouchCalibration;");

    GET_METHOD_ID(gServiceClassInfo.getContextForDisplay, clazz, "getContextForDisplay",
                  "(I)Landroid/content/Context;");

    GET_METHOD_ID(gServiceClassInfo.getParentSurfaceForPointers, clazz,
                  "getParentSurfaceForPointers", "(I)J");

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

    // Light
    FIND_CLASS(gLightClassInfo.clazz, "android/hardware/lights/Light");
    gLightClassInfo.clazz = jclass(env->NewGlobalRef(gLightClassInfo.clazz));
    GET_METHOD_ID(gLightClassInfo.constructor, gLightClassInfo.clazz, "<init>",
                  "(ILjava/lang/String;III[I)V");

    gLightClassInfo.clazz = jclass(env->NewGlobalRef(gLightClassInfo.clazz));
    gLightClassInfo.lightTypeInput =
            env->GetStaticFieldID(gLightClassInfo.clazz, "LIGHT_TYPE_INPUT", "I");
    gLightClassInfo.lightTypePlayerId =
            env->GetStaticFieldID(gLightClassInfo.clazz, "LIGHT_TYPE_PLAYER_ID", "I");
    gLightClassInfo.lightTypeKeyboardBacklight =
            env->GetStaticFieldID(gLightClassInfo.clazz, "LIGHT_TYPE_KEYBOARD_BACKLIGHT", "I");
    gLightClassInfo.lightCapabilityBrightness =
            env->GetStaticFieldID(gLightClassInfo.clazz, "LIGHT_CAPABILITY_BRIGHTNESS", "I");
    gLightClassInfo.lightCapabilityColorRgb =
            env->GetStaticFieldID(gLightClassInfo.clazz, "LIGHT_CAPABILITY_COLOR_RGB", "I");

    // ArrayList
    FIND_CLASS(gArrayListClassInfo.clazz, "java/util/ArrayList");
    gArrayListClassInfo.clazz = jclass(env->NewGlobalRef(gArrayListClassInfo.clazz));
    GET_METHOD_ID(gArrayListClassInfo.constructor, gArrayListClassInfo.clazz, "<init>", "()V");
    GET_METHOD_ID(gArrayListClassInfo.add, gArrayListClassInfo.clazz, "add",
                  "(Ljava/lang/Object;)Z");

    // SparseArray
    FIND_CLASS(gSparseArrayClassInfo.clazz, "android/util/SparseArray");
    gSparseArrayClassInfo.clazz = jclass(env->NewGlobalRef(gSparseArrayClassInfo.clazz));
    GET_METHOD_ID(gSparseArrayClassInfo.constructor, gSparseArrayClassInfo.clazz, "<init>", "()V");
    GET_METHOD_ID(gSparseArrayClassInfo.keyAt, gSparseArrayClassInfo.clazz, "keyAt", "(I)I");
    GET_METHOD_ID(gSparseArrayClassInfo.valueAt, gSparseArrayClassInfo.clazz, "valueAt",
                  "(I)Ljava/lang/Object;");
    GET_METHOD_ID(gSparseArrayClassInfo.size, gSparseArrayClassInfo.clazz, "size", "()I");
    // InputSensorInfo
    // android.hardware.input.InputDeviceSensorInfo
    FIND_CLASS(clazz, "android/hardware/input/InputSensorInfo");
    gInputSensorInfo.clazz = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));

    GET_FIELD_ID(gInputSensorInfo.name, gInputSensorInfo.clazz, "mName", "Ljava/lang/String;");
    GET_FIELD_ID(gInputSensorInfo.vendor, gInputSensorInfo.clazz, "mVendor", "Ljava/lang/String;");
    GET_FIELD_ID(gInputSensorInfo.version, gInputSensorInfo.clazz, "mVersion", "I");
    GET_FIELD_ID(gInputSensorInfo.handle, gInputSensorInfo.clazz, "mHandle", "I");
    GET_FIELD_ID(gInputSensorInfo.maxRange, gInputSensorInfo.clazz, "mMaxRange", "F");
    GET_FIELD_ID(gInputSensorInfo.resolution, gInputSensorInfo.clazz, "mResolution", "F");
    GET_FIELD_ID(gInputSensorInfo.power, gInputSensorInfo.clazz, "mPower", "F");
    GET_FIELD_ID(gInputSensorInfo.minDelay, gInputSensorInfo.clazz, "mMinDelay", "I");
    GET_FIELD_ID(gInputSensorInfo.fifoReservedEventCount, gInputSensorInfo.clazz,
                 "mFifoReservedEventCount", "I");
    GET_FIELD_ID(gInputSensorInfo.fifoMaxEventCount, gInputSensorInfo.clazz, "mFifoMaxEventCount",
                 "I");
    GET_FIELD_ID(gInputSensorInfo.stringType, gInputSensorInfo.clazz, "mStringType",
                 "Ljava/lang/String;");
    GET_FIELD_ID(gInputSensorInfo.requiredPermission, gInputSensorInfo.clazz, "mRequiredPermission",
                 "Ljava/lang/String;");
    GET_FIELD_ID(gInputSensorInfo.maxDelay, gInputSensorInfo.clazz, "mMaxDelay", "I");
    GET_FIELD_ID(gInputSensorInfo.flags, gInputSensorInfo.clazz, "mFlags", "I");
    GET_FIELD_ID(gInputSensorInfo.type, gInputSensorInfo.clazz, "mType", "I");
    GET_FIELD_ID(gInputSensorInfo.id, gInputSensorInfo.clazz, "mId", "I");

    GET_METHOD_ID(gInputSensorInfo.init, gInputSensorInfo.clazz, "<init>", "()V");

    return 0;
}

} /* namespace android */
