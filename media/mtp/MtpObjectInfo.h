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

#ifndef _MTP_OBJECT_INFO_H
#define _MTP_OBJECT_INFO_H

#include "MtpTypes.h"

namespace android {

class MtpDataPacket;

class MtpObjectInfo {
public:
    MtpObjectHandle     mHandle;
    MtpStorageID        mStorageID;
    MtpObjectFormat     mFormat;
    uint16_t            mProtectionStatus;
    uint32_t            mCompressedSize;
    MtpObjectFormat     mThumbFormat;
    uint32_t            mThumbCompressedSize;
    uint32_t            mThumbPixWidth;
    uint32_t            mThumbPixHeight;
    uint32_t            mImagePixWidth;
    uint32_t            mImagePixHeight;
    uint32_t            mImagePixDepth;
    MtpObjectHandle     mParent;
    uint16_t            mAssociationType;
    uint32_t            mAssociationDesc;
    uint32_t            mSequenceNumber;
    char*               mName;
    time_t              mDateCreated;
    time_t              mDateModified;
    char*               mKeywords;

public:
                        MtpObjectInfo(MtpObjectHandle handle);
    virtual             ~MtpObjectInfo();

    void                read(MtpDataPacket& packet);

    void                print();
};

}; // namespace android

#endif // _MTP_OBJECT_INFO_H
