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

#include <unistd.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>

#if HAVE_READLINE
#include <readline/readline.h>
#include <readline/history.h>
#endif

#include "MtpClient.h"
#include "MtpDevice.h"
#include "MtpObjectInfo.h"

#include "MtpFile.h"

#define PROMPT  "mtp> "

using namespace android;

static MtpClient* sClient = NULL;

// current working directory information for interactive shell
static MtpFile* sCurrentDirectory = NULL;

static MtpFile* parse_path(char* path) {
    return MtpFile::parsePath(sCurrentDirectory, path);
}

class MyClient : public MtpClient {
private:
    virtual void deviceAdded(MtpDevice *device) {
    }

    virtual void deviceRemoved(MtpDevice *device) {
    }

public:
};

static void init() {
    sClient = new MyClient;
    sClient->start();
    MtpFile::init(sClient);
}

static int set_cwd(int argc, char* argv[]) {
    if (argc != 1) {
        fprintf(stderr, "cd should have one argument\n");
        return -1;
    }
    if (!strcmp(argv[0], "/")) {
        delete sCurrentDirectory;
        sCurrentDirectory = NULL;
    }
    else {
        MtpFile* file = parse_path(argv[0]);
        if (file) {
            delete sCurrentDirectory;
            sCurrentDirectory = file;
        } else {
            fprintf(stderr, "could not find %s\n", argv[0]);
            return -1;
        }
    }
    return 0;
}

static void list_devices() {
    // TODO - need to make sure the list will not change while iterating
    MtpDeviceList& devices = sClient->getDeviceList();
    for (int i = 0; i < devices.size(); i++) {
        MtpDevice* device = devices[i];
        MtpFile* file = new MtpFile(device);
        file->print();
        delete file;
    }
}

static int list(int argc, char* argv[]) {
    if (argc == 0) {
        // list cwd
        if (sCurrentDirectory) {
            sCurrentDirectory->list();
        } else {
            list_devices();
        }
    }

    for (int i = 0; i < argc; i++) {
        char* path = argv[i];
        if (!strcmp(path, "/")) {
            list_devices();
        } else {
            MtpFile* file = parse_path(path);
            if (!file) {
                fprintf(stderr, "could not find %s\n", path);
                return -1;
            }
            file->list();
        }
    }

    return 0;
}

static int get_file(int argc, char* argv[]) {
    int ret = -1;
    int srcFD = -1;
    int destFD = -1;
    MtpFile* srcFile = NULL;
    MtpObjectInfo* info = NULL;
    char* dest;

    if (argc < 1) {
        fprintf(stderr, "not enough arguments\n");
        return -1;
    } else if (argc > 2) {
        fprintf(stderr, "too many arguments\n");
        return -1;
    }

    // find source object
    char* src = argv[0];
    srcFile = parse_path(src);
    if (!srcFile) {
        fprintf(stderr, "could not find %s\n", src);
        return -1;
    }
    info = srcFile->getObjectInfo();
    if (!info) {
        fprintf(stderr, "could not find object info for %s\n", src);
        goto fail;
    }
    if (info->mFormat == MTP_FORMAT_ASSOCIATION) {
        fprintf(stderr, "copying directories not implemented yet\n");
        goto fail;
    }

    dest = (argc > 1 ? argv[1] : info->mName);
    destFD = open(dest, O_WRONLY | O_CREAT | O_TRUNC, S_IRUSR | S_IWUSR | S_IRGRP | S_IROTH);
    if (destFD < 0) {
        fprintf(stderr, "could not create %s\n", dest);
        goto fail;
    }
    srcFD = srcFile->getDevice()->readObject(info->mHandle, info->mCompressedSize);
    if (srcFD < 0)
        goto fail;

    char buffer[65536];
    while (1) {
        int count = read(srcFD, buffer, sizeof(buffer));
        if (count <= 0)
            break;
        write(destFD, buffer, count);
    }
    // FIXME - error checking and reporting
    ret = 0;

fail:
    delete srcFile;
    delete info;
    if (srcFD >= 0)
        close(srcFD);
    if (destFD >= 0)
        close(destFD);
    return ret;
}

