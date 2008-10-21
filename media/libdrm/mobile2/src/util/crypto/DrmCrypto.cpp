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

#include <util/crypto/DrmCrypto.h>
#include <ustring.h>

using namespace ustl;

void AesAgent::discardPaddingByte( unsigned char* decryptedBuf,unsigned long* decryptedBufLen)
{
    if(!decryptedBuf)
    {
        return;
    }

    int i;
    unsigned long tmpLen = *decryptedBufLen;

    // Check whether the last several bytes are padding or not
    for ( i = 1; i < decryptedBuf[tmpLen - 1]; i++)
    {
        if (decryptedBuf[tmpLen - 1 - i] != decryptedBuf[tmpLen - 1])
            break;
    }

    // They are padding bytes
    if (i == decryptedBuf[tmpLen - 1])
    {
        *decryptedBufLen = tmpLen - i;
    }

    return;
}

int32_t AesAgent::decContent( unsigned char* iv,
                              const unsigned char* encData,
                              unsigned long encLen,
                              unsigned char* decData)
{
    if(AES_128_CBC == mode)
    {
        AES_KEY key;
        AES_set_decrypt_key(AesKey,AES_KEY_BITS,&key);

        uint8_t *tmpBuf = new uint8_t[encLen];

        AES_cbc_encrypt( encData,
                         tmpBuf,
                         encLen,
                         &key,
                         iv,
                         AES_DECRYPT);

        unsigned long tempLen = encLen;
        discardPaddingByte(tmpBuf,&tempLen);

        memcpy(decData, tmpBuf, tempLen);

        delete []tmpBuf;
        return encLen - tempLen;
    }
    else
    {
        return AES_DEC_FAILED;
    }
}

void Sha1Agent::computeHash( const unsigned char* inData,
                             unsigned long inLen,
                             unsigned char* outHash) const
{
    EVP_Digest(inData,inLen,outHash,NULL,EVP_sha1(),NULL);
    return;
}

void HmacSha1Agent::computeMac( const unsigned char* inData,
                                unsigned long inLen,
                                unsigned char* outData) const
{
    HMAC(EVP_sha1(),macKey,keyLen,inData,inLen,outData,NULL);
    return;
}

bool RsaAgent::signature( const unsigned char* rawData,
                          unsigned long rawLen,
                          unsigned char* sigData,
                          RsaAlg sigAlg)
{
    switch(sigAlg)
    {
        case RSA_PSS:
            {
                unsigned char mHash[SHA_DIGEST_LENGTH];
                Sha1Agent sha1;
                sha1.computeHash(rawData,rawLen,mHash);

                unsigned char EM[rsaSize];
                if( 0 == RSA_padding_add_PKCS1_PSS( &rsaKey,
                                                    EM,
                                                    mHash,
                                                    EVP_sha1(),
                                                    SHA_DIGEST_LENGTH))
                {
                    return false;
                }

                if(0 > RSA_private_encrypt( SHA_DIGEST_LENGTH,
                                            EM,
                                            sigData,
                                            &rsaKey,
                                            RSA_PKCS1_PADDING))
                {
                    return false;
                }
                else
                {
                    return true;
                }
            }
            break;
        case RSA_SHA1:
            {
                unsigned char mHash[SHA_DIGEST_LENGTH];
                Sha1Agent sha1;
                sha1.computeHash(rawData,rawLen,mHash);

                if(0 != RSA_sign( NID_sha1WithRSA,
                                  mHash,
                                  SHA_DIGEST_LENGTH,
                                  sigData,
                                  &rsaSize,
                                  &rsaKey))
                {
                    return true;
                }
                else
                {
                    return false;
                }
            }
           break;
        default:
            return false;
    }

    return false;
}

bool RsaAgent::sigVerify( unsigned char* sigData,
                          unsigned long sigLen,
                          const unsigned char* rawData,
                          unsigned long rawLen,
                          RsaAlg sigAlg)
{
    if( sigAlg == RSA_PSS)
    {
        unsigned char decSigData[rsaSize];

        if(0 > RSA_public_decrypt(sigLen,
                                  sigData,
                                  decSigData,
                                  &rsaKey,
                                  RSA_PKCS1_PADDING))
        {
            return false;
        }
        else
        {
            unsigned char mHash[SHA_DIGEST_LENGTH];
            Sha1Agent sha1;
            sha1.computeHash(rawData,rawLen,mHash);

            if( 0 == RSA_verify_PKCS1_PSS( &rsaKey,
                                           mHash,
                                           EVP_sha1(),
                                           decSigData,
                                           -1))
            {
                return true;
            }
            else
            {
                return false;
            }
        }
    }
    else if(sigAlg == RSA_SHA1)
    {
        unsigned char mHash[SHA_DIGEST_LENGTH];
        Sha1Agent sha1;
        sha1.computeHash(rawData,rawLen,mHash);

        if(0 != RSA_verify( NID_sha1WithRSA,
                            mHash,
                            SHA_DIGEST_LENGTH,
                            sigData,
                            sigLen,
                            &rsaKey))
        {
            return true;
        }
        else
        {
            return false;
        }
    }
    else
    {
        return false;
    }
}

int RsaAgent::decrypt( const unsigned char* encData,
                       unsigned long encLen,
                       unsigned char* decData)
{
    return RSA_private_decrypt( encLen,
                                encData,
                                decData,
                                &rsaKey,
                                RSA_PKCS1_PADDING);
}
