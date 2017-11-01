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

#ifndef ANDROID_OS_DUMPSTATE_ARGS_H_
#define ANDROID_OS_DUMPSTATE_ARGS_H_

#include <binder/Parcel.h>
#include <binder/Parcelable.h>
#include <utils/String16.h>

#include <set>
#include <vector>

namespace android {
namespace os {

using namespace std;

class IncidentReportArgs : public Parcelable {
public:
    IncidentReportArgs();
    explicit IncidentReportArgs(const IncidentReportArgs& that);
    virtual ~IncidentReportArgs();

    virtual status_t writeToParcel(Parcel* out) const;
    virtual status_t readFromParcel(const Parcel* in);

    void setAll(bool all);
    void addSection(int section);
    void addHeader(const vector<int8_t>& header);

    inline bool all() const { return mAll; };
    bool containsSection(int section) const;

    inline const set<int>& sections() const { return mSections; }
    inline const vector<vector<int8_t>>& headers() const { return mHeaders; }

    void merge(const IncidentReportArgs& that);

private:
    set<int> mSections;
    vector<vector<int8_t>> mHeaders;
    bool mAll;
};

}
}

#endif // ANDROID_OS_DUMPSTATE_ARGS_H_
