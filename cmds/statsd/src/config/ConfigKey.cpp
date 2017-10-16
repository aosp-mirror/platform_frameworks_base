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

#include <sstream>

namespace android {
namespace os {
namespace statsd {

using std::ostringstream;

ConfigKey::ConfigKey() {
}

ConfigKey::ConfigKey(const ConfigKey& that) : mName(that.mName), mUid(that.mUid) {
}

ConfigKey::ConfigKey(int uid, const string& name) : mName(name), mUid(uid) {
}

ConfigKey::~ConfigKey() {
}

string ConfigKey::ToString() const {
    ostringstream out;
    out << '(' << mUid << ',' << mName << ')';
    return out.str();
}

}  // namespace statsd
}  // namespace os
}  // namespace android
