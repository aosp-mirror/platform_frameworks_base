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

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.ref.WeakReference;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.SystemProperties;
import android.util.AndroidRuntimeException;
import android.util.Log;


/**
 * The SoundPool class manages and plays audio resources for applications.
 *
 * <p>A SoundPool is a collection of samples that can be loaded into memory
 * from a resource inside the APK or from a file in the file system. The
 * SoundPool library uses the MediaPlayer service to decode the audio
 * into a raw 16-bit PCM mono or stereo stream. This allows applications
 * to ship with compressed streams without having to suffer the CPU load
 * and latency of decompressing during playback.</p>
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
 * longer be valid. However, the application is allowed to call methods on
 * the streamID without error. This may help simplify program logic since
 * the application need not concern itself with the stream lifecycle.</p>
 *
 * <p>In our example, when the player has completed the level, the game
 * logic should call SoundPool.release() to release all the native resources
 * in use and then set the SoundPool reference to null. If the player starts
 * another level, a new SoundPool is created, sounds are loaded, and play
 * resumes.</p>
 */
public class SoundPool {
    private final SoundPoolDelegate mImpl;

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
     */
    public SoundPool(int maxStreams, int streamType, int srcQuality) {
        if (SystemProperties.getBoolean("config.disable_media", false)) {
            mImpl = new SoundPoolStub();
        } else {
            mImpl = new SoundPoolImpl(this, maxStreams, streamType, srcQuality);
        }
    }

