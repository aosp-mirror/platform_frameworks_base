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

#include "installd.h"

int create_pkg_path_in_dir(char path[PKG_PATH_MAX],
                                const dir_rec_t* dir,
                                const char* pkgname,
                                const char* postfix)
{
     const size_t postfix_len = strlen(postfix);

     const size_t pkgname_len = strlen(pkgname);
     if (pkgname_len > PKG_NAME_MAX) {
         return -1;
     }

     if (is_valid_package_name(pkgname) < 0) {
         return -1;
     }

     if ((pkgname_len + dir->len + postfix_len) >= PKG_PATH_MAX) {
         return -1;
     }

     char *dst = path;
     size_t dst_size = PKG_PATH_MAX;

     if (append_and_increment(&dst, dir->path, &dst_size) < 0
             || append_and_increment(&dst, pkgname, &dst_size) < 0
             || append_and_increment(&dst, postfix, &dst_size) < 0) {
         LOGE("Error building APK path");
         return -1;
     }

     return 0;
}

/**
 * Create the package path name for a given package name with a postfix for
 * a certain persona. Returns 0 on success, and -1 on failure.
 */
int create_pkg_path(char path[PKG_PATH_MAX],
                    const char *pkgname,
                    const char *postfix,
                    uid_t persona)
{
    size_t uid_len;
    char* persona_prefix;
    if (persona == 0) {
        persona_prefix = PRIMARY_USER_PREFIX;
        uid_len = 0;
    } else {
        persona_prefix = SECONDARY_USER_PREFIX;
        uid_len = snprintf(NULL, 0, "%d", persona);
    }

    const size_t prefix_len = android_data_dir.len + strlen(persona_prefix) + uid_len + 1 /*slash*/;
    char prefix[prefix_len + 1];

    char *dst = prefix;
    size_t dst_size = sizeof(prefix);

    if (append_and_increment(&dst, android_data_dir.path, &dst_size) < 0
            || append_and_increment(&dst, persona_prefix, &dst_size) < 0) {
        LOGE("Error building prefix for APK path");
        return -1;
    }

    if (persona != 0) {
        int ret = snprintf(dst, dst_size, "%d/", persona);
        if (ret < 0 || (size_t) ret != uid_len + 1) {
            LOGW("Error appending UID to APK path");
            return -1;
        }
    }

    dir_rec_t dir;
    dir.path = prefix;
    dir.len = prefix_len;

    return create_pkg_path_in_dir(path, &dir, pkgname, postfix);
}

/**
 * Create the path name for user data for a certain persona.
 * Returns 0 on success, and -1 on failure.
 */
int create_persona_path(char path[PKG_PATH_MAX],
                    uid_t persona)
{
    size_t uid_len;
    char* persona_prefix;
    if (persona == 0) {
        persona_prefix = PRIMARY_USER_PREFIX;
        uid_len = 0;
    } else {
        persona_prefix = SECONDARY_USER_PREFIX;
        uid_len = snprintf(NULL, 0, "%d/", persona);
    }

    char *dst = path;
    size_t dst_size = PKG_PATH_MAX;

    if (append_and_increment(&dst, android_data_dir.path, &dst_size) < 0
            || append_and_increment(&dst, persona_prefix, &dst_size) < 0) {
        LOGE("Error building prefix for user path");
        return -1;
    }

    if (persona != 0) {
        if (dst_size < uid_len + 1) {
            LOGE("Error building user path");
            return -1;
        }
        int ret = snprintf(dst, dst_size, "%d/", persona);
        if (ret < 0 || (size_t) ret != uid_len) {
            LOGE("Error appending persona id to path");
            return -1;
        }
    }
    return 0;
}

int create_move_path(char path[PKG_PATH_MAX],
    const char* pkgname,
    const char* leaf,
    uid_t persona)
{
    if ((android_data_dir.len + strlen(PRIMARY_USER_PREFIX) + strlen(pkgname) + strlen(leaf) + 1)
            >= PKG_PATH_MAX) {
        return -1;
    }

    sprintf(path, "%s%s%s/%s", android_data_dir.path, PRIMARY_USER_PREFIX, pkgname, leaf);
    return 0;
}

/**
 * Checks whether the package name is valid. Returns -1 on error and
 * 0 on success.
 */
