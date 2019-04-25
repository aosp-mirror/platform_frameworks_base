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

#define DEBUG true  // STOPSHIP if true
#include "Log.h"

#include "ProtoEncryption.h"

#include <android/util/protobuf.h>

#include "IncidentKeyStore.h"

namespace android {
namespace os {
namespace incidentd {

using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_STRING;
using android::util::ProtoOutputStream;
using android::util::ProtoReader;
using std::string;

static const int FIELD_ID_BLOCK = 1;

size_t ProtoEncryptor::encrypt() {
    string block;
    int i = 0;
    // Read at most sBlockSize at a time and encrypt.
    while (mReader->readBuffer() != NULL) {
        size_t readBytes =
                mReader->currentToRead() > sBlockSize ? sBlockSize : mReader->currentToRead();
        block.resize(readBytes);
        std::memcpy(block.data(), mReader->readBuffer(), readBytes);

        string encrypted;
        if (IncidentKeyStore::getInstance().encrypt(block, 0, &encrypted)) {
            mOutputStream.write(FIELD_TYPE_STRING | FIELD_ID_BLOCK | FIELD_COUNT_REPEATED,
                                encrypted);
            VLOG("Block %d Encryption: original %lld now %lld", i++, (long long)readBytes,
                 (long long)encrypted.length());
            mReader->move(readBytes);
        } else {
            return 0;
        }
    }
    return mOutputStream.size();
}

status_t ProtoEncryptor::flush(int fd) {
    if (!mOutputStream.flush(fd)) {
        return BAD_VALUE;
    }
    return NO_ERROR;
}

status_t ProtoDecryptor::readOneBlock(string* output) {
    if (!mReader->hasNext()) {
        return NO_ERROR;
    }
    uint64_t fieldTag = mReader->readRawVarint();
    uint32_t fieldId = read_field_id(fieldTag);
    uint8_t wireType = read_wire_type(fieldTag);
    if (wireType == WIRE_TYPE_LENGTH_DELIMITED) {
        // Read this section from the reader into an FdBuffer
        size_t sectionSize = mReader->readRawVarint();
        output->resize(sectionSize);
        size_t pos = 0;
        while (pos < sectionSize && mReader->readBuffer() != NULL) {
            size_t toRead = (sectionSize - pos) > mReader->currentToRead()
                                    ? mReader->currentToRead()
                                    : (sectionSize - pos);
            std::memcpy(&((output->data())[pos]), mReader->readBuffer(), toRead);
            pos += toRead;
            mReader->move(toRead);
        }
        if (pos != sectionSize) {
            ALOGE("Failed to read one block");
            return BAD_VALUE;
        }
    } else {
        return BAD_VALUE;
    }
    return NO_ERROR;
}

status_t ProtoDecryptor::decryptAndFlush(FdBuffer* out) {
    size_t mStartBytes = mReader->bytesRead();
    size_t bytesRead = 0;
    int i = 0;
    status_t err = NO_ERROR;
    // Let's read until we read mTotalSize. If any error occurs before that, make sure to move the
    // read pointer so the caller can continue to read the following sections.
    while (bytesRead < mTotalSize) {
        string block;
        err = readOneBlock(&block);
        bytesRead = mReader->bytesRead() - mStartBytes;

        if (err != NO_ERROR) {
            break;
        }

        if (block.length() == 0) {
            VLOG("Done reading all blocks");
            break;
        }

        string decryptedBlock;
        if ((IncidentKeyStore::getInstance()).decrypt(block, &decryptedBlock)) {
            VLOG("Block %d Original Size %lu Decrypted size %lu", i++,
                 (unsigned long)block.length(), (unsigned long)decryptedBlock.length());
            out->write(reinterpret_cast<uint8_t*>(decryptedBlock.data()), decryptedBlock.length());
        } else {
            err = BAD_VALUE;
            break;
        }
    }

    if (bytesRead < mTotalSize) {
        mReader->move(mTotalSize - bytesRead);
    }
    return err;
}

}  // namespace incidentd
}  // namespace os
}  // namespace android
