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
    STR(ERR_SPKAC_TOO_LONG),
    STR(ERR_INVALID_ARGS),
};

static void save_in_store(EVP_PKEY *pkey)
{
    EVP_PKEY *newpkey = EVP_PKEY_new();
    RSA *rsa = EVP_PKEY_get1_RSA(pkey);
    EVP_PKEY_set1_RSA(newpkey, rsa);
    PKEY_STORE_free(pkey_store[store_index]);
    pkey_store[store_index].key_len = i2d_RSA_PUBKEY(rsa, &pkey_store[store_index].public_key);
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

int gen_csr(int bits, const char *challenge, char reply[REPLY_MAX])
{
    int len, ret_code = 0;
    BIGNUM *bn = NULL;
    char *spkstr = NULL;
    EVP_PKEY *pkey = NULL;
    RSA *rsa = NULL;
    NETSCAPE_SPKI *req = NULL;

    if (challenge == NULL) {
        ret_code = ERR_INVALID_ARGS;
        goto err;
    }

    if ((bits != KEYLENGTH_MEDIUM) && (bits != KEYLENGTH_MAXIMUM)) {
        ret_code = ERR_INVALID_KEY_LENGTH;
        goto err;
    }

    if (((pkey = EVP_PKEY_new()) == NULL) ||
        ((req = NETSCAPE_SPKI_new()) == NULL) ||
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

    rsa = NULL;
    ASN1_STRING_set(req->spkac->challenge, challenge, (int)strlen(challenge));
    NETSCAPE_SPKI_set_pubkey(req, pkey);
    NETSCAPE_SPKI_sign(req, pkey, EVP_md5());
    spkstr = NETSCAPE_SPKI_b64_encode(req);

    if ((strlcpy(reply, spkstr, REPLY_MAX)) < REPLY_MAX) {
        save_in_store(pkey);
    } else {
        ret_code = ERR_SPKAC_TOO_LONG;
    }

err:
    if (rsa) RSA_free(rsa);
    if (bn) BN_free(bn);
    if (req) NETSCAPE_SPKI_free(req);
    if (pkey) EVP_PKEY_free(pkey);
    if (spkstr) OPENSSL_free(spkstr);
    if ((ret_code > 0) && (ret_code < ERR_MAXIMUM)) LOGE(emsg[ret_code]);
    return -ret_code;
}

PKCS12 *get_p12_handle(const char *buf, int bufLen)
{
    BIO *bp = NULL;
    PKCS12  *p12 = NULL;

    if (!buf || (bufLen < 1) || (buf[0] != 48)) goto err;

    bp = BIO_new(BIO_s_mem());
    if (!bp) goto err;

    if (!BIO_write(bp, buf, bufLen)) goto err;

    p12 = d2i_PKCS12_bio(bp, NULL);

err:
    if (bp) BIO_free(bp);
    return p12;
}

PKCS12_KEYSTORE *get_pkcs12_keystore_handle(const char *buf, int bufLen,
                                            const char *passwd)
{
    PKCS12_KEYSTORE *p12store = NULL;
    EVP_PKEY *pkey = NULL;
    X509 *cert = NULL;
    STACK_OF(X509) *certs = NULL;
    PKCS12  *p12 = get_p12_handle(buf, bufLen);

    if (p12 == NULL) return NULL;
    if (!PKCS12_parse(p12, passwd, &pkey, &cert, &certs)) {
        LOGE("Can not parse PKCS12 content");
        PKCS12_free(p12);
        return NULL;
    }
    if ((p12store = malloc(sizeof(PKCS12_KEYSTORE))) == NULL) {
        if (cert) X509_free(cert);
        if (pkey) EVP_PKEY_free(pkey);
        if (certs) sk_X509_free(certs);
    }
    p12store->p12 = p12;
    p12store->pkey = pkey;
    p12store->cert = cert;
    p12store->certs = certs;
    return p12store;
}

void free_pkcs12_keystore(PKCS12_KEYSTORE *p12store)
{
    if (p12store != NULL) {
        if (p12store->cert) X509_free(p12store->cert);
        if (p12store->pkey) EVP_PKEY_free(p12store->pkey);
        if (p12store->certs) sk_X509_free(p12store->certs);
        free(p12store);
    }
}

int is_pkcs12(const char *buf, int bufLen)
{
    int ret = 0;
    PKCS12  *p12 = get_p12_handle(buf, bufLen);
    if (p12 != NULL) ret = 1;
    PKCS12_free(p12);
    return ret;
}

static int convert_to_pem(void *data, int is_cert, char *buf, int size)
{
    int len = 0;
    BIO *bio = NULL;

    if (data == NULL) return -1;

    if ((bio = BIO_new(BIO_s_mem())) == NULL) goto err;
    if (is_cert) {
        if ((len = PEM_write_bio_X509(bio, (X509*)data)) == 0) {
            goto err;
        }
    } else {
        if ((len = PEM_write_bio_PrivateKey(bio, (EVP_PKEY *)data, NULL,
                                            NULL, 0, NULL, NULL)) == 0) {
            goto err;
        }
    }
    if (len < size && (len = BIO_read(bio, buf, size - 1)) > 0) {
        buf[len] = 0;
    }
err:
    if (bio) BIO_free(bio);
    return len;
}

int get_pkcs12_certificate(PKCS12_KEYSTORE *p12store, char *buf, int size)
{
    if ((p12store != NULL) && (p12store->cert != NULL)) {
        int len = convert_to_pem((void*)p12store->cert, 1, buf, size);
        return (len == 0) ? -1 : 0;
    }
    return -1;
}

int get_pkcs12_private_key(PKCS12_KEYSTORE *p12store, char *buf, int size)
{
    if ((p12store != NULL) && (p12store->pkey != NULL)) {
        int len = convert_to_pem((void*)p12store->pkey, 0, buf, size);
        return (len == 0) ? -1 : 0;
    }
    return -1;
}

int pop_pkcs12_certs_stack(PKCS12_KEYSTORE *p12store, char *buf, int size)
{
    X509 *cert = NULL;
    int len = 0;

    if ((p12store != NULL) && (p12store->certs != NULL)) {
        while (((cert = sk_X509_pop(p12store->certs)) != NULL) && (len < size)) {
            int s = convert_to_pem((void*)cert, 1, buf + len, size - len);
            if (s == 0) {
                LOGE("buffer size is too small. len=%d size=%d\n", len, size);
                return -1;
            }
            len += s;
            X509_free(cert);
        }
        return (len == 0) ? -1 : 0;
    }
    return -1;
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
