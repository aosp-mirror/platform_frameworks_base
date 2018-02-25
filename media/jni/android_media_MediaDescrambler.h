/*
 * Copyright 2017, The Android Open Source Project
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

#ifndef _ANDROID_MEDIA_DESCRAMBLER_H_
#define _ANDROID_MEDIA_DESCRAMBLER_H_

#include "jni.h"

#include <android/hardware/cas/native/1.0/IDescrambler.h>

#include <media/stagefright/foundation/ABase.h>
#include <utils/Mutex.h>

namespace android {
class IMemory;
class MemoryDealer;

namespace hardware {
class HidlMemory;
};
using hardware::HidlMemory;
using hardware::hidl_string;
using hardware::hidl_vec;
using namespace hardware::cas::V1_0;
using namespace hardware::cas::native::V1_0;

struct JDescrambler : public RefBase {
    JDescrambler(JNIEnv *env, jobject descramberBinderObj);

    status_t descramble(
            jbyte key,
            ssize_t totalLength,
            const hidl_vec<SubSample>& subSamples,
            const void *srcPtr,
            jint srcOffset,
            void *dstPtr,
            jint dstOffset,
            Status *status,
            uint32_t *bytesWritten,
            hidl_string *detailedError);

    static sp<IDescrambler> GetDescrambler(JNIEnv *env, jobject obj);

protected:
    virtual ~JDescrambler();

private:
    sp<IDescrambler> mDescrambler;
    sp<IMemory> mMem;
    sp<MemoryDealer> mDealer;
    sp<HidlMemory> mHidlMemory;
    SharedBuffer mDescramblerSrcBuffer;

    Mutex mSharedMemLock;

    bool ensureBufferCapacity(size_t neededSize);

    DISALLOW_EVIL_CONSTRUCTORS(JDescrambler);
};

}  // namespace android

#endif  // _ANDROID_MEDIA_DESCRAMBLER_H_
