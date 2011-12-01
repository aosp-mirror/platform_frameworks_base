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
#include <diskusage/dirsize.h>

/* Directory records that are used in execution of commands. */
dir_rec_t android_data_dir;
dir_rec_t android_asec_dir;
dir_rec_t android_app_dir;
dir_rec_t android_app_private_dir;
dir_rec_array_t android_system_dirs;

int install(const char *pkgname, uid_t uid, gid_t gid)
{
    char pkgdir[PKG_PATH_MAX];
    char libdir[PKG_PATH_MAX];

    if ((uid < AID_SYSTEM) || (gid < AID_SYSTEM)) {
        LOGE("invalid uid/gid: %d %d\n", uid, gid);
        return -1;
    }

    if (create_pkg_path(pkgdir, pkgname, PKG_DIR_POSTFIX, 0)) {
        LOGE("cannot create package path\n");
        return -1;
    }

    if (create_pkg_path(libdir, pkgname, PKG_LIB_POSTFIX, 0)) {
        LOGE("cannot create package lib path\n");
        return -1;
    }

    if (mkdir(pkgdir, 0751) < 0) {
        LOGE("cannot create dir '%s': %s\n", pkgdir, strerror(errno));
        return -errno;
    }
    if (chmod(pkgdir, 0751) < 0) {
        LOGE("cannot chmod dir '%s': %s\n", pkgdir, strerror(errno));
        unlink(pkgdir);
        return -errno;
    }
    if (chown(pkgdir, uid, gid) < 0) {
        LOGE("cannot chown dir '%s': %s\n", pkgdir, strerror(errno));
        unlink(pkgdir);
        return -errno;
    }
    if (mkdir(libdir, 0755) < 0) {
        LOGE("cannot create dir '%s': %s\n", libdir, strerror(errno));
        unlink(pkgdir);
        return -errno;
    }
    if (chmod(libdir, 0755) < 0) {
        LOGE("cannot chmod dir '%s': %s\n", libdir, strerror(errno));
        unlink(libdir);
        unlink(pkgdir);
        return -errno;
    }
    if (chown(libdir, AID_SYSTEM, AID_SYSTEM) < 0) {
        LOGE("cannot chown dir '%s': %s\n", libdir, strerror(errno));
        unlink(libdir);
        unlink(pkgdir);
        return -errno;
    }
    return 0;
}

int uninstall(const char *pkgname, uid_t persona)
{
    char pkgdir[PKG_PATH_MAX];

    if (create_pkg_path(pkgdir, pkgname, PKG_DIR_POSTFIX, persona))
        return -1;

    /* delete contents AND directory, no exceptions */
    return delete_dir_contents(pkgdir, 1, NULL);
}

int renamepkg(const char *oldpkgname, const char *newpkgname)
{
    char oldpkgdir[PKG_PATH_MAX];
    char newpkgdir[PKG_PATH_MAX];

    if (create_pkg_path(oldpkgdir, oldpkgname, PKG_DIR_POSTFIX, 0))
        return -1;
    if (create_pkg_path(newpkgdir, newpkgname, PKG_DIR_POSTFIX, 0))
        return -1;

    if (rename(oldpkgdir, newpkgdir) < 0) {
        LOGE("cannot rename dir '%s' to '%s': %s\n", oldpkgdir, newpkgdir, strerror(errno));
        return -errno;
    }
    return 0;
}

int delete_user_data(const char *pkgname, uid_t persona)
{
    char pkgdir[PKG_PATH_MAX];

    if (create_pkg_path(pkgdir, pkgname, PKG_DIR_POSTFIX, persona))
        return -1;

    /* delete contents, excluding "lib", but not the directory itself */
    return delete_dir_contents(pkgdir, 0, "lib");
}

