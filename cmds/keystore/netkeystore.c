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

#include <stdio.h>
#include <stdlib.h>
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
#include <private/android_filesystem_config.h>

#include <cutils/sockets.h>
#include <cutils/log.h>
#include <cutils/properties.h>

#include "netkeystore.h"
#include "keymgmt.h"

#define  DBG  1
#define  CMD_PUT_WITH_FILE  "putfile"

typedef void CMD_FUNC(LPC_MARSHAL *cmd, LPC_MARSHAL *reply);

struct cmdinfo {
    const char *name;
    CMD_FUNC *func;
};

static CMD_FUNC do_lock;
static CMD_FUNC do_unlock;
static CMD_FUNC do_passwd;
static CMD_FUNC do_get_state;;
static CMD_FUNC do_listkeys;
static CMD_FUNC do_get_key;
static CMD_FUNC do_put_key;
static CMD_FUNC do_remove_key;
static CMD_FUNC do_reset_keystore;

#define str(x)      #x

struct cmdinfo cmds[] = {
    { str(LOCK),           do_lock },
    { str(UNLOCK),         do_unlock },
    { str(PASSWD),         do_passwd },
    { str(GETSTATE),       do_get_state },
    { str(LISTKEYS),       do_listkeys },
    { str(GET),            do_get_key },
    { str(PUT),            do_put_key },
    { str(REMOVE),         do_remove_key },
    { str(RESET),          do_reset_keystore },
};

static  struct ucred cr;

static int check_get_perm(int uid)
{
    if (uid == AID_WIFI || uid == AID_VPN) return 0;
    return -1;
}

static int check_reset_perm(int uid)
{
    if (uid == AID_SYSTEM) return 0;
    return -1;
}

/**
 * The function parse_strings() only handle two or three tokens just for
 * keystore's need.
 */
static int parse_strings(char *data, int data_len, int ntokens, ...)
{
    int count = 0;
    va_list args;
    char *p = data, **q;

    va_start(args, ntokens);
    q = va_arg(args, char**);
    *q = p;
    while (p < (data + data_len)) {
        if (*(p++) == 0) {
            if (++count == ntokens) break;
            if ((q = va_arg(args, char**)) == NULL) break;
            *q = p;
        }
    }
    va_end(args);
    // the first two strings should be null-terminated and the third could
    // ignore the delimiter.
    if (count >= 2) {
        if ((ntokens == 3) || ((ntokens == 2) && (p == (data + data_len)))) {
            return 0;
        }
    }
    return -1;
}

static int is_alnum_string(char *s)
{
    while (*s != 0) {
        if (!isalnum(*s++)) return 0;
    }
    LOGE("The string %s is not an alphanumeric string\n", s);
    return 1;
}

// args of passwd():
// firstPassword - for the first time
// oldPassword newPassword - for changing the password
static void do_passwd(LPC_MARSHAL *cmd, LPC_MARSHAL *reply)
{
    char *p1 = NULL, *p2 = NULL;

    if (strlen((char*)cmd->data) == (cmd->len - 1)) {
        reply->retcode = new_passwd((char*)cmd->data);
    } else {
        if (parse_strings((char *)cmd->data, cmd->len, 2, &p1, &p2) != 0) {
            reply->retcode = -1;
        } else {
            reply->retcode = change_passwd(p1, p2);
        }
    }
}

// args of lock():
// no argument
static void do_lock(LPC_MARSHAL *cmd, LPC_MARSHAL *reply)
{
    reply->retcode = lock();
}

// args of unlock():
// password
static void do_unlock(LPC_MARSHAL *cmd, LPC_MARSHAL *reply)
{
    reply->retcode = unlock((char*)cmd->data);
}

// args of get_state():
// no argument
static void do_get_state(LPC_MARSHAL *cmd, LPC_MARSHAL *reply)
{
    reply->retcode = get_state();
}

// args of listkeys():
// namespace
static void do_listkeys(LPC_MARSHAL *cmd, LPC_MARSHAL *reply)
{
    reply->retcode = list_keys((const char*)cmd->data, (char*)reply->data);
    if (!reply->retcode) reply->len = strlen((char*)reply->data);
}

