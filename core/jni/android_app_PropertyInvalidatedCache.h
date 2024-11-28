/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <string.h>
#include <memory.h>

#include <atomic>

namespace android {
namespace app {
namespace PropertyInvalidatedCache {

/**
 * A cache nonce block contains an array of std::atomic<int64_t> and an array of bytes.  The
 * byte array has an associated hash.  This class provides methods to read and write the fields
 * of the block but it does not interpret the fields.
 *
 * On initialization, all fields are set to zero.
 *
 * In general, methods do not report errors.  This allows the methods to be used in
 * CriticalNative JNI APIs.
 *
 * The template is parameterized by the number of nonces it supports and the number of bytes in
 * the string block.
 */
template<int maxNonce, size_t maxByte> class CacheNonce {

    // The value of an unset field.
    static const int UNSET = 0;

    // A convenient typedef.  The jbyteArray element type is jbyte, which the compiler treats as
    // signed char.
    typedef signed char block_t;

    // The array of nonces
    volatile std::atomic<int64_t> mNonce[maxNonce];

    // The byte array.  This is not atomic but it is guarded by the mByteHash.
    volatile block_t mByteBlock[maxByte];

    // The hash that validates the byte block
    volatile std::atomic<int32_t> mByteHash;

    // Pad the class to a multiple of 8 bytes.
    int32_t _pad;

  public:

    // The expected size of this instance.  This is a compile-time constant and can be used in a
    // static assertion.
    static const int expectedSize =
            maxNonce * sizeof(std::atomic<int64_t>)
            + sizeof(std::atomic<int32_t>)
            + maxByte * sizeof(block_t)
            + sizeof(int32_t);

    // These provide run-time access to the sizing parameters.
    int getMaxNonce() const {
        return maxNonce;
    }

    size_t getMaxByte() const {
        return maxByte;
    }

    // Construct and initialize the memory.
    CacheNonce() {
        for (int i = 0; i < maxNonce; i++) {
            mNonce[i] = UNSET;
        }
        mByteHash = UNSET;
        memset((void*) mByteBlock, UNSET, sizeof(mByteBlock));
    }

    // Fetch a nonce, returning UNSET if the index is out of range.  This method specifically
    // does not throw or generate an error if the index is out of range; this allows the method
    // to be called in a CriticalNative JNI API.
    int64_t getNonce(int index) const {
        if (index < 0 || index >= maxNonce) {
            return UNSET;
        } else {
            return mNonce[index];
        }
    }

    // Set a nonce and return true. Return false if the index is out of range.  This method
    // specifically does not throw or generate an error if the index is out of range; this
    // allows the method to be called in a CriticalNative JNI API.
    bool setNonce(int index, int64_t value) {
        if (index < 0 || index >= maxNonce) {
            return false;
        } else {
            mNonce[index] = value;
            return true;
        }
    }

    // Fetch just the byte-block hash
    int32_t getHash() const {
        return mByteHash;
    }

    // Copy the byte block to the target and return the current hash.
    int32_t getByteBlock(block_t* block, size_t len) const {
        memcpy(block, (void*) mByteBlock, std::min(maxByte, len));
        return mByteHash;
    }

    // Set the byte block and the hash.
    void setByteBlock(int hash, const block_t* block, size_t len) {
        memcpy((void*) mByteBlock, block, len = std::min(maxByte, len));
        mByteHash = hash;
    }
};

/**
 * Sizing parameters for the system_server PropertyInvalidatedCache support.  A client can
 * retrieve the values through the accessors in CacheNonce instances.
 */
static const int MAX_NONCE = 64;
static const int BYTE_BLOCK_SIZE = 8192;

// The CacheNonce for system server holds 64 nonces with a string block of 8192 bytes.
typedef CacheNonce<MAX_NONCE, BYTE_BLOCK_SIZE> SystemCacheNonce;

// The goal of this assertion is to ensure that the data structure is the same size across 32-bit
// and 64-bit systems.
static_assert(sizeof(SystemCacheNonce) == SystemCacheNonce::expectedSize,
              "Unexpected SystemCacheNonce size");

} // namespace PropertyInvalidatedCache
} // namespace app
} // namespace android
