package android.media.musicrecognition;

import android.os.Bundle;
import android.media.MediaMetadata;

/**
 * Callback used by system server to notify invoker of {@link MusicRecognitionManager} of the result
 *
 * @hide
 */
oneway interface IMusicRecognitionManagerCallback {
    void onRecognitionSucceeded(in MediaMetadata result, in Bundle extras);
    void onRecognitionFailed(int failureCode);
    void onAudioStreamClosed();
}