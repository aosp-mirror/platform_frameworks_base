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

#include <linux/capability.h>
#include <linux/prctl.h>

#include "installd.h"


#define BUFFER_MAX    1024  /* input buffer for commands */
#define TOKEN_MAX     8     /* max number of arguments in buffer */
#define REPLY_MAX     256   /* largest reply allowed */

static int do_ping(char **arg, char reply[REPLY_MAX])
{
    return 0;
}

static int do_install(char **arg, char reply[REPLY_MAX])
{
    return install(arg[0], atoi(arg[1]), atoi(arg[2])); /* pkgname, uid, gid */
}

static int do_dexopt(char **arg, char reply[REPLY_MAX])
{
        /* apk_path, uid, is_public */
    return dexopt(arg[0], atoi(arg[1]), atoi(arg[2]));
}

static int do_move_dex(char **arg, char reply[REPLY_MAX])
{
    return move_dex(arg[0], arg[1]); /* src, dst */
}

static int do_rm_dex(char **arg, char reply[REPLY_MAX])
{
    return rm_dex(arg[0]); /* pkgname */
}

static int do_remove(char **arg, char reply[REPLY_MAX])
{
    return uninstall(arg[0], atoi(arg[1])); /* pkgname, userid */
}

static int do_rename(char **arg, char reply[REPLY_MAX])
{
    return renamepkg(arg[0], arg[1]); /* oldpkgname, newpkgname */
}

static int do_fixuid(char **arg, char reply[REPLY_MAX])
{
    return fix_uid(arg[0], atoi(arg[1]), atoi(arg[2])); /* pkgname, uid, gid */
}

static int do_free_cache(char **arg, char reply[REPLY_MAX]) /* TODO int:free_size */
{
    return free_cache((int64_t)atoll(arg[0])); /* free_size */
}

static int do_rm_cache(char **arg, char reply[REPLY_MAX])
{
    return delete_cache(arg[0], atoi(arg[1])); /* pkgname, userid */
}

static int do_get_size(char **arg, char reply[REPLY_MAX])
{
    int64_t codesize = 0;
    int64_t datasize = 0;
    int64_t cachesize = 0;
    int64_t asecsize = 0;
    int res = 0;

        /* pkgdir, persona, apkpath */
    res = get_size(arg[0], atoi(arg[1]), arg[2], arg[3], arg[4],
            &codesize, &datasize, &cachesize, &asecsize);

    /*
     * Each int64_t can take up 22 characters printed out. Make sure it
     * doesn't go over REPLY_MAX in the future.
     */
    snprintf(reply, REPLY_MAX, "%" PRId64 " %" PRId64 " %" PRId64 " %" PRId64,
            codesize, datasize, cachesize, asecsize);
    return res;
}

static int do_rm_user_data(char **arg, char reply[REPLY_MAX])
{
    return delete_user_data(arg[0], atoi(arg[1])); /* pkgname, userid */
}

static int do_mk_user_data(char **arg, char reply[REPLY_MAX])
{
    return make_user_data(arg[0], atoi(arg[1]), atoi(arg[2])); /* pkgname, uid, userid */
}

static int do_rm_user(char **arg, char reply[REPLY_MAX])
{
    return delete_persona(atoi(arg[0])); /* userid */
}

static int do_clone_user_data(char **arg, char reply[REPLY_MAX])
{
    return clone_persona_data(atoi(arg[0]), atoi(arg[1]), atoi(arg[2]));
}

static int do_movefiles(char **arg, char reply[REPLY_MAX])
{
    return movefiles();
}

static int do_linklib(char **arg, char reply[REPLY_MAX])
{
    return linklib(arg[0], arg[1], atoi(arg[2]));
}

struct cmdinfo {
    const char *name;
    unsigned numargs;
    int (*func)(char **arg, char reply[REPLY_MAX]);
};

struct cmdinfo cmds[] = {
    { "ping",                 0, do_ping },
    { "install",              3, do_install },
    { "dexopt",               3, do_dexopt },
    { "movedex",              2, do_move_dex },
    { "rmdex",                1, do_rm_dex },
    { "remove",               2, do_remove },
    { "rename",               2, do_rename },
    { "fixuid",               3, do_fixuid },
    { "freecache",            1, do_free_cache },
    { "rmcache",              2, do_rm_cache },
    { "getsize",              5, do_get_size },
    { "rmuserdata",           2, do_rm_user_data },
    { "movefiles",            0, do_movefiles },
    { "linklib",              3, do_linklib },
    { "mkuserdata",           3, do_mk_user_data },
    { "rmuser",               1, do_rm_user },
    { "cloneuserdata",        3, do_clone_user_data },
};