int make_user_data(const char *pkgname, uid_t uid, uid_t persona)
{
    char pkgdir[PKG_PATH_MAX];
    char real_libdir[PKG_PATH_MAX];

    // Create the data dir for the package
    if (create_pkg_path(pkgdir, pkgname, PKG_DIR_POSTFIX, persona)) {
        return -1;
    }
    if (mkdir(pkgdir, 0751) < 0) {
        LOGE("cannot create dir '%s': %s\n", pkgdir, strerror(errno));
        return -errno;
    }
    if (chown(pkgdir, uid, uid) < 0) {
        LOGE("cannot chown dir '%s': %s\n", pkgdir, strerror(errno));
        unlink(pkgdir);
        return -errno;
    }
    return 0;
}

int delete_persona(uid_t persona)
{
    char pkgdir[PKG_PATH_MAX];

    if (create_persona_path(pkgdir, persona))
        return -1;

    return delete_dir_contents(pkgdir, 1, NULL);
}

int delete_cache(const char *pkgname)
{
    char cachedir[PKG_PATH_MAX];

    if (create_pkg_path(cachedir, pkgname, CACHE_DIR_POSTFIX, 0))
        return -1;

        /* delete contents, not the directory, no exceptions */
    return delete_dir_contents(cachedir, 0, 0);
}

static int64_t disk_free()
{
    struct statfs sfs;
    if (statfs(android_data_dir.path, &sfs) == 0) {
        return sfs.f_bavail * sfs.f_bsize;
    } else {
        LOGE("Couldn't statfs %s: %s\n", android_data_dir.path, strerror(errno));
        return -1;
    }
}

/* Try to ensure free_size bytes of storage are available.
 * Returns 0 on success.
 * This is rather simple-minded because doing a full LRU would
 * be potentially memory-intensive, and without atime it would
 * also require that apps constantly modify file metadata even
 * when just reading from the cache, which is pretty awful.
 */
int free_cache(int64_t free_size)
{
    const char *name;
    int dfd, subfd;
    DIR *d;
    struct dirent *de;
    int64_t avail;
    char datadir[PKG_PATH_MAX];

    avail = disk_free();
    if (avail < 0) return -1;

    LOGI("free_cache(%" PRId64 ") avail %" PRId64 "\n", free_size, avail);
    if (avail >= free_size) return 0;

    if (create_persona_path(datadir, 0)) {
        LOGE("couldn't get directory for persona 0");
        return -1;
    }

    d = opendir(datadir);
    if (d == NULL) {
        LOGE("cannot open %s: %s\n", datadir, strerror(errno));
        return -1;
    }
    dfd = dirfd(d);

    while ((de = readdir(d))) {
        if (de->d_type != DT_DIR) continue;
        name = de->d_name;

        /* always skip "." and ".." */
        if (name[0] == '.') {
            if (name[1] == 0) continue;
            if ((name[1] == '.') && (name[2] == 0)) continue;
        }

        subfd = openat(dfd, name, O_RDONLY | O_DIRECTORY);
        if (subfd < 0) continue;

        delete_dir_contents_fd(subfd, "cache");
        close(subfd);

        avail = disk_free();
        if (avail >= free_size) {
            closedir(d);
            return 0;
        }
    }
    closedir(d);

    /* Fail case - not possible to free space */
    return -1;
}

int move_dex(const char *src, const char *dst)
{
    char src_dex[PKG_PATH_MAX];
    char dst_dex[PKG_PATH_MAX];

    if (validate_apk_path(src)) return -1;
    if (validate_apk_path(dst)) return -1;

    if (create_cache_path(src_dex, src)) return -1;
    if (create_cache_path(dst_dex, dst)) return -1;

    LOGV("move %s -> %s\n", src_dex, dst_dex);
    if (rename(src_dex, dst_dex) < 0) {
        LOGE("Couldn't move %s: %s\n", src_dex, strerror(errno));
        return -1;
    } else {
        return 0;
    }
}

int rm_dex(const char *path)
{
    char dex_path[PKG_PATH_MAX];

    if (validate_apk_path(path)) return -1;
    if (create_cache_path(dex_path, path)) return -1;

    LOGV("unlink %s\n", dex_path);
    if (unlink(dex_path) < 0) {
        LOGE("Couldn't unlink %s: %s\n", dex_path, strerror(errno));
        return -1;
    } else {
        return 0;
    }
}

