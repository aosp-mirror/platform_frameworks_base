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

#include <drm/DrmSupportInfo.h>
#include <strings.h>

using namespace android;

DrmSupportInfo::DrmSupportInfo() {

}

DrmSupportInfo::DrmSupportInfo(const DrmSupportInfo& drmSupportInfo):
    mMimeTypeVector(drmSupportInfo.mMimeTypeVector),
    mFileSuffixVector(drmSupportInfo.mFileSuffixVector),
    mDescription(drmSupportInfo.mDescription) {

}

bool DrmSupportInfo::operator<(const DrmSupportInfo& drmSupportInfo) const {
    // Do we need to check mMimeTypeVector & mFileSuffixVector ?
    // Note Vector doesn't overrides "<" operator
    return mDescription < drmSupportInfo.mDescription;
}

bool DrmSupportInfo::operator==(const DrmSupportInfo& drmSupportInfo) const {
    // Do we need to check mMimeTypeVector & mFileSuffixVector ?
    // Note Vector doesn't overrides "==" operator
    return (mDescription == drmSupportInfo.mDescription);
}

bool DrmSupportInfo::isSupportedMimeType(const String8& mimeType) const {
    if (String8("") == mimeType) {
        return false;
    }

    for (unsigned int i = 0; i < mMimeTypeVector.size(); i++) {
        const String8 item = mMimeTypeVector.itemAt(i);

        if (!strcasecmp(item.string(), mimeType.string())) {
            return true;
        }
    }
    return false;
}

bool DrmSupportInfo::isSupportedFileSuffix(const String8& fileType) const {
    for (unsigned int i = 0; i < mFileSuffixVector.size(); i++) {
        const String8 item = mFileSuffixVector.itemAt(i);

        if (!strcasecmp(item.string(), fileType.string())) {
            return true;
        }
    }
    return false;
}

DrmSupportInfo& DrmSupportInfo::operator=(const DrmSupportInfo& drmSupportInfo) {
    mMimeTypeVector = drmSupportInfo.mMimeTypeVector;
    mFileSuffixVector = drmSupportInfo.mFileSuffixVector;
    mDescription = drmSupportInfo.mDescription;
    return *this;
}

int DrmSupportInfo::getMimeTypeCount(void) const {
    return mMimeTypeVector.size();
}

int DrmSupportInfo::getFileSuffixCount(void) const {
    return mFileSuffixVector.size();
}

status_t DrmSupportInfo::addMimeType(const String8& mimeType) {
    mMimeTypeVector.push(mimeType);
    return DRM_NO_ERROR;
}

status_t DrmSupportInfo::addFileSuffix(const String8& fileSuffix) {
    mFileSuffixVector.push(fileSuffix);
    return DRM_NO_ERROR;
}

status_t DrmSupportInfo::setDescription(const String8& description) {
    mDescription = description;
    return DRM_NO_ERROR;
}

String8 DrmSupportInfo::getDescription() const {
    return mDescription;
}

DrmSupportInfo::FileSuffixIterator DrmSupportInfo::getFileSuffixIterator() {
    return FileSuffixIterator(this);
}

DrmSupportInfo::MimeTypeIterator DrmSupportInfo::getMimeTypeIterator() {
    return MimeTypeIterator(this);
}

DrmSupportInfo::FileSuffixIterator::FileSuffixIterator(
    const DrmSupportInfo::FileSuffixIterator& iterator) :
    mDrmSupportInfo(iterator.mDrmSupportInfo),
    mIndex(iterator.mIndex) {

}

DrmSupportInfo::FileSuffixIterator& DrmSupportInfo::FileSuffixIterator::operator=(
    const DrmSupportInfo::FileSuffixIterator& iterator) {
    mDrmSupportInfo = iterator.mDrmSupportInfo;
    mIndex = iterator.mIndex;
    return *this;
}

bool DrmSupportInfo::FileSuffixIterator::hasNext() {
    return mIndex < mDrmSupportInfo->mFileSuffixVector.size();
}

String8& DrmSupportInfo::FileSuffixIterator::next() {
    String8& value = mDrmSupportInfo->mFileSuffixVector.editItemAt(mIndex);
    mIndex++;
    return value;
}

DrmSupportInfo::MimeTypeIterator::MimeTypeIterator(
    const DrmSupportInfo::MimeTypeIterator& iterator) :
    mDrmSupportInfo(iterator.mDrmSupportInfo),
    mIndex(iterator.mIndex) {

}

DrmSupportInfo::MimeTypeIterator& DrmSupportInfo::MimeTypeIterator::operator=(
    const DrmSupportInfo::MimeTypeIterator& iterator) {
    mDrmSupportInfo = iterator.mDrmSupportInfo;
    mIndex = iterator.mIndex;
    return *this;
}

bool DrmSupportInfo::MimeTypeIterator::hasNext() {
    return mIndex < mDrmSupportInfo->mMimeTypeVector.size();
}

String8& DrmSupportInfo::MimeTypeIterator::next() {
    String8& value = mDrmSupportInfo->mMimeTypeVector.editItemAt(mIndex);
    mIndex++;
    return value;
}