static int readx(int s, void *_buf, int count)
{
    char *buf = _buf;
    int n = 0, r;
    if (count < 0) return -1;
    while (n < count) {
        r = read(s, buf + n, count - n);
        if (r < 0) {
            if (errno == EINTR) continue;
            ALOGE("read error: %s\n", strerror(errno));
            return -1;
        }
        if (r == 0) {
            ALOGE("eof\n");
            return -1; /* EOF */
        }
        n += r;
    }
    return 0;
}

static int writex(int s, const void *_buf, int count)
{
    const char *buf = _buf;
    int n = 0, r;
    if (count < 0) return -1;
    while (n < count) {
        r = write(s, buf + n, count - n);
        if (r < 0) {
            if (errno == EINTR) continue;
            ALOGE("write error: %s\n", strerror(errno));
            return -1;
        }
        n += r;
    }
    return 0;
}


/* Tokenize the command buffer, locate a matching command,
 * ensure that the required number of arguments are provided,
 * call the function(), return the result.
 */
static int execute(int s, char cmd[BUFFER_MAX])
{
    char reply[REPLY_MAX];
    char *arg[TOKEN_MAX+1];
    unsigned i;
    unsigned n = 0;
    unsigned short count;
    int ret = -1;

//    ALOGI("execute('%s')\n", cmd);

        /* default reply is "" */
    reply[0] = 0;

        /* n is number of args (not counting arg[0]) */
    arg[0] = cmd;
    while (*cmd) {
        if (isspace(*cmd)) {
            *cmd++ = 0;
            n++;
            arg[n] = cmd;
            if (n == TOKEN_MAX) {
                ALOGE("too many arguments\n");
                goto done;
            }
        }
        cmd++;
    }

    for (i = 0; i < sizeof(cmds) / sizeof(cmds[0]); i++) {
        if (!strcmp(cmds[i].name,arg[0])) {
            if (n != cmds[i].numargs) {
                ALOGE("%s requires %d arguments (%d given)\n",
                     cmds[i].name, cmds[i].numargs, n);
            } else {
                ret = cmds[i].func(arg + 1, reply);
            }
            goto done;
        }
    }
    ALOGE("unsupported command '%s'\n", arg[0]);

done:
    if (reply[0]) {
        n = snprintf(cmd, BUFFER_MAX, "%d %s", ret, reply);
    } else {
        n = snprintf(cmd, BUFFER_MAX, "%d", ret);
    }
    if (n > BUFFER_MAX) n = BUFFER_MAX;
    count = n;

//    ALOGI("reply: '%s'\n", cmd);
    if (writex(s, &count, sizeof(count))) return -1;
    if (writex(s, cmd, count)) return -1;
    return 0;
}

/**
 * Initialize all the global variables that are used elsewhere. Returns 0 upon
 * success and -1 on error.
 */
void free_globals() {
    size_t i;

    for (i = 0; i < android_system_dirs.count; i++) {
        if (android_system_dirs.dirs[i].path != NULL) {
            free(android_system_dirs.dirs[i].path);
        }
    }

    free(android_system_dirs.dirs);
}

int initialize_globals() {
    // Get the android data directory.
    if (get_path_from_env(&android_data_dir, "ANDROID_DATA") < 0) {
        return -1;
    }

    // Get the android app directory.
    if (copy_and_append(&android_app_dir, &android_data_dir, APP_SUBDIR) < 0) {
        return -1;
    }

    // Get the android protected app directory.
    if (copy_and_append(&android_app_private_dir, &android_data_dir, PRIVATE_APP_SUBDIR) < 0) {
        return -1;
    }

    // Get the android app native library directory.
    if (copy_and_append(&android_app_lib_dir, &android_data_dir, APP_LIB_SUBDIR) < 0) {
        return -1;
    }

    // Get the sd-card ASEC mount point.
    if (get_path_from_env(&android_asec_dir, "ASEC_MOUNTPOINT") < 0) {
        return -1;
    }

    // Get the android media directory.
    if (copy_and_append(&android_media_dir, &android_data_dir, MEDIA_SUBDIR) < 0) {
        return -1;
    }

    // Take note of the system and vendor directories.
    android_system_dirs.count = 2;

    android_system_dirs.dirs = calloc(android_system_dirs.count, sizeof(dir_rec_t));
    if (android_system_dirs.dirs == NULL) {
        ALOGE("Couldn't allocate array for dirs; aborting\n");
        return -1;
    }

    // system
    if (get_path_from_env(&android_system_dirs.dirs[0], "ANDROID_ROOT") < 0) {
        free_globals();
        return -1;
    }

    // append "app/" to dirs[0]
    char *system_app_path = build_string2(android_system_dirs.dirs[0].path, APP_SUBDIR);
    android_system_dirs.dirs[0].path = system_app_path;
    android_system_dirs.dirs[0].len = strlen(system_app_path);

    // vendor
    // TODO replace this with an environment variable (doesn't exist yet)
    android_system_dirs.dirs[1].path = "/vendor/app/";
    android_system_dirs.dirs[1].len = strlen(android_system_dirs.dirs[1].path);

    return 0;
}

