package android.media.musicrecognition;

import android.media.AudioFormat;
import android.os.ParcelFileDescriptor;
import android.os.IBinder;
import android.media.musicrecognition.IMusicRecognitionServiceCallback;
import android.media.musicrecognition.IMusicRecognitionAttributionTagCallback;

/**
 * Interface from the system to a {@link MusicRecognitionService}.
 *
 * @hide
 */
oneway interface IMusicRecognitionService {
  void onAudioStreamStarted(
      in ParcelFileDescriptor fd,
      in AudioFormat audioFormat,
      in IMusicRecognitionServiceCallback callback);

  void getAttributionTag(in IMusicRecognitionAttributionTagCallback callback);
}