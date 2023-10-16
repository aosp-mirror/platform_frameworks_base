/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <input/KeyCharacterMap.h>
#include <input/KeyLayoutMap.h>
#include <input/PropertyMap.h>
#include <input/VirtualKeyMap.h>

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

using namespace android;

static const char* PROG_NAME = "validatekeymaps";
static bool gQuiet = false;

/**
 * Return true if 'str' contains 'substr', ignoring case.
 */
static bool containsSubstringCaseInsensitive(std::string_view str, std::string_view substr) {
    auto it = std::search(str.begin(), str.end(), substr.begin(), substr.end(),
                          [](char left, char right) {
                              return std::tolower(left) == std::tolower(right);
                          });
    return it != str.end();
}

enum class FileType {
    UNKNOWN,
    KEY_LAYOUT,
    KEY_CHARACTER_MAP,
    VIRTUAL_KEY_DEFINITION,
    INPUT_DEVICE_CONFIGURATION,
};

static void log(const char* fmt, ...) {
    if (gQuiet) {
        return;
    }
    va_list args;
    va_start(args, fmt);
    vfprintf(stdout, fmt, args);
    va_end(args);
}

static void error(const char* fmt,  ...) {
    va_list args;
    va_start(args, fmt);
    vfprintf(stderr, fmt, args);
    va_end(args);
}

static void usage() {
    error("Keymap Validation Tool\n\n");
    error("Usage:\n");
    error(" %s [-q] [*.kl] [*.kcm] [*.idc] [virtualkeys.*] [...]\n"
          "   Validates the specified key layouts, key character maps, \n"
          "   input device configurations, or virtual key definitions.\n\n"
          "   -q Quiet; do not write anything to standard out.\n",
          PROG_NAME);
}

static FileType getFileType(const char* filename) {
    const char *extension = strrchr(filename, '.');
    if (extension) {
        if (strcmp(extension, ".kl") == 0) {
            return FileType::KEY_LAYOUT;
        }
        if (strcmp(extension, ".kcm") == 0) {
            return FileType::KEY_CHARACTER_MAP;
        }
        if (strcmp(extension, ".idc") == 0) {
            return FileType::INPUT_DEVICE_CONFIGURATION;
        }
    }

    if (strstr(filename, "virtualkeys.")) {
        return FileType::VIRTUAL_KEY_DEFINITION;
    }

    return FileType::UNKNOWN;
}

/**
 * Return true if the filename is allowed, false otherwise.
 */
static bool validateKeyLayoutFileName(const std::string& filename) {
    static const std::string kMicrosoftReason =
            "Microsoft's controllers are designed to work with Generic.kl. Please check with "
            "Microsoft prior to adding these layouts. See b/194334400";
    static const std::vector<std::pair<std::string, std::string>> kBannedDevices{
            std::make_pair("Vendor_0a5c_Product_8502",
                           "This vendorId/productId combination conflicts with 'SnakeByte "
                           "iDroid:con', 'BT23BK keyboard', and other keyboards. Instead, consider "
                           "matching these specific devices by name. See b/36976285, b/191720859"),
            std::make_pair("Vendor_045e_Product_0b05", kMicrosoftReason),
            std::make_pair("Vendor_045e_Product_0b20", kMicrosoftReason),
            std::make_pair("Vendor_045e_Product_0b21", kMicrosoftReason),
            std::make_pair("Vendor_045e_Product_0b22", kMicrosoftReason),
    };

    for (const auto& [filenameSubstr, reason] : kBannedDevices) {
        if (containsSubstringCaseInsensitive(filename, filenameSubstr)) {
            error("You are trying to add a key layout %s, which matches %s. ", filename.c_str(),
                  filenameSubstr.c_str());
            error("This would cause some devices to function incorrectly. ");
            error("%s. ", reason.c_str());
            return false;
        }
    }
    return true;
}

static bool validateFile(const char* filename) {
    log("Validating file '%s'...\n", filename);

    FileType fileType = getFileType(filename);
    switch (fileType) {
        case FileType::UNKNOWN:
            error("Supported file types: *.kl, *.kcm, virtualkeys.*\n\n");
            return false;

        case FileType::KEY_LAYOUT: {
            if (!validateKeyLayoutFileName(filename)) {
                return false;
            }
            base::Result<std::shared_ptr<KeyLayoutMap>> ret = KeyLayoutMap::load(filename);
            if (!ret.ok()) {
                if (ret.error().message() == "Missing kernel config") {
                    // It means the layout is valid, but won't be loaded on this device because
                    // this layout requires a certain kernel config.
                    return true;
                }
                error("Error %s parsing key layout file.\n\n", ret.error().message().c_str());
                return false;
            }
            break;
        }

        case FileType::KEY_CHARACTER_MAP: {
            base::Result<std::shared_ptr<KeyCharacterMap>> ret =
                    KeyCharacterMap::load(filename, KeyCharacterMap::Format::ANY);
            if (!ret.ok()) {
                error("Error %s parsing key character map file.\n\n",
                      ret.error().message().c_str());
                return false;
            }
            break;
        }

        case FileType::INPUT_DEVICE_CONFIGURATION: {
            android::base::Result<std::unique_ptr<PropertyMap>> propertyMap =
                    PropertyMap::load(String8(filename).c_str());
            if (!propertyMap.ok()) {
                error("Error parsing input device configuration file: %s.\n\n",
                      propertyMap.error().message().c_str());
                return false;
            }
            break;
        }

        case FileType::VIRTUAL_KEY_DEFINITION: {
            std::unique_ptr<VirtualKeyMap> map = VirtualKeyMap::load(filename);
            if (!map) {
                error("Error while parsing virtual key definition file.\n\n");
                return false;
            }
            break;
        }
    }

    return true;
}

int main(int argc, const char** argv) {
    if (argc < 2) {
        usage();
        return 1;
    }

    int result = 0;
    for (int i = 1; i < argc; i++) {
        if (i == 1 && !strcmp(argv[1], "-q")) {
            gQuiet = true;
            continue;
        }
        if (!validateFile(argv[i])) {
            result = 1;
        }
    }

    if (result) {
        error("Failed!\n");
    } else {
        log("Success.\n");
    }
    return result;
}
