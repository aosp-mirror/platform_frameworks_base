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

#define LOG_TAG "keystore"

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

#include <cutils/sockets.h>
#include <cutils/log.h>
#include <cutils/properties.h>

#define SOCKET_PATH "keystore"


/* path of the keystore */

#define KEYSTORE_DIR_PREFIX "/data/misc/keystore"
#define CERTS_DIR           KEYSTORE_DIR_PREFIX "/certs"
#define USERKEYS_DIR         KEYSTORE_DIR_PREFIX "/userkeys"

#define BUFFER_MAX      1024  /* input buffer for commands */
#define TOKEN_MAX       8     /* max number of arguments in buffer */
#define REPLY_MAX       1024  /* largest reply allowed */
#define KEYNAME_LENGTH  128

/* commands.c */
int list_certs(char reply[REPLY_MAX]);
int list_userkeys(char reply[REPLY_MAX]);
int install_cert(const char *certfile);
int install_userkey(const char *keyfile);
int remove_cert(const char *certfile);
int remove_userkey(const char *keyfile);
