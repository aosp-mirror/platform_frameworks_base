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

        mRS = createRenderScript(false);
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
    boolean mZoomMode = false;
    boolean mFlingMode = false;
    float mFlingX = 0;
    float mFlingY = 0;
    float mColumn = -1;
    float mOldColumn;
    float mZoom = 1;

    int mIconCount = 29;
    int mRows = 4;
    int mColumns = (mIconCount + mRows - 1) / mRows;

    float mMaxZoom = ((float)mColumns) / 3.f;


    void setColumn(boolean clamp)
    {
        //Log.e("rs", " col = " + Float.toString(mColumn));
        float c = mColumn;
        if(c > (mColumns -2)) {
            c = (mColumns -2);
        }
        if(c < 0) {
            c = 0;
        }
        mRender.setPosition(c);
        if(clamp) {
            mColumn = c;
        }
    }

    void computeSelection(float x, float y)
    {
        float col = mColumn + (x - 0.5f) * 4 + 1.25f;
        int iCol = (int)(col + 0.25f);

        float row = (y / 0.8f) * mRows;
        int iRow = (int)(row - 0.5f);

        mRender.setSelected(iCol * mRows + iRow);
    }

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

        //Log.e("rs", "width=" + Float.toString(getWidth()));
        //Log.e("rs", "height=" + Float.toString(getHeight()));

        mRender.setTouch(ret);

        if((ny > 0.85f) || mControlMode) {
            mFlingMode = false;

            // Projector control
            if((nx > 0.2f) && (nx < 0.8f) || mControlMode) {
                if(act != ev.ACTION_UP) {
                    float zoom = mMaxZoom;
                    if(mControlMode) {
                        if(!mZoomMode) {
                            zoom = 1.f;
                        }
                        float dx = nx - mFlingX;

                        if((ny < 0.9) && mZoomMode) {
                            zoom = mMaxZoom - ((0.9f - ny) * 10.f);
                            if(zoom < 1) {
                                zoom = 1;
                                mZoomMode = false;
                            }
                            mOldColumn = mColumn;
                        }
                        mColumn += dx * 4;// * zoom;
                        if(zoom > 1.01f) {
                            mColumn += (mZoom - zoom) * (nx - 0.5f) * 4 * zoom;
                        }
                    } else {
                        mOldColumn = mColumn;
                        mColumn = ((float)mColumns) / 2;
                        mControlMode = true;
                        mZoomMode = true;
                    }
                    mZoom = zoom;
                    mFlingX = nx;
                    mRender.setZoom(zoom);
                    if(mZoom < 1.01f) {
                        computeSelection(nx, ny);
                    }
                } else {
                    mControlMode = false;
                    mColumn = mOldColumn;
                    mRender.setZoom(1.f);
                    mRender.setSelected(-1);
                }
            } else {
                // Do something with corners here....
            }
            setColumn(true);

        } else {
            // icon control
            if(act != ev.ACTION_UP) {
                if(mFlingMode) {
                    mColumn += (mFlingX - nx) * 4;
                    setColumn(true);
                }
                mFlingMode = true;
                mFlingX = nx;
                mFlingY = ny;
            } else {
                mFlingMode = false;
                mColumn = (float)(java.lang.Math.floor(mColumn * 0.25f + 0.3f) * 4.f) + 1.f;
                setColumn(true);
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


