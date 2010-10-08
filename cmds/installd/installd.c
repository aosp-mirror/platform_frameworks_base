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


#define BUFFER_MAX    1024  /* input buffer for commands */
#define TOKEN_MAX     8     /* max number of arguments in buffer */
#define REPLY_MAX     256   /* largest reply allowed */


static int do_ping(char **arg, char reply[REPLY_MAX])
{
    return 0;
}

static int do_install(char **arg, char reply[REPLY_MAX])
{
    return install(arg[0], atoi(arg[1]), atoi(arg[2]), atoi(arg[3])); /* pkgname, uid, gid */
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
    return uninstall(arg[0], atoi(arg[1])); /* pkgname */
}

static int do_rename(char **arg, char reply[REPLY_MAX])
{
    return renamepkg(arg[0], arg[1], atoi(arg[2])); /* oldpkgname, newpkgname */
}

static int do_free_cache(char **arg, char reply[REPLY_MAX]) /* TODO int:free_size */
{
    return free_cache((int64_t)atoll(arg[0])); /* free_size */
}

static int do_rm_cache(char **arg, char reply[REPLY_MAX])
{
    return delete_cache(arg[0], atoi(arg[1])); /* pkgname */
}

static int do_protect(char **arg, char reply[REPLY_MAX])
{
    return protect(arg[0], atoi(arg[1])); /* pkgname, gid */
}

static int do_get_size(char **arg, char reply[REPLY_MAX])
{
    int64_t codesize = 0;
    int64_t datasize = 0;
    int64_t cachesize = 0;
    int res = 0;

        /* pkgdir, apkpath */
    res = get_size(arg[0], arg[1], arg[2], &codesize, &datasize, &cachesize, atoi(arg[3]));

    /*
     * Each int64_t can take up 22 characters printed out. Make sure it
     * doesn't go over REPLY_MAX in the future.
     */
    snprintf(reply, REPLY_MAX, "%" PRId64 " %" PRId64 " %" PRId64, codesize, datasize, cachesize);
    return res;
}

static int do_rm_user_data(char **arg, char reply[REPLY_MAX])
{
    return delete_user_data(arg[0], atoi(arg[1])); /* pkgname */
}

static int do_movefiles(char **arg, char reply[REPLY_MAX])
{
    return movefiles();
}

static int do_linklib(char **arg, char reply[REPLY_MAX])
{
    return linklib(arg[0], arg[1]);
}

static int do_unlinklib(char **arg, char reply[REPLY_MAX])
{
    return unlinklib(arg[0]);
}

struct cmdinfo {
    const char *name;
    unsigned numargs;
    int (*func)(char **arg, char reply[REPLY_MAX]);
};

struct cmdinfo cmds[] = {
    { "ping",                 0, do_ping },
    { "install",              4, do_install },
    { "dexopt",               3, do_dexopt },
    { "movedex",              2, do_move_dex },
    { "rmdex",                1, do_rm_dex },
    { "remove",               2, do_remove },
    { "rename",               3, do_rename },
    { "freecache",            1, do_free_cache },
    { "rmcache",              2, do_rm_cache },
    { "protect",              2, do_protect },
    { "getsize",              4, do_get_size },
    { "rmuserdata",           2, do_rm_user_data },
    { "movefiles",            0, do_movefiles },
    { "linklib",              2, do_linklib },
    { "unlinklib",            1, do_unlinklib },
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
            LOGE("read error: %s\n", strerror(errno));
            return -1;
        }
        if (r == 0) {
            LOGE("eof\n");
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
            LOGE("write error: %s\n", strerror(errno));
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

//    LOGI("execute('%s')\n", cmd);

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
                LOGE("too many arguments\n");
                goto done;
            }
        }
        cmd++;
    }

    for (i = 0; i < sizeof(cmds) / sizeof(cmds[0]); i++) {
        if (!strcmp(cmds[i].name,arg[0])) {
            if (n != cmds[i].numargs) {
                LOGE("%s requires %d arguments (%d given)\n",
                     cmds[i].name, cmds[i].numargs, n);
            } else {
                ret = cmds[i].func(arg + 1, reply);
            }
            goto done;
        }
    }
    LOGE("unsupported command '%s'\n", arg[0]);

done:
    if (reply[0]) {
        n = snprintf(cmd, BUFFER_MAX, "%d %s", ret, reply);
    } else {
        n = snprintf(cmd, BUFFER_MAX, "%d", ret);
    }
    if (n > BUFFER_MAX) n = BUFFER_MAX;
    count = n;

//    LOGI("reply: '%s'\n", cmd);
    if (writex(s, &count, sizeof(count))) return -1;
    if (writex(s, cmd, count)) return -1;
    return 0;
}

int main(const int argc, const char *argv[]) {    
    char buf[BUFFER_MAX];
    struct sockaddr addr;
    socklen_t alen;
    int lsocket, s, count;

    lsocket = android_get_control_socket(SOCKET_PATH);
    if (lsocket < 0) {
        LOGE("Failed to get socket from environment: %s\n", strerror(errno));
        exit(1);
    }
    if (listen(lsocket, 5)) {
        LOGE("Listen on socket failed: %s\n", strerror(errno));
        exit(1);
    }
    fcntl(lsocket, F_SETFD, FD_CLOEXEC);

    for (;;) {
        alen = sizeof(addr);
        s = accept(lsocket, &addr, &alen);
        if (s < 0) {
            LOGE("Accept failed: %s\n", strerror(errno));
            continue;
        }
        fcntl(s, F_SETFD, FD_CLOEXEC);

        LOGI("new connection\n");
        for (;;) {
            unsigned short count;
            if (readx(s, &count, sizeof(count))) {
                LOGE("failed to read size\n");
                break;
            }
            if ((count < 1) || (count >= BUFFER_MAX)) {
                LOGE("invalid size %d\n", count);
                break;
            }
            if (readx(s, buf, count)) {
                LOGE("failed to read command\n");
                break;
            }
            buf[count] = 0;
            if (execute(s, buf)) break;
        }
        LOGI("closing connection\n");
        close(s);
    }

    return 0;
}
