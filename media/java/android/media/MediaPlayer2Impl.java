/*
 * Copyright 2018 The Android Open Source Project
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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Rect;
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


import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @hide
 */
public final class MediaPlayer2Impl extends MediaPlayer2 {
    static {
        System.loadLibrary("media2_jni");
        native_init();
    }

    private static final int NEXT_SOURCE_STATE_ERROR = -1;
    private static final int NEXT_SOURCE_STATE_INIT = 0;
    private static final int NEXT_SOURCE_STATE_PREPARING = 1;
    private static final int NEXT_SOURCE_STATE_PREPARED = 2;

    private final static String TAG = "MediaPlayer2Impl";

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
    private long mSrcIdGenerator = 0;
    private DataSourceDesc mCurrentDSD;
    private long mCurrentSrcId = mSrcIdGenerator++;
    private List<DataSourceDesc> mNextDSDs;
    private long mNextSrcId = mSrcIdGenerator++;
    private int mNextSourceState = NEXT_SOURCE_STATE_INIT;
    private boolean mNextSourcePlayPending = false;
    //--- guarded by |mSrcLock| end

    private AtomicInteger mBufferedPercentageCurrent = new AtomicInteger(0);
    private AtomicInteger mBufferedPercentageNext = new AtomicInteger(0);
    private volatile float mVolume = 1.0f;
    private VideoSize mVideoSize = new VideoSize(0, 0);

    // Modular DRM
    private final Object mDrmLock = new Object();
    //--- guarded by |mDrmLock| start
    private UUID mDrmUUID;
    private DrmInfoImpl mDrmInfoImpl;
    private MediaDrm mDrmObj;
    private byte[] mDrmSessionId;
    private boolean mDrmInfoResolved;
    private boolean mActiveDrmScheme;
    private boolean mDrmConfigAllowed;
    private boolean mDrmProvisioningInProgress;
    private boolean mPrepareDrmInProgress;
    private ProvisioningThread mDrmProvisioningThread;
    //--- guarded by |mDrmLock| end

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

    /**
     * Default constructor.
     * <p>When done with the MediaPlayer2Impl, you should call {@link #close()},
     * to free the resources. If not released, too many MediaPlayer2Impl instances may
     * result in an exception.</p>
     */
    public MediaPlayer2Impl(Context context) {
        mContext = context;
        mHandlerThread = new HandlerThread("MediaPlayer2TaskThread");
        mHandlerThread.start();
        Looper looper = mHandlerThread.getLooper();
        mTaskHandler = new TaskHandler(this, looper);

        /* Native setup requires a weak reference to our object.
         * It's easier to create it here than in C++.
         */
        native_setup(new WeakReference<MediaPlayer2Impl>(this));
    }

    @Override
    public void close() {
        super.close();
        release();
    }

    @Override
    public Object play() {
        return addTask(new Task(CALL_COMPLETED_PLAY, false) {
            @Override
            void process() {
                stayAwake(true);
                _start();
            }
        });
    }

    private native void _start() throws IllegalStateException;

    @Override
    public Object prepare() {
        return addTask(new Task(CALL_COMPLETED_PREPARE, true) {
            @Override
            void process() {
                _prepare();
            }
        });
    }

    public native void _prepare();

    @Override
    public Object pause() {
        return addTask(new Task(CALL_COMPLETED_PAUSE, false) {
            @Override
            void process() {
                stayAwake(false);

                _pause();
            }
        });
    }

    private native void _pause() throws IllegalStateException;

    @Override
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

    @Override
    public native long getCurrentPosition();

    @Override
    public native long getDuration();

    @Override
    public long getBufferedPosition() {
        // Use cached buffered percent for now.
        return getDuration() * mBufferedPercentageCurrent.get() / 100;
    }

    @Override
    public @MediaPlayer2State int getState() {
        return native_getState();
    }

    private native int native_getState();

    @Override
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

    @Override
    public @NonNull AudioAttributes getAudioAttributes() {
        return native_getAudioAttributes();
    }

