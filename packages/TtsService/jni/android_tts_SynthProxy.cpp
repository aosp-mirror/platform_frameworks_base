/*
 * Copyright (C) 2009-2010 Google Inc.
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

#include <stdio.h>
#include <unistd.h>

#define LOG_TAG "SynthProxy"

#include <utils/Log.h>
#include <nativehelper/jni.h>
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <android/tts.h>
#include <media/AudioTrack.h>
#include <math.h>

#include <dlfcn.h>

#define DEFAULT_TTS_RATE        16000
#define DEFAULT_TTS_FORMAT      AudioSystem::PCM_16_BIT
#define DEFAULT_TTS_NB_CHANNELS 1
#define DEFAULT_TTS_BUFFERSIZE  2048
// TODO use the TTS stream type when available
#define DEFAULT_TTS_STREAM_TYPE AudioSystem::MUSIC

// EQ + BOOST parameters
#define FILTER_LOWSHELF_ATTENUATION -18.0f // in dB
#define FILTER_TRANSITION_FREQ 1100.0f     // in Hz
#define FILTER_SHELF_SLOPE 1.0f            // Q
#define FILTER_GAIN 5.5f // linear gain

#define USAGEMODE_PLAY_IMMEDIATELY 0
#define USAGEMODE_WRITE_TO_FILE    1

#define SYNTHPLAYSTATE_IS_STOPPED 0
#define SYNTHPLAYSTATE_IS_PLAYING 1

using namespace android;

// ----------------------------------------------------------------------------
struct fields_t {
    jfieldID    synthProxyFieldJniData;
    jclass      synthProxyClass;
    jmethodID   synthProxyMethodPost;
};

// structure to hold the data that is used each time the TTS engine has synthesized more data
struct afterSynthData_t {
    jint jniStorage;
    int  usageMode;
    FILE* outputFile;
    AudioSystem::stream_type streamType;
};

// ----------------------------------------------------------------------------
// EQ data
double amp;
double w;
double sinw;
double cosw;
double beta;
double a0, a1, a2, b0, b1, b2;
double m_fa, m_fb, m_fc, m_fd, m_fe;
double x0;  // x[n]
double x1;  // x[n-1]
double x2;  // x[n-2]
double out0;// y[n]
double out1;// y[n-1]
double out2;// y[n-2]

static float fFilterLowshelfAttenuation = FILTER_LOWSHELF_ATTENUATION;
static float fFilterTransitionFreq = FILTER_TRANSITION_FREQ;
static float fFilterShelfSlope = FILTER_SHELF_SLOPE;
static float fFilterGain = FILTER_GAIN;
static bool  bUseFilter = false;

void initializeEQ() {

    amp = float(pow(10.0, fFilterLowshelfAttenuation / 40.0));
    w = 2.0 * M_PI * (fFilterTransitionFreq / DEFAULT_TTS_RATE);
    sinw = float(sin(w));
    cosw = float(cos(w));
    beta = float(sqrt(amp)/fFilterShelfSlope);

    // initialize low-shelf parameters
    b0 = amp * ((amp+1.0F) - ((amp-1.0F)*cosw) + (beta*sinw));
    b1 = 2.0F * amp * ((amp-1.0F) - ((amp+1.0F)*cosw));
    b2 = amp * ((amp+1.0F) - ((amp-1.0F)*cosw) - (beta*sinw));
    a0 = (amp+1.0F) + ((amp-1.0F)*cosw) + (beta*sinw);
    a1 = 2.0F * ((amp-1.0F) + ((amp+1.0F)*cosw));
    a2 = -((amp+1.0F) + ((amp-1.0F)*cosw) - (beta*sinw));

    m_fa = fFilterGain * b0/a0;
    m_fb = fFilterGain * b1/a0;
    m_fc = fFilterGain * b2/a0;
    m_fd = a1/a0;
    m_fe = a2/a0;
}

void initializeFilter() {
    x0 = 0.0f;
    x1 = 0.0f;
    x2 = 0.0f;
    out0 = 0.0f;
    out1 = 0.0f;
    out2 = 0.0f;
}

void applyFilter(int16_t* buffer, size_t sampleCount) {

    for (size_t i=0 ; i<sampleCount ; i++) {

        x0 = (double) buffer[i];

        out0 = (m_fa*x0) + (m_fb*x1) + (m_fc*x2) + (m_fd*out1) + (m_fe*out2);

        x2 = x1;
        x1 = x0;

        out2 = out1;
        out1 = out0;

        if (out0 > 32767.0f) {
            buffer[i] = 32767;
        } else if (out0 < -32768.0f) {
            buffer[i] = -32768;
        } else {
            buffer[i] = (int16_t) out0;
        }
    }
}


// ----------------------------------------------------------------------------
static fields_t javaTTSFields;

// TODO move to synth member once we have multiple simultaneous engines running
static Mutex engineMutex;

// ----------------------------------------------------------------------------
class SynthProxyJniStorage {
    public :
        jobject                   tts_ref;
        android_tts_engine_t*       mEngine;
        void*                     mEngineLibHandle;
        AudioTrack*               mAudioOut;
        int8_t                    mPlayState;
        Mutex                     mPlayLock;
        AudioSystem::stream_type  mStreamType;
        uint32_t                  mSampleRate;
        uint32_t                  mAudFormat;
        int                       mNbChannels;
        int8_t *                  mBuffer;
        size_t                    mBufferSize;

        SynthProxyJniStorage() {
            tts_ref = NULL;
            mEngine = NULL;
            mEngineLibHandle = NULL;
            mAudioOut = NULL;
            mPlayState =  SYNTHPLAYSTATE_IS_STOPPED;
            mStreamType = DEFAULT_TTS_STREAM_TYPE;
            mSampleRate = DEFAULT_TTS_RATE;
            mAudFormat  = DEFAULT_TTS_FORMAT;
            mNbChannels = DEFAULT_TTS_NB_CHANNELS;
            mBufferSize = DEFAULT_TTS_BUFFERSIZE;
            mBuffer = new int8_t[mBufferSize];
            memset(mBuffer, 0, mBufferSize);
        }

        ~SynthProxyJniStorage() {
            //LOGV("entering ~SynthProxyJniStorage()");
            killAudio();
            if (mEngine) {
                mEngine->funcs->shutdown(mEngine);
                mEngine = NULL;
            }
            if (mEngineLibHandle) {
                //LOGE("~SynthProxyJniStorage(): before close library");
                int res = dlclose(mEngineLibHandle);
                LOGE_IF( res != 0, "~SynthProxyJniStorage(): dlclose returned %d", res);
            }
            delete mBuffer;
        }

        void killAudio() {
            if (mAudioOut) {
                mAudioOut->stop();
                delete mAudioOut;
                mAudioOut = NULL;
            }
        }

        void createAudioOut(AudioSystem::stream_type streamType, uint32_t rate,
                AudioSystem::audio_format format, int channel) {
            mSampleRate = rate;
            mAudFormat  = format;
            mNbChannels = channel;
            mStreamType = streamType;

            // retrieve system properties to ensure successful creation of the
            // AudioTrack object for playback
            int afSampleRate;
            if (AudioSystem::getOutputSamplingRate(&afSampleRate, mStreamType) != NO_ERROR) {
                afSampleRate = 44100;
            }
            int afFrameCount;
            if (AudioSystem::getOutputFrameCount(&afFrameCount, mStreamType) != NO_ERROR) {
                afFrameCount = 2048;
            }
            uint32_t afLatency;
            if (AudioSystem::getOutputLatency(&afLatency, mStreamType) != NO_ERROR) {
                afLatency = 500;
            }
            uint32_t minBufCount = afLatency / ((1000 * afFrameCount)/afSampleRate);
            if (minBufCount < 2) minBufCount = 2;
            int minFrameCount = (afFrameCount * rate * minBufCount)/afSampleRate;

            mPlayLock.lock();
            mAudioOut = new AudioTrack(mStreamType, rate, format,
                    (channel == 2) ? AudioSystem::CHANNEL_OUT_STEREO : AudioSystem::CHANNEL_OUT_MONO,
                    minFrameCount > 4096 ? minFrameCount : 4096,
                    0, 0, 0, 0); // not using an AudioTrack callback

            if (mAudioOut->initCheck() != NO_ERROR) {
              LOGE("createAudioOut(): AudioTrack error");
              delete mAudioOut;
              mAudioOut = NULL;
            } else {
              //LOGI("AudioTrack OK");
              mAudioOut->setVolume(1.0f, 1.0f);
              LOGV("AudioTrack ready");
            }
            mPlayLock.unlock();
        }
};


// ----------------------------------------------------------------------------
void prepAudioTrack(SynthProxyJniStorage* pJniData, AudioSystem::stream_type streamType,
        uint32_t rate, AudioSystem::audio_format format, int channel) {
    // Don't bother creating a new audiotrack object if the current
    // object is already initialized with the same audio parameters.
    if ( pJniData->mAudioOut &&
         (rate == pJniData->mSampleRate) &&
         (format == pJniData->mAudFormat) &&
         (channel == pJniData->mNbChannels) &&
         (streamType == pJniData->mStreamType) ){
        return;
    }
    if (pJniData->mAudioOut){
        pJniData->killAudio();
    }
    pJniData->createAudioOut(streamType, rate, format, channel);
}


// ----------------------------------------------------------------------------
/*
 * Callback from TTS engine.
 * Directly speaks using AudioTrack or write to file
 */
