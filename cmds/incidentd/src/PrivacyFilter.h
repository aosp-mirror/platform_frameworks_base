/*
 * Copyright (C) 2017 The Android Open Source Project
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
#pragma once

#ifndef PRIVACY_BUFFER_H
#define PRIVACY_BUFFER_H

#include "Privacy.h"

#include "FdBuffer.h"

#include <android/os/IncidentReportArgs.h>
#include <android/util/ProtoOutputStream.h>
#include <stdint.h>
#include <utils/Errors.h>

namespace android {
namespace os {
namespace incidentd {

using namespace android::util;

/**
 * Class to wrap a file descriptor, so callers of PrivacyFilter
 * can associate additional data with each fd for their own
 * purposes.
 */
class FilterFd : public RefBase {
public:
    FilterFd(uint8_t privacyPolicy, int fd);
    virtual ~FilterFd();

    uint8_t getPrivacyPolicy() const { return mPrivacyPolicy; }
    int getFd() { return mFd;}

    virtual void onWriteError(status_t err) = 0;

private:
    uint8_t mPrivacyPolicy;
    int mFd;
};

/**
 * PrivacyFilter holds the original protobuf data and strips PII-sensitive fields
 * for several requests, streaming them to a set of corresponding file descriptors.
 */
class PrivacyFilter {
public:
    /**
     * Constructor, with the field --> privacy restrictions mapping.
     */
    PrivacyFilter(int sectionId, const Privacy* restrictions);

    ~PrivacyFilter();

    /**
     * Add a target file descriptor, and the privacy policy to which
     * it should be filtered.
     */
    void addFd(const sp<FilterFd>& output);

    /**
     * Write the data, filtered according to the privacy specs, to each of the
     * file descriptors.  Any non-NO_ERROR return codes are fatal to the whole
     * report.  Individual write errors to streams are reported via the callbacks
     * on the FilterFds.
     *
     * If maxSize is not NULL, it will be set to the maximum size buffer that
     * was written (i.e. after filtering).
     *
     * The buffer is assumed to have already been filtered to bufferLevel.
     */
    status_t writeData(const FdBuffer& buffer, uint8_t bufferLevel, size_t* maxSize);

private:
    int mSectionId;
    const Privacy* mRestrictions;
    vector<sp<FilterFd>> mOutputs;
};

status_t filter_and_write_report(int to, int from, uint8_t bufferLevel,
        const IncidentReportArgs& args);

}  // namespace incidentd
}  // namespace os
}  // namespace android

#endif  // PRIVACY_BUFFER_H
