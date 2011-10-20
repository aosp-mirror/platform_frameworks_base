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

#include <drm/DrmMetadata.h>

using namespace android;

int DrmMetadata::getCount(void) const {
	return mMetadataMap.size();
}

status_t DrmMetadata::put(const String8* key,
                          const char* value) {
    if((value != NULL) && (key != NULL)) {
        int length = strlen(value);
        char* charValue = new char[length + 1];

        memcpy(charValue, value, length);
        charValue[length] = '\0';
        mMetadataMap.add(*key, charValue);
    }
    return NO_ERROR;
}

String8 DrmMetadata::get(const String8& key) const {
    if (NULL != getValue(&key)) {
        return String8(getValue(&key));
    }
    else {
        return String8("");
    }
}

const char* DrmMetadata::getValue(const String8* key) const {
    if(key != NULL) {
        if (NAME_NOT_FOUND != mMetadataMap.indexOfKey(*key)) {
            return mMetadataMap.valueFor(*key);
        }
        else {
            return NULL;
        }
    } else {
        return NULL;
    }
}

const char* DrmMetadata::getAsByteArray(const String8* key) const {
    return getValue(key);
}

bool DrmMetadata::KeyIterator::hasNext() {
    return mIndex < mDrmMetadata->mMetadataMap.size();
}

const String8& DrmMetadata::KeyIterator::next() {
    const String8& key = mDrmMetadata->mMetadataMap.keyAt(mIndex);
    mIndex++;
    return key;
}

DrmMetadata::KeyIterator DrmMetadata::keyIterator() {
    return KeyIterator(this);
}

DrmMetadata::KeyIterator::KeyIterator(const DrmMetadata::KeyIterator& keyIterator) :
    mDrmMetadata(keyIterator.mDrmMetadata),
    mIndex(keyIterator.mIndex) {
    ALOGV("DrmMetadata::KeyIterator::KeyIterator");
}

DrmMetadata::KeyIterator& DrmMetadata::KeyIterator::operator=(const DrmMetadata::KeyIterator& keyIterator) {
    ALOGV("DrmMetadata::KeyIterator::operator=");
    mDrmMetadata = keyIterator.mDrmMetadata;
    mIndex = keyIterator.mIndex;
    return *this;
}


DrmMetadata::Iterator DrmMetadata::iterator() {
    return Iterator(this);
}

DrmMetadata::Iterator::Iterator(const DrmMetadata::Iterator& iterator) :
    mDrmMetadata(iterator.mDrmMetadata),
    mIndex(iterator.mIndex) {
    ALOGV("DrmMetadata::Iterator::Iterator");
}

DrmMetadata::Iterator& DrmMetadata::Iterator::operator=(const DrmMetadata::Iterator& iterator) {
    ALOGV("DrmMetadata::Iterator::operator=");
    mDrmMetadata = iterator.mDrmMetadata;
    mIndex = iterator.mIndex;
    return *this;
}

bool DrmMetadata::Iterator::hasNext() {
    return mIndex < mDrmMetadata->mMetadataMap.size();
}

String8 DrmMetadata::Iterator::next() {
    String8 value = String8(mDrmMetadata->mMetadataMap.editValueAt(mIndex));
    mIndex++;
    return value;
}
