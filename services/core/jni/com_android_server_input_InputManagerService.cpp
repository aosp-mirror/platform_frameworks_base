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

#include <android-base/logging.h>
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
#include <include/gestures.h>
#include <input/Input.h>
#include <input/PointerController.h>
#include <input/PrintTools.h>
#include <input/SpriteController.h>
#include <inputflinger/InputManager.h>
#include <limits.h>
#include <nativehelper/ScopedLocalFrame.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include <server_configurable_flags/get_flags.h>
#include <ui/LogicalDisplayId.h>
#include <ui/Region.h>
#include <utils/Log.h>
#include <utils/Looper.h>
#include <utils/Trace.h>
#include <utils/threads.h>

#include <atomic>
#include <cinttypes>
#include <map>
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

static const bool ENABLE_INPUT_FILTER_RUST = input_flags::enable_input_filter_rust_impl();

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
    jmethodID notifyInputDevicesChanged;
    jmethodID notifyTouchpadHardwareState;
    jmethodID notifyTouchpadGestureInfo;
    jmethodID notifySwitch;
    jmethodID notifyInputChannelBroken;
    jmethodID notifyNoFocusedWindowAnr;
    jmethodID notifyWindowUnresponsive;
    jmethodID notifyWindowResponsive;
    jmethodID notifyFocusChanged;
    jmethodID notifySensorEvent;
    jmethodID notifySensorAccuracy;
    jmethodID notifyStickyModifierStateChanged;
    jmethodID notifyStylusGestureStarted;
    jmethodID notifyVibratorState;
    jmethodID filterInputEvent;
    jmethodID interceptKeyBeforeQueueing;
    jmethodID interceptMotionBeforeQueueingNonInteractive;
    jmethodID interceptKeyBeforeDispatching;
    jmethodID dispatchUnhandledKey;
    jmethodID onPointerDownOutsideFocus;
    jmethodID getVirtualKeyQuietTimeMillis;
    jmethodID getExcludedDeviceNames;
    jmethodID getInputPortAssociations;
    jmethodID getInputUniqueIdAssociationsByPort;
    jmethodID getInputUniqueIdAssociationsByDescriptor;
    jmethodID getDeviceTypeAssociations;
    jmethodID getKeyboardLayoutAssociations;
    jmethodID getHoverTapTimeout;
    jmethodID getHoverTapSlop;
    jmethodID getDoubleTapTimeout;
    jmethodID getLongPressTimeout;
    jmethodID getPointerLayer;
    jmethodID getLoadedPointerIcon;
    jmethodID getKeyboardLayoutOverlay;
    jmethodID getDeviceAlias;
    jmethodID getTouchCalibrationForInputDevice;
    jmethodID notifyDropWindow;
    jmethodID getParentSurfaceForPointers;
} gServiceClassInfo;

static struct {
    jclass clazz;
    // fields
    jfieldID timestamp;
    jfieldID buttonsDown;
    jfieldID fingerCount;
    jfieldID touchCount;
    jfieldID fingerStates;
    // methods
    jmethodID init;
} gTouchpadHardwareStateClassInfo;

static struct {
    jclass clazz;
    // fields
    jfieldID touchMajor;
    jfieldID touchMinor;
    jfieldID widthMajor;
    jfieldID widthMinor;
    jfieldID pressure;
    jfieldID orientation;
    jfieldID positionX;
    jfieldID positionY;
    jfieldID trackingId;
    // methods
    jmethodID init;
} gTouchpadFingerStateClassInfo;

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
    jfieldID lightTypeKeyboardMicMute;
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

static struct TouchpadHardwarePropertiesOffsets {
    jclass clazz;
    jmethodID constructor;
    jfieldID left;
    jfieldID top;
    jfieldID right;
    jfieldID bottom;
    jfieldID resX;
    jfieldID resY;
    jfieldID orientationMinimum;
    jfieldID orientationMaximum;
    jfieldID maxFingerCount;
    jfieldID isButtonPad;
    jfieldID isHapticPad;
    jfieldID reportsPressure;
} gTouchpadHardwarePropertiesOffsets;

// --- Global functions ---

template<typename T>
inline static T min(const T& a, const T& b) {
    return a < b ? a : b;
}

template<typename T>
inline static T max(const T& a, const T& b) {
    return a > b ? a : b;
}

