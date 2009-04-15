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

#include <hardware_legacy/AudioHardwareInterface.h>

#include "AudioMixer.h"
#include "AudioFlinger.h"

#ifdef WITH_A2DP
#include "A2dpAudioInterface.h"
#endif

// ----------------------------------------------------------------------------
// the sim build doesn't have gettid

#ifndef HAVE_GETTID
# define gettid getpid
#endif

// ----------------------------------------------------------------------------

namespace android {

static const char* kDeadlockedString = "AudioFlinger may be deadlocked\n";
static const char* kHardwareLockedString = "Hardware lock is taken\n";

//static const nsecs_t kStandbyTimeInNsecs = seconds(3);
static const unsigned long kBufferRecoveryInUsecs = 2000;
static const unsigned long kMaxBufferRecoveryInUsecs = 20000;
static const float MAX_GAIN = 4096.0f;

// retry counts for buffer fill timeout
// 50 * ~20msecs = 1 second
static const int8_t kMaxTrackRetries = 50;
static const int8_t kMaxTrackStartupRetries = 50;

static const int kStartSleepTime = 30000;
static const int kStopSleepTime = 30000;

static const int kDumpLockRetries = 50;
static const int kDumpLockSleep = 20000;

// Maximum number of pending buffers allocated by OutputTrack::write()
static const uint8_t kMaxOutputTrackBuffers = 5;


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
    : BnAudioFlinger(),
        mAudioHardware(0), mA2dpAudioInterface(0), mA2dpEnabled(false), mNotifyA2dpChange(false),
        mForcedSpeakerCount(0), mA2dpDisableCount(0), mA2dpSuppressed(false), mForcedRoute(0),
        mRouteRestoreTime(0), mMusicMuteSaved(false)
{
    mHardwareStatus = AUDIO_HW_IDLE;
    mAudioHardware = AudioHardwareInterface::create();
    mHardwareStatus = AUDIO_HW_INIT;
    if (mAudioHardware->initCheck() == NO_ERROR) {
        // open 16-bit output stream for s/w mixer
        mHardwareStatus = AUDIO_HW_OUTPUT_OPEN;
        status_t status;
        AudioStreamOut *hwOutput = mAudioHardware->openOutputStream(AudioSystem::PCM_16_BIT, 0, 0, &status);
        mHardwareStatus = AUDIO_HW_IDLE;
        if (hwOutput) {
            mHardwareMixerThread = new MixerThread(this, hwOutput, AudioSystem::AUDIO_OUTPUT_HARDWARE);
        } else {
            LOGE("Failed to initialize hardware output stream, status: %d", status);
        }
        
#ifdef WITH_A2DP
        // Create A2DP interface
        mA2dpAudioInterface = new A2dpAudioInterface();
        AudioStreamOut *a2dpOutput = mA2dpAudioInterface->openOutputStream(AudioSystem::PCM_16_BIT, 0, 0, &status);
        if (a2dpOutput) {
            mA2dpMixerThread = new MixerThread(this, a2dpOutput, AudioSystem::AUDIO_OUTPUT_A2DP);
            if (hwOutput) {  
                uint32_t frameCount = ((a2dpOutput->bufferSize()/a2dpOutput->frameSize()) * hwOutput->sampleRate()) / a2dpOutput->sampleRate();
                MixerThread::OutputTrack *a2dpOutTrack = new MixerThread::OutputTrack(mA2dpMixerThread,
                                                            hwOutput->sampleRate(),
                                                            AudioSystem::PCM_16_BIT,
                                                            hwOutput->channelCount(),
                                                            frameCount);
                mHardwareMixerThread->setOuputTrack(a2dpOutTrack);                
            }
        } else {
            LOGE("Failed to initialize A2DP output stream, status: %d", status);
        }
#endif
 
        // FIXME - this should come from settings
        setRouting(AudioSystem::MODE_NORMAL, AudioSystem::ROUTE_SPEAKER, AudioSystem::ROUTE_ALL);
        setRouting(AudioSystem::MODE_RINGTONE, AudioSystem::ROUTE_SPEAKER, AudioSystem::ROUTE_ALL);
        setRouting(AudioSystem::MODE_IN_CALL, AudioSystem::ROUTE_EARPIECE, AudioSystem::ROUTE_ALL);
        setMode(AudioSystem::MODE_NORMAL);

        setMasterVolume(1.0f);
        setMasterMute(false);

        // Start record thread
        mAudioRecordThread = new AudioRecordThread(mAudioHardware, this);
        if (mAudioRecordThread != 0) {
            mAudioRecordThread->run("AudioRecordThread", PRIORITY_URGENT_AUDIO);            
        }
     } else {
        LOGE("Couldn't even initialize the stubbed audio hardware!");
    }
}

AudioFlinger::~AudioFlinger()
{
    if (mAudioRecordThread != 0) {
        mAudioRecordThread->exit();
        mAudioRecordThread.clear();        
    }
    mHardwareMixerThread.clear();
    delete mAudioHardware;
    // deleting mA2dpAudioInterface also deletes mA2dpOutput;
#ifdef WITH_A2DP
    mA2dpMixerThread.clear();
    delete mA2dpAudioInterface;
#endif
}


#ifdef WITH_A2DP
// setA2dpEnabled_l() must be called with AudioFlinger::mLock held
void AudioFlinger::setA2dpEnabled_l(bool enable)
{    
    SortedVector < sp<MixerThread::Track> > tracks;
    SortedVector < wp<MixerThread::Track> > activeTracks;
    
    LOGV_IF(enable, "set output to A2DP\n");
    LOGV_IF(!enable, "set output to hardware audio\n");

    // Transfer tracks playing on MUSIC stream from one mixer to the other
    if (enable) {
        mHardwareMixerThread->getTracks_l(tracks, activeTracks);
        mA2dpMixerThread->putTracks_l(tracks, activeTracks);
    } else {
        mA2dpMixerThread->getTracks_l(tracks, activeTracks);
        mHardwareMixerThread->putTracks_l(tracks, activeTracks);
    }
    mA2dpEnabled = enable;
    mNotifyA2dpChange = true;
    mWaitWorkCV.broadcast();
}

// checkA2dpEnabledChange_l() must be called with AudioFlinger::mLock held
void AudioFlinger::checkA2dpEnabledChange_l()
{
    if (mNotifyA2dpChange) {
        // Notify AudioSystem of the A2DP activation/deactivation
        size_t size = mNotificationClients.size();
        for (size_t i = 0; i < size; i++) {
            sp<IBinder> binder = mNotificationClients.itemAt(i).promote();
            if (binder != NULL) {
                LOGV("Notifying output change to client %p", binder.get());
                sp<IAudioFlingerClient> client = interface_cast<IAudioFlingerClient> (binder);
                client->a2dpEnabledChanged(mA2dpEnabled);
            }
        }
        mNotifyA2dpChange = false;
    }
}
#endif // WITH_A2DP

bool AudioFlinger::streamForcedToSpeaker(int streamType)
{
    // NOTE that streams listed here must not be routed to A2DP by default:
    // AudioSystem::routedToA2dpOutput(streamType) == false
    return (streamType == AudioSystem::RING ||
            streamType == AudioSystem::ALARM ||
            streamType == AudioSystem::NOTIFICATION ||
            streamType == AudioSystem::ENFORCED_AUDIBLE);
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


status_t AudioFlinger::dumpInternals(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    int hardwareStatus = mHardwareStatus;
    
    if (hardwareStatus == AUDIO_HW_IDLE && mHardwareMixerThread->mStandby) {
        hardwareStatus = AUDIO_HW_STANDBY;
    }
    snprintf(buffer, SIZE, "Hardware status: %d\n", hardwareStatus);
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

static bool tryLock(Mutex& mutex)
{
    bool locked = false;
    for (int i = 0; i < kDumpLockRetries; ++i) {
        if (mutex.tryLock() == NO_ERROR) {
            locked = true;
            break;
        }
        usleep(kDumpLockSleep);
    }
    return locked;
}

status_t AudioFlinger::dump(int fd, const Vector<String16>& args)
{
    if (checkCallingPermission(String16("android.permission.DUMP")) == false) {
        dumpPermissionDenial(fd, args);
    } else {
        // get state of hardware lock
        bool hardwareLocked = tryLock(mHardwareLock);
        if (!hardwareLocked) {
            String8 result(kHardwareLockedString);
            write(fd, result.string(), result.size());
        } else {
            mHardwareLock.unlock();
        }

        bool locked = tryLock(mLock);

        // failed to lock - AudioFlinger is probably deadlocked
        if (!locked) {
            String8 result(kDeadlockedString);
            write(fd, result.string(), result.size());
        }

        dumpClients(fd, args);
        dumpInternals(fd, args);
        mHardwareMixerThread->dump(fd, args);
#ifdef WITH_A2DP
        mA2dpMixerThread->dump(fd, args);
#endif

        // dump record client
        if (mAudioRecordThread != 0) mAudioRecordThread->dump(fd, args);

        if (mAudioHardware) {
            mAudioHardware->dumpState(fd, args);
        }
        if (locked) mLock.unlock();
    }
    return NO_ERROR;
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
    sp<MixerThread::Track> track;
    sp<TrackHandle> trackHandle;
    sp<Client> client;
    wp<Client> wclient;
    status_t lStatus;

    if (streamType >= AudioSystem::NUM_STREAM_TYPES) {
        LOGE("invalid stream type");
        lStatus = BAD_VALUE;
        goto Exit;
    }

    {
        Mutex::Autolock _l(mLock);

        wclient = mClients.valueFor(pid);

        if (wclient != NULL) {
            client = wclient.promote();
        } else {
            client = new Client(this, pid);
            mClients.add(pid, client);
        }
#ifdef WITH_A2DP
        if (isA2dpEnabled() && AudioSystem::routedToA2dpOutput(streamType)) {
            track = mA2dpMixerThread->createTrack_l(client, streamType, sampleRate, format,
                    channelCount, frameCount, sharedBuffer, &lStatus);            
        } else 
#endif
        {
            track = mHardwareMixerThread->createTrack_l(client, streamType, sampleRate, format,
                    channelCount, frameCount, sharedBuffer, &lStatus);            
        }
    }
    if (lStatus == NO_ERROR) {
        trackHandle = new TrackHandle(track);
    } else {
        track.clear();
    }

Exit:
    if(status) {
        *status = lStatus;
    }
    return trackHandle;
}

uint32_t AudioFlinger::sampleRate(int output) const
{
#ifdef WITH_A2DP
     if (output == AudioSystem::AUDIO_OUTPUT_A2DP) {
         return mA2dpMixerThread->sampleRate();
     }
#endif
     return mHardwareMixerThread->sampleRate();
}

int AudioFlinger::channelCount(int output) const
{
#ifdef WITH_A2DP
     if (output == AudioSystem::AUDIO_OUTPUT_A2DP) {
         return mA2dpMixerThread->channelCount();
     }
#endif
     return mHardwareMixerThread->channelCount();
}

int AudioFlinger::format(int output) const
{
#ifdef WITH_A2DP
     if (output == AudioSystem::AUDIO_OUTPUT_A2DP) {
         return mA2dpMixerThread->format();
     }
#endif
     return mHardwareMixerThread->format();
}

size_t AudioFlinger::frameCount(int output) const
{
#ifdef WITH_A2DP
     if (output == AudioSystem::AUDIO_OUTPUT_A2DP) {
         return mA2dpMixerThread->frameCount();
     }
#endif
     return mHardwareMixerThread->frameCount();
}

uint32_t AudioFlinger::latency(int output) const
{
#ifdef WITH_A2DP
     if (output == AudioSystem::AUDIO_OUTPUT_A2DP) {
         return mA2dpMixerThread->latency();
     }
#endif
     return mHardwareMixerThread->latency();
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
        value = 1.0f;
    }
    mHardwareStatus = AUDIO_HW_IDLE;
    mHardwareMixerThread->setMasterVolume(value);
#ifdef WITH_A2DP
    mA2dpMixerThread->setMasterVolume(value);
#endif

    return NO_ERROR;
}

status_t AudioFlinger::setRouting(int mode, uint32_t routes, uint32_t mask)
{
    status_t err = NO_ERROR;

    // check calling permissions
    if (!settingsAllowed()) {
        return PERMISSION_DENIED;
    }
    if ((mode < AudioSystem::MODE_CURRENT) || (mode >= AudioSystem::NUM_MODES)) {
        LOGW("Illegal value: setRouting(%d, %u, %u)", mode, routes, mask);
        return BAD_VALUE;
    }

#ifdef WITH_A2DP
    LOGD("setRouting %d %d %d, tid %d, calling tid %d\n", mode, routes, mask, gettid(), IPCThreadState::self()->getCallingPid());
    if (mode == AudioSystem::MODE_NORMAL && 
            (mask & AudioSystem::ROUTE_BLUETOOTH_A2DP)) {
        AutoMutex lock(&mLock);

        bool enableA2dp = false;
        if (routes & AudioSystem::ROUTE_BLUETOOTH_A2DP) {
            enableA2dp = true;
        }
        if (mA2dpDisableCount > 0) {
            mA2dpSuppressed = enableA2dp;
        } else {
            setA2dpEnabled_l(enableA2dp);
        }
        LOGV("setOutput done\n");
    }
    // setRouting() is always called at least for mode == AudioSystem::MODE_IN_CALL when 
    // SCO is enabled, whatever current mode is so we can safely handle A2DP disabling only
    // in this case to avoid doing it several times.
    if (mode == AudioSystem::MODE_IN_CALL &&
        (mask & AudioSystem::ROUTE_BLUETOOTH_SCO)) {
        AutoMutex lock(&mLock);
        handleRouteDisablesA2dp_l(routes);
    }
#endif

    // do nothing if only A2DP routing is affected
    mask &= ~AudioSystem::ROUTE_BLUETOOTH_A2DP;
    if (mask) {
        AutoMutex lock(mHardwareLock);
        mHardwareStatus = AUDIO_HW_GET_ROUTING;
        uint32_t r;
        err = mAudioHardware->getRouting(mode, &r);
        if (err == NO_ERROR) {
            r = (r & ~mask) | (routes & mask);
            if (mode == AudioSystem::MODE_NORMAL || 
                (mode == AudioSystem::MODE_CURRENT && getMode() == AudioSystem::MODE_NORMAL)) {
                mSavedRoute = r;
                r |= mForcedRoute;
                LOGV("setRouting mSavedRoute %08x mForcedRoute %08x\n", mSavedRoute, mForcedRoute);
            }
            mHardwareStatus = AUDIO_HW_SET_ROUTING;
            err = mAudioHardware->setRouting(mode, r);
        }
        mHardwareStatus = AUDIO_HW_IDLE;
    }
    return err;
}

uint32_t AudioFlinger::getRouting(int mode) const
{
    uint32_t routes = 0;
    if ((mode >= AudioSystem::MODE_CURRENT) && (mode < AudioSystem::NUM_MODES)) {
        if (mode == AudioSystem::MODE_NORMAL || 
            (mode == AudioSystem::MODE_CURRENT && getMode() == AudioSystem::MODE_NORMAL)) {
            routes = mSavedRoute;                
        } else {
            mHardwareStatus = AUDIO_HW_GET_ROUTING;
            mAudioHardware->getRouting(mode, &routes);
            mHardwareStatus = AUDIO_HW_IDLE;
        }
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
    mHardwareMixerThread->setMasterMute(muted);
#ifdef WITH_A2DP
    mA2dpMixerThread->setMasterMute(muted);
#endif
    return NO_ERROR;
}

float AudioFlinger::masterVolume() const
{
    return mHardwareMixerThread->masterVolume();
}

bool AudioFlinger::masterMute() const
{
    return mHardwareMixerThread->masterMute();
}

status_t AudioFlinger::setStreamVolume(int stream, float value)
{
    // check calling permissions
    if (!settingsAllowed()) {
        return PERMISSION_DENIED;
    }

    if (uint32_t(stream) >= AudioSystem::NUM_STREAM_TYPES ||
        uint32_t(stream) == AudioSystem::ENFORCED_AUDIBLE) {
        return BAD_VALUE;
    }

    status_t ret = NO_ERROR;
    
    if (stream == AudioSystem::VOICE_CALL ||
        stream == AudioSystem::BLUETOOTH_SCO) {
        float hwValue = value;
        if (stream == AudioSystem::VOICE_CALL) {
            hwValue = (float)AudioSystem::logToLinear(value)/100.0f;
            // FIXME: This is a temporary fix to re-base the internally
            // generated in-call audio so that it is never muted, which is
            // already the case for the hardware routed in-call audio.
            // When audio stream handling is reworked, this should be
            // addressed more cleanly.  Fixes #1324; see discussion at
            // http://review.source.android.com/8224
            value = value * 0.99 + 0.01;        
        } else { // (type == AudioSystem::BLUETOOTH_SCO)
            hwValue = 1.0f;
        }

        AutoMutex lock(mHardwareLock);
        mHardwareStatus = AUDIO_SET_VOICE_VOLUME;
        ret = mAudioHardware->setVoiceVolume(hwValue);
        mHardwareStatus = AUDIO_HW_IDLE;
        
    }
    
    mHardwareMixerThread->setStreamVolume(stream, value);
#ifdef WITH_A2DP
    mA2dpMixerThread->setStreamVolume(stream, value);
#endif

    return ret;
}

status_t AudioFlinger::setStreamMute(int stream, bool muted)
{
    // check calling permissions
    if (!settingsAllowed()) {
        return PERMISSION_DENIED;
    }

    if (uint32_t(stream) >= AudioSystem::NUM_STREAM_TYPES ||
        uint32_t(stream) == AudioSystem::ENFORCED_AUDIBLE) {
        return BAD_VALUE;
    }

#ifdef WITH_A2DP
    mA2dpMixerThread->setStreamMute(stream, muted);
#endif
    if (stream == AudioSystem::MUSIC) 
    {
        AutoMutex lock(&mHardwareLock);
        if (mForcedRoute != 0)
            mMusicMuteSaved = muted;
        else
            mHardwareMixerThread->setStreamMute(stream, muted);
    } else {
        mHardwareMixerThread->setStreamMute(stream, muted);
    }

    return NO_ERROR;
}

float AudioFlinger::streamVolume(int stream) const
{
    if (uint32_t(stream) >= AudioSystem::NUM_STREAM_TYPES) {
        return 0.0f;
    }
    float value = mHardwareMixerThread->streamVolume(stream);
    
    if (stream == AudioSystem::VOICE_CALL) {
        // FIXME: Re-base internally generated in-call audio,
        // reverse of above in setStreamVolume.
        value = (value - 0.01) / 0.99;
    }
    
    return value;
}

bool AudioFlinger::streamMute(int stream) const
{
    if (uint32_t(stream) >= AudioSystem::NUM_STREAM_TYPES) {
        return true;
    }
    
    if (stream == AudioSystem::MUSIC && mForcedRoute != 0) 
    {
        return mMusicMuteSaved;
    }
    return mHardwareMixerThread->streamMute(stream);
}

bool AudioFlinger::isMusicActive() const
{
 #ifdef WITH_A2DP
     if (isA2dpEnabled()) {
         return mA2dpMixerThread->isMusicActive();
     }
 #endif
    return mHardwareMixerThread->isMusicActive();
}

status_t AudioFlinger::setParameter(const char* key, const char* value)
{
    status_t result, result2;
    AutoMutex lock(mHardwareLock);
    mHardwareStatus = AUDIO_SET_PARAMETER;
    
    LOGV("setParameter() key %s, value %s, tid %d, calling tid %d", key, value, gettid(), IPCThreadState::self()->getCallingPid());
    result = mAudioHardware->setParameter(key, value);
    if (mA2dpAudioInterface) {
        result2 = mA2dpAudioInterface->setParameter(key, value);
        if (result2)
            result = result2;
    }
    mHardwareStatus = AUDIO_HW_IDLE;
    return result;
}

size_t AudioFlinger::getInputBufferSize(uint32_t sampleRate, int format, int channelCount)
{
    return mAudioHardware->getInputBufferSize(sampleRate, format, channelCount);
}

void AudioFlinger::registerClient(const sp<IAudioFlingerClient>& client)
{
    
    LOGV("registerClient() %p, tid %d, calling tid %d", client.get(), gettid(), IPCThreadState::self()->getCallingPid());
    Mutex::Autolock _l(mLock);

    sp<IBinder> binder = client->asBinder();
    if (mNotificationClients.indexOf(binder) < 0) {
        LOGV("Adding notification client %p", binder.get());
        binder->linkToDeath(this);
        mNotificationClients.add(binder);
        client->a2dpEnabledChanged(isA2dpEnabled());
    }
}

void AudioFlinger::binderDied(const wp<IBinder>& who) {
    
    LOGV("binderDied() %p, tid %d, calling tid %d", who.unsafe_get(), gettid(), IPCThreadState::self()->getCallingPid());
    Mutex::Autolock _l(mLock);

    IBinder *binder = who.unsafe_get();

    if (binder != NULL) {
        int index = mNotificationClients.indexOf(binder);
        if (index >= 0) {
            LOGV("Removing notification client %p", binder);
            mNotificationClients.removeAt(index);
        }
    }
}

void AudioFlinger::removeClient(pid_t pid)
{
    LOGV("removeClient() pid %d, tid %d, calling tid %d", pid, gettid(), IPCThreadState::self()->getCallingPid());
    Mutex::Autolock _l(mLock);
    mClients.removeItem(pid);
}

bool AudioFlinger::isA2dpEnabled() const
{
    return mA2dpEnabled;
}

void AudioFlinger::handleForcedSpeakerRoute(int command)
{
    switch(command) {
    case ACTIVE_TRACK_ADDED:
        {
            AutoMutex lock(mHardwareLock);
            if (mForcedSpeakerCount++ == 0) {
                mRouteRestoreTime = 0;
                mMusicMuteSaved = mHardwareMixerThread->streamMute(AudioSystem::MUSIC);
                if (mForcedRoute == 0 && !(mSavedRoute & AudioSystem::ROUTE_SPEAKER)) {
                    LOGV("Route forced to Speaker ON %08x", mSavedRoute | AudioSystem::ROUTE_SPEAKER);
                    mHardwareMixerThread->setStreamMute(AudioSystem::MUSIC, true);
                    mHardwareStatus = AUDIO_HW_SET_MASTER_VOLUME;
                    mAudioHardware->setMasterVolume(0);
                    usleep(mHardwareMixerThread->latency()*1000);
                    mHardwareStatus = AUDIO_HW_SET_ROUTING;
                    mAudioHardware->setRouting(AudioSystem::MODE_NORMAL, mSavedRoute | AudioSystem::ROUTE_SPEAKER);
                    mHardwareStatus = AUDIO_HW_IDLE;
                    // delay track start so that audio hardware has time to siwtch routes
                    usleep(kStartSleepTime);
                    mHardwareStatus = AUDIO_HW_SET_MASTER_VOLUME;
                    mAudioHardware->setMasterVolume(mHardwareMixerThread->masterVolume());
                    mHardwareStatus = AUDIO_HW_IDLE;
                }
                mForcedRoute = AudioSystem::ROUTE_SPEAKER;
            }
            LOGV("mForcedSpeakerCount incremented to %d", mForcedSpeakerCount);
        }
        break;
    case ACTIVE_TRACK_REMOVED:
        {
            AutoMutex lock(mHardwareLock);
            if (mForcedSpeakerCount > 0){
                if (--mForcedSpeakerCount == 0) {
                    mRouteRestoreTime = systemTime() + milliseconds(kStopSleepTime/1000);
                }
                LOGV("mForcedSpeakerCount decremented to %d", mForcedSpeakerCount);
            } else {
                LOGE("mForcedSpeakerCount is already zero");
            }
        }
        break;
    case CHECK_ROUTE_RESTORE_TIME:
    case FORCE_ROUTE_RESTORE:
        if (mRouteRestoreTime) {
            AutoMutex lock(mHardwareLock);
            if (mRouteRestoreTime && 
               (systemTime() > mRouteRestoreTime || command == FORCE_ROUTE_RESTORE)) {
                mHardwareMixerThread->setStreamMute(AudioSystem::MUSIC, mMusicMuteSaved);
                mForcedRoute = 0;
                if (!(mSavedRoute & AudioSystem::ROUTE_SPEAKER)) {
                    mHardwareStatus = AUDIO_HW_SET_ROUTING;
                    mAudioHardware->setRouting(AudioSystem::MODE_NORMAL, mSavedRoute);
                    mHardwareStatus = AUDIO_HW_IDLE;
                    LOGV("Route forced to Speaker OFF %08x", mSavedRoute);
                }
                mRouteRestoreTime = 0;
            }
        }
        break;
    }
}

#ifdef WITH_A2DP
// handleRouteDisablesA2dp_l() must be called with AudioFlinger::mLock held
void AudioFlinger::handleRouteDisablesA2dp_l(int routes)
{
   if (routes & AudioSystem::ROUTE_BLUETOOTH_SCO) {
        if (mA2dpDisableCount++ == 0) {
            if (mA2dpEnabled) {
                setA2dpEnabled_l(false);
                mA2dpSuppressed = true;
            }
        }
        LOGV("mA2dpDisableCount incremented to %d", mA2dpDisableCount);
   } else {
        if (mA2dpDisableCount > 0) {
            if (--mA2dpDisableCount == 0) {
                if (mA2dpSuppressed) {
                    setA2dpEnabled_l(true);
                    mA2dpSuppressed = false;
                }
            }
            LOGV("mA2dpDisableCount decremented to %d", mA2dpDisableCount);
        } else {
            LOGE("mA2dpDisableCount is already zero");
        }
    }
}
#endif

// ----------------------------------------------------------------------------

AudioFlinger::MixerThread::MixerThread(const sp<AudioFlinger>& audioFlinger, AudioStreamOut* output, int outputType)
    :   Thread(false),
        mAudioFlinger(audioFlinger), mAudioMixer(0), mOutput(output), mOutputType(outputType), 
        mSampleRate(0), mFrameCount(0), mChannelCount(0), mFormat(0), mMixBuffer(0),
        mLastWriteTime(0), mNumWrites(0), mNumDelayedWrites(0), mStandby(false),
        mInWrite(false)
{
    mSampleRate = output->sampleRate();
    mChannelCount = output->channelCount();

    // FIXME - Current mixer implementation only supports stereo output
    if (mChannelCount == 1) {
        LOGE("Invalid audio hardware channel count");
    }

    mFormat = output->format();
    mFrameCount = output->bufferSize() / output->channelCount() / sizeof(int16_t);
    mAudioMixer = new AudioMixer(mFrameCount, output->sampleRate());

    // FIXME - Current mixer implementation only supports stereo output: Always
    // Allocate a stereo buffer even if HW output is mono.
    mMixBuffer = new int16_t[mFrameCount * 2];
    memset(mMixBuffer, 0, mFrameCount * 2 * sizeof(int16_t));
}

AudioFlinger::MixerThread::~MixerThread()
{
    delete [] mMixBuffer;
    delete mAudioMixer;
}

status_t AudioFlinger::MixerThread::dump(int fd, const Vector<String16>& args)
{
    dumpInternals(fd, args);
    dumpTracks(fd, args);
    return NO_ERROR;
}

status_t AudioFlinger::MixerThread::dumpTracks(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;

    snprintf(buffer, SIZE, "Output %d mixer thread tracks\n", mOutputType);
    result.append(buffer);
    result.append("   Name Clien Typ Fmt Chn Buf S M F SRate LeftV RighV Serv User\n");
    for (size_t i = 0; i < mTracks.size(); ++i) {
        sp<Track> track = mTracks[i];
        if (track != 0) {
            track->dump(buffer, SIZE);
            result.append(buffer);
        }
    }

    snprintf(buffer, SIZE, "Output %d mixer thread active tracks\n", mOutputType);
    result.append(buffer);
    result.append("   Name Clien Typ Fmt Chn Buf S M F SRate LeftV RighV Serv User\n");
    for (size_t i = 0; i < mActiveTracks.size(); ++i) {
        wp<Track> wTrack = mActiveTracks[i];
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

status_t AudioFlinger::MixerThread::dumpInternals(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;

    snprintf(buffer, SIZE, "Output %d mixer thread internals\n", mOutputType);
    result.append(buffer);
    snprintf(buffer, SIZE, "AudioMixer tracks: %08x\n", mAudioMixer->trackNames());
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
    write(fd, result.string(), result.size());
    return NO_ERROR;
}

// Thread virtuals
bool AudioFlinger::MixerThread::threadLoop()
{
    unsigned long sleepTime = kBufferRecoveryInUsecs;
    int16_t* curBuf = mMixBuffer;
    Vector< sp<Track> > tracksToRemove;
    size_t enabledTracks = 0;
    nsecs_t standbyTime = systemTime();   
    size_t mixBufferSize = mFrameCount*mChannelCount*sizeof(int16_t);
    nsecs_t maxPeriod = seconds(mFrameCount) / mSampleRate * 2;

#ifdef WITH_A2DP
    bool outputTrackActive = false;
#endif

    do {
        enabledTracks = 0;
        { // scope for the AudioFlinger::mLock
        
            Mutex::Autolock _l(mAudioFlinger->mLock);

#ifdef WITH_A2DP
            if (mOutputTrack != NULL && !mAudioFlinger->isA2dpEnabled()) {
                if (outputTrackActive) {
                    mAudioFlinger->mLock.unlock();
                    mOutputTrack->stop();
                    mAudioFlinger->mLock.lock();
                    outputTrackActive = false;
                }
            }
            mAudioFlinger->checkA2dpEnabledChange_l();
#endif

            const SortedVector< wp<Track> >& activeTracks = mActiveTracks;

            // put audio hardware into standby after short delay
            if UNLIKELY(!activeTracks.size() && systemTime() > standbyTime) {
                // wait until we have something to do...
                LOGV("Audio hardware entering standby, output %d\n", mOutputType);
                if (!mStandby) {
                    mOutput->standby();
                    mStandby = true;
                }
                
#ifdef WITH_A2DP
                if (outputTrackActive) {
                    mAudioFlinger->mLock.unlock();
                    mOutputTrack->stop();
                    mAudioFlinger->mLock.lock();
                    outputTrackActive = false;
                }
#endif
                if (mOutputType == AudioSystem::AUDIO_OUTPUT_HARDWARE) {
                    mAudioFlinger->handleForcedSpeakerRoute(FORCE_ROUTE_RESTORE);
                }                
                // we're about to wait, flush the binder command buffer
                IPCThreadState::self()->flushCommands();
                mAudioFlinger->mWaitWorkCV.wait(mAudioFlinger->mLock);
                LOGV("Audio hardware exiting standby, output %d\n", mOutputType);
                
                if (mMasterMute == false) {
                    char value[PROPERTY_VALUE_MAX];
                    property_get("ro.audio.silent", value, "0");
                    if (atoi(value)) {
                        LOGD("Silence is golden");
                        setMasterMute(true);
                    }                    
                }
                
                standbyTime = systemTime() + kStandbyTimeInNsecs;
                continue;
            }

            // Forced route to speaker is handled by hardware mixer thread
            if (mOutputType == AudioSystem::AUDIO_OUTPUT_HARDWARE) {
                mAudioFlinger->handleForcedSpeakerRoute(CHECK_ROUTE_RESTORE_TIME);
            }

            // find out which tracks need to be processed
            size_t count = activeTracks.size();
            for (size_t i=0 ; i<count ; i++) {
                sp<Track> t = activeTracks[i].promote();
                if (t == 0) continue;

                Track* const track = t.get();
                audio_track_cblk_t* cblk = track->cblk();

                // The first time a track is added we wait
                // for all its buffers to be filled before processing it
                mAudioMixer->setActiveTrack(track->name());
                if (cblk->framesReady() && (track->isReady() || track->isStopped()) &&
                        !track->isPaused())
                {
                    //LOGV("track %d u=%08x, s=%08x [OK]", track->name(), cblk->user, cblk->server);

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
                    mAudioMixer->setBufferProvider(track);
                    mAudioMixer->enable(AudioMixer::MIXING);

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
                    mAudioMixer->setParameter(param, AudioMixer::VOLUME0, left);
                    mAudioMixer->setParameter(param, AudioMixer::VOLUME1, right);
                    mAudioMixer->setParameter(
                        AudioMixer::TRACK,
                        AudioMixer::FORMAT, track->format());
                    mAudioMixer->setParameter(
                        AudioMixer::TRACK,
                        AudioMixer::CHANNEL_COUNT, track->channelCount());
                    mAudioMixer->setParameter(
                        AudioMixer::RESAMPLE,
                        AudioMixer::SAMPLE_RATE,
                        int(cblk->sampleRate));

                    // reset retry count
                    track->mRetryCount = kMaxTrackRetries;
                    enabledTracks++;
                } else {
                    //LOGV("track %d u=%08x, s=%08x [NOT READY]", track->name(), cblk->user, cblk->server);
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
                    mAudioMixer->disable(AudioMixer::MIXING);
                }
            }

            // remove all the tracks that need to be...
            count = tracksToRemove.size();
            if (UNLIKELY(count)) {
                for (size_t i=0 ; i<count ; i++) {
                    const sp<Track>& track = tracksToRemove[i];
                    removeActiveTrack_l(track);
                    if (track->isTerminated()) {
                        mTracks.remove(track);
                        deleteTrackName_l(track->mName);
                    }
                }
            }
       }
        
        if (LIKELY(enabledTracks)) {
            // mix buffers...
            mAudioMixer->process(curBuf);

#ifdef WITH_A2DP
            if (mOutputTrack != NULL && mAudioFlinger->isA2dpEnabled()) {
                if (!outputTrackActive) {
                    LOGV("starting output track in mixer for output %d", mOutputType);
                    mOutputTrack->start();
                    outputTrackActive = true;
                }
                mOutputTrack->write(curBuf, mFrameCount);
            }
#endif

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
#ifdef WITH_A2DP
            if (mOutputTrack != NULL && mAudioFlinger->isA2dpEnabled()) {
                if (outputTrackActive) {
                    mOutputTrack->write(curBuf, 0);
                    if (mOutputTrack->bufferQueueEmpty()) {
                        mOutputTrack->stop();
                        outputTrackActive = false;
                    } else {
                        standbyTime = systemTime() + kStandbyTimeInNsecs;
                    }
                }
            }
#endif
            // There was nothing to mix this round, which means all
            // active tracks were late. Sleep a little bit to give
            // them another chance. If we're too late, the audio
            // hardware will zero-fill for us.
            //LOGV("no buffers - usleep(%lu)", sleepTime);
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

status_t AudioFlinger::MixerThread::readyToRun()
{
    if (mSampleRate == 0) {
        LOGE("No working audio driver found.");
        return NO_INIT;
    }
    LOGI("AudioFlinger's thread ready to run for output %d", mOutputType);
    return NO_ERROR;
}

void AudioFlinger::MixerThread::onFirstRef()
{
    const size_t SIZE = 256;
    char buffer[SIZE];

    snprintf(buffer, SIZE, "Mixer Thread for output %d", mOutputType);

    run(buffer, ANDROID_PRIORITY_URGENT_AUDIO);
}

// MixerThread::createTrack_l() must be called with AudioFlinger::mLock held
sp<AudioFlinger::MixerThread::Track>  AudioFlinger::MixerThread::createTrack_l(
        const sp<AudioFlinger::Client>& client,
        int streamType,
        uint32_t sampleRate,
        int format,
        int channelCount,
        int frameCount,
        const sp<IMemory>& sharedBuffer,
        status_t *status)
{
    sp<Track> track;
    status_t lStatus;
    
    // Resampler implementation limits input sampling rate to 2 x output sampling rate.
    if (sampleRate > MAX_SAMPLE_RATE || sampleRate > mSampleRate*2) {
        LOGE("Sample rate out of range: %d mSampleRate %d", sampleRate, mSampleRate);
        lStatus = BAD_VALUE;
        goto Exit;
    }


    if (mSampleRate == 0) {
        LOGE("Audio driver not initialized.");
        lStatus = NO_INIT;
        goto Exit;
    }

    track = new Track(this, client, streamType, sampleRate, format,
            channelCount, frameCount, sharedBuffer);
    if (track->getCblk() == NULL) {
        lStatus = NO_MEMORY;
        goto Exit;
    }
    mTracks.add(track);
    lStatus = NO_ERROR;

Exit:
    if(status) {
        *status = lStatus;
    }
    return track;
}

// getTracks_l() must be called with AudioFlinger::mLock held
void AudioFlinger::MixerThread::getTracks_l(
        SortedVector < sp<Track> >& tracks,
        SortedVector < wp<Track> >& activeTracks)
{
    size_t size = mTracks.size();
    LOGV ("MixerThread::getTracks_l() for output %d, mTracks.size %d, mActiveTracks.size %d", mOutputType,  mTracks.size(), mActiveTracks.size());
    for (size_t i = 0; i < size; i++) {
        sp<Track> t = mTracks[i];
        if (AudioSystem::routedToA2dpOutput(t->mStreamType)) {
            tracks.add(t);
            int j = mActiveTracks.indexOf(t);
            if (j >= 0) {
                t = mActiveTracks[j].promote();
                if (t != NULL) {
                    activeTracks.add(t);                                    
                }                            
            }
        }
    }

    size = activeTracks.size();
    for (size_t i = 0; i < size; i++) {
        removeActiveTrack_l(activeTracks[i]);
    }
    
    size = tracks.size();
    for (size_t i = 0; i < size; i++) {
        sp<Track> t = tracks[i];
        mTracks.remove(t);
        deleteTrackName_l(t->name());
    }
}

// putTracks_l() must be called with AudioFlinger::mLock held
void AudioFlinger::MixerThread::putTracks_l(
        SortedVector < sp<Track> >& tracks,
        SortedVector < wp<Track> >& activeTracks)
{

    LOGV ("MixerThread::putTracks_l() for output %d, tracks.size %d, activeTracks.size %d", mOutputType,  tracks.size(), activeTracks.size());

    size_t size = tracks.size();
    for (size_t i = 0; i < size ; i++) {
        sp<Track> t = tracks[i];
        int name = getTrackName_l();

        if (name < 0) return;
        
        t->mName = name;
        t->mMixerThread = this;
        mTracks.add(t);

        int j = activeTracks.indexOf(t);
        if (j >= 0) {
            addActiveTrack_l(t);
        }            
    }
}

uint32_t AudioFlinger::MixerThread::sampleRate() const
{
    return mSampleRate;
}

int AudioFlinger::MixerThread::channelCount() const
{
    return mChannelCount;
}

int AudioFlinger::MixerThread::format() const
{
    return mFormat;
}

size_t AudioFlinger::MixerThread::frameCount() const
{
    return mFrameCount;
}

uint32_t AudioFlinger::MixerThread::latency() const
{
    if (mOutput) {
        return mOutput->latency();
    }
    else {
        return 0;
    }
}

status_t AudioFlinger::MixerThread::setMasterVolume(float value)
{
    mMasterVolume = value;
    return NO_ERROR;
}

status_t AudioFlinger::MixerThread::setMasterMute(bool muted)
{
    mMasterMute = muted;
    return NO_ERROR;
}

float AudioFlinger::MixerThread::masterVolume() const
{
    return mMasterVolume;
}

bool AudioFlinger::MixerThread::masterMute() const
{
    return mMasterMute;
}

status_t AudioFlinger::MixerThread::setStreamVolume(int stream, float value)
{
    mStreamTypes[stream].volume = value;
    return NO_ERROR;
}

status_t AudioFlinger::MixerThread::setStreamMute(int stream, bool muted)
{
    mStreamTypes[stream].mute = muted;
    return NO_ERROR;
}

float AudioFlinger::MixerThread::streamVolume(int stream) const
{
    return mStreamTypes[stream].volume;
}

bool AudioFlinger::MixerThread::streamMute(int stream) const
{
    return mStreamTypes[stream].mute;
}

bool AudioFlinger::MixerThread::isMusicActive() const
{
    size_t count = mActiveTracks.size();
    for (size_t i = 0 ; i < count ; ++i) {
        sp<Track> t = mActiveTracks[i].promote();
        if (t == 0) continue;
        Track* const track = t.get();
        if (t->mStreamType == AudioSystem::MUSIC)
            return true;
    }
    return false;
}

// addTrack_l() must be called with AudioFlinger::mLock held
status_t AudioFlinger::MixerThread::addTrack_l(const sp<Track>& track)
{
    status_t status = ALREADY_EXISTS;

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
    if (mActiveTracks.indexOf(track) < 0) {
        // the track is newly added, make sure it fills up all its
        // buffers before playing. This is to ensure the client will
        // effectively get the latency it requested.
        track->mFillingUpStatus = Track::FS_FILLING;
        track->mResetDone = false;
        addActiveTrack_l(track);
        status = NO_ERROR;
    }
    
    LOGV("mWaitWorkCV.broadcast");
    mAudioFlinger->mWaitWorkCV.broadcast();

    return status;
}

// removeTrack_l() must be called with AudioFlinger::mLock held
void AudioFlinger::MixerThread::removeTrack_l(wp<Track> track, int name)
{
    sp<Track> t = track.promote();
    if (t!=NULL && (t->mState <= TrackBase::STOPPED)) {
        t->reset();
        deleteTrackName_l(name);
        removeActiveTrack_l(track);
        mAudioFlinger->mWaitWorkCV.broadcast();
    }
}

// destroyTrack_l() must be called with AudioFlinger::mLock held
void AudioFlinger::MixerThread::destroyTrack_l(const sp<Track>& track)
{
    track->mState = TrackBase::TERMINATED;
    if (mActiveTracks.indexOf(track) < 0) {
        LOGV("remove track (%d) and delete from mixer", track->name());
        mTracks.remove(track);
        deleteTrackName_l(track->name());
    }
}

// addActiveTrack_l() must be called with AudioFlinger::mLock held
void AudioFlinger::MixerThread::addActiveTrack_l(const wp<Track>& t)
{
    mActiveTracks.add(t);

    // Force routing to speaker for certain stream types
    // The forced routing to speaker is managed by hardware mixer
    if (mOutputType == AudioSystem::AUDIO_OUTPUT_HARDWARE) {
        sp<Track> track = t.promote();
        if (track == NULL) return;
   
        if (streamForcedToSpeaker(track->type())) {
            mAudioFlinger->handleForcedSpeakerRoute(ACTIVE_TRACK_ADDED);
        }        
    }
}

// removeActiveTrack_l() must be called with AudioFlinger::mLock held
void AudioFlinger::MixerThread::removeActiveTrack_l(const wp<Track>& t)
{
    mActiveTracks.remove(t);

    // Force routing to speaker for certain stream types
    // The forced routing to speaker is managed by hardware mixer
    if (mOutputType == AudioSystem::AUDIO_OUTPUT_HARDWARE) {
        sp<Track> track = t.promote();
        if (track == NULL) return;

        if (streamForcedToSpeaker(track->type())) {
            mAudioFlinger->handleForcedSpeakerRoute(ACTIVE_TRACK_REMOVED);
        }
    }
}

// getTrackName_l() must be called with AudioFlinger::mLock held
int AudioFlinger::MixerThread::getTrackName_l()
{
    return mAudioMixer->getTrackName();
}

// deleteTrackName_l() must be called with AudioFlinger::mLock held
void AudioFlinger::MixerThread::deleteTrackName_l(int name)
{
    mAudioMixer->deleteTrackName(name);
}

size_t AudioFlinger::MixerThread::getOutputFrameCount() 
{
    return mOutput->bufferSize() / mOutput->channelCount() / sizeof(int16_t);
}

// ----------------------------------------------------------------------------

// TrackBase constructor must be called with AudioFlinger::mLock held
AudioFlinger::MixerThread::TrackBase::TrackBase(
            const sp<MixerThread>& mixerThread,
            const sp<Client>& client,
            int streamType,
            uint32_t sampleRate,
            int format,
            int channelCount,
            int frameCount,
            uint32_t flags,
            const sp<IMemory>& sharedBuffer)
    :   RefBase(),
        mMixerThread(mixerThread),
        mClient(client),
        mStreamType(streamType),
        mFrameCount(0),
        mState(IDLE),
        mClientTid(-1),
        mFormat(format),
        mFlags(flags & ~SYSTEM_FLAGS_MASK)
{
    mName = mixerThread->getTrackName_l();
    LOGV("TrackBase contructor name %d, calling thread %d", mName, IPCThreadState::self()->getCallingPid());
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

   if (client != NULL) {
        mCblkMemory = client->heap()->allocate(size);
        if (mCblkMemory != 0) {
            mCblk = static_cast<audio_track_cblk_t *>(mCblkMemory->pointer());
            if (mCblk) { // construct the shared structure in-place.
                new(mCblk) audio_track_cblk_t();
                // clear all buffers
                mCblk->frameCount = frameCount;
                mCblk->sampleRate = (uint16_t)sampleRate;
                mCblk->channels = (uint16_t)channelCount;
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
   } else {
       mCblk = (audio_track_cblk_t *)(new uint8_t[size]);
       if (mCblk) { // construct the shared structure in-place.
           new(mCblk) audio_track_cblk_t();
           // clear all buffers
           mCblk->frameCount = frameCount;
           mCblk->sampleRate = (uint16_t)sampleRate;
           mCblk->channels = (uint16_t)channelCount;
           mBuffer = (char*)mCblk + sizeof(audio_track_cblk_t);
           memset(mBuffer, 0, frameCount*channelCount*sizeof(int16_t));
           // Force underrun condition to avoid false underrun callback until first data is
           // written to buffer
           mCblk->flowControlFlag = 1;
           mBufferEnd = (uint8_t *)mBuffer + bufferSize;
       }
   }
}

AudioFlinger::MixerThread::TrackBase::~TrackBase()
{
    if (mCblk) {
        mCblk->~audio_track_cblk_t();   // destroy our shared-structure.        
    }
    mCblkMemory.clear();            // and free the shared memory
    mClient.clear();
}

void AudioFlinger::MixerThread::TrackBase::releaseBuffer(AudioBufferProvider::Buffer* buffer)
{
    buffer->raw = 0;
    mFrameCount = buffer->frameCount;
    step();
    buffer->frameCount = 0;
}

bool AudioFlinger::MixerThread::TrackBase::step() {
    bool result;
    audio_track_cblk_t* cblk = this->cblk();

    result = cblk->stepServer(mFrameCount);
    if (!result) {
        LOGV("stepServer failed acquiring cblk mutex");
        mFlags |= STEPSERVER_FAILED;
    }
    return result;
}

void AudioFlinger::MixerThread::TrackBase::reset() {
    audio_track_cblk_t* cblk = this->cblk();

    cblk->user = 0;
    cblk->server = 0;
    cblk->userBase = 0;
    cblk->serverBase = 0;
    mFlags &= (uint32_t)(~SYSTEM_FLAGS_MASK);
    LOGV("TrackBase::reset");
}

sp<IMemory> AudioFlinger::MixerThread::TrackBase::getCblk() const
{
    return mCblkMemory;
}

int AudioFlinger::MixerThread::TrackBase::sampleRate() const {
    return (int)mCblk->sampleRate;
}

int AudioFlinger::MixerThread::TrackBase::channelCount() const {
    return mCblk->channels;
}

void* AudioFlinger::MixerThread::TrackBase::getBuffer(uint32_t offset, uint32_t frames) const {
    audio_track_cblk_t* cblk = this->cblk();
    int16_t *bufferStart = (int16_t *)mBuffer + (offset-cblk->serverBase)*cblk->channels;
    int16_t *bufferEnd = bufferStart + frames * cblk->channels;

    // Check validity of returned pointer in case the track control block would have been corrupted.
    if (bufferStart < mBuffer || bufferStart > bufferEnd || bufferEnd > mBufferEnd || 
            cblk->channels == 2 && ((unsigned long)bufferStart & 3) ) {
        LOGE("TrackBase::getBuffer buffer out of range:\n    start: %p, end %p , mBuffer %p mBufferEnd %p\n    \
                server %d, serverBase %d, user %d, userBase %d, channels %d",
                bufferStart, bufferEnd, mBuffer, mBufferEnd,
                cblk->server, cblk->serverBase, cblk->user, cblk->userBase, cblk->channels);
        return 0;
    }

    return bufferStart;
}

// ----------------------------------------------------------------------------

// Track constructor must be called with AudioFlinger::mLock held
AudioFlinger::MixerThread::Track::Track(
            const sp<MixerThread>& mixerThread,
            const sp<Client>& client,
            int streamType,
            uint32_t sampleRate,
            int format,
            int channelCount,
            int frameCount,
            const sp<IMemory>& sharedBuffer)
    :   TrackBase(mixerThread, client, streamType, sampleRate, format, channelCount, frameCount, 0, sharedBuffer)
{
    mVolume[0] = 1.0f;
    mVolume[1] = 1.0f;
    mMute = false;
    mSharedBuffer = sharedBuffer;
}

AudioFlinger::MixerThread::Track::~Track()
{
    wp<Track> weak(this); // never create a strong ref from the dtor
    Mutex::Autolock _l(mMixerThread->mAudioFlinger->mLock);
    mState = TERMINATED;
    mMixerThread->removeTrack_l(weak, mName);
}

void AudioFlinger::MixerThread::Track::destroy()
{
    // NOTE: destroyTrack_l() can remove a strong reference to this Track 
    // by removing it from mTracks vector, so there is a risk that this Tracks's
    // desctructor is called. As the destructor needs to lock AudioFlinger::mLock,
    // we must acquire a strong reference on this Track before locking AudioFlinger::mLock
    // here so that the destructor is called only when exiting this function.
    // On the other hand, as long as Track::destroy() is only called by 
    // TrackHandle destructor, the TrackHandle still holds a strong ref on 
    // this Track with its member mTrack.
    sp<Track> keep(this);
    { // scope for AudioFlinger::mLock
        Mutex::Autolock _l(mMixerThread->mAudioFlinger->mLock);
        mMixerThread->destroyTrack_l(this);
    }
}

void AudioFlinger::MixerThread::Track::dump(char* buffer, size_t size)
{
    snprintf(buffer, size, "  %5d %5d %3u %3u %3u %3u %1d %1d %1d %5u %5u %5u %04x %04x\n",
            mName - AudioMixer::TRACK0,
            (mClient == NULL) ? getpid() : mClient->pid(),
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

status_t AudioFlinger::MixerThread::Track::getNextBuffer(AudioBufferProvider::Buffer* buffer)
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

bool AudioFlinger::MixerThread::Track::isReady() const {
    if (mFillingUpStatus != FS_FILLING) return true;

    if (mCblk->framesReady() >= mCblk->frameCount ||
        mCblk->forceReady) {
        mFillingUpStatus = FS_FILLED;
        mCblk->forceReady = 0;
        LOGV("Track::isReady() track %d for output %d", mName, mMixerThread->mOutputType);
        return true;
    }
    return false;
}

status_t AudioFlinger::MixerThread::Track::start()
{
    LOGV("start(%d), calling thread %d for output %d", mName, IPCThreadState::self()->getCallingPid(), mMixerThread->mOutputType);
    Mutex::Autolock _l(mMixerThread->mAudioFlinger->mLock);
    mMixerThread->addTrack_l(this);
    return NO_ERROR;
}

void AudioFlinger::MixerThread::Track::stop()
{
    LOGV("stop(%d), calling thread %d for output %d", mName, IPCThreadState::self()->getCallingPid(), mMixerThread->mOutputType);
    Mutex::Autolock _l(mMixerThread->mAudioFlinger->mLock);
    if (mState > STOPPED) {
        mState = STOPPED;
        // If the track is not active (PAUSED and buffers full), flush buffers
        if (mMixerThread->mActiveTracks.indexOf(this) < 0) {
            reset();
        }
        LOGV("(> STOPPED) => STOPPED (%d)", mName);
    }
}

void AudioFlinger::MixerThread::Track::pause()
{
    LOGV("pause(%d), calling thread %d", mName, IPCThreadState::self()->getCallingPid());
    Mutex::Autolock _l(mMixerThread->mAudioFlinger->mLock);
    if (mState == ACTIVE || mState == RESUMING) {
        mState = PAUSING;
        LOGV("ACTIVE/RESUMING => PAUSING (%d)", mName);
    }
}

void AudioFlinger::MixerThread::Track::flush()
{
    LOGV("flush(%d)", mName);
    Mutex::Autolock _l(mMixerThread->mAudioFlinger->mLock);
    if (mState != STOPPED && mState != PAUSED && mState != PAUSING) {
        return;
    }
    // No point remaining in PAUSED state after a flush => go to
    // STOPPED state
    mState = STOPPED;

    mCblk->lock.lock();
    // NOTE: reset() will reset cblk->user and cblk->server with
    // the risk that at the same time, the AudioMixer is trying to read
    // data. In this case, getNextBuffer() would return a NULL pointer
    // as audio buffer => the AudioMixer code MUST always test that pointer
    // returned by getNextBuffer() is not NULL!
    reset();
    mCblk->lock.unlock();
}

void AudioFlinger::MixerThread::Track::reset()
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

void AudioFlinger::MixerThread::Track::mute(bool muted)
{
    mMute = muted;
}

void AudioFlinger::MixerThread::Track::setVolume(float left, float right)
{
    mVolume[0] = left;
    mVolume[1] = right;
}

// ----------------------------------------------------------------------------

// RecordTrack constructor must be called with AudioFlinger::mLock held
AudioFlinger::MixerThread::RecordTrack::RecordTrack(
            const sp<MixerThread>& mixerThread,
            const sp<Client>& client,
            int streamType,
            uint32_t sampleRate,
            int format,
            int channelCount,
            int frameCount,
            uint32_t flags)
    :   TrackBase(mixerThread, client, streamType, sampleRate, format,
                  channelCount, frameCount, flags, 0),
        mOverflow(false)
{
}

AudioFlinger::MixerThread::RecordTrack::~RecordTrack()
{
    Mutex::Autolock _l(mMixerThread->mAudioFlinger->mLock);
    mMixerThread->deleteTrackName_l(mName);
}

status_t AudioFlinger::MixerThread::RecordTrack::getNextBuffer(AudioBufferProvider::Buffer* buffer)
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

status_t AudioFlinger::MixerThread::RecordTrack::start()
{
    return mMixerThread->mAudioFlinger->startRecord(this);
}

void AudioFlinger::MixerThread::RecordTrack::stop()
{
    mMixerThread->mAudioFlinger->stopRecord(this);
    TrackBase::reset();
    // Force overerrun condition to avoid false overrun callback until first data is
    // read from buffer
    mCblk->flowControlFlag = 1;
}


// ----------------------------------------------------------------------------

AudioFlinger::MixerThread::OutputTrack::OutputTrack(
            const sp<MixerThread>& mixerThread,
            uint32_t sampleRate,
            int format,
            int channelCount,
            int frameCount)
    :   Track(mixerThread, NULL, AudioSystem::SYSTEM, sampleRate, format, channelCount, frameCount, NULL),
    mOutputMixerThread(mixerThread)
{
                
    mCblk->out = 1;
    mCblk->buffers = (char*)mCblk + sizeof(audio_track_cblk_t);
    mCblk->volume[0] = mCblk->volume[1] = 0x1000;
    mOutBuffer.frameCount = 0;
    mCblk->bufferTimeoutMs = 10;
    
    LOGV("OutputTrack constructor mCblk %p, mBuffer %p, mCblk->buffers %p, mCblk->frameCount %d, mCblk->sampleRate %d, mCblk->channels %d mBufferEnd %p", 
            mCblk, mBuffer, mCblk->buffers, mCblk->frameCount, mCblk->sampleRate, mCblk->channels, mBufferEnd);
    
}

AudioFlinger::MixerThread::OutputTrack::~OutputTrack()
{
    stop();
}

status_t AudioFlinger::MixerThread::OutputTrack::start()
{
    status_t status = Track::start();
    
    mRetryCount = 127;
    return status;
}

void AudioFlinger::MixerThread::OutputTrack::stop()
{
    Track::stop();
    clearBufferQueue();
    mOutBuffer.frameCount = 0;
}

void AudioFlinger::MixerThread::OutputTrack::write(int16_t* data, uint32_t frames)
{
    Buffer *pInBuffer;
    Buffer inBuffer;
    uint32_t channels = mCblk->channels;
        
    inBuffer.frameCount = frames;
    inBuffer.i16 = data;
    
    if (mCblk->user == 0) {
        if (mOutputMixerThread->isMusicActive()) {
            mCblk->forceReady = 1;
            LOGV("OutputTrack::start() force ready");
        } else if (mCblk->frameCount > frames){
            if (mBufferQueue.size() < kMaxOutputTrackBuffers) {
                uint32_t startFrames = (mCblk->frameCount - frames);
                LOGV("OutputTrack::start() write %d frames", startFrames);
                pInBuffer = new Buffer;
                pInBuffer->mBuffer = new int16_t[startFrames * channels];
                pInBuffer->frameCount = startFrames;
                pInBuffer->i16 = pInBuffer->mBuffer;
                memset(pInBuffer->raw, 0, startFrames * channels * sizeof(int16_t));
                mBufferQueue.add(pInBuffer);                
            } else {
                LOGW ("OutputTrack::write() no more buffers");
            }
        }        
    }

    while (1) { 
        // First write pending buffers, then new data
        if (mBufferQueue.size()) {
            pInBuffer = mBufferQueue.itemAt(0);
        } else {
            pInBuffer = &inBuffer;
        }
 
        if (pInBuffer->frameCount == 0) {
            break;
        }
        
        if (mOutBuffer.frameCount == 0) {
            mOutBuffer.frameCount = pInBuffer->frameCount;
            if (obtainBuffer(&mOutBuffer) == (status_t)AudioTrack::NO_MORE_BUFFERS) {
                break;
            }
        }
            
        uint32_t outFrames = pInBuffer->frameCount > mOutBuffer.frameCount ? mOutBuffer.frameCount : pInBuffer->frameCount;
        memcpy(mOutBuffer.raw, pInBuffer->raw, outFrames * channels * sizeof(int16_t));
        mCblk->stepUser(outFrames);
        pInBuffer->frameCount -= outFrames;
        pInBuffer->i16 += outFrames * channels;
        mOutBuffer.frameCount -= outFrames;
        mOutBuffer.i16 += outFrames * channels;            
        
        if (pInBuffer->frameCount == 0) {
            if (mBufferQueue.size()) {
                mBufferQueue.removeAt(0);
                delete [] pInBuffer->mBuffer;
                delete pInBuffer;
            } else {
                break;
            }
        }
    }
 
    // If we could not write all frames, allocate a buffer and queue it for next time.
    if (inBuffer.frameCount) {
        if (mBufferQueue.size() < kMaxOutputTrackBuffers) {
            pInBuffer = new Buffer;
            pInBuffer->mBuffer = new int16_t[inBuffer.frameCount * channels];
            pInBuffer->frameCount = inBuffer.frameCount;
            pInBuffer->i16 = pInBuffer->mBuffer;
            memcpy(pInBuffer->raw, inBuffer.raw, inBuffer.frameCount * channels * sizeof(int16_t));
            mBufferQueue.add(pInBuffer);
        } else {
            LOGW("OutputTrack::write() no more buffers");
        }
    }
    
    // Calling write() with a 0 length buffer, means that no more data will be written:
    // If no more buffers are pending, fill output track buffer to make sure it is started 
    // by output mixer.
    if (frames == 0 && mBufferQueue.size() == 0 && mCblk->user < mCblk->frameCount) {
        frames = mCblk->frameCount - mCblk->user;
        pInBuffer = new Buffer;
        pInBuffer->mBuffer = new int16_t[frames * channels];
        pInBuffer->frameCount = frames;
        pInBuffer->i16 = pInBuffer->mBuffer;
        memset(pInBuffer->raw, 0, frames * channels * sizeof(int16_t));
        mBufferQueue.add(pInBuffer);
    }

}

status_t AudioFlinger::MixerThread::OutputTrack::obtainBuffer(AudioBufferProvider::Buffer* buffer)
{
    int active;
    int timeout = 0;
    status_t result;
    audio_track_cblk_t* cblk = mCblk;
    uint32_t framesReq = buffer->frameCount;

    LOGV("OutputTrack::obtainBuffer user %d, server %d", cblk->user, cblk->server);
    buffer->frameCount  = 0;
    
    uint32_t framesAvail = cblk->framesAvailable();

    if (framesAvail == 0) {
        return AudioTrack::NO_MORE_BUFFERS;
    }

    if (framesReq > framesAvail) {
        framesReq = framesAvail;
    }

    uint32_t u = cblk->user;
    uint32_t bufferEnd = cblk->userBase + cblk->frameCount;

    if (u + framesReq > bufferEnd) {
        framesReq = bufferEnd - u;
    }

    buffer->frameCount  = framesReq;
    buffer->raw         = (void *)cblk->buffer(u);
    return NO_ERROR;
}


void AudioFlinger::MixerThread::OutputTrack::clearBufferQueue()
{
    size_t size = mBufferQueue.size();
    Buffer *pBuffer;
    
    for (size_t i = 0; i < size; i++) {
        pBuffer = mBufferQueue.itemAt(i);
        delete [] pBuffer->mBuffer;
        delete pBuffer;
    }
    mBufferQueue.clear();
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

AudioFlinger::TrackHandle::TrackHandle(const sp<AudioFlinger::MixerThread::Track>& track)
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
    sp<MixerThread::RecordTrack> recordTrack;
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
    { // scope for mLock
        Mutex::Autolock _l(mLock);
        wclient = mClients.valueFor(pid);
        if (wclient != NULL) {
            client = wclient.promote();
        } else {
            client = new Client(this, pid);
            mClients.add(pid, client);
        }

        // frameCount must be a multiple of input buffer size
        inFrameCount = inputBufferSize/channelCount/sizeof(short);
        frameCount = ((frameCount - 1)/inFrameCount + 1) * inFrameCount;
    
        // create new record track. The record track uses one track in mHardwareMixerThread by convention.
        recordTrack = new MixerThread::RecordTrack(mHardwareMixerThread, client, streamType, sampleRate,
                                                   format, channelCount, frameCount, flags);
    }
    if (recordTrack->getCblk() == NULL) {
        recordTrack.clear();
        lStatus = NO_MEMORY;
        goto Exit;
    }

    // return to handle to client
    recordHandle = new RecordHandle(recordTrack);
    lStatus = NO_ERROR;

Exit:
    if (status) {
        *status = lStatus;
    }
    return recordHandle;
}

status_t AudioFlinger::startRecord(MixerThread::RecordTrack* recordTrack) {
    if (mAudioRecordThread != 0) {
        return mAudioRecordThread->start(recordTrack);        
    }
    return NO_INIT;
}

void AudioFlinger::stopRecord(MixerThread::RecordTrack* recordTrack) {
    if (mAudioRecordThread != 0) {
        mAudioRecordThread->stop(recordTrack);
    }
}

// ----------------------------------------------------------------------------

AudioFlinger::RecordHandle::RecordHandle(const sp<AudioFlinger::MixerThread::RecordTrack>& recordTrack)
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

AudioFlinger::AudioRecordThread::AudioRecordThread(AudioHardwareInterface* audioHardware,
            const sp<AudioFlinger>& audioFlinger) :
    mAudioHardware(audioHardware),
    mAudioFlinger(audioFlinger),
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
                mStopped.signal();

                mWaitWorkCV.wait(mLock);
               
                LOGV("AudioRecordThread: loop starting");
                if (mRecordTrack != 0) {
                    input = mAudioHardware->openInputStream(mRecordTrack->format(), 
                                    mRecordTrack->channelCount(), 
                                    mRecordTrack->sampleRate(), 
                                    &mStartStatus,
                                    (AudioSystem::audio_in_acoustics)(mRecordTrack->mFlags >> 16));
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
        } else if (mRecordTrack != 0) {

            buffer.frameCount = inFrameCount;
            if (LIKELY(mRecordTrack->getNextBuffer(&buffer) == NO_ERROR &&
                       (int)buffer.frameCount == inFrameCount)) {
                LOGV("AudioRecordThread read: %d frames", buffer.frameCount);
                ssize_t bytesRead = input->read(buffer.raw, inBufferSize);
                if (bytesRead < 0) {
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

status_t AudioFlinger::AudioRecordThread::start(MixerThread::RecordTrack* recordTrack)
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

void AudioFlinger::AudioRecordThread::stop(MixerThread::RecordTrack* recordTrack) {
    LOGV("AudioRecordThread::stop");
    AutoMutex lock(&mLock);
    if (mActive && (recordTrack == mRecordTrack.get())) {
        mActive = false;
        mStopped.wait(mLock);
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

status_t AudioFlinger::AudioRecordThread::dump(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    pid_t pid = 0;

    if (mRecordTrack != 0 && mRecordTrack->mClient != 0) {
        snprintf(buffer, SIZE, "Record client pid: %d\n", mRecordTrack->mClient->pid());
        result.append(buffer);
    } else {
        result.append("No record client\n");
    }
    write(fd, result.string(), result.size());
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