int is_valid_package_name(const char* pkgname) {
    const char *x = pkgname;
    int alpha = -1;

    while (*x) {
        if (isalnum(*x) || (*x == '_')) {
                /* alphanumeric or underscore are fine */
        } else if (*x == '.') {
            if ((x == pkgname) || (x[1] == '.') || (x[1] == 0)) {
                    /* periods must not be first, last, or doubled */
                LOGE("invalid package name '%s'\n", pkgname);
                return -1;
            }
        } else if (*x == '-') {
            /* Suffix -X is fine to let versioning of packages.
               But whatever follows should be alphanumeric.*/
            alpha = 1;
        } else {
                /* anything not A-Z, a-z, 0-9, _, or . is invalid */
            LOGE("invalid package name '%s'\n", pkgname);
            return -1;
        }

        x++;
    }

    if (alpha == 1) {
        // Skip current character
        x++;
        while (*x) {
            if (!isalnum(*x)) {
                LOGE("invalid package name '%s' should include only numbers after -\n", pkgname);
                return -1;
            }
            x++;
        }
    }

    return 0;
}

static int _delete_dir_contents(DIR *d, const char *ignore)
{
    int result = 0;
    struct dirent *de;
    int dfd;

    dfd = dirfd(d);

    if (dfd < 0) return -1;

    while ((de = readdir(d))) {
        const char *name = de->d_name;

            /* skip the ignore name if provided */
        if (ignore && !strcmp(name, ignore)) continue;

        if (de->d_type == DT_DIR) {
            int r, subfd;
            DIR *subdir;

                /* always skip "." and ".." */
            if (name[0] == '.') {
                if (name[1] == 0) continue;
                if ((name[1] == '.') && (name[2] == 0)) continue;
            }

            subfd = openat(dfd, name, O_RDONLY | O_DIRECTORY);
            if (subfd < 0) {
                LOGE("Couldn't openat %s: %s\n", name, strerror(errno));
                result = -1;
                continue;
            }
            subdir = fdopendir(subfd);
            if (subdir == NULL) {
                LOGE("Couldn't fdopendir %s: %s\n", name, strerror(errno));
                close(subfd);
                result = -1;
                continue;
            }
            if (_delete_dir_contents(subdir, 0)) {
                result = -1;
            }
            closedir(subdir);
            if (unlinkat(dfd, name, AT_REMOVEDIR) < 0) {
                LOGE("Couldn't unlinkat %s: %s\n", name, strerror(errno));
                result = -1;
            }
        } else {
            if (unlinkat(dfd, name, 0) < 0) {
                LOGE("Couldn't unlinkat %s: %s\n", name, strerror(errno));
                result = -1;
            }
        }
    }

    return result;
}

int delete_dir_contents(const char *pathname,
                        int also_delete_dir,
                        const char *ignore)
{
    int res = 0;
    DIR *d;

    d = opendir(pathname);
    if (d == NULL) {
        LOGE("Couldn't opendir %s: %s\n", pathname, strerror(errno));
        return -errno;
    }
    res = _delete_dir_contents(d, ignore);
    closedir(d);
    if (also_delete_dir) {
        if (rmdir(pathname)) {
            LOGE("Couldn't rmdir %s: %s\n", pathname, strerror(errno));
            res = -1;
        }
    }
    return res;
}

int delete_dir_contents_fd(int dfd, const char *name)
{
    int fd, res;
    DIR *d;

    fd = openat(dfd, name, O_RDONLY | O_DIRECTORY);
    if (fd < 0) {
        LOGE("Couldn't openat %s: %s\n", name, strerror(errno));
        return -1;
    }
    d = fdopendir(fd);
    if (d == NULL) {
        LOGE("Couldn't fdopendir %s: %s\n", name, strerror(errno));
        close(fd);
        return -1;
    }
    res = _delete_dir_contents(d, 0);
    closedir(d);
    return res;
}

/**
 * Checks whether a path points to a system app (.apk file). Returns 0
 * if it is a system app or -1 if it is not.
 */
int validate_system_app_path(const char* path) {
    size_t i;

    for (i = 0; i < android_system_dirs.count; i++) {
        const size_t dir_len = android_system_dirs.dirs[i].len;
        if (!strncmp(path, android_system_dirs.dirs[i].path, dir_len)) {
            if (path[dir_len] == '.' || strchr(path + dir_len, '/') != NULL) {
                LOGE("invalid system apk path '%s' (trickery)\n", path);
                return -1;
            }
            return 0;
        }
    }

    return -1;
}

/**
 * Get the contents of a environment variable that contains a path. Caller
 * owns the string that is inserted into the directory record. Returns
 * 0 on success and -1 on error.
 */
int get_path_from_env(dir_rec_t* rec, const char* var) {
    const char* path = getenv(var);
    int ret = get_path_from_string(rec, path);
    if (ret < 0) {
        LOGW("Problem finding value for environment variable %s\n", var);
    }
    return ret;
}

