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

#include <ui/Input.h>
#include <ui/Keyboard.h>
#include <ui/KeyLayoutMap.h>
#include <ui/KeyCharacterMap.h>
#include <ui/VirtualKeyMap.h>
#include <utils/String8.h>
#include <utils/threads.h>
#include <utils/Log.h>
#include <utils/threads.h>
#include <utils/List.h>
#include <utils/Errors.h>
#include <utils/PropertyMap.h>
#include <utils/Vector.h>

#include <linux/input.h>

/* These constants are not defined in linux/input.h but they are part of the multitouch
 * input protocol. */

#define ABS_MT_TOUCH_MAJOR 0x30  /* Major axis of touching ellipse */
#define ABS_MT_TOUCH_MINOR 0x31  /* Minor axis (omit if circular) */
#define ABS_MT_WIDTH_MAJOR 0x32  /* Major axis of approaching ellipse */
#define ABS_MT_WIDTH_MINOR 0x33  /* Minor axis (omit if circular) */
#define ABS_MT_ORIENTATION 0x34  /* Ellipse orientation */
#define ABS_MT_POSITION_X 0x35   /* Center X ellipse position */
#define ABS_MT_POSITION_Y 0x36   /* Center Y ellipse position */
#define ABS_MT_TOOL_TYPE 0x37    /* Type of touching device (finger, pen, ...) */
#define ABS_MT_BLOB_ID 0x38      /* Group a set of packets as a blob */
#define ABS_MT_TRACKING_ID 0x39  /* Unique ID of initiated contact */
#define ABS_MT_PRESSURE 0x3a     /* Pressure on contact area */

#define MT_TOOL_FINGER 0 /* Identifies a finger */
#define MT_TOOL_PEN 1    /* Identifies a pen */

#define SYN_MT_REPORT 2

/* Convenience constants. */

#define BTN_FIRST 0x100  // first button scancode
#define BTN_LAST 0x15f   // last button scancode

struct pollfd;

namespace android {

/*
 * A raw event as retrieved from the EventHub.
 */
struct RawEvent {
    nsecs_t when;
    int32_t deviceId;
    int32_t type;
    int32_t scanCode;
    int32_t keyCode;
    int32_t value;
    uint32_t flags;
};

/* Describes an absolute axis. */
struct RawAbsoluteAxisInfo {
    bool valid; // true if the information is valid, false otherwise

    int32_t minValue;  // minimum value
    int32_t maxValue;  // maximum value
    int32_t flat;      // center flat position, eg. flat == 8 means center is between -8 and 8
    int32_t fuzz;      // error tolerance, eg. fuzz == 4 means value is +/- 4 due to noise

    inline void clear() {
        valid = false;
        minValue = 0;
        maxValue = 0;
        flat = 0;
        fuzz = 0;
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

    /* The input device is external (not built-in). */
    INPUT_DEVICE_CLASS_EXTERNAL      = 0x80000000,
};

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

    virtual String8 getDeviceName(int32_t deviceId) const = 0;

    virtual void getConfiguration(int32_t deviceId, PropertyMap* outConfiguration) const = 0;

    virtual status_t getAbsoluteAxisInfo(int32_t deviceId, int axis,
            RawAbsoluteAxisInfo* outAxisInfo) const = 0;

    virtual bool hasRelativeAxis(int32_t deviceId, int axis) const = 0;

    virtual status_t mapKey(int32_t deviceId, int scancode,
            int32_t* outKeycode, uint32_t* outFlags) const = 0;

    virtual status_t mapAxis(int32_t deviceId, int scancode,
            AxisInfo* outAxisInfo) const = 0;

    // exclude a particular device from opening
    // this can be used to ignore input devices for sensors
    virtual void addExcludedDevice(const char* deviceName) = 0;

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

    /*
     * Examine key input devices for specific framework keycode support
     */
    virtual bool markSupportedKeyCodes(int32_t deviceId, size_t numCodes, const int32_t* keyCodes,
            uint8_t* outFlags) const = 0;

    virtual bool hasLed(int32_t deviceId, int32_t led) const = 0;
    virtual void setLedState(int32_t deviceId, int32_t led, bool on) = 0;

    virtual void getVirtualKeyDefinitions(int32_t deviceId,
            Vector<VirtualKeyDefinition>& outVirtualKeys) const = 0;

    virtual void dump(String8& dump) = 0;
};

class EventHub : public EventHubInterface
{
public:
    EventHub();