extern "C" android_tts_callback_status_t
__ttsSynthDoneCB(void ** pUserdata, uint32_t rate,
               android_tts_audio_format_t format, int channel,
               int8_t **pWav, size_t *pBufferSize,
               android_tts_synth_status_t status) 
{
    //LOGV("ttsSynthDoneCallback: %d bytes", bufferSize);
    AudioSystem::audio_format  encoding;

    if (*pUserdata == NULL){
        LOGE("userdata == NULL");
        return ANDROID_TTS_CALLBACK_HALT;
    }
    switch (format) {
    case ANDROID_TTS_AUDIO_FORMAT_PCM_8_BIT:
        encoding = AudioSystem::PCM_8_BIT;
        break;
    case ANDROID_TTS_AUDIO_FORMAT_PCM_16_BIT:
        encoding = AudioSystem::PCM_16_BIT;
        break;
    default:
        LOGE("Can't play, bad format");
        return ANDROID_TTS_CALLBACK_HALT;
    }
    afterSynthData_t* pForAfter = (afterSynthData_t*) *pUserdata;
    SynthProxyJniStorage* pJniData = (SynthProxyJniStorage*)(pForAfter->jniStorage);

    if (pForAfter->usageMode == USAGEMODE_PLAY_IMMEDIATELY){
        //LOGV("Direct speech");

        if (*pWav == NULL) {
            delete pForAfter;
            pForAfter = NULL;
            LOGV("Null: speech has completed");
            return ANDROID_TTS_CALLBACK_HALT;
        }

        if (*pBufferSize > 0) {
            prepAudioTrack(pJniData, pForAfter->streamType, rate, encoding, channel);
            if (pJniData->mAudioOut) {
                pJniData->mPlayLock.lock();
                if(pJniData->mAudioOut->stopped()
                        && (pJniData->mPlayState == SYNTHPLAYSTATE_IS_PLAYING)) {
                    pJniData->mAudioOut->start();
                }
                pJniData->mPlayLock.unlock();
                if (bUseFilter) {
                    applyFilter((int16_t*)*pWav, *pBufferSize/2);
                }
                pJniData->mAudioOut->write(*pWav, *pBufferSize);
                memset(*pWav, 0, *pBufferSize);
                //LOGV("AudioTrack wrote: %d bytes", bufferSize);
            } else {
                LOGE("Can't play, null audiotrack");
                delete pForAfter;
                pForAfter = NULL;
                return ANDROID_TTS_CALLBACK_HALT;
            }
        }
    } else  if (pForAfter->usageMode == USAGEMODE_WRITE_TO_FILE) {
        //LOGV("Save to file");
        if (*pWav == NULL) {
            delete pForAfter;
            LOGV("Null: speech has completed");
            return ANDROID_TTS_CALLBACK_HALT;
        }
        if (*pBufferSize > 0){
            if (bUseFilter) {
                applyFilter((int16_t*)*pWav, *pBufferSize/2);
            }
            fwrite(*pWav, 1, *pBufferSize, pForAfter->outputFile);
            memset(*pWav, 0, *pBufferSize);
        }
    }
    // Future update:
    //      For sync points in the speech, call back into the SynthProxy class through the
    //      javaTTSFields.synthProxyMethodPost methode to notify
    //      playback has completed if the synthesis is done or if a marker has been reached.

    if (status == ANDROID_TTS_SYNTH_DONE) {
        // this struct was allocated in the original android_tts_SynthProxy_speak call,
        // all processing matching this call is now done.
        LOGV("Speech synthesis done.");
        if (pForAfter->usageMode == USAGEMODE_PLAY_IMMEDIATELY) {
            // only delete for direct playback. When writing to a file, we still have work to do
            // in android_tts_SynthProxy_synthesizeToFile. The struct will be deleted there.
            delete pForAfter;
            pForAfter = NULL;
        }
        return ANDROID_TTS_CALLBACK_HALT;
    }

    // we don't update the wav (output) parameter as we'll let the next callback
    // write at the same location, we've consumed the data already, but we need
    // to update bufferSize to let the TTS engine know how much it can write the
    // next time it calls this function.
    *pBufferSize = pJniData->mBufferSize;

    return ANDROID_TTS_CALLBACK_CONTINUE;
}


