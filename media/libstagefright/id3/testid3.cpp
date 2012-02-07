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

#include "../include/ID3.h"

#include <sys/stat.h>

#include <ctype.h>
#include <dirent.h>

#include <binder/ProcessState.h>
#include <media/stagefright/FileSource.h>
#include <media/stagefright/foundation/ADebug.h>

#define MAXPATHLEN 256

using namespace android;

static void hexdump(const void *_data, size_t size) {
    const uint8_t *data = (const uint8_t *)_data;
    size_t offset = 0;
    while (offset < size) {
        printf("0x%04x  ", offset);

        size_t n = size - offset;
        if (n > 16) {
            n = 16;
        }

        for (size_t i = 0; i < 16; ++i) {
            if (i == 8) {
                printf(" ");
            }

            if (offset + i < size) {
                printf("%02x ", data[offset + i]);
            } else {
                printf("   ");
            }
        }

        printf(" ");

        for (size_t i = 0; i < n; ++i) {
            if (isprint(data[offset + i])) {
                printf("%c", data[offset + i]);
            } else {
                printf(".");
            }
        }

        printf("\n");

        offset += 16;
    }
}

void scanFile(const char *path) {
    sp<FileSource> file = new FileSource(path);
    CHECK_EQ(file->initCheck(), (status_t)OK);

    ID3 tag(file);
    if (!tag.isValid()) {
        printf("FAIL %s\n", path);
    } else {
        printf("SUCCESS %s\n", path);

        ID3::Iterator it(tag, NULL);
        while (!it.done()) {
            String8 id;
            it.getID(&id);

            CHECK(id.length() > 0);
            if (id[0] == 'T') {
                String8 text;
                it.getString(&text);

                printf("  found text frame '%s': %s\n", id.string(), text.string());
            } else {
                printf("  found frame '%s'.\n", id.string());
            }

            it.next();
        }

        size_t dataSize;
        String8 mime;
        const void *data = tag.getAlbumArt(&dataSize, &mime);

        if (data) {
            printf("found album art: size=%d mime='%s'\n", dataSize,
                   mime.string());

            hexdump(data, dataSize > 128 ? 128 : dataSize);
        }
    }
}

void scan(const char *path) {
    struct stat st;
    if (stat(path, &st) == 0 && S_ISREG(st.st_mode)) {
        scanFile(path);
        return;
    }

    DIR *dir = opendir(path);

    if (dir == NULL) {
        return;
    }

    rewinddir(dir);

    struct dirent *ent;
    while ((ent = readdir(dir)) != NULL) {
        if (!strcmp(".", ent->d_name) || !strcmp("..", ent->d_name)) {
            continue;
        }

        char newPath[MAXPATHLEN];
        strcpy(newPath, path);
        strcat(newPath, "/");
        strcat(newPath, ent->d_name);

        if (ent->d_type == DT_DIR) {
            scan(newPath);
        } else if (ent->d_type == DT_REG) {
            size_t len = strlen(ent->d_name);

            if (len >= 4
                && !strcasecmp(ent->d_name + len - 4, ".mp3")) {
                scanFile(newPath);
            }
        }
    }

    closedir(dir);
    dir = NULL;
}

int main(int argc, char **argv) {
    android::ProcessState::self()->startThreadPool();

    DataSource::RegisterDefaultSniffers();

    for (int i = 1; i < argc; ++i) {
        scan(argv[i]);
    }

    return 0;
}
