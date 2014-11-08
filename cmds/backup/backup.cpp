/*
 * Copyright (C) 2009 The Android Open Source Project
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

#include <androidfw/BackupHelpers.h>
#include <utils/String8.h>

#include <fcntl.h>
#include <stdio.h>
#include <string.h>

using namespace android;

#include <unistd.h>

static int usage(int /* argc */, const char** argv)
{
    const char* p = argv[0];

    fprintf(stderr, "%s: Backs up your data.\n"
                    "\n"
                    "usage: %s\n"
                    "  Prints all of the data that can be backed up to stdout.\n"
                    "\n"
                    "usage: %s list FILE\n"
                    "  Lists the backup entities in the file.\n"
                    "\n"
                    "usage: %s print NAME FILE\n"
                    "  Prints the entity named NAME in FILE.\n",
                    p, p, p, p);
    return 1;
}

static int perform_full_backup()
{
    printf("this would have written all of your data to stdout\n");
    return 0;
}

static int perform_list(const char* filename)
{
    int err;
    int fd;

    fd = open(filename, O_RDONLY);
    if (fd == -1) {
        fprintf(stderr, "Error opening: %s\n", filename);
        return 1;
    }

    BackupDataReader reader(fd);
    bool done;
    int type;

    while (reader.ReadNextHeader(&done, &type) == 0) {
        if (done) {
            break;
        }
        switch (type) {
            case BACKUP_HEADER_ENTITY_V1:
            {
                String8 key;
                size_t dataSize;
                err = reader.ReadEntityHeader(&key, &dataSize);
                if (err == 0) {
                    printf("   entity: %s (%zu bytes)\n", key.string(), dataSize);
                } else {
                    printf("   Error reading entity header\n");
                }
                break;
            }
            default:
            {
                printf("Unknown chunk type: 0x%08x\n", type);
                break;
            }
        }
    }

    return 0;
}

static int perform_print(const char* entityname, const char* filename)
{
    printf("perform_print(%s, %s);", entityname, filename);
    return 0;
}

int main(int argc, const char** argv)
{
    if (argc <= 1) {
        return perform_full_backup();
    }
    if (argc == 3 && 0 == strcmp(argv[1], "list")) {
        return perform_list(argv[2]);
    }
    if (argc == 4 && 0 == strcmp(argv[1], "print")) {
        return perform_print(argv[2], argv[3]);
    }
    return usage(argc, argv);
}