// ----------------------------------------------------------------------------
static int
android_tts_SynthProxy_setLowShelf(JNIEnv *env, jobject thiz, jboolean applyFilter,
        jfloat filterGain, jfloat attenuationInDb, jfloat freqInHz, jfloat slope)
{
    int result = ANDROID_TTS_SUCCESS;

    bUseFilter = applyFilter;
    if (applyFilter) {
        fFilterLowshelfAttenuation = attenuationInDb;
        fFilterTransitionFreq = freqInHz;
        fFilterShelfSlope = slope;
        fFilterGain = filterGain;

        if (fFilterShelfSlope != 0.0f) {
            initializeEQ();
        } else {
            LOGE("Invalid slope, can't be null");
            result = ANDROID_TTS_FAILURE;
        }
    }

    return result;
}

// ----------------------------------------------------------------------------
static int
android_tts_SynthProxy_native_setup(JNIEnv *env, jobject thiz,
        jobject weak_this, jstring nativeSoLib, jstring engConfig)
{
    int result = ANDROID_TTS_FAILURE;

    bUseFilter = false;

    SynthProxyJniStorage* pJniStorage = new SynthProxyJniStorage();

    prepAudioTrack(pJniStorage,
            DEFAULT_TTS_STREAM_TYPE, DEFAULT_TTS_RATE, DEFAULT_TTS_FORMAT, DEFAULT_TTS_NB_CHANNELS);

    const char *nativeSoLibNativeString =  env->GetStringUTFChars(nativeSoLib, 0);
    const char *engConfigString = env->GetStringUTFChars(engConfig, 0);

    void *engine_lib_handle = dlopen(nativeSoLibNativeString,
            RTLD_NOW | RTLD_LOCAL);
    if (engine_lib_handle == NULL) {
       LOGE("android_tts_SynthProxy_native_setup(): engine_lib_handle == NULL");
    } else {
        android_tts_engine_t * (*get_TtsEngine)() =
            reinterpret_cast<android_tts_engine_t* (*)()>(dlsym(engine_lib_handle, "android_getTtsEngine"));

        // Support obsolete/legacy binary modules
        if (get_TtsEngine == NULL) {
            get_TtsEngine =
                reinterpret_cast<android_tts_engine_t* (*)()>(dlsym(engine_lib_handle, "getTtsEngine"));
        }

        pJniStorage->mEngine = (*get_TtsEngine)();
        pJniStorage->mEngineLibHandle = engine_lib_handle;

        android_tts_engine_t *engine = pJniStorage->mEngine;
        if (engine) {
            Mutex::Autolock l(engineMutex);
            engine->funcs->init(
                engine,
                __ttsSynthDoneCB,
                engConfigString);
        }

        result = ANDROID_TTS_SUCCESS;
    }

    // we use a weak reference so the SynthProxy object can be garbage collected.
    pJniStorage->tts_ref = env->NewGlobalRef(weak_this);

    // save the JNI resources so we can use them (and free them) later
    env->SetIntField(thiz, javaTTSFields.synthProxyFieldJniData, (int)pJniStorage);

    env->ReleaseStringUTFChars(nativeSoLib, nativeSoLibNativeString);
    env->ReleaseStringUTFChars(engConfig, engConfigString);

    return result;
}


