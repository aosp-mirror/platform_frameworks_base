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

#ifndef _MTP_CURSOR_H
#define _MTP_CURSOR_H

#include "MtpTypes.h"

namespace android {

class CursorWindow;

class MtpCursor {
private:
    enum {
        DEVICE              = 1,
        DEVICE_ID           = 2,
        STORAGE             = 3,
        STORAGE_ID          = 4,
        OBJECT              = 5,
        OBJECT_ID           = 6,
        STORAGE_CHILDREN    = 7,
        OBJECT_CHILDREN     = 8,
    };

    MtpClient*  mClient;
    int         mQueryType;
    int         mDeviceID;
    int         mStorageID;
    int         mQbjectID;
    int         mColumnCount;
    int*        mColumns;

public:
                MtpCursor(MtpClient* client, int queryType, int deviceID,
                        int storageID, int objectID, int columnCount, int* columns);
    virtual     ~MtpCursor();

    int         fillWindow(CursorWindow* window, int startPos);

private:
    int         fillDevices(CursorWindow* window, int startPos);
    int         fillDevice(CursorWindow* window, int startPos);
    int         fillStorages(CursorWindow* window, int startPos);
    int         fillStorage(CursorWindow* window, int startPos);
    int         fillObjects(CursorWindow* window, int parent, int startPos);
    int         fillObject(CursorWindow* window, int startPos);

    bool        fillDevice(CursorWindow* window, MtpDevice* device, int startPos);
    bool        fillStorage(CursorWindow* window, MtpDevice* device,
                        MtpStorageID storageID, int row);
    bool        fillObject(CursorWindow* window, MtpDevice* device,
                        MtpObjectHandle objectID, int row);

    bool        prepareRow(CursorWindow* window);
    bool        putLong(CursorWindow* window, int value, int row, int column);
    bool        putString(CursorWindow* window, const char* text, int row, int column);
    bool        putThumbnail(CursorWindow* window, int objectID, int format, int row, int column);
};

}; // namespace android

#endif // _MTP_CURSOR_H
