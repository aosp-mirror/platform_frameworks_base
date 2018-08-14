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

#include <cutils/log.h>

namespace android {
namespace os {

IncidentReportArgs::IncidentReportArgs()
    :mSections(),
     mAll(false),
     mDest(-1)
{
}

IncidentReportArgs::IncidentReportArgs(const IncidentReportArgs& that)
    :mSections(that.mSections),
     mHeaders(that.mHeaders),
     mAll(that.mAll),
     mDest(that.mDest)
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

    err = out->writeInt32(mDest);
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

    int32_t dest;
    err = in->readInt32(&dest);
    if (err != NO_ERROR) {
        return err;
    }
    mDest = dest;

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
IncidentReportArgs::setDest(int dest)
{
    mDest = dest;
}

void
IncidentReportArgs::addSection(int section)
{
    if (!mAll) {
        mSections.insert(section);
    }
}

void
IncidentReportArgs::addHeader(const IncidentHeaderProto& headerProto)
{
    vector<uint8_t> header;
    auto serialized = headerProto.SerializeAsString();
    if (serialized.empty()) return;
    for (auto it = serialized.begin(); it != serialized.end(); it++) {
        header.push_back((uint8_t)*it);
    }
    mHeaders.push_back(header);
}

bool
IncidentReportArgs::containsSection(int section) const
{
     return mAll || mSections.find(section) != mSections.end();
}

void
IncidentReportArgs::merge(const IncidentReportArgs& that)
{
    if (mAll) {
        return;
    } else if (that.mAll) {
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
