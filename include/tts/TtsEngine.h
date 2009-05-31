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
// to access Text-To-Speech functionality in shared libraries that implement speech
// synthesis and the management of resources associated with the synthesis.
// An example of the implementation of this interface can be found in 
// FIXME: add path+name to implementation of default TTS engine
// Libraries implementing this interface are used in:
//  frameworks/base/tts/jni/android_tts_SpeechSynthesis.cpp

namespace android {

// The callback is used by the implementation of this interface to notify its
// client, the Android TTS service, that the last requested synthesis has been
// completed.
// The callback for synthesis completed takes:
//    void *       - The userdata pointer set in the original synth call
//    uint32_t     - Track sampling rate in Hz
//    audio_format - The AudioSystem::audio_format enum
//    int          - The number of channels
//    int8_t *     - A buffer of audio data only valid during the execution of the callback
//    size_t       - The size of the buffer
// Note about memory management:
//    The implementation of TtsEngine is responsible for the management of the memory
//    it allocates to store the synthesized speech. After the execution of the callback
//    to hand the synthesized data to the client of TtsEngine, the TTS engine is
//    free to reuse or free the previously allocated memory.
//    This implies that the implementation of the "synthDoneCB" callback cannot use
//    the pointer to the buffer of audio samples outside of the callback itself.
typedef void (synthDoneCB_t)(void *, uint32_t, AudioSystem::audio_format, int, int8_t *, size_t);

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

class TtsEngine
{
public:
    // Initialize the TTS engine and returns whether initialization succeeded.
    // @param synthDoneCBPtr synthesis callback function pointer
    // @return TTS_SUCCESS, or TTS_FAILURE
    virtual tts_result init(synthDoneCB_t synthDoneCBPtr);

    // Shut down the TTS engine and releases all associated resources.
    // @return TTS_SUCCESS, or TTS_FAILURE
    virtual tts_result shutdown();

    // Interrupt synthesis and flushes any synthesized data that hasn't been output yet.
    // This will block until callbacks underway are completed.
    // @return TTS_SUCCESS, or TTS_FAILURE
    virtual tts_result stop();

    // Load the resources associated with the specified language. The loaded language will
    // only be used once a call to setLanguage() with the same language value is issued.
    // Language values are based on the Android conventions for localization as described in
    // the Android platform documentation on internationalization. This implies that language
    // data is specified in the format xx-rYY, where xx is a two letter ISO 639-1 language code
    // in lowercase and rYY is a two letter ISO 3166-1-alpha-2 language code in uppercase
    // preceded by a lowercase "r".
    // @param value pointer to the language value
    // @param size  length of the language value
    // @return TTS_SUCCESS, or TTS_FAILURE
    virtual tts_result loadLanguage(const char *value, const size_t size);

    // Signal the engine to use the specified language. This will force the language to be
    // loaded if it wasn't loaded previously with loadLanguage().
    // See loadLanguage for the specification of the language.
    // @param value pointer to the language value
    // @param size  length of the language value
    // @return TTS_SUCCESS, or TTS_FAILURE
    virtual tts_result setLanguage(const char *value, const size_t size);

    // Retrieve the currently set language, or an empty "value" if no language has
    // been set.
    // @param[out]   value pointer to the retrieved language value
    // @param[inout] iosize  in: stores the size available to store the language value in *value
    //                       out: stores the size required to hold the language value if
    //                         getLanguage() returned  TTS_PROPERTY_SIZE_TOO_SMALL,
    //                         unchanged otherwise.
    // @return TTS_SUCCESS, or TTS_PROPERTY_SIZE_TOO_SMALL, or TTS_FAILURE
    virtual tts_result getLanguage(char *value, size_t *iosize);

    // Set a property for the the TTS engine
    // "size" is the maximum size of "value" for properties "property"
    // @param property pointer to the property name
    // @param value    pointer to the property value
    // @param size     maximum size required to store this type of property
    // @return         TTS_PROPERTY_UNSUPPORTED, or TTS_SUCCESS, or TTS_FAILURE, 
    //                  or TTS_VALUE_INVALID
    virtual tts_result setProperty(const char *property, const char *value, const size_t size);

    // Retrieve a property from the TTS engine
    // @param        property pointer to the property name
    // @param[out]   value    pointer to the retrieved language value
    // @param[inout] iosize   in: stores the size available to store the property value
    //                        out: stores the size required to hold the language value if
    //                         getLanguage() returned  TTS_PROPERTY_SIZE_TOO_SMALL,
    //                         unchanged otherwise.
    // @return TTS_PROPERTY_UNSUPPORTED, or TTS_SUCCESS, or TTS_PROPERTY_SIZE_TOO_SMALL
    virtual tts_result getProperty(const char *property, char *value, size_t *iosize);

    // Synthesize the text.
    // When synthesis completes, the engine invokes the callback to notify the TTS framework.
    // Note about the format of the input: the text parameter may use the following elements
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
    // @return          TTS_SUCCESS or TTS_FAILURE
    virtual tts_result synthesizeText(const char *text, void *userdata);

    // Synthesize IPA text. When synthesis completes, the engine must call the given callback to notify the TTS API.
    // @param ipa      the IPA data to synthesize
    // @param userdata  pointer to be returned when the call is invoked
    // @return TTS_FEATURE_UNSUPPORTED if IPA is not supported, otherwise TTS_SUCCESS or TTS_FAILURE
    virtual tts_result synthesizeIpa(const char *ipa, void *userdata);
};

} // namespace android