static void
android_tts_SynthProxy_native_finalize(JNIEnv *env, jobject thiz, jint jniData)
{
    //LOGV("entering android_tts_SynthProxy_finalize()");
    if (jniData == 0) {
        //LOGE("android_tts_SynthProxy_native_finalize(): invalid JNI data");
        return;
    }

    Mutex::Autolock l(engineMutex);

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;
    env->DeleteGlobalRef(pSynthData->tts_ref);
    delete pSynthData;

    env->SetIntField(thiz, javaTTSFields.synthProxyFieldJniData, 0);
}


static void
android_tts_SynthProxy_shutdown(JNIEnv *env, jobject thiz, jint jniData)
{
    //LOGV("entering android_tts_SynthProxy_shutdown()");

    // do everything a call to finalize would
    android_tts_SynthProxy_native_finalize(env, thiz, jniData);
}


static int
android_tts_SynthProxy_isLanguageAvailable(JNIEnv *env, jobject thiz, jint jniData,
        jstring language, jstring country, jstring variant)
{
    int result = ANDROID_TTS_LANG_NOT_SUPPORTED;

    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_isLanguageAvailable(): invalid JNI data");
        return result;
    }

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;
    const char *langNativeString = env->GetStringUTFChars(language, 0);
    const char *countryNativeString = env->GetStringUTFChars(country, 0);
    const char *variantNativeString = env->GetStringUTFChars(variant, 0);

    android_tts_engine_t *engine = pSynthData->mEngine;

    if (engine) {
        result = engine->funcs->isLanguageAvailable(engine,langNativeString,
                countryNativeString, variantNativeString);
    }
    env->ReleaseStringUTFChars(language, langNativeString);
    env->ReleaseStringUTFChars(country, countryNativeString);
    env->ReleaseStringUTFChars(variant, variantNativeString);
    return result;
}

