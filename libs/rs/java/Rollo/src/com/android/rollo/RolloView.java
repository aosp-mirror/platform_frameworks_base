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

package com.android.rollo;

import java.io.Writer;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;
import java.lang.Float;

import android.renderscript.RSSurfaceView;
import android.renderscript.RenderScript;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.graphics.PixelFormat;

public class RolloView extends RSSurfaceView {

    public RolloView(Context context) {
        super(context);
        setFocusable(true);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
    }

    private RenderScript mRS;
    private RolloRS mRender;

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        super.surfaceChanged(holder, format, w, h);

        mRS = createRenderScript();
        mRender = new RolloRS();
        mRender.init(mRS, getResources(), w, h);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        // break point at here
        // this method doesn't work when 'extends View' include 'extends ScrollView'.
        return super.onKeyDown(keyCode, event);
    }

    boolean mControlMode = false;
    boolean mFlingMode = false;
    float mFlingX = 0;
    float mFlingY = 0;
    float mColumn = -1;
    float mCurve = 1;
    float mZoom = 1;

    @Override
    public boolean onTouchEvent(MotionEvent ev)
    {
        boolean ret = true;
        int act = ev.getAction();
        if (act == ev.ACTION_UP) {
            ret = false;
        }

        float nx = ev.getX() / getWidth();
        float ny = ev.getY() / getHeight();

        mRender.setTouch(ret);

        if((ny > 0.85f) || mControlMode) {
            mRender.setShadow(0, 0, 0);
            mFlingMode = false;

            // Projector control
            if((nx > 0.2f) && (nx < 0.8f) || mControlMode) {
                if(act != ev.ACTION_UP) {
                    float zoom = 5.f;//1.f + 10.f * ev.getSize();
                    if(mControlMode) {
                        float dx = nx - mFlingX;

                        if(ny < 0.9) {
                            zoom = 5.f - ((0.9f - ny) * 15.f);
                            if(zoom < 1) {
                                zoom = 1;
                                mControlMode = false;
                            }
                        }
                        mColumn += dx * 3;// * zoom;
                        mColumn += -(mZoom - zoom) * (nx - 0.5f) * 2 * zoom;
                        mZoom = zoom;

                        if(mColumn > 1) {
                            mColumn = 1;
                        }
                        mRender.setPosition(mColumn);
                    } else {
                        mControlMode = true;
                        mZoom = 5;
                    }
                    mFlingX = nx;
                    mRender.setZoom(zoom);
                } else {
                    mControlMode = false;
                    mRender.setZoom(1.f);
                }
            } else {
                if(nx > 0.2f) {
                    mCurve += 0.1f;
                    if(mCurve > 2) {
                        mCurve = 2;
                    }
                }
                if(nx < 0.8f) {
                    mCurve -= 0.1f;
                    if(mCurve < (-2)) {
                        mCurve = -2;
                    }
                }
                mRender.setCurve(mCurve);
            }

        } else {
            // icon control
            if(act != ev.ACTION_UP) {
                if(mFlingMode) {
                    float dx = nx - mFlingX;
                    mColumn += dx * 5;
                    if(mColumn > 1) {
                        mColumn = 1;
                    }
                    mRender.setPosition(mColumn);
                }
                mFlingMode = true;
                mFlingX = nx;
                mFlingY = ny;
                //mRender.setShadow(nx, ny, ev.getSize());
            } else {
                mFlingMode = false;
                mRender.setShadow(nx, ny, 0);
            }
        }


        return ret;
    }

    @Override
    public boolean onTrackballEvent(MotionEvent ev)
    {
        float x = ev.getX();
        float y = ev.getY();
        //Float tx = new Float(x);
        //Float ty = new Float(y);
        //Log.e("rs", "tbe " + tx.toString() + ", " + ty.toString());


        return true;
    }
       
}