// args of get():
// namespace keyname
static void do_get_key(LPC_MARSHAL *cmd, LPC_MARSHAL *reply)
{
    char *namespace = NULL, *keyname = NULL;

    if (check_get_perm(cr.uid)) {
        LOGE("uid %d doesn't have the permission to get key value\n", cr.uid);
        reply->retcode = -1;
        return;
    }

    if (parse_strings((char*)cmd->data, cmd->len, 2, &namespace, &keyname) ||
        !is_alnum_string(namespace) || !is_alnum_string(keyname)) {
        reply->retcode = -1;
    } else {
        reply->retcode = get_key(namespace, keyname, reply->data,
                                 (int*)&reply->len);
    }
}

static int get_value_index(LPC_MARSHAL *cmd)
{
    uint32_t count = 0, i;
    for (i = 0 ; i < cmd->len ; ++i) {
        if (cmd->data[i] == ' ') {
            if (++count == 2) return ++i;
        }
    }
    return -1;
}

// args of put():
// namespace keyname keyvalue
static void do_put_key(LPC_MARSHAL *cmd, LPC_MARSHAL *reply)
{
    char *namespace = NULL, *keyname = NULL;
    char *value = NULL;

    if (parse_strings((char*)cmd->data, cmd->len, 3, &namespace, &keyname, &value) ||
        !is_alnum_string(namespace) || !is_alnum_string(keyname)) {
        reply->retcode = -1;
        return;
    }
    int len = cmd->len - (value - namespace);
    reply->retcode = put_key(namespace, keyname, (unsigned char *)value, len);
}

// args of remove_key():
// namespace keyname
static void do_remove_key(LPC_MARSHAL *cmd, LPC_MARSHAL *reply)
{
    char *namespace = NULL, *keyname = NULL;

    if (parse_strings((char*)cmd->data, cmd->len, 2, &namespace, &keyname) ||
        !is_alnum_string(namespace) || !is_alnum_string(keyname)) {
        reply->retcode = -1;
        return;
    }
    reply->retcode = remove_key(namespace, keyname);
}

// args of reset_keystore():
// no argument
static void do_reset_keystore(LPC_MARSHAL *cmd, LPC_MARSHAL *reply)
{
    if (check_reset_perm(cr.uid)) {
        LOGE("uid %d doesn't have the permission to reset the keystore\n",
             cr.uid);
        reply->retcode = -1;
        return;
    }
    reply->retcode = reset_keystore();
}

void execute(LPC_MARSHAL *cmd, LPC_MARSHAL *reply)
{
    uint32_t cmd_max = sizeof(cmds)/sizeof(struct cmdinfo);

    if (cmd->opcode >= cmd_max) {
        LOGE("the opcode (%d) is not valid", cmd->opcode);
        reply->retcode = -1;
        return;
    }
    cmds[cmd->opcode].func(cmd, reply);
}

static int set_read_timeout(int socket)
{
    struct timeval tv;
    tv.tv_sec = READ_TIMEOUT;
    tv.tv_usec = 0;
    if (setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, (char *)&tv,  sizeof tv))
    {
        LOGE("setsockopt failed");
        return -1;
    }
    return 0;
}

static int append_input_from_file(const char *filename, LPC_MARSHAL *cmd)
{
    int fd, len, ret = 0;

    // get opcode of the function put()
    if ((fd = open(filename, O_RDONLY)) == -1) {
        fprintf(stderr, "Can not open file %s\n", filename);
        return -1;
    }
    len = read(fd, cmd->data + cmd->len, BUFFER_MAX - cmd->len);
    if (len < 0 || (len == (int)(BUFFER_MAX - cmd->len))) {
        ret = -1;
    } else {
        cmd->len += len;
    }
    close(fd);
    return ret;
}

static int flatten_str_args(int argc, const char **argv, LPC_MARSHAL *cmd)
{
    int i, len = 0;
    char *buf = (char*)cmd->data;
    buf[0] = 0;
    for (i = 0 ; i < argc ; ++i) {
        // we also include the \0 character in the input.
        if (i == 0) {
            len = (strlcpy(buf, argv[i], BUFFER_MAX) + 1);
        } else {
            len += (snprintf(buf + len, BUFFER_MAX - len, "%s", argv[i]) + 1);
        }
        if (len >= BUFFER_MAX) return -1;
    }
    if (len) cmd->len = len ;
    return 0;
}