    status_t errorCheck() const;

    virtual uint32_t getDeviceClasses(int32_t deviceId) const;

    virtual String8 getDeviceName(int32_t deviceId) const;

    virtual void getConfiguration(int32_t deviceId, PropertyMap* outConfiguration) const;

    virtual status_t getAbsoluteAxisInfo(int32_t deviceId, int axis,
            RawAbsoluteAxisInfo* outAxisInfo) const;

    virtual bool hasRelativeAxis(int32_t deviceId, int axis) const;

    virtual status_t mapKey(int32_t deviceId, int scancode,
            int32_t* outKeycode, uint32_t* outFlags) const;

    virtual status_t mapAxis(int32_t deviceId, int scancode,
            AxisInfo* outAxisInfo) const;

    virtual void addExcludedDevice(const char* deviceName);

    virtual int32_t getScanCodeState(int32_t deviceId, int32_t scanCode) const;
    virtual int32_t getKeyCodeState(int32_t deviceId, int32_t keyCode) const;
    virtual int32_t getSwitchState(int32_t deviceId, int32_t sw) const;

    virtual bool markSupportedKeyCodes(int32_t deviceId, size_t numCodes,
            const int32_t* keyCodes, uint8_t* outFlags) const;

    virtual size_t getEvents(int timeoutMillis, RawEvent* buffer, size_t bufferSize);

    virtual bool hasLed(int32_t deviceId, int32_t led) const;
    virtual void setLedState(int32_t deviceId, int32_t led, bool on);

    virtual void getVirtualKeyDefinitions(int32_t deviceId,
            Vector<VirtualKeyDefinition>& outVirtualKeys) const;

    virtual void dump(String8& dump);

protected:
    virtual ~EventHub();
    
private:
    bool openPlatformInput(void);

    int openDevice(const char *devicePath);
    int closeDevice(const char *devicePath);
    int closeDeviceAtIndexLocked(int index);
    int scanDir(const char *dirname);
    int readNotify(int nfd);

    status_t mError;

    struct Device {
        Device* next;

        int fd;
        const int32_t id;
        const String8 path;
        const InputDeviceIdentifier identifier;

        uint32_t classes;
        uint8_t* keyBitmask;
        uint8_t* relBitmask;
        String8 configurationFile;
        PropertyMap* configuration;
        VirtualKeyMap* virtualKeyMap;
        KeyMap keyMap;

        Device(int fd, int32_t id, const String8& path, const InputDeviceIdentifier& identifier);
        ~Device();

        void close();
    };

    Device* getDeviceLocked(int32_t deviceId) const;
    bool hasKeycodeLocked(Device* device, int keycode) const;

    int32_t getScanCodeStateLocked(Device* device, int32_t scanCode) const;
    int32_t getKeyCodeStateLocked(Device* device, int32_t keyCode) const;
    int32_t getSwitchStateLocked(Device* device, int32_t sw) const;
    bool markSupportedKeyCodesLocked(Device* device, size_t numCodes,
            const int32_t* keyCodes, uint8_t* outFlags) const;

    void loadConfiguration(Device* device);
    status_t loadVirtualKeyMap(Device* device);
    status_t loadKeyMap(Device* device);
    void setKeyboardProperties(Device* device, bool builtInKeyboard);
    void clearKeyboardProperties(Device* device, bool builtInKeyboard);

    bool isExternalDevice(Device* device);

    // Protect all internal state.
    mutable Mutex mLock;

    // The actual id of the built-in keyboard, or -1 if none.
    // EventHub remaps the built-in keyboard to id 0 externally as required by the API.
    int32_t mBuiltInKeyboardId;

    int32_t mNextDeviceId;

    // Parallel arrays of fds and devices.
    // First index is reserved for inotify.
    Vector<struct pollfd> mFds;
    Vector<Device*> mDevices;

    Device *mOpeningDevices;
    Device *mClosingDevices;

    bool mOpened;
    bool mNeedToSendFinishedDeviceScan;
    List<String8> mExcludedDevices;

    // device ids that report particular switches.
    int32_t mSwitches[SW_MAX + 1];

    // The index of the next file descriptor that needs to be read.
    size_t mInputFdIndex;

    // Set to the number of CPUs.
    int32_t mNumCpus;
};

}; // namespace android

#endif // _RUNTIME_EVENT_HUB_H
