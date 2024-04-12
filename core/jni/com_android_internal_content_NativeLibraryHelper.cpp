/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "NativeLibraryHelper"
//#define LOG_NDEBUG 0

#include <androidfw/ApkParsing.h>
#include <androidfw/ZipFileRO.h>
#include <androidfw/ZipUtils.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/fs.h>
#include <nativehelper/ScopedUtfChars.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>
#include <utils/Log.h>
#include <zlib.h>

#include <memory>

#include "com_android_internal_content_FileSystemUtils.h"
#include "core_jni_helpers.h"

#define RS_BITCODE_SUFFIX ".bc"

#define TMP_FILE_PATTERN "/tmp.XXXXXX"
#define TMP_FILE_PATTERN_LEN (sizeof(TMP_FILE_PATTERN) - 1)

namespace android {

// These match PackageManager.java install codes
enum install_status_t {
    INSTALL_SUCCEEDED = 1,
    INSTALL_FAILED_INVALID_APK = -2,
    INSTALL_FAILED_INSUFFICIENT_STORAGE = -4,
    INSTALL_FAILED_CONTAINER_ERROR = -18,
    INSTALL_FAILED_INTERNAL_ERROR = -110,
    INSTALL_FAILED_NO_MATCHING_ABIS = -113,
    NO_NATIVE_LIBRARIES = -114
};

typedef install_status_t (*iterFunc)(JNIEnv*, void*, ZipFileRO*, ZipEntryRO, const char*);

static bool
isFileDifferent(const char* filePath, uint32_t fileSize, time_t modifiedTime,
        uint32_t zipCrc, struct stat64* st)
{
    if (lstat64(filePath, st) < 0) {
        // File is not found or cannot be read.
        ALOGV("Couldn't stat %s, copying: %s\n", filePath, strerror(errno));
        return true;
    }

    if (!S_ISREG(st->st_mode)) {
        return true;
    }

    if (static_cast<uint64_t>(st->st_size) != static_cast<uint64_t>(fileSize)) {
        return true;
    }

    // For some reason, bionic doesn't define st_mtime as time_t
    if (time_t(st->st_mtime) != modifiedTime) {
        ALOGV("mod time doesn't match: %ld vs. %ld\n", st->st_mtime, modifiedTime);
        return true;
    }

    int fd = TEMP_FAILURE_RETRY(open(filePath, O_RDONLY | O_CLOEXEC));
    if (fd < 0) {
        ALOGV("Couldn't open file %s: %s", filePath, strerror(errno));
        return true;
    }

    // uLong comes from zlib.h. It's a bit of a wart that they're
    // potentially using a 64-bit type for a 32-bit CRC.
    uLong crc = crc32(0L, Z_NULL, 0);
    unsigned char crcBuffer[16384];
    ssize_t numBytes;
    while ((numBytes = TEMP_FAILURE_RETRY(read(fd, crcBuffer, sizeof(crcBuffer)))) > 0) {
        crc = crc32(crc, crcBuffer, numBytes);
    }
    close(fd);

    ALOGV("%s: crc = %lx, zipCrc = %" PRIu32 "\n", filePath, crc, zipCrc);

    if (crc != static_cast<uLong>(zipCrc)) {
        return true;
    }

    return false;
}

static install_status_t
sumFiles(JNIEnv*, void* arg, ZipFileRO* zipFile, ZipEntryRO zipEntry, const char*)
{
    size_t* total = (size_t*) arg;
    uint32_t uncompLen;

    if (!zipFile->getEntryInfo(zipEntry, nullptr, &uncompLen, nullptr, nullptr, nullptr, nullptr)) {
        return INSTALL_FAILED_INVALID_APK;
    }

    *total += static_cast<size_t>(uncompLen);

    return INSTALL_SUCCEEDED;
}

/*
 * Copy the native library if needed.
 *
 * This function assumes the library and path names passed in are considered safe.
 */
static install_status_t
copyFileIfChanged(JNIEnv *env, void* arg, ZipFileRO* zipFile, ZipEntryRO zipEntry, const char* fileName)
{
    static const size_t kPageSize = getpagesize();
    void** args = reinterpret_cast<void**>(arg);
    jstring* javaNativeLibPath = (jstring*) args[0];
    jboolean extractNativeLibs = *(jboolean*) args[1];
    jboolean debuggable = *(jboolean*) args[2];

    ScopedUtfChars nativeLibPath(env, *javaNativeLibPath);

    uint32_t uncompLen;
    uint32_t when;
    uint32_t crc;

    uint16_t method;
    off64_t offset;
    uint16_t extraFieldLength;
    if (!zipFile->getEntryInfo(zipEntry, &method, &uncompLen, nullptr, &offset, &when, &crc,
                               &extraFieldLength)) {
        ALOGE("Couldn't read zip entry info\n");
        return INSTALL_FAILED_INVALID_APK;
    }

    // Always extract wrap.sh for debuggable, even if extractNativeLibs=false. This makes it
    // easier to use wrap.sh because it only works when it is extracted, see
    // frameworks/base/services/core/java/com/android/server/am/ProcessList.java.
    bool forceExtractCurrentFile = debuggable && strcmp(fileName, "wrap.sh") == 0;

    if (!extractNativeLibs && !forceExtractCurrentFile) {
        // check if library is uncompressed and page-aligned
        if (method != ZipFileRO::kCompressStored) {
            ALOGE("Library '%s' is compressed - will not be able to open it directly from apk.\n",
                fileName);
            return INSTALL_FAILED_INVALID_APK;
        }

        if (offset % kPageSize != 0) {
            ALOGE("Library '%s' is not PAGE(%zu)-aligned - will not be able to open it directly "
                  "from apk.\n", fileName, kPageSize);
            return INSTALL_FAILED_INVALID_APK;
        }

#ifdef ENABLE_PUNCH_HOLES
        // if library is uncompressed, punch hole in it in place
        if (!punchHolesInElf64(zipFile->getZipFileName(), offset)) {
            ALOGW("Failed to punch uncompressed elf file :%s inside apk : %s at offset: "
                  "%" PRIu64 "",
                  fileName, zipFile->getZipFileName(), offset);
        }

        // if extra field for this zip file is present with some length, possibility is that it is
        // padding added for zip alignment. Punch holes there too.
        if (!punchHolesInZip(zipFile->getZipFileName(), offset, extraFieldLength)) {
            ALOGW("Failed to punch apk : %s at extra field", zipFile->getZipFileName());
        }
#endif // ENABLE_PUNCH_HOLES

        return INSTALL_SUCCEEDED;
    }

    // Build local file path
    const size_t fileNameLen = strlen(fileName);
    char localFileName[nativeLibPath.size() + fileNameLen + 2];

    if (strlcpy(localFileName, nativeLibPath.c_str(), sizeof(localFileName)) != nativeLibPath.size()) {
        ALOGE("Couldn't allocate local file name for library");
        return INSTALL_FAILED_INTERNAL_ERROR;
    }

    *(localFileName + nativeLibPath.size()) = '/';

    if (strlcpy(localFileName + nativeLibPath.size() + 1, fileName, sizeof(localFileName)
                    - nativeLibPath.size() - 1) != fileNameLen) {
        ALOGE("Couldn't allocate local file name for library");
        return INSTALL_FAILED_INTERNAL_ERROR;
    }

    // Only copy out the native file if it's different.
    struct tm t;
    ZipUtils::zipTimeToTimespec(when, &t);
    const time_t modTime = mktime(&t);
    struct stat64 st;
    if (!isFileDifferent(localFileName, uncompLen, modTime, crc, &st)) {
        return INSTALL_SUCCEEDED;
    }

    char localTmpFileName[nativeLibPath.size() + TMP_FILE_PATTERN_LEN + 1];
    if (strlcpy(localTmpFileName, nativeLibPath.c_str(), sizeof(localTmpFileName))
            != nativeLibPath.size()) {
        ALOGE("Couldn't allocate local file name for library");
        return INSTALL_FAILED_INTERNAL_ERROR;
    }

    if (strlcpy(localTmpFileName + nativeLibPath.size(), TMP_FILE_PATTERN,
                    TMP_FILE_PATTERN_LEN + 1) != TMP_FILE_PATTERN_LEN) {
        ALOGE("Couldn't allocate temporary file name for library");
        return INSTALL_FAILED_INTERNAL_ERROR;
    }

    int fd = mkstemp(localTmpFileName);
    if (fd < 0) {
        ALOGE("Couldn't open temporary file name: %s: %s\n", localTmpFileName, strerror(errno));
        return INSTALL_FAILED_CONTAINER_ERROR;
    }

    // If a filesystem like f2fs supports per-file compression, set the compression bit before data
    // writes
    unsigned int flags;
    if (ioctl(fd, FS_IOC_GETFLAGS, &flags) == -1) {
        ALOGE("Failed to call FS_IOC_GETFLAGS on %s: %s\n", localTmpFileName, strerror(errno));
    } else if ((flags & FS_COMPR_FL) == 0) {
        flags |= FS_COMPR_FL;
        ioctl(fd, FS_IOC_SETFLAGS, &flags);
    }

    if (!zipFile->uncompressEntry(zipEntry, fd)) {
        ALOGE("Failed uncompressing %s to %s\n", fileName, localTmpFileName);
        close(fd);
        unlink(localTmpFileName);
        return INSTALL_FAILED_CONTAINER_ERROR;
    }

    if (fsync(fd) < 0) {
        ALOGE("Coulnd't fsync temporary file name: %s: %s\n", localTmpFileName, strerror(errno));
        close(fd);
        unlink(localTmpFileName);
        return INSTALL_FAILED_INTERNAL_ERROR;
    }

    close(fd);

    // Set the modification time for this file to the ZIP's mod time.
    struct timeval times[2];
    times[0].tv_sec = st.st_atime;
    times[1].tv_sec = modTime;
    times[0].tv_usec = times[1].tv_usec = 0;
    if (utimes(localTmpFileName, times) < 0) {
        ALOGE("Couldn't change modification time on %s: %s\n", localTmpFileName, strerror(errno));
        unlink(localTmpFileName);
        return INSTALL_FAILED_CONTAINER_ERROR;
    }

    // Set the mode to 755
    static const mode_t mode = S_IRUSR | S_IWUSR | S_IXUSR | S_IRGRP |  S_IXGRP | S_IROTH | S_IXOTH;
    if (chmod(localTmpFileName, mode) < 0) {
        ALOGE("Couldn't change permissions on %s: %s\n", localTmpFileName, strerror(errno));
        unlink(localTmpFileName);
        return INSTALL_FAILED_CONTAINER_ERROR;
    }

    // Finally, rename it to the final name.
    if (rename(localTmpFileName, localFileName) < 0) {
        ALOGE("Couldn't rename %s to %s: %s\n", localTmpFileName, localFileName, strerror(errno));
        unlink(localTmpFileName);
        return INSTALL_FAILED_CONTAINER_ERROR;
    }

#ifdef ENABLE_PUNCH_HOLES
    // punch extracted elf files as well. This will fail where compression is on (like f2fs) but it
    // will be useful for ext4 based systems
    struct statfs64 fsInfo;
    int result = statfs64(localFileName, &fsInfo);
    if (result < 0) {
        ALOGW("Failed to stat file :%s", localFileName);
    }

    if (result == 0 && fsInfo.f_type == EXT4_SUPER_MAGIC) {
        ALOGD("Punching extracted elf file %s on fs:%" PRIu64 "", fileName,
              static_cast<uint64_t>(fsInfo.f_type));
        if (!punchHolesInElf64(localFileName, 0)) {
            ALOGW("Failed to punch extracted elf file :%s from apk : %s", fileName,
                  zipFile->getZipFileName());
        }
    }
#endif // ENABLE_PUNCH_HOLES

    ALOGV("Successfully moved %s to %s\n", localTmpFileName, localFileName);

    return INSTALL_SUCCEEDED;
}

/*
 * An iterator over all shared libraries in a zip file. An entry is
 * considered to be a shared library if all of the conditions below are
 * satisfied :
 *
 * - The entry is under the lib/ directory.
 * - The entry name ends with ".so" and the entry name starts with "lib",
 *   an exception is made for debuggable apps.
 * - The entry filename is "safe" (as determined by isFilenameSafe).
 *
 */
class NativeLibrariesIterator {
private:
    NativeLibrariesIterator(ZipFileRO* zipFile, bool debuggable, void* cookie)
          : mZipFile(zipFile), mDebuggable(debuggable), mCookie(cookie), mLastSlash(nullptr) {
        fileName[0] = '\0';
    }

public:
    static base::expected<std::unique_ptr<NativeLibrariesIterator>, int32_t> create(
            ZipFileRO* zipFile, bool debuggable) {
        // Do not specify a suffix to find both .so files and gdbserver.
        auto result = zipFile->startIterationOrError(APK_LIB.data(), nullptr /* suffix */);
        if (!result.ok()) {
            return base::unexpected(result.error());
        }

        return std::unique_ptr<NativeLibrariesIterator>(
                new NativeLibrariesIterator(zipFile, debuggable, result.value()));
    }

