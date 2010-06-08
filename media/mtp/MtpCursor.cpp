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

#define LOG_TAG "MtpCursor"
#include "utils/Log.h"

#include "MtpClient.h"
#include "MtpCursor.h"
#include "MtpDevice.h"
#include "MtpDeviceInfo.h"
#include "MtpObjectInfo.h"
#include "MtpStorageInfo.h"

#include "binder/CursorWindow.h"

namespace android {

/* Device Column IDs */
/* These must match the values in MtpCursor.java */
#define DEVICE_ROW_ID           1
#define DEVICE_MANUFACTURER     2
#define DEVICE_MODEL            3

/* Storage Column IDs */
/* These must match the values in MtpCursor.java */
#define STORAGE_ROW_ID          101
#define STORAGE_IDENTIFIER      102
#define STORAGE_DESCRIPTION     103

/* Object Column IDs */
/* These must match the values in MtpCursor.java */
#define OBJECT_ROW_ID               201
#define OBJECT_STORAGE_ID           202
#define OBJECT_FORMAT               203
#define OBJECT_PROTECTION_STATUS    204
#define OBJECT_SIZE                 205
#define OBJECT_THUMB_FORMAT         206
#define OBJECT_THUMB_SIZE           207
#define OBJECT_THUMB_WIDTH          208
#define OBJECT_THUMB_HEIGHT         209
#define OBJECT_IMAGE_WIDTH          210
#define OBJECT_IMAGE_HEIGHT         211
#define OBJECT_IMAGE_DEPTH          212
#define OBJECT_PARENT               213
#define OBJECT_ASSOCIATION_TYPE     214
#define OBJECT_ASSOCIATION_DESC     215
#define OBJECT_SEQUENCE_NUMBER      216
#define OBJECT_NAME                 217
#define OBJECT_DATE_CREATED         218
#define OBJECT_DATE_MODIFIED        219
#define OBJECT_KEYWORDS             220

MtpCursor::MtpCursor(MtpClient* client, int queryType, int deviceID,
                int storageID, int objectID, int columnCount, int* columns)
        :   mClient(client),
            mQueryType(queryType),
            mDeviceID(deviceID),
            mStorageID(storageID),
            mQbjectID(objectID),
            mColumnCount(columnCount),
            mColumns(NULL)
{
    if (columns) {
        mColumns = new int[columnCount];
        memcpy(mColumns, columns, columnCount * sizeof(int));
    }
}

MtpCursor::~MtpCursor() {
    delete[] mColumns;
}

int MtpCursor::fillWindow(CursorWindow* window, int startPos) {
    LOGD("MtpCursor::fillWindow mQueryType: %d\n", mQueryType);

    switch (mQueryType) {
        case DEVICE:
            return fillDevices(window, startPos);
        case DEVICE_ID:
            return fillDevice(window, startPos);
        case STORAGE:
            return fillStorages(window, startPos);
        case STORAGE_ID:
            return fillStorage(window, startPos);
        case OBJECT:
            return fillObjects(window, 0, startPos);
        case OBJECT_ID:
            return fillObject(window, startPos);
        case STORAGE_CHILDREN:
            return fillObjects(window, -1, startPos);
        case OBJECT_CHILDREN:
            return fillObjects(window, mQbjectID, startPos);
        default:
            LOGE("MtpCursor::fillWindow: unknown query type %d\n", mQueryType);
            return 0;
    }
}

int MtpCursor::fillDevices(CursorWindow* window, int startPos) {
    int count = 0;
    MtpDeviceList& deviceList = mClient->getDeviceList();
    for (int i = 0; i < deviceList.size(); i++) {
        MtpDevice* device = deviceList[i];
        if (fillDevice(window, device, startPos)) {
            count++;
            startPos++;
        } else {
            break;
        }
    }
    return count;
}

int MtpCursor::fillDevice(CursorWindow* window, int startPos) {
    MtpDevice* device = mClient->getDevice(mDeviceID);
    if (device && fillDevice(window, device, startPos))
        return 1;
    else
        return 0;
}

int MtpCursor::fillStorages(CursorWindow* window, int startPos) {
    int count = 0;
    MtpDevice* device = mClient->getDevice(mDeviceID);
    if (!device)
        return 0;
    MtpStorageIDList* storageIDs = device->getStorageIDs();
    if (!storageIDs)
        return 0;

    for (int i = 0; i < storageIDs->size(); i++) {
        MtpStorageID storageID = (*storageIDs)[i];
        if (fillStorage(window, device, storageID, startPos)) {
            count++;
            startPos++;
        } else {
            break;
        }
    }
    delete storageIDs;
    return count;
}

int MtpCursor::fillStorage(CursorWindow* window, int startPos) {
    MtpDevice* device = mClient->getDevice(mDeviceID);
    if (device && fillStorage(window, device, mStorageID, startPos))
        return 1;
    else
        return 0;
}

int MtpCursor::fillObjects(CursorWindow* window, int parent, int startPos) {
    int count = 0;
    MtpDevice* device = mClient->getDevice(mDeviceID);
    if (!device)
        return 0;
    MtpObjectHandleList* handles = device->getObjectHandles(mStorageID, 0, parent);
    if (!handles)
        return 0;

    for (int i = 0; i < handles->size(); i++) {
        MtpObjectHandle handle = (*handles)[i];
        if (fillObject(window, device, handle, startPos)) {
            count++;
            startPos++;
        } else {
            break;
        }
    }
    delete handles;
    return count;
}

int MtpCursor::fillObject(CursorWindow* window, int startPos) {
    MtpDevice* device = mClient->getDevice(mDeviceID);
    if (device && fillObject(window, device, mQbjectID, startPos))
        return 1;
    else
        return 0;
}

bool MtpCursor::fillDevice(CursorWindow* window, MtpDevice* device, int row) {
    MtpDeviceInfo* deviceInfo = device->getDeviceInfo();
    if (!deviceInfo)
        return false;
    if (!prepareRow(window))
        return false;

    for (int i = 0; i < mColumnCount; i++) {
        switch (mColumns[i]) {
            case DEVICE_ROW_ID:
                if (!putLong(window, device->getID(), row, i))
                    return false;
                 break;
            case DEVICE_MANUFACTURER:
                if (!putString(window, deviceInfo->mManufacturer, row, i))
                    return false;
                 break;
            case DEVICE_MODEL:
                if (!putString(window, deviceInfo->mModel, row, i))
                    return false;
                 break;
            default:
                LOGE("fillDevice: unknown column %d\n", mColumns[i]);
                return false;
        }
    }

    return true;
}

bool MtpCursor::fillStorage(CursorWindow* window, MtpDevice* device,
        MtpStorageID storageID, int row) {

LOGD("fillStorage %d\n", storageID);

    MtpStorageInfo* storageInfo = device->getStorageInfo(storageID);
    if (!storageInfo)
        return false;
    if (!prepareRow(window)) {
        delete storageInfo;
        return false;
    }

    const char* text;
    for (int i = 0; i < mColumnCount; i++) {
        switch (mColumns[i]) {
            case STORAGE_ROW_ID:
                if (!putLong(window, storageID, row, i))
                    goto fail;
                 break;
            case STORAGE_IDENTIFIER:
                text = storageInfo->mVolumeIdentifier;
                if (!text || !text[0])
                    text = "Camera Storage";
                if (!putString(window, text, row, i))
                    goto fail;
                 break;
            case STORAGE_DESCRIPTION:
                text = storageInfo->mStorageDescription;
                if (!text || !text[0])
                    text = "Storage Description";
                if (!putString(window, text, row, i))
                    goto fail;
                 break;
            default:
                LOGE("fillStorage: unknown column %d\n", mColumns[i]);
                goto fail;
        }
    }

    delete storageInfo;
    return true;

fail:
    delete storageInfo;
    return false;
}

bool MtpCursor::fillObject(CursorWindow* window, MtpDevice* device,
        MtpObjectHandle objectID, int row) {

    MtpObjectInfo* objectInfo = device->getObjectInfo(objectID);
    if (!objectInfo)
        return false;
    // objectInfo->print();
    if (!prepareRow(window)) {
        delete objectInfo;
        return false;
    }

    for (int i = 0; i < mColumnCount; i++) {
        switch (mColumns[i]) {
            case OBJECT_ROW_ID:
                if (!putLong(window, objectID, row, i))
                    goto fail;
                 break;
            case OBJECT_STORAGE_ID:
                if (!putLong(window, objectInfo->mStorageID, row, i))
                    goto fail;
                 break;
            case OBJECT_FORMAT:
                if (!putLong(window, objectInfo->mFormat, row, i))
                    goto fail;
                 break;
            case OBJECT_PROTECTION_STATUS:
                if (!putLong(window, objectInfo->mProtectionStatus, row, i))
                    goto fail;
                 break;
            case OBJECT_SIZE:
                if (!putLong(window, objectInfo->mCompressedSize, row, i))
                    goto fail;
                 break;
            case OBJECT_THUMB_FORMAT:
                if (!putLong(window, objectInfo->mThumbFormat, row, i))
                    goto fail;
                 break;
            case OBJECT_THUMB_SIZE:
                if (!putLong(window, objectInfo->mThumbCompressedSize, row, i))
                    goto fail;
                 break;
            case OBJECT_THUMB_WIDTH:
                if (!putLong(window, objectInfo->mThumbPixWidth, row, i))
                    goto fail;
                 break;
            case OBJECT_THUMB_HEIGHT:
                if (!putLong(window, objectInfo->mThumbPixHeight, row, i))
                    goto fail;
                 break;
            case OBJECT_IMAGE_WIDTH:
                if (!putLong(window, objectInfo->mImagePixWidth, row, i))
                    goto fail;
                 break;
            case OBJECT_IMAGE_HEIGHT:
                if (!putLong(window, objectInfo->mImagePixHeight, row, i))
                    goto fail;
                 break;
            case OBJECT_IMAGE_DEPTH:
                if (!putLong(window, objectInfo->mImagePixDepth, row, i))
                    goto fail;
                 break;
            case OBJECT_PARENT:
                if (!putLong(window, objectInfo->mParent, row, i))
                    goto fail;
                 break;
            case OBJECT_ASSOCIATION_TYPE:
                if (!putLong(window, objectInfo->mAssociationType, row, i))
                    goto fail;
                 break;
            case OBJECT_ASSOCIATION_DESC:
                if (!putLong(window, objectInfo->mAssociationDesc, row, i))
                    goto fail;
                 break;
            case OBJECT_SEQUENCE_NUMBER:
                if (!putLong(window, objectInfo->mSequenceNumber, row, i))
                    goto fail;
                 break;
            case OBJECT_NAME:
                if (!putString(window, objectInfo->mName, row, i))
                    goto fail;
                 break;
            case OBJECT_DATE_CREATED:
                if (!putLong(window, objectInfo->mDateCreated, row, i))
                    goto fail;
                 break;
            case OBJECT_DATE_MODIFIED:
                if (!putLong(window, objectInfo->mDateModified, row, i))
                    goto fail;
                 break;
            case OBJECT_KEYWORDS:
                if (!putString(window, objectInfo->mKeywords, row, i))
                    goto fail;
                 break;
            default:
                LOGE("fillStorage: unknown column %d\n", mColumns[i]);
                goto fail;
        }
    }

    delete objectInfo;
    return true;

fail:
    delete objectInfo;
    return false;
}

bool MtpCursor::prepareRow(CursorWindow* window) {
    if (!window->setNumColumns(mColumnCount)) {
        LOGE("Failed to change column count from %d to %d", window->getNumColumns(), mColumnCount);
        return false;
    }
    field_slot_t * fieldDir = window->allocRow();
    if (!fieldDir) {
        LOGE("Failed allocating fieldDir");
        return false;
    }
    return true;
}


bool MtpCursor::putLong(CursorWindow* window, int value, int row, int column) {

    if (!window->putLong(row, column, value)) {
        window->freeLastRow();
        LOGE("Failed allocating space for a long in column %d", column);
        return false;
    }
    return true;
}

bool MtpCursor::putString(CursorWindow* window, const char* text, int row, int column) {
    int size = strlen(text) + 1;
    int offset = window->alloc(size);
    if (!offset) {
        window->freeLastRow();
        LOGE("Failed allocating %u bytes for text/blob %s", size, text);
        return false;
    }
    window->copyIn(offset, (const uint8_t*)text, size);

    // This must be updated after the call to alloc(), since that
    // may move the field around in the window
    field_slot_t * fieldSlot = window->getFieldSlot(row, column);
    fieldSlot->type = FIELD_TYPE_STRING;
    fieldSlot->data.buffer.offset = offset;
    fieldSlot->data.buffer.size = size;
    return true;
}

} // namespace android