static int
android_tts_SynthProxy_setConfig(JNIEnv *env, jobject thiz, jint jniData, jstring engineConfig)
{
    int result = ANDROID_TTS_FAILURE;

    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_setConfig(): invalid JNI data");
        return result;
    }

    Mutex::Autolock l(engineMutex);

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;
    const char *engineConfigNativeString = env->GetStringUTFChars(engineConfig, 0);
    android_tts_engine_t *engine = pSynthData->mEngine;

    if (engine) {
        result = engine->funcs->setProperty(engine,ANDROID_TTS_ENGINE_PROPERTY_CONFIG,
                engineConfigNativeString, strlen(engineConfigNativeString));
    }
    env->ReleaseStringUTFChars(engineConfig, engineConfigNativeString);

    return result;
}

static int
android_tts_SynthProxy_setLanguage(JNIEnv *env, jobject thiz, jint jniData,
        jstring language, jstring country, jstring variant)
{
    int result = ANDROID_TTS_LANG_NOT_SUPPORTED;

    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_setLanguage(): invalid JNI data");
        return result;
    }

    Mutex::Autolock l(engineMutex);

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;
    const char *langNativeString = env->GetStringUTFChars(language, 0);
    const char *countryNativeString = env->GetStringUTFChars(country, 0);
    const char *variantNativeString = env->GetStringUTFChars(variant, 0);
    android_tts_engine_t *engine = pSynthData->mEngine;

    if (engine) {
        result = engine->funcs->setLanguage(engine, langNativeString,
                countryNativeString, variantNativeString);
    }
    env->ReleaseStringUTFChars(language, langNativeString);
    env->ReleaseStringUTFChars(country, countryNativeString);
    env->ReleaseStringUTFChars(variant, variantNativeString);
    return result;
}


static int
android_tts_SynthProxy_loadLanguage(JNIEnv *env, jobject thiz, jint jniData,
        jstring language, jstring country, jstring variant)
{
    int result = ANDROID_TTS_LANG_NOT_SUPPORTED;

    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_loadLanguage(): invalid JNI data");
        return result;
    }

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;
    const char *langNativeString = env->GetStringUTFChars(language, 0);
    const char *countryNativeString = env->GetStringUTFChars(country, 0);
    const char *variantNativeString = env->GetStringUTFChars(variant, 0);
    android_tts_engine_t *engine = pSynthData->mEngine;

    if (engine) {
        result = engine->funcs->loadLanguage(engine, langNativeString,
                countryNativeString, variantNativeString);
    }
    env->ReleaseStringUTFChars(language, langNativeString);
    env->ReleaseStringUTFChars(country, countryNativeString);
    env->ReleaseStringUTFChars(variant, variantNativeString);

    return result;
}


static int
android_tts_SynthProxy_setSpeechRate(JNIEnv *env, jobject thiz, jint jniData,
        jint speechRate)
{
    int result = ANDROID_TTS_FAILURE;

    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_setSpeechRate(): invalid JNI data");
        return result;
    }

    int bufSize = 12;
    char buffer [bufSize];
    sprintf(buffer, "%d", speechRate);

    Mutex::Autolock l(engineMutex);

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;
    LOGI("setting speech rate to %d", speechRate);
    android_tts_engine_t *engine = pSynthData->mEngine;

    if (engine) {
        result = engine->funcs->setProperty(engine, "rate", buffer, bufSize);
    }

    return result;
}


