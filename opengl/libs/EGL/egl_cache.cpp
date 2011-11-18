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

#include "egl_cache.h"
#include "egl_display.h"
#include "egl_impl.h"
#include "egldefs.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

// Cache size limits.
static const size_t maxKeySize = 1024;
static const size_t maxValueSize = 4096;
static const size_t maxTotalSize = 64 * 1024;

// Cache file header
static const char* cacheFileMagic = "EGL$";
static const size_t cacheFileHeaderSize = 8;

// The time in seconds to wait before saving newly inserted cache entries.
static const unsigned int deferredSaveDelay = 4;

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

#define BC_EXT_STR "EGL_ANDROID_blob_cache"

//
// Callback functions passed to EGL.
//
static void setBlob(const void* key, EGLsizeiANDROID keySize,
        const void* value, EGLsizeiANDROID valueSize) {
    egl_cache_t::get()->setBlob(key, keySize, value, valueSize);
}

static EGLsizeiANDROID getBlob(const void* key, EGLsizeiANDROID keySize,
        void* value, EGLsizeiANDROID valueSize) {
    return egl_cache_t::get()->getBlob(key, keySize, value, valueSize);
}

//
// egl_cache_t definition
//
egl_cache_t::egl_cache_t() :
        mInitialized(false),
        mBlobCache(NULL) {
}

egl_cache_t::~egl_cache_t() {
}

egl_cache_t egl_cache_t::sCache;

egl_cache_t* egl_cache_t::get() {
    return &sCache;
}

void egl_cache_t::initialize(egl_display_t *display) {
    Mutex::Autolock lock(mMutex);
    for (int i = 0; i < IMPL_NUM_IMPLEMENTATIONS; i++) {
        egl_connection_t* const cnx = &gEGLImpl[i];
        if (cnx->dso && cnx->major >= 0 && cnx->minor >= 0) {
            const char* exts = display->disp[i].queryString.extensions;
            size_t bcExtLen = strlen(BC_EXT_STR);
            size_t extsLen = strlen(exts);
            bool equal = !strcmp(BC_EXT_STR, exts);
            bool atStart = !strncmp(BC_EXT_STR " ", exts, bcExtLen+1);
            bool atEnd = (bcExtLen+1) < extsLen &&
                    !strcmp(" " BC_EXT_STR, exts + extsLen - (bcExtLen+1));
            bool inMiddle = strstr(exts, " " BC_EXT_STR " ");
            if (equal || atStart || atEnd || inMiddle) {
                PFNEGLSETBLOBCACHEFUNCSANDROIDPROC eglSetBlobCacheFuncsANDROID;
                eglSetBlobCacheFuncsANDROID =
                        reinterpret_cast<PFNEGLSETBLOBCACHEFUNCSANDROIDPROC>(
                            cnx->egl.eglGetProcAddress(
                                    "eglSetBlobCacheFuncsANDROID"));
                if (eglSetBlobCacheFuncsANDROID == NULL) {
                    LOGE("EGL_ANDROID_blob_cache advertised by display %d, "
                            "but unable to get eglSetBlobCacheFuncsANDROID", i);
                    continue;
                }

                eglSetBlobCacheFuncsANDROID(display->disp[i].dpy,
                        android::setBlob, android::getBlob);
                EGLint err = cnx->egl.eglGetError();
                if (err != EGL_SUCCESS) {
                    LOGE("eglSetBlobCacheFuncsANDROID resulted in an error: "
                            "%#x", err);
                }
            }
        }
    }
    mInitialized = true;
}

void egl_cache_t::terminate() {
    Mutex::Autolock lock(mMutex);
    if (mBlobCache != NULL) {
        saveBlobCacheLocked();
        mBlobCache = NULL;
    }
    mInitialized = false;
}

void egl_cache_t::setBlob(const void* key, EGLsizeiANDROID keySize,
        const void* value, EGLsizeiANDROID valueSize) {
    Mutex::Autolock lock(mMutex);

    if (keySize < 0 || valueSize < 0) {
        LOGW("EGL_ANDROID_blob_cache set: negative sizes are not allowed");
        return;
    }

    if (mInitialized) {
        sp<BlobCache> bc = getBlobCacheLocked();
        bc->set(key, keySize, value, valueSize);

        if (!mSavePending) {
            class DeferredSaveThread : public Thread {
            public:
                DeferredSaveThread() : Thread(false) {}

                virtual bool threadLoop() {
                    sleep(deferredSaveDelay);
                    egl_cache_t* c = egl_cache_t::get();
                    Mutex::Autolock lock(c->mMutex);
                    if (c->mInitialized) {
                        c->saveBlobCacheLocked();
                    }
                    c->mSavePending = false;
                    return false;
                }
            };

            // The thread will hold a strong ref to itself until it has finished
            // running, so there's no need to keep a ref around.
            sp<Thread> deferredSaveThread(new DeferredSaveThread());
            mSavePending = true;
            deferredSaveThread->run();
        }
    }
}

EGLsizeiANDROID egl_cache_t::getBlob(const void* key, EGLsizeiANDROID keySize,
        void* value, EGLsizeiANDROID valueSize) {
    Mutex::Autolock lock(mMutex);

    if (keySize < 0 || valueSize < 0) {
        LOGW("EGL_ANDROID_blob_cache set: negative sizes are not allowed");
        return 0;
    }

    if (mInitialized) {
        sp<BlobCache> bc = getBlobCacheLocked();
        return bc->get(key, keySize, value, valueSize);
    }
    return 0;
}

