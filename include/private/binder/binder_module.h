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

#ifndef _BINDER_MODULE_H_
#define _BINDER_MODULE_H_

#ifdef __cplusplus
namespace android {
#endif

#if defined(HAVE_ANDROID_OS)

/* obtain structures and constants from the kernel header */

#include <sys/ioctl.h>
#include <linux/binder.h>

#else

/* Some parts of the simulator need fake versions of this 
 * stuff in order to compile.  Really this should go away
 * entirely...
 */

#define BINDER_CURRENT_PROTOCOL_VERSION 7

#define BINDER_TYPE_BINDER 1
#define BINDER_TYPE_WEAK_BINDER 2
#define BINDER_TYPE_HANDLE 3
#define BINDER_TYPE_WEAK_HANDLE 4
#define BINDER_TYPE_FD 5

struct flat_binder_object {
    unsigned long type;
    unsigned long flags;
    union {
        void *binder;
        signed long handle;
    };
    void *cookie;
};

struct binder_write_read {
    signed long write_size;
    signed long write_consumed;
    unsigned long write_buffer;
    signed long read_size;
    signed long read_consumed;
    unsigned long read_buffer;
};

struct binder_transaction_data {
    union {
        size_t handle;
        void *ptr;
    } target;
    void *cookie;
    unsigned int code;
    
    unsigned int flags;
    pid_t sender_pid;
    uid_t sender_euid;
    size_t data_size;
    size_t offsets_size;
    
    union {
        struct {
            const void *buffer;
            const void *offsets;
        } ptr;
        uint8_t buf[8];
    } data;
};

enum transaction_flags {
    TF_ONE_WAY = 0x01,
    TF_ROOT_OBJECT = 0x04,
    TF_STATUS_CODE = 0x08,
    TF_ACCEPT_FDS = 0x10,
};


enum {
    FLAT_BINDER_FLAG_PRIORITY_MASK = 0xff,
    FLAT_BINDER_FLAG_ACCEPTS_FDS = 0x100,
};

enum BinderDriverReturnProtocol {
    BR_ERROR,
    BR_OK,
    BR_TRANSACTION,
    BR_REPLY,
    BR_ACQUIRE_RESULT,
    BR_DEAD_REPLY,
    BR_TRANSACTION_COMPLETE,
    BR_INCREFS,
    BR_ACQUIRE,
    BR_RELEASE,
    BR_DECREFS,
    BR_ATTEMPT_ACQUIRE,
    BR_NOOP,
    BR_SPAWN_LOOPER,
    BR_FINISHED,
    BR_DEAD_BINDER,
    BR_CLEAR_DEATH_NOTIFICATION_DONE,
    BR_FAILED_REPLY,
};

enum BinderDriverCommandProtocol {
    BC_TRANSACTION,
    BC_REPLY,
    BC_ACQUIRE_RESULT,
    BC_FREE_BUFFER,
    BC_INCREFS,
    BC_ACQUIRE,
    BC_RELEASE,
    BC_DECREFS,
    BC_INCREFS_DONE,
    BC_ACQUIRE_DONE,
    BC_ATTEMPT_ACQUIRE,
    BC_REGISTER_LOOPER,
    BC_ENTER_LOOPER,
    BC_EXIT_LOOPER,
    BC_REQUEST_DEATH_NOTIFICATION,
    BC_CLEAR_DEATH_NOTIFICATION,
    BC_DEAD_BINDER_DONE,
};

#endif

#ifdef __cplusplus
}   // namespace android
#endif

#endif // _BINDER_MODULE_H_