static int
android_tts_SynthProxy_setPitch(JNIEnv *env, jobject thiz, jint jniData,
        jint pitch)
{
    int result = ANDROID_TTS_FAILURE;

    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_setPitch(): invalid JNI data");
        return result;
    }

    Mutex::Autolock l(engineMutex);

    int bufSize = 12;
    char buffer [bufSize];
    sprintf(buffer, "%d", pitch);

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;
    LOGI("setting pitch to %d", pitch);
    android_tts_engine_t *engine = pSynthData->mEngine;

    if (engine) {
        result = engine->funcs->setProperty(engine, "pitch", buffer, bufSize);
    }

    return result;
}


static int
android_tts_SynthProxy_synthesizeToFile(JNIEnv *env, jobject thiz, jint jniData,
        jstring textJavaString, jstring filenameJavaString)
{
    int result = ANDROID_TTS_FAILURE;

    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_synthesizeToFile(): invalid JNI data");
        return result;
    }

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;
    if (!pSynthData->mEngine) {
        LOGE("android_tts_SynthProxy_synthesizeToFile(): invalid engine handle");
        return result;
    }

    initializeFilter();

    Mutex::Autolock l(engineMutex);

    // Retrieve audio parameters before writing the file header
    AudioSystem::audio_format encoding;
    uint32_t rate = DEFAULT_TTS_RATE;
    int channels = DEFAULT_TTS_NB_CHANNELS;
    android_tts_engine_t *engine = pSynthData->mEngine;
    android_tts_audio_format_t  format = ANDROID_TTS_AUDIO_FORMAT_DEFAULT;

    engine->funcs->setAudioFormat(engine, &format, &rate, &channels);

    switch (format) {
    case ANDROID_TTS_AUDIO_FORMAT_PCM_16_BIT:
        encoding = AudioSystem::PCM_16_BIT;
        break;
    case ANDROID_TTS_AUDIO_FORMAT_PCM_8_BIT:
        encoding = AudioSystem::PCM_8_BIT;
        break;
    default:
        LOGE("android_tts_SynthProxy_synthesizeToFile(): engine uses invalid format");
        return result;
    }

    const char *filenameNativeString =
            env->GetStringUTFChars(filenameJavaString, 0);
    const char *textNativeString = env->GetStringUTFChars(textJavaString, 0);

    afterSynthData_t* pForAfter = new (afterSynthData_t);
    pForAfter->jniStorage = jniData;
    pForAfter->usageMode  = USAGEMODE_WRITE_TO_FILE;

    pForAfter->outputFile = fopen(filenameNativeString, "wb");

    if (pForAfter->outputFile == NULL) {
        LOGE("android_tts_SynthProxy_synthesizeToFile(): error creating output file");
        delete pForAfter;
        return result;
    }

    // Write 44 blank bytes for WAV header, then come back and fill them in
    // after we've written the audio data
    char header[44];
    fwrite(header, 1, 44, pForAfter->outputFile);

    unsigned int unique_identifier;

    memset(pSynthData->mBuffer, 0, pSynthData->mBufferSize);

    result = engine->funcs->synthesizeText(engine, textNativeString,
            pSynthData->mBuffer, pSynthData->mBufferSize, (void *)pForAfter);

    long filelen = ftell(pForAfter->outputFile);

    int samples = (((int)filelen) - 44) / 2;
    header[0] = 'R';
    header[1] = 'I';
    header[2] = 'F';
    header[3] = 'F';
    ((uint32_t *)(&header[4]))[0] = filelen - 8;
    header[8] = 'W';
    header[9] = 'A';
    header[10] = 'V';
    header[11] = 'E';

    header[12] = 'f';
    header[13] = 'm';
    header[14] = 't';
    header[15] = ' ';

    ((uint32_t *)(&header[16]))[0] = 16;  // size of fmt

    int sampleSizeInByte = (encoding == AudioSystem::PCM_16_BIT ? 2 : 1);

    ((unsigned short *)(&header[20]))[0] = 1;  // format
    ((unsigned short *)(&header[22]))[0] = channels;  // channels
    ((uint32_t *)(&header[24]))[0] = rate;  // samplerate
    ((uint32_t *)(&header[28]))[0] = rate * sampleSizeInByte * channels;// byterate
    ((unsigned short *)(&header[32]))[0] = sampleSizeInByte * channels;  // block align
    ((unsigned short *)(&header[34]))[0] = sampleSizeInByte * 8;  // bits per sample

    header[36] = 'd';
    header[37] = 'a';
    header[38] = 't';
    header[39] = 'a';

    ((uint32_t *)(&header[40]))[0] = samples * 2;  // size of data

    // Skip back to the beginning and rewrite the header
    fseek(pForAfter->outputFile, 0, SEEK_SET);
    fwrite(header, 1, 44, pForAfter->outputFile);

    fflush(pForAfter->outputFile);
    fclose(pForAfter->outputFile);

    delete pForAfter;
    pForAfter = NULL;

    env->ReleaseStringUTFChars(textJavaString, textNativeString);
    env->ReleaseStringUTFChars(filenameJavaString, filenameNativeString);

    return result;
}


