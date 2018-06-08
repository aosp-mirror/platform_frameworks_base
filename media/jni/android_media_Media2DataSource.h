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

#ifndef _ANDROID_MEDIA_MEDIA2DATASOURCE_H_
#define _ANDROID_MEDIA_MEDIA2DATASOURCE_H_

#include "jni.h"

#include <media/DataSource.h>
#include <media/stagefright/foundation/ABase.h>
#include <utils/Errors.h>
#include <utils/Mutex.h>

namespace android {

// The native counterpart to a Java android.media.Media2DataSource. It inherits from
// DataSource.
//
// If the java DataSource returns an error or throws an exception it
// will be considered to be in a broken state, and the only further call this
// will make is to close().
class JMedia2DataSource : public DataSource {
public:
    JMedia2DataSource(JNIEnv *env, jobject source);
    virtual ~JMedia2DataSource();

    virtual status_t initCheck() const override;
    virtual ssize_t readAt(off64_t offset, void *data, size_t size) override;
    virtual status_t getSize(off64_t *size) override;

    virtual String8 toString() override;
    virtual String8 getMIMEType() const override;
    virtual void close() override;
private:
    // Protect all member variables with mLock because this object will be
    // accessed on different threads.
    Mutex mLock;

    // The status of the java DataSource. Set to OK unless an error occurred or
    // close() was called.
    status_t mJavaObjStatus;
    // Only call the java getSize() once so the app can't change the size on us.
    bool mSizeIsCached;
    off64_t mCachedSize;

    jobject mMedia2DataSourceObj;
    jmethodID mReadAtMethod;
    jmethodID mGetSizeMethod;
    jmethodID mCloseMethod;
    jbyteArray mByteArrayObj;

    DISALLOW_EVIL_CONSTRUCTORS(JMedia2DataSource);
};

}  // namespace android

#endif  // _ANDROID_MEDIA_MEDIA2DATASOURCE_H_
