
package android.webkit;

import android.content.Context;
import android.media.MediaPlayer;
import android.media.Metadata;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.MediaController.MediaPlayerControl;


/**
 * @hide This is only used by the browser
 */
public class HTML5VideoFullScreen extends HTML5VideoView
    implements MediaPlayerControl, MediaPlayer.OnPreparedListener,
    View.OnTouchListener {

    // Add this sub-class to handle the resizing when rotating screen.
    private class VideoSurfaceView extends SurfaceView {

        public VideoSurfaceView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = getDefaultSize(mVideoWidth, widthMeasureSpec);
            int height = getDefaultSize(mVideoHeight, heightMeasureSpec);
            if (mVideoWidth > 0 && mVideoHeight > 0) {
                if ( mVideoWidth * height  > width * mVideoHeight ) {
                    height = width * mVideoHeight / mVideoWidth;
                } else if ( mVideoWidth * height  < width * mVideoHeight ) {
                    width = height * mVideoWidth / mVideoHeight;
                }
            }
            setMeasuredDimension(width, height);
        }
    }

    // This view will contain the video.
    private VideoSurfaceView mVideoSurfaceView;

    // We need the full screen state to decide which surface to render to and
    // when to create the MediaPlayer accordingly.
    static final int FULLSCREEN_OFF               = 0;
    static final int FULLSCREEN_SURFACECREATING   = 1;
    static final int FULLSCREEN_SURFACECREATED    = 2;

    private int mFullScreenMode;
    // The Media Controller only used for full screen mode
    private MediaController mMediaController;

    // SurfaceHolder for full screen
    private SurfaceHolder mSurfaceHolder = null;

    // Data only for MediaController
    private boolean mCanSeekBack;
    private boolean mCanSeekForward;
    private boolean mCanPause;
    private int mCurrentBufferPercentage;

    // The progress view.
    private static View mProgressView;
    // The container for the progress view and video view
    private static FrameLayout mLayout;

    // The video size will be ready when prepared. Used to make sure the aspect
    // ratio is correct.
    private int mVideoWidth;
    private int mVideoHeight;

    SurfaceHolder.Callback mSHCallback = new SurfaceHolder.Callback()
    {
        public void surfaceChanged(SurfaceHolder holder, int format,
                                    int w, int h)
        {
            if (mPlayer != null && mMediaController != null
                    && mCurrentState == STATE_PREPARED) {
                if (mMediaController.isShowing()) {
                    // ensure the controller will get repositioned later
                    mMediaController.hide();
                }
                mMediaController.show();
            }
        }

        public void surfaceCreated(SurfaceHolder holder)
        {
            mSurfaceHolder = holder;
            mFullScreenMode = FULLSCREEN_SURFACECREATED;

            prepareForFullScreen();
        }

        public void surfaceDestroyed(SurfaceHolder holder)
        {
            // After we return from this we can't use the surface any more.
            // The current Video View will be destroy when we play a new video.
            pauseAndDispatch(mProxy);
            mPlayer.release();
            mSurfaceHolder = null;
            if (mMediaController != null) {
                mMediaController.hide();
            }
        }
    };

    private SurfaceView getSurfaceView() {
        return mVideoSurfaceView;
    }

    HTML5VideoFullScreen(Context context, int videoLayerId, int position,
            boolean autoStart) {
        mVideoSurfaceView = new VideoSurfaceView(context);
        mFullScreenMode = FULLSCREEN_OFF;
        mVideoWidth = 0;
        mVideoHeight = 0;
        init(videoLayerId, position, autoStart);
    }

    private void setMediaController(MediaController m) {
        mMediaController  = m;
        attachMediaController();
    }

    private void attachMediaController() {
        if (mPlayer != null && mMediaController != null) {
            mMediaController.setMediaPlayer(this);
            mMediaController.setAnchorView(mVideoSurfaceView);
            //Will be enabled when prepared
            mMediaController.setEnabled(false);
        }
    }

    @Override
    public void decideDisplayMode() {
        mPlayer.setDisplay(mSurfaceHolder);
    }

    private void prepareForFullScreen() {
        // So in full screen, we reset the MediaPlayer
        mPlayer.reset();
        MediaController mc = new FullScreenMediaController(mProxy.getContext(), mLayout);
        mc.setSystemUiVisibility(mLayout.getSystemUiVisibility());
        setMediaController(mc);
        mPlayer.setScreenOnWhilePlaying(true);
        prepareDataAndDisplayMode(mProxy);
    }


    private void toggleMediaControlsVisiblity() {
        if (mMediaController.isShowing()) {
            mMediaController.hide();
        } else {
            mMediaController.show();
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        super.onPrepared(mp);

        mVideoSurfaceView.setOnTouchListener(this);
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

        // mMediaController status depends on the Metadata result, so put it
        // after reading the MetaData
        if (mMediaController != null) {
            mMediaController.setEnabled(true);
            // If paused , should show the controller for ever!
            if (getAutostart())
                mMediaController.show();
            else
                mMediaController.show(0);
        }

        if (mProgressView != null) {
            mProgressView.setVisibility(View.GONE);
        }

        mVideoWidth = mp.getVideoWidth();
        mVideoHeight = mp.getVideoHeight();
        // This will trigger the onMeasure to get the display size right.
        mVideoSurfaceView.getHolder().setFixedSize(mVideoWidth, mVideoHeight);
    }

    public boolean fullScreenExited() {
        return (mLayout == null);
    }

    private final WebChromeClient.CustomViewCallback mCallback =
        new WebChromeClient.CustomViewCallback() {
            public void onCustomViewHidden() {
                // It listens to SurfaceHolder.Callback.SurfaceDestroyed event
                // which happens when the video view is detached from its parent
                // view. This happens in the WebChromeClient before this method
                // is invoked.
                mProxy.dispatchOnStopFullScreen();
                mLayout.removeView(getSurfaceView());

                if (mProgressView != null) {
                    mLayout.removeView(mProgressView);
                    mProgressView = null;
                }
                mLayout = null;
                // Re enable plugin views.
                mProxy.getWebView().getViewManager().showAll();

                mProxy = null;

                // Don't show the controller after exiting the full screen.
                mMediaController = null;
                mCurrentState = STATE_RELEASED;
            }
        };

    @Override
    public void enterFullScreenVideoState(int layerId,
            HTML5VideoViewProxy proxy, WebView webView) {
        mFullScreenMode = FULLSCREEN_SURFACECREATING;
        mCurrentBufferPercentage = 0;
        mPlayer.setOnBufferingUpdateListener(mBufferingUpdateListener);
        mProxy = proxy;

        mVideoSurfaceView.getHolder().addCallback(mSHCallback);
        mVideoSurfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        mVideoSurfaceView.setFocusable(true);
        mVideoSurfaceView.setFocusableInTouchMode(true);
        mVideoSurfaceView.requestFocus();

        // Create a FrameLayout that will contain the VideoView and the
        // progress view (if any).
        mLayout = new FrameLayout(mProxy.getContext());
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER);

        mLayout.addView(getSurfaceView(), layoutParams);

        mLayout.setVisibility(View.VISIBLE);
        WebChromeClient client = webView.getWebChromeClient();
        if (client != null) {
            client.onShowCustomView(mLayout, mCallback);
            // Plugins like Flash will draw over the video so hide
            // them while we're playing.
            if (webView.getViewManager() != null)
                webView.getViewManager().hideAll();

            mProgressView = client.getVideoLoadingProgressView();
            if (mProgressView != null) {
                mLayout.addView(mProgressView, layoutParams);
                mProgressView.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * @return true when we are in full screen mode, even the surface not fully
     * created.
     */
    public boolean isFullScreenMode() {
        return true;
    }

    // MediaController FUNCTIONS:
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
    public int getBufferPercentage() {
        if (mPlayer != null) {
            return mCurrentBufferPercentage;
        }
    return 0;
    }

    // Other listeners functions:
    private MediaPlayer.OnBufferingUpdateListener mBufferingUpdateListener =
        new MediaPlayer.OnBufferingUpdateListener() {
        public void onBufferingUpdate(MediaPlayer mp, int percent) {
            mCurrentBufferPercentage = percent;
        }
    };

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (mFullScreenMode >= FULLSCREEN_SURFACECREATED
                && mMediaController != null) {
            toggleMediaControlsVisiblity();
        }
        return false;
    }

    @Override
    protected void switchProgressView(boolean playerBuffering) {
        if (mProgressView != null) {
            if (playerBuffering) {
                mProgressView.setVisibility(View.VISIBLE);
            } else {
                mProgressView.setVisibility(View.GONE);
            }
        }
        return;
    }

    static class FullScreenMediaController extends MediaController {

        View mVideoView;

        public FullScreenMediaController(Context context, View video) {
            super(context);
            mVideoView = video;
        }

        @Override
        public void show() {
            super.show();
            if (mVideoView != null) {
                mVideoView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        }

        @Override
        public void hide() {
            if (mVideoView != null) {
                mVideoView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
            }
            super.hide();
        }

    }

}
