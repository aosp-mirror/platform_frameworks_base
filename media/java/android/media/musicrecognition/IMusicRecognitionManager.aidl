package android.media.musicrecognition;

import android.media.musicrecognition.RecognitionRequest;
import android.os.IBinder;

/**
 * Used by {@link MusicRecognitionManager} to tell system server to begin open an audio stream to
 * the designated lookup service.
 *
 * @hide
 */
interface IMusicRecognitionManager {
    void beginRecognition(in RecognitionRequest recognitionRequest, in IBinder callback);
}