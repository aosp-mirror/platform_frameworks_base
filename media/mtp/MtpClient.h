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

#ifndef _MTP_CLIENT_H
#define _MTP_CLIENT_H

#include "MtpTypes.h"

namespace android {

class MtpClient {
private:
    MtpDeviceList           mDeviceList;
    bool                    mStarted;

public:
                            MtpClient();
    virtual                 ~MtpClient();

    bool                    start();

    inline MtpDeviceList&   getDeviceList() { return mDeviceList; }
    MtpDevice*              getDevice(int id);


    virtual void            deviceAdded(MtpDevice *device) = 0;
    virtual void            deviceRemoved(MtpDevice *device) = 0;

private:
    void                    usbDeviceAdded(const char *devname);
    void                    usbDeviceRemoved(const char *devname);
    static void             usb_device_added(const char *devname, void* client_data);
    static void             usb_device_removed(const char *devname, void* client_data);
};

}; // namespace android

#endif // _MTP_CLIENT_H
