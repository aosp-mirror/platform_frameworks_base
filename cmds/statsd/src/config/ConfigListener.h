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

#include "config/ConfigKey.h"

#include <utils/RefBase.h>

namespace android {
namespace os {
namespace statsd {

using android::RefBase;

/**
 * Callback for different subsystems inside statsd to implement to find out
 * when a configuration has been added, updated or removed.
 */
class ConfigListener : public virtual RefBase {
public:
    ConfigListener();
    virtual ~ConfigListener();

    /**
     * A configuration was added or updated.
     */
    virtual void OnConfigUpdated(const int64_t timestampNs, const ConfigKey& key,
                                 const StatsdConfig& config) = 0;

    /**
     * A configuration was removed.
     */
    virtual void OnConfigRemoved(const ConfigKey& key) = 0;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
