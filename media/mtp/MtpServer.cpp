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

#include <cutils/properties.h>

#include "MtpDebug.h"
#include "MtpDatabase.h"
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
//    MTP_OPERATION_GET_THUMB,
    MTP_OPERATION_DELETE_OBJECT,
    MTP_OPERATION_SEND_OBJECT_INFO,
    MTP_OPERATION_SEND_OBJECT,
//    MTP_OPERATION_INITIATE_CAPTURE,
//    MTP_OPERATION_FORMAT_STORE,
//    MTP_OPERATION_RESET_DEVICE,
//    MTP_OPERATION_SELF_TEST,
//    MTP_OPERATION_SET_OBJECT_PROTECTION,
//    MTP_OPERATION_POWER_DOWN,
//    MTP_OPERATION_GET_DEVICE_PROP_DESC,
//    MTP_OPERATION_GET_DEVICE_PROP_VALUE,
//    MTP_OPERATION_SET_DEVICE_PROP_VALUE,
//    MTP_OPERATION_RESET_DEVICE_PROP_VALUE,
//    MTP_OPERATION_TERMINATE_OPEN_CAPTURE,
//    MTP_OPERATION_MOVE_OBJECT,
//    MTP_OPERATION_COPY_OBJECT,
//    MTP_OPERATION_GET_PARTIAL_OBJECT,
//    MTP_OPERATION_INITIATE_OPEN_CAPTURE,
    MTP_OPERATION_GET_OBJECT_PROPS_SUPPORTED,
//    MTP_OPERATION_GET_OBJECT_PROP_DESC,
    MTP_OPERATION_GET_OBJECT_PROP_VALUE,
//    MTP_OPERATION_SET_OBJECT_PROP_VALUE,
    MTP_OPERATION_GET_OBJECT_REFERENCES,
    MTP_OPERATION_SET_OBJECT_REFERENCES,
//    MTP_OPERATION_SKIP,
};

static const MtpEventCode kSupportedEventCodes[] = {
    MTP_EVENT_OBJECT_ADDED,
    MTP_EVENT_OBJECT_REMOVED,
};

static const MtpObjectProperty kSupportedObjectProperties[] = {
    MTP_PROPERTY_STORAGE_ID,
    MTP_PROPERTY_OBJECT_FORMAT,
    MTP_PROPERTY_OBJECT_SIZE,
    MTP_PROPERTY_OBJECT_FILE_NAME,
    MTP_PROPERTY_PARENT_OBJECT,
};

static const MtpObjectFormat kSupportedPlaybackFormats[] = {
    // MTP_FORMAT_UNDEFINED,
    MTP_FORMAT_ASSOCIATION,
    // MTP_FORMAT_TEXT,
    // MTP_FORMAT_HTML,
    MTP_FORMAT_MP3,
    //MTP_FORMAT_AVI,
    MTP_FORMAT_MPEG,
    // MTP_FORMAT_ASF,
    MTP_FORMAT_EXIF_JPEG,
    MTP_FORMAT_TIFF_EP,
    // MTP_FORMAT_BMP,
    MTP_FORMAT_GIF,
    MTP_FORMAT_JFIF,
    MTP_FORMAT_PNG,
    MTP_FORMAT_TIFF,
    MTP_FORMAT_WMA,
    MTP_FORMAT_OGG,
    MTP_FORMAT_AAC,
    // MTP_FORMAT_FLAC,
    // MTP_FORMAT_WMV,
    MTP_FORMAT_MP4_CONTAINER,
    MTP_FORMAT_MP2,
    MTP_FORMAT_3GP_CONTAINER,
    // MTP_FORMAT_ABSTRACT_AUDIO_ALBUM,
    MTP_FORMAT_ABSTRACT_AV_PLAYLIST,
    MTP_FORMAT_WPL_PLAYLIST,
    MTP_FORMAT_M3U_PLAYLIST,
    // MTP_FORMAT_MPL_PLAYLIST,
    MTP_FORMAT_PLS_PLAYLIST,
};

