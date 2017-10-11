#include <atomic>
#include <inttypes.h>
#include <stdio.h>
#include <string.h>

#include <jni.h>

#include <midi/midi.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

#include "messagequeue.h"

extern "C" {
JNIEXPORT jstring JNICALL Java_com_example_android_nativemididemo_NativeMidi_initAudio(
        JNIEnv* env, jobject thiz, jint sampleRate, jint playSamples);
JNIEXPORT void JNICALL Java_com_example_android_nativemididemo_NativeMidi_pauseAudio(
        JNIEnv* env, jobject thiz);
JNIEXPORT void JNICALL Java_com_example_android_nativemididemo_NativeMidi_resumeAudio(
        JNIEnv* env, jobject thiz);
JNIEXPORT void JNICALL Java_com_example_android_nativemididemo_NativeMidi_shutdownAudio(
        JNIEnv* env, jobject thiz);
JNIEXPORT jlong JNICALL Java_com_example_android_nativemididemo_NativeMidi_getPlaybackCounter(
        JNIEnv* env, jobject thiz);
JNIEXPORT jobjectArray JNICALL Java_com_example_android_nativemididemo_NativeMidi_getRecentMessages(
        JNIEnv* env, jobject thiz);
JNIEXPORT void JNICALL Java_com_example_android_nativemididemo_NativeMidi_startReadingMidi(
        JNIEnv* env, jobject thiz, jint deviceId, jint portNumber);
JNIEXPORT void JNICALL Java_com_example_android_nativemididemo_NativeMidi_stopReadingMidi(
        JNIEnv* env, jobject thiz);
}

static const char* errStrings[] = {
    "SL_RESULT_SUCCESS",                    // 0
    "SL_RESULT_PRECONDITIONS_VIOLATED",     // 1
    "SL_RESULT_PARAMETER_INVALID",          // 2
    "SL_RESULT_MEMORY_FAILURE",             // 3
    "SL_RESULT_RESOURCE_ERROR",             // 4
    "SL_RESULT_RESOURCE_LOST",              // 5
    "SL_RESULT_IO_ERROR",                   // 6
    "SL_RESULT_BUFFER_INSUFFICIENT",        // 7
    "SL_RESULT_CONTENT_CORRUPTED",          // 8
    "SL_RESULT_CONTENT_UNSUPPORTED",        // 9
    "SL_RESULT_CONTENT_NOT_FOUND",          // 10
    "SL_RESULT_PERMISSION_DENIED",          // 11
    "SL_RESULT_FEATURE_UNSUPPORTED",        // 12
    "SL_RESULT_INTERNAL_ERROR",             // 13
    "SL_RESULT_UNKNOWN_ERROR",              // 14
    "SL_RESULT_OPERATION_ABORTED",          // 15
    "SL_RESULT_CONTROL_LOST" };             // 16
static const char* getSLErrStr(int code) {
    return errStrings[code];
}

static SLObjectItf engineObject;
static SLEngineItf engineEngine;
static SLObjectItf outputMixObject;
static SLObjectItf playerObject;
static SLPlayItf playerPlay;
static SLAndroidSimpleBufferQueueItf playerBufferQueue;

static const int minPlaySamples = 32;
static const int maxPlaySamples = 1000;
static std::atomic_int playSamples(maxPlaySamples);
static short playBuffer[maxPlaySamples];

static std::atomic_ullong sharedCounter;

static AMIDI_Device* midiDevice = AMIDI_INVALID_HANDLE;
static std::atomic<AMIDI_OutputPort*> midiOutputPort(AMIDI_INVALID_HANDLE);

static int setPlaySamples(int newPlaySamples)
{
    if (newPlaySamples < minPlaySamples) newPlaySamples = minPlaySamples;
    if (newPlaySamples > maxPlaySamples) newPlaySamples = maxPlaySamples;
    playSamples.store(newPlaySamples);
    return newPlaySamples;
}

// Amount of messages we are ready to handle during one callback cycle.
static const size_t MAX_INCOMING_MIDI_MESSAGES = 20;
// Static allocation to save time in the callback.
static AMIDI_Message incomingMidiMessages[MAX_INCOMING_MIDI_MESSAGES];

