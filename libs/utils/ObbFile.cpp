/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define LOG_TAG "ObbFile"

#include <utils/Compat.h>
#include <utils/Log.h>
#include <utils/ObbFile.h>

//#define DEBUG 1

#define kFooterTagSize 8  /* last two 32-bit integers */

#define kFooterMinSize 33 /* 32-bit signature version (4 bytes)
                           * 32-bit package version (4 bytes)
                           * 32-bit flags (4 bytes)
                           * 64-bit salt (8 bytes)
                           * 32-bit package name size (4 bytes)
                           * >=1-character package name (1 byte)
                           * 32-bit footer size (4 bytes)
                           * 32-bit footer marker (4 bytes)
                           */

#define kMaxBufSize    32768 /* Maximum file read buffer */

#define kSignature     0x01059983U /* ObbFile signature */

#define kSigVersion    1 /* We only know about signature version 1 */

/* offsets in version 1 of the header */
#define kPackageVersionOffset 4
#define kFlagsOffset          8
#define kSaltOffset           12
#define kPackageNameLenOffset 20
#define kPackageNameOffset    24

/*
 * TEMP_FAILURE_RETRY is defined by some, but not all, versions of
 * <unistd.h>. (Alas, it is not as standard as we'd hoped!) So, if it's
 * not already defined, then define it here.
 */
#ifndef TEMP_FAILURE_RETRY
/* Used to retry syscalls that can return EINTR. */
#define TEMP_FAILURE_RETRY(exp) ({         \
    typeof (exp) _rc;                      \
    do {                                   \
        _rc = (exp);                       \
    } while (_rc == -1 && errno == EINTR); \
    _rc; })
#endif