    base::expected<ZipEntryRO, int32_t> next() {
        ZipEntryRO nextEntry;
        while (true) {
            auto next = mZipFile->nextEntryOrError(mCookie);
            if (!next.ok()) {
                return base::unexpected(next.error());
            }
            nextEntry = next.value();
            if (nextEntry == nullptr) {
                break;
            }
            // Make sure this entry has a filename.
            if (mZipFile->getEntryFileName(nextEntry, fileName, sizeof(fileName))) {
                continue;
            }

            const char* lastSlash = util::ValidLibraryPathLastSlash(fileName, false, mDebuggable);
            if (lastSlash) {
                mLastSlash = lastSlash;
                break;
            }
        }

        return nextEntry;
    }

    inline const char* currentEntry() const {
        return fileName;
    }

    inline const char* lastSlash() const {
        return mLastSlash;
    }

    virtual ~NativeLibrariesIterator() {
        mZipFile->endIteration(mCookie);
    }
private:

    char fileName[PATH_MAX];
    ZipFileRO* const mZipFile;
    const bool mDebuggable;
    void* mCookie;
    const char* mLastSlash;
};

static install_status_t
iterateOverNativeFiles(JNIEnv *env, jlong apkHandle, jstring javaCpuAbi,
                       jboolean debuggable, iterFunc callFunc, void* callArg) {
    ZipFileRO* zipFile = reinterpret_cast<ZipFileRO*>(apkHandle);
    if (zipFile == nullptr) {
        return INSTALL_FAILED_INVALID_APK;
    }

    auto result = NativeLibrariesIterator::create(zipFile, debuggable);
    if (!result.ok()) {
        return INSTALL_FAILED_INVALID_APK;
    }
    std::unique_ptr<NativeLibrariesIterator> it(std::move(result.value()));

    const ScopedUtfChars cpuAbi(env, javaCpuAbi);
    if (cpuAbi.c_str() == nullptr) {
        // This would've thrown, so this return code isn't observable by Java.
        return INSTALL_FAILED_INVALID_APK;
    }

    while (true) {
        auto next = it->next();
        if (!next.ok()) {
            return INSTALL_FAILED_INVALID_APK;
        }
        auto entry = next.value();
        if (entry == nullptr) {
            break;
        }

        const char* fileName = it->currentEntry();
        const char* lastSlash = it->lastSlash();

        // Check to make sure the CPU ABI of this file is one we support.
        const char* cpuAbiOffset = fileName + APK_LIB_LEN;
        const size_t cpuAbiRegionSize = lastSlash - cpuAbiOffset;

        if (cpuAbi.size() == cpuAbiRegionSize && !strncmp(cpuAbiOffset, cpuAbi.c_str(), cpuAbiRegionSize)) {
            install_status_t ret = callFunc(env, callArg, zipFile, entry, lastSlash + 1);

            if (ret != INSTALL_SUCCEEDED) {
                ALOGV("Failure for entry %s", lastSlash + 1);
                return ret;
            }
        }
    }

    return INSTALL_SUCCEEDED;
}

static int findSupportedAbi(JNIEnv* env, jlong apkHandle, jobjectArray supportedAbisArray,
                            jboolean debuggable) {
    ZipFileRO* zipFile = reinterpret_cast<ZipFileRO*>(apkHandle);
    if (zipFile == nullptr) {
        return INSTALL_FAILED_INVALID_APK;
    }

    auto result = NativeLibrariesIterator::create(zipFile, debuggable);
    if (!result.ok()) {
        return INSTALL_FAILED_INVALID_APK;
    }
    std::unique_ptr<NativeLibrariesIterator> it(std::move(result.value()));

    const int numAbis = env->GetArrayLength(supportedAbisArray);

    std::vector<ScopedUtfChars> supportedAbis;
    supportedAbis.reserve(numAbis);
    for (int i = 0; i < numAbis; ++i) {
        supportedAbis.emplace_back(env, (jstring)env->GetObjectArrayElement(supportedAbisArray, i));
    }

    int status = NO_NATIVE_LIBRARIES;
    while (true) {
        auto next = it->next();
        if (!next.ok()) {
            return INSTALL_FAILED_INVALID_APK;
        }
        auto entry = next.value();
        if (entry == nullptr) {
            break;
        }

        // We're currently in the lib/ directory of the APK, so it does have some native
        // code. We should return INSTALL_FAILED_NO_MATCHING_ABIS if none of the
        // libraries match.
        if (status == NO_NATIVE_LIBRARIES) {
            status = INSTALL_FAILED_NO_MATCHING_ABIS;
        }

        const char* fileName = it->currentEntry();
        const char* lastSlash = it->lastSlash();

        // Check to see if this CPU ABI matches what we are looking for.
        const char* abiOffset = fileName + APK_LIB_LEN;
        const size_t abiSize = lastSlash - abiOffset;
        for (int i = 0; i < numAbis; i++) {
            const ScopedUtfChars& abi = supportedAbis[i];
            if (abi.size() == abiSize && !strncmp(abiOffset, abi.c_str(), abiSize)) {
                // The entry that comes in first (i.e. with a lower index) has the higher priority.
                if (((i < status) && (status >= 0)) || (status < 0) ) {
                    status = i;
                }
            }
        }
    }

    return status;
}

static jint
com_android_internal_content_NativeLibraryHelper_copyNativeBinaries(JNIEnv *env, jclass clazz,
        jlong apkHandle, jstring javaNativeLibPath, jstring javaCpuAbi,
        jboolean extractNativeLibs, jboolean debuggable)
{
    void* args[] = { &javaNativeLibPath, &extractNativeLibs, &debuggable };
    return (jint) iterateOverNativeFiles(env, apkHandle, javaCpuAbi, debuggable,
            copyFileIfChanged, reinterpret_cast<void*>(args));
}

static jlong
com_android_internal_content_NativeLibraryHelper_sumNativeBinaries(JNIEnv *env, jclass clazz,
        jlong apkHandle, jstring javaCpuAbi, jboolean debuggable)
{
    size_t totalSize = 0;

    iterateOverNativeFiles(env, apkHandle, javaCpuAbi, debuggable, sumFiles, &totalSize);

    return totalSize;
}

static jint
com_android_internal_content_NativeLibraryHelper_findSupportedAbi(JNIEnv *env, jclass clazz,
        jlong apkHandle, jobjectArray javaCpuAbisToSearch, jboolean debuggable)
{
    return (jint) findSupportedAbi(env, apkHandle, javaCpuAbisToSearch, debuggable);
}

enum bitcode_scan_result_t {
  APK_SCAN_ERROR = -1,
  NO_BITCODE_PRESENT = 0,
  BITCODE_PRESENT = 1,
};

static jint
com_android_internal_content_NativeLibraryHelper_hasRenderscriptBitcode(JNIEnv *env, jclass clazz,
        jlong apkHandle) {
    ZipFileRO* zipFile = reinterpret_cast<ZipFileRO*>(apkHandle);
    void* cookie = nullptr;
    if (!zipFile->startIteration(&cookie, nullptr /* prefix */, RS_BITCODE_SUFFIX)) {
        return APK_SCAN_ERROR;
    }

    char fileName[PATH_MAX];
    ZipEntryRO next = nullptr;
    while ((next = zipFile->nextEntry(cookie)) != nullptr) {
        if (zipFile->getEntryFileName(next, fileName, sizeof(fileName))) {
            continue;
        }
        const char* lastSlash = strrchr(fileName, '/');
        const char* baseName = (lastSlash == nullptr) ? fileName : fileName + 1;
        if (util::isFilenameSafe(baseName)) {
            zipFile->endIteration(cookie);
            return BITCODE_PRESENT;
        }
    }

    zipFile->endIteration(cookie);
    return NO_BITCODE_PRESENT;
}

static jlong
com_android_internal_content_NativeLibraryHelper_openApk(JNIEnv *env, jclass, jstring apkPath)
{
    ScopedUtfChars filePath(env, apkPath);
    ZipFileRO* zipFile = ZipFileRO::open(filePath.c_str());

    return reinterpret_cast<jlong>(zipFile);
}

static jlong
com_android_internal_content_NativeLibraryHelper_openApkFd(JNIEnv *env, jclass,
        jobject fileDescriptor, jstring debugPathName)
{
    ScopedUtfChars debugFilePath(env, debugPathName);

    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    if (fd < 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Bad FileDescriptor");
        return 0;
    }

    int dupedFd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (dupedFd == -1) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                             "Failed to dup FileDescriptor: %s", strerror(errno));
        return 0;
    }

    ZipFileRO* zipFile = ZipFileRO::openFd(dupedFd, debugFilePath.c_str());

    return reinterpret_cast<jlong>(zipFile);
}

