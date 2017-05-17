// Copyright 2011 Google Inc. All Rights Reserved.

package android.speech.tts;

import android.media.AudioFormat;

/**
 * Listener for events relating to the progress of an utterance through
 * the synthesis queue. Each utterance is associated with a call to
 * {@link TextToSpeech#speak} or {@link TextToSpeech#synthesizeToFile} with an
 * associated utterance identifier, as per {@link TextToSpeech.Engine#KEY_PARAM_UTTERANCE_ID}.
 *
 * The callbacks specified in this method can be called from multiple threads.
 */
public abstract class UtteranceProgressListener {
    /**
     * Called when an utterance "starts" as perceived by the caller. This will
     * be soon before audio is played back in the case of a {@link TextToSpeech#speak}
     * or before the first bytes of a file are written to the file system in the case
     * of {@link TextToSpeech#synthesizeToFile}.
     *
     * @param utteranceId The utterance ID of the utterance.
     */
    public abstract void onStart(String utteranceId);

    /**
     * Called when an utterance has successfully completed processing.
     * All audio will have been played back by this point for audible output, and all
     * output will have been written to disk for file synthesis requests.
     *
     * This request is guaranteed to be called after {@link #onStart(String)}.
     *
     * @param utteranceId The utterance ID of the utterance.
     */
    public abstract void onDone(String utteranceId);

    /**
     * Called when an error has occurred during processing. This can be called
     * at any point in the synthesis process. Note that there might be calls
     * to {@link #onStart(String)} for specified utteranceId but there will never
     * be a call to both {@link #onDone(String)} and {@link #onError(String)} for
     * the same utterance.
     *
     * @param utteranceId The utterance ID of the utterance.
     * @deprecated Use {@link #onError(String,int)} instead
     */
    @Deprecated
    public abstract void onError(String utteranceId);

    /**
     * Called when an error has occurred during processing. This can be called
     * at any point in the synthesis process. Note that there might be calls
     * to {@link #onStart(String)} for specified utteranceId but there will never
     * be a call to both {@link #onDone(String)} and {@link #onError(String,int)} for
     * the same utterance. The default implementation calls {@link #onError(String)}.
     *
     * @param utteranceId The utterance ID of the utterance.
     * @param errorCode one of the ERROR_* codes from {@link TextToSpeech}
     */
    public void onError(String utteranceId, int errorCode) {
        onError(utteranceId);
    }

    /**
     * Called when an utterance has been stopped while in progress or flushed from the
     * synthesis queue. This can happen if a client calls {@link TextToSpeech#stop()}
     * or uses {@link TextToSpeech#QUEUE_FLUSH} as an argument with the
     * {@link TextToSpeech#speak} or {@link TextToSpeech#synthesizeToFile} methods.
     *
     * @param utteranceId The utterance ID of the utterance.
     * @param interrupted If true, then the utterance was interrupted while being synthesized
     *        and its output is incomplete. If false, then the utterance was flushed
     *        before the synthesis started.
     */
    public void onStop(String utteranceId, boolean interrupted) {
    }

    /**
     * Called when the TTS engine begins to synthesize the audio for a request.
     *
     * <p>
     * It provides information about the format of the byte array for subsequent
     * {@link #onAudioAvailable} calls.
     * </p>
     *
     * <p>
     * This is called when the TTS engine starts synthesizing audio for the request. If an
     * application wishes to know when the audio is about to start playing, {#onStart(String)}
     * should be used instead.
     * </p>
     *
     * @param utteranceId The utterance ID of the utterance.
     * @param sampleRateInHz Sample rate in hertz of the generated audio.
     * @param audioFormat Audio format of the generated audio. Should be one of
     *        {@link AudioFormat#ENCODING_PCM_8BIT}, {@link AudioFormat#ENCODING_PCM_16BIT} or
     *        {@link AudioFormat#ENCODING_PCM_FLOAT}.
     * @param channelCount The number of channels.
     */
    public void onBeginSynthesis(String utteranceId, int sampleRateInHz, int audioFormat, int channelCount) {
    }

    /**
     * This is called when a chunk of audio is ready for consumption.
     *
     * <p>
     * The audio parameter is a copy of what will be synthesized to the speakers (when synthesis was
     * initiated with a {@link TextToSpeech#speak} call) or written to the file system (for
     * {@link TextToSpeech#synthesizeToFile}). The audio bytes are delivered in one or more chunks;
     * if {@link #onDone} or {@link #onError} is called all chunks have been received.
     * </p>
     *
     * <p>
     * The audio received here may not be played for some time depending on buffer sizes and the
     * amount of items on the synthesis queue.
     * </p>
     *
     * @param utteranceId The utterance ID of the utterance.
     * @param audio A chunk of audio; the format can be known by listening to
     *        {@link #onBeginSynthesis(String, int, int, int)}.
     */
    public void onAudioAvailable(String utteranceId, byte[] audio) {
    }

    /**
     * This is called when the TTS service is about to speak the specified range of the utterance
     * with the given utteranceId.
     *
     * <p>This method is called when the audio is expected to start playing on the speaker. Note
     * that this is different from {@link #onAudioAvailable} which is called as soon as the audio is
     * generated.

     * <p>This information can be used, for example, to highlight ranges of the text while it is
     * spoken.
     *
     * <p>Only called if the engine supplies timing information by calling {@link
     * SynthesisCallback#rangeStart(int, int, int)}.
     *
     * @param utteranceId Unique id identifying the synthesis request.
     * @param start The start index of the range in the utterance text.
     * @param end The end index of the range (exclusive) in the utterance text.
     * @param frame The position in frames in the audio of the request where this range is spoken.
     */
    public void onRangeStart(String utteranceId, int start, int end, int frame) {
        onUtteranceRangeStart(utteranceId, start, end);
    }

    /** @removed */
    @Deprecated
    public void onUtteranceRangeStart(String utteranceId, int start, int end) {
    }

    /**
     * Wraps an old deprecated OnUtteranceCompletedListener with a shiny new progress listener.
     *
     * @hide
     */
    static UtteranceProgressListener from(
            final TextToSpeech.OnUtteranceCompletedListener listener) {
        return new UtteranceProgressListener() {
            @Override
            public synchronized void onDone(String utteranceId) {
                listener.onUtteranceCompleted(utteranceId);
            }

            @Override
            public void onError(String utteranceId) {
                listener.onUtteranceCompleted(utteranceId);
            }

            @Override
            public void onStart(String utteranceId) {
                // Left unimplemented, has no equivalent in the old
                // API.
            }

            @Override
            public void onStop(String utteranceId, boolean interrupted) {
                listener.onUtteranceCompleted(utteranceId);
            }
        };
    }
}
