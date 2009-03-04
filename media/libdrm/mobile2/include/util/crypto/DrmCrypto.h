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

#ifndef _DRMCRYPTO_H_
#define _DRMCRYPTO_H_

#include <Drm2CommonTypes.h>
#include <openssl/aes.h>
#include <openssl/hmac.h>
#include <openssl/sha.h>
#include <openssl/rsa.h>

// AES encrypt mode
typedef enum {AES_128_CBC = 0x01,AES_128_CTR = 0x02}AesMode;

// aes crypto for decrypt
class AesAgent
{
    public:
        AesAgent(const AesMode method,const unsigned char* decryptedKey)
            :mode(method),AesKey(decryptedKey){};

        /**
         * decrypt data using AES, now only support 128 bits CBC
         * \param iv       128 bits initialization vector/counter
         *                 prefixing the ciphertext
         * \param encData  encrypted data
         * \param encLen   the length of encData
         * \param decData  the buffer to store decrypted data
         * \param decLen   the actual length of decrypted data
         * \return
         *   >=   succeed, the padding length
         *   < 0  failed
         */
        int32_t decContent( unsigned char* iv,
                            const unsigned char* encData,
                            const unsigned long encLen,
                            unsigned char* decData);
        static const int32_t AES_DEC_FAILED = -1;

    PRIVATE:
        static const uint32_t AES_KEY_BITS = 128;
        const AesMode mode;
        const unsigned char* AesKey;

    PRIVATE:
        // get the actual length of decrypt data
        void discardPaddingByte(unsigned char* decryptedBuf,unsigned long* decryptedBufLen);
};

// Sha1 crypto for hash
class Sha1Agent
{
    public:
        /**
         * compute hash using Sha1
         * \param inData   the data to be hashed
         * \param inLen    the length of inData
         * \param outHash  the hash of inData
         * \return   none
         */
        void computeHash( const unsigned char* inData,
                          unsigned long inLen,
                          unsigned char* outHash) const;

        /**
         * get the length of SHA1 hash
         * \param  none
         * \return
         *      the length of SHA1 hash
         */
        unsigned long getShaLen(void) const
        {
            return SHA_DIGEST_LENGTH;
        }
};

// Hmac-Sha1 crypto for MAC
class HmacSha1Agent
{
    public:
        HmacSha1Agent(const unsigned char* Key, int key_len)
          :macKey(Key),keyLen(key_len){};

        /**
         * compute MAC using Hmac-Sha1
         * \param inData  the data to be MAC
         * \param inLen   the length of inData
         * \param outMac  the MAC of inData
         * \return   none
         */
        void computeMac( const unsigned char* inData,
                         unsigned long inLen,
                         unsigned char* outMac) const;

        /**
         * get the length of HMAC-SHA1 MAC
         * \param  none
         * \return
         *      the length of HMAC-SHA1 MAC
         */
        unsigned long getHmacLen(void) const
        {
            return SHA_DIGEST_LENGTH;
        }

    PRIVATE:
        const unsigned char* macKey;
        const int keyLen;
};

// Rsa crypto for signature,verify signature and key transport
class RsaAgent
{
    public:
        RsaAgent(RSA& Key):rsaKey(Key)
        {
            rsaSize = (unsigned int)RSA_size(&Key);
        };

        // signature algorithm
        typedef enum {RSA_PSS,RSA_SHA1}RsaAlg;

        /**
         * Do signature using RSA-PSS
         * \param rawData  the data to be signature
         * \param rawLen   the length of inData
         * \param sigData  the buffer to store the signature of rawData
         * \param sigAlg   signature algorithm
         * \return
         *   true   succeed
         *   false  failed
         */
        bool signature( const unsigned char* rawData,
                        const unsigned long rawLen,
                        unsigned char* sigData,
                        const RsaAlg sigAlg);

        /**
         * get the length of signature
         * \param  none
         * \return
         *      the length of signature
         */
        unsigned int getSigLen(void) const
        {
            return rsaSize;
        }

        /**
         * Verify signature using RSA-PSS
         * \param sigData  the data to be verify
         * \param sigLen   the length of sigData
         * \param rawData  the data from which the sigData generated
         * \param rawLen   the length of rawData
         * \param sigAlg   signature algorithm
         * \return
         *   true   succeed
         *   false  failed
         */
        bool sigVerify(unsigned char* sigData,
                       unsigned long sigLen,
                       const unsigned char* rawData,
                       const unsigned long rawLen,
                       const RsaAlg sigAlg);


        /**
         * Decrypt data using RSA
         * \param encData  encrypted data
         * \param encLen   the length of encData
         * \param decData  the buffer to store decrypted data
         * \return
         *   -1  decrypted failed
         *   >0  the actual length of decrypted data
         */
        int decrypt( const unsigned char* encData,
                     const unsigned long encLen,
                     unsigned char* decData);

        /**
         * get the length of decrypted data
         * \param none
         * \return
         *      the length of decrypted data
         */
        unsigned int getDecLen(void) const
        {
           return rsaSize;
        }

    PRIVATE:
        RSA& rsaKey;
        unsigned int rsaSize;
};


#endif /* _DRMCRYPTO_H_ */