void egl_cache_t::setCacheFilename(const char* filename) {
    Mutex::Autolock lock(mMutex);
    mFilename = filename;
}

sp<BlobCache> egl_cache_t::getBlobCacheLocked() {
    if (mBlobCache == NULL) {
        mBlobCache = new BlobCache(maxKeySize, maxValueSize, maxTotalSize);
        loadBlobCacheLocked();
    }
    return mBlobCache;
}

static uint32_t crc32c(const uint8_t* buf, size_t len) {
    const uint32_t polyBits = 0x82F63B78;
    uint32_t r = 0;
    for (size_t i = 0; i < len; i++) {
        r ^= buf[i];
        for (int j = 0; j < 8; j++) {
            if (r & 1) {
                r = (r >> 1) ^ polyBits;
            } else {
                r >>= 1;
            }
        }
    }
    return r;
}

void egl_cache_t::saveBlobCacheLocked() {
    if (mFilename.length() > 0) {
        size_t cacheSize = mBlobCache->getFlattenedSize();
        size_t headerSize = cacheFileHeaderSize;
        const char* fname = mFilename.string();

        // Try to create the file with no permissions so we can write it
        // without anyone trying to read it.
        int fd = open(fname, O_CREAT | O_EXCL | O_RDWR, 0);
        if (fd == -1) {
            if (errno == EEXIST) {
                // The file exists, delete it and try again.
                if (unlink(fname) == -1) {
                    // No point in retrying if the unlink failed.
                    LOGE("error unlinking cache file %s: %s (%d)", fname,
                            strerror(errno), errno);
                    return;
                }
                // Retry now that we've unlinked the file.
                fd = open(fname, O_CREAT | O_EXCL | O_RDWR, 0);
            }
            if (fd == -1) {
                LOGE("error creating cache file %s: %s (%d)", fname,
                        strerror(errno), errno);
                return;
            }
        }

        size_t fileSize = headerSize + cacheSize;
        if (ftruncate(fd, fileSize) == -1) {
            LOGE("error setting cache file size: %s (%d)", strerror(errno),
                    errno);
            close(fd);
            unlink(fname);
            return;
        }

        uint8_t* buf = reinterpret_cast<uint8_t*>(mmap(NULL, fileSize,
                PROT_WRITE, MAP_SHARED, fd, 0));
        if (buf == MAP_FAILED) {
            LOGE("error mmaping cache file: %s (%d)", strerror(errno),
                    errno);
            close(fd);
            unlink(fname);
            return;
        }

        status_t err = mBlobCache->flatten(buf + headerSize, cacheSize, NULL,
                0);
        if (err != OK) {
            LOGE("error writing cache contents: %s (%d)", strerror(-err),
                    -err);
            munmap(buf, fileSize);
            close(fd);
            unlink(fname);
            return;
        }

        // Write the file magic and CRC
        memcpy(buf, cacheFileMagic, 4);
        uint32_t* crc = reinterpret_cast<uint32_t*>(buf + 4);
        *crc = crc32c(buf + headerSize, cacheSize);

        munmap(buf, fileSize);
        fchmod(fd, S_IRUSR);
        close(fd);
    }
}

void egl_cache_t::loadBlobCacheLocked() {
    if (mFilename.length() > 0) {
        size_t headerSize = cacheFileHeaderSize;

        int fd = open(mFilename.string(), O_RDONLY, 0);
        if (fd == -1) {
            if (errno != ENOENT) {
                LOGE("error opening cache file %s: %s (%d)", mFilename.string(),
                        strerror(errno), errno);
            }
            return;
        }

        struct stat statBuf;
        if (fstat(fd, &statBuf) == -1) {
            LOGE("error stat'ing cache file: %s (%d)", strerror(errno), errno);
            close(fd);
            return;
        }

        // Sanity check the size before trying to mmap it.
        size_t fileSize = statBuf.st_size;
        if (fileSize > maxTotalSize * 2) {
            LOGE("cache file is too large: %#llx", statBuf.st_size);
            close(fd);
            return;
        }

        uint8_t* buf = reinterpret_cast<uint8_t*>(mmap(NULL, fileSize,
                PROT_READ, MAP_PRIVATE, fd, 0));
        if (buf == MAP_FAILED) {
            LOGE("error mmaping cache file: %s (%d)", strerror(errno),
                    errno);
            close(fd);
            return;
        }

        // Check the file magic and CRC
        size_t cacheSize = fileSize - headerSize;
        if (memcmp(buf, cacheFileMagic, 4) != 0) {
            LOGE("cache file has bad mojo");
            close(fd);
            return;
        }
        uint32_t* crc = reinterpret_cast<uint32_t*>(buf + 4);
        if (crc32c(buf + headerSize, cacheSize) != *crc) {
            LOGE("cache file failed CRC check");
            close(fd);
            return;
        }

        status_t err = mBlobCache->unflatten(buf + headerSize, cacheSize, NULL,
                0);
        if (err != OK) {
            LOGE("error reading cache contents: %s (%d)", strerror(-err),
                    -err);
            munmap(buf, fileSize);
            close(fd);
            return;
        }

        munmap(buf, fileSize);
        close(fd);
    }
}

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------
