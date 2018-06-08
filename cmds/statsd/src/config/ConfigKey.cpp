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

#include "config/ConfigKey.h"

namespace android {
namespace os {
namespace statsd {

ConfigKey::ConfigKey() {
}

ConfigKey::ConfigKey(const ConfigKey& that) : mId(that.mId), mUid(that.mUid) {
}

ConfigKey::ConfigKey(int uid, const int64_t& id) : mId(id), mUid(uid) {
}

ConfigKey::~ConfigKey() {
}

string ConfigKey::ToString() const {
    string s;
    s += "(" + std::to_string(mUid) + " " + std::to_string(mId) + ")";
    return s;
}


int64_t StrToInt64(const string& str) {
    char* endp;
    int64_t value;
    value = strtoll(str.c_str(), &endp, 0);
    if (endp == str.c_str() || *endp != '\0') {
        value = 0;
    }
    return value;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
