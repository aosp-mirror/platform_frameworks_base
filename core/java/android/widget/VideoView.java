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

package android.widget;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.media.AudioManager;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnInfoListener;
import android.media.Metadata;
import android.media.SubtitleController;
import android.media.SubtitleTrack.RenderingWidget;
import android.media.WebVttRenderer;
import android.net.Uri;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.MediaController.MediaPlayerControl;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Vector;

/**
 * Displays a video file.  The VideoView class
 * can load images from various sources (such as resources or content
 * providers), takes care of computing its measurement from the video so that
 * it can be used in any layout manager, and provides various display options
 * such as scaling and tinting.<p>
 *
 * <em>Note: VideoView does not retain its full state when going into the
 * background.</em>  In particular, it does not restore the current play state,
 * play position, selected tracks, or any subtitle tracks added via
 * {@link #addSubtitleSource addSubtitleSource()}.  Applications should
 * save and restore these on their own in
 * {@link android.app.Activity#onSaveInstanceState} and
 * {@link android.app.Activity#onRestoreInstanceState}.<p>
 * Also note that the audio session id (from {@link #getAudioSessionId}) may
 * change from its previously returned value when the VideoView is restored.
 */
public class VideoView extends SurfaceView
        implements MediaPlayerControl, SubtitleController.Anchor {
    private String TAG = "VideoView";
    // settable by the client
    private Uri         mUri;
    private Map<String, String> mHeaders;

    // all possible internal states
    private static final int STATE_ERROR              = -1;
    private static final int STATE_IDLE               = 0;
    private static final int STATE_PREPARING          = 1;
    private static final int STATE_PREPARED           = 2;
    private static final int STATE_PLAYING            = 3;
    private static final int STATE_PAUSED             = 4;
    private static final int STATE_PLAYBACK_COMPLETED = 5;

    // mCurrentState is a VideoView object's current state.
    // mTargetState is the state that a method caller intends to reach.
    // For instance, regardless the VideoView object's current state,
    // calling pause() intends to bring the object to a target state
    // of STATE_PAUSED.
    private int mCurrentState = STATE_IDLE;
    private int mTargetState  = STATE_IDLE;

    // All the stuff we need for playing and showing a video
    private SurfaceHolder mSurfaceHolder = null;
    private MediaPlayer mMediaPlayer = null;
    private int         mAudioSession;
    private int         mVideoWidth;
    private int         mVideoHeight;
    private int         mSurfaceWidth;
    private int         mSurfaceHeight;
    private MediaController mMediaController;
    private OnCompletionListener mOnCompletionListener;
    private MediaPlayer.OnPreparedListener mOnPreparedListener;
    private int         mCurrentBufferPercentage;
    private OnErrorListener mOnErrorListener;
    private OnInfoListener  mOnInfoListener;
    private int         mSeekWhenPrepared;  // recording the seek position while preparing
    private boolean     mCanPause;
    private boolean     mCanSeekBack;
    private boolean     mCanSeekForward;

    /** Subtitle rendering widget overlaid on top of the video. */
    private RenderingWidget mSubtitleWidget;

    /** Listener for changes to subtitle data, used to redraw when needed. */
    private RenderingWidget.OnChangedListener mSubtitlesChangedListener;

    public VideoView(Context context) {
        super(context);
        initVideoView();
    }

    public VideoView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
        initVideoView();
    }

    public VideoView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initVideoView();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        //Log.i("@@@@", "onMeasure(" + MeasureSpec.toString(widthMeasureSpec) + ", "
        //        + MeasureSpec.toString(heightMeasureSpec) + ")");

        int width = getDefaultSize(mVideoWidth, widthMeasureSpec);
        int height = getDefaultSize(mVideoHeight, heightMeasureSpec);
        if (mVideoWidth > 0 && mVideoHeight > 0) {

            int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
            int widthSpecSize = MeasureSpec.getSize(widthMeasureSpec);
            int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);
            int heightSpecSize = MeasureSpec.getSize(heightMeasureSpec);

            if (widthSpecMode == MeasureSpec.EXACTLY && heightSpecMode == MeasureSpec.EXACTLY) {
                // the size is fixed
                width = widthSpecSize;
                height = heightSpecSize;

                // for compatibility, we adjust size based on aspect ratio
                if ( mVideoWidth * height  < width * mVideoHeight ) {
                    //Log.i("@@@", "image too wide, correcting");
                    width = height * mVideoWidth / mVideoHeight;
                } else if ( mVideoWidth * height  > width * mVideoHeight ) {
                    //Log.i("@@@", "image too tall, correcting");
                    height = width * mVideoHeight / mVideoWidth;
                }
            } else if (widthSpecMode == MeasureSpec.EXACTLY) {
                // only the width is fixed, adjust the height to match aspect ratio if possible
                width = widthSpecSize;
                height = width * mVideoHeight / mVideoWidth;
                if (heightSpecMode == MeasureSpec.AT_MOST && height > heightSpecSize) {
                    // couldn't match aspect ratio within the constraints
                    height = heightSpecSize;
                }
            } else if (heightSpecMode == MeasureSpec.EXACTLY) {
                // only the height is fixed, adjust the width to match aspect ratio if possible
                height = heightSpecSize;
                width = height * mVideoWidth / mVideoHeight;
                if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
                    // couldn't match aspect ratio within the constraints
                    width = widthSpecSize;
                }
            } else {
                // neither the width nor the height are fixed, try to use actual video size
                width = mVideoWidth;
                height = mVideoHeight;
                if (heightSpecMode == MeasureSpec.AT_MOST && height > heightSpecSize) {
                    // too tall, decrease both width and height
                    height = heightSpecSize;
                    width = height * mVideoWidth / mVideoHeight;
                }
                if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
                    // too wide, decrease both width and height
                    width = widthSpecSize;
                    height = width * mVideoHeight / mVideoWidth;
                }
            }
        } else {
            // no size yet, just adopt the given spec sizes
        }
        setMeasuredDimension(width, height);
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName(VideoView.class.getName());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(VideoView.class.getName());
    }

    public int resolveAdjustedSize(int desiredSize, int measureSpec) {
        return getDefaultSize(desiredSize, measureSpec);
    }

    private void initVideoView() {
        mVideoWidth = 0;
        mVideoHeight = 0;
        getHolder().addCallback(mSHCallback);
        getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        mPendingSubtitleTracks = new Vector<Pair<InputStream, MediaFormat>>();
        mCurrentState = STATE_IDLE;
        mTargetState  = STATE_IDLE;
    }

    public void setVideoPath(String path) {
        setVideoURI(Uri.parse(path));
    }

    public void setVideoURI(Uri uri) {
        setVideoURI(uri, null);
    }

    /**
     * @hide
     */
    public void setVideoURI(Uri uri, Map<String, String> headers) {
        mUri = uri;
        mHeaders = headers;
        mSeekWhenPrepared = 0;
        openVideo();
        requestLayout();
        invalidate();
    }

    /**
     * Adds an external subtitle source file (from the provided input stream.)
     *
     * Note that a single external subtitle source may contain multiple or no
     * supported tracks in it. If the source contained at least one track in
     * it, one will receive an {@link MediaPlayer#MEDIA_INFO_METADATA_UPDATE}
     * info message. Otherwise, if reading the source takes excessive time,
     * one will receive a {@link MediaPlayer#MEDIA_INFO_SUBTITLE_TIMED_OUT}
     * message. If the source contained no supported track (including an empty
     * source file or null input stream), one will receive a {@link
     * MediaPlayer#MEDIA_INFO_UNSUPPORTED_SUBTITLE} message. One can find the
     * total number of available tracks using {@link MediaPlayer#getTrackInfo()}
     * to see what additional tracks become available after this method call.
     *
     * @param is     input stream containing the subtitle data.  It will be
     *               closed by the media framework.
     * @param format the format of the subtitle track(s).  Must contain at least
     *               the mime type ({@link MediaFormat#KEY_MIME}) and the
     *               language ({@link MediaFormat#KEY_LANGUAGE}) of the file.
     *               If the file itself contains the language information,
     *               specify "und" for the language.
     */
    public void addSubtitleSource(InputStream is, MediaFormat format) {
        if (mMediaPlayer == null) {
            mPendingSubtitleTracks.add(Pair.create(is, format));
        } else {
            try {
                mMediaPlayer.addSubtitleSource(is, format);
            } catch (IllegalStateException e) {
                mInfoListener.onInfo(
                        mMediaPlayer, MediaPlayer.MEDIA_INFO_UNSUPPORTED_SUBTITLE, 0);
            }
        }
    }

    private Vector<Pair<InputStream, MediaFormat>> mPendingSubtitleTracks;

    public void stopPlayback() {
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.release();
            mMediaPlayer = null;
            mCurrentState = STATE_IDLE;
            mTargetState  = STATE_IDLE;
        }
    }

    private void openVideo() {
        if (mUri == null || mSurfaceHolder == null) {
            // not ready for playback just yet, will try again later
            return;
        }
        // Tell the music playback service to pause
        // TODO: these constants need to be published somewhere in the framework.
        Intent i = new Intent("com.android.music.musicservicecommand");
        i.putExtra("command", "pause");
        mContext.sendBroadcast(i);

        // we shouldn't clear the target state, because somebody might have
        // called start() previously
        release(false);
        try {
            mMediaPlayer = new MediaPlayer();
            // TODO: create SubtitleController in MediaPlayer, but we need
            // a context for the subtitle renderers
            final Context context = getContext();
            final SubtitleController controller = new SubtitleController(
                    context, mMediaPlayer.getMediaTimeProvider(), mMediaPlayer);
            controller.registerRenderer(new WebVttRenderer(context));
            mMediaPlayer.setSubtitleAnchor(controller, this);

            if (mAudioSession != 0) {
                mMediaPlayer.setAudioSessionId(mAudioSession);
            } else {
                mAudioSession = mMediaPlayer.getAudioSessionId();
            }
            mMediaPlayer.setOnPreparedListener(mPreparedListener);
            mMediaPlayer.setOnVideoSizeChangedListener(mSizeChangedListener);
            mMediaPlayer.setOnCompletionListener(mCompletionListener);
            mMediaPlayer.setOnErrorListener(mErrorListener);
            mMediaPlayer.setOnInfoListener(mInfoListener);
            mMediaPlayer.setOnBufferingUpdateListener(mBufferingUpdateListener);
            mCurrentBufferPercentage = 0;
            mMediaPlayer.setDataSource(mContext, mUri, mHeaders);
            mMediaPlayer.setDisplay(mSurfaceHolder);
            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mMediaPlayer.setScreenOnWhilePlaying(true);
            mMediaPlayer.prepareAsync();

            for (Pair<InputStream, MediaFormat> pending: mPendingSubtitleTracks) {
                try {
                    mMediaPlayer.addSubtitleSource(pending.first, pending.second);
                } catch (IllegalStateException e) {
                    mInfoListener.onInfo(
                            mMediaPlayer, MediaPlayer.MEDIA_INFO_UNSUPPORTED_SUBTITLE, 0);
                }
            }

            // we don't set the target state here either, but preserve the
            // target state that was there before.
            mCurrentState = STATE_PREPARING;
            attachMediaController();
        } catch (IOException ex) {
            Log.w(TAG, "Unable to open content: " + mUri, ex);
            mCurrentState = STATE_ERROR;
            mTargetState = STATE_ERROR;
            mErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
            return;
        } catch (IllegalArgumentException ex) {
            Log.w(TAG, "Unable to open content: " + mUri, ex);
            mCurrentState = STATE_ERROR;
            mTargetState = STATE_ERROR;
            mErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
            return;
        } finally {
            mPendingSubtitleTracks.clear();
        }
    }

    public void setMediaController(MediaController controller) {
        if (mMediaController != null) {
            mMediaController.hide();
        }
        mMediaController = controller;
        attachMediaController();
    }

    private void attachMediaController() {
        if (mMediaPlayer != null && mMediaController != null) {
            mMediaController.setMediaPlayer(this);
            View anchorView = this.getParent() instanceof View ?
                    (View)this.getParent() : this;
            mMediaController.setAnchorView(anchorView);
            mMediaController.setEnabled(isInPlaybackState());
        }
    }

    MediaPlayer.OnVideoSizeChangedListener mSizeChangedListener =
        new MediaPlayer.OnVideoSizeChangedListener() {
            public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                mVideoWidth = mp.getVideoWidth();
                mVideoHeight = mp.getVideoHeight();
                if (mVideoWidth != 0 && mVideoHeight != 0) {
                    getHolder().setFixedSize(mVideoWidth, mVideoHeight);
                    requestLayout();
                }
            }
    };

    MediaPlayer.OnPreparedListener mPreparedListener = new MediaPlayer.OnPreparedListener() {
        public void onPrepared(MediaPlayer mp) {
            mCurrentState = STATE_PREPARED;

            // Get the capabilities of the player for this stream
            Metadata data = mp.getMetadata(MediaPlayer.METADATA_ALL,
                                      MediaPlayer.BYPASS_METADATA_FILTER);

            if (data != null) {
                mCanPause = !data.has(Metadata.PAUSE_AVAILABLE)
                        || data.getBoolean(Metadata.PAUSE_AVAILABLE);
                mCanSeekBack = !data.has(Metadata.SEEK_BACKWARD_AVAILABLE)
                        || data.getBoolean(Metadata.SEEK_BACKWARD_AVAILABLE);
                mCanSeekForward = !data.has(Metadata.SEEK_FORWARD_AVAILABLE)
                        || data.getBoolean(Metadata.SEEK_FORWARD_AVAILABLE);
            } else {
                mCanPause = mCanSeekBack = mCanSeekForward = true;
            }

            if (mOnPreparedListener != null) {
                mOnPreparedListener.onPrepared(mMediaPlayer);
            }
            if (mMediaController != null) {
                mMediaController.setEnabled(true);
            }
            mVideoWidth = mp.getVideoWidth();
            mVideoHeight = mp.getVideoHeight();

            int seekToPosition = mSeekWhenPrepared;  // mSeekWhenPrepared may be changed after seekTo() call
            if (seekToPosition != 0) {
                seekTo(seekToPosition);
            }
            if (mVideoWidth != 0 && mVideoHeight != 0) {
                //Log.i("@@@@", "video size: " + mVideoWidth +"/"+ mVideoHeight);
                getHolder().setFixedSize(mVideoWidth, mVideoHeight);
                if (mSurfaceWidth == mVideoWidth && mSurfaceHeight == mVideoHeight) {
                    // We didn't actually change the size (it was already at the size
                    // we need), so we won't get a "surface changed" callback, so
                    // start the video here instead of in the callback.
                    if (mTargetState == STATE_PLAYING) {
                        start();
                        if (mMediaController != null) {
                            mMediaController.show();
                        }
                    } else if (!isPlaying() &&
                               (seekToPosition != 0 || getCurrentPosition() > 0)) {
                       if (mMediaController != null) {
                           // Show the media controls when we're paused into a video and make 'em stick.
                           mMediaController.show(0);
                       }
                   }
                }
            } else {
                // We don't know the video size yet, but should start anyway.
                // The video size might be reported to us later.
                if (mTargetState == STATE_PLAYING) {
                    start();
                }
            }
        }
    };

    private MediaPlayer.OnCompletionListener mCompletionListener =
        new MediaPlayer.OnCompletionListener() {
        public void onCompletion(MediaPlayer mp) {
            mCurrentState = STATE_PLAYBACK_COMPLETED;
            mTargetState = STATE_PLAYBACK_COMPLETED;
            if (mMediaController != null) {
                mMediaController.hide();
            }
            if (mOnCompletionListener != null) {
                mOnCompletionListener.onCompletion(mMediaPlayer);
            }
        }
    };

    private MediaPlayer.OnInfoListener mInfoListener =
        new MediaPlayer.OnInfoListener() {
        public  boolean onInfo(MediaPlayer mp, int arg1, int arg2) {
            if (mOnInfoListener != null) {
                mOnInfoListener.onInfo(mp, arg1, arg2);
            }
            return true;
        }
    };

    private MediaPlayer.OnErrorListener mErrorListener =
        new MediaPlayer.OnErrorListener() {
        public boolean onError(MediaPlayer mp, int framework_err, int impl_err) {
            Log.d(TAG, "Error: " + framework_err + "," + impl_err);
            mCurrentState = STATE_ERROR;
            mTargetState = STATE_ERROR;
            if (mMediaController != null) {
                mMediaController.hide();
            }

            /* If an error handler has been supplied, use it and finish. */
            if (mOnErrorListener != null) {
                if (mOnErrorListener.onError(mMediaPlayer, framework_err, impl_err)) {
                    return true;
                }
            }

            /* Otherwise, pop up an error dialog so the user knows that
             * something bad has happened. Only try and pop up the dialog
             * if we're attached to a window. When we're going away and no
             * longer have a window, don't bother showing the user an error.
             */
            if (getWindowToken() != null) {
                Resources r = mContext.getResources();
                int messageId;

                if (framework_err == MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK) {
                    messageId = com.android.internal.R.string.VideoView_error_text_invalid_progressive_playback;
                } else {
                    messageId = com.android.internal.R.string.VideoView_error_text_unknown;
                }

                new AlertDialog.Builder(mContext)
                        .setMessage(messageId)
                        .setPositiveButton(com.android.internal.R.string.VideoView_error_button,
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int whichButton) {
                                        /* If we get here, there is no onError listener, so
                                         * at least inform them that the video is over.
                                         */
                                        if (mOnCompletionListener != null) {
                                            mOnCompletionListener.onCompletion(mMediaPlayer);
                                        }
                                    }
                                })
                        .setCancelable(false)
                        .show();
            }
            return true;
        }
    };

    private MediaPlayer.OnBufferingUpdateListener mBufferingUpdateListener =
        new MediaPlayer.OnBufferingUpdateListener() {
        public void onBufferingUpdate(MediaPlayer mp, int percent) {
            mCurrentBufferPercentage = percent;
        }
    };

    /**
     * Register a callback to be invoked when the media file
     * is loaded and ready to go.
     *
     * @param l The callback that will be run
     */
    public void setOnPreparedListener(MediaPlayer.OnPreparedListener l)
    {
        mOnPreparedListener = l;
    }

    /**
     * Register a callback to be invoked when the end of a media file
     * has been reached during playback.
     *
     * @param l The callback that will be run
     */
    public void setOnCompletionListener(OnCompletionListener l)
    {
        mOnCompletionListener = l;
    }

    /**
     * Register a callback to be invoked when an error occurs
     * during playback or setup.  If no listener is specified,
     * or if the listener returned false, VideoView will inform
     * the user of any errors.
     *
     * @param l The callback that will be run
     */
    public void setOnErrorListener(OnErrorListener l)
    {
        mOnErrorListener = l;
    }

    /**
     * Register a callback to be invoked when an informational event
     * occurs during playback or setup.
     *
     * @param l The callback that will be run
     */
    public void setOnInfoListener(OnInfoListener l) {
        mOnInfoListener = l;
    }

    SurfaceHolder.Callback mSHCallback = new SurfaceHolder.Callback()
    {
        public void surfaceChanged(SurfaceHolder holder, int format,
                                    int w, int h)
        {
            mSurfaceWidth = w;
            mSurfaceHeight = h;
            boolean isValidState =  (mTargetState == STATE_PLAYING);
            boolean hasValidSize = (mVideoWidth == w && mVideoHeight == h);
            if (mMediaPlayer != null && isValidState && hasValidSize) {
                if (mSeekWhenPrepared != 0) {
                    seekTo(mSeekWhenPrepared);
                }
                start();
            }
        }

        public void surfaceCreated(SurfaceHolder holder)
        {
            mSurfaceHolder = holder;
            openVideo();
        }

        public void surfaceDestroyed(SurfaceHolder holder)
        {
            // after we return from this we can't use the surface any more
            mSurfaceHolder = null;
            if (mMediaController != null) mMediaController.hide();
            release(true);
        }
    };

    /*
     * release the media player in any state
     */
    private void release(boolean cleartargetstate) {
        if (mMediaPlayer != null) {
            mMediaPlayer.reset();
            mMediaPlayer.release();
            mMediaPlayer = null;
            mPendingSubtitleTracks.clear();
            mCurrentState = STATE_IDLE;
            if (cleartargetstate) {
                mTargetState  = STATE_IDLE;
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (isInPlaybackState() && mMediaController != null) {
            toggleMediaControlsVisiblity();
        }
        return false;
    }

    @Override
    public boolean onTrackballEvent(MotionEvent ev) {
        if (isInPlaybackState() && mMediaController != null) {
            toggleMediaControlsVisiblity();
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        boolean isKeyCodeSupported = keyCode != KeyEvent.KEYCODE_BACK &&
                                     keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
                                     keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
                                     keyCode != KeyEvent.KEYCODE_VOLUME_MUTE &&
                                     keyCode != KeyEvent.KEYCODE_MENU &&
                                     keyCode != KeyEvent.KEYCODE_CALL &&
                                     keyCode != KeyEvent.KEYCODE_ENDCALL;
        if (isInPlaybackState() && isKeyCodeSupported && mMediaController != null) {
            if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (mMediaPlayer.isPlaying()) {
                    pause();
                    mMediaController.show();
                } else {
                    start();
                    mMediaController.hide();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                if (!mMediaPlayer.isPlaying()) {
                    start();
                    mMediaController.hide();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP
                    || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (mMediaPlayer.isPlaying()) {
                    pause();
                    mMediaController.show();
                }
                return true;
            } else {
                toggleMediaControlsVisiblity();
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    private void toggleMediaControlsVisiblity() {
        if (mMediaController.isShowing()) {
            mMediaController.hide();
        } else {
            mMediaController.show();
        }
    }

    @Override
    public void start() {
        if (isInPlaybackState()) {
            mMediaPlayer.start();
            mCurrentState = STATE_PLAYING;
        }
        mTargetState = STATE_PLAYING;
    }

    @Override
    public void pause() {
        if (isInPlaybackState()) {
            if (mMediaPlayer.isPlaying()) {
                mMediaPlayer.pause();
                mCurrentState = STATE_PAUSED;
            }
        }
        mTargetState = STATE_PAUSED;
    }

    public void suspend() {
        release(false);
    }

    public void resume() {
        openVideo();
    }

    @Override
    public int getDuration() {
        if (isInPlaybackState()) {
            return mMediaPlayer.getDuration();
        }

        return -1;
    }

    @Override
    public int getCurrentPosition() {
        if (isInPlaybackState()) {
            return mMediaPlayer.getCurrentPosition();
        }
        return 0;
    }

    @Override
    public void seekTo(int msec) {
        if (isInPlaybackState()) {
            mMediaPlayer.seekTo(msec);
            mSeekWhenPrepared = 0;
        } else {
            mSeekWhenPrepared = msec;
        }
    }

    @Override
    public boolean isPlaying() {
        return isInPlaybackState() && mMediaPlayer.isPlaying();
    }

    @Override
    public int getBufferPercentage() {
        if (mMediaPlayer != null) {
            return mCurrentBufferPercentage;
        }
        return 0;
    }

    private boolean isInPlaybackState() {
        return (mMediaPlayer != null &&
                mCurrentState != STATE_ERROR &&
                mCurrentState != STATE_IDLE &&
                mCurrentState != STATE_PREPARING);
    }

    @Override
    public boolean canPause() {
        return mCanPause;
    }

    @Override
    public boolean canSeekBackward() {
        return mCanSeekBack;
    }

    @Override
    public boolean canSeekForward() {
        return mCanSeekForward;
    }

    @Override
    public int getAudioSessionId() {
        if (mAudioSession == 0) {
            MediaPlayer foo = new MediaPlayer();
            mAudioSession = foo.getAudioSessionId();
            foo.release();
        }
        return mAudioSession;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (mSubtitleWidget != null) {
            mSubtitleWidget.onAttachedToWindow();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (mSubtitleWidget != null) {
            mSubtitleWidget.onDetachedFromWindow();
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if (mSubtitleWidget != null) {
            measureAndLayoutSubtitleWidget();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);

        if (mSubtitleWidget != null) {
            final int saveCount = canvas.save();
            canvas.translate(getPaddingLeft(), getPaddingTop());
            mSubtitleWidget.draw(canvas);
            canvas.restoreToCount(saveCount);
        }
    }

    /**
     * Forces a measurement and layout pass for all overlaid views.
     *
     * @see #setSubtitleWidget(RenderingWidget)
     */
    private void measureAndLayoutSubtitleWidget() {
        final int width = getWidth() - getPaddingLeft() - getPaddingRight();
        final int height = getHeight() - getPaddingTop() - getPaddingBottom();

        mSubtitleWidget.setSize(width, height);
    }

    /** @hide */
    @Override
    public void setSubtitleWidget(RenderingWidget subtitleWidget) {
        if (mSubtitleWidget == subtitleWidget) {
            return;
        }

        final boolean attachedToWindow = isAttachedToWindow();
        if (mSubtitleWidget != null) {
            if (attachedToWindow) {
                mSubtitleWidget.onDetachedFromWindow();
            }

            mSubtitleWidget.setOnChangedListener(null);
        }

        mSubtitleWidget = subtitleWidget;

        if (subtitleWidget != null) {
            if (mSubtitlesChangedListener == null) {
                mSubtitlesChangedListener = new RenderingWidget.OnChangedListener() {
                    @Override
                    public void onChanged(RenderingWidget renderingWidget) {
                        invalidate();
                    }
                };
            }

            setWillNotDraw(false);
            subtitleWidget.setOnChangedListener(mSubtitlesChangedListener);

            if (attachedToWindow) {
                subtitleWidget.onAttachedToWindow();
                requestLayout();
            }
        } else {
            setWillNotDraw(true);
        }

        invalidate();
    }

    /** @hide */
    @Override
    public Looper getSubtitleLooper() {
        return Looper.getMainLooper();
    }
}
