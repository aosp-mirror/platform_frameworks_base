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
#include <utils/String8.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

using namespace android;

static const char* gProgName = "validatekeymaps";

enum FileType {
    FILETYPE_UNKNOWN,
    FILETYPE_KEYLAYOUT,
    FILETYPE_KEYCHARACTERMAP,
    FILETYPE_VIRTUALKEYDEFINITION,
    FILETYPE_INPUTDEVICECONFIGURATION,
};


static void usage() {
    fprintf(stderr, "Keymap Validation Tool\n\n");
    fprintf(stderr, "Usage:\n");
    fprintf(stderr,
        " %s [*.kl] [*.kcm] [*.idc] [virtualkeys.*] [...]\n"
        "   Validates the specified key layouts, key character maps, \n"
        "   input device configurations, or virtual key definitions.\n\n",
        gProgName);
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
    fprintf(stdout, "Validating file '%s'...\n", filename);

    FileType fileType = getFileType(filename);
    switch (fileType) {
    case FILETYPE_UNKNOWN:
        fprintf(stderr, "Supported file types: *.kl, *.kcm, virtualkeys.*\n\n");
        return false;

    case FILETYPE_KEYLAYOUT: {
        sp<KeyLayoutMap> map;
        status_t status = KeyLayoutMap::load(String8(filename), &map);
        if (status) {
            fprintf(stderr, "Error %d parsing key layout file.\n\n", status);
            return false;
        }
        break;
    }

    case FILETYPE_KEYCHARACTERMAP: {
        sp<KeyCharacterMap> map;
        status_t status = KeyCharacterMap::load(String8(filename),
                KeyCharacterMap::FORMAT_ANY, &map);
        if (status) {
            fprintf(stderr, "Error %d parsing key character map file.\n\n", status);
            return false;
        }
        break;
    }

    case FILETYPE_INPUTDEVICECONFIGURATION: {
        PropertyMap* map;
        status_t status = PropertyMap::load(String8(filename), &map);
        if (status) {
            fprintf(stderr, "Error %d parsing input device configuration file.\n\n", status);
            return false;
        }
        delete map;
        break;
    }

    case FILETYPE_VIRTUALKEYDEFINITION: {
        VirtualKeyMap* map;
        status_t status = VirtualKeyMap::load(String8(filename), &map);
        if (status) {
            fprintf(stderr, "Error %d parsing virtual key definition file.\n\n", status);
            return false;
        }
        delete map;
        break;
    }
    }

    fputs("No errors.\n\n", stdout);
    return true;
}

int main(int argc, const char** argv) {
    if (argc < 2) {
        usage();
        return 1;
    }

    int result = 0;
    for (int i = 1; i < argc; i++) {
        if (!validateFile(argv[i])) {
            result = 1;
        }
    }

    if (result) {
        fputs("Failed!\n", stderr);
    } else {
        fputs("Success.\n", stdout);
    }
    return result;
}
