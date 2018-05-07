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

#ifndef _BOOTANIMATION_BOOT_PARAMETERS_H_
#define _BOOTANIMATION_BOOT_PARAMETERS_H_

#include <list>
#include <string>
#include <vector>

#include <boot_action/boot_action.h>  // libandroidthings native API.
#include <json/json.h>

namespace android {

// Provides access to the parameters set by DeviceManager.reboot().
class BootParameters {
public:
    // Constructor loads the parameters for this boot and swaps the param files
    // to clear the parameters for next boot.
    BootParameters();

    // Returns whether or not this is a silent boot.
    bool isSilentBoot() const { return mIsSilentBoot; }

    // Returns the additional boot parameters that were set on reboot.
    const std::vector<ABootActionParameter>& getParameters() const { return mParameters; }

    // Exposed for testing. Updates the parameters with new JSON values.
    void loadParameters(const std::string& raw_json);
private:
    void loadParameters();

    void parseBootParameters();

    bool mIsSilentBoot = false;

    std::vector<ABootActionParameter> mParameters;

    // Store parsed JSON because mParameters makes a shallow copy.
    Json::Value mJson;

    // Store parameter keys because mParameters makes a shallow copy.
    Json::Value::Members mKeys;
};

}  // namespace android


#endif  // _BOOTANIMATION_BOOT_PARAMETERS_H_