static SpriteIcon toSpriteIcon(PointerIcon pointerIcon) {
    // As a minor optimization, do not make a copy of the PointerIcon bitmap here. The loaded
    // PointerIcons are only cached by InputManagerService in java, so we can safely assume they
    // will not be modified. This is safe because the native bitmap object holds a strong reference
    // to the underlying bitmap, so even if the java object is released, we will still have access
    // to it.
    return SpriteIcon(pointerIcon.bitmap, pointerIcon.style, pointerIcon.hotSpotX,
                      pointerIcon.hotSpotY, pointerIcon.drawNativeDropShadow);
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
                           public virtual PointerChoreographerPolicyInterface,
                           public virtual InputFilterPolicyInterface {
protected:
    virtual ~NativeInputManager();

public:
    NativeInputManager(jobject serviceObj, const sp<Looper>& looper);

    inline sp<InputManagerInterface> getInputManager() const { return mInputManager; }

    void dump(std::string& dump);

    void setDisplayViewports(JNIEnv* env, jobjectArray viewportObjArray);

    base::Result<std::unique_ptr<InputChannel>> createInputChannel(const std::string& name);
    base::Result<std::unique_ptr<InputChannel>> createInputMonitor(ui::LogicalDisplayId displayId,
                                                                   const std::string& name,
                                                                   gui::Pid pid);
    status_t removeInputChannel(const sp<IBinder>& connectionToken);
    status_t pilferPointers(const sp<IBinder>& token);

    void displayRemoved(JNIEnv* env, ui::LogicalDisplayId displayId);
    void setFocusedApplication(JNIEnv* env, ui::LogicalDisplayId displayId,
                               jobject applicationHandleObj);
    void setFocusedDisplay(ui::LogicalDisplayId displayId);
    void setMinTimeBetweenUserActivityPokes(int64_t intervalMillis);
    void setInputDispatchMode(bool enabled, bool frozen);
    void setSystemUiLightsOut(bool lightsOut);
    void setPointerDisplayId(ui::LogicalDisplayId displayId);
    int32_t getMousePointerSpeed();
    void setPointerSpeed(int32_t speed);
    void setMousePointerAccelerationEnabled(ui::LogicalDisplayId displayId, bool enabled);
    void setTouchpadPointerSpeed(int32_t speed);
    void setTouchpadNaturalScrollingEnabled(bool enabled);
    void setTouchpadTapToClickEnabled(bool enabled);
    void setTouchpadTapDraggingEnabled(bool enabled);
    void setShouldNotifyTouchpadHardwareState(bool enabled);
    void setTouchpadRightClickZoneEnabled(bool enabled);
    void setInputDeviceEnabled(uint32_t deviceId, bool enabled);
    void setShowTouches(bool enabled);
    void setNonInteractiveDisplays(const std::set<ui::LogicalDisplayId>& displayIds);
    void reloadCalibration();
    void reloadPointerIcons();
    void requestPointerCapture(const sp<IBinder>& windowToken, bool enabled);
    bool setPointerIcon(std::variant<std::unique_ptr<SpriteIcon>, PointerIconStyle> icon,
                        ui::LogicalDisplayId displayId, DeviceId deviceId, int32_t pointerId,
                        const sp<IBinder>& inputToken);
    void setPointerIconVisibility(ui::LogicalDisplayId displayId, bool visible);
    void setMotionClassifierEnabled(bool enabled);
    std::optional<std::string> getBluetoothAddress(int32_t deviceId);
    void setStylusButtonMotionEventsEnabled(bool enabled);
    FloatPoint getMouseCursorPosition(ui::LogicalDisplayId displayId);
    void setStylusPointerIconEnabled(bool enabled);
    void setInputMethodConnectionIsActive(bool isActive);
    void setKeyRemapping(const std::map<int32_t, int32_t>& keyRemapping);

    /* --- InputReaderPolicyInterface implementation --- */

    void getReaderConfiguration(InputReaderConfiguration* outConfig) override;
    void notifyInputDevicesChanged(const std::vector<InputDeviceInfo>& inputDevices) override;
    void notifyTouchpadHardwareState(const SelfContainedHardwareState& schs,
                                     int32_t deviceId) override;
    void notifyTouchpadGestureInfo(enum GestureType type, int32_t deviceId) override;
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
            ui::LogicalDisplayId associatedDisplayId) override;

    /* --- InputDispatcherPolicyInterface implementation --- */

    void notifySwitch(nsecs_t when, uint32_t switchValues, uint32_t switchMask,
                      uint32_t policyFlags) override;
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
    void interceptMotionBeforeQueueing(ui::LogicalDisplayId displayId, uint32_t source,
                                       int32_t action, nsecs_t when,
                                       uint32_t& policyFlags) override;
    nsecs_t interceptKeyBeforeDispatching(const sp<IBinder>& token, const KeyEvent& keyEvent,
                                          uint32_t policyFlags) override;
    std::optional<KeyEvent> dispatchUnhandledKey(const sp<IBinder>& token, const KeyEvent& keyEvent,
                                                 uint32_t policyFlags) override;
    void pokeUserActivity(nsecs_t eventTime, int32_t eventType,
                          ui::LogicalDisplayId displayId) override;
    void onPointerDownOutsideFocus(const sp<IBinder>& touchedToken) override;
    void setPointerCapture(const PointerCaptureRequest& request) override;
    void notifyDropWindow(const sp<IBinder>& token, float x, float y) override;
    void notifyDeviceInteraction(int32_t deviceId, nsecs_t timestamp,
                                 const std::set<gui::Uid>& uids) override;
    void notifyFocusedDisplayChanged(ui::LogicalDisplayId displayId) override;

    /* --- PointerControllerPolicyInterface implementation --- */

    virtual void loadPointerIcon(SpriteIcon* icon, ui::LogicalDisplayId displayId);
    virtual void loadPointerResources(PointerResources* outResources,
                                      ui::LogicalDisplayId displayId);
    virtual void loadAdditionalMouseResources(
            std::map<PointerIconStyle, SpriteIcon>* outResources,
            std::map<PointerIconStyle, PointerAnimation>* outAnimationResources,
            ui::LogicalDisplayId displayId);
    virtual PointerIconStyle getDefaultPointerIconId();
    virtual PointerIconStyle getDefaultStylusIconId();
    virtual PointerIconStyle getCustomPointerIconId();

    /* --- PointerChoreographerPolicyInterface implementation --- */
    std::shared_ptr<PointerControllerInterface> createPointerController(
            PointerControllerInterface::ControllerType type) override;
    void notifyPointerDisplayIdChanged(ui::LogicalDisplayId displayId,
                                       const FloatPoint& position) override;
    void notifyMouseCursorFadedOnTyping() override;

    /* --- InputFilterPolicyInterface implementation --- */
    void notifyStickyModifierStateChanged(uint32_t modifierState,
                                          uint32_t lockedModifierState) override;

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

        // Displays on which its associated mice will have pointer acceleration disabled.
        std::set<ui::LogicalDisplayId> displaysWithMousePointerAccelerationDisabled{};

        // True if pointer gestures are enabled.
        bool pointerGesturesEnabled{true};

        // The latest request to enable or disable Pointer Capture.
        PointerCaptureRequest pointerCaptureRequest{};

        // Sprite controller singleton, created on first use.
        std::shared_ptr<SpriteController> spriteController{};

        // The list of PointerControllers created and managed by the PointerChoreographer.
        std::list<std::weak_ptr<PointerController>> pointerControllers{};

        // Input devices to be disabled
        std::set<int32_t> disabledInputDevices{};

        // Associated Pointer controller display.
        ui::LogicalDisplayId pointerDisplayId{ui::LogicalDisplayId::DEFAULT};

        // True if stylus button reporting through motion events is enabled.
        bool stylusButtonMotionEventsEnabled{true};

        // The touchpad pointer speed, as a number from -7 (slowest) to 7 (fastest).
        int32_t touchpadPointerSpeed{0};

        // True to invert the touchpad scrolling direction, so that moving two fingers downwards on
        // the touchpad scrolls the content upwards.
        bool touchpadNaturalScrollingEnabled{true};

        // True to enable tap-to-click on touchpads.
        bool touchpadTapToClickEnabled{true};

        // True to enable tap dragging on touchpads.
        bool touchpadTapDraggingEnabled{false};

        // True if hardware state update notifications should be sent to the policy.
        bool shouldNotifyTouchpadHardwareState{false};

        // True to enable a zone on the right-hand side of touchpads where clicks will be turned
        // into context (a.k.a. "right") clicks.
        bool touchpadRightClickZoneEnabled{false};

        // True if a pointer icon should be shown for stylus pointers.
        bool stylusPointerIconEnabled{false};

        // True if there is an active input method connection.
        bool isInputMethodConnectionActive{false};

        // Keycodes to be remapped.
        std::map<int32_t /* fromKeyCode */, int32_t /* toKeyCode */> keyRemapping{};

        // Displays which are non-interactive.
        std::set<ui::LogicalDisplayId> nonInteractiveDisplays;
    } mLocked GUARDED_BY(mLock);

    void updateInactivityTimeoutLocked();
    void handleInterceptActions(jint wmActions, nsecs_t when, uint32_t& policyFlags);
    void ensureSpriteControllerLocked();
    sp<SurfaceControl> getParentSurfaceForPointers(ui::LogicalDisplayId displayId);
    static bool checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName);
    template <typename T>
    std::unordered_map<std::string, T> readMapFromInterleavedJavaArray(
            jmethodID method, const char* methodName,
            std::function<T(std::string)> opOnValue = [](auto&& v) { return std::move(v); });

    void forEachPointerControllerLocked(std::function<void(PointerController&)> apply)
            REQUIRES(mLock);
    PointerIcon loadPointerIcon(JNIEnv* env, ui::LogicalDisplayId displayId, PointerIconStyle type);
    bool isDisplayInteractive(ui::LogicalDisplayId displayId);

    static inline JNIEnv* jniEnv() { return AndroidRuntime::getJNIEnv(); }
};

NativeInputManager::NativeInputManager(jobject serviceObj, const sp<Looper>& looper)
      : mLooper(looper) {
    JNIEnv* env = jniEnv();

    mServiceObj = env->NewGlobalRef(serviceObj);

    InputManager* im = new InputManager(this, *this, *this, *this);
    mInputManager = im;
    defaultServiceManager()->addService(String16("inputflinger"), im);
}

NativeInputManager::~NativeInputManager() {
    JNIEnv* env = jniEnv();

    env->DeleteGlobalRef(mServiceObj);
}

void NativeInputManager::dump(std::string& dump) {
    dump += "Input Manager State:\n";
    { // acquire lock
        std::scoped_lock _l(mLock);
        auto logicalDisplayIdToString = [](const ui::LogicalDisplayId& displayId) {
            return std::to_string(displayId.val());
        };
        dump += StringPrintf(INDENT "Display not interactive: %s\n",
                             dumpSet(mLocked.nonInteractiveDisplays, streamableToString).c_str());
        dump += StringPrintf(INDENT "System UI Lights Out: %s\n",
                             toString(mLocked.systemUiLightsOut));
        dump += StringPrintf(INDENT "Pointer Speed: %" PRId32 "\n", mLocked.pointerSpeed);
        dump += StringPrintf(INDENT "Display with Mouse Pointer Acceleration Disabled: %s\n",
                             dumpSet(mLocked.displaysWithMousePointerAccelerationDisabled,
                                     streamableToString)
                                     .c_str());
        dump += StringPrintf(INDENT "Pointer Gestures Enabled: %s\n",
                             toString(mLocked.pointerGesturesEnabled));
        dump += StringPrintf(INDENT "Pointer Capture: %s, seq=%" PRIu32 "\n",
                             mLocked.pointerCaptureRequest.isEnable() ? "Enabled" : "Disabled",
                             mLocked.pointerCaptureRequest.seq);
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

    mInputManager->getChoreographer().setDisplayViewports(viewports);
    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::DISPLAY_INFO);
}

base::Result<std::unique_ptr<InputChannel>> NativeInputManager::createInputChannel(
        const std::string& name) {
    ATRACE_CALL();
    return mInputManager->getDispatcher().createInputChannel(name);
}

