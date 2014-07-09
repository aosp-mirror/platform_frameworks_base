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

#include <utils/misc.h>
#include <sys/ioctl.h>
#include <sys/mount.h>

#include <inttypes.h>
#include <fcntl.h>

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

    static jlong com_android_server_PeristentDataBlockService_getBlockDeviceSize(JNIEnv *env, jclass, jstring jpath) {
        const char *path = env->GetStringUTFChars(jpath, 0);
        int fd = open(path, O_RDONLY);

        if (fd < 0)
            return 0;

        return get_block_device_size(fd);
    }

    static JNINativeMethod sMethods[] = {
         /* name, signature, funcPtr */
        {"getBlockDeviceSize", "(Ljava/lang/String;)J", (void*)com_android_server_PeristentDataBlockService_getBlockDeviceSize},
    };

    int register_android_server_PersistentDataBlockService(JNIEnv* env)
    {
        return jniRegisterNativeMethods(env, "com/android/server/PersistentDataBlockService",
                                        sMethods, NELEM(sMethods));
    }

} /* namespace android */