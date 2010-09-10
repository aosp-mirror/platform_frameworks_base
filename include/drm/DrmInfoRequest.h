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

#ifndef __DRM_INFO_REQUEST_H__
#define __DRM_INFO_REQUEST_H__

#include "drm_framework_common.h"

namespace android {

/**
 * This is an utility class used to pass required parameters to get
 * the necessary information to communicate with online DRM server
 *
 * An instance of this class is passed to
 * DrmManagerClient::acquireDrmInfo(const DrmInfoRequest*) to get the
 * instance of DrmInfo.
 *
 */
class DrmInfoRequest {
public:
    // Changes in following constants should be in sync with DrmInfoRequest.java
    static const int TYPE_REGISTRATION_INFO = 1;
    static const int TYPE_UNREGISTRATION_INFO = 2;
    static const int TYPE_RIGHTS_ACQUISITION_INFO = 3;
    static const int TYPE_RIGHTS_ACQUISITION_PROGRESS_INFO = 4;

    /**
     * Key to pass the unique id for the account or the user
     */
    static const String8 ACCOUNT_ID;
    /**
     * Key to pass the subscription id
     */
    static const String8 SUBSCRIPTION_ID;

public:
    /**
     * Constructor for DrmInfoRequest
     *
     * @param[in] infoType Type of information
     * @param[in] mimeType MIME type
     */
    DrmInfoRequest(int infoType, const String8& mimeType);

    /**
     * Destructor for DrmInfoRequest
     */
    virtual ~DrmInfoRequest() {}

public:
    /**
     * Iterator for key
     */
    class KeyIterator {
        friend class DrmInfoRequest;

    private:
        KeyIterator(const DrmInfoRequest* drmInfoRequest)
            : mDrmInfoRequest(const_cast <DrmInfoRequest*> (drmInfoRequest)), mIndex(0) {}

    public:
        KeyIterator(const KeyIterator& keyIterator);
        KeyIterator& operator=(const KeyIterator& keyIterator);
        virtual ~KeyIterator() {}

    public:
        bool hasNext();
        const String8& next();

    private:
        DrmInfoRequest* mDrmInfoRequest;
        unsigned int mIndex;
    };

    /**
     * Iterator
     */
    class Iterator {
        friend class DrmInfoRequest;

    private:
        Iterator(const DrmInfoRequest* drmInfoRequest)
            : mDrmInfoRequest(const_cast <DrmInfoRequest*> (drmInfoRequest)), mIndex(0) {}

    public:
        Iterator(const Iterator& iterator);
        Iterator& operator=(const Iterator& iterator);
        virtual ~Iterator() {}

    public:
        bool hasNext();
        String8& next();

    private:
        DrmInfoRequest* mDrmInfoRequest;
        unsigned int mIndex;
    };

public:
    /**
     * Returns information type associated with this instance
     *
     * @return Information type
     */
    int getInfoType(void) const;

    /**
     * Returns MIME type associated with this instance
     *
     * @return MIME type
     */
    String8 getMimeType(void) const;

    /**
     * Returns the number of entries in DrmRequestInfoMap
     *
     * @return Number of entries
     */
    int getCount(void) const;

    /**
     * Adds optional information as <key, value> pair to this instance
     *
     * @param[in] key Key to add
     * @param[in] value Value to add
     * @return Returns the error code
     */
    status_t put(const String8& key, const String8& value);

    /**
     * Retrieves the value of given key
     *
     * @param key Key whose value to be retrieved
     * @return The value
     */
    String8 get(const String8& key) const;

    /**
     * Returns KeyIterator object to walk through the keys associated with this instance
     *
     * @return KeyIterator object
     */
    KeyIterator keyIterator() const;

    /**
     * Returns Iterator object to walk through the values associated with this instance
     *
     * @return Iterator object
     */
    Iterator iterator() const;

private:
    int mInfoType;
    String8 mMimeType;

    typedef KeyedVector<String8, String8> DrmRequestInfoMap;
    DrmRequestInfoMap mRequestInformationMap;
};

};

#endif /* __DRM_INFO_REQUEST_H__ */

