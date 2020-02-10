/*
 * Copyright (C) 2020 The Android Open Source Project
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
#define LOG_TAG "PackageManagerShellCommandDataLoader-jni"
#include <android-base/logging.h>

#include <android-base/unique_fd.h>
#include <nativehelper/JNIHelp.h>

#include <core_jni_helpers.h>

#include "dataloader.h"

#include <chrono>
#include <thread>

namespace android {

namespace {

using android::base::unique_fd;

static constexpr int BUFFER_SIZE = 256 * 1024;
static constexpr int BLOCKS_COUNT = BUFFER_SIZE / INCFS_DATA_FILE_BLOCK_SIZE;

struct JniIds {
    jclass packageManagerShellCommandDataLoader;
    jmethodID pmscdLookupShellCommand;
    jmethodID pmscdGetStdInPFD;
    jmethodID pmscdGetLocalFile;

    jmethodID parcelFileDescriptorGetFileDescriptor;

    jclass ioUtils;
    jmethodID ioUtilsCloseQuietly;

    JniIds(JNIEnv* env) {
        packageManagerShellCommandDataLoader = (jclass)env->NewGlobalRef(
                FindClassOrDie(env, "com/android/server/pm/PackageManagerShellCommandDataLoader"));
        pmscdLookupShellCommand =
                GetStaticMethodIDOrDie(env, packageManagerShellCommandDataLoader,
                                       "lookupShellCommand",
                                       "(Ljava/lang/String;)Landroid/os/ShellCommand;");
        pmscdGetStdInPFD =
                GetStaticMethodIDOrDie(env, packageManagerShellCommandDataLoader, "getStdInPFD",
                                       "(Landroid/os/ShellCommand;)Landroid/os/"
                                       "ParcelFileDescriptor;");
        pmscdGetLocalFile =
                GetStaticMethodIDOrDie(env, packageManagerShellCommandDataLoader, "getLocalFile",
                                       "(Landroid/os/ShellCommand;Ljava/lang/String;)Landroid/os/"
                                       "ParcelFileDescriptor;");

        auto parcelFileDescriptor = FindClassOrDie(env, "android/os/ParcelFileDescriptor");
        parcelFileDescriptorGetFileDescriptor =
                GetMethodIDOrDie(env, parcelFileDescriptor, "getFileDescriptor",
                                 "()Ljava/io/FileDescriptor;");

        ioUtils = (jclass)env->NewGlobalRef(FindClassOrDie(env, "libcore/io/IoUtils"));
        ioUtilsCloseQuietly = GetStaticMethodIDOrDie(env, ioUtils, "closeQuietly",
                                                     "(Ljava/lang/AutoCloseable;)V");
    }
};

const JniIds& jniIds(JNIEnv* env) {
    static const JniIds ids(env);
    return ids;
}

static inline unique_fd convertPfdToFdAndDup(JNIEnv* env, const JniIds& jni, jobject pfd) {
    if (!pfd) {
        ALOGE("Missing In ParcelFileDescriptor.");
        return {};
    }
    auto managedFd = env->CallObjectMethod(pfd, jni.parcelFileDescriptorGetFileDescriptor);
    if (!pfd) {
        ALOGE("Missing In FileDescriptor.");
        return {};
    }
    return unique_fd{dup(jniGetFDFromFileDescriptor(env, managedFd))};
}

static inline std::pair<unique_fd, bool> openIncomingFile(JNIEnv* env, const JniIds& jni,
                                                          jobject shellCommand,
                                                          IncFsSpan metadata) {
    jobject pfd = nullptr;
    const bool stdin = (metadata.size == 0 || *metadata.data == '-');
    if (stdin) {
        // stdin
        pfd = env->CallStaticObjectMethod(jni.packageManagerShellCommandDataLoader,
                                          jni.pmscdGetStdInPFD, shellCommand);
    } else {
        // file
        const std::string filePath(metadata.data, metadata.size);
        pfd = env->CallStaticObjectMethod(jni.packageManagerShellCommandDataLoader,
                                          jni.pmscdGetLocalFile, shellCommand,
                                          env->NewStringUTF(filePath.c_str()));
    }

    auto result = convertPfdToFdAndDup(env, jni, pfd);
    if (pfd) {
        // Can be closed after dup.
        env->CallStaticVoidMethod(jni.ioUtils, jni.ioUtilsCloseQuietly, pfd);
    }

    return {std::move(result), stdin};
}

static inline JNIEnv* GetJNIEnvironment(JavaVM* vm) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return 0;
    }
    return env;
}

static inline JNIEnv* GetOrAttachJNIEnvironment(JavaVM* jvm) {
    JNIEnv* env = GetJNIEnvironment(jvm);
    if (!env) {
        int result = jvm->AttachCurrentThread(&env, nullptr);
        CHECK_EQ(result, JNI_OK) << "thread attach failed";
        struct VmDetacher {
            VmDetacher(JavaVM* vm) : mVm(vm) {}
            ~VmDetacher() { mVm->DetachCurrentThread(); }

        private:
            JavaVM* const mVm;
        };
        static thread_local VmDetacher detacher(jvm);
    }
    return env;
}

class PackageManagerShellCommandDataLoaderDataLoader : public android::dataloader::DataLoader {
public:
    PackageManagerShellCommandDataLoaderDataLoader(JavaVM* jvm) : mJvm(jvm) { CHECK(mJvm); }

private:
    // Lifecycle.
    bool onCreate(const android::dataloader::DataLoaderParams& params,
                  android::dataloader::FilesystemConnectorPtr ifs,
                  android::dataloader::StatusListenerPtr statusListener,
                  android::dataloader::ServiceConnectorPtr,
                  android::dataloader::ServiceParamsPtr) final {
        mArgs = params.arguments();
        mIfs = ifs;
        return true;
    }
    bool onStart() final { return true; }
    void onStop() final {}
    void onDestroy() final {}

    // IFS callbacks.
    void onPendingReads(const dataloader::PendingReads& pendingReads) final {}
    void onPageReads(const dataloader::PageReads& pageReads) final {}

    // FS callbacks.
    bool onPrepareImage(const dataloader::DataLoaderInstallationFiles& addedFiles) final {
        JNIEnv* env = GetOrAttachJNIEnvironment(mJvm);
        const auto& jni = jniIds(env);

        jobject shellCommand = env->CallStaticObjectMethod(jni.packageManagerShellCommandDataLoader,
                                                           jni.pmscdLookupShellCommand,
                                                           env->NewStringUTF(mArgs.c_str()));
        if (!shellCommand) {
            ALOGE("Missing shell command.");
            return false;
        }

        std::vector<char> buffer;
        buffer.reserve(BUFFER_SIZE);

        std::vector<IncFsDataBlock> blocks;
        blocks.reserve(BLOCKS_COUNT);

        for (auto&& file : addedFiles) {
            auto [incomingFd, stdin] = openIncomingFile(env, jni, shellCommand, file.metadata);
            if (incomingFd < 0) {
                ALOGE("Failed to open an IncFS file for metadata: %.*s, final file name is: %s. "
                      "Error %d",
                      int(file.metadata.size), file.metadata.data, file.name, errno);
                return false;
            }

            const auto fileId = IncFs_FileIdFromMetadata(file.metadata);

            const auto incfsFd(mIfs->openWrite(fileId));
            if (incfsFd < 0) {
                ALOGE("Failed to open an IncFS file for metadata: %.*s, final file name is: %s. "
                      "Error %d",
                      int(file.metadata.size), file.metadata.data, file.name, errno);
                return false;
            }

            IncFsSize size = file.size;
            IncFsSize remaining = size;
            IncFsSize totalSize = 0;
            IncFsBlockIndex blockIdx = 0;
            while (remaining > 0) {
                constexpr auto capacity = BUFFER_SIZE;
                auto size = buffer.size();
                if (capacity - size < INCFS_DATA_FILE_BLOCK_SIZE) {
                    if (!flashToIncFs(incfsFd, false, &blocks, &blockIdx, &buffer)) {
                        return false;
                    }
                    continue;
                }

                auto toRead = std::min<IncFsSize>(remaining, capacity - size);
                buffer.resize(size + toRead);
                auto read = ::read(incomingFd, buffer.data() + size, toRead);
                if (read == 0) {
                    if (stdin) {
                        // eof of stdin, waiting...
                        ALOGE("eof of stdin, waiting...: %d, remaining: %d, block: %d, read: %d",
                              int(totalSize), int(remaining), int(blockIdx), int(read));
                        using namespace std::chrono_literals;
                        std::this_thread::sleep_for(10ms);
                        continue;
                    }
                    break;
                }
                if (read < 0) {
                    ALOGE("Underlying file read error: %.*s: %d", int(file.metadata.size),
                          file.metadata.data, int(read));
                    return false;
                }

                buffer.resize(size + read);
                remaining -= read;
                totalSize += read;
            }
            if (!buffer.empty() && !flashToIncFs(incfsFd, true, &blocks, &blockIdx, &buffer)) {
                return false;
            }
        }

        ALOGE("All done.");
        return true;
    }

    bool flashToIncFs(int incfsFd, bool eof, std::vector<IncFsDataBlock>* blocks,
                      IncFsBlockIndex* blockIdx, std::vector<char>* buffer) {
        int consumed = 0;
        const auto fullBlocks = buffer->size() / INCFS_DATA_FILE_BLOCK_SIZE;
        for (int i = 0; i < fullBlocks; ++i) {
            const auto inst = IncFsDataBlock{
                    .fileFd = incfsFd,
                    .pageIndex = (*blockIdx)++,
                    .compression = INCFS_COMPRESSION_KIND_NONE,
                    .kind = INCFS_BLOCK_KIND_DATA,
                    .dataSize = INCFS_DATA_FILE_BLOCK_SIZE,
                    .data = buffer->data() + consumed,
            };
            blocks->push_back(inst);
            consumed += INCFS_DATA_FILE_BLOCK_SIZE;
        }
        const auto remain = buffer->size() - fullBlocks * INCFS_DATA_FILE_BLOCK_SIZE;
        if (remain && eof) {
            const auto inst = IncFsDataBlock{
                    .fileFd = incfsFd,
                    .pageIndex = (*blockIdx)++,
                    .compression = INCFS_COMPRESSION_KIND_NONE,
                    .kind = INCFS_BLOCK_KIND_DATA,
                    .dataSize = static_cast<uint16_t>(remain),
                    .data = buffer->data() + consumed,
            };
            blocks->push_back(inst);
            consumed += remain;
        }

        auto res = mIfs->writeBlocks({blocks->data(), blocks->data() + blocks->size()});

        blocks->clear();
        buffer->erase(buffer->begin(), buffer->begin() + consumed);

        if (res < 0) {
            ALOGE("Failed to write block to IncFS: %d", int(res));
            return false;
        }
        return true;
    }

    JavaVM* const mJvm;
    std::string mArgs;
    android::dataloader::FilesystemConnectorPtr mIfs;
};

static void nativeInitialize(JNIEnv* env, jclass klass) {
    jniIds(env);
}

static const JNINativeMethod method_table[] = {
        {"nativeInitialize", "()V", (void*)nativeInitialize},
};

} // namespace

int register_android_server_com_android_server_pm_PackageManagerShellCommandDataLoader(
        JNIEnv* env) {
    android::dataloader::DataLoader::initialize(
            [](auto jvm, const auto& params) -> android::dataloader::DataLoaderPtr {
                if (params.type() == DATA_LOADER_TYPE_INCREMENTAL) {
                    // This DataLoader only supports incremental installations.
                    return std::make_unique<PackageManagerShellCommandDataLoaderDataLoader>(jvm);
                }
                return {};
            });
    return jniRegisterNativeMethods(env,
                                    "com/android/server/pm/PackageManagerShellCommandDataLoader",
                                    method_table, NELEM(method_table));
}

} // namespace android
