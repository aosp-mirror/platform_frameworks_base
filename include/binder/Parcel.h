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

#ifndef ANDROID_PARCEL_H
#define ANDROID_PARCEL_H

#include <cutils/native_handle.h>
#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/String16.h>
#include <utils/Vector.h>

// ---------------------------------------------------------------------------
namespace android {

class Flattenable;
class IBinder;
class IPCThreadState;
class ProcessState;
class String8;
class TextOutput;

struct flat_binder_object;  // defined in support_p/binder_module.h

class Parcel
{
public:
                        Parcel();
                        ~Parcel();
    
    const uint8_t*      data() const;
    size_t              dataSize() const;
    size_t              dataAvail() const;
    size_t              dataPosition() const;
    size_t              dataCapacity() const;
    
    status_t            setDataSize(size_t size);
    void                setDataPosition(size_t pos) const;
    status_t            setDataCapacity(size_t size);
    
    status_t            setData(const uint8_t* buffer, size_t len);

    status_t            appendFrom(Parcel *parcel, size_t start, size_t len);

    bool                hasFileDescriptors() const;

    // Writes the RPC header.
    status_t            writeInterfaceToken(const String16& interface);

    // Parses the RPC header, returning true if the interface name
    // in the header matches the expected interface from the caller.
    //
    // Additionally, enforceInterface does part of the work of
    // propagating the StrictMode policy mask, populating the current
    // IPCThreadState, which as an optimization may optionally be
    // passed in.
    bool                enforceInterface(const String16& interface,
                                         IPCThreadState* threadState = NULL) const;
    bool                checkInterface(IBinder*) const;

    void                freeData();

    const size_t*       objects() const;
    size_t              objectsCount() const;
    
    status_t            errorCheck() const;
    void                setError(status_t err);
    
    status_t            write(const void* data, size_t len);
    void*               writeInplace(size_t len);
    status_t            writeUnpadded(const void* data, size_t len);
    status_t            writeInt32(int32_t val);
    status_t            writeInt64(int64_t val);
    status_t            writeFloat(float val);
    status_t            writeDouble(double val);
    status_t            writeIntPtr(intptr_t val);
    status_t            writeCString(const char* str);
    status_t            writeString8(const String8& str);
    status_t            writeString16(const String16& str);
    status_t            writeString16(const char16_t* str, size_t len);
    status_t            writeStrongBinder(const sp<IBinder>& val);
    status_t            writeWeakBinder(const wp<IBinder>& val);
    status_t            write(const Flattenable& val);

    // Place a native_handle into the parcel (the native_handle's file-
    // descriptors are dup'ed, so it is safe to delete the native_handle
    // when this function returns). 
    // Doesn't take ownership of the native_handle.
    status_t            writeNativeHandle(const native_handle* handle);
    
    // Place a file descriptor into the parcel.  The given fd must remain
    // valid for the lifetime of the parcel.
    status_t            writeFileDescriptor(int fd);
    
    // Place a file descriptor into the parcel.  A dup of the fd is made, which
    // will be closed once the parcel is destroyed.
    status_t            writeDupFileDescriptor(int fd);
    
    status_t            writeObject(const flat_binder_object& val, bool nullMetaData);

    // Like Parcel.java's writeNoException().  Just writes a zero int32.
    // Currently the native implementation doesn't do any of the StrictMode
    // stack gathering and serialization that the Java implementation does.
    status_t            writeNoException();

    void                remove(size_t start, size_t amt);
    
    status_t            read(void* outData, size_t len) const;
    const void*         readInplace(size_t len) const;
    int32_t             readInt32() const;
    status_t            readInt32(int32_t *pArg) const;
    int64_t             readInt64() const;
    status_t            readInt64(int64_t *pArg) const;
    float               readFloat() const;
    status_t            readFloat(float *pArg) const;
    double              readDouble() const;
    status_t            readDouble(double *pArg) const;
    intptr_t            readIntPtr() const;
    status_t            readIntPtr(intptr_t *pArg) const;

