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


static int do_list_certs(char **arg, char reply[REPLY_MAX])
{
    return list_certs(reply);
}

static int do_list_userkeys(char **arg, char reply[REPLY_MAX])
{
    return list_userkeys(reply);
}

static int do_install_cert(char **arg, char reply[REPLY_MAX])
{
    return install_cert(arg[0]); /* move the certificate to keystore */
}

static int do_remove_cert(char **arg, char reply[REPLY_MAX])
{
    return remove_cert(arg[0]); /* certificate */
}

static int do_install_userkey(char **arg, char reply[REPLY_MAX])
{
    return install_userkey(arg[0]); /* move the certificate to keystore */
}

static int do_remove_userkey(char **arg, char reply[REPLY_MAX])
{
    return remove_userkey(arg[0]); /* userkey */
}

struct cmdinfo {
    const char *name;
    unsigned numargs;
    int (*func)(char **arg, char reply[REPLY_MAX]);
};


struct cmdinfo cmds[] = {
    { "listcerts",            0, do_list_certs },
    { "listuserkeys",         0, do_list_userkeys },
    { "installcert",          1, do_install_cert },
    { "removecert",           1, do_remove_cert },
    { "installuserkey",       1, do_install_userkey },
    { "removeuserkey",        1, do_remove_userkey },
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
    short ret = -1;

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
                ret = (short) cmds[i].func(arg + 1, reply);
            }
            goto done;
        }
    }
    LOGE("unsupported command '%s'\n", arg[0]);

done:
    if (reply[0]) {
        strlcpy(cmd, reply, BUFFER_MAX);
        count = strlen(cmd);
    } else {
        count = 0;
    }
    if (writex(s, &ret, sizeof(ret))) return -1;
    if (ret == 0) {
        if (writex(s, &count, sizeof(count))) return -1;
        if (writex(s, cmd, count)) return -1;
    }

    return 0;
}

int shell_command(const int argc, const char **argv)
{
    int fd, i;
    short ret;
    unsigned short count;
    char buf[BUFFER_MAX]="";

    fd = socket_local_client(SOCKET_PATH,
                             ANDROID_SOCKET_NAMESPACE_RESERVED,
                             SOCK_STREAM);
    if (fd == -1) {
        fprintf(stderr, "Keystore service is not up and running\n");
        exit(1);
    }
    for(i = 0; i < argc; i++) {
        if (i > 0) strlcat(buf, " ", BUFFER_MAX);
        if(strlcat(buf, argv[i], BUFFER_MAX) >= BUFFER_MAX) {
            fprintf(stderr, "Arguments are too long\n");
            exit(1);
        }
    }
    count = strlen(buf);
    if (writex(fd, &count, sizeof(count))) return -1;
    if (writex(fd, buf, strlen(buf))) return -1;
    if (readx(fd, &ret, sizeof(ret))) return -1;
    if (ret == 0) {
        if (readx(fd, &count, sizeof(count))) return -1;
        if (readx(fd, buf, count)) return -1;
        buf[count]=0;
        fprintf(stdout, "%s\n", buf);
    } else {
        fprintf(stderr, "Failed, please check log!\n");
    }
    return 0;
}

int main(const int argc, const char *argv[])
{
    char buf[BUFFER_MAX];
    struct sockaddr addr;
    socklen_t alen;
    int lsocket, s, count;

    if (argc > 1) {
        return shell_command(argc - 1, argv + 1);
    }

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