int initialize_directories() {
    int res = -1;

    // Read current filesystem layout version to handle upgrade paths
    char version_path[PATH_MAX];
    snprintf(version_path, PATH_MAX, "%s.layout_version", android_data_dir.path);

    int oldVersion;
    if (fs_read_atomic_int(version_path, &oldVersion) == -1) {
        oldVersion = 0;
    }
    int version = oldVersion;

    // /data/user
    char *user_data_dir = build_string2(android_data_dir.path, SECONDARY_USER_PREFIX);
    // /data/data
    char *legacy_data_dir = build_string2(android_data_dir.path, PRIMARY_USER_PREFIX);
    // /data/user/0
    char *primary_data_dir = build_string3(android_data_dir.path, SECONDARY_USER_PREFIX, "0");
    if (!user_data_dir || !legacy_data_dir || !primary_data_dir) {
        goto fail;
    }

    // Make the /data/user directory if necessary
    if (access(user_data_dir, R_OK) < 0) {
        if (mkdir(user_data_dir, 0711) < 0) {
            goto fail;
        }
        if (chown(user_data_dir, AID_SYSTEM, AID_SYSTEM) < 0) {
            goto fail;
        }
        if (chmod(user_data_dir, 0711) < 0) {
            goto fail;
        }
    }
    // Make the /data/user/0 symlink to /data/data if necessary
    if (access(primary_data_dir, R_OK) < 0) {
        if (symlink(legacy_data_dir, primary_data_dir)) {
            goto fail;
        }
    }

    if (version == 0) {
        // Introducing multi-user, so migrate /data/media contents into /data/media/0
        ALOGD("Upgrading /data/media for multi-user");

        // Ensure /data/media
        if (fs_prepare_dir(android_media_dir.path, 0770, AID_MEDIA_RW, AID_MEDIA_RW) == -1) {
            goto fail;
        }

        // /data/media.tmp
        char media_tmp_dir[PATH_MAX];
        snprintf(media_tmp_dir, PATH_MAX, "%smedia.tmp", android_data_dir.path);

        // Only copy when upgrade not already in progress
        if (access(media_tmp_dir, F_OK) == -1) {
            if (rename(android_media_dir.path, media_tmp_dir) == -1) {
                ALOGE("Failed to move legacy media path: %s", strerror(errno));
                goto fail;
            }
        }

        // Create /data/media again
        if (fs_prepare_dir(android_media_dir.path, 0770, AID_MEDIA_RW, AID_MEDIA_RW) == -1) {
            goto fail;
        }

        // /data/media/0
        char owner_media_dir[PATH_MAX];
        snprintf(owner_media_dir, PATH_MAX, "%s0", android_media_dir.path);

        // Move any owner data into place
        if (access(media_tmp_dir, F_OK) == 0) {
            if (rename(media_tmp_dir, owner_media_dir) == -1) {
                ALOGE("Failed to move owner media path: %s", strerror(errno));
                goto fail;
            }
        }

        // Ensure media directories for any existing users
        DIR *dir;
        struct dirent *dirent;
        char user_media_dir[PATH_MAX];

        dir = opendir(user_data_dir);
        if (dir != NULL) {
            while ((dirent = readdir(dir))) {
                if (dirent->d_type == DT_DIR) {
                    const char *name = dirent->d_name;

                    // skip "." and ".."
                    if (name[0] == '.') {
                        if (name[1] == 0) continue;
                        if ((name[1] == '.') && (name[2] == 0)) continue;
                    }

                    // /data/media/<user_id>
                    snprintf(user_media_dir, PATH_MAX, "%s%s", android_media_dir.path, name);
                    if (fs_prepare_dir(user_media_dir, 0770, AID_MEDIA_RW, AID_MEDIA_RW) == -1) {
                        goto fail;
                    }
                }
            }
            closedir(dir);
        }

        version = 1;
    }

    // /data/media/obb
    char media_obb_dir[PATH_MAX];
    snprintf(media_obb_dir, PATH_MAX, "%sobb", android_media_dir.path);

    if (version == 1) {
        // Introducing /data/media/obb for sharing OBB across users; migrate
        // any existing OBB files from owner.
        ALOGD("Upgrading to shared /data/media/obb");

        // /data/media/0/Android/obb
        char owner_obb_path[PATH_MAX];
        snprintf(owner_obb_path, PATH_MAX, "%s0/Android/obb", android_media_dir.path);

        // Only move if target doesn't already exist
        if (access(media_obb_dir, F_OK) != 0 && access(owner_obb_path, F_OK) == 0) {
            if (rename(owner_obb_path, media_obb_dir) == -1) {
                ALOGE("Failed to move OBB from owner: %s", strerror(errno));
                goto fail;
            }
        }

        version = 2;
    }

    if (ensure_media_user_dirs(0) == -1) {
        ALOGE("Failed to setup media for user 0");
        goto fail;
    }
    if (fs_prepare_dir(media_obb_dir, 0770, AID_MEDIA_RW, AID_MEDIA_RW) == -1) {
        goto fail;
    }

    // Persist layout version if changed
    if (version != oldVersion) {
        if (fs_write_atomic_int(version_path, version) == -1) {
            ALOGE("Failed to save version to %s: %s", version_path, strerror(errno));
            goto fail;
        }
    }

    // Success!
    res = 0;

fail:
    free(user_data_dir);
    free(legacy_data_dir);
    free(primary_data_dir);
    return res;
}

