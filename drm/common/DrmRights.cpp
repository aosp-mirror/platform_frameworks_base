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

#include <drm/DrmRights.h>
#include <ReadWriteUtils.h>

using namespace android;

DrmRights::DrmRights(const String8& rightsFilePath, const String8& mimeType,
            const String8& accountId, const String8& subscriptionId) :
    mMimeType(mimeType),
    mAccountId(accountId),
    mSubscriptionId(subscriptionId),
    mRightsFromFile(NULL) {
    int rightsLength = 0;
    if (String8("") != rightsFilePath) {
        rightsLength = ReadWriteUtils::readBytes(rightsFilePath, &mRightsFromFile);
    }
    mData = DrmBuffer(mRightsFromFile, rightsLength);
}

DrmRights::DrmRights(const DrmBuffer& rightsData, const String8& mimeType,
            const String8& accountId, const String8& subscriptionId) :
    mData(rightsData),
    mMimeType(mimeType),
    mAccountId(accountId),
    mSubscriptionId(subscriptionId),
    mRightsFromFile(NULL) {
}

DrmRights::~DrmRights() {
    delete[] mRightsFromFile; mRightsFromFile = NULL;
}

const DrmBuffer& DrmRights::getData(void) const {
    return mData;
}

String8 DrmRights::getMimeType(void) const {
    return mMimeType;
}

String8 DrmRights::getAccountId(void) const {
    return mAccountId;
}

String8 DrmRights::getSubscriptionId(void) const {
    return mSubscriptionId;
}