    /**
     * Load the sound from the specified path.
     *
     * @param path the path to the audio file
     * @param priority the priority of the sound. Currently has no effect. Use
     *                 a value of 1 for future compatibility.
     * @return a sound ID. This value can be used to play or unload the sound.
     */
    public int load(String path, int priority) {
        return mImpl.load(path, priority);
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
        return mImpl.load(context, resId, priority);
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
        return mImpl.load(afd, priority);
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
        return mImpl.load(fd, offset, length, priority);
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
    public final boolean unload(int soundID) {
        return mImpl.unload(soundID);
    }

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
        return mImpl.play(
            soundID, leftVolume, rightVolume, priority, loop, rate);
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
    public final void pause(int streamID) {
        mImpl.pause(streamID);
    }

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
    public final void resume(int streamID) {
        mImpl.resume(streamID);
    }

    /**
     * Pause all active streams.
     *
     * Pause all streams that are currently playing. This function
     * iterates through all the active streams and pauses any that
     * are playing. It also sets a flag so that any streams that
     * are playing can be resumed by calling autoResume().
     */
    public final void autoPause() {
        mImpl.autoPause();
    }

    /**
     * Resume all previously active streams.
     *
     * Automatically resumes all streams that were paused in previous
     * calls to autoPause().
     */
    public final void autoResume() {
        mImpl.autoResume();
    }

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
    public final void stop(int streamID) {
        mImpl.stop(streamID);
    }

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
    public final void setVolume(int streamID,
            float leftVolume, float rightVolume) {
        mImpl.setVolume(streamID, leftVolume, rightVolume);
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
    public final void setPriority(int streamID, int priority) {
        mImpl.setPriority(streamID, priority);
    }

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
    public final void setLoop(int streamID, int loop) {
        mImpl.setLoop(streamID, loop);
    }

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
    public final void setRate(int streamID, float rate) {
        mImpl.setRate(streamID, rate);
    }

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
        mImpl.setOnLoadCompleteListener(listener);
    }

    /**
     * Release the SoundPool resources.
     *
     * Release all memory and native resources used by the SoundPool
     * object. The SoundPool can no longer be used and the reference
     * should be set to null.
     */
    public final void release() {
        mImpl.release();
    }

    /**
     * Interface for SoundPool implementations.
     * SoundPool is statically referenced and unconditionally called from all
     * over the framework, so we can't simply omit the class or make it throw
     * runtime exceptions, as doing so would break the framework. Instead we
     * now select either a real or no-op impl object based on whether media is
     * enabled.
     *
     * @hide
     */
    /* package */ interface SoundPoolDelegate {
        public int load(String path, int priority);
        public int load(Context context, int resId, int priority);
        public int load(AssetFileDescriptor afd, int priority);
        public int load(
                FileDescriptor fd, long offset, long length, int priority);
        public boolean unload(int soundID);
        public int play(
                int soundID, float leftVolume, float rightVolume,
                int priority, int loop, float rate);
        public void pause(int streamID);
        public void resume(int streamID);
        public void autoPause();
        public void autoResume();
        public void stop(int streamID);
        public void setVolume(int streamID, float leftVolume, float rightVolume);
        public void setVolume(int streamID, float volume);
        public void setPriority(int streamID, int priority);
        public void setLoop(int streamID, int loop);
        public void setRate(int streamID, float rate);
        public void setOnLoadCompleteListener(OnLoadCompleteListener listener);
        public void release();
    }


    /**
     * Real implementation of the delegate interface. This was formerly the
     * body of SoundPool itself.
     */
    /* package */ static class SoundPoolImpl implements SoundPoolDelegate {
        static { System.loadLibrary("soundpool"); }

        private final static String TAG = "SoundPool";
        private final static boolean DEBUG = false;

        private int mNativeContext; // accessed by native methods

        private EventHandler mEventHandler;
        private SoundPool.OnLoadCompleteListener mOnLoadCompleteListener;
        private SoundPool mProxy;

        private final Object mLock;

        // SoundPool messages
        //
        // must match SoundPool.h
        private static final int SAMPLE_LOADED = 1;

        public SoundPoolImpl(SoundPool proxy, int maxStreams, int streamType, int srcQuality) {

            // do native setup
            if (native_setup(new WeakReference(this), maxStreams, streamType, srcQuality) != 0) {
                throw new RuntimeException("Native setup failed");
            }
            mLock = new Object();
            mProxy = proxy;
        }

        public int load(String path, int priority)
        {
            // pass network streams to player
            if (path.startsWith("http:"))
                return _load(path, priority);

            // try local path
            int id = 0;
            try {
                File f = new File(path);
                ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                if (fd != null) {
                    id = _load(fd.getFileDescriptor(), 0, f.length(), priority);
                    fd.close();
                }
            } catch (java.io.IOException e) {
                Log.e(TAG, "error loading " + path);
            }
            return id;
        }

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

        public int load(FileDescriptor fd, long offset, long length, int priority) {
            return _load(fd, offset, length, priority);
        }

        private native final int _load(String uri, int priority);

        private native final int _load(FileDescriptor fd, long offset, long length, int priority);

        public native final boolean unload(int soundID);

        public native final int play(int soundID, float leftVolume, float rightVolume,
                int priority, int loop, float rate);

        public native final void pause(int streamID);

        public native final void resume(int streamID);

        public native final void autoPause();

        public native final void autoResume();

        public native final void stop(int streamID);

        public native final void setVolume(int streamID,
                float leftVolume, float rightVolume);

        public void setVolume(int streamID, float volume) {
            setVolume(streamID, volume, volume);
        }

        public native final void setPriority(int streamID, int priority);

        public native final void setLoop(int streamID, int loop);

        public native final void setRate(int streamID, float rate);

        public void setOnLoadCompleteListener(SoundPool.OnLoadCompleteListener listener)
        {
            synchronized(mLock) {
                if (listener != null) {
                    // setup message handler
                    Looper looper;
                    if ((looper = Looper.myLooper()) != null) {
                        mEventHandler = new EventHandler(mProxy, looper);
                    } else if ((looper = Looper.getMainLooper()) != null) {
                        mEventHandler = new EventHandler(mProxy, looper);
                    } else {
                        mEventHandler = null;
                    }
                } else {
                    mEventHandler = null;
                }
                mOnLoadCompleteListener = listener;
            }
        }

        private class EventHandler extends Handler
        {
            private SoundPool mSoundPool;

            public EventHandler(SoundPool soundPool, Looper looper) {
                super(looper);
                mSoundPool = soundPool;
            }

            @Override
            public void handleMessage(Message msg) {
                switch(msg.what) {
                case SAMPLE_LOADED:
                    if (DEBUG) Log.d(TAG, "Sample " + msg.arg1 + " loaded");
                    synchronized(mLock) {
                        if (mOnLoadCompleteListener != null) {
                            mOnLoadCompleteListener.onLoadComplete(mSoundPool, msg.arg1, msg.arg2);
                        }
                    }
                    break;
                default:
                    Log.e(TAG, "Unknown message type " + msg.what);
                    return;
                }
            }
        }

        // post event from native code to message handler
        private static void postEventFromNative(Object weakRef, int msg, int arg1, int arg2, Object obj)
        {
            SoundPoolImpl soundPoolImpl = (SoundPoolImpl)((WeakReference)weakRef).get();
            if (soundPoolImpl == null)
                return;

            if (soundPoolImpl.mEventHandler != null) {
                Message m = soundPoolImpl.mEventHandler.obtainMessage(msg, arg1, arg2, obj);
                soundPoolImpl.mEventHandler.sendMessage(m);
            }
        }

        public native final void release();

        private native final int native_setup(Object weakRef, int maxStreams, int streamType, int srcQuality);

        protected void finalize() { release(); }
    }

    /**
     * No-op implementation of SoundPool.
     * Used when media is disabled by the system.
     * @hide
     */
    /* package */ static class SoundPoolStub implements SoundPoolDelegate {
        public SoundPoolStub() { }

        public int load(String path, int priority) {
            return 0;
        }

        public int load(Context context, int resId, int priority) {
            return 0;
        }

        public int load(AssetFileDescriptor afd, int priority) {
            return 0;
        }

        public int load(FileDescriptor fd, long offset, long length, int priority) {
            return 0;
        }

        public final boolean unload(int soundID) {
            return true;
        }

        public final int play(int soundID, float leftVolume, float rightVolume,
                int priority, int loop, float rate) {
            return 0;
        }

        public final void pause(int streamID) { }

        public final void resume(int streamID) { }

        public final void autoPause() { }

        public final void autoResume() { }

        public final void stop(int streamID) { }

        public final void setVolume(int streamID,
                float leftVolume, float rightVolume) { }

        public void setVolume(int streamID, float volume) {
        }

        public final void setPriority(int streamID, int priority) { }

        public final void setLoop(int streamID, int loop) { }

        public final void setRate(int streamID, float rate) { }

        public void setOnLoadCompleteListener(SoundPool.OnLoadCompleteListener listener) {
        }

        public final void release() { }
    }
}
