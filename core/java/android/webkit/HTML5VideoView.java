
package android.webkit;

import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.Log;
import android.webkit.HTML5VideoViewProxy;
import android.widget.MediaController;
import android.opengl.GLES20;
import java.io.IOException;
import java.util.Map;

/**
 * @hide This is only used by the browser
 */
public class HTML5VideoView implements MediaPlayer.OnPreparedListener{
    // Due to the fact that SurfaceTexture consume a lot of memory, we make it
    // as static. m_textureNames is the texture bound with this SurfaceTexture.
    private static SurfaceTexture mSurfaceTexture = null;
    private static int[] mTextureNames;

    // Only when the video is prepared, we render using SurfaceTexture.
    // This in fact is used to avoid showing the obsolete content when
    // switching videos.
    private static boolean mReadyToUseSurfTex = false;

    // For handling the seekTo before prepared, we need to know whether or not
    // the video is prepared. Therefore, we differentiate the state between
    // prepared and not prepared.
    // When the video is not prepared, we will have to save the seekTo time,
    // and use it when prepared to play.
    private static final int STATE_NOTPREPARED        = 0;
    private static final int STATE_PREPARED           = 1;

    // We only need state for handling seekTo
    private int mCurrentState;

    // Basically for calling back the OnPrepared in the proxy
    private HTML5VideoViewProxy mProxy;

    // Save the seek time when not prepared. This can happen when switching
    // video besides initial load.
    private int mSaveSeekTime;

    // This is used to find the VideoLayer on the native side.
    private int mVideoLayerId;

    // Every video will have one MediaPlayer. Given the fact we only have one
    // SurfaceTexture, there is only one MediaPlayer in action. Every time we
    // switch videos, a new instance of MediaPlayer will be created in reset().
    private MediaPlayer mPlayer;

    private static HTML5VideoView mInstance = new HTML5VideoView();

    // Video control FUNCTIONS:
    public void start() {
        if (mCurrentState == STATE_PREPARED) {
            mPlayer.start();
            mReadyToUseSurfTex = true;
        }
    }

    public void pause() {
        mPlayer.pause();
    }

    public int getDuration() {
        return mPlayer.getDuration();
    }

    public int getCurrentPosition() {
        return mPlayer.getCurrentPosition();
    }

    public void seekTo(int pos) {
        if (mCurrentState == STATE_PREPARED)
            mPlayer.seekTo(pos);
        else
            mSaveSeekTime = pos;
    }

    public boolean isPlaying() {
        return mPlayer.isPlaying();
    }

    public void release() {
        mPlayer.release();
    }

    public void stopPlayback() {
        mPlayer.stop();
    }

    private void reset(int videoLayerId) {
        mPlayer = new MediaPlayer();
        mCurrentState = STATE_NOTPREPARED;
        mProxy = null;
        mVideoLayerId = videoLayerId;
        mReadyToUseSurfTex = false;
    }

    public static HTML5VideoView getInstance(int videoLayerId) {
        // Every time we switch between the videos, a new MediaPlayer will be
        // created. Make sure we call the m_player.release() when it is done.
        mInstance.reset(videoLayerId);
        return mInstance;
    }

    private HTML5VideoView() {
        // This is a singleton across WebViews (i.e. Tabs).
        // HTML5VideoViewProxy will reset the internal state every time a new
        // video start.
    }

    public void setMediaController(MediaController m) {
        this.setMediaController(m);
    }

    public void setVideoURI(String uri, Map<String, String> headers) {
        // When switching players, surface texture will be reused.
        mPlayer.setTexture(getSurfaceTextureInstance());

        // When there is exception, we could just bail out silently.
        // No Video will be played though. Write the stack for debug
        try {
            mPlayer.setDataSource(uri, headers);
            mPlayer.prepareAsync();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // TODO [FULL SCREEN SUPPORT]

    // Listeners setup FUNCTIONS:
    public void setOnCompletionListener(HTML5VideoViewProxy proxy) {
        mPlayer.setOnCompletionListener(proxy);
    }

    public void setOnErrorListener(HTML5VideoViewProxy proxy) {
        mPlayer.setOnErrorListener(proxy);
    }

    public void setOnPreparedListener(HTML5VideoViewProxy proxy) {
        mProxy = proxy;
        mPlayer.setOnPreparedListener(this);
    }

    // Inline Video specific FUNCTIONS:

    public SurfaceTexture getSurfaceTexture() {
        return mSurfaceTexture;
    }

    public void deleteSurfaceTexture() {
        mSurfaceTexture = null;
        return;
    }

    // SurfaceTexture is a singleton here , too
    private SurfaceTexture getSurfaceTextureInstance() {
        // Create the surface texture.
        if (mSurfaceTexture == null)
        {
            mTextureNames = new int[1];
            GLES20.glGenTextures(1, mTextureNames, 0);
            mSurfaceTexture = new SurfaceTexture(mTextureNames[0]);
        }
        return mSurfaceTexture;
    }

    public int getTextureName() {
        return mTextureNames[0];
    }

    public int getVideoLayerId() {
        return mVideoLayerId;
    }

    public boolean getReadyToUseSurfTex() {
        return mReadyToUseSurfTex;
    }

    public void setFrameAvailableListener(SurfaceTexture.OnFrameAvailableListener l) {
        mSurfaceTexture.setOnFrameAvailableListener(l);
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        mCurrentState = STATE_PREPARED;
        seekTo(mSaveSeekTime);
        if (mProxy != null)
            mProxy.onPrepared(mp);
    }

    // Pause the play and update the play/pause button
    public void pauseAndDispatch(HTML5VideoViewProxy proxy) {
        if (isPlaying()) {
            pause();
            if (proxy != null) {
                proxy.dispatchOnPaused();
            }
        }
        mReadyToUseSurfTex = false;
    }

}
