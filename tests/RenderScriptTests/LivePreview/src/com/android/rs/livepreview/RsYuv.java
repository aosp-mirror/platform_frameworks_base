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

package com.android.rs.livepreview;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Bundle;
import android.graphics.SurfaceTexture;
import android.renderscript.Allocation;
import android.renderscript.Matrix3f;
import android.renderscript.RenderScript;
import android.util.Log;
import android.view.TextureView;
import android.view.View;

import android.content.res.Resources;
import android.renderscript.*;

import android.graphics.Bitmap;

public class RsYuv
{
    private int mHeight;
    private int mWidth;
    private RenderScript mRS;
    private Allocation mAllocationOut;
    private Allocation mAllocationIn;
    private ScriptC_yuv mScript;
    private ScriptIntrinsicYuvToRGB mYuv;

    RsYuv(RenderScript rs, Resources res, int width, int height) {
        mHeight = height;
        mWidth = width;
        mRS = rs;
        mScript = new ScriptC_yuv(mRS, res, R.raw.yuv);
        mScript.invoke_setSize(mWidth, mHeight);

        mYuv = ScriptIntrinsicYuvToRGB.create(rs, Element.RGBA_8888(mRS));

        Type.Builder tb = new Type.Builder(mRS, Element.RGBA_8888(mRS));
        tb.setX(mWidth);
        tb.setY(mHeight);

        mAllocationOut = Allocation.createTyped(rs, tb.create());
        mAllocationIn = Allocation.createSized(rs, Element.U8(mRS), (mHeight * mWidth) +
                                               ((mHeight / 2) * (mWidth / 2) * 2));

        mYuv.setInput(mAllocationIn);
    }

    private long mTiming[] = new long[50];
    private int mTimingSlot = 0;

    void execute(byte[] yuv, Bitmap b) {
        mAllocationIn.copyFrom(yuv);
        mYuv.forEach(mAllocationOut);
        mScript.forEach_root(mAllocationOut, mAllocationOut);
        mAllocationOut.copyTo(b);
    }

}

