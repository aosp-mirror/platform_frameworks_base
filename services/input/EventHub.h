/*
 * Copyright (C) 2005 The Android Open Source Project
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

//
#ifndef _RUNTIME_EVENT_HUB_H
#define _RUNTIME_EVENT_HUB_H

#include <androidfw/Input.h>
#include <androidfw/InputDevice.h>
#include <androidfw/Keyboard.h>
#include <androidfw/KeyLayoutMap.h>
#include <androidfw/KeyCharacterMap.h>
#include <androidfw/VirtualKeyMap.h>
#include <utils/String8.h>
#include <utils/threads.h>
#include <utils/Log.h>
#include <utils/threads.h>
#include <utils/List.h>
#include <utils/Errors.h>
#include <utils/PropertyMap.h>
#include <utils/Vector.h>
#include <utils/KeyedVector.h>

#include <linux/input.h>
#include <sys/epoll.h>

/* Convenience constants. */

#define BTN_FIRST 0x100  // first button code
#define BTN_LAST 0x15f   // last button code

/*
 * These constants are used privately in Android to pass raw timestamps
 * through evdev from uinput device drivers because there is currently no
 * other way to transfer this information.  The evdev driver automatically
 * timestamps all input events with the time they were posted and clobbers
 * whatever information was passed in.
 *
 * For the purposes of this hack, the timestamp is specified in the
 * CLOCK_MONOTONIC timebase and is split into two EV_MSC events specifying
 * seconds and microseconds.
 */
#define MSC_ANDROID_TIME_SEC 0x6
#define MSC_ANDROID_TIME_USEC 0x7

namespace android {

enum {
    // Device id of a special "virtual" keyboard that is always present.
    VIRTUAL_KEYBOARD_ID = -1,
    // Device id of the "built-in" keyboard if there is one.
    BUILT_IN_KEYBOARD_ID = 0,
};

/*
 * A raw event as retrieved from the EventHub.
 */
struct RawEvent {
    nsecs_t when;
    int32_t deviceId;
    int32_t type;
    int32_t code;
    int32_t value;
};

/* Describes an absolute axis. */
struct RawAbsoluteAxisInfo {
    bool valid; // true if the information is valid, false otherwise

    int32_t minValue;  // minimum value
    int32_t maxValue;  // maximum value
    int32_t flat;      // center flat position, eg. flat == 8 means center is between -8 and 8
    int32_t fuzz;      // error tolerance, eg. fuzz == 4 means value is +/- 4 due to noise
    int32_t resolution; // resolution in units per mm or radians per mm

    inline void clear() {
        valid = false;
        minValue = 0;
        maxValue = 0;
        flat = 0;
        fuzz = 0;
        resolution = 0;
    }
};

/*
 * Input device classes.
 */
enum {
    /* The input device is a keyboard or has buttons. */
    INPUT_DEVICE_CLASS_KEYBOARD      = 0x00000001,

    /* The input device is an alpha-numeric keyboard (not just a dial pad). */
    INPUT_DEVICE_CLASS_ALPHAKEY      = 0x00000002,

    /* The input device is a touchscreen or a touchpad (either single-touch or multi-touch). */
    INPUT_DEVICE_CLASS_TOUCH         = 0x00000004,

    /* The input device is a cursor device such as a trackball or mouse. */
    INPUT_DEVICE_CLASS_CURSOR        = 0x00000008,

    /* The input device is a multi-touch touchscreen. */
    INPUT_DEVICE_CLASS_TOUCH_MT      = 0x00000010,

    /* The input device is a directional pad (implies keyboard, has DPAD keys). */
    INPUT_DEVICE_CLASS_DPAD          = 0x00000020,

    /* The input device is a gamepad (implies keyboard, has BUTTON keys). */
    INPUT_DEVICE_CLASS_GAMEPAD       = 0x00000040,

    /* The input device has switches. */
    INPUT_DEVICE_CLASS_SWITCH        = 0x00000080,

    /* The input device is a joystick (implies gamepad, has joystick absolute axes). */
    INPUT_DEVICE_CLASS_JOYSTICK      = 0x00000100,

