/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * @hide
 *
 **/
public class RSSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
    private SurfaceHolder mSurfaceHolder;
    private RenderScriptGL mRS;

    /**
     * Standard View constructor. In order to render something, you
     * must call {@link #setRenderer} to register a renderer.
     */
    public RSSurfaceView(Context context) {
        super(context);
        init();
        //Log.v(RenderScript.LOG_TAG, "RSSurfaceView");
    }

    /**
     * Standard View constructor. In order to render something, you
     * must call {@link #setRenderer} to register a renderer.
     */
    public RSSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
        //Log.v(RenderScript.LOG_TAG, "RSSurfaceView");
    }

    private void init() {
        // Install a SurfaceHolder.Callback so we get notified when the
        // underlying surface is created and destroyed
        SurfaceHolder holder = getHolder();
        holder.addCallback(this);
    }

    /**
     * This method is part of the SurfaceHolder.Callback interface, and is
     * not normally called or subclassed by clients of RSSurfaceView.
     */
    public void surfaceCreated(SurfaceHolder holder) {
        Log.v(RenderScript.LOG_TAG, "surfaceCreated");
        mSurfaceHolder = holder;
    }

    /**
     * This method is part of the SurfaceHolder.Callback interface, and is
     * not normally called or subclassed by clients of RSSurfaceView.
     */
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Surface will be destroyed when we return
        Log.v(RenderScript.LOG_TAG, "surfaceDestroyed");
        if (mRS != null) {
            mRS.contextSetSurface(0, 0, null);
        }
    }

    /**
     * This method is part of the SurfaceHolder.Callback interface, and is
     * not normally called or subclassed by clients of RSSurfaceView.
     */
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        Log.v(RenderScript.LOG_TAG, "surfaceChanged");
        if (mRS != null) {
            mRS.contextSetSurface(w, h, holder.getSurface());
        }
    }

    /**
     * Inform the view that the activity is paused. The owner of this view must
     * call this method when the activity is paused. Calling this method will
     * pause the rendering thread.
     * Must not be called before a renderer has been set.
     */
    public void onPause() {
        if(mRS != null) {
            mRS.pause();
        }
        //Log.v(RenderScript.LOG_TAG, "onPause");
    }

    /**
     * Inform the view that the activity is resumed. The owner of this view must
     * call this method when the activity is resumed. Calling this method will
     * recreate the OpenGL display and resume the rendering
     * thread.
     * Must not be called before a renderer has been set.
     */
    public void onResume() {
        if(mRS != null) {
            mRS.resume();
        }
        //Log.v(RenderScript.LOG_TAG, "onResume");
    }

    /**
     * Queue a runnable to be run on the GL rendering thread. This can be used
     * to communicate with the Renderer on the rendering thread.
     * Must not be called before a renderer has been set.
     * @param r the runnable to be run on the GL rendering thread.
     */
    public void queueEvent(Runnable r) {
        //Log.v(RenderScript.LOG_TAG, "queueEvent");
    }

    /**
     * This method is used as part of the View class and is not normally
     * called or subclassed by clients of RSSurfaceView.
     * Must not be called before a renderer has been set.
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    // ----------------------------------------------------------------------

    public RenderScriptGL createRenderScript(boolean useDepth, boolean forceSW) {
        Log.v(RenderScript.LOG_TAG, "createRenderScript");
        mRS = new RenderScriptGL(useDepth, forceSW);
        return mRS;
    }

    public RenderScriptGL createRenderScript(boolean useDepth) {
        return createRenderScript(useDepth, false);
    }

    public void destroyRenderScript() {
        Log.v(RenderScript.LOG_TAG, "destroyRenderScript");
        mRS.destroy();
        mRS = null;
    }
    
    public void createRenderScript(RenderScriptGL rs) {
        mRS = rs;
    }
}

