/* //device/include/server/AudioFlinger/AudioFlinger.cpp
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


#define LOG_TAG "AudioFlinger"
//#define LOG_NDEBUG 0

#include <math.h>
#include <signal.h>
#include <sys/time.h>
#include <sys/resource.h>

#include <utils/IServiceManager.h>
#include <utils/Log.h>
#include <utils/Parcel.h>
#include <utils/IPCThreadState.h>
#include <utils/String16.h>
#include <utils/threads.h>

#include <media/AudioTrack.h>
#include <media/AudioRecord.h>

#include <private/media/AudioTrackShared.h>

#include <hardware/AudioHardwareInterface.h>

#include "AudioMixer.h"
#include "AudioFlinger.h"

namespace android {

static const nsecs_t kStandbyTimeInNsecs = seconds(3);
static const unsigned long kBufferRecoveryInUsecs = 2000;
static const unsigned long kMaxBufferRecoveryInUsecs = 20000;
static const float MAX_GAIN = 4096.0f;

// retry counts for buffer fill timeout
// 50 * ~20msecs = 1 second
static const int8_t kMaxTrackRetries = 50;
static const int8_t kMaxTrackStartupRetries = 50;

#define AUDIOFLINGER_SECURITY_ENABLED 1

// ----------------------------------------------------------------------------

static bool recordingAllowed() {
#ifndef HAVE_ANDROID_OS
    return true;
#endif
#if AUDIOFLINGER_SECURITY_ENABLED
    if (getpid() == IPCThreadState::self()->getCallingPid()) return true;
    bool ok = checkCallingPermission(String16("android.permission.RECORD_AUDIO"));
    if (!ok) LOGE("Request requires android.permission.RECORD_AUDIO");
    return ok;
#else
    if (!checkCallingPermission(String16("android.permission.RECORD_AUDIO")))
        LOGW("WARNING: Need to add android.permission.RECORD_AUDIO to manifest");
    return true;
#endif
}

static bool settingsAllowed() {
#ifndef HAVE_ANDROID_OS
    return true;
#endif
#if AUDIOFLINGER_SECURITY_ENABLED
    if (getpid() == IPCThreadState::self()->getCallingPid()) return true;
    bool ok = checkCallingPermission(String16("android.permission.MODIFY_AUDIO_SETTINGS"));
    if (!ok) LOGE("Request requires android.permission.MODIFY_AUDIO_SETTINGS");
    return ok;
#else
    if (!checkCallingPermission(String16("android.permission.MODIFY_AUDIO_SETTINGS")))
        LOGW("WARNING: Need to add android.permission.MODIFY_AUDIO_SETTINGS to manifest");
    return true;
#endif
}

// ----------------------------------------------------------------------------

AudioFlinger::AudioFlinger()
    : BnAudioFlinger(), Thread(false),
        mMasterVolume(0), mMasterMute(true),
        mAudioMixer(0), mAudioHardware(0), mOutput(0), mAudioRecordThread(0),
        mSampleRate(0), mFrameCount(0), mChannelCount(0), mFormat(0),
        mMixBuffer(0), mLastWriteTime(0), mNumWrites(0), mNumDelayedWrites(0),
        mStandby(false), mInWrite(false)
{
    mHardwareStatus = AUDIO_HW_IDLE;
    mAudioHardware = AudioHardwareInterface::create();
    mHardwareStatus = AUDIO_HW_INIT;
    if (mAudioHardware->initCheck() == NO_ERROR) {
        // open 16-bit output stream for s/w mixer
        mHardwareStatus = AUDIO_HW_OUTPUT_OPEN;
        mOutput = mAudioHardware->openOutputStream(AudioSystem::PCM_16_BIT);
        mHardwareStatus = AUDIO_HW_IDLE;
        if (mOutput) {
            mSampleRate = mOutput->sampleRate();
            mChannelCount = mOutput->channelCount();
            mFormat = mOutput->format();
            mMixBufferSize = mOutput->bufferSize();
            mFrameCount = mMixBufferSize / mChannelCount / sizeof(int16_t);
            mMixBuffer = new int16_t[mFrameCount * mChannelCount];
            memset(mMixBuffer, 0, mMixBufferSize);
            mAudioMixer = new AudioMixer(mFrameCount, mSampleRate);
            // FIXME - this should come from settings
            setMasterVolume(1.0f);
            setRouting(AudioSystem::MODE_NORMAL, AudioSystem::ROUTE_SPEAKER, AudioSystem::ROUTE_ALL);
            setRouting(AudioSystem::MODE_RINGTONE, AudioSystem::ROUTE_SPEAKER, AudioSystem::ROUTE_ALL);
            setRouting(AudioSystem::MODE_IN_CALL, AudioSystem::ROUTE_EARPIECE, AudioSystem::ROUTE_ALL);
            setMode(AudioSystem::MODE_NORMAL);
            mMasterMute = false;
        } else {
            LOGE("Failed to initialize output stream");
        }
    } else {
        LOGE("Couldn't even initialize the stubbed audio hardware!");
    }
}

AudioFlinger::~AudioFlinger()
{
    delete mOutput;
    delete mAudioHardware;
    delete [] mMixBuffer;
    delete mAudioMixer;
    mAudioRecordThread.clear();
}

status_t AudioFlinger::dumpClients(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;

    result.append("Clients:\n");
    for (size_t i = 0; i < mClients.size(); ++i) {
        wp<Client> wClient = mClients.valueAt(i);
        if (wClient != 0) {
            sp<Client> client = wClient.promote();
            if (client != 0) {
                snprintf(buffer, SIZE, "  pid: %d\n", client->pid());
                result.append(buffer);
            }
        }
    }
    write(fd, result.string(), result.size());
    return NO_ERROR;
}

status_t AudioFlinger::dumpTracks(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;

    result.append("Tracks:\n");
    result.append("   Name Clien Typ Fmt Chn Buf S M F SRate LeftV RighV Serv User\n");
    for (size_t i = 0; i < mTracks.size(); ++i) {
        wp<Track> wTrack = mTracks[i];
        if (wTrack != 0) {
            sp<Track> track = wTrack.promote();
            if (track != 0) {
                track->dump(buffer, SIZE);
                result.append(buffer);
            }
        }
    }

    result.append("Active Tracks:\n");
    result.append("   Name Clien Typ Fmt Chn Buf S M F SRate LeftV RighV Serv User\n");
    for (size_t i = 0; i < mActiveTracks.size(); ++i) {
        wp<Track> wTrack = mTracks[i];
        if (wTrack != 0) {
            sp<Track> track = wTrack.promote();
            if (track != 0) {
                track->dump(buffer, SIZE);
                result.append(buffer);
            }
        }
    }
    write(fd, result.string(), result.size());
    return NO_ERROR;
}

status_t AudioFlinger::dumpInternals(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    
    snprintf(buffer, SIZE, "AudioMixer tracks: %08x\n", audioMixer().trackNames());
    result.append(buffer);
    snprintf(buffer, SIZE, "last write occurred (msecs): %llu\n", ns2ms(systemTime() - mLastWriteTime));
    result.append(buffer);
    snprintf(buffer, SIZE, "total writes: %d\n", mNumWrites);
    result.append(buffer);
    snprintf(buffer, SIZE, "delayed writes: %d\n", mNumDelayedWrites);
    result.append(buffer);
    snprintf(buffer, SIZE, "blocked in write: %d\n", mInWrite);
    result.append(buffer);
    snprintf(buffer, SIZE, "standby: %d\n", mStandby);
    result.append(buffer);
    snprintf(buffer, SIZE, "Hardware status: %d\n", mHardwareStatus);
    result.append(buffer);
    write(fd, result.string(), result.size());
    return NO_ERROR;
}

status_t AudioFlinger::dumpPermissionDenial(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    snprintf(buffer, SIZE, "Permission Denial: "
            "can't dump AudioFlinger from pid=%d, uid=%d\n",
            IPCThreadState::self()->getCallingPid(),
            IPCThreadState::self()->getCallingUid());
    result.append(buffer);
    write(fd, result.string(), result.size());
    return NO_ERROR;
}

status_t AudioFlinger::dump(int fd, const Vector<String16>& args)
{
    if (checkCallingPermission(String16("android.permission.DUMP")) == false) {
        dumpPermissionDenial(fd, args);
    } else {
        AutoMutex lock(&mLock);

        dumpClients(fd, args);
        dumpTracks(fd, args);
        dumpInternals(fd, args);
        if (mAudioHardware) {
            mAudioHardware->dumpState(fd, args);
        }
    }
    return NO_ERROR;
}

// Thread virtuals
bool AudioFlinger::threadLoop()
{
    nsecs_t maxPeriod = seconds(mFrameCount) / mSampleRate * 2;
    unsigned long sleepTime = kBufferRecoveryInUsecs;
    const size_t mixBufferSize = mFrameCount*mChannelCount*sizeof(int16_t);
    int16_t* curBuf = mMixBuffer;
    Vector< sp<Track> > tracksToRemove;
    size_t enabledTracks;
    nsecs_t standbyTime = systemTime();

    do {
        enabledTracks = 0;
        { // scope for the lock
            Mutex::Autolock _l(mLock);
            const SortedVector< wp<Track> >& activeTracks = mActiveTracks;

            // put audio hardware into standby after short delay
            if UNLIKELY(!activeTracks.size() && systemTime() > standbyTime) {
                // wait until we have something to do...
                LOGV("Audio hardware entering standby\n");
                mHardwareStatus = AUDIO_HW_STANDBY;
                if (!mStandby) {
                    mAudioHardware->standby();
                    mStandby = true;
                }
                mHardwareStatus = AUDIO_HW_IDLE;
                // we're about to wait, flush the binder command buffer
                IPCThreadState::self()->flushCommands();
                mWaitWorkCV.wait(mLock);
                LOGV("Audio hardware exiting standby\n");
                standbyTime = systemTime() + kStandbyTimeInNsecs;
                continue;
            }

            // find out which tracks need to be processed
            size_t count = activeTracks.size();
            for (size_t i=0 ; i<count ; i++) {
                sp<Track> t = activeTracks[i].promote();
                if (t == 0) continue;

                Track* const track = t.get();
                audio_track_cblk_t* cblk = track->cblk();
                uint32_t u = cblk->user;
                uint32_t s = cblk->server;

                // The first time a track is added we wait
                // for all its buffers to be filled before processing it
                audioMixer().setActiveTrack(track->name());
                if ((u > s) && (track->isReady(u, s) || track->isStopped()) &&
                        !track->isPaused())
                {
                    //LOGD("u=%08x, s=%08x [OK]", u, s);

                    // compute volume for this track
                    int16_t left, right;
                    if (track->isMuted() || mMasterMute || track->isPausing()) {
                        left = right = 0;
                        if (track->isPausing()) {
                            LOGV("paused(%d)", track->name());
                            track->setPaused();
                        }
                    } else {
                        float typeVolume = mStreamTypes[track->type()].volume;
                        float v = mMasterVolume * typeVolume;
                        float v_clamped = v * cblk->volume[0];
                        if (v_clamped > MAX_GAIN) v_clamped = MAX_GAIN;
                        left = int16_t(v_clamped);
                        v_clamped = v * cblk->volume[1];
                        if (v_clamped > MAX_GAIN) v_clamped = MAX_GAIN;
                        right = int16_t(v_clamped);
                    }

                    // XXX: these things DON'T need to be done each time
                    AudioMixer& mixer(audioMixer());
                    mixer.setBufferProvider(track);
                    mixer.enable(AudioMixer::MIXING);

                    int param;
                    if ( track->mFillingUpStatus == Track::FS_FILLED) {
                        // no ramp for the first volume setting
                        track->mFillingUpStatus = Track::FS_ACTIVE;
                        if (track->mState == TrackBase::RESUMING) {
                            track->mState = TrackBase::ACTIVE;
                            param = AudioMixer::RAMP_VOLUME;
                        } else {
                            param = AudioMixer::VOLUME;
                        }
                    } else {
                        param = AudioMixer::RAMP_VOLUME;
                    }
                    mixer.setParameter(param, AudioMixer::VOLUME0, left);
                    mixer.setParameter(param, AudioMixer::VOLUME1, right);
                    mixer.setParameter(
                        AudioMixer::TRACK,
                        AudioMixer::FORMAT, track->format());
                    mixer.setParameter(
                        AudioMixer::TRACK,
                        AudioMixer::CHANNEL_COUNT, track->channelCount());
                    mixer.setParameter(
                        AudioMixer::RESAMPLE,
                        AudioMixer::SAMPLE_RATE,
                        int(cblk->sampleRate));

                    // reset retry count
                    track->mRetryCount = kMaxTrackRetries;
                    enabledTracks++;
                } else {
                    //LOGD("u=%08x, s=%08x [NOT READY]", u, s);
                    if (track->isStopped()) {
                        track->mFillingUpStatus = Track::FS_FILLING;
                        track->mFlags = 0;    
                    }
                    if (track->isTerminated() || track->isStopped() || track->isPaused()) {
                        // We have consumed all the buffers of this track.
                        // Remove it from the list of active tracks.
                        LOGV("remove(%d) from active list", track->name());
                        tracksToRemove.add(track);
                    } else {
                        // No buffers for this track. Give it a few chances to
                        // fill a buffer, then remove it from active list.
                        if (--(track->mRetryCount) <= 0) {
                            LOGV("BUFFER TIMEOUT: remove(%d) from active list", track->name());
                            tracksToRemove.add(track);
                        }
                    }
                    // LOGV("disable(%d)", track->name());
                    audioMixer().disable(AudioMixer::MIXING);
                }
            }

            // remove all the tracks that need to be...
            count = tracksToRemove.size();
            if (UNLIKELY(count)) {
                for (size_t i=0 ; i<count ; i++) {
                    const sp<Track>& track = tracksToRemove[i];
                    mActiveTracks.remove(track);
                    if (track->isTerminated()) {
                        mTracks.remove(track);
                        audioMixer().deleteTrackName(track->mName);
                    }
                }
            }
        }

        if (LIKELY(enabledTracks)) {
            // mix buffers...
            audioMixer().process(curBuf);

            // output audio to hardware
            mLastWriteTime = systemTime();
            mInWrite = true;
            mOutput->write(curBuf, mixBufferSize);
            mNumWrites++;
            mInWrite = false;
            mStandby = false;
            nsecs_t temp = systemTime();
            standbyTime = temp + kStandbyTimeInNsecs;
            nsecs_t delta = temp - mLastWriteTime;
            if (delta > maxPeriod) {
                LOGW("write blocked for %llu msecs", ns2ms(delta));
                mNumDelayedWrites++;
            }
            sleepTime = kBufferRecoveryInUsecs;
        } else {
            // There was nothing to mix this round, which means all
            // active tracks were late. Sleep a little bit to give
            // them another chance. If we're too late, the audio
            // hardware will zero-fill for us.
            LOGV("no buffers - usleep(%lu)", sleepTime);
            usleep(sleepTime);
            if (sleepTime < kMaxBufferRecoveryInUsecs) {
                sleepTime += kBufferRecoveryInUsecs;
            }
        }

        // finally let go of all our tracks, without the lock held
        // since we can't guarantee the destructors won't acquire that
        // same lock.
        tracksToRemove.clear();
    } while (true);

    return false;
}

status_t AudioFlinger::readyToRun()
{
    if (mSampleRate == 0) {
        LOGE("No working audio driver found.");
        return NO_INIT;
    }
    LOGI("AudioFlinger's main thread ready to run.");
    return NO_ERROR;
}

void AudioFlinger::onFirstRef()
{
    run("AudioFlinger", ANDROID_PRIORITY_URGENT_AUDIO);
}

// IAudioFlinger interface
sp<IAudioTrack> AudioFlinger::createTrack(
        pid_t pid,
        int streamType,
        uint32_t sampleRate,
        int format,
        int channelCount,
        int bufferCount,
        uint32_t flags)
{
    if (streamType >= AudioTrack::NUM_STREAM_TYPES) {
        LOGE("invalid stream type");
        return NULL;
    }

    if (sampleRate > MAX_SAMPLE_RATE) {
        LOGE("Sample rate out of range: %d", sampleRate);
        return NULL;
    }

    sp<Track> track;
    sp<TrackHandle> trackHandle;
    Mutex::Autolock _l(mLock);

    if (mSampleRate == 0) {
        LOGE("Audio driver not initialized.");
        return trackHandle;
    }

    sp<Client> client;
    wp<Client> wclient = mClients.valueFor(pid);

    if (wclient != NULL) {
        client = wclient.promote();
    } else {
        client = new Client(this, pid);
        mClients.add(pid, client);
    }

    // FIXME: Buffer size should be based on sample rate for consistent latency
    track = new Track(this, client, streamType, sampleRate, format,
            channelCount, bufferCount, channelCount == 1 ? mMixBufferSize>>1 : mMixBufferSize);
    mTracks.add(track);
    trackHandle = new TrackHandle(track);
    return trackHandle;
}

uint32_t AudioFlinger::sampleRate() const
{
    return mSampleRate;
}

int AudioFlinger::channelCount() const
{
    return mChannelCount;
}

int AudioFlinger::format() const
{
    return mFormat;
}

size_t AudioFlinger::frameCount() const
{
    return mFrameCount;
}

status_t AudioFlinger::setMasterVolume(float value)
{
    // check calling permissions
    if (!settingsAllowed()) {
        return PERMISSION_DENIED;
    }

    // when hw supports master volume, don't scale in sw mixer
    AutoMutex lock(mHardwareLock);
    mHardwareStatus = AUDIO_HW_SET_MASTER_VOLUME;
    if (mAudioHardware->setMasterVolume(value) == NO_ERROR) {
        mMasterVolume = 1.0f;
    }
    else {
        mMasterVolume = value;
    }
    mHardwareStatus = AUDIO_HW_IDLE;
    return NO_ERROR;
}

status_t AudioFlinger::setRouting(int mode, uint32_t routes, uint32_t mask)
{
    // check calling permissions
    if (!settingsAllowed()) {
        return PERMISSION_DENIED;
    }
    if ((mode < AudioSystem::MODE_CURRENT) || (mode >= AudioSystem::NUM_MODES)) {
        LOGW("Illegal value: setRouting(%d, %u, %u)", mode, routes, mask);
        return BAD_VALUE;
    }

    AutoMutex lock(mHardwareLock);
    mHardwareStatus = AUDIO_HW_GET_ROUTING;
    uint32_t r;
    uint32_t err = mAudioHardware->getRouting(mode, &r);
    if (err == NO_ERROR) {
        r = (r & ~mask) | (routes & mask);
        mHardwareStatus = AUDIO_HW_SET_ROUTING;
        err = mAudioHardware->setRouting(mode, r);
    }
    mHardwareStatus = AUDIO_HW_IDLE;
    return err;
}

uint32_t AudioFlinger::getRouting(int mode) const
{
    uint32_t routes = 0;
    if ((mode >= AudioSystem::MODE_CURRENT) && (mode < AudioSystem::NUM_MODES)) {
        mHardwareStatus = AUDIO_HW_GET_ROUTING;
        mAudioHardware->getRouting(mode, &routes);
        mHardwareStatus = AUDIO_HW_IDLE;
    } else {
        LOGW("Illegal value: getRouting(%d)", mode);
    }
    return routes;
}

status_t AudioFlinger::setMode(int mode)
{
    // check calling permissions
    if (!settingsAllowed()) {
        return PERMISSION_DENIED;
    }
    if ((mode < 0) || (mode >= AudioSystem::NUM_MODES)) {
        LOGW("Illegal value: setMode(%d)", mode);
        return BAD_VALUE;
    }

    AutoMutex lock(mHardwareLock);
    mHardwareStatus = AUDIO_HW_SET_MODE;
    status_t ret = mAudioHardware->setMode(mode);
    mHardwareStatus = AUDIO_HW_IDLE;
    return ret;
}

int AudioFlinger::getMode() const
{
    int mode = AudioSystem::MODE_INVALID;
    mHardwareStatus = AUDIO_HW_SET_MODE;
    mAudioHardware->getMode(&mode);
    mHardwareStatus = AUDIO_HW_IDLE;
    return mode;
}

status_t AudioFlinger::setMicMute(bool state)
{
    // check calling permissions
    if (!settingsAllowed()) {
        return PERMISSION_DENIED;
    }

    AutoMutex lock(mHardwareLock);
    mHardwareStatus = AUDIO_HW_SET_MIC_MUTE;
    status_t ret = mAudioHardware->setMicMute(state);
    mHardwareStatus = AUDIO_HW_IDLE;
    return ret;
}

bool AudioFlinger::getMicMute() const
{
    bool state = AudioSystem::MODE_INVALID;
    mHardwareStatus = AUDIO_HW_GET_MIC_MUTE;
    mAudioHardware->getMicMute(&state);
    mHardwareStatus = AUDIO_HW_IDLE;
    return state;
}

status_t AudioFlinger::setMasterMute(bool muted)
{
    // check calling permissions
    if (!settingsAllowed()) {
        return PERMISSION_DENIED;
    }

    mMasterMute = muted;
    return NO_ERROR;
}

float AudioFlinger::masterVolume() const
{
    return mMasterVolume;
}

bool AudioFlinger::masterMute() const
{
    return mMasterMute;
}

status_t AudioFlinger::setStreamVolume(int stream, float value)
{
    // check calling permissions
    if (!settingsAllowed()) {
        return PERMISSION_DENIED;
    }

    if (uint32_t(stream) >= AudioTrack::NUM_STREAM_TYPES) {
        return BAD_VALUE;
    }
    
    mStreamTypes[stream].volume = value;
    status_t ret = NO_ERROR;
    if (stream == AudioTrack::VOICE_CALL) {
        AutoMutex lock(mHardwareLock);
        mHardwareStatus = AUDIO_SET_VOICE_VOLUME;
        ret = mAudioHardware->setVoiceVolume(value);
        mHardwareStatus = AUDIO_HW_IDLE;
    }
    return ret;
}

status_t AudioFlinger::setStreamMute(int stream, bool muted)
{
    // check calling permissions
    if (!settingsAllowed()) {
        return PERMISSION_DENIED;
    }

    if (uint32_t(stream) >= AudioTrack::NUM_STREAM_TYPES) {
        return BAD_VALUE;
    }
    mStreamTypes[stream].mute = muted;
    return NO_ERROR;
}

float AudioFlinger::streamVolume(int stream) const
{
    if (uint32_t(stream) >= AudioTrack::NUM_STREAM_TYPES) {
        return 0.0f;
    }
    return mStreamTypes[stream].volume;
}

bool AudioFlinger::streamMute(int stream) const
{
    if (uint32_t(stream) >= AudioTrack::NUM_STREAM_TYPES) {
        return true;
    }
    return mStreamTypes[stream].mute;
}

bool AudioFlinger::isMusicActive() const
{
    size_t count = mActiveTracks.size();
    for (size_t i = 0 ; i < count ; ++i) {
        sp<Track> t = mActiveTracks[i].promote();
        if (t == 0) continue;
        Track* const track = t.get();
        if (t->mStreamType == AudioTrack::MUSIC)
            return true;
    }
    return false;
}

status_t AudioFlinger::setParameter(const char* key, const char* value)
{
    status_t result;
    AutoMutex lock(mHardwareLock);
    mHardwareStatus = AUDIO_SET_PARAMETER;
    result = mAudioHardware->setParameter(key, value);
    mHardwareStatus = AUDIO_HW_IDLE;
    return result;
}

void AudioFlinger::removeClient(pid_t pid)
{
    Mutex::Autolock _l(mLock);
    mClients.removeItem(pid);
}

status_t AudioFlinger::addTrack(const sp<Track>& track)
{
    Mutex::Autolock _l(mLock);

    // here the track could be either new, or restarted
    // in both cases "unstop" the track
    if (track->isPaused()) {
        track->mState = TrackBase::RESUMING;
        LOGV("PAUSED => RESUMING (%d)", track->name());
    } else {
        track->mState = TrackBase::ACTIVE;
        LOGV("? => ACTIVE (%d)", track->name());
    }
    // set retry count for buffer fill
    track->mRetryCount = kMaxTrackStartupRetries;
    LOGV("mWaitWorkCV.broadcast");
    mWaitWorkCV.broadcast();

    if (mActiveTracks.indexOf(track) < 0) {
        // the track is newly added, make sure it fills up all its
        // buffers before playing. This is to ensure the client will
        // effectively get the latency it requested.
        track->mFillingUpStatus = Track::FS_FILLING;
        mActiveTracks.add(track);
        return NO_ERROR;
    }
    return ALREADY_EXISTS;
}

void AudioFlinger::removeTrack(wp<Track> track, int name)
{
    Mutex::Autolock _l(mLock);
    sp<Track> t = track.promote();
    if (t!=NULL && (t->mState <= TrackBase::STOPPED)) {
        remove_track_l(track, name);
    }
}

void AudioFlinger::remove_track_l(wp<Track> track, int name)
{
    sp<Track> t = track.promote();
    if (t!=NULL) {
        t->reset();
    }
    audioMixer().deleteTrackName(name);
    mActiveTracks.remove(track);
    mWaitWorkCV.broadcast();
}

void AudioFlinger::destroyTrack(const sp<Track>& track)
{
    // NOTE: We're acquiring a strong reference on the track before
    // acquiring the lock, this is to make sure removing it from
    // mTracks won't cause the destructor to be called while the lock is
    // held (note that technically, 'track' could be a reference to an item
    // in mTracks, which is why we need to do this).
    sp<Track> keep(track);
    Mutex::Autolock _l(mLock);
    track->mState = TrackBase::TERMINATED;
    if (mActiveTracks.indexOf(track) < 0) {
        LOGV("remove track (%d) and delete from mixer", track->name());
        mTracks.remove(track);
        audioMixer().deleteTrackName(keep->name());
    }
}

// ----------------------------------------------------------------------------

AudioFlinger::Client::Client(const sp<AudioFlinger>& audioFlinger, pid_t pid)
    :   RefBase(),
        mAudioFlinger(audioFlinger),
        mMemoryDealer(new MemoryDealer(1024*1024)),
        mPid(pid)
{
    // 1 MB of address space is good for 32 tracks, 8 buffers each, 4 KB/buffer
}

AudioFlinger::Client::~Client()
{
    mAudioFlinger->removeClient(mPid);
}

const sp<MemoryDealer>& AudioFlinger::Client::heap() const
{
    return mMemoryDealer;
}

// ----------------------------------------------------------------------------

AudioFlinger::TrackBase::TrackBase(
            const sp<AudioFlinger>& audioFlinger,
            const sp<Client>& client,
            int streamType,
            uint32_t sampleRate,
            int format,
            int channelCount,
            int bufferCount,
            int bufferSize)
    :   RefBase(),
        mAudioFlinger(audioFlinger),
        mClient(client),
        mStreamType(streamType),
        mFormat(format),
        mChannelCount(channelCount),
        mBufferCount(bufferCount),
        mFlags(0),
        mBufferSize(bufferSize),
        mState(IDLE),
        mClientTid(-1)
{
    mName = audioFlinger->audioMixer().getTrackName();
    if (mName < 0) {
        LOGE("no more track names availlable");
        return;
    }

    // LOGD("Creating track with %d buffers @ %d bytes", bufferCount, bufferSize);
    size_t size = sizeof(audio_track_cblk_t) + bufferCount * bufferSize;
    mCblkMemory = client->heap()->allocate(size);
    if (mCblkMemory != 0) {
        mCblk = static_cast<audio_track_cblk_t *>(mCblkMemory->pointer());
        if (mCblk) { // construct the shared structure in-place.
            new(mCblk) audio_track_cblk_t();
            // clear all buffers
            mCblk->size = bufferSize;
            mCblk->sampleRate = sampleRate;
            mBuffers = (char*)mCblk + sizeof(audio_track_cblk_t);
            memset(mBuffers, 0, bufferCount * bufferSize);
        }
    } else {
        LOGE("not enough memory for AudioTrack size=%u", size);
        client->heap()->dump("AudioTrack");
        return;
    }
}

AudioFlinger::TrackBase::~TrackBase()
{
    mCblk->~audio_track_cblk_t();   // destroy our shared-structure.
    mCblkMemory.clear();            // and free the shared memory
    mClient.clear();
}

void AudioFlinger::TrackBase::releaseBuffer(AudioBufferProvider::Buffer* buffer)
{
    buffer->raw = 0;
    buffer->frameCount = 0;
    step();
}

bool AudioFlinger::TrackBase::step() {
    bool result;
    audio_track_cblk_t* cblk = this->cblk();
    
    result = cblk->stepServer(bufferCount()); 
    if (!result) {
        LOGV("stepServer failed acquiring cblk mutex");
        mFlags |= STEPSERVER_FAILED;
    }
    return result;
}

void AudioFlinger::TrackBase::reset() {
    audio_track_cblk_t* cblk = this->cblk();

    cblk->user = 0;
    cblk->server = 0;
    mFlags = 0;    
}

sp<IMemory> AudioFlinger::TrackBase::getCblk() const
{
    return mCblkMemory;
}

int AudioFlinger::TrackBase::sampleRate() const {
    return mCblk->sampleRate;
}

// ----------------------------------------------------------------------------

AudioFlinger::Track::Track(
            const sp<AudioFlinger>& audioFlinger,
            const sp<Client>& client,
            int streamType,
            uint32_t sampleRate,
            int format,
            int channelCount,
            int bufferCount,
            int bufferSize)
    :   TrackBase(audioFlinger, client, streamType, sampleRate, format, channelCount, bufferCount, bufferSize)
{
    mVolume[0] = 1.0f;
    mVolume[1] = 1.0f;
    mMute = false;
}

AudioFlinger::Track::~Track()
{
    wp<Track> weak(this); // never create a strong ref from the dtor
    mState = TERMINATED;
    mAudioFlinger->removeTrack(weak, mName);
}

void AudioFlinger::Track::destroy()
{
    mAudioFlinger->destroyTrack(this);
}

void AudioFlinger::Track::dump(char* buffer, size_t size)
{
    snprintf(buffer, size, "  %5d %5d %3u %3u %3u %3u %1d %1d %1d %5u %5u %5u %04x %04x\n",
            mName - AudioMixer::TRACK0,
            mClient->pid(),
            mStreamType,
            mFormat,
            mChannelCount,
            mBufferCount,
            mState,
            mMute,
            mFillingUpStatus,
            mCblk->sampleRate,
            mCblk->volume[0],
            mCblk->volume[1],
            mCblk->server,
            mCblk->user);
}

status_t AudioFlinger::Track::getNextBuffer(AudioBufferProvider::Buffer* buffer)
{
     audio_track_cblk_t* cblk = this->cblk();
     uint32_t u = cblk->user;
     uint32_t s = cblk->server;
     
     // Check if last stepServer failed, try to step now 
     if (mFlags & TrackBase::STEPSERVER_FAILED) {
         if (!step())  goto getNextBuffer_exit;
         LOGV("stepServer recovered");
         mFlags &= ~TrackBase::STEPSERVER_FAILED;
     }

     if (LIKELY(u > s)) {
         int index = s & audio_track_cblk_t::BUFFER_MASK;
         buffer->raw = getBuffer(index);
         buffer->frameCount = mAudioFlinger->frameCount();
         return NO_ERROR;
     }
getNextBuffer_exit:
     buffer->raw = 0;
     buffer->frameCount = 0;
     return NOT_ENOUGH_DATA;
}

bool AudioFlinger::Track::isReady(uint32_t u, int32_t s) const {
    if (mFillingUpStatus != FS_FILLING) return true;
    const uint32_t u_seq = u & audio_track_cblk_t::SEQUENCE_MASK;
    const uint32_t u_buf = u & audio_track_cblk_t::BUFFER_MASK;
    const uint32_t s_seq = s & audio_track_cblk_t::SEQUENCE_MASK;
    const uint32_t s_buf = s & audio_track_cblk_t::BUFFER_MASK;
    if (u_seq > s_seq && u_buf == s_buf) {
        mFillingUpStatus = FS_FILLED;
        return true;
    }
    return false;
}

status_t AudioFlinger::Track::start()
{
    LOGV("start(%d)", mName);
    mAudioFlinger->addTrack(this);
    return NO_ERROR;
}

void AudioFlinger::Track::stop()
{
    LOGV("stop(%d)", mName);
    Mutex::Autolock _l(mAudioFlinger->mLock);
    if (mState > STOPPED) {
        mState = STOPPED;
        // If the track is not active (PAUSED and buffers full), flush buffers  
        if (mAudioFlinger->mActiveTracks.indexOf(this) < 0) {
            reset();
        }
        LOGV("(> STOPPED) => STOPPED (%d)", mName);
    }
}

void AudioFlinger::Track::pause()
{
    LOGV("pause(%d)", mName);
    Mutex::Autolock _l(mAudioFlinger->mLock);
    if (mState == ACTIVE || mState == RESUMING) {
        mState = PAUSING;
        LOGV("ACTIVE/RESUMING => PAUSING (%d)", mName);
    }
}

void AudioFlinger::Track::flush()
{
    LOGV("flush(%d)", mName);
    Mutex::Autolock _l(mAudioFlinger->mLock);
    if (mState != STOPPED && mState != PAUSED && mState != PAUSING) {
        return;
    }
    // No point remaining in PAUSED state after a flush => go to
    // STOPPED state
    mState = STOPPED;

    // NOTE: reset() will reset cblk->user and cblk->server with
    // the risk that at the same time, the AudioMixer is trying to read
    // data. In this case, getNextBuffer() would return a NULL pointer
    // as audio buffer => the AudioMixer code MUST always test that pointer 
    // returned by getNextBuffer() is not NULL! 
    reset();
}

void AudioFlinger::Track::reset()
{
    TrackBase::reset();
    mFillingUpStatus = FS_FILLING;
}

void AudioFlinger::Track::mute(bool muted)
{
    mMute = muted;
}

void AudioFlinger::Track::setVolume(float left, float right)
{
    mVolume[0] = left;
    mVolume[1] = right;
}

// ----------------------------------------------------------------------------

AudioFlinger::TrackHandle::TrackHandle(const sp<AudioFlinger::Track>& track)
    : BnAudioTrack(),
      mTrack(track)
{
}

AudioFlinger::TrackHandle::~TrackHandle() {
    // just stop the track on deletion, associated resources
    // will be freed from the main thread once all pending buffers have
    // been played. Unless it's not in the active track list, in which
    // case we free everything now...
    mTrack->destroy();
}

status_t AudioFlinger::TrackHandle::start() {
    return mTrack->start();
}

void AudioFlinger::TrackHandle::stop() {
    mTrack->stop();
}

void AudioFlinger::TrackHandle::flush() {
    mTrack->flush();
}

void AudioFlinger::TrackHandle::mute(bool e) {
    mTrack->mute(e);
}

void AudioFlinger::TrackHandle::pause() {
    mTrack->pause();
}

void AudioFlinger::TrackHandle::setVolume(float left, float right) {
    mTrack->setVolume(left, right);
}

sp<IMemory> AudioFlinger::TrackHandle::getCblk() const {
    return mTrack->getCblk();
}

status_t AudioFlinger::TrackHandle::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    return BnAudioTrack::onTransact(code, data, reply, flags);
}

// ----------------------------------------------------------------------------

sp<AudioFlinger::AudioRecordThread> AudioFlinger::audioRecordThread()
{
    Mutex::Autolock _l(mLock);
    return mAudioRecordThread;
}

void AudioFlinger::endRecord()
{
    Mutex::Autolock _l(mLock);
    mAudioRecordThread.clear();
}

sp<IAudioRecord> AudioFlinger::openRecord(
        pid_t pid,
        int streamType,
        uint32_t sampleRate,
        int format,
        int channelCount,
        int bufferCount,
        uint32_t flags)
{
    sp<AudioRecordThread> thread;
    sp<RecordTrack> recordTrack;
    sp<RecordHandle> recordHandle;
    sp<Client> client;
    wp<Client> wclient;
    AudioStreamIn* input = 0;

    // check calling permissions
    if (!recordingAllowed()) {
        goto Exit;
    }

    if (uint32_t(streamType) >= AudioRecord::NUM_STREAM_TYPES) {
        LOGE("invalid stream type");
        goto Exit;
    }

    if (sampleRate > MAX_SAMPLE_RATE) {
        LOGE("Sample rate out of range");
        goto Exit;
    }

    if (mSampleRate == 0) {
        LOGE("Audio driver not initialized");
        goto Exit;
    }

    // Create audio thread - take mutex to prevent race condition
    {
        Mutex::Autolock _l(mLock);
        if (mAudioRecordThread != 0) {
            LOGE("Record channel already open");
            goto Exit;
        }
        thread = new AudioRecordThread(this);
        mAudioRecordThread = thread;
    }
    // It's safe to release the mutex here since the client doesn't get a
    // handle until we return from this call

    // open driver, initialize h/w
    input = mAudioHardware->openInputStream(
            AudioSystem::PCM_16_BIT, channelCount, sampleRate);
    if (!input) {
        LOGE("Error opening input stream");
        mAudioRecordThread.clear();
        goto Exit;
    }

    // add client to list
    {
        Mutex::Autolock _l(mLock);
        wclient = mClients.valueFor(pid);
        if (wclient != NULL) {
            client = wclient.promote();
        } else {
            client = new Client(this, pid);
            mClients.add(pid, client);
        }
    }

    // create new record track and pass to record thread
    recordTrack = new RecordTrack(this, client, streamType, sampleRate,
            format, channelCount, bufferCount, input->bufferSize());

    // spin up record thread
    thread->open(recordTrack, input);
    thread->run("AudioRecordThread", PRIORITY_URGENT_AUDIO);

    // return to handle to client
    recordHandle = new RecordHandle(recordTrack);

Exit:
    return recordHandle;
}

status_t AudioFlinger::startRecord() {
    sp<AudioRecordThread> t = audioRecordThread();
    if (t == 0) return NO_INIT;
    return t->start();
}

void AudioFlinger::stopRecord() {
    sp<AudioRecordThread> t = audioRecordThread();
    if (t != 0) t->stop();
}

void AudioFlinger::exitRecord()
{
    sp<AudioRecordThread> t = audioRecordThread();
    if (t != 0) t->exit();
}

// ----------------------------------------------------------------------------

AudioFlinger::RecordTrack::RecordTrack(
            const sp<AudioFlinger>& audioFlinger,
            const sp<Client>& client,
            int streamType,
            uint32_t sampleRate,
            int format,
            int channelCount,
            int bufferCount,
            int bufferSize)
    :   TrackBase(audioFlinger, client, streamType, sampleRate, format,
            channelCount, bufferCount, bufferSize),
            mOverflow(false)
{
}

AudioFlinger::RecordTrack::~RecordTrack()
{
    mAudioFlinger->audioMixer().deleteTrackName(mName);
    mAudioFlinger->exitRecord();
}

status_t AudioFlinger::RecordTrack::getNextBuffer(AudioBufferProvider::Buffer* buffer)
{
     audio_track_cblk_t* cblk = this->cblk();
     const uint32_t u_seq = cblk->user & audio_track_cblk_t::SEQUENCE_MASK;
     const uint32_t u_buf = cblk->user & audio_track_cblk_t::BUFFER_MASK;
     const uint32_t s_seq = cblk->server & audio_track_cblk_t::SEQUENCE_MASK;
     const uint32_t s_buf = cblk->server & audio_track_cblk_t::BUFFER_MASK;
     
     // Check if last stepServer failed, try to step now 
     if (mFlags & TrackBase::STEPSERVER_FAILED) {
         if (!step())  goto getNextBuffer_exit;
         LOGV("stepServer recovered");
         mFlags &= ~TrackBase::STEPSERVER_FAILED;
     }

     if (LIKELY(s_seq == u_seq || s_buf != u_buf)) {
         buffer->raw = getBuffer(s_buf);
         buffer->frameCount = mAudioFlinger->frameCount();
         return NO_ERROR;
     }

getNextBuffer_exit:     
     buffer->raw = 0;
     buffer->frameCount = 0;
     return NOT_ENOUGH_DATA;
}

status_t AudioFlinger::RecordTrack::start()
{
    return mAudioFlinger->startRecord();
}

void AudioFlinger::RecordTrack::stop()
{
    mAudioFlinger->stopRecord();
}

// ----------------------------------------------------------------------------

AudioFlinger::RecordHandle::RecordHandle(const sp<AudioFlinger::RecordTrack>& recordTrack)
    : BnAudioRecord(),
    mRecordTrack(recordTrack)
{
}

AudioFlinger::RecordHandle::~RecordHandle() {}

status_t AudioFlinger::RecordHandle::start() {
    LOGV("RecordHandle::start()");
    return mRecordTrack->start();
}

void AudioFlinger::RecordHandle::stop() {
    LOGV("RecordHandle::stop()");
    mRecordTrack->stop();
}

sp<IMemory> AudioFlinger::RecordHandle::getCblk() const {
    return mRecordTrack->getCblk();
}

status_t AudioFlinger::RecordHandle::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    return BnAudioRecord::onTransact(code, data, reply, flags);
}

// ----------------------------------------------------------------------------

AudioFlinger::AudioRecordThread::AudioRecordThread(const sp<AudioFlinger>& audioFlinger) :
    mAudioFlinger(audioFlinger),
    mRecordTrack(0),
    mInput(0),
    mActive(false)
{
}

AudioFlinger::AudioRecordThread::~AudioRecordThread()
{
}

bool AudioFlinger::AudioRecordThread::threadLoop()
{
    LOGV("AudioRecordThread: start record loop");

    // start recording
    while (!exitPending()) {
        if (!mActive) {
            mLock.lock();
            if (!mActive && !exitPending()) {
                LOGV("AudioRecordThread: loop stopping");
                mWaitWorkCV.wait(mLock);
                LOGV("AudioRecordThread: loop starting");
            }
            mLock.unlock();
        } else {
            // promote strong ref so track isn't deleted while we access it
            sp<RecordTrack> t = mRecordTrack.promote();

            // if we lose the weak reference, client is gone.
            if (t == 0) {
                LOGV("AudioRecordThread: client deleted track");
                break;
            }

            if (LIKELY(t->getNextBuffer(&mBuffer) == NO_ERROR)) {
                if (mInput->read(mBuffer.raw, t->mBufferSize) < 0) {
                    LOGE("Error reading audio input");
                    sleep(1);
                }
                t->releaseBuffer(&mBuffer);
            }

            // client isn't retrieving buffers fast enough
            else {
                if (!t->setOverflow())
                    LOGW("AudioRecordThread: buffer overflow");
            }
        }
    };

    // close hardware
    close();

    // delete this object - no more data references after this call
    mAudioFlinger->endRecord();
    return false;
}

status_t AudioFlinger::AudioRecordThread::open(const sp<RecordTrack>& recordTrack, AudioStreamIn *input) {
    LOGV("AudioRecordThread::open");
    // check for record channel already open
    AutoMutex lock(&mLock);
    if (mRecordTrack != NULL) {
        LOGE("Record channel already open");
        return ALREADY_EXISTS;
    }
    mRecordTrack = recordTrack;
    mInput = input;
    return NO_ERROR;
}

status_t AudioFlinger::AudioRecordThread::start()
{
    LOGV("AudioRecordThread::start");
    AutoMutex lock(&mLock);
    if (mActive) return -EBUSY;

    sp<RecordTrack> t = mRecordTrack.promote();
    if (t == 0) return UNKNOWN_ERROR;

    // signal thread to start
    LOGV("Signal record thread");
    mActive = true;
    mWaitWorkCV.signal();
    return NO_ERROR;
}

void AudioFlinger::AudioRecordThread::stop() {
    LOGV("AudioRecordThread::stop");
    AutoMutex lock(&mLock);
    if (mActive) {
        mActive = false;
        mWaitWorkCV.signal();
    }
}

void AudioFlinger::AudioRecordThread::exit()
{
    LOGV("AudioRecordThread::exit");
    AutoMutex lock(&mLock);
    requestExit();
    mWaitWorkCV.signal();
}


status_t AudioFlinger::AudioRecordThread::close()
{
    LOGV("AudioRecordThread::close");
    AutoMutex lock(&mLock);
    if (!mInput) return NO_INIT;
    delete mInput;
    mInput = 0;
    return NO_ERROR;
}

status_t AudioFlinger::onTransact(
        uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    return BnAudioFlinger::onTransact(code, data, reply, flags);
}

// ----------------------------------------------------------------------------
void AudioFlinger::instantiate() {
    defaultServiceManager()->addService(
            String16("media.audio_flinger"), new AudioFlinger());
}

}; // namespace android
