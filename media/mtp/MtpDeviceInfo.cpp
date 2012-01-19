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

#define LOG_TAG "MtpDeviceInfo"

#include "MtpDebug.h"
#include "MtpDataPacket.h"
#include "MtpDeviceInfo.h"
#include "MtpStringBuffer.h"

namespace android {

MtpDeviceInfo::MtpDeviceInfo()
    :   mStandardVersion(0),
        mVendorExtensionID(0),
        mVendorExtensionVersion(0),
        mVendorExtensionDesc(NULL),
        mFunctionalCode(0),
        mOperations(NULL),
        mEvents(NULL),
        mDeviceProperties(NULL),
        mCaptureFormats(NULL),
        mPlaybackFormats(NULL),
        mManufacturer(NULL),
        mModel(NULL),
        mVersion(NULL),
        mSerial(NULL)
{
}

MtpDeviceInfo::~MtpDeviceInfo() {
    if (mVendorExtensionDesc)
        free(mVendorExtensionDesc);
    delete mOperations;
    delete mEvents;
    delete mDeviceProperties;
    delete mCaptureFormats;
    delete mPlaybackFormats;
    if (mManufacturer)
        free(mManufacturer);
    if (mModel)
        free(mModel);
    if (mVersion)
        free(mVersion);
    if (mSerial)
        free(mSerial);
}

void MtpDeviceInfo::read(MtpDataPacket& packet) {
    MtpStringBuffer string;

    // read the device info
    mStandardVersion = packet.getUInt16();
    mVendorExtensionID = packet.getUInt32();
    mVendorExtensionVersion = packet.getUInt16();

    packet.getString(string);
    mVendorExtensionDesc = strdup((const char *)string);

    mFunctionalCode = packet.getUInt16();
    mOperations = packet.getAUInt16();
    mEvents = packet.getAUInt16();
    mDeviceProperties = packet.getAUInt16();
    mCaptureFormats = packet.getAUInt16();
    mPlaybackFormats = packet.getAUInt16();

    packet.getString(string);
    mManufacturer = strdup((const char *)string);
    packet.getString(string);
    mModel = strdup((const char *)string);
    packet.getString(string);
    mVersion = strdup((const char *)string);
    packet.getString(string);
    mSerial = strdup((const char *)string);
}

void MtpDeviceInfo::print() {
    ALOGV("Device Info:\n\tmStandardVersion: %d\n\tmVendorExtensionID: %d\n\tmVendorExtensionVersiony: %d\n",
            mStandardVersion, mVendorExtensionID, mVendorExtensionVersion);
    ALOGV("\tmVendorExtensionDesc: %s\n\tmFunctionalCode: %d\n\tmManufacturer: %s\n\tmModel: %s\n\tmVersion: %s\n\tmSerial: %s\n",
            mVendorExtensionDesc, mFunctionalCode, mManufacturer, mModel, mVersion, mSerial);
}

}  // namespace android
