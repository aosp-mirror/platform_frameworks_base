/*
 * Copyright (C) 2009 The Android Open Source Project
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 */

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <cutils/log.h>

#include "common.h"
#include "keymgmt.h"
#include "netkeystore.h"

#define LOG_TAG "keystore_test"

typedef int FUNC_PTR();
typedef struct {
    const char *name;
    FUNC_PTR *func;
} TESTFUNC;

#define FUNC_NAME(x) { #x, test_##x }
#define FUNC_BODY(x) int test_##x()

#define TEST_PASSWD        "12345678"
#define TEST_NPASSWD    "hello world"
#define TEST_DIR        "/data/local/tmp/keystore"
#define READONLY_DIR    "/proc/keystore"
#define TEST_NAMESPACE    "test"
#define TEST_KEYNAME    "key"
#define TEST_KEYNAME2    "key2"
#define TEST_KEYVALUE    "ANDROID"

void setup()
{
    if (init_keystore(TEST_DIR) != 0) {
        fprintf(stderr, "Can not create the test directory %s\n", TEST_DIR);
        exit(-1);
    }
}

void teardown()
{
    if (reset_keystore() != 0) {
        fprintf(stderr, "Can not reset the test directory %s\n", TEST_DIR);
    }
    rmdir(TEST_DIR);
}

FUNC_BODY(init_keystore)
{
    if (init_keystore(READONLY_DIR) == 0) return -1;

    return EXIT_SUCCESS;
}

FUNC_BODY(reset_keystore)
{
    int ret = chdir("/proc");
    if (reset_keystore() == 0) return -1;
    chdir(TEST_DIR);
    return EXIT_SUCCESS;
}

FUNC_BODY(get_state)
{
    if (get_state() != UNINITIALIZED) return -1;
    new_passwd(TEST_PASSWD);
    if (get_state() != UNLOCKED) return -1;
    lock();
    if (get_state() != LOCKED) return -1;

    if (reset_keystore() != 0) return -1;
    if (get_state() != UNINITIALIZED) return -1;
    return EXIT_SUCCESS;
}

FUNC_BODY(passwd)
{
    char buf[512];

    if (new_passwd("2d fsdf") == 0) return -1;
    if (new_passwd("dsfsdf") == 0) return -1;
    new_passwd(TEST_PASSWD);
    lock();
    if (unlock("55555555") == 0) return -1;
    if (unlock(TEST_PASSWD) != 0) return -1;

    // change the password
    if (change_passwd("klfdjdsklfjg", "abcdefghi") == 0) return -1;

    if (change_passwd(TEST_PASSWD, TEST_NPASSWD) != 0) return -1;
    lock();

    if (unlock(TEST_PASSWD) == 0) return -1;
    if (unlock(TEST_NPASSWD) != 0) return -1;

    return EXIT_SUCCESS;
}

FUNC_BODY(lock)
{
    if (lock() == 0) return -1;
    new_passwd(TEST_PASSWD);
    if (lock() != 0) return -1;
    if (lock() != 0) return -1;
    return EXIT_SUCCESS;
}

FUNC_BODY(unlock)
{
    int i = MAX_RETRY_COUNT;
    new_passwd(TEST_PASSWD);
    lock();
    while (i > 1) {
        if (unlock(TEST_NPASSWD) != --i) return -1;
    }
    if (unlock(TEST_NPASSWD) != -1) return -1;
    return EXIT_SUCCESS;
}

FUNC_BODY(put_key)
{
    int i = 0;
    char keyname[512];

    if (put_key(TEST_NAMESPACE, TEST_KEYNAME, (unsigned char *)TEST_KEYVALUE,
                strlen(TEST_KEYVALUE)) == 0) return -1;
    new_passwd(TEST_PASSWD);
    if (put_key(TEST_NAMESPACE, TEST_KEYNAME, (unsigned char *)TEST_KEYVALUE,
                strlen(TEST_KEYVALUE)) != 0) return -1;

    for(i = 0; i < 500; i++) keyname[i] = 'K';
    keyname[i] = 0;
    if (put_key(TEST_NAMESPACE, keyname, (unsigned char *)TEST_KEYVALUE,
                strlen(TEST_KEYVALUE)) == 0) return -1;
    if (put_key(TEST_NAMESPACE, TEST_KEYNAME, (unsigned char *)TEST_KEYVALUE,
                MAX_KEY_VALUE_LENGTH + 1) == 0) return -1;
    return EXIT_SUCCESS;
}

