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

#ifndef __CERTTOOL_H__
#define __CERTTOOL_H__

#include <stdio.h>
#include <string.h>
#include <cutils/sockets.h>
#include <cutils/log.h>

#include "common.h"
#include "netkeystore.h"

#define CERT_NAME_LEN (2 * MAX_KEY_NAME_LENGTH + 2)

/*
 * The specific function 'get_cert' is used in daemons to get the key value
 * from keystore. Caller should allocate the buffer and the length of the buffer
 * should be MAX_KEY_VALUE_LENGTH.
 */
static inline int get_cert(const char *certname, unsigned char *value, int *size)
{
    int count, fd, ret = -1;
    LPC_MARSHAL cmd;
    char delimiter[] = "_";
    char *p = NULL;
    char *context = NULL;
    char *cname = (char*)cmd.data;

    if ((certname == NULL) || (value == NULL)) {
        LOGE("get_cert: certname or value is null\n");
        return -1;
    }

    if (strlcpy(cname, certname, CERT_NAME_LEN) >= CERT_NAME_LEN) {
        LOGE("get_cert: keyname is too long\n");
        return -1;
    }

    fd = socket_local_client(SOCKET_PATH,
                             ANDROID_SOCKET_NAMESPACE_RESERVED,
                             SOCK_STREAM);
    if (fd == -1) {
        LOGE("Keystore service is not up and running.\n");
        return -1;
    }

    cmd.opcode = GET;
    p = strstr(cname, delimiter);
    cmd.len = strlen(certname) + 1;
    if (p == NULL) goto err;
    *p = 0; // replace the delimiter with \0 .

    if (write_marshal(fd, &cmd)) {
        LOGE("Incorrect command or command line is too long.\n");
        goto err;
    }
    if (read_marshal(fd, &cmd)) {
        LOGE("Failed to read the result.\n");
        goto err;
    }

    // copy the result if succeeded.
    if (!cmd.retcode && cmd.len <= BUFFER_MAX) {
        memcpy(value, cmd.data, cmd.len);
        ret = 0;
        *size = cmd.len;
    }
err:
    close(fd);
    return ret;
}

#endif
