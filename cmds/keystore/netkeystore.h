/*
**
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

#ifndef __NETKEYSTORE_H__
#define __NETKEYSTORE_H__

#include <stdio.h>
#include <arpa/inet.h>
#include <cutils/sockets.h>
#include <cutils/log.h>

#include "common.h"

// for testing
int parse_cmd(int argc, const char **argv, LPC_MARSHAL *cmd);
void execute(LPC_MARSHAL *cmd, LPC_MARSHAL *reply);

static inline int readx(int s, void *_buf, int count)
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

static inline int writex(int s, const void *_buf, int count)
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

static inline int read_marshal(int s, LPC_MARSHAL *cmd)
{
    if (readx(s, cmd, 2 * sizeof(uint32_t))) {
        LOGE("failed to read header\n");
        return -1;
    }
    cmd->len = ntohl(cmd->len);
    cmd->opcode = ntohl(cmd->opcode);
    if (cmd->len > BUFFER_MAX) {
        LOGE("invalid size %d\n", cmd->len);
        return -1;
    }
    if (readx(s, cmd->data, cmd->len)) {
        LOGE("failed to read data\n");
        return -1;
    }
    cmd->data[cmd->len] = 0;
    return 0;
}

static inline int write_marshal(int s, LPC_MARSHAL *cmd)
{
    int len = cmd->len;
    cmd->len = htonl(cmd->len);
    cmd->opcode = htonl(cmd->opcode);
    if (writex(s, cmd, 2 * sizeof(uint32_t))) {
        LOGE("failed to write marshal header\n");
        return -1;
    }
    if (writex(s, cmd->data, len)) {
        LOGE("failed to write marshal data\n");
        return -1;
    }
    return 0;
}

#endif
