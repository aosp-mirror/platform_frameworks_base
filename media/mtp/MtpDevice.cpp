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
            const struct usb_endpoint_descriptor *ep_in,
            const struct usb_endpoint_descriptor *ep_out,
            const struct usb_endpoint_descriptor *ep_intr)
    :   mDevice(device),
        mInterface(interface),
        mRequestIn1(NULL),
        mRequestIn2(NULL),
        mRequestOut(NULL),
        mRequestIntr(NULL),
        mDeviceInfo(NULL),
        mID(usb_device_get_unique_id(device)),
        mSessionID(0),
        mTransactionID(0),
        mReceivedResponse(false)
{
    mRequestIn1 = usb_request_new(device, ep_in);
    mRequestIn2 = usb_request_new(device, ep_in);
    mRequestOut = usb_request_new(device, ep_out);
    mRequestIntr = usb_request_new(device, ep_intr);
}

MtpDevice::~MtpDevice() {
    close();
    for (int i = 0; i < mDeviceProperties.size(); i++)
        delete mDeviceProperties[i];
    usb_request_free(mRequestIn1);
    usb_request_free(mRequestIn2);
    usb_request_free(mRequestOut);
    usb_request_free(mRequestIntr);
}

void MtpDevice::initialize() {
    openSession();
    mDeviceInfo = getDeviceInfo();
    if (mDeviceInfo) {
        if (mDeviceInfo->mDeviceProperties) {
            int count = mDeviceInfo->mDeviceProperties->size();
            for (int i = 0; i < count; i++) {
                MtpDeviceProperty propCode = (*mDeviceInfo->mDeviceProperties)[i];
                MtpProperty* property = getDevicePropDesc(propCode);
                if (property)
                    mDeviceProperties.push(property);
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

void MtpDevice::print() {
    if (mDeviceInfo) {
        mDeviceInfo->print();

        if (mDeviceInfo->mDeviceProperties) {
            LOGI("***** DEVICE PROPERTIES *****\n");
            int count = mDeviceInfo->mDeviceProperties->size();
            for (int i = 0; i < count; i++) {
                MtpDeviceProperty propCode = (*mDeviceInfo->mDeviceProperties)[i];
                MtpProperty* property = getDevicePropDesc(propCode);
                if (property) {
                    property->print();
                }
            }
        }
    }

    if (mDeviceInfo->mPlaybackFormats) {
            LOGI("***** OBJECT PROPERTIES *****\n");
        int count = mDeviceInfo->mPlaybackFormats->size();
        for (int i = 0; i < count; i++) {
            MtpObjectFormat format = (*mDeviceInfo->mPlaybackFormats)[i];
            LOGI("*** FORMAT: %s\n", MtpDebug::getFormatCodeName(format));
            MtpObjectPropertyList* props = getObjectPropsSupported(format);
            if (props) {
                for (int j = 0; j < props->size(); j++) {
                    MtpObjectProperty prop = (*props)[j];
                    MtpProperty* property = getObjectPropDesc(prop, format);
                    if (property)
                        property->print();
                    else
                        LOGE("could not fetch property: %s",
                                MtpDebug::getObjectPropCodeName(prop));
                }
            }
        }
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
                int written = mData.write(mRequestOut, buffer, count);
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

MtpObjectPropertyList* MtpDevice::getObjectPropsSupported(MtpObjectFormat format) {
    Mutex::Autolock autoLock(mMutex);

    mRequest.reset();
    mRequest.setParameter(1, format);
    if (!sendRequest(MTP_OPERATION_GET_OBJECT_PROPS_SUPPORTED))
        return NULL;
    if (!readData())
        return NULL;
    MtpResponseCode ret = readResponse();
    if (ret == MTP_RESPONSE_OK) {
        return mData.getAUInt16();
    }
    return NULL;

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
        property->read(mData);
        return property;
    }
    return NULL;
}

MtpProperty* MtpDevice::getObjectPropDesc(MtpObjectProperty code, MtpObjectFormat format) {
    Mutex::Autolock autoLock(mMutex);

    mRequest.reset();
    mRequest.setParameter(1, code);
    mRequest.setParameter(2, format);
    if (!sendRequest(MTP_OPERATION_GET_OBJECT_PROP_DESC))
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

// reads the object's data and writes it to the specified file path
bool MtpDevice::readObject(MtpObjectHandle handle, const char* destPath, int group, int perm) {
    LOGD("readObject: %s", destPath);
    int fd = ::open(destPath, O_RDWR | O_CREAT | O_TRUNC);
    if (fd < 0) {
        LOGE("open failed for %s", destPath);
        return false;
    }

    fchown(fd, getuid(), group);
    // set permissions
    int mask = umask(0);
    fchmod(fd, perm);
    umask(mask);

    Mutex::Autolock autoLock(mMutex);
    bool result = false;

    mRequest.reset();
    mRequest.setParameter(1, handle);
    if (sendRequest(MTP_OPERATION_GET_OBJECT)
            && mData.readDataHeader(mRequestIn1)) {
        uint32_t length = mData.getContainerLength();
        if (length < MTP_CONTAINER_HEADER_SIZE)
            goto fail;
        length -= MTP_CONTAINER_HEADER_SIZE;
        uint32_t remaining = length;

        int initialDataLength = 0;
        void* initialData = mData.getData(initialDataLength);
        if (initialData) {
            if (initialDataLength > 0) {
                if (write(fd, initialData, initialDataLength) != initialDataLength)
                    goto fail;
                remaining -= initialDataLength;
            }
            free(initialData);
        }

        // USB reads greater than 16K don't work
        char buffer1[16384], buffer2[16384];
        mRequestIn1->buffer = buffer1;
        mRequestIn2->buffer = buffer2;
        struct usb_request* req = mRequestIn1;
        void* writeBuffer = NULL;
        int writeLength = 0;

        while (remaining > 0 || writeBuffer) {
            if (remaining > 0) {
                // queue up a read request
                req->buffer_length = (remaining > sizeof(buffer1) ? sizeof(buffer1) : remaining);
                if (mData.readDataAsync(req)) {
                    LOGE("readDataAsync failed");
                    goto fail;
                }
            } else {
                req = NULL;
            }

            if (writeBuffer) {
                // write previous buffer
                if (write(fd, writeBuffer, writeLength) != writeLength) {
                    LOGE("write failed");
                    // wait for pending read before failing
                    if (req)
                        mData.readDataWait(mDevice);
                    goto fail;
                }
                writeBuffer = NULL;
            }

            // wait for read to complete
            if (req) {
                int read = mData.readDataWait(mDevice);
                if (read < 0)
                    goto fail;

                writeBuffer = req->buffer;
                writeLength = read;
                remaining -= read;
                req = (req == mRequestIn1 ? mRequestIn2 : mRequestIn1);
            }
        }

        MtpResponseCode response = readResponse();
        if (response == MTP_RESPONSE_OK)
            result = true;
    }

fail:
    ::close(fd);
    return result;
}

bool MtpDevice::sendRequest(MtpOperationCode operation) {
    LOGV("sendRequest: %s\n", MtpDebug::getOperationCodeName(operation));
    mReceivedResponse = false;
    mRequest.setOperationCode(operation);
    if (mTransactionID > 0)
        mRequest.setTransactionID(mTransactionID++);
    int ret = mRequest.write(mRequestOut);
    mRequest.dump();
    return (ret > 0);
}

bool MtpDevice::sendData() {
    LOGV("sendData\n");
    mData.setOperationCode(mRequest.getOperationCode());
    mData.setTransactionID(mRequest.getTransactionID());
    int ret = mData.write(mRequestOut);
    mData.dump();
    return (ret > 0);
}

bool MtpDevice::readData() {
    mData.reset();
    int ret = mData.read(mRequestIn1);
    LOGV("readData returned %d\n", ret);
    if (ret >= MTP_CONTAINER_HEADER_SIZE) {
        if (mData.getContainerType() == MTP_CONTAINER_TYPE_RESPONSE) {
            LOGD("got response packet instead of data packet");
            // we got a response packet rather than data
            // copy it to mResponse
            mResponse.copyFrom(mData);
            mReceivedResponse = true;
            return false;
        }
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
    return (!mData.writeDataHeader(mRequestOut, dataLength));
}

MtpResponseCode MtpDevice::readResponse() {
    LOGV("readResponse\n");
    if (mReceivedResponse) {
        mReceivedResponse = false;
        return mResponse.getResponseCode();
    }
    int ret = mResponse.read(mRequestIn1);
    if (ret >= MTP_CONTAINER_HEADER_SIZE) {
        mResponse.dump();
        return mResponse.getResponseCode();
    } else {
        LOGD("readResponse failed\n");
        return -1;
    }
}

}  // namespace android
