/*
 * Copyright 2012, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


/**
 * The probability of a syscall failing from 0.0 to 1.0
 */
#define PROBABILITY 0.9



#include <stdio.h>
#include <stdlib.h>
#include <errno.h>

/* for various intercepted calls */
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <fcntl.h>

/* For builds on glibc */
#define __USE_GNU
#include <dlfcn.h>

#include "interrupter.h"

static int probability = PROBABILITY * RAND_MAX;

static int maybe_interrupt() {
    if (rand() < probability) {
        return 1;
    }
    return 0;
}

DEFINE_INTERCEPT(read, ssize_t, int, void*, size_t);
DEFINE_INTERCEPT(write, ssize_t, int, const void*, size_t);
DEFINE_INTERCEPT(accept, int, int, struct sockaddr*, socklen_t*);
DEFINE_INTERCEPT(creat, int, const char*, mode_t);
