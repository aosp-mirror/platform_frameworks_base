/*
 * Copyright (C) 2005 The Android Open Source Project
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

#define LOG_TAG "Parcel"
//#define LOG_NDEBUG 0

#include <binder/Parcel.h>

#include <binder/IPCThreadState.h>
#include <binder/Binder.h>
#include <binder/BpBinder.h>
#include <utils/Debug.h>
#include <binder/ProcessState.h>
#include <utils/Log.h>
#include <utils/String8.h>
#include <utils/String16.h>
#include <utils/TextOutput.h>
#include <utils/misc.h>
#include <utils/Flattenable.h>
#include <cutils/ashmem.h>

#include <private/binder/binder_module.h>

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <sys/mman.h>

#ifndef INT32_MAX
#define INT32_MAX ((int32_t)(2147483647))
#endif

#define LOG_REFS(...)
//#define LOG_REFS(...) ALOG(LOG_DEBUG, "Parcel", __VA_ARGS__)

// ---------------------------------------------------------------------------

#define PAD_SIZE(s) (((s)+3)&~3)

// Note: must be kept in sync with android/os/StrictMode.java's PENALTY_GATHER
#define STRICT_MODE_PENALTY_GATHER 0x100

// Note: must be kept in sync with android/os/Parcel.java's EX_HAS_REPLY_HEADER
#define EX_HAS_REPLY_HEADER -128

// Maximum size of a blob to transfer in-place.
static const size_t IN_PLACE_BLOB_LIMIT = 40 * 1024;

// XXX This can be made public if we want to provide
// support for typed data.
struct small_flat_data
{
    uint32_t type;
    uint32_t data;
};

namespace android {

void acquire_object(const sp<ProcessState>& proc,
    const flat_binder_object& obj, const void* who)
{
    switch (obj.type) {
        case BINDER_TYPE_BINDER:
            if (obj.binder) {
                LOG_REFS("Parcel %p acquiring reference on local %p", who, obj.cookie);
                static_cast<IBinder*>(obj.cookie)->incStrong(who);
            }
            return;
        case BINDER_TYPE_WEAK_BINDER:
            if (obj.binder)
                static_cast<RefBase::weakref_type*>(obj.binder)->incWeak(who);
            return;
        case BINDER_TYPE_HANDLE: {
            const sp<IBinder> b = proc->getStrongProxyForHandle(obj.handle);
            if (b != NULL) {
                LOG_REFS("Parcel %p acquiring reference on remote %p", who, b.get());
                b->incStrong(who);
            }
            return;
        }
        case BINDER_TYPE_WEAK_HANDLE: {
            const wp<IBinder> b = proc->getWeakProxyForHandle(obj.handle);
            if (b != NULL) b.get_refs()->incWeak(who);
            return;
        }
        case BINDER_TYPE_FD: {
            // intentionally blank -- nothing to do to acquire this, but we do
            // recognize it as a legitimate object type.
            return;
        }
    }

    ALOGD("Invalid object type 0x%08lx", obj.type);
}

void release_object(const sp<ProcessState>& proc,
    const flat_binder_object& obj, const void* who)
{
    switch (obj.type) {
        case BINDER_TYPE_BINDER:
            if (obj.binder) {
                LOG_REFS("Parcel %p releasing reference on local %p", who, obj.cookie);
                static_cast<IBinder*>(obj.cookie)->decStrong(who);
            }
            return;
        case BINDER_TYPE_WEAK_BINDER:
            if (obj.binder)
                static_cast<RefBase::weakref_type*>(obj.binder)->decWeak(who);
            return;
        case BINDER_TYPE_HANDLE: {
            const sp<IBinder> b = proc->getStrongProxyForHandle(obj.handle);
            if (b != NULL) {
                LOG_REFS("Parcel %p releasing reference on remote %p", who, b.get());
                b->decStrong(who);
            }
            return;
        }
        case BINDER_TYPE_WEAK_HANDLE: {
            const wp<IBinder> b = proc->getWeakProxyForHandle(obj.handle);
            if (b != NULL) b.get_refs()->decWeak(who);
            return;
        }
        case BINDER_TYPE_FD: {
            if (obj.cookie != (void*)0) close(obj.handle);
            return;
        }
    }

    ALOGE("Invalid object type 0x%08lx", obj.type);
}

inline static status_t finish_flatten_binder(
    const sp<IBinder>& binder, const flat_binder_object& flat, Parcel* out)
{
    return out->writeObject(flat, false);
}

status_t flatten_binder(const sp<ProcessState>& proc,
    const sp<IBinder>& binder, Parcel* out)
{
    flat_binder_object obj;
    
    obj.flags = 0x7f | FLAT_BINDER_FLAG_ACCEPTS_FDS;
    if (binder != NULL) {
        IBinder *local = binder->localBinder();
        if (!local) {
            BpBinder *proxy = binder->remoteBinder();
            if (proxy == NULL) {
                ALOGE("null proxy");
            }
            const int32_t handle = proxy ? proxy->handle() : 0;
            obj.type = BINDER_TYPE_HANDLE;
            obj.handle = handle;
            obj.cookie = NULL;
        } else {
            obj.type = BINDER_TYPE_BINDER;
            obj.binder = local->getWeakRefs();
            obj.cookie = local;
        }
    } else {
        obj.type = BINDER_TYPE_BINDER;
        obj.binder = NULL;
        obj.cookie = NULL;
    }
    
    return finish_flatten_binder(binder, obj, out);
}

status_t flatten_binder(const sp<ProcessState>& proc,
    const wp<IBinder>& binder, Parcel* out)
{
    flat_binder_object obj;
    
    obj.flags = 0x7f | FLAT_BINDER_FLAG_ACCEPTS_FDS;
    if (binder != NULL) {
        sp<IBinder> real = binder.promote();
        if (real != NULL) {
            IBinder *local = real->localBinder();
            if (!local) {
                BpBinder *proxy = real->remoteBinder();
                if (proxy == NULL) {
                    ALOGE("null proxy");
                }
                const int32_t handle = proxy ? proxy->handle() : 0;
                obj.type = BINDER_TYPE_WEAK_HANDLE;
                obj.handle = handle;
                obj.cookie = NULL;
            } else {
                obj.type = BINDER_TYPE_WEAK_BINDER;
                obj.binder = binder.get_refs();
                obj.cookie = binder.unsafe_get();
            }
            return finish_flatten_binder(real, obj, out);
        }
        
        // XXX How to deal?  In order to flatten the given binder,
        // we need to probe it for information, which requires a primary
        // reference...  but we don't have one.
        //
        // The OpenBinder implementation uses a dynamic_cast<> here,
        // but we can't do that with the different reference counting
        // implementation we are using.
        ALOGE("Unable to unflatten Binder weak reference!");
        obj.type = BINDER_TYPE_BINDER;
        obj.binder = NULL;
        obj.cookie = NULL;
        return finish_flatten_binder(NULL, obj, out);
    
    } else {
        obj.type = BINDER_TYPE_BINDER;
        obj.binder = NULL;
        obj.cookie = NULL;
        return finish_flatten_binder(NULL, obj, out);
    }
}

inline static status_t finish_unflatten_binder(
    BpBinder* proxy, const flat_binder_object& flat, const Parcel& in)
{
    return NO_ERROR;
}
    
status_t unflatten_binder(const sp<ProcessState>& proc,
    const Parcel& in, sp<IBinder>* out)
{
    const flat_binder_object* flat = in.readObject(false);
    
    if (flat) {
        switch (flat->type) {
            case BINDER_TYPE_BINDER:
                *out = static_cast<IBinder*>(flat->cookie);
                return finish_unflatten_binder(NULL, *flat, in);
            case BINDER_TYPE_HANDLE:
                *out = proc->getStrongProxyForHandle(flat->handle);
                return finish_unflatten_binder(
                    static_cast<BpBinder*>(out->get()), *flat, in);
        }        
    }
    return BAD_TYPE;
}

status_t unflatten_binder(const sp<ProcessState>& proc,
    const Parcel& in, wp<IBinder>* out)
{
    const flat_binder_object* flat = in.readObject(false);
    
    if (flat) {
        switch (flat->type) {
            case BINDER_TYPE_BINDER:
                *out = static_cast<IBinder*>(flat->cookie);
                return finish_unflatten_binder(NULL, *flat, in);
            case BINDER_TYPE_WEAK_BINDER:
                if (flat->binder != NULL) {
                    out->set_object_and_refs(
                        static_cast<IBinder*>(flat->cookie),
                        static_cast<RefBase::weakref_type*>(flat->binder));
                } else {
                    *out = NULL;
                }
                return finish_unflatten_binder(NULL, *flat, in);
            case BINDER_TYPE_HANDLE:
            case BINDER_TYPE_WEAK_HANDLE:
                *out = proc->getWeakProxyForHandle(flat->handle);
                return finish_unflatten_binder(
                    static_cast<BpBinder*>(out->unsafe_get()), *flat, in);
        }
    }
    return BAD_TYPE;
}

// ---------------------------------------------------------------------------

Parcel::Parcel()
{
    initState();
}

Parcel::~Parcel()
{
    freeDataNoInit();
}

const uint8_t* Parcel::data() const
{
    return mData;
}

size_t Parcel::dataSize() const
{
    return (mDataSize > mDataPos ? mDataSize : mDataPos);
}

size_t Parcel::dataAvail() const
{
    // TODO: decide what to do about the possibility that this can
    // report an available-data size that exceeds a Java int's max
    // positive value, causing havoc.  Fortunately this will only
    // happen if someone constructs a Parcel containing more than two
    // gigabytes of data, which on typical phone hardware is simply
    // not possible.
    return dataSize() - dataPosition();
}

size_t Parcel::dataPosition() const
{
    return mDataPos;
}

size_t Parcel::dataCapacity() const
{
    return mDataCapacity;
}

status_t Parcel::setDataSize(size_t size)
{
    status_t err;
    err = continueWrite(size);
    if (err == NO_ERROR) {
        mDataSize = size;
        ALOGV("setDataSize Setting data size of %p to %d\n", this, mDataSize);
    }
    return err;
}

void Parcel::setDataPosition(size_t pos) const
{
    mDataPos = pos;
    mNextObjectHint = 0;
}

status_t Parcel::setDataCapacity(size_t size)
{
    if (size > mDataCapacity) return continueWrite(size);
    return NO_ERROR;
}

status_t Parcel::setData(const uint8_t* buffer, size_t len)
{
    status_t err = restartWrite(len);
    if (err == NO_ERROR) {
        memcpy(const_cast<uint8_t*>(data()), buffer, len);
        mDataSize = len;
        mFdsKnown = false;
    }
    return err;
}

status_t Parcel::appendFrom(const Parcel *parcel, size_t offset, size_t len)
{
    const sp<ProcessState> proc(ProcessState::self());
    status_t err;
    const uint8_t *data = parcel->mData;
    const size_t *objects = parcel->mObjects;
    size_t size = parcel->mObjectsSize;
    int startPos = mDataPos;
    int firstIndex = -1, lastIndex = -2;

    if (len == 0) {
        return NO_ERROR;
    }

    // range checks against the source parcel size
    if ((offset > parcel->mDataSize)
            || (len > parcel->mDataSize)
            || (offset + len > parcel->mDataSize)) {
        return BAD_VALUE;
    }

    // Count objects in range
    for (int i = 0; i < (int) size; i++) {
        size_t off = objects[i];
        if ((off >= offset) && (off < offset + len)) {
            if (firstIndex == -1) {
                firstIndex = i;
            }
            lastIndex = i;
        }
    }
    int numObjects = lastIndex - firstIndex + 1;

    if ((mDataSize+len) > mDataCapacity) {
        // grow data
        err = growData(len);
        if (err != NO_ERROR) {
            return err;
        }
    }

    // append data
    memcpy(mData + mDataPos, data + offset, len);
    mDataPos += len;
    mDataSize += len;

    err = NO_ERROR;

    if (numObjects > 0) {
        // grow objects
        if (mObjectsCapacity < mObjectsSize + numObjects) {
            int newSize = ((mObjectsSize + numObjects)*3)/2;
            size_t *objects =
                (size_t*)realloc(mObjects, newSize*sizeof(size_t));
            if (objects == (size_t*)0) {
                return NO_MEMORY;
            }
            mObjects = objects;
            mObjectsCapacity = newSize;
        }
        
        // append and acquire objects
        int idx = mObjectsSize;
        for (int i = firstIndex; i <= lastIndex; i++) {
            size_t off = objects[i] - offset + startPos;
            mObjects[idx++] = off;
            mObjectsSize++;

            flat_binder_object* flat
                = reinterpret_cast<flat_binder_object*>(mData + off);
            acquire_object(proc, *flat, this);

            if (flat->type == BINDER_TYPE_FD) {
                // If this is a file descriptor, we need to dup it so the
                // new Parcel now owns its own fd, and can declare that we
                // officially know we have fds.
                flat->handle = dup(flat->handle);
                flat->cookie = (void*)1;
                mHasFds = mFdsKnown = true;
                if (!mAllowFds) {
                    err = FDS_NOT_ALLOWED;
                }
            }
        }
    }

    return err;
}

bool Parcel::pushAllowFds(bool allowFds)
{
    const bool origValue = mAllowFds;
    if (!allowFds) {
        mAllowFds = false;
    }
    return origValue;
}

void Parcel::restoreAllowFds(bool lastValue)
{
    mAllowFds = lastValue;
}

bool Parcel::hasFileDescriptors() const
{
    if (!mFdsKnown) {
        scanForFds();
    }
    return mHasFds;
}

// Write RPC headers.  (previously just the interface token)
status_t Parcel::writeInterfaceToken(const String16& interface)
{
    writeInt32(IPCThreadState::self()->getStrictModePolicy() |
               STRICT_MODE_PENALTY_GATHER);
    // currently the interface identification token is just its name as a string
    return writeString16(interface);
}

bool Parcel::checkInterface(IBinder* binder) const
{
    return enforceInterface(binder->getInterfaceDescriptor());
}

bool Parcel::enforceInterface(const String16& interface,
                              IPCThreadState* threadState) const
{
    int32_t strictPolicy = readInt32();
    if (threadState == NULL) {
        threadState = IPCThreadState::self();
    }
    if ((threadState->getLastTransactionBinderFlags() &
         IBinder::FLAG_ONEWAY) != 0) {
      // For one-way calls, the callee is running entirely
      // disconnected from the caller, so disable StrictMode entirely.
      // Not only does disk/network usage not impact the caller, but
      // there's no way to commuicate back any violations anyway.
      threadState->setStrictModePolicy(0);
    } else {
      threadState->setStrictModePolicy(strictPolicy);
    }
    const String16 str(readString16());
    if (str == interface) {
        return true;
    } else {
        ALOGW("**** enforceInterface() expected '%s' but read '%s'\n",
                String8(interface).string(), String8(str).string());
        return false;
    }
}

const size_t* Parcel::objects() const
{
    return mObjects;
}

size_t Parcel::objectsCount() const
{
    return mObjectsSize;
}

status_t Parcel::errorCheck() const
{
    return mError;
}

void Parcel::setError(status_t err)
{
    mError = err;
}

status_t Parcel::finishWrite(size_t len)
{
    //printf("Finish write of %d\n", len);
    mDataPos += len;
    ALOGV("finishWrite Setting data pos of %p to %d\n", this, mDataPos);
    if (mDataPos > mDataSize) {
        mDataSize = mDataPos;
        ALOGV("finishWrite Setting data size of %p to %d\n", this, mDataSize);
    }
    //printf("New pos=%d, size=%d\n", mDataPos, mDataSize);
    return NO_ERROR;
}

status_t Parcel::writeUnpadded(const void* data, size_t len)
{
    size_t end = mDataPos + len;
    if (end < mDataPos) {
        // integer overflow
        return BAD_VALUE;
    }

    if (end <= mDataCapacity) {
restart_write:
        memcpy(mData+mDataPos, data, len);
        return finishWrite(len);
    }

    status_t err = growData(len);
    if (err == NO_ERROR) goto restart_write;
    return err;
}

status_t Parcel::write(const void* data, size_t len)
{
    void* const d = writeInplace(len);
    if (d) {
        memcpy(d, data, len);
        return NO_ERROR;
    }
    return mError;
}

void* Parcel::writeInplace(size_t len)
{
    const size_t padded = PAD_SIZE(len);

    // sanity check for integer overflow
    if (mDataPos+padded < mDataPos) {
        return NULL;
    }

    if ((mDataPos+padded) <= mDataCapacity) {
restart_write:
        //printf("Writing %ld bytes, padded to %ld\n", len, padded);
        uint8_t* const data = mData+mDataPos;

        // Need to pad at end?
        if (padded != len) {
#if BYTE_ORDER == BIG_ENDIAN
            static const uint32_t mask[4] = {
                0x00000000, 0xffffff00, 0xffff0000, 0xff000000
            };
#endif
#if BYTE_ORDER == LITTLE_ENDIAN
            static const uint32_t mask[4] = {
                0x00000000, 0x00ffffff, 0x0000ffff, 0x000000ff
            };
#endif
            //printf("Applying pad mask: %p to %p\n", (void*)mask[padded-len],
            //    *reinterpret_cast<void**>(data+padded-4));
            *reinterpret_cast<uint32_t*>(data+padded-4) &= mask[padded-len];
        }

        finishWrite(padded);
        return data;
    }

    status_t err = growData(padded);
    if (err == NO_ERROR) goto restart_write;
    return NULL;
}

status_t Parcel::writeInt32(int32_t val)
{
    return writeAligned(val);
}

status_t Parcel::writeInt64(int64_t val)
{
    return writeAligned(val);
}

status_t Parcel::writeFloat(float val)
{
    return writeAligned(val);
}

status_t Parcel::writeDouble(double val)
{
    return writeAligned(val);
}

status_t Parcel::writeIntPtr(intptr_t val)
{
    return writeAligned(val);
}

status_t Parcel::writeCString(const char* str)
{
    return write(str, strlen(str)+1);
}

status_t Parcel::writeString8(const String8& str)
{
    status_t err = writeInt32(str.bytes());
    // only write string if its length is more than zero characters,
    // as readString8 will only read if the length field is non-zero.
    // this is slightly different from how writeString16 works.
    if (str.bytes() > 0 && err == NO_ERROR) {
        err = write(str.string(), str.bytes()+1);
    }
    return err;
}

status_t Parcel::writeString16(const String16& str)
{
    return writeString16(str.string(), str.size());
}

status_t Parcel::writeString16(const char16_t* str, size_t len)
{
    if (str == NULL) return writeInt32(-1);
    
    status_t err = writeInt32(len);
    if (err == NO_ERROR) {
        len *= sizeof(char16_t);
        uint8_t* data = (uint8_t*)writeInplace(len+sizeof(char16_t));
        if (data) {
            memcpy(data, str, len);
            *reinterpret_cast<char16_t*>(data+len) = 0;
            return NO_ERROR;
        }
        err = mError;
    }
    return err;
}

status_t Parcel::writeStrongBinder(const sp<IBinder>& val)
{
    return flatten_binder(ProcessState::self(), val, this);
}

status_t Parcel::writeWeakBinder(const wp<IBinder>& val)
{
    return flatten_binder(ProcessState::self(), val, this);
}

status_t Parcel::writeNativeHandle(const native_handle* handle)
{
    if (!handle || handle->version != sizeof(native_handle))
        return BAD_TYPE;

    status_t err;
    err = writeInt32(handle->numFds);
    if (err != NO_ERROR) return err;

    err = writeInt32(handle->numInts);
    if (err != NO_ERROR) return err;

    for (int i=0 ; err==NO_ERROR && i<handle->numFds ; i++)
        err = writeDupFileDescriptor(handle->data[i]);

    if (err != NO_ERROR) {
        ALOGD("write native handle, write dup fd failed");
        return err;
    }
    err = write(handle->data + handle->numFds, sizeof(int)*handle->numInts);
    return err;
}

status_t Parcel::writeFileDescriptor(int fd, bool takeOwnership)
{
    flat_binder_object obj;
    obj.type = BINDER_TYPE_FD;
    obj.flags = 0x7f | FLAT_BINDER_FLAG_ACCEPTS_FDS;
    obj.handle = fd;
    obj.cookie = (void*) (takeOwnership ? 1 : 0);
    return writeObject(obj, true);
}

status_t Parcel::writeDupFileDescriptor(int fd)
{
    int dupFd = dup(fd);
    if (dupFd < 0) {
        return -errno;
    }
    status_t err = writeFileDescriptor(dupFd, true /*takeOwnership*/);
    if (err) {
        close(dupFd);
    }
    return err;
}

