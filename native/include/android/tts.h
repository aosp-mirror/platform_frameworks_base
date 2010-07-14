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
#ifndef ANDROID_TTS_H
#define ANDROID_TTS_H 

// This header defines the interface used by the Android platform
// to access Text-To-Speech functionality in shared libraries that implement
// speech synthesis and the management of resources associated with the
// synthesis.

// The shared library must contain a function named "android_getTtsEngine"
// that returns an 'android_tts_engine_t' instance.

#ifdef __cplusplus
extern "C" {
#endif

#define ANDROID_TTS_ENGINE_PROPERTY_CONFIG "engineConfig"
#define ANDROID_TTS_ENGINE_PROPERTY_PITCH  "pitch"
#define ANDROID_TTS_ENGINE_PROPERTY_RATE   "rate"
#define ANDROID_TTS_ENGINE_PROPERTY_VOLUME "volume"

typedef enum {
    ANDROID_TTS_SUCCESS                 = 0,
    ANDROID_TTS_FAILURE                 = -1,
    ANDROID_TTS_FEATURE_UNSUPPORTED     = -2,
    ANDROID_TTS_VALUE_INVALID           = -3,
    ANDROID_TTS_PROPERTY_UNSUPPORTED    = -4,
    ANDROID_TTS_PROPERTY_SIZE_TOO_SMALL = -5,
    ANDROID_TTS_MISSING_RESOURCES       = -6
} android_tts_result_t;

typedef enum {
    ANDROID_TTS_LANG_COUNTRY_VAR_AVAILABLE = 2,
    ANDROID_TTS_LANG_COUNTRY_AVAILABLE    = 1,
    ANDROID_TTS_LANG_AVAILABLE            = 0,
    ANDROID_TTS_LANG_MISSING_DATA         = -1,
    ANDROID_TTS_LANG_NOT_SUPPORTED        = -2
} android_tts_support_result_t;

typedef enum {
    ANDROID_TTS_SYNTH_DONE              = 0,
    ANDROID_TTS_SYNTH_PENDING           = 1
} android_tts_synth_status_t;

typedef enum {
    ANDROID_TTS_CALLBACK_HALT           = 0,
    ANDROID_TTS_CALLBACK_CONTINUE       = 1
} android_tts_callback_status_t;

// Supported audio formats
typedef enum {
    ANDROID_TTS_AUDIO_FORMAT_INVALID    = -1,
    ANDROID_TTS_AUDIO_FORMAT_DEFAULT    = 0,
    ANDROID_TTS_AUDIO_FORMAT_PCM_16_BIT = 1,
    ANDROID_TTS_AUDIO_FORMAT_PCM_8_BIT  = 2,
} android_tts_audio_format_t;


/* An android_tts_engine_t object can be anything, but must have,
 * as its first field, a pointer to a table of functions.
 *
 * See the full definition of struct android_tts_engine_t_funcs_t
 * below for details.
 */
typedef struct android_tts_engine_funcs_t  android_tts_engine_funcs_t;

typedef struct {
    android_tts_engine_funcs_t *funcs;
} android_tts_engine_t;

/* This function must be located in the TTS Engine shared library
 * and must return the address of an android_tts_engine_t library.
 */
extern android_tts_engine_t *android_getTtsEngine();

/* Including the old version for legacy support (Froyo compatibility).
 * This should return the same thing as android_getTtsEngine.
 */
extern "C" android_tts_engine_t *getTtsEngine();

// A callback type used to notify the framework of new synthetized
// audio samples, status will be SYNTH_DONE for the last sample of
// the last request, of SYNTH_PENDING otherwise.
//
// This is passed by the framework to the engine through the
// 'engine_init' function (see below).
//
// The callback for synthesis completed takes:
// @param [inout] void *&       - The userdata pointer set in the original
//                                 synth call
// @param [in]    uint32_t      - Track sampling rate in Hz
// @param [in]    uint32_t      - The audio format
// @param [in]    int           - The number of channels
// @param [inout] int8_t *&     - A buffer of audio data only valid during the
//                                execution of the callback
// @param [inout] size_t  &     - The size of the buffer
// @param [in] tts_synth_status - indicate whether the synthesis is done, or
//                                 if more data is to be synthesized.
// @return TTS_CALLBACK_HALT to indicate the synthesis must stop,
//         TTS_CALLBACK_CONTINUE to indicate the synthesis must continue if
//            there is more data to produce.
typedef android_tts_callback_status_t (*android_tts_synth_cb_t)
            (void **pUserData,
             uint32_t trackSamplingHz,
             android_tts_audio_format_t audioFormat,
             int channelCount,
             int8_t **pAudioBuffer,
             size_t *pBufferSize,
             android_tts_synth_status_t status);


// The table of function pointers that the android_tts_engine_t must point to.
// Note that each of these functions will take a handle to the engine itself
// as their first parameter.
//

struct android_tts_engine_funcs_t {
    // reserved fields, ignored by the framework
    // they must be placed here to ensure binary compatibility
    // of legacy binary plugins.
    void *reserved[2];

    // Initialize the TTS engine and returns whether initialization succeeded.
    // @param synthDoneCBPtr synthesis callback function pointer
    // @return TTS_SUCCESS, or TTS_FAILURE
    android_tts_result_t (*init)
            (void *engine,
             android_tts_synth_cb_t synthDonePtr,
             const char *engineConfig);

    // Shut down the TTS engine and releases all associated resources.
    // @return TTS_SUCCESS, or TTS_FAILURE
    android_tts_result_t (*shutdown)
            (void *engine);

    // Interrupt synthesis and flushes any synthesized data that hasn't been
    // output yet. This will block until callbacks underway are completed.
    // @return TTS_SUCCESS, or TTS_FAILURE
    android_tts_result_t (*stop)
            (void *engine);

    // Returns the level of support for the language, country and variant.
    // @return TTS_LANG_COUNTRY_VAR_AVAILABLE if the language, country and variant are supported,
    //            and the corresponding resources are correctly installed
    //         TTS_LANG_COUNTRY_AVAILABLE if the language and country are supported and the
    //             corresponding resources are correctly installed, but there is no match for
    //             the specified variant
    //         TTS_LANG_AVAILABLE if the language is supported and the
    //             corresponding resources are correctly installed, but there is no match for
    //             the specified country and variant
    //         TTS_LANG_MISSING_DATA if the required resources to provide any level of support
    //             for the language are not correctly installed
    //         TTS_LANG_NOT_SUPPORTED if the language is not supported by the TTS engine.
    android_tts_support_result_t (*isLanguageAvailable)
            (void *engine,
             const char *lang,
             const char *country,
             const char *variant);

    // Load the resources associated with the specified language. The loaded
    // language will only be used once a call to setLanguage() with the same
    // language value is issued. Language and country values are coded according to the ISO three
    // letter codes for languages and countries, as can be retrieved from a java.util.Locale
    // instance. The variant value is encoded as the variant string retrieved from a
    // java.util.Locale instance built with that variant data.
    // @param lang pointer to the ISO three letter code for the language
    // @param country pointer to the ISO three letter code for the country
    // @param variant pointer to the variant code
    // @return TTS_SUCCESS, or TTS_FAILURE
    android_tts_result_t (*loadLanguage)
            (void *engine,
             const char *lang,
             const char *country,
             const char *variant);

    // Load the resources associated with the specified language, country and Locale variant.
    // The loaded language will only be used once a call to setLanguageFromLocale() with the same
    // language value is issued. Language and country values are coded according to the ISO three
    // letter codes for languages and countries, as can be retrieved from a java.util.Locale
    // instance. The variant value is encoded as the variant string retrieved from a
    // java.util.Locale instance built with that variant data.
    // @param lang pointer to the ISO three letter code for the language
    // @param country pointer to the ISO three letter code for the country
    // @param variant pointer to the variant code
    // @return TTS_SUCCESS, or TTS_FAILURE
    android_tts_result_t (*setLanguage)
            (void *engine,
             const char *lang,
             const char *country,
             const char *variant);

    // Retrieve the currently set language, country and variant, or empty strings if none of
    // parameters have been set. Language and country are represented by their 3-letter ISO code
    // @param[out]   pointer to the retrieved 3-letter code language value
    // @param[out]   pointer to the retrieved 3-letter code country value
    // @param[out]   pointer to the retrieved variant value
    // @return TTS_SUCCESS, or TTS_FAILURE
    android_tts_result_t (*getLanguage)
            (void *engine,
             char *language,
             char *country,
             char *variant);

    // Notifies the engine what audio parameters should be used for the synthesis.
    // This is meant to be used as a hint, the engine implementation will set the output values
    // to those of the synthesis format, based on a given hint.
    // @param[inout] encoding in: the desired audio sample format
    //                         out: the format used by the TTS engine
    // @param[inout] rate in: the desired audio sample rate
    //                         out: the sample rate used by the TTS engine
    // @param[inout] channels in: the desired number of audio channels
    //                         out: the number of channels used by the TTS engine
    // @return TTS_SUCCESS, or TTS_FAILURE
    android_tts_result_t (*setAudioFormat)
            (void *engine,
             android_tts_audio_format_t* pEncoding,
             uint32_t* pRate,
             int* pChannels);

    // Set a property for the the TTS engine
    // "size" is the maximum size of "value" for properties "property"
    // @param property pointer to the property name
    // @param value    pointer to the property value
    // @param size     maximum size required to store this type of property
    // @return         TTS_PROPERTY_UNSUPPORTED, or TTS_SUCCESS, or TTS_FAILURE,
    //                  or TTS_VALUE_INVALID
    android_tts_result_t (*setProperty)
            (void *engine,
             const char *property,
             const char *value,
             const size_t size);

    // Retrieve a property from the TTS engine
    // @param        property pointer to the property name
    // @param[out]   value    pointer to the retrieved language value
    // @param[inout] iosize   in: stores the size available to store the
    //                          property value.
    //                        out: stores the size required to hold the language
    //                          value if getLanguage() returned
    //                          TTS_PROPERTY_SIZE_TOO_SMALL, unchanged otherwise
    // @return TTS_PROPERTY_UNSUPPORTED, or TTS_SUCCESS,
    //         or TTS_PROPERTY_SIZE_TOO_SMALL
    android_tts_result_t (*getProperty)
            (void *engine,
             const char *property,
             char *value,
             size_t *iosize);

    // Synthesize the text.
    // As the synthesis is performed, the engine invokes the callback to notify
    // the TTS framework that it has filled the given buffer, and indicates how
    // many bytes it wrote. The callback is called repeatedly until the engine
    // has generated all the audio data corresponding to the text.
    // Note about the format of the input: the text parameter may use the
    // following elements
    // and their respective attributes as defined in the SSML 1.0 specification:
    //    * lang
    //    * say-as:
    //          o interpret-as
    //    * phoneme
    //    * voice:
    //          o gender,
    //          o age,
    //          o variant,
    //          o name
    //    * emphasis
    //    * break:
    //          o strength,
    //          o time
    //    * prosody:
    //          o pitch,
    //          o contour,
    //          o range,
    //          o rate,
    //          o duration,
    //          o volume
    //    * mark
    // Differences between this text format and SSML are:
    //    * full SSML documents are not supported
    //    * namespaces are not supported
    // Text is coded in UTF-8.
    // @param text      the UTF-8 text to synthesize
    // @param userdata  pointer to be returned when the call is invoked
    // @param buffer    the location where the synthesized data must be written
    // @param bufferSize the number of bytes that can be written in buffer
    // @return          TTS_SUCCESS or TTS_FAILURE
    android_tts_result_t (*synthesizeText)
            (void *engine,
             const char *text,
             int8_t *buffer,
             size_t bufferSize,
             void *userdata);
};

#ifdef __cplusplus
}
#endif

#endif /* ANDROID_TTS_H */
