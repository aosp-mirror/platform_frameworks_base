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

#ifndef STATSD_PACKAGE_INFO_LISTENER_H
#define STATSD_PACKAGE_INFO_LISTENER_H

#include <string>

namespace android {
namespace os {
namespace statsd {

class PackageInfoListener : public virtual android::RefBase {
public:
    // Uid map will notify this listener that the app with apk name and uid has been upgraded to
    // the specified version.
    virtual void notifyAppUpgrade(const int64_t& eventTimeNs, const std::string& apk,
                                  const int uid, const int64_t version) = 0;

    // Notify interested listeners that the given apk and uid combination no longer exits.
    virtual void notifyAppRemoved(const int64_t& eventTimeNs, const std::string& apk,
                                  const int uid) = 0;

    // Notify the listener that the UidMap snapshot is available.
    virtual void onUidMapReceived(const int64_t& eventTimeNs) = 0;
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // STATSD_PACKAGE_INFO_LISTENER_H
