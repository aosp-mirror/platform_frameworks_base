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

#include "MtpDebug.h"
#include "MtpDevice.h"
#include "MtpDeviceInfo.h"
#include "MtpObjectInfo.h"
#include "MtpProperty.h"
#include "MtpStorageInfo.h"
#include "MtpStringBuffer.h"
#include "MtpUtils.h"

#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <errno.h>
#include <endian.h>

#include <usbhost/usbhost.h>

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
    Mutex::Autolock autoLock(mMutex);

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
    Mutex::Autolock autoLock(mMutex);

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
    Mutex::Autolock autoLock(mMutex);

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
    Mutex::Autolock autoLock(mMutex);

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
    Mutex::Autolock autoLock(mMutex);

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
    Mutex::Autolock autoLock(mMutex);

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
    Mutex::Autolock autoLock(mMutex);

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

MtpObjectHandle MtpDevice::sendObjectInfo(MtpObjectInfo* info) {
    Mutex::Autolock autoLock(mMutex);

    mRequest.reset();
    MtpObjectHandle parent = info->mParent;
    if (parent == 0)
        parent = MTP_PARENT_ROOT;

    mRequest.setParameter(1, info->mStorageID);
    mRequest.setParameter(2, info->mParent);

    mData.putUInt32(info->mStorageID);
    mData.putUInt16(info->mFormat);
    mData.putUInt16(info->mProtectionStatus);
    mData.putUInt32(info->mCompressedSize);
    mData.putUInt16(info->mThumbFormat);
    mData.putUInt32(info->mThumbCompressedSize);
    mData.putUInt32(info->mThumbPixWidth);
    mData.putUInt32(info->mThumbPixHeight);
    mData.putUInt32(info->mImagePixWidth);
    mData.putUInt32(info->mImagePixHeight);
    mData.putUInt32(info->mImagePixDepth);
    mData.putUInt32(info->mParent);
    mData.putUInt16(info->mAssociationType);
    mData.putUInt32(info->mAssociationDesc);
    mData.putUInt32(info->mSequenceNumber);
    mData.putString(info->mName);

    char created[100], modified[100];
    formatDateTime(info->mDateCreated, created, sizeof(created));
    formatDateTime(info->mDateModified, modified, sizeof(modified));

    mData.putString(created);
    mData.putString(modified);
    if (info->mKeywords)
        mData.putString(info->mKeywords);
    else
        mData.putEmptyString();

   if (sendRequest(MTP_OPERATION_SEND_OBJECT_INFO) && sendData()) {
        MtpResponseCode ret = readResponse();
        if (ret == MTP_RESPONSE_OK) {
            info->mStorageID = mResponse.getParameter(1);
            info->mParent = mResponse.getParameter(2);
            info->mHandle = mResponse.getParameter(3);
            return info->mHandle;
        }
    }
    return (MtpObjectHandle)-1;
}

bool MtpDevice::sendObject(MtpObjectInfo* info, int srcFD) {
    Mutex::Autolock autoLock(mMutex);

    int remaining = info->mCompressedSize;
    mRequest.reset();
    mRequest.setParameter(1, info->mHandle);
    if (sendRequest(MTP_OPERATION_SEND_OBJECT)) {
        // send data header
        writeDataHeader(MTP_OPERATION_SEND_OBJECT, remaining);

        char buffer[65536];
        while (remaining > 0) {
            int count = read(srcFD, buffer, sizeof(buffer));
            if (count > 0) {
                int written = mData.write(mEndpointOut, buffer, count);
                // FIXME check error
                remaining -= count;
            } else {
                break;
            }
        }
    }
    MtpResponseCode ret = readResponse();
    return (remaining == 0 && ret == MTP_RESPONSE_OK);
}

bool MtpDevice::deleteObject(MtpObjectHandle handle) {
    Mutex::Autolock autoLock(mMutex);

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
    Mutex::Autolock autoLock(mMutex);

    mRequest.reset();
    mRequest.setParameter(1, code);
    if (!sendRequest(MTP_OPERATION_GET_DEVICE_PROP_DESC))
        return NULL;
    if (!readData())
        return NULL;
    MtpResponseCode ret = readResponse();
    if (ret == MTP_RESPONSE_OK) {
        MtpProperty* property = new MtpProperty;
        property->read(mData, true);
        return property;
    }
    return NULL;
}

