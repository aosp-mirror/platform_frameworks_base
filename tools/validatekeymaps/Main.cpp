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
#include <input/VirtualKeyMap.h>
#include <utils/PropertyMap.h>

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

using namespace android;

static const char* kProgName = "validatekeymaps";
static bool gQuiet = false;

enum FileType {
    FILETYPE_UNKNOWN,
    FILETYPE_KEYLAYOUT,
    FILETYPE_KEYCHARACTERMAP,
    FILETYPE_VIRTUALKEYDEFINITION,
    FILETYPE_INPUTDEVICECONFIGURATION,
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
    error(
        " %s [-q] [*.kl] [*.kcm] [*.idc] [virtualkeys.*] [...]\n"
        "   Validates the specified key layouts, key character maps, \n"
        "   input device configurations, or virtual key definitions.\n\n"
        "   -q Quiet; do not write anything to standard out.\n",
        kProgName);
}

static FileType getFileType(const char* filename) {
    const char *extension = strrchr(filename, '.');
    if (extension) {
        if (strcmp(extension, ".kl") == 0) {
            return FILETYPE_KEYLAYOUT;
        }
        if (strcmp(extension, ".kcm") == 0) {
            return FILETYPE_KEYCHARACTERMAP;
        }
        if (strcmp(extension, ".idc") == 0) {
            return FILETYPE_INPUTDEVICECONFIGURATION;
        }
    }

    if (strstr(filename, "virtualkeys.")) {
        return FILETYPE_VIRTUALKEYDEFINITION;
    }

    return FILETYPE_UNKNOWN;
}

static bool validateFile(const char* filename) {
    log("Validating file '%s'...\n", filename);

    FileType fileType = getFileType(filename);
    switch (fileType) {
    case FILETYPE_UNKNOWN:
        error("Supported file types: *.kl, *.kcm, virtualkeys.*\n\n");
        return false;

    case FILETYPE_KEYLAYOUT: {
        sp<KeyLayoutMap> map;
        status_t status = KeyLayoutMap::load(filename, &map);
        if (status) {
            error("Error %d parsing key layout file.\n\n", status);
            return false;
        }
        break;
    }

    case FILETYPE_KEYCHARACTERMAP: {
        sp<KeyCharacterMap> map;
        status_t status = KeyCharacterMap::load(filename,
                KeyCharacterMap::FORMAT_ANY, &map);
        if (status) {
            error("Error %d parsing key character map file.\n\n", status);
            return false;
        }
        break;
    }

    case FILETYPE_INPUTDEVICECONFIGURATION: {
        PropertyMap* map;
        status_t status = PropertyMap::load(String8(filename), &map);
        if (status) {
            error("Error %d parsing input device configuration file.\n\n", status);
            return false;
        }
        delete map;
        break;
    }

    case FILETYPE_VIRTUALKEYDEFINITION: {
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
