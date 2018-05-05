/*
 * Copyright (C) 2018, The Android Open Source Project
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

#ifndef ANDROID_STATS_LOG_STATS_WRITER_H
#define ANDROID_STATS_LOG_STATS_WRITER_H

#include <pthread.h>
#include <stdatomic.h>
#include <sys/socket.h>

/**
 * Internal lock should not be exposed. This is bad design.
 * TODO: rewrite it in c++ code and encapsulate the functionality in a
 * StatsdWriter class.
 */
void statsd_writer_init_lock();
int statsd_writer_init_trylock();
void statsd_writer_init_unlock();

struct android_log_transport_write {
    const char* name; /* human name to describe the transport */
    atomic_int sock;
    int (*available)(); /* Does not cause resources to be taken */
    int (*open)(); /* can be called multiple times, reusing current resources */
    void (*close)(); /* free up resources */
    /* write log to transport, returns number of bytes propagated, or -errno */
    int (*write)(struct timespec* ts, struct iovec* vec, size_t nr);
};


#endif  // ANDROID_STATS_LOG_STATS_WRITER_H