int protect(char *pkgname, gid_t gid)
{
    struct stat s;
    char pkgpath[PKG_PATH_MAX];

    if (gid < AID_SYSTEM) return -1;

    if (create_pkg_path_in_dir(pkgpath, &android_app_private_dir, pkgname, ".apk"))
        return -1;

    if (stat(pkgpath, &s) < 0) return -1;

    if (chown(pkgpath, s.st_uid, gid) < 0) {
        LOGE("failed to chgrp '%s': %s\n", pkgpath, strerror(errno));
        return -1;
    }

    if (chmod(pkgpath, S_IRUSR|S_IWUSR|S_IRGRP) < 0) {
        LOGE("failed to chmod '%s': %s\n", pkgpath, strerror(errno));
        return -1;
    }

    return 0;
}

int get_size(const char *pkgname, const char *apkpath,
             const char *fwdlock_apkpath, const char *asecpath,
             int64_t *_codesize, int64_t *_datasize, int64_t *_cachesize,
             int64_t* _asecsize)
{
    DIR *d;
    int dfd;
    struct dirent *de;
    struct stat s;
    char path[PKG_PATH_MAX];

    int64_t codesize = 0;
    int64_t datasize = 0;
    int64_t cachesize = 0;
    int64_t asecsize = 0;

        /* count the source apk as code -- but only if it's not
         * on the /system partition and its not on the sdcard.
         */
    if (validate_system_app_path(apkpath) &&
            strncmp(apkpath, android_asec_dir.path, android_asec_dir.len) != 0) {
        if (stat(apkpath, &s) == 0) {
            codesize += stat_size(&s);
        }
    }
        /* count the forward locked apk as code if it is given
         */
    if (fwdlock_apkpath != NULL && fwdlock_apkpath[0] != '!') {
        if (stat(fwdlock_apkpath, &s) == 0) {
            codesize += stat_size(&s);
        }
    }
        /* count the cached dexfile as code */
    if (!create_cache_path(path, apkpath)) {
        if (stat(path, &s) == 0) {
            codesize += stat_size(&s);
        }
    }

        /* compute asec size if it is given
         */
    if (asecpath != NULL && asecpath[0] != '!') {
        if (stat(asecpath, &s) == 0) {
            asecsize += stat_size(&s);
        }
    }

    if (create_pkg_path(path, pkgname, PKG_DIR_POSTFIX, 0)) {
        goto done;
    }

    d = opendir(path);
    if (d == NULL) {
        goto done;
    }
    dfd = dirfd(d);

    /* most stuff in the pkgdir is data, except for the "cache"
     * directory and below, which is cache, and the "lib" directory
     * and below, which is code...
     */
    while ((de = readdir(d))) {
        const char *name = de->d_name;

        if (de->d_type == DT_DIR) {
            int subfd;
                /* always skip "." and ".." */
            if (name[0] == '.') {
                if (name[1] == 0) continue;
                if ((name[1] == '.') && (name[2] == 0)) continue;
            }
            subfd = openat(dfd, name, O_RDONLY | O_DIRECTORY);
            if (subfd >= 0) {
                int64_t size = calculate_dir_size(subfd);
                if (!strcmp(name,"lib")) {
                    codesize += size;
                } else if(!strcmp(name,"cache")) {
                    cachesize += size;
                } else {
                    datasize += size;
                }
            }
        } else {
            if (fstatat(dfd, name, &s, AT_SYMLINK_NOFOLLOW) == 0) {
                datasize += stat_size(&s);
            }
        }
    }
    closedir(d);
done:
    *_codesize = codesize;
    *_datasize = datasize;
    *_cachesize = cachesize;
    *_asecsize = asecsize;
    return 0;
}


