/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.renderscript;

import java.io.Writer;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.TextureView;

/**
 * The Texture View for a graphics renderscript (RenderScriptGL)
 * to draw on.
 *
 */
public class RSTextureView extends TextureView implements TextureView.SurfaceTextureListener {
    private RenderScriptGL mRS;
    private SurfaceTexture mSurfaceTexture;

    /**
     * Standard View constructor. In order to render something, you
     * must call {@link android.opengl.GLSurfaceView#setRenderer} to
     * register a renderer.
     */
    public RSTextureView(Context context) {
        super(context);
        init();
        //Log.v(RenderScript.LOG_TAG, "RSSurfaceView");
    }

    /**
     * Standard View constructor. In order to render something, you
     * must call {@link android.opengl.GLSurfaceView#setRenderer} to
     * register a renderer.
     */
    public RSTextureView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
        //Log.v(RenderScript.LOG_TAG, "RSSurfaceView");
    }

    private void init() {
        setSurfaceTextureListener(this);
        //android.util.Log.e("rs", "getSurfaceTextureListerner " + getSurfaceTextureListener());
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        //Log.e(RenderScript.LOG_TAG, "onSurfaceTextureAvailable");
        mSurfaceTexture = surface;

        if (mRS != null) {
            mRS.setSurfaceTexture(mSurfaceTexture, width, height);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        //Log.e(RenderScript.LOG_TAG, "onSurfaceTextureSizeChanged");
        mSurfaceTexture = surface;

        if (mRS != null) {
            mRS.setSurfaceTexture(mSurfaceTexture, width, height);
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        //Log.e(RenderScript.LOG_TAG, "onSurfaceTextureDestroyed");
        mSurfaceTexture = surface;

        if (mRS != null) {
            mRS.setSurfaceTexture(null, 0, 0);
        }

        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        //Log.e(RenderScript.LOG_TAG, "onSurfaceTextureUpdated");
        mSurfaceTexture = surface;
    }

   /**
     * Inform the view that the activity is paused. The owner of this view must
     * call this method when the activity is paused. Calling this method will
     * pause the rendering thread.
     * Must not be called before a renderer has been set.
     */
    public void pause() {
        if(mRS != null) {
            mRS.pause();
        }
    }

    /**
     * Inform the view that the activity is resumed. The owner of this view must
     * call this method when the activity is resumed. Calling this method will
     * recreate the OpenGL display and resume the rendering
     * thread.
     * Must not be called before a renderer has been set.
     */
    public void resume() {
        if(mRS != null) {
            mRS.resume();
        }
    }

    /**
     * Create a new RenderScriptGL object and attach it to the
     * TextureView if present.
     *
     *
     * @param sc The RS surface config to create.
     *
     * @return RenderScriptGL The new object created.
     */
    public RenderScriptGL createRenderScriptGL(RenderScriptGL.SurfaceConfig sc) {
        RenderScriptGL rs = new RenderScriptGL(this.getContext(), sc);
        setRenderScriptGL(rs);
        if (mSurfaceTexture != null) {
            mRS.setSurfaceTexture(mSurfaceTexture, getWidth(), getHeight());
        }
        return rs;
    }

    /**
     * Destroy the RenderScriptGL object associated with this
     * TextureView.
     */
    public void destroyRenderScriptGL() {
        mRS.destroy();
        mRS = null;
    }

    /**
     * Set a new RenderScriptGL object.  This also will attach the
     * new object to the TextureView if present.
     *
     * @param rs The new RS object.
     */
    public void setRenderScriptGL(RenderScriptGL rs) {
        mRS = rs;
        if (mSurfaceTexture != null) {
            mRS.setSurfaceTexture(mSurfaceTexture, getWidth(), getHeight());
        }
    }

    /**
     * Returns the previously set RenderScriptGL object.
     *
     * @return RenderScriptGL
     */
    public RenderScriptGL getRenderScriptGL() {
        return mRS;
    }
}

