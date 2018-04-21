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

#include "stats_event_list.h"

#include "statsd_writer.h"

namespace android {
namespace util {

enum ReadWriteFlag {
    kAndroidLoggerRead = 1,
    kAndroidLoggerWrite = 2,
};

typedef struct {
    uint32_t tag;
    unsigned pos; /* Read/write position into buffer */
    unsigned count[ANDROID_MAX_LIST_NEST_DEPTH + 1]; /* Number of elements   */
    unsigned list[ANDROID_MAX_LIST_NEST_DEPTH + 1];  /* pos for list counter */
    unsigned list_nest_depth;
    unsigned len; /* Length or raw buffer. */
    bool overflow;
    bool list_stop; /* next call decrement list_nest_depth and issue a stop */
    ReadWriteFlag read_write_flag;
    uint8_t storage[LOGGER_ENTRY_MAX_PAYLOAD];
} android_log_context_internal;

extern struct android_log_transport_write statsdLoggerWrite;

static int __write_to_statsd_init(struct iovec* vec, size_t nr);
static int (*write_to_statsd)(struct iovec* vec,
                              size_t nr) = __write_to_statsd_init;

int stats_write_list(android_log_context ctx) {
    android_log_context_internal* context;
    const char* msg;
    ssize_t len;

    context = (android_log_context_internal*)(ctx);
    if (!context || (kAndroidLoggerWrite != context->read_write_flag)) {
        return -EBADF;
    }

    if (context->list_nest_depth) {
        return -EIO;
    }

    /* NB: if there was overflow, then log is truncated. Nothing reported */
    context->storage[1] = context->count[0];
    len = context->len = context->pos;
    msg = (const char*)context->storage;
    /* it's not a list */
    if (context->count[0] <= 1) {
        len -= sizeof(uint8_t) + sizeof(uint8_t);
        if (len < 0) {
            len = 0;
        }
        msg += sizeof(uint8_t) + sizeof(uint8_t);
    }

    struct iovec vec[2];
    vec[0].iov_base = &context->tag;
    vec[0].iov_len = sizeof(context->tag);
    vec[1].iov_base = (void*)msg;
    vec[1].iov_len = len;
    return write_to_statsd(vec, 2);
}

int stats_event_list::write_to_logger(android_log_context ctx, log_id_t id) {
    int retValue = 0;

    if (WRITE_TO_LOGD) {
        retValue = android_log_write_list(ctx, id);
    }

    if (WRITE_TO_STATSD) {
        // log_event_list's cast operator is overloaded.
        int ret = stats_write_list(static_cast<android_log_context>(*this));
        // In debugging phase, we may write to both logd and statsd. Prefer to return
        // statsd socket write error code here.
        if (ret < 0) {
            retValue = ret;
        }
    }

    return retValue;
}

/* log_init_lock assumed */
static int __write_to_statsd_initialize_locked() {
    if (!statsdLoggerWrite.open || ((*statsdLoggerWrite.open)() < 0)) {
        if (statsdLoggerWrite.close) {
            (*statsdLoggerWrite.close)();
            return -ENODEV;
        }
    }
    return 1;
}

static int __write_to_stats_daemon(struct iovec* vec, size_t nr) {
    int ret, save_errno;
    struct timespec ts;
    size_t len, i;

    for (len = i = 0; i < nr; ++i) {
        len += vec[i].iov_len;
    }
    if (!len) {
        return -EINVAL;
    }

    save_errno = errno;
    clock_gettime(CLOCK_REALTIME, &ts);

    ret = 0;

    ssize_t retval;
    retval = (*statsdLoggerWrite.write)(&ts, vec, nr);
    if (ret >= 0) {
        ret = retval;
    }

    errno = save_errno;
    return ret;
}

static int __write_to_statsd_init(struct iovec* vec, size_t nr) {
    int ret, save_errno = errno;

    statsd_writer_init_lock();

    if (write_to_statsd == __write_to_statsd_init) {
        ret = __write_to_statsd_initialize_locked();
        if (ret < 0) {
            statsd_writer_init_unlock();
            errno = save_errno;
            return ret;
        }

        write_to_statsd = __write_to_stats_daemon;
    }

    statsd_writer_init_unlock();

    ret = write_to_statsd(vec, nr);
    errno = save_errno;
    return ret;
}

}  // namespace util
}  // namespace android