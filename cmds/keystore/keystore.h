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
#define CERTS_DIR           KEYSTORE_DIR_PREFIX "/keys"
#define CACERTS_DIR         KEYSTORE_DIR_PREFIX "/cacerts"
#define CA_CERTIFICATE      "ca.crt"
#define USER_CERTIFICATE    "user.crt"
#define USER_P12_CERT       "user.p12"
#define USER_KEY            "user.key"
#define DOT                 "."
#define DOTDOT              ".."

#define BUFFER_MAX      4096  /* input buffer for commands */
#define TOKEN_MAX       8     /* max number of arguments in buffer */
#define REPLY_MAX       4096  /* largest reply allowed */
#define CMD_DELIMITER   '\t'
#define KEYNAME_LENGTH  128
#define IS_CONTENT      0
#define IS_FILE         1


/* commands.c */
int list_ca_certs(char reply[REPLY_MAX]);
int list_user_certs(char reply[REPLY_MAX]);
int install_user_cert(const char *certname, const char *cert, const char *key);
int install_ca_cert(const char *certname, const char *cert);
int install_p12_cert(const char *certname, const char *cert);
int add_ca_cert(const char *certname, const char *content);
int add_user_cert(const char *certname, const char *content);
int add_user_key(const char *keyname, const char *content);
int get_ca_cert(const char *keyname, char reply[REPLY_MAX]);
int get_user_cert(const char *keyname, char reply[REPLY_MAX]);
int get_user_key(const char *keyname, char reply[REPLY_MAX]);
int remove_user_cert(const char *certname);
int remove_ca_cert(const char *certname);
