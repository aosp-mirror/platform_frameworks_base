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

    public abstract void run();
    abstract void stop(boolean isError);
}
