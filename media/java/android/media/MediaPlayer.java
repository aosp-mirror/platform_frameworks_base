/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.graphics.Bitmap;
import android.media.AudioManager;

import java.io.FileDescriptor;
import java.io.IOException;

import java.lang.ref.WeakReference;

/**
 * Used to play audio and video files and streams.
 * See the <a href="/android/toolbox/apis/media.html">Android Media APIs</a> 
 * page for help using using MediaPlayer.
 */
public class MediaPlayer
{    
    static {
        System.loadLibrary("media_jni");
    }
    
    private final static String TAG = "MediaPlayer";
    
    private int mNativeContext; // accessed by native methods
    private int mListenerContext; // accessed by native methods
    private Surface mSurface; // accessed by native methods
    private SurfaceHolder  mSurfaceHolder;
    private EventHandler mEventHandler;
    private PowerManager.WakeLock mWakeLock = null;
    private boolean mScreenOnWhilePlaying;
    private boolean mStayAwake;
    
    /**
     * Default constructor. Consider using one of the create() methods for 
     * synchronously instantiating a MediaPlayer from a Uri or resource.
     * <p>When done with the MediaPlayer, you should call  {@link #release()},
     * to free the resources. If not released, too many MediaPlayer instances may
     * result in an exception.</p>
     */
    public MediaPlayer() {
   
        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            mEventHandler = null;
        }

