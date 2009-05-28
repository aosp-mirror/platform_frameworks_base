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

package com.android.fountain;

import java.io.Writer;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.KeyEvent;
import android.view.MotionEvent;

public class FountainView extends RSSurfaceView {

    public FountainView(Context context) {
        super(context);

        //setFocusable(true);
    }

    private RenderScript mRS;
    private RenderScript.Allocation mIntAlloc;
    private RenderScript.Allocation mPartAlloc;
    private RenderScript.Allocation mVertAlloc;
    private RenderScript.Script mScript;
    private RenderScript.ProgramFragmentStore mPFS;
    private RenderScript.ProgramFragment mPF;

    int mParams[] = new int[10];

    private void initRS() {
        mRS = createRenderScript();

        int partCount = 1024;

        mIntAlloc = mRS.allocationCreatePredefSized(RenderScript.ElementPredefined.USER_I32, 10);
        mPartAlloc = mRS.allocationCreatePredefSized(RenderScript.ElementPredefined.USER_I32, partCount * 3 * 3);
        mVertAlloc = mRS.allocationCreatePredefSized(RenderScript.ElementPredefined.USER_I32, partCount * 5 + 1);

        mRS.programFragmentStoreBegin(null, null);
        mRS.programFragmentStoreBlendFunc(RenderScript.BlendSrcFunc.SRC_ALPHA, RenderScript.BlendDstFunc.ONE);
        mRS.programFragmentStoreDepthFunc(RenderScript.DepthFunc.ALWAYS);
        mPFS = mRS.programFragmentStoreCreate();
        mRS.contextBindProgramFragmentStore(mPFS);

        mRS.programFragmentBegin(null, null);
        mPF = mRS.programFragmentCreate();
        mRS.contextBindProgramFragment(mPF);

        mParams[0] = 0;
        mParams[1] = partCount;
        mParams[2] = 0;
        mParams[3] = 0;
        mParams[4] = 0;
        mParams[5] = mPartAlloc.mID;
        mIntAlloc.data(mParams);

        int t2[] = new int[partCount * 4*3];
        for (int ct=0; ct < t2.length; ct++) {
            t2[ct] = 0;
        }
        mPartAlloc.data(t2);

        mRS.scriptCBegin();
        mRS.scriptCSetClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        mRS.scriptCSetScript("");
        mRS.scriptCSetRoot(true);
        mScript = mRS.scriptCCreate();

        mScript.bindAllocation(mIntAlloc, 0);
        mScript.bindAllocation(mPartAlloc, 1);
        mScript.bindAllocation(mVertAlloc, 2);
        mRS.contextBindRootScript(mScript);

    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        super.surfaceChanged(holder, format, w, h);

        initRS();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        // break point at here
        // this method doesn't work when 'extends View' include 'extends ScrollView'.
        return super.onKeyDown(keyCode, event);
    }

    int mTouchAction;

    @Override
    public boolean onTouchEvent(MotionEvent ev)
    {
        //Log.e("FountainView", ev.toString());
        boolean ret = true;
        int act = ev.getAction();
        mParams[1] = (int)ev.getX();
        mParams[2] = (int)ev.getY();

        if (act == ev.ACTION_DOWN) {
            mParams[0] = 1;
        } else if (act == ev.ACTION_UP) {
            //mParams[0] = 0;
            ret = false;
        }
        mIntAlloc.subData1D(2, 3, mParams);

        return ret;
    }
}


