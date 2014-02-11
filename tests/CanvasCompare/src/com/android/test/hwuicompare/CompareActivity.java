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

package com.android.test.hwuicompare;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import com.android.test.hwuicompare.R;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Trace;
import android.util.Log;
import android.view.View;

abstract public class CompareActivity extends Activity {
    private static final String LOG_TAG = "CompareActivity";

    protected MainView mHardwareView = null;

    protected Bitmap mSoftwareBitmap;
    protected Bitmap mHardwareBitmap;

    protected ErrorCalculator mErrorCalculator;

    protected Handler mHandler;

    Runnable mDrawCallback = null;
    protected boolean mRedrewFlag = true;

    protected void onCreateCommon(final Runnable postDrawCallback) {
        mDrawCallback = new Runnable() {
            @Override
            public void run() {
                mRedrewFlag = true;
                mHandler.post(postDrawCallback);
            };
        };
        getWindow().setBackgroundDrawable(new ColorDrawable(0xffefefef));
        ResourceModifiers.init(getResources());

        mHardwareView = (MainView) findViewById(R.id.hardware_view);
        mHardwareView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        mHardwareView.setBackgroundColor(Color.WHITE);
        mHardwareView.addDrawCallback(mDrawCallback);

        int width = getResources().getDimensionPixelSize(R.dimen.layer_width);
        int height = getResources().getDimensionPixelSize(R.dimen.layer_height);
        mSoftwareBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        mHardwareBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        mErrorCalculator = new ErrorCalculator(getApplicationContext(), getResources());

        mHandler = new Handler();
    }

    protected abstract boolean forceRecreateBitmaps();

    protected void loadBitmaps() {
        Trace.traceBegin(Trace.TRACE_TAG_ALWAYS, "loadBitmaps");
        if (forceRecreateBitmaps()) {
            int width = mSoftwareBitmap.getWidth();
            int height = mSoftwareBitmap.getHeight();

            mSoftwareBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            mHardwareBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }

        Trace.traceBegin(Trace.TRACE_TAG_ALWAYS, "softwareDraw");
        mHardwareView.draw(new Canvas(mSoftwareBitmap));
        Trace.traceEnd(Trace.TRACE_TAG_ALWAYS);

        try {
            Method getHardwareLayer = View.class.getDeclaredMethod("getHardwareLayer");
            if (!getHardwareLayer.isAccessible())
                getHardwareLayer.setAccessible(true);
            Object hardwareLayer = getHardwareLayer.invoke(mHardwareView);
            if (hardwareLayer == null) {
                Log.d(LOG_TAG, "failure to access hardware layer");
                return;
            }
            Method copyInto = hardwareLayer.getClass()
                    .getDeclaredMethod("copyInto", Bitmap.class);
            if (!copyInto.isAccessible())
                copyInto.setAccessible(true);

            Trace.traceBegin(Trace.TRACE_TAG_ALWAYS, "copyInto");
            boolean success = (Boolean) copyInto.invoke(hardwareLayer, mHardwareBitmap);
            Trace.traceEnd(Trace.TRACE_TAG_ALWAYS);
            if (!success) {
                Log.d(LOG_TAG, "failure to copy hardware layer into bitmap");
            }
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        Trace.traceEnd(Trace.TRACE_TAG_ALWAYS);
    }
}
