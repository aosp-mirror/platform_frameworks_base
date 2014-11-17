/*
 * Copyright 2013, The Android Open Source Project
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

#ifndef _ANDROID_MEDIA_MEDIAHTTPCONNECTION_H_
#define _ANDROID_MEDIA_MEDIAHTTPCONNECTION_H_

#include "jni.h"

#include <media/stagefright/foundation/ABase.h>
#include <utils/RefBase.h>

namespace android {

class IMemory;
class MemoryDealer;

struct JMediaHTTPConnection : public RefBase {
    enum {
        kBufferSize = 32768,
    };

    JMediaHTTPConnection(JNIEnv *env, jobject thiz);

    sp<IMemory> getIMemory();

    jbyteArray getByteArrayObj();

protected:
    virtual ~JMediaHTTPConnection();

private:
    jclass mClass;
    jweak mObject;
    jbyteArray mByteArrayObj;

    sp<MemoryDealer> mDealer;
    sp<IMemory> mMemory;

    DISALLOW_EVIL_CONSTRUCTORS(JMediaHTTPConnection);
};

}  // namespace android

#endif  // _ANDROID_MEDIA_MEDIAHTTPCONNECTION_H_