    /* The input device has a vibrator (supports FF_RUMBLE). */
    INPUT_DEVICE_CLASS_VIBRATOR      = 0x00000200,

    /* The input device is virtual (not a real device, not part of UI configuration). */
    INPUT_DEVICE_CLASS_VIRTUAL       = 0x40000000,

    /* The input device is external (not built-in). */
    INPUT_DEVICE_CLASS_EXTERNAL      = 0x80000000,
};

/*
 * Gets the class that owns an axis, in cases where multiple classes might claim
 * the same axis for different purposes.
 */
extern uint32_t getAbsAxisUsage(int32_t axis, uint32_t deviceClasses);

/*
 * Grand Central Station for events.
 *
 * The event hub aggregates input events received across all known input
 * devices on the system, including devices that may be emulated by the simulator
 * environment.  In addition, the event hub generates fake input events to indicate
 * when devices are added or removed.
 *
 * The event hub provides a stream of input events (via the getEvent function).
 * It also supports querying the current actual state of input devices such as identifying
 * which keys are currently down.  Finally, the event hub keeps track of the capabilities of
 * individual input devices, such as their class and the set of key codes that they support.
 */
class EventHubInterface : public virtual RefBase {
protected:
    EventHubInterface() { }
    virtual ~EventHubInterface() { }

public:
    // Synthetic raw event type codes produced when devices are added or removed.
    enum {
        // Sent when a device is added.
        DEVICE_ADDED = 0x10000000,
        // Sent when a device is removed.
        DEVICE_REMOVED = 0x20000000,
        // Sent when all added/removed devices from the most recent scan have been reported.
        // This event is always sent at least once.
        FINISHED_DEVICE_SCAN = 0x30000000,

        FIRST_SYNTHETIC_EVENT = DEVICE_ADDED,
    };

    virtual uint32_t getDeviceClasses(int32_t deviceId) const = 0;

    virtual InputDeviceIdentifier getDeviceIdentifier(int32_t deviceId) const = 0;

    virtual void getConfiguration(int32_t deviceId, PropertyMap* outConfiguration) const = 0;

    virtual status_t getAbsoluteAxisInfo(int32_t deviceId, int axis,
            RawAbsoluteAxisInfo* outAxisInfo) const = 0;

    virtual bool hasRelativeAxis(int32_t deviceId, int axis) const = 0;

    virtual bool hasInputProperty(int32_t deviceId, int property) const = 0;

    virtual status_t mapKey(int32_t deviceId, int32_t scanCode, int32_t usageCode,
            int32_t* outKeycode, uint32_t* outFlags) const = 0;

    virtual status_t mapAxis(int32_t deviceId, int32_t scanCode,
            AxisInfo* outAxisInfo) const = 0;

    // Sets devices that are excluded from opening.
    // This can be used to ignore input devices for sensors.
    virtual void setExcludedDevices(const Vector<String8>& devices) = 0;

    /*
     * Wait for events to become available and returns them.
     * After returning, the EventHub holds onto a wake lock until the next call to getEvent.
     * This ensures that the device will not go to sleep while the event is being processed.
     * If the device needs to remain awake longer than that, then the caller is responsible
     * for taking care of it (say, by poking the power manager user activity timer).
     *
     * The timeout is advisory only.  If the device is asleep, it will not wake just to
     * service the timeout.
     *
     * Returns the number of events obtained, or 0 if the timeout expired.
     */
    virtual size_t getEvents(int timeoutMillis, RawEvent* buffer, size_t bufferSize) = 0;

    /*
     * Query current input state.
     */
    virtual int32_t getScanCodeState(int32_t deviceId, int32_t scanCode) const = 0;
    virtual int32_t getKeyCodeState(int32_t deviceId, int32_t keyCode) const = 0;
    virtual int32_t getSwitchState(int32_t deviceId, int32_t sw) const = 0;
    virtual status_t getAbsoluteAxisValue(int32_t deviceId, int32_t axis,
            int32_t* outValue) const = 0;

