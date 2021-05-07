/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.util.AndroidRuntimeException;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicReference;


/**
 * The SoundPool class manages and plays audio resources for applications.
 *
 * <p>A SoundPool is a collection of sound samples that can be loaded into memory
 * from a resource inside the APK or from a file in the file system. The
 * SoundPool library uses the MediaCodec service to decode the audio
 * into raw 16-bit PCM. This allows applications
 * to ship with compressed streams without having to suffer the CPU load
 * and latency of decompressing during playback.</p>
 *
 * <p>Soundpool sounds are expected to be short as they are
 * predecoded into memory. Each decoded sound is internally limited to one
 * megabyte storage, which represents approximately 5.6 seconds at 44.1kHz stereo
 * (the duration is proportionally longer at lower sample rates or
 * a channel mask of mono). A decoded audio sound will be truncated if it would
 * exceed the per-sound one megabyte storage space.</p>
 *
 * <p>In addition to low-latency playback, SoundPool can also manage the number
 * of audio streams being rendered at once. When the SoundPool object is
 * constructed, the maxStreams parameter sets the maximum number of streams
 * that can be played at a time from this single SoundPool. SoundPool tracks
 * the number of active streams. If the maximum number of streams is exceeded,
 * SoundPool will automatically stop a previously playing stream based first
 * on priority and then by age within that priority. Limiting the maximum
 * number of streams helps to cap CPU loading and reducing the likelihood that
 * audio mixing will impact visuals or UI performance.</p>
 *
 * <p>Sounds can be looped by setting a non-zero loop value. A value of -1
 * causes the sound to loop forever. In this case, the application must
 * explicitly call the stop() function to stop the sound. Any other non-zero
 * value will cause the sound to repeat the specified number of times, e.g.
 * a value of 3 causes the sound to play a total of 4 times.</p>
 *
 * <p>The playback rate can also be changed. A playback rate of 1.0 causes
 * the sound to play at its original frequency (resampled, if necessary,
 * to the hardware output frequency). A playback rate of 2.0 causes the
 * sound to play at twice its original frequency, and a playback rate of
 * 0.5 causes it to play at half its original frequency. The playback
 * rate range is 0.5 to 2.0.</p>
 *
 * <p>Priority runs low to high, i.e. higher numbers are higher priority.
 * Priority is used when a call to play() would cause the number of active
 * streams to exceed the value established by the maxStreams parameter when
 * the SoundPool was created. In this case, the stream allocator will stop
 * the lowest priority stream. If there are multiple streams with the same
 * low priority, it will choose the oldest stream to stop. In the case
 * where the priority of the new stream is lower than all the active
 * streams, the new sound will not play and the play() function will return
 * a streamID of zero.</p>
 *
 * <p>Let's examine a typical use case: A game consists of several levels of
 * play. For each level, there is a set of unique sounds that are used only
 * by that level. In this case, the game logic should create a new SoundPool
 * object when the first level is loaded. The level data itself might contain
 * the list of sounds to be used by this level. The loading logic iterates
 * through the list of sounds calling the appropriate SoundPool.load()
 * function. This should typically be done early in the process to allow time
 * for decompressing the audio to raw PCM format before they are needed for
 * playback.</p>
 *
 * <p>Once the sounds are loaded and play has started, the application can
 * trigger sounds by calling SoundPool.play(). Playing streams can be
 * paused or resumed, and the application can also alter the pitch by
 * adjusting the playback rate in real-time for doppler or synthesis
 * effects.</p>
 *
 * <p>Note that since streams can be stopped due to resource constraints, the
 * streamID is a reference to a particular instance of a stream. If the stream
 * is stopped to allow a higher priority stream to play, the stream is no
 * longer valid. However, the application is allowed to call methods on
 * the streamID without error. This may help simplify program logic since
 * the application need not concern itself with the stream lifecycle.</p>
 *
 * <p>In our example, when the player has completed the level, the game
 * logic should call SoundPool.release() to release all the native resources
 * in use and then set the SoundPool reference to null. If the player starts
 * another level, a new SoundPool is created, sounds are loaded, and play
 * resumes.</p>
 */
