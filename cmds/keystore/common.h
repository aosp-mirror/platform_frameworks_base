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

#ifndef __COMMON_H__
#define __COMMON_H__

#define SOCKET_PATH             "keystore"
#define KEYSTORE_DIR            "/data/misc/keystore/"

#define READ_TIMEOUT            3
#define MAX_KEY_NAME_LENGTH     64
#define MAX_NAMESPACE_LENGTH    MAX_KEY_NAME_LENGTH
#define MAX_KEY_VALUE_LENGTH    4096

#define BUFFER_MAX              MAX_KEY_VALUE_LENGTH

typedef enum {
    BOOTUP,
    UNINITIALIZED,
    LOCKED,
    UNLOCKED,
} KEYSTORE_STATE;

typedef enum {
    LOCK,
    UNLOCK,
    PASSWD,
    GETSTATE,
    LISTKEYS,
    GET,
    PUT,
    REMOVE,
    RESET,
    MAX_OPCODE
} KEYSTORE_OPCODE;

typedef struct {
    uint32_t  len;
    union {
        uint32_t  opcode;
        uint32_t  retcode;
    };
    unsigned char data[BUFFER_MAX + 1];
} LPC_MARSHAL;

#endif
