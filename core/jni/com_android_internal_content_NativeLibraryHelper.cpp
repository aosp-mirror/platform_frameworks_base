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

#include <android_runtime/AndroidRuntime.h>

#include <utils/Log.h>
#include <ScopedUtfChars.h>
#include <utils/ZipFileRO.h>

#include <zlib.h>

#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>


#define APK_LIB "lib/"
#define APK_LIB_LEN (sizeof(APK_LIB) - 1)

#define LIB_PREFIX "/lib"
#define LIB_PREFIX_LEN (sizeof(LIB_PREFIX) - 1)

#define LIB_SUFFIX ".so"
#define LIB_SUFFIX_LEN (sizeof(LIB_SUFFIX) - 1)

#define GDBSERVER "gdbserver"
#define GDBSERVER_LEN (sizeof(GDBSERVER) - 1)

#define TMP_FILE_PATTERN "/tmp.XXXXXX"
#define TMP_FILE_PATTERN_LEN (sizeof(TMP_FILE_PATTERN) - 1)

namespace android {

typedef void (*iterFunc)(JNIEnv*, void*, ZipFileRO*, ZipEntryRO, const char*);

// These match PackageManager.java install codes
typedef enum {
    INSTALL_SUCCEEDED = 0,
    INSTALL_FAILED_INVALID_APK = -2,
    INSTALL_FAILED_INSUFFICIENT_STORAGE = -4,
} install_status_t;

// Equivalent to isFilenameSafe
static bool
isFilenameSafe(const char* filename)
{
    off_t offset = 0;
    for (;;) {
        switch (*(filename + offset)) {
        case 0:
            // Null.
            // If we've reached the end, all the other characters are good.
            return true;

        case 'A' ... 'Z':
        case 'a' ... 'z':
        case '0' ... '9':
        case '+':
        case ',':
        case '-':
        case '.':
        case '/':
        case '=':
        case '_':
            offset++;
            break;

        default:
            // We found something that is not good.
            return false;
        }
    }
    // Should not reach here.
}

static bool
isFileDifferent(const char* filePath, size_t fileSize, time_t modifiedTime,
        long zipCrc, struct stat64* st)
{
    if (lstat64(filePath, st) < 0) {
        // File is not found or cannot be read.
        LOGV("Couldn't stat %s, copying: %s\n", filePath, strerror(errno));
        return true;
    }

    if (!S_ISREG(st->st_mode)) {
        return true;
    }

    if (st->st_size != fileSize) {
        return true;
    }

    // For some reason, bionic doesn't define st_mtime as time_t
    if (time_t(st->st_mtime) != modifiedTime) {
        LOGV("mod time doesn't match: %ld vs. %ld\n", st->st_mtime, modifiedTime);
        return true;
    }

    int fd = TEMP_FAILURE_RETRY(open(filePath, O_RDONLY));
    if (fd < 0) {
        LOGV("Couldn't open file %s: %s", filePath, strerror(errno));
        return true;
    }

    long crc = crc32(0L, Z_NULL, 0);
    unsigned char crcBuffer[16384];
    ssize_t numBytes;
    while ((numBytes = TEMP_FAILURE_RETRY(read(fd, crcBuffer, sizeof(crcBuffer)))) > 0) {
        crc = crc32(crc, crcBuffer, numBytes);
    }
    close(fd);

    LOGV("%s: crc = %lx, zipCrc = %lx\n", filePath, crc, zipCrc);

    if (crc != zipCrc) {
        return true;
    }

    return false;
}

static void
sumFiles(JNIEnv* env, void* arg, ZipFileRO* zipFile, ZipEntryRO zipEntry, const char* fileName)
{
    size_t* total = (size_t*) arg;
    size_t uncompLen;

    if (!zipFile->getEntryInfo(zipEntry, NULL, &uncompLen, NULL, NULL, NULL, NULL)) {
        return;
    }

    *total += uncompLen;
}

/*
 * Copy the native library if needed.
 *
 * This function assumes the library and path names passed in are considered safe.
 */
static void
copyFileIfChanged(JNIEnv *env, void* arg, ZipFileRO* zipFile, ZipEntryRO zipEntry, const char* fileName)
{
    jstring* javaNativeLibPath = (jstring*) arg;
    ScopedUtfChars nativeLibPath(env, *javaNativeLibPath);

    size_t uncompLen;
    long when;
    long crc;
    time_t modTime;

    if (!zipFile->getEntryInfo(zipEntry, NULL, &uncompLen, NULL, NULL, &when, &crc)) {
        return;
    } else {
        struct tm t;
        ZipFileRO::zipTimeToTimespec(when, &t);
        modTime = mktime(&t);
    }

    // Build local file path
    const size_t fileNameLen = strlen(fileName);
    char localFileName[nativeLibPath.size() + fileNameLen + 2];

    if (strlcpy(localFileName, nativeLibPath.c_str(), sizeof(localFileName)) != nativeLibPath.size()) {
        LOGD("Couldn't allocate local file name for library: %s", strerror(errno));
        return;
    }

    *(localFileName + nativeLibPath.size()) = '/';

    if (strlcpy(localFileName + nativeLibPath.size() + 1, fileName, sizeof(localFileName)
                    - nativeLibPath.size() - 1) != fileNameLen) {
        LOGD("Couldn't allocate local file name for library: %s", strerror(errno));
        return;
    }

    // Only copy out the native file if it's different.
    struct stat st;
    if (!isFileDifferent(localFileName, uncompLen, modTime, crc, &st)) {
        return;
    }

    char localTmpFileName[nativeLibPath.size() + TMP_FILE_PATTERN_LEN + 2];
    if (strlcpy(localTmpFileName, nativeLibPath.c_str(), sizeof(localTmpFileName))
            != nativeLibPath.size()) {
        LOGD("Couldn't allocate local file name for library: %s", strerror(errno));
        return;
    }

    *(localFileName + nativeLibPath.size()) = '/';

    if (strlcpy(localTmpFileName + nativeLibPath.size(), TMP_FILE_PATTERN,
                    TMP_FILE_PATTERN_LEN - nativeLibPath.size()) != TMP_FILE_PATTERN_LEN) {
        LOGI("Couldn't allocate temporary file name for library: %s", strerror(errno));
        return;
    }

    int fd = mkstemp(localTmpFileName);
    if (fd < 0) {
        LOGI("Couldn't open temporary file name: %s: %s\n", localTmpFileName, strerror(errno));
        return;
    }

    if (!zipFile->uncompressEntry(zipEntry, fd)) {
        LOGI("Failed uncompressing %s to %s: %s", fileName, localTmpFileName, strerror(errno));
        close(fd);
        unlink(localTmpFileName);
        return;
    }

    close(fd);

    // Set the modification time for this file to the ZIP's mod time.
    struct timeval times[2];
    times[0].tv_sec = st.st_atime;
    times[1].tv_sec = modTime;
    times[0].tv_usec = times[1].tv_usec = 0;
    if (utimes(localTmpFileName, times) < 0) {
        LOGI("Couldn't change modification time on %s: %s\n", localTmpFileName, strerror(errno));
        unlink(localTmpFileName);
        return;
    }

    // Set the mode to 755
    static const mode_t mode = S_IRUSR | S_IWUSR | S_IXUSR | S_IRGRP |  S_IXGRP | S_IROTH | S_IXOTH;
    if (chmod(localTmpFileName, mode) < 0) {
        LOGI("Couldn't change permissions on %s: %s\n", localTmpFileName, strerror(errno));
        unlink(localTmpFileName);
        return;
    }

    // Finally, rename it to the final name.
    if (rename(localTmpFileName, localFileName) < 0) {
        LOGI("Couldn't rename %s to %s: %s\n", localTmpFileName, localFileName, strerror(errno));
        unlink(localTmpFileName);
        return;
    }

    LOGV("Successfully moved %s to %s\n", localTmpFileName, localFileName);
}

static install_status_t
iterateOverNativeFiles(JNIEnv *env, jstring javaFilePath, jstring javaCpuAbi, jstring javaCpuAbi2,
        iterFunc callFunc, void* callArg) {
    ScopedUtfChars filePath(env, javaFilePath);
    ScopedUtfChars cpuAbi(env, javaCpuAbi);
    ScopedUtfChars cpuAbi2(env, javaCpuAbi2);

    ZipFileRO zipFile;

    if (zipFile.open(filePath.c_str()) != NO_ERROR) {
        LOGI("Couldn't open APK %s\n", filePath.c_str());
        return INSTALL_FAILED_INVALID_APK;
    }

    const int N = zipFile.getNumEntries();

    char fileName[PATH_MAX];

    for (int i = 0; i < N; i++) {
        const ZipEntryRO entry = zipFile.findEntryByIndex(i);
        if (entry == NULL) {
            continue;
        }

        // Make sure this entry has a filename.
        if (zipFile.getEntryFileName(entry, fileName, sizeof(fileName))) {
            continue;
        }

        // Make sure we're in the lib directory of the ZIP.
        if (strncmp(fileName, APK_LIB, APK_LIB_LEN)) {
            continue;
        }

        // Make sure the filename is at least to the minimum library name size.
        const size_t fileNameLen = strlen(fileName);
        static const size_t minLength = APK_LIB_LEN + 2 + LIB_PREFIX_LEN + 1 + LIB_SUFFIX_LEN;
        if (fileNameLen < minLength) {
            continue;
        }

        const char* lastSlash = strrchr(fileName, '/');
        if (lastSlash == NULL) {
            LOG_ASSERT("last slash was null somehow for %s\n", fileName);
            continue;
        }

        // Check to make sure the CPU ABI of this file is one we support.
        const char* cpuAbiOffset = fileName + APK_LIB_LEN;
        const size_t cpuAbiRegionSize = lastSlash - cpuAbiOffset;

        LOGV("Comparing ABIs %s and %s versus %s\n", cpuAbi.c_str(), cpuAbi2.c_str(), cpuAbiOffset);
        if (cpuAbi.size() == cpuAbiRegionSize
                && *(cpuAbiOffset + cpuAbi.size()) == '/'
                && !strncmp(cpuAbiOffset, cpuAbi.c_str(), cpuAbiRegionSize)) {
            LOGV("Using ABI %s\n", cpuAbi.c_str());
        } else if (cpuAbi2.size() == cpuAbiRegionSize
                && *(cpuAbiOffset + cpuAbi2.size()) == '/'
                && !strncmp(cpuAbiOffset, cpuAbi2.c_str(), cpuAbiRegionSize)) {
            LOGV("Using ABI %s\n", cpuAbi2.c_str());
        } else {
            LOGV("abi didn't match anything: %s (end at %zd)\n", cpuAbiOffset, cpuAbiRegionSize);
            continue;
        }

        // If this is a .so file, check to see if we need to copy it.
        if (!strncmp(fileName + fileNameLen - LIB_SUFFIX_LEN, LIB_SUFFIX, LIB_SUFFIX_LEN)
                && !strncmp(lastSlash, LIB_PREFIX, LIB_PREFIX_LEN)
                && isFilenameSafe(lastSlash + 1)) {
            callFunc(env, callArg, &zipFile, entry, lastSlash + 1);
        } else if (!strncmp(lastSlash + 1, GDBSERVER, GDBSERVER_LEN)) {
            callFunc(env, callArg, &zipFile, entry, lastSlash + 1);
        }
    }

    return INSTALL_SUCCEEDED;
}

static jint
com_android_internal_content_NativeLibraryHelper_copyNativeBinaries(JNIEnv *env, jclass clazz,
        jstring javaFilePath, jstring javaNativeLibPath, jstring javaCpuAbi, jstring javaCpuAbi2)
{
    return iterateOverNativeFiles(env, javaFilePath, javaCpuAbi, javaCpuAbi2,
            copyFileIfChanged, &javaNativeLibPath);
}

static jlong
com_android_internal_content_NativeLibraryHelper_sumNativeBinaries(JNIEnv *env, jclass clazz,
        jstring javaFilePath, jstring javaCpuAbi, jstring javaCpuAbi2)
{
    size_t totalSize = 0;

    iterateOverNativeFiles(env, javaFilePath, javaCpuAbi, javaCpuAbi2, sumFiles, &totalSize);

    return totalSize;
}

static JNINativeMethod gMethods[] = {
    {"nativeCopyNativeBinaries",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I",
            (void *)com_android_internal_content_NativeLibraryHelper_copyNativeBinaries},
    {"nativeSumNativeBinaries",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)J",
            (void *)com_android_internal_content_NativeLibraryHelper_sumNativeBinaries},
};


int register_com_android_internal_content_NativeLibraryHelper(JNIEnv *env)
{
    return AndroidRuntime::registerNativeMethods(env,
                "com/android/internal/content/NativeLibraryHelper", gMethods, NELEM(gMethods));
}

};
