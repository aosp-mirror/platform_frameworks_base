/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.service.wallpaper;

import android.app.WallpaperManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

/**
 * Default built-in wallpaper that simply shows a static image.
 */
public class ImageWallpaper extends WallpaperService {
    public WallpaperManager mWallpaperManager;
    
    static final int MSG_DRAW = 1;
    
    class MyEngine extends Engine {
        final Paint mTextPaint = new Paint();
        float mDensity;
        Drawable mBackground;
        long mAnimStartTime;
        boolean mAnimLarger;
        
        final Handler mHandler = new Handler() {

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_DRAW:
                        drawFrame(true);
                        mHandler.sendEmptyMessage(MSG_DRAW);
                        break;
                    default:
                        super.handleMessage(msg);
                }
            }
        };
        
        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            mBackground = mWallpaperManager.getDrawable();
            mTextPaint.setAntiAlias(true);
            mDensity = getResources().getDisplayMetrics().density;
            mTextPaint.setTextSize(30 * mDensity);
            mTextPaint.setShadowLayer(5*mDensity, 3*mDensity, 3*mDensity, 0xff000000);
            mTextPaint.setARGB(255, 255, 255, 255);
            mTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            mHandler.removeMessages(MSG_DRAW);
            if (visible) {
                mHandler.sendEmptyMessage(MSG_DRAW);
                mAnimStartTime = SystemClock.uptimeMillis();
                mAnimLarger = true;
            } else {
                drawFrame(false);
            }
        }
        
        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            drawFrame(false);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
        }
        
        void drawFrame(boolean drawText) {
            SurfaceHolder sh = getSurfaceHolder();
            Canvas c = sh.lockCanvas();
            if (c != null) {
                final Rect frame = sh.getSurfaceFrame();
                mBackground.setBounds(frame);
                mBackground.draw(c);
                
                if (drawText) {
                    // Figure out animation.
                    long now = SystemClock.uptimeMillis();
                    while (mAnimStartTime < (now-1000)) {
                        mAnimStartTime += 1000;
                        mAnimLarger = !mAnimLarger;
                    }
                    float size = (now-mAnimStartTime) / (float)1000;
                    if (!mAnimLarger) size = 1-size;
                    int alpha = (int)(255*(size*size));
                    mTextPaint.setARGB(alpha, 255, 255, 255);
                    mTextPaint.setShadowLayer(5*mDensity, 3*mDensity, 3*mDensity,
                            alpha<<24);
                    mTextPaint.setTextSize(100 * mDensity * size);
                    c.drawText("Am I live?",
                            frame.left + (frame.right-frame.left)/2,
                            frame.top + (frame.bottom-frame.top)/2, mTextPaint);
                }
            }
            sh.unlockCanvasAndPost(c);
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        mWallpaperManager = (WallpaperManager)getSystemService(WALLPAPER_SERVICE);
    }
    
    public Engine onCreateEngine() {
        return new MyEngine();
    }
}