base::Result<std::unique_ptr<InputChannel>> NativeInputManager::createInputMonitor(
        ui::LogicalDisplayId displayId, const std::string& name, gui::Pid pid) {
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
    outConfig->inputPortToDisplayPortAssociations.clear();
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
            outConfig->inputPortToDisplayPortAssociations.insert({inputPort, displayPort});
        }
        env->DeleteLocalRef(portAssociations);
    }

    outConfig->inputPortToDisplayUniqueIdAssociations = readMapFromInterleavedJavaArray<
            std::string>(gServiceClassInfo.getInputUniqueIdAssociationsByPort,
                         "getInputUniqueIdAssociationsByPort");

    outConfig->inputDeviceDescriptorToDisplayUniqueIdAssociations = readMapFromInterleavedJavaArray<
            std::string>(gServiceClassInfo.getInputUniqueIdAssociationsByDescriptor,
                         "getInputUniqueIdAssociationsByDescriptor");

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

        outConfig->mousePointerSpeed = mLocked.pointerSpeed;
        outConfig->displaysWithMousePointerAccelerationDisabled =
                mLocked.displaysWithMousePointerAccelerationDisabled;
        outConfig->pointerVelocityControlParameters.scale =
                exp2f(mLocked.pointerSpeed * POINTER_SPEED_EXPONENT);
        outConfig->pointerVelocityControlParameters.acceleration =
                mLocked.displaysWithMousePointerAccelerationDisabled.count(
                        mLocked.pointerDisplayId) == 0
                ? android::os::IInputConstants::DEFAULT_POINTER_ACCELERATION
                : 1;
        outConfig->pointerGesturesEnabled = mLocked.pointerGesturesEnabled;

        outConfig->pointerCaptureRequest = mLocked.pointerCaptureRequest;

        outConfig->setDisplayViewports(mLocked.viewports);

        outConfig->defaultPointerDisplayId = mLocked.pointerDisplayId;

        outConfig->touchpadPointerSpeed = mLocked.touchpadPointerSpeed;
        outConfig->touchpadNaturalScrollingEnabled = mLocked.touchpadNaturalScrollingEnabled;
        outConfig->touchpadTapToClickEnabled = mLocked.touchpadTapToClickEnabled;
        outConfig->touchpadTapDraggingEnabled = mLocked.touchpadTapDraggingEnabled;
        outConfig->shouldNotifyTouchpadHardwareState = mLocked.shouldNotifyTouchpadHardwareState;
        outConfig->touchpadRightClickZoneEnabled = mLocked.touchpadRightClickZoneEnabled;

        outConfig->disabledDevices = mLocked.disabledInputDevices;

        outConfig->stylusButtonMotionEventsEnabled = mLocked.stylusButtonMotionEventsEnabled;

        outConfig->stylusPointerIconEnabled = mLocked.stylusPointerIconEnabled;

        outConfig->keyRemapping = mLocked.keyRemapping;
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

PointerIcon NativeInputManager::loadPointerIcon(JNIEnv* env, ui::LogicalDisplayId displayId,
                                                PointerIconStyle type) {
    if (type == PointerIconStyle::TYPE_CUSTOM) {
        LOG(FATAL) << __func__ << ": Cannot load non-system icon type";
    }
    if (type == PointerIconStyle::TYPE_NULL) {
        return PointerIcon();
    }

    ScopedLocalRef<jobject> pointerIconObj(env,
                                           env->CallObjectMethod(mServiceObj,
                                                                 gServiceClassInfo
                                                                         .getLoadedPointerIcon,
                                                                 displayId, type));
    if (checkAndClearExceptionFromCallback(env, "getLoadedPointerIcon")) {
        LOG(FATAL) << __func__ << ": Failed to load pointer icon";
    }

    return android_view_PointerIcon_toNative(env, pointerIconObj.get());
}

std::shared_ptr<PointerControllerInterface> NativeInputManager::createPointerController(
        PointerControllerInterface::ControllerType type) {
    std::scoped_lock _l(mLock);
    ensureSpriteControllerLocked();
    std::shared_ptr<PointerController> pc =
            PointerController::create(this, mLooper, *mLocked.spriteController, type);
    mLocked.pointerControllers.emplace_back(pc);
    return pc;
}

void NativeInputManager::notifyPointerDisplayIdChanged(ui::LogicalDisplayId pointerDisplayId,
                                                       const FloatPoint& position) {
    // Notify the Reader so that devices can be reconfigured.
    { // acquire lock
        std::scoped_lock _l(mLock);
        if (mLocked.pointerDisplayId == pointerDisplayId) {
            return;
        }
        mLocked.pointerDisplayId = pointerDisplayId;
        ALOGI("%s: pointer displayId set to: %s", __func__, pointerDisplayId.toString().c_str());
    } // release lock
    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::DISPLAY_INFO);
}

void NativeInputManager::notifyMouseCursorFadedOnTyping() {
    mInputManager->getReader().notifyMouseCursorFadedOnTyping();
}

void NativeInputManager::notifyStickyModifierStateChanged(uint32_t modifierState,
                                                          uint32_t lockedModifierState) {
    JNIEnv* env = jniEnv();
    env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifyStickyModifierStateChanged,
                        modifierState, lockedModifierState);
    checkAndClearExceptionFromCallback(env, "notifyStickyModifierStateChanged");
}

sp<SurfaceControl> NativeInputManager::getParentSurfaceForPointers(ui::LogicalDisplayId displayId) {
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
            std::make_shared<SpriteController>(mLooper, layer,
                                               [this](ui::LogicalDisplayId displayId) {
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

static ScopedLocalRef<jobject> createTouchpadHardwareStateObj(
        JNIEnv* env, const SelfContainedHardwareState& schs) {
    ScopedLocalRef<jobject>
            touchpadHardwareStateObj(env,
                                     env->NewObject(gTouchpadHardwareStateClassInfo.clazz,
                                                    gTouchpadHardwareStateClassInfo.init, ""));

    if (!touchpadHardwareStateObj.get()) {
        return ScopedLocalRef<jobject>(env);
    }

    env->SetFloatField(touchpadHardwareStateObj.get(), gTouchpadHardwareStateClassInfo.timestamp,
                       static_cast<jfloat>(schs.state.timestamp));
    env->SetIntField(touchpadHardwareStateObj.get(), gTouchpadHardwareStateClassInfo.buttonsDown,
                     static_cast<jint>(schs.state.buttons_down));
    env->SetIntField(touchpadHardwareStateObj.get(), gTouchpadHardwareStateClassInfo.fingerCount,
                     static_cast<jint>(schs.state.finger_cnt));
    env->SetIntField(touchpadHardwareStateObj.get(), gTouchpadHardwareStateClassInfo.touchCount,
                     static_cast<jint>(schs.state.touch_cnt));

    size_t count = schs.fingers.size();
    ScopedLocalRef<jobjectArray>
            fingerStateObjArray(env,
                                env->NewObjectArray(count, gTouchpadFingerStateClassInfo.clazz,
                                                    nullptr));

    if (!fingerStateObjArray.get()) {
        return ScopedLocalRef<jobject>(env);
    }

    for (size_t i = 0; i < count; i++) {
        ScopedLocalRef<jobject> fingerStateObj(env,
                                               env->NewObject(gTouchpadFingerStateClassInfo.clazz,
                                                              gTouchpadFingerStateClassInfo.init,
                                                              ""));
        if (!fingerStateObj.get()) {
            return ScopedLocalRef<jobject>(env);
        }
        env->SetFloatField(fingerStateObj.get(), gTouchpadFingerStateClassInfo.touchMajor,
                           static_cast<jfloat>(schs.fingers[i].touch_major));
        env->SetFloatField(fingerStateObj.get(), gTouchpadFingerStateClassInfo.touchMinor,
                           static_cast<jfloat>(schs.fingers[i].touch_minor));
        env->SetFloatField(fingerStateObj.get(), gTouchpadFingerStateClassInfo.widthMajor,
                           static_cast<jfloat>(schs.fingers[i].width_major));
        env->SetFloatField(fingerStateObj.get(), gTouchpadFingerStateClassInfo.widthMinor,
                           static_cast<jfloat>(schs.fingers[i].width_minor));
        env->SetFloatField(fingerStateObj.get(), gTouchpadFingerStateClassInfo.pressure,
                           static_cast<jfloat>(schs.fingers[i].pressure));
        env->SetFloatField(fingerStateObj.get(), gTouchpadFingerStateClassInfo.orientation,
                           static_cast<jfloat>(schs.fingers[i].orientation));
        env->SetFloatField(fingerStateObj.get(), gTouchpadFingerStateClassInfo.positionX,
                           static_cast<jfloat>(schs.fingers[i].position_x));
        env->SetFloatField(fingerStateObj.get(), gTouchpadFingerStateClassInfo.positionY,
                           static_cast<jfloat>(schs.fingers[i].position_y));
        env->SetIntField(fingerStateObj.get(), gTouchpadFingerStateClassInfo.trackingId,
                         static_cast<jint>(schs.fingers[i].tracking_id));

        env->SetObjectArrayElement(fingerStateObjArray.get(), i, fingerStateObj.get());
    }

    env->SetObjectField(touchpadHardwareStateObj.get(),
                        gTouchpadHardwareStateClassInfo.fingerStates, fingerStateObjArray.get());

    return touchpadHardwareStateObj;
}

void NativeInputManager::notifyTouchpadHardwareState(const SelfContainedHardwareState& schs,
                                                     int32_t deviceId) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();

    ScopedLocalRef<jobject> hardwareStateObj = createTouchpadHardwareStateObj(env, schs);

    if (hardwareStateObj.get()) {
        env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifyTouchpadHardwareState,
                            hardwareStateObj.get(), deviceId);
    }

    checkAndClearExceptionFromCallback(env, "notifyTouchpadHardwareState");
}

void NativeInputManager::notifyTouchpadGestureInfo(enum GestureType type, int32_t deviceId) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();

    env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifyTouchpadGestureInfo, type, deviceId);

    checkAndClearExceptionFromCallback(env, "notifyTouchpadGestureInfo");
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
        env->CallVoidMethod(mServiceObj, gServiceClassInfo.notifyInputChannelBroken, tokenObj);
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