/* a simpler version of dexOptGenerateCacheFileName() */
int create_cache_path(char path[PKG_PATH_MAX], const char *src)
{
    char *tmp;
    int srclen;
    int dstlen;

    srclen = strlen(src);

        /* demand that we are an absolute path */
    if ((src == 0) || (src[0] != '/') || strstr(src,"..")) {
        return -1;
    }

    if (srclen > PKG_PATH_MAX) {        // XXX: PKG_NAME_MAX?
        return -1;
    }

    dstlen = srclen + strlen(DALVIK_CACHE_PREFIX) + 
        strlen(DALVIK_CACHE_POSTFIX) + 1;
    
    if (dstlen > PKG_PATH_MAX) {
        return -1;
    }

    sprintf(path,"%s%s%s",
            DALVIK_CACHE_PREFIX,
            src + 1, /* skip the leading / */
            DALVIK_CACHE_POSTFIX);
    
    for(tmp = path + strlen(DALVIK_CACHE_PREFIX); *tmp; tmp++) {
        if (*tmp == '/') {
            *tmp = '@';
        }
    }

    return 0;
}

static void run_dexopt(int zip_fd, int odex_fd, const char* input_file_name,
    const char* dexopt_flags)
{
    static const char* DEX_OPT_BIN = "/system/bin/dexopt";
    static const int MAX_INT_LEN = 12;      // '-'+10dig+'\0' -OR- 0x+8dig
    char zip_num[MAX_INT_LEN];
    char odex_num[MAX_INT_LEN];

    sprintf(zip_num, "%d", zip_fd);
    sprintf(odex_num, "%d", odex_fd);

    execl(DEX_OPT_BIN, DEX_OPT_BIN, "--zip", zip_num, odex_num, input_file_name,
        dexopt_flags, (char*) NULL);
    LOGE("execl(%s) failed: %s\n", DEX_OPT_BIN, strerror(errno));
}

static int wait_dexopt(pid_t pid, const char* apk_path)
{
    int status;
    pid_t got_pid;

    /*
     * Wait for the optimization process to finish.
     */
    while (1) {
        got_pid = waitpid(pid, &status, 0);
        if (got_pid == -1 && errno == EINTR) {
            printf("waitpid interrupted, retrying\n");
        } else {
            break;
        }
    }
    if (got_pid != pid) {
        LOGW("waitpid failed: wanted %d, got %d: %s\n",
            (int) pid, (int) got_pid, strerror(errno));
        return 1;
    }

    if (WIFEXITED(status) && WEXITSTATUS(status) == 0) {
        LOGV("DexInv: --- END '%s' (success) ---\n", apk_path);
        return 0;
    } else {
        LOGW("DexInv: --- END '%s' --- status=0x%04x, process failed\n",
            apk_path, status);
        return status;      /* always nonzero */
    }
}

