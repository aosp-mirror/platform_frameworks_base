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

#ifndef __DRM_INFO_H__
#define __DRM_INFO_H__

#include "drm_framework_common.h"

namespace android {

/**
 * This is an utility class in which necessary information required to transact
 * between device and online DRM server is described. DRM Framework achieves
 * server registration, license acquisition and any other server related transaction
 * by passing an instance of this class to DrmManagerClient::processDrmInfo(const DrmInfo*).
 *
 * The Caller can retrieve the DrmInfo instance by using
 * DrmManagerClient::acquireDrmInfo(const DrmInfoRequest*) by passing DrmInfoRequest instance.
 *
 */
class DrmInfo {
public:
    /**
     * Constructor for DrmInfo
     *
     * @param[in] infoType Type of information
     * @param[in] drmBuffer Trigger data
     * @param[in] mimeType MIME type
     */
    DrmInfo(int infoType, const DrmBuffer& drmBuffer, const String8& mimeType);

    /**
     * Destructor for DrmInfo
     */
    virtual ~DrmInfo() {}

public:
    /**
     * Iterator for key
     */
    class KeyIterator {
        friend class DrmInfo;

    private:
        KeyIterator(const DrmInfo* drmInfo)
            : mDrmInfo(const_cast <DrmInfo*> (drmInfo)), mIndex(0) {}

    public:
        KeyIterator(const KeyIterator& keyIterator);
        KeyIterator& operator=(const KeyIterator& keyIterator);
        virtual ~KeyIterator() {}

    public:
        bool hasNext();
        const String8& next();

    private:
        DrmInfo* mDrmInfo;
        unsigned int mIndex;
    };

    /**
     * Iterator
     */
    class Iterator {
        friend class DrmInfo;

    private:
        Iterator(const DrmInfo* drmInfo)
            : mDrmInfo(const_cast <DrmInfo*> (drmInfo)), mIndex(0) {}

    public:
        Iterator(const Iterator& iterator);
        Iterator& operator=(const Iterator& iterator);
        virtual ~Iterator() {}

    public:
        bool hasNext();
        String8& next();

    private:
        DrmInfo* mDrmInfo;
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
     * Returns the trigger data associated with this instance
     *
     * @return Trigger data
     */
    const DrmBuffer& getData(void) const;

    /**
     * Returns the number of attributes contained in this instance
     *
     * @return Number of attributes
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

    /**
     * Returns index of the given key
     *
     * @return index
     */
    int indexOfKey(const String8& key) const;

protected:
    int mInfoType;
    DrmBuffer mData;
    String8 mMimeType;
    KeyedVector<String8, String8> mAttributes;
};

};

#endif /* __DRM_INFO_H__ */