int parse_cmd(int argc, const char **argv, LPC_MARSHAL *cmd)
{
    uint32_t i, len = 0;
    uint32_t cmd_max = sizeof(cmds)/sizeof(cmds[0]);

    for (i = 0 ; i < cmd_max ; ++i) {
        if (!strcasecmp(argv[0], cmds[i].name)) break;
    }

    if (i == cmd_max) {
        // check if this is a command to put the key value with a file.
        if (strcmp(argv[0], CMD_PUT_WITH_FILE) != 0) return -1;
        cmd->opcode = PUT;
        if (argc != 4) {
            fprintf(stderr, "%s args\n\tnamespace keyname filename\n",
                    argv[0]);
            return -1;
        }
        if (flatten_str_args(argc - 2, argv + 1, cmd)) return -1;
        return append_input_from_file(argv[3], cmd);
    } else {
        cmd->opcode = i;
        return flatten_str_args(argc - 1, argv + 1, cmd);
    }
}

int shell_command(const int argc, const char **argv)
{
    int fd, i;
    LPC_MARSHAL  cmd;

    if (parse_cmd(argc, argv, &cmd)) {
        fprintf(stderr, "Incorrect command or command line is too long.\n");
        return -1;
    }
    fd = socket_local_client(SOCKET_PATH,
                             ANDROID_SOCKET_NAMESPACE_RESERVED,
                             SOCK_STREAM);
    if (fd == -1) {
        fprintf(stderr, "Keystore service is not up and running.\n");
        return -1;
    }

    if (write_marshal(fd, &cmd)) {
        fprintf(stderr, "Incorrect command or command line is too long.\n");
        return -1;
    }
    if (read_marshal(fd, &cmd)) {
        fprintf(stderr, "Failed to read the result.\n");
        return -1;
    }
    cmd.data[cmd.len] = 0;
    fprintf(stdout, "%s\n", (cmd.retcode == 0) ? "Succeeded!" : "Failed!");
    if (cmd.len) fprintf(stdout, "\t%s\n", (char*)cmd.data);
    close(fd);
    return 0;
}

int server_main(const int argc, const char *argv[])
{
    struct sockaddr addr;
    socklen_t alen;
    int lsocket, s;
    LPC_MARSHAL  cmd, reply;

    if (init_keystore(KEYSTORE_DIR)) {
        LOGE("Can not initialize the keystore, the directory exist?\n");
        return -1;
    }

    lsocket = android_get_control_socket(SOCKET_PATH);
    if (lsocket < 0) {
        LOGE("Failed to get socket from environment: %s\n", strerror(errno));
        return -1;
    }
    if (listen(lsocket, 5)) {
        LOGE("Listen on socket failed: %s\n", strerror(errno));
        return -1;
    }
    fcntl(lsocket, F_SETFD, FD_CLOEXEC);
    memset(&reply, 0, sizeof(LPC_MARSHAL));

    for (;;) {
        socklen_t cr_size = sizeof(cr);
        alen = sizeof(addr);
        s = accept(lsocket, &addr, &alen);

        /* retrieve the caller info here */
        if (getsockopt(s, SOL_SOCKET, SO_PEERCRED, &cr, &cr_size) < 0) {
            close(s);
            LOGE("Unable to recieve socket options\n");
            continue;
        }

        if (s < 0) {
            LOGE("Accept failed: %s\n", strerror(errno));
            continue;
        }
        fcntl(s, F_SETFD, FD_CLOEXEC);
        if (set_read_timeout(s)) {
            close(s);
            continue;
        }

        // read the command, execute and send the result back.
        if(read_marshal(s, &cmd)) goto err;
        if (DBG) LOGD("new connection\n");
        execute(&cmd, &reply);
        write_marshal(s, &reply);
err:
        memset(&reply, 0, sizeof(LPC_MARSHAL));
        if (DBG) LOGD("closing connection\n");
        close(s);
    }

    return 0;
}
