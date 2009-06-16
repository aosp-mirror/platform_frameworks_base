/*
** Copyright 2008, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#include "keystore.h"

static DIR *open_keystore(const char *dir)
{
    DIR *d;
    if ((d = opendir(dir)) == NULL) {
        if (mkdir(dir, 0770) < 0) {
            LOGE("cannot create dir '%s': %s\n", dir, strerror(errno));
            unlink(dir);
            return NULL;
        }
        d = open_keystore(dir);
    }
    return d;
}

static int list_files(const char *dir, char reply[REPLY_MAX]) {
    struct dirent *de;
    DIR *d;

    if ((d = open_keystore(dir)) == NULL) {
        return -1;
    }
    reply[0]=0;
    while ((de = readdir(d))) {
        if (de->d_type != DT_REG) continue;
        if (reply[0] != 0) strlcat(reply, " ", REPLY_MAX);
        if (strlcat(reply, de->d_name, REPLY_MAX) >= REPLY_MAX) {
            LOGE("reply is too long(too many files under '%s'\n", dir);
            return -1;
        }
    }
    closedir(d);
    return 0;
}

static int copy_keyfile(const char *keystore, const char *srcfile) {
    int srcfd, dstfd;
    int length;
    char buf[2048];
    char dstfile[KEYNAME_LENGTH];
    const char *filename = strrchr(srcfile, '/');

    strlcpy(dstfile, keystore, KEYNAME_LENGTH);
    strlcat(dstfile, "/", KEYNAME_LENGTH);
    if (strlcat(dstfile, filename ? filename + 1 : srcfile,
                KEYNAME_LENGTH) >= KEYNAME_LENGTH) {
        LOGE("keyname is too long '%s'\n", srcfile);
        return -1;
    }

    if ((srcfd = open(srcfile, O_RDONLY)) == -1) {
        LOGE("Cannot open the original file '%s'\n", srcfile);
        return -1;
    }
    if ((dstfd = open(dstfile, O_CREAT|O_RDWR)) == -1) {
        LOGE("Cannot open the destination file '%s'\n", dstfile);
        return -1;
    }
    while((length = read(srcfd, buf, 2048)) > 0) {
        write(dstfd, buf, length);
    }
    close(srcfd);
    close(dstfd);
    chmod(dstfile, 0440);
    return 0;
}

static int install_key(const char *dir, const char *keyfile)
{
    struct dirent *de;
    DIR *d;

    if ((d = open_keystore(dir)) == NULL) {
        return -1;
    }
    return copy_keyfile(dir, keyfile);
}

static int remove_key(const char *dir, const char *keyfile)
{
    char dstfile[KEYNAME_LENGTH];

    strlcpy(dstfile, dir, KEYNAME_LENGTH);
    strlcat(dstfile, "/", KEYNAME_LENGTH);
    if (strlcat(dstfile, keyfile, KEYNAME_LENGTH) >= KEYNAME_LENGTH) {
        LOGE("keyname is too long '%s'\n", keyfile);
        return -1;
    }
    if (unlink(dstfile)) {
        LOGE("cannot delete '%s': %s\n", dstfile, strerror(errno));
        return -1;
    }
    return 0;
}

int list_certs(char reply[REPLY_MAX])
{
    return list_files(CERTS_DIR, reply);
}

int list_userkeys(char reply[REPLY_MAX])
{
    return list_files(USERKEYS_DIR, reply);
}

int install_cert(const char *certfile)
{
    return install_key(CERTS_DIR, certfile);
}

int install_userkey(const char *keyfile)
{
    return install_key(USERKEYS_DIR, keyfile);
}

int remove_cert(const char *certfile)
{
    return remove_key(CERTS_DIR, certfile);
}

int remove_userkey(const char *keyfile)
{
    return remove_key(USERKEYS_DIR, keyfile);
}