static void
com_android_internal_content_NativeLibraryHelper_close(JNIEnv *env, jclass, jlong apkHandle)
{
    delete reinterpret_cast<ZipFileRO*>(apkHandle);
}

static const JNINativeMethod gMethods[] = {
    {"nativeOpenApk",
            "(Ljava/lang/String;)J",
            (void *)com_android_internal_content_NativeLibraryHelper_openApk},
    {"nativeOpenApkFd",
            "(Ljava/io/FileDescriptor;Ljava/lang/String;)J",
            (void *)com_android_internal_content_NativeLibraryHelper_openApkFd},
    {"nativeClose",
            "(J)V",
            (void *)com_android_internal_content_NativeLibraryHelper_close},
    {"nativeCopyNativeBinaries",
            "(JLjava/lang/String;Ljava/lang/String;ZZ)I",
            (void *)com_android_internal_content_NativeLibraryHelper_copyNativeBinaries},
    {"nativeSumNativeBinaries",
            "(JLjava/lang/String;Z)J",
            (void *)com_android_internal_content_NativeLibraryHelper_sumNativeBinaries},
    {"nativeFindSupportedAbi",
            "(J[Ljava/lang/String;Z)I",
            (void *)com_android_internal_content_NativeLibraryHelper_findSupportedAbi},
    {"hasRenderscriptBitcode", "(J)I",
            (void *)com_android_internal_content_NativeLibraryHelper_hasRenderscriptBitcode},
};


int register_com_android_internal_content_NativeLibraryHelper(JNIEnv *env)
{
    return RegisterMethodsOrDie(env,
            "com/android/internal/content/NativeLibraryHelper", gMethods, NELEM(gMethods));
}

};
