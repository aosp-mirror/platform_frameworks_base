/*
 * Copyright (C) 2009 The Android Open Source Project
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

#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <sys/types.h>
#include <sys/socket.h>

#include <cutils/sockets.h>

#include "keystore.h"

static const char* responses[] = {
    NULL,
    /* [NO_ERROR]           = */ "No error",
    /* [LOCKED]             = */ "Locked",
    /* [UNINITIALIZED]      = */ "Uninitialized",
    /* [SYSTEM_ERROR]       = */ "System error",
    /* [PROTOCOL_ERROR]     = */ "Protocol error",
    /* [PERMISSION_DENIED]  = */ "Permission denied",
    /* [KEY_NOT_FOUND]      = */ "Key not found",
    /* [VALUE_CORRUPTED]    = */ "Value corrupted",
    /* [UNDEFINED_ACTION]   = */ "Undefined action",
    /* [WRONG_PASSWORD]     = */ "Wrong password (last chance)",
    /* [WRONG_PASSWORD + 1] = */ "Wrong password (2 tries left)",
    /* [WRONG_PASSWORD + 2] = */ "Wrong password (3 tries left)",
    /* [WRONG_PASSWORD + 3] = */ "Wrong password (4 tries left)",
};

int main(int argc, char* argv[])
{
    if (argc < 2) {
        printf("Usage: %s action [parameter ...]\n", argv[0]);
        return 0;
    }

    int sock = socket_local_client("keystore", ANDROID_SOCKET_NAMESPACE_RESERVED,
                                   SOCK_STREAM);
    if (sock == -1) {
        puts("Failed to connect");
        return 1;
    }

    send(sock, argv[1], 1, 0);
    uint8_t bytes[65536];
    for (int i = 2; i < argc; ++i) {
        uint16_t length = strlen(argv[i]);
        bytes[0] = length >> 8;
        bytes[1] = length;
        send(sock, &bytes, 2, 0);
        send(sock, argv[i], length, 0);
    }
    shutdown(sock, SHUT_WR);

    uint8_t code;
    if (recv(sock, &code, 1, 0) != 1) {
        puts("Failed to receive");
        return 1;
    }
    printf("%d %s\n", code , responses[code] ? responses[code] : "Unknown");
    int i;
    while ((i = recv(sock, &bytes[0], 1, 0)) == 1) {
        int length;
        int offset;
        if ((i = recv(sock, &bytes[1], 1, 0)) != 1) {
            puts("Failed to receive");
            return 1;
        }
        length = bytes[0] << 8 | bytes[1];
        for (offset = 0; offset < length; offset += i) {
            i = recv(sock, &bytes[offset], length - offset, 0);
            if (i <= 0) {
                puts("Failed to receive");
                return 1;
            }
        }
        fwrite(bytes, 1, length, stdout);
        puts("");
    }
    return 0;
}
