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

#ifndef __DRM_RIGHTS_H__
#define __DRM_RIGHTS_H__

#include "drm_framework_common.h"

namespace android {

/**
 * This is an utility class which wraps the license information which was
 * retrieved from the online DRM server.
 *
 * Caller can instantiate DrmRights by invoking DrmRights(const DrmBuffer&, String)
 * constructor by using the result of DrmManagerClient::ProcessDrmInfo(const DrmInfo*) API.
 * Caller can also instantiate DrmRights using the file path which contains rights information.
 *
 */
class DrmRights {
public:
    /**
     * Constructor for DrmRights
     *
     * @param[in] rightsFilePath Path of the file containing rights data
     * @param[in] mimeType MIME type
     * @param[in] accountId Account Id of the user
     * @param[in] subscriptionId Subscription Id of the user
     */
    DrmRights(
            const String8& rightsFilePath, const String8& mimeType,
            const String8& accountId = String8("_NO_USER"),
            const String8& subscriptionId = String8(""));

    /**
     * Constructor for DrmRights
     *
     * @param[in] rightsData Rights data
     * @param[in] mimeType MIME type
     * @param[in] accountId Account Id of the user
     * @param[in] subscriptionId Subscription Id of the user
     */
    DrmRights(
            const DrmBuffer& rightsData, const String8& mimeType,
            const String8& accountId = String8("_NO_USER"),
            const String8& subscriptionId = String8(""));

    /**
     * Destructor for DrmRights
     */
    virtual ~DrmRights();

public:
    /**
     * Returns the rights data associated with this instance
     *
     * @return Rights data
     */
    const DrmBuffer& getData(void) const;

    /**
     * Returns MIME type associated with this instance
     *
     * @return MIME type
     */
    String8 getMimeType(void) const;

    /**
     * Returns the account-id associated with this instance
     *
     * @return Account Id
     */
    String8 getAccountId(void) const;

    /**
     * Returns the subscription-id associated with this object
     *
     * @return Subscription Id
     */
    String8 getSubscriptionId(void) const;

private:
    DrmBuffer mData;
    String8 mMimeType;
    String8 mAccountId;
    String8 mSubscriptionId;
    char* mRightsFromFile;
};

};

#endif /* __DRM_RIGHTS_H__ */