static void drop_privileges() {
    if (prctl(PR_SET_KEEPCAPS, 1) < 0) {
        ALOGE("prctl(PR_SET_KEEPCAPS) failed: %s\n", strerror(errno));
        exit(1);
    }

    if (setgid(AID_INSTALL) < 0) {
        ALOGE("setgid() can't drop privileges; exiting.\n");
        exit(1);
    }

    if (setuid(AID_INSTALL) < 0) {
        ALOGE("setuid() can't drop privileges; exiting.\n");
        exit(1);
    }

    struct __user_cap_header_struct capheader;
    struct __user_cap_data_struct capdata[2];
    memset(&capheader, 0, sizeof(capheader));
    memset(&capdata, 0, sizeof(capdata));
    capheader.version = _LINUX_CAPABILITY_VERSION_3;
    capheader.pid = 0;

    capdata[CAP_TO_INDEX(CAP_DAC_OVERRIDE)].permitted |= CAP_TO_MASK(CAP_DAC_OVERRIDE);
    capdata[CAP_TO_INDEX(CAP_CHOWN)].permitted        |= CAP_TO_MASK(CAP_CHOWN);
    capdata[CAP_TO_INDEX(CAP_SETUID)].permitted       |= CAP_TO_MASK(CAP_SETUID);
    capdata[CAP_TO_INDEX(CAP_SETGID)].permitted       |= CAP_TO_MASK(CAP_SETGID);

    capdata[0].effective = capdata[0].permitted;
    capdata[1].effective = capdata[1].permitted;
    capdata[0].inheritable = 0;
    capdata[1].inheritable = 0;

    if (capset(&capheader, &capdata[0]) < 0) {
        ALOGE("capset failed: %s\n", strerror(errno));
        exit(1);
    }
}

int main(const int argc, const char *argv[]) {
    char buf[BUFFER_MAX];
    struct sockaddr addr;
    socklen_t alen;
    int lsocket, s, count;

    ALOGI("installd firing up\n");

    if (initialize_globals() < 0) {
        ALOGE("Could not initialize globals; exiting.\n");
        exit(1);
    }

    if (initialize_directories() < 0) {
        ALOGE("Could not create directories; exiting.\n");
        exit(1);
    }

    drop_privileges();

    lsocket = android_get_control_socket(SOCKET_PATH);
    if (lsocket < 0) {
        ALOGE("Failed to get socket from environment: %s\n", strerror(errno));
        exit(1);
    }
    if (listen(lsocket, 5)) {
        ALOGE("Listen on socket failed: %s\n", strerror(errno));
        exit(1);
    }
    fcntl(lsocket, F_SETFD, FD_CLOEXEC);

    for (;;) {
        alen = sizeof(addr);
        s = accept(lsocket, &addr, &alen);
        if (s < 0) {
            ALOGE("Accept failed: %s\n", strerror(errno));
            continue;
        }
        fcntl(s, F_SETFD, FD_CLOEXEC);

        ALOGI("new connection\n");
        for (;;) {
            unsigned short count;
            if (readx(s, &count, sizeof(count))) {
                ALOGE("failed to read size\n");
                break;
            }
            if ((count < 1) || (count >= BUFFER_MAX)) {
                ALOGE("invalid size %d\n", count);
                break;
            }
            if (readx(s, buf, count)) {
                ALOGE("failed to read command\n");
                break;
            }
            buf[count] = 0;
            if (execute(s, buf)) break;
        }
        ALOGI("closing connection\n");
        close(s);
    }

    return 0;
}
