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

#ifndef __FWDLOCKGLUE_H__
#define __FWDLOCKGLUE_H__

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Generates the specified number of cryptographically secure random bytes.
 *
 * @param[out] pBuffer A reference to the buffer that should receive the random data.
 * @param[in] numBytes The number of random bytes to generate.
 *
 * @return A Boolean value indicating whether the operation was successful.
 */
int FwdLockGlue_GetRandomNumber(void *pBuffer, size_t numBytes);

/**
 * Performs initialization of the key-encryption key. Should be called once during startup to
 * facilitate encryption and decryption of session keys.
 *
 * @return A Boolean value indicating whether the operation was successful.
 */
int FwdLockGlue_InitializeKeyEncryption();

/**
 * Returns the length of the encrypted key, given the length of the plaintext key.
 *
 * @param[in] plaintextKeyLength The length in bytes of the plaintext key.
 *
 * @return The length in bytes of the encrypted key.
 */
size_t FwdLockGlue_GetEncryptedKeyLength(size_t plaintextKeyLength);

/**
 * Encrypts the given session key using a key-encryption key unique to this device.
 *
 * @param[in] pPlaintextKey A reference to the buffer containing the plaintext key.
 * @param[in] plaintextKeyLength The length in bytes of the plaintext key.
 * @param[out] pEncryptedKey A reference to the buffer containing the encrypted key.
 * @param[in] encryptedKeyLength The length in bytes of the encrypted key.
 *
 * @return A Boolean value indicating whether the operation was successful.
 */
int FwdLockGlue_EncryptKey(const void *pPlaintextKey,
                           size_t plaintextKeyLength,
                           void *pEncryptedKey,
                           size_t encryptedKeyLength);

/**
 * Decrypts the given session key using a key-encryption key unique to this device.
 *
 * @param[in] pEncryptedKey A reference to the buffer containing the encrypted key.
 * @param[in] encryptedKeyLength The length in bytes of the encrypted key.
 * @param[out] pDecryptedKey A reference to the buffer containing the decrypted key.
 * @param[in] decryptedKeyLength The length in bytes of the decrypted key.
 *
 * @return A Boolean value indicating whether the operation was successful.
 */
int FwdLockGlue_DecryptKey(const void *pEncryptedKey,
                           size_t encryptedKeyLength,
                           void *pDecryptedKey,
                           size_t decryptedKeyLength);

#ifdef __cplusplus
}
#endif

#endif // __FWDLOCKGLUE_H__