public class SoundPool extends PlayerBase {
    static { System.loadLibrary("soundpool"); }

    // SoundPool messages
    //
    // must match SoundPool.h
    private static final int SAMPLE_LOADED = 1;

    private final static String TAG = "SoundPool";
    private final static boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final AtomicReference<EventHandler> mEventHandler = new AtomicReference<>(null);

    private long mNativeContext; // accessed by native methods

    private boolean mHasAppOpsPlayAudio;

    private final AudioAttributes mAttributes;

    /**
     * Constructor. Constructs a SoundPool object with the following
     * characteristics:
     *
     * @param maxStreams the maximum number of simultaneous streams for this
     *                   SoundPool object
     * @param streamType the audio stream type as described in AudioManager
     *                   For example, game applications will normally use
     *                   {@link AudioManager#STREAM_MUSIC}.
     * @param srcQuality the sample-rate converter quality. Currently has no
     *                   effect. Use 0 for the default.
     * @return a SoundPool object, or null if creation failed
     * @deprecated use {@link SoundPool.Builder} instead to create and configure a
     *     SoundPool instance
     */
    public SoundPool(int maxStreams, int streamType, int srcQuality) {
        this(maxStreams,
                new AudioAttributes.Builder().setInternalLegacyStreamType(streamType).build());
        PlayerBase.deprecateStreamTypeForPlayback(streamType, "SoundPool", "SoundPool()");
    }

    private SoundPool(int maxStreams, AudioAttributes attributes) {
        super(attributes, AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL);

        // do native setup
        if (native_setup(new WeakReference<SoundPool>(this),
                maxStreams, attributes, getCurrentOpPackageName()) != 0) {
            throw new RuntimeException("Native setup failed");
        }
        mAttributes = attributes;

        // FIXME: b/174876164 implement session id for soundpool
        baseRegisterPlayer(AudioSystem.AUDIO_SESSION_ALLOCATE);
    }

    /**
     * Release the SoundPool resources.
     *
     * Release all memory and native resources used by the SoundPool
     * object. The SoundPool can no longer be used and the reference
     * should be set to null.
     */
    public final void release() {
        baseRelease();
        native_release();
    }

    private native final void native_release();

    protected void finalize() { release(); }

    /**
     * Load the sound from the specified path.
     *
     * @param path the path to the audio file
     * @param priority the priority of the sound. Currently has no effect. Use
     *                 a value of 1 for future compatibility.
     * @return a sound ID. This value can be used to play or unload the sound.
     */
    public int load(String path, int priority) {
        int id = 0;
        try {
            File f = new File(path);
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(f,
                    ParcelFileDescriptor.MODE_READ_ONLY);
            if (fd != null) {
                id = _load(fd.getFileDescriptor(), 0, f.length(), priority);
                fd.close();
            }
        } catch (java.io.IOException e) {
            Log.e(TAG, "error loading " + path);
        }
        return id;
    }

    /**
     * Load the sound from the specified APK resource.
     *
     * Note that the extension is dropped. For example, if you want to load
     * a sound from the raw resource file "explosion.mp3", you would specify
     * "R.raw.explosion" as the resource ID. Note that this means you cannot
     * have both an "explosion.wav" and an "explosion.mp3" in the res/raw
     * directory.
     *
     * @param context the application context
     * @param resId the resource ID
     * @param priority the priority of the sound. Currently has no effect. Use
     *                 a value of 1 for future compatibility.
     * @return a sound ID. This value can be used to play or unload the sound.
     */
    public int load(Context context, int resId, int priority) {
        AssetFileDescriptor afd = context.getResources().openRawResourceFd(resId);
        int id = 0;
        if (afd != null) {
            id = _load(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength(), priority);
            try {
                afd.close();
            } catch (java.io.IOException ex) {
                //Log.d(TAG, "close failed:", ex);
            }
        }
        return id;
    }

