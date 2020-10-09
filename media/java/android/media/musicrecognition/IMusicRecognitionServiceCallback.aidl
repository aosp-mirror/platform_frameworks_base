package android.media.musicrecognition;

import android.os.Bundle;
import android.media.MediaMetadata;

/**
 * Interface from a {@MusicRecognitionService} the system.
 *
 * @hide
 */
oneway interface IMusicRecognitionServiceCallback {
  void onRecognitionSucceeded(in MediaMetadata result, in Bundle extras);

  void onRecognitionFailed(int failureCode);
}