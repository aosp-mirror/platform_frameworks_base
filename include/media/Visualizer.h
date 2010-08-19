/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_MEDIA_VISUALIZER_H
#define ANDROID_MEDIA_VISUALIZER_H

#include <media/AudioEffect.h>
#include <media/EffectVisualizerApi.h>
#include <string.h>

/**
 * The Visualizer class enables application to retrieve part of the currently playing audio for
 * visualization purpose. It is not an audio recording interface and only returns partial and low
 * quality audio content. However, to protect privacy of certain audio data (e.g voice mail) the use
 * of the visualizer requires the permission android.permission.RECORD_AUDIO.
 * The audio session ID passed to the constructor indicates which audio content should be
 * visualized:
 * - If the session is 0, the audio output mix is visualized
 * - If the session is not 0, the audio from a particular MediaPlayer or AudioTrack
 *   using this audio session is visualized
 * Two types of representation of audio content can be captured:
 * - Waveform data: consecutive 8-bit (unsigned) mono samples by using the getWaveForm() method
 * - Frequency data: 8-bit magnitude FFT by using the getFft() method
 *
 * The length of the capture can be retrieved or specified by calling respectively
 * getCaptureSize() and setCaptureSize() methods. Note that the size of the FFT
 * is half of the specified capture size but both sides of the spectrum are returned yielding in a
 * number of bytes equal to the capture size. The capture size must be a power of 2 in the range
 * returned by getMinCaptureSize() and getMaxCaptureSize().
 * In addition to the polling capture mode, a callback mode is also available by installing a
 * callback function by use of the setCaptureCallBack() method. The rate at which the callback
 * is called as well as the type of data returned is specified.
 * Before capturing data, the Visualizer must be enabled by calling the setEnabled() method.
 * When data capture is not needed any more, the Visualizer should be disabled.
 */


namespace android {

// ----------------------------------------------------------------------------

class Visualizer: public AudioEffect {
public:

    enum callback_flags {
        CAPTURE_WAVEFORM = 0x00000001,  // capture callback returns a PCM wave form
        CAPTURE_FFT = 0x00000002,       // apture callback returns a frequency representation
        CAPTURE_CALL_JAVA = 0x00000004  // the callback thread can call java
    };


    /* Constructor.
     * See AudioEffect constructor for details on parameters.
     */
                        Visualizer(int32_t priority = 0,
                                   effect_callback_t cbf = 0,
                                   void* user = 0,
                                   int sessionId = 0);

                        ~Visualizer();

    virtual status_t    setEnabled(bool enabled);

    // maximum capture size in samples
    static uint32_t getMaxCaptureSize() { return VISUALIZER_CAPTURE_SIZE_MAX; }
    // minimum capture size in samples
    static uint32_t getMinCaptureSize() { return VISUALIZER_CAPTURE_SIZE_MIN; }
    // maximum capture rate in millihertz
    static uint32_t getMaxCaptureRate() { return CAPTURE_RATE_MAX; }

    // callback used to return periodic PCM or FFT captures to the application. Either one or both
    // types of data are returned (PCM and FFT) according to flags indicated when installing the
    // callback. When a type of data is not present, the corresponding size (waveformSize or
    // fftSize) is 0.
    typedef void (*capture_cbk_t)(void* user,
                                    uint32_t waveformSize,
                                    uint8_t *waveform,
                                    uint32_t fftSize,
                                    uint8_t *fft,
                                    uint32_t samplingrate);

    // install a callback to receive periodic captures. The capture rate is specified in milliHertz
    // and the capture format is according to flags  (see callback_flags).
    status_t setCaptureCallBack(capture_cbk_t cbk, void* user, uint32_t flags, uint32_t rate);

    // set the capture size capture size must be a power of two in the range
    // [VISUALIZER_CAPTURE_SIZE_MAX. VISUALIZER_CAPTURE_SIZE_MIN]
    // must be called when the visualizer is not enabled
    status_t setCaptureSize(uint32_t size);
    uint32_t getCaptureSize() { return mCaptureSize; }

    // returns the capture rate indicated when installing the callback
    uint32_t getCaptureRate() { return mCaptureRate; }

    // returns the sampling rate of the audio being captured
    uint32_t getSamplingRate() { return mSampleRate; }

    // return a capture in PCM 8 bit unsigned format. The size of the capture is equal to
    // getCaptureSize()
    status_t getWaveForm(uint8_t *waveform);

    // return a capture in FFT 8 bit signed format. The size of the capture is equal to
    // getCaptureSize() but the length of the FFT is half of the size (both parts of the spectrum
    // are returned
    status_t getFft(uint8_t *fft);

private:

    static const uint32_t CAPTURE_RATE_MAX = 20000;
    static const uint32_t CAPTURE_RATE_DEF = 10000;
    static const uint32_t CAPTURE_SIZE_DEF = VISUALIZER_CAPTURE_SIZE_MAX;

    /* internal class to handle the callback */
    class CaptureThread : public Thread
    {
    public:
        CaptureThread(Visualizer& receiver, uint32_t captureRate, bool bCanCallJava = false);

    private:
        friend class Visualizer;
        virtual bool        threadLoop();
        virtual status_t    readyToRun();
        virtual void        onFirstRef();
        Visualizer& mReceiver;
        Mutex       mLock;
        uint32_t mSleepTimeUs;
    };

    status_t doFft(uint8_t *fft, uint8_t *waveform);
    void periodicCapture();
    uint32_t initCaptureSize();

    Mutex mLock;
    uint32_t mCaptureRate;
    uint32_t mCaptureSize;
    uint32_t mSampleRate;
    capture_cbk_t mCaptureCallBack;
    void *mCaptureCbkUser;
    sp<CaptureThread> mCaptureThread;
    uint32_t mCaptureFlags;
};


}; // namespace android

#endif // ANDROID_MEDIA_VISUALIZER_H
