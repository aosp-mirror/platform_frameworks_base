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

#define LOG_TAG "CertTool"

#include <stdio.h>
#include <openssl/engine.h>
#include <openssl/pem.h>
#include <openssl/pkcs12.h>
#include <openssl/rsa.h>
#include <openssl/x509v3.h>
#include <cutils/log.h>

#include "cert.h"

static PKEY_STORE pkey_store[KEYGEN_STORE_SIZE];
static int store_index = 0;

static char emsg[][30] = {
    "",
    STR(ERR_INVALID_KEY_LENGTH),
    STR(ERR_CONSTRUCT_NEW_DATA),
    STR(ERR_RSA_KEYGEN),
    STR(ERR_X509_PROCESS),
    STR(ERR_BIO_READ),
};

static void save_in_store(X509_REQ *req, EVP_PKEY *pkey)
{
    EVP_PKEY *newpkey = EVP_PKEY_new();
    RSA *rsa = EVP_PKEY_get1_RSA(pkey);
    EVP_PKEY_set1_RSA(newpkey, rsa);
    PKEY_STORE_free(pkey_store[store_index]);
    pkey_store[store_index].key_len =
    i2d_X509_PUBKEY(req->req_info->pubkey, &pkey_store[store_index].public_key);
    pkey_store[store_index++].pkey = newpkey;
    store_index %= KEYGEN_STORE_SIZE;
    RSA_free(rsa);
}

static EVP_PKEY *get_pkey_from_store(X509 *cert)
{
    int i, key_len;
    unsigned char *buf = NULL;
    if ((key_len = i2d_X509_PUBKEY(X509_get_X509_PUBKEY(cert), &buf)) == 0) {
        return NULL;
    }
    for (i = 0 ; i < KEYGEN_STORE_SIZE ; ++i) {
        if ((key_len == pkey_store[i].key_len) &&
            memcmp(buf, pkey_store[i].public_key, key_len) == 0) {
            break;
        }
    }
    free(buf);
    return (i == KEYGEN_STORE_SIZE) ? NULL : pkey_store[i].pkey;
}

int gen_csr(int bits, const char *organizations, char reply[REPLY_MAX])
{
    int len, ret_code = 0;
    BIGNUM *bn = NULL;
    BIO *bio = NULL;
    EVP_PKEY *pkey = NULL;
    RSA *rsa = NULL;
    X509_REQ *req = NULL;
    X509_NAME *name = NULL;

    if ((bio = BIO_new(BIO_s_mem())) == NULL) goto err;

    if ((bits != KEYLENGTH_MEDIUM) && (bits != KEYLENGTH_MAXIMUM)) {
        ret_code = ERR_INVALID_KEY_LENGTH;
        goto err;
    }

    if (((pkey = EVP_PKEY_new()) == NULL) ||
        ((req = X509_REQ_new()) == NULL) ||
        ((rsa = RSA_new()) == NULL) || ((bn = BN_new()) == NULL)) {
        ret_code = ERR_CONSTRUCT_NEW_DATA;
        goto err;
    }

    if (!BN_set_word(bn, RSA_F4) ||
        !RSA_generate_key_ex(rsa, bits, bn, NULL) ||
        !EVP_PKEY_assign_RSA(pkey, rsa)) {
        ret_code = ERR_RSA_KEYGEN;
        goto err;
    }

    // rsa will be part of the req, it will be freed in X509_REQ_free(req)
    rsa = NULL;

    X509_REQ_set_pubkey(req, pkey);
    name = X509_REQ_get_subject_name(req);

    X509_NAME_add_entry_by_txt(name, "C",  MBSTRING_ASC,
                               (const unsigned char *)"US", -1, -1, 0);
    X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC,
                               (const unsigned char *) ANDROID_KEYSTORE,
                               -1, -1, 0);
    X509_NAME_add_entry_by_txt(name, "O", MBSTRING_ASC,
                               (const unsigned char *)organizations, -1, -1, 0);

    if (!X509_REQ_sign(req, pkey, EVP_md5()) ||
        (PEM_write_bio_X509_REQ(bio, req) <= 0)) {
        ret_code = ERR_X509_PROCESS;
        goto err;
    }
    if ((len = BIO_read(bio, reply, REPLY_MAX - 1)) > 0) {
      reply[len] = 0;
      save_in_store(req, pkey);
    } else {
      ret_code = ERR_BIO_READ;
    }

