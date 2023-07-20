/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "dumpstate"

#include <android/os/IncidentReportArgs.h>

#include <log/log.h>

namespace android {
namespace os {

IncidentReportArgs::IncidentReportArgs()
    :mSections(),
     mAll(false),
     mPrivacyPolicy(-1),
     mGzip(false)
{
}

IncidentReportArgs::IncidentReportArgs(const IncidentReportArgs& that)
    :mSections(that.mSections),
     mHeaders(that.mHeaders),
     mAll(that.mAll),
     mPrivacyPolicy(that.mPrivacyPolicy),
     mReceiverPkg(that.mReceiverPkg),
     mReceiverCls(that.mReceiverCls),
     mGzip(that.mGzip)
{
}

IncidentReportArgs::~IncidentReportArgs()
{
}

status_t
IncidentReportArgs::writeToParcel(Parcel* out) const
{
    status_t err;

    err = out->writeInt32(mAll);
    if (err != NO_ERROR) {
        return err;
    }

    err = out->writeInt32(mSections.size());
    if (err != NO_ERROR) {
        return err;
    }

    for (set<int>::const_iterator it=mSections.begin(); it!=mSections.end(); it++) {
        err = out->writeInt32(*it);
        if (err != NO_ERROR) {
            return err;
        }
    }

    err = out->writeInt32(mHeaders.size());
    if (err != NO_ERROR) {
        return err;
    }

    for (vector<vector<uint8_t>>::const_iterator it = mHeaders.begin(); it != mHeaders.end(); it++) {
        err = out->writeByteVector(*it);
        if (err != NO_ERROR) {
            return err;
        }
    }

    err = out->writeInt32(mPrivacyPolicy);
    if (err != NO_ERROR) {
        return err;
    }

    err = out->writeString16(String16(mReceiverPkg.c_str()));
    if (err != NO_ERROR) {
        return err;
    }

    err = out->writeString16(String16(mReceiverCls.c_str()));
    if (err != NO_ERROR) {
        return err;
    }

    err = out->writeInt32(mGzip);
    if (err != NO_ERROR) {
        return err;
    }

    return NO_ERROR;
}

status_t
IncidentReportArgs::readFromParcel(const Parcel* in)
{
    status_t err;

    int32_t all;
    err = in->readInt32(&all);
    if (err != NO_ERROR) {
        return err;
    }
    if (all != 0) {
        mAll = all;
    }

    mSections.clear();
    int32_t sectionCount;
    err = in->readInt32(&sectionCount);
    if (err != NO_ERROR) {
        return err;
    }
    for (int i=0; i<sectionCount; i++) {
        int32_t section;
        err = in->readInt32(&section);
        if (err != NO_ERROR) {
            return err;
        }

        mSections.insert(section);
    }

    int32_t headerCount;
    err = in->readInt32(&headerCount);
    if (err != NO_ERROR) {
        return err;
    }
    mHeaders.resize(headerCount);
    for (int i=0; i<headerCount; i++) {
        err = in->readByteVector(&mHeaders[i]);
        if (err != NO_ERROR) {
            return err;
        }
    }

    int32_t privacyPolicy;
    err = in->readInt32(&privacyPolicy);
    if (err != NO_ERROR) {
        return err;
    }
    mPrivacyPolicy = privacyPolicy;

    mReceiverPkg = String8(in->readString16()).string();
    mReceiverCls = String8(in->readString16()).string();

    int32_t gzip;
    err = in->readInt32(&gzip);
    if (err != NO_ERROR) {
        return err;
    }
    if (gzip != 0) {
        mGzip = gzip;
    }

    return OK;
}

void
IncidentReportArgs::setAll(bool all)
{
    mAll = all;
    if (all) {
        mSections.clear();
    }
}

void
IncidentReportArgs::setPrivacyPolicy(int privacyPolicy)
{
    mPrivacyPolicy = privacyPolicy;
}

void
IncidentReportArgs::addSection(int section)
{
    if (!mAll) {
        mSections.insert(section);
    }
}

void
IncidentReportArgs::setReceiverPkg(const string& pkg)
{
    mReceiverPkg = pkg;
}

void
IncidentReportArgs::setReceiverCls(const string& cls)
{
    mReceiverCls = cls;
}

void
IncidentReportArgs::addHeader(const vector<uint8_t>& headerProto)
{
    mHeaders.push_back(headerProto);
}

void
IncidentReportArgs::setGzip(bool gzip)
{
    mGzip = gzip;
}

bool
IncidentReportArgs::containsSection(int section, bool specific) const
{
    if (specific) {
        return mSections.find(section) != mSections.end();
    } else {
        return mAll || mSections.find(section) != mSections.end();
    }
}

void
IncidentReportArgs::merge(const IncidentReportArgs& that)
{
    for (const vector<uint8_t>& header: that.mHeaders) {
        mHeaders.push_back(header);
    }
    if (!mAll) {
        if (that.mAll) {
            mAll = true;
            mSections.clear();
        } else {
            for (set<int>::const_iterator it=that.mSections.begin();
                    it!=that.mSections.end(); it++) {
                mSections.insert(*it);
            }
        }
    }
}

}
}