    /*
     * Examine key input devices for specific framework keycode support
     */
    virtual bool markSupportedKeyCodes(int32_t deviceId, size_t numCodes, const int32_t* keyCodes,
            uint8_t* outFlags) const = 0;

    virtual bool hasScanCode(int32_t deviceId, int32_t scanCode) const = 0;
    virtual bool hasLed(int32_t deviceId, int32_t led) const = 0;
    virtual void setLedState(int32_t deviceId, int32_t led, bool on) = 0;

    virtual void getVirtualKeyDefinitions(int32_t deviceId,
            Vector<VirtualKeyDefinition>& outVirtualKeys) const = 0;

    virtual sp<KeyCharacterMap> getKeyCharacterMap(int32_t deviceId) const = 0;
    virtual bool setKeyboardLayoutOverlay(int32_t deviceId, const sp<KeyCharacterMap>& map) = 0;

    /* Control the vibrator. */
    virtual void vibrate(int32_t deviceId, nsecs_t duration) = 0;
    virtual void cancelVibrate(int32_t deviceId) = 0;

    /* Requests the EventHub to reopen all input devices on the next call to getEvents(). */
    virtual void requestReopenDevices() = 0;

    /* Wakes up getEvents() if it is blocked on a read. */
    virtual void wake() = 0;

    /* Dump EventHub state to a string. */
    virtual void dump(String8& dump) = 0;

    /* Called by the heatbeat to ensures that the reader has not deadlocked. */
    virtual void monitor() = 0;
};

class EventHub : public EventHubInterface
{
public:
    EventHub();

    virtual uint32_t getDeviceClasses(int32_t deviceId) const;

    virtual InputDeviceIdentifier getDeviceIdentifier(int32_t deviceId) const;

    virtual void getConfiguration(int32_t deviceId, PropertyMap* outConfiguration) const;

    virtual status_t getAbsoluteAxisInfo(int32_t deviceId, int axis,
            RawAbsoluteAxisInfo* outAxisInfo) const;

    virtual bool hasRelativeAxis(int32_t deviceId, int axis) const;

    virtual bool hasInputProperty(int32_t deviceId, int property) const;

    virtual status_t mapKey(int32_t deviceId, int32_t scanCode, int32_t usageCode,
            int32_t* outKeycode, uint32_t* outFlags) const;

    virtual status_t mapAxis(int32_t deviceId, int32_t scanCode,
            AxisInfo* outAxisInfo) const;

    virtual void setExcludedDevices(const Vector<String8>& devices);

    virtual int32_t getScanCodeState(int32_t deviceId, int32_t scanCode) const;
    virtual int32_t getKeyCodeState(int32_t deviceId, int32_t keyCode) const;
    virtual int32_t getSwitchState(int32_t deviceId, int32_t sw) const;
    virtual status_t getAbsoluteAxisValue(int32_t deviceId, int32_t axis, int32_t* outValue) const;

    virtual bool markSupportedKeyCodes(int32_t deviceId, size_t numCodes,
            const int32_t* keyCodes, uint8_t* outFlags) const;

    virtual size_t getEvents(int timeoutMillis, RawEvent* buffer, size_t bufferSize);

    virtual bool hasScanCode(int32_t deviceId, int32_t scanCode) const;
    virtual bool hasLed(int32_t deviceId, int32_t led) const;
    virtual void setLedState(int32_t deviceId, int32_t led, bool on);

    virtual void getVirtualKeyDefinitions(int32_t deviceId,
            Vector<VirtualKeyDefinition>& outVirtualKeys) const;

    virtual sp<KeyCharacterMap> getKeyCharacterMap(int32_t deviceId) const;
    virtual bool setKeyboardLayoutOverlay(int32_t deviceId, const sp<KeyCharacterMap>& map);

    virtual void vibrate(int32_t deviceId, nsecs_t duration);
    virtual void cancelVibrate(int32_t deviceId);

    virtual void requestReopenDevices();

    virtual void wake();

    virtual void dump(String8& dump);
    virtual void monitor();

protected:
    virtual ~EventHub();

private:
    struct Device {
        Device* next;