void NativeInputManager::notifyFocusedDisplayChanged(ui::LogicalDisplayId displayId) {
    mInputManager->getChoreographer().setFocusedDisplay(displayId);
}

void NativeInputManager::displayRemoved(JNIEnv* env, ui::LogicalDisplayId displayId) {
    mInputManager->getDispatcher().displayRemoved(displayId);
}

void NativeInputManager::setFocusedApplication(JNIEnv* env, ui::LogicalDisplayId displayId,
                                               jobject applicationHandleObj) {
    if (!applicationHandleObj) {
        return;
    }
    std::shared_ptr<InputApplicationHandle> applicationHandle =
            android_view_InputApplicationHandle_getHandle(env, applicationHandleObj);
    applicationHandle->updateInfo();
    mInputManager->getDispatcher().setFocusedApplication(displayId, applicationHandle);
}

void NativeInputManager::setFocusedDisplay(ui::LogicalDisplayId displayId) {
    mInputManager->getDispatcher().setFocusedDisplay(displayId);
}

void NativeInputManager::setMinTimeBetweenUserActivityPokes(int64_t intervalMillis) {
    mInputManager->getDispatcher().setMinTimeBetweenUserActivityPokes(
            std::chrono::milliseconds(intervalMillis));
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

void NativeInputManager::setPointerDisplayId(ui::LogicalDisplayId displayId) {
    mInputManager->getChoreographer().setDefaultMouseDisplayId(displayId);
}

int32_t NativeInputManager::getMousePointerSpeed() {
    std::scoped_lock _l(mLock);
    return mLocked.pointerSpeed;
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

void NativeInputManager::setMousePointerAccelerationEnabled(ui::LogicalDisplayId displayId,
                                                            bool enabled) {
    { // acquire lock
        std::scoped_lock _l(mLock);

        const bool oldEnabled =
                mLocked.displaysWithMousePointerAccelerationDisabled.count(displayId) == 0;
        if (oldEnabled == enabled) {
            return;
        }

        ALOGI("Setting mouse pointer acceleration to %s on display %s", toString(enabled),
              displayId.toString().c_str());
        if (enabled) {
            mLocked.displaysWithMousePointerAccelerationDisabled.erase(displayId);
        } else {
            mLocked.displaysWithMousePointerAccelerationDisabled.emplace(displayId);
        }
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

void NativeInputManager::setTouchpadTapDraggingEnabled(bool enabled) {
    { // acquire lock
        std::scoped_lock _l(mLock);

        if (mLocked.touchpadTapDraggingEnabled == enabled) {
            return;
        }

        ALOGI("Setting touchpad tap dragging to %s.", toString(enabled));
        mLocked.touchpadTapDraggingEnabled = enabled;
    } // release lock

    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::TOUCHPAD_SETTINGS);
}

void NativeInputManager::setShouldNotifyTouchpadHardwareState(bool enabled) {
    { // acquire lock
        std::scoped_lock _l(mLock);

        if (mLocked.shouldNotifyTouchpadHardwareState == enabled) {
            return;
        }

        ALOGI("Should touchpad hardware state be notified: %s.", toString(enabled));
        mLocked.shouldNotifyTouchpadHardwareState = enabled;
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
    mInputManager->getChoreographer().setShowTouchesEnabled(enabled);
}

void NativeInputManager::requestPointerCapture(const sp<IBinder>& windowToken, bool enabled) {
    mInputManager->getDispatcher().requestPointerCapture(windowToken, enabled);
}

void NativeInputManager::setNonInteractiveDisplays(
        const std::set<ui::LogicalDisplayId>& displayIds) {
    std::scoped_lock _l(mLock);
    mLocked.nonInteractiveDisplays = displayIds;
}

void NativeInputManager::reloadCalibration() {
    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::TOUCH_AFFINE_TRANSFORMATION);
}

void NativeInputManager::reloadPointerIcons() {
    std::scoped_lock _l(mLock);
    forEachPointerControllerLocked([](PointerController& pc) { pc.reloadPointerResources(); });
}

bool NativeInputManager::setPointerIcon(
        std::variant<std::unique_ptr<SpriteIcon>, PointerIconStyle> icon,
        ui::LogicalDisplayId displayId, DeviceId deviceId, int32_t pointerId,
        const sp<IBinder>& inputToken) {
    if (!mInputManager->getDispatcher().isPointerInWindow(inputToken, displayId, deviceId,
                                                          pointerId)) {
        LOG(WARNING) << "Attempted to change the pointer icon for deviceId " << deviceId
                     << " on display " << displayId << " from input token " << inputToken.get()
                     << ", but the pointer is not in the window.";
        return false;
    }

    return mInputManager->getChoreographer().setPointerIcon(std::move(icon), displayId, deviceId);
}

void NativeInputManager::setPointerIconVisibility(ui::LogicalDisplayId displayId, bool visible) {
    mInputManager->getChoreographer().setPointerIconVisibility(displayId, visible);
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
    std::scoped_lock _l(mLock);
    return mLocked.isInputMethodConnectionActive;
}

std::optional<DisplayViewport> NativeInputManager::getPointerViewportForAssociatedDisplay(
        ui::LogicalDisplayId associatedDisplayId) {
    return mInputManager->getChoreographer().getViewportForPointerDevice(associatedDisplayId);
}

bool NativeInputManager::filterInputEvent(const InputEvent& inputEvent, uint32_t policyFlags) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();

    ScopedLocalRef<jobject> inputEventObj(env);
    switch (inputEvent.getType()) {
        case InputEventType::KEY:
            inputEventObj =
                    android_view_KeyEvent_obtainAsCopy(env,
                                                       static_cast<const KeyEvent&>(inputEvent));
            break;
        case InputEventType::MOTION:
            inputEventObj = android_view_MotionEvent_obtainAsCopy(env,
                                                                  static_cast<const MotionEvent&>(
                                                                          inputEvent));
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
    const bool interactive = isDisplayInteractive(keyEvent.getDisplayId());
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
    ScopedLocalRef<jobject> keyEventObj = android_view_KeyEvent_obtainAsCopy(env, keyEvent);
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

void NativeInputManager::interceptMotionBeforeQueueing(ui::LogicalDisplayId displayId,
                                                       uint32_t source, int32_t action,
                                                       nsecs_t when, uint32_t& policyFlags) {
    ATRACE_CALL();
    // Policy:
    // - Ignore untrusted events and pass them along.
    // - No special filtering for injected events required at this time.
    // - Filter normal events based on screen state.
    // - For normal events brighten (but do not wake) the screen if currently dim.
    const bool interactive = isDisplayInteractive(displayId);
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
                               displayId, source, action, when, policyFlags);
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

bool NativeInputManager::isDisplayInteractive(ui::LogicalDisplayId displayId) {
    // If an input event doesn't have an associated id, use the default display id
    if (displayId == ui::LogicalDisplayId::INVALID) {
        displayId = ui::LogicalDisplayId::DEFAULT;
    }

    { // acquire lock
        std::scoped_lock _l(mLock);

        auto it = mLocked.nonInteractiveDisplays.find(displayId);
        if (it != mLocked.nonInteractiveDisplays.end()) {
            return false;
        }
    } // release lock

    return true;
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
    ScopedLocalRef<jobject> keyEventObj = android_view_KeyEvent_obtainAsCopy(env, keyEvent);
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
    ScopedLocalRef<jobject> keyEventObj = android_view_KeyEvent_obtainAsCopy(env, keyEvent);
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

    const KeyEvent fallbackEvent =
            android_view_KeyEvent_obtainAsCopy(env, fallbackKeyEventObj.get());
    android_view_KeyEvent_recycle(env, fallbackKeyEventObj.get());
    return fallbackEvent;
}

void NativeInputManager::pokeUserActivity(nsecs_t eventTime, int32_t eventType,
                                          ui::LogicalDisplayId displayId) {
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

        ALOGV("%s pointer capture.", request.isEnable() ? "Enabling" : "Disabling");
        mLocked.pointerCaptureRequest = request;
    } // release lock

    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::POINTER_CAPTURE);
}

void NativeInputManager::loadPointerIcon(SpriteIcon* icon, ui::LogicalDisplayId displayId) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();
    *icon = toSpriteIcon(loadPointerIcon(env, displayId, PointerIconStyle::TYPE_ARROW));
}

void NativeInputManager::loadPointerResources(PointerResources* outResources,
                                              ui::LogicalDisplayId displayId) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();

    outResources->spotHover =
            toSpriteIcon(loadPointerIcon(env, displayId, PointerIconStyle::TYPE_SPOT_HOVER));
    outResources->spotTouch =
            toSpriteIcon(loadPointerIcon(env, displayId, PointerIconStyle::TYPE_SPOT_TOUCH));
    outResources->spotAnchor =
            toSpriteIcon(loadPointerIcon(env, displayId, PointerIconStyle::TYPE_SPOT_ANCHOR));
}

