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

#define PRIMARY_USER_PREFIX    "data/"
#define SECONDARY_USER_PREFIX  "user/"

#define PKG_DIR_POSTFIX        ""

#define PKG_LIB_POSTFIX        "/lib"

#define CACHE_DIR_POSTFIX      "/cache"

#define APP_SUBDIR             "app/" // sub-directory under ANDROID_DATA

/* other handy constants */

#define PRIVATE_APP_SUBDIR     "app-private/" // sub-directory under ANDROID_DATA

#define DALVIK_CACHE_PREFIX    "/data/dalvik-cache/"
#define DALVIK_CACHE_POSTFIX   "/classes.dex"

#define UPDATE_COMMANDS_DIR_PREFIX  "/system/etc/updatecmds/"

#define PKG_NAME_MAX  128   /* largest allowed package name */
#define PKG_PATH_MAX  256   /* max size of any path we use */

/* data structures */

typedef struct {
    char* path;
    size_t len;
} dir_rec_t;

typedef struct {
    size_t count;
    dir_rec_t* dirs;
} dir_rec_array_t;

extern dir_rec_t android_app_dir;
extern dir_rec_t android_app_private_dir;
extern dir_rec_t android_data_dir;
extern dir_rec_t android_asec_dir;
extern dir_rec_array_t android_system_dirs;

/* util.c */

int create_pkg_path_in_dir(char path[PKG_PATH_MAX],
                                const dir_rec_t* dir,
                                const char* pkgname,
                                const char* postfix);

int create_pkg_path(char path[PKG_PATH_MAX],
                    const char *pkgname,
                    const char *postfix,
                    uid_t persona);

int create_persona_path(char path[PKG_PATH_MAX],
                    uid_t persona);

int create_move_path(char path[PKG_PATH_MAX],
                     const char* pkgname,
                     const char* leaf,
                     uid_t persona);

int is_valid_package_name(const char* pkgname);

int create_cache_path(char path[PKG_PATH_MAX], const char *src);

int delete_dir_contents(const char *pathname,
                        int also_delete_dir,
                        const char *ignore);

int delete_dir_contents_fd(int dfd, const char *name);

int validate_system_app_path(const char* path);

int get_path_from_env(dir_rec_t* rec, const char* var);

int get_path_from_string(dir_rec_t* rec, const char* path);

int copy_and_append(dir_rec_t* dst, const dir_rec_t* src, const char* suffix);

int validate_apk_path(const char *path);

int append_and_increment(char** dst, const char* src, size_t* dst_size);

char *build_string2(char *s1, char *s2);
char *build_string3(char *s1, char *s2, char *s3);

/* commands.c */

int install(const char *pkgname, uid_t uid, gid_t gid);
int uninstall(const char *pkgname, uid_t persona);
int renamepkg(const char *oldpkgname, const char *newpkgname);
int delete_user_data(const char *pkgname, uid_t persona);
int make_user_data(const char *pkgname, uid_t uid, uid_t persona);
int delete_persona(uid_t persona);
int delete_cache(const char *pkgname);
int move_dex(const char *src, const char *dst);
int rm_dex(const char *path);
int protect(char *pkgname, gid_t gid);
int get_size(const char *pkgname, const char *apkpath, const char *fwdlock_apkpath,
             const char *asecpath, int64_t *codesize, int64_t *datasize, int64_t *cachesize,
             int64_t *asecsize);
int free_cache(int64_t free_size);
int dexopt(const char *apk_path, uid_t uid, int is_public);
int movefiles();
int linklib(const char* target, const char* source);
int unlinklib(const char* libPath);