static int
android_tts_SynthProxy_speak(JNIEnv *env, jobject thiz, jint jniData,
        jstring textJavaString, jint javaStreamType)
{
    int result = ANDROID_TTS_FAILURE;

    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_speak(): invalid JNI data");
        return result;
    }

    initializeFilter();

    Mutex::Autolock l(engineMutex);

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;

    pSynthData->mPlayLock.lock();
    pSynthData->mPlayState = SYNTHPLAYSTATE_IS_PLAYING;
    pSynthData->mPlayLock.unlock();

    afterSynthData_t* pForAfter = new (afterSynthData_t);
    pForAfter->jniStorage = jniData;
    pForAfter->usageMode  = USAGEMODE_PLAY_IMMEDIATELY;
    pForAfter->streamType = (AudioSystem::stream_type) javaStreamType;

    if (pSynthData->mEngine) {
        const char *textNativeString = env->GetStringUTFChars(textJavaString, 0);
        memset(pSynthData->mBuffer, 0, pSynthData->mBufferSize);
        android_tts_engine_t *engine = pSynthData->mEngine;

        result = engine->funcs->synthesizeText(engine, textNativeString,
                pSynthData->mBuffer, pSynthData->mBufferSize, (void *)pForAfter);
        env->ReleaseStringUTFChars(textJavaString, textNativeString);
    }

    return result;
}


static int
android_tts_SynthProxy_stop(JNIEnv *env, jobject thiz, jint jniData)
{
    int result = ANDROID_TTS_FAILURE;

    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_stop(): invalid JNI data");
        return result;
    }

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;

    pSynthData->mPlayLock.lock();
    pSynthData->mPlayState = SYNTHPLAYSTATE_IS_STOPPED;
    if (pSynthData->mAudioOut) {
        pSynthData->mAudioOut->stop();
    }
    pSynthData->mPlayLock.unlock();

    android_tts_engine_t *engine = pSynthData->mEngine;
    if (engine) {
        result = engine->funcs->stop(engine);
    }

    return result;
}


static int
android_tts_SynthProxy_stopSync(JNIEnv *env, jobject thiz, jint jniData)
{
    int result = ANDROID_TTS_FAILURE;

    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_stop(): invalid JNI data");
        return result;
    }

    // perform a regular stop
    result = android_tts_SynthProxy_stop(env, thiz, jniData);
    // but wait on the engine having released the engine mutex which protects
    // the synthesizer resources.
    engineMutex.lock();
    engineMutex.unlock();

    return result;
}


static jobjectArray
android_tts_SynthProxy_getLanguage(JNIEnv *env, jobject thiz, jint jniData)
{
    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_getLanguage(): invalid JNI data");
        return NULL;
    }

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;

    if (pSynthData->mEngine) {
        size_t bufSize = 100;
        char lang[bufSize];
        char country[bufSize];
        char variant[bufSize];
        memset(lang, 0, bufSize);
        memset(country, 0, bufSize);
        memset(variant, 0, bufSize);
        jobjectArray retLocale = (jobjectArray)env->NewObjectArray(3,
                env->FindClass("java/lang/String"), env->NewStringUTF(""));

        android_tts_engine_t *engine = pSynthData->mEngine;
        engine->funcs->getLanguage(engine, lang, country, variant);
        env->SetObjectArrayElement(retLocale, 0, env->NewStringUTF(lang));
        env->SetObjectArrayElement(retLocale, 1, env->NewStringUTF(country));
        env->SetObjectArrayElement(retLocale, 2, env->NewStringUTF(variant));
        return retLocale;
    } else {
        return NULL;
    }
}


