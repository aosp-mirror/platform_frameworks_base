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
#include <media/AudioSystem.h>

// This header defines the interface used by the Android platform
// to access Text-To-Speech functionality in shared libraries that implement
// speech synthesis and the management of resources associated with the
// synthesis.
// An example of the implementation of this interface can be found in
// FIXME: add path+name to implementation of default TTS engine
// Libraries implementing this interface are used in:
//  frameworks/base/tts/jni/android_tts_SpeechSynthesis.cpp

namespace android {

#define ANDROID_TTS_ENGINE_PROPERTY_CONFIG "engineConfig"
#define ANDROID_TTS_ENGINE_PROPERTY_PITCH  "pitch"
#define ANDROID_TTS_ENGINE_PROPERTY_RATE   "rate"
#define ANDROID_TTS_ENGINE_PROPERTY_VOLUME "volume"


enum tts_synth_status {
    TTS_SYNTH_DONE              = 0,
    TTS_SYNTH_PENDING           = 1
};

enum tts_callback_status {
    TTS_CALLBACK_HALT           = 0,
    TTS_CALLBACK_CONTINUE       = 1
};

// The callback is used by the implementation of this interface to notify its
// client, the Android TTS service, that the last requested synthesis has been
// completed. // TODO reword
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
typedef tts_callback_status (synthDoneCB_t)(void *&, uint32_t,
        uint32_t, int, int8_t *&, size_t&, tts_synth_status);

class TtsEngine;
extern "C" TtsEngine* getTtsEngine();

enum tts_result {
    TTS_SUCCESS                 = 0,
    TTS_FAILURE                 = -1,
    TTS_FEATURE_UNSUPPORTED     = -2,
    TTS_VALUE_INVALID           = -3,
    TTS_PROPERTY_UNSUPPORTED    = -4,
    TTS_PROPERTY_SIZE_TOO_SMALL = -5,
    TTS_MISSING_RESOURCES       = -6
};

enum tts_support_result {
    TTS_LANG_COUNTRY_VAR_AVAILABLE = 2,
    TTS_LANG_COUNTRY_AVAILABLE = 1,
    TTS_LANG_AVAILABLE = 0,
    TTS_LANG_MISSING_DATA = -1,
    TTS_LANG_NOT_SUPPORTED = -2
};

class TtsEngine
{
public:
    virtual ~TtsEngine() {}

    // Initialize the TTS engine and returns whether initialization succeeded.
    // @param synthDoneCBPtr synthesis callback function pointer
    // @return TTS_SUCCESS, or TTS_FAILURE
    virtual tts_result init(synthDoneCB_t synthDoneCBPtr, const char *engineConfig);

    // Shut down the TTS engine and releases all associated resources.
    // @return TTS_SUCCESS, or TTS_FAILURE
    virtual tts_result shutdown();

    // Interrupt synthesis and flushes any synthesized data that hasn't been
    // output yet. This will block until callbacks underway are completed.
    // @return TTS_SUCCESS, or TTS_FAILURE
    virtual tts_result stop();

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
    virtual tts_support_result isLanguageAvailable(const char *lang, const char *country,
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
    virtual tts_result loadLanguage(const char *lang, const char *country, const char *variant);

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
    virtual tts_result setLanguage(const char *lang, const char *country, const char *variant);

    // Retrieve the currently set language, country and variant, or empty strings if none of
    // parameters have been set. Language and country are represented by their 3-letter ISO code
    // @param[out]   pointer to the retrieved 3-letter code language value
    // @param[out]   pointer to the retrieved 3-letter code country value
    // @param[out]   pointer to the retrieved variant value
    // @return TTS_SUCCESS, or TTS_FAILURE
    virtual tts_result getLanguage(char *language, char *country, char *variant);

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
    virtual tts_result setAudioFormat(AudioSystem::audio_format& encoding, uint32_t& rate,
            int& channels);

    // Set a property for the the TTS engine
    // "size" is the maximum size of "value" for properties "property"
    // @param property pointer to the property name
    // @param value    pointer to the property value
    // @param size     maximum size required to store this type of property
    // @return         TTS_PROPERTY_UNSUPPORTED, or TTS_SUCCESS, or TTS_FAILURE,
    //                  or TTS_VALUE_INVALID
    virtual tts_result setProperty(const char *property, const char *value,
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
    virtual tts_result getProperty(const char *property, char *value,
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
    virtual tts_result synthesizeText(const char *text, int8_t *buffer,
            size_t bufferSize, void *userdata);

};

} // namespace android

