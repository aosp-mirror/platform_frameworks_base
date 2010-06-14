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
        DEVICE_ADDED = 0x10000000,
        DEVICE_REMOVED = 0x20000000
    };

    virtual uint32_t getDeviceClasses(int32_t deviceId) const = 0;

    virtual String8 getDeviceName(int32_t deviceId) const = 0;

    virtual int getAbsoluteInfo(int32_t deviceId, int axis, int *outMinValue,
            int* outMaxValue, int* outFlat, int* outFuzz) const = 0;

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
    virtual bool getEvent(int32_t* outDeviceId, int32_t* outType,
            int32_t* outScancode, int32_t* outKeycode, uint32_t *outFlags,
            int32_t* outValue, nsecs_t* outWhen) = 0;

    /*
     * Query current input state.
     *   deviceId may be -1 to search for the device automatically, filtered by class.
     *   deviceClasses may be -1 to ignore device class while searching.
     */
    virtual int32_t getScanCodeState(int32_t deviceId, int32_t deviceClasses,
            int32_t scanCode) const = 0;
    virtual int32_t getKeyCodeState(int32_t deviceId, int32_t deviceClasses,
            int32_t keyCode) const = 0;
    virtual int32_t getSwitchState(int32_t deviceId, int32_t deviceClasses,
            int32_t sw) const = 0;

    /*
     * Examine key input devices for specific framework keycode support
     */
    virtual bool hasKeys(size_t numCodes, const int32_t* keyCodes,
            uint8_t* outFlags) const = 0;
};

class EventHub : public EventHubInterface
{
public:
    EventHub();

    status_t errorCheck() const;

    virtual uint32_t getDeviceClasses(int32_t deviceId) const;
    
    virtual String8 getDeviceName(int32_t deviceId) const;
    
    virtual int getAbsoluteInfo(int32_t deviceId, int axis, int *outMinValue,
            int* outMaxValue, int* outFlat, int* outFuzz) const;
        
    virtual status_t scancodeToKeycode(int32_t deviceId, int scancode,
            int32_t* outKeycode, uint32_t* outFlags) const;

    virtual void addExcludedDevice(const char* deviceName);

    virtual int32_t getScanCodeState(int32_t deviceId, int32_t deviceClasses,
            int32_t scanCode) const;
    virtual int32_t getKeyCodeState(int32_t deviceId, int32_t deviceClasses,
            int32_t keyCode) const;
    virtual int32_t getSwitchState(int32_t deviceId, int32_t deviceClasses,
            int32_t sw) const;

    virtual bool hasKeys(size_t numCodes, const int32_t* keyCodes, uint8_t* outFlags) const;

    virtual bool getEvent(int32_t* outDeviceId, int32_t* outType,
            int32_t* outScancode, int32_t* outKeycode, uint32_t *outFlags,
            int32_t* outValue, nsecs_t* outWhen);

protected:
    virtual ~EventHub();
    
private:
    bool openPlatformInput(void);
    int32_t convertDeviceKey_TI_P2(int code);

    int open_device(const char *device);
    int close_device(const char *device);
    int scan_dir(const char *dirname);
    int read_notify(int nfd);

    status_t mError;

    struct device_t {
        const int32_t   id;
        const String8   path;
        String8         name;
        uint32_t        classes;
        uint8_t*        keyBitmask;
        KeyLayoutMap*   layoutMap;
        String8         keylayoutFilename;
        device_t*       next;
        
        device_t(int32_t _id, const char* _path, const char* name);
        ~device_t();
    };

    device_t* getDevice(int32_t deviceId) const;
    bool hasKeycode(device_t* device, int keycode) const;
    
    int32_t getScanCodeStateLocked(device_t* device, int32_t scanCode) const;
    int32_t getKeyCodeStateLocked(device_t* device, int32_t keyCode) const;
    int32_t getSwitchStateLocked(device_t* device, int32_t sw) const;

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
    List<String8>   mExcludedDevices;

    // device ids that report particular switches.
#ifdef EV_SW
    int32_t         mSwitches[SW_MAX + 1];
#endif
};

}; // namespace android

#endif // _RUNTIME_EVENT_HUB_H
