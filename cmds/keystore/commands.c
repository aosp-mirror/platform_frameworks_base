/*
** Copyright 2009, The Android Open Source Project
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

static int list_files(const char *dir, char reply[REPLY_MAX])
{
    struct dirent *de;
    DIR *d;

    if ((d = open_keystore(dir)) == NULL) {
        return -1;
    }
    reply[0]=0;
    while ((de = readdir(d))) {
        if (de->d_type != DT_DIR) continue;
        if ((strcmp(DOT, de->d_name) == 0) ||
                (strcmp(DOTDOT, de->d_name) == 0)) continue;
        if (reply[0] != 0) strlcat(reply, " ", REPLY_MAX);
        if (strlcat(reply, de->d_name, REPLY_MAX) >= REPLY_MAX) {
            LOGE("reply is too long(too many files under '%s'\n", dir);
            return -1;
        }
    }
    closedir(d);
    return 0;
}

static int copy_keyfile(const char *src, int src_type, const char *dstfile) {
    int srcfd = -1, dstfd;
    char buf[REPLY_MAX];

    if ((src_type == IS_FILE) && (srcfd = open(src, O_RDONLY)) == -1) {
        LOGE("Cannot open the original file '%s'\n", src);
        return -1;
    }
    if ((dstfd = open(dstfile, O_CREAT|O_RDWR)) == -1) {
        LOGE("Cannot open the destination file '%s'\n", dstfile);
        return -1;
    }
    if (src_type == IS_FILE) {
        int length;
        while((length = read(srcfd, buf, REPLY_MAX)) > 0) {
            write(dstfd, buf, length);
        }
    } else {
        write(dstfd, src, strlen(src));
    }
    close(srcfd);
    close(dstfd);
    chmod(dstfile, 0440);
    return 0;
}

static int install_key(const char *path, const char *certname, const char *src,
        int src_is_file, char *dstfile)
{
    struct dirent *de;
    char fullpath[KEYNAME_LENGTH];
    DIR *d;

    if (snprintf(fullpath, sizeof(fullpath), "%s/%s/", path, certname)
            >= KEYNAME_LENGTH) {
        LOGE("cert name '%s' is too long.\n", certname);
        return -1;
    }

    if ((d = open_keystore(fullpath)) == NULL) {
        LOGE("Can not open the keystore '%s'\n", fullpath);
        return -1;
    }
    closedir(d);
    if (strlcat(fullpath, dstfile, KEYNAME_LENGTH) >= KEYNAME_LENGTH) {
        LOGE("cert name '%s' is too long.\n", certname);
        return -1;
    }
    return copy_keyfile(src, src_is_file, fullpath);
}

static int get_key(const char *path, const char *keyname, const char *file,
        char reply[REPLY_MAX])
{
    struct dirent *de;
    char filename[KEYNAME_LENGTH];
    int fd;

    if (snprintf(filename, sizeof(filename), "%s/%s/%s", path, keyname, file)
            >= KEYNAME_LENGTH) {
        LOGE("cert name '%s' is too long.\n", keyname);
        return -1;
    }

    if ((fd = open(filename, O_RDONLY)) == -1) {
        return -1;
    }
    close(fd);
    strlcpy(reply, filename, REPLY_MAX);
    return 0;
}

static int remove_key(const char *dir, const char *key)
{
    char dstfile[KEYNAME_LENGTH];
    char *keyfile[4] = { USER_KEY, USER_P12_CERT, USER_CERTIFICATE,
            CA_CERTIFICATE };
    int i, count = 0;

    for ( i = 0 ; i < 4 ; i++) {
        if (snprintf(dstfile, KEYNAME_LENGTH, "%s/%s/%s", dir, key, keyfile[i])
                >= KEYNAME_LENGTH) {
            LOGE("keyname is too long '%s'\n", key);
            return -1;
        }
        if (unlink(dstfile) == 0) count++;
    }

    if (count == 0) {
        LOGE("can not clean up '%s' keys or not exist\n", key);
        return -1;
    }

    snprintf(dstfile, KEYNAME_LENGTH, "%s/%s", dir, key);
    if (rmdir(dstfile)) {
        LOGE("can not clean up '%s' directory\n", key);
        return -1;
    }
    return 0;
}

int list_user_certs(char reply[REPLY_MAX])
{
    return list_files(CERTS_DIR, reply);
}

int list_ca_certs(char reply[REPLY_MAX])
{
    return list_files(CACERTS_DIR, reply);
}

int install_user_cert(const char *keyname, const char *cert, const char *key)
{
    if (install_key(CERTS_DIR, keyname, cert, IS_FILE, USER_CERTIFICATE) == 0) {
        return install_key(CERTS_DIR, keyname, key, IS_FILE, USER_KEY);
    }
    return -1;
}

int install_ca_cert(const char *keyname, const char *certfile)
{
    return install_key(CACERTS_DIR, keyname, certfile, IS_FILE, CA_CERTIFICATE);
}

int install_p12_cert(const char *keyname, const char *certfile)
{
    return install_key(CERTS_DIR, keyname, certfile, IS_FILE, USER_P12_CERT);
}

int add_ca_cert(const char *keyname, const char *certificate)
{
    return install_key(CACERTS_DIR, keyname, certificate, IS_CONTENT,
            CA_CERTIFICATE);
}

int add_user_cert(const char *keyname, const char *certificate)
{
    return install_key(CERTS_DIR, keyname, certificate, IS_CONTENT,
            USER_CERTIFICATE);
}

int add_user_key(const char *keyname, const char *key)
{
    return install_key(CERTS_DIR, keyname, key, IS_CONTENT, USER_KEY);
}

int get_ca_cert(const char *keyname, char reply[REPLY_MAX])
{
    return get_key(CACERTS_DIR, keyname, CA_CERTIFICATE, reply);
}

int get_user_cert(const char *keyname, char reply[REPLY_MAX])
{
    return get_key(CERTS_DIR, keyname, USER_CERTIFICATE, reply);
}

int get_user_key(const char *keyname, char reply[REPLY_MAX])
{
    if(get_key(CERTS_DIR, keyname, USER_KEY, reply))
        return get_key(CERTS_DIR, keyname, USER_P12_CERT, reply);
    return 0;
}

int remove_user_cert(const char *key)
{
    return remove_key(CERTS_DIR, key);
}

int remove_ca_cert(const char *key)
{
    return remove_key(CACERTS_DIR, key);
}
