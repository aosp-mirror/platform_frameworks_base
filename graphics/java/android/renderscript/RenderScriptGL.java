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


/**
 * @hide
 *
 **/
public class RenderScriptGL extends RenderScript {
    private Surface mSurface;
    int mWidth;
    int mHeight;


    public RenderScriptGL(boolean useDepth, boolean forceSW) {
        mSurface = null;
        mWidth = 0;
        mHeight = 0;
        mDev = nDeviceCreate();
        if(forceSW) {
            nDeviceSetConfig(mDev, 0, 1);
        }
        mContext = nContextCreateGL(mDev, 0, useDepth);
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


