/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * See the License for the specic language governing permissions and
 * limitations under the License.
 */

#define LOG_NDEBUG 0
#define LOG_TAG "AppFuseJNI"
#include "utils/Log.h"

#include <assert.h>
#include <dirent.h>
#include <inttypes.h>

#include <linux/fuse.h>
#include <sys/stat.h>

#include <map>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

namespace {

constexpr int min(int a, int b) {
    return a < b ? a : b;
}

// Maximum number of bytes to write in one request.
constexpr size_t MAX_WRITE = 256 * 1024;

// Largest possible request.
// The request size is bounded by the maximum size of a FUSE_WRITE request
// because it has the largest possible data payload.
constexpr size_t MAX_REQUEST_SIZE = sizeof(struct fuse_in_header) +
        sizeof(struct fuse_write_in) + MAX_WRITE;

static jclass app_fuse_class;

struct FuseRequest {
    char buffer[MAX_REQUEST_SIZE];
    FuseRequest() {}
    const struct fuse_in_header& header() const {
        return *(const struct fuse_in_header*) buffer;
    }
    const void* data() const {
        return (buffer + sizeof(struct fuse_in_header));
    }
    size_t data_length() const {
        return header().len - sizeof(struct fuse_in_header);
    }
};

class ScopedFd {
    int mFd;

public:
    explicit ScopedFd(int fd) : mFd(fd) {}
    ~ScopedFd() {
        close(mFd);
    }
    operator int() {
        return mFd;
    }
};

/**
 * The class is used to access AppFuse class in Java from fuse handlers.
 */
class AppFuse {
public:
    AppFuse(JNIEnv* /*env*/, jobject /*self*/) {
    }

    bool handle_fuse_request(int fd, const FuseRequest& req) {
        ALOGV("Request op=%d", req.header().opcode);
        switch (req.header().opcode) {
            // TODO: Handle more operations that are enough to provide seekable
            // FD.
            case FUSE_INIT:
                invoke_handler(fd, req, &AppFuse::handle_fuse_init);
                return true;
            case FUSE_GETATTR:
                invoke_handler(fd, req, &AppFuse::handle_fuse_getattr);
                return true;
            case FUSE_FORGET:
                return false;
            default: {
                ALOGV("NOTIMPL op=%d uniq=%" PRIx64 " nid=%" PRIx64 "\n",
                      req.header().opcode,
                      req.header().unique,
                      req.header().nodeid);
                fuse_reply(fd, req.header().unique, -ENOSYS, NULL, 0);
                return true;
            }
        }
    }

private:
    int handle_fuse_init(const fuse_in_header&,
                         const fuse_init_in* in,
                         fuse_init_out* out,
                         size_t* reply_size) {
        // Kernel 2.6.16 is the first stable kernel with struct fuse_init_out
        // defined (fuse version 7.6). The structure is the same from 7.6 through
        // 7.22. Beginning with 7.23, the structure increased in size and added
        // new parameters.
        if (in->major != FUSE_KERNEL_VERSION || in->minor < 6) {
            ALOGE("Fuse kernel version mismatch: Kernel version %d.%d, "
                  "Expected at least %d.6",
                  in->major, in->minor, FUSE_KERNEL_VERSION);
            return -1;
        }

        // We limit ourselves to 15 because we don't handle BATCH_FORGET yet
        out->minor = min(in->minor, 15);
#if defined(FUSE_COMPAT_22_INIT_OUT_SIZE)
        // FUSE_KERNEL_VERSION >= 23.

        // If the kernel only works on minor revs older than or equal to 22,
        // then use the older structure size since this code only uses the 7.22
        // version of the structure.
        if (in->minor <= 22) {
            *reply_size = FUSE_COMPAT_22_INIT_OUT_SIZE;
        }
#else
        // Don't drop this line to prevent an 'unused' compile error.
        *reply_size = sizeof(fuse_init_out);
#endif

        out->major = FUSE_KERNEL_VERSION;
        out->max_readahead = in->max_readahead;
        out->flags = FUSE_ATOMIC_O_TRUNC | FUSE_BIG_WRITES;
        out->max_background = 32;
        out->congestion_threshold = 32;
        out->max_write = MAX_WRITE;

        return 0;
    }

