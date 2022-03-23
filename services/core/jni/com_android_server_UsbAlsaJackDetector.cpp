/*
 * Copyright (C) 2018 The Android Open Source Project
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

#define LOG_TAG "UsbAlsaJackDetectorJNI"
#include "utils/Log.h"

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "android-base/strings.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"

#include <stdio.h>
#include <string.h>
#include <asm/byteorder.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>

#include <tinyalsa/asoundlib.h>

#define DRIVER_NAME "/dev/usb_accessory"

#define USB_IN_JACK_SUFFIX "Input Jack"
#define USB_OUT_JACK_SUFFIX "Output Jack"

namespace android
{

static struct mixer_ctl* find_mixer_with_suffix(struct mixer* card_mixer, const char* suffix) {
    int id = 0;
    struct mixer_ctl* ctl;
    while ((ctl = mixer_get_ctl(card_mixer, id++)) != NULL) {
        const char* name = mixer_ctl_get_name(ctl);
        if (android::base::EndsWith(name, suffix)) {
          return ctl;
        }
    }
    return NULL;
}

static jboolean is_jack_connected(jint card, const char* suffix) {
  struct mixer* card_mixer = mixer_open(card);
  if (card_mixer == NULL) {
    return true;
  }
  struct mixer_ctl* ctl = find_mixer_with_suffix(card_mixer, suffix);
  if (!ctl) {
    return true;
  }
  mixer_ctl_update(ctl);
  int val = mixer_ctl_get_value(ctl, 0);
  ALOGI("%s - value %d\n", mixer_ctl_get_name(ctl), val);
  mixer_close(card_mixer);

  return val != 0;
}

static jboolean android_server_UsbAlsaJackDetector_hasJackDetect(JNIEnv* /* env */,
                                                                 jobject /* thiz */,
                                                                 jint card)
{
    struct mixer* card_mixer = mixer_open(card);
    if (card_mixer == NULL) {
        return false;
    }

    jboolean has_jack = false;
    if ((find_mixer_with_suffix(card_mixer, USB_IN_JACK_SUFFIX) != NULL) ||
            (find_mixer_with_suffix(card_mixer, USB_OUT_JACK_SUFFIX) != NULL)) {
        has_jack = true;
    }
    mixer_close(card_mixer);
    return has_jack;
}

static jboolean android_server_UsbAlsaJackDetector_inputJackConnected(JNIEnv* /* env */,
                                                                      jobject /* thiz */,
                                                                      jint card)
{
    return is_jack_connected(card, USB_IN_JACK_SUFFIX);
}

static jboolean android_server_UsbAlsaJackDetector_outputJackConnected(JNIEnv* /* env */,
                                                                       jobject /* thiz */,
                                                                       jint card)
{
    return is_jack_connected(card, USB_OUT_JACK_SUFFIX);
}

static void android_server_UsbAlsaJackDetector_jackDetect(JNIEnv* env,
                                                          jobject thiz,
                                                          jint card) {
    jclass jdclass = env->GetObjectClass(thiz);
    jmethodID method_jackDetectCallback = env->GetMethodID(jdclass, "jackDetectCallback", "()Z");
    if (method_jackDetectCallback == NULL) {
        ALOGE("Can't find jackDetectCallback");
        return;
    }

    struct mixer* m = mixer_open(card);
    if (!m) {
        ALOGE("Jack detect unable to open mixer\n");
        return;
    }
    mixer_subscribe_events(m, 1);
    do {

        // Wait for a mixer event.  Retry if interrupted, exit on error.
        int retval;
        do {
            retval = mixer_wait_event(m, -1);
        } while (retval == -EINTR);
        if (retval < 0) {
            break;
        }
        mixer_consume_event(m);
    } while (env->CallBooleanMethod(thiz, method_jackDetectCallback));

    mixer_close(m);
    return;
}

static const JNINativeMethod method_table[] = {
    { "nativeHasJackDetect", "(I)Z", (void*)android_server_UsbAlsaJackDetector_hasJackDetect },
    { "nativeInputJackConnected",     "(I)Z",
            (void*)android_server_UsbAlsaJackDetector_inputJackConnected },
    { "nativeOutputJackConnected",    "(I)Z",
            (void*)android_server_UsbAlsaJackDetector_outputJackConnected },
    { "nativeJackDetect", "(I)Z", (void*)android_server_UsbAlsaJackDetector_jackDetect },
};

int register_android_server_UsbAlsaJackDetector(JNIEnv *env)
{
    jclass clazz = env->FindClass("com/android/server/usb/UsbAlsaJackDetector");
    if (clazz == NULL) {
        ALOGE("Can't find com/android/server/usb/UsbAlsaJackDetector");
        return -1;
    }

    if (!jniRegisterNativeMethods(env, "com/android/server/usb/UsbAlsaJackDetector",
            method_table, NELEM(method_table))) {
      ALOGE("Can't register UsbAlsaJackDetector native methods");
      return -1;
    }

    return 0;
}

}
