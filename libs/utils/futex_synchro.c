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

#include <stdio.h>
#include <limits.h>

#include <sys/time.h>
#include <sched.h>

#include <errno.h>

#include <private/utils/futex_synchro.h>


// This futex glue code is need on desktop linux, but is already part of bionic.
#if !defined(HAVE_FUTEX_WRAPPERS)

#include <sys/syscall.h>
typedef unsigned int u32;
#define asmlinkage
#define __user
#include <linux/futex.h>
#include <utils/Atomic.h>


int futex (int *uaddr, int op, int val, const struct timespec *timeout, int *uaddr2, int val3)
{
    int err = syscall(SYS_futex, uaddr, op, val, timeout, uaddr2, val3);
    return err == 0 ? 0 : -errno;
}

int __futex_wait(volatile void *ftx, int val, const struct timespec *timeout)
{
    return futex((int*)ftx, FUTEX_WAIT, val, timeout, NULL, 0);
}

int __futex_wake(volatile void *ftx, int count)
{
    return futex((int*)ftx, FUTEX_WAKE, count, NULL, NULL, 0);
}

int __atomic_cmpxchg(int old, int _new, volatile int *ptr)
{
    return android_atomic_cmpxchg(old, _new, ptr);
}

int __atomic_swap(int _new, volatile int *ptr)
{
    return android_atomic_swap(_new, ptr);
}

int __atomic_dec(volatile int *ptr)
{
    return android_atomic_dec(ptr);
}

#else // !defined(__arm__)

int __futex_wait(volatile void *ftx, int val, const struct timespec *timeout);
int __futex_wake(volatile void *ftx, int count);

int __atomic_cmpxchg(int old, int _new, volatile int *ptr);
int __atomic_swap(int _new, volatile int *ptr);
int __atomic_dec(volatile int *ptr);

#endif // !defined(HAVE_FUTEX_WRAPPERS)


// lock states
//
// 0: unlocked
// 1: locked, no waiters
// 2: locked, maybe waiters

void futex_mutex_init(futex_mutex_t *m)
{
    m->value = 0;
}

int futex_mutex_lock(futex_mutex_t *m, unsigned msec)
{
    if(__atomic_cmpxchg(0, 1, &m->value) == 0) {
        return 0;
    }
    if(msec == FUTEX_WAIT_INFINITE) {
        while(__atomic_swap(2, &m->value) != 0) {
            __futex_wait(&m->value, 2, 0);
        }
    } else {
        struct timespec ts;
        ts.tv_sec = msec / 1000;
        ts.tv_nsec = (msec % 1000) * 1000000;
        while(__atomic_swap(2, &m->value) != 0) {
            if(__futex_wait(&m->value, 2, &ts) == -ETIMEDOUT) {
                return -1;
            }
        }
    }
    return 0;
}

int futex_mutex_trylock(futex_mutex_t *m)
{
    if(__atomic_cmpxchg(0, 1, &m->value) == 0) {
        return 0;
    }
    return -1;
}

void futex_mutex_unlock(futex_mutex_t *m)
{
    if(__atomic_dec(&m->value) != 1) {
        m->value = 0;
        __futex_wake(&m->value, 1);
    }
}

/* XXX *technically* there is a race condition that could allow
 * XXX a signal to be missed.  If thread A is preempted in _wait()
 * XXX after unlocking the mutex and before waiting, and if other
 * XXX threads call signal or broadcast UINT_MAX times (exactly),
 * XXX before thread A is scheduled again and calls futex_wait(),
 * XXX then the signal will be lost.
 */

void futex_cond_init(futex_cond_t *c)
{
    c->value = 0;
}

int futex_cond_wait(futex_cond_t *c, futex_mutex_t *m, unsigned msec)
{
    if(msec == FUTEX_WAIT_INFINITE){
        int oldvalue = c->value;
        futex_mutex_unlock(m);
        __futex_wait(&c->value, oldvalue, 0);
        futex_mutex_lock(m, FUTEX_WAIT_INFINITE);
        return 0;
    } else {
        int oldvalue = c->value;
        struct timespec ts;        
        ts.tv_sec = msec / 1000;
        ts.tv_nsec = (msec % 1000) * 1000000;
        futex_mutex_unlock(m);
        const int err = __futex_wait(&c->value, oldvalue, &ts);
        futex_mutex_lock(m, FUTEX_WAIT_INFINITE);
        return err;
    }
}

void futex_cond_signal(futex_cond_t *c)
{
    __atomic_dec(&c->value);
    __futex_wake(&c->value, 1);
}

void futex_cond_broadcast(futex_cond_t *c)
{
    __atomic_dec(&c->value);
    __futex_wake(&c->value, INT_MAX);
}

