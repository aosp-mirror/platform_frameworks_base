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
import android.graphics.drawable.Drawable;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

/**
 * Default built-in wallpaper that simply shows a static image.
 */
public class ImageWallpaper extends WallpaperService {
    public WallpaperManager mWallpaperManager;
    
    class MyEngine extends Engine {

        Drawable mBackground;
        
        @Override
        public void onAttach(SurfaceHolder surfaceHolder) {
            super.onAttach(surfaceHolder);
            mBackground = mWallpaperManager.getDrawable();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            Canvas c = holder.lockCanvas();
            mBackground.setBounds(0, 0, width, height);
            mBackground.draw(c);
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            final float density = getResources().getDisplayMetrics().density;
            paint.setTextSize(30 * density);
            paint.setShadowLayer(5*density, 3*density, 3*density, 0xff000000);
            paint.setARGB(255, 255, 255, 255);
            c.drawText("Am I live?", 10, 60*density, paint);
            holder.unlockCanvasAndPost(c);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
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
