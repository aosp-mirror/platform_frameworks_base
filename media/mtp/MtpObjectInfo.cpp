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

#define LOG_TAG "MtpObjectInfo"

#include "MtpDebug.h"
#include "MtpDataPacket.h"
#include "MtpObjectInfo.h"
#include "MtpStringBuffer.h"
#include "MtpUtils.h"

namespace android {

MtpObjectInfo::MtpObjectInfo(MtpObjectHandle handle)
    :   mHandle(handle),
        mStorageID(0),
        mFormat(0),
        mProtectionStatus(0),
        mCompressedSize(0),
        mThumbFormat(0),
        mThumbCompressedSize(0),
        mThumbPixWidth(0),
        mThumbPixHeight(0),
        mImagePixWidth(0),
        mImagePixHeight(0),
        mImagePixDepth(0),
        mParent(0),
        mAssociationType(0),
        mAssociationDesc(0),
        mSequenceNumber(0),
        mName(NULL),
        mDateCreated(0),
        mDateModified(0),
        mKeywords(NULL)
{
}

MtpObjectInfo::~MtpObjectInfo() {
    if (mName)
        free(mName);
    if (mKeywords)
        free(mKeywords);
}

void MtpObjectInfo::read(MtpDataPacket& packet) {
    MtpStringBuffer string;
    time_t time;

    mStorageID = packet.getUInt32();
    mFormat = packet.getUInt16();
    mProtectionStatus = packet.getUInt16();
    mCompressedSize = packet.getUInt32();
    mThumbFormat = packet.getUInt16();
    mThumbCompressedSize = packet.getUInt32();
    mThumbPixWidth = packet.getUInt32();
    mThumbPixHeight = packet.getUInt32();
    mImagePixWidth = packet.getUInt32();
    mImagePixHeight = packet.getUInt32();
    mImagePixDepth = packet.getUInt32();
    mParent = packet.getUInt32();
    mAssociationType = packet.getUInt16();
    mAssociationDesc = packet.getUInt32();
    mSequenceNumber = packet.getUInt32();

    packet.getString(string);
    mName = strdup((const char *)string);

    packet.getString(string);
    if (parseDateTime((const char*)string, time))
        mDateCreated = time;

    packet.getString(string);
    if (parseDateTime((const char*)string, time))
        mDateModified = time;

    packet.getString(string);
    mKeywords = strdup((const char *)string);
}

void MtpObjectInfo::print() {
    ALOGD("MtpObject Info %08X: %s\n", mHandle, mName);
    ALOGD("  mStorageID: %08X mFormat: %04X mProtectionStatus: %d\n",
            mStorageID, mFormat, mProtectionStatus);
    ALOGD("  mCompressedSize: %d mThumbFormat: %04X mThumbCompressedSize: %d\n",
            mCompressedSize, mFormat, mThumbCompressedSize);
    ALOGD("  mThumbPixWidth: %d mThumbPixHeight: %d\n", mThumbPixWidth, mThumbPixHeight);
    ALOGD("  mImagePixWidth: %d mImagePixHeight: %d mImagePixDepth: %d\n",
            mImagePixWidth, mImagePixHeight, mImagePixDepth);
    ALOGD("  mParent: %08X mAssociationType: %04X mAssociationDesc: %04X\n",
            mParent, mAssociationType, mAssociationDesc);
    ALOGD("  mSequenceNumber: %d mDateCreated: %ld mDateModified: %ld mKeywords: %s\n",
            mSequenceNumber, mDateCreated, mDateModified, mKeywords);
}

}  // namespace android
