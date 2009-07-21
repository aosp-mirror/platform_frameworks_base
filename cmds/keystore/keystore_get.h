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

#ifndef __KEYSTORE_GET_H__
#define __KEYSTORE_GET_H__

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "certtool.h"

/* This function is provided to native components to get values from keystore.
 * Users are required to link against libcutils. If something goes wrong, NULL
 * is returned. Otherwise it returns the value in dynamically allocated memory
 * and sets the size if the pointer is not NULL. One can release the memory by
 * calling free(). */
static char *keystore_get(char *key, int *size)
{
    char buffer[MAX_KEY_VALUE_LENGTH];
    char *value;
    int length;

    if (get_cert(key, (unsigned char *)buffer, &length) != 0) {
        return NULL;
    }
    value = malloc(length + 1);
    if (!value) {
        return NULL;
    }
    memcpy(value, buffer, length);
    value[length] = 0;
    if (size) {
        *size = length;
    }
    return value;
}

#endif
