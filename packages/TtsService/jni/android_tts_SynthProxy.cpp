/*
 * Copyright (C) 2009 Google Inc.
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
#define LOG_NDEBUG 0

#include <stdio.h>
#include <unistd.h>

#define LOG_TAG "SynthProxy"

#include <utils/Log.h>
#include <nativehelper/jni.h>
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <tts/TtsEngine.h>
#include <media/AudioTrack.h>

#include <dlfcn.h>

#define DEFAULT_TTS_RATE        16000
#define DEFAULT_TTS_FORMAT      AudioSystem::PCM_16_BIT
#define DEFAULT_TTS_NB_CHANNELS 1
#define DEFAULT_TTS_BUFFERSIZE  1024

#define USAGEMODE_PLAY_IMMEDIATELY 0
#define USAGEMODE_WRITE_TO_FILE    1

using namespace android;

// ----------------------------------------------------------------------------
struct fields_t {
    jfieldID    synthProxyFieldJniData;
    jclass      synthProxyClass;
    jmethodID   synthProxyMethodPost;
};

struct afterSynthData_t {
    jint jniStorage;
    int  usageMode;
    FILE* outputFile;
};

// ----------------------------------------------------------------------------
static fields_t javaTTSFields;

// ----------------------------------------------------------------------------
class SynthProxyJniStorage {
    public :
        //jclass                    tts_class;
        jobject                   tts_ref;
        TtsEngine*                mNativeSynthInterface;
        AudioTrack*               mAudioOut;
        uint32_t                  mSampleRate;
        AudioSystem::audio_format mAudFormat;
        int                       mNbChannels;
        int8_t *                  mBuffer;
        size_t                    mBufferSize;

        SynthProxyJniStorage() {
            //tts_class = NULL;
            tts_ref = NULL;
            mNativeSynthInterface = NULL;
            mAudioOut = NULL;
            mSampleRate = DEFAULT_TTS_RATE;
            mAudFormat  = DEFAULT_TTS_FORMAT;
            mNbChannels = DEFAULT_TTS_NB_CHANNELS;
            mBufferSize = DEFAULT_TTS_BUFFERSIZE;
            mBuffer = new int8_t[mBufferSize];
        }

        ~SynthProxyJniStorage() {
            killAudio();
            if (mNativeSynthInterface) {
                mNativeSynthInterface->shutdown();
                mNativeSynthInterface = NULL;
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

        void createAudioOut(uint32_t rate, AudioSystem::audio_format format,
                int channel) {
            mSampleRate = rate;
            mAudFormat  = format;
            mNbChannels = channel;

            // TODO use the TTS stream type
            int streamType = AudioSystem::MUSIC;

            // retrieve system properties to ensure successful creation of the
            // AudioTrack object for playback
            int afSampleRate;
            if (AudioSystem::getOutputSamplingRate(&afSampleRate, streamType) != NO_ERROR) {
                afSampleRate = 44100;
            }
            int afFrameCount;
            if (AudioSystem::getOutputFrameCount(&afFrameCount, streamType) != NO_ERROR) {
                afFrameCount = 2048;
            }
            uint32_t afLatency;
            if (AudioSystem::getOutputLatency(&afLatency, streamType) != NO_ERROR) {
                afLatency = 500;
            }
            uint32_t minBufCount = afLatency / ((1000 * afFrameCount)/afSampleRate);
            if (minBufCount < 2) minBufCount = 2;
            int minFrameCount = (afFrameCount * rate * minBufCount)/afSampleRate;

            mAudioOut = new AudioTrack(streamType, rate, format, channel,
                    minFrameCount > 4096 ? minFrameCount : 4096,
                    0, 0, 0, 0); // not using an AudioTrack callback

            if (mAudioOut->initCheck() != NO_ERROR) {
              LOGI("AudioTrack error");
              delete mAudioOut;
              mAudioOut = NULL;
            } else {
              //LOGI("AudioTrack OK");
              mAudioOut->start();
              LOGI("AudioTrack started");
            }
        }
};


// ----------------------------------------------------------------------------
void prepAudioTrack(SynthProxyJniStorage* pJniData,
        uint32_t rate, AudioSystem::audio_format format, int channel)
{
    // Don't bother creating a new audiotrack object if the current
    // object is already set.
    if ( pJniData->mAudioOut &&
         (rate == pJniData->mSampleRate) &&
         (format == pJniData->mAudFormat) &&
         (channel == pJniData->mNbChannels) ){
        return;
    }
    if (pJniData->mAudioOut){
        pJniData->killAudio();
    }
    pJniData->createAudioOut(rate, format, channel);
}


// ----------------------------------------------------------------------------
/*
 * Callback from TTS engine.
 * Directly speaks using AudioTrack or write to file
 */