        int fd; // may be -1 if device is virtual
        const int32_t id;
        const String8 path;
        const InputDeviceIdentifier identifier;

        uint32_t classes;

        uint8_t keyBitmask[(KEY_MAX + 1) / 8];
        uint8_t absBitmask[(ABS_MAX + 1) / 8];
        uint8_t relBitmask[(REL_MAX + 1) / 8];
        uint8_t swBitmask[(SW_MAX + 1) / 8];
        uint8_t ledBitmask[(LED_MAX + 1) / 8];
        uint8_t ffBitmask[(FF_MAX + 1) / 8];
        uint8_t propBitmask[(INPUT_PROP_MAX + 1) / 8];

        String8 configurationFile;
        PropertyMap* configuration;
        VirtualKeyMap* virtualKeyMap;
        KeyMap keyMap;

        sp<KeyCharacterMap> overlayKeyMap;
        sp<KeyCharacterMap> combinedKeyMap;

        bool ffEffectPlaying;
        int16_t ffEffectId; // initially -1

        int32_t timestampOverrideSec;
        int32_t timestampOverrideUsec;

        Device(int fd, int32_t id, const String8& path, const InputDeviceIdentifier& identifier);
        ~Device();

        void close();

        inline bool isVirtual() const { return fd < 0; }

        const sp<KeyCharacterMap>& getKeyCharacterMap() const {
            if (combinedKeyMap != NULL) {
                return combinedKeyMap;
            }
            return keyMap.keyCharacterMap;
        }
    };

    status_t openDeviceLocked(const char *devicePath);
    void createVirtualKeyboardLocked();
    void addDeviceLocked(Device* device);

    status_t closeDeviceByPathLocked(const char *devicePath);
    void closeDeviceLocked(Device* device);
    void closeAllDevicesLocked();

    status_t scanDirLocked(const char *dirname);
    void scanDevicesLocked();
    status_t readNotifyLocked();

    Device* getDeviceLocked(int32_t deviceId) const;
    Device* getDeviceByPathLocked(const char* devicePath) const;

    bool hasKeycodeLocked(Device* device, int keycode) const;

    void loadConfigurationLocked(Device* device);
    status_t loadVirtualKeyMapLocked(Device* device);
    status_t loadKeyMapLocked(Device* device);

    bool isExternalDeviceLocked(Device* device);

    // Protect all internal state.
    mutable Mutex mLock;

    // The actual id of the built-in keyboard, or NO_BUILT_IN_KEYBOARD if none.
    // EventHub remaps the built-in keyboard to id 0 externally as required by the API.
    enum {
        // Must not conflict with any other assigned device ids, including
        // the virtual keyboard id (-1).
        NO_BUILT_IN_KEYBOARD = -2,
    };
    int32_t mBuiltInKeyboardId;

    int32_t mNextDeviceId;

    KeyedVector<int32_t, Device*> mDevices;

    Device *mOpeningDevices;
    Device *mClosingDevices;

    bool mNeedToSendFinishedDeviceScan;
    bool mNeedToReopenDevices;
    bool mNeedToScanDevices;
    Vector<String8> mExcludedDevices;

    int mEpollFd;
    int mINotifyFd;
    int mWakeReadPipeFd;
    int mWakeWritePipeFd;

    // Ids used for epoll notifications not associated with devices.
    static const uint32_t EPOLL_ID_INOTIFY = 0x80000001;
    static const uint32_t EPOLL_ID_WAKE = 0x80000002;

    // Epoll FD list size hint.
    static const int EPOLL_SIZE_HINT = 8;

    // Maximum number of signalled FDs to handle at a time.
    static const int EPOLL_MAX_EVENTS = 16;

    // The array of pending epoll events and the index of the next event to be handled.
    struct epoll_event mPendingEventItems[EPOLL_MAX_EVENTS];
    size_t mPendingEventCount;
    size_t mPendingEventIndex;
    bool mPendingINotify;
};

}; // namespace android

#endif // _RUNTIME_EVENT_HUB_H