class ReadObjectThread : public Thread {
private:
    MtpDevice*          mDevice;
    MtpObjectHandle     mHandle;
    int                 mObjectSize;
    void*               mInitialData;
    int                 mInitialDataLength;
    int                 mFD;

public:
    ReadObjectThread(MtpDevice* device, MtpObjectHandle handle, int objectSize)
        : mDevice(device),
          mHandle(handle),
          mObjectSize(objectSize),
          mInitialData(NULL),
          mInitialDataLength(0)
    {
    }

    virtual ~ReadObjectThread() {
        if (mFD >= 0)
            close(mFD);
        free(mInitialData);
    }

    // returns file descriptor
    int init() {
        mDevice->mRequest.reset();
        mDevice->mRequest.setParameter(1, mHandle);
        if (mDevice->sendRequest(MTP_OPERATION_GET_OBJECT)
                && mDevice->mData.readDataHeader(mDevice->mEndpointIn)) {

            // mData will contain header and possibly the beginning of the object data
            mInitialData = mDevice->mData.getData(mInitialDataLength);

            // create a pipe for the client to read from
            int pipefd[2];
            if (pipe(pipefd) < 0) {
                LOGE("pipe failed (%s)", strerror(errno));
                return -1;
            }

            mFD = pipefd[1];
            return pipefd[0];
        } else {
           return -1;
        }
    }

    virtual bool threadLoop() {
        int remaining = mObjectSize;
        if (mInitialData) {
            write(mFD, mInitialData, mInitialDataLength);
            remaining -= mInitialDataLength;
            free(mInitialData);
            mInitialData = NULL;
        }

        char buffer[16384];
        while (remaining > 0) {
            int readSize = (remaining > sizeof(buffer) ? sizeof(buffer) : remaining);
            int count = mDevice->mData.readData(mDevice->mEndpointIn, buffer, readSize);
            int written;
            if (count >= 0) {
                int written = write(mFD, buffer, count);
                // FIXME check error
                remaining -= count;
            } else {
                break;
            }
        }

        MtpResponseCode ret = mDevice->readResponse();
        mDevice->mMutex.unlock();
        return false;
    }
};

    // returns the file descriptor for a pipe to read the object's data
int MtpDevice::readObject(MtpObjectHandle handle, int objectSize) {
    mMutex.lock();

    ReadObjectThread* thread = new ReadObjectThread(this, handle, objectSize);
    int fd = thread->init();
    if (fd < 0) {
        delete thread;
        mMutex.unlock();
    } else {
        thread->run("ReadObjectThread");
    }
    return fd;
}

bool MtpDevice::sendRequest(MtpOperationCode operation) {
    LOGV("sendRequest: %s\n", MtpDebug::getOperationCodeName(operation));
    mRequest.setOperationCode(operation);
    if (mTransactionID > 0)
        mRequest.setTransactionID(mTransactionID++);
    int ret = mRequest.write(mEndpointOut);
    mRequest.dump();
    return (ret > 0);
}

bool MtpDevice::sendData() {
    LOGV("sendData\n");
    mData.setOperationCode(mRequest.getOperationCode());
    mData.setTransactionID(mRequest.getTransactionID());
    int ret = mData.write(mEndpointOut);
    mData.dump();
    return (ret > 0);
}

bool MtpDevice::readData() {
    mData.reset();
    int ret = mData.read(mEndpointIn);
    LOGV("readData returned %d\n", ret);
    if (ret >= MTP_CONTAINER_HEADER_SIZE) {
        mData.dump();
        return true;
    }
    else {
        LOGV("readResponse failed\n");
        return false;
    }
}

bool MtpDevice::writeDataHeader(MtpOperationCode operation, int dataLength) {
    mData.setOperationCode(operation);
    mData.setTransactionID(mRequest.getTransactionID());
    return (!mData.writeDataHeader(mEndpointOut, dataLength));
}

MtpResponseCode MtpDevice::readResponse() {
    LOGV("readResponse\n");
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
