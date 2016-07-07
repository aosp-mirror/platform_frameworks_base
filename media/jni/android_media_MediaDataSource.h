/*
 * Copyright 2015, The Android Open Source Project
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

#ifndef _ANDROID_MEDIA_MEDIADATASOURCE_H_
#define _ANDROID_MEDIA_MEDIADATASOURCE_H_

#include "jni.h"

#include <media/IDataSource.h>
#include <media/stagefright/foundation/ABase.h>
#include <utils/Errors.h>
#include <utils/Mutex.h>

namespace android {

// The native counterpart to a Java android.media.MediaDataSource. It inherits from
// IDataSource so that it can be accessed remotely.
//
// If the java DataSource returns an error or throws an exception it
// will be considered to be in a broken state, and the only further call this
// will make is to close().
class JMediaDataSource : public BnDataSource {
public:
    enum {
        kBufferSize = 64 * 1024,
    };

    JMediaDataSource(JNIEnv *env, jobject source);
    virtual ~JMediaDataSource();

    virtual sp<IMemory> getIMemory();
    virtual ssize_t readAt(off64_t offset, size_t size);
    virtual status_t getSize(off64_t* size);
    virtual void close();
    virtual uint32_t getFlags();
    virtual String8 toString();
    virtual sp<DecryptHandle> DrmInitialization(const char *mime);

private:
    // Protect all member variables with mLock because this object will be
    // accessed on different binder worker threads.
    Mutex mLock;

    // The status of the java DataSource. Set to OK unless an error occurred or
    // close() was called.
    status_t mJavaObjStatus;
    // Only call the java getSize() once so the app can't change the size on us.
    bool mSizeIsCached;
    off64_t mCachedSize;
    sp<IMemory> mMemory;

    jobject mMediaDataSourceObj;
    jmethodID mReadMethod;
    jmethodID mGetSizeMethod;
    jmethodID mCloseMethod;
    jbyteArray mByteArrayObj;

    DISALLOW_EVIL_CONSTRUCTORS(JMediaDataSource);
};

}  // namespace android

#endif  // _ANDROID_MEDIA_MEDIADATASOURCE_H_
