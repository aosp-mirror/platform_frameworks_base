/*
 * Copyright (C) 2006 The Android Open Source Project
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

#include "jni.h"
#include <JNIHelp.h>
#include <stdlib.h>
#include <stdint.h>

#include <openssl/md5.h>

namespace android
{

struct fields_t {
    jfieldID    context;
};
static fields_t fields;

static void native_init(JNIEnv *env, jobject clazz)
{
    MD5_CTX* context = (MD5_CTX *)malloc(sizeof(MD5_CTX));
    MD5_Init(context);
    
    env->SetIntField(clazz, fields.context, (int)context);
}

static void native_reset(JNIEnv *env, jobject clazz)
{
    MD5_CTX *context = (MD5_CTX *)env->GetIntField(clazz, fields.context);
    if (context != NULL) {
        free(context);
        env->SetIntField(clazz, fields.context, 0 );
    }   
}

static void native_update(JNIEnv *env, jobject clazz, jbyteArray dataArray)
{
    jbyte * data;
    jsize dataSize;
    MD5_CTX *context = (MD5_CTX *)env->GetIntField(clazz, fields.context);
    
    if (context == NULL) {
        native_init(env, clazz);
        context = (MD5_CTX *)env->GetIntField(clazz, fields.context);
    }
    
    data = env->GetByteArrayElements(dataArray, NULL);
    if (data == NULL) {
        LOGE("Unable to get byte array elements");
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "Invalid data array when calling MessageDigest.update()");
        return;
    }
    dataSize = env->GetArrayLength(dataArray);   
    
    MD5_Update(context, data, dataSize);

    env->ReleaseByteArrayElements(dataArray, data, 0);
}
    
static jbyteArray native_digest(JNIEnv *env, jobject clazz)
{
    jbyteArray array;
    jbyte md[MD5_DIGEST_LENGTH];
    MD5_CTX *context = (MD5_CTX *)env->GetIntField(clazz, fields.context);
    
    MD5_Final((uint8_t*)md, context);  
    
    array = env->NewByteArray(MD5_DIGEST_LENGTH);
    LOG_ASSERT(array, "Native could not create new byte[]");
    
    env->SetByteArrayRegion(array, 0, MD5_DIGEST_LENGTH, md);
    
    native_reset(env, clazz);
        
    return array;
}


/*
 * JNI registration.
 */

static JNINativeMethod gMethods[] = 
{
     /* name, signature, funcPtr */
    {"init", "()V", (void *)native_init},
    {"update", "([B)V", (void *)native_update},
    {"digest", "()[B", (void *)native_digest},
    {"reset", "()V", (void *)native_reset},
};

int register_android_security_Md5MessageDigest(JNIEnv *env)
{
    jclass clazz;

    clazz = env->FindClass("android/security/Md5MessageDigest");
    if (clazz == NULL) {
        LOGE("Can't find android/security/Md5MessageDigest");
        return -1;
    }
    
    fields.context = env->GetFieldID(clazz, "mNativeMd5Context", "I");
    if (fields.context == NULL) {
        LOGE("Can't find Md5MessageDigest.mNativeMd5Context");
        return -1;
    }

    return jniRegisterNativeMethods(env, "android/security/Md5MessageDigest",
        gMethods, NELEM(gMethods));
}

};
