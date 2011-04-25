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

#ifndef _MTP_DATABASE_H
#define _MTP_DATABASE_H

#include "MtpTypes.h"

namespace android {

class MtpDataPacket;
class MtpProperty;
class MtpObjectInfo;

class MtpDatabase {
public:
    virtual ~MtpDatabase() {}

    // called from SendObjectInfo to reserve a database entry for the incoming file
    virtual MtpObjectHandle         beginSendObject(const char* path,
                                            MtpObjectFormat format,
                                            MtpObjectHandle parent,
                                            MtpStorageID storage,
                                            uint64_t size,
                                            time_t modified) = 0;

    // called to report success or failure of the SendObject file transfer
    // success should signal a notification of the new object's creation,
    // failure should remove the database entry created in beginSendObject
    virtual void                    endSendObject(const char* path,
                                            MtpObjectHandle handle,
                                            MtpObjectFormat format,
                                            bool succeeded) = 0;

    virtual MtpObjectHandleList*    getObjectList(MtpStorageID storageID,
                                            MtpObjectFormat format,
                                            MtpObjectHandle parent) = 0;

    virtual int                     getNumObjects(MtpStorageID storageID,
                                            MtpObjectFormat format,
                                            MtpObjectHandle parent) = 0;

    // callee should delete[] the results from these
    // results can be NULL
    virtual MtpObjectFormatList*    getSupportedPlaybackFormats() = 0;
    virtual MtpObjectFormatList*    getSupportedCaptureFormats() = 0;
    virtual MtpObjectPropertyList*  getSupportedObjectProperties(MtpObjectFormat format) = 0;
    virtual MtpDevicePropertyList*  getSupportedDeviceProperties() = 0;

    virtual MtpResponseCode         getObjectPropertyValue(MtpObjectHandle handle,
                                            MtpObjectProperty property,
                                            MtpDataPacket& packet) = 0;

    virtual MtpResponseCode         setObjectPropertyValue(MtpObjectHandle handle,
                                            MtpObjectProperty property,
                                            MtpDataPacket& packet) = 0;

    virtual MtpResponseCode         getDevicePropertyValue(MtpDeviceProperty property,
                                            MtpDataPacket& packet) = 0;

    virtual MtpResponseCode         setDevicePropertyValue(MtpDeviceProperty property,
                                            MtpDataPacket& packet) = 0;

    virtual MtpResponseCode         resetDeviceProperty(MtpDeviceProperty property) = 0;

    virtual MtpResponseCode         getObjectPropertyList(MtpObjectHandle handle,
                                            uint32_t format, uint32_t property,
                                            int groupCode, int depth,
                                            MtpDataPacket& packet) = 0;

    virtual MtpResponseCode         getObjectInfo(MtpObjectHandle handle,
                                            MtpObjectInfo& info) = 0;

    virtual void*                   getThumbnail(MtpObjectHandle handle, size_t& outThumbSize) = 0;

    virtual MtpResponseCode         getObjectFilePath(MtpObjectHandle handle,
                                            MtpString& outFilePath,
                                            int64_t& outFileLength,
                                            MtpObjectFormat& outFormat) = 0;

    virtual MtpResponseCode         deleteFile(MtpObjectHandle handle) = 0;

    virtual MtpObjectHandleList*    getObjectReferences(MtpObjectHandle handle) = 0;

    virtual MtpResponseCode         setObjectReferences(MtpObjectHandle handle,
                                            MtpObjectHandleList* references) = 0;

    virtual MtpProperty*            getObjectPropertyDesc(MtpObjectProperty property,
                                            MtpObjectFormat format) = 0;

    virtual MtpProperty*            getDevicePropertyDesc(MtpDeviceProperty property) = 0;

    virtual void                    sessionStarted() = 0;

    virtual void                    sessionEnded() = 0;
};

}; // namespace android

#endif // _MTP_DATABASE_H
