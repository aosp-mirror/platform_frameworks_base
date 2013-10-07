/*Copyright (c) 2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *      with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *      contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#include "android_media_ExtMediaPlayer.h"

#define LOG_TAG "ExtMediaPlayer-JNI"

using namespace android;

bool JNIExtMediaPlayerListener::checkExtMedia(JNIEnv *env, jobject thiz)
{
  jclass clazz = NULL;
  bool nRet = false;
  clazz = env->FindClass("com/qualcomm/qcmedia/QCMediaPlayer");
  if (clazz != NULL)
  {
    if (env->IsInstanceOf(thiz,clazz))
    {
      nRet = true;
      ALOGD("QCMediaPlayer mediaplayer present");
    }
  }
  else
  {
    //Clear the exception as QCMediaPlayer is optional
    env->ExceptionClear();
  }
  return nRet;
}

JNIExtMediaPlayerListener::JNIExtMediaPlayerListener(JNIEnv* env, jobject thiz, jobject weak_thiz,const sp<MediaPlayerListener>& listener)
{
  jclass clazz = env->GetObjectClass(thiz);
  if (clazz == NULL) {
      ALOGE("Can't find android/media/MediaPlayer");
      jniThrowException(env, "java/lang/Exception", NULL);
      return;
  }
  mplistener = listener;
  extfields.ext_post_event = env->GetStaticMethodID(clazz, "QCMediaPlayerNativeEventHandler",
                             "(Ljava/lang/Object;IIILjava/lang/Object;)V");
  mClass = (jclass)env->NewGlobalRef(clazz);

  // We use a weak reference so the MediaPlayer object can be garbage collected.
  // The reference is only used as a proxy for callbacks.
  mObject  = env->NewGlobalRef(weak_thiz);
  //Set up the event handler as we were able to detect QCMediaPlayer.

  mParcel = env->NewGlobalRef(createJavaParcelObject(env));
  mParcelCodecConf = env->NewGlobalRef(createJavaParcelObject(env));

  mParcelIndex = 0;
  for (int i = 0; i < MAX_NUM_PARCELS; i++)
  {
    mParcelArray[i] = env->NewGlobalRef(createJavaParcelObject(env));
  }
}

JNIExtMediaPlayerListener::~JNIExtMediaPlayerListener()
{
  // remove global references
  JNIEnv *env = AndroidRuntime::getJNIEnv();
  env->DeleteGlobalRef(mObject);
  env->DeleteGlobalRef(mClass);
  recycleJavaParcelObject(env, mParcel);
  env->DeleteGlobalRef(mParcel);

  recycleJavaParcelObject(env, mParcelCodecConf);
  env->DeleteGlobalRef(mParcelCodecConf);

  for (int i = 0; i < MAX_NUM_PARCELS; i++)
  {
    recycleJavaParcelObject(env, mParcelArray[i]);
    env->DeleteGlobalRef(mParcelArray[i]);
  }
}


void JNIExtMediaPlayerListener::notify(int msg, int ext1, int ext2, const Parcel *obj)
{
  JNIEnv *env = AndroidRuntime::getJNIEnv();
  if (env && obj && obj->dataSize() > 0)
  {
    if (mParcel != NULL)
    {
      if((extfields.ext_post_event != NULL) &&
         ((msg == MEDIA_PREPARED) || (msg == MEDIA_TIMED_TEXT) || (msg == MEDIA_QOE)))
      {
        ALOGE("JNIExtMediaPlayerListener::notify calling ext_post_event");
        if (ext2 == 1 && (msg == MEDIA_TIMED_TEXT))
        { // only in case of codec config frame
          if (mParcelCodecConf != NULL)
          {
            Parcel* nativeParcelLocal = parcelForJavaObject(env, mParcelCodecConf);
            nativeParcelLocal->setData(obj->data(), obj->dataSize());
            env->CallStaticVoidMethod(mClass, extfields.ext_post_event, mObject,
                            msg, ext1, ext2, mParcelCodecConf);
            ALOGD("JNIExtMediaPlayerListener::notify ext_post_event done (Codec Conf)");
          }
        }
        else
        {
          jobject mTempJParcel = mParcelArray[mParcelIndex];
          mParcelIndex = (mParcelIndex + 1) % MAX_NUM_PARCELS;
          Parcel* javaparcel = parcelForJavaObject(env, mTempJParcel);
          javaparcel->setData(obj->data(), obj->dataSize());
          env->CallStaticVoidMethod(mClass, extfields.ext_post_event, mObject,
                    msg, ext1, ext2, mTempJParcel);
          ALOGD("JNIExtMediaPlayerListener::notify ext_post_event done");
        }
      }
      else
      {
        ALOGD("JNIExtMediaPlayerListener::notify calling for generic event");
        mplistener->notify(msg, ext1, ext2, obj);
      }
    }
  }
  else
  {
    if((extfields.ext_post_event != NULL) &&
       ((msg == MEDIA_PREPARED) || (msg == MEDIA_TIMED_TEXT) ||(msg == MEDIA_QOE)))
    {
      ALOGD("JNIExtMediaPlayerListener::notify calling ext_post_events");
      env->CallStaticVoidMethod(mClass, extfields.ext_post_event, mObject, msg, ext1, ext2, NULL);
    }
    else
    {
      ALOGD("JNIExtMediaPlayerListener::notify for generic events");
      mplistener->notify(msg, ext1, ext2, obj);
    }
  }
  return;
}