    /**
     * Load the sound from an asset file descriptor.
     *
     * @param afd an asset file descriptor
     * @param priority the priority of the sound. Currently has no effect. Use
     *                 a value of 1 for future compatibility.
     * @return a sound ID. This value can be used to play or unload the sound.
     */
    public int load(AssetFileDescriptor afd, int priority) {
        if (afd != null) {
            long len = afd.getLength();
            if (len < 0) {
                throw new AndroidRuntimeException("no length for fd");
            }
            return _load(afd.getFileDescriptor(), afd.getStartOffset(), len, priority);
        } else {
            return 0;
        }
    }

    /**
     * Load the sound from a FileDescriptor.
     *
     * This version is useful if you store multiple sounds in a single
     * binary. The offset specifies the offset from the start of the file
     * and the length specifies the length of the sound within the file.
     *
     * @param fd a FileDescriptor object
     * @param offset offset to the start of the sound
     * @param length length of the sound
     * @param priority the priority of the sound. Currently has no effect. Use
     *                 a value of 1 for future compatibility.
     * @return a sound ID. This value can be used to play or unload the sound.
     */
    public int load(FileDescriptor fd, long offset, long length, int priority) {
        return _load(fd, offset, length, priority);
    }

    /**
     * Unload a sound from a sound ID.
     *
     * Unloads the sound specified by the soundID. This is the value
     * returned by the load() function. Returns true if the sound is
     * successfully unloaded, false if the sound was already unloaded.
     *
     * @param soundID a soundID returned by the load() function
     * @return true if just unloaded, false if previously unloaded
     */
    public native final boolean unload(int soundID);

    /**
     * Play a sound from a sound ID.
     *
     * Play the sound specified by the soundID. This is the value
     * returned by the load() function. Returns a non-zero streamID
     * if successful, zero if it fails. The streamID can be used to
     * further control playback. Note that calling play() may cause
     * another sound to stop playing if the maximum number of active
     * streams is exceeded. A loop value of -1 means loop forever,
     * a value of 0 means don't loop, other values indicate the
     * number of repeats, e.g. a value of 1 plays the audio twice.
     * The playback rate allows the application to vary the playback
     * rate (pitch) of the sound. A value of 1.0 means play back at
     * the original frequency. A value of 2.0 means play back twice
     * as fast, and a value of 0.5 means playback at half speed.
     *
     * @param soundID a soundID returned by the load() function
     * @param leftVolume left volume value (range = 0.0 to 1.0)
     * @param rightVolume right volume value (range = 0.0 to 1.0)
     * @param priority stream priority (0 = lowest priority)
     * @param loop loop mode (0 = no loop, -1 = loop forever)
     * @param rate playback rate (1.0 = normal playback, range 0.5 to 2.0)
     * @return non-zero streamID if successful, zero if failed
     */
    public final int play(int soundID, float leftVolume, float rightVolume,
            int priority, int loop, float rate) {
        // FIXME: b/174876164 implement device id for soundpool
        baseStart(0);
        return _play(soundID, leftVolume, rightVolume, priority, loop, rate);
    }

    /**
     * Pause a playback stream.
     *
     * Pause the stream specified by the streamID. This is the
     * value returned by the play() function. If the stream is
     * playing, it will be paused. If the stream is not playing
     * (e.g. is stopped or was previously paused), calling this
     * function will have no effect.
     *
     * @param streamID a streamID returned by the play() function
     */
    public native final void pause(int streamID);

    /**
     * Resume a playback stream.
     *
     * Resume the stream specified by the streamID. This
     * is the value returned by the play() function. If the stream
     * is paused, this will resume playback. If the stream was not
     * previously paused, calling this function will have no effect.
     *
     * @param streamID a streamID returned by the play() function
     */
    public native final void resume(int streamID);

    /**
     * Pause all active streams.
     *
     * Pause all streams that are currently playing. This function
     * iterates through all the active streams and pauses any that
     * are playing. It also sets a flag so that any streams that
     * are playing can be resumed by calling autoResume().
     */
    public native final void autoPause();

