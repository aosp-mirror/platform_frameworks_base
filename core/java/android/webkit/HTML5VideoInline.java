/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.webkit;

import android.Manifest.permission;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.webkit.HTML5VideoView;
import android.webkit.HTML5VideoViewProxy;
import android.view.Surface;
import android.opengl.GLES20;
import android.os.PowerManager;

/**
 * @hide This is only used by the browser
 */
public class HTML5VideoInline extends HTML5VideoView{

    // Due to the fact that the decoder consume a lot of memory, we make the
    // surface texture as singleton. But the GL texture (m_textureNames)
    // associated with the surface texture can be used for showing the screen
    // shot when paused, so they are not singleton.
    private static SurfaceTexture mSurfaceTexture = null;
    private static int[] mTextureNames = null;
    // Every time when the VideoLayer Id change, we need to recreate the
    // SurfaceTexture in order to delete the old video's decoder memory.
    private static int mVideoLayerUsingSurfaceTexture = -1;

    // Video control FUNCTIONS:
    @Override
    public void start() {
        if (!getPauseDuringPreparing()) {
            super.start();
        }
    }

    HTML5VideoInline(int videoLayerId, int position, boolean skipPrepare) {
        init(videoLayerId, position, skipPrepare);
    }

    @Override
    public void decideDisplayMode() {
        SurfaceTexture surfaceTexture = getSurfaceTexture(getVideoLayerId());
        Surface surface = new Surface(surfaceTexture);
        mPlayer.setSurface(surface);
        surface.release();
    }

    // Normally called immediately after setVideoURI. But for full screen,
    // this should be after surface holder created
    @Override
    public void prepareDataAndDisplayMode(HTML5VideoViewProxy proxy) {
        super.prepareDataAndDisplayMode(proxy);
        setFrameAvailableListener(proxy);
        // TODO: This is a workaround, after b/5375681 fixed, we should switch
        // to the better way.
        if (mProxy.getContext().checkCallingOrSelfPermission(permission.WAKE_LOCK)
                == PackageManager.PERMISSION_GRANTED) {
            mPlayer.setWakeMode(proxy.getContext(), PowerManager.FULL_WAKE_LOCK);
        }
    }

    // Pause the play and update the play/pause button
    @Override
    public void pauseAndDispatch(HTML5VideoViewProxy proxy) {
        super.pauseAndDispatch(proxy);
    }

    // Inline Video specific FUNCTIONS:

    public static SurfaceTexture getSurfaceTexture(int videoLayerId) {
        // Create the surface texture.
        if (videoLayerId != mVideoLayerUsingSurfaceTexture
            || mSurfaceTexture == null
            || mTextureNames == null) {
            // The GL texture will store in the VideoLayerManager at native side.
            // They will be clean up when requested.
            // The reason we recreated GL texture name is for screen shot support.
            mTextureNames = new int[1];
            GLES20.glGenTextures(1, mTextureNames, 0);
            mSurfaceTexture = new SurfaceTexture(mTextureNames[0]);
        }
        mVideoLayerUsingSurfaceTexture = videoLayerId;
        return mSurfaceTexture;
    }

    public static boolean surfaceTextureDeleted() {
        return (mSurfaceTexture == null);
    }

    @Override
    public void deleteSurfaceTexture() {
        cleanupSurfaceTexture();
        return;
    }

    public static void cleanupSurfaceTexture() {
        mSurfaceTexture = null;
        mVideoLayerUsingSurfaceTexture = -1;
        return;
    }

    @Override
    public int getTextureName() {
        if (mTextureNames != null) {
            return mTextureNames[0];
        } else {
            return 0;
        }
    }

    private void setFrameAvailableListener(SurfaceTexture.OnFrameAvailableListener l) {
        if (mSurfaceTexture != null) {
            mSurfaceTexture.setOnFrameAvailableListener(l);
        }
    }

}
