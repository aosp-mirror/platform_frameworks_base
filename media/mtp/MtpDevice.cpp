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

#define LOG_TAG "MtpDevice"
#include "utils/Log.h"

#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <errno.h>

#include <usbhost/usbhost.h>

#include "MtpDevice.h"
#include "MtpDebug.h"
#include "MtpDeviceInfo.h"
#include "MtpObjectInfo.h"
#include "MtpProperty.h"
#include "MtpStorageInfo.h"
#include "MtpStringBuffer.h"

namespace android {

MtpDevice::MtpDevice(struct usb_device* device, int interface,
            struct usb_endpoint *ep_in, struct usb_endpoint *ep_out,
            struct usb_endpoint *ep_intr)
    :   mDevice(device),
        mInterface(interface),
        mEndpointIn(ep_in),
        mEndpointOut(ep_out),
        mEndpointIntr(ep_intr),
        mDeviceInfo(NULL),
        mID(usb_device_get_unique_id(device)),
        mSessionID(0),
        mTransactionID(0)
{
}

MtpDevice::~MtpDevice() {
    close();
    for (int i = 0; i < mDeviceProperties.size(); i++)
        delete mDeviceProperties[i];
}

void MtpDevice::initialize() {
    openSession();
    mDeviceInfo = getDeviceInfo();
    if (mDeviceInfo) {
        mDeviceInfo->print();

        if (mDeviceInfo->mDeviceProperties) {
            int count = mDeviceInfo->mDeviceProperties->size();
            for (int i = 0; i < count; i++) {
                MtpDeviceProperty propCode = (*mDeviceInfo->mDeviceProperties)[i];
                MtpProperty* property = getDevicePropDesc(propCode);
                if (property) {
                    property->print();
                    mDeviceProperties.push(property);
                }
            }
        }
    }
}

void MtpDevice::close() {
    if (mDevice) {
        usb_device_release_interface(mDevice, mInterface);
        usb_device_close(mDevice);
        mDevice = NULL;
    }
}

const char* MtpDevice::getDeviceName() {
    if (mDevice)
        return usb_device_get_name(mDevice);
    else
        return "???";
}

bool MtpDevice::openSession() {
    mSessionID = 0;
    mTransactionID = 0;
    MtpSessionID newSession = 1;
    mRequest.reset();
    mRequest.setParameter(1, newSession);
    if (!sendRequest(MTP_OPERATION_OPEN_SESSION))
        return false;
    MtpResponseCode ret = readResponse();
    if (ret == MTP_RESPONSE_SESSION_ALREADY_OPEN)
        newSession = mResponse.getParameter(1);
    else if (ret != MTP_RESPONSE_OK)
        return false;

    mSessionID = newSession;
    mTransactionID = 1;
    return true;
}

bool MtpDevice::closeSession() {
    // FIXME
    return true;
}

MtpDeviceInfo* MtpDevice::getDeviceInfo() {
    mRequest.reset();
    if (!sendRequest(MTP_OPERATION_GET_DEVICE_INFO))
        return NULL;
    if (!readData())
        return NULL;
    MtpResponseCode ret = readResponse();
    if (ret == MTP_RESPONSE_OK) {
        MtpDeviceInfo* info = new MtpDeviceInfo;
        info->read(mData);
        return info;
    }
    return NULL;
}

MtpStorageIDList* MtpDevice::getStorageIDs() {
    mRequest.reset();
    if (!sendRequest(MTP_OPERATION_GET_STORAGE_IDS))
        return NULL;
    if (!readData())
        return NULL;
    MtpResponseCode ret = readResponse();
    if (ret == MTP_RESPONSE_OK) {
        return mData.getAUInt32();
    }
    return NULL;
}

MtpStorageInfo* MtpDevice::getStorageInfo(MtpStorageID storageID) {
    mRequest.reset();
    mRequest.setParameter(1, storageID);
    if (!sendRequest(MTP_OPERATION_GET_STORAGE_INFO))
        return NULL;
    if (!readData())
        return NULL;
    MtpResponseCode ret = readResponse();
    if (ret == MTP_RESPONSE_OK) {
        MtpStorageInfo* info = new MtpStorageInfo(storageID);
        info->read(mData);
        return info;
    }
    return NULL;
}

MtpObjectHandleList* MtpDevice::getObjectHandles(MtpStorageID storageID,
            MtpObjectFormat format, MtpObjectHandle parent) {
    mRequest.reset();
    mRequest.setParameter(1, storageID);
    mRequest.setParameter(2, format);
    mRequest.setParameter(3, parent);
    if (!sendRequest(MTP_OPERATION_GET_OBJECT_HANDLES))
        return NULL;
    if (!readData())
        return NULL;
    MtpResponseCode ret = readResponse();
    if (ret == MTP_RESPONSE_OK) {
        return mData.getAUInt32();
    }
    return NULL;
}

MtpObjectInfo* MtpDevice::getObjectInfo(MtpObjectHandle handle) {
    // FIXME - we might want to add some caching here

    mRequest.reset();
    mRequest.setParameter(1, handle);
    if (!sendRequest(MTP_OPERATION_GET_OBJECT_INFO))
        return NULL;
    if (!readData())
        return NULL;
    MtpResponseCode ret = readResponse();
    if (ret == MTP_RESPONSE_OK) {
        MtpObjectInfo* info = new MtpObjectInfo(handle);
        info->read(mData);
        return info;
    }
    return NULL;
}

void* MtpDevice::getThumbnail(MtpObjectHandle handle, int& outLength) {
    mRequest.reset();
    mRequest.setParameter(1, handle);
    if (sendRequest(MTP_OPERATION_GET_THUMB) && readData()) {
        MtpResponseCode ret = readResponse();
        if (ret == MTP_RESPONSE_OK) {
            return mData.getData(outLength);
        }
    }
    outLength = 0;
    return NULL;
}

bool MtpDevice::deleteObject(MtpObjectHandle handle) {
    mRequest.reset();
    mRequest.setParameter(1, handle);
    if (sendRequest(MTP_OPERATION_DELETE_OBJECT)) {
        MtpResponseCode ret = readResponse();
        if (ret == MTP_RESPONSE_OK)
            return true;
    }
    return false;
}

MtpObjectHandle MtpDevice::getParent(MtpObjectHandle handle) {
    MtpObjectInfo* info = getObjectInfo(handle);
    if (info)
        return info->mParent;
    else
        return -1;
}

MtpObjectHandle MtpDevice::getStorageID(MtpObjectHandle handle) {
    MtpObjectInfo* info = getObjectInfo(handle);
    if (info)
        return info->mStorageID;
    else
        return -1;
}

MtpProperty* MtpDevice::getDevicePropDesc(MtpDeviceProperty code) {
    mRequest.reset();
    mRequest.setParameter(1, code);
    if (!sendRequest(MTP_OPERATION_GET_DEVICE_PROP_DESC))
        return NULL;
    if (!readData())
        return NULL;
    MtpResponseCode ret = readResponse();
    if (ret == MTP_RESPONSE_OK) {
        MtpProperty* property = new MtpProperty;
        property->read(mData);
        return property;
    }
    return NULL;
}


bool MtpDevice::sendRequest(MtpOperationCode operation) {
    LOGD("sendRequest: %s\n", MtpDebug::getOperationCodeName(operation));
    mRequest.setOperationCode(operation);
    if (mTransactionID > 0)
        mRequest.setTransactionID(mTransactionID++);
    int ret = mRequest.write(mEndpointOut);
    mRequest.dump();
    return (ret > 0);
}

bool MtpDevice::sendData(MtpOperationCode operation) {
    LOGD("sendData\n");
    mData.setOperationCode(mRequest.getOperationCode());
    mData.setTransactionID(mRequest.getTransactionID());
    int ret = mData.write(mEndpointOut);
    mData.dump();
    return (ret > 0);
}

bool MtpDevice::readData() {
    mData.reset();
    int ret = mData.read(mEndpointIn);
    LOGD("readData returned %d\n", ret);
    if (ret >= MTP_CONTAINER_HEADER_SIZE) {
        mData.dump();
        return true;
    }
    else {
        LOGD("readResponse failed\n");
        return false;
    }
}

MtpResponseCode MtpDevice::readResponse() {
    LOGD("readResponse\n");
    int ret = mResponse.read(mEndpointIn);
    if (ret >= MTP_CONTAINER_HEADER_SIZE) {
        mResponse.dump();
        return mResponse.getResponseCode();
    }
    else {
        LOGD("readResponse failed\n");
        return -1;
    }
}

}  // namespace android
