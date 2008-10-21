/* //device/libs/android_runtime/android_os_Wifi.cpp
**
** Copyright 2007, The Android Open Source Project
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

#define LOG_TAG "NetStat"

#include "jni.h"
#include <utils/misc.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>

#include <stdio.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#if HAVE_ANDROID_OS
#include <utils/Atomic.h>
#endif

namespace android {

static jint android_os_netStatGetTxPkts(JNIEnv* env, jobject clazz)
{
  int ret = 0;
  int fd = -1;
  char input[50];
  
  fd = open("/sys/class/net/rmnet0/statistics/tx_packets", O_RDONLY);
  if (fd <= 0) {
    fd = open("/sys/class/net/ppp0/statistics/tx_packets", O_RDONLY);
  }
  
  if (fd > 0) {
    int size = read(fd, input, 50);
    if (size > 0) {
      ret = atoi(input);
    }
    close(fd);
  }
  
  return (jint)ret;
}

static jint android_os_netStatGetRxPkts(JNIEnv* env, jobject clazz)
{
  int ret = 0;
  int fd = -1;
  char input[50];
  
  fd = open("/sys/class/net/rmnet0/statistics/rx_packets", O_RDONLY);
  if (fd <= 0) {
    fd = open("/sys/class/net/ppp0/statistics/rx_packets", O_RDONLY);
  }
  
  if (fd > 0) {
    int size = read(fd, input, 50);
    if (size > 0) {
      ret = atoi(input);
    }
    close(fd);
  }
  
  return (jint)ret;
}

static jint android_os_netStatGetRxBytes(JNIEnv* env, jobject clazz)
{
  int ret = 0;
  int fd = -1;
  char input[50];
  
  fd = open("/sys/class/net/rmnet0/statistics/rx_bytes", O_RDONLY);
  if (fd <= 0) {
    fd = open("/sys/class/net/ppp0/statistics/rx_bytes", O_RDONLY);
  }
  
  if (fd > 0) {
    int size = read(fd, input, 50);
    if (size > 0) {
      ret = atoi(input);
    }
    close(fd);
  }
  
  return (jint)ret;
}


static jint android_os_netStatGetTxBytes(JNIEnv* env, jobject clazz)
{
  int ret = 0;
  int fd = -1;
  char input[50];
  
  fd = open("/sys/class/net/rmnet0/statistics/tx_bytes", O_RDONLY);
  if (fd <= 0) {
    fd = open("/sys/class/net/ppp0/statistics/tx_bytes", O_RDONLY);
  }
  
  if (fd > 0) {
    int size = read(fd, input, 50);
    if (size > 0) {
      ret = atoi(input);
    }
    close(fd);
  }
  
  return (jint)ret;
}

// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */
static JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */

    { "netStatGetTxPkts", "()I",
      (void*) android_os_netStatGetTxPkts },

    { "netStatGetRxPkts", "()I",
      (void*) android_os_netStatGetRxPkts },

    { "netStatGetTxBytes", "()I",
      (void*) android_os_netStatGetTxBytes },

    { "netStatGetRxBytes", "()I",
      (void*) android_os_netStatGetRxBytes },

};

int register_android_os_NetStat(JNIEnv* env)
{
    jclass netStat = env->FindClass("android/os/NetStat");
    LOG_FATAL_IF(netStat == NULL, "Unable to find class android/os/NetStat");

    return AndroidRuntime::registerNativeMethods(env,
            "android/os/NetStat", gMethods, NELEM(gMethods));
}

}; // namespace android

