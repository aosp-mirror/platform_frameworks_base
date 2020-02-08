/*
 * Copyright (C) 2019 The Android Open Source Project
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

#define LOG_TAG "derive_sdk"

#include <algorithm>
#include <dirent.h>
#include <iostream>
#include <sys/stat.h>
#include <vector>

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/properties.h>

#include "frameworks/base/apex/sdkextensions/derive_sdk/sdk.pb.h"

using com::android::sdkext::proto::SdkVersion;

int main(int, char**) {
    std::unique_ptr<DIR, decltype(&closedir)> apex(opendir("/apex"), closedir);
    if (!apex) {
        LOG(ERROR) << "Could not read /apex";
        return EXIT_FAILURE;
    }
    struct dirent* de;
    std::vector<std::string> paths;
    while ((de = readdir(apex.get()))) {
        std::string name = de->d_name;
        if (name[0] == '.' || name.find('@') != std::string::npos) {
            // Skip <name>@<ver> dirs, as they are bind-mounted to <name>
            continue;
        }
        std::string path = "/apex/" + name + "/etc/sdkinfo.binarypb";
        struct stat statbuf;
        if (stat(path.c_str(), &statbuf) == 0) {
            paths.push_back(path);
        }
    }

    std::vector<int> versions;
    for (const auto& path : paths) {
        std::string contents;
        if (!android::base::ReadFileToString(path, &contents, true)) {
            LOG(ERROR) << "failed to read " << path;
            continue;
        }
        SdkVersion sdk_version;
        if (!sdk_version.ParseFromString(contents)) {
            LOG(ERROR) << "failed to parse " << path;
            continue;
        }
        LOG(INFO) << "Read version " << sdk_version.version() << " from " << path;
        versions.push_back(sdk_version.version());
    }
    auto itr = std::min_element(versions.begin(), versions.end());
    std::string prop_value = itr == versions.end() ? "0" : std::to_string(*itr);

    if (!android::base::SetProperty("build.version.extensions.r", prop_value)) {
        LOG(ERROR) << "failed to set sdk_info prop";
        return EXIT_FAILURE;
    }

    LOG(INFO) << "R extension version is " << prop_value;
    return EXIT_SUCCESS;
}
