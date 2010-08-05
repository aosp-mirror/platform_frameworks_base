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

int create_pkg_path(char path[PKG_PATH_MAX],
                    const char *prefix,
                    const char *pkgname,
                    const char *postfix)
{
    int len;
    const char *x;

    len = strlen(pkgname);
    if (len > PKG_NAME_MAX) {
        return -1;
    }
    if ((len + strlen(prefix) + strlen(postfix)) >= PKG_PATH_MAX) {
        return -1;
    }

    x = pkgname;
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
        }else {
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

    sprintf(path, "%s%s%s", prefix, pkgname, postfix);
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
