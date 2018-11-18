/*
 * Copyright 2017 The Android Open Source Project
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

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer2Proto.PlayerMessage;
import android.media.MediaPlayer2Proto.Value;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.util.Log;
import android.util.Pair;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.android.framework.protobuf.InvalidProtocolBufferException;
import com.android.internal.annotations.GuardedBy;

import dalvik.system.CloseGuard;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @hide
 *
 * MediaPlayer2 class can be used to control playback of audio/video files and streams.
 *
 * <p>Topics covered here are:
 * <ol>
 * <li><a href="#PlayerStates">Player states</a>
 * <li><a href="#InvalidStates">Invalid method calls</a>
 * <li><a href="#Permissions">Permissions</a>
 * <li><a href="#Callbacks">Callbacks</a>
 * </ol>
 *
 *
 * <h3 id="PlayerStates">Player states</h3>
 *
 * <p>The playback control of audio/video files is managed as a state machine.</p>
 * <p><div style="text-align:center;"><img src="../../../images/mediaplayer2_state_diagram.png"
 *         alt="MediaPlayer2 State diagram"
 *         border="0" /></div></p>
 * <p>The MediaPlayer2 object has five states:</p>
 * <ol>
 *     <li><p>{@link #PLAYER_STATE_IDLE}: MediaPlayer2 is in the <strong>Idle</strong>
 *         state after you create it using
 *         {@link #create()}, or after calling {@link #reset()}.</p>
 *
 *         <p>While in this state, you should call
 *         {@link #setDataSource(DataSourceDesc2) setDataSource()}. It is a good
 *         programming practice to register an {@link EventCallback#onCallCompleted onCallCompleted}
 *         <a href="#Callbacks">callback</a> and watch for {@link #CALL_STATUS_BAD_VALUE} and
 *         {@link #CALL_STATUS_ERROR_IO}, which might be caused by <code>setDataSource</code>.
 *         </p>
 *
 *         <p>Calling {@link #prepare()} transfers a MediaPlayer2 object to
 *         the <strong>Prepared</strong> state. Note
 *         that {@link #prepare()} is asynchronous. When the preparation completes,
 *         if you register an {@link EventCallback#onInfo onInfo} <a href="#Callbacks">callback</a>,
 *         the player executes the callback
 *         with {@link #MEDIA_INFO_PREPARED} and transitions to the
 *         <strong>Prepared</strong> state.</p>
 *         </li>
 *
 *     <li>{@link #PLAYER_STATE_PREPARED}: A MediaPlayer object must be in the
 *         <strong>Prepared</strong> state before playback can be started for the first time.
 *         While in this state, you can set player properties
 *         such as audio/sound volume and looping by invoking the corresponding set methods.
 *         Calling {@link #play()} transfers a MediaPlayer2 object to
 *         the <strong>Playing</strong> state.
 *      </li>
 *
 *     <li>{@link #PLAYER_STATE_PLAYING}:
 *         <p>The player plays the data source while in this state.
 *         If you register an {@link EventCallback#onInfo onInfo} <a href="#Callbacks">callback</a>,
 *         the player regularly executes the callback with
 *         {@link #MEDIA_INFO_BUFFERING_UPDATE}.
 *         This allows applications to keep track of the buffering status
 *         while streaming audio/video.</p>
 *
 *         <p> When the playback reaches the end of stream, the behavior depends on whether or
 *         not you've enabled looping by calling {@link #loopCurrent(boolean) loopCurrent}:</p>
 *         <ul>
 *         <li>If the looping mode was set to <code>false</code>, the player will transfer
 *         to the <strong>Paused</strong> state. If you registered an {@link EventCallback#onInfo
 *         onInfo} <a href="#Callbacks">callback</a>
 *         the player calls the callback with {@link #MEDIA_INFO_DATA_SOURCE_END} and enters
 *         the <strong>Paused</strong> state.
 *         </li>
 *         <li>If the looping mode was set to <code>true</code>,
 *         the MediaPlayer2 object remains in the <strong>Playing</strong> state and replays its
 *         data source from the beginning.</li>
 *         </ul>
 *         </li>
 *
 *     <li>{@link #PLAYER_STATE_PAUSED}: Audio/video playback pauses while in this state.
 *         Call {@link #play()} to resume playback from the position where it paused.</li>
 *
 *     <li>{@link #PLAYER_STATE_ERROR}: <p>In general, playback might fail due to various
 *          reasons such as unsupported audio/video format, poorly interleaved
 *          audio/video, resolution too high, streaming timeout, and others.
 *          In addition, due to programming errors, a playback
 *          control operation might be performed from an <a href="#InvalidStates">invalid state</a>.
 *          In these cases the player transitions to the <strong>Error</strong> state.</p>
 *
 *          <p>If you register an {@link EventCallback#onError onError}}
 *          <a href="#Callbacks">callback</a>,
 *          the callback will be performed when entering the state. When programming errors happen,
 *          such as calling {@link #prepare() prepare} and
 *          {@link #setDataSource(DataSourceDesc) setDataSource} methods
 *          from an <a href="#InvalidStates">invalid state</a>, the callback is called with
 *          {@link #CALL_STATUS_INVALID_OPERATION}. The MediaPlayer2 object enters the
 *          <strong>Error</strong> state whether or not a callback exists. </p>
 *
 *          <p>To recover from an error and reuse a MediaPlayer2 object that is in the <strong>
 *          Error</strong> state,
 *          call {@link #reset() reset}. The object will return to the <strong>Idle</strong>
 *          state and all state information will be lost.</p>
 *          </li>
 * </ol>
 *
 * <p>You should follow these best practices when coding an app that uses MediaPlayer2:</p>
 *
 * <ul>
 *
 * <li>Use <a href="#Callbacks">callbacks</a> to respond to state changes and errors.</li>
 *
 * <li>When  a MediaPlayer2 object is no longer being used, call {@link #close() close} as soon as
 * possible to release the resources used by the internal player engine associated with the
 * MediaPlayer2. Failure to call {@link #close() close} may cause subsequent instances of
 * MediaPlayer2 objects to fallback to software implementations or fail altogether.
 * You cannot use MediaPlayer2
 * after you call {@link #close() close}. There is no way to bring it back to any other state.</li>
 *
 * <li>The current playback position can be retrieved with a call to
 * {@link #getCurrentPosition() getCurrentPosition},
 * which is helpful for applications such as a Music player that need to keep track of the playback
 * progress.</li>
 *
 * <li>The playback position can be adjusted with a call to {@link #seekTo seekTo}. Although the
 * asynchronous {@link #seekTo seekTo} call returns right away, the actual seek operation may take a
 * while to finish, especially for audio/video being streamed. If you register an
 * {@link EventCallback#onCallCompleted onCallCompleted} <a href="#Callbacks">callback</a>,
 * the callback is
 * called When the seek operation completes with {@link #CALL_COMPLETED_SEEK_TO}.</li>
 *
 * <li>You can call {@link #seekTo seekTo} from the <strong>Paused</strong> state.
 * In this case, if you are playing a video stream and
 * the requested position is valid  one video frame is displayed.</li>
 *
 * </ul>
 *
 * <h3 id="InvalidStates">Invalid method calls</h3>
 *
 * <p>The only methods you safely call from the <strong>Error</strong> state are
 * {@link #close() close},
 * {@link #reset() reset},
 * {@link #notifyWhenCommandLabelReached notifyWhenCommandLabelReached},
 * {@link #clearPendingCommands() clearPendingCommands},
 * {@link #setEventCallback setEventCallback},
 * {@link #clearEventCallback() clearEventCallback}
 * and {@link #getState() getState}.
 * Any other methods might throw an exception, return meaningless data, or invoke a
 * {@link EventCallback#onCallCompleted onCallCompleted} with an error code.</p>
 *
 * <p>Most methods can be called from any non-Error state. They will either perform their work or
 * silently have no effect. The following table lists the methods that will invoke a
 * {@link EventCallback#onCallCompleted onCallCompleted} with an error code
 * or throw an exception when they are called from the associated invalid states.</p>
 *
 * <table border="0" cellspacing="0" cellpadding="0">
 * <tr><th>Method Name</th>
 * <th>Invalid States</th></tr>
 *
 * <tr><td>setDataSource</td> <td>{Prepared, Paused, Playing}</td></tr>
 * <tr><td>prepare</td> <td>{Prepared, Paused, Playing}</td></tr>
 * <tr><td>play</td> <td>{Idle}</td></tr>
 * <tr><td>pause</td> <td>{Idle}</td></tr>
 * <tr><td>seekTo</td> <td>{Idle}</td></tr>
 * <tr><td>getCurrentPosition</td> <td>{Idle}</td></tr>
 * <tr><td>getDuration</td> <td>{Idle}</td></tr>
 * <tr><td>getBufferedPosition</td> <td>{Idle}</td></tr>
 * <tr><td>getTrackInfo</td> <td>{Idle}</td></tr>
 * <tr><td>getSelectedTrack</td> <td>{Idle}</td></tr>
 * <tr><td>selectTrack</td> <td>{Idle}</td></tr>
 * <tr><td>deselectTrack</td> <td>{Idle}</td></tr>
 * </table>
 *
 * <h3 id="Permissions">Permissions</h3>
 * <p>This class requires the {@link android.Manifest.permission#INTERNET} permission
 * when used with network-based content.
 *
 * <h3 id="Callbacks">Callbacks</h3>
 * <p>Many errors do not result in a transition to the  <strong>Error</strong> state.
 * It is good programming practice to register callback listeners using
 * {@link #setEventCallback(Executor, EventCallback) setEventCallback} and
 * {@link #setDrmEventCallback(Executor, DrmEventCallback) setDrmEventCallback}).
 * You can receive a callback at any time and from any state.</p>
 *
 * <p>If it's important for your app to respond to state changes (for instance, to update the
 * controls on a transport UI), you should register an
 * {@link EventCallback#onCallCompleted onCallCompleted} and
 * detect state change commands by testing the <code>what</code> parameter for a callback from one
 * of the state transition methods: {@link #CALL_COMPLETED_PREPARE}, {@link #CALL_COMPLETED_PLAY},
 * and {@link #CALL_COMPLETED_PAUSE}.
 * Then check the <code>status</code> parameter. The value {@link #CALL_STATUS_NO_ERROR} indicates a
 * successful transition. Any other value will be an error. Call {@link #getState()} to
 * determine the current state. </p>
 */
