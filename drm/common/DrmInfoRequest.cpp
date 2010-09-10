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

#include <drm/DrmInfoRequest.h>

using namespace android;

const String8 DrmInfoRequest::ACCOUNT_ID("account_id");
const String8 DrmInfoRequest::SUBSCRIPTION_ID("subscription_id");

DrmInfoRequest::DrmInfoRequest(int infoType, const String8& mimeType) :
    mInfoType(infoType), mMimeType(mimeType) {

}

String8 DrmInfoRequest::getMimeType(void) const {
    return mMimeType;
}

int DrmInfoRequest::getInfoType(void) const {
    return mInfoType;
}

int DrmInfoRequest::getCount(void) const {
    return mRequestInformationMap.size();
}

status_t DrmInfoRequest::put(const String8& key, const String8& value) {
    mRequestInformationMap.add(key, value);
    return DRM_NO_ERROR;
}

String8 DrmInfoRequest::get(const String8& key) const {
    if (NAME_NOT_FOUND != mRequestInformationMap.indexOfKey(key)) {
        return mRequestInformationMap.valueFor(key);
    }
    return String8("");
}

DrmInfoRequest::KeyIterator DrmInfoRequest::keyIterator() const {
    return KeyIterator(this);
}

DrmInfoRequest::Iterator DrmInfoRequest::iterator() const {
    return Iterator(this);
}

// KeyIterator implementation
DrmInfoRequest::KeyIterator::KeyIterator(const DrmInfoRequest::KeyIterator& keyIterator)
    : mDrmInfoRequest(keyIterator.mDrmInfoRequest),
      mIndex(keyIterator.mIndex) {

}

bool DrmInfoRequest::KeyIterator::hasNext() {
    return (mIndex < mDrmInfoRequest->mRequestInformationMap.size());
}

const String8& DrmInfoRequest::KeyIterator::next() {
    const String8& key = mDrmInfoRequest->mRequestInformationMap.keyAt(mIndex);
    mIndex++;
    return key;
}

DrmInfoRequest::KeyIterator& DrmInfoRequest::KeyIterator::operator=(
    const DrmInfoRequest::KeyIterator& keyIterator) {
    mDrmInfoRequest = keyIterator.mDrmInfoRequest;
    mIndex = keyIterator.mIndex;
    return *this;
}

// Iterator implementation
DrmInfoRequest::Iterator::Iterator(const DrmInfoRequest::Iterator& iterator) :
    mDrmInfoRequest(iterator.mDrmInfoRequest), mIndex(iterator.mIndex) {
}

DrmInfoRequest::Iterator& DrmInfoRequest::Iterator::operator=(
    const DrmInfoRequest::Iterator& iterator) {
    mDrmInfoRequest = iterator.mDrmInfoRequest;
    mIndex = iterator.mIndex;
    return *this;
}

bool DrmInfoRequest::Iterator::hasNext() {
    return mIndex < mDrmInfoRequest->mRequestInformationMap.size();
}

String8& DrmInfoRequest::Iterator::next() {
    String8& value = mDrmInfoRequest->mRequestInformationMap.editValueAt(mIndex);
    mIndex++;
    return value;
}

