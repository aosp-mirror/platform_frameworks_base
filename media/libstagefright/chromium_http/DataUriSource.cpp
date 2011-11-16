/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <include/DataUriSource.h>

#include <net/base/data_url.h>
#include <googleurl/src/gurl.h>


namespace android {

DataUriSource::DataUriSource(const char *uri) :
    mDataUri(uri),
    mInited(NO_INIT) {

    // Copy1: const char *uri -> String8 mDataUri.
    std::string mimeTypeStr, unusedCharsetStr, dataStr;
    // Copy2: String8 mDataUri -> std::string
    const bool ret = net::DataURL::Parse(
            GURL(std::string(mDataUri.string())),
            &mimeTypeStr, &unusedCharsetStr, &dataStr);
    // Copy3: std::string dataStr -> AString mData
    mData.setTo(dataStr.data(), dataStr.length());
    mInited = ret ? OK : UNKNOWN_ERROR;

    // The chromium data url implementation defaults to using "text/plain"
    // if no mime type is specified. We prefer to leave this unspecified
    // instead, since the mime type is sniffed in most cases.
    if (mimeTypeStr != "text/plain") {
        mMimeType = mimeTypeStr.c_str();
    }
}

ssize_t DataUriSource::readAt(off64_t offset, void *out, size_t size) {
    if (mInited != OK) {
        return mInited;
    }

    const off64_t length = mData.size();
    if (offset >= length) {
        return UNKNOWN_ERROR;
    }

    const char *dataBuf = mData.c_str();
    const size_t bytesToCopy =
            offset + size >= length ? (length - offset) : size;

    if (bytesToCopy > 0) {
        memcpy(out, dataBuf + offset, bytesToCopy);
    }

    return bytesToCopy;
}

}  // namespace android