int dexopt(const char *apk_path, uid_t uid, int is_public)
{
    struct utimbuf ut;
    struct stat apk_stat, dex_stat;
    char dex_path[PKG_PATH_MAX];
    char dexopt_flags[PROPERTY_VALUE_MAX];
    char *end;
    int res, zip_fd=-1, odex_fd=-1;

        /* Before anything else: is there a .odex file?  If so, we have
         * pre-optimized the apk and there is nothing to do here.
         */
    if (strlen(apk_path) >= (PKG_PATH_MAX - 8)) {
        return -1;
    }

    /* platform-specific flags affecting optimization and verification */
    property_get("dalvik.vm.dexopt-flags", dexopt_flags, "");

    strcpy(dex_path, apk_path);
    end = strrchr(dex_path, '.');
    if (end != NULL) {
        strcpy(end, ".odex");
        if (stat(dex_path, &dex_stat) == 0) {
            return 0;
        }
    }

    if (create_cache_path(dex_path, apk_path)) {
        return -1;
    }

    memset(&apk_stat, 0, sizeof(apk_stat));
    stat(apk_path, &apk_stat);

    zip_fd = open(apk_path, O_RDONLY, 0);
    if (zip_fd < 0) {
        LOGE("dexopt cannot open '%s' for input\n", apk_path);
        return -1;
    }

    unlink(dex_path);
    odex_fd = open(dex_path, O_RDWR | O_CREAT | O_EXCL, 0644);
    if (odex_fd < 0) {
        LOGE("dexopt cannot open '%s' for output\n", dex_path);
        goto fail;
    }
    if (fchown(odex_fd, AID_SYSTEM, uid) < 0) {
        LOGE("dexopt cannot chown '%s'\n", dex_path);
        goto fail;
    }
    if (fchmod(odex_fd,
               S_IRUSR|S_IWUSR|S_IRGRP |
               (is_public ? S_IROTH : 0)) < 0) {
        LOGE("dexopt cannot chmod '%s'\n", dex_path);
        goto fail;
    }

    LOGV("DexInv: --- BEGIN '%s' ---\n", apk_path);

    pid_t pid;
    pid = fork();
    if (pid == 0) {
        /* child -- drop privileges before continuing */
        if (setgid(uid) != 0) {
            LOGE("setgid(%d) failed during dexopt\n", uid);
            exit(64);
        }
        if (setuid(uid) != 0) {
            LOGE("setuid(%d) during dexopt\n", uid);
            exit(65);
        }
        if (flock(odex_fd, LOCK_EX | LOCK_NB) != 0) {
            LOGE("flock(%s) failed: %s\n", dex_path, strerror(errno));
            exit(66);
        }

        run_dexopt(zip_fd, odex_fd, apk_path, dexopt_flags);
        exit(67);   /* only get here on exec failure */
    } else {
        res = wait_dexopt(pid, apk_path);
        if (res != 0) {
            LOGE("dexopt failed on '%s' res = %d\n", dex_path, res);
            goto fail;
        }
    }

    ut.actime = apk_stat.st_atime;
    ut.modtime = apk_stat.st_mtime;
    utime(dex_path, &ut);
    
    close(odex_fd);
    close(zip_fd);
    return 0;

fail:
    if (odex_fd >= 0) {
        close(odex_fd);
        unlink(dex_path);
    }
    if (zip_fd >= 0) {
        close(zip_fd);
    }
    return -1;
}

void mkinnerdirs(char* path, int basepos, mode_t mode, int uid, int gid,
        struct stat* statbuf)
{
    while (path[basepos] != 0) {
        if (path[basepos] == '/') {
            path[basepos] = 0;
            if (lstat(path, statbuf) < 0) {
                LOGV("Making directory: %s\n", path);
                if (mkdir(path, mode) == 0) {
                    chown(path, uid, gid);
                } else {
                    LOGW("Unable to make directory %s: %s\n", path, strerror(errno));
                }
            }
            path[basepos] = '/';
            basepos++;
        }
        basepos++;
    }
}

int movefileordir(char* srcpath, char* dstpath, int dstbasepos,
        int dstuid, int dstgid, struct stat* statbuf)
{
    DIR *d;
    struct dirent *de;
    int res;

    int srcend = strlen(srcpath);
    int dstend = strlen(dstpath);
    
    if (lstat(srcpath, statbuf) < 0) {
        LOGW("Unable to stat %s: %s\n", srcpath, strerror(errno));
        return 1;
    }
    
    if ((statbuf->st_mode&S_IFDIR) == 0) {
        mkinnerdirs(dstpath, dstbasepos, S_IRWXU|S_IRWXG|S_IXOTH,
                dstuid, dstgid, statbuf);
        LOGV("Renaming %s to %s (uid %d)\n", srcpath, dstpath, dstuid);
        if (rename(srcpath, dstpath) >= 0) {
            if (chown(dstpath, dstuid, dstgid) < 0) {
                LOGE("cannot chown %s: %s\n", dstpath, strerror(errno));
                unlink(dstpath);
                return 1;
            }
        } else {
            LOGW("Unable to rename %s to %s: %s\n",
                srcpath, dstpath, strerror(errno));
            return 1;
        }
        return 0;
    }

    d = opendir(srcpath);
    if (d == NULL) {
        LOGW("Unable to opendir %s: %s\n", srcpath, strerror(errno));
        return 1;
    }

    res = 0;
    
    while ((de = readdir(d))) {
        const char *name = de->d_name;
            /* always skip "." and ".." */
        if (name[0] == '.') {
            if (name[1] == 0) continue;
            if ((name[1] == '.') && (name[2] == 0)) continue;
        }
        
        if ((srcend+strlen(name)) >= (PKG_PATH_MAX-2)) {
            LOGW("Source path too long; skipping: %s/%s\n", srcpath, name);
            continue;
        }
        
        if ((dstend+strlen(name)) >= (PKG_PATH_MAX-2)) {
            LOGW("Destination path too long; skipping: %s/%s\n", dstpath, name);
            continue;
        }
        
        srcpath[srcend] = dstpath[dstend] = '/';
        strcpy(srcpath+srcend+1, name);
        strcpy(dstpath+dstend+1, name);
        
        if (movefileordir(srcpath, dstpath, dstbasepos, dstuid, dstgid, statbuf) != 0) {
            res = 1;
        }
        
        // Note: we will be leaving empty directories behind in srcpath,
        // but that is okay, the package manager will be erasing all of the
        // data associated with .apks that disappear.
        
        srcpath[srcend] = dstpath[dstend] = 0;
    }
    
    closedir(d);
    return res;
}