    const char*         readCString() const;
    String8             readString8() const;
    String16            readString16() const;
    const char16_t*     readString16Inplace(size_t* outLen) const;
    sp<IBinder>         readStrongBinder() const;
    wp<IBinder>         readWeakBinder() const;
    status_t            read(Flattenable& val) const;

    // Like Parcel.java's readExceptionCode().  Reads the first int32
    // off of a Parcel's header, returning 0 or the negative error
    // code on exceptions, but also deals with skipping over rich
    // response headers.  Callers should use this to read & parse the
    // response headers rather than doing it by hand.
    int32_t             readExceptionCode() const;

    // Retrieve native_handle from the parcel. This returns a copy of the
    // parcel's native_handle (the caller takes ownership). The caller
    // must free the native_handle with native_handle_close() and 
    // native_handle_delete().
    native_handle*     readNativeHandle() const;

    
    // Retrieve a file descriptor from the parcel.  This returns the raw fd
    // in the parcel, which you do not own -- use dup() to get your own copy.
    int                 readFileDescriptor() const;
    
    const flat_binder_object* readObject(bool nullMetaData) const;

    // Explicitly close all file descriptors in the parcel.
    void                closeFileDescriptors();
    
    typedef void        (*release_func)(Parcel* parcel,
                                        const uint8_t* data, size_t dataSize,
                                        const size_t* objects, size_t objectsSize,
                                        void* cookie);
                        
    const uint8_t*      ipcData() const;
    size_t              ipcDataSize() const;
    const size_t*       ipcObjects() const;
    size_t              ipcObjectsCount() const;
    void                ipcSetDataReference(const uint8_t* data, size_t dataSize,
                                            const size_t* objects, size_t objectsCount,
                                            release_func relFunc, void* relCookie);
    
    void                print(TextOutput& to, uint32_t flags = 0) const;
        
private:
                        Parcel(const Parcel& o);
    Parcel&             operator=(const Parcel& o);
    
    status_t            finishWrite(size_t len);
    void                releaseObjects();
    void                acquireObjects();
    status_t            growData(size_t len);
    status_t            restartWrite(size_t desired);
    status_t            continueWrite(size_t desired);
    void                freeDataNoInit();
    void                initState();
    void                scanForFds() const;
                        
    template<class T>
    status_t            readAligned(T *pArg) const;

    template<class T>   T readAligned() const;

    template<class T>
    status_t            writeAligned(T val);

    status_t            mError;
    uint8_t*            mData;
    size_t              mDataSize;
    size_t              mDataCapacity;
    mutable size_t      mDataPos;
    size_t*             mObjects;
    size_t              mObjectsSize;
    size_t              mObjectsCapacity;
    mutable size_t      mNextObjectHint;

    mutable bool        mFdsKnown;
    mutable bool        mHasFds;
    
    release_func        mOwner;
    void*               mOwnerCookie;
};

// ---------------------------------------------------------------------------

inline TextOutput& operator<<(TextOutput& to, const Parcel& parcel)
{
    parcel.print(to);
    return to;
}

// ---------------------------------------------------------------------------

// Generic acquire and release of objects.
void acquire_object(const sp<ProcessState>& proc,
                    const flat_binder_object& obj, const void* who);
void release_object(const sp<ProcessState>& proc,
                    const flat_binder_object& obj, const void* who);

void flatten_binder(const sp<ProcessState>& proc,
                    const sp<IBinder>& binder, flat_binder_object* out);
void flatten_binder(const sp<ProcessState>& proc,
                    const wp<IBinder>& binder, flat_binder_object* out);
status_t unflatten_binder(const sp<ProcessState>& proc,
                          const flat_binder_object& flat, sp<IBinder>* out);
status_t unflatten_binder(const sp<ProcessState>& proc,
                          const flat_binder_object& flat, wp<IBinder>* out);

}; // namespace android

// ---------------------------------------------------------------------------

#endif // ANDROID_PARCEL_H
