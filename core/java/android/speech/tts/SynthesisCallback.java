/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.speech.tts;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.media.AudioFormat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A callback to return speech data synthesized by a text to speech engine.
 *
 * The engine can provide streaming audio by calling
 * {@link #start}, then {@link #audioAvailable} until all audio has been provided, then finally
 * {@link #done}.
 *
 * {@link #error} can be called at any stage in the synthesis process to
 * indicate that an error has occurred, but if the call is made after a call
 * to {@link #done}, it might be discarded.
 *
 * {@link #done} must be called at the end of synthesis, regardless of errors.
 *
 * All methods can be only called on the synthesis thread.
 */
public interface SynthesisCallback {

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        AudioFormat.ENCODING_PCM_8BIT,
        AudioFormat.ENCODING_PCM_16BIT,
        AudioFormat.ENCODING_PCM_FLOAT
    })
    @interface SupportedAudioFormat {};

    /**
     * @return the maximum number of bytes that the TTS engine can pass in a single call of {@link
     *     #audioAvailable}. Calls to {@link #audioAvailable} with data lengths larger than this
     *     value will not succeed.
     */
    int getMaxBufferSize();

  /**
   * The service should call this when it starts to synthesize audio for this request.
   *
   * <p>This method should only be called on the synthesis thread, while in {@link
   * TextToSpeechService#onSynthesizeText}.
   *
   * @param sampleRateInHz Sample rate in HZ of the generated audio.
   * @param audioFormat Audio format of the generated audio. Must be one of {@link
   *     AudioFormat#ENCODING_PCM_8BIT} or {@link AudioFormat#ENCODING_PCM_16BIT}. Can also be
   *     {@link AudioFormat#ENCODING_PCM_FLOAT} when targetting Android N and above.
   * @param channelCount The number of channels. Must be {@code 1} or {@code 2}.
   * @return {@link android.speech.tts.TextToSpeech#SUCCESS}, {@link
   *     android.speech.tts.TextToSpeech#ERROR} or {@link android.speech.tts.TextToSpeech#STOPPED}.
   */
  int start(
      int sampleRateInHz,
      @SupportedAudioFormat int audioFormat,
      @IntRange(from = 1, to = 2) int channelCount);

  /**
   * The service should call this method when synthesized audio is ready for consumption.
   *
   * <p>This method should only be called on the synthesis thread, while in {@link
   * TextToSpeechService#onSynthesizeText}.
   *
   * @param buffer The generated audio data. This method will not hold on to {@code buffer}, so the
   *     caller is free to modify it after this method returns.
   * @param offset The offset into {@code buffer} where the audio data starts.
   * @param length The number of bytes of audio data in {@code buffer}. This must be less than or
   *     equal to the return value of {@link #getMaxBufferSize}.
   * @return {@link android.speech.tts.TextToSpeech#SUCCESS}, {@link
   *     android.speech.tts.TextToSpeech#ERROR} or {@link android.speech.tts.TextToSpeech#STOPPED}.
   */
  int audioAvailable(byte[] buffer, int offset, int length);

  /**
   * The service should call this method when all the synthesized audio for a request has been
   * passed to {@link #audioAvailable}.
   *
   * <p>This method should only be called on the synthesis thread, while in {@link
   * TextToSpeechService#onSynthesizeText}.
   *
   * <p>This method has to be called if {@link #start} and/or {@link #error} was called.
   *
   * @return {@link android.speech.tts.TextToSpeech#SUCCESS}, {@link
   *     android.speech.tts.TextToSpeech#ERROR} or {@link android.speech.tts.TextToSpeech#STOPPED}.
   */
  int done();

    /**
     * The service should call this method if the speech synthesis fails.
     *
     * <p>This method should only be called on the synthesis thread, while in {@link
     * TextToSpeechService#onSynthesizeText}.
     */
    void error();

  /**
   * The service should call this method if the speech synthesis fails.
   *
   * <p>This method should only be called on the synthesis thread, while in {@link
   * TextToSpeechService#onSynthesizeText}.
   *
   * @param errorCode Error code to pass to the client. One of the ERROR_ values from {@link
   *     android.speech.tts.TextToSpeech}
   */
  void error(@TextToSpeech.Error int errorCode);

    /**
     * Check if {@link #start} was called or not.
     *
     * <p>This method should only be called on the synthesis thread, while in {@link
     * TextToSpeechService#onSynthesizeText}.
     *
     * <p>Useful for checking if a fallback from network request is possible.
     */
    boolean hasStarted();

    /**
     * Check if {@link #done} was called or not.
     *
     * <p>This method should only be called on the synthesis thread, while in {@link
     * TextToSpeechService#onSynthesizeText}.
     *
     * <p>Useful for checking if a fallback from network request is possible.
     */
    boolean hasFinished();

    /**
     * The service may call this method to provide timing information about the spoken text.
     *
     * <p>Calling this method means that at the given audio frame, the given range of the input is
     * about to be spoken. If this method is called the client will receive a callback on the
     * listener ({@link UtteranceProgressListener#onRangeStart}) at the moment that frame has been
     * reached by the playback head.
     *
     * <p>This information can be used by the client, for example, to highlight ranges of the text
     * while it is spoken.
     *
     * <p>The markerInFrames is a frame index into the audio for this synthesis request, i.e. into
     * the concatenation of the audio bytes sent to audioAvailable for this synthesis request. The
     * definition of a frame depends on the format given by {@link #start}. See {@link AudioFormat}
     * for more information.
     *
     * <p>This method should only be called on the synthesis thread, while in {@link
     * TextToSpeechService#onSynthesizeText}.
     *
     * @param markerInFrames The position in frames in the audio where this range is spoken.
     * @param start The start index of the range in the input text.
     * @param end The end index (exclusive) of the range in the input text.
     */
    default void rangeStart(int markerInFrames, int start, int end) {}
}
