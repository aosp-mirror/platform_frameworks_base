/*
 * Copyright (C) 2022 The Android Open Source Project
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

#define ATRACE_TAG ATRACE_TAG_ADB
#define LOG_TAG "Settings-jni"
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/no_destructor.h>
#include <core_jni_helpers.h>
#include <lz4frame.h>
#include <nativehelper/JNIHelp.h>

#include <vector>

namespace android {

namespace {

struct LZ4FCContextDeleter {
    void operator()(LZ4F_cctx* cctx) { LZ4F_freeCompressionContext(cctx); }
};

static constexpr int LZ4_BUFFER_SIZE = 64 * 1024;

static bool writeToFile(std::vector<char>& outBuffer, int fdOut) {
    if (!android::base::WriteFully(fdOut, outBuffer.data(), outBuffer.size())) {
        PLOG(ERROR) << "Error to write to output file";
        return false;
    }
    outBuffer.clear();
    return true;
}

static bool compressAndWriteLz4(LZ4F_cctx* context, std::vector<char>& inBuffer,
                                std::vector<char>& outBuffer, int fdOut) {
    auto inSize = inBuffer.size();
    if (inSize > 0) {
        auto prvSize = outBuffer.size();
        auto outSize = LZ4F_compressBound(inSize, nullptr);
        outBuffer.resize(prvSize + outSize);
        auto rc = LZ4F_compressUpdate(context, outBuffer.data() + prvSize, outSize, inBuffer.data(),
                                      inSize, nullptr);
        if (LZ4F_isError(rc)) {
            LOG(ERROR) << "LZ4F_compressUpdate failed: " << LZ4F_getErrorName(rc);
            return false;
        }
        outBuffer.resize(prvSize + rc);
    }

    if (outBuffer.size() > LZ4_BUFFER_SIZE) {
        return writeToFile(outBuffer, fdOut);
    }

    return true;
}

static jboolean nativeCompressLz4(JNIEnv* env, jclass klass, jint fdIn, jint fdOut) {
    LZ4F_cctx* cctx;
    if (LZ4F_createCompressionContext(&cctx, LZ4F_VERSION) != 0) {
        LOG(ERROR) << "Failed to initialize LZ4 compression context.";
        return false;
    }
    std::unique_ptr<LZ4F_cctx, LZ4FCContextDeleter> context(cctx);

    std::vector<char> inBuffer, outBuffer;
    inBuffer.reserve(LZ4_BUFFER_SIZE);
    outBuffer.reserve(2 * LZ4_BUFFER_SIZE);

    LZ4F_preferences_t prefs;

    memset(&prefs, 0, sizeof(prefs));

    // Set compression parameters.
    prefs.autoFlush = 0;
    prefs.compressionLevel = 0;
    prefs.frameInfo.blockMode = LZ4F_blockLinked;
    prefs.frameInfo.blockSizeID = LZ4F_default;
    prefs.frameInfo.blockChecksumFlag = LZ4F_noBlockChecksum;
    prefs.frameInfo.contentChecksumFlag = LZ4F_contentChecksumEnabled;
    prefs.favorDecSpeed = 0;

    struct stat sb;
    if (fstat(fdIn, &sb) == -1) {
        PLOG(ERROR) << "Failed to obtain input file size.";
        return false;
    }
    prefs.frameInfo.contentSize = sb.st_size;

    // Write header first.
    outBuffer.resize(LZ4F_HEADER_SIZE_MAX);
    auto rc = LZ4F_compressBegin(context.get(), outBuffer.data(), outBuffer.size(), &prefs);
    if (LZ4F_isError(rc)) {
        LOG(ERROR) << "LZ4F_compressBegin failed: " << LZ4F_getErrorName(rc);
        return false;
    }
    outBuffer.resize(rc);

    bool eof = false;
    while (!eof) {
        constexpr auto capacity = LZ4_BUFFER_SIZE;
        inBuffer.resize(capacity);
        auto read = TEMP_FAILURE_RETRY(::read(fdIn, inBuffer.data(), inBuffer.size()));
        if (read < 0) {
            PLOG(ERROR) << "Failed to read from input file.";
            return false;
        }

        inBuffer.resize(read);

        if (read == 0) {
            eof = true;
        }

        if (!compressAndWriteLz4(context.get(), inBuffer, outBuffer, fdOut)) {
            return false;
        }
    }

    // Footer.
    auto prvSize = outBuffer.size();
    outBuffer.resize(outBuffer.capacity());
    rc = LZ4F_compressEnd(context.get(), outBuffer.data() + prvSize, outBuffer.size() - prvSize,
                          nullptr);
    if (LZ4F_isError(rc)) {
        LOG(ERROR) << "LZ4F_compressEnd failed: " << LZ4F_getErrorName(rc);
        return false;
    }
    outBuffer.resize(prvSize + rc);

    if (!writeToFile(outBuffer, fdOut)) {
        return false;
    }

    return true;
}

static const JNINativeMethod method_table[] = {
        {"nativeCompressLz4", "(II)Z", (void*)nativeCompressLz4},
};

} // namespace

int register_android_server_com_android_server_pm_Settings(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/pm/Settings", method_table,
                                    NELEM(method_table));
}

} // namespace android
