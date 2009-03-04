/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


#ifndef HAVE_WINSOCK
//#define SOCKETLOG
#endif

#ifdef SOCKETLOG

#define LOG_TAG "SOCKETLOG"

#include <string.h>
#include <cutils/log.h>
#include "utils/LogSocket.h"
#include "utils/logger.h"
#include "cutils/hashmap.h"

// defined in //device/data/etc/event-log-tags
#define SOCKET_CLOSE_LOG 51000

static Hashmap* statsMap = NULL;

#define LOG_LIST_NUMBER 5

typedef struct SocketStats {
    int fd;
    unsigned int send;
    unsigned int recv;
    unsigned int ip;
    unsigned short port;
    short reason;
}SocketStats;

SocketStats *get_socket_stats(int fd) {
    if (statsMap == NULL) {
        statsMap = hashmapCreate(8, &hashmapIntHash, &hashmapIntEquals);
    }

    SocketStats *s = (SocketStats*) hashmapGet(statsMap, &fd);
    if (s == NULL) {
        // LOGD("create SocketStats for fd %d", fd);
        s = (SocketStats*) malloc(sizeof(SocketStats));
        memset(s, 0, sizeof(SocketStats));
        s->fd = fd;
        hashmapPut(statsMap, &s->fd, s);
    }
    return s;
}

void log_socket_connect(int fd, unsigned int ip, unsigned short port) {
    // LOGD("log_socket_connect for fd %d ip %d port%d", fd, ip, port);
    SocketStats *s = get_socket_stats(fd);
    s->ip = ip;
    s->port = port;
}

void add_send_stats(int fd, int send) {
    if (send <=0) {
        LOGE("add_send_stats send %d", send);
        return;
    }
    SocketStats *s = get_socket_stats(fd);
    s->send += send;
    // LOGD("add_send_stats for fd %d ip %d port%d", fd, s->ip, s->port);
}

void add_recv_stats(int fd, int recv) {
    if (recv <=0) {
        LOGE("add_recv_stats recv %d", recv);
        return;
    }
    SocketStats *s = get_socket_stats(fd);
    s->recv += recv;
    // LOGD("add_recv_stats for fd %d ip %d port%d", fd, s->ip, s->port);
}

char* put_int(char* buf, int value) {
    *buf = EVENT_TYPE_INT;
    buf++;
    memcpy(buf, &value, sizeof(int));
    return buf + sizeof(int);
}

void log_socket_close(int fd, short reason) {
    if (statsMap) {
        SocketStats *s = (SocketStats*) hashmapGet(statsMap, &fd);
        if (s != NULL) {
            if (s->send != 0 || s->recv != 0) {
                s->reason = reason;
                // 5 int + list type need 2 bytes
                char buf[LOG_LIST_NUMBER * 5 + 2];
                buf[0] = EVENT_TYPE_LIST;
                buf[1] = LOG_LIST_NUMBER;
                char* writePos = buf + 2;
                writePos = put_int(writePos, s->send);
                writePos = put_int(writePos, s->recv);
                writePos = put_int(writePos, s->ip);
                writePos = put_int(writePos, s->port);
                writePos = put_int(writePos, s->reason);
                
                android_bWriteLog(SOCKET_CLOSE_LOG, buf, sizeof(buf));
                // LOGD("send %d recv %d reason %d", s->send, s->recv, s->reason);
            }
            hashmapRemove(statsMap, &s->fd);
            free(s);
        }
    }
}

#else
void add_send_stats(int fd, int send) {} 
void add_recv_stats(int fd, int recv) {}
void log_socket_close(int fd, short reason) {}
void log_socket_connect(int fd, unsigned int ip, unsigned short port) {}
#endif
