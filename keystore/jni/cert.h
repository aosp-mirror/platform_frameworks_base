/*
**
** Copyright 2009, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#ifndef __CERT_H__
#define __CERT_H__

#define ANDROID_KEYSTORE "Android Keystore"
#define KEYGEN_STORE_SIZE     5
#define KEYLENGTH_MEDIUM      1024
#define KEYLENGTH_MAXIMUM     2048
#define MAX_CERT_NAME_LEN     128
#define MAX_PEM_LENGTH        4096
#define REPLY_MAX             MAX_PEM_LENGTH


#define STR(token) #token
#define ERR_INVALID_KEY_LENGTH  1
#define ERR_CONSTRUCT_NEW_DATA  2
#define ERR_RSA_KEYGEN          3
#define ERR_X509_PROCESS        4
#define ERR_BIO_READ            5
#define ERR_MAXIMUM             6

typedef struct {
    EVP_PKEY *pkey;
    unsigned char *public_key;
    int key_len;
} PKEY_STORE;

#define PKEY_STORE_free(x) { \
    if(x.pkey) EVP_PKEY_free(x.pkey); \
    if(x.public_key) free(x.public_key); \
}

#define nelem(x) (sizeof (x) / sizeof *(x))

int gen_csr(int bits, const char *organizations, char reply[REPLY_MAX]);
int is_pkcs12(const char *buf, int bufLen);
X509*    parse_cert(const char *buf, int bufLen);
int get_cert_name(X509 *cert, char *buf, int size);
int get_issuer_name(X509 *cert, char *buf, int size);
int is_ca_cert(X509 *cert);
int get_private_key_pem(X509 *cert, char *buf, int size);

#endif
