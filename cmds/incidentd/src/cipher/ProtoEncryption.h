/*
 * Copyright (C) 2019 The Android Open Source Project
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
#pragma once

#include <android/util/ProtoOutputStream.h>
#include <android/util/ProtoReader.h>
#include <frameworks/base/cmds/incidentd/src/cipher/cipher_blocks.pb.h>

#include "FdBuffer.h"

namespace android {
namespace os {
namespace incidentd {

// PlainText IncidentReport format
// [section1_header(id, size, type)][section1_data] ...

// Let's say section1 needs encryption
// After encryption, it becomes
// [section1_header(id, encrypted_size, type)][[cipher_block][cipher_block][cipher_block]..]

// When clients read the report, it's decrypted, and written in its original format

/**
 * Takes a ProtoReader, encrypts its whole content -- which is one section, and flush to
 * a file descriptor.
 * The underlying encryption is done using Keystore binder APIs. We encrypt the data
 * in blocks, and write to the file in android.os.incidentd.CipherBlocks format.
 */
class ProtoEncryptor {
public:
    ProtoEncryptor(const sp<android::util::ProtoReader>& reader) : mReader(reader){};

    // Encrypt the data from ProtoReader, and store in CipherBlocks format.
    // return the size of CipherBlocks.
    size_t encrypt();

    status_t flush(int fd);

private:
    static const size_t sBlockSize = 8 * 1024;
    const sp<android::util::ProtoReader> mReader;
    android::util::ProtoOutputStream mOutputStream;
};

// Read data from ProtoReader, which is in CipherBlocks proto format. Parse and decrypt
// block by block.
class ProtoDecryptor {
public:
    ProtoDecryptor(const sp<android::util::ProtoReader>& reader, size_t size)
        : mReader(reader), mTotalSize(size){};
    status_t decryptAndFlush(FdBuffer* out);

private:
    const sp<android::util::ProtoReader> mReader;

    // Total size in bytes we should read from ProtoReader.
    const size_t mTotalSize;

    // Read one cipher block from ProtoReader, instead of reading the whole content
    // and parse to CipherBlocks which could be huge.
    status_t readOneBlock(std::string* output);
};

}  // namespace incidentd
}  // namespace os
}  // namespace android