    /**
     * Resume all previously active streams.
     *
     * Automatically resumes all streams that were paused in previous
     * calls to autoPause().
     */
    public native final void autoResume();

    /**
     * Stop a playback stream.
     *
     * Stop the stream specified by the streamID. This
     * is the value returned by the play() function. If the stream
     * is playing, it will be stopped. It also releases any native
     * resources associated with this stream. If the stream is not
     * playing, it will have no effect.
     *
     * @param streamID a streamID returned by the play() function
     */
    public native final void stop(int streamID);

    /**
     * Set stream volume.
     *
     * Sets the volume on the stream specified by the streamID.
     * This is the value returned by the play() function. The
     * value must be in the range of 0.0 to 1.0. If the stream does
     * not exist, it will have no effect.
     *
     * @param streamID a streamID returned by the play() function
     * @param leftVolume left volume value (range = 0.0 to 1.0)
     * @param rightVolume right volume value (range = 0.0 to 1.0)
     */
    public final void setVolume(int streamID, float leftVolume, float rightVolume) {
        // unlike other subclasses of PlayerBase, we are not calling
        // baseSetVolume(leftVolume, rightVolume) as we need to keep track of each
        // volume separately for each player, so we still send the command, but
        // handle mute/unmute separately through playerSetVolume()
        _setVolume(streamID, leftVolume, rightVolume);
    }

    @Override
    /* package */ int playerApplyVolumeShaper(
            @NonNull VolumeShaper.Configuration configuration,
            @Nullable VolumeShaper.Operation operation) {
        return -1;
    }

    @Override
    /* package */ @Nullable VolumeShaper.State playerGetVolumeShaperState(int id) {
        return null;
    }

    @Override
    void playerSetVolume(boolean muting, float leftVolume, float rightVolume) {
        // not used here to control the player volume directly, but used to mute/unmute
        _mute(muting);
    }

    @Override
    int playerSetAuxEffectSendLevel(boolean muting, float level) {
        // no aux send functionality so no-op
        return AudioSystem.SUCCESS;
    }

    @Override
    void playerStart() {
        // FIXME implement resuming any paused sound
    }

    @Override
    void playerPause() {
        // FIXME implement pausing any playing sound
    }

    @Override
    void playerStop() {
        // FIXME implement pausing any playing sound
    }

    /**
     * Similar, except set volume of all channels to same value.
     * @hide
     */
    public void setVolume(int streamID, float volume) {
        setVolume(streamID, volume, volume);
    }

    /**
     * Change stream priority.
     *
     * Change the priority of the stream specified by the streamID.
     * This is the value returned by the play() function. Affects the
     * order in which streams are re-used to play new sounds. If the
     * stream does not exist, it will have no effect.
     *
     * @param streamID a streamID returned by the play() function
     */
    public native final void setPriority(int streamID, int priority);

    /**
     * Set loop mode.
     *
     * Change the loop mode. A loop value of -1 means loop forever,
     * a value of 0 means don't loop, other values indicate the
     * number of repeats, e.g. a value of 1 plays the audio twice.
     * If the stream does not exist, it will have no effect.
     *
     * @param streamID a streamID returned by the play() function
     * @param loop loop mode (0 = no loop, -1 = loop forever)
     */
    public native final void setLoop(int streamID, int loop);

    /**
     * Change playback rate.
     *
     * The playback rate allows the application to vary the playback
     * rate (pitch) of the sound. A value of 1.0 means playback at
     * the original frequency. A value of 2.0 means playback twice
     * as fast, and a value of 0.5 means playback at half speed.
     * If the stream does not exist, it will have no effect.
     *
     * @param streamID a streamID returned by the play() function
     * @param rate playback rate (1.0 = normal playback, range 0.5 to 2.0)
     */
    public native final void setRate(int streamID, float rate);

