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

//
// Shared file mapping class.
//

#define LOG_TAG "filemap"

#include <utils/FileMap.h>
#include <utils/Log.h>

#include <stdio.h>
#include <stdlib.h>

#ifdef HAVE_POSIX_FILEMAP
#include <sys/mman.h>
#endif

#include <string.h>
#include <memory.h>
#include <errno.h>
#include <assert.h>

using namespace android;

/*static*/ long FileMap::mPageSize = -1;


/*
 * Constructor.  Create an empty object.
 */
FileMap::FileMap(void)
    : mRefCount(1), mFileName(NULL), mBasePtr(NULL), mBaseLength(0),
      mDataPtr(NULL), mDataLength(0)
{
}

/*
 * Destructor.
 */
FileMap::~FileMap(void)
{
    assert(mRefCount == 0);

    //printf("+++ removing FileMap %p %u\n", mDataPtr, mDataLength);

    mRefCount = -100;       // help catch double-free
    if (mFileName != NULL) {
        free(mFileName);
    }
#ifdef HAVE_POSIX_FILEMAP    
    if (mBasePtr && munmap(mBasePtr, mBaseLength) != 0) {
        LOGD("munmap(%p, %d) failed\n", mBasePtr, (int) mBaseLength);
    }
#endif
#ifdef HAVE_WIN32_FILEMAP
    if (mBasePtr && UnmapViewOfFile(mBasePtr) == 0) {
        LOGD("UnmapViewOfFile(%p) failed, error = %ld\n", mBasePtr, 
              GetLastError() );
    }
    if (mFileMapping != INVALID_HANDLE_VALUE) {
        CloseHandle(mFileMapping);
    }
    CloseHandle(mFileHandle);
#endif
}


/*
 * Create a new mapping on an open file.
 *
 * Closing the file descriptor does not unmap the pages, so we don't
 * claim ownership of the fd.
 *
 * Returns "false" on failure.
 */
bool FileMap::create(const char* origFileName, int fd, off64_t offset, size_t length,
        bool readOnly)
{
#ifdef HAVE_WIN32_FILEMAP
    int     adjust;
    off64_t adjOffset;
    size_t  adjLength;

    if (mPageSize == -1) {
        SYSTEM_INFO  si;
        
        GetSystemInfo( &si );
        mPageSize = si.dwAllocationGranularity;
    }

    DWORD  protect = readOnly ? PAGE_READONLY : PAGE_READWRITE;
    
    mFileHandle  = (HANDLE) _get_osfhandle(fd);
    mFileMapping = CreateFileMapping( mFileHandle, NULL, protect, 0, 0, NULL);
    if (mFileMapping == NULL) {
        LOGE("CreateFileMapping(%p, %lx) failed with error %ld\n",
              mFileHandle, protect, GetLastError() );
        return false;
    }
    
    adjust    = offset % mPageSize;
    adjOffset = offset - adjust;
    adjLength = length + adjust;
    
    mBasePtr = MapViewOfFile( mFileMapping, 
                              readOnly ? FILE_MAP_READ : FILE_MAP_ALL_ACCESS,
                              0,
                              (DWORD)(adjOffset),
                              adjLength );
    if (mBasePtr == NULL) {
        LOGE("MapViewOfFile(%ld, %ld) failed with error %ld\n",
              adjOffset, adjLength, GetLastError() );
        CloseHandle(mFileMapping);
        mFileMapping = INVALID_HANDLE_VALUE;
        return false;
    }
#endif
#ifdef HAVE_POSIX_FILEMAP
    int     prot, flags, adjust;
    off64_t adjOffset;
    size_t  adjLength;

    void* ptr;

    assert(mRefCount == 1);
    assert(fd >= 0);
    assert(offset >= 0);
    assert(length > 0);

    /* init on first use */
    if (mPageSize == -1) {
#if NOT_USING_KLIBC
        mPageSize = sysconf(_SC_PAGESIZE);
        if (mPageSize == -1) {
            LOGE("could not get _SC_PAGESIZE\n");
            return false;
        }
#else
        /* this holds for Linux, Darwin, Cygwin, and doesn't pain the ARM */
        mPageSize = 4096;
#endif
    }

    adjust   = offset % mPageSize;
try_again:
    adjOffset = offset - adjust;
    adjLength = length + adjust;

    flags = MAP_SHARED;
    prot = PROT_READ;
    if (!readOnly)
        prot |= PROT_WRITE;

    ptr = mmap(NULL, adjLength, prot, flags, fd, adjOffset);
    if (ptr == MAP_FAILED) {
    	// Cygwin does not seem to like file mapping files from an offset.
    	// So if we fail, try again with offset zero
    	if (adjOffset > 0) {
    		adjust = offset;
    		goto try_again;
    	}
    
        LOGE("mmap(%ld,%ld) failed: %s\n",
            (long) adjOffset, (long) adjLength, strerror(errno));
        return false;
    }
    mBasePtr = ptr;
#endif /* HAVE_POSIX_FILEMAP */

    mFileName = origFileName != NULL ? strdup(origFileName) : NULL;
    mBaseLength = adjLength;
    mDataOffset = offset;
    mDataPtr = (char*) mBasePtr + adjust;
    mDataLength = length;

    assert(mBasePtr != NULL);

    ALOGV("MAP: base %p/%d data %p/%d\n",
        mBasePtr, (int) mBaseLength, mDataPtr, (int) mDataLength);

    return true;
}

/*
 * Provide guidance to the system.
 */
int FileMap::advise(MapAdvice advice)
{
#if HAVE_MADVISE
    int cc, sysAdvice;

    switch (advice) {
        case NORMAL:        sysAdvice = MADV_NORMAL;        break;
        case RANDOM:        sysAdvice = MADV_RANDOM;        break;
        case SEQUENTIAL:    sysAdvice = MADV_SEQUENTIAL;    break;
        case WILLNEED:      sysAdvice = MADV_WILLNEED;      break;
        case DONTNEED:      sysAdvice = MADV_DONTNEED;      break;
        default:
                            assert(false);
                            return -1;
    }

    cc = madvise(mBasePtr, mBaseLength, sysAdvice);
    if (cc != 0)
        LOGW("madvise(%d) failed: %s\n", sysAdvice, strerror(errno));
    return cc;
#else
	return -1;
#endif // HAVE_MADVISE
}