void NativeInputManager::loadAdditionalMouseResources(
        std::map<PointerIconStyle, SpriteIcon>* outResources,
        std::map<PointerIconStyle, PointerAnimation>* outAnimationResources,
        ui::LogicalDisplayId displayId) {
    ATRACE_CALL();
    JNIEnv* env = jniEnv();

    constexpr static std::array ADDITIONAL_STYLES{PointerIconStyle::TYPE_CONTEXT_MENU,
                                                  PointerIconStyle::TYPE_HAND,
                                                  PointerIconStyle::TYPE_HELP,
                                                  PointerIconStyle::TYPE_WAIT,
                                                  PointerIconStyle::TYPE_CELL,
                                                  PointerIconStyle::TYPE_CROSSHAIR,
                                                  PointerIconStyle::TYPE_TEXT,
                                                  PointerIconStyle::TYPE_VERTICAL_TEXT,
                                                  PointerIconStyle::TYPE_ALIAS,
                                                  PointerIconStyle::TYPE_COPY,
                                                  PointerIconStyle::TYPE_NO_DROP,
                                                  PointerIconStyle::TYPE_ALL_SCROLL,
                                                  PointerIconStyle::TYPE_HORIZONTAL_DOUBLE_ARROW,
                                                  PointerIconStyle::TYPE_VERTICAL_DOUBLE_ARROW,
                                                  PointerIconStyle::TYPE_TOP_RIGHT_DOUBLE_ARROW,
                                                  PointerIconStyle::TYPE_TOP_LEFT_DOUBLE_ARROW,
                                                  PointerIconStyle::TYPE_ZOOM_IN,
                                                  PointerIconStyle::TYPE_ZOOM_OUT,
                                                  PointerIconStyle::TYPE_GRAB,
                                                  PointerIconStyle::TYPE_GRABBING,
                                                  PointerIconStyle::TYPE_HANDWRITING,
                                                  PointerIconStyle::TYPE_SPOT_HOVER};

    for (const auto pointerIconStyle : ADDITIONAL_STYLES) {
        PointerIcon pointerIcon = loadPointerIcon(env, displayId, pointerIconStyle);
        (*outResources)[pointerIconStyle] = toSpriteIcon(pointerIcon);
        if (!pointerIcon.bitmapFrames.empty()) {
            PointerAnimation& animationData = (*outAnimationResources)[pointerIconStyle];
            size_t numFrames = pointerIcon.bitmapFrames.size() + 1;
            animationData.durationPerFrame =
                    milliseconds_to_nanoseconds(pointerIcon.durationPerFrame);
            animationData.animationFrames.reserve(numFrames);
            animationData.animationFrames.emplace_back(pointerIcon.bitmap, pointerIcon.style,
                                                       pointerIcon.hotSpotX, pointerIcon.hotSpotY,
                                                       pointerIcon.drawNativeDropShadow);
            for (size_t i = 0; i < numFrames - 1; ++i) {
                animationData.animationFrames.emplace_back(pointerIcon.bitmapFrames[i],
                                                           pointerIcon.style, pointerIcon.hotSpotX,
                                                           pointerIcon.hotSpotY,
                                                           pointerIcon.drawNativeDropShadow);
            }
        }
    }

    (*outResources)[PointerIconStyle::TYPE_NULL] =
            toSpriteIcon(loadPointerIcon(env, displayId, PointerIconStyle::TYPE_NULL));
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

FloatPoint NativeInputManager::getMouseCursorPosition(ui::LogicalDisplayId displayId) {
    return mInputManager->getChoreographer().getMouseCursorPosition(displayId);
}

void NativeInputManager::setStylusPointerIconEnabled(bool enabled) {
    mInputManager->getChoreographer().setStylusPointerIconEnabled(enabled);
    return;
}

void NativeInputManager::setInputMethodConnectionIsActive(bool isActive) {
    { // acquire lock
        std::scoped_lock _l(mLock);
        mLocked.isInputMethodConnectionActive = isActive;
    } // release lock

    mInputManager->getDispatcher().setInputMethodConnectionIsActive(isActive);
}

void NativeInputManager::setKeyRemapping(const std::map<int32_t, int32_t>& keyRemapping) {
    { // acquire lock
        std::scoped_lock _l(mLock);
        mLocked.keyRemapping = keyRemapping;
    } // release lock

    mInputManager->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::KEY_REMAPPING);
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

static void nativeSetKeyRemapping(JNIEnv* env, jobject nativeImplObj, jintArray fromKeyCodesArr,
                                  jintArray toKeyCodesArr) {
    const std::vector<int32_t> fromKeycodes = getIntArray(env, fromKeyCodesArr);
    const std::vector<int32_t> toKeycodes = getIntArray(env, toKeyCodesArr);
    if (fromKeycodes.size() != toKeycodes.size()) {
        jniThrowRuntimeException(env, "FromKeycodes and toKeycodes cannot match.");
    }
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    std::map<int32_t, int32_t> keyRemapping;
    for (int i = 0; i < fromKeycodes.size(); i++) {
        keyRemapping.insert_or_assign(fromKeycodes[i], toKeycodes[i]);
    }
    im->setKeyRemapping(keyRemapping);
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

    if (ui::LogicalDisplayId{displayId} == ui::LogicalDisplayId::INVALID) {
        std::string message = "InputChannel used as a monitor must be associated with a display";
        jniThrowRuntimeException(env, message.c_str());
        return nullptr;
    }

    ScopedUtfChars nameChars(env, nameObj);
    std::string name = nameChars.c_str();

    base::Result<std::unique_ptr<InputChannel>> inputChannel =
            im->createInputMonitor(ui::LogicalDisplayId{displayId}, name, gui::Pid{pid});

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
                                                                 hasPermission,
                                                                 ui::LogicalDisplayId{displayId});
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
        const KeyEvent keyEvent = android_view_KeyEvent_obtainAsCopy(env, inputEventObj);
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
        const KeyEvent keyEvent = android_view_KeyEvent_obtainAsCopy(env, inputEventObj);
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

    im->displayRemoved(env, ui::LogicalDisplayId{displayId});
}

static void nativeSetFocusedApplication(JNIEnv* env, jobject nativeImplObj, jint displayId,
                                        jobject applicationHandleObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setFocusedApplication(env, ui::LogicalDisplayId{displayId}, applicationHandleObj);
}

static void nativeSetFocusedDisplay(JNIEnv* env, jobject nativeImplObj, jint displayId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setFocusedDisplay(ui::LogicalDisplayId{displayId});
}

static void nativeSetUserActivityPokeInterval(JNIEnv* env, jobject nativeImplObj,
                                              jlong intervalMillis) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setMinTimeBetweenUserActivityPokes(intervalMillis);
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

