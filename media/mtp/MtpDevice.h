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

#ifndef _MTP_DEVICE_H
#define _MTP_DEVICE_H

#include "MtpRequestPacket.h"
#include "MtpDataPacket.h"
#include "MtpResponsePacket.h"
#include "MtpTypes.h"

#include <utils/threads.h>

struct usb_device;
struct usb_request;
struct usb_endpoint_descriptor;

namespace android {

class MtpDeviceInfo;
class MtpObjectInfo;
class MtpStorageInfo;

class MtpDevice {
private:
    struct usb_device*      mDevice;
    int                     mInterface;
    struct usb_request*     mRequestIn1;
    struct usb_request*     mRequestIn2;
    struct usb_request*     mRequestOut;
    struct usb_request*     mRequestIntr;
    MtpDeviceInfo*          mDeviceInfo;
    MtpPropertyList         mDeviceProperties;

    // current session ID
    MtpSessionID            mSessionID;
    // current transaction ID
    MtpTransactionID        mTransactionID;

    MtpRequestPacket        mRequest;
    MtpDataPacket           mData;
    MtpResponsePacket       mResponse;
    // set to true if we received a response packet instead of a data packet
    bool                    mReceivedResponse;

    // to ensure only one MTP transaction at a time
    Mutex                   mMutex;

public:
                            MtpDevice(struct usb_device* device, int interface,
                                    const struct usb_endpoint_descriptor *ep_in,
                                    const struct usb_endpoint_descriptor *ep_out,
                                    const struct usb_endpoint_descriptor *ep_intr);

    static MtpDevice*       open(const char* deviceName, int fd);

    virtual                 ~MtpDevice();

    void                    initialize();
    void                    close();
    void                    print();
    const char*             getDeviceName();

    bool                    openSession();
    bool                    closeSession();

    MtpDeviceInfo*          getDeviceInfo();
    MtpStorageIDList*       getStorageIDs();
    MtpStorageInfo*         getStorageInfo(MtpStorageID storageID);
    MtpObjectHandleList*    getObjectHandles(MtpStorageID storageID, MtpObjectFormat format,
                                    MtpObjectHandle parent);
    MtpObjectInfo*          getObjectInfo(MtpObjectHandle handle);
    void*                   getThumbnail(MtpObjectHandle handle, int& outLength);
    MtpObjectHandle         sendObjectInfo(MtpObjectInfo* info);
    bool                    sendObject(MtpObjectInfo* info, int srcFD);
    bool                    deleteObject(MtpObjectHandle handle);
    MtpObjectHandle         getParent(MtpObjectHandle handle);
    MtpObjectHandle         getStorageID(MtpObjectHandle handle);

    MtpObjectPropertyList*  getObjectPropsSupported(MtpObjectFormat format);

    MtpProperty*            getDevicePropDesc(MtpDeviceProperty code);
    MtpProperty*            getObjectPropDesc(MtpObjectProperty code, MtpObjectFormat format);

    bool                    readObject(MtpObjectHandle handle,
                                    bool (* callback)(void* data, int offset,
                                            int length, void* clientData),
                                    int objectSize, void* clientData);
    bool                    readObject(MtpObjectHandle handle, const char* destPath, int group,
                                    int perm);

private:
    bool                    sendRequest(MtpOperationCode operation);
    bool                    sendData();
    bool                    readData();
    bool                    writeDataHeader(MtpOperationCode operation, int dataLength);
    MtpResponseCode         readResponse();

};

}; // namespace android

#endif // _MTP_DEVICE_H
