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

#include <cutils/properties.h>

#include <media/AudioTrack.h>
#include <media/AudioRecord.h>

#include <private/media/AudioTrackShared.h>

#include <hardware/AudioHardwareInterface.h>

#include "AudioMixer.h"
#include "AudioFlinger.h"

#ifdef WITH_A2DP
#include "A2dpAudioInterface.h"
#endif

namespace android {

//static const nsecs_t kStandbyTimeInNsecs = seconds(3);
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
        mMasterVolume(0), mMasterMute(true), mHardwareAudioMixer(0), mA2dpAudioMixer(0),
        mAudioMixer(0), mAudioHardware(0), mA2dpAudioInterface(0),
        mHardwareOutput(0), mA2dpOutput(0), mOutput(0), mAudioRecordThread(0),
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
        status_t status;
        mHardwareOutput = mAudioHardware->openOutputStream(AudioSystem::PCM_16_BIT, 0, 0, &status);
        mHardwareStatus = AUDIO_HW_IDLE;
        if (mHardwareOutput) {
            mSampleRate = mHardwareOutput->sampleRate();
            mHardwareAudioMixer = new AudioMixer(getOutputFrameCount(mHardwareOutput), mSampleRate);
            setOutput(mHardwareOutput);

            // FIXME - this should come from settings
            setMasterVolume(1.0f);
            setRouting(AudioSystem::MODE_NORMAL, AudioSystem::ROUTE_SPEAKER, AudioSystem::ROUTE_ALL);
            setRouting(AudioSystem::MODE_RINGTONE, AudioSystem::ROUTE_SPEAKER, AudioSystem::ROUTE_ALL);
            setRouting(AudioSystem::MODE_IN_CALL, AudioSystem::ROUTE_EARPIECE, AudioSystem::ROUTE_ALL);
            setMode(AudioSystem::MODE_NORMAL);
            mMasterMute = false;
        } else {
            LOGE("Failed to initialize output stream, status: %d", status);
        }
        
#ifdef WITH_A2DP
        // Create A2DP interface
        mA2dpAudioInterface = new A2dpAudioInterface();
        mA2dpOutput = mA2dpAudioInterface->openOutputStream(AudioSystem::PCM_16_BIT, 0, 0, &status);       
        mA2dpAudioMixer = new AudioMixer(getOutputFrameCount(mA2dpOutput), mA2dpOutput->sampleRate());

        // create a buffer big enough for both hardware and A2DP audio output.
        size_t hwFrameCount = getOutputFrameCount(mHardwareOutput);
        size_t a2dpFrameCount = getOutputFrameCount(mA2dpOutput);
        size_t frameCount = (hwFrameCount > a2dpFrameCount ? hwFrameCount : a2dpFrameCount);
#else
        size_t frameCount = getOutputFrameCount(mHardwareOutput);
#endif
        // FIXME - Current mixer implementation only supports stereo output: Always
        // Allocate a stereo buffer even if HW output is mono.
        mMixBuffer = new int16_t[frameCount * 2];
        memset(mMixBuffer, 0, frameCount * 2 * sizeof(int16_t));
        
        // Start record thread
        mAudioRecordThread = new AudioRecordThread(mAudioHardware);
        if (mAudioRecordThread != 0) {
            mAudioRecordThread->run("AudioRecordThread", PRIORITY_URGENT_AUDIO);            
        }
     } else {
        LOGE("Couldn't even initialize the stubbed audio hardware!");
    }

    char value[PROPERTY_VALUE_MAX];
    // FIXME: What property should this be???
    property_get("ro.audio.silent", value, "0");
    if (atoi(value)) {
        LOGD("Silence is golden");
        mMasterMute = true;
    }
}

AudioFlinger::~AudioFlinger()
{
    if (mAudioRecordThread != 0) {
        mAudioRecordThread->exit();
        mAudioRecordThread.clear();        
    }
    delete mOutput;
    delete mA2dpOutput;
    delete mAudioHardware;
    delete mA2dpAudioInterface;
    delete [] mMixBuffer;
    delete mHardwareAudioMixer;
    delete mA2dpAudioMixer;
}
 
