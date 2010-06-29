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

#define LOG_TAG "MtpServer"

#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <errno.h>

#include <cutils/properties.h>

#include "MtpDebug.h"
#include "MtpServer.h"
#include "MtpStorage.h"
#include "MtpStringBuffer.h"
#include "MtpDatabase.h"
#include "MtpDebug.h"

#include "f_mtp.h"

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
    MTP_OPERATION_GET_DEVICE_PROP_DESC,
    MTP_OPERATION_GET_DEVICE_PROP_VALUE,
    MTP_OPERATION_SET_DEVICE_PROP_VALUE,
    MTP_OPERATION_RESET_DEVICE_PROP_VALUE,
//    MTP_OPERATION_TERMINATE_OPEN_CAPTURE,
//    MTP_OPERATION_MOVE_OBJECT,
//    MTP_OPERATION_COPY_OBJECT,
//    MTP_OPERATION_GET_PARTIAL_OBJECT,
//    MTP_OPERATION_INITIATE_OPEN_CAPTURE,
    MTP_OPERATION_GET_OBJECT_PROPS_SUPPORTED,
//    MTP_OPERATION_GET_OBJECT_PROP_DESC,
    MTP_OPERATION_GET_OBJECT_PROP_VALUE,
    MTP_OPERATION_SET_OBJECT_PROP_VALUE,
//    MTP_OPERATION_GET_OBJECT_REFERENCES,
//    MTP_OPERATION_SET_OBJECT_REFERENCES,
//    MTP_OPERATION_SKIP,
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
    // MTP_FORMAT_ABSTRACT_AV_PLAYLIST,
    // MTP_FORMAT_WPL_PLAYLIST,
    // MTP_FORMAT_M3U_PLAYLIST,
    // MTP_FORMAT_MPL_PLAYLIST,
    // MTP_FORMAT_PLS_PLAYLIST,
};

MtpServer::MtpServer(int fd, const char* databasePath)
    :   mFD(fd),
        mDatabasePath(databasePath),
        mDatabase(NULL),
        mSessionID(0),
        mSessionOpen(false),
        mSendObjectHandle(kInvalidObjectHandle),
        mSendObjectFileSize(0)
{
    mDatabase = new MtpDatabase();
    mDatabase->open(databasePath, true);
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

void MtpServer::scanStorage() {
    for (int i = 0; i < mStorages.size(); i++) {
        MtpStorage* storage =  mStorages[i];
        storage->scanFiles();
    }
}

void MtpServer::run() {
    int fd = mFD;

    LOGD("MtpServer::run fd: %d", fd);

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
        bool dataIn = (operation == MTP_OPERATION_SEND_OBJECT_INFO);
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
            LOGV("skipping response");
        }
    }
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
    mData.putEmptyArray(); // Events Supported
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

    MtpObjectHandleList* handles = mDatabase->getObjectList(storageID, format, parent);
    mData.putAUInt32(handles);
    delete handles;
    return MTP_RESPONSE_OK;
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
    if (!mDatabase->getObjectFilePath(handle, pathBuf, fileLength))
        return MTP_RESPONSE_INVALID_OBJECT_HANDLE;
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
    if (parent == MTP_PARENT_ROOT)
        path = storage->getPath();
    else {
        int64_t dummy;
        if (!mDatabase->getObjectFilePath(parent, path, dummy))
            return MTP_RESPONSE_INVALID_OBJECT_HANDLE;
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
    LOGV("SendObjectInfo format: %04X size: %d name: %s, created: %s, modified: %s",
            format, mSendObjectFileSize, (const char*)name, (const char*)created,
            (const char*)modified);

    if (path[path.size() - 1] != '/')
        path += "/";
    path += (const char *)name;

    mDatabase->beginTransaction();
    MtpObjectHandle handle = mDatabase->addFile((const char*)path, format, parent, storageID, 
                                    mSendObjectFileSize, modifiedTime);
    if (handle == kInvalidObjectHandle) {
        mDatabase->rollbackTransaction();
        return MTP_RESPONSE_GENERAL_ERROR;
    }
    uint32_t table = MtpDatabase::getTableForFile(format);
    if (table == kObjectHandleTableAudio)
        handle = mDatabase->addAudioFile(handle);
    mDatabase->commitTransaction();

  if (format == MTP_FORMAT_ASSOCIATION) {
        mode_t mask = umask(0);
        int ret = mkdir((const char *)path, S_IRWXU | S_IRWXG | S_IRWXO);
        umask(mask);
        if (ret && ret != -EEXIST)
            return MTP_RESPONSE_GENERAL_ERROR;
    } else {
        mSendObjectFilePath = path;
        // save the handle for the SendObject call, which should follow
        mSendObjectHandle = handle;
    }

    mResponse.setParameter(1, storageID);
    mResponse.setParameter(2, parent);
    mResponse.setParameter(3, handle);

    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doSendObject() {
    if (mSendObjectHandle == kInvalidObjectHandle) {
        LOGE("Expected SendObjectInfo before SendObject");
        return MTP_RESPONSE_NO_VALID_OBJECT_INFO;
    }

    // read the header
    int ret = mData.readDataHeader(mFD);
    // FIXME - check for errors here.

    // reset so we don't attempt to send this back
    mData.reset();

    mtp_file_range  mfr;
    mfr.fd = open(mSendObjectFilePath, O_RDWR | O_CREAT | O_TRUNC);
    if (mfr.fd < 0) {
        return MTP_RESPONSE_GENERAL_ERROR;
    }
    mfr.offset = 0;
    mfr.length = mSendObjectFileSize;

    // transfer the file
    ret = ioctl(mFD, MTP_RECEIVE_FILE, (unsigned long)&mfr);
    close(mfr.fd);
    // FIXME - we need to delete mSendObjectHandle from the database if this fails.
    LOGV("MTP_RECEIVE_FILE returned %d", ret);
    mSendObjectHandle = kInvalidObjectHandle;

    if (ret < 0) {
        unlink(mSendObjectFilePath);
        if (errno == ECANCELED)
            return MTP_RESPONSE_TRANSACTION_CANCELLED;
        else
            return MTP_RESPONSE_GENERAL_ERROR;
    }
    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doDeleteObject() {
    MtpObjectHandle handle = mRequest.getParameter(1);
    MtpObjectFormat format = mRequest.getParameter(1);
    // FIXME - support deleting all objects if handle is 0xFFFFFFFF
    // FIXME - implement deleting objects by format
    // FIXME - handle non-empty directories

    MtpString filePath;
    int64_t fileLength;
    if (!mDatabase->getObjectFilePath(handle, filePath, fileLength))
        return MTP_RESPONSE_INVALID_OBJECT_HANDLE;

    LOGV("deleting %s", (const char *)filePath);
    // one of these should work
    rmdir((const char *)filePath);
    unlink((const char *)filePath);

    mDatabase->deleteFile(handle);

    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpServer::doGetObjectPropDesc() {
    MtpObjectProperty property = mRequest.getParameter(1);
    MtpObjectFormat format = mRequest.getParameter(2);

    return -1;
}

}  // namespace android
