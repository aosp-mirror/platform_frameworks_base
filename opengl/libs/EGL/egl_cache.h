/*
 ** Copyright 2011, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

#ifndef ANDROID_EGL_CACHE_H
#define ANDROID_EGL_CACHE_H

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <utils/BlobCache.h>
#include <utils/String8.h>
#include <utils/StrongPointer.h>

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

class egl_display_t;

class EGLAPI egl_cache_t {
public:

    // get returns a pointer to the singleton egl_cache_t object.  This
    // singleton object will never be destroyed.
    static egl_cache_t* get();

    // initialize puts the egl_cache_t into an initialized state, such that it
    // is able to insert and retrieve entries from the cache.  This should be
    // called when EGL is initialized.  When not in the initialized state the
    // getBlob and setBlob methods will return without performing any cache
    // operations.
    void initialize(egl_display_t* display);

    // terminate puts the egl_cache_t back into the uninitialized state.  When
    // in this state the getBlob and setBlob methods will return without
    // performing any cache operations.
    void terminate();

    // setBlob attempts to insert a new key/value blob pair into the cache.
    // This will be called by the hardware vendor's EGL implementation via the
    // EGL_ANDROID_blob_cache extension.
    void setBlob(const void* key, EGLsizeiANDROID keySize, const void* value,
        EGLsizeiANDROID valueSize);

    // getBlob attempts to retrieve the value blob associated with a given key
    // blob from cache.  This will be called by the hardware vendor's EGL
    // implementation via the EGL_ANDROID_blob_cache extension.
    EGLsizeiANDROID getBlob(const void* key, EGLsizeiANDROID keySize,
        void* value, EGLsizeiANDROID valueSize);

    // setCacheFilename sets the name of the file that should be used to store
    // cache contents from one program invocation to another.
    void setCacheFilename(const char* filename);

private:
    // Creation and (the lack of) destruction is handled internally.
    egl_cache_t();
    ~egl_cache_t();

    // Copying is disallowed.
    egl_cache_t(const egl_cache_t&); // not implemented
    void operator=(const egl_cache_t&); // not implemented

    // getBlobCacheLocked returns the BlobCache object being used to store the
    // key/value blob pairs.  If the BlobCache object has not yet been created,
    // this will do so, loading the serialized cache contents from disk if
    // possible.
    sp<BlobCache> getBlobCacheLocked();

    // saveBlobCache attempts to save the current contents of mBlobCache to
    // disk.
    void saveBlobCacheLocked();

    // loadBlobCache attempts to load the saved cache contents from disk into
    // mBlobCache.
    void loadBlobCacheLocked();

    // mInitialized indicates whether the egl_cache_t is in the initialized
    // state.  It is initialized to false at construction time, and gets set to
    // true when initialize is called.  It is set back to false when terminate
    // is called.  When in this state, the cache behaves as normal.  When not,
    // the getBlob and setBlob methods will return without performing any cache
    // operations.
    bool mInitialized;

    // mBlobCache is the cache in which the key/value blob pairs are stored.  It
    // is initially NULL, and will be initialized by getBlobCacheLocked the
    // first time it's needed.
    sp<BlobCache> mBlobCache;

    // mFilename is the name of the file for storing cache contents in between
    // program invocations.  It is initialized to an empty string at
    // construction time, and can be set with the setCacheFilename method.  An
    // empty string indicates that the cache should not be saved to or restored
    // from disk.
    String8 mFilename;

    // mSavePending indicates whether or not a deferred save operation is
    // pending.  Each time a key/value pair is inserted into the cache via
    // setBlob, a deferred save is initiated if one is not already pending.
    // This will wait some amount of time and then trigger a save of the cache
    // contents to disk.
    bool mSavePending;

    // mMutex is the mutex used to prevent concurrent access to the member
    // variables. It must be locked whenever the member variables are accessed.
    mutable Mutex mMutex;

    // sCache is the singleton egl_cache_t object.
    static egl_cache_t sCache;
};

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------

#endif // ANDROID_EGL_CACHE_H
