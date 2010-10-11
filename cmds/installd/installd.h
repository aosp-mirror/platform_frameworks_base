/*
**
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

#define LOG_TAG "installd"

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <inttypes.h>
#include <sys/stat.h>
#include <dirent.h>
#include <unistd.h>
#include <ctype.h>
#include <fcntl.h>
#include <errno.h>
#include <utime.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/wait.h>

#include <cutils/sockets.h>
#include <cutils/log.h>
#include <cutils/properties.h>

#include <private/android_filesystem_config.h>

#if INCLUDE_SYS_MOUNT_FOR_STATFS
#include <sys/mount.h>
#else
#include <sys/statfs.h>
#endif

#define SOCKET_PATH "installd"


/* elements combined with a valid package name to form paths */

#define PKG_DIR_PREFIX         "/data/data/"
#define PKG_SEC_DIR_PREFIX     "/data/secure/data/"
#define PKG_DIR_POSTFIX        ""

#define PKG_LIB_PREFIX         "/data/data/"
#define PKG_SEC_LIB_PREFIX     "/data/secure/data/"
#define PKG_LIB_POSTFIX        "/lib"

#define CACHE_DIR_PREFIX       "/data/data/"
#define CACHE_SEC_DIR_PREFIX   "/data/secure/data/"
#define CACHE_DIR_POSTFIX      "/cache"

#define APK_DIR_PREFIX         "/data/app/"

/* Encrypted File SYstems constants */
#define USE_ENCRYPTED_FS       1
#define USE_UNENCRYPTED_FS     0

/* other handy constants */

#define PROTECTED_DIR_PREFIX  "/data/app-private/"
#define SDCARD_DIR_PREFIX  getenv("ASEC_MOUNTPOINT")

#define DALVIK_CACHE_PREFIX   "/data/dalvik-cache/"
#define DALVIK_CACHE_POSTFIX  "/classes.dex"

#define UPDATE_COMMANDS_DIR_PREFIX  "/system/etc/updatecmds/"

#define PKG_NAME_MAX  128   /* largest allowed package name */
#define PKG_PATH_MAX  256   /* max size of any path we use */


/* util.c */

int create_pkg_path(char path[PKG_PATH_MAX],
                    const char *prefix,
                    const char *pkgname,
                    const char *postfix);

int create_cache_path(char path[PKG_PATH_MAX], const char *src);

int delete_dir_contents(const char *pathname,
                        int also_delete_dir,
                        const char *ignore);

int delete_dir_contents_fd(int dfd, const char *name);

/* commands.c */

int install(const char *pkgname, int encrypted_fs_flag, uid_t uid, gid_t gid);
int uninstall(const char *pkgname, int encrypted_fs_flag);
int renamepkg(const char *oldpkgname, const char *newpkgname, int encrypted_fs_flag);
int delete_user_data(const char *pkgname, int encrypted_fs_flag);
int delete_cache(const char *pkgname, int encrypted_fs_flag);
int move_dex(const char *src, const char *dst);
int rm_dex(const char *path);
int protect(char *pkgname, gid_t gid);
int get_size(const char *pkgname, const char *apkpath, const char *fwdlock_apkpath,
             int64_t *codesize, int64_t *datasize, int64_t *cachesize, int encrypted_fs_flag);
int free_cache(int64_t free_size);
int dexopt(const char *apk_path, uid_t uid, int is_public);
int movefiles();
int linklib(const char* target, const char* source);
int unlinklib(const char* libPath);