MtpServer::MtpServer(int fd, MtpDatabase* database,
                    int fileGroup, int filePerm, int directoryPerm)
    :   mFD(fd),
        mDatabase(database),
        mFileGroup(fileGroup),
        mFilePermission(filePerm),
        mDirectoryPermission(directoryPerm),
        mSessionID(0),
        mSessionOpen(false),
        mSendObjectHandle(kInvalidObjectHandle),
        mSendObjectFormat(0),
        mSendObjectFileSize(0)
{
    initObjectProperties();
}

MtpServer::~MtpServer() {
}

void MtpServer::addStorage(const char* filePath) {
    int index = mStorages.size() + 1;
    index |= index << 16;   // set high and low part to our index
    MtpStorage* storage = new MtpStorage(index, filePath, mDatabase);
    addStorage(storage);
}

MtpStorage* MtpServer::getStorage(MtpStorageID id) {
    for (int i = 0; i < mStorages.size(); i++) {
        MtpStorage* storage =  mStorages[i];
        if (storage->getStorageID() == id)
            return storage;
    }
    return NULL;
}

void MtpServer::run() {
    int fd = mFD;

    LOGV("MtpServer::run fd: %d\n", fd);

    while (1) {
        int ret = mRequest.read(fd);
        if (ret < 0) {
            LOGE("request read returned %d, errno: %d", ret, errno);
            if (errno == ECANCELED) {
                // return to top of loop and wait for next command
                continue;
            }
            break;
        }
        MtpOperationCode operation = mRequest.getOperationCode();
        MtpTransactionID transaction = mRequest.getTransactionID();

        LOGV("operation: %s", MtpDebug::getOperationCodeName(operation));
        mRequest.dump();

        // FIXME need to generalize this
        bool dataIn = (operation == MTP_OPERATION_SEND_OBJECT_INFO
                    || operation == MTP_OPERATION_SET_OBJECT_REFERENCES);
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
            LOGV("received data:");
            mData.dump();
        } else {
            mData.reset();
        }

        if (handleRequest()) {
            if (!dataIn && mData.hasData()) {
                mData.setOperationCode(operation);
                mData.setTransactionID(transaction);
                LOGV("sending data:");
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
            LOGV("sending response %04X", mResponse.getResponseCode());
            ret = mResponse.write(fd);
            if (ret < 0) {
                LOGE("request write returned %d, errno: %d", ret, errno);
                if (errno == ECANCELED) {
                    // return to top of loop and wait for next command
                    continue;
                }
                break;
            }
        } else {
            LOGV("skipping response\n");
        }
    }
}

MtpProperty* MtpServer::getObjectProperty(MtpPropertyCode propCode) {
    for (int i = 0; i < mObjectProperties.size(); i++) {
        MtpProperty* property = mObjectProperties[i];
        if (property->getPropertyCode() == propCode)
            return property;
    }
    return NULL;
}

MtpProperty* MtpServer::getDeviceProperty(MtpPropertyCode propCode) {
    for (int i = 0; i < mDeviceProperties.size(); i++) {
        MtpProperty* property = mDeviceProperties[i];
        if (property->getPropertyCode() == propCode)
            return property;
    }
    return NULL;
}

void MtpServer::sendObjectAdded(MtpObjectHandle handle) {
    if (mSessionOpen) {
        LOGD("sendObjectAdded %d\n", handle);
        mEvent.setEventCode(MTP_EVENT_OBJECT_ADDED);
        mEvent.setTransactionID(mRequest.getTransactionID());
        mEvent.setParameter(1, handle);
        int ret = mEvent.write(mFD);
        LOGD("mEvent.write returned %d\n", ret);
    }
}

void MtpServer::sendObjectRemoved(MtpObjectHandle handle) {
    if (mSessionOpen) {
        LOGD("sendObjectRemoved %d\n", handle);
        mEvent.setEventCode(MTP_EVENT_OBJECT_REMOVED);
        mEvent.setTransactionID(mRequest.getTransactionID());
        mEvent.setParameter(1, handle);
        int ret = mEvent.write(mFD);
        LOGD("mEvent.write returned %d\n", ret);
    }
}

