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

#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/stat.h>
#include <dirent.h>

#include <cutils/properties.h>

#define LOG_TAG "MtpServer"

#include "MtpDebug.h"
#include "MtpDatabase.h"
#include "MtpObjectInfo.h"
#include "MtpProperty.h"
#include "MtpServer.h"
#include "MtpStorage.h"
#include "MtpStringBuffer.h"

#include <linux/usb/f_mtp.h>

namespace android {

static const MtpOperationCode kSupportedOperationCodes[] = {
    MTP_OPERATION_GET_DEVICE_INFO,
    MTP_OPERATION_OPEN_SESSION,
    MTP_OPERATION_CLOSE_SESSION,
    MTP_OPERATION_GET_STORAGE_IDS,
    MTP_OPERATION_GET_STORAGE_INFO,
    MTP_OPERATION_GET_NUM_OBJECTS,
    MTP_OPERATION_GET_OBJECT_HANDLES,
    MTP_OPERATION_GET_OBJECT_INFO,
    MTP_OPERATION_GET_OBJECT,
    MTP_OPERATION_GET_THUMB,
    MTP_OPERATION_DELETE_OBJECT,
    MTP_OPERATION_SEND_OBJECT_INFO,
    MTP_OPERATION_SEND_OBJECT,
//    MTP_OPERATION_INITIATE_CAPTURE,
//    MTP_OPERATION_FORMAT_STORE,
//    MTP_OPERATION_RESET_DEVICE,
//    MTP_OPERATION_SELF_TEST,
//    MTP_OPERATION_SET_OBJECT_PROTECTION,
//    MTP_OPERATION_POWER_DOWN,
    MTP_OPERATION_GET_DEVICE_PROP_DESC,
    MTP_OPERATION_GET_DEVICE_PROP_VALUE,
    MTP_OPERATION_SET_DEVICE_PROP_VALUE,
    MTP_OPERATION_RESET_DEVICE_PROP_VALUE,
//    MTP_OPERATION_TERMINATE_OPEN_CAPTURE,
//    MTP_OPERATION_MOVE_OBJECT,
//    MTP_OPERATION_COPY_OBJECT,
    MTP_OPERATION_GET_PARTIAL_OBJECT,
//    MTP_OPERATION_INITIATE_OPEN_CAPTURE,
    MTP_OPERATION_GET_OBJECT_PROPS_SUPPORTED,
    MTP_OPERATION_GET_OBJECT_PROP_DESC,
    MTP_OPERATION_GET_OBJECT_PROP_VALUE,
    MTP_OPERATION_SET_OBJECT_PROP_VALUE,
    MTP_OPERATION_GET_OBJECT_PROP_LIST,
//    MTP_OPERATION_SET_OBJECT_PROP_LIST,
//    MTP_OPERATION_GET_INTERDEPENDENT_PROP_DESC,
//    MTP_OPERATION_SEND_OBJECT_PROP_LIST,
    MTP_OPERATION_GET_OBJECT_REFERENCES,
    MTP_OPERATION_SET_OBJECT_REFERENCES,
//    MTP_OPERATION_SKIP,
    // Android extension for direct file IO
    MTP_OPERATION_GET_PARTIAL_OBJECT_64,
    MTP_OPERATION_SEND_PARTIAL_OBJECT,
    MTP_OPERATION_TRUNCATE_OBJECT,
    MTP_OPERATION_BEGIN_EDIT_OBJECT,
    MTP_OPERATION_END_EDIT_OBJECT,
};

static const MtpEventCode kSupportedEventCodes[] = {
    MTP_EVENT_OBJECT_ADDED,
    MTP_EVENT_OBJECT_REMOVED,
    MTP_EVENT_STORE_ADDED,
    MTP_EVENT_STORE_REMOVED,
};

MtpServer::MtpServer(int fd, MtpDatabase* database, bool ptp,
                    int fileGroup, int filePerm, int directoryPerm)
    :   mFD(fd),
        mDatabase(database),
        mPtp(ptp),
        mFileGroup(fileGroup),
        mFilePermission(filePerm),
        mDirectoryPermission(directoryPerm),
        mSessionID(0),
        mSessionOpen(false),
        mSendObjectHandle(kInvalidObjectHandle),
        mSendObjectFormat(0),
        mSendObjectFileSize(0)
{
}

MtpServer::~MtpServer() {
}

void MtpServer::addStorage(MtpStorage* storage) {
    Mutex::Autolock autoLock(mMutex);

    mStorages.push(storage);
    sendStoreAdded(storage->getStorageID());
}

void MtpServer::removeStorage(MtpStorage* storage) {
    Mutex::Autolock autoLock(mMutex);

    for (int i = 0; i < mStorages.size(); i++) {
        if (mStorages[i] == storage) {
            mStorages.removeAt(i);
            sendStoreRemoved(storage->getStorageID());
            break;
        }
    }
}

MtpStorage* MtpServer::getStorage(MtpStorageID id) {
    if (id == 0)
        return mStorages[0];
    for (int i = 0; i < mStorages.size(); i++) {
        MtpStorage* storage = mStorages[i];
        if (storage->getStorageID() == id)
            return storage;
    }
    return NULL;
}

bool MtpServer::hasStorage(MtpStorageID id) {
    if (id == 0 || id == 0xFFFFFFFF)
        return mStorages.size() > 0;
    return (getStorage(id) != NULL);
}

void MtpServer::run() {
    int fd = mFD;

    ALOGV("MtpServer::run fd: %d\n", fd);

    while (1) {
        int ret = mRequest.read(fd);
        if (ret < 0) {
            ALOGV("request read returned %d, errno: %d", ret, errno);
            if (errno == ECANCELED) {
                // return to top of loop and wait for next command
                continue;
            }
            break;
        }
        MtpOperationCode operation = mRequest.getOperationCode();
        MtpTransactionID transaction = mRequest.getTransactionID();

        ALOGV("operation: %s", MtpDebug::getOperationCodeName(operation));
        mRequest.dump();

        // FIXME need to generalize this
        bool dataIn = (operation == MTP_OPERATION_SEND_OBJECT_INFO
                    || operation == MTP_OPERATION_SET_OBJECT_REFERENCES
                    || operation == MTP_OPERATION_SET_OBJECT_PROP_VALUE
                    || operation == MTP_OPERATION_SET_DEVICE_PROP_VALUE);
        if (dataIn) {
            int ret = mData.read(fd);
            if (ret < 0) {
                LOGE("data read returned %d, errno: %d", ret, errno);
                if (errno == ECANCELED) {
                    // return to top of loop and wait for next command
                    continue;
                }
                break;
            }
            ALOGV("received data:");
            mData.dump();
        } else {
            mData.reset();
        }

        if (handleRequest()) {
            if (!dataIn && mData.hasData()) {
                mData.setOperationCode(operation);
                mData.setTransactionID(transaction);
                ALOGV("sending data:");
                mData.dump();
                ret = mData.write(fd);
                if (ret < 0) {
                    LOGE("request write returned %d, errno: %d", ret, errno);
                    if (errno == ECANCELED) {
                        // return to top of loop and wait for next command
                        continue;
                    }
                    break;
                }
            }

            mResponse.setTransactionID(transaction);
            ALOGV("sending response %04X", mResponse.getResponseCode());
            ret = mResponse.write(fd);
            mResponse.dump();
            if (ret < 0) {
                LOGE("request write returned %d, errno: %d", ret, errno);
                if (errno == ECANCELED) {
                    // return to top of loop and wait for next command
                    continue;
                }
                break;
            }
        } else {
            ALOGV("skipping response\n");
        }
    }

    // commit any open edits
    int count = mObjectEditList.size();
    for (int i = 0; i < count; i++) {
        ObjectEdit* edit = mObjectEditList[i];
        commitEdit(edit);
        delete edit;
    }
    mObjectEditList.clear();

    if (mSessionOpen)
        mDatabase->sessionEnded();
    close(fd);
    mFD = -1;
}

void MtpServer::sendObjectAdded(MtpObjectHandle handle) {
    ALOGV("sendObjectAdded %d\n", handle);
    sendEvent(MTP_EVENT_OBJECT_ADDED, handle);
}

void MtpServer::sendObjectRemoved(MtpObjectHandle handle) {
    ALOGV("sendObjectRemoved %d\n", handle);
    sendEvent(MTP_EVENT_OBJECT_REMOVED, handle);
}

void MtpServer::sendStoreAdded(MtpStorageID id) {
    ALOGV("sendStoreAdded %08X\n", id);
    sendEvent(MTP_EVENT_STORE_ADDED, id);
}

void MtpServer::sendStoreRemoved(MtpStorageID id) {
    ALOGV("sendStoreRemoved %08X\n", id);
    sendEvent(MTP_EVENT_STORE_REMOVED, id);
}

void MtpServer::sendEvent(MtpEventCode code, uint32_t param1) {
    if (mSessionOpen) {
        mEvent.setEventCode(code);
        mEvent.setTransactionID(mRequest.getTransactionID());
        mEvent.setParameter(1, param1);
        int ret = mEvent.write(mFD);
        ALOGV("mEvent.write returned %d\n", ret);
    }
}

void MtpServer::addEditObject(MtpObjectHandle handle, MtpString& path,
        uint64_t size, MtpObjectFormat format, int fd) {
    ObjectEdit*  edit = new ObjectEdit(handle, path, size, format, fd);
    mObjectEditList.add(edit);
}

MtpServer::ObjectEdit* MtpServer::getEditObject(MtpObjectHandle handle) {
    int count = mObjectEditList.size();
    for (int i = 0; i < count; i++) {
        ObjectEdit* edit = mObjectEditList[i];
        if (edit->mHandle == handle) return edit;
    }
    return NULL;
}

void MtpServer::removeEditObject(MtpObjectHandle handle) {
    int count = mObjectEditList.size();
    for (int i = 0; i < count; i++) {
        ObjectEdit* edit = mObjectEditList[i];
        if (edit->mHandle == handle) {
            delete edit;
            mObjectEditList.removeAt(i);
            return;
        }
    }
    LOGE("ObjectEdit not found in removeEditObject");
}

void MtpServer::commitEdit(ObjectEdit* edit) {
    mDatabase->endSendObject((const char *)edit->mPath, edit->mHandle, edit->mFormat, true);
}


bool MtpServer::handleRequest() {
    Mutex::Autolock autoLock(mMutex);

    MtpOperationCode operation = mRequest.getOperationCode();
    MtpResponseCode response;

    mResponse.reset();

    if (mSendObjectHandle != kInvalidObjectHandle && operation != MTP_OPERATION_SEND_OBJECT) {
        // FIXME - need to delete mSendObjectHandle from the database
        LOGE("expected SendObject after SendObjectInfo");
        mSendObjectHandle = kInvalidObjectHandle;
    }

    switch (operation) {
        case MTP_OPERATION_GET_DEVICE_INFO:
            response = doGetDeviceInfo();
            break;
        case MTP_OPERATION_OPEN_SESSION:
            response = doOpenSession();
            break;
        case MTP_OPERATION_CLOSE_SESSION:
            response = doCloseSession();
            break;
        case MTP_OPERATION_GET_STORAGE_IDS:
            response = doGetStorageIDs();
            break;
         case MTP_OPERATION_GET_STORAGE_INFO:
            response = doGetStorageInfo();
            break;
        case MTP_OPERATION_GET_OBJECT_PROPS_SUPPORTED:
            response = doGetObjectPropsSupported();
            break;
        case MTP_OPERATION_GET_OBJECT_HANDLES:
            response = doGetObjectHandles();
            break;
        case MTP_OPERATION_GET_NUM_OBJECTS:
            response = doGetNumObjects();
            break;
        case MTP_OPERATION_GET_OBJECT_REFERENCES:
            response = doGetObjectReferences();
            break;
        case MTP_OPERATION_SET_OBJECT_REFERENCES:
            response = doSetObjectReferences();
            break;
        case MTP_OPERATION_GET_OBJECT_PROP_VALUE:
            response = doGetObjectPropValue();
            break;
        case MTP_OPERATION_SET_OBJECT_PROP_VALUE:
            response = doSetObjectPropValue();
            break;
        case MTP_OPERATION_GET_DEVICE_PROP_VALUE:
            response = doGetDevicePropValue();
            break;
        case MTP_OPERATION_SET_DEVICE_PROP_VALUE:
            response = doSetDevicePropValue();
            break;
        case MTP_OPERATION_RESET_DEVICE_PROP_VALUE:
            response = doResetDevicePropValue();
            break;
        case MTP_OPERATION_GET_OBJECT_PROP_LIST:
            response = doGetObjectPropList();
            break;
        case MTP_OPERATION_GET_OBJECT_INFO:
            response = doGetObjectInfo();
            break;
        case MTP_OPERATION_GET_OBJECT:
            response = doGetObject();
            break;
        case MTP_OPERATION_GET_THUMB:
            response = doGetThumb();
            break;
        case MTP_OPERATION_GET_PARTIAL_OBJECT:
        case MTP_OPERATION_GET_PARTIAL_OBJECT_64:
            response = doGetPartialObject(operation);
            break;
        case MTP_OPERATION_SEND_OBJECT_INFO:
            response = doSendObjectInfo();
            break;
        case MTP_OPERATION_SEND_OBJECT:
            response = doSendObject();
            break;
        case MTP_OPERATION_DELETE_OBJECT:
            response = doDeleteObject();
            break;
        case MTP_OPERATION_GET_OBJECT_PROP_DESC:
            response = doGetObjectPropDesc();
            break;
        case MTP_OPERATION_GET_DEVICE_PROP_DESC:
            response = doGetDevicePropDesc();
            break;
        case MTP_OPERATION_SEND_PARTIAL_OBJECT:
            response = doSendPartialObject();
            break;
        case MTP_OPERATION_TRUNCATE_OBJECT:
            response = doTruncateObject();
            break;
        case MTP_OPERATION_BEGIN_EDIT_OBJECT:
            response = doBeginEditObject();
            break;
        case MTP_OPERATION_END_EDIT_OBJECT:
            response = doEndEditObject();
            break;
        default:
            LOGE("got unsupported command %s", MtpDebug::getOperationCodeName(operation));
            response = MTP_RESPONSE_OPERATION_NOT_SUPPORTED;
            break;
    }

    if (response == MTP_RESPONSE_TRANSACTION_CANCELLED)
        return false;
    mResponse.setResponseCode(response);
    return true;
}

MtpResponseCode MtpServer::doGetDeviceInfo() {
    MtpStringBuffer   string;
    char prop_value[PROPERTY_VALUE_MAX];

    MtpObjectFormatList* playbackFormats = mDatabase->getSupportedPlaybackFormats();
    MtpObjectFormatList* captureFormats = mDatabase->getSupportedCaptureFormats();
    MtpDevicePropertyList* deviceProperties = mDatabase->getSupportedDeviceProperties();

    // fill in device info
    mData.putUInt16(MTP_STANDARD_VERSION);
    if (mPtp) {
        mData.putUInt32(0);
    } else {
        // MTP Vendor Extension ID
        mData.putUInt32(6);
    }
    mData.putUInt16(MTP_STANDARD_VERSION);
    if (mPtp) {
        // no extensions
        string.set("");
    } else {
        // MTP extensions
        string.set("microsoft.com: 1.0; android.com: 1.0;");
    }
    mData.putString(string); // MTP Extensions
    mData.putUInt16(0); //Functional Mode
    mData.putAUInt16(kSupportedOperationCodes,
            sizeof(kSupportedOperationCodes) / sizeof(uint16_t)); // Operations Supported
    mData.putAUInt16(kSupportedEventCodes,
            sizeof(kSupportedEventCodes) / sizeof(uint16_t)); // Events Supported
    mData.putAUInt16(deviceProperties); // Device Properties Supported
    mData.putAUInt16(captureFormats); // Capture Formats
    mData.putAUInt16(playbackFormats);  // Playback Formats

    property_get("ro.product.manufacturer", prop_value, "unknown manufacturer");
    string.set(prop_value);
    mData.putString(string);   // Manufacturer

    property_get("ro.product.model", prop_value, "MTP Device");
    string.set(prop_value);
    mData.putString(string);   // Model
    string.set("1.0");
    mData.putString(string);   // Device Version

    property_get("ro.serialno", prop_value, "????????");
    string.set(prop_value);
    mData.putString(string);   // Serial Number

    delete playbackFormats;
    delete captureFormats;
    delete deviceProperties;

    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doOpenSession() {
    if (mSessionOpen) {
        mResponse.setParameter(1, mSessionID);
        return MTP_RESPONSE_SESSION_ALREADY_OPEN;
    }
    mSessionID = mRequest.getParameter(1);
    mSessionOpen = true;

    mDatabase->sessionStarted();

    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doCloseSession() {
    if (!mSessionOpen)
        return MTP_RESPONSE_SESSION_NOT_OPEN;
    mSessionID = 0;
    mSessionOpen = false;
    mDatabase->sessionEnded();
    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doGetStorageIDs() {
    if (!mSessionOpen)
        return MTP_RESPONSE_SESSION_NOT_OPEN;

    int count = mStorages.size();
    mData.putUInt32(count);
    for (int i = 0; i < count; i++)
        mData.putUInt32(mStorages[i]->getStorageID());

    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doGetStorageInfo() {
    MtpStringBuffer   string;

    if (!mSessionOpen)
        return MTP_RESPONSE_SESSION_NOT_OPEN;
    MtpStorageID id = mRequest.getParameter(1);
    MtpStorage* storage = getStorage(id);
    if (!storage)
        return MTP_RESPONSE_INVALID_STORAGE_ID;

    mData.putUInt16(storage->getType());
    mData.putUInt16(storage->getFileSystemType());
    mData.putUInt16(storage->getAccessCapability());
    mData.putUInt64(storage->getMaxCapacity());
    mData.putUInt64(storage->getFreeSpace());
    mData.putUInt32(1024*1024*1024); // Free Space in Objects
    string.set(storage->getDescription());
    mData.putString(string);
    mData.putEmptyString();   // Volume Identifier

    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doGetObjectPropsSupported() {
    if (!mSessionOpen)
        return MTP_RESPONSE_SESSION_NOT_OPEN;
    MtpObjectFormat format = mRequest.getParameter(1);
    MtpObjectPropertyList* properties = mDatabase->getSupportedObjectProperties(format);
    mData.putAUInt16(properties);
    delete properties;
    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doGetObjectHandles() {
    if (!mSessionOpen)
        return MTP_RESPONSE_SESSION_NOT_OPEN;
    MtpStorageID storageID = mRequest.getParameter(1);      // 0xFFFFFFFF for all storage
    MtpObjectFormat format = mRequest.getParameter(2);      // 0 for all formats
    MtpObjectHandle parent = mRequest.getParameter(3);      // 0xFFFFFFFF for objects with no parent
                                                            // 0x00000000 for all objects

    if (!hasStorage(storageID))
        return MTP_RESPONSE_INVALID_STORAGE_ID;

    MtpObjectHandleList* handles = mDatabase->getObjectList(storageID, format, parent);
    mData.putAUInt32(handles);
    delete handles;
    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doGetNumObjects() {
    if (!mSessionOpen)
        return MTP_RESPONSE_SESSION_NOT_OPEN;
    MtpStorageID storageID = mRequest.getParameter(1);      // 0xFFFFFFFF for all storage
    MtpObjectFormat format = mRequest.getParameter(2);      // 0 for all formats
    MtpObjectHandle parent = mRequest.getParameter(3);      // 0xFFFFFFFF for objects with no parent
                                                            // 0x00000000 for all objects
    if (!hasStorage(storageID))
        return MTP_RESPONSE_INVALID_STORAGE_ID;

    int count = mDatabase->getNumObjects(storageID, format, parent);
    if (count >= 0) {
        mResponse.setParameter(1, count);
        return MTP_RESPONSE_OK;
    } else {
        mResponse.setParameter(1, 0);
        return MTP_RESPONSE_INVALID_OBJECT_HANDLE;
    }
}

MtpResponseCode MtpServer::doGetObjectReferences() {
    if (!mSessionOpen)
        return MTP_RESPONSE_SESSION_NOT_OPEN;
    if (!hasStorage())
        return MTP_RESPONSE_INVALID_OBJECT_HANDLE;
    MtpObjectHandle handle = mRequest.getParameter(1);

    // FIXME - check for invalid object handle
    MtpObjectHandleList* handles = mDatabase->getObjectReferences(handle);
    if (handles) {
        mData.putAUInt32(handles);
        delete handles;
    } else {
        mData.putEmptyArray();
    }
    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doSetObjectReferences() {
    if (!mSessionOpen)
        return MTP_RESPONSE_SESSION_NOT_OPEN;
    if (!hasStorage())
        return MTP_RESPONSE_INVALID_OBJECT_HANDLE;
    MtpStorageID handle = mRequest.getParameter(1);

    MtpObjectHandleList* references = mData.getAUInt32();
    MtpResponseCode result = mDatabase->setObjectReferences(handle, references);
    delete references;
    return result;
}

MtpResponseCode MtpServer::doGetObjectPropValue() {
    if (!hasStorage())
        return MTP_RESPONSE_INVALID_OBJECT_HANDLE;
    MtpObjectHandle handle = mRequest.getParameter(1);
    MtpObjectProperty property = mRequest.getParameter(2);
    ALOGV("GetObjectPropValue %d %s\n", handle,
            MtpDebug::getObjectPropCodeName(property));

    return mDatabase->getObjectPropertyValue(handle, property, mData);
}

MtpResponseCode MtpServer::doSetObjectPropValue() {
    if (!hasStorage())
        return MTP_RESPONSE_INVALID_OBJECT_HANDLE;
    MtpObjectHandle handle = mRequest.getParameter(1);
    MtpObjectProperty property = mRequest.getParameter(2);
    ALOGV("SetObjectPropValue %d %s\n", handle,
            MtpDebug::getObjectPropCodeName(property));

    return mDatabase->setObjectPropertyValue(handle, property, mData);
}

MtpResponseCode MtpServer::doGetDevicePropValue() {
    MtpDeviceProperty property = mRequest.getParameter(1);
    ALOGV("GetDevicePropValue %s\n",
            MtpDebug::getDevicePropCodeName(property));

    return mDatabase->getDevicePropertyValue(property, mData);
}

MtpResponseCode MtpServer::doSetDevicePropValue() {
    MtpDeviceProperty property = mRequest.getParameter(1);
    ALOGV("SetDevicePropValue %s\n",
            MtpDebug::getDevicePropCodeName(property));

    return mDatabase->setDevicePropertyValue(property, mData);
}

MtpResponseCode MtpServer::doResetDevicePropValue() {
    MtpDeviceProperty property = mRequest.getParameter(1);
    ALOGV("ResetDevicePropValue %s\n",
            MtpDebug::getDevicePropCodeName(property));

    return mDatabase->resetDeviceProperty(property);
}

MtpResponseCode MtpServer::doGetObjectPropList() {
    if (!hasStorage())
        return MTP_RESPONSE_INVALID_OBJECT_HANDLE;

    MtpObjectHandle handle = mRequest.getParameter(1);
    // use uint32_t so we can support 0xFFFFFFFF
    uint32_t format = mRequest.getParameter(2);
    uint32_t property = mRequest.getParameter(3);
    int groupCode = mRequest.getParameter(4);
    int depth = mRequest.getParameter(5);
   ALOGV("GetObjectPropList %d format: %s property: %s group: %d depth: %d\n",
            handle, MtpDebug::getFormatCodeName(format),
            MtpDebug::getObjectPropCodeName(property), groupCode, depth);

    return mDatabase->getObjectPropertyList(handle, format, property, groupCode, depth, mData);
}

MtpResponseCode MtpServer::doGetObjectInfo() {
    if (!hasStorage())
        return MTP_RESPONSE_INVALID_OBJECT_HANDLE;
    MtpObjectHandle handle = mRequest.getParameter(1);
    MtpObjectInfo info(handle);
    MtpResponseCode result = mDatabase->getObjectInfo(handle, info);
    if (result == MTP_RESPONSE_OK) {
        char    date[20];

        mData.putUInt32(info.mStorageID);
        mData.putUInt16(info.mFormat);
        mData.putUInt16(info.mProtectionStatus);

        // if object is being edited the database size may be out of date
        uint32_t size = info.mCompressedSize;
        ObjectEdit* edit = getEditObject(handle);
        if (edit)
            size = (edit->mSize > 0xFFFFFFFFLL ? 0xFFFFFFFF : (uint32_t)edit->mSize);
        mData.putUInt32(size);

        mData.putUInt16(info.mThumbFormat);
        mData.putUInt32(info.mThumbCompressedSize);
        mData.putUInt32(info.mThumbPixWidth);
        mData.putUInt32(info.mThumbPixHeight);
        mData.putUInt32(info.mImagePixWidth);
        mData.putUInt32(info.mImagePixHeight);
        mData.putUInt32(info.mImagePixDepth);
        mData.putUInt32(info.mParent);
        mData.putUInt16(info.mAssociationType);
        mData.putUInt32(info.mAssociationDesc);
        mData.putUInt32(info.mSequenceNumber);
        mData.putString(info.mName);
        mData.putEmptyString();    // date created
        formatDateTime(info.mDateModified, date, sizeof(date));
        mData.putString(date);   // date modified
        mData.putEmptyString();   // keywords
    }
    return result;
}

MtpResponseCode MtpServer::doGetObject() {
    if (!hasStorage())
        return MTP_RESPONSE_INVALID_OBJECT_HANDLE;
    MtpObjectHandle handle = mRequest.getParameter(1);
    MtpString pathBuf;
    int64_t fileLength;
    MtpObjectFormat format;
    int result = mDatabase->getObjectFilePath(handle, pathBuf, fileLength, format);
    if (result != MTP_RESPONSE_OK)
        return result;

    const char* filePath = (const char *)pathBuf;
    mtp_file_range  mfr;
    mfr.fd = open(filePath, O_RDONLY);
    if (mfr.fd < 0) {
        return MTP_RESPONSE_GENERAL_ERROR;
    }
    mfr.offset = 0;
    mfr.length = fileLength;
    mfr.command = mRequest.getOperationCode();
    mfr.transaction_id = mRequest.getTransactionID();

    // then transfer the file
    int ret = ioctl(mFD, MTP_SEND_FILE_WITH_HEADER, (unsigned long)&mfr);
    ALOGV("MTP_SEND_FILE_WITH_HEADER returned %d\n", ret);
    close(mfr.fd);
    if (ret < 0) {
        if (errno == ECANCELED)
            return MTP_RESPONSE_TRANSACTION_CANCELLED;
        else
            return MTP_RESPONSE_GENERAL_ERROR;
    }
    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doGetThumb() {
    MtpObjectHandle handle = mRequest.getParameter(1);
    size_t thumbSize;
    void* thumb = mDatabase->getThumbnail(handle, thumbSize);
    if (thumb) {
        // send data
        mData.setOperationCode(mRequest.getOperationCode());
        mData.setTransactionID(mRequest.getTransactionID());
        mData.writeData(mFD, thumb, thumbSize);
        free(thumb);
        return MTP_RESPONSE_OK;
    } else {
        return MTP_RESPONSE_GENERAL_ERROR;
    }
}

MtpResponseCode MtpServer::doGetPartialObject(MtpOperationCode operation) {
    if (!hasStorage())
        return MTP_RESPONSE_INVALID_OBJECT_HANDLE;
    MtpObjectHandle handle = mRequest.getParameter(1);
    uint64_t offset;
    uint32_t length;
    offset = mRequest.getParameter(2);
    if (operation == MTP_OPERATION_GET_PARTIAL_OBJECT_64) {
        // android extension with 64 bit offset
        uint64_t offset2 = mRequest.getParameter(3);
        offset = offset | (offset2 << 32);
        length = mRequest.getParameter(4);
    } else {
        // standard GetPartialObject
        length = mRequest.getParameter(3);
    }
    MtpString pathBuf;
    int64_t fileLength;
    MtpObjectFormat format;
    int result = mDatabase->getObjectFilePath(handle, pathBuf, fileLength, format);
    if (result != MTP_RESPONSE_OK)
        return result;
    if (offset + length > fileLength)
        length = fileLength - offset;

    const char* filePath = (const char *)pathBuf;
    mtp_file_range  mfr;
    mfr.fd = open(filePath, O_RDONLY);
    if (mfr.fd < 0) {
        return MTP_RESPONSE_GENERAL_ERROR;
    }
    mfr.offset = offset;
    mfr.length = length;
    mfr.command = mRequest.getOperationCode();
    mfr.transaction_id = mRequest.getTransactionID();
    mResponse.setParameter(1, length);

    // transfer the file
    int ret = ioctl(mFD, MTP_SEND_FILE_WITH_HEADER, (unsigned long)&mfr);
    ALOGV("MTP_SEND_FILE_WITH_HEADER returned %d\n", ret);
    close(mfr.fd);
    if (ret < 0) {
        if (errno == ECANCELED)
            return MTP_RESPONSE_TRANSACTION_CANCELLED;
        else
            return MTP_RESPONSE_GENERAL_ERROR;
    }
    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doSendObjectInfo() {
    MtpString path;
    MtpStorageID storageID = mRequest.getParameter(1);
    MtpStorage* storage = getStorage(storageID);
    MtpObjectHandle parent = mRequest.getParameter(2);
    if (!storage)
        return MTP_RESPONSE_INVALID_STORAGE_ID;

    // special case the root
    if (parent == MTP_PARENT_ROOT) {
        path = storage->getPath();
        parent = 0;
    } else {
        int64_t length;
        MtpObjectFormat format;
        int result = mDatabase->getObjectFilePath(parent, path, length, format);
        if (result != MTP_RESPONSE_OK)
            return result;
        if (format != MTP_FORMAT_ASSOCIATION)
            return MTP_RESPONSE_INVALID_PARENT_OBJECT;
    }

    // read only the fields we need
    mData.getUInt32();  // storage ID
    MtpObjectFormat format = mData.getUInt16();
    mData.getUInt16();  // protection status
    mSendObjectFileSize = mData.getUInt32();
    mData.getUInt16();  // thumb format
    mData.getUInt32();  // thumb compressed size
    mData.getUInt32();  // thumb pix width
    mData.getUInt32();  // thumb pix height
    mData.getUInt32();  // image pix width
    mData.getUInt32();  // image pix height
    mData.getUInt32();  // image bit depth
    mData.getUInt32();  // parent
    uint16_t associationType = mData.getUInt16();
    uint32_t associationDesc = mData.getUInt32();   // association desc
    mData.getUInt32();  // sequence number
    MtpStringBuffer name, created, modified;
    mData.getString(name);    // file name
    mData.getString(created);      // date created
    mData.getString(modified);     // date modified
    // keywords follow

    ALOGV("name: %s format: %04X\n", (const char *)name, format);
    time_t modifiedTime;
    if (!parseDateTime(modified, modifiedTime))
        modifiedTime = 0;

    if (path[path.size() - 1] != '/')
        path += "/";
    path += (const char *)name;

    // check space first
    if (mSendObjectFileSize > storage->getFreeSpace())
        return MTP_RESPONSE_STORAGE_FULL;
    uint64_t maxFileSize = storage->getMaxFileSize();
    // check storage max file size
    if (maxFileSize != 0) {
        // if mSendObjectFileSize is 0xFFFFFFFF, then all we know is the file size
        // is >= 0xFFFFFFFF
        if (mSendObjectFileSize > maxFileSize || mSendObjectFileSize == 0xFFFFFFFF)
            return MTP_RESPONSE_OBJECT_TOO_LARGE;
    }

LOGD("path: %s parent: %d storageID: %08X", (const char*)path, parent, storageID);
    MtpObjectHandle handle = mDatabase->beginSendObject((const char*)path,
            format, parent, storageID, mSendObjectFileSize, modifiedTime);
    if (handle == kInvalidObjectHandle) {
        return MTP_RESPONSE_GENERAL_ERROR;
    }

  if (format == MTP_FORMAT_ASSOCIATION) {
        mode_t mask = umask(0);
        int ret = mkdir((const char *)path, mDirectoryPermission);
        umask(mask);
        if (ret && ret != -EEXIST)
            return MTP_RESPONSE_GENERAL_ERROR;
        chown((const char *)path, getuid(), mFileGroup);

        // SendObject does not get sent for directories, so call endSendObject here instead
        mDatabase->endSendObject(path, handle, MTP_FORMAT_ASSOCIATION, MTP_RESPONSE_OK);
    } else {
        mSendObjectFilePath = path;
        // save the handle for the SendObject call, which should follow
        mSendObjectHandle = handle;
        mSendObjectFormat = format;
    }

    mResponse.setParameter(1, storageID);
    mResponse.setParameter(2, parent);
    mResponse.setParameter(3, handle);

    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doSendObject() {
    if (!hasStorage())
        return MTP_RESPONSE_GENERAL_ERROR;
    MtpResponseCode result = MTP_RESPONSE_OK;
    mode_t mask;
    int ret, initialData;

    if (mSendObjectHandle == kInvalidObjectHandle) {
        LOGE("Expected SendObjectInfo before SendObject");
        result = MTP_RESPONSE_NO_VALID_OBJECT_INFO;
        goto done;
    }

    // read the header, and possibly some data
    ret = mData.read(mFD);
    if (ret < MTP_CONTAINER_HEADER_SIZE) {
        result = MTP_RESPONSE_GENERAL_ERROR;
        goto done;
    }
    initialData = ret - MTP_CONTAINER_HEADER_SIZE;

    mtp_file_range  mfr;
    mfr.fd = open(mSendObjectFilePath, O_RDWR | O_CREAT | O_TRUNC);
    if (mfr.fd < 0) {
        result = MTP_RESPONSE_GENERAL_ERROR;
        goto done;
    }
    fchown(mfr.fd, getuid(), mFileGroup);
    // set permissions
    mask = umask(0);
    fchmod(mfr.fd, mFilePermission);
    umask(mask);

    if (initialData > 0)
        ret = write(mfr.fd, mData.getData(), initialData);

    if (mSendObjectFileSize - initialData > 0) {
        mfr.offset = initialData;
        if (mSendObjectFileSize == 0xFFFFFFFF) {
            // tell driver to read until it receives a short packet
            mfr.length = 0xFFFFFFFF;
        } else {
            mfr.length = mSendObjectFileSize - initialData;
        }

        ALOGV("receiving %s\n", (const char *)mSendObjectFilePath);
        // transfer the file
        ret = ioctl(mFD, MTP_RECEIVE_FILE, (unsigned long)&mfr);
        ALOGV("MTP_RECEIVE_FILE returned %d\n", ret);
    }
    close(mfr.fd);

    if (ret < 0) {
        unlink(mSendObjectFilePath);
        if (errno == ECANCELED)
            result = MTP_RESPONSE_TRANSACTION_CANCELLED;
        else
            result = MTP_RESPONSE_GENERAL_ERROR;
    }

done:
    // reset so we don't attempt to send the data back
    mData.reset();

    mDatabase->endSendObject(mSendObjectFilePath, mSendObjectHandle, mSendObjectFormat,
            result == MTP_RESPONSE_OK);
    mSendObjectHandle = kInvalidObjectHandle;
    mSendObjectFormat = 0;
    return result;
}

static void deleteRecursive(const char* path) {
    char pathbuf[PATH_MAX];
    int pathLength = strlen(path);
    if (pathLength >= sizeof(pathbuf) - 1) {
        LOGE("path too long: %s\n", path);
    }
    strcpy(pathbuf, path);
    if (pathbuf[pathLength - 1] != '/') {
        pathbuf[pathLength++] = '/';
    }
    char* fileSpot = pathbuf + pathLength;
    int pathRemaining = sizeof(pathbuf) - pathLength - 1;

    DIR* dir = opendir(path);
    if (!dir) {
        LOGE("opendir %s failed: %s", path, strerror(errno));
        return;
    }

    struct dirent* entry;
    while ((entry = readdir(dir))) {
        const char* name = entry->d_name;

        // ignore "." and ".."
        if (name[0] == '.' && (name[1] == 0 || (name[1] == '.' && name[2] == 0))) {
            continue;
        }

        int nameLength = strlen(name);
        if (nameLength > pathRemaining) {
            LOGE("path %s/%s too long\n", path, name);
            continue;
        }
        strcpy(fileSpot, name);

        int type = entry->d_type;
        if (entry->d_type == DT_DIR) {
            deleteRecursive(pathbuf);
            rmdir(pathbuf);
        } else {
            unlink(pathbuf);
        }
    }
    closedir(dir);
}

static void deletePath(const char* path) {
    struct stat statbuf;
    if (stat(path, &statbuf) == 0) {
        if (S_ISDIR(statbuf.st_mode)) {
            deleteRecursive(path);
            rmdir(path);
        } else {
            unlink(path);
        }
    } else {
        LOGE("deletePath stat failed for %s: %s", path, strerror(errno));
    }
}

MtpResponseCode MtpServer::doDeleteObject() {
    if (!hasStorage())
        return MTP_RESPONSE_INVALID_OBJECT_HANDLE;
    MtpObjectHandle handle = mRequest.getParameter(1);
    MtpObjectFormat format = mRequest.getParameter(2);
    // FIXME - support deleting all objects if handle is 0xFFFFFFFF
    // FIXME - implement deleting objects by format

    MtpString filePath;
    int64_t fileLength;
    int result = mDatabase->getObjectFilePath(handle, filePath, fileLength, format);
    if (result == MTP_RESPONSE_OK) {
        ALOGV("deleting %s", (const char *)filePath);
        result = mDatabase->deleteFile(handle);
        // Don't delete the actual files unless the database deletion is allowed
        if (result == MTP_RESPONSE_OK) {
            deletePath((const char *)filePath);
        }
    }

    return result;
}

MtpResponseCode MtpServer::doGetObjectPropDesc() {
    MtpObjectProperty propCode = mRequest.getParameter(1);
    MtpObjectFormat format = mRequest.getParameter(2);
    ALOGV("GetObjectPropDesc %s %s\n", MtpDebug::getObjectPropCodeName(propCode),
                                        MtpDebug::getFormatCodeName(format));
    MtpProperty* property = mDatabase->getObjectPropertyDesc(propCode, format);
    if (!property)
        return MTP_RESPONSE_OBJECT_PROP_NOT_SUPPORTED;
    property->write(mData);
    delete property;
    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doGetDevicePropDesc() {
    MtpDeviceProperty propCode = mRequest.getParameter(1);
    ALOGV("GetDevicePropDesc %s\n", MtpDebug::getDevicePropCodeName(propCode));
    MtpProperty* property = mDatabase->getDevicePropertyDesc(propCode);
    if (!property)
        return MTP_RESPONSE_DEVICE_PROP_NOT_SUPPORTED;
    property->write(mData);
    delete property;
    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doSendPartialObject() {
    if (!hasStorage())
        return MTP_RESPONSE_INVALID_OBJECT_HANDLE;
    MtpObjectHandle handle = mRequest.getParameter(1);
    uint64_t offset = mRequest.getParameter(2);
    uint64_t offset2 = mRequest.getParameter(3);
    offset = offset | (offset2 << 32);
    uint32_t length = mRequest.getParameter(4);

    ObjectEdit* edit = getEditObject(handle);
    if (!edit) {
        LOGE("object not open for edit in doSendPartialObject");
        return MTP_RESPONSE_GENERAL_ERROR;
    }

    // can't start writing past the end of the file
    if (offset > edit->mSize) {
        LOGD("writing past end of object, offset: %lld, edit->mSize: %lld", offset, edit->mSize);
        return MTP_RESPONSE_GENERAL_ERROR;
    }

    const char* filePath = (const char *)edit->mPath;
    ALOGV("receiving partial %s %lld %lld\n", filePath, offset, length);

    // read the header, and possibly some data
    int ret = mData.read(mFD);
    if (ret < MTP_CONTAINER_HEADER_SIZE)
        return MTP_RESPONSE_GENERAL_ERROR;
    int initialData = ret - MTP_CONTAINER_HEADER_SIZE;

    if (initialData > 0) {
        ret = write(edit->mFD, mData.getData(), initialData);
        offset += initialData;
        length -= initialData;
    }

    if (length > 0) {
        mtp_file_range  mfr;
        mfr.fd = edit->mFD;
        mfr.offset = offset;
        mfr.length = length;

        // transfer the file
        ret = ioctl(mFD, MTP_RECEIVE_FILE, (unsigned long)&mfr);
        ALOGV("MTP_RECEIVE_FILE returned %d", ret);
    }
    if (ret < 0) {
        mResponse.setParameter(1, 0);
        if (errno == ECANCELED)
            return MTP_RESPONSE_TRANSACTION_CANCELLED;
        else
            return MTP_RESPONSE_GENERAL_ERROR;
    }

    // reset so we don't attempt to send this back
    mData.reset();
    mResponse.setParameter(1, length);
    uint64_t end = offset + length;
    if (end > edit->mSize) {
        edit->mSize = end;
    }
    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doTruncateObject() {
    MtpObjectHandle handle = mRequest.getParameter(1);
    ObjectEdit* edit = getEditObject(handle);
    if (!edit) {
        LOGE("object not open for edit in doTruncateObject");
        return MTP_RESPONSE_GENERAL_ERROR;
    }

    uint64_t offset = mRequest.getParameter(2);
    uint64_t offset2 = mRequest.getParameter(3);
    offset |= (offset2 << 32);
    if (ftruncate(edit->mFD, offset) != 0) {
        return MTP_RESPONSE_GENERAL_ERROR;
    } else {
        edit->mSize = offset;
        return MTP_RESPONSE_OK;
    }
}

MtpResponseCode MtpServer::doBeginEditObject() {
    MtpObjectHandle handle = mRequest.getParameter(1);
    if (getEditObject(handle)) {
        LOGE("object already open for edit in doBeginEditObject");
        return MTP_RESPONSE_GENERAL_ERROR;
    }

    MtpString path;
    int64_t fileLength;
    MtpObjectFormat format;
    int result = mDatabase->getObjectFilePath(handle, path, fileLength, format);
    if (result != MTP_RESPONSE_OK)
        return result;

    int fd = open((const char *)path, O_RDWR | O_EXCL);
    if (fd < 0) {
        LOGE("open failed for %s in doBeginEditObject (%d)", (const char *)path, errno);
        return MTP_RESPONSE_GENERAL_ERROR;
    }

    addEditObject(handle, path, fileLength, format, fd);
    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doEndEditObject() {
    MtpObjectHandle handle = mRequest.getParameter(1);
    ObjectEdit* edit = getEditObject(handle);
    if (!edit) {
        LOGE("object not open for edit in doEndEditObject");
        return MTP_RESPONSE_GENERAL_ERROR;
    }

    commitEdit(edit);
    removeEditObject(handle);
    return MTP_RESPONSE_OK;
}

}  // namespace android
