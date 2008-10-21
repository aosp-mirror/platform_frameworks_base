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

#ifndef _FUTEX_SYNCHRO_H
#define _FUTEX_SYNCHRO_H

#ifndef HAVE_FUTEX
#error "HAVE_FUTEX not defined"
#endif

#define FUTEX_WAIT_INFINITE (0)

typedef struct futex_mutex_t futex_mutex_t;

struct futex_mutex_t 
{
    volatile int value;
};

typedef struct futex_cond_t futex_cond_t;

struct futex_cond_t 
{
    volatile int value;
};


#if __cplusplus
extern "C" {
#endif

void futex_mutex_init(futex_mutex_t *m);
int futex_mutex_lock(futex_mutex_t *m, unsigned msec);
void futex_mutex_unlock(futex_mutex_t *m);
int futex_mutex_trylock(futex_mutex_t *m);

void futex_cond_init(futex_cond_t *c);
int futex_cond_wait(futex_cond_t *c, futex_mutex_t *m, unsigned msec);
void futex_cond_signal(futex_cond_t *c);
void futex_cond_broadcast(futex_cond_t *c);

#if __cplusplus
} // extern "C"
#endif

#endif // _FUTEX_SYNCHRO_H

