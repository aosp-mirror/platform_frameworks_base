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

#include <drm/DrmConstraints.h>

using namespace android;

const String8 DrmConstraints::MAX_REPEAT_COUNT("max_repeat_count");
const String8 DrmConstraints::REMAINING_REPEAT_COUNT("remaining_repeat_count");
const String8 DrmConstraints::LICENSE_START_TIME("license_start_time");
const String8 DrmConstraints::LICENSE_EXPIRY_TIME("license_expiry_time");
const String8 DrmConstraints::LICENSE_AVAILABLE_TIME("license_available_time");
const String8 DrmConstraints::EXTENDED_METADATA("extended_metadata");

int DrmConstraints::getCount(void) const {
    return mConstraintMap.size();
}

status_t DrmConstraints::put(const String8* key, const char* value) {
    int length = strlen(value);
    char* charValue = new char[length + 1];
    if (NULL != charValue) {
        strncpy(charValue, value, length);
        charValue[length] = '\0';
        mConstraintMap.add(*key, charValue);
    }
    return DRM_NO_ERROR;
}

String8 DrmConstraints::get(const String8& key) const {
    if (NULL != getValue(&key)) {
        return String8(getValue(&key));
    }
    return String8("");
}

const char* DrmConstraints::getValue(const String8* key) const {
    if (NAME_NOT_FOUND != mConstraintMap.indexOfKey(*key)) {
        return mConstraintMap.valueFor(*key);
    }
    return NULL;
}

const char* DrmConstraints::getAsByteArray(const String8* key) const {
    return getValue(key);
}

bool DrmConstraints::KeyIterator::hasNext() {
    return mIndex < mDrmConstraints->mConstraintMap.size();
}

const String8& DrmConstraints::KeyIterator::next() {
    const String8& key = mDrmConstraints->mConstraintMap.keyAt(mIndex);
    mIndex++;
    return key;
}

DrmConstraints::KeyIterator DrmConstraints::keyIterator() {
    return KeyIterator(this);
}

DrmConstraints::KeyIterator::KeyIterator(const DrmConstraints::KeyIterator& keyIterator)
    : mDrmConstraints(keyIterator.mDrmConstraints),
      mIndex(keyIterator.mIndex) {
}

DrmConstraints::KeyIterator& DrmConstraints::KeyIterator::operator=(
    const DrmConstraints::KeyIterator& keyIterator) {
    mDrmConstraints = keyIterator.mDrmConstraints;
    mIndex = keyIterator.mIndex;
    return *this;
}


DrmConstraints::Iterator DrmConstraints::iterator() {
    return Iterator(this);
}

DrmConstraints::Iterator::Iterator(const DrmConstraints::Iterator& iterator) :
    mDrmConstraints(iterator.mDrmConstraints),
    mIndex(iterator.mIndex) {
}

DrmConstraints::Iterator& DrmConstraints::Iterator::operator=(
    const DrmConstraints::Iterator& iterator) {
    mDrmConstraints = iterator.mDrmConstraints;
    mIndex = iterator.mIndex;
    return *this;
}

bool DrmConstraints::Iterator::hasNext() {
    return mIndex < mDrmConstraints->mConstraintMap.size();
}

String8 DrmConstraints::Iterator::next() {
    String8 value = String8(mDrmConstraints->mConstraintMap.editValueAt(mIndex));
    mIndex++;
    return value;
}