err:
    if (rsa) RSA_free(rsa);
    if (bn) BN_free(bn);
    if (req) X509_REQ_free(req);
    if (pkey) EVP_PKEY_free(pkey);
    if (bio) BIO_free(bio);
    if ((ret_code > 0) && (ret_code < ERR_MAXIMUM)) LOGE(emsg[ret_code]);
    return ret_code;
}

int is_pkcs12(const char *buf, int bufLen)
{
    int ret = 0;
    BIO *bp = NULL;
    PKCS12  *p12 = NULL;

    if (!buf || bufLen < 1) goto err;

    bp = BIO_new(BIO_s_mem());
    if (!bp) goto err;

    if (buf[0] != 48) goto err; // it is not DER.

    if (!BIO_write(bp, buf, bufLen)) goto err;

    if ((p12 = d2i_PKCS12_bio(bp, NULL)) != NULL) {
        PKCS12_free(p12);
        ret = 1;
    }
err:
    if (bp) BIO_free(bp);
    return ret;
}

X509* parse_cert(const char *buf, int bufLen)
{
    X509 *cert = NULL;
    BIO *bp = NULL;

    if(!buf || bufLen < 1)
        return NULL;

    bp = BIO_new(BIO_s_mem());
    if (!bp) goto err;

    if (!BIO_write(bp, buf, bufLen)) goto err;

    cert = PEM_read_bio_X509(bp, NULL, NULL, NULL);
    if (!cert) {
        BIO_free(bp);
        if((bp = BIO_new(BIO_s_mem())) == NULL) goto err;

        if(!BIO_write(bp, (char *) buf, bufLen)) goto err;
        cert = d2i_X509_bio(bp, NULL);
   }

err:
    if (bp) BIO_free(bp);
    return cert;
}

static int get_distinct_name(X509_NAME *dname, char *buf, int size)
{
   int i, len;
   char *p, *name;

   if (X509_NAME_oneline(dname, buf, size) == NULL) {
      return -1;
   }
   name = strstr(buf, "/CN=");
   p = name = name ? (name + 4) : buf;
   while (*p != 0) {
       if (*p == ' ') *p = '_';
       if (*p == '/') {
          *p = 0;
          break;
       }
       ++p;
   }
   return 0;
}

int get_cert_name(X509 *cert, char *buf, int size)
{
   if (!cert) return -1;
   return get_distinct_name(X509_get_subject_name(cert), buf, size);
}

int get_issuer_name(X509 *cert, char *buf, int size)
{
   if (!cert) return -1;
   return get_distinct_name(X509_get_issuer_name(cert), buf, size);
}

int is_ca_cert(X509 *cert)
{
    int ret = 0;
    BASIC_CONSTRAINTS *bs = (BASIC_CONSTRAINTS *)
            X509_get_ext_d2i(cert, NID_basic_constraints, NULL, NULL);
    if (bs != NULL) ret = bs->ca;
    if (bs) BASIC_CONSTRAINTS_free(bs);
    return ret;
}

int get_private_key_pem(X509 *cert, char *buf, int size)
{
    int len = 0;
    BIO *bio = NULL;
    EVP_PKEY *pkey = get_pkey_from_store(cert);

    if (pkey == NULL) return -1;

    bio = BIO_new(BIO_s_mem());
    if ((bio = BIO_new(BIO_s_mem())) == NULL) goto err;
    if (!PEM_write_bio_PrivateKey(bio, pkey, NULL,NULL,0,NULL, NULL)) {
        goto err;
    }
    if ((len = BIO_read(bio, buf, size - 1)) > 0) {
        buf[len] = 0;
    }
err:
    if (bio) BIO_free(bio);
    return (len == 0) ? -1 : 0;
}
