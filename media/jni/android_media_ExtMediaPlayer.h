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

#ifndef _ANDROID_MEDIA_EXTMEDIAPLAYER_H_
#define _ANDROID_MEDIA_EXTMEDIAPLAYER_H_

//#define LOG_NDEBUG 0


#include <media/mediaplayer.h>
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"
#include "android_os_Parcel.h"
#include <binder/Parcel.h>

using namespace android;

struct extfields_t
{
    jmethodID   ext_post_event;
};
 static extfields_t extfields;

#define MAX_NUM_PARCELS 5

class JNIExtMediaPlayerListener: public MediaPlayerListener
{
  public:
    JNIExtMediaPlayerListener(JNIEnv* env, jobject thiz, jobject weak_thiz, const sp<MediaPlayerListener>& listener);
    ~JNIExtMediaPlayerListener();
    virtual void notify(int msg, int ext1, int ext2, const Parcel *obj = NULL);
    static bool checkExtMedia(JNIEnv *env, jobject thiz);
  private:
    JNIExtMediaPlayerListener();
    jclass     mClass;	 // Reference to MediaPlayer class
    jobject    mObject;	 // Weak ref to MediaPlayer Java object to call on
    jobject    mParcel;
    jobject    mParcelArray[MAX_NUM_PARCELS];
    int        mParcelIndex;
    jobject    mParcelCodecConf;
    sp<MediaPlayerListener> mplistener;
};




#endif //_ANDROID_MEDIA_EXTMEDIAPLAYER_H_