void AudioFlinger::setOutput(AudioStreamOut* output)
{
    // lock on mOutputLock to prevent threadLoop() from starving us
    Mutex::Autolock _l2(mOutputLock);
    
    // to synchronize with threadLoop()
    Mutex::Autolock _l(mLock);

    if (mOutput != output) {
        mSampleRate = output->sampleRate();
        mChannelCount = output->channelCount();
    
        // FIXME - Current mixer implementation only supports stereo output
        if (mChannelCount == 1) {
            LOGE("Invalid audio hardware channel count");
        }
        mFormat = output->format();
        mFrameCount = getOutputFrameCount(output);
                
        mAudioMixer = (output == mA2dpOutput ? mA2dpAudioMixer : mHardwareAudioMixer);
        mOutput = output;
    }
}

size_t AudioFlinger::getOutputFrameCount(AudioStreamOut* output) 
{
    return output->bufferSize() / output->channelCount() / sizeof(int16_t);
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

    snprintf(buffer, SIZE, "AudioMixer tracks: %08x\n", audioMixer()->trackNames());
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
    unsigned long sleepTime = kBufferRecoveryInUsecs;
    int16_t* curBuf = mMixBuffer;
    Vector< sp<Track> > tracksToRemove;
    size_t enabledTracks = 0;
    nsecs_t standbyTime = systemTime();
    AudioMixer* mixer = 0;
    size_t frameCount = 0;
    int channelCount = 0;
    uint32_t sampleRate = 0;
    AudioStreamOut* output = 0;

    do {
        enabledTracks = 0;
        { // scope for the mLock
        
            // locking briefly on the secondary mOutputLock is necessary to avoid
            // having this thread starve the thread that called setOutput()
            mOutputLock.lock();
            mOutputLock.unlock();

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

            // get active mixer and output parameter while the lock is held and keep them
            // consistent till the next loop.
            
            mixer = audioMixer();
            frameCount = mFrameCount;
            channelCount = mChannelCount;
            sampleRate = mSampleRate;
            output = mOutput;
            
            // find out which tracks need to be processed
            size_t count = activeTracks.size();
            for (size_t i=0 ; i<count ; i++) {
                sp<Track> t = activeTracks[i].promote();
                if (t == 0) continue;

                Track* const track = t.get();
                audio_track_cblk_t* cblk = track->cblk();

                // The first time a track is added we wait
                // for all its buffers to be filled before processing it
                mixer->setActiveTrack(track->name());
                if (cblk->framesReady() && (track->isReady() || track->isStopped()) &&
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
                    mixer->setBufferProvider(track);
                    mixer->enable(AudioMixer::MIXING);

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
                    mixer->setParameter(param, AudioMixer::VOLUME0, left);
                    mixer->setParameter(param, AudioMixer::VOLUME1, right);
                    mixer->setParameter(
                        AudioMixer::TRACK,
                        AudioMixer::FORMAT, track->format());
                    mixer->setParameter(
                        AudioMixer::TRACK,
                        AudioMixer::CHANNEL_COUNT, track->channelCount());
                    mixer->setParameter(
                        AudioMixer::RESAMPLE,
                        AudioMixer::SAMPLE_RATE,
                        int(cblk->sampleRate));

                    // reset retry count
                    track->mRetryCount = kMaxTrackRetries;
                    enabledTracks++;
                } else {
                    //LOGD("u=%08x, s=%08x [NOT READY]", u, s);
                    if (track->isStopped()) {
                        track->reset();
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
                    mixer->disable(AudioMixer::MIXING);
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
                        mixer->deleteTrackName(track->mName);
                    }
                }
            }  
       }
        if (LIKELY(enabledTracks)) {
            // mix buffers...
            mixer->process(curBuf);

            // output audio to hardware
            mLastWriteTime = systemTime();
            mInWrite = true;
            size_t mixBufferSize = frameCount*channelCount*sizeof(int16_t);
            output->write(curBuf, mixBufferSize);
            mNumWrites++;
            mInWrite = false;
            mStandby = false;
            nsecs_t temp = systemTime();
            standbyTime = temp + kStandbyTimeInNsecs;
            nsecs_t delta = temp - mLastWriteTime;
            nsecs_t maxPeriod = seconds(frameCount) / sampleRate * 2;
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
        int frameCount,
        uint32_t flags,
        const sp<IMemory>& sharedBuffer,
        status_t *status)
{
    sp<Track> track;
    sp<TrackHandle> trackHandle;
    sp<Client> client;
    wp<Client> wclient;
    status_t lStatus;

    if (streamType >= AudioTrack::NUM_STREAM_TYPES) {
        LOGE("invalid stream type");
        lStatus = BAD_VALUE;
        goto Exit;
    }

    // Resampler implementation limits input sampling rate to 2 x output sampling rate.
    if (sampleRate > MAX_SAMPLE_RATE || sampleRate > mSampleRate*2) {
        LOGE("Sample rate out of range: %d", sampleRate);
        lStatus = BAD_VALUE;
        goto Exit;
    }

    {
        Mutex::Autolock _l(mLock);

        if (mSampleRate == 0) {
            LOGE("Audio driver not initialized.");
            lStatus = NO_INIT;
            goto Exit;
        }

        wclient = mClients.valueFor(pid);

        if (wclient != NULL) {
            client = wclient.promote();
        } else {
            client = new Client(this, pid);
            mClients.add(pid, client);
        }

        track = new Track(this, client, streamType, sampleRate, format,
                channelCount, frameCount, sharedBuffer);
        mTracks.add(track);
        trackHandle = new TrackHandle(track);

        lStatus = NO_ERROR;
    }

Exit:
    if(status) {
        *status = lStatus;
    }
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

uint32_t AudioFlinger::latency() const
{
    if (mOutput) {
        return mOutput->latency();
    }
    else {
        return 0;
    }
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

#ifdef WITH_A2DP
    LOGD("setRouting %d %d %d\n", mode, routes, mask);
    if (mode == AudioSystem::MODE_NORMAL && 
            (mask & AudioSystem::ROUTE_BLUETOOTH_A2DP)) {
        if (routes & AudioSystem::ROUTE_BLUETOOTH_A2DP) {
            LOGD("set output to A2DP\n");
            setOutput(mA2dpOutput);
        } else {
            LOGD("set output to hardware audio\n");
            setOutput(mHardwareOutput);
        }
        LOGD("setOutput done\n");
    }
#endif

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
        track->mResetDone = false;
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
    audioMixer()->deleteTrackName(name);
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
        audioMixer()->deleteTrackName(keep->name());
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
            int frameCount,
            const sp<IMemory>& sharedBuffer)
    :   RefBase(),
        mAudioFlinger(audioFlinger),
        mClient(client),
        mStreamType(streamType),
        mFrameCount(0),
        mState(IDLE),
        mClientTid(-1),
        mFormat(format),
        mFlags(0)
{
    mName = audioFlinger->audioMixer()->getTrackName();
    if (mName < 0) {
        LOGE("no more track names availlable");
        return;
    }

    LOGV_IF(sharedBuffer != 0, "sharedBuffer: %p, size: %d", sharedBuffer->pointer(), sharedBuffer->size());


    // LOGD("Creating track with %d buffers @ %d bytes", bufferCount, bufferSize);
   size_t size = sizeof(audio_track_cblk_t);
   size_t bufferSize = frameCount*channelCount*sizeof(int16_t);
   if (sharedBuffer == 0) {
       size += bufferSize;
   }

    mCblkMemory = client->heap()->allocate(size);
    if (mCblkMemory != 0) {
        mCblk = static_cast<audio_track_cblk_t *>(mCblkMemory->pointer());
        if (mCblk) { // construct the shared structure in-place.
            new(mCblk) audio_track_cblk_t();
            // clear all buffers
            mCblk->frameCount = frameCount;
            mCblk->sampleRate = sampleRate;
            mCblk->channels = channelCount;
            if (sharedBuffer == 0) {
                mBuffer = (char*)mCblk + sizeof(audio_track_cblk_t);
                memset(mBuffer, 0, frameCount*channelCount*sizeof(int16_t));
                // Force underrun condition to avoid false underrun callback until first data is
                // written to buffer
                mCblk->flowControlFlag = 1;
            } else {
                mBuffer = sharedBuffer->pointer();
            }
            mBufferEnd = (uint8_t *)mBuffer + bufferSize;
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
    mFrameCount = buffer->frameCount;
    step();
    buffer->frameCount = 0;
}

bool AudioFlinger::TrackBase::step() {
    bool result;
    audio_track_cblk_t* cblk = this->cblk();

    result = cblk->stepServer(mFrameCount);
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
    cblk->userBase = 0;
    cblk->serverBase = 0;
    mFlags = 0;
    LOGV("TrackBase::reset");
}

sp<IMemory> AudioFlinger::TrackBase::getCblk() const
{
    return mCblkMemory;
}

int AudioFlinger::TrackBase::sampleRate() const {
    return mCblk->sampleRate;
}

int AudioFlinger::TrackBase::channelCount() const {
    return mCblk->channels;
}

void* AudioFlinger::TrackBase::getBuffer(uint32_t offset, uint32_t frames) const {
    audio_track_cblk_t* cblk = this->cblk();
    int16_t *bufferStart = (int16_t *)mBuffer + (offset-cblk->serverBase)*cblk->channels;
    int16_t *bufferEnd = bufferStart + frames * cblk->channels;

    // Check validity of returned pointer in case the track control block would have been corrupted.
    if (bufferStart < mBuffer || bufferStart > bufferEnd || bufferEnd > mBufferEnd) {
        LOGW("TrackBase::getBuffer buffer out of range:\n    start: %p, end %p , mBuffer %p mBufferEnd %p\n    \
                server %d, serverBase %d, user %d, userBase %d",
                bufferStart, bufferEnd, mBuffer, mBufferEnd,
                cblk->server, cblk->serverBase, cblk->user, cblk->userBase);
        return 0;
    }

    return bufferStart;
}

// ----------------------------------------------------------------------------

AudioFlinger::Track::Track(
            const sp<AudioFlinger>& audioFlinger,
            const sp<Client>& client,
            int streamType,
            uint32_t sampleRate,
            int format,
            int channelCount,
            int frameCount,
            const sp<IMemory>& sharedBuffer)
    :   TrackBase(audioFlinger, client, streamType, sampleRate, format, channelCount, frameCount, sharedBuffer)
{
    mVolume[0] = 1.0f;
    mVolume[1] = 1.0f;
    mMute = false;
    mSharedBuffer = sharedBuffer;
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
            mCblk->channels,
            mFrameCount,
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
     uint32_t framesReady;
     uint32_t framesReq = buffer->frameCount;

     // Check if last stepServer failed, try to step now
     if (mFlags & TrackBase::STEPSERVER_FAILED) {
         if (!step())  goto getNextBuffer_exit;
         LOGV("stepServer recovered");
         mFlags &= ~TrackBase::STEPSERVER_FAILED;
     }

     framesReady = cblk->framesReady();

     if (LIKELY(framesReady)) {
        uint32_t s = cblk->server;
        uint32_t bufferEnd = cblk->serverBase + cblk->frameCount;

        bufferEnd = (cblk->loopEnd < bufferEnd) ? cblk->loopEnd : bufferEnd;
        if (framesReq > framesReady) {
            framesReq = framesReady;
        }
        if (s + framesReq > bufferEnd) {
            framesReq = bufferEnd - s;
        }

         buffer->raw = getBuffer(s, framesReq);
         if (buffer->raw == 0) goto getNextBuffer_exit;

         buffer->frameCount = framesReq;
        return NO_ERROR;
     }

getNextBuffer_exit:
     buffer->raw = 0;
     buffer->frameCount = 0;
     return NOT_ENOUGH_DATA;
}

bool AudioFlinger::Track::isReady() const {
    if (mFillingUpStatus != FS_FILLING) return true;

    if (mCblk->framesReady() >= mCblk->frameCount ||
        mCblk->forceReady) {
        mFillingUpStatus = FS_FILLED;
        mCblk->forceReady = 0;
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
    // Do not reset twice to avoid discarding data written just after a flush and before
    // the audioflinger thread detects the track is stopped.
    if (!mResetDone) {
        TrackBase::reset();
        // Force underrun condition to avoid false underrun callback until first data is
        // written to buffer
        mCblk->flowControlFlag = 1;
        mCblk->forceReady = 0;
        mFillingUpStatus = FS_FILLING;        
        mResetDone = true;
    }
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

sp<IAudioRecord> AudioFlinger::openRecord(
        pid_t pid,
        int streamType,
        uint32_t sampleRate,
        int format,
        int channelCount,
        int frameCount,
        uint32_t flags,
        status_t *status)
{
    sp<AudioRecordThread> thread;
    sp<RecordTrack> recordTrack;
    sp<RecordHandle> recordHandle;
    sp<Client> client;
    wp<Client> wclient;
    AudioStreamIn* input = 0;
    int inFrameCount;
    size_t inputBufferSize;
    status_t lStatus;

    // check calling permissions
    if (!recordingAllowed()) {
        lStatus = PERMISSION_DENIED;
        goto Exit;
    }

    if (uint32_t(streamType) >= AudioRecord::NUM_STREAM_TYPES) {
        LOGE("invalid stream type");
        lStatus = BAD_VALUE;
        goto Exit;
    }

    if (sampleRate > MAX_SAMPLE_RATE) {
        LOGE("Sample rate out of range");
        lStatus = BAD_VALUE;
        goto Exit;
    }

    if (mSampleRate == 0) {
        LOGE("Audio driver not initialized");
        lStatus = NO_INIT;
        goto Exit;
    }

    if (mAudioRecordThread == 0) {
        LOGE("Audio record thread not started");
        lStatus = NO_INIT;
        goto Exit;
    }


    // Check that audio input stream accepts requested audio parameters 
    inputBufferSize = mAudioHardware->getInputBufferSize(sampleRate, format, channelCount);
    if (inputBufferSize == 0) {
        lStatus = BAD_VALUE;
        LOGE("Bad audio input parameters: sampling rate %u, format %d, channels %d",  sampleRate, format, channelCount);
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

    // frameCount must be a multiple of input buffer size
    inFrameCount = inputBufferSize/channelCount/sizeof(short);
    frameCount = ((frameCount - 1)/inFrameCount + 1) * inFrameCount;

    // create new record track and pass to record thread
    recordTrack = new RecordTrack(this, client, streamType, sampleRate,
            format, channelCount, frameCount);

    // return to handle to client
    recordHandle = new RecordHandle(recordTrack);
    lStatus = NO_ERROR;

Exit:
    if (status) {
        *status = lStatus;
    }
    return recordHandle;
}

status_t AudioFlinger::startRecord(RecordTrack* recordTrack) {
    if (mAudioRecordThread != 0) {
        return mAudioRecordThread->start(recordTrack);        
    }
    return NO_INIT;
}

void AudioFlinger::stopRecord(RecordTrack* recordTrack) {
    if (mAudioRecordThread != 0) {
        mAudioRecordThread->stop(recordTrack);
    }
}


// ----------------------------------------------------------------------------

AudioFlinger::RecordTrack::RecordTrack(
            const sp<AudioFlinger>& audioFlinger,
            const sp<Client>& client,
            int streamType,
            uint32_t sampleRate,
            int format,
            int channelCount,
            int frameCount)
    :   TrackBase(audioFlinger, client, streamType, sampleRate, format,
            channelCount, frameCount, 0),
            mOverflow(false)
{
}

AudioFlinger::RecordTrack::~RecordTrack()
{
    mAudioFlinger->audioMixer()->deleteTrackName(mName);
}

status_t AudioFlinger::RecordTrack::getNextBuffer(AudioBufferProvider::Buffer* buffer)
{
    audio_track_cblk_t* cblk = this->cblk();
    uint32_t framesAvail;
    uint32_t framesReq = buffer->frameCount;

     // Check if last stepServer failed, try to step now
    if (mFlags & TrackBase::STEPSERVER_FAILED) {
        if (!step()) goto getNextBuffer_exit;
        LOGV("stepServer recovered");
        mFlags &= ~TrackBase::STEPSERVER_FAILED;
    }

    framesAvail = cblk->framesAvailable_l();

    if (LIKELY(framesAvail)) {
        uint32_t s = cblk->server;
        uint32_t bufferEnd = cblk->serverBase + cblk->frameCount;

        if (framesReq > framesAvail) {
            framesReq = framesAvail;
        }
        if (s + framesReq > bufferEnd) {
            framesReq = bufferEnd - s;
        }

        buffer->raw = getBuffer(s, framesReq);
        if (buffer->raw == 0) goto getNextBuffer_exit;

        buffer->frameCount = framesReq;
        return NO_ERROR;
    }

getNextBuffer_exit:
    buffer->raw = 0;
    buffer->frameCount = 0;
    return NOT_ENOUGH_DATA;
}

status_t AudioFlinger::RecordTrack::start()
{
    return mAudioFlinger->startRecord(this);
}

void AudioFlinger::RecordTrack::stop()
{
    mAudioFlinger->stopRecord(this);
    TrackBase::reset();
    // Force overerrun condition to avoid false overrun callback until first data is
    // read from buffer
    mCblk->flowControlFlag = 1;
}

// ----------------------------------------------------------------------------

AudioFlinger::RecordHandle::RecordHandle(const sp<AudioFlinger::RecordTrack>& recordTrack)
    : BnAudioRecord(),
    mRecordTrack(recordTrack)
{
}

AudioFlinger::RecordHandle::~RecordHandle() {
    stop();
}

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

AudioFlinger::AudioRecordThread::AudioRecordThread(AudioHardwareInterface* audioHardware) :
    mAudioHardware(audioHardware),
    mActive(false)
{
}

AudioFlinger::AudioRecordThread::~AudioRecordThread()
{
}

bool AudioFlinger::AudioRecordThread::threadLoop()
{
    LOGV("AudioRecordThread: start record loop");
    AudioBufferProvider::Buffer buffer;
    int inBufferSize = 0;
    int inFrameCount = 0;
    AudioStreamIn* input = 0;

    mActive = 0;
    
    // start recording
    while (!exitPending()) {
        if (!mActive) {
            mLock.lock();
            if (!mActive && !exitPending()) {
                LOGV("AudioRecordThread: loop stopping");
                if (input) {
                    delete input;
                    input = 0;
                }
                mRecordTrack.clear();

                mWaitWorkCV.wait(mLock);
               
                LOGV("AudioRecordThread: loop starting");
                if (mRecordTrack != 0) {
                    input = mAudioHardware->openInputStream(mRecordTrack->format(), 
                                            mRecordTrack->channelCount(), 
                                            mRecordTrack->sampleRate(), 
                                            &mStartStatus);
                    if (input != 0) {
                        inBufferSize = input->bufferSize();
                        inFrameCount = inBufferSize/input->frameSize();                        
                    }
                } else {
                    mStartStatus = NO_INIT;
                }
                if (mStartStatus !=NO_ERROR) {
                    LOGW("record start failed, status %d", mStartStatus);
                    mActive = false;
                    mRecordTrack.clear();                    
                }
                mWaitWorkCV.signal();
            }
            mLock.unlock();
        } else if (mRecordTrack != 0){

            buffer.frameCount = inFrameCount;
            if (LIKELY(mRecordTrack->getNextBuffer(&buffer) == NO_ERROR)) {
                LOGV("AudioRecordThread read: %d frames", buffer.frameCount);
                if (input->read(buffer.raw, inBufferSize) < 0) {
                    LOGE("Error reading audio input");
                    sleep(1);
                }
                mRecordTrack->releaseBuffer(&buffer);
                mRecordTrack->overflow();
            }

            // client isn't retrieving buffers fast enough
            else {
                if (!mRecordTrack->setOverflow())
                    LOGW("AudioRecordThread: buffer overflow");
                // Release the processor for a while before asking for a new buffer.
                // This will give the application more chance to read from the buffer and
                // clear the overflow.
                usleep(5000);
            }
        }
    }


    if (input) {
        delete input;
    }
    mRecordTrack.clear();
    
    return false;
}

status_t AudioFlinger::AudioRecordThread::start(RecordTrack* recordTrack)
{
    LOGV("AudioRecordThread::start");
    AutoMutex lock(&mLock);
    mActive = true;
    // If starting the active track, just reset mActive in case a stop
    // was pending and exit
    if (recordTrack == mRecordTrack.get()) return NO_ERROR;

    if (mRecordTrack != 0) return -EBUSY;

    mRecordTrack = recordTrack;

    // signal thread to start
    LOGV("Signal record thread");
    mWaitWorkCV.signal();
    mWaitWorkCV.wait(mLock);
    LOGV("Record started, status %d", mStartStatus);
    return mStartStatus;
}

void AudioFlinger::AudioRecordThread::stop(RecordTrack* recordTrack) {
    LOGV("AudioRecordThread::stop");
    AutoMutex lock(&mLock);
    if (mActive && (recordTrack == mRecordTrack.get())) {
        mActive = false;
    }
}

void AudioFlinger::AudioRecordThread::exit()
{
    LOGV("AudioRecordThread::exit");
    {
        AutoMutex lock(&mLock);
        requestExit();
        mWaitWorkCV.signal();
    }
    requestExitAndWait();
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
