/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef ANDROID_AUDIO_TRACK_SHARED_H
#define ANDROID_AUDIO_TRACK_SHARED_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/threads.h>

namespace android {

// ----------------------------------------------------------------------------

// Maximum cumulated timeout milliseconds before restarting audioflinger thread
#define MAX_STARTUP_TIMEOUT_MS  3000    // Longer timeout period at startup to cope with A2DP init time
#define MAX_RUN_TIMEOUT_MS      1000
#define WAIT_PERIOD_MS          10
#define RESTORE_TIMEOUT_MS      5000    // Maximum waiting time for a track to be restored

#define CBLK_UNDERRUN_MSK       0x0001
#define CBLK_UNDERRUN_ON        0x0001  // underrun (out) or overrrun (in) indication
#define CBLK_UNDERRUN_OFF       0x0000  // no underrun
#define CBLK_DIRECTION_MSK      0x0002
#define CBLK_DIRECTION_OUT      0x0002  // this cblk is for an AudioTrack
#define CBLK_DIRECTION_IN       0x0000  // this cblk is for an AudioRecord
#define CBLK_FORCEREADY_MSK     0x0004
#define CBLK_FORCEREADY_ON      0x0004  // track is considered ready immediately by AudioFlinger
#define CBLK_FORCEREADY_OFF     0x0000  // track is ready when buffer full
#define CBLK_INVALID_MSK        0x0008
#define CBLK_INVALID_ON         0x0008  // track buffer is invalidated by AudioFlinger:
#define CBLK_INVALID_OFF        0x0000  // must be re-created
#define CBLK_DISABLED_MSK       0x0010
#define CBLK_DISABLED_ON        0x0010  // track disabled by AudioFlinger due to underrun:
#define CBLK_DISABLED_OFF       0x0000  // must be re-started
#define CBLK_RESTORING_MSK      0x0020
#define CBLK_RESTORING_ON       0x0020  // track is being restored after invalidation
#define CBLK_RESTORING_OFF      0x0000  // by AudioFlinger
#define CBLK_RESTORED_MSK       0x0040
#define CBLK_RESTORED_ON        0x0040  // track has been restored after invalidation
#define CBLK_RESTORED_OFF       0x0040  // by AudioFlinger

struct audio_track_cblk_t
{

    // The data members are grouped so that members accessed frequently and in the same context
    // are in the same line of data cache.
                Mutex       lock;           // sizeof(int)
                Condition   cv;             // sizeof(int)
    volatile    uint32_t    user;
    volatile    uint32_t    server;
                uint32_t    userBase;
                uint32_t    serverBase;
                void*       buffers;
                uint32_t    frameCount;
                // Cache line boundary
                uint32_t    loopStart;
                uint32_t    loopEnd;
                int         loopCount;
    volatile    union {
                    uint16_t    volume[2];
                    uint32_t    volumeLR;
                };
                uint32_t    sampleRate;
                // NOTE: audio_track_cblk_t::frameSize is not equal to AudioTrack::frameSize() for
                // 8 bit PCM data: in this case,  mCblk->frameSize is based on a sample size of
                // 16 bit because data is converted to 16 bit before being stored in buffer

                uint8_t     frameSize;
                uint8_t     pad1;
                uint16_t    bufferTimeoutMs; // Maximum cumulated timeout before restarting audioflinger

                uint16_t    waitTimeMs;      // Cumulated wait time
private:
                uint16_t    mSendLevel;      // Fixed point U4.12 so 0x1000 means 1.0
public:
    volatile    int32_t     flags;

                // Cache line boundary (32 bytes)

                            audio_track_cblk_t();
                uint32_t    stepUser(uint32_t frameCount);
                bool        stepServer(uint32_t frameCount);
                void*       buffer(uint32_t offset) const;
                uint32_t    framesAvailable();
                uint32_t    framesAvailable_l();
                uint32_t    framesReady();
                bool        tryLock();

                // No barriers on the following operations, so the ordering of loads/stores
                // with respect to other parameters is UNPREDICTABLE. That's considered safe.

                // for AudioTrack client only, caller must limit to 0.0 <= sendLevel <= 1.0
                void        setSendLevel(float sendLevel) {
                    mSendLevel = uint16_t(sendLevel * 0x1000);
                }

                // for AudioFlinger only; the return value must be validated by the caller
                uint16_t    getSendLevel_U4_12() const {
                    return mSendLevel;
                }
};


// ----------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_AUDIO_TRACK_SHARED_H
