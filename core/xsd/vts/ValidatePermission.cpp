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

#include <dirent.h>
#include <regex>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <string>

#include "android-base/logging.h"
#include "utility/ValidateXml.h"

static void get_files_in_dirs(const char* dir_path, std::vector<std::string>& files) {
    DIR* d;
    struct dirent* de;

    d = opendir(dir_path);
    if (d == nullptr) {
        return;
    }

    while ((de = readdir(d))) {
        if (de->d_type != DT_REG) {
            continue;
        }
        if (std::regex_match(de->d_name, std::regex("(.*)(.xml)"))) {
            files.push_back(de->d_name);
        }
    }
    closedir(d);
}

TEST(CheckConfig, permission) {
    RecordProperty("description",
                   "Verify that the permission file "
                   "is valid according to the schema");

    const char* location = "/vendor/etc/permissions";

    std::vector<std::string> files;
    get_files_in_dirs(location, files);

    for (std::string file_name : files) {
        EXPECT_ONE_VALID_XML_MULTIPLE_LOCATIONS(file_name.c_str(), {location},
                                                "/data/local/tmp/permission.xsd");
    }
}
