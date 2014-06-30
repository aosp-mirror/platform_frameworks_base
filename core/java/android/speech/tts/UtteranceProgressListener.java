// Copyright 2011 Google Inc. All Rights Reserved.

package android.speech.tts;

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
     * or before the first bytes of a file are written to storage in the case
     * of {@link TextToSpeech#synthesizeToFile}.
     *
     * @param utteranceId the utterance ID of the utterance.
     */
    public abstract void onStart(String utteranceId);

    /**
     * Called when an utterance has successfully completed processing.
     * All audio will have been played back by this point for audible output, and all
     * output will have been written to disk for file synthesis requests.
     *
     * This request is guaranteed to be called after {@link #onStart(String)}.
     *
     * @param utteranceId the utterance ID of the utterance.
     */
    public abstract void onDone(String utteranceId);

    /**
     * Called when an error has occurred during processing. This can be called
     * at any point in the synthesis process. Note that there might be calls
     * to {@link #onStart(String)} for specified utteranceId but there will never
     * be a call to both {@link #onDone(String)} and {@link #onError(String)} for
     * the same utterance.
     *
     * @param utteranceId the utterance ID of the utterance.
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
     * @param utteranceId the utterance ID of the utterance.
     * @param errorCode one of the ERROR_* codes from {@link TextToSpeech}
     */
    public void onError(String utteranceId, int errorCode) {
        onError(utteranceId);
    }

    /**
     * Wraps an old deprecated OnUtteranceCompletedListener with a shiny new
     * progress listener.
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
        };
    }
}