    public interface OnLoadCompleteListener {
        /**
         * Called when a sound has completed loading.
         *
         * @param soundPool SoundPool object from the load() method
         * @param sampleId the sample ID of the sound loaded.
         * @param status the status of the load operation (0 = success)
         */
        public void onLoadComplete(SoundPool soundPool, int sampleId, int status);
    }

    /**
     * Sets the callback hook for the OnLoadCompleteListener.
     */
    public void setOnLoadCompleteListener(OnLoadCompleteListener listener) {
        if (listener == null) {
            mEventHandler.set(null);
            return;
        }

        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler.set(new EventHandler(looper, listener));
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler.set(new EventHandler(looper, listener));
        } else {
            mEventHandler.set(null);
        }
    }

    private native final int _load(FileDescriptor fd, long offset, long length, int priority);

    private native final int native_setup(Object weakRef, int maxStreams,
            @NonNull Object/*AudioAttributes*/ attributes, @NonNull String opPackageName);

    private native final int _play(int soundID, float leftVolume, float rightVolume,
            int priority, int loop, float rate);

    private native final void _setVolume(int streamID, float leftVolume, float rightVolume);

    private native final void _mute(boolean muting);

    // post event from native code to message handler
    @SuppressWarnings("unchecked")
    private static void postEventFromNative(Object ref, int msg, int arg1, int arg2, Object obj) {
        SoundPool soundPool = ((WeakReference<SoundPool>) ref).get();
        if (soundPool == null) {
            return;
        }

        Handler eventHandler = soundPool.mEventHandler.get();
        if (eventHandler == null) {
            return;
        }

        Message message = eventHandler.obtainMessage(msg, arg1, arg2, obj);
        eventHandler.sendMessage(message);
    }

    private final class EventHandler extends Handler {
        private final OnLoadCompleteListener mOnLoadCompleteListener;

        EventHandler(Looper looper, @NonNull OnLoadCompleteListener onLoadCompleteListener) {
            super(looper);
            mOnLoadCompleteListener = onLoadCompleteListener;
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what != SAMPLE_LOADED) {
                Log.e(TAG, "Unknown message type " + msg.what);
                return;
            }

            if (DEBUG) Log.d(TAG, "Sample " + msg.arg1 + " loaded");
            mOnLoadCompleteListener.onLoadComplete(SoundPool.this, msg.arg1, msg.arg2);
        }
    }

    /**
     * Builder class for {@link SoundPool} objects.
     */
    public static class Builder {
        private int mMaxStreams = 1;
        private AudioAttributes mAudioAttributes;

        /**
         * Constructs a new Builder with the defaults format values.
         * If not provided, the maximum number of streams is 1 (see {@link #setMaxStreams(int)} to
         * change it), and the audio attributes have a usage value of
         * {@link AudioAttributes#USAGE_MEDIA} (see {@link #setAudioAttributes(AudioAttributes)} to
         * change them).
         */
        public Builder() {
        }

        /**
         * Sets the maximum of number of simultaneous streams that can be played simultaneously.
         * @param maxStreams a value equal to 1 or greater.
         * @return the same Builder instance
         * @throws IllegalArgumentException
         */
        public Builder setMaxStreams(int maxStreams) throws IllegalArgumentException {
            if (maxStreams <= 0) {
                throw new IllegalArgumentException(
                        "Strictly positive value required for the maximum number of streams");
            }
            mMaxStreams = maxStreams;
            return this;
        }

        /**
         * Sets the {@link AudioAttributes}. For examples, game applications will use attributes
         * built with usage information set to {@link AudioAttributes#USAGE_GAME}.
         * @param attributes a non-null
         * @return
         */
        public Builder setAudioAttributes(AudioAttributes attributes)
                throws IllegalArgumentException {
            if (attributes == null) {
                throw new IllegalArgumentException("Invalid null AudioAttributes");
            }
            mAudioAttributes = attributes;
            return this;
        }

        public SoundPool build() {
            if (mAudioAttributes == null) {
                mAudioAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA).build();
            }
            return new SoundPool(mMaxStreams, mAudioAttributes);
        }
    }
}
