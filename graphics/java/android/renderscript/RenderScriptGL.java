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

import java.lang.reflect.Field;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Config;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * @hide
 *
 **/
public class RenderScriptGL extends RenderScript {
    private Surface mSurface;
    int mWidth;
    int mHeight;

    public static class SurfaceConfig {
        int mDepthMin       = 0;
        int mDepthPref      = 0;
        int mStencilMin     = 0;
        int mStencilPref    = 0;
        int mColorMin       = 8;
        int mColorPref      = 8;
        int mAlphaMin       = 0;
        int mAlphaPref      = 0;
        int mSamplesMin     = 1;
        int mSamplesPref    = 1;
        float mSamplesQ     = 1.f;

        public SurfaceConfig() {
        }

        public SurfaceConfig(SurfaceConfig sc) {
            mDepthMin = sc.mDepthMin;
            mDepthPref = sc.mDepthPref;
            mStencilMin = sc.mStencilMin;
            mStencilPref = sc.mStencilPref;
            mColorMin = sc.mColorMin;
            mColorPref = sc.mColorPref;
            mAlphaMin = sc.mAlphaMin;
            mAlphaPref = sc.mAlphaPref;
            mSamplesMin = sc.mSamplesMin;
            mSamplesPref = sc.mSamplesPref;
            mSamplesQ = sc.mSamplesQ;
        }

        private void validateRange(int umin, int upref, int rmin, int rmax) {
            if (umin < rmin || umin > rmax) {
                throw new IllegalArgumentException("Minimum value provided out of range.");
            }
            if (upref < umin) {
                throw new IllegalArgumentException("Prefered must be >= Minimum.");
            }
        }

        public void setColor(int minimum, int prefered) {
            validateRange(minimum, prefered, 5, 8);
            mColorMin = minimum;
            mColorPref = prefered;
        }
        public void setAlpha(int minimum, int prefered) {
            validateRange(minimum, prefered, 0, 8);
            mAlphaMin = minimum;
            mAlphaPref = prefered;
        }
        public void setDepth(int minimum, int prefered) {
            validateRange(minimum, prefered, 0, 24);
            mDepthMin = minimum;
            mDepthPref = prefered;
        }
        public void setSamples(int minimum, int prefered, float Q) {
            validateRange(minimum, prefered, 0, 24);
            if (Q < 0.0f || Q > 1.0f) {
                throw new IllegalArgumentException("Quality out of 0-1 range.");
            }
            mSamplesMin = minimum;
            mSamplesPref = prefered;
            mSamplesQ = Q;
        }
    };

    SurfaceConfig mSurfaceConfig;

    public void configureSurface(SurfaceHolder sh) {
        //getHolder().setFormat(PixelFormat.TRANSLUCENT);
    }

    public void checkSurface(SurfaceHolder sh) {
    }

    public RenderScriptGL(SurfaceConfig sc) {
        mSurfaceConfig = new SurfaceConfig(sc);



        mSurface = null;
        mWidth = 0;
        mHeight = 0;
        mDev = nDeviceCreate();
        mContext = nContextCreateGL(mDev, 0, mSurfaceConfig.mDepthMin > 0);
        mMessageThread = new MessageThread(this);
        mMessageThread.start();
        Element.initPredefined(this);
    }

    public void contextSetSurface(int w, int h, Surface sur) {
        mSurface = sur;
        mWidth = w;
        mHeight = h;
        validate();
        nContextSetSurface(w, h, mSurface);
    }


    void pause() {
        validate();
        nContextPause();
    }

    void resume() {
        validate();
        nContextResume();
    }


    public void contextBindRootScript(Script s) {
        validate();
        nContextBindRootScript(safeID(s));
    }

    public void contextBindProgramStore(ProgramStore p) {
        validate();
        nContextBindProgramStore(safeID(p));
    }

    public void contextBindProgramFragment(ProgramFragment p) {
        validate();
        nContextBindProgramFragment(safeID(p));
    }

    public void contextBindProgramRaster(ProgramRaster p) {
        validate();
        nContextBindProgramRaster(safeID(p));
    }

    public void contextBindProgramVertex(ProgramVertex p) {
        validate();
        nContextBindProgramVertex(safeID(p));
    }




    //////////////////////////////////////////////////////////////////////////////////
    // File

    public class File extends BaseObj {
        File(int id) {
            super(id, RenderScriptGL.this);
        }
    }

    public File fileOpen(String s) throws IllegalStateException, IllegalArgumentException
    {
        if(s.length() < 1) {
            throw new IllegalArgumentException("fileOpen does not accept a zero length string.");
        }

        try {
            byte[] bytes = s.getBytes("UTF-8");
            int id = nFileOpen(bytes);
            return new File(id);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

}