static jboolean nativeTransferTouchGesture(JNIEnv* env, jobject nativeImplObj,
                                           jobject fromChannelTokenObj, jobject toChannelTokenObj,
                                           jboolean isDragDrop) {
    if (fromChannelTokenObj == nullptr || toChannelTokenObj == nullptr) {
        return JNI_FALSE;
    }

    sp<IBinder> fromChannelToken = ibinderForJavaObject(env, fromChannelTokenObj);
    sp<IBinder> toChannelToken = ibinderForJavaObject(env, toChannelTokenObj);

    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    if (im->getInputManager()->getDispatcher().transferTouchGesture(fromChannelToken,
                                                                    toChannelToken, isDragDrop)) {
        return JNI_TRUE;
    } else {
        return JNI_FALSE;
    }
}

static jboolean nativeTransferTouchOnDisplay(JNIEnv* env, jobject nativeImplObj,
                                             jobject destChannelTokenObj, jint displayId) {
    sp<IBinder> destChannelToken = ibinderForJavaObject(env, destChannelTokenObj);

    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    if (im->getInputManager()->getDispatcher().transferTouchOnDisplay(destChannelToken,
                                                                      ui::LogicalDisplayId{
                                                                              displayId})) {
        return JNI_TRUE;
    } else {
        return JNI_FALSE;
    }
}

static jint nativeGetMousePointerSpeed(JNIEnv* env, jobject nativeImplObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    return static_cast<jint>(im->getMousePointerSpeed());
}

static void nativeSetPointerSpeed(JNIEnv* env, jobject nativeImplObj, jint speed) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setPointerSpeed(speed);
}

static void nativeSetMousePointerAccelerationEnabled(JNIEnv* env, jobject nativeImplObj,
                                                     jint displayId, jboolean enabled) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setMousePointerAccelerationEnabled(ui::LogicalDisplayId{displayId}, enabled);
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

static void nativeSetTouchpadTapDraggingEnabled(JNIEnv* env, jobject nativeImplObj,
                                                jboolean enabled) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setTouchpadTapDraggingEnabled(enabled);
}

static void nativeSetShouldNotifyTouchpadHardwareState(JNIEnv* env, jobject nativeImplObj,
                                                       jboolean enabled) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setShouldNotifyTouchpadHardwareState(enabled);
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

static void nativeSetNonInteractiveDisplays(JNIEnv* env, jobject nativeImplObj,
                                            jintArray displayIds) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    const std::vector displayIdsVec = getIntArray(env, displayIds);
    std::set<ui::LogicalDisplayId> logicalDisplayIds;
    for (int displayId : displayIdsVec) {
        logicalDisplayIds.emplace(ui::LogicalDisplayId{displayId});
    }

    im->setNonInteractiveDisplays(logicalDisplayIds);
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
        } else if (lightInfo.type == InputDeviceLightType::KEYBOARD_MIC_MUTE) {
            jTypeId = env->GetStaticIntField(gLightClassInfo.clazz,
                                             gLightClassInfo.lightTypeKeyboardMicMute);
        } else {
            ALOGW("Unknown light type %s", ftl::enum_string(lightInfo.type).c_str());
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

static void nativeEnableInputDevice(JNIEnv* env, jobject nativeImplObj, jint deviceId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setInputDeviceEnabled(deviceId, true);
}

static void nativeDisableInputDevice(JNIEnv* env, jobject nativeImplObj, jint deviceId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setInputDeviceEnabled(deviceId, false);
}

static void nativeReloadPointerIcons(JNIEnv* env, jobject nativeImplObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->reloadPointerIcons();
}

static bool nativeSetPointerIcon(JNIEnv* env, jobject nativeImplObj, jobject iconObj,
                                 jint displayId, jint deviceId, jint pointerId,
                                 jobject inputTokenObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    PointerIcon pointerIcon = android_view_PointerIcon_toNative(env, iconObj);

    std::variant<std::unique_ptr<SpriteIcon>, PointerIconStyle> icon;
    if (pointerIcon.style == PointerIconStyle::TYPE_CUSTOM) {
        icon = std::make_unique<SpriteIcon>(pointerIcon.bitmap.copy(
                                                    ANDROID_BITMAP_FORMAT_RGBA_8888),
                                            pointerIcon.style, pointerIcon.hotSpotX,
                                            pointerIcon.hotSpotY, pointerIcon.drawNativeDropShadow);
    } else {
        icon = pointerIcon.style;
    }

    return im->setPointerIcon(std::move(icon), ui::LogicalDisplayId{displayId}, deviceId, pointerId,
                              ibinderForJavaObject(env, inputTokenObj));
}

static void nativeSetPointerIconVisibility(JNIEnv* env, jobject nativeImplObj, jint displayId,
                                           jboolean visible) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);

    im->setPointerIconVisibility(ui::LogicalDisplayId{displayId}, visible);
}

static jboolean nativeCanDispatchToDisplay(JNIEnv* env, jobject nativeImplObj, jint deviceId,
                                           jint displayId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    return im->getInputManager()->getReader().canDispatchToDisplay(deviceId,
                                                                   ui::LogicalDisplayId{displayId});
}

static void nativeNotifyPortAssociationsChanged(JNIEnv* env, jobject nativeImplObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    im->getInputManager()->getReader().requestRefreshConfiguration(
            InputReaderConfiguration::Change::DISPLAY_INFO);
}

static void nativeSetDisplayEligibilityForPointerCapture(JNIEnv* env, jobject nativeImplObj,
                                                         jint displayId, jboolean isEligible) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    im->getInputManager()
            ->getDispatcher()
            .setDisplayEligibilityForPointerCapture(ui::LogicalDisplayId{displayId}, isEligible);
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
                                            jint delayMs, jboolean keyRepeatEnabled) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    im->getInputManager()->getDispatcher().setKeyRepeatConfiguration(std::chrono::milliseconds(
                                                                             timeoutMs),
                                                                     std::chrono::milliseconds(
                                                                             delayMs),
                                                                     keyRepeatEnabled);
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

static jobject nativeGetTouchpadHardwareProperties(JNIEnv* env, jobject nativeImplObj,
                                                   jint deviceId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    std::optional<HardwareProperties> touchpadHardwareProperties =
            im->getInputManager()->getReader().getTouchpadHardwareProperties(deviceId);

    jobject hwPropsObj = env->NewObject(gTouchpadHardwarePropertiesOffsets.clazz,
                                        gTouchpadHardwarePropertiesOffsets.constructor);
    if (hwPropsObj == NULL || !touchpadHardwareProperties.has_value()) {
        return hwPropsObj;
    }
    env->SetFloatField(hwPropsObj, gTouchpadHardwarePropertiesOffsets.left,
                       touchpadHardwareProperties->left);
    env->SetFloatField(hwPropsObj, gTouchpadHardwarePropertiesOffsets.top,
                       touchpadHardwareProperties->top);
    env->SetFloatField(hwPropsObj, gTouchpadHardwarePropertiesOffsets.right,
                       touchpadHardwareProperties->right);
    env->SetFloatField(hwPropsObj, gTouchpadHardwarePropertiesOffsets.bottom,
                       touchpadHardwareProperties->bottom);
    env->SetFloatField(hwPropsObj, gTouchpadHardwarePropertiesOffsets.resX,
                       touchpadHardwareProperties->res_x);
    env->SetFloatField(hwPropsObj, gTouchpadHardwarePropertiesOffsets.resY,
                       touchpadHardwareProperties->res_y);
    env->SetFloatField(hwPropsObj, gTouchpadHardwarePropertiesOffsets.orientationMinimum,
                       touchpadHardwareProperties->orientation_minimum);
    env->SetFloatField(hwPropsObj, gTouchpadHardwarePropertiesOffsets.orientationMaximum,
                       touchpadHardwareProperties->orientation_maximum);
    env->SetIntField(hwPropsObj, gTouchpadHardwarePropertiesOffsets.maxFingerCount,
                     touchpadHardwareProperties->max_finger_cnt);
    env->SetBooleanField(hwPropsObj, gTouchpadHardwarePropertiesOffsets.isButtonPad,
                         touchpadHardwareProperties->is_button_pad);
    env->SetBooleanField(hwPropsObj, gTouchpadHardwarePropertiesOffsets.isHapticPad,
                         touchpadHardwareProperties->is_haptic_pad);
    env->SetBooleanField(hwPropsObj, gTouchpadHardwarePropertiesOffsets.reportsPressure,
                         touchpadHardwareProperties->reports_pressure);

    return hwPropsObj;
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
    im->setPointerDisplayId(ui::LogicalDisplayId{displayId});
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

static jfloatArray nativeGetMouseCursorPosition(JNIEnv* env, jobject nativeImplObj,
                                                jint displayId) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    const auto p = im->getMouseCursorPosition(ui::LogicalDisplayId{displayId});
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

static void nativeSetAccessibilityBounceKeysThreshold(JNIEnv* env, jobject nativeImplObj,
                                                      jint thresholdTimeMs) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    if (ENABLE_INPUT_FILTER_RUST) {
        im->getInputManager()->getInputFilter().setAccessibilityBounceKeysThreshold(
                static_cast<nsecs_t>(thresholdTimeMs) * 1000000);
    }
}