        /* Native setup requires a weak reference to our object.
         * It's easier to create it here than in C++.
         */
        native_setup(new WeakReference<MediaPlayer>(this));
    }
  
    /**
     * Sets the SurfaceHolder to use for displaying the video portion of the media.
     * This call is optional. Not calling it when playing back a video will
     * result in only the audio track being played.
     * 
     * @param sh the SurfaceHolder to use for video display
     */
    public void setDisplay(SurfaceHolder sh) {
        mSurfaceHolder = sh;
        mSurface = sh.getSurface();
        updateSurfaceScreenOn();
    }

    /**
     * Convenience method to create a MediaPlayer for a given Uri.
     * On success, {@link #prepare()} will already have been called and must not be called again.
     * <p>When done with the MediaPlayer, you should call  {@link #release()},
     * to free the resources. If not released, too many MediaPlayer instances will
     * result in an exception.</p>
     * 
     * @param context the Context to use 
     * @param uri the Uri from which to get the datasource
     * @return a MediaPlayer object, or null if creation failed
     */
    public static MediaPlayer create(Context context, Uri uri) {
        return create (context, uri, null);
    }
    
    /**
     * Convenience method to create a MediaPlayer for a given Uri.
     * On success, {@link #prepare()} will already have been called and must not be called again.
     * <p>When done with the MediaPlayer, you should call  {@link #release()},
     * to free the resources. If not released, too many MediaPlayer instances will
     * result in an exception.</p>
     * 
     * @param context the Context to use 
     * @param uri the Uri from which to get the datasource
     * @param holder the SurfaceHolder to use for displaying the video
     * @return a MediaPlayer object, or null if creation failed
     */
    public static MediaPlayer create(Context context, Uri uri, SurfaceHolder holder) {
        
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(context, uri);
            if (holder != null) {
                mp.setDisplay(holder);
            }
            mp.prepare();
            return mp;
        } catch (IOException ex) {
            Log.d(TAG, "create failed:", ex);
            // fall through
        } catch (IllegalArgumentException ex) {
            Log.d(TAG, "create failed:", ex);
            // fall through
        } catch (SecurityException ex) {
            Log.d(TAG, "create failed:", ex);
            // fall through
        }

        return null;
    }

    /**
     * Convenience method to create a MediaPlayer for a given resource id.
     * On success, {@link #prepare()} will already have been called and must not be called again.
     * <p>When done with the MediaPlayer, you should call  {@link #release()},
     * to free the resources. If not released, too many MediaPlayer instances will
     * result in an exception.</p>
     * 
     * @param context the Context to use 
     * @param resid the raw resource id (<var>R.raw.&lt;something></var>) for 
     *              the resource to use as the datasource
     * @return a MediaPlayer object, or null if creation failed
     */
    public static MediaPlayer create(Context context, int resid) {
        try {
            AssetFileDescriptor afd = context.getResources().openRawResourceFd(resid);
            if (afd == null) return null;

            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.prepare();
            return mp;
        } catch (IOException ex) {
            Log.d(TAG, "create failed:", ex);
            // fall through
        } catch (IllegalArgumentException ex) {
            Log.d(TAG, "create failed:", ex);
           // fall through
        } catch (SecurityException ex) {
            Log.d(TAG, "create failed:", ex);
            // fall through
        }
        return null;
    }
    
    /**
     * Sets the data source as a content Uri. Call this after reset(), or before 
     * any other method (including setDataSource()) that might throw 
     * IllegalStateException in this class.
     * 
     * @param context the Context to use when resolving the Uri
     * @param uri the Content URI of the data you want to play
     * @throws IllegalStateException if it is called
     * in an order other than the one specified above
     */
    public void setDataSource(Context context, Uri uri)
        throws IOException, IllegalArgumentException, SecurityException, IllegalStateException {
        
        String scheme = uri.getScheme();
        if(scheme == null || scheme.equals("file")) {
            setDataSource(uri.getPath());
            return;
        }

        ParcelFileDescriptor fd = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            fd = resolver.openFileDescriptor(uri, "r");
            if (fd == null) {
                return;
            }
            setDataSource(fd.getFileDescriptor());
            return;
        } catch (SecurityException ex) {
        } catch (IOException ex) {
        } finally {
            if (fd != null) {
                fd.close();
            }
        }
        setDataSource(uri.toString());
        return;
    }

    /**
     * Sets the data source (file-path or http/rtsp URL) to use. Call this after 
     * reset(), or before any other method (including setDataSource()) that might
     * throw IllegalStateException in this class.
     * 
     * @param path the path of the file, or the http/rtsp URL of the stream you want to play
     * @throws IllegalStateException if it is called
     * in an order other than the one specified above
     */
    public native void setDataSource(String path) throws IOException, IllegalArgumentException, IllegalStateException;

    /**
     * Sets the data source (FileDescriptor) to use. It is the caller's responsibility
     * to close the file descriptor. It is safe to do so as soon as this call returns.
     * Call this after reset(), or before any other method (including setDataSource()) 
     * that might throw IllegalStateException in this class.
     * 
     * @param fd the FileDescriptor for the file you want to play
     * @throws IllegalStateException if it is called
     * in an order other than the one specified above
     */
    public void setDataSource(FileDescriptor fd) 
            throws IOException, IllegalArgumentException, IllegalStateException {
        // intentionally less than LONG_MAX
        setDataSource(fd, 0, 0x7ffffffffffffffL);
    }
    
    /**
     * Sets the data source (FileDescriptor) to use.  It is the caller's responsibility
     * to close the file descriptor. It is safe to do so as soon as this call returns.
     * Call this after reset(), or before any other method (including setDataSource()) 
     * that might throw IllegalStateException in this class.
     * 
     * @param fd the FileDescriptor for the file you want to play
     * @param offset the offset into the file where the data to be played starts, in bytes
     * @param length the length in bytes of the data to be played
     * @throws IllegalStateException if it is called
     * in an order other than the one specified above
     */
    public native void setDataSource(FileDescriptor fd, long offset, long length) 
            throws IOException, IllegalArgumentException, IllegalStateException;

    /**
     * Prepares the player for playback, synchronously. Call this after
     * setDataSource() or stop(), and before any other method that might
     * throw IllegalStateException in this class.
     * 
     * After setting the datasource and the display surface, you need to either
     * call prepare() or prepareAsync(). For files, it is OK to call prepare(),
     * which blocks until MediaPlayer is ready for playback.
     * 
     * @throws IllegalStateException if it is called
     * in an order other than the one specified above
     */
    public native void prepare() throws IOException, IllegalStateException;
    
    /**
     * Prepares the player for playback, asynchronously. Call this after
     * setDataSource() or stop(), and before any other method that might
     * throw IllegalStateException in this class.
     * 
     * After setting the datasource and the display surface, you need to either
     * call prepare() or prepareAsync(). For streams, you should call prepareAsync(),
     * which returns immediately, rather than blocking until enough data has been
     * buffered.
     * 
     * @throws IllegalStateException if it is called
     * in an order other than the one specified above
     */
    public native void prepareAsync() throws IllegalStateException;
    
    /**
     * Starts or resumes playback. If playback had previously been paused,
     * playback will continue from where it was paused. If playback had
     * been stopped, or never started before, playback will start at the
     * beginning. Call this after receiving onCompletion or onPrepared
     * event notification from OnCompletionListener or OnPreparedListener
     * interface, or called after prepare() or pause().
     * 
     * @throws IllegalStateException if it is called
     * in an order other than the one specified above
     */
    public  void start() throws IllegalStateException {
        stayAwake(true);
        _start();
    }

    private native void _start() throws IllegalStateException;
    
    /**
     * Stops playback after playback has been stopped or paused. 
     * Call this after start() or pause(), or after receiving the onPrepared 
     * event notification from OnPreparedListener interface.
     * 
     * @throws IllegalStateException if it is called
     * in an order other than the one specified above
     */
    public void stop() throws IllegalStateException {
        stayAwake(false);
        _stop();
    }

    private native void _stop() throws IllegalStateException;
    
    /**
     * Pauses playback. Call start() to resume. Call this after start() 
     * and before any other method that might throw IllegalStateException in this class.
     * 
     * @throws IllegalStateException if it is called
     * in an order other than the one specified above
     */
    public void pause() throws IllegalStateException {
        stayAwake(false);
        _pause();
    }

    private native void _pause() throws IllegalStateException;
    
    /**
     * Set the low-level power management behavior for this MediaPlayer.  This
     * can be used when the MediaPlayer is not playing through a SurfaceHolder
     * set with {@link #setDisplay(SurfaceHolder)} and thus can use the
     * high-level {@link #setScreenOnWhilePlaying(boolean)} feature.
     * 
     * <p>This function has the MediaPlayer access the low-level power manager
     * service to control the device's power usage while playing is occurring.
     * The parameter is a combination of {@link android.os.PowerManager} wake flags.
     * Use of this method requires {@link android.Manifest.permission#WAKE_LOCK}
     * permission.
     * By default, no attempt is made to keep the device awake during playback.
     * 
     * @param context the Context to use
     * @param mode    the power/wake mode to set
     * @see android.os.PowerManager
     */
    public void setWakeMode(Context context, int mode) {
        boolean washeld = false;
        if (mWakeLock != null) {
            if (mWakeLock.isHeld()) {
                washeld = true;
                mWakeLock.release();
            }
            mWakeLock = null;
        }

        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(mode|PowerManager.ON_AFTER_RELEASE, MediaPlayer.class.getName());
        mWakeLock.setReferenceCounted(false);
        if (washeld) {
            mWakeLock.acquire();
        }
    }
    
    /**
     * Control whether we should use the attached SurfaceHolder to keep the
     * screen on while video playback is occurring.  This is the preferred
     * method over {@link #setWakeMode} where possible, since it doesn't
     * require that the application have permission for low-level wake lock
     * access.
     * 
     * @param screenOn Supply true to keep the screen on, false to allow it
     * to turn off.
     */
    public void setScreenOnWhilePlaying(boolean screenOn) {
        if (mScreenOnWhilePlaying != screenOn) {
            mScreenOnWhilePlaying = screenOn;
            updateSurfaceScreenOn();
        }
    }
    
    private void stayAwake(boolean awake) {
        if (mWakeLock != null) {
            if (awake && !mWakeLock.isHeld()) {
                mWakeLock.acquire();
            } else if (!awake && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
        mStayAwake = awake;
        updateSurfaceScreenOn();
    }
    
    private void updateSurfaceScreenOn() {
        if (mSurfaceHolder != null) {
            mSurfaceHolder.setKeepScreenOn(mScreenOnWhilePlaying && mStayAwake);
        }
    }
    
    /**
     * Returns the width of the video. Call this after setDataSource() method.
     * 
     * @return the width of the video, or 0 if there is no video,
     * no display surface was set, or prepare()/prepareAsync()
     * have not completed yet
     */
    public native int getVideoWidth();
    
    /**
     * Returns the height of the video. Call this after setDataSource() method.
     * 
     * @return the height of the video, or 0 if there is no video,
     * no display surface was set, or prepare()/prepareAsync()
     * have not completed yet
     */
    public native int getVideoHeight();
    
    /**
     * Checks whether the MediaPlayer is playing. Call this after
     * setDataSource() method.
     * 
     * @return true if currently playing, false otherwise
     */
    public native boolean isPlaying();
    
    /**
     * Seeks to specified time position. Call this after start(), pause(), or 
     * prepare(), or after receiving onPrepared or onCompletion event notification
     * from OnPreparedListener or OnCompletionListener interface.
     * 
     * @param msec the offset in milliseconds from the start to seek to
     * @throws IllegalStateException if it is called
     * in an order other than the one specified above
     */
    public native void seekTo(int msec) throws IllegalStateException;
    
    /**
     * Gets the current playback position. Call this after setDataSource() method.
     * 
     * @return the current position in milliseconds
     */
    public native int getCurrentPosition();
    
    /**
     * Gets the duration of the file.  Call this after setDataSource() method.
     * 
     * @return the duration in milliseconds
     */
    public native int getDuration();
    
    /**
     * Releases resources associated with this MediaPlayer object.
     * It is considered good practice to call this method when you're
     * done using the MediaPlayer.
     */
    public void release() {
        if (mWakeLock != null) mWakeLock.release();
        updateSurfaceScreenOn();
        mOnPreparedListener = null;
        mOnBufferingUpdateListener = null;
        mOnCompletionListener = null;
        mOnSeekCompleteListener = null;
        mOnErrorListener = null;
        _release();
    }

    private native void _release();
    
    /**
     * Resets the MediaPlayer to its uninitialized state. After calling
     * this method, you will have to initialize it again by setting the
     * data source and calling prepare().
     */
    public void reset() {
        _reset();
        // make sure none of the listeners get called anymore
        mEventHandler.removeCallbacksAndMessages(null);
    }
    
    private native void _reset();
    
    /**
     * Sets the audio stream type for this MediaPlayer. See {@link AudioManager}
     * for a list of stream types.
     *  
     * @param streamtype the audio stream type
     * @see android.media.AudioManager
     */
    public native void setAudioStreamType(int streamtype);

    /**
     * Sets the player to be looping or non-looping. Call this
     * after setDataSource method.
     * 
     * @param looping whether to loop or not
     */
    public native void setLooping(boolean looping);

    /**
     * Sets the volume on this player. Call after setDataSource method.
     * This API is recommended for balancing the output of audio streams
     * within an application. Unless you are writing an application to
     * control user settings, this API should be used in preference to
     * AudioManager::setStreamVolume API which sets the volume of ALL streams of
     * a particular type. Note that the passed volume values are raw scalars.
     * UI controls should be scaled logarithmically.
     *
     * @param leftVolume left volume scalar
     * @param rightVolume right volume scalar
     */
    public native void setVolume(float leftVolume, float rightVolume);

    /**
     * Returns a Bitmap containing the video frame at the specified time. Call
     * this after setDataSource() or stop().
     *
     * @param msec the time at which to capture the video frame, in milliseconds
     * @return a Bitmap containing the video frame at the specified time
     * @throws IllegalStateException if it is called
     * in an order other than the one specified above
     * @hide
     */
    public native Bitmap getFrameAt(int msec) throws IllegalStateException;
 
    private native final void native_setup(Object mediaplayer_this);
    private native final void native_finalize();
    protected void finalize() { native_finalize(); }

    /* Do not change these values without updating their counterparts
     * in include/media/mediaplayer.h!
     */
    private static final int MEDIA_NOP = 0; // interface test message
    private static final int MEDIA_PREPARED = 1;
    private static final int MEDIA_PLAYBACK_COMPLETE = 2;
    private static final int MEDIA_BUFFERING_UPDATE = 3;
    private static final int MEDIA_SEEK_COMPLETE = 4;
    private static final int MEDIA_ERROR = 100;

    // error codes from framework that indicate content issues
    // contained in arg1 of error message

    // Seek not supported - live stream
    private static final int ERROR_SEEK_NOT_SUPPORTED = 42;

    // A/V interleave exceeds the progressive streaming buffer
    private static final int ERROR_CONTENT_IS_POORLY_INTERLEAVED = 43;

    // video decoder is falling behind - content is too complex
    private static final int ERROR_VIDEO_TRACK_IS_FALLING_BEHIND = 44;

    private class EventHandler extends Handler
    {
        private MediaPlayer mMediaPlayer;

        public EventHandler(MediaPlayer mp, Looper looper) {
            super(looper);
            mMediaPlayer = mp;
        }

        @Override
        public void handleMessage(Message msg) {
            if (mMediaPlayer.mNativeContext == 0) {
                Log.w(TAG, "mediaplayer went away with unhandled events");
                return;
            }
            switch(msg.what) {
            case MEDIA_PREPARED:
                if (mOnPreparedListener != null)
                    mOnPreparedListener.onPrepared(mMediaPlayer);
                return;

            case MEDIA_PLAYBACK_COMPLETE:
                if (mOnCompletionListener != null)
                    mOnCompletionListener.onCompletion(mMediaPlayer);
                stayAwake(false);
                return;

            case MEDIA_BUFFERING_UPDATE:
                if (mOnBufferingUpdateListener != null)
                    mOnBufferingUpdateListener.onBufferingUpdate(mMediaPlayer, msg.arg1);
                return;

            case MEDIA_SEEK_COMPLETE:
              if (mOnSeekCompleteListener != null)
                  mOnSeekCompleteListener.onSeekComplete(mMediaPlayer);
              return;

            case MEDIA_ERROR:
                Log.e(TAG, "Error (" + msg.arg1 + "," + msg.arg2 + ")");
                boolean error_was_handled = false;
                if (mOnErrorListener != null) {
                    error_was_handled = mOnErrorListener.onError(mMediaPlayer, msg.arg1, msg.arg2);
                }
                if (mOnCompletionListener != null && ! error_was_handled) {
                    mOnCompletionListener.onCompletion(mMediaPlayer);
                }
                stayAwake(false);
                return;
            case MEDIA_NOP: // interface test message - ignore
                break;

            default:
                Log.e(TAG, "Unknown message type " + msg.what);
                return;
            }
        }
    }

    /**
     * Called from native code when an interesting event happens.  This method
     * just uses the EventHandler system to post the event back to the main app thread.
     * We use a weak reference to the original MediaPlayer object so that the native
     * code is safe from the object disappearing from underneath it.  (This is
     * the cookie passed to native_setup().)
     */
    private static void postEventFromNative(Object mediaplayer_ref,
                                            int what, int arg1, int arg2, Object obj)
    {
        MediaPlayer mp = (MediaPlayer)((WeakReference)mediaplayer_ref).get();
        if (mp == null) {
            return;
        }

        if (mp.mEventHandler != null) {
            Message m = mp.mEventHandler.obtainMessage(what, arg1, arg2, obj);
            mp.mEventHandler.sendMessage(m);
        }
    }

    /**
     * Interface definition for a callback to be invoked when the media
     * file is ready for playback.
     */
    public interface OnPreparedListener
    {
        /**
         * Called when the media file is ready for playback.
         * 
         * @param mp the MediaPlayer that is ready for playback
         */
        void onPrepared(MediaPlayer mp);
    }

    /**
     * Register a callback to be invoked when the media file is ready
     * for playback.
     *
     * @param l the callback that will be run
     */
    public void setOnPreparedListener(OnPreparedListener l)
    {
        mOnPreparedListener = l;
    }

    private OnPreparedListener mOnPreparedListener;

    /**
     * Interface definition for a callback to be invoked when playback of
     * a media file has completed.
     */
    public interface OnCompletionListener
    {
        /**
         * Called when the end of a media file is reached during playback.
         * 
         * @param mp the MediaPlayer that reached the end of the file
         */
        void onCompletion(MediaPlayer mp);
    }

    /**
     * Register a callback to be invoked when the end of a media file
     * has been reached during playback.
     *
     * @param l the callback that will be run
     */
    public void setOnCompletionListener(OnCompletionListener l)
    {
        mOnCompletionListener = l;
    }

    private OnCompletionListener mOnCompletionListener;

    /**
     * Interface definition of a callback to be invoked indicating buffering
     * status of a media resource being streamed over the network.
     */
    public interface OnBufferingUpdateListener
    {
        /**
         * Called to update status in buffering a media stream.
         * 
         * @param mp      the MediaPlayer the update pertains to
         * @param percent the percentage (0-100) of the buffer
         *                that has been filled thus far
         */
        void onBufferingUpdate(MediaPlayer mp, int percent);
    }
   
    /**
     * Register a callback to be invoked when the status of a network
     * stream's buffer has changed.
     *
     * @param l the callback that will be run
     */
    public void setOnBufferingUpdateListener(OnBufferingUpdateListener l)
    {
        mOnBufferingUpdateListener = l;
    }

    private OnBufferingUpdateListener mOnBufferingUpdateListener;
    
    /**
     * Interface definition of a callback to be invoked indicating
     * the completion of a seek operation.
     */
    public interface OnSeekCompleteListener
    {
        /**
         * Called to indicate the completion of a seek operation.
         * 
         * @param mp the MediaPlayer that issued the seek operation
         */
        public void onSeekComplete(MediaPlayer mp);
    }
    
    /**
     * Register a callback to be invoked when a seek operation has been
     * completed.
     * 
     * @param l the callback that will be run
     */
    public void setOnSeekCompleteListener(OnSeekCompleteListener l)
    {
        mOnSeekCompleteListener = l;
    }
    
    private OnSeekCompleteListener mOnSeekCompleteListener;

    /* Do not change these values without updating their counterparts
     * in include/media/mediaplayer.h!
     */
    /** Unspecified media player error.  @see #OnErrorListener */
    public static final int MEDIA_ERROR_UNKNOWN = 1;
    /** Media server died. In this case, the application must release the
     * MediaPlayer object and instantiate a new one. @see #OnErrorListener */
    public static final int MEDIA_ERROR_SERVER_DIED = 100;
    

    /**
     * Interface definition of a callback to be invoked when there
     * has been an error during an asynchronous operation (other errors
     * will throw exceptions at method call time).
     */
    public interface OnErrorListener
    {
        /**
         * Called to indicate an error.
         * 
         * @param mp      the MediaPlayer the error pertains to
         * @param what    the type of error that has occurred:
         * <ul>
         * <li>{@link #MEDIA_ERROR_UNKNOWN}
         * <li>{@link #MEDIA_ERROR_SERVER_DIED}
         * </ul>
         * @param extra   an extra code, specific to the error type
         * @return True if the method handled the error, false if it didn't.
         * Returning false, or not having an OnErrorListener at all, will
         * cause the OnCompletionListener to be called.
         */
        boolean onError(MediaPlayer mp, int what, int extra);
    }
   
    /**
     * Register a callback to be invoked when an error has happened
     * during an asynchronous operation.
     * 
     * @param l the callback that will be run
     */
    public void setOnErrorListener(OnErrorListener l)
    {
        mOnErrorListener = l;
    }

    private OnErrorListener mOnErrorListener;
}