int movefiles()
{
    DIR *d;
    int dfd, subfd;
    struct dirent *de;
    struct stat s;
    char buf[PKG_PATH_MAX+1];
    int bufp, bufe, bufi, readlen;

    char srcpkg[PKG_NAME_MAX];
    char dstpkg[PKG_NAME_MAX];
    char srcpath[PKG_PATH_MAX];
    char dstpath[PKG_PATH_MAX];
    int dstuid=-1, dstgid=-1;
    int hasspace;

    d = opendir(UPDATE_COMMANDS_DIR_PREFIX);
    if (d == NULL) {
        goto done;
    }
    dfd = dirfd(d);

        /* Iterate through all files in the directory, executing the
         * file movements requested there-in.
         */
    while ((de = readdir(d))) {
        const char *name = de->d_name;

        if (de->d_type == DT_DIR) {
            continue;
        } else {
            subfd = openat(dfd, name, O_RDONLY);
            if (subfd < 0) {
                LOGW("Unable to open update commands at %s%s\n",
                        UPDATE_COMMANDS_DIR_PREFIX, name);
                continue;
            }
            
            bufp = 0;
            bufe = 0;
            buf[PKG_PATH_MAX] = 0;
            srcpkg[0] = dstpkg[0] = 0;
            while (1) {
                bufi = bufp;
                while (bufi < bufe && buf[bufi] != '\n') {
                    bufi++;
                }
                if (bufi < bufe) {
                    buf[bufi] = 0;
                    LOGV("Processing line: %s\n", buf+bufp);
                    hasspace = 0;
                    while (bufp < bufi && isspace(buf[bufp])) {
                        hasspace = 1;
                        bufp++;
                    }
                    if (buf[bufp] == '#' || bufp == bufi) {
                        // skip comments and empty lines.
                    } else if (hasspace) {
                        if (dstpkg[0] == 0) {
                            LOGW("Path before package line in %s%s: %s\n",
                                    UPDATE_COMMANDS_DIR_PREFIX, name, buf+bufp);
                        } else if (srcpkg[0] == 0) {
                            // Skip -- source package no longer exists.
                        } else {
                            LOGV("Move file: %s (from %s to %s)\n", buf+bufp, srcpkg, dstpkg);
                            if (!create_move_path(srcpath, srcpkg, buf+bufp, 0) &&
                                    !create_move_path(dstpath, dstpkg, buf+bufp, 0)) {
                                movefileordir(srcpath, dstpath,
                                        strlen(dstpath)-strlen(buf+bufp),
                                        dstuid, dstgid, &s);
                            }
                        }
                    } else {
                        char* div = strchr(buf+bufp, ':');
                        if (div == NULL) {
                            LOGW("Bad package spec in %s%s; no ':' sep: %s\n",
                                    UPDATE_COMMANDS_DIR_PREFIX, name, buf+bufp);
                        } else {
                            *div = 0;
                            div++;
                            if (strlen(buf+bufp) < PKG_NAME_MAX) {
                                strcpy(dstpkg, buf+bufp);
                            } else {
                                srcpkg[0] = dstpkg[0] = 0;
                                LOGW("Package name too long in %s%s: %s\n",
                                        UPDATE_COMMANDS_DIR_PREFIX, name, buf+bufp);
                            }
                            if (strlen(div) < PKG_NAME_MAX) {
                                strcpy(srcpkg, div);
                            } else {
                                srcpkg[0] = dstpkg[0] = 0;
                                LOGW("Package name too long in %s%s: %s\n",
                                        UPDATE_COMMANDS_DIR_PREFIX, name, div);
                            }
                            if (srcpkg[0] != 0) {
                                if (!create_pkg_path(srcpath, srcpkg, PKG_DIR_POSTFIX, 0)) {
                                    if (lstat(srcpath, &s) < 0) {
                                        // Package no longer exists -- skip.
                                        srcpkg[0] = 0;
                                    }
                                } else {
                                    srcpkg[0] = 0;
                                    LOGW("Can't create path %s in %s%s\n",
                                            div, UPDATE_COMMANDS_DIR_PREFIX, name);
                                }
                                if (srcpkg[0] != 0) {
                                    if (!create_pkg_path(dstpath, dstpkg, PKG_DIR_POSTFIX, 0)) {
                                        if (lstat(dstpath, &s) == 0) {
                                            dstuid = s.st_uid;
                                            dstgid = s.st_gid;
                                        } else {
                                            // Destination package doesn't
                                            // exist...  due to original-package,
                                            // this is normal, so don't be
                                            // noisy about it.
                                            srcpkg[0] = 0;
                                        }
                                    } else {
                                        srcpkg[0] = 0;
                                        LOGW("Can't create path %s in %s%s\n",
                                                div, UPDATE_COMMANDS_DIR_PREFIX, name);
                                    }
                                }
                                LOGV("Transfering from %s to %s: uid=%d\n",
                                    srcpkg, dstpkg, dstuid);
                            }
                        }
                    }
                    bufp = bufi+1;
                } else {
                    if (bufp == 0) {
                        if (bufp < bufe) {
                            LOGW("Line too long in %s%s, skipping: %s\n",
                                    UPDATE_COMMANDS_DIR_PREFIX, name, buf);
                        }
                    } else if (bufp < bufe) {
                        memcpy(buf, buf+bufp, bufe-bufp);
                        bufe -= bufp;
                        bufp = 0;
                    }
                    readlen = read(subfd, buf+bufe, PKG_PATH_MAX-bufe);
                    if (readlen < 0) {
                        LOGW("Failure reading update commands in %s%s: %s\n",
                                UPDATE_COMMANDS_DIR_PREFIX, name, strerror(errno));
                        break;
                    } else if (readlen == 0) {
                        break;
                    }
                    bufe += readlen;
                    buf[bufe] = 0;
                    LOGV("Read buf: %s\n", buf);
                }
            }
            close(subfd);
        }
    }
    closedir(d);
done:
    return 0;
}

