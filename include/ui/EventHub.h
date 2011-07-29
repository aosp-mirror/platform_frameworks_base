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

#include <android/input.h>
#include <utils/String8.h>
#include <utils/threads.h>
#include <utils/Log.h>
#include <utils/threads.h>
#include <utils/List.h>
#include <utils/Errors.h>

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

class KeyLayoutMap;

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

    inline int32_t getRange() { return maxValue - minValue; }

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
    /* The input device is a keyboard. */
    INPUT_DEVICE_CLASS_KEYBOARD      = 0x00000001,

    /* The input device is an alpha-numeric keyboard (not just a dial pad). */
    INPUT_DEVICE_CLASS_ALPHAKEY      = 0x00000002,

    /* The input device is a touchscreen (either single-touch or multi-touch). */
    INPUT_DEVICE_CLASS_TOUCHSCREEN   = 0x00000004,

    /* The input device is a trackball. */
    INPUT_DEVICE_CLASS_TRACKBALL     = 0x00000008,

    /* The input device is a multi-touch touchscreen. */
    INPUT_DEVICE_CLASS_TOUCHSCREEN_MT= 0x00000010,

    /* The input device is a directional pad (implies keyboard, has DPAD keys). */
    INPUT_DEVICE_CLASS_DPAD          = 0x00000020,

    /* The input device is a gamepad (implies keyboard, has BUTTON keys). */
    INPUT_DEVICE_CLASS_GAMEPAD       = 0x00000040,

    /* The input device has switches. */
    INPUT_DEVICE_CLASS_SWITCH        = 0x00000080,
};

/*
 * Grand Central Station for events.
 *
 * The event hub aggregates input events received across all known input
 * devices on the system, including devices that may be emulated by the simulator
 * environment.  In addition, the event hub generates fake input events to indicate
 * when devices are added or removed.
 *
 * The event hub provies a stream of input events (via the getEvent function).
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
    };

    virtual uint32_t getDeviceClasses(int32_t deviceId) const = 0;

    virtual String8 getDeviceName(int32_t deviceId) const = 0;

    virtual status_t getAbsoluteAxisInfo(int32_t deviceId, int axis,
            RawAbsoluteAxisInfo* outAxisInfo) const = 0;

    virtual status_t scancodeToKeycode(int32_t deviceId, int scancode,
            int32_t* outKeycode, uint32_t* outFlags) const = 0;

    // exclude a particular device from opening
    // this can be used to ignore input devices for sensors
    virtual void addExcludedDevice(const char* deviceName) = 0;

    /*
     * Wait for the next event to become available and return it.
     * After returning, the EventHub holds onto a wake lock until the next call to getEvent.
     * This ensures that the device will not go to sleep while the event is being processed.
     * If the device needs to remain awake longer than that, then the caller is responsible
     * for taking care of it (say, by poking the power manager user activity timer).
     */
    virtual bool getEvent(RawEvent* outEvent) = 0;

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

    virtual void dump(String8& dump) = 0;
};

class EventHub : public EventHubInterface
{
public:
    EventHub();

    status_t errorCheck() const;

    virtual uint32_t getDeviceClasses(int32_t deviceId) const;
    
    virtual String8 getDeviceName(int32_t deviceId) const;
    
    virtual status_t getAbsoluteAxisInfo(int32_t deviceId, int axis,
            RawAbsoluteAxisInfo* outAxisInfo) const;

    virtual status_t scancodeToKeycode(int32_t deviceId, int scancode,
            int32_t* outKeycode, uint32_t* outFlags) const;

    virtual void addExcludedDevice(const char* deviceName);

    virtual int32_t getScanCodeState(int32_t deviceId, int32_t scanCode) const;
    virtual int32_t getKeyCodeState(int32_t deviceId, int32_t keyCode) const;
    virtual int32_t getSwitchState(int32_t deviceId, int32_t sw) const;

    virtual bool markSupportedKeyCodes(int32_t deviceId, size_t numCodes,
            const int32_t* keyCodes, uint8_t* outFlags) const;

    virtual bool getEvent(RawEvent* outEvent);

    virtual void dump(String8& dump);

protected:
    virtual ~EventHub();
    
private:
    bool openPlatformInput(void);

    int openDevice(const char *device);
    int closeDevice(const char *device);
    int scanDir(const char *dirname);
    int readNotify(int nfd);

    status_t mError;

    struct device_t {
        const int32_t   id;
        const String8   path;
        String8         name;
        uint32_t        classes;
        uint8_t*        keyBitmask;
        uint8_t*        switchBitmask;
        KeyLayoutMap*   layoutMap;
        String8         keylayoutFilename;
        int             fd;
        device_t*       next;
        
        device_t(int32_t _id, const char* _path, const char* name);
        ~device_t();
    };

    device_t* getDeviceLocked(int32_t deviceId) const;
    bool hasKeycodeLocked(device_t* device, int keycode) const;
    
    int32_t getScanCodeStateLocked(device_t* device, int32_t scanCode) const;
    int32_t getKeyCodeStateLocked(device_t* device, int32_t keyCode) const;
    int32_t getSwitchStateLocked(device_t* device, int32_t sw) const;
    bool markSupportedKeyCodesLocked(device_t* device, size_t numCodes,
            const int32_t* keyCodes, uint8_t* outFlags) const;

    // Protect all internal state.
    mutable Mutex   mLock;
    
    bool            mHaveFirstKeyboard;
    int32_t         mFirstKeyboardId; // the API is that the built-in keyboard is id 0, so map it
    
    struct device_ent {
        device_t* device;
        uint32_t seq;
    };
    device_ent      *mDevicesById;
    int             mNumDevicesById;
    
    device_t        *mOpeningDevices;
    device_t        *mClosingDevices;
    
    device_t        **mDevices;
    struct pollfd   *mFDs;
    int             mFDCount;

    bool            mOpened;
    bool            mNeedToSendFinishedDeviceScan;
    List<String8>   mExcludedDevices;

    // device ids that report particular switches.
#ifdef EV_SW
    int32_t         mSwitches[SW_MAX + 1];
#endif

    static const int INPUT_BUFFER_SIZE = 64;
    struct input_event mInputBufferData[INPUT_BUFFER_SIZE];
    int32_t mInputBufferIndex;
    int32_t mInputBufferCount;
    int32_t mInputDeviceIndex;
};

}; // namespace android

#endif // _RUNTIME_EVENT_HUB_H
