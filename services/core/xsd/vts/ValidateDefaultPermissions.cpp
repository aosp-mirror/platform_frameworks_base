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

#include "utility/ValidateXml.h"

#include <dirent.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>

#include <string>

#include <android-base/strings.h>

static std::vector<std::string> get_files_in_dirs(const char* dir_path) {
    std::vector<std::string> files;
    std::unique_ptr<DIR, decltype(&closedir)> d(opendir(dir_path), closedir);

    if (d == nullptr) {
        return files;
    }

    struct dirent* de;
    while ((de = readdir(d.get()))) {
        if (de->d_type != DT_REG) {
            continue;
        }
        if (android::base::EndsWith(de->d_name, ".xml")) {
            files.push_back(de->d_name);
        }
    }
    return files;
}

TEST(CheckConfig, defaultPermissions) {
    RecordProperty("description",
                   "Verify that the default-permissions file "
                   "is valid according to the schema");

    std::vector<const char*> locations = {"/vendor/etc/default-permissions",
                                          "/odm/etc/default-permissions"};

    for (const char* dir_path : locations) {
        std::vector<std::string> files = get_files_in_dirs(dir_path);
        for (auto& file_name : files) {
            EXPECT_ONE_VALID_XML_MULTIPLE_LOCATIONS(file_name.c_str(), {dir_path},
                                                    "/data/local/tmp/default-permissions.xsd");
        }
    }
}