    @Override
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
                    mCurrentDSD = dsd;
                    mCurrentSrcId = mSrcIdGenerator++;
                    handleDataSource(true /* isCurrent */, dsd, mCurrentSrcId);
                }
            }
        });
    }

    @Override
    public Object setNextDataSource(@NonNull DataSourceDesc dsd) {
        return addTask(new Task(CALL_COMPLETED_SET_NEXT_DATA_SOURCE, false) {
            @Override
            void process() {
                checkArgument(dsd != null, "the DataSourceDesc cannot be null");
                synchronized (mSrcLock) {
                    mNextDSDs = new ArrayList<DataSourceDesc>(1);
                    mNextDSDs.add(dsd);
                    mNextSrcId = mSrcIdGenerator++;
                    mNextSourceState = NEXT_SOURCE_STATE_INIT;
                }
                prepareNextDataSource();
            }
        });
    }

    @Override
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
                    mNextDSDs = new ArrayList(dsds);
                    mNextSrcId = mSrcIdGenerator++;
                    mNextSourceState = NEXT_SOURCE_STATE_INIT;
                }
                prepareNextDataSource();
            }
        });
    }

    @Override
    public Object clearNextDataSources() {
        return addTask(new Task(CALL_COMPLETED_CLEAR_NEXT_DATA_SOURCES, false) {
            @Override
            void process() {
                synchronized (mSrcLock) {
                    if (mNextDSDs != null) {
                        mNextDSDs.clear();
                        mNextDSDs = null;
                    }
                    mNextSrcId = mSrcIdGenerator++;
                    mNextSourceState = NEXT_SOURCE_STATE_INIT;
                }
            }
        });
    }

    @Override
    public @NonNull DataSourceDesc getCurrentDataSource() {
        synchronized (mSrcLock) {
            return mCurrentDSD;
        }
    }

    @Override
    public Object loopCurrent(boolean loop) {
        return addTask(new Task(CALL_COMPLETED_LOOP_CURRENT, false) {
            @Override
            void process() {
                setLooping(loop);
            }
        });
    }

    private native void setLooping(boolean looping);

    @Override
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

    @Override
    public float getPlayerVolume() {
        return mVolume;
    }

    @Override
    public float getMaxPlayerVolume() {
        return 1.0f;
    }

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

    @Override
    public Object notifyWhenCommandLabelReached(Object label) {
        return addTask(new Task(CALL_COMPLETED_NOTIFY_WHEN_COMMAND_LABEL_REACHED, false) {
            @Override
            void process() {
                sendEvent(new EventNotifier() {
                    @Override
                    public void notify(EventCallback callback) {
                        callback.onCommandLabelReached(
                                MediaPlayer2Impl.this, label);
                    }
                });
            }
        });
    }

    @Override
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

    @Override
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

    @Override
    public boolean cancelCommand(Object token) {
        synchronized (mTaskLock) {
            return mPendingTasks.remove(token);
        }
    }

    @Override
    public void clearPendingCommands() {
        synchronized (mTaskLock) {
            mPendingTasks.clear();
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
        if (Looper.myLooper() != mHandlerThread.getLooper()) {
            Log.e(TAG, "prepareNextDataSource: called on wrong looper");
        }

        boolean hasNextDSD;
        synchronized (mSrcLock) {
            hasNextDSD = (mNextDSDs != null && !mNextDSDs.isEmpty());
        }

        int state = getState();
        if (state == PLAYER_STATE_ERROR || state == PLAYER_STATE_IDLE) {
            // Current source has not been prepared yet.
            return hasNextDSD;
        }

        synchronized (mSrcLock) {
            if (!hasNextDSD || mNextSourceState != NEXT_SOURCE_STATE_INIT) {
                // There is no next source or it's in preparing or prepared state.
                return hasNextDSD;
            }

            try {
                mNextSourceState = NEXT_SOURCE_STATE_PREPARING;
                handleDataSource(false /* isCurrent */, mNextDSDs.get(0), mNextSrcId);
            } catch (Exception e) {
                Message msg = mTaskHandler.obtainMessage(
                        MEDIA_ERROR, MEDIA_ERROR_IO, MEDIA_ERROR_UNKNOWN, null);
                mTaskHandler.handleMessage(msg, mNextSrcId);

                mNextDSDs.remove(0);
                // make a new SrcId to obsolete notification for previous one.
                mNextSrcId = mSrcIdGenerator++;
                mNextSourceState = NEXT_SOURCE_STATE_INIT;
                return prepareNextDataSource();
            }
        }
        return hasNextDSD;
    }

    // This function should be always called on |mHandlerThread|.
    private void playNextDataSource() {
        if (Looper.myLooper() != mHandlerThread.getLooper()) {
            Log.e(TAG, "playNextDataSource: called on wrong looper");
        }

        boolean hasNextDSD = false;
        synchronized (mSrcLock) {
            if (mNextDSDs != null && !mNextDSDs.isEmpty()) {
                hasNextDSD = true;
                if (mNextSourceState == NEXT_SOURCE_STATE_PREPARED) {
                    // Switch to next source only when it has been prepared.
                    mCurrentDSD = mNextDSDs.get(0);
                    mCurrentSrcId = mNextSrcId;
                    mBufferedPercentageCurrent.set(mBufferedPercentageNext.get());
                    mNextDSDs.remove(0);
                    mNextSrcId = mSrcIdGenerator++;  // make it different from |mCurrentSrcId|
                    mBufferedPercentageNext.set(0);
                    mNextSourceState = NEXT_SOURCE_STATE_INIT;

                    long srcId = mCurrentSrcId;
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
                        mNextSourcePlayPending = false;
                    }
                } else if (mNextSourceState == NEXT_SOURCE_STATE_INIT) {
                    hasNextDSD = prepareNextDataSource();
                }
            }
        }

        if (!hasNextDSD) {
            sendEvent(new EventNotifier() {
                @Override
                public void notify(EventCallback callback) {
                    callback.onInfo(
                            MediaPlayer2Impl.this, null, MEDIA_INFO_DATA_SOURCE_LIST_END, 0);
                }
            });
        }
    }

    private native void nativePlayNextDataSource(long srcId);

    //--------------------------------------------------------------------------
    // Explicit Routing
    //--------------------
    private AudioDeviceInfo mPreferredDevice = null;

    @Override
    public boolean setPreferredDevice(AudioDeviceInfo deviceInfo) {
        boolean status = native_setPreferredDevice(deviceInfo);
        if (status == true) {
            synchronized (this) {
                mPreferredDevice = deviceInfo;
            }
        }
        return status;
    }

    @Override
    public AudioDeviceInfo getPreferredDevice() {
        synchronized (this) {
            return mPreferredDevice;
        }
    }

    @Override
    public native AudioDeviceInfo getRoutedDevice();

    @Override
    public void addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener,
            Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("addOnRoutingChangedListener: listener is NULL");
        }
        RoutingDelegate routingDelegate = new RoutingDelegate(this, listener, handler);
        native_addDeviceCallback(routingDelegate);
    }

    @Override
    public void removeOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("removeOnRoutingChangedListener: listener is NULL");
        }
        native_removeDeviceCallback(listener);
    }

    private native boolean native_setPreferredDevice(AudioDeviceInfo device);
    private native void native_addDeviceCallback(RoutingDelegate rd);
    private native void native_removeDeviceCallback(
            AudioRouting.OnRoutingChangedListener listener);

    @Override
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

    @Override
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

    @Override
    public VideoSize getVideoSize() {
        return mVideoSize;
    }

    @Override
    public PersistableBundle getMetrics() {
        PersistableBundle bundle = native_getMetrics();
        return bundle;
    }

    private native PersistableBundle native_getMetrics();

    @Override
    @NonNull
    native BufferingParams getBufferingParams();

    @Override
    Object setBufferingParams(@NonNull BufferingParams params) {
        return addTask(new Task(CALL_COMPLETED_SET_BUFFERING_PARAMS, false) {
            @Override
            void process() {
                checkArgument(params != null, "the BufferingParams cannot be null");
                _setBufferingParams(params);
            }
        });
    }

    private native void _setBufferingParams(@NonNull BufferingParams params);

    @Override
    public Object setPlaybackParams(@NonNull PlaybackParams params) {
        return addTask(new Task(CALL_COMPLETED_SET_PLAYBACK_PARAMS, false) {
            @Override
            void process() {
                checkArgument(params != null, "the PlaybackParams cannot be null");
                _setPlaybackParams(params);
            }
        });
    }

    private native void _setPlaybackParams(@NonNull PlaybackParams params);

    @Override
    @NonNull
    public native PlaybackParams getPlaybackParams();

    @Override
    public Object setSyncParams(@NonNull SyncParams params) {
        return addTask(new Task(CALL_COMPLETED_SET_SYNC_PARAMS, false) {
            @Override
            void process() {
                checkArgument(params != null, "the SyncParams cannot be null");
                _setSyncParams(params);
            }
        });
    }

    private native void _setSyncParams(@NonNull SyncParams params);

    @Override
    @NonNull
    public native SyncParams getSyncParams();

    @Override
    public Object seekTo(final long msec, @SeekMode int mode) {
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

                _seekTo(posMs, mode);

                synchronized (mTaskLock) {
                    mIsPreviousCommandSeekTo = true;
                    mPreviousSeekPos = posMs;
                    mPreviousSeekMode = mode;
                }
            }
        });
    }

    private native final void _seekTo(long msec, int mode);

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
    @Override
    @Nullable
    public MediaTimestamp getTimestamp()
    {
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
     * Resets the MediaPlayer2 to its uninitialized state. After calling
     * this method, you will have to initialize it again by setting the
     * data source and calling prepare().
     */
    @Override
    public void reset() {
        synchronized (mEventCbLock) {
            mEventCallbackRecords.clear();
        }
        synchronized (mDrmEventCbLock) {
            mDrmEventCallbackRecords.clear();
        }
        synchronized (mSrcLock) {
            if (mNextDSDs != null) {
                mNextDSDs.clear();
                mNextDSDs = null;
            }
            mNextSrcId = mSrcIdGenerator++;
            mNextSourceState = NEXT_SOURCE_STATE_INIT;
        }

        synchronized (mTaskLock) {
            mPendingTasks.clear();
            mIsPreviousCommandSeekTo = false;
        }

        stayAwake(false);
        _reset();
        // make sure none of the listeners get called anymore
        if (mTaskHandler != null) {
            mTaskHandler.removeCallbacksAndMessages(null);
        }

        resetDrmState();
    }

    private native void _reset();

    // Keep KEY_PARAMETER_* in sync with include/media/mediaplayer2.h
    private final static int KEY_PARAMETER_AUDIO_ATTRIBUTES = 1400;
    /**
     * Sets the audio attributes.
     * @param value value of the parameter to be set.
     * @return true if the parameter is set successfully, false otherwise
     */
    private native boolean native_setAudioAttributes(AudioAttributes audioAttributes);
    private native AudioAttributes native_getAudioAttributes();


    /**
     * Checks whether the MediaPlayer2 is looping or non-looping.
     *
     * @return true if the MediaPlayer2 is currently looping, false otherwise
     * @hide
     */
    @Override
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
     * This method must be called before one of the overloaded <code> setDataSource </code> methods.
     * @throws IllegalStateException if it is called in an invalid state
     * @throws IllegalArgumentException if the sessionId is invalid.
     */
    @Override
    public Object setAudioSessionId(int sessionId) {
        return addTask(new Task(CALL_COMPLETED_SET_AUDIO_SESSION_ID, false) {
            @Override
            void process() {
                _setAudioSessionId(sessionId);
            }
        });
    }

    private native void _setAudioSessionId(int sessionId);

    /**
     * Returns the audio session ID.
     *
     * @return the audio session ID. {@see #setAudioSessionId(int)}
     * Note that the audio session ID is 0 only if a problem occured when the MediaPlayer2 was contructed.
     */
    @Override
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
     */
    @Override
    public Object attachAuxEffect(int effectId) {
        return addTask(new Task(CALL_COMPLETED_ATTACH_AUX_EFFECT, false) {
            @Override
            void process() {
                _attachAuxEffect(effectId);
            }
        });
    }

    private native void _attachAuxEffect(int effectId);

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
     */
    @Override
    public Object setAuxEffectSendLevel(float level) {
        return addTask(new Task(CALL_COMPLETED_SET_AUX_EFFECT_SEND_LEVEL, false) {
            @Override
            void process() {
                _setAuxEffectSendLevel(level);
            }
        });
    }

    private native void _setAuxEffectSendLevel(float level);

    private static native final void native_init();
    private native final void native_setup(Object mediaplayer2_this);
    private native final void native_finalize();

    private static native final void native_stream_event_onTearDown(
            long nativeCallbackPtr, long userDataPtr);
    private static native final void native_stream_event_onStreamPresentationEnd(
            long nativeCallbackPtr, long userDataPtr);
    private static native final void native_stream_event_onStreamDataRequest(
            long jAudioTrackPtr, long nativeCallbackPtr, long userDataPtr);

    /**
     * Class for MediaPlayer2 to return each audio/video/subtitle track's metadata.
     *
     * @see android.media.MediaPlayer2#getTrackInfo
     */
    public static final class TrackInfoImpl extends TrackInfo {
        /**
         * Gets the track type.
         * @return TrackType which indicates if the track is video, audio, timed text.
         */
        @Override
        public int getTrackType() {
            return mTrackType;
        }

        /**
         * Gets the language code of the track.
         * @return a language code in either way of ISO-639-1 or ISO-639-2.
         * When the language is unknown or could not be determined,
         * ISO-639-2 language code, "und", is returned.
         */
        @Override
        public String getLanguage() {
            String language = mFormat.getString(MediaFormat.KEY_LANGUAGE);
            return language == null ? "und" : language;
        }

        /**
         * Gets the {@link MediaFormat} of the track.  If the format is
         * unknown or could not be determined, null is returned.
         */
        @Override
        public MediaFormat getFormat() {
            if (mTrackType == MEDIA_TRACK_TYPE_TIMEDTEXT
                    || mTrackType == MEDIA_TRACK_TYPE_SUBTITLE) {
                return mFormat;
            }
            return null;
        }

        final int mTrackType;
        final MediaFormat mFormat;

        TrackInfoImpl(Iterator<Value> in) {
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
        TrackInfoImpl(int type, MediaFormat format) {
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
    @Override
    public List<TrackInfo> getTrackInfo() {
        TrackInfoImpl trackInfo[] = getInbandTrackInfoImpl();
        return Arrays.asList(trackInfo);
    }

    private TrackInfoImpl[] getInbandTrackInfoImpl() throws IllegalStateException {
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
        TrackInfoImpl trackInfo[] = new TrackInfoImpl[size];
        for (int i = 0; i < size; ++i) {
            trackInfo[i] = new TrackInfoImpl(in);
        }
        return trackInfo;
    }

    /*
     * A helper function to check if the mime type is supported by media framework.
     */
    private static boolean availableMimeTypeForExternalSource(String mimeType) {
        if (MEDIA_MIMETYPE_TEXT_SUBRIP.equals(mimeType)) {
            return true;
        }
        return false;
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
    @Override
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
     * @throws IllegalStateException if called in an invalid state.
     *
     * @see android.media.MediaPlayer2#getTrackInfo
     */
    @Override
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
     * @throws IllegalStateException if called in an invalid state.
     *
     * @see android.media.MediaPlayer2#getTrackInfo
     */
    @Override
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
                            select? INVOKE_ID_SELECT_TRACK: INVOKE_ID_DESELECT_TRACK))
                .addValues(Value.newBuilder().setInt32Value(index))
                .build();
        invoke(request);
    }

    // Have to declare protected for finalize() since it is protected
    // in the base class Object.
    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        native_finalize();
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

        _release();
        mReleased = true;
    }

    private native void _release();

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
        private MediaPlayer2Impl mMediaPlayer;

        public TaskHandler(MediaPlayer2Impl mp, Looper looper) {
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

            final DataSourceDesc dsd;
            boolean isCurrentSrcId = false;
            boolean isNextSrcId = false;
            synchronized (mSrcLock) {
                if (srcId == mCurrentSrcId) {
                    dsd = mCurrentDSD;
                    isCurrentSrcId = true;
                } else if (mNextDSDs != null && !mNextDSDs.isEmpty() && srcId == mNextSrcId) {
                    dsd = mNextDSDs.get(0);
                    isNextSrcId = true;
                } else {
                    return;
                }
            }

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
                        Log.i(TAG, "MEDIA_PREPARED: srcId=" + srcId
                                + ", currentSrcId=" + mCurrentSrcId + ", nextSrcId=" + mNextSrcId);

                        if (isCurrentSrcId) {
                            prepareNextDataSource();
                        } else if (isNextSrcId) {
                            mNextSourceState = NEXT_SOURCE_STATE_PREPARED;
                            if (mNextSourcePlayPending) {
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
                        final DrmInfoImpl drmInfo;

                        synchronized (mDrmLock) {
                            if (mDrmInfoImpl != null) {
                                drmInfo = mDrmInfoImpl.makeCopy();
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
                    if (isCurrentSrcId) {
                        sendEvent(new EventNotifier() {
                            @Override
                            public void notify(EventCallback callback) {
                                callback.onInfo(
                                        mMediaPlayer, dsd, MEDIA_INFO_DATA_SOURCE_END, 0);
                            }
                        });
                        stayAwake(false);

                        synchronized (mSrcLock) {
                            mNextSourcePlayPending = true;

                            Log.i(TAG, "MEDIA_PLAYBACK_COMPLETE: srcId=" + srcId
                                    + ", currentSrcId=" + mCurrentSrcId
                                    + ", nextSrcId=" + mNextSrcId);
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

                    synchronized (mSrcLock) {
                        if (isCurrentSrcId) {
                            mBufferedPercentageCurrent.set(percent);
                        } else if (isNextSrcId) {
                            mBufferedPercentageNext.set(percent);
                        }
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
                        if (isCurrentSrcId) {
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
    private static void postEventFromNative(Object mediaplayer2_ref, long srcId,
                                            int what, int arg1, int arg2, byte[] obj)
    {
        final MediaPlayer2Impl mp = (MediaPlayer2Impl)((WeakReference)mediaplayer2_ref).get();
        if (mp == null) {
            return;
        }

        switch (what) {
        case MEDIA_DRM_INFO:
            // We need to derive mDrmInfoImpl before prepare() returns so processing it here
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
                DrmInfoImpl drmInfo = new DrmInfoImpl(playerMsg);
                synchronized (mp.mDrmLock) {
                    mp.mDrmInfoImpl = drmInfo;
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

    private final Object mEventCbLock = new Object();
    private ArrayList<Pair<Executor, EventCallback> > mEventCallbackRecords
        = new ArrayList<Pair<Executor, EventCallback> >();

    /**
     * Register a callback to be invoked when the media source is ready
     * for playback.
     *
     * @param eventCallback the callback that will be run
     * @param executor the executor through which the callback should be invoked
     */
    @Override
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
     * Clears the {@link EventCallback}.
     */
    @Override
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

    // Modular DRM begin

    /**
     * Register a callback to be invoked for configuration of the DRM object before
     * the session is created.
     * The callback will be invoked synchronously during the execution
     * of {@link #prepareDrm(UUID uuid)}.
     *
     * @param listener the callback that will be run
     */
    @Override
    public void setOnDrmConfigHelper(OnDrmConfigHelper listener)
    {
        synchronized (mDrmLock) {
            mOnDrmConfigHelper = listener;
        } // synchronized
    }

    private OnDrmConfigHelper mOnDrmConfigHelper;

    private final Object mDrmEventCbLock = new Object();
    private ArrayList<Pair<Executor, DrmEventCallback> > mDrmEventCallbackRecords
        = new ArrayList<Pair<Executor, DrmEventCallback> >();

    @Override
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

    @Override
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
     * Retrieves the DRM Info associated with the current source
     *
     * @throws IllegalStateException if called before prepare()
     */
    @Override
    public DrmInfo getDrmInfo() {
        DrmInfoImpl drmInfo = null;

        // there is not much point if the app calls getDrmInfo within an OnDrmInfoListenet;
        // regardless below returns drmInfo anyway instead of raising an exception
        synchronized (mDrmLock) {
            if (!mDrmInfoResolved && mDrmInfoImpl == null) {
                final String msg = "The Player has not been prepared yet";
                Log.v(TAG, msg);
                throw new IllegalStateException(msg);
            }

            if (mDrmInfoImpl != null) {
                drmInfo = mDrmInfoImpl.makeCopy();
            }
        }   // synchronized

        return drmInfo;
    }

    @Override
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
                    status = HandleProvisioninig(uuid);

                    if (status == PREPARE_DRM_STATUS_SUCCESS) {
                        // DrmEventCallback will be fired in provisioning
                        sendEvent = false;
                    } else {
                        synchronized (mDrmLock) {
                            cleanDrmObj();
                        }

                        switch (status) {
                        case PREPARE_DRM_STATUS_PROVISIONING_NETWORK_ERROR:
                            Log.e(TAG, "prepareDrm: Provisioning was required but failed " +
                                    "due to a network error.");
                            break;

                        case PREPARE_DRM_STATUS_PROVISIONING_SERVER_ERROR:
                            Log.e(TAG, "prepareDrm: Provisioning was required but the request " +
                                    "was denied by the server.");
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
                                    MediaPlayer2Impl.this, mCurrentDSD, prepareDrmStatus);
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
            if (mDrmInfoImpl == null) {
                final String msg = "prepareDrm(): Wrong usage: The player must be prepared and " +
                        "DRM info be retrieved before this call.";
                Log.e(TAG, msg);
                throw new IllegalStateException(msg);
            }

            if (mActiveDrmScheme) {
                final String msg = "prepareDrm(): Wrong usage: There is already " +
                        "an active DRM scheme with " + mDrmUUID;
                Log.e(TAG, msg);
                throw new IllegalStateException(msg);
            }

            if (mPrepareDrmInProgress) {
                final String msg = "prepareDrm(): Wrong usage: There is already " +
                        "a pending prepareDrm call.";
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
            mOnDrmConfigHelper.onDrmConfig(this, mCurrentDSD);
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
                final String msg = "prepareDrm(): Wrong usage: The player must be " +
                        "in the prepared state to call prepareDrm().";
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

    @Override
    public void releaseDrm()
            throws NoDrmSchemeException {
        synchronized (mDrmLock) {
            Log.v(TAG, "releaseDrm:");

            if (!mActiveDrmScheme) {
                Log.e(TAG, "releaseDrm(): No active DRM scheme to release.");
                throw new NoDrmSchemeExceptionImpl(
                        "releaseDrm: No active DRM scheme to release.");
            }

            try {
                // we don't have the player's state in this layer. The below call raises
                // exception if we're in a non-stopped/prepared state.

                // for cleaning native/mediaserver crypto object
                _releaseDrm();

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

    private native void _releaseDrm();

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
    @Override
    @NonNull
    public MediaDrm.KeyRequest getDrmKeyRequest(@Nullable byte[] keySetId, @Nullable byte[] initData,
            @Nullable String mimeType, @MediaDrm.KeyType int keyType,
            @Nullable Map<String, String> optionalParameters)
            throws NoDrmSchemeException
    {
        Log.v(TAG, "getDrmKeyRequest: " +
                " keySetId: " + keySetId + " initData:" + initData + " mimeType: " + mimeType +
                " keyType: " + keyType + " optionalParameters: " + optionalParameters);

        synchronized (mDrmLock) {
            if (!mActiveDrmScheme) {
                Log.e(TAG, "getDrmKeyRequest NoDrmSchemeException");
                throw new NoDrmSchemeExceptionImpl(
                        "getDrmKeyRequest: Has to set a DRM scheme first.");
            }

            try {
                byte[] scope = (keyType != MediaDrm.KEY_TYPE_RELEASE) ?
                        mDrmSessionId : // sessionId for KEY_TYPE_STREAMING/OFFLINE
                        keySetId;       // keySetId for KEY_TYPE_RELEASE

                HashMap<String, String> hmapOptionalParameters =
                                                (optionalParameters != null) ?
                                                new HashMap<String, String>(optionalParameters) :
                                                null;

                MediaDrm.KeyRequest request = mDrmObj.getKeyRequest(scope, initData, mimeType,
                                                              keyType, hmapOptionalParameters);
                Log.v(TAG, "getDrmKeyRequest:   --> request: " + request);

                return request;

            } catch (NotProvisionedException e) {
                Log.w(TAG, "getDrmKeyRequest NotProvisionedException: " +
                        "Unexpected. Shouldn't have reached here.");
                throw new IllegalStateException("getDrmKeyRequest: Unexpected provisioning error.");
            } catch (Exception e) {
                Log.w(TAG, "getDrmKeyRequest Exception " + e);
                throw e;
            }

        }   // synchronized
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
     * passed to the earlier {@ link #getDrmKeyRequest} call. It MUST be null when the
     * response is for either streaming or offline key requests.
     *
     * @param response the byte array response from the server
     *
     * @throws NoDrmSchemeException if there is no active DRM session
     * @throws DeniedByServerException if the response indicates that the
     * server rejected the request
     */
    @Override
    public byte[] provideDrmKeyResponse(@Nullable byte[] keySetId, @NonNull byte[] response)
            throws NoDrmSchemeException, DeniedByServerException
    {
        Log.v(TAG, "provideDrmKeyResponse: keySetId: " + keySetId + " response: " + response);

        synchronized (mDrmLock) {

            if (!mActiveDrmScheme) {
                Log.e(TAG, "getDrmKeyRequest NoDrmSchemeException");
                throw new NoDrmSchemeExceptionImpl(
                        "getDrmKeyRequest: Has to set a DRM scheme first.");
            }

            try {
                byte[] scope = (keySetId == null) ?
                                mDrmSessionId :     // sessionId for KEY_TYPE_STREAMING/OFFLINE
                                keySetId;           // keySetId for KEY_TYPE_RELEASE

                byte[] keySetResult = mDrmObj.provideKeyResponse(scope, response);

                Log.v(TAG, "provideDrmKeyResponse: keySetId: " + keySetId + " response: " + response
                        + " --> " + keySetResult);


                return keySetResult;

            } catch (NotProvisionedException e) {
                Log.w(TAG, "provideDrmKeyResponse NotProvisionedException: " +
                        "Unexpected. Shouldn't have reached here.");
                throw new IllegalStateException("provideDrmKeyResponse: " +
                        "Unexpected provisioning error.");
            } catch (Exception e) {
                Log.w(TAG, "provideDrmKeyResponse Exception " + e);
                throw e;
            }
        }   // synchronized
    }

    @Override
    public void restoreDrmKeys(@NonNull byte[] keySetId)
            throws NoDrmSchemeException {
        Log.v(TAG, "restoreDrmKeys: keySetId: " + keySetId);

        synchronized (mDrmLock) {
            if (!mActiveDrmScheme) {
                Log.w(TAG, "restoreDrmKeys NoDrmSchemeException");
                throw new NoDrmSchemeExceptionImpl(
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
    @Override
    @NonNull
    public String getDrmPropertyString(@NonNull @MediaDrm.StringProperty String propertyName)
            throws NoDrmSchemeException
    {
        Log.v(TAG, "getDrmPropertyString: propertyName: " + propertyName);

        String value;
        synchronized (mDrmLock) {

            if (!mActiveDrmScheme && !mDrmConfigAllowed) {
                Log.w(TAG, "getDrmPropertyString NoDrmSchemeException");
                throw new NoDrmSchemeExceptionImpl(
                        "getDrmPropertyString: Has to prepareDrm() first.");
            }

            try {
                value = mDrmObj.getPropertyString(propertyName);
            } catch (Exception e) {
                Log.w(TAG, "getDrmPropertyString Exception " + e);
                throw e;
            }
        }   // synchronized

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
    @Override
    public void setDrmPropertyString(@NonNull @MediaDrm.StringProperty String propertyName,
                                     @NonNull String value)
            throws NoDrmSchemeException
    {
        Log.v(TAG, "setDrmPropertyString: propertyName: " + propertyName + " value: " + value);

        synchronized (mDrmLock) {

            if ( !mActiveDrmScheme && !mDrmConfigAllowed ) {
                Log.w(TAG, "setDrmPropertyString NoDrmSchemeException");
                throw new NoDrmSchemeExceptionImpl(
                        "setDrmPropertyString: Has to prepareDrm() first.");
            }

            try {
                mDrmObj.setPropertyString(propertyName, value);
            } catch ( Exception e ) {
                Log.w(TAG, "setDrmPropertyString Exception " + e);
                throw e;
            }
        }   // synchronized
    }

    /**
     * Encapsulates the DRM properties of the source.
     */
    public static final class DrmInfoImpl extends DrmInfo {
        private Map<UUID, byte[]> mapPssh;
        private UUID[] supportedSchemes;

        /**
         * Returns the PSSH info of the data source for each supported DRM scheme.
         */
        @Override
        public Map<UUID, byte[]> getPssh() {
            return mapPssh;
        }

        /**
         * Returns the intersection of the data source and the device DRM schemes.
         * It effectively identifies the subset of the source's DRM schemes which
         * are supported by the device too.
         */
        @Override
        public List<UUID> getSupportedSchemes() {
            return Arrays.asList(supportedSchemes);
        }

        private DrmInfoImpl(Map<UUID, byte[]> Pssh, UUID[] SupportedSchemes) {
            mapPssh = Pssh;
            supportedSchemes = SupportedSchemes;
        }

        private DrmInfoImpl(PlayerMessage msg) {
            Log.v(TAG, "DrmInfoImpl(" + msg + ")");

            Iterator<Value> in = msg.getValuesList().iterator();
            byte[] pssh = in.next().getBytesValue().toByteArray();

            Log.v(TAG, "DrmInfoImpl() PSSH: " + arrToHex(pssh));
            mapPssh = parsePSSH(pssh, pssh.length);
            Log.v(TAG, "DrmInfoImpl() PSSH: " + mapPssh);

            int supportedDRMsCount = in.next().getInt32Value();
            supportedSchemes = new UUID[supportedDRMsCount];
            for (int i = 0; i < supportedDRMsCount; i++) {
                byte[] uuid = new byte[16];
                in.next().getBytesValue().copyTo(uuid, 0);

                supportedSchemes[i] = bytesToUUID(uuid);

                Log.v(TAG, "DrmInfoImpl() supportedScheme[" + i + "]: " +
                      supportedSchemes[i]);
            }

            Log.v(TAG, "DrmInfoImpl() psshsize: " + pssh.length +
                  " supportedDRMsCount: " + supportedDRMsCount);
        }

        private DrmInfoImpl makeCopy() {
            return new DrmInfoImpl(this.mapPssh, this.supportedSchemes);
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
                msb |= ( ((long)uuid[i]   & 0xff) << (8 * (7 - i)) );
                lsb |= ( ((long)uuid[i+8] & 0xff) << (8 * (7 - i)) );
            }

            return new UUID(msb, lsb);
        }

        private Map<UUID, byte[]> parsePSSH(byte[] pssh, int psshsize) {
            Map<UUID, byte[]> result = new HashMap<UUID, byte[]>();

            final int UUID_SIZE = 16;
            final int DATALEN_SIZE = 4;

            int len = psshsize;
            int numentries = 0;
            int i = 0;

            while (len > 0) {
                if (len < UUID_SIZE) {
                    Log.w(TAG, String.format("parsePSSH: len is too short to parse " +
                                             "UUID: (%d < 16) pssh: %d", len, psshsize));
                    return null;
                }

                byte[] subset = Arrays.copyOfRange(pssh, i, i + UUID_SIZE);
                UUID uuid = bytesToUUID(subset);
                i += UUID_SIZE;
                len -= UUID_SIZE;

                // get data length
                if (len < 4) {
                    Log.w(TAG, String.format("parsePSSH: len is too short to parse " +
                                             "datalen: (%d < 4) pssh: %d", len, psshsize));
                    return null;
                }

                subset = Arrays.copyOfRange(pssh, i, i+DATALEN_SIZE);
                int datalen = (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) ?
                    ((subset[3] & 0xff) << 24) | ((subset[2] & 0xff) << 16) |
                    ((subset[1] & 0xff) <<  8) |  (subset[0] & 0xff)          :
                    ((subset[0] & 0xff) << 24) | ((subset[1] & 0xff) << 16) |
                    ((subset[2] & 0xff) <<  8) |  (subset[3] & 0xff) ;
                i += DATALEN_SIZE;
                len -= DATALEN_SIZE;

                if (len < datalen) {
                    Log.w(TAG, String.format("parsePSSH: len is too short to parse " +
                                             "data: (%d < %d) pssh: %d", len, datalen, psshsize));
                    return null;
                }

                byte[] data = Arrays.copyOfRange(pssh, i, i+datalen);

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

    };  // DrmInfoImpl

    /**
     * Thrown when a DRM method is called before preparing a DRM scheme through prepareDrm().
     * Extends MediaDrm.MediaDrmException
     */
    public static final class NoDrmSchemeExceptionImpl extends NoDrmSchemeException {
        public NoDrmSchemeExceptionImpl(String detailMessage) {
            super(detailMessage);
        }
    }

    private native void _prepareDrm(@NonNull byte[] uuid, @NonNull byte[] drmSessionId);

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
            _prepareDrm(getByteArrayFromUUID(uuid), mDrmSessionId);
            Log.v(TAG, "prepareDrm_openSessionStep: _prepareDrm/Crypto succeeded");

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

        public StreamEventCallback(long jAudioTrackPtr, long nativeCallbackPtr, long userDataPtr) {
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

        private UUID uuid;
        private String urlStr;
        private Object drmLock;
        private MediaPlayer2Impl mediaPlayer;
        private int status;
        public  int status() {
            return status;
        }

        public ProvisioningThread initialize(MediaDrm.ProvisionRequest request,
                                          UUID uuid, MediaPlayer2Impl mediaPlayer) {
            // lock is held by the caller
            drmLock = mediaPlayer.mDrmLock;
            this.mediaPlayer = mediaPlayer;

            urlStr = request.getDefaultUrl() + "&signedRequest=" + new String(request.getData());
            this.uuid = uuid;

            status = PREPARE_DRM_STATUS_PREPARATION_ERROR;

            Log.v(TAG, "HandleProvisioninig: Thread is initialised url: " + urlStr);
            return this;
        }

        public void run() {

            byte[] response = null;
            boolean provisioningSucceeded = false;
            try {
                URL url = new URL(urlStr);
                final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                try {
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(false);
                    connection.setDoInput(true);
                    connection.setConnectTimeout(TIMEOUT_MS);
                    connection.setReadTimeout(TIMEOUT_MS);

                    connection.connect();
                    response = readInputStreamFully(connection.getInputStream());

                    Log.v(TAG, "HandleProvisioninig: Thread run: response " +
                            response.length + " " + response);
                } catch (Exception e) {
                    status = PREPARE_DRM_STATUS_PROVISIONING_NETWORK_ERROR;
                    Log.w(TAG, "HandleProvisioninig: Thread run: connect " + e + " url: " + url);
                } finally {
                    connection.disconnect();
                }
            } catch (Exception e)   {
                status = PREPARE_DRM_STATUS_PROVISIONING_NETWORK_ERROR;
                Log.w(TAG, "HandleProvisioninig: Thread run: openConnection " + e);
            }

            if (response != null) {
                try {
                    mDrmObj.provideProvisionResponse(response);
                    Log.v(TAG, "HandleProvisioninig: Thread run: " +
                            "provideProvisionResponse SUCCEEDED!");

                    provisioningSucceeded = true;
                } catch (Exception e) {
                    status = PREPARE_DRM_STATUS_PROVISIONING_SERVER_ERROR;
                    Log.w(TAG, "HandleProvisioninig: Thread run: " +
                            "provideProvisionResponse " + e);
                }
            }

            boolean succeeded = false;

            synchronized (drmLock) {
                // continuing with prepareDrm
                if (provisioningSucceeded) {
                    succeeded = mediaPlayer.resumePrepareDrm(uuid);
                    status = (succeeded) ?
                            PREPARE_DRM_STATUS_SUCCESS :
                            PREPARE_DRM_STATUS_PREPARATION_ERROR;
                }
                mediaPlayer.mDrmProvisioningInProgress = false;
                mediaPlayer.mPrepareDrmInProgress = false;
                if (!succeeded) {
                    cleanDrmObj();  // cleaning up if it hasn't gone through while in the lock
                }
            }  // synchronized

            // calling the callback outside the lock
            sendDrmEvent(new DrmEventNotifier() {
                @Override
                public void notify(DrmEventCallback callback) {
                    callback.onDrmPrepared(
                            mediaPlayer, mCurrentDSD, status);
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
    }   // ProvisioningThread

    private int HandleProvisioninig(UUID uuid) {
        synchronized (mDrmLock) {
            if (mDrmProvisioningInProgress) {
                Log.e(TAG, "HandleProvisioninig: Unexpected mDrmProvisioningInProgress");
                return PREPARE_DRM_STATUS_PREPARATION_ERROR;
            }

            MediaDrm.ProvisionRequest provReq = mDrmObj.getProvisionRequest();
            if (provReq == null) {
                Log.e(TAG, "HandleProvisioninig: getProvisionRequest returned null.");
                return PREPARE_DRM_STATUS_PREPARATION_ERROR;
            }

            Log.v(TAG, "HandleProvisioninig provReq " +
                    " data: " + provReq.getData() + " url: " + provReq.getDefaultUrl());

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
            Log.w(TAG, "HandleProvisioninig: Thread run _prepareDrm resume failed with " + e);
            // mDrmObj clean up is done by the caller
        }

        return success;
    }

    private void resetDrmState() {
        synchronized (mDrmLock) {
            Log.v(TAG, "resetDrmState: " +
                    " mDrmInfoImpl=" + mDrmInfoImpl +
                    " mDrmProvisioningThread=" + mDrmProvisioningThread +
                    " mPrepareDrmInProgress=" + mPrepareDrmInProgress +
                    " mActiveDrmScheme=" + mActiveDrmScheme);

            mDrmInfoResolved = false;
            mDrmInfoImpl = null;

            if (mDrmProvisioningThread != null) {
                // timeout; relying on HttpUrlConnection
                try {
                    mDrmProvisioningThread.join();
                }
                catch (InterruptedException e) {
                    Log.w(TAG, "resetDrmState: ProvThread.join Exception " + e);
                }
                mDrmProvisioningThread = null;
            }

            mPrepareDrmInProgress = false;
            mActiveDrmScheme = false;

            cleanDrmObj();
        }   // synchronized
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

    private static final byte[] getByteArrayFromUUID(@NonNull UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();

        byte[] uuidBytes = new byte[16];
        for (int i = 0; i < 8; ++i) {
            uuidBytes[i] = (byte)(msb >>> (8 * (7 - i)));
            uuidBytes[8 + i] = (byte)(lsb >>> (8 * (7 - i)));
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

        public Task (int mediaCallType, boolean needToWaitForEventToComplete) {
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
            synchronized (mSrcLock) {
                mDSD = mCurrentDSD;
            }

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
                            MediaPlayer2Impl.this, mDSD, mMediaCallType, status);
                }
            });
        }
    };

    private final class CommandSkippedException extends RuntimeException {
        public CommandSkippedException(String detailMessage) {
            super(detailMessage);
        }
    };
}