/**
 * Puts the string into the record as a directory. Appends '/' to the end
 * of all paths. Caller owns the string that is inserted into the directory
 * record. A null value will result in an error.
 *
 * Returns 0 on success and -1 on error.
 */
int get_path_from_string(dir_rec_t* rec, const char* path) {
    if (path == NULL) {
        return -1;
    } else {
        const size_t path_len = strlen(path);
        if (path_len <= 0) {
            return -1;
        }

        // Make sure path is absolute.
        if (path[0] != '/') {
            return -1;
        }

        if (path[path_len - 1] == '/') {
            // Path ends with a forward slash. Make our own copy.

            rec->path = strdup(path);
            if (rec->path == NULL) {
                return -1;
            }

            rec->len = path_len;
        } else {
            // Path does not end with a slash. Generate a new string.
            char *dst;

            // Add space for slash and terminating null.
            size_t dst_size = path_len + 2;

            rec->path = malloc(dst_size);
            if (rec->path == NULL) {
                return -1;
            }

            dst = rec->path;

            if (append_and_increment(&dst, path, &dst_size) < 0
                    || append_and_increment(&dst, "/", &dst_size)) {
                LOGE("Error canonicalizing path");
                return -1;
            }

            rec->len = dst - rec->path;
        }
    }
    return 0;
}

int copy_and_append(dir_rec_t* dst, const dir_rec_t* src, const char* suffix) {
    dst->len = src->len + strlen(suffix);
    const size_t dstSize = dst->len + 1;
    dst->path = (char*) malloc(dstSize);

    if (dst->path == NULL
            || snprintf(dst->path, dstSize, "%s%s", src->path, suffix)
                    != (ssize_t) dst->len) {
        LOGE("Could not allocate memory to hold appended path; aborting\n");
        return -1;
    }

    return 0;
}

/**
 * Check whether path points to a valid path for an APK file. An ASEC
 * directory is allowed to have one level of subdirectory names. Returns -1
 * when an invalid path is encountered and 0 when a valid path is encountered.
 */
int validate_apk_path(const char *path)
{
    int allowsubdir = 0;
    char *subdir = NULL;
    size_t dir_len;
    size_t path_len;

    if (!strncmp(path, android_app_dir.path, android_app_dir.len)) {
        dir_len = android_app_dir.len;
    } else if (!strncmp(path, android_app_private_dir.path, android_app_private_dir.len)) {
        dir_len = android_app_private_dir.len;
    } else if (!strncmp(path, android_asec_dir.path, android_asec_dir.len)) {
        dir_len = android_asec_dir.len;
        allowsubdir = 1;
    } else {
        LOGE("invalid apk path '%s' (bad prefix)\n", path);
        return -1;
    }

    path_len = strlen(path);

    /*
     * Only allow the path to have a subdirectory if it's been marked as being allowed.
     */
    if ((subdir = strchr(path + dir_len, '/')) != NULL) {
        ++subdir;
        if (!allowsubdir
                || (path_len > (size_t) (subdir - path) && (strchr(subdir, '/') != NULL))) {
            LOGE("invalid apk path '%s' (subdir?)\n", path);
            return -1;
        }
    }

    /*
     *  Directories can't have a period directly after the directory markers
     *  to prevent ".."
     */
    if (path[dir_len] == '.'
            || (subdir != NULL && ((*subdir == '.') || (strchr(subdir, '/') != NULL)))) {
        LOGE("invalid apk path '%s' (trickery)\n", path);
        return -1;
    }

    return 0;
}

int append_and_increment(char** dst, const char* src, size_t* dst_size) {
    ssize_t ret = strlcpy(*dst, src, *dst_size);
    if (ret < 0 || (size_t) ret >= *dst_size) {
        return -1;
    }
    *dst += ret;
    *dst_size -= ret;
    return 0;
}

char *build_string2(char *s1, char *s2) {
    if (s1 == NULL || s2 == NULL) return NULL;

    int len_s1 = strlen(s1);
    int len_s2 = strlen(s2);
    int len = len_s1 + len_s2 + 1;
    char *result = malloc(len);
    if (result == NULL) return NULL;

    strcpy(result, s1);
    strcpy(result + len_s1, s2);

    return result;
}

char *build_string3(char *s1, char *s2, char *s3) {
    if (s1 == NULL || s2 == NULL || s3 == NULL) return NULL;

    int len_s1 = strlen(s1);
    int len_s2 = strlen(s2);
    int len_s3 = strlen(s3);
    int len = len_s1 + len_s2 + len_s3 + 1;
    char *result = malloc(len);
    if (result == NULL) return NULL;

    strcpy(result, s1);
    strcpy(result + len_s1, s2);
    strcpy(result + len_s1 + len_s2, s3);

    return result;
}