int linklib(const char* dataDir, const char* asecLibDir)
{
    char libdir[PKG_PATH_MAX];
    struct stat s, libStat;
    int rc = 0;

    const size_t libdirLen = strlen(dataDir) + strlen(PKG_LIB_POSTFIX);
    if (libdirLen >= PKG_PATH_MAX) {
        LOGE("library dir len too large");
        return -1;
    }

    if (snprintf(libdir, sizeof(libdir), "%s%s", dataDir, PKG_LIB_POSTFIX) != (ssize_t)libdirLen) {
        LOGE("library dir not written successfully: %s\n", strerror(errno));
        return -1;
    }

    if (stat(dataDir, &s) < 0) return -1;

    if (chown(dataDir, 0, 0) < 0) {
        LOGE("failed to chown '%s': %s\n", dataDir, strerror(errno));
        return -1;
    }

    if (chmod(dataDir, 0700) < 0) {
        LOGE("failed to chmod '%s': %s\n", dataDir, strerror(errno));
        rc = -1;
        goto out;
    }

    if (lstat(libdir, &libStat) < 0) {
        LOGE("couldn't stat lib dir: %s\n", strerror(errno));
        rc = -1;
        goto out;
    }

    if (S_ISDIR(libStat.st_mode)) {
        if (delete_dir_contents(libdir, 1, 0) < 0) {
            rc = -1;
            goto out;
        }
    } else if (S_ISLNK(libStat.st_mode)) {
        if (unlink(libdir) < 0) {
            rc = -1;
            goto out;
        }
    }

    if (symlink(asecLibDir, libdir) < 0) {
        LOGE("couldn't symlink directory '%s' -> '%s': %s\n", libdir, asecLibDir, strerror(errno));
        rc = -errno;
        goto out;
    }

    if (lchown(libdir, AID_SYSTEM, AID_SYSTEM) < 0) {
        LOGE("cannot chown dir '%s': %s\n", libdir, strerror(errno));
        unlink(libdir);
        rc = -errno;
        goto out;
    }

out:
    if (chmod(dataDir, s.st_mode) < 0) {
        LOGE("failed to chmod '%s': %s\n", dataDir, strerror(errno));
        return -errno;
    }

    if (chown(dataDir, s.st_uid, s.st_gid) < 0) {
        LOGE("failed to chown '%s' : %s\n", dataDir, strerror(errno));
        return -errno;
    }

    return rc;
}