FUNC_BODY(get_key)
{
    int size;
    unsigned char data[MAX_KEY_VALUE_LENGTH];

    if (get_key(TEST_NAMESPACE, TEST_KEYNAME, data, &size) == 0) return -1;

    new_passwd(TEST_PASSWD);
    put_key(TEST_NAMESPACE, TEST_KEYNAME, (unsigned char *)TEST_KEYVALUE,
            strlen(TEST_KEYVALUE));
    if (get_key(TEST_NAMESPACE, TEST_KEYNAME, data, &size) != 0) return -1;
    if (memcmp(data, TEST_KEYVALUE, size) != 0) return -1;

    return EXIT_SUCCESS;
}

FUNC_BODY(remove_key)
{
    if (remove_key(TEST_NAMESPACE, TEST_KEYNAME) == 0) return -1;

    new_passwd(TEST_PASSWD);
    if (remove_key(TEST_NAMESPACE, TEST_KEYNAME) == 0) return -1;

    put_key(TEST_NAMESPACE, TEST_KEYNAME, (unsigned char *)TEST_KEYVALUE,
            strlen(TEST_KEYVALUE));
    if (remove_key(TEST_NAMESPACE, TEST_KEYNAME) != 0) return -1;

    return EXIT_SUCCESS;
}

FUNC_BODY(list_keys)
{
    int i;
    char buf[128];
    char reply[BUFFER_MAX];

    for(i = 0; i < 100; i++) buf[i] = 'K';
    buf[i] = 0;

    if (list_keys(TEST_NAMESPACE, reply) == 0) return -1;

    new_passwd(TEST_PASSWD);
    if (list_keys(buf, reply) == 0) return -1;

    if (list_keys(TEST_NAMESPACE, reply) != 0) return -1;
    if (strcmp(reply, "") != 0) return -1;

    put_key(TEST_NAMESPACE, TEST_KEYNAME, (unsigned char *)TEST_KEYVALUE,
            strlen(TEST_KEYVALUE));
    if (list_keys(TEST_NAMESPACE, reply) != 0) return -1;
    if (strcmp(reply, TEST_KEYNAME) != 0) return -1;

    put_key(TEST_NAMESPACE, TEST_KEYNAME2, (unsigned char *)TEST_KEYVALUE,
            strlen(TEST_KEYVALUE));

    if (list_keys(TEST_NAMESPACE, reply) != 0) return -1;
    sprintf(buf, "%s %s", TEST_KEYNAME2, TEST_KEYNAME);
    if (strcmp(reply, buf) != 0) return -1;

    return EXIT_SUCCESS;
}

static int execute_cmd(int argc, const char *argv[], LPC_MARSHAL *cmd,
        LPC_MARSHAL *reply)
{
    memset(cmd, 0, sizeof(LPC_MARSHAL));
    memset(reply, 0, sizeof(LPC_MARSHAL));
    if (parse_cmd(argc, argv, cmd)) return -1;
    execute(cmd, reply);
    return (reply->retcode ? -1 : 0);
}

FUNC_BODY(client_passwd)
{
    LPC_MARSHAL cmd, reply;
    const char *set_passwd_cmds[2] = {"passwd", TEST_PASSWD};
    const char *change_passwd_cmds[3] = {"passwd", TEST_PASSWD, TEST_NPASSWD};

    if (execute_cmd(2, set_passwd_cmds, &cmd, &reply)) return -1;

    lock();
    if (unlock("55555555") == 0) return -1;
    if (unlock(TEST_PASSWD) != 0) return -1;

    if (execute_cmd(3, change_passwd_cmds, &cmd, &reply)) return -1;

    lock();
    if (unlock(TEST_PASSWD) == 0) return -1;
    if (unlock(TEST_NPASSWD) != 0) return -1;

    return EXIT_SUCCESS;
}

TESTFUNC all_tests[] = {
    FUNC_NAME(init_keystore),
    FUNC_NAME(reset_keystore),
    FUNC_NAME(get_state),
    FUNC_NAME(passwd),
    FUNC_NAME(lock),
    FUNC_NAME(unlock),
    FUNC_NAME(put_key),
    FUNC_NAME(get_key),
    FUNC_NAME(remove_key),
    FUNC_NAME(list_keys),
    FUNC_NAME(client_passwd),
};

int main(int argc, char **argv) {
    int i, ret;
    for (i = 0 ; i < (int)(sizeof(all_tests)/sizeof(TESTFUNC)) ; ++i) {
        LOGD("run %s...\n", all_tests[i].name);
        setup();
        if ((ret = all_tests[i].func()) != EXIT_SUCCESS) {
            fprintf(stderr, "ERROR in function %s\n", all_tests[i].name);
            return ret;
        } else {
            fprintf(stderr, "function %s PASSED!\n", all_tests[i].name);
        }
        teardown();
    }
    return EXIT_SUCCESS;
}
