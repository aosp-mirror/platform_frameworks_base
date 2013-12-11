/*
 * Copyright (C) 2007 The Android Open Source Project
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
// Read-only access to Zip archives, with minimal heap allocation.
//
#define LOG_TAG "zipro"
//#define LOG_NDEBUG 0
#include <androidfw/ZipFileRO.h>
#include <utils/Log.h>
#include <utils/Compat.h>
#include <utils/misc.h>
#include <utils/threads.h>
#include <ziparchive/zip_archive.h>

#include <zlib.h>

#include <string.h>
#include <fcntl.h>
#include <errno.h>
#include <assert.h>
#include <unistd.h>

/*
 * We must open binary files using open(path, ... | O_BINARY) under Windows.
 * Otherwise strange read errors will happen.
 */
#ifndef O_BINARY
#  define O_BINARY  0
#endif

using namespace android;

class _ZipEntryRO {
public:
    ZipEntry entry;
    ZipEntryName name;
    void *cookie;

    _ZipEntryRO() : cookie(NULL) {
    }

private:
    _ZipEntryRO(const _ZipEntryRO& other);
    _ZipEntryRO& operator=(const _ZipEntryRO& other);
};

ZipFileRO::~ZipFileRO() {
    CloseArchive(mHandle);
    free(mFileName);
}

/*
 * Open the specified file read-only.  We memory-map the entire thing and
 * close the file before returning.
 */
/* static */ ZipFileRO* ZipFileRO::open(const char* zipFileName)
{
    ZipArchiveHandle handle;
    const int32_t error = OpenArchive(zipFileName, &handle);
    if (error) {
        ALOGW("Error opening archive %s: %s", zipFileName, ErrorCodeString(error));
        return NULL;
    }

    return new ZipFileRO(handle, strdup(zipFileName));
}


ZipEntryRO ZipFileRO::findEntryByName(const char* entryName) const
{
    _ZipEntryRO* data = new _ZipEntryRO;
    const int32_t error = FindEntry(mHandle, entryName, &(data->entry));
    if (error) {
        delete data;
        return NULL;
    }

    data->name.name = entryName;
    data->name.name_length = strlen(entryName);

    return (ZipEntryRO) data;
}

/*
 * Get the useful fields from the zip entry.
 *
 * Returns "false" if the offsets to the fields or the contents of the fields
 * appear to be bogus.
 */
bool ZipFileRO::getEntryInfo(ZipEntryRO entry, int* pMethod, size_t* pUncompLen,
    size_t* pCompLen, off64_t* pOffset, long* pModWhen, long* pCrc32) const
{
    const _ZipEntryRO* zipEntry = reinterpret_cast<_ZipEntryRO*>(entry);
    const ZipEntry& ze = zipEntry->entry;

    if (pMethod != NULL) {
        *pMethod = ze.method;
    }
    if (pUncompLen != NULL) {
        *pUncompLen = ze.uncompressed_length;
    }
    if (pCompLen != NULL) {
        *pCompLen = ze.compressed_length;
    }
    if (pOffset != NULL) {
        *pOffset = ze.offset;
    }
    if (pModWhen != NULL) {
        *pModWhen = ze.mod_time;
    }
    if (pCrc32 != NULL) {
        *pCrc32 = ze.crc32;
    }

    return true;
}

bool ZipFileRO::startIteration(void** cookie)
{
    _ZipEntryRO* ze = new _ZipEntryRO;
    int32_t error = StartIteration(mHandle, &(ze->cookie), NULL /* prefix */);
    if (error) {
        ALOGW("Could not start iteration over %s: %s", mFileName, ErrorCodeString(error));
        delete ze;
        return false;
    }

    *cookie = ze;
    return true;
}

ZipEntryRO ZipFileRO::nextEntry(void* cookie)
{
    _ZipEntryRO* ze = reinterpret_cast<_ZipEntryRO*>(cookie);
    int32_t error = Next(ze->cookie, &(ze->entry), &(ze->name));
    if (error) {
        if (error != -1) {
            ALOGW("Error iteration over %s: %s", mFileName, ErrorCodeString(error));
        }
        return NULL;
    }

    return &(ze->entry);
}

void ZipFileRO::endIteration(void* cookie)
{
    delete reinterpret_cast<_ZipEntryRO*>(cookie);
}

void ZipFileRO::releaseEntry(ZipEntryRO entry) const
{
    delete reinterpret_cast<_ZipEntryRO*>(entry);
}

/*
 * Copy the entry's filename to the buffer.
 */
int ZipFileRO::getEntryFileName(ZipEntryRO entry, char* buffer, int bufLen)
    const
{
    const _ZipEntryRO* zipEntry = reinterpret_cast<_ZipEntryRO*>(entry);
    const uint16_t requiredSize = zipEntry->name.name_length + 1;

    if (bufLen < requiredSize) {
        ALOGW("Buffer too short, requires %d bytes for entry name", requiredSize);
        return requiredSize;
    }

    memcpy(buffer, zipEntry->name.name, requiredSize - 1);
    buffer[requiredSize - 1] = '\0';

    return 0;
}

/*
 * Create a new FileMap object that spans the data in "entry".
 */
FileMap* ZipFileRO::createEntryFileMap(ZipEntryRO entry) const
{
    const _ZipEntryRO *zipEntry = reinterpret_cast<_ZipEntryRO*>(entry);
    const ZipEntry& ze = zipEntry->entry;
    int fd = GetFileDescriptor(mHandle);
    size_t actualLen = 0;

    if (ze.method == kCompressStored) {
        actualLen = ze.uncompressed_length;
    } else {
        actualLen = ze.compressed_length;
    }

    FileMap* newMap = new FileMap();
    if (!newMap->create(mFileName, fd, ze.offset, actualLen, true)) {
        newMap->release();
        return NULL;
    }

    return newMap;
}

/*
 * Uncompress an entry, in its entirety, into the provided output buffer.
 *
 * This doesn't verify the data's CRC, which might be useful for
 * uncompressed data.  The caller should be able to manage it.
 */
bool ZipFileRO::uncompressEntry(ZipEntryRO entry, void* buffer, size_t size) const
{
    _ZipEntryRO *zipEntry = reinterpret_cast<_ZipEntryRO*>(entry);
    const int32_t error = ExtractToMemory(mHandle, &(zipEntry->entry),
        (uint8_t*) buffer, size);
    if (error) {
        ALOGW("ExtractToMemory failed with %s", ErrorCodeString(error));
        return false;
    }

    return true;
}

/*
 * Uncompress an entry, in its entirety, to an open file descriptor.
 *
 * This doesn't verify the data's CRC, but probably should.
 */
bool ZipFileRO::uncompressEntry(ZipEntryRO entry, int fd) const
{
    _ZipEntryRO *zipEntry = reinterpret_cast<_ZipEntryRO*>(entry);
    const int32_t error = ExtractEntryToFile(mHandle, &(zipEntry->entry), fd);
    if (error) {
        ALOGW("ExtractToMemory failed with %s", ErrorCodeString(error));
        return false;
    }

    return true;
}
