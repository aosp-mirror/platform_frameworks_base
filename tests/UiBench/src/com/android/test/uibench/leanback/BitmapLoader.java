/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.test.uibench.leanback;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.AsyncTask;
import androidx.collection.LruCache;
import android.util.DisplayMetrics;
import android.widget.ImageView;

/**
 * This class simulates a typical Bitmap memory cache with up to 1.5 times of screen pixels.
 * The sample bitmap is generated in worker threads in AsyncTask.THREAD_POOL_EXECUTOR.
 * The class does not involve decoding, disk cache i/o, network i/o, as the test is mostly focusing
 * on the graphics side.
 * There will be two general use cases for cards in leanback test:
 * 1. As a typical app, each card has its own id and load its own Bitmap, the test result will
 *    include impact of texture upload.
 * 2. All cards share same id/Bitmap and there wont be texture upload.
 */
public class BitmapLoader {

    /**
     * Caches bitmaps with bytes adds up to 1.5 x screen
     * DO NOT CHANGE this defines baseline of test result.
     */
    static final float CACHE_SIZE_TO_SCREEN = 1.5f;
    /**
     * 4 bytes per pixel for RGBA_8888
     */
    static final int BYTES_PER_PIXEL = 4;

    static LruCache<Long, Bitmap> sLruCache;
    static Paint sTextPaint = new Paint();

    static {
        sTextPaint.setColor(Color.BLACK);
    }

    /**
     * get or initialize LruCache, the max is set to full screen pixels.
     */
    static LruCache<Long, Bitmap> getLruCache(Context context) {
        if (sLruCache == null) {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            int width = metrics.widthPixels;
            int height = metrics.heightPixels;
            int maxBytes = (int) (width * height * BYTES_PER_PIXEL * CACHE_SIZE_TO_SCREEN);
            sLruCache = new LruCache<Long, Bitmap>(maxBytes) {
                @Override
                protected int sizeOf(Long key, Bitmap value) {
                    return value.getByteCount();
                }
            };
        }
        return sLruCache;
    }

    static class BitmapAsyncTask extends AsyncTask<Void, Void, Bitmap> {

        ImageView mImageView;
        long mId;
        int mWidth;
        int mHeight;

        BitmapAsyncTask(ImageView view, long id, int width, int height) {
            mImageView = view;
            mId = id;
            mImageView.setTag(this);
            mWidth = width;
            mHeight = height;
        }

        @Override
        protected Bitmap doInBackground(Void... voids) {
            // generate a sample bitmap: white background and text showing id
            Bitmap bitmap = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawARGB(0xff, 0xff, 0xff, 0xff);
            canvas.drawText(Long.toString(mId), 0f, mHeight / 2, sTextPaint);
            canvas.setBitmap(null);
            bitmap.prepareToDraw();
            return bitmap;
        }

        @Override
        protected void onCancelled() {
            if (mImageView.getTag() == this) {
                mImageView.setTag(null);
            }
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (mImageView.getTag() == this) {
                mImageView.setTag(null);
                sLruCache.put(mId, bitmap);
                mImageView.setImageBitmap(bitmap);
            }
        }
    }

    public static void loadBitmap(ImageView view, long id, int width, int height) {
        Context context = view.getContext();
        Bitmap bitmap = getLruCache(context).get(id);
        if (bitmap != null) {
            view.setImageBitmap(bitmap);
            return;
        }
        new BitmapAsyncTask(view, id, width, height)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public static void cancel(ImageView view) {
        BitmapAsyncTask task = (BitmapAsyncTask) view.getTag();
        if (task != null && task.mImageView == view) {
            task.mImageView.setTag(null);
            task.cancel(false);
        }
    }

    public static void clear() {
        if (sLruCache != null) {
            sLruCache.evictAll();
        }
    }
}