int unlinklib(const char* dataDir)
{
    char libdir[PKG_PATH_MAX];
    struct stat s, libStat;
    int rc = 0;

    const size_t libdirLen = strlen(dataDir) + strlen(PKG_LIB_POSTFIX);
    if (libdirLen >= PKG_PATH_MAX) {
        return -1;
    }

    if (snprintf(libdir, sizeof(libdir), "%s%s", dataDir, PKG_LIB_POSTFIX) != (ssize_t)libdirLen) {
        LOGE("library dir not written successfully: %s\n", strerror(errno));
        return -1;
    }

    if (stat(dataDir, &s) < 0) {
        LOGE("couldn't state data dir");
        return -1;
    }

    if (chown(dataDir, 0, 0) < 0) {
        LOGE("failed to chown '%s': %s\n", dataDir, strerror(errno));
        return -1;
    }

    if (chmod(dataDir, 0700) < 0) {
        LOGE("failed to chmod '%s': %s\n", dataDir, strerror(errno));
        rc = -1;
        goto out;
    }

    if (lstat(libdir, &libStat) < 0) {
        LOGE("couldn't stat lib dir: %s\n", strerror(errno));
        rc = -1;
        goto out;
    }

    if (S_ISDIR(libStat.st_mode)) {
        if (delete_dir_contents(libdir, 1, 0) < 0) {
            rc = -1;
            goto out;
        }
    } else if (S_ISLNK(libStat.st_mode)) {
        if (unlink(libdir) < 0) {
            rc = -1;
            goto out;
        }
    }

    if (mkdir(libdir, 0755) < 0) {
        LOGE("cannot create dir '%s': %s\n", libdir, strerror(errno));
        rc = -errno;
        goto out;
    }

    if (chown(libdir, AID_SYSTEM, AID_SYSTEM) < 0) {
        LOGE("cannot chown dir '%s': %s\n", libdir, strerror(errno));
        unlink(libdir);
        rc = -errno;
        goto out;
    }

out:
    if (chmod(dataDir, s.st_mode) < 0) {
        LOGE("failed to chmod '%s': %s\n", dataDir, strerror(errno));
        return -1;
    }

    if (chown(dataDir, s.st_uid, s.st_gid) < 0) {
        LOGE("failed to chown '%s' : %s\n", dataDir, strerror(errno));
        return -1;
    }

    return rc;
}