static tts_callback_status ttsSynthDoneCB(void *& userdata, uint32_t rate,
                           AudioSystem::audio_format format, int channel,
                           int8_t *&wav, size_t &bufferSize, tts_synth_status status) {
    //LOGV("ttsSynthDoneCallback: %d bytes", bufferSize);

    if (userdata == NULL){
        LOGE("userdata == NULL");
        return TTS_CALLBACK_HALT;
    }
    afterSynthData_t* pForAfter = (afterSynthData_t*)userdata;
    SynthProxyJniStorage* pJniData = (SynthProxyJniStorage*)(pForAfter->jniStorage);

    if (pForAfter->usageMode == USAGEMODE_PLAY_IMMEDIATELY){
        //LOGV("Direct speech");

        if (wav == NULL) {
            delete pForAfter;
            LOGI("Null: speech has completed");
        }

        if (bufferSize > 0) {
            prepAudioTrack(pJniData, rate, format, channel);
            if (pJniData->mAudioOut) {
                pJniData->mAudioOut->write(wav, bufferSize);
                //LOGV("AudioTrack wrote: %d bytes", bufferSize);
            } else {
                LOGE("Can't play, null audiotrack");
            }
        }
    } else  if (pForAfter->usageMode == USAGEMODE_WRITE_TO_FILE) {
        LOGV("Save to file");
        if (wav == NULL) {
            delete pForAfter;
            LOGV("Null: speech has completed");
            return TTS_CALLBACK_HALT;
        }
        if (bufferSize > 0){
            fwrite(wav, 1, bufferSize, pForAfter->outputFile);
        }
    }
    // Future update:
    //      For sync points in the speech, call back into the SynthProxy class through the
    //      javaTTSFields.synthProxyMethodPost methode to notify
    //      playback has completed if the synthesis is done or if a marker has been reached.

    if (status == TTS_SYNTH_DONE) {
        // this struct was allocated in the original android_tts_SynthProxy_speak call,
        // all processing matching this call is now done.
        LOGV("Speech synthesis done.");
        if (pForAfter->usageMode == USAGEMODE_PLAY_IMMEDIATELY) {
            // only delete for direct playback. When writing to a file, we still have work to do
            // in android_tts_SynthProxy_synthesizeToFile. The struct will be deleted there.
            delete pForAfter;
            pForAfter = NULL;
        }
        return TTS_CALLBACK_HALT;
    }

    // we don't update the wav (output) parameter as we'll let the next callback
    // write at the same location, we've consumed the data already, but we need
    // to update bufferSize to let the TTS engine know how much it can write the
    // next time it calls this function.
    bufferSize = pJniData->mBufferSize;

    return TTS_CALLBACK_CONTINUE;
}


// ----------------------------------------------------------------------------
static void
android_tts_SynthProxy_native_setup(JNIEnv *env, jobject thiz,
        jobject weak_this, jstring nativeSoLib)
{
    SynthProxyJniStorage* pJniStorage = new SynthProxyJniStorage();

    prepAudioTrack(pJniStorage,
            DEFAULT_TTS_RATE, DEFAULT_TTS_FORMAT, DEFAULT_TTS_NB_CHANNELS);

    const char *nativeSoLibNativeString =
            env->GetStringUTFChars(nativeSoLib, 0);

    void *engine_lib_handle = dlopen(nativeSoLibNativeString,
            RTLD_NOW | RTLD_LOCAL);
    if (engine_lib_handle==NULL) {
       LOGI("engine_lib_handle==NULL");
       // TODO report error so the TTS can't be used
    } else {
        TtsEngine *(*get_TtsEngine)() =
            reinterpret_cast<TtsEngine* (*)()>(dlsym(engine_lib_handle, "getTtsEngine"));

        pJniStorage->mNativeSynthInterface = (*get_TtsEngine)();

        if (pJniStorage->mNativeSynthInterface) {
            pJniStorage->mNativeSynthInterface->init(ttsSynthDoneCB);
        }
    }

    // we use a weak reference so the SynthProxy object can be garbage collected.
    pJniStorage->tts_ref = env->NewGlobalRef(weak_this);

    // save the JNI resources so we can use them (and free them) later
    env->SetIntField(thiz, javaTTSFields.synthProxyFieldJniData,
            (int)pJniStorage);

    env->ReleaseStringUTFChars(nativeSoLib, nativeSoLibNativeString);
}


