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

#ifndef ANDROID_OS_INCIDENT_REPORT_ARGS_H
#define ANDROID_OS_INCIDENT_REPORT_ARGS_H

#include <binder/IServiceManager.h>
#include <binder/Parcel.h>
#include <binder/Parcelable.h>
#include <utils/String16.h>

#include <set>
#include <vector>

namespace android {
namespace os {

using namespace std;

// DESTINATION enum value, sync with frameworks/base/core/proto/android/privacy.proto,
// incident/incident_report.h and IncidentReportArgs.java
const uint8_t PRIVACY_POLICY_LOCAL = 0;
const uint8_t PRIVACY_POLICY_EXPLICIT = 100;
const uint8_t PRIVACY_POLICY_AUTOMATIC = 200;
const uint8_t PRIVACY_POLICY_UNSET = 255;


class IncidentReportArgs : public Parcelable {
public:
    IncidentReportArgs();
    IncidentReportArgs(const IncidentReportArgs& that);
    virtual ~IncidentReportArgs();

    virtual status_t writeToParcel(Parcel* out) const;
    virtual status_t readFromParcel(const Parcel* in);

    void setAll(bool all);
    void setPrivacyPolicy(int privacyPolicy);
    void addSection(int section);
    void setReceiverPkg(const string& pkg);
    void setReceiverCls(const string& cls);
    void addHeader(const vector<uint8_t>& headerProto);
    void setGzip(bool gzip);

    inline bool all() const { return mAll; }
    bool containsSection(int section, bool specific) const;
    inline int getPrivacyPolicy() const { return mPrivacyPolicy; }
    inline const set<int>& sections() const { return mSections; }
    inline const string& receiverPkg() const { return mReceiverPkg; }
    inline const string& receiverCls() const { return mReceiverCls; }
    inline const vector<vector<uint8_t>>& headers() const { return mHeaders; }
    inline bool gzip() const {return mGzip; }

    void merge(const IncidentReportArgs& that);

private:
    set<int> mSections;
    vector<vector<uint8_t>> mHeaders;
    bool mAll;
    int mPrivacyPolicy;
    string mReceiverPkg;
    string mReceiverCls;
    bool mGzip;
};

}
}

#endif // ANDROID_OS_INCIDENT_REPORT_ARGS_H
