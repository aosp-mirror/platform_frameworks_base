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

#ifndef __DRM_CONSTRAINTS_H__
#define __DRM_CONSTRAINTS_H__

#include "drm_framework_common.h"

namespace android {

/**
 * This is an utility class which contains the constraints information.
 *
 * As a result of DrmManagerClient::getConstraints(const String8*, const int)
 * an instance of DrmConstraints would be returned.
 *
 */
class DrmConstraints {
public:
    /**
     * The following variables are replica of android.drm.DrmStore.ConstraintsColumns
     * Any changes should also be incorporated with Java Layer as well
     */
    /**
     * The max repeat count
     */
    static const String8 MAX_REPEAT_COUNT;
    /**
     * The remaining repeat count
     */
    static const String8 REMAINING_REPEAT_COUNT;

    /**
     * The time before which the protected file can not be played/viewed
     */
    static const String8 LICENSE_START_TIME;

    /**
     * The time after which the protected file can not be played/viewed
     */
    static const String8 LICENSE_EXPIRY_TIME;

    /**
     * The available time for license
     */
    static const String8 LICENSE_AVAILABLE_TIME;

    /**
     * The data stream for extended metadata
     */
    static const String8 EXTENDED_METADATA;

public:
    /**
     * Iterator for key
     */
    class KeyIterator {
        friend class DrmConstraints;
    private:
        KeyIterator(DrmConstraints* drmConstraints)
            : mDrmConstraints(drmConstraints), mIndex(0) {}

    public:
        KeyIterator(const KeyIterator& keyIterator);
        KeyIterator& operator=(const KeyIterator& keyIterator);
        virtual ~KeyIterator() {}

    public:
        bool hasNext();
        const String8& next();

    private:
        DrmConstraints* mDrmConstraints;
        unsigned int mIndex;
    };

    /**
     * Iterator for constraints
     */
    class Iterator {
        friend class DrmConstraints;
    private:
        Iterator(DrmConstraints* drmConstraints)
            : mDrmConstraints(drmConstraints), mIndex(0) {}

    public:
        Iterator(const Iterator& iterator);
        Iterator& operator=(const Iterator& iterator);
        virtual ~Iterator() {}

    public:
        bool hasNext();
        String8 next();

    private:
        DrmConstraints* mDrmConstraints;
        unsigned int mIndex;
    };

public:
    DrmConstraints() {}
    virtual ~DrmConstraints() {
        DrmConstraints::KeyIterator keyIt = this->keyIterator();

        while (keyIt.hasNext()) {
            String8 key = keyIt.next();
                const char* value = this->getAsByteArray(&key);
                if (NULL != value) {
                    delete[] value;
                    value = NULL;
                }
        }
        mConstraintMap.clear();
    }
public:
    /**
     * Returns the number of constraints contained in this instance
     *
     * @return Number of constraints
     */
    int getCount(void) const;

    /**
     * Adds constraint information as <key, value> pair to this instance
     *
     * @param[in] key Key to add
     * @param[in] value Value to add
     * @return Returns DRM_NO_ERROR for success, DRM_ERROR_UNKNOWN for failure
     */
    status_t put(const String8* key, const char* value);

    /**
     * Retrieves the value of given key
     *
     * @param key Key whose value to be retrieved
     * @return The value
     */
    String8 get(const String8& key) const;

     /**
     * Retrieves the value as byte array of given key
     * @param key Key whose value to be retrieved as byte array
     * @return The byte array value
     */
    const char* getAsByteArray(const String8* key) const;

    /**
     * Returns KeyIterator object to walk through the keys associated with this instance
     *
     * @return KeyIterator object
     */
    KeyIterator keyIterator();

    /**
     * Returns Iterator object to walk through the values associated with this instance
     *
     * @return Iterator object
     */
    Iterator iterator();
private:
    const char* getValue(const String8* key) const;
private:
    typedef KeyedVector<String8, const char*> DrmConstraintsMap;
    DrmConstraintsMap mConstraintMap;
};

};

#endif /* __DRM_CONSTRAINTS_H__ */