void MtpServer::initObjectProperties() {
    mObjectProperties.push(new MtpProperty(MTP_PROPERTY_STORAGE_ID, MTP_TYPE_UINT32));
    mObjectProperties.push(new MtpProperty(MTP_PROPERTY_OBJECT_FORMAT, MTP_TYPE_UINT16));
    mObjectProperties.push(new MtpProperty(MTP_PROPERTY_OBJECT_SIZE, MTP_TYPE_UINT64));
    mObjectProperties.push(new MtpProperty(MTP_PROPERTY_OBJECT_FILE_NAME, MTP_TYPE_STR));
    mObjectProperties.push(new MtpProperty(MTP_PROPERTY_PARENT_OBJECT, MTP_TYPE_UINT32));
}

bool MtpServer::handleRequest() {
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
        case MTP_OPERATION_GET_OBJECT_INFO:
            response = doGetObjectInfo();
            break;
        case MTP_OPERATION_GET_OBJECT:
            response = doGetObject();
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
        default:
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

    // fill in device info
    mData.putUInt16(MTP_STANDARD_VERSION);
    mData.putUInt32(6); // MTP Vendor Extension ID
    mData.putUInt16(MTP_STANDARD_VERSION);
    string.set("microsoft.com: 1.0;");
    mData.putString(string); // MTP Extensions
    mData.putUInt16(0); //Functional Mode
    mData.putAUInt16(kSupportedOperationCodes,
            sizeof(kSupportedOperationCodes) / sizeof(uint16_t)); // Operations Supported
    mData.putAUInt16(kSupportedEventCodes,
            sizeof(kSupportedEventCodes) / sizeof(uint16_t)); // Events Supported
    mData.putEmptyArray(); // Device Properties Supported
    mData.putEmptyArray(); // Capture Formats
    mData.putAUInt16(kSupportedPlaybackFormats,
            sizeof(kSupportedPlaybackFormats) / sizeof(uint16_t)); // Playback Formats
    // FIXME
    string.set("Google, Inc.");
    mData.putString(string);   // Manufacturer

    property_get("ro.product.model", prop_value, "MTP Device");
    string.set(prop_value);
    mData.putString(string);   // Model
    string.set("1.0");
    mData.putString(string);   // Device Version

    property_get("ro.serialno", prop_value, "????????");
    string.set(prop_value);
    mData.putString(string);   // Serial Number

    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doOpenSession() {
    if (mSessionOpen) {
        mResponse.setParameter(1, mSessionID);
        return MTP_RESPONSE_SESSION_ALREADY_OPEN;
    }
    mSessionID = mRequest.getParameter(1);
    mSessionOpen = true;
    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doCloseSession() {
    if (!mSessionOpen)
        return MTP_RESPONSE_SESSION_NOT_OPEN;
    mSessionID = 0;
    mSessionOpen = false;
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
    mData.putAUInt16(kSupportedObjectProperties,
            sizeof(kSupportedObjectProperties) / sizeof(uint16_t));
    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doGetObjectHandles() {
    if (!mSessionOpen)
        return MTP_RESPONSE_SESSION_NOT_OPEN;
    MtpStorageID storageID = mRequest.getParameter(1);      // 0xFFFFFFFF for all storage
    MtpObjectFormat format = mRequest.getParameter(2);      // 0 for all formats
    MtpObjectHandle parent = mRequest.getParameter(3);      // 0xFFFFFFFF for objects with no parent
                                                            // 0x00000000 for all objects?
    if (parent == 0xFFFFFFFF)
        parent = 0;

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
                                                            // 0x00000000 for all objects?
    if (parent == 0xFFFFFFFF)
        parent = 0;

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
    MtpStorageID handle = mRequest.getParameter(1);
    MtpObjectHandleList* handles = mDatabase->getObjectReferences(handle);
    if (!handles) {
        mData.putEmptyArray();
        return MTP_RESPONSE_INVALID_OBJECT_HANDLE;
    }
    mData.putAUInt32(handles);
    delete handles;
    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doSetObjectReferences() {
    if (!mSessionOpen)
        return MTP_RESPONSE_SESSION_NOT_OPEN;
    MtpStorageID handle = mRequest.getParameter(1);
    MtpObjectHandleList* references = mData.getAUInt32();
    MtpResponseCode result = mDatabase->setObjectReferences(handle, references);
    delete references;
    return result;
}

MtpResponseCode MtpServer::doGetObjectPropValue() {
    MtpObjectHandle handle = mRequest.getParameter(1);
    MtpObjectProperty property = mRequest.getParameter(2);

    return mDatabase->getObjectProperty(handle, property, mData);
}

MtpResponseCode MtpServer::doGetObjectInfo() {
    MtpObjectHandle handle = mRequest.getParameter(1);
    return mDatabase->getObjectInfo(handle, mData);
}

MtpResponseCode MtpServer::doGetObject() {
    MtpObjectHandle handle = mRequest.getParameter(1);
    MtpString pathBuf;
    int64_t fileLength;
    int result = mDatabase->getObjectFilePath(handle, pathBuf, fileLength);
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

    // send data header
    mData.setOperationCode(mRequest.getOperationCode());
    mData.setTransactionID(mRequest.getTransactionID());
    mData.writeDataHeader(mFD, fileLength);

    // then transfer the file
    int ret = ioctl(mFD, MTP_SEND_FILE, (unsigned long)&mfr);
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
        int64_t dummy;
        int result = mDatabase->getObjectFilePath(parent, path, dummy);
        if (result != MTP_RESPONSE_OK)
            return result;
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

    time_t modifiedTime;
    if (!parseDateTime(modified, modifiedTime))
        modifiedTime = 0;

    if (path[path.size() - 1] != '/')
        path += "/";
    path += (const char *)name;

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
    } else {
        mSendObjectFilePath = path;
        // save the handle for the SendObject call, which should follow
        mSendObjectHandle = handle;
        mSendObjectFormat = format;
    }

    mResponse.setParameter(1, storageID);
    mResponse.setParameter(2, (parent == 0 ? 0xFFFFFFFF: parent));
    mResponse.setParameter(3, handle);

    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doSendObject() {
    MtpResponseCode result = MTP_RESPONSE_OK;
    mode_t mask;
    int ret;

    if (mSendObjectHandle == kInvalidObjectHandle) {
        LOGE("Expected SendObjectInfo before SendObject");
        result = MTP_RESPONSE_NO_VALID_OBJECT_INFO;
        goto done;
    }

    // read the header
    ret = mData.readDataHeader(mFD);
    // FIXME - check for errors here.

    // reset so we don't attempt to send this back
    mData.reset();

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

    mfr.offset = 0;
    mfr.length = mSendObjectFileSize;

    // transfer the file
    ret = ioctl(mFD, MTP_RECEIVE_FILE, (unsigned long)&mfr);
    close(mfr.fd);

    LOGV("MTP_RECEIVE_FILE returned %d", ret);

    if (ret < 0) {
        unlink(mSendObjectFilePath);
        if (errno == ECANCELED)
            result = MTP_RESPONSE_TRANSACTION_CANCELLED;
        else
            result = MTP_RESPONSE_GENERAL_ERROR;
    }

done:
    mDatabase->endSendObject(mSendObjectFilePath, mSendObjectHandle, mSendObjectFormat,
            result == MTP_RESPONSE_OK);
    mSendObjectHandle = kInvalidObjectHandle;
    mSendObjectFormat = 0;
    return result;
}

MtpResponseCode MtpServer::doDeleteObject() {
    MtpObjectHandle handle = mRequest.getParameter(1);
    MtpObjectFormat format = mRequest.getParameter(1);
    // FIXME - support deleting all objects if handle is 0xFFFFFFFF
    // FIXME - implement deleting objects by format
    // FIXME - handle non-empty directories

    MtpString filePath;
    int64_t fileLength;
    int result = mDatabase->getObjectFilePath(handle, filePath, fileLength);
    if (result == MTP_RESPONSE_OK) {
        LOGV("deleting %s", (const char *)filePath);
        // one of these should work
        rmdir((const char *)filePath);
        unlink((const char *)filePath);
        return mDatabase->deleteFile(handle);
    } else {
        return result;
    }
}

MtpResponseCode MtpServer::doGetObjectPropDesc() {
    MtpObjectProperty propCode = mRequest.getParameter(1);
    MtpObjectFormat format = mRequest.getParameter(2);
    MtpProperty* property = getObjectProperty(propCode);
    if (!property)
        return MTP_RESPONSE_OBJECT_PROP_NOT_SUPPORTED;

    property->write(mData);
    return MTP_RESPONSE_OK;
}

}  // namespace android