public class MediaPlayer2 implements AutoCloseable
                                            , AudioRouting {
    static {
        System.loadLibrary("media2_jni");
        native_init();
    }

    private static native void native_init();

    private static final int NEXT_SOURCE_STATE_ERROR = -1;
    private static final int NEXT_SOURCE_STATE_INIT = 0;
    private static final int NEXT_SOURCE_STATE_PREPARING = 1;
    private static final int NEXT_SOURCE_STATE_PREPARED = 2;

    private static final String TAG = "MediaPlayer2";

    private Context mContext;

    private long mNativeContext;  // accessed by native methods
    private long mNativeSurfaceTexture;  // accessed by native methods
    private int mListenerContext;  // accessed by native methods
    private SurfaceHolder mSurfaceHolder;
    private PowerManager.WakeLock mWakeLock = null;
    private boolean mScreenOnWhilePlaying;
    private boolean mStayAwake;

    private final Object mSrcLock = new Object();
    //--- guarded by |mSrcLock| start
    private SourceInfo mCurrentSourceInfo;
    private final Queue<SourceInfo> mNextSourceInfos = new ConcurrentLinkedQueue<>();
    //--- guarded by |mSrcLock| end
    private final AtomicLong mSrcIdGenerator = new AtomicLong(0);

    private volatile float mVolume = 1.0f;
    private VideoSize mVideoSize = new VideoSize(0, 0);

    // Modular DRM
    private final Object mDrmLock = new Object();
    //--- guarded by |mDrmLock| start
    private UUID mDrmUUID;
    private DrmInfo mDrmInfo;
    private MediaDrm mDrmObj;
    private byte[] mDrmSessionId;
    private boolean mDrmInfoResolved;
    private boolean mActiveDrmScheme;
    private boolean mDrmConfigAllowed;
    private boolean mDrmProvisioningInProgress;
    private boolean mPrepareDrmInProgress;
    private ProvisioningThread mDrmProvisioningThread;
    //--- guarded by |mDrmLock| end

    // Creating a dummy audio track, used for keeping session id alive
    private final Object mSessionIdLock = new Object();
    @GuardedBy("mSessionIdLock")
    private AudioTrack mDummyAudioTrack;

    private HandlerThread mHandlerThread;
    private final TaskHandler mTaskHandler;
    private final Object mTaskLock = new Object();
    @GuardedBy("mTaskLock")
    private final List<Task> mPendingTasks = new LinkedList<>();
    @GuardedBy("mTaskLock")
    private Task mCurrentTask;

    @GuardedBy("mTaskLock")
    boolean mIsPreviousCommandSeekTo = false;
    // |mPreviousSeekPos| and |mPreviousSeekMode| are valid only when |mIsPreviousCommandSeekTo|
    // is true, and they are accessed on |mHandlerThread| only.
    long mPreviousSeekPos = -1;
    int mPreviousSeekMode = SEEK_PREVIOUS_SYNC;

    @GuardedBy("this")
    private boolean mReleased;

    private final CloseGuard mGuard = CloseGuard.get();

    /**
     * Default constructor.
     * <p>When done with the MediaPlayer2, you should call {@link #close()},
     * to free the resources. If not released, too many MediaPlayer2 instances may
     * result in an exception.</p>
     */
    public MediaPlayer2(Context context) {
        mGuard.open("close");

        mContext = context;
        mHandlerThread = new HandlerThread("MediaPlayer2TaskThread");
        mHandlerThread.start();
        Looper looper = mHandlerThread.getLooper();
        mTaskHandler = new TaskHandler(this, looper);
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int sessionId = am.generateAudioSessionId();
        keepAudioSessionIdAlive(sessionId);

        /* Native setup requires a weak reference to our object.
         * It's easier to create it here than in C++.
         */
        native_setup(sessionId, new WeakReference<MediaPlayer2>(this));
    }

    private native void native_setup(int sessionId, Object mediaplayer2This);

    /**
     * Releases the resources held by this {@code MediaPlayer2} object.
     *
     * It is considered good practice to call this method when you're
     * done using the MediaPlayer2. In particular, whenever an Activity
     * of an application is paused (its onPause() method is called),
     * or stopped (its onStop() method is called), this method should be
     * invoked to release the MediaPlayer2 object, unless the application
     * has a special need to keep the object around. In addition to
     * unnecessary resources (such as memory and instances of codecs)
     * being held, failure to call this method immediately if a
     * MediaPlayer2 object is no longer needed may also lead to
     * continuous battery consumption for mobile devices, and playback
     * failure for other applications if no multiple instances of the
     * same codec are supported on a device. Even if multiple instances
     * of the same codec are supported, some performance degradation
     * may be expected when unnecessary multiple instances are used
     * at the same time.
     *
     * {@code close()} may be safely called after a prior {@code close()}.
     * This class implements the Java {@code AutoCloseable} interface and
     * may be used with try-with-resources.
     */
    // This is a synchronous call.
    @Override
    public void close() {
        synchronized (mGuard) {
            mGuard.close();
        }
        release();
    }

    private synchronized void release() {
        if (mReleased) {
            return;
        }
        stayAwake(false);
        updateSurfaceScreenOn();
        synchronized (mEventCbLock) {
            mEventCallbackRecords.clear();
        }
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
            mHandlerThread = null;
        }

        // Modular DRM clean up
        mOnDrmConfigHelper = null;
        synchronized (mDrmEventCbLock) {
            mDrmEventCallbackRecords.clear();
        }
        resetDrmState();

        native_release();

        synchronized (mSessionIdLock) {
            mDummyAudioTrack.release();
        }

        mReleased = true;
    }

    private native void native_release();

    // Have to declare protected for finalize() since it is protected
    // in the base class Object.
    @Override
    protected void finalize() throws Throwable {
        if (mGuard != null) {
            mGuard.warnIfOpen();
        }

        close();
        native_finalize();
    }

    private native void native_finalize();

    /**
     * Resets the MediaPlayer2 to its uninitialized state. After calling
     * this method, you will have to initialize it again by setting the
     * data source and calling prepare().
     */
    // This is a synchronous call.
    public void reset() {
        synchronized (mEventCbLock) {
            mEventCallbackRecords.clear();
        }
        synchronized (mDrmEventCbLock) {
            mDrmEventCallbackRecords.clear();
        }
        synchronized (mSrcLock) {
            mCurrentSourceInfo = null;
            mNextSourceInfos.clear();
        }

        synchronized (mTaskLock) {
            mPendingTasks.clear();
            mIsPreviousCommandSeekTo = false;
        }

        stayAwake(false);
        native_reset();

        AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        int sessionId = am.generateAudioSessionId();
        keepAudioSessionIdAlive(sessionId);

        // make sure none of the listeners get called anymore
        if (mTaskHandler != null) {
            mTaskHandler.removeCallbacksAndMessages(null);
        }

        resetDrmState();
    }

    private native void native_reset();

    /**
     * Starts or resumes playback. If playback had previously been paused,
     * playback will continue from where it was paused. If playback had
     * reached end of stream and been paused, or never started before,
     * playback will start at the beginning.
     *
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object play() {
        return addTask(new Task(CALL_COMPLETED_PLAY, false) {
            @Override
            void process() {
                stayAwake(true);
                native_start();
            }
        });
    }

    private native void native_start() throws IllegalStateException;

    /**
     * Prepares the player for playback, asynchronously.
     *
     * After setting the datasource and the display surface, you need to call prepare().
     *
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object prepare() {
        return addTask(new Task(CALL_COMPLETED_PREPARE, true) {
            @Override
            void process() {
                native_prepare();
            }
        });
    }

    private native void native_prepare();

    /**
     * Pauses playback. Call play() to resume.
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object pause() {
        return addTask(new Task(CALL_COMPLETED_PAUSE, false) {
            @Override
            void process() {
                stayAwake(false);

                native_pause();
            }
        });
    }

    private native void native_pause() throws IllegalStateException;

    /**
     * Tries to play next data source if applicable.
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object skipToNext() {
        return addTask(new Task(CALL_COMPLETED_SKIP_TO_NEXT, false) {
            @Override
            void process() {
                if (getState() == PLAYER_STATE_PLAYING) {
                    pause();
                }
                playNextDataSource();
            }
        });
    }

    /**
     * Gets the current playback position.
     *
     * @return the current position in milliseconds
     */
    public native long getCurrentPosition();

    /**
     * Gets the duration of the file.
     *
     * @return the duration in milliseconds, if no duration is available
     *         (for example, if streaming live content), -1 is returned.
     */
    public native long getDuration();

    /**
     * Gets the current buffered media source position received through progressive downloading.
     * For example a buffering update of 8000 milliseconds when 5000 milliseconds of the content
     * has already been played indicates that the next 3000 milliseconds of the
     * content to play has been buffered.
     *
     * @return the current buffered media source position in milliseconds
     */
    public long getBufferedPosition() {
        // Use cached buffered percent for now.
        int bufferedPercentage;
        synchronized (mSrcLock) {
            if (mCurrentSourceInfo == null) {
                bufferedPercentage = 0;
            } else {
                bufferedPercentage = mCurrentSourceInfo.mBufferedPercentage.get();
            }
        }
        return getDuration() * bufferedPercentage / 100;
    }

    /**
     * MediaPlayer2 has not been prepared or just has been reset.
     * In this state, MediaPlayer2 doesn't fetch data.
     */
    public static final int PLAYER_STATE_IDLE = 1001;

    /**
     * MediaPlayer2 has been just prepared.
     * In this state, MediaPlayer2 just fetches data from media source,
     * but doesn't actively render data.
     */
    public static final int PLAYER_STATE_PREPARED = 1002;

    /**
     * MediaPlayer2 is paused.
     * In this state, MediaPlayer2 has allocated resources to construct playback
     * pipeline, but it doesn't actively render data.
     */
    public static final int PLAYER_STATE_PAUSED = 1003;

    /**
     * MediaPlayer2 is actively playing back data.
     */
    public static final int PLAYER_STATE_PLAYING = 1004;

    /**
     * MediaPlayer2 has hit some fatal error and cannot continue playback.
     */
    public static final int PLAYER_STATE_ERROR = 1005;

    /**
     * @hide
     */
    @IntDef(flag = false, prefix = "MEDIAPLAYER2_STATE", value = {
        PLAYER_STATE_IDLE,
        PLAYER_STATE_PREPARED,
        PLAYER_STATE_PAUSED,
        PLAYER_STATE_PLAYING,
        PLAYER_STATE_ERROR })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MediaPlayer2State {}

    /**
     * Gets the current player state.
     *
     * @return the current player state.
     */
    public @MediaPlayer2State int getState() {
        return native_getState();
    }

    private native int native_getState();

    /**
     * Sets the audio attributes for this MediaPlayer2.
     * See {@link AudioAttributes} for how to build and configure an instance of this class.
     * You must call this method before {@link #play()} and {@link #pause()} in order
     * for the audio attributes to become effective thereafter.
     * @param attributes a non-null set of audio attributes
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object setAudioAttributes(@NonNull AudioAttributes attributes) {
        return addTask(new Task(CALL_COMPLETED_SET_AUDIO_ATTRIBUTES, false) {
            @Override
            void process() {
                if (attributes == null) {
                    final String msg = "Cannot set AudioAttributes to null";
                    throw new IllegalArgumentException(msg);
                }
                native_setAudioAttributes(attributes);
            }
        });
    }

    // return true if the parameter is set successfully, false otherwise
    private native boolean native_setAudioAttributes(AudioAttributes audioAttributes);

    /**
     * Gets the audio attributes for this MediaPlayer2.
     * @return attributes a set of audio attributes
     */
    public @NonNull AudioAttributes getAudioAttributes() {
        return native_getAudioAttributes();
    }

    private native AudioAttributes native_getAudioAttributes();

    /**
     * Sets the data source as described by a DataSourceDesc.
     *
     * @param dsd the descriptor of data source you want to play
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object setDataSource(@NonNull DataSourceDesc dsd) {
        return addTask(new Task(CALL_COMPLETED_SET_DATA_SOURCE, false) {
            @Override
            void process() throws IOException {
                checkArgument(dsd != null, "the DataSourceDesc cannot be null");
                int state = getState();
                if (state != PLAYER_STATE_ERROR && state != PLAYER_STATE_IDLE) {
                    throw new IllegalStateException("called in wrong state " + state);
                }

                synchronized (mSrcLock) {
                    mCurrentSourceInfo = new SourceInfo(dsd);
                    handleDataSource(true /* isCurrent */, dsd, mCurrentSourceInfo.mId);
                }
            }
        });
    }

    /**
     * Sets a single data source as described by a DataSourceDesc which will be played
     * after current data source is finished.
     *
     * @param dsd the descriptor of data source you want to play after current one
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object setNextDataSource(@NonNull DataSourceDesc dsd) {
        return addTask(new Task(CALL_COMPLETED_SET_NEXT_DATA_SOURCE, false) {
            @Override
            void process() {
                checkArgument(dsd != null, "the DataSourceDesc cannot be null");
                synchronized (mSrcLock) {
                    mNextSourceInfos.clear();
                    mNextSourceInfos.add(new SourceInfo(dsd));
                }
                prepareNextDataSource();
            }
        });
    }

    /**
     * Sets a list of data sources to be played sequentially after current data source is done.
     *
     * @param dsds the list of data sources you want to play after current one
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object setNextDataSources(@NonNull List<DataSourceDesc> dsds) {
        return addTask(new Task(CALL_COMPLETED_SET_NEXT_DATA_SOURCES, false) {
            @Override
            void process() {
                if (dsds == null || dsds.size() == 0) {
                    throw new IllegalArgumentException("data source list cannot be null or empty.");
                }
                for (DataSourceDesc dsd : dsds) {
                    if (dsd == null) {
                        throw new IllegalArgumentException(
                                "DataSourceDesc in the source list cannot be null.");
                    }
                }

                synchronized (mSrcLock) {
                    mNextSourceInfos.clear();
                    for (DataSourceDesc dsd : dsds) {
                        mNextSourceInfos.add(new SourceInfo(dsd));
                    }
                }
                prepareNextDataSource();
            }
        });
    }

    /**
     * Removes all data sources pending to be played.
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object clearNextDataSources() {
        return addTask(new Task(CALL_COMPLETED_CLEAR_NEXT_DATA_SOURCES, false) {
            @Override
            void process() {
                mNextSourceInfos.clear();
            }
        });
    }

    /**
     * Gets the current data source as described by a DataSourceDesc.
     *
     * @return the current DataSourceDesc
     */
    public DataSourceDesc getCurrentDataSource() {
        synchronized (mSrcLock) {
            return mCurrentSourceInfo == null ? null : mCurrentSourceInfo.mDSD;
        }
    }

    private void handleDataSource(boolean isCurrent, @NonNull DataSourceDesc dsd, long srcId)
            throws IOException {
        checkArgument(dsd != null, "the DataSourceDesc cannot be null");

        switch (dsd.getType()) {
            case DataSourceDesc.TYPE_CALLBACK:
                handleDataSource(isCurrent,
                                 srcId,
                                 dsd.getMedia2DataSource(),
                                 dsd.getStartPosition(),
                                 dsd.getEndPosition());
                break;

            case DataSourceDesc.TYPE_FD:
                handleDataSource(isCurrent,
                                 srcId,
                                 dsd.getFileDescriptor(),
                                 dsd.getFileDescriptorOffset(),
                                 dsd.getFileDescriptorLength(),
                                 dsd.getStartPosition(),
                                 dsd.getEndPosition());
                break;

            case DataSourceDesc.TYPE_URI:
                handleDataSource(isCurrent,
                                 srcId,
                                 dsd.getUriContext(),
                                 dsd.getUri(),
                                 dsd.getUriHeaders(),
                                 dsd.getUriCookies(),
                                 dsd.getStartPosition(),
                                 dsd.getEndPosition());
                break;

            default:
                break;
        }
    }

    /**
     * To provide cookies for the subsequent HTTP requests, you can install your own default cookie
     * handler and use other variants of setDataSource APIs instead. Alternatively, you can use
     * this API to pass the cookies as a list of HttpCookie. If the app has not installed
     * a CookieHandler already, this API creates a CookieManager and populates its CookieStore with
     * the provided cookies. If the app has installed its own handler already, this API requires the
     * handler to be of CookieManager type such that the API can update the manager’s CookieStore.
     *
     * <p><strong>Note</strong> that the cross domain redirection is allowed by default,
     * but that can be changed with key/value pairs through the headers parameter with
     * "android-allow-cross-domain-redirect" as the key and "0" or "1" as the value to
     * disallow or allow cross domain redirection.
     *
     * @throws IllegalArgumentException if cookies are provided and the installed handler is not
     *                                  a CookieManager
     * @throws IllegalStateException    if it is called in an invalid state
     * @throws NullPointerException     if context or uri is null
     * @throws IOException              if uri has a file scheme and an I/O error occurs
     */
    private void handleDataSource(
            boolean isCurrent, long srcId,
            @NonNull Context context, @NonNull Uri uri,
            @Nullable Map<String, String> headers, @Nullable List<HttpCookie> cookies,
            long startPos, long endPos)
            throws IOException {
        // The context and URI usually belong to the calling user. Get a resolver for that user.
        final ContentResolver resolver = context.getContentResolver();
        final String scheme = uri.getScheme();
        if (ContentResolver.SCHEME_FILE.equals(scheme)) {
            handleDataSource(isCurrent, srcId, uri.getPath(), null, null, startPos, endPos);
            return;
        }

        final int ringToneType = RingtoneManager.getDefaultType(uri);
        try {
            AssetFileDescriptor afd;
            // Try requested Uri locally first
            if (ContentResolver.SCHEME_CONTENT.equals(scheme) && ringToneType != -1) {
                afd = RingtoneManager.openDefaultRingtoneUri(context, uri);
                if (attemptDataSource(isCurrent, srcId, afd, startPos, endPos)) {
                    return;
                }
                final Uri actualUri = RingtoneManager.getActualDefaultRingtoneUri(
                        context, ringToneType);
                afd = resolver.openAssetFileDescriptor(actualUri, "r");
            } else {
                afd = resolver.openAssetFileDescriptor(uri, "r");
            }
            if (attemptDataSource(isCurrent, srcId, afd, startPos, endPos)) {
                return;
            }
        } catch (NullPointerException | SecurityException | IOException ex) {
            Log.w(TAG, "Couldn't open " + uri + ": " + ex);
            // Fallback to media server
        }
        handleDataSource(isCurrent, srcId, uri.toString(), headers, cookies, startPos, endPos);
    }

    private boolean attemptDataSource(boolean isCurrent, long srcId, AssetFileDescriptor afd,
            long startPos, long endPos) throws IOException {
        try {
            if (afd.getDeclaredLength() < 0) {
                handleDataSource(isCurrent,
                        srcId,
                        afd.getFileDescriptor(),
                        0,
                        DataSourceDesc.LONG_MAX,
                        startPos,
                        endPos);
            } else {
                handleDataSource(isCurrent,
                        srcId,
                        afd.getFileDescriptor(),
                        afd.getStartOffset(),
                        afd.getDeclaredLength(),
                        startPos,
                        endPos);
            }
            return true;
        } catch (NullPointerException | SecurityException | IOException ex) {
            Log.w(TAG, "Couldn't open srcId:" + srcId + ": " + ex);
            return false;
        } finally {
            if (afd != null) {
                afd.close();
            }
        }
    }

    private void handleDataSource(
            boolean isCurrent, long srcId,
            String path, Map<String, String> headers, List<HttpCookie> cookies,
            long startPos, long endPos)
            throws IOException {
        String[] keys = null;
        String[] values = null;

        if (headers != null) {
            keys = new String[headers.size()];
            values = new String[headers.size()];

            int i = 0;
            for (Map.Entry<String, String> entry: headers.entrySet()) {
                keys[i] = entry.getKey();
                values[i] = entry.getValue();
                ++i;
            }
        }
        handleDataSource(isCurrent, srcId, path, keys, values, cookies, startPos, endPos);
    }

    private void handleDataSource(boolean isCurrent, long srcId,
            String path, String[] keys, String[] values, List<HttpCookie> cookies,
            long startPos, long endPos)
            throws IOException {
        final Uri uri = Uri.parse(path);
        final String scheme = uri.getScheme();
        if ("file".equals(scheme)) {
            path = uri.getPath();
        } else if (scheme != null) {
            // handle non-file sources
            Media2Utils.storeCookies(cookies);
            nativeHandleDataSourceUrl(
                    isCurrent,
                    srcId,
                    Media2HTTPService.createHTTPService(path),
                    path,
                    keys,
                    values,
                    startPos,
                    endPos);
            return;
        }

        final File file = new File(path);
        if (file.exists()) {
            FileInputStream is = new FileInputStream(file);
            FileDescriptor fd = is.getFD();
            handleDataSource(isCurrent, srcId, fd, 0, DataSourceDesc.LONG_MAX, startPos, endPos);
            is.close();
        } else {
            throw new IOException("handleDataSource failed.");
        }
    }

    private native void nativeHandleDataSourceUrl(
            boolean isCurrent, long srcId,
            Media2HTTPService httpService, String path, String[] keys, String[] values,
            long startPos, long endPos)
            throws IOException;

    /**
     * Sets the data source (FileDescriptor) to use. The FileDescriptor must be
     * seekable (N.B. a LocalSocket is not seekable). It is the caller's responsibility
     * to close the file descriptor. It is safe to do so as soon as this call returns.
     *
     * @throws IllegalStateException if it is called in an invalid state
     * @throws IllegalArgumentException if fd is not a valid FileDescriptor
     * @throws IOException if fd can not be read
     */
    private void handleDataSource(
            boolean isCurrent, long srcId,
            FileDescriptor fd, long offset, long length,
            long startPos, long endPos) throws IOException {
        nativeHandleDataSourceFD(isCurrent, srcId, fd, offset, length, startPos, endPos);
    }

    private native void nativeHandleDataSourceFD(boolean isCurrent, long srcId,
            FileDescriptor fd, long offset, long length,
            long startPos, long endPos) throws IOException;

    /**
     * @throws IllegalStateException if it is called in an invalid state
     * @throws IllegalArgumentException if dataSource is not a valid Media2DataSource
     */
    private void handleDataSource(boolean isCurrent, long srcId, Media2DataSource dataSource,
            long startPos, long endPos) {
        nativeHandleDataSourceCallback(isCurrent, srcId, dataSource, startPos, endPos);
    }

    private native void nativeHandleDataSourceCallback(
            boolean isCurrent, long srcId, Media2DataSource dataSource,
            long startPos, long endPos);

    // return true if there is a next data source, false otherwise.
    // This function should be always called on |mHandlerThread|.
    private boolean prepareNextDataSource() {
        HandlerThread handlerThread = mHandlerThread;
        if (handlerThread != null && Looper.myLooper() != handlerThread.getLooper()) {
            Log.e(TAG, "prepareNextDataSource: called on wrong looper");
        }

        boolean hasNextDSD;
        int state = getState();
        synchronized (mSrcLock) {
            hasNextDSD = !mNextSourceInfos.isEmpty();
            if (state == PLAYER_STATE_ERROR || state == PLAYER_STATE_IDLE) {
                // Current source has not been prepared yet.
                return hasNextDSD;
            }

            SourceInfo nextSource = mNextSourceInfos.peek();
            if (!hasNextDSD || nextSource.mStateAsNextSource != NEXT_SOURCE_STATE_INIT) {
                // There is no next source or it's in preparing or prepared state.
                return hasNextDSD;
            }

            try {
                nextSource.mStateAsNextSource = NEXT_SOURCE_STATE_PREPARING;
                handleDataSource(false /* isCurrent */, nextSource.mDSD, nextSource.mId);
            } catch (Exception e) {
                Message msg = mTaskHandler.obtainMessage(
                        MEDIA_ERROR, MEDIA_ERROR_IO, MEDIA_ERROR_UNKNOWN, null);
                mTaskHandler.handleMessage(msg, nextSource.mId);

                mNextSourceInfos.poll();
                return prepareNextDataSource();
            }
        }
        return hasNextDSD;
    }

    // This function should be always called on |mHandlerThread|.
    private void playNextDataSource() {
        HandlerThread handlerThread = mHandlerThread;
        if (handlerThread != null && Looper.myLooper() != handlerThread.getLooper()) {
            Log.e(TAG, "playNextDataSource: called on wrong looper");
        }

        boolean hasNextDSD = false;
        synchronized (mSrcLock) {
            if (!mNextSourceInfos.isEmpty()) {
                hasNextDSD = true;
                SourceInfo nextSourceInfo = mNextSourceInfos.peek();
                if (nextSourceInfo.mStateAsNextSource == NEXT_SOURCE_STATE_PREPARED) {
                    // Switch to next source only when it has been prepared.
                    mCurrentSourceInfo = mNextSourceInfos.poll();

                    long srcId = mCurrentSourceInfo.mId;
                    try {
                        nativePlayNextDataSource(srcId);
                    } catch (Exception e) {
                        Message msg2 = mTaskHandler.obtainMessage(
                                MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, MEDIA_ERROR_UNSUPPORTED, null);
                        mTaskHandler.handleMessage(msg2, srcId);
                        // Keep |mNextSourcePlayPending|
                        hasNextDSD = prepareNextDataSource();
                    }
                    if (hasNextDSD) {
                        stayAwake(true);

                        // Now a new current src is playing.
                        // Wait for MEDIA_INFO_DATA_SOURCE_START to prepare next source.
                    }
                } else if (nextSourceInfo.mStateAsNextSource == NEXT_SOURCE_STATE_INIT) {
                    hasNextDSD = prepareNextDataSource();
                }
            }
        }

        if (!hasNextDSD) {
            sendEvent(new EventNotifier() {
                @Override
                public void notify(EventCallback callback) {
                    callback.onInfo(
                            MediaPlayer2.this, null, MEDIA_INFO_DATA_SOURCE_LIST_END, 0);
                }
            });
        }
    }

    private native void nativePlayNextDataSource(long srcId);

    /**
     * Configures the player to loop on the current data source.
     * @param loop true if the current data source is meant to loop.
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object loopCurrent(boolean loop) {
        return addTask(new Task(CALL_COMPLETED_LOOP_CURRENT, false) {
            @Override
            void process() {
                setLooping(loop);
            }
        });
    }

    private native void setLooping(boolean looping);

    /**
     * Sets the volume of the audio of the media to play, expressed as a linear multiplier
     * on the audio samples.
     * Note that this volume is specific to the player, and is separate from stream volume
     * used across the platform.<br>
     * A value of 0.0f indicates muting, a value of 1.0f is the nominal unattenuated and unamplified
     * gain. See {@link #getMaxPlayerVolume()} for the volume range supported by this player.
     * @param volume a value between 0.0f and {@link #getMaxPlayerVolume()}.
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object setPlayerVolume(float volume) {
        return addTask(new Task(CALL_COMPLETED_SET_PLAYER_VOLUME, false) {
            @Override
            void process() {
                mVolume = volume;
                native_setVolume(volume);
            }
        });
    }

    private native void native_setVolume(float volume);

    /**
     * Returns the current volume of this player.
     * Note that it does not take into account the associated stream volume.
     * @return the player volume.
     */
    public float getPlayerVolume() {
        return mVolume;
    }

    /**
     * @return the maximum volume that can be used in {@link #setPlayerVolume(float)}.
     */
    public float getMaxPlayerVolume() {
        return 1.0f;
    }

    /**
     * Insert a task in the command queue to help the client to identify whether a batch
     * of commands has been finished. When this command is processed, a notification
     * {@link EventCallback#onCommandLabelReached onCommandLabelReached} will be fired with the
     * given {@code label}.
     *
     * @see EventCallback#onCommandLabelReached
     *
     * @param label An application specific Object used to help to identify the completeness
     * of a batch of commands.
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object notifyWhenCommandLabelReached(@NonNull Object label) {
        return addTask(new Task(CALL_COMPLETED_NOTIFY_WHEN_COMMAND_LABEL_REACHED, false) {
            @Override
            void process() {
                sendEvent(new EventNotifier() {
                    @Override
                    public void notify(EventCallback callback) {
                        callback.onCommandLabelReached(
                                MediaPlayer2.this, label);
                    }
                });
            }
        });
    }

    /**
     * Sets the {@link SurfaceHolder} to use for displaying the video
     * portion of the media.
     *
     * Either a surface holder or surface must be set if a display or video sink
     * is needed. Not calling this method or {@link #setSurface(Surface)}
     * when playing back a video will result in only the audio track being played.
     * A null surface holder or surface will result in only the audio track being
     * played.
     *
     * @param sh the SurfaceHolder to use for video display
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    public Object setDisplay(SurfaceHolder sh) {
        return addTask(new Task(CALL_COMPLETED_SET_DISPLAY, false) {
            @Override
            void process() {
                mSurfaceHolder = sh;
                Surface surface;
                if (sh != null) {
                    surface = sh.getSurface();
                } else {
                    surface = null;
                }
                native_setVideoSurface(surface);
                updateSurfaceScreenOn();
            }
        });
    }

    /**
     * Sets the {@link Surface} to be used as the sink for the video portion of
     * the media.  Setting a
     * Surface will un-set any Surface or SurfaceHolder that was previously set.
     * A null surface will result in only the audio track being played.
     *
     * If the Surface sends frames to a {@link SurfaceTexture}, the timestamps
     * returned from {@link SurfaceTexture#getTimestamp()} will have an
     * unspecified zero point.  These timestamps cannot be directly compared
     * between different media sources, different instances of the same media
     * source, or multiple runs of the same program.  The timestamp is normally
     * monotonically increasing and is unaffected by time-of-day adjustments,
     * but it is reset when the position is set.
     *
     * @param surface The {@link Surface} to be used for the video portion of
     * the media.
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object setSurface(Surface surface) {
        return addTask(new Task(CALL_COMPLETED_SET_SURFACE, false) {
            @Override
            void process() {
                if (mScreenOnWhilePlaying && surface != null) {
                    Log.w(TAG, "setScreenOnWhilePlaying(true) is ineffective for Surface");
                }
                mSurfaceHolder = null;
                native_setVideoSurface(surface);
                updateSurfaceScreenOn();
            }
        });
    }

    private native void native_setVideoSurface(Surface surface);

    /**
     * Set the low-level power management behavior for this MediaPlayer2. This
     * can be used when the MediaPlayer2 is not playing through a SurfaceHolder
     * set with {@link #setDisplay(SurfaceHolder)} and thus can use the
     * high-level {@link #setScreenOnWhilePlaying(boolean)} feature.
     *
     * <p>This function has the MediaPlayer2 access the low-level power manager
     * service to control the device's power usage while playing is occurring.
     * The parameter is a combination of {@link android.os.PowerManager} wake flags.
     * Use of this method requires {@link android.Manifest.permission#WAKE_LOCK}
     * permission.
     * By default, no attempt is made to keep the device awake during playback.
     *
     * @param context the Context to use
     * @param mode    the power/wake mode to set
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     * @see android.os.PowerManager
     */
    // This is an asynchronous call.
    public Object setWakeMode(Context context, int mode) {
        return addTask(new Task(CALL_COMPLETED_SET_WAKE_MODE, false) {
            @Override
            void process() {
                boolean washeld = false;

                if (mWakeLock != null) {
                    if (mWakeLock.isHeld()) {
                        washeld = true;
                        mWakeLock.release();
                    }
                    mWakeLock = null;
                }

                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                ActivityManager am =
                        (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                List<RunningAppProcessInfo> runningAppsProcInfo = am.getRunningAppProcesses();
                int pid = android.os.Process.myPid();
                String name = "pid " + String.valueOf(pid);
                if (runningAppsProcInfo != null) {
                    for (RunningAppProcessInfo procInfo : runningAppsProcInfo) {
                        if (procInfo.pid == pid) {
                            name = procInfo.processName;
                            break;
                        }
                    }
                }
                mWakeLock = pm.newWakeLock(mode | PowerManager.ON_AFTER_RELEASE, name);
                mWakeLock.setReferenceCounted(false);
                if (washeld) {
                    mWakeLock.acquire();
                }
            }
        });
    }

    /**
     * Control whether we should use the attached SurfaceHolder to keep the
     * screen on while video playback is occurring.  This is the preferred
     * method over {@link #setWakeMode} where possible, since it doesn't
     * require that the application have permission for low-level wake lock
     * access.
     *
     * @param screenOn Supply true to keep the screen on, false to allow it to turn off.
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object setScreenOnWhilePlaying(boolean screenOn) {
        return addTask(new Task(CALL_COMPLETED_SET_SCREEN_ON_WHILE_PLAYING, false) {
            @Override
            void process() {
                if (mScreenOnWhilePlaying != screenOn) {
                    if (screenOn && mSurfaceHolder == null) {
                        Log.w(TAG, "setScreenOnWhilePlaying(true) is ineffective"
                                + " without a SurfaceHolder");
                    }
                    mScreenOnWhilePlaying = screenOn;
                    updateSurfaceScreenOn();
                }
            }
        });
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
     * Cancels a pending command.
     *
     * @param token the command to be canceled. This is the returned Object when command is issued.
     * @return {@code false} if the task could not be cancelled; {@code true} otherwise.
     */
    // This is a synchronous call.
    public boolean cancelCommand(Object token) {
        synchronized (mTaskLock) {
            return mPendingTasks.remove(token);
        }
    }

    /**
     * Discards all pending commands.
     */
    // This is a synchronous call.
    public void clearPendingCommands() {
        synchronized (mTaskLock) {
            mPendingTasks.clear();
        }
    }

    //--------------------------------------------------------------------------
    // Explicit Routing
    //--------------------
    private AudioDeviceInfo mPreferredDevice = null;

    /**
     * Specifies an audio device (via an {@link AudioDeviceInfo} object) to route
     * the output from this MediaPlayer2.
     * @param deviceInfo The {@link AudioDeviceInfo} specifying the audio sink or source.
     *  If deviceInfo is null, default routing is restored.
     * @return true if succesful, false if the specified {@link AudioDeviceInfo} is non-null and
     * does not correspond to a valid audio device.
     */
    // This is a synchronous call.
    @Override
    public boolean setPreferredDevice(AudioDeviceInfo deviceInfo) {
        boolean status = native_setPreferredDevice(deviceInfo);
        if (status) {
            synchronized (this) {
                mPreferredDevice = deviceInfo;
            }
        }
        return status;
    }

    private native boolean native_setPreferredDevice(AudioDeviceInfo device);

    /**
     * Returns the selected output specified by {@link #setPreferredDevice}. Note that this
     * is not guaranteed to correspond to the actual device being used for playback.
     */
    @Override
    public AudioDeviceInfo getPreferredDevice() {
        synchronized (this) {
            return mPreferredDevice;
        }
    }

    /**
     * Returns an {@link AudioDeviceInfo} identifying the current routing of this MediaPlayer2
     * Note: The query is only valid if the MediaPlayer2 is currently playing.
     * If the player is not playing, the returned device can be null or correspond to previously
     * selected device when the player was last active.
     */
    @Override
    public native AudioDeviceInfo getRoutedDevice();

    /**
     * Adds an {@link AudioRouting.OnRoutingChangedListener} to receive notifications of routing
     * changes on this MediaPlayer2.
     * @param listener The {@link AudioRouting.OnRoutingChangedListener} interface to receive
     * notifications of rerouting events.
     * @param handler  Specifies the {@link Handler} object for the thread on which to execute
     * the callback. If <code>null</code>, the handler on the main looper will be used.
     */
    // This is a synchronous call.
    @Override
    public void addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener,
            Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("addOnRoutingChangedListener: listener is NULL");
        }
        RoutingDelegate routingDelegate = new RoutingDelegate(this, listener, handler);
        native_addDeviceCallback(routingDelegate);
    }

    private native void native_addDeviceCallback(RoutingDelegate rd);

    /**
     * Removes an {@link AudioRouting.OnRoutingChangedListener} which has been previously added
     * to receive rerouting notifications.
     * @param listener The previously added {@link AudioRouting.OnRoutingChangedListener} interface
     * to remove.
     */
    // This is a synchronous call.
    @Override
    public void removeOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("removeOnRoutingChangedListener: listener is NULL");
        }
        native_removeDeviceCallback(listener);
    }

    private native void native_removeDeviceCallback(
            AudioRouting.OnRoutingChangedListener listener);

    /**
     * Returns the size of the video.
     *
     * @return the size of the video. The width and height of size could be 0 if there is no video,
     * no display surface was set, or the size has not been determined yet.
     * The {@code EventCallback} can be registered via
     * {@link #setEventCallback(Executor, EventCallback)} to provide a
     * notification {@code EventCallback.onVideoSizeChanged} when the size
     * is available.
     */
    public VideoSize getVideoSize() {
        return mVideoSize;
    }

    /**
     * Return Metrics data about the current player.
     *
     * @return a {@link PersistableBundle} containing the set of attributes and values
     * available for the media being handled by this instance of MediaPlayer2
     * The attributes are descibed in {@link MetricsConstants}.
     *
     * Additional vendor-specific fields may also be present in the return value.
     */
    public PersistableBundle getMetrics() {
        PersistableBundle bundle = native_getMetrics();
        return bundle;
    }

    private native PersistableBundle native_getMetrics();


    /**
     * Gets the current buffering management params used by the source component.
     * Calling it only after {@code setDataSource} has been called.
     * Each type of data source might have different set of default params.
     *
     * @return the current buffering management params used by the source component.
     * @throws IllegalStateException if the internal player engine has not been
     * initialized, or {@code setDataSource} has not been called.
     */
    // TODO: make it public when ready
    @NonNull
    native BufferingParams getBufferingParams();

    /**
     * Sets buffering management params.
     * The object sets its internal BufferingParams to the input, except that the input is
     * invalid or not supported.
     * Call it only after {@code setDataSource} has been called.
     * The input is a hint to MediaPlayer2.
     *
     * @param params the buffering management params.
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // TODO: make it public when ready
    // This is an asynchronous call.
    Object setBufferingParams(@NonNull BufferingParams params) {
        return addTask(new Task(CALL_COMPLETED_SET_BUFFERING_PARAMS, false) {
            @Override
            void process() {
                checkArgument(params != null, "the BufferingParams cannot be null");
                native_setBufferingParams(params);
            }
        });
    }

    private native void native_setBufferingParams(@NonNull BufferingParams params);


    /**
     * Sets playback rate using {@link PlaybackParams}. The object sets its internal
     * PlaybackParams to the input. This allows the object to resume at previous speed
     * when play() is called. Speed of zero is not allowed. Calling it does not change
     * the object state.
     *
     * @param params the playback params.
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object setPlaybackParams(@NonNull PlaybackParams params) {
        return addTask(new Task(CALL_COMPLETED_SET_PLAYBACK_PARAMS, false) {
            @Override
            void process() {
                checkArgument(params != null, "the PlaybackParams cannot be null");
                native_setPlaybackParams(params);
            }
        });
    }

    private native void native_setPlaybackParams(@NonNull PlaybackParams params);

    /**
     * Gets the playback params, containing the current playback rate.
     *
     * @return the playback params.
     * @throws IllegalStateException if the internal player engine has not been initialized.
     */
    @NonNull
    public native PlaybackParams getPlaybackParams();

    /**
     * Sets A/V sync mode.
     *
     * @param params the A/V sync params to apply
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object setSyncParams(@NonNull SyncParams params) {
        return addTask(new Task(CALL_COMPLETED_SET_SYNC_PARAMS, false) {
            @Override
            void process() {
                checkArgument(params != null, "the SyncParams cannot be null");
                native_setSyncParams(params);
            }
        });
    }

    private native void native_setSyncParams(@NonNull SyncParams params);

    /**
     * Gets the A/V sync mode.
     *
     * @return the A/V sync params
     * @throws IllegalStateException if the internal player engine has not been initialized.
     */
    @NonNull
    public native SyncParams getSyncParams();

    /**
     * Moves the media to specified time position.
     * Same as {@link #seekTo(long, int)} with {@code mode = SEEK_PREVIOUS_SYNC}.
     *
     * @param msec the offset in milliseconds from the start to seek to
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object seekTo(long msec) {
        return seekTo(msec, SEEK_PREVIOUS_SYNC /* mode */);
    }

    /**
     * Seek modes used in method seekTo(long, int) to move media position
     * to a specified location.
     *
     * Do not change these mode values without updating their counterparts
     * in include/media/IMediaSource.h!
     */
    /**
     * This mode is used with {@link #seekTo(long, int)} to move media position to
     * a sync (or key) frame associated with a data source that is located
     * right before or at the given time.
     *
     * @see #seekTo(long, int)
     */
    public static final int SEEK_PREVIOUS_SYNC    = 0x00;
    /**
     * This mode is used with {@link #seekTo(long, int)} to move media position to
     * a sync (or key) frame associated with a data source that is located
     * right after or at the given time.
     *
     * @see #seekTo(long, int)
     */
    public static final int SEEK_NEXT_SYNC        = 0x01;
    /**
     * This mode is used with {@link #seekTo(long, int)} to move media position to
     * a sync (or key) frame associated with a data source that is located
     * closest to (in time) or at the given time.
     *
     * @see #seekTo(long, int)
     */
    public static final int SEEK_CLOSEST_SYNC     = 0x02;
    /**
     * This mode is used with {@link #seekTo(long, int)} to move media position to
     * a frame (not necessarily a key frame) associated with a data source that
     * is located closest to or at the given time.
     *
     * @see #seekTo(long, int)
     */
    public static final int SEEK_CLOSEST          = 0x03;

    /** @hide */
    @IntDef(flag = false, prefix = "SEEK", value = {
            SEEK_PREVIOUS_SYNC,
            SEEK_NEXT_SYNC,
            SEEK_CLOSEST_SYNC,
            SEEK_CLOSEST,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SeekMode {}

    /**
     * Moves the media to specified time position by considering the given mode.
     * <p>
     * When seekTo is finished, the user will be notified via
     * {@link EventCallback#onCallCompleted} with {@link #CALL_COMPLETED_SEEK_TO}.
     * There is at most one active seekTo processed at any time. If there is a to-be-completed
     * seekTo, new seekTo requests will be queued in such a way that only the last request
     * is kept. When current seekTo is completed, the queued request will be processed if
     * that request is different from just-finished seekTo operation, i.e., the requested
     * position or mode is different.
     *
     * @param msec the offset in milliseconds from the start to seek to.
     * When seeking to the given time position, there is no guarantee that the data source
     * has a frame located at the position. When this happens, a frame nearby will be rendered.
     * If msec is negative, time position zero will be used.
     * If msec is larger than duration, duration will be used.
     * @param mode the mode indicating where exactly to seek to.
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object seekTo(long msec, @SeekMode int mode) {
        return addTask(new Task(CALL_COMPLETED_SEEK_TO, true) {
            @Override
            void process() {
                if (mode < SEEK_PREVIOUS_SYNC || mode > SEEK_CLOSEST) {
                    final String msg = "Illegal seek mode: " + mode;
                    throw new IllegalArgumentException(msg);
                }
                // TODO: pass long to native, instead of truncating here.
                long posMs = msec;
                if (posMs > Integer.MAX_VALUE) {
                    Log.w(TAG, "seekTo offset " + posMs + " is too large, cap to "
                            + Integer.MAX_VALUE);
                    posMs = Integer.MAX_VALUE;
                } else if (posMs < Integer.MIN_VALUE) {
                    Log.w(TAG, "seekTo offset " + posMs + " is too small, cap to "
                            + Integer.MIN_VALUE);
                    posMs = Integer.MIN_VALUE;
                }

                synchronized (mTaskLock) {
                    if (mIsPreviousCommandSeekTo
                            && mPreviousSeekPos == posMs
                            && mPreviousSeekMode == mode) {
                        throw new CommandSkippedException(
                                "same as previous seekTo");
                    }
                }

                native_seekTo(posMs, mode);

                synchronized (mTaskLock) {
                    mIsPreviousCommandSeekTo = true;
                    mPreviousSeekPos = posMs;
                    mPreviousSeekMode = mode;
                }
            }
        });
    }

    private native void native_seekTo(long msec, int mode);

    /**
     * Get current playback position as a {@link MediaTimestamp}.
     * <p>
     * The MediaTimestamp represents how the media time correlates to the system time in
     * a linear fashion using an anchor and a clock rate. During regular playback, the media
     * time moves fairly constantly (though the anchor frame may be rebased to a current
     * system time, the linear correlation stays steady). Therefore, this method does not
     * need to be called often.
     * <p>
     * To help users get current playback position, this method always anchors the timestamp
     * to the current {@link System#nanoTime system time}, so
     * {@link MediaTimestamp#getAnchorMediaTimeUs} can be used as current playback position.
     *
     * @return a MediaTimestamp object if a timestamp is available, or {@code null} if no timestamp
     *         is available, e.g. because the media player has not been initialized.
     *
     * @see MediaTimestamp
     */
    @Nullable
    public MediaTimestamp getTimestamp() {
        try {
            // TODO: get the timestamp from native side
            return new MediaTimestamp(
                    getCurrentPosition() * 1000L,
                    System.nanoTime(),
                    getState() == PLAYER_STATE_PLAYING ? getPlaybackParams().getSpeed() : 0.f);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /**
     * Checks whether the MediaPlayer2 is looping or non-looping.
     *
     * @return true if the MediaPlayer2 is currently looping, false otherwise
     */
    // This is a synchronous call.
    public native boolean isLooping();

    /**
     * Sets the audio session ID.
     *
     * @param sessionId the audio session ID.
     * The audio session ID is a system wide unique identifier for the audio stream played by
     * this MediaPlayer2 instance.
     * The primary use of the audio session ID  is to associate audio effects to a particular
     * instance of MediaPlayer2: if an audio session ID is provided when creating an audio effect,
     * this effect will be applied only to the audio content of media players within the same
     * audio session and not to the output mix.
     * When created, a MediaPlayer2 instance automatically generates its own audio session ID.
     * However, it is possible to force this player to be part of an already existing audio session
     * by calling this method.
     * This method must be called when player is in {@link #PLAYER_STATE_IDLE} or
     * {@link #PLAYER_STATE_PREPARED} state in order to have sessionId take effect.
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object setAudioSessionId(int sessionId) {
        keepAudioSessionIdAlive(sessionId);
        return addTask(new Task(CALL_COMPLETED_SET_AUDIO_SESSION_ID, false) {
            @Override
            void process() {
                native_setAudioSessionId(sessionId);
            }
        });
    }

    private native void native_setAudioSessionId(int sessionId);

    /**
     * Returns the audio session ID.
     *
     * @return the audio session ID. {@see #setAudioSessionId(int)}
     * Note that the audio session ID is 0 only if a problem occured when the MediaPlayer2 was
     * contructed.
     */
    // This is a synchronous call.
    public native int getAudioSessionId();

    /**
     * Attaches an auxiliary effect to the player. A typical auxiliary effect is a reverberation
     * effect which can be applied on any sound source that directs a certain amount of its
     * energy to this effect. This amount is defined by setAuxEffectSendLevel().
     * See {@link #setAuxEffectSendLevel(float)}.
     * <p>After creating an auxiliary effect (e.g.
     * {@link android.media.audiofx.EnvironmentalReverb}), retrieve its ID with
     * {@link android.media.audiofx.AudioEffect#getId()} and use it when calling this method
     * to attach the player to the effect.
     * <p>To detach the effect from the player, call this method with a null effect id.
     * <p>This method must be called after one of the overloaded <code> setDataSource </code>
     * methods.
     * @param effectId system wide unique id of the effect to attach
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object attachAuxEffect(int effectId) {
        return addTask(new Task(CALL_COMPLETED_ATTACH_AUX_EFFECT, false) {
            @Override
            void process() {
                native_attachAuxEffect(effectId);
            }
        });
    }

    private native void native_attachAuxEffect(int effectId);

    /**
     * Sets the send level of the player to the attached auxiliary effect.
     * See {@link #attachAuxEffect(int)}. The level value range is 0 to 1.0.
     * <p>By default the send level is 0, so even if an effect is attached to the player
     * this method must be called for the effect to be applied.
     * <p>Note that the passed level value is a raw scalar. UI controls should be scaled
     * logarithmically: the gain applied by audio framework ranges from -72dB to 0dB,
     * so an appropriate conversion from linear UI input x to level is:
     * x == 0 -> level = 0
     * 0 < x <= R -> level = 10^(72*(x-R)/20/R)
     * @param level send level scalar
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object setAuxEffectSendLevel(float level) {
        return addTask(new Task(CALL_COMPLETED_SET_AUX_EFFECT_SEND_LEVEL, false) {
            @Override
            void process() {
                native_setAuxEffectSendLevel(level);
            }
        });
    }

    private native void native_setAuxEffectSendLevel(float level);

    private static native void native_stream_event_onTearDown(
            long nativeCallbackPtr, long userDataPtr);
    private static native void native_stream_event_onStreamPresentationEnd(
            long nativeCallbackPtr, long userDataPtr);
    private static native void native_stream_event_onStreamDataRequest(
            long jAudioTrackPtr, long nativeCallbackPtr, long userDataPtr);

    /* Do not change these values (starting with INVOKE_ID) without updating
     * their counterparts in include/media/mediaplayer2.h!
     */
    private static final int INVOKE_ID_GET_TRACK_INFO = 1;
    private static final int INVOKE_ID_ADD_EXTERNAL_SOURCE = 2;
    private static final int INVOKE_ID_ADD_EXTERNAL_SOURCE_FD = 3;
    private static final int INVOKE_ID_SELECT_TRACK = 4;
    private static final int INVOKE_ID_DESELECT_TRACK = 5;
    private static final int INVOKE_ID_GET_SELECTED_TRACK = 7;

    /**
     * Invoke a generic method on the native player using opaque protocol
     * buffer message for the request and reply. Both payloads' format is a
     * convention between the java caller and the native player.
     *
     * @param msg PlayerMessage for the extension.
     *
     * @return PlayerMessage with the data returned by the
     * native player.
     */
    private PlayerMessage invoke(PlayerMessage msg) {
        byte[] ret = native_invoke(msg.toByteArray());
        if (ret == null) {
            return null;
        }
        try {
            return PlayerMessage.parseFrom(ret);
        } catch (InvalidProtocolBufferException e) {
            return null;
        }
    }

    private native byte[] native_invoke(byte[] request);

    /**
     * Class for MediaPlayer2 to return each audio/video/subtitle track's metadata.
     *
     * @see MediaPlayer2#getTrackInfo
     */
    public static class TrackInfo {
        /**
         * Gets the track type.
         * @return TrackType which indicates if the track is video, audio, timed text.
         */
        @UnsupportedAppUsage
        public int getTrackType() {
            return mTrackType;
        }

        /**
         * Gets the language code of the track.
         * @return a language code in either way of ISO-639-1 or ISO-639-2.
         * When the language is unknown or could not be determined,
         * ISO-639-2 language code, "und", is returned.
         */
        @UnsupportedAppUsage
        public String getLanguage() {
            String language = mFormat.getString(MediaFormat.KEY_LANGUAGE);
            return language == null ? "und" : language;
        }

        /**
         * Gets the {@link MediaFormat} of the track.  If the format is
         * unknown or could not be determined, null is returned.
         */
        public MediaFormat getFormat() {
            if (mTrackType == MEDIA_TRACK_TYPE_TIMEDTEXT
                    || mTrackType == MEDIA_TRACK_TYPE_SUBTITLE) {
                return mFormat;
            }
            return null;
        }

        public static final int MEDIA_TRACK_TYPE_UNKNOWN = 0;
        public static final int MEDIA_TRACK_TYPE_VIDEO = 1;
        public static final int MEDIA_TRACK_TYPE_AUDIO = 2;

        /** @hide */
        public static final int MEDIA_TRACK_TYPE_TIMEDTEXT = 3;

        public static final int MEDIA_TRACK_TYPE_SUBTITLE = 4;
        public static final int MEDIA_TRACK_TYPE_METADATA = 5;

        final int mTrackType;
        final MediaFormat mFormat;

        TrackInfo(Iterator<Value> in) {
            mTrackType = in.next().getInt32Value();
            // TODO: build the full MediaFormat; currently we are using createSubtitleFormat
            // even for audio/video tracks, meaning we only set the mime and language.
            String mime = in.next().getStringValue();
            String language = in.next().getStringValue();
            mFormat = MediaFormat.createSubtitleFormat(mime, language);

            if (mTrackType == MEDIA_TRACK_TYPE_SUBTITLE) {
                mFormat.setInteger(MediaFormat.KEY_IS_AUTOSELECT, in.next().getInt32Value());
                mFormat.setInteger(MediaFormat.KEY_IS_DEFAULT, in.next().getInt32Value());
                mFormat.setInteger(MediaFormat.KEY_IS_FORCED_SUBTITLE, in.next().getInt32Value());
            }
        }

        /** @hide */
        TrackInfo(int type, MediaFormat format) {
            mTrackType = type;
            mFormat = format;
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder(128);
            out.append(getClass().getName());
            out.append('{');
            switch (mTrackType) {
                case MEDIA_TRACK_TYPE_VIDEO:
                    out.append("VIDEO");
                    break;
                case MEDIA_TRACK_TYPE_AUDIO:
                    out.append("AUDIO");
                    break;
                case MEDIA_TRACK_TYPE_TIMEDTEXT:
                    out.append("TIMEDTEXT");
                    break;
                case MEDIA_TRACK_TYPE_SUBTITLE:
                    out.append("SUBTITLE");
                    break;
                default:
                    out.append("UNKNOWN");
                    break;
            }
            out.append(", " + mFormat.toString());
            out.append("}");
            return out.toString();
        }
    };

    /**
     * Returns a List of track information.
     *
     * @return List of track info. The total number of tracks is the array length.
     * Must be called again if an external timed text source has been added after
     * addTimedTextSource method is called.
     * @throws IllegalStateException if it is called in an invalid state.
     */
    public List<TrackInfo> getTrackInfo() {
        TrackInfo[] trackInfo = getInbandTrackInfo();
        return Arrays.asList(trackInfo);
    }

    private TrackInfo[] getInbandTrackInfo() throws IllegalStateException {
        PlayerMessage request = PlayerMessage.newBuilder()
                .addValues(Value.newBuilder().setInt32Value(INVOKE_ID_GET_TRACK_INFO))
                .build();
        PlayerMessage response = invoke(request);
        if (response == null) {
            return null;
        }
        Iterator<Value> in = response.getValuesList().iterator();
        int size = in.next().getInt32Value();
        if (size == 0) {
            return null;
        }
        TrackInfo[] trackInfo = new TrackInfo[size];
        for (int i = 0; i < size; ++i) {
            trackInfo[i] = new TrackInfo(in);
        }
        return trackInfo;
    }

    /**
     * Returns the index of the audio, video, or subtitle track currently selected for playback,
     * The return value is an index into the array returned by {@link #getTrackInfo()}, and can
     * be used in calls to {@link #selectTrack(int)} or {@link #deselectTrack(int)}.
     *
     * @param trackType should be one of {@link TrackInfo#MEDIA_TRACK_TYPE_VIDEO},
     * {@link TrackInfo#MEDIA_TRACK_TYPE_AUDIO}, or
     * {@link TrackInfo#MEDIA_TRACK_TYPE_SUBTITLE}
     * @return index of the audio, video, or subtitle track currently selected for playback;
     * a negative integer is returned when there is no selected track for {@code trackType} or
     * when {@code trackType} is not one of audio, video, or subtitle.
     * @throws IllegalStateException if called after {@link #close()}
     *
     * @see #getTrackInfo()
     * @see #selectTrack(int)
     * @see #deselectTrack(int)
     */
    public int getSelectedTrack(int trackType) {
        PlayerMessage request = PlayerMessage.newBuilder()
                .addValues(Value.newBuilder().setInt32Value(INVOKE_ID_GET_SELECTED_TRACK))
                .addValues(Value.newBuilder().setInt32Value(trackType))
                .build();
        PlayerMessage response = invoke(request);
        if (response == null) {
            return -1;
        }
        return response.getValues(0).getInt32Value();
    }

    /**
     * Selects a track.
     * <p>
     * If a MediaPlayer2 is in invalid state, it throws an IllegalStateException exception.
     * If a MediaPlayer2 is in <em>Started</em> state, the selected track is presented immediately.
     * If a MediaPlayer2 is not in Started state, it just marks the track to be played.
     * </p>
     * <p>
     * In any valid state, if it is called multiple times on the same type of track (ie. Video,
     * Audio, Timed Text), the most recent one will be chosen.
     * </p>
     * <p>
     * The first audio and video tracks are selected by default if available, even though
     * this method is not called. However, no timed text track will be selected until
     * this function is called.
     * </p>
     * <p>
     * Currently, only timed text tracks or audio tracks can be selected via this method.
     * In addition, the support for selecting an audio track at runtime is pretty limited
     * in that an audio track can only be selected in the <em>Prepared</em> state.
     * </p>
     * @param index the index of the track to be selected. The valid range of the index
     * is 0..total number of track - 1. The total number of tracks as well as the type of
     * each individual track can be found by calling {@link #getTrackInfo()} method.
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     *
     * @see MediaPlayer2#getTrackInfo
     */
    // This is an asynchronous call.
    public Object selectTrack(int index) {
        return addTask(new Task(CALL_COMPLETED_SELECT_TRACK, false) {
            @Override
            void process() {
                selectOrDeselectTrack(index, true /* select */);
            }
        });
    }

    /**
     * Deselect a track.
     * <p>
     * Currently, the track must be a timed text track and no audio or video tracks can be
     * deselected. If the timed text track identified by index has not been
     * selected before, it throws an exception.
     * </p>
     * @param index the index of the track to be deselected. The valid range of the index
     * is 0..total number of tracks - 1. The total number of tracks as well as the type of
     * each individual track can be found by calling {@link #getTrackInfo()} method.
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     *
     * @see MediaPlayer2#getTrackInfo
     */
    // This is an asynchronous call.
    public Object deselectTrack(int index) {
        return addTask(new Task(CALL_COMPLETED_DESELECT_TRACK, false) {
            @Override
            void process() {
                selectOrDeselectTrack(index, false /* select */);
            }
        });
    }

    private void selectOrDeselectTrack(int index, boolean select)
            throws IllegalStateException {
        PlayerMessage request = PlayerMessage.newBuilder()
                .addValues(Value.newBuilder().setInt32Value(
                            select ? INVOKE_ID_SELECT_TRACK : INVOKE_ID_DESELECT_TRACK))
                .addValues(Value.newBuilder().setInt32Value(index))
                .build();
        invoke(request);
    }

    /* Do not change these values without updating their counterparts
     * in include/media/mediaplayer2.h!
     */
    private static final int MEDIA_NOP = 0; // interface test message
    private static final int MEDIA_PREPARED = 1;
    private static final int MEDIA_PLAYBACK_COMPLETE = 2;
    private static final int MEDIA_BUFFERING_UPDATE = 3;
    private static final int MEDIA_SEEK_COMPLETE = 4;
    private static final int MEDIA_SET_VIDEO_SIZE = 5;
    private static final int MEDIA_STARTED = 6;
    private static final int MEDIA_PAUSED = 7;
    private static final int MEDIA_STOPPED = 8;
    private static final int MEDIA_SKIPPED = 9;
    private static final int MEDIA_NOTIFY_TIME = 98;
    private static final int MEDIA_TIMED_TEXT = 99;
    private static final int MEDIA_ERROR = 100;
    private static final int MEDIA_INFO = 200;
    private static final int MEDIA_SUBTITLE_DATA = 201;
    private static final int MEDIA_META_DATA = 202;
    private static final int MEDIA_DRM_INFO = 210;

    private class TaskHandler extends Handler {
        private MediaPlayer2 mMediaPlayer;

        TaskHandler(MediaPlayer2 mp, Looper looper) {
            super(looper);
            mMediaPlayer = mp;
        }

        @Override
        public void handleMessage(Message msg) {
            handleMessage(msg, 0);
        }

        public void handleMessage(Message msg, long srcId) {
            if (mMediaPlayer.mNativeContext == 0) {
                Log.w(TAG, "mediaplayer2 went away with unhandled events");
                return;
            }
            final int what = msg.arg1;
            final int extra = msg.arg2;

            final SourceInfo sourceInfo = getSourceInfoById(srcId);
            if (sourceInfo == null) {
                return;
            }
            final DataSourceDesc dsd = sourceInfo.mDSD;

            switch(msg.what) {
                case MEDIA_PREPARED:
                {
                    if (dsd != null) {
                        sendEvent(new EventNotifier() {
                            @Override
                            public void notify(EventCallback callback) {
                                callback.onInfo(
                                        mMediaPlayer, dsd, MEDIA_INFO_PREPARED, 0);
                            }
                        });
                    }

                    synchronized (mSrcLock) {
                        SourceInfo nextSourceInfo = mNextSourceInfos.peek();
                        Log.i(TAG, "MEDIA_PREPARED: srcId=" + srcId
                                + ", curSrc=" + mCurrentSourceInfo
                                + ", nextSrc=" + nextSourceInfo);

                        if (isCurrentSource(srcId)) {
                            prepareNextDataSource();
                        } else if (isNextSource(srcId)) {
                            nextSourceInfo.mStateAsNextSource = NEXT_SOURCE_STATE_PREPARED;
                            if (nextSourceInfo.mPlayPendingAsNextSource) {
                                playNextDataSource();
                            }
                        }
                    }

                    synchronized (mTaskLock) {
                        if (mCurrentTask != null
                                && mCurrentTask.mMediaCallType == CALL_COMPLETED_PREPARE
                                && mCurrentTask.mDSD == dsd
                                && mCurrentTask.mNeedToWaitForEventToComplete) {
                            mCurrentTask.sendCompleteNotification(CALL_STATUS_NO_ERROR);
                            mCurrentTask = null;
                            processPendingTask_l();
                        }
                    }
                    return;
                }

                case MEDIA_DRM_INFO:
                {
                    if (msg.obj == null) {
                        Log.w(TAG, "MEDIA_DRM_INFO msg.obj=NULL");
                    } else if (msg.obj instanceof byte[]) {
                        // The PlayerMessage was parsed already in postEventFromNative
                        final DrmInfo drmInfo;

                        synchronized (mDrmLock) {
                            if (mDrmInfo != null) {
                                drmInfo = mDrmInfo.makeCopy();
                            } else {
                                drmInfo = null;
                            }
                        }

                        // notifying the client outside the lock
                        if (drmInfo != null) {
                            sendDrmEvent(new DrmEventNotifier() {
                                @Override
                                public void notify(DrmEventCallback callback) {
                                    callback.onDrmInfo(
                                            mMediaPlayer, dsd, drmInfo);
                                }
                            });
                        }
                    } else {
                        Log.w(TAG, "MEDIA_DRM_INFO msg.obj of unexpected type " + msg.obj);
                    }
                    return;
                }

                case MEDIA_PLAYBACK_COMPLETE:
                {
                    if (isCurrentSource(srcId)) {
                        sendEvent(new EventNotifier() {
                            @Override
                            public void notify(EventCallback callback) {
                                callback.onInfo(
                                        mMediaPlayer, dsd, MEDIA_INFO_DATA_SOURCE_END, 0);
                            }
                        });
                        stayAwake(false);

                        synchronized (mSrcLock) {
                            SourceInfo nextSourceInfo = mNextSourceInfos.peek();
                            if (nextSourceInfo != null) {
                                nextSourceInfo.mPlayPendingAsNextSource = true;
                            }
                            Log.i(TAG, "MEDIA_PLAYBACK_COMPLETE: srcId=" + srcId
                                    + ", curSrc=" + mCurrentSourceInfo
                                    + ", nextSrc=" + nextSourceInfo);
                        }

                        playNextDataSource();
                    }

                    return;
                }

                case MEDIA_STOPPED:
                case MEDIA_STARTED:
                case MEDIA_PAUSED:
                case MEDIA_SKIPPED:
                case MEDIA_NOTIFY_TIME:
                {
                    // Do nothing. The client should have enough information with
                    // {@link EventCallback#onMediaTimeDiscontinuity}.
                    break;
                }

                case MEDIA_BUFFERING_UPDATE:
                {
                    final int percent = msg.arg1;
                    sendEvent(new EventNotifier() {
                        @Override
                        public void notify(EventCallback callback) {
                            callback.onInfo(
                                    mMediaPlayer, dsd, MEDIA_INFO_BUFFERING_UPDATE, percent);
                        }
                    });

                    SourceInfo src = getSourceInfoById(srcId);
                    if (src != null) {
                        src.mBufferedPercentage.set(percent);
                    }

                    return;
                }

                case MEDIA_SEEK_COMPLETE:
                {
                    synchronized (mTaskLock) {
                        if (!mPendingTasks.isEmpty()
                                && mPendingTasks.get(0).mMediaCallType != CALL_COMPLETED_SEEK_TO
                                && getState() == PLAYER_STATE_PLAYING) {
                            mIsPreviousCommandSeekTo = false;
                        }

                        if (mCurrentTask != null
                                && mCurrentTask.mMediaCallType == CALL_COMPLETED_SEEK_TO
                                && mCurrentTask.mNeedToWaitForEventToComplete) {
                            mCurrentTask.sendCompleteNotification(CALL_STATUS_NO_ERROR);
                            mCurrentTask = null;
                            processPendingTask_l();
                        }
                    }
                    return;
                }

                case MEDIA_SET_VIDEO_SIZE:
                {
                    final int width = msg.arg1;
                    final int height = msg.arg2;

                    mVideoSize = new VideoSize(width, height);
                    sendEvent(new EventNotifier() {
                        @Override
                        public void notify(EventCallback callback) {
                            callback.onVideoSizeChanged(
                                    mMediaPlayer, dsd, mVideoSize);
                        }
                    });
                    return;
                }

                case MEDIA_ERROR:
                {
                    Log.e(TAG, "Error (" + msg.arg1 + "," + msg.arg2 + ")");
                    sendEvent(new EventNotifier() {
                        @Override
                        public void notify(EventCallback callback) {
                            callback.onError(
                                    mMediaPlayer, dsd, what, extra);
                        }
                    });
                    sendEvent(new EventNotifier() {
                        @Override
                        public void notify(EventCallback callback) {
                            callback.onInfo(
                                    mMediaPlayer, dsd, MEDIA_INFO_DATA_SOURCE_END, 0);
                        }
                    });
                    stayAwake(false);
                    return;
                }

                case MEDIA_INFO:
                {
                    switch (msg.arg1) {
                        case MEDIA_INFO_VIDEO_TRACK_LAGGING:
                            Log.i(TAG, "Info (" + msg.arg1 + "," + msg.arg2 + ")");
                            break;
                    }

                    sendEvent(new EventNotifier() {
                        @Override
                        public void notify(EventCallback callback) {
                            callback.onInfo(
                                    mMediaPlayer, dsd, what, extra);
                        }
                    });

                    if (msg.arg1 == MEDIA_INFO_DATA_SOURCE_START) {
                        if (isCurrentSource(srcId)) {
                            prepareNextDataSource();
                        }
                    }

                    // No real default action so far.
                    return;
                }

                case MEDIA_TIMED_TEXT:
                {
                    final TimedText text;
                    if (msg.obj instanceof byte[]) {
                        PlayerMessage playerMsg;
                        try {
                            playerMsg = PlayerMessage.parseFrom((byte[]) msg.obj);
                        } catch (InvalidProtocolBufferException e) {
                            Log.w(TAG, "Failed to parse timed text.", e);
                            return;
                        }
                        text = TimedTextUtil.parsePlayerMessage(playerMsg);
                    } else {
                        text = null;
                    }

                    sendEvent(new EventNotifier() {
                        @Override
                        public void notify(EventCallback callback) {
                            callback.onTimedText(
                                    mMediaPlayer, dsd, text);
                        }
                    });
                    return;
                }

                case MEDIA_SUBTITLE_DATA:
                {
                    if (msg.obj instanceof byte[]) {
                        PlayerMessage playerMsg;
                        try {
                            playerMsg = PlayerMessage.parseFrom((byte[]) msg.obj);
                        } catch (InvalidProtocolBufferException e) {
                            Log.w(TAG, "Failed to parse subtitle data.", e);
                            return;
                        }
                        Iterator<Value> in = playerMsg.getValuesList().iterator();
                        SubtitleData data = new SubtitleData(
                                in.next().getInt32Value(),  // trackIndex
                                in.next().getInt64Value(),  // startTimeUs
                                in.next().getInt64Value(),  // durationUs
                                in.next().getBytesValue().toByteArray());  // data
                        sendEvent(new EventNotifier() {
                            @Override
                            public void notify(EventCallback callback) {
                                callback.onSubtitleData(
                                        mMediaPlayer, dsd, data);
                            }
                        });
                    }
                    return;
                }

                case MEDIA_META_DATA:
                {
                    final TimedMetaData data;
                    if (msg.obj instanceof byte[]) {
                        PlayerMessage playerMsg;
                        try {
                            playerMsg = PlayerMessage.parseFrom((byte[]) msg.obj);
                        } catch (InvalidProtocolBufferException e) {
                            Log.w(TAG, "Failed to parse timed meta data.", e);
                            return;
                        }
                        Iterator<Value> in = playerMsg.getValuesList().iterator();
                        data = new TimedMetaData(
                                in.next().getInt64Value(),  // timestampUs
                                in.next().getBytesValue().toByteArray());  // metaData
                    } else {
                        data = null;
                    }

                    sendEvent(new EventNotifier() {
                        @Override
                        public void notify(EventCallback callback) {
                            callback.onTimedMetaDataAvailable(
                                    mMediaPlayer, dsd, data);
                        }
                    });
                    return;
                }

                case MEDIA_NOP: // interface test message - ignore
                {
                    break;
                }

                default:
                {
                    Log.e(TAG, "Unknown message type " + msg.what);
                    return;
                }
            }
        }
    }

    /*
     * Called from native code when an interesting event happens.  This method
     * just uses the TaskHandler system to post the event back to the main app thread.
     * We use a weak reference to the original MediaPlayer2 object so that the native
     * code is safe from the object disappearing from underneath it.  (This is
     * the cookie passed to native_setup().)
     */
    private static void postEventFromNative(Object mediaplayer2Ref, long srcId,
                                            int what, int arg1, int arg2, byte[] obj) {
        final MediaPlayer2 mp = (MediaPlayer2) ((WeakReference) mediaplayer2Ref).get();
        if (mp == null) {
            return;
        }

        switch (what) {
            case MEDIA_DRM_INFO:
                // We need to derive mDrmInfo before prepare() returns so processing it here
                // before the notification is sent to TaskHandler below. TaskHandler runs in the
                // notification looper so its handleMessage might process the event after prepare()
                // has returned.
                Log.v(TAG, "postEventFromNative MEDIA_DRM_INFO");
                if (obj != null) {
                    PlayerMessage playerMsg;
                    try {
                        playerMsg = PlayerMessage.parseFrom(obj);
                    } catch (InvalidProtocolBufferException e) {
                        Log.w(TAG, "MEDIA_DRM_INFO failed to parse msg.obj " + obj);
                        break;
                    }
                    DrmInfo drmInfo = new DrmInfo(playerMsg);
                    synchronized (mp.mDrmLock) {
                        mp.mDrmInfo = drmInfo;
                    }
                } else {
                    Log.w(TAG, "MEDIA_DRM_INFO msg.obj of unexpected type " + obj);
                }
                break;

            case MEDIA_PREPARED:
                // By this time, we've learned about DrmInfo's presence or absence. This is meant
                // mainly for prepare() use case. For prepare(), this still can run to a race
                // condition b/c MediaPlayerNative releases the prepare() lock before calling notify
                // so we also set mDrmInfoResolved in prepare().
                synchronized (mp.mDrmLock) {
                    mp.mDrmInfoResolved = true;
                }
                break;
        }

        if (mp.mTaskHandler != null) {
            Message m = mp.mTaskHandler.obtainMessage(what, arg1, arg2, obj);

            mp.mTaskHandler.post(new Runnable() {
                @Override
                public void run() {
                    mp.mTaskHandler.handleMessage(m, srcId);
                }
            });
        }
    }

    /**
     * Interface definition for callbacks to be invoked when the player has the corresponding
     * events.
     */
    public static class EventCallback {
        /**
         * Called to indicate the video size
         *
         * The video size (width and height) could be 0 if there was no video,
         * no display surface was set, or the value was not determined yet.
         *
         * @param mp the MediaPlayer2 associated with this callback
         * @param dsd the DataSourceDesc of this data source
         * @param size the size of the video
         */
        public void onVideoSizeChanged(
                MediaPlayer2 mp, DataSourceDesc dsd, VideoSize size) { }

        /**
         * Called to indicate an avaliable timed text
         *
         * @param mp the MediaPlayer2 associated with this callback
         * @param dsd the DataSourceDesc of this data source
         * @param text the timed text sample which contains the text
         *             needed to be displayed and the display format.
         * @hide
         */
        public void onTimedText(MediaPlayer2 mp, DataSourceDesc dsd, TimedText text) { }

        /**
         * Called to indicate avaliable timed metadata
         * <p>
         * This method will be called as timed metadata is extracted from the media,
         * in the same order as it occurs in the media. The timing of this event is
         * not controlled by the associated timestamp.
         * <p>
         * Currently only HTTP live streaming data URI's embedded with timed ID3 tags generates
         * {@link TimedMetaData}.
         *
         * @see MediaPlayer2#selectTrack(int)
         * @see MediaPlayer2.OnTimedMetaDataAvailableListener
         * @see TimedMetaData
         *
         * @param mp the MediaPlayer2 associated with this callback
         * @param dsd the DataSourceDesc of this data source
         * @param data the timed metadata sample associated with this event
         */
        public void onTimedMetaDataAvailable(
                MediaPlayer2 mp, DataSourceDesc dsd, TimedMetaData data) { }

        /**
         * Called to indicate an error.
         *
         * @param mp the MediaPlayer2 the error pertains to
         * @param dsd the DataSourceDesc of this data source
         * @param what the type of error that has occurred.
         * @param extra an extra code, specific to the error. Typically
         * implementation dependent.
         */
        public void onError(
                MediaPlayer2 mp, DataSourceDesc dsd, @MediaError int what, int extra) { }

        /**
         * Called to indicate an info or a warning.
         *
         * @param mp the MediaPlayer2 the info pertains to.
         * @param dsd the DataSourceDesc of this data source
         * @param what the type of info or warning.
         * @param extra an extra code, specific to the info. Typically
         * implementation dependent.
         */
        public void onInfo(MediaPlayer2 mp, DataSourceDesc dsd, @MediaInfo int what, int extra) { }

        /**
         * Called to acknowledge an API call.
         *
         * @param mp the MediaPlayer2 the call was made on.
         * @param dsd the DataSourceDesc of this data source
         * @param what the enum for the API call.
         * @param status the returned status code for the call.
         */
        public void onCallCompleted(
                MediaPlayer2 mp, DataSourceDesc dsd, @CallCompleted int what,
                @CallStatus int status) { }

        /**
         * Called to indicate media clock has changed.
         *
         * @param mp the MediaPlayer2 the media time pertains to.
         * @param dsd the DataSourceDesc of this data source
         * @param timestamp the new media clock.
         */
        public void onMediaTimeDiscontinuity(
                MediaPlayer2 mp, DataSourceDesc dsd, MediaTimestamp timestamp) { }

        /**
         * Called to indicate {@link #notifyWhenCommandLabelReached(Object)} has been processed.
         *
         * @param mp the MediaPlayer2 {@link #notifyWhenCommandLabelReached(Object)} was called on.
         * @param label the application specific Object given by
         *        {@link #notifyWhenCommandLabelReached(Object)}.
         */
        public void onCommandLabelReached(MediaPlayer2 mp, @NonNull Object label) { }

        /**
         * Called when when a player subtitle track has new subtitle data available.
         * @param mp the player that reports the new subtitle data
         * @param dsd the DataSourceDesc of this data source
         * @param data the subtitle data
         */
        public void onSubtitleData(
                MediaPlayer2 mp, DataSourceDesc dsd, @NonNull SubtitleData data) { }
    }

    private final Object mEventCbLock = new Object();
    private ArrayList<Pair<Executor, EventCallback>> mEventCallbackRecords =
            new ArrayList<Pair<Executor, EventCallback>>();

    /**
     * Registers the callback to be invoked for various events covered by {@link EventCallback}.
     *
     * @param executor the executor through which the callback should be invoked
     * @param eventCallback the callback that will be run
     */
    // This is a synchronous call.
    public void registerEventCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull EventCallback eventCallback) {
        if (eventCallback == null) {
            throw new IllegalArgumentException("Illegal null EventCallback");
        }
        if (executor == null) {
            throw new IllegalArgumentException(
                    "Illegal null Executor for the EventCallback");
        }
        synchronized (mEventCbLock) {
            mEventCallbackRecords.add(new Pair(executor, eventCallback));
        }
    }

    /**
     * Unregisters the {@link EventCallback}.
     *
     * @param eventCallback the callback to be unregistered
     */
    // This is a synchronous call.
    public void unregisterEventCallback(EventCallback eventCallback) {
        synchronized (mEventCbLock) {
            for (Pair<Executor, EventCallback> cb : mEventCallbackRecords) {
                if (cb.second == eventCallback) {
                    mEventCallbackRecords.remove(cb);
                }
            }
        }
    }

    private static void checkArgument(boolean expression, String errorMessage) {
        if (!expression) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    private void sendEvent(final EventNotifier notifier) {
        synchronized (mEventCbLock) {
            try {
                for (Pair<Executor, EventCallback> cb : mEventCallbackRecords) {
                    cb.first.execute(() -> notifier.notify(cb.second));
                }
            } catch (RejectedExecutionException e) {
                // The executor has been shut down.
                Log.w(TAG, "The executor has been shut down. Ignoring event.");
            }
        }
    }

    private void sendDrmEvent(final DrmEventNotifier notifier) {
        synchronized (mDrmEventCbLock) {
            try {
                for (Pair<Executor, DrmEventCallback> cb : mDrmEventCallbackRecords) {
                    cb.first.execute(() -> notifier.notify(cb.second));
                }
            } catch (RejectedExecutionException e) {
                // The executor has been shut down.
                Log.w(TAG, "The executor has been shut down. Ignoring drm event.");
            }
        }
    }

    private interface EventNotifier {
        void notify(EventCallback callback);
    }

    private interface DrmEventNotifier {
        void notify(DrmEventCallback callback);
    }

    /* Do not change these values without updating their counterparts
     * in include/media/MediaPlayer2Types.h!
     */
    /** Unspecified media player error.
     * @see EventCallback#onError
     */
    public static final int MEDIA_ERROR_UNKNOWN = 1;

    /**
     * The video is streamed and its container is not valid for progressive
     * playback i.e the video's index (e.g moov atom) is not at the start of the
     * file.
     * @see EventCallback#onError
     */
    public static final int MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK = 200;

    /** File or network related operation errors. */
    public static final int MEDIA_ERROR_IO = -1004;
    /** Bitstream is not conforming to the related coding standard or file spec. */
    public static final int MEDIA_ERROR_MALFORMED = -1007;
    /** Bitstream is conforming to the related coding standard or file spec, but
     * the media framework does not support the feature. */
    public static final int MEDIA_ERROR_UNSUPPORTED = -1010;
    /** Some operation takes too long to complete, usually more than 3-5 seconds. */
    public static final int MEDIA_ERROR_TIMED_OUT = -110;

    /** Unspecified low-level system error. This value originated from UNKNOWN_ERROR in
     * system/core/include/utils/Errors.h
     * @see EventCallback#onError
     * @hide
     */
    public static final int MEDIA_ERROR_SYSTEM = -2147483648;

    /**
     * @hide
     */
    @IntDef(flag = false, prefix = "MEDIA_ERROR", value = {
            MEDIA_ERROR_UNKNOWN,
            MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK,
            MEDIA_ERROR_IO,
            MEDIA_ERROR_MALFORMED,
            MEDIA_ERROR_UNSUPPORTED,
            MEDIA_ERROR_TIMED_OUT,
            MEDIA_ERROR_SYSTEM
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MediaError {}

    /* Do not change these values without updating their counterparts
     * in include/media/MediaPlayer2Types.h!
     */
    /** Unspecified media player info.
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_UNKNOWN = 1;

    /** The player just started the playback of this datas source.
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_DATA_SOURCE_START = 2;

    /** The player just pushed the very first video frame for rendering.
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_VIDEO_RENDERING_START = 3;

    /** The player just rendered the very first audio sample.
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_AUDIO_RENDERING_START = 4;

    /** The player just completed the playback of this data source.
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_DATA_SOURCE_END = 5;

    /** The player just completed the playback of all data sources set by {@link #setDataSource},
     * {@link #setNextDataSource} and {@link #setNextDataSources}.
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_DATA_SOURCE_LIST_END = 6;

    /** The player just completed an iteration of playback loop. This event is sent only when
     *  looping is enabled by {@link #loopCurrent}.
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_DATA_SOURCE_REPEAT = 7;

    /** The player just prepared a data source.
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_PREPARED = 100;

    /** The video is too complex for the decoder: it can't decode frames fast
     *  enough. Possibly only the audio plays fine at this stage.
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_VIDEO_TRACK_LAGGING = 700;

    /** MediaPlayer2 is temporarily pausing playback internally in order to
     * buffer more data.
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_BUFFERING_START = 701;

    /** MediaPlayer2 is resuming playback after filling buffers.
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_BUFFERING_END = 702;

    /** Estimated network bandwidth information (kbps) is available; currently this event fires
     * simultaneously as {@link #MEDIA_INFO_BUFFERING_START} and {@link #MEDIA_INFO_BUFFERING_END}
     * when playing network files.
     * @see EventCallback#onInfo
     * @hide
     */
    public static final int MEDIA_INFO_NETWORK_BANDWIDTH = 703;

    /**
     * Update status in buffering a media source received through progressive downloading.
     * The received buffering percentage indicates how much of the content has been buffered
     * or played. For example a buffering update of 80 percent when half the content
     * has already been played indicates that the next 30 percent of the
     * content to play has been buffered.
     *
     * The {@code extra} parameter in {@code EventCallback.onInfo} is the
     * percentage (0-100) of the content that has been buffered or played thus far.
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_BUFFERING_UPDATE = 704;

    /** Bad interleaving means that a media has been improperly interleaved or
     * not interleaved at all, e.g has all the video samples first then all the
     * audio ones. Video is playing but a lot of disk seeks may be happening.
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_BAD_INTERLEAVING = 800;

    /** The media cannot be seeked (e.g live stream)
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_NOT_SEEKABLE = 801;

    /** A new set of metadata is available.
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_METADATA_UPDATE = 802;

    /** Informs that audio is not playing. Note that playback of the video
     * is not interrupted.
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_AUDIO_NOT_PLAYING = 804;

    /** Informs that video is not playing. Note that playback of the audio
     * is not interrupted.
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_VIDEO_NOT_PLAYING = 805;

    /** Failed to handle timed text track properly.
     * @see EventCallback#onInfo
     *
     * {@hide}
     */
    public static final int MEDIA_INFO_TIMED_TEXT_ERROR = 900;

    /** Subtitle track was not supported by the media framework.
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_UNSUPPORTED_SUBTITLE = 901;

    /** Reading the subtitle track takes too long.
     * @see EventCallback#onInfo
     */
    public static final int MEDIA_INFO_SUBTITLE_TIMED_OUT = 902;

    /**
     * @hide
     */
    @IntDef(flag = false, prefix = "MEDIA_INFO", value = {
            MEDIA_INFO_UNKNOWN,
            MEDIA_INFO_DATA_SOURCE_START,
            MEDIA_INFO_VIDEO_RENDERING_START,
            MEDIA_INFO_AUDIO_RENDERING_START,
            MEDIA_INFO_DATA_SOURCE_END,
            MEDIA_INFO_DATA_SOURCE_LIST_END,
            MEDIA_INFO_PREPARED,
            MEDIA_INFO_VIDEO_TRACK_LAGGING,
            MEDIA_INFO_BUFFERING_START,
            MEDIA_INFO_BUFFERING_END,
            MEDIA_INFO_NETWORK_BANDWIDTH,
            MEDIA_INFO_BUFFERING_UPDATE,
            MEDIA_INFO_BAD_INTERLEAVING,
            MEDIA_INFO_NOT_SEEKABLE,
            MEDIA_INFO_METADATA_UPDATE,
            MEDIA_INFO_AUDIO_NOT_PLAYING,
            MEDIA_INFO_VIDEO_NOT_PLAYING,
            MEDIA_INFO_TIMED_TEXT_ERROR,
            MEDIA_INFO_UNSUPPORTED_SUBTITLE,
            MEDIA_INFO_SUBTITLE_TIMED_OUT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MediaInfo {}

    //--------------------------------------------------------------------------
    /** The player just completed a call {@link #attachAuxEffect}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_ATTACH_AUX_EFFECT = 1;

    /** The player just completed a call {@link #deselectTrack}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_DESELECT_TRACK = 2;

    /** The player just completed a call {@link #loopCurrent}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_LOOP_CURRENT = 3;

    /** The player just completed a call {@link #pause}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_PAUSE = 4;

    /** The player just completed a call {@link #play}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_PLAY = 5;

    /** The player just completed a call {@link #prepare}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_PREPARE = 6;

    /** The player just completed a call {@link #seekTo}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_SEEK_TO = 14;

    /** The player just completed a call {@link #selectTrack}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_SELECT_TRACK = 15;

    /** The player just completed a call {@link #setAudioAttributes}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_SET_AUDIO_ATTRIBUTES = 16;

    /** The player just completed a call {@link #setAudioSessionId}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_SET_AUDIO_SESSION_ID = 17;

    /** The player just completed a call {@link #setAuxEffectSendLevel}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_SET_AUX_EFFECT_SEND_LEVEL = 18;

    /** The player just completed a call {@link #setDataSource}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_SET_DATA_SOURCE = 19;

    /** The player just completed a call {@link #setNextDataSource}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_SET_NEXT_DATA_SOURCE = 22;

    /** The player just completed a call {@link #setNextDataSources}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_SET_NEXT_DATA_SOURCES = 23;

    /** The player just completed a call {@link #setPlaybackParams}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_SET_PLAYBACK_PARAMS = 24;

    /** The player just completed a call {@link #setPlayerVolume}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_SET_PLAYER_VOLUME = 26;

    /** The player just completed a call {@link #setSurface}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_SET_SURFACE = 27;

    /** The player just completed a call {@link #setSyncParams}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_SET_SYNC_PARAMS = 28;

    /** The player just completed a call {@link #skipToNext}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_SKIP_TO_NEXT = 29;

    /** The player just completed a call {@link #clearNextDataSources}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_CLEAR_NEXT_DATA_SOURCES = 30;

    /** The player just completed a call {@link #setBufferingParams}.
     * @see EventCallback#onCallCompleted
     * @hide
     */
    public static final int CALL_COMPLETED_SET_BUFFERING_PARAMS = 31;

    /** The player just completed a call {@link #setDisplay}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_SET_DISPLAY = 33;

    /** The player just completed a call {@link #setWakeMode}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_SET_WAKE_MODE = 34;

    /** The player just completed a call {@link #setScreenOnWhilePlaying}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_COMPLETED_SET_SCREEN_ON_WHILE_PLAYING = 35;

    /**
     * The start of the methods which have separate call complete callback.
     * @hide
     */
    public static final int SEPARATE_CALL_COMPLETED_CALLBACK_START = 1000;

    /** The player just completed a call {@link #notifyWhenCommandLabelReached}.
     * @see EventCallback#onCommandLabelReached
     * @hide
     */
    public static final int CALL_COMPLETED_NOTIFY_WHEN_COMMAND_LABEL_REACHED =
            SEPARATE_CALL_COMPLETED_CALLBACK_START;

    /** The player just completed a call {@link #prepareDrm}.
     * @see DrmEventCallback#onDrmPrepared
     * @hide
     */
    public static final int CALL_COMPLETED_PREPARE_DRM =
            SEPARATE_CALL_COMPLETED_CALLBACK_START + 1;

    /**
     * @hide
     */
    @IntDef(flag = false, prefix = "CALL_COMPLETED", value = {
            CALL_COMPLETED_ATTACH_AUX_EFFECT,
            CALL_COMPLETED_DESELECT_TRACK,
            CALL_COMPLETED_LOOP_CURRENT,
            CALL_COMPLETED_PAUSE,
            CALL_COMPLETED_PLAY,
            CALL_COMPLETED_PREPARE,
            CALL_COMPLETED_SEEK_TO,
            CALL_COMPLETED_SELECT_TRACK,
            CALL_COMPLETED_SET_AUDIO_ATTRIBUTES,
            CALL_COMPLETED_SET_AUDIO_SESSION_ID,
            CALL_COMPLETED_SET_AUX_EFFECT_SEND_LEVEL,
            CALL_COMPLETED_SET_DATA_SOURCE,
            CALL_COMPLETED_SET_NEXT_DATA_SOURCE,
            CALL_COMPLETED_SET_NEXT_DATA_SOURCES,
            CALL_COMPLETED_SET_PLAYBACK_PARAMS,
            CALL_COMPLETED_SET_PLAYER_VOLUME,
            CALL_COMPLETED_SET_SURFACE,
            CALL_COMPLETED_SET_SYNC_PARAMS,
            CALL_COMPLETED_SKIP_TO_NEXT,
            CALL_COMPLETED_CLEAR_NEXT_DATA_SOURCES,
            CALL_COMPLETED_SET_BUFFERING_PARAMS,
            CALL_COMPLETED_SET_DISPLAY,
            CALL_COMPLETED_SET_WAKE_MODE,
            CALL_COMPLETED_SET_SCREEN_ON_WHILE_PLAYING,
            CALL_COMPLETED_NOTIFY_WHEN_COMMAND_LABEL_REACHED,
            CALL_COMPLETED_PREPARE_DRM,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CallCompleted {}

    /** Status code represents that call is completed without an error.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_STATUS_NO_ERROR = 0;

    /** Status code represents that call is ended with an unknown error.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_STATUS_ERROR_UNKNOWN = Integer.MIN_VALUE;

    /** Status code represents that the player is not in valid state for the operation.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_STATUS_INVALID_OPERATION = 1;

    /** Status code represents that the argument is illegal.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_STATUS_BAD_VALUE = 2;

    /** Status code represents that the operation is not allowed.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_STATUS_PERMISSION_DENIED = 3;

    /** Status code represents a file or network related operation error.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_STATUS_ERROR_IO = 4;

    /** Status code represents that the call has been skipped. For example, a {@link #seekTo}
     * request may be skipped if it is followed by another {@link #seekTo} request.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_STATUS_SKIPPED = 5;

    /** Status code represents that DRM operation is called before preparing a DRM scheme through
     *  {@link #prepareDrm}.
     * @see EventCallback#onCallCompleted
     */
    public static final int CALL_STATUS_NO_DRM_SCHEME = 6;

    /**
     * @hide
     */
    @IntDef(flag = false, prefix = "CALL_STATUS", value = {
            CALL_STATUS_NO_ERROR,
            CALL_STATUS_ERROR_UNKNOWN,
            CALL_STATUS_INVALID_OPERATION,
            CALL_STATUS_BAD_VALUE,
            CALL_STATUS_PERMISSION_DENIED,
            CALL_STATUS_ERROR_IO,
            CALL_STATUS_SKIPPED,
            CALL_STATUS_NO_DRM_SCHEME})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CallStatus {}

    // Modular DRM begin

    /**
     * Interface definition of a callback to be invoked when the app
     * can do DRM configuration (get/set properties) before the session
     * is opened. This facilitates configuration of the properties, like
     * 'securityLevel', which has to be set after DRM scheme creation but
     * before the DRM session is opened.
     *
     * The only allowed DRM calls in this listener are {@link #getDrmPropertyString}
     * and {@link #setDrmPropertyString}.
     */
    public interface OnDrmConfigHelper {
        /**
         * Called to give the app the opportunity to configure DRM before the session is created
         *
         * @param mp the {@code MediaPlayer2} associated with this callback
         * @param dsd the DataSourceDesc of this data source
         */
        public void onDrmConfig(MediaPlayer2 mp, DataSourceDesc dsd);
    }

    /**
     * Register a callback to be invoked for configuration of the DRM object before
     * the session is created.
     * The callback will be invoked synchronously during the execution
     * of {@link #prepareDrm(UUID uuid)}.
     *
     * @param listener the callback that will be run
     */
    // This is a synchronous call.
    public void setOnDrmConfigHelper(OnDrmConfigHelper listener) {
        synchronized (mDrmLock) {
            mOnDrmConfigHelper = listener;
        }
    }

    private OnDrmConfigHelper mOnDrmConfigHelper;

    /**
     * Interface definition for callbacks to be invoked when the player has the corresponding
     * DRM events.
     */
    public static class DrmEventCallback {
        /**
         * Called to indicate DRM info is available
         *
         * @param mp the {@code MediaPlayer2} associated with this callback
         * @param dsd the DataSourceDesc of this data source
         * @param drmInfo DRM info of the source including PSSH, and subset
         *                of crypto schemes supported by this device
         */
        public void onDrmInfo(MediaPlayer2 mp, DataSourceDesc dsd, DrmInfo drmInfo) { }

        /**
         * Called to notify the client that {@link #prepareDrm} is finished and ready for
         * key request/response.
         *
         * @param mp the {@code MediaPlayer2} associated with this callback
         * @param dsd the DataSourceDesc of this data source
         * @param status the result of DRM preparation.
         */
        public void onDrmPrepared(
                MediaPlayer2 mp, DataSourceDesc dsd, @PrepareDrmStatusCode int status) { }
    }

    private final Object mDrmEventCbLock = new Object();
    private ArrayList<Pair<Executor, DrmEventCallback>> mDrmEventCallbackRecords =
            new ArrayList<Pair<Executor, DrmEventCallback>>();

    /**
     * Registers the callback to be invoked for various DRM events.
     *
     * @param eventCallback the callback that will be run
     * @param executor the executor through which the callback should be invoked
     */
    // This is a synchronous call.
    public void registerDrmEventCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull DrmEventCallback eventCallback) {
        if (eventCallback == null) {
            throw new IllegalArgumentException("Illegal null EventCallback");
        }
        if (executor == null) {
            throw new IllegalArgumentException(
                    "Illegal null Executor for the EventCallback");
        }
        synchronized (mDrmEventCbLock) {
            mDrmEventCallbackRecords.add(new Pair(executor, eventCallback));
        }
    }

    /**
     * Unregisters the {@link DrmEventCallback}.
     *
     * @param eventCallback the callback to be unregistered
     */
    // This is a synchronous call.
    public void unregisterDrmEventCallback(DrmEventCallback eventCallback) {
        synchronized (mDrmEventCbLock) {
            for (Pair<Executor, DrmEventCallback> cb : mDrmEventCallbackRecords) {
                if (cb.second == eventCallback) {
                    mDrmEventCallbackRecords.remove(cb);
                }
            }
        }
    }

    /**
     * The status codes for {@link DrmEventCallback#onDrmPrepared} listener.
     * <p>
     *
     * DRM preparation has succeeded.
     */
    public static final int PREPARE_DRM_STATUS_SUCCESS = 0;

    /**
     * The device required DRM provisioning but couldn't reach the provisioning server.
     */
    public static final int PREPARE_DRM_STATUS_PROVISIONING_NETWORK_ERROR = 1;

    /**
     * The device required DRM provisioning but the provisioning server denied the request.
     */
    public static final int PREPARE_DRM_STATUS_PROVISIONING_SERVER_ERROR = 2;

    /**
     * The DRM preparation has failed .
     */
    public static final int PREPARE_DRM_STATUS_PREPARATION_ERROR = 3;

    /**
     * The crypto scheme UUID is not supported by the device.
     */
    public static final int PREPARE_DRM_STATUS_UNSUPPORTED_SCHEME = 4;

    /**
     * The hardware resources are not available, due to being in use.
     */
    public static final int PREPARE_DRM_STATUS_RESOURCE_BUSY = 5;

    /** @hide */
    @IntDef(flag = false, prefix = "PREPARE_DRM_STATUS", value = {
        PREPARE_DRM_STATUS_SUCCESS,
        PREPARE_DRM_STATUS_PROVISIONING_NETWORK_ERROR,
        PREPARE_DRM_STATUS_PROVISIONING_SERVER_ERROR,
        PREPARE_DRM_STATUS_PREPARATION_ERROR,
        PREPARE_DRM_STATUS_UNSUPPORTED_SCHEME,
        PREPARE_DRM_STATUS_RESOURCE_BUSY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PrepareDrmStatusCode {}

    /**
     * Retrieves the DRM Info associated with the current source
     *
     * @throws IllegalStateException if called before being prepared
     */
    public DrmInfo getDrmInfo() {
        DrmInfo drmInfo = null;

        // there is not much point if the app calls getDrmInfo within an OnDrmInfoListenet;
        // regardless below returns drmInfo anyway instead of raising an exception
        synchronized (mDrmLock) {
            if (!mDrmInfoResolved && mDrmInfo == null) {
                final String msg = "The Player has not been prepared yet";
                Log.v(TAG, msg);
                throw new IllegalStateException(msg);
            }

            if (mDrmInfo != null) {
                drmInfo = mDrmInfo.makeCopy();
            }
        }  // synchronized

        return drmInfo;
    }

    /**
     * Prepares the DRM for the current source
     * <p>
     * If {@link OnDrmConfigHelper} is registered, it will be called during
     * preparation to allow configuration of the DRM properties before opening the
     * DRM session. It should be used only for a series of {@link #getDrmPropertyString}
     * and {@link #setDrmPropertyString} calls and refrain from any lengthy operation.
     * <p>
     * If the device has not been provisioned before, this call also provisions the device
     * which involves accessing the provisioning server and can take a variable time to
     * complete depending on the network connectivity.
     * When needed, the provisioning will be launched  in the background.
     * The listener {@link DrmEventCallback#onDrmPrepared}
     * will be called when provisioning and preparation are finished. The application should
     * check the status code returned with {@link DrmEventCallback#onDrmPrepared} to proceed.
     * <p>
     * The registered {@link DrmEventCallback#onDrmPrepared} is called to indicate the DRM
     * session being ready. The application should not make any assumption about its call
     * sequence (e.g., before or after prepareDrm returns).
     * <p>
     *
     * @param uuid The UUID of the crypto scheme. If not known beforehand, it can be retrieved
     * from the source through {@code getDrmInfo} or registering a
     * {@link DrmEventCallback#onDrmInfo}.
     *
     * @return a token which can be used to cancel the operation later with {@link #cancelCommand}.
     */
    // This is an asynchronous call.
    public Object prepareDrm(@NonNull UUID uuid) {
        return addTask(new Task(CALL_COMPLETED_PREPARE_DRM, true) {
            @Override
            void process() {
                int status = PREPARE_DRM_STATUS_SUCCESS;
                boolean sendEvent = true;

                try {
                    doPrepareDrm(uuid);
                } catch (ResourceBusyException e) {
                    status = PREPARE_DRM_STATUS_RESOURCE_BUSY;
                } catch (UnsupportedSchemeException e) {
                    status = PREPARE_DRM_STATUS_UNSUPPORTED_SCHEME;
                } catch (NotProvisionedException e) {
                    Log.w(TAG, "prepareDrm: NotProvisionedException");

                    // handle provisioning internally; it'll reset mPrepareDrmInProgress
                    status = handleProvisioninig(uuid);

                    if (status == PREPARE_DRM_STATUS_SUCCESS) {
                        // DrmEventCallback will be fired in provisioning
                        sendEvent = false;
                    } else {
                        synchronized (mDrmLock) {
                            cleanDrmObj();
                        }

                        switch (status) {
                            case PREPARE_DRM_STATUS_PROVISIONING_NETWORK_ERROR:
                                Log.e(TAG, "prepareDrm: Provisioning was required but failed "
                                        + "due to a network error.");
                                break;

                            case PREPARE_DRM_STATUS_PROVISIONING_SERVER_ERROR:
                                Log.e(TAG, "prepareDrm: Provisioning was required but the request "
                                        + "was denied by the server.");
                                break;

                            case PREPARE_DRM_STATUS_PREPARATION_ERROR:
                            default:
                                Log.e(TAG, "prepareDrm: Post-provisioning preparation failed.");
                                break;
                        }
                    }
                } catch (Exception e) {
                    status = PREPARE_DRM_STATUS_PREPARATION_ERROR;
                }

                if (sendEvent) {
                    final int prepareDrmStatus = status;
                    sendDrmEvent(new DrmEventNotifier() {
                        @Override
                        public void notify(DrmEventCallback callback) {
                            callback.onDrmPrepared(
                                    MediaPlayer2.this, getCurrentDataSource(), prepareDrmStatus);
                        }
                    });

                    synchronized (mTaskLock) {
                        mCurrentTask = null;
                        processPendingTask_l();
                    }
                }
            }
        });
    }

    private void doPrepareDrm(@NonNull UUID uuid)
            throws UnsupportedSchemeException, ResourceBusyException,
                   NotProvisionedException {
        Log.v(TAG, "prepareDrm: uuid: " + uuid + " mOnDrmConfigHelper: " + mOnDrmConfigHelper);

        synchronized (mDrmLock) {
            // only allowing if tied to a protected source; might relax for releasing offline keys
            if (mDrmInfo == null) {
                final String msg = "prepareDrm(): Wrong usage: The player must be prepared and "
                        + "DRM info be retrieved before this call.";
                Log.e(TAG, msg);
                throw new IllegalStateException(msg);
            }

            if (mActiveDrmScheme) {
                final String msg = "prepareDrm(): Wrong usage: There is already "
                        + "an active DRM scheme with " + mDrmUUID;
                Log.e(TAG, msg);
                throw new IllegalStateException(msg);
            }

            if (mPrepareDrmInProgress) {
                final String msg = "prepareDrm(): Wrong usage: There is already "
                        + "a pending prepareDrm call.";
                Log.e(TAG, msg);
                throw new IllegalStateException(msg);
            }

            if (mDrmProvisioningInProgress) {
                final String msg = "prepareDrm(): Unexpectd: Provisioning is already in progress.";
                Log.e(TAG, msg);
                throw new IllegalStateException(msg);
            }

            // shouldn't need this; just for safeguard
            cleanDrmObj();

            mPrepareDrmInProgress = true;

            try {
                // only creating the DRM object to allow pre-openSession configuration
                prepareDrm_createDrmStep(uuid);
            } catch (Exception e) {
                Log.w(TAG, "prepareDrm(): Exception ", e);
                mPrepareDrmInProgress = false;
                throw e;
            }

            mDrmConfigAllowed = true;
        }  // synchronized

        // call the callback outside the lock
        if (mOnDrmConfigHelper != null)  {
            mOnDrmConfigHelper.onDrmConfig(this, getCurrentDataSource());
        }

        synchronized (mDrmLock) {
            mDrmConfigAllowed = false;
            boolean earlyExit = false;

            try {
                prepareDrm_openSessionStep(uuid);

                mDrmUUID = uuid;
                mActiveDrmScheme = true;
                mPrepareDrmInProgress = false;
            } catch (IllegalStateException e) {
                final String msg = "prepareDrm(): Wrong usage: The player must be "
                        + "in the prepared state to call prepareDrm().";
                Log.e(TAG, msg);
                earlyExit = true;
                mPrepareDrmInProgress = false;
                throw new IllegalStateException(msg);
            } catch (NotProvisionedException e) {
                Log.w(TAG, "prepareDrm: NotProvisionedException", e);
                throw e;
            } catch (Exception e) {
                Log.e(TAG, "prepareDrm: Exception " + e);
                earlyExit = true;
                mPrepareDrmInProgress = false;
                throw e;
            } finally {
                if (earlyExit) {  // clean up object if didn't succeed
                    cleanDrmObj();
                }
            }  // finally
        }  // synchronized
    }

    /**
     * Releases the DRM session
     * <p>
     * The player has to have an active DRM session and be in stopped, or prepared
     * state before this call is made.
     * A {@code reset()} call will release the DRM session implicitly.
     *
     * @throws NoDrmSchemeException if there is no active DRM session to release
     */
    // This is a synchronous call.
    public void releaseDrm()
            throws NoDrmSchemeException {
        synchronized (mDrmLock) {
            Log.v(TAG, "releaseDrm:");

            if (!mActiveDrmScheme) {
                Log.e(TAG, "releaseDrm(): No active DRM scheme to release.");
                throw new NoDrmSchemeException(
                        "releaseDrm: No active DRM scheme to release.");
            }

            try {
                // we don't have the player's state in this layer. The below call raises
                // exception if we're in a non-stopped/prepared state.

                // for cleaning native/mediaserver crypto object
                native_releaseDrm();

                // for cleaning client-side MediaDrm object; only called if above has succeeded
                cleanDrmObj();

                mActiveDrmScheme = false;
            } catch (IllegalStateException e) {
                Log.w(TAG, "releaseDrm: Exception ", e);
                throw new IllegalStateException(
                        "releaseDrm: The player is not in a valid state.");
            } catch (Exception e) {
                Log.e(TAG, "releaseDrm: Exception ", e);
            }
        }  // synchronized
    }

    private native void native_releaseDrm();

    /**
     * A key request/response exchange occurs between the app and a license server
     * to obtain or release keys used to decrypt encrypted content.
     * <p>
     * getDrmKeyRequest() is used to obtain an opaque key request byte array that is
     * delivered to the license server.  The opaque key request byte array is returned
     * in KeyRequest.data.  The recommended URL to deliver the key request to is
     * returned in KeyRequest.defaultUrl.
     * <p>
     * After the app has received the key request response from the server,
     * it should deliver to the response to the DRM engine plugin using the method
     * {@link #provideDrmKeyResponse}.
     *
     * @param keySetId is the key-set identifier of the offline keys being released when keyType is
     * {@link MediaDrm#KEY_TYPE_RELEASE}. It should be set to null for other key requests, when
     * keyType is {@link MediaDrm#KEY_TYPE_STREAMING} or {@link MediaDrm#KEY_TYPE_OFFLINE}.
     *
     * @param initData is the container-specific initialization data when the keyType is
     * {@link MediaDrm#KEY_TYPE_STREAMING} or {@link MediaDrm#KEY_TYPE_OFFLINE}. Its meaning is
     * interpreted based on the mime type provided in the mimeType parameter.  It could
     * contain, for example, the content ID, key ID or other data obtained from the content
     * metadata that is required in generating the key request.
     * When the keyType is {@link MediaDrm#KEY_TYPE_RELEASE}, it should be set to null.
     *
     * @param mimeType identifies the mime type of the content
     *
     * @param keyType specifies the type of the request. The request may be to acquire
     * keys for streaming, {@link MediaDrm#KEY_TYPE_STREAMING}, or for offline content
     * {@link MediaDrm#KEY_TYPE_OFFLINE}, or to release previously acquired
     * keys ({@link MediaDrm#KEY_TYPE_RELEASE}), which are identified by a keySetId.
     *
     * @param optionalParameters are included in the key request message to
     * allow a client application to provide additional message parameters to the server.
     * This may be {@code null} if no additional parameters are to be sent.
     *
     * @throws NoDrmSchemeException if there is no active DRM session
     */
    @NonNull
    public MediaDrm.KeyRequest getDrmKeyRequest(
            @Nullable byte[] keySetId, @Nullable byte[] initData,
            @Nullable String mimeType, @MediaDrm.KeyType int keyType,
            @Nullable Map<String, String> optionalParameters)
            throws NoDrmSchemeException {
        Log.v(TAG, "getDrmKeyRequest: "
                + " keySetId: " + keySetId + " initData:" + initData + " mimeType: " + mimeType
                + " keyType: " + keyType + " optionalParameters: " + optionalParameters);

        synchronized (mDrmLock) {
            if (!mActiveDrmScheme) {
                Log.e(TAG, "getDrmKeyRequest NoDrmSchemeException");
                throw new NoDrmSchemeException(
                        "getDrmKeyRequest: Has to set a DRM scheme first.");
            }

            try {
                byte[] scope = (keyType != MediaDrm.KEY_TYPE_RELEASE)
                        ? mDrmSessionId :  // sessionId for KEY_TYPE_STREAMING/OFFLINE
                        keySetId;  // keySetId for KEY_TYPE_RELEASE

                HashMap<String, String> hmapOptionalParameters =
                                                (optionalParameters != null)
                                                ? new HashMap<String, String>(optionalParameters) :
                                                null;

                MediaDrm.KeyRequest request = mDrmObj.getKeyRequest(scope, initData, mimeType,
                                                              keyType, hmapOptionalParameters);
                Log.v(TAG, "getDrmKeyRequest:   --> request: " + request);

                return request;

            } catch (NotProvisionedException e) {
                Log.w(TAG, "getDrmKeyRequest NotProvisionedException: "
                        + "Unexpected. Shouldn't have reached here.");
                throw new IllegalStateException("getDrmKeyRequest: Unexpected provisioning error.");
            } catch (Exception e) {
                Log.w(TAG, "getDrmKeyRequest Exception " + e);
                throw e;
            }

        }  // synchronized
    }

    /**
     * A key response is received from the license server by the app, then it is
     * provided to the DRM engine plugin using provideDrmKeyResponse. When the
     * response is for an offline key request, a key-set identifier is returned that
     * can be used to later restore the keys to a new session with the method
     * {@link # restoreDrmKeys}.
     * When the response is for a streaming or release request, null is returned.
     *
     * @param keySetId When the response is for a release request, keySetId identifies
     * the saved key associated with the release request (i.e., the same keySetId
     * passed to the earlier {@ link # getDrmKeyRequest} call. It MUST be null when the
     * response is for either streaming or offline key requests.
     *
     * @param response the byte array response from the server
     *
     * @throws NoDrmSchemeException if there is no active DRM session
     * @throws DeniedByServerException if the response indicates that the
     * server rejected the request
     */
    // This is a synchronous call.
    public byte[] provideDrmKeyResponse(
            @Nullable byte[] keySetId, @NonNull byte[] response)
            throws NoDrmSchemeException, DeniedByServerException {
        Log.v(TAG, "provideDrmKeyResponse: keySetId: " + keySetId + " response: " + response);

        synchronized (mDrmLock) {

            if (!mActiveDrmScheme) {
                Log.e(TAG, "getDrmKeyRequest NoDrmSchemeException");
                throw new NoDrmSchemeException(
                        "getDrmKeyRequest: Has to set a DRM scheme first.");
            }

            try {
                byte[] scope = (keySetId == null)
                                ? mDrmSessionId :     // sessionId for KEY_TYPE_STREAMING/OFFLINE
                                keySetId;           // keySetId for KEY_TYPE_RELEASE

                byte[] keySetResult = mDrmObj.provideKeyResponse(scope, response);

                Log.v(TAG, "provideDrmKeyResponse: keySetId: " + keySetId + " response: " + response
                        + " --> " + keySetResult);


                return keySetResult;

            } catch (NotProvisionedException e) {
                Log.w(TAG, "provideDrmKeyResponse NotProvisionedException: "
                        + "Unexpected. Shouldn't have reached here.");
                throw new IllegalStateException("provideDrmKeyResponse: "
                        + "Unexpected provisioning error.");
            } catch (Exception e) {
                Log.w(TAG, "provideDrmKeyResponse Exception " + e);
                throw e;
            }
        }  // synchronized
    }

    /**
     * Restore persisted offline keys into a new session.  keySetId identifies the
     * keys to load, obtained from a prior call to {@link #provideDrmKeyResponse}.
     *
     * @param keySetId identifies the saved key set to restore
     *
     * @throws NoDrmSchemeException if there is no active DRM session
     */
    // This is a synchronous call.
    public void restoreDrmKeys(@NonNull byte[] keySetId)
            throws NoDrmSchemeException {
        Log.v(TAG, "restoreDrmKeys: keySetId: " + keySetId);

        synchronized (mDrmLock) {
            if (!mActiveDrmScheme) {
                Log.w(TAG, "restoreDrmKeys NoDrmSchemeException");
                throw new NoDrmSchemeException(
                        "restoreDrmKeys: Has to set a DRM scheme first.");
            }

            try {
                mDrmObj.restoreKeys(mDrmSessionId, keySetId);
            } catch (Exception e) {
                Log.w(TAG, "restoreKeys Exception " + e);
                throw e;
            }
        }  // synchronized
    }

    /**
     * Read a DRM engine plugin String property value, given the property name string.
     * <p>
     * @param propertyName the property name
     *
     * Standard fields names are:
     * {@link MediaDrm#PROPERTY_VENDOR}, {@link MediaDrm#PROPERTY_VERSION},
     * {@link MediaDrm#PROPERTY_DESCRIPTION}, {@link MediaDrm#PROPERTY_ALGORITHMS}
     */
    @NonNull
    public String getDrmPropertyString(
            @NonNull @MediaDrm.StringProperty String propertyName)
            throws NoDrmSchemeException {
        Log.v(TAG, "getDrmPropertyString: propertyName: " + propertyName);

        String value;
        synchronized (mDrmLock) {

            if (!mActiveDrmScheme && !mDrmConfigAllowed) {
                Log.w(TAG, "getDrmPropertyString NoDrmSchemeException");
                throw new NoDrmSchemeException(
                        "getDrmPropertyString: Has to prepareDrm() first.");
            }

            try {
                value = mDrmObj.getPropertyString(propertyName);
            } catch (Exception e) {
                Log.w(TAG, "getDrmPropertyString Exception " + e);
                throw e;
            }
        }  // synchronized

        Log.v(TAG, "getDrmPropertyString: propertyName: " + propertyName + " --> value: " + value);

        return value;
    }

    /**
     * Set a DRM engine plugin String property value.
     * <p>
     * @param propertyName the property name
     * @param value the property value
     *
     * Standard fields names are:
     * {@link MediaDrm#PROPERTY_VENDOR}, {@link MediaDrm#PROPERTY_VERSION},
     * {@link MediaDrm#PROPERTY_DESCRIPTION}, {@link MediaDrm#PROPERTY_ALGORITHMS}
     */
    // This is a synchronous call.
    public void setDrmPropertyString(
            @NonNull @MediaDrm.StringProperty String propertyName, @NonNull String value)
            throws NoDrmSchemeException {
        Log.v(TAG, "setDrmPropertyString: propertyName: " + propertyName + " value: " + value);

        synchronized (mDrmLock) {

            if (!mActiveDrmScheme && !mDrmConfigAllowed) {
                Log.w(TAG, "setDrmPropertyString NoDrmSchemeException");
                throw new NoDrmSchemeException(
                        "setDrmPropertyString: Has to prepareDrm() first.");
            }

            try {
                mDrmObj.setPropertyString(propertyName, value);
            } catch (Exception e) {
                Log.w(TAG, "setDrmPropertyString Exception " + e);
                throw e;
            }
        }  // synchronized
    }

    /**
     * Encapsulates the DRM properties of the source.
     */
    public static final class DrmInfo {
        private Map<UUID, byte[]> mMapPssh;
        private UUID[] mSupportedSchemes;

        /**
         * Returns the PSSH info of the data source for each supported DRM scheme.
         */
        public Map<UUID, byte[]> getPssh() {
            return mMapPssh;
        }

        /**
         * Returns the intersection of the data source and the device DRM schemes.
         * It effectively identifies the subset of the source's DRM schemes which
         * are supported by the device too.
         */
        public List<UUID> getSupportedSchemes() {
            return Arrays.asList(mSupportedSchemes);
        }

        private DrmInfo(Map<UUID, byte[]> pssh, UUID[] supportedSchemes) {
            mMapPssh = pssh;
            mSupportedSchemes = supportedSchemes;
        }

        private DrmInfo(PlayerMessage msg) {
            Log.v(TAG, "DrmInfo(" + msg + ")");

            Iterator<Value> in = msg.getValuesList().iterator();
            byte[] pssh = in.next().getBytesValue().toByteArray();

            Log.v(TAG, "DrmInfo() PSSH: " + arrToHex(pssh));
            mMapPssh = parsePSSH(pssh, pssh.length);
            Log.v(TAG, "DrmInfo() PSSH: " + mMapPssh);

            int supportedDRMsCount = in.next().getInt32Value();
            mSupportedSchemes = new UUID[supportedDRMsCount];
            for (int i = 0; i < supportedDRMsCount; i++) {
                byte[] uuid = new byte[16];
                in.next().getBytesValue().copyTo(uuid, 0);

                mSupportedSchemes[i] = bytesToUUID(uuid);

                Log.v(TAG, "DrmInfo() supportedScheme[" + i + "]: " + mSupportedSchemes[i]);
            }

            Log.v(TAG, "DrmInfo() psshsize: " + pssh.length
                    + " supportedDRMsCount: " + supportedDRMsCount);
        }

        private DrmInfo makeCopy() {
            return new DrmInfo(this.mMapPssh, this.mSupportedSchemes);
        }

        private String arrToHex(byte[] bytes) {
            String out = "0x";
            for (int i = 0; i < bytes.length; i++) {
                out += String.format("%02x", bytes[i]);
            }

            return out;
        }

        private UUID bytesToUUID(byte[] uuid) {
            long msb = 0, lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb |= (((long) uuid[i]     & 0xff) << (8 * (7 - i)));
                lsb |= (((long) uuid[i + 8] & 0xff) << (8 * (7 - i)));
            }

            return new UUID(msb, lsb);
        }

        private Map<UUID, byte[]> parsePSSH(byte[] pssh, int psshsize) {
            Map<UUID, byte[]> result = new HashMap<UUID, byte[]>();

            final int uuidSize = 16;
            final int dataLenSize = 4;

            int len = psshsize;
            int numentries = 0;
            int i = 0;

            while (len > 0) {
                if (len < uuidSize) {
                    Log.w(TAG, String.format("parsePSSH: len is too short to parse "
                                             + "UUID: (%d < 16) pssh: %d", len, psshsize));
                    return null;
                }

                byte[] subset = Arrays.copyOfRange(pssh, i, i + uuidSize);
                UUID uuid = bytesToUUID(subset);
                i += uuidSize;
                len -= uuidSize;

                // get data length
                if (len < 4) {
                    Log.w(TAG, String.format("parsePSSH: len is too short to parse "
                                             + "datalen: (%d < 4) pssh: %d", len, psshsize));
                    return null;
                }

                subset = Arrays.copyOfRange(pssh, i, i + dataLenSize);
                int datalen = (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN)
                        ? ((subset[3] & 0xff) << 24) | ((subset[2] & 0xff) << 16)
                        | ((subset[1] & 0xff) <<  8) |  (subset[0] & 0xff)        :
                        ((subset[0] & 0xff) << 24) | ((subset[1] & 0xff) << 16)
                        | ((subset[2] & 0xff) <<  8) |  (subset[3] & 0xff);
                i += dataLenSize;
                len -= dataLenSize;

                if (len < datalen) {
                    Log.w(TAG, String.format("parsePSSH: len is too short to parse "
                                             + "data: (%d < %d) pssh: %d", len, datalen, psshsize));
                    return null;
                }

                byte[] data = Arrays.copyOfRange(pssh, i, i + datalen);

                // skip the data
                i += datalen;
                len -= datalen;

                Log.v(TAG, String.format("parsePSSH[%d]: <%s, %s> pssh: %d",
                                         numentries, uuid, arrToHex(data), psshsize));
                numentries++;
                result.put(uuid, data);
            }

            return result;
        }
    };  // DrmInfo

    /**
     * Thrown when a DRM method is called before preparing a DRM scheme through prepareDrm().
     * Extends MediaDrm.MediaDrmException
     */
    public static final class NoDrmSchemeException extends MediaDrmException {
        public NoDrmSchemeException(String detailMessage) {
            super(detailMessage);
        }
    }

    private native void native_prepareDrm(@NonNull byte[] uuid, @NonNull byte[] drmSessionId);

    // Modular DRM helpers

    private void prepareDrm_createDrmStep(@NonNull UUID uuid)
            throws UnsupportedSchemeException {
        Log.v(TAG, "prepareDrm_createDrmStep: UUID: " + uuid);

        try {
            mDrmObj = new MediaDrm(uuid);
            Log.v(TAG, "prepareDrm_createDrmStep: Created mDrmObj=" + mDrmObj);
        } catch (Exception e) { // UnsupportedSchemeException
            Log.e(TAG, "prepareDrm_createDrmStep: MediaDrm failed with " + e);
            throw e;
        }
    }

    private void prepareDrm_openSessionStep(@NonNull UUID uuid)
            throws NotProvisionedException, ResourceBusyException {
        Log.v(TAG, "prepareDrm_openSessionStep: uuid: " + uuid);

        // TODO: don't need an open session for a future specialKeyReleaseDrm mode but we should do
        // it anyway so it raises provisioning error if needed. We'd rather handle provisioning
        // at prepareDrm/openSession rather than getDrmKeyRequest/provideDrmKeyResponse
        try {
            mDrmSessionId = mDrmObj.openSession();
            Log.v(TAG, "prepareDrm_openSessionStep: mDrmSessionId=" + mDrmSessionId);

            // Sending it down to native/mediaserver to create the crypto object
            // This call could simply fail due to bad player state, e.g., after play().
            native_prepareDrm(getByteArrayFromUUID(uuid), mDrmSessionId);
            Log.v(TAG, "prepareDrm_openSessionStep: native_prepareDrm/Crypto succeeded");

        } catch (Exception e) { //ResourceBusyException, NotProvisionedException
            Log.e(TAG, "prepareDrm_openSessionStep: open/crypto failed with " + e);
            throw e;
        }
    }

    // Instantiated from the native side
    @SuppressWarnings("unused")
    private static class StreamEventCallback extends AudioTrack.StreamEventCallback {
        public long mJAudioTrackPtr;
        public long mNativeCallbackPtr;
        public long mUserDataPtr;

        StreamEventCallback(long jAudioTrackPtr, long nativeCallbackPtr, long userDataPtr) {
            super();
            mJAudioTrackPtr = jAudioTrackPtr;
            mNativeCallbackPtr = nativeCallbackPtr;
            mUserDataPtr = userDataPtr;
        }

        @Override
        public void onTearDown(AudioTrack track) {
            native_stream_event_onTearDown(mNativeCallbackPtr, mUserDataPtr);
        }

        @Override
        public void onPresentationEnded(AudioTrack track) {
            native_stream_event_onStreamPresentationEnd(mNativeCallbackPtr, mUserDataPtr);
        }

        @Override
        public void onDataRequest(AudioTrack track, int size) {
            native_stream_event_onStreamDataRequest(
                    mJAudioTrackPtr, mNativeCallbackPtr, mUserDataPtr);
        }
    }

    private class ProvisioningThread extends Thread {
        public static final int TIMEOUT_MS = 60000;

        private UUID mUuid;
        private String mUrlStr;
        private Object mDrmLock;
        private MediaPlayer2 mMediaPlayer;
        private int mStatus;
        public  int status() {
            return mStatus;
        }

        public ProvisioningThread initialize(MediaDrm.ProvisionRequest request,
                                          UUID uuid, MediaPlayer2 mediaPlayer) {
            // lock is held by the caller
            mDrmLock = mediaPlayer.mDrmLock;
            this.mMediaPlayer = mediaPlayer;

            mUrlStr = request.getDefaultUrl() + "&signedRequest=" + new String(request.getData());
            this.mUuid = uuid;

            mStatus = PREPARE_DRM_STATUS_PREPARATION_ERROR;

            Log.v(TAG, "handleProvisioninig: Thread is initialised url: " + mUrlStr);
            return this;
        }

        public void run() {

            byte[] response = null;
            boolean provisioningSucceeded = false;
            try {
                URL url = new URL(mUrlStr);
                final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                try {
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(false);
                    connection.setDoInput(true);
                    connection.setConnectTimeout(TIMEOUT_MS);
                    connection.setReadTimeout(TIMEOUT_MS);

                    connection.connect();
                    response = readInputStreamFully(connection.getInputStream());

                    Log.v(TAG, "handleProvisioninig: Thread run: response "
                            + response.length + " " + response);
                } catch (Exception e) {
                    mStatus = PREPARE_DRM_STATUS_PROVISIONING_NETWORK_ERROR;
                    Log.w(TAG, "handleProvisioninig: Thread run: connect " + e + " url: " + url);
                } finally {
                    connection.disconnect();
                }
            } catch (Exception e)   {
                mStatus = PREPARE_DRM_STATUS_PROVISIONING_NETWORK_ERROR;
                Log.w(TAG, "handleProvisioninig: Thread run: openConnection " + e);
            }

            if (response != null) {
                try {
                    mDrmObj.provideProvisionResponse(response);
                    Log.v(TAG, "handleProvisioninig: Thread run: "
                            + "provideProvisionResponse SUCCEEDED!");

                    provisioningSucceeded = true;
                } catch (Exception e) {
                    mStatus = PREPARE_DRM_STATUS_PROVISIONING_SERVER_ERROR;
                    Log.w(TAG, "handleProvisioninig: Thread run: "
                            + "provideProvisionResponse " + e);
                }
            }

            boolean succeeded = false;

            synchronized (mDrmLock) {
                // continuing with prepareDrm
                if (provisioningSucceeded) {
                    succeeded = mMediaPlayer.resumePrepareDrm(mUuid);
                    mStatus = (succeeded)
                            ? PREPARE_DRM_STATUS_SUCCESS :
                            PREPARE_DRM_STATUS_PREPARATION_ERROR;
                }
                mMediaPlayer.mDrmProvisioningInProgress = false;
                mMediaPlayer.mPrepareDrmInProgress = false;
                if (!succeeded) {
                    cleanDrmObj();  // cleaning up if it hasn't gone through while in the lock
                }
            }  // synchronized

            // calling the callback outside the lock
            sendDrmEvent(new DrmEventNotifier() {
                @Override
                public void notify(DrmEventCallback callback) {
                    callback.onDrmPrepared(
                            mMediaPlayer, getCurrentDataSource(), mStatus);
                }
            });

            synchronized (mTaskLock) {
                if (mCurrentTask != null
                        && mCurrentTask.mMediaCallType == CALL_COMPLETED_PREPARE_DRM
                        && mCurrentTask.mNeedToWaitForEventToComplete) {
                    mCurrentTask = null;
                    processPendingTask_l();
                }
            }
        }

        /**
         * Returns a byte[] containing the remainder of 'in', closing it when done.
         */
        private byte[] readInputStreamFully(InputStream in) throws IOException {
            try {
                return readInputStreamFullyNoClose(in);
            } finally {
                in.close();
            }
        }

        /**
         * Returns a byte[] containing the remainder of 'in'.
         */
        private byte[] readInputStreamFullyNoClose(InputStream in) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }  // ProvisioningThread

    private int handleProvisioninig(UUID uuid) {
        synchronized (mDrmLock) {
            if (mDrmProvisioningInProgress) {
                Log.e(TAG, "handleProvisioninig: Unexpected mDrmProvisioningInProgress");
                return PREPARE_DRM_STATUS_PREPARATION_ERROR;
            }

            MediaDrm.ProvisionRequest provReq = mDrmObj.getProvisionRequest();
            if (provReq == null) {
                Log.e(TAG, "handleProvisioninig: getProvisionRequest returned null.");
                return PREPARE_DRM_STATUS_PREPARATION_ERROR;
            }

            Log.v(TAG, "handleProvisioninig provReq "
                    + " data: " + provReq.getData() + " url: " + provReq.getDefaultUrl());

            // networking in a background thread
            mDrmProvisioningInProgress = true;

            mDrmProvisioningThread = new ProvisioningThread().initialize(provReq, uuid, this);
            mDrmProvisioningThread.start();

            return PREPARE_DRM_STATUS_SUCCESS;
        }
    }

    private boolean resumePrepareDrm(UUID uuid) {
        Log.v(TAG, "resumePrepareDrm: uuid: " + uuid);

        // mDrmLock is guaranteed to be held
        boolean success = false;
        try {
            // resuming
            prepareDrm_openSessionStep(uuid);

            mDrmUUID = uuid;
            mActiveDrmScheme = true;

            success = true;
        } catch (Exception e) {
            Log.w(TAG, "handleProvisioninig: Thread run native_prepareDrm resume failed with " + e);
            // mDrmObj clean up is done by the caller
        }

        return success;
    }

    private void resetDrmState() {
        synchronized (mDrmLock) {
            Log.v(TAG, "resetDrmState:"
                    + " mDrmInfo=" + mDrmInfo
                    + " mDrmProvisioningThread=" + mDrmProvisioningThread
                    + " mPrepareDrmInProgress=" + mPrepareDrmInProgress
                    + " mActiveDrmScheme=" + mActiveDrmScheme);

            mDrmInfoResolved = false;
            mDrmInfo = null;

            if (mDrmProvisioningThread != null) {
                // timeout; relying on HttpUrlConnection
                try {
                    mDrmProvisioningThread.join();
                } catch (InterruptedException e) {
                    Log.w(TAG, "resetDrmState: ProvThread.join Exception " + e);
                }
                mDrmProvisioningThread = null;
            }

            mPrepareDrmInProgress = false;
            mActiveDrmScheme = false;

            cleanDrmObj();
        }  // synchronized
    }

    private void cleanDrmObj() {
        // the caller holds mDrmLock
        Log.v(TAG, "cleanDrmObj: mDrmObj=" + mDrmObj + " mDrmSessionId=" + mDrmSessionId);

        if (mDrmSessionId != null)    {
            mDrmObj.closeSession(mDrmSessionId);
            mDrmSessionId = null;
        }
        if (mDrmObj != null) {
            mDrmObj.release();
            mDrmObj = null;
        }
    }

    private static byte[] getByteArrayFromUUID(@NonNull UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();

        byte[] uuidBytes = new byte[16];
        for (int i = 0; i < 8; ++i) {
            uuidBytes[i] = (byte) (msb >>> (8 * (7 - i)));
            uuidBytes[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
        }

        return uuidBytes;
    }

    // Modular DRM end

    private static class TimedTextUtil {
        // These keys must be in sync with the keys in TextDescription2.h
        private static final int KEY_START_TIME                     = 7; // int
        private static final int KEY_STRUCT_TEXT_POS               = 14; // TextPos
        private static final int KEY_STRUCT_TEXT                   = 16; // Text
        private static final int KEY_GLOBAL_SETTING               = 101;
        private static final int KEY_LOCAL_SETTING                = 102;

        private static TimedText parsePlayerMessage(PlayerMessage playerMsg) {
            if (playerMsg.getValuesCount() == 0) {
                return null;
            }

            String textChars = null;
            Rect textBounds = null;
            Iterator<Value> in = playerMsg.getValuesList().iterator();
            int type = in.next().getInt32Value();
            if (type == KEY_LOCAL_SETTING) {
                type = in.next().getInt32Value();
                if (type != KEY_START_TIME) {
                    return null;
                }
                int startTimeMs = in.next().getInt32Value();

                type = in.next().getInt32Value();
                if (type != KEY_STRUCT_TEXT) {
                    return null;
                }

                byte[] text = in.next().getBytesValue().toByteArray();
                if (text == null || text.length == 0) {
                    textChars = null;
                } else {
                    textChars = new String(text);
                }

            } else if (type != KEY_GLOBAL_SETTING) {
                Log.w(TAG, "Invalid timed text key found: " + type);
                return null;
            }
            if (in.hasNext()) {
                type = in.next().getInt32Value();
                if (type == KEY_STRUCT_TEXT_POS) {
                    int top = in.next().getInt32Value();
                    int left = in.next().getInt32Value();
                    int bottom = in.next().getInt32Value();
                    int right = in.next().getInt32Value();
                    textBounds = new Rect(left, top, right, bottom);
                }
            }
            return new TimedText(textChars, textBounds);
        }
    }

    private Object addTask(Task task) {
        synchronized (mTaskLock) {
            mPendingTasks.add(task);
            processPendingTask_l();
        }
        return task;
    }

    @GuardedBy("mTaskLock")
    private void processPendingTask_l() {
        if (mCurrentTask != null) {
            return;
        }
        if (!mPendingTasks.isEmpty()) {
            Task task = mPendingTasks.remove(0);
            mCurrentTask = task;
            mTaskHandler.post(task);
        }
    }

    private abstract class Task implements Runnable {
        private final int mMediaCallType;
        private final boolean mNeedToWaitForEventToComplete;
        private DataSourceDesc mDSD;

        Task(int mediaCallType, boolean needToWaitForEventToComplete) {
            mMediaCallType = mediaCallType;
            mNeedToWaitForEventToComplete = needToWaitForEventToComplete;
        }

        abstract void process() throws IOException, NoDrmSchemeException;

        @Override
        public void run() {
            int status = CALL_STATUS_NO_ERROR;
            try {
                if (mMediaCallType != CALL_COMPLETED_NOTIFY_WHEN_COMMAND_LABEL_REACHED
                        && getState() == PLAYER_STATE_ERROR) {
                    status = CALL_STATUS_INVALID_OPERATION;
                } else {
                    if (mMediaCallType == CALL_COMPLETED_SEEK_TO) {
                        synchronized (mTaskLock) {
                            if (!mPendingTasks.isEmpty()) {
                                Task nextTask = mPendingTasks.get(0);
                                if (nextTask.mMediaCallType == mMediaCallType) {
                                    throw new CommandSkippedException(
                                            "consecutive seekTo is skipped except last one");
                                }
                            }
                        }
                    }
                    process();
                }
            } catch (IllegalStateException e) {
                status = CALL_STATUS_INVALID_OPERATION;
            } catch (IllegalArgumentException e) {
                status = CALL_STATUS_BAD_VALUE;
            } catch (SecurityException e) {
                status = CALL_STATUS_PERMISSION_DENIED;
            } catch (IOException e) {
                status = CALL_STATUS_ERROR_IO;
            } catch (NoDrmSchemeException e) {
                status = CALL_STATUS_NO_DRM_SCHEME;
            } catch (CommandSkippedException e) {
                status = CALL_STATUS_SKIPPED;
            } catch (Exception e) {
                status = CALL_STATUS_ERROR_UNKNOWN;
            }
            mDSD = getCurrentDataSource();

            if (mMediaCallType != CALL_COMPLETED_SEEK_TO) {
                synchronized (mTaskLock) {
                    mIsPreviousCommandSeekTo = false;
                }
            }

            // TODO: Make native implementations asynchronous and let them send notifications.
            if (!mNeedToWaitForEventToComplete || status != CALL_STATUS_NO_ERROR) {

                sendCompleteNotification(status);

                synchronized (mTaskLock) {
                    mCurrentTask = null;
                    processPendingTask_l();
                }
            }
        }

        private void sendCompleteNotification(int status) {
            // In {@link #notifyWhenCommandLabelReached} case, a separate callback
            // {@link #onCommandLabelReached} is already called in {@code process()}.
            // CALL_COMPLETED_PREPARE_DRM is sent via DrmEventCallback#onDrmPrepared
            if (mMediaCallType == CALL_COMPLETED_NOTIFY_WHEN_COMMAND_LABEL_REACHED
                    || mMediaCallType == CALL_COMPLETED_PREPARE_DRM) {
                return;
            }
            sendEvent(new EventNotifier() {
                @Override
                public void notify(EventCallback callback) {
                    callback.onCallCompleted(
                            MediaPlayer2.this, mDSD, mMediaCallType, status);
                }
            });
        }
    };

    private final class CommandSkippedException extends RuntimeException {
        CommandSkippedException(String detailMessage) {
            super(detailMessage);
        }
    };

    private final class SourceInfo {
        final DataSourceDesc mDSD;
        final long mId = mSrcIdGenerator.getAndIncrement();
        AtomicInteger mBufferedPercentage = new AtomicInteger(0);

        // m*AsNextSource (below) only applies to pending data sources in the playlist;
        // the meanings of mCurrentSourceInfo.{mStateAsNextSource,mPlayPendingAsNextSource}
        // are undefined.
        int mStateAsNextSource = NEXT_SOURCE_STATE_INIT;
        boolean mPlayPendingAsNextSource = false;

        SourceInfo(DataSourceDesc dsd) {
            this.mDSD = dsd;
        }

        @Override
        public String toString() {
            return String.format("%s(%d)", SourceInfo.class.getName(), mId);
        }

    }

    private SourceInfo getSourceInfoById(long srcId) {
        synchronized (mSrcLock) {
            if (isCurrentSource(srcId)) {
                return mCurrentSourceInfo;
            }
            if (isNextSource(srcId)) {
                return mNextSourceInfos.peek();
            }
        }
        return null;
    }

    private boolean isCurrentSource(long srcId) {
        synchronized (mSrcLock) {
            return mCurrentSourceInfo != null && mCurrentSourceInfo.mId == srcId;
        }
    }

    private boolean isNextSource(long srcId) {
        SourceInfo nextSourceInfo = mNextSourceInfos.peek();
        return nextSourceInfo != null && nextSourceInfo.mId == srcId;
    }

    public static final class MetricsConstants {
        private MetricsConstants() {}

        /**
         * Key to extract the MIME type of the video track
         * from the {@link MediaPlayer2#getMetrics} return value.
         * The value is a String.
         */
        public static final String MIME_TYPE_VIDEO = "android.media.mediaplayer.video.mime";

        /**
         * Key to extract the codec being used to decode the video track
         * from the {@link MediaPlayer2#getMetrics} return value.
         * The value is a String.
         */
        public static final String CODEC_VIDEO = "android.media.mediaplayer.video.codec";

        /**
         * Key to extract the width (in pixels) of the video track
         * from the {@link MediaPlayer2#getMetrics} return value.
         * The value is an integer.
         */
        public static final String WIDTH = "android.media.mediaplayer.width";

        /**
         * Key to extract the height (in pixels) of the video track
         * from the {@link MediaPlayer2#getMetrics} return value.
         * The value is an integer.
         */
        public static final String HEIGHT = "android.media.mediaplayer.height";

        /**
         * Key to extract the count of video frames played
         * from the {@link MediaPlayer2#getMetrics} return value.
         * The value is an integer.
         */
        public static final String FRAMES = "android.media.mediaplayer.frames";

        /**
         * Key to extract the count of video frames dropped
         * from the {@link MediaPlayer2#getMetrics} return value.
         * The value is an integer.
         */
        public static final String FRAMES_DROPPED = "android.media.mediaplayer.dropped";

        /**
         * Key to extract the MIME type of the audio track
         * from the {@link MediaPlayer2#getMetrics} return value.
         * The value is a String.
         */
        public static final String MIME_TYPE_AUDIO = "android.media.mediaplayer.audio.mime";

        /**
         * Key to extract the codec being used to decode the audio track
         * from the {@link MediaPlayer2#getMetrics} return value.
         * The value is a String.
         */
        public static final String CODEC_AUDIO = "android.media.mediaplayer.audio.codec";

        /**
         * Key to extract the duration (in milliseconds) of the
         * media being played
         * from the {@link MediaPlayer2#getMetrics} return value.
         * The value is a long.
         */
        public static final String DURATION = "android.media.mediaplayer.durationMs";

        /**
         * Key to extract the playing time (in milliseconds) of the
         * media being played
         * from the {@link MediaPlayer2#getMetrics} return value.
         * The value is a long.
         */
        public static final String PLAYING = "android.media.mediaplayer.playingMs";

        /**
         * Key to extract the count of errors encountered while
         * playing the media
         * from the {@link MediaPlayer2#getMetrics} return value.
         * The value is an integer.
         */
        public static final String ERRORS = "android.media.mediaplayer.err";

        /**
         * Key to extract an (optional) error code detected while
         * playing the media
         * from the {@link MediaPlayer2#getMetrics} return value.
         * The value is an integer.
         */
        public static final String ERROR_CODE = "android.media.mediaplayer.errcode";

    }

    private void keepAudioSessionIdAlive(int sessionId) {
        synchronized (mSessionIdLock) {
            if (mDummyAudioTrack != null) {
                if (mDummyAudioTrack.getAudioSessionId() == sessionId) {
                    return;
                }
                mDummyAudioTrack.release();
            }
            // TODO: parameters can be optimized
            mDummyAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 44100,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, 2,
                    AudioTrack.MODE_STATIC, sessionId);
        }
    }
}
