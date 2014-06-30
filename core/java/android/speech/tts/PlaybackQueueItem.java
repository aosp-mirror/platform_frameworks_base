// Copyright 2011 Google Inc. All Rights Reserved.

package android.speech.tts;

import android.speech.tts.TextToSpeechService.UtteranceProgressDispatcher;

abstract class PlaybackQueueItem implements Runnable {
    private final UtteranceProgressDispatcher mDispatcher;
    private final Object mCallerIdentity;

    PlaybackQueueItem(TextToSpeechService.UtteranceProgressDispatcher dispatcher,
            Object callerIdentity) {
        mDispatcher = dispatcher;
        mCallerIdentity = callerIdentity;
    }

    Object getCallerIdentity() {
        return mCallerIdentity;
    }

    protected UtteranceProgressDispatcher getDispatcher() {
        return mDispatcher;
    }

    @Override
    public abstract void run();

    /**
     * Stop the playback.
     *
     * @param errorCode Cause of the stop. Can be either one of the error codes from
     *         {@link android.speech.tts.TextToSpeech} or
     *         {@link android.speech.tts.TextToSpeech#STOPPED}
     *         if stopped on a client request.
     */
    abstract void stop(int errorCode);
}
