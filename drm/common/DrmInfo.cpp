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

#include <drm/DrmInfo.h>

using namespace android;

DrmInfo::DrmInfo(int infoType, const DrmBuffer& drmBuffer, const String8& mimeType) :
    mInfoType(infoType),
    mData(drmBuffer),
    mMimeType(mimeType) {

}

int DrmInfo::getInfoType(void) const {
    return mInfoType;
}

String8 DrmInfo::getMimeType(void) const {
    return mMimeType;
}

const DrmBuffer& DrmInfo::getData(void) const {
    return mData;
}

int DrmInfo::getCount(void) const {
    return mAttributes.size();
}

status_t DrmInfo::put(const String8& key, const String8& value) {
    mAttributes.add(key, value);
    return DRM_NO_ERROR;
}

String8 DrmInfo::get(const String8& key) const {
    if (NAME_NOT_FOUND != mAttributes.indexOfKey(key)) {
        return mAttributes.valueFor(key);
    }
    return String8("");
}

int DrmInfo::indexOfKey(const String8& key) const {
    return mAttributes.indexOfKey(key);
}

DrmInfo::KeyIterator DrmInfo::keyIterator() const {
    return KeyIterator(this);
}

DrmInfo::Iterator DrmInfo::iterator() const {
    return Iterator(this);
}

// KeyIterator implementation
DrmInfo::KeyIterator::KeyIterator(const DrmInfo::KeyIterator& keyIterator) :
    mDrmInfo(keyIterator.mDrmInfo), mIndex(keyIterator.mIndex) {

}

bool DrmInfo::KeyIterator::hasNext() {
    return (mIndex < mDrmInfo->mAttributes.size());
}

const String8& DrmInfo::KeyIterator::next() {
    const String8& key = mDrmInfo->mAttributes.keyAt(mIndex);
    mIndex++;
    return key;
}

DrmInfo::KeyIterator& DrmInfo::KeyIterator::operator=(const DrmInfo::KeyIterator& keyIterator) {
    mDrmInfo = keyIterator.mDrmInfo;
    mIndex = keyIterator.mIndex;
    return *this;
}

// Iterator implementation
DrmInfo::Iterator::Iterator(const DrmInfo::Iterator& iterator)
    : mDrmInfo(iterator.mDrmInfo), mIndex(iterator.mIndex) {

}

DrmInfo::Iterator& DrmInfo::Iterator::operator=(const DrmInfo::Iterator& iterator) {
    mDrmInfo = iterator.mDrmInfo;
    mIndex = iterator.mIndex;
    return *this;
}

bool DrmInfo::Iterator::hasNext() {
    return mIndex < mDrmInfo->mAttributes.size();
}

String8& DrmInfo::Iterator::next() {
    String8& value = mDrmInfo->mAttributes.editValueAt(mIndex);
    mIndex++;
    return value;
}

