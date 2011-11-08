/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <media/MediaPlayerInterface.h>

#include <media/stagefright/foundation/ABase.h>

namespace android {

struct ALooper;
struct NuPlayer;

struct NuPlayerDriver : public MediaPlayerInterface {
    NuPlayerDriver();

    virtual status_t initCheck();

    virtual status_t setUID(uid_t uid);

    virtual status_t setDataSource(
            const char *url, const KeyedVector<String8, String8> *headers);

    virtual status_t setDataSource(int fd, int64_t offset, int64_t length);

    virtual status_t setDataSource(const sp<IStreamSource> &source);

    virtual status_t setVideoSurfaceTexture(
            const sp<ISurfaceTexture> &surfaceTexture);
    virtual status_t prepare();
    virtual status_t prepareAsync();
    virtual status_t start();
    virtual status_t stop();
    virtual status_t pause();
    virtual bool isPlaying();
    virtual status_t seekTo(int msec);
    virtual status_t getCurrentPosition(int *msec);
    virtual status_t getDuration(int *msec);
    virtual status_t reset();
    virtual status_t setLooping(int loop);
    virtual player_type playerType();
    virtual status_t invoke(const Parcel &request, Parcel *reply);
    virtual void setAudioSink(const sp<AudioSink> &audioSink);
    virtual status_t setParameter(int key, const Parcel &request);
    virtual status_t getParameter(int key, Parcel *reply);

    virtual status_t getMetadata(
            const media::Metadata::Filter& ids, Parcel *records);

    virtual status_t dump(int fd, const Vector<String16> &args) const;

    void notifyResetComplete();
    void notifyDuration(int64_t durationUs);
    void notifyPosition(int64_t positionUs);
    void notifySeekComplete();
    void notifyFrameStats(int64_t numFramesTotal, int64_t numFramesDropped);
    void notifyListener(int msg, int ext1 = 0, int ext2 = 0);

protected:
    virtual ~NuPlayerDriver();

private:
    mutable Mutex mLock;
    Condition mCondition;

    // The following are protected through "mLock"
    // >>>
    bool mResetInProgress;
    int64_t mDurationUs;
    int64_t mPositionUs;
    int64_t mNumFramesTotal;
    int64_t mNumFramesDropped;
    // <<<

    sp<ALooper> mLooper;
    sp<NuPlayer> mPlayer;

    enum State {
        UNINITIALIZED,
        STOPPED,
        PLAYING,
        PAUSED
    };

    State mState;
    bool mAtEOS;

    int64_t mStartupSeekTimeUs;

    DISALLOW_EVIL_CONSTRUCTORS(NuPlayerDriver);
};

}  // namespace android


