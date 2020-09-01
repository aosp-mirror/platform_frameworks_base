/*
 * Copyright (C) 2006 The Android Open Source Project
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

#ifndef _ANDROID_GRAPHICS_UTILS_H_
#define _ANDROID_GRAPHICS_UTILS_H_

#include "SkStream.h"

#include <jni.h>
#include <androidfw/Asset.h>

namespace android {

class AssetStreamAdaptor : public SkStreamRewindable {
public:
    explicit AssetStreamAdaptor(Asset*);

    virtual bool rewind();
    virtual size_t read(void* buffer, size_t size);
    virtual bool hasLength() const { return true; }
    virtual size_t getLength() const;
    virtual bool hasPosition() const;
    virtual size_t getPosition() const;
    virtual bool seek(size_t position);
    virtual bool move(long offset);
    virtual bool isAtEnd() const;

protected:
    SkStreamRewindable* onDuplicate() const override;

private:
    Asset* fAsset;
};

/**
 *  Make a deep copy of the asset, and return it as a stream, or NULL if there
 *  was an error.
 *  FIXME: If we could "ref/reopen" the asset, we may not need to copy it here.
 */

SkMemoryStream* CopyAssetToStream(Asset*);

/** Restore the file descriptor's offset in our destructor
 */
class AutoFDSeek {
public:
    explicit AutoFDSeek(int fd) : fFD(fd) {
        fCurr = ::lseek(fd, 0, SEEK_CUR);
    }
    ~AutoFDSeek() {
        if (fCurr >= 0) {
            ::lseek(fFD, fCurr, SEEK_SET);
        }
    }
private:
    int     fFD;
    off64_t   fCurr;
};

jobject nullObjectReturn(const char msg[]);

/** Check if the file descriptor is seekable.
 */
bool isSeekable(int descriptor);

JNIEnv* get_env_or_die(JavaVM* jvm);

/**
 * Helper method for accessing the JNI interface pointer.
 *
 * Image decoding (which this supports) is started on a thread that is already
 * attached to the Java VM. But an AnimatedImageDrawable continues decoding on
 * the AnimatedImageThread, which is not attached. This will attach if
 * necessary.
 */
JNIEnv* requireEnv(JavaVM* jvm);

}; // namespace android

#endif  // _ANDROID_GRAPHICS_UTILS_H_