static void bqPlayerCallback(SLAndroidSimpleBufferQueueItf bq, void */*context*/)
{
    sharedCounter++;

    AMIDI_OutputPort* outputPort = midiOutputPort.load();
    if (outputPort != AMIDI_INVALID_HANDLE) {
        char midiDumpBuffer[1024];
        ssize_t midiReceived = AMIDI_receive(
                outputPort, incomingMidiMessages, MAX_INCOMING_MIDI_MESSAGES);
        if (midiReceived >= 0) {
            for (ssize_t i = 0; i < midiReceived; ++i) {
                AMIDI_Message* msg = &incomingMidiMessages[i];
                if (msg->opcode == AMIDI_OPCODE_DATA) {
                    memset(midiDumpBuffer, 0, sizeof(midiDumpBuffer));
                    int pos = snprintf(midiDumpBuffer, sizeof(midiDumpBuffer),
                            "%" PRIx64 " ", msg->timestamp);
                    for (uint8_t *b = msg->buffer, *e = b + msg->len; b < e; ++b) {
                        pos += snprintf(midiDumpBuffer + pos, sizeof(midiDumpBuffer) - pos,
                                "%02x ", *b);
                    }
                    nativemididemo::writeMessage(midiDumpBuffer);
                } else if (msg->opcode == AMIDI_OPCODE_FLUSH) {
                    nativemididemo::writeMessage("MIDI flush");
                }
            }
        } else {
            snprintf(midiDumpBuffer, sizeof(midiDumpBuffer),
                    "! MIDI Receive error: %s !", strerror(-midiReceived));
            nativemididemo::writeMessage(midiDumpBuffer);
        }
    }

    size_t usedBufferSize = playSamples.load() * sizeof(playBuffer[0]);
    if (usedBufferSize > sizeof(playBuffer)) {
        usedBufferSize = sizeof(playBuffer);
    }
    (*bq)->Enqueue(bq, playBuffer, usedBufferSize);
}

jstring Java_com_example_android_nativemididemo_NativeMidi_initAudio(
        JNIEnv* env, jobject, jint sampleRate, jint playSamples) {
    const char* stage;
    SLresult result;
    char printBuffer[1024];

    playSamples = setPlaySamples(playSamples);

    result = slCreateEngine(&engineObject, 0, NULL, 0, NULL, NULL);
    if (SL_RESULT_SUCCESS != result) { stage = "slCreateEngine"; goto handle_error; }

    result = (*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE);
    if (SL_RESULT_SUCCESS != result) { stage = "realize Engine object"; goto handle_error; }

    result = (*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &engineEngine);
    if (SL_RESULT_SUCCESS != result) { stage = "get Engine interface"; goto handle_error; }

    result = (*engineEngine)->CreateOutputMix(engineEngine, &outputMixObject, 0, NULL, NULL);
    if (SL_RESULT_SUCCESS != result) { stage = "CreateOutputMix"; goto handle_error; }

    result = (*outputMixObject)->Realize(outputMixObject, SL_BOOLEAN_FALSE);
    if (SL_RESULT_SUCCESS != result) { stage = "realize OutputMix object"; goto handle_error; }

    {
    SLDataFormat_PCM format_pcm = { SL_DATAFORMAT_PCM, 1, (SLuint32)sampleRate * 1000,
                                    SL_PCMSAMPLEFORMAT_FIXED_16, SL_PCMSAMPLEFORMAT_FIXED_16,
                                    SL_SPEAKER_FRONT_LEFT, SL_BYTEORDER_LITTLEENDIAN };
    SLDataLocator_AndroidSimpleBufferQueue loc_bufq =
            { SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 1 };
    SLDataSource audioSrc = { &loc_bufq, &format_pcm };
    SLDataLocator_OutputMix loc_outmix = { SL_DATALOCATOR_OUTPUTMIX, outputMixObject };
    SLDataSink audioSnk = { &loc_outmix, NULL };
    const SLInterfaceID ids[1] = { SL_IID_BUFFERQUEUE };
    const SLboolean req[1] = { SL_BOOLEAN_TRUE };
    result = (*engineEngine)->CreateAudioPlayer(
            engineEngine, &playerObject, &audioSrc, &audioSnk, 1, ids, req);
    if (SL_RESULT_SUCCESS != result) { stage = "CreateAudioPlayer"; goto handle_error; }

    result = (*playerObject)->Realize(playerObject, SL_BOOLEAN_FALSE);
    if (SL_RESULT_SUCCESS != result) { stage = "realize Player object"; goto handle_error; }
    }

    result = (*playerObject)->GetInterface(playerObject, SL_IID_PLAY, &playerPlay);
    if (SL_RESULT_SUCCESS != result) { stage = "get Play interface"; goto handle_error; }

    result = (*playerObject)->GetInterface(playerObject, SL_IID_BUFFERQUEUE, &playerBufferQueue);
    if (SL_RESULT_SUCCESS != result) { stage = "get BufferQueue interface"; goto handle_error; }

    result = (*playerBufferQueue)->RegisterCallback(playerBufferQueue, bqPlayerCallback, NULL);
    if (SL_RESULT_SUCCESS != result) { stage = "register BufferQueue callback"; goto handle_error; }

    result = (*playerBufferQueue)->Enqueue(playerBufferQueue, playBuffer, sizeof(playBuffer));
    if (SL_RESULT_SUCCESS != result) {
        stage = "enqueue into PlayerBufferQueue"; goto handle_error; }

    result = (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_PLAYING);
    if (SL_RESULT_SUCCESS != result) {
        stage = "SetPlayState(SL_PLAYSTATE_PLAYING)"; goto handle_error; }

    snprintf(printBuffer, sizeof(printBuffer),
            "Success, sample rate %d, buffer samples %d", sampleRate, playSamples);
    return env->NewStringUTF(printBuffer);

handle_error:
    snprintf(printBuffer, sizeof(printBuffer), "Error at %s: %s", stage, getSLErrStr(result));
    return env->NewStringUTF(printBuffer);
}

