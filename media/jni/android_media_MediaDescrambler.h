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

#include <binder/Status.h>
#include <media/cas/DescramblerAPI.h>
#include <media/stagefright/foundation/ABase.h>
#include <utils/Mutex.h>
#include <utils/RefBase.h>

namespace android {
class IMemory;
class MemoryDealer;
namespace media {
class IDescrambler;
};
using namespace media;
using binder::Status;

struct JDescrambler : public RefBase {
    JDescrambler(JNIEnv *env, jobject descramberBinderObj);

    Status descramble(
            jbyte key,
            size_t numSubSamples,
            ssize_t totalLength,
            DescramblerPlugin::SubSample *subSamples,
            const void *srcPtr,
            jint srcOffset,
            void *dstPtr,
            jint dstOffset,
            ssize_t *result);

protected:
    virtual ~JDescrambler();

private:
    sp<IDescrambler> mDescrambler;
    sp<IMemory> mMem;
    sp<MemoryDealer> mDealer;
    Mutex mSharedMemLock;

    void ensureBufferCapacity(size_t neededSize);

    DISALLOW_EVIL_CONSTRUCTORS(JDescrambler);
};

}  // namespace android

#endif  // _ANDROID_MEDIA_DESCRAMBLER_H_
