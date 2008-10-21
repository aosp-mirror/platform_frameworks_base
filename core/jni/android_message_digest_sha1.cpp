/* //device/libs/android_runtime/android_message_digest_sha1.cpp
**
** Copyright 2006, The Android Open Source Project
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

#include "jni.h"
#include <JNIHelp.h>
#include "android_runtime/AndroidRuntime.h"

#include <openssl/sha.h>

//#define _DEBUG 1

// ----------------------------------------------------------------------------

using namespace android;

// ----------------------------------------------------------------------------

struct fields_t {
    jfieldID	context;
};
static fields_t fields;

static void native_init(JNIEnv *env, jobject clazz)
{
	SHA_CTX* context;
	
#ifdef _DEBUG
	printf("sha1.native_init\n");
#endif
	
	context = (SHA_CTX *)malloc(sizeof(SHA_CTX));
	SHA1_Init(context);
	
	env->SetIntField(clazz, fields.context, (int)context);
}

static void native_reset(JNIEnv *env, jobject clazz)
{
    SHA_CTX *context = (SHA_CTX *)env->GetIntField(clazz, fields.context);
	if (context != NULL) {
#ifdef _DEBUG
		printf("sha1.native_reset: free context\n");
#endif
		free(context);
  		env->SetIntField(clazz, fields.context, 0 );
	}	
}


static void native_update(JNIEnv *env, jobject clazz, jbyteArray dataArray)
{
#ifdef _DEBUG
	printf("sha1.native_update\n");
#endif
	jbyte * data;
    jsize dataSize;
    SHA_CTX *context = (SHA_CTX *)env->GetIntField(clazz, fields.context);
    
    if (context == NULL) {
#ifdef _DEBUG
		printf("sha1.native_update: context is NULL, call init...\n");
#endif
    	native_init(env, clazz);
    	context = (SHA_CTX *)env->GetIntField(clazz, fields.context);
    }
    
    data = env->GetByteArrayElements(dataArray, NULL);
    if (data == NULL) {
        LOGE("Unable to get byte array elements");
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "Invalid data array when calling MessageDigest.update()");
        return;
    }
    dataSize = env->GetArrayLength(dataArray);   
    
    SHA1_Update(context, data, dataSize);

    env->ReleaseByteArrayElements(dataArray, data, 0);
}
	
static jbyteArray native_digest(JNIEnv *env, jobject clazz)
{
#ifdef _DEBUG
	printf("sha1.native_digest\n");
#endif
	jbyteArray array;
	jbyte md[SHA_DIGEST_LENGTH];
	SHA_CTX *context = (SHA_CTX *)env->GetIntField(clazz, fields.context);
  	
  	SHA1_Final((uint8_t*)md, context);	
  	
  	array = env->NewByteArray(SHA_DIGEST_LENGTH);
    LOG_ASSERT(array, "Native could not create new byte[]");
  	
  	env->SetByteArrayRegion(array, 0, SHA_DIGEST_LENGTH, md);
  	
  	native_reset(env, clazz);
  	  	
  	return array;
}


static JNINativeMethod method_table[] = 
{
     /* name, signature, funcPtr */
	{"init", "()V", (void *)native_init},
    {"update", "([B)V", (void *)native_update},
    {"digest", "()[B", (void *)native_digest},
	{"reset", "()V", (void *)native_reset},
};

int register_android_message_digest_sha1(JNIEnv *env)
{
    jclass clazz;

    clazz = env->FindClass("android/security/Sha1MessageDigest");
    if (clazz == NULL) {
        LOGE("Can't find android/security/Sha1MessageDigest");
        return -1;
    }
    
	fields.context = env->GetFieldID(clazz, "mNativeSha1Context", "I");
	if (fields.context == NULL) {
		LOGE("Can't find Sha1MessageDigest.mNativeSha1Context");
		return -1;
	}

    return AndroidRuntime::registerNativeMethods(
    					env, "android/security/Sha1MessageDigest",
    					method_table, NELEM(method_table));
}