namespace android {

ObbFile::ObbFile()
        : mPackageName("")
        , mVersion(-1)
        , mFlags(0)
{
    memset(mSalt, 0, sizeof(mSalt));
}

ObbFile::~ObbFile() {
}

bool ObbFile::readFrom(const char* filename)
{
    int fd;
    bool success = false;

    fd = ::open(filename, O_RDONLY);
    if (fd < 0) {
        ALOGW("couldn't open file %s: %s", filename, strerror(errno));
        goto out;
    }
    success = readFrom(fd);
    close(fd);

    if (!success) {
        ALOGW("failed to read from %s (fd=%d)\n", filename, fd);
    }

out:
    return success;
}

bool ObbFile::readFrom(int fd)
{
    if (fd < 0) {
        ALOGW("attempt to read from invalid fd\n");
        return false;
    }

    return parseObbFile(fd);
}

bool ObbFile::parseObbFile(int fd)
{
    off64_t fileLength = lseek64(fd, 0, SEEK_END);

    if (fileLength < kFooterMinSize) {
        if (fileLength < 0) {
            ALOGW("error seeking in ObbFile: %s\n", strerror(errno));
        } else {
            ALOGW("file is only %lld (less than %d minimum)\n", fileLength, kFooterMinSize);
        }
        return false;
    }

    ssize_t actual;
    size_t footerSize;

    {
        lseek64(fd, fileLength - kFooterTagSize, SEEK_SET);

        char *footer = new char[kFooterTagSize];
        actual = TEMP_FAILURE_RETRY(read(fd, footer, kFooterTagSize));
        if (actual != kFooterTagSize) {
            ALOGW("couldn't read footer signature: %s\n", strerror(errno));
            return false;
        }

        unsigned int fileSig = get4LE((unsigned char*)footer + sizeof(int32_t));
        if (fileSig != kSignature) {
            ALOGW("footer didn't match magic string (expected 0x%08x; got 0x%08x)\n",
                    kSignature, fileSig);
            return false;
        }

        footerSize = get4LE((unsigned char*)footer);
        if (footerSize > (size_t)fileLength - kFooterTagSize
                || footerSize > kMaxBufSize) {
            ALOGW("claimed footer size is too large (0x%08zx; file size is 0x%08llx)\n",
                    footerSize, fileLength);
            return false;
        }

        if (footerSize < (kFooterMinSize - kFooterTagSize)) {
            ALOGW("claimed footer size is too small (0x%zx; minimum size is 0x%x)\n",
                    footerSize, kFooterMinSize - kFooterTagSize);
            return false;
        }
    }

    off64_t fileOffset = fileLength - footerSize - kFooterTagSize;
    if (lseek64(fd, fileOffset, SEEK_SET) != fileOffset) {
        ALOGW("seek %lld failed: %s\n", fileOffset, strerror(errno));
        return false;
    }

    mFooterStart = fileOffset;

    char* scanBuf = (char*)malloc(footerSize);
    if (scanBuf == NULL) {
        ALOGW("couldn't allocate scanBuf: %s\n", strerror(errno));
        return false;
    }

    actual = TEMP_FAILURE_RETRY(read(fd, scanBuf, footerSize));
    // readAmount is guaranteed to be less than kMaxBufSize
    if (actual != (ssize_t)footerSize) {
        ALOGI("couldn't read ObbFile footer: %s\n", strerror(errno));
        free(scanBuf);
        return false;
    }

#ifdef DEBUG
    for (int i = 0; i < footerSize; ++i) {
        ALOGI("char: 0x%02x\n", scanBuf[i]);
    }
#endif

    uint32_t sigVersion = get4LE((unsigned char*)scanBuf);
    if (sigVersion != kSigVersion) {
        ALOGW("Unsupported ObbFile version %d\n", sigVersion);
        free(scanBuf);
        return false;
    }

    mVersion = (int32_t) get4LE((unsigned char*)scanBuf + kPackageVersionOffset);
    mFlags = (int32_t) get4LE((unsigned char*)scanBuf + kFlagsOffset);

    memcpy(&mSalt, (unsigned char*)scanBuf + kSaltOffset, sizeof(mSalt));

    size_t packageNameLen = get4LE((unsigned char*)scanBuf + kPackageNameLenOffset);
    if (packageNameLen == 0
            || packageNameLen > (footerSize - kPackageNameOffset)) {
        ALOGW("bad ObbFile package name length (0x%04zx; 0x%04zx possible)\n",
                packageNameLen, footerSize - kPackageNameOffset);
        free(scanBuf);
        return false;
    }

    char* packageName = reinterpret_cast<char*>(scanBuf + kPackageNameOffset);
    mPackageName = String8(const_cast<char*>(packageName), packageNameLen);

    free(scanBuf);

#ifdef DEBUG
    ALOGI("Obb scan succeeded: packageName=%s, version=%d\n", mPackageName.string(), mVersion);
#endif

    return true;
}

bool ObbFile::writeTo(const char* filename)
{
    int fd;
    bool success = false;

    fd = ::open(filename, O_WRONLY);
    if (fd < 0) {
        goto out;
    }
    success = writeTo(fd);
    close(fd);

out:
    if (!success) {
        ALOGW("failed to write to %s: %s\n", filename, strerror(errno));
    }
    return success;
}

bool ObbFile::writeTo(int fd)
{
    if (fd < 0) {
        return false;
    }

    lseek64(fd, 0, SEEK_END);

    if (mPackageName.size() == 0 || mVersion == -1) {
        ALOGW("tried to write uninitialized ObbFile data\n");
        return false;
    }

    unsigned char intBuf[sizeof(uint32_t)+1];
    memset(&intBuf, 0, sizeof(intBuf));

    put4LE(intBuf, kSigVersion);
    if (write(fd, &intBuf, sizeof(uint32_t)) != (ssize_t)sizeof(uint32_t)) {
        ALOGW("couldn't write signature version: %s\n", strerror(errno));
        return false;
    }

    put4LE(intBuf, mVersion);
    if (write(fd, &intBuf, sizeof(uint32_t)) != (ssize_t)sizeof(uint32_t)) {
        ALOGW("couldn't write package version\n");
        return false;
    }

    put4LE(intBuf, mFlags);
    if (write(fd, &intBuf, sizeof(uint32_t)) != (ssize_t)sizeof(uint32_t)) {
        ALOGW("couldn't write package version\n");
        return false;
    }

    if (write(fd, mSalt, sizeof(mSalt)) != (ssize_t)sizeof(mSalt)) {
        ALOGW("couldn't write salt: %s\n", strerror(errno));
        return false;
    }

    size_t packageNameLen = mPackageName.size();
    put4LE(intBuf, packageNameLen);
    if (write(fd, &intBuf, sizeof(uint32_t)) != (ssize_t)sizeof(uint32_t)) {
        ALOGW("couldn't write package name length: %s\n", strerror(errno));
        return false;
    }

    if (write(fd, mPackageName.string(), packageNameLen) != (ssize_t)packageNameLen) {
        ALOGW("couldn't write package name: %s\n", strerror(errno));
        return false;
    }

    put4LE(intBuf, kPackageNameOffset + packageNameLen);
    if (write(fd, &intBuf, sizeof(uint32_t)) != (ssize_t)sizeof(uint32_t)) {
        ALOGW("couldn't write footer size: %s\n", strerror(errno));
        return false;
    }

    put4LE(intBuf, kSignature);
    if (write(fd, &intBuf, sizeof(uint32_t)) != (ssize_t)sizeof(uint32_t)) {
        ALOGW("couldn't write footer magic signature: %s\n", strerror(errno));
        return false;
    }

    return true;
}

bool ObbFile::removeFrom(const char* filename)
{
    int fd;
    bool success = false;

    fd = ::open(filename, O_RDWR);
    if (fd < 0) {
        goto out;
    }
    success = removeFrom(fd);
    close(fd);

out:
    if (!success) {
        ALOGW("failed to remove signature from %s: %s\n", filename, strerror(errno));
    }
    return success;
}

bool ObbFile::removeFrom(int fd)
{
    if (fd < 0) {
        return false;
    }

    if (!readFrom(fd)) {
        return false;
    }

    ftruncate(fd, mFooterStart);

    return true;
}

}