status_t Parcel::writeBlob(size_t len, WritableBlob* outBlob)
{
    status_t status;

    if (!mAllowFds || len <= IN_PLACE_BLOB_LIMIT) {
        ALOGV("writeBlob: write in place");
        status = writeInt32(0);
        if (status) return status;

        void* ptr = writeInplace(len);
        if (!ptr) return NO_MEMORY;

        outBlob->init(false /*mapped*/, ptr, len);
        return NO_ERROR;
    }

    ALOGV("writeBlob: write to ashmem");
    int fd = ashmem_create_region("Parcel Blob", len);
    if (fd < 0) return NO_MEMORY;

    int result = ashmem_set_prot_region(fd, PROT_READ | PROT_WRITE);
    if (result < 0) {
        status = result;
    } else {
        void* ptr = ::mmap(NULL, len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (ptr == MAP_FAILED) {
            status = -errno;
        } else {
            result = ashmem_set_prot_region(fd, PROT_READ);
            if (result < 0) {
                status = result;
            } else {
                status = writeInt32(1);
                if (!status) {
                    status = writeFileDescriptor(fd, true /*takeOwnership*/);
                    if (!status) {
                        outBlob->init(true /*mapped*/, ptr, len);
                        return NO_ERROR;
                    }
                }
            }
        }
        ::munmap(ptr, len);
    }
    ::close(fd);
    return status;
}

status_t Parcel::write(const Flattenable& val)
{
    status_t err;

    // size if needed
    size_t len = val.getFlattenedSize();
    size_t fd_count = val.getFdCount();

    err = this->writeInt32(len);
    if (err) return err;

    err = this->writeInt32(fd_count);
    if (err) return err;

    // payload
    void* buf = this->writeInplace(PAD_SIZE(len));
    if (buf == NULL)
        return BAD_VALUE;

    int* fds = NULL;
    if (fd_count) {
        fds = new int[fd_count];
    }

    err = val.flatten(buf, len, fds, fd_count);
    for (size_t i=0 ; i<fd_count && err==NO_ERROR ; i++) {
        err = this->writeDupFileDescriptor( fds[i] );
    }

    if (fd_count) {
        delete [] fds;
    }

    return err;
}

status_t Parcel::writeObject(const flat_binder_object& val, bool nullMetaData)
{
    const bool enoughData = (mDataPos+sizeof(val)) <= mDataCapacity;
    const bool enoughObjects = mObjectsSize < mObjectsCapacity;
    if (enoughData && enoughObjects) {
restart_write:
        *reinterpret_cast<flat_binder_object*>(mData+mDataPos) = val;
        
        // Need to write meta-data?
        if (nullMetaData || val.binder != NULL) {
            mObjects[mObjectsSize] = mDataPos;
            acquire_object(ProcessState::self(), val, this);
            mObjectsSize++;
        }
        
        // remember if it's a file descriptor
        if (val.type == BINDER_TYPE_FD) {
            if (!mAllowFds) {
                return FDS_NOT_ALLOWED;
            }
            mHasFds = mFdsKnown = true;
        }

        return finishWrite(sizeof(flat_binder_object));
    }

    if (!enoughData) {
        const status_t err = growData(sizeof(val));
        if (err != NO_ERROR) return err;
    }
    if (!enoughObjects) {
        size_t newSize = ((mObjectsSize+2)*3)/2;
        size_t* objects = (size_t*)realloc(mObjects, newSize*sizeof(size_t));
        if (objects == NULL) return NO_MEMORY;
        mObjects = objects;
        mObjectsCapacity = newSize;
    }
    
    goto restart_write;
}

status_t Parcel::writeNoException()
{
    return writeInt32(0);
}

void Parcel::remove(size_t start, size_t amt)
{
    LOG_ALWAYS_FATAL("Parcel::remove() not yet implemented!");
}

status_t Parcel::read(void* outData, size_t len) const
{
    if ((mDataPos+PAD_SIZE(len)) >= mDataPos && (mDataPos+PAD_SIZE(len)) <= mDataSize) {
        memcpy(outData, mData+mDataPos, len);
        mDataPos += PAD_SIZE(len);
        ALOGV("read Setting data pos of %p to %d\n", this, mDataPos);
        return NO_ERROR;
    }
    return NOT_ENOUGH_DATA;
}

const void* Parcel::readInplace(size_t len) const
{
    if ((mDataPos+PAD_SIZE(len)) >= mDataPos && (mDataPos+PAD_SIZE(len)) <= mDataSize) {
        const void* data = mData+mDataPos;
        mDataPos += PAD_SIZE(len);
        ALOGV("readInplace Setting data pos of %p to %d\n", this, mDataPos);
        return data;
    }
    return NULL;
}

template<class T>
status_t Parcel::readAligned(T *pArg) const {
    COMPILE_TIME_ASSERT_FUNCTION_SCOPE(PAD_SIZE(sizeof(T)) == sizeof(T));

    if ((mDataPos+sizeof(T)) <= mDataSize) {
        const void* data = mData+mDataPos;
        mDataPos += sizeof(T);
        *pArg =  *reinterpret_cast<const T*>(data);
        return NO_ERROR;
    } else {
        return NOT_ENOUGH_DATA;
    }
}

template<class T>
T Parcel::readAligned() const {
    T result;
    if (readAligned(&result) != NO_ERROR) {
        result = 0;
    }

    return result;
}

template<class T>
status_t Parcel::writeAligned(T val) {
    COMPILE_TIME_ASSERT_FUNCTION_SCOPE(PAD_SIZE(sizeof(T)) == sizeof(T));

    if ((mDataPos+sizeof(val)) <= mDataCapacity) {
restart_write:
        *reinterpret_cast<T*>(mData+mDataPos) = val;
        return finishWrite(sizeof(val));
    }

    status_t err = growData(sizeof(val));
    if (err == NO_ERROR) goto restart_write;
    return err;
}

status_t Parcel::readInt32(int32_t *pArg) const
{
    return readAligned(pArg);
}

int32_t Parcel::readInt32() const
{
    return readAligned<int32_t>();
}


status_t Parcel::readInt64(int64_t *pArg) const
{
    return readAligned(pArg);
}


int64_t Parcel::readInt64() const
{
    return readAligned<int64_t>();
}

status_t Parcel::readFloat(float *pArg) const
{
    return readAligned(pArg);
}


float Parcel::readFloat() const
{
    return readAligned<float>();
}

status_t Parcel::readDouble(double *pArg) const
{
    return readAligned(pArg);
}


double Parcel::readDouble() const
{
    return readAligned<double>();
}

status_t Parcel::readIntPtr(intptr_t *pArg) const
{
    return readAligned(pArg);
}


intptr_t Parcel::readIntPtr() const
{
    return readAligned<intptr_t>();
}


const char* Parcel::readCString() const
{
    const size_t avail = mDataSize-mDataPos;
    if (avail > 0) {
        const char* str = reinterpret_cast<const char*>(mData+mDataPos);
        // is the string's trailing NUL within the parcel's valid bounds?
        const char* eos = reinterpret_cast<const char*>(memchr(str, 0, avail));
        if (eos) {
            const size_t len = eos - str;
            mDataPos += PAD_SIZE(len+1);
            ALOGV("readCString Setting data pos of %p to %d\n", this, mDataPos);
            return str;
        }
    }
    return NULL;
}

String8 Parcel::readString8() const
{
    int32_t size = readInt32();
    // watch for potential int overflow adding 1 for trailing NUL
    if (size > 0 && size < INT32_MAX) {
        const char* str = (const char*)readInplace(size+1);
        if (str) return String8(str, size);
    }
    return String8();
}

String16 Parcel::readString16() const
{
    size_t len;
    const char16_t* str = readString16Inplace(&len);
    if (str) return String16(str, len);
    ALOGE("Reading a NULL string not supported here.");
    return String16();
}

const char16_t* Parcel::readString16Inplace(size_t* outLen) const
{
    int32_t size = readInt32();
    // watch for potential int overflow from size+1
    if (size >= 0 && size < INT32_MAX) {
        *outLen = size;
        const char16_t* str = (const char16_t*)readInplace((size+1)*sizeof(char16_t));
        if (str != NULL) {
            return str;
        }
    }
    *outLen = 0;
    return NULL;
}

sp<IBinder> Parcel::readStrongBinder() const
{
    sp<IBinder> val;
    unflatten_binder(ProcessState::self(), *this, &val);
    return val;
}

wp<IBinder> Parcel::readWeakBinder() const
{
    wp<IBinder> val;
    unflatten_binder(ProcessState::self(), *this, &val);
    return val;
}

int32_t Parcel::readExceptionCode() const
{
  int32_t exception_code = readAligned<int32_t>();
  if (exception_code == EX_HAS_REPLY_HEADER) {
    int32_t header_size = readAligned<int32_t>();
    // Skip over fat responses headers.  Not used (or propagated) in
    // native code
    setDataPosition(dataPosition() + header_size);
    // And fat response headers are currently only used when there are no
    // exceptions, so return no error:
    return 0;
  }
  return exception_code;
}

native_handle* Parcel::readNativeHandle() const
{
    int numFds, numInts;
    status_t err;
    err = readInt32(&numFds);
    if (err != NO_ERROR) return 0;
    err = readInt32(&numInts);
    if (err != NO_ERROR) return 0;

    native_handle* h = native_handle_create(numFds, numInts);
    for (int i=0 ; err==NO_ERROR && i<numFds ; i++) {
        h->data[i] = dup(readFileDescriptor());
        if (h->data[i] < 0) err = BAD_VALUE;
    }
    err = read(h->data + numFds, sizeof(int)*numInts);
    if (err != NO_ERROR) {
        native_handle_close(h);
        native_handle_delete(h);
        h = 0;
    }
    return h;
}


int Parcel::readFileDescriptor() const
{
    const flat_binder_object* flat = readObject(true);
    if (flat) {
        switch (flat->type) {
            case BINDER_TYPE_FD:
                //ALOGI("Returning file descriptor %ld from parcel %p\n", flat->handle, this);
                return flat->handle;
        }        
    }
    return BAD_TYPE;
}

status_t Parcel::readBlob(size_t len, ReadableBlob* outBlob) const
{
    int32_t useAshmem;
    status_t status = readInt32(&useAshmem);
    if (status) return status;

    if (!useAshmem) {
        ALOGV("readBlob: read in place");
        const void* ptr = readInplace(len);
        if (!ptr) return BAD_VALUE;

        outBlob->init(false /*mapped*/, const_cast<void*>(ptr), len);
        return NO_ERROR;
    }

    ALOGV("readBlob: read from ashmem");
    int fd = readFileDescriptor();
    if (fd == int(BAD_TYPE)) return BAD_VALUE;

    void* ptr = ::mmap(NULL, len, PROT_READ, MAP_SHARED, fd, 0);
    if (!ptr) return NO_MEMORY;

    outBlob->init(true /*mapped*/, ptr, len);
    return NO_ERROR;
}

status_t Parcel::read(Flattenable& val) const
{
    // size
    const size_t len = this->readInt32();
    const size_t fd_count = this->readInt32();

    // payload
    void const* buf = this->readInplace(PAD_SIZE(len));
    if (buf == NULL)
        return BAD_VALUE;

    int* fds = NULL;
    if (fd_count) {
        fds = new int[fd_count];
    }

    status_t err = NO_ERROR;
    for (size_t i=0 ; i<fd_count && err==NO_ERROR ; i++) {
        fds[i] = dup(this->readFileDescriptor());
        if (fds[i] < 0) err = BAD_VALUE;
    }

    if (err == NO_ERROR) {
        err = val.unflatten(buf, len, fds, fd_count);
    }

    if (fd_count) {
        delete [] fds;
    }

    return err;
}
const flat_binder_object* Parcel::readObject(bool nullMetaData) const
{
    const size_t DPOS = mDataPos;
    if ((DPOS+sizeof(flat_binder_object)) <= mDataSize) {
        const flat_binder_object* obj
                = reinterpret_cast<const flat_binder_object*>(mData+DPOS);
        mDataPos = DPOS + sizeof(flat_binder_object);
        if (!nullMetaData && (obj->cookie == NULL && obj->binder == NULL)) {
            // When transferring a NULL object, we don't write it into
            // the object list, so we don't want to check for it when
            // reading.
            ALOGV("readObject Setting data pos of %p to %d\n", this, mDataPos);
            return obj;
        }
        
        // Ensure that this object is valid...
        size_t* const OBJS = mObjects;
        const size_t N = mObjectsSize;
        size_t opos = mNextObjectHint;
        
        if (N > 0) {
            ALOGV("Parcel %p looking for obj at %d, hint=%d\n",
                 this, DPOS, opos);
            
            // Start at the current hint position, looking for an object at
            // the current data position.
            if (opos < N) {
                while (opos < (N-1) && OBJS[opos] < DPOS) {
                    opos++;
                }
            } else {
                opos = N-1;
            }
            if (OBJS[opos] == DPOS) {
                // Found it!
                ALOGV("Parcel found obj %d at index %d with forward search",
                     this, DPOS, opos);
                mNextObjectHint = opos+1;
                ALOGV("readObject Setting data pos of %p to %d\n", this, mDataPos);
                return obj;
            }
        
            // Look backwards for it...
            while (opos > 0 && OBJS[opos] > DPOS) {
                opos--;
            }
            if (OBJS[opos] == DPOS) {
                // Found it!
                ALOGV("Parcel found obj %d at index %d with backward search",
                     this, DPOS, opos);
                mNextObjectHint = opos+1;
                ALOGV("readObject Setting data pos of %p to %d\n", this, mDataPos);
                return obj;
            }
        }
        ALOGW("Attempt to read object from Parcel %p at offset %d that is not in the object list",
             this, DPOS);
    }
    return NULL;
}

void Parcel::closeFileDescriptors()
{
    size_t i = mObjectsSize;
    if (i > 0) {
        //ALOGI("Closing file descriptors for %d objects...", mObjectsSize);
    }
    while (i > 0) {
        i--;
        const flat_binder_object* flat
            = reinterpret_cast<flat_binder_object*>(mData+mObjects[i]);
        if (flat->type == BINDER_TYPE_FD) {
            //ALOGI("Closing fd: %ld\n", flat->handle);
            close(flat->handle);
        }
    }
}

const uint8_t* Parcel::ipcData() const
{
    return mData;
}

size_t Parcel::ipcDataSize() const
{
    return (mDataSize > mDataPos ? mDataSize : mDataPos);
}

const size_t* Parcel::ipcObjects() const
{
    return mObjects;
}

size_t Parcel::ipcObjectsCount() const
{
    return mObjectsSize;
}

void Parcel::ipcSetDataReference(const uint8_t* data, size_t dataSize,
    const size_t* objects, size_t objectsCount, release_func relFunc, void* relCookie)
{
    freeDataNoInit();
    mError = NO_ERROR;
    mData = const_cast<uint8_t*>(data);
    mDataSize = mDataCapacity = dataSize;
    //ALOGI("setDataReference Setting data size of %p to %lu (pid=%d)\n", this, mDataSize, getpid());
    mDataPos = 0;
    ALOGV("setDataReference Setting data pos of %p to %d\n", this, mDataPos);
    mObjects = const_cast<size_t*>(objects);
    mObjectsSize = mObjectsCapacity = objectsCount;
    mNextObjectHint = 0;
    mOwner = relFunc;
    mOwnerCookie = relCookie;
    scanForFds();
}

void Parcel::print(TextOutput& to, uint32_t flags) const
{
    to << "Parcel(";
    
    if (errorCheck() != NO_ERROR) {
        const status_t err = errorCheck();
        to << "Error: " << (void*)err << " \"" << strerror(-err) << "\"";
    } else if (dataSize() > 0) {
        const uint8_t* DATA = data();
        to << indent << HexDump(DATA, dataSize()) << dedent;
        const size_t* OBJS = objects();
        const size_t N = objectsCount();
        for (size_t i=0; i<N; i++) {
            const flat_binder_object* flat
                = reinterpret_cast<const flat_binder_object*>(DATA+OBJS[i]);
            to << endl << "Object #" << i << " @ " << (void*)OBJS[i] << ": "
                << TypeCode(flat->type & 0x7f7f7f00)
                << " = " << flat->binder;
        }
    } else {
        to << "NULL";
    }
    
    to << ")";
}

void Parcel::releaseObjects()
{
    const sp<ProcessState> proc(ProcessState::self());
    size_t i = mObjectsSize;
    uint8_t* const data = mData;
    size_t* const objects = mObjects;
    while (i > 0) {
        i--;
        const flat_binder_object* flat
            = reinterpret_cast<flat_binder_object*>(data+objects[i]);
        release_object(proc, *flat, this);
    }
}

void Parcel::acquireObjects()
{
    const sp<ProcessState> proc(ProcessState::self());
    size_t i = mObjectsSize;
    uint8_t* const data = mData;
    size_t* const objects = mObjects;
    while (i > 0) {
        i--;
        const flat_binder_object* flat
            = reinterpret_cast<flat_binder_object*>(data+objects[i]);
        acquire_object(proc, *flat, this);
    }
}

void Parcel::freeData()
{
    freeDataNoInit();
    initState();
}

void Parcel::freeDataNoInit()
{
    if (mOwner) {
        //ALOGI("Freeing data ref of %p (pid=%d)\n", this, getpid());
        mOwner(this, mData, mDataSize, mObjects, mObjectsSize, mOwnerCookie);
    } else {
        releaseObjects();
        if (mData) free(mData);
        if (mObjects) free(mObjects);
    }
}

status_t Parcel::growData(size_t len)
{
    size_t newSize = ((mDataSize+len)*3)/2;
    return (newSize <= mDataSize)
            ? (status_t) NO_MEMORY
            : continueWrite(newSize);
}

status_t Parcel::restartWrite(size_t desired)
{
    if (mOwner) {
        freeData();
        return continueWrite(desired);
    }
    
    uint8_t* data = (uint8_t*)realloc(mData, desired);
    if (!data && desired > mDataCapacity) {
        mError = NO_MEMORY;
        return NO_MEMORY;
    }
    
    releaseObjects();
    
    if (data) {
        mData = data;
        mDataCapacity = desired;
    }
    
    mDataSize = mDataPos = 0;
    ALOGV("restartWrite Setting data size of %p to %d\n", this, mDataSize);
    ALOGV("restartWrite Setting data pos of %p to %d\n", this, mDataPos);
        
    free(mObjects);
    mObjects = NULL;
    mObjectsSize = mObjectsCapacity = 0;
    mNextObjectHint = 0;
    mHasFds = false;
    mFdsKnown = true;
    mAllowFds = true;
    
    return NO_ERROR;
}

status_t Parcel::continueWrite(size_t desired)
{
    // If shrinking, first adjust for any objects that appear
    // after the new data size.
    size_t objectsSize = mObjectsSize;
    if (desired < mDataSize) {
        if (desired == 0) {
            objectsSize = 0;
        } else {
            while (objectsSize > 0) {
                if (mObjects[objectsSize-1] < desired)
                    break;
                objectsSize--;
            }
        }
    }
    
    if (mOwner) {
        // If the size is going to zero, just release the owner's data.
        if (desired == 0) {
            freeData();
            return NO_ERROR;
        }

        // If there is a different owner, we need to take
        // posession.
        uint8_t* data = (uint8_t*)malloc(desired);
        if (!data) {
            mError = NO_MEMORY;
            return NO_MEMORY;
        }
        size_t* objects = NULL;
        
        if (objectsSize) {
            objects = (size_t*)malloc(objectsSize*sizeof(size_t));
            if (!objects) {
                mError = NO_MEMORY;
                return NO_MEMORY;
            }

            // Little hack to only acquire references on objects
            // we will be keeping.
            size_t oldObjectsSize = mObjectsSize;
            mObjectsSize = objectsSize;
            acquireObjects();
            mObjectsSize = oldObjectsSize;
        }
        
        if (mData) {
            memcpy(data, mData, mDataSize < desired ? mDataSize : desired);
        }
        if (objects && mObjects) {
            memcpy(objects, mObjects, objectsSize*sizeof(size_t));
        }
        //ALOGI("Freeing data ref of %p (pid=%d)\n", this, getpid());
        mOwner(this, mData, mDataSize, mObjects, mObjectsSize, mOwnerCookie);
        mOwner = NULL;

        mData = data;
        mObjects = objects;
        mDataSize = (mDataSize < desired) ? mDataSize : desired;
        ALOGV("continueWrite Setting data size of %p to %d\n", this, mDataSize);
        mDataCapacity = desired;
        mObjectsSize = mObjectsCapacity = objectsSize;
        mNextObjectHint = 0;

    } else if (mData) {
        if (objectsSize < mObjectsSize) {
            // Need to release refs on any objects we are dropping.
            const sp<ProcessState> proc(ProcessState::self());
            for (size_t i=objectsSize; i<mObjectsSize; i++) {
                const flat_binder_object* flat
                    = reinterpret_cast<flat_binder_object*>(mData+mObjects[i]);
                if (flat->type == BINDER_TYPE_FD) {
                    // will need to rescan because we may have lopped off the only FDs
                    mFdsKnown = false;
                }
                release_object(proc, *flat, this);
            }
            size_t* objects =
                (size_t*)realloc(mObjects, objectsSize*sizeof(size_t));
            if (objects) {
                mObjects = objects;
            }
            mObjectsSize = objectsSize;
            mNextObjectHint = 0;
        }

        // We own the data, so we can just do a realloc().
        if (desired > mDataCapacity) {
            uint8_t* data = (uint8_t*)realloc(mData, desired);
            if (data) {
                mData = data;
                mDataCapacity = desired;
            } else if (desired > mDataCapacity) {
                mError = NO_MEMORY;
                return NO_MEMORY;
            }
        } else {
            if (mDataSize > desired) {
                mDataSize = desired;
                ALOGV("continueWrite Setting data size of %p to %d\n", this, mDataSize);
            }
            if (mDataPos > desired) {
                mDataPos = desired;
                ALOGV("continueWrite Setting data pos of %p to %d\n", this, mDataPos);
            }
        }
        
    } else {
        // This is the first data.  Easy!
        uint8_t* data = (uint8_t*)malloc(desired);
        if (!data) {
            mError = NO_MEMORY;
            return NO_MEMORY;
        }
        
        if(!(mDataCapacity == 0 && mObjects == NULL
             && mObjectsCapacity == 0)) {
            ALOGE("continueWrite: %d/%p/%d/%d", mDataCapacity, mObjects, mObjectsCapacity, desired);
        }
        
        mData = data;
        mDataSize = mDataPos = 0;
        ALOGV("continueWrite Setting data size of %p to %d\n", this, mDataSize);
        ALOGV("continueWrite Setting data pos of %p to %d\n", this, mDataPos);
        mDataCapacity = desired;
    }

    return NO_ERROR;
}

void Parcel::initState()
{
    mError = NO_ERROR;
    mData = 0;
    mDataSize = 0;
    mDataCapacity = 0;
    mDataPos = 0;
    ALOGV("initState Setting data size of %p to %d\n", this, mDataSize);
    ALOGV("initState Setting data pos of %p to %d\n", this, mDataPos);
    mObjects = NULL;
    mObjectsSize = 0;
    mObjectsCapacity = 0;
    mNextObjectHint = 0;
    mHasFds = false;
    mFdsKnown = true;
    mAllowFds = true;
    mOwner = NULL;
}

void Parcel::scanForFds() const
{
    bool hasFds = false;
    for (size_t i=0; i<mObjectsSize; i++) {
        const flat_binder_object* flat
            = reinterpret_cast<const flat_binder_object*>(mData + mObjects[i]);
        if (flat->type == BINDER_TYPE_FD) {
            hasFds = true;
            break;
        }
    }
    mHasFds = hasFds;
    mFdsKnown = true;
}

// --- Parcel::Blob ---

Parcel::Blob::Blob() :
        mMapped(false), mData(NULL), mSize(0) {
}

Parcel::Blob::~Blob() {
    release();
}

void Parcel::Blob::release() {
    if (mMapped && mData) {
        ::munmap(mData, mSize);
    }
    clear();
}

void Parcel::Blob::init(bool mapped, void* data, size_t size) {
    mMapped = mapped;
    mData = data;
    mSize = size;
}

void Parcel::Blob::clear() {
    mMapped = false;
    mData = NULL;
    mSize = 0;
}

}; // namespace android
