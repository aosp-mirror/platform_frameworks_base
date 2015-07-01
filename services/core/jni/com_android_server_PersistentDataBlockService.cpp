/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <android_runtime/AndroidRuntime.h>
#include <JNIHelp.h>
#include <jni.h>
#include <ScopedUtfChars.h>

#include <utils/misc.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <utils/Log.h>


#include <inttypes.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>

namespace android {

    uint64_t get_block_device_size(int fd)
    {
        uint64_t size = 0;
        int ret;

        ret = ioctl(fd, BLKGETSIZE64, &size);

        if (ret)
            return 0;

        return size;
    }

    int wipe_block_device(int fd)
    {
        uint64_t range[2];
        int ret;
        uint64_t len = get_block_device_size(fd);

        range[0] = 0;
        range[1] = len;

        if (range[1] == 0)
            return 0;

        ret = ioctl(fd, BLKSECDISCARD, &range);
        if (ret < 0) {
            ALOGE("Something went wrong secure discarding block: %s\n", strerror(errno));
            range[0] = 0;
            range[1] = len;
            ret = ioctl(fd, BLKDISCARD, &range);
            if (ret < 0) {
                ALOGE("Discard failed: %s\n", strerror(errno));
                return -1;
            } else {
                ALOGE("Wipe via secure discard failed, used non-secure discard instead\n");
                return 0;
            }

        }

        return ret;
    }

    static jlong com_android_server_PersistentDataBlockService_getBlockDeviceSize(JNIEnv *env, jclass, jstring jpath)
    {
        ScopedUtfChars path(env, jpath);
        int fd = open(path.c_str(), O_RDONLY);

        if (fd < 0)
            return 0;

        return get_block_device_size(fd);
    }

    static int com_android_server_PersistentDataBlockService_wipe(JNIEnv *env, jclass, jstring jpath) {
        ScopedUtfChars path(env, jpath);
        int fd = open(path.c_str(), O_WRONLY);

        if (fd < 0)
            return 0;

        return wipe_block_device(fd);
    }

    static JNINativeMethod sMethods[] = {
         /* name, signature, funcPtr */
        {"nativeGetBlockDeviceSize", "(Ljava/lang/String;)J", (void*)com_android_server_PersistentDataBlockService_getBlockDeviceSize},
        {"nativeWipe", "(Ljava/lang/String;)I", (void*)com_android_server_PersistentDataBlockService_wipe},
    };

    int register_android_server_PersistentDataBlockService(JNIEnv* env)
    {
        return jniRegisterNativeMethods(env, "com/android/server/PersistentDataBlockService",
                                        sMethods, NELEM(sMethods));
    }

} /* namespace android */