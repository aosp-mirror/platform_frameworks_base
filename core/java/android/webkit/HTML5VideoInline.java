
package android.webkit;

import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.webkit.HTML5VideoView;
import android.webkit.HTML5VideoViewProxy;
import android.opengl.GLES20;

/**
 * @hide This is only used by the browser
 */
public class HTML5VideoInline extends HTML5VideoView{

    // Due to the fact that SurfaceTexture consume a lot of memory, we make it
    // as static. m_textureNames is the texture bound with this SurfaceTexture.
    private static SurfaceTexture mSurfaceTexture = null;
    private static int[] mTextureNames;

    // Only when the video is prepared, we render using SurfaceTexture.
    // This in fact is used to avoid showing the obsolete content when
    // switching videos.
    private static boolean mReadyToUseSurfTex = false;

    // Video control FUNCTIONS:
    @Override
    public void start() {
        super.start();
        if (mCurrentState == STATE_PREPARED) {
            mReadyToUseSurfTex = true;
        }
    }

    HTML5VideoInline(int videoLayerId, int position,
            boolean autoStart) {
        init(videoLayerId, position, autoStart);
        mReadyToUseSurfTex = false;
    }

    @Override
    public void decideDisplayMode() {
        mPlayer.setTexture(getSurfaceTextureInstance());
    }

    // Normally called immediately after setVideoURI. But for full screen,
    // this should be after surface holder created
    @Override
    public void prepareDataAndDisplayMode(HTML5VideoViewProxy proxy) {
        super.prepareDataAndDisplayMode(proxy);
        setFrameAvailableListener(proxy);
    }

    // Pause the play and update the play/pause button
    @Override
    public void pauseAndDispatch(HTML5VideoViewProxy proxy) {
        super.pauseAndDispatch(proxy);
        mReadyToUseSurfTex = false;
    }

    // Inline Video specific FUNCTIONS:

    @Override
    public SurfaceTexture getSurfaceTexture() {
        return mSurfaceTexture;
    }

    @Override
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

    @Override
    public int getTextureName() {
        return mTextureNames[0];
    }

    @Override
    public boolean getReadyToUseSurfTex() {
        return mReadyToUseSurfTex;
    }

    private void setFrameAvailableListener(SurfaceTexture.OnFrameAvailableListener l) {
        mSurfaceTexture.setOnFrameAvailableListener(l);
    }

}