static void nativeSetAccessibilitySlowKeysThreshold(JNIEnv* env, jobject nativeImplObj,
                                                    jint thresholdTimeMs) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    if (ENABLE_INPUT_FILTER_RUST) {
        im->getInputManager()->getInputFilter().setAccessibilitySlowKeysThreshold(
                static_cast<nsecs_t>(thresholdTimeMs) * 1000000);
    }
}

static void nativeSetAccessibilityStickyKeysEnabled(JNIEnv* env, jobject nativeImplObj,
                                                    jboolean enabled) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    if (ENABLE_INPUT_FILTER_RUST) {
        im->getInputManager()->getInputFilter().setAccessibilityStickyKeysEnabled(enabled);
    }
}

static void nativeSetInputMethodConnectionIsActive(JNIEnv* env, jobject nativeImplObj,
                                                   jboolean isActive) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    im->setInputMethodConnectionIsActive(isActive);
}

static jint nativeGetLastUsedInputDeviceId(JNIEnv* env, jobject nativeImplObj) {
    NativeInputManager* im = getNativeInputManager(env, nativeImplObj);
    return static_cast<jint>(im->getInputManager()->getReader().getLastUsedInputDeviceId());
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
        {"setKeyRemapping", "([I[I)V", (void*)nativeSetKeyRemapping},
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
        {"setMinTimeBetweenUserActivityPokes", "(J)V", (void*)nativeSetUserActivityPokeInterval},
        {"requestPointerCapture", "(Landroid/os/IBinder;Z)V", (void*)nativeRequestPointerCapture},
        {"setInputDispatchMode", "(ZZ)V", (void*)nativeSetInputDispatchMode},
        {"setSystemUiLightsOut", "(Z)V", (void*)nativeSetSystemUiLightsOut},
        {"transferTouchGesture", "(Landroid/os/IBinder;Landroid/os/IBinder;Z)Z",
         (void*)nativeTransferTouchGesture},
        {"transferTouch", "(Landroid/os/IBinder;I)Z", (void*)nativeTransferTouchOnDisplay},
        {"getMousePointerSpeed", "()I", (void*)nativeGetMousePointerSpeed},
        {"setPointerSpeed", "(I)V", (void*)nativeSetPointerSpeed},
        {"setMousePointerAccelerationEnabled", "(IZ)V",
         (void*)nativeSetMousePointerAccelerationEnabled},
        {"setTouchpadPointerSpeed", "(I)V", (void*)nativeSetTouchpadPointerSpeed},
        {"setTouchpadNaturalScrollingEnabled", "(Z)V",
         (void*)nativeSetTouchpadNaturalScrollingEnabled},
        {"setTouchpadTapToClickEnabled", "(Z)V", (void*)nativeSetTouchpadTapToClickEnabled},
        {"setTouchpadTapDraggingEnabled", "(Z)V", (void*)nativeSetTouchpadTapDraggingEnabled},
        {"setShouldNotifyTouchpadHardwareState", "(Z)V",
         (void*)nativeSetShouldNotifyTouchpadHardwareState},
        {"setTouchpadRightClickZoneEnabled", "(Z)V", (void*)nativeSetTouchpadRightClickZoneEnabled},
        {"setShowTouches", "(Z)V", (void*)nativeSetShowTouches},
        {"setNonInteractiveDisplays", "([I)V", (void*)nativeSetNonInteractiveDisplays},
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
        {"enableInputDevice", "(I)V", (void*)nativeEnableInputDevice},
        {"disableInputDevice", "(I)V", (void*)nativeDisableInputDevice},
        {"reloadPointerIcons", "()V", (void*)nativeReloadPointerIcons},
        {"setPointerIcon", "(Landroid/view/PointerIcon;IIILandroid/os/IBinder;)Z",
         (void*)nativeSetPointerIcon},
        {"setPointerIconVisibility", "(IZ)V", (void*)nativeSetPointerIconVisibility},
        {"canDispatchToDisplay", "(II)Z", (void*)nativeCanDispatchToDisplay},
        {"notifyPortAssociationsChanged", "()V", (void*)nativeNotifyPortAssociationsChanged},
        {"changeUniqueIdAssociation", "()V", (void*)nativeChangeUniqueIdAssociation},
        {"changeTypeAssociation", "()V", (void*)nativeChangeTypeAssociation},
        {"changeKeyboardLayoutAssociation", "()V", (void*)changeKeyboardLayoutAssociation},
        {"setDisplayEligibilityForPointerCapture", "(IZ)V",
         (void*)nativeSetDisplayEligibilityForPointerCapture},
        {"setMotionClassifierEnabled", "(Z)V", (void*)nativeSetMotionClassifierEnabled},
        {"setKeyRepeatConfiguration", "(IIZ)V", (void*)nativeSetKeyRepeatConfiguration},
        {"getSensorList", "(I)[Landroid/hardware/input/InputSensorInfo;",
         (void*)nativeGetSensorList},
        {"getTouchpadHardwareProperties",
         "(I)Lcom/android/server/input/TouchpadHardwareProperties;",
         (void*)nativeGetTouchpadHardwareProperties},
        {"enableSensor", "(IIII)Z", (void*)nativeEnableSensor},
        {"disableSensor", "(II)V", (void*)nativeDisableSensor},
        {"flushSensor", "(II)Z", (void*)nativeFlushSensor},
        {"cancelCurrentTouch", "()V", (void*)nativeCancelCurrentTouch},
        {"setPointerDisplayId", "(I)V", (void*)nativeSetPointerDisplayId},
        {"getBluetoothAddress", "(I)Ljava/lang/String;", (void*)nativeGetBluetoothAddress},
        {"setStylusButtonMotionEventsEnabled", "(Z)V",
         (void*)nativeSetStylusButtonMotionEventsEnabled},
        {"getMouseCursorPosition", "(I)[F", (void*)nativeGetMouseCursorPosition},
        {"setStylusPointerIconEnabled", "(Z)V", (void*)nativeSetStylusPointerIconEnabled},
        {"setAccessibilityBounceKeysThreshold", "(I)V",
         (void*)nativeSetAccessibilityBounceKeysThreshold},
        {"setAccessibilitySlowKeysThreshold", "(I)V",
         (void*)nativeSetAccessibilitySlowKeysThreshold},
        {"setAccessibilityStickyKeysEnabled", "(Z)V",
         (void*)nativeSetAccessibilityStickyKeysEnabled},
        {"setInputMethodConnectionIsActive", "(Z)V", (void*)nativeSetInputMethodConnectionIsActive},
        {"getLastUsedInputDeviceId", "()I", (void*)nativeGetLastUsedInputDeviceId},
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

    GET_METHOD_ID(gServiceClassInfo.notifyInputDevicesChanged, clazz,
            "notifyInputDevicesChanged", "([Landroid/view/InputDevice;)V");

    GET_METHOD_ID(gServiceClassInfo.notifyTouchpadHardwareState, clazz,
                  "notifyTouchpadHardwareState",
                  "(Lcom/android/server/input/TouchpadHardwareState;I)V")

    GET_METHOD_ID(gServiceClassInfo.notifyTouchpadGestureInfo, clazz, "notifyTouchpadGestureInfo",
                  "(II)V")

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
            "interceptMotionBeforeQueueingNonInteractive", "(IIIJI)I");

    GET_METHOD_ID(gServiceClassInfo.interceptKeyBeforeDispatching, clazz,
            "interceptKeyBeforeDispatching",
            "(Landroid/os/IBinder;Landroid/view/KeyEvent;I)J");

    GET_METHOD_ID(gServiceClassInfo.dispatchUnhandledKey, clazz,
            "dispatchUnhandledKey",
            "(Landroid/os/IBinder;Landroid/view/KeyEvent;I)Landroid/view/KeyEvent;");

    GET_METHOD_ID(gServiceClassInfo.notifyStickyModifierStateChanged, clazz,
                  "notifyStickyModifierStateChanged", "(II)V");

    GET_METHOD_ID(gServiceClassInfo.onPointerDownOutsideFocus, clazz,
            "onPointerDownOutsideFocus", "(Landroid/os/IBinder;)V");

    GET_METHOD_ID(gServiceClassInfo.getVirtualKeyQuietTimeMillis, clazz,
            "getVirtualKeyQuietTimeMillis", "()I");

    GET_STATIC_METHOD_ID(gServiceClassInfo.getExcludedDeviceNames, clazz,
            "getExcludedDeviceNames", "()[Ljava/lang/String;");

    GET_METHOD_ID(gServiceClassInfo.getInputPortAssociations, clazz,
            "getInputPortAssociations", "()[Ljava/lang/String;");

    GET_METHOD_ID(gServiceClassInfo.getInputUniqueIdAssociationsByPort, clazz,
                  "getInputUniqueIdAssociationsByPort", "()[Ljava/lang/String;");

    GET_METHOD_ID(gServiceClassInfo.getInputUniqueIdAssociationsByDescriptor, clazz,
                  "getInputUniqueIdAssociationsByDescriptor", "()[Ljava/lang/String;");

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

    GET_METHOD_ID(gServiceClassInfo.getLoadedPointerIcon, clazz, "getLoadedPointerIcon",
                  "(II)Landroid/view/PointerIcon;");

    GET_METHOD_ID(gServiceClassInfo.getKeyboardLayoutOverlay, clazz, "getKeyboardLayoutOverlay",
                  "(Landroid/hardware/input/InputDeviceIdentifier;Ljava/lang/String;Ljava/lang/"
                  "String;)[Ljava/lang/String;");

    GET_METHOD_ID(gServiceClassInfo.getDeviceAlias, clazz,
            "getDeviceAlias", "(Ljava/lang/String;)Ljava/lang/String;");

    GET_METHOD_ID(gServiceClassInfo.getTouchCalibrationForInputDevice, clazz,
            "getTouchCalibrationForInputDevice",
            "(Ljava/lang/String;I)Landroid/hardware/input/TouchCalibration;");

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
    gLightClassInfo.lightTypeKeyboardMicMute =
            env->GetStaticFieldID(gLightClassInfo.clazz, "LIGHT_TYPE_KEYBOARD_MIC_MUTE", "I");
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

    // TouchpadHardwareState

    FIND_CLASS(gTouchpadHardwareStateClassInfo.clazz,
               "com/android/server/input/TouchpadHardwareState");
    gTouchpadHardwareStateClassInfo.clazz =
            reinterpret_cast<jclass>(env->NewGlobalRef(gTouchpadHardwareStateClassInfo.clazz));

    GET_FIELD_ID(gTouchpadHardwareStateClassInfo.touchCount, gTouchpadHardwareStateClassInfo.clazz,
                 "mTouchCount", "I");
    GET_FIELD_ID(gTouchpadHardwareStateClassInfo.fingerCount, gTouchpadHardwareStateClassInfo.clazz,
                 "mFingerCount", "I");
    GET_FIELD_ID(gTouchpadHardwareStateClassInfo.buttonsDown, gTouchpadHardwareStateClassInfo.clazz,
                 "mButtonsDown", "I");
    GET_FIELD_ID(gTouchpadHardwareStateClassInfo.timestamp, gTouchpadHardwareStateClassInfo.clazz,
                 "mTimestamp", "F");
    GET_FIELD_ID(gTouchpadHardwareStateClassInfo.fingerStates,
                 gTouchpadHardwareStateClassInfo.clazz, "mFingerStates",
                 "[Lcom/android/server/input/TouchpadFingerState;");

    GET_METHOD_ID(gTouchpadHardwareStateClassInfo.init, gTouchpadHardwareStateClassInfo.clazz,
                  "<init>", "()V");

    // TouchpadFingerState

    FIND_CLASS(gTouchpadFingerStateClassInfo.clazz, "com/android/server/input/TouchpadFingerState");
    gTouchpadFingerStateClassInfo.clazz =
            reinterpret_cast<jclass>(env->NewGlobalRef(gTouchpadFingerStateClassInfo.clazz));

    GET_FIELD_ID(gTouchpadFingerStateClassInfo.touchMajor, gTouchpadFingerStateClassInfo.clazz,
                 "mTouchMajor", "F");
    GET_FIELD_ID(gTouchpadFingerStateClassInfo.touchMinor, gTouchpadFingerStateClassInfo.clazz,
                 "mTouchMinor", "F");
    GET_FIELD_ID(gTouchpadFingerStateClassInfo.widthMajor, gTouchpadFingerStateClassInfo.clazz,
                 "mWidthMajor", "F");
    GET_FIELD_ID(gTouchpadFingerStateClassInfo.widthMinor, gTouchpadFingerStateClassInfo.clazz,
                 "mWidthMinor", "F");
    GET_FIELD_ID(gTouchpadFingerStateClassInfo.pressure, gTouchpadFingerStateClassInfo.clazz,
                 "mPressure", "F");
    GET_FIELD_ID(gTouchpadFingerStateClassInfo.orientation, gTouchpadFingerStateClassInfo.clazz,
                 "mOrientation", "F")
    GET_FIELD_ID(gTouchpadFingerStateClassInfo.positionX, gTouchpadFingerStateClassInfo.clazz,
                 "mPositionX", "F");
    GET_FIELD_ID(gTouchpadFingerStateClassInfo.positionY, gTouchpadFingerStateClassInfo.clazz,
                 "mPositionY", "F");
    GET_FIELD_ID(gTouchpadFingerStateClassInfo.trackingId, gTouchpadFingerStateClassInfo.clazz,
                 "mTrackingId", "I");

    GET_METHOD_ID(gTouchpadFingerStateClassInfo.init, gTouchpadFingerStateClassInfo.clazz, "<init>",
                  "()V");

    // TouchpadHardawreProperties
    FIND_CLASS(gTouchpadHardwarePropertiesOffsets.clazz,
               "com/android/server/input/TouchpadHardwareProperties");
    gTouchpadHardwarePropertiesOffsets.clazz =
            reinterpret_cast<jclass>(env->NewGlobalRef(gTouchpadHardwarePropertiesOffsets.clazz));

    // Get the constructor ID
    GET_METHOD_ID(gTouchpadHardwarePropertiesOffsets.constructor,
                  gTouchpadHardwarePropertiesOffsets.clazz, "<init>", "()V");

    // Get the field IDs
    GET_FIELD_ID(gTouchpadHardwarePropertiesOffsets.left, gTouchpadHardwarePropertiesOffsets.clazz,
                 "mLeft", "F");
    GET_FIELD_ID(gTouchpadHardwarePropertiesOffsets.top, gTouchpadHardwarePropertiesOffsets.clazz,
                 "mTop", "F");
    GET_FIELD_ID(gTouchpadHardwarePropertiesOffsets.right, gTouchpadHardwarePropertiesOffsets.clazz,
                 "mRight", "F");
    GET_FIELD_ID(gTouchpadHardwarePropertiesOffsets.bottom,
                 gTouchpadHardwarePropertiesOffsets.clazz, "mBottom", "F");
    GET_FIELD_ID(gTouchpadHardwarePropertiesOffsets.resX, gTouchpadHardwarePropertiesOffsets.clazz,
                 "mResX", "F");
    GET_FIELD_ID(gTouchpadHardwarePropertiesOffsets.resY, gTouchpadHardwarePropertiesOffsets.clazz,
                 "mResY", "F");
    GET_FIELD_ID(gTouchpadHardwarePropertiesOffsets.orientationMinimum,
                 gTouchpadHardwarePropertiesOffsets.clazz, "mOrientationMinimum", "F");
    GET_FIELD_ID(gTouchpadHardwarePropertiesOffsets.orientationMaximum,
                 gTouchpadHardwarePropertiesOffsets.clazz, "mOrientationMaximum", "F");
    GET_FIELD_ID(gTouchpadHardwarePropertiesOffsets.maxFingerCount,
                 gTouchpadHardwarePropertiesOffsets.clazz, "mMaxFingerCount", "S");
    GET_FIELD_ID(gTouchpadHardwarePropertiesOffsets.isButtonPad,
                 gTouchpadHardwarePropertiesOffsets.clazz, "mIsButtonPad", "Z");
    GET_FIELD_ID(gTouchpadHardwarePropertiesOffsets.isHapticPad,
                 gTouchpadHardwarePropertiesOffsets.clazz, "mIsHapticPad", "Z");
    GET_FIELD_ID(gTouchpadHardwarePropertiesOffsets.reportsPressure,
                 gTouchpadHardwarePropertiesOffsets.clazz, "mReportsPressure", "Z");

    return 0;
}

} /* namespace android */
