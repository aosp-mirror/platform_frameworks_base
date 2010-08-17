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

#include <utils/String8.h>
#include <utils/threads.h>
#include <utils/Log.h>
#include <utils/threads.h>
#include <utils/List.h>
#include <utils/Errors.h>

#include <linux/input.h>

struct pollfd;

namespace android {

class KeyLayoutMap;

/*
 * Grand Central Station for events.  With a single call to waitEvent()
 * you can wait for:
 *  - input events from the keypad of a real device
 *  - input events and meta-events (e.g. "quit") from the simulator
 *  - synthetic events from the runtime (e.g. "URL fetch completed")
 *  - real or forged "vsync" events
 *
 * Do not instantiate this class.  Instead, call startUp().
 */
class EventHub : public RefBase
{
public:
    EventHub();
    
    status_t errorCheck() const;
    
    // bit fields for classes of devices.
    enum {
        CLASS_KEYBOARD      = 0x00000001,
        CLASS_ALPHAKEY      = 0x00000002,
        CLASS_TOUCHSCREEN   = 0x00000004,
        CLASS_TRACKBALL     = 0x00000008,
        CLASS_TOUCHSCREEN_MT= 0x00000010,
        CLASS_DPAD          = 0x00000020
    };
    uint32_t getDeviceClasses(int32_t deviceId) const;
    
    String8 getDeviceName(int32_t deviceId) const;
    
    int getAbsoluteInfo(int32_t deviceId, int axis, int *outMinValue,
            int* outMaxValue, int* outFlat, int* outFuzz) const;
        
    int getSwitchState(int sw) const;
    int getSwitchState(int32_t deviceId, int sw) const;
    
    int getScancodeState(int key) const;
    int getScancodeState(int32_t deviceId, int key) const;
    
    int getKeycodeState(int key) const;
    int getKeycodeState(int32_t deviceId, int key) const;
    
    status_t scancodeToKeycode(int32_t deviceId, int scancode,
            int32_t* outKeycode, uint32_t* outFlags) const;

    // exclude a particular device from opening
    // this can be used to ignore input devices for sensors
    void addExcludedDevice(const char* deviceName);

    // special type codes when devices are added/removed.
    enum {
        DEVICE_ADDED = 0x10000000,
        DEVICE_REMOVED = 0x20000000
    };
    
    // examine key input devices for specific framework keycode support
    bool hasKeys(size_t numCodes, int32_t* keyCodes, uint8_t* outFlags);

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
        int             fd;
        device_t*       next;
        
        device_t(int32_t _id, const char* _path, const char* name);
        ~device_t();
    };

    device_t* getDevice(int32_t deviceId) const;
    bool hasKeycode(device_t* device, int keycode) const;
    
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
    int32_t         mSwitches[SW_MAX+1];
#endif
};

}; // namespace android

#endif // _RUNTIME_EVENT_HUB_H