static void
android_tts_SynthProxy_native_finalize(JNIEnv *env, jobject thiz, jint jniData)
{
    if (jniData) {
        SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;
        delete pSynthData;
    }
}


static int
android_tts_SynthProxy_isLanguageAvailable(JNIEnv *env, jobject thiz, jint jniData,
        jstring language, jstring country, jstring variant)
{
    int result = TTS_LANG_NOT_SUPPORTED;

    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_isLanguageAvailable(): invalid JNI data");
        return result;
    }

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;
    const char *langNativeString = env->GetStringUTFChars(language, 0);
    const char *countryNativeString = env->GetStringUTFChars(country, 0);
    const char *variantNativeString = env->GetStringUTFChars(variant, 0);

    if (pSynthData->mNativeSynthInterface) {
        result = pSynthData->mNativeSynthInterface->isLanguageAvailable(langNativeString,
                countryNativeString, variantNativeString);
    }
    env->ReleaseStringUTFChars(language, langNativeString);
    env->ReleaseStringUTFChars(country, countryNativeString);
    env->ReleaseStringUTFChars(variant, variantNativeString);
    return result;
}


static int
android_tts_SynthProxy_setLanguage(JNIEnv *env, jobject thiz, jint jniData,
        jstring language, jstring country, jstring variant)
{
    int result = TTS_LANG_NOT_SUPPORTED;

    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_setLanguage(): invalid JNI data");
        return result;
    }

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;
    const char *langNativeString = env->GetStringUTFChars(language, 0);
    const char *countryNativeString = env->GetStringUTFChars(country, 0);
    const char *variantNativeString = env->GetStringUTFChars(variant, 0);

    if (pSynthData->mNativeSynthInterface) {
        result = pSynthData->mNativeSynthInterface->setLanguage(langNativeString,
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
    int result = TTS_LANG_NOT_SUPPORTED;

    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_loadLanguage(): invalid JNI data");
        return result;
    }

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;
    const char *langNativeString = env->GetStringUTFChars(language, 0);
    const char *countryNativeString = env->GetStringUTFChars(country, 0);
    const char *variantNativeString = env->GetStringUTFChars(variant, 0);

    if (pSynthData->mNativeSynthInterface) {
        result = pSynthData->mNativeSynthInterface->loadLanguage(langNativeString,
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
    int result = TTS_FAILURE;

    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_setSpeechRate(): invalid JNI data");
        return result;
    }

    int bufSize = 10;
    char buffer [bufSize];
    sprintf(buffer, "%d", speechRate);

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;
    LOGI("setting speech rate to %d", speechRate);

    if (pSynthData->mNativeSynthInterface) {
        result = pSynthData->mNativeSynthInterface->setProperty("rate", buffer, bufSize);
    }

    return result;
}


static int
android_tts_SynthProxy_setPitch(JNIEnv *env, jobject thiz, jint jniData,
        jint pitch)
{
    int result = TTS_FAILURE;

    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_setPitch(): invalid JNI data");
        return result;
    }

    int bufSize = 10;
    char buffer [bufSize];
    sprintf(buffer, "%d", pitch);

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;
    LOGI("setting pitch to %d", pitch);

    if (pSynthData->mNativeSynthInterface) {
        result = pSynthData->mNativeSynthInterface->setProperty("pitch", buffer, bufSize);
    }

    return result;
}