void Java_com_example_android_nativemididemo_NativeMidi_pauseAudio(
        JNIEnv*, jobject) {
    if (playerPlay != NULL) {
        (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_PAUSED);
    }
}

void Java_com_example_android_nativemididemo_NativeMidi_resumeAudio(
        JNIEnv*, jobject) {
    if (playerBufferQueue != NULL && playerPlay != NULL) {
        (*playerBufferQueue)->Enqueue(playerBufferQueue, playBuffer, sizeof(playBuffer));
        (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_PLAYING);
    }
}

void Java_com_example_android_nativemididemo_NativeMidi_shutdownAudio(
        JNIEnv*, jobject) {
    if (playerObject != NULL) {
        (*playerObject)->Destroy(playerObject);
        playerObject = NULL;
        playerPlay = NULL;
        playerBufferQueue = NULL;
    }

    if (outputMixObject != NULL) {
        (*outputMixObject)->Destroy(outputMixObject);
        outputMixObject = NULL;
    }

    if (engineObject != NULL) {
        (*engineObject)->Destroy(engineObject);
        engineObject = NULL;
        engineEngine = NULL;
    }
}

jlong Java_com_example_android_nativemididemo_NativeMidi_getPlaybackCounter(JNIEnv*, jobject) {
    return sharedCounter.load();
}

jobjectArray Java_com_example_android_nativemididemo_NativeMidi_getRecentMessages(
        JNIEnv* env, jobject thiz) {
    return nativemididemo::getRecentMessagesForJava(env, thiz);
}

void Java_com_example_android_nativemididemo_NativeMidi_startReadingMidi(
        JNIEnv*, jobject, jlong deviceHandle, jint portNumber) {
    char buffer[1024];

    midiDevice = (AMIDI_Device*)deviceHandle;
//    int result = AMIDI_getDeviceById(deviceId, &midiDevice);
//    if (result == 0) {
//        snprintf(buffer, sizeof(buffer), "Obtained device token for uid %d: token %d", deviceId, midiDevice);
//    } else {
//        snprintf(buffer, sizeof(buffer), "Could not obtain device token for uid %d: %d", deviceId, result);
//    }
    nativemididemo::writeMessage(buffer);
//    if (result) return;

    AMIDI_DeviceInfo deviceInfo;
    int result = AMIDI_getDeviceInfo(midiDevice, &deviceInfo);
    if (result == 0) {
        snprintf(buffer, sizeof(buffer), "Device info: uid %d, type %d, priv %d, ports %d I / %d O",
                deviceInfo.uid, deviceInfo.type, deviceInfo.isPrivate,
                (int)deviceInfo.inputPortCount, (int)deviceInfo.outputPortCount);
    } else {
        snprintf(buffer, sizeof(buffer), "Could not obtain device info %d", result);
    }
    nativemididemo::writeMessage(buffer);
    if (result) return;

    AMIDI_OutputPort* outputPort;
    result = AMIDI_openOutputPort(midiDevice, portNumber, &outputPort);
    if (result == 0) {
        snprintf(buffer, sizeof(buffer), "Opened port %d: token %p", portNumber, outputPort);
        midiOutputPort.store(outputPort);
    } else {
        snprintf(buffer, sizeof(buffer), "Could not open port %p: %d", midiDevice, result);
    }
    nativemididemo::writeMessage(buffer);
}

void Java_com_example_android_nativemididemo_NativeMidi_stopReadingMidi(
        JNIEnv*, jobject) {
    AMIDI_OutputPort* outputPort = midiOutputPort.exchange(AMIDI_INVALID_HANDLE);
    if (outputPort == AMIDI_INVALID_HANDLE) return;
    int result = AMIDI_closeOutputPort(outputPort);
    char buffer[1024];
    if (result == 0) {
        snprintf(buffer, sizeof(buffer), "Closed port by token %p", outputPort);
    } else {
        snprintf(buffer, sizeof(buffer), "Could not close port by token %p: %d", outputPort, result);
    }
    nativemididemo::writeMessage(buffer);
}
