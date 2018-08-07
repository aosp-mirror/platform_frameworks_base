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

#ifndef _ANDROID_MEDIA_MEDIA2HTTPCONNECTION_H_
#define _ANDROID_MEDIA_MEDIA2HTTPCONNECTION_H_

#include "jni.h"

#include <media/MediaHTTPConnection.h>
#include <media/stagefright/foundation/ABase.h>

namespace android {

struct JMedia2HTTPConnection : public MediaHTTPConnection {
    JMedia2HTTPConnection(JNIEnv *env, jobject thiz);

    virtual bool connect(
            const char *uri, const KeyedVector<String8, String8> *headers) override;

    virtual void disconnect() override;
    virtual ssize_t readAt(off64_t offset, void *data, size_t size) override;
    virtual off64_t getSize() override;
    virtual status_t getMIMEType(String8 *mimeType) override;
    virtual status_t getUri(String8 *uri) override;

protected:
    virtual ~JMedia2HTTPConnection();

private:
    jobject mMedia2HTTPConnectionObj;
    jmethodID mConnectMethod;
    jmethodID mDisconnectMethod;
    jmethodID mReadAtMethod;
    jmethodID mGetSizeMethod;
    jmethodID mGetMIMETypeMethod;
    jmethodID mGetUriMethod;

    jbyteArray mByteArrayObj;

    DISALLOW_EVIL_CONSTRUCTORS(JMedia2HTTPConnection);
};

}  // namespace android

#endif  // _ANDROID_MEDIA_MEDIA2HTTPCONNECTION_H_