static int
android_tts_SynthProxy_synthesizeToFile(JNIEnv *env, jobject thiz, jint jniData,
        jstring textJavaString, jstring filenameJavaString)
{
    int result = TTS_FAILURE;

    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_synthesizeToFile(): invalid JNI data");
        return result;
    }

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;
    if (!pSynthData->mNativeSynthInterface) {
        LOGE("android_tts_SynthProxy_synthesizeToFile(): invalid engine handle");
        return result;
    }

    // Retrieve audio parameters before writing the file header
    AudioSystem::audio_format encoding = DEFAULT_TTS_FORMAT;
    uint32_t rate = DEFAULT_TTS_RATE;
    int channels = DEFAULT_TTS_NB_CHANNELS;
    pSynthData->mNativeSynthInterface->setAudioFormat(encoding, rate, channels);

    if ((encoding != AudioSystem::PCM_16_BIT) && (encoding != AudioSystem::PCM_8_BIT)) {
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

    result = pSynthData->mNativeSynthInterface->synthesizeText(textNativeString,
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
        jstring textJavaString)
{
    int result = TTS_FAILURE;

    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_speak(): invalid JNI data");
        return result;
    }

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;

    if (pSynthData->mAudioOut) {
        pSynthData->mAudioOut->stop();
        pSynthData->mAudioOut->start();
    }

    afterSynthData_t* pForAfter = new (afterSynthData_t);
    pForAfter->jniStorage = jniData;
    pForAfter->usageMode  = USAGEMODE_PLAY_IMMEDIATELY;

    if (pSynthData->mNativeSynthInterface) {
        const char *textNativeString = env->GetStringUTFChars(textJavaString, 0);
        result = pSynthData->mNativeSynthInterface->synthesizeText(textNativeString,
                pSynthData->mBuffer, pSynthData->mBufferSize, (void *)pForAfter);
        env->ReleaseStringUTFChars(textJavaString, textNativeString);
    }

    return result;
}


static int
android_tts_SynthProxy_stop(JNIEnv *env, jobject thiz, jint jniData)
{
    int result = TTS_FAILURE;

    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_stop(): invalid JNI data");
        return result;
    }

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;

    if (pSynthData->mNativeSynthInterface) {
        result = pSynthData->mNativeSynthInterface->stop();
    }
    if (pSynthData->mAudioOut) {
        pSynthData->mAudioOut->stop();
    }

    return result;
}


static void
android_tts_SynthProxy_shutdown(JNIEnv *env, jobject thiz, jint jniData)
{
    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_shutdown(): invalid JNI data");
        return;
    }

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;
    if (pSynthData->mNativeSynthInterface) {
        pSynthData->mNativeSynthInterface->shutdown();
        pSynthData->mNativeSynthInterface = NULL;
    }
}


// TODO add buffer format
static void
android_tts_SynthProxy_playAudioBuffer(JNIEnv *env, jobject thiz, jint jniData,
        int bufferPointer, int bufferSize)
{
LOGI("android_tts_SynthProxy_playAudioBuffer");
    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_playAudioBuffer(): invalid JNI data");
        return;
    }

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;
    short* wav = (short*) bufferPointer;
    pSynthData->mAudioOut->write(wav, bufferSize);
    //LOGI("AudioTrack wrote: %d bytes", bufferSize);
}


static jobjectArray
android_tts_SynthProxy_getLanguage(JNIEnv *env, jobject thiz, jint jniData)
{
    if (jniData == 0) {
        LOGE("android_tts_SynthProxy_getLanguage(): invalid JNI data");
        return NULL;
    }

    SynthProxyJniStorage* pSynthData = (SynthProxyJniStorage*)jniData;

    if (pSynthData->mNativeSynthInterface) {
        size_t bufSize = 100;
        char lang[bufSize];
        char country[bufSize];
        char variant[bufSize];
        memset(lang, 0, bufSize);
        memset(country, 0, bufSize);
        memset(variant, 0, bufSize);
        jobjectArray retLocale = (jobjectArray)env->NewObjectArray(3,
                env->FindClass("java/lang/String"), env->NewStringUTF(""));
        pSynthData->mNativeSynthInterface->getLanguage(lang, country, variant);
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
    if (pSynthData->mNativeSynthInterface) {
        pSynthData->mNativeSynthInterface->getProperty("rate", buf, &bufSize);
    }
    return atoi(buf);
}

// Dalvik VM type signatures
static JNINativeMethod gMethods[] = {
    {   "native_stop",
        "(I)I",
        (void*)android_tts_SynthProxy_stop
    },
    {   "native_speak",
        "(ILjava/lang/String;)I",
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
    {   "native_playAudioBuffer",
        "(III)V",
        (void*)android_tts_SynthProxy_playAudioBuffer
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
        "(Ljava/lang/Object;Ljava/lang/String;)V",
        (void*)android_tts_SynthProxy_native_setup
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
