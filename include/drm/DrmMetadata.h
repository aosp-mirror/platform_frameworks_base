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

#ifndef __DRM_METADATA_H__
#define __DRM_METADATA_H__

#include "drm_framework_common.h"

namespace android {

/**
 * This is an utility class which contains the constraints information.
 *
 * As a result of DrmManagerClient::getMetadata(const String8*)
 * an instance of DrmMetadata would be returned.
 */
class DrmMetadata {
public:
    /**
     * Iterator for key
     */
    class KeyIterator {
        friend class DrmMetadata;
    private:
        KeyIterator(DrmMetadata* drmMetadata) : mDrmMetadata(drmMetadata), mIndex(0) {}

    public:
        KeyIterator(const KeyIterator& keyIterator);
        KeyIterator& operator=(const KeyIterator& keyIterator);
        virtual ~KeyIterator() {}

    public:
        bool hasNext();
        const String8& next();

    private:
        DrmMetadata* mDrmMetadata;
        unsigned int mIndex;
    };

    /**
     * Iterator for constraints
     */
    class Iterator {
        friend class DrmMetadata;
    private:
        Iterator(DrmMetadata* drmMetadata) : mDrmMetadata(drmMetadata), mIndex(0) {}

    public:
        Iterator(const Iterator& iterator);
        Iterator& operator=(const Iterator& iterator);
        virtual ~Iterator() {}

    public:
        bool hasNext();
        String8 next();

    private:
        DrmMetadata* mDrmMetadata;
        unsigned int mIndex;
    };

public:
    DrmMetadata() {}
    virtual ~DrmMetadata() {
        DrmMetadata::KeyIterator keyIt = this->keyIterator();

        while (keyIt.hasNext()) {
            String8 key = keyIt.next();
            const char* value = this->getAsByteArray(&key);
            if (NULL != value) {
                delete[] value;
                value = NULL;
            }
        }
        mMetadataMap.clear();
    }

public:
    int getCount(void) const;
    status_t put(const String8* key, const char* value);
    String8 get(const String8& key) const;
    const char* getAsByteArray(const String8* key) const;
    KeyIterator keyIterator();
    Iterator iterator();

private:
    const char* getValue(const String8* key) const;

private:
    typedef KeyedVector<String8, const char*> DrmMetadataMap;
    DrmMetadataMap mMetadataMap;
};

};

#endif /* __DRM_METADATA_H__ */

