/* //device/libs/android_runtime/android_util_Base64.cpp
**
** Copyright 2008, The Android Open Source Project
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

/*********************************************************
*
* This code was copied from
* system/extra/ssh/dropbear-0.49/libtomcrypt/src/misc/base64/base64_decode.c
*
*********************************************************/

#define LOG_TAG "Base64"

#include <utils/Log.h>

#include <android_runtime/AndroidRuntime.h>

#include "JNIHelp.h"

#include <sys/errno.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <fcntl.h>
#include <signal.h>

namespace android {

static const unsigned char map[256] = {
255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
255, 255, 255, 255, 255, 255, 255,  62, 255, 255, 255,  63,
 52,  53,  54,  55,  56,  57,  58,  59,  60,  61, 255, 255,
255, 254, 255, 255, 255,   0,   1,   2,   3,   4,   5,   6,
  7,   8,   9,  10,  11,  12,  13,  14,  15,  16,  17,  18,
 19,  20,  21,  22,  23,  24,  25, 255, 255, 255, 255, 255,
255,  26,  27,  28,  29,  30,  31,  32,  33,  34,  35,  36,
 37,  38,  39,  40,  41,  42,  43,  44,  45,  46,  47,  48,
 49,  50,  51, 255, 255, 255, 255, 255, 255, 255, 255, 255,
255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
255, 255, 255, 255 };

/**
   base64 decode a block of memory
   @param in       The base64 data to decode
   @param inlen    The length of the base64 data
   @param out      [out] The destination of the binary decoded data
   @param outlen   [in/out] The max size and resulting size of the decoded data
   @return 0 if successful
*/
int base64_decode(const unsigned char *in,  unsigned long inlen, 
                        unsigned char *out, unsigned long *outlen)
{
   unsigned long t, x, y, z;
   unsigned char c;
   int           g;

   g = 3;
   for (x = y = z = t = 0; x < inlen; x++) {
       c = map[in[x]&0xFF];
       if (c == 255) continue;
       /* the final = symbols are read and used to trim the remaining bytes */
       if (c == 254) { 
          c = 0; 
          /* prevent g < 0 which would potentially allow an overflow later */
          if (--g < 0) {
             return -3;
          }
       } else if (g != 3) {
          /* we only allow = to be at the end */
          return -4;
       }

       t = (t<<6)|c;

       if (++y == 4) {
          if (z + g > *outlen) { 
             return -2; 
          }
          out[z++] = (unsigned char)((t>>16)&255);
          if (g > 1) out[z++] = (unsigned char)((t>>8)&255);
          if (g > 2) out[z++] = (unsigned char)(t&255);
          y = t = 0;
       }
   }
   if (y != 0) {
       return -5;
   }
   *outlen = z;
   return 0;
}

static jbyteArray decodeBase64(JNIEnv *env, jobject jobj, jstring jdata)
{
    const char * rawData = env->GetStringUTFChars(jdata, NULL);
    int stringLength = env->GetStringUTFLength(jdata);

    int resultLength = stringLength / 4 * 3;
    if (rawData[stringLength-1] == '=') {
        resultLength -= 1;
        if (rawData[stringLength-2] == '=') {
            resultLength -= 1;
        }
    }

    jbyteArray byteArray = env->NewByteArray(resultLength);
    jbyte* byteArrayData = env->GetByteArrayElements(byteArray, NULL);

    unsigned long outlen = resultLength;
    int result = base64_decode((const unsigned char*)rawData, stringLength, (unsigned char *)byteArrayData, &outlen);
    if (result != 0)
        memset((unsigned char *)byteArrayData, -result, resultLength);
    
    env->ReleaseStringUTFChars(jdata, rawData);
    env->ReleaseByteArrayElements(byteArray, byteArrayData, 0);

    return byteArray;
}

static const JNINativeMethod methods[] = {
  {"decodeBase64Native", "(Ljava/lang/String;)[B", (void*)decodeBase64 }
};

static const char* const kBase64PathName = "android/os/Base64Utils";

int register_android_util_Base64(JNIEnv* env)
{
    jclass clazz;

    clazz = env->FindClass(kBase64PathName);
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.Base64Utils");

    return AndroidRuntime::registerNativeMethods(
        env, kBase64PathName,
        methods, NELEM(methods));
}

}