static int put_file(int argc, char* argv[]) {
    int ret = -1;
    int srcFD = -1;
    MtpFile* destFile = NULL;
    MtpObjectInfo* srcInfo = NULL;
    MtpObjectInfo* destInfo = NULL;
    MtpObjectHandle handle;
    struct stat statbuf;
    const char* lastSlash;

    if (argc < 1) {
        fprintf(stderr, "not enough arguments\n");
        return -1;
    } else if (argc > 2) {
        fprintf(stderr, "too many arguments\n");
        return -1;
    }
    const char* src = argv[0];
    srcFD = open(src, O_RDONLY);
    if (srcFD < 0) {
        fprintf(stderr, "could not open %s\n", src);
        goto fail;
    }
    if (argc == 2) {
         char* dest = argv[1];
        destFile = parse_path(dest);
        if (!destFile) {
            fprintf(stderr, "could not find %s\n", dest);
            goto fail;
        }
    } else {
        if (!sCurrentDirectory) {
            fprintf(stderr, "current working directory not set\n");
            goto fail;
        }
        destFile = new MtpFile(sCurrentDirectory);
    }

    destInfo = destFile->getObjectInfo();
    if (!destInfo) {
        fprintf(stderr, "could not find object info destination directory\n");
        goto fail;
    }
    if (destInfo->mFormat != MTP_FORMAT_ASSOCIATION) {
        fprintf(stderr, "destination not a directory\n");
        goto fail;
    }

    if (fstat(srcFD, &statbuf))
        goto fail;

    srcInfo = new MtpObjectInfo(0);
    srcInfo->mStorageID = destInfo->mStorageID;
    srcInfo->mFormat = MTP_FORMAT_EXIF_JPEG;  // FIXME
    srcInfo->mCompressedSize = statbuf.st_size;
    srcInfo->mParent = destInfo->mHandle;
    lastSlash = strrchr(src, '/');
    srcInfo->mName = strdup(lastSlash ? lastSlash + 1 : src);
    srcInfo->mDateModified = statbuf.st_mtime;
    handle = destFile->getDevice()->sendObjectInfo(srcInfo);
    if (handle <= 0) {
        printf("sendObjectInfo returned %04X\n", handle);
        goto fail;
    }
    if (destFile->getDevice()->sendObject(srcInfo, srcFD))
        ret = 0;

fail:
    delete destFile;
    delete srcInfo;
    delete destInfo;
    if (srcFD >= 0)
        close(srcFD);
    printf("returning %d\n", ret);
    return ret;
}

typedef int (* command_func)(int argc, char* argv[]);

struct command_table_entry {
    const char* name;
    command_func func;
};

const command_table_entry command_list[] = {
    {   "cd",       set_cwd         },
    {   "ls",       list            },
    {   "get",      get_file        },
    {   "put",      put_file        },
    {   NULL,       NULL            },
};


static int do_command(int argc, char* argv[]) {
    const command_table_entry* command = command_list;
    const char* name = *argv++;
    argc--;

    while (command->name) {
        if (!strcmp(command->name, name))
            return command->func(argc, argv);
        else
            command++;
    }
    fprintf(stderr, "unknown command %s\n", name);
    return -1;
}

static int shell() {
    int argc;
    int result = 0;
#define MAX_ARGS    100
    char* argv[MAX_ARGS];

#if HAVE_READLINE
    using_history();
#endif

    while (1) {
#if HAVE_READLINE
        char* line = readline(PROMPT);
        if (!line) {
            printf("\n");
            exit(0);
        }
#else
        char    buffer[1000];
        printf("%s", PROMPT);
        char* line = NULL;
        size_t length = 0;

        buffer[0] = 0;
        fgets(buffer, sizeof(buffer), stdin);
        int count = strlen(buffer);
        if (count > 0 && buffer[0] == EOF) {
            printf("\n");
            exit(0);
        }
        if (count > 0 && line[count - 1] == '\n')
            line[count - 1] == 0;
#endif
        char* tok = strtok(line, " \t\n\r");
        if (!tok)
            continue;
        if (!strcmp(tok, "quit") || !strcmp(tok, "exit")) {
            exit(0);
        }
#if HAVE_READLINE
        add_history(line);
#endif
        argc = 0;
        while (tok) {
            if (argc + 1 == MAX_ARGS) {
                fprintf(stderr, "too many arguments\n");
                result = -1;
                goto bottom_of_loop;
            }

            argv[argc++] = strdup(tok);
            tok = strtok(NULL, " \t\n\r");
        }

        result = do_command(argc, argv);

bottom_of_loop:
        for (int i = 0; i < argc; i++)
            free(argv[i]);
        free(line);
    }

    return result;
}

int main(int argc, char* argv[]) {
    init();

    if (argc == 1)
        return shell();
    else
        return do_command(argc - 1, argv + 1);
}