    int handle_fuse_getattr(const fuse_in_header& header,
                            const fuse_getattr_in* /* in */,
                            fuse_attr_out* out,
                            size_t* /*unused*/) {
        if (header.nodeid != 1) {
            return -ENOENT;
        }
        out->attr_valid = 1000 * 60 * 10;
        out->attr.ino = header.nodeid;
        out->attr.mode = S_IFDIR | 0777;
        out->attr.size = 0;
        return 0;
    }

    template <typename T, typename S>
    void invoke_handler(int fd,
                        const FuseRequest& request,
                        int (AppFuse::*handler)(const fuse_in_header&,
                                                const T*,
                                                S*,
                                                size_t*),
                        size_t reply_size = sizeof(S)) {
        char reply_data[reply_size];
        memset(reply_data, 0, reply_size);
        const int reply_code = (this->*handler)(
                request.header(),
                static_cast<const T*>(request.data()),
                reinterpret_cast<S*>(reply_data),
                &reply_size);
        fuse_reply(
                fd,
                request.header().unique,
                reply_code,
                reply_data,
                reply_size);
    }

    static void fuse_reply(int fd, int unique, int reply_code, void* reply_data,
                           size_t reply_size) {
        // Don't send any data for error case.
        if (reply_code != 0) {
            reply_size = 0;
        }

        struct fuse_out_header hdr;
        hdr.len = reply_size + sizeof(hdr);
        hdr.error = reply_code;
        hdr.unique = unique;

        struct iovec vec[2];
        vec[0].iov_base = &hdr;
        vec[0].iov_len = sizeof(hdr);
        vec[1].iov_base = reply_data;
        vec[1].iov_len = reply_size;

        const int res = writev(fd, vec, reply_size != 0 ? 2 : 1);
        if (res < 0) {
            ALOGE("*** REPLY FAILED *** %d\n", errno);
        }
    }
};

jboolean com_android_mtp_AppFuse_start_app_fuse_loop(
        JNIEnv* env, jobject self, jint jfd) {
    ScopedFd fd(dup(static_cast<int>(jfd)));
    AppFuse appfuse(env, self);

    ALOGD("Start fuse loop.");
    while (true) {
        FuseRequest request;
        const ssize_t result = TEMP_FAILURE_RETRY(
                read(fd, request.buffer, sizeof(request.buffer)));
        if (result < 0) {
            if (errno == ENODEV) {
                ALOGE("Someone stole our marbles!\n");
                return JNI_FALSE;
            }
            ALOGE("Failed to read bytes from FD: errno=%d\n", errno);
            continue;
        }

        const size_t length = static_cast<size_t>(result);
        if (length < sizeof(struct fuse_in_header)) {
            ALOGE("request too short: len=%zu\n", length);
            continue;
        }

        if (request.header().len != length) {
            ALOGE("malformed header: len=%zu, hdr->len=%u\n",
                  length, request.header().len);
            continue;
        }

        if (!appfuse.handle_fuse_request(fd, request)) {
            return JNI_TRUE;
        }
    }
}

static const JNINativeMethod gMethods[] = {
    {
        "native_start_app_fuse_loop",
        "(I)Z",
        (void *) com_android_mtp_AppFuse_start_app_fuse_loop
    }
};

}

jint JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("ERROR: GetEnv failed\n");
        return -1;

    }
    assert(env != nullptr);

    jclass clazz = env->FindClass("com/android/mtp/AppFuse");
    if (clazz == nullptr) {
        ALOGE("Can't find com/android/mtp/AppFuse");
        return -1;
    }
    app_fuse_class = static_cast<jclass>(env->NewGlobalRef(clazz));
    if (app_fuse_class == nullptr) {
        ALOGE("Can't obtain global reference for com/android/mtp/AppFuse");
        return -1;
    }

    const int result = android::AndroidRuntime::registerNativeMethods(
            env, "com/android/mtp/AppFuse", gMethods, NELEM(gMethods));
    if (result < 0) {
        return -1;
    }

    return JNI_VERSION_1_4;
}
