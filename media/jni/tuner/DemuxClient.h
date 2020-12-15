/*
 * Copyright 2020 The Android Open Source Project
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

#ifndef _ANDROID_MEDIA_TV_DEMUX_CLIENT_H_
#define _ANDROID_MEDIA_TV_DEMUX_CLIENT_H_

//#include <aidl/android/media/tv/tuner/ITunerDemux.h>
#include <android/hardware/tv/tuner/1.0/IDemux.h>
#include <android/hardware/tv/tuner/1.1/types.h>

#include "FilterClient.h"
#include "FrontendClient.h"

//using ::aidl::android::media::tv::tuner::ITunerDemux;

using ::android::hardware::tv::tuner::V1_0::IDemux;

using namespace std;

namespace android {

struct DemuxClient : public RefBase {

public:
    DemuxClient();
    ~DemuxClient();

    // TODO: remove after migration to Tuner Service is done.
    void setHidlDemux(sp<IDemux> demux);

    /**
     * Set a frontend resource as data input of the demux.
     */
    Result setFrontendDataSource(sp<FrontendClient> tunerFrontend);

    /**
     * Open a new filter client.
     */
    //FilterClient openFilter(int mainType, int subType, int bufferSize, FilterClientCallback cb);

    // TODO: handle TimeFilterClient

    /**
     * Get hardware sync ID for audio and video.
     */
    int getAvSyncHwId(FilterClient tunerFilter);

    /**
     * Get current time stamp to use for A/V sync.
     */
    long getAvSyncTime(int avSyncHwId);

    /**
     * Open a DVR (Digital Video Record) client.
     */
    // TODO: handle DvrClient and callback
    //DvrClient openDvr(int dvbType, int bufferSize, DvrClientCallback cb);  

    /**
     * Connect Conditional Access Modules (CAM) through Common Interface (CI).
     */
    Result connectCiCam(int ciCamId);

    /**
     * Disconnect Conditional Access Modules (CAM).
     */
    Result disconnectCiCam();

    /**
     * Release the Demux Client.
     */
    Result close();

private:
    /**
     * An AIDL Tuner Demux Singleton assigned at the first time the Tuner Client
     * opens a demux. Default null when demux is not opened.
     */
    // TODO: pending on aidl interface
    //shared_ptr<ITunerDemux> mTunerDemux;

    /**
     * A Demux HAL interface that is ready before migrating to the TunerDemux.
     * This is a temprary interface before Tuner Framework migrates to use TunerService.
     * Default null when the HAL service does not exist.
     */
    sp<IDemux> mDemux;
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_DEMUX_CLIENT_H_