JNIEXPORT int JNICALL
android_tts_SynthProxy_getRate(JNIEnv *env, jobject thiz, jint jniData)
{
    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_getRate(): invalid JNI data");
        return 0;
    }

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;
    size_t bufSize = 100;

    char buf[bufSize];
    memset(buf, 0, bufSize);
    // TODO check return codes
    android_tts_engine_t *engine = pSynthData->mEngine;
    if (engine) {
        engine->funcs->getProperty(engine,"rate", buf, &bufSize);
    }
    return atoi(buf);
}

// Dalvik VM type signatures
static JNINativeMethod gMethods[] = {
    {   "native_stop",
        "(I)I",
        (void*)android_tts_SynthProxy_stop
    },
    {   "native_stopSync",
        "(I)I",
        (void*)android_tts_SynthProxy_stopSync
    },
    {   "native_speak",
        "(ILjava/lang/String;I)I",
        (void*)android_tts_SynthProxy_speak
    },
    {   "native_synthesizeToFile",
        "(ILjava/lang/String;Ljava/lang/String;)I",
        (void*)android_tts_SynthProxy_synthesizeToFile
    },
    {   "native_isLanguageAvailable",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)I",
        (void*)android_tts_SynthProxy_isLanguageAvailable
    },
    {   "native_setConfig",
            "(ILjava/lang/String;)I",
            (void*)android_tts_SynthProxy_setConfig
    },
    {   "native_setLanguage",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)I",
        (void*)android_tts_SynthProxy_setLanguage
    },
    {   "native_loadLanguage",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)I",
        (void*)android_tts_SynthProxy_loadLanguage
    },
    {   "native_setSpeechRate",
        "(II)I",
        (void*)android_tts_SynthProxy_setSpeechRate
    },
    {   "native_setPitch",
        "(II)I",
        (void*)android_tts_SynthProxy_setPitch
    },
    {   "native_getLanguage",
        "(I)[Ljava/lang/String;",
        (void*)android_tts_SynthProxy_getLanguage
    },
    {   "native_getRate",
        "(I)I",
        (void*)android_tts_SynthProxy_getRate
    },
    {   "native_shutdown",
        "(I)V",
        (void*)android_tts_SynthProxy_shutdown
    },
    {   "native_setup",
        "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)I",
        (void*)android_tts_SynthProxy_native_setup
    },
    {   "native_setLowShelf",
        "(ZFFFF)I",
        (void*)android_tts_SynthProxy_setLowShelf
    },
    {   "native_finalize",
        "(I)V",
        (void*)android_tts_SynthProxy_native_finalize
    }
};

#define SP_JNIDATA_FIELD_NAME                "mJniData"
#define SP_POSTSPEECHSYNTHESIZED_METHOD_NAME "postNativeSpeechSynthesizedInJava"

static const char* const kClassPathName = "android/tts/SynthProxy";

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env = NULL;
    jint result = -1;
    jclass clazz;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        LOGE("ERROR: GetEnv failed\n");
        goto bail;
    }
    assert(env != NULL);

    clazz = env->FindClass(kClassPathName);
    if (clazz == NULL) {
        LOGE("Can't find %s", kClassPathName);
        goto bail;
    }

    javaTTSFields.synthProxyClass = clazz;
    javaTTSFields.synthProxyFieldJniData = NULL;
    javaTTSFields.synthProxyMethodPost = NULL;

    javaTTSFields.synthProxyFieldJniData = env->GetFieldID(clazz,
            SP_JNIDATA_FIELD_NAME, "I");
    if (javaTTSFields.synthProxyFieldJniData == NULL) {
        LOGE("Can't find %s.%s field", kClassPathName, SP_JNIDATA_FIELD_NAME);
        goto bail;
    }

    javaTTSFields.synthProxyMethodPost = env->GetStaticMethodID(clazz,
            SP_POSTSPEECHSYNTHESIZED_METHOD_NAME, "(Ljava/lang/Object;II)V");
    if (javaTTSFields.synthProxyMethodPost == NULL) {
        LOGE("Can't find %s.%s method", kClassPathName, SP_POSTSPEECHSYNTHESIZED_METHOD_NAME);
        goto bail;
    }

    if (jniRegisterNativeMethods(
            env, kClassPathName, gMethods, NELEM(gMethods)) < 0)
        goto bail;

    /* success -- return valid version number */
    result = JNI_VERSION_1_4;

 bail:
    return result;
}
