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

#include <string.h>
#include <jni.h>
#include <cutils/log.h>
#include <openssl/x509v3.h>

#include "cert.h"

jstring
android_security_CertTool_generateCertificateRequest(JNIEnv* env,
                                                     jobject thiz,
                                                     jint bits,
                                                     jstring subject)

{
    char csr[REPLY_MAX];
    if (gen_csr(bits, subject, csr) == 0) {
        return (*env)->NewStringUTF(env, csr);
    }
    return NULL;
}

jboolean
android_security_CertTool_isPkcs12Keystore(JNIEnv* env,
                                           jobject thiz,
                                           jbyteArray data)
{
    char buf[REPLY_MAX];
    int len = (*env)->GetArrayLength(env, data);

    if (len > REPLY_MAX) return 0;
    (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte*)buf);
    return (jboolean) is_pkcs12(buf, len);
}

jint
android_security_CertTool_generateX509Certificate(JNIEnv* env,
                                                  jobject thiz,
                                                  jbyteArray data)
{
    char buf[REPLY_MAX];
    int len = (*env)->GetArrayLength(env, data);

    if (len > REPLY_MAX) return 0;
    (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte*)buf);
    return (jint) parse_cert(buf, len);
}

jboolean android_security_CertTool_isCaCertificate(JNIEnv* env,
                                                   jobject thiz,
                                                   jint handle)
{
    return (handle == 0) ? (jboolean)0 : (jboolean) is_ca_cert((X509*)handle);
}

jstring android_security_CertTool_getIssuerDN(JNIEnv* env,
                                              jobject thiz,
                                              jint handle)
{
    char issuer[MAX_CERT_NAME_LEN];

    if (handle == 0) return NULL;
    if (get_issuer_name((X509*)handle, issuer, MAX_CERT_NAME_LEN)) return NULL;
    return (*env)->NewStringUTF(env, issuer);
}

jstring android_security_CertTool_getCertificateDN(JNIEnv* env,
                                                   jobject thiz,
                                                   jint handle)
{
    char name[MAX_CERT_NAME_LEN];
    if (handle == 0) return NULL;
    if (get_cert_name((X509*)handle, name, MAX_CERT_NAME_LEN)) return NULL;
    return (*env)->NewStringUTF(env, name);
}

jstring android_security_CertTool_getPrivateKeyPEM(JNIEnv* env,
                                                   jobject thiz,
                                                   jint handle)
{
    char pem[MAX_PEM_LENGTH];
    if (handle == 0) return NULL;
    if (get_private_key_pem((X509*)handle, pem, MAX_PEM_LENGTH)) return NULL;
    return (*env)->NewStringUTF(env, pem);
}

void android_security_CertTool_freeX509Certificate(JNIEnv* env,
                                                   jobject thiz,
                                                   jint handle)
{
    if (handle != 0) X509_free((X509*)handle);
}

/*
 * Table of methods associated with the CertTool class.
 */
static JNINativeMethod gCertToolMethods[] = {
    /* name, signature, funcPtr */
    {"generateCertificateRequest", "(ILjava/lang/String;)Ljava/lang/String;",
        (void*)android_security_CertTool_generateCertificateRequest},
    {"isPkcs12Keystore", "([B)Z",
        (void*)android_security_CertTool_isPkcs12Keystore},
    {"generateX509Certificate", "([B)I",
        (void*)android_security_CertTool_generateX509Certificate},
    {"isCaCertificate", "(I)Z",
        (void*)android_security_CertTool_isCaCertificate},
    {"getIssuerDN", "(I)Ljava/lang/String;",
        (void*)android_security_CertTool_getIssuerDN},
    {"getCertificateDN", "(I)Ljava/lang/String;",
        (void*)android_security_CertTool_getCertificateDN},
    {"getPrivateKeyPEM", "(I)Ljava/lang/String;",
        (void*)android_security_CertTool_getPrivateKeyPEM},
    {"freeX509Certificate", "(I)V",
        (void*)android_security_CertTool_freeX509Certificate},
};

/*
 * Register several native methods for one class.
 */
static int registerNatives(JNIEnv* env, const char* className,
                           JNINativeMethod* gMethods, int numMethods)
{
    jclass clazz;

    clazz = (*env)->FindClass(env, className);
    if (clazz == NULL) {
        LOGE("Can not find class %s\n", className);
        return JNI_FALSE;
    }

    if ((*env)->RegisterNatives(env, clazz, gMethods, numMethods) < 0) {
        LOGE("Can not RegisterNatives\n");
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env = NULL;
    jint result = -1;


    if ((*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        goto bail;
    }

    if (!registerNatives(env, "android/security/CertTool",
                         gCertToolMethods, nelem(gCertToolMethods))) {
        goto bail;
    }

    /* success -- return valid version number */
    result = JNI_VERSION_1_4;

bail:
    return result;
}
