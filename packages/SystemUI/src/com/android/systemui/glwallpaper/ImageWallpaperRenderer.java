/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.glwallpaper;

import static android.opengl.GLES20.GL_COLOR_BUFFER_BIT;
import static android.opengl.GLES20.glClear;
import static android.opengl.GLES20.glClearColor;
import static android.opengl.GLES20.glViewport;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.UserHandle;
import android.util.Log;
import android.util.Size;

import com.android.systemui.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A GL renderer for image wallpaper.
 */
public class ImageWallpaperRenderer implements GLWallpaperRenderer {
    private static final String TAG = ImageWallpaperRenderer.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final ImageGLProgram mProgram;
    private final ImageGLWallpaper mWallpaper;
    private final Rect mSurfaceSize = new Rect();
    private final WallpaperTexture mTexture;
    private Consumer<Bitmap> mOnBitmapUpdated;

    public ImageWallpaperRenderer(Context context) {
        final WallpaperManager wpm = context.getSystemService(WallpaperManager.class);
        if (wpm == null) {
            Log.w(TAG, "WallpaperManager not available");
        }

        mTexture = new WallpaperTexture(wpm);
        mProgram = new ImageGLProgram(context);
        mWallpaper = new ImageGLWallpaper(mProgram);
    }

    /**
     * @hide
     */
    public void setOnBitmapChanged(Consumer<Bitmap> c) {
        mOnBitmapUpdated = c;
    }

    /**
     * @hide
     */
    public void use(Consumer<Bitmap> c) {
        mTexture.use(c);
    }

    @Override
    public boolean isWcgContent() {
        return mTexture.isWcgContent();
    }

    @Override
    public void onSurfaceCreated() {
        glClearColor(0f, 0f, 0f, 1.0f);
        mProgram.useGLProgram(
                R.raw.image_wallpaper_vertex_shader, R.raw.image_wallpaper_fragment_shader);

        mTexture.use(bitmap -> {
            if (bitmap == null) {
                Log.w(TAG, "reload texture failed!");
            } else if (mOnBitmapUpdated != null) {
                mOnBitmapUpdated.accept(bitmap);
            }
            mWallpaper.setup(bitmap);
        });
    }

    @Override
    public void onSurfaceChanged(int width, int height) {
        glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame() {
        glClear(GL_COLOR_BUFFER_BIT);
        glViewport(0, 0, mSurfaceSize.width(), mSurfaceSize.height());
        mWallpaper.useTexture();
        mWallpaper.draw();
    }

    @Override
    public Size reportSurfaceSize() {
        mSurfaceSize.set(mTexture.getTextureDimensions());
        return new Size(mSurfaceSize.width(), mSurfaceSize.height());
    }

    @Override
    public void finish() {
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter out, String[] args) {
        out.print(prefix); out.print("mSurfaceSize="); out.print(mSurfaceSize);
        out.print(prefix); out.print("mWcgContent="); out.print(isWcgContent());
        mWallpaper.dump(prefix, fd, out, args);
    }

    static class WallpaperTexture {
        private final AtomicInteger mRefCount;
        private final Rect mDimensions;
        private final WallpaperManager mWallpaperManager;
        private Bitmap mBitmap;
        private boolean mWcgContent;
        private boolean mTextureUsed;

        private WallpaperTexture(WallpaperManager wallpaperManager) {
            mWallpaperManager = wallpaperManager;
            mRefCount = new AtomicInteger();
            mDimensions = new Rect();
        }

        public void use(Consumer<Bitmap> consumer) {
            mRefCount.incrementAndGet();
            synchronized (mRefCount) {
                if (mBitmap == null) {
                    mBitmap = mWallpaperManager.getBitmapAsUser(UserHandle.USER_CURRENT,
                            false /* hardware */);
                    mWcgContent = mWallpaperManager.wallpaperSupportsWcg(
                            WallpaperManager.FLAG_SYSTEM);
                    mWallpaperManager.forgetLoadedWallpaper();
                    if (mBitmap != null) {
                        mDimensions.set(0, 0, mBitmap.getWidth(), mBitmap.getHeight());
                        mTextureUsed = true;
                    } else {
                        Log.w(TAG, "Can't get bitmap");
                    }
                }
            }
            if (consumer != null) {
                consumer.accept(mBitmap);
            }
            synchronized (mRefCount) {
                final int count = mRefCount.decrementAndGet();
                if (count == 0 && mBitmap != null) {
                    if (DEBUG) {
                        Log.v(TAG, "WallpaperTexture: release 0x" + getHash()
                                + ", refCount=" + count);
                    }
                    mBitmap.recycle();
                    mBitmap = null;
                }
            }
        }

        private boolean isWcgContent() {
            return mWcgContent;
        }

        private String getHash() {
            return mBitmap != null ? Integer.toHexString(mBitmap.hashCode()) : "null";
        }

        private Rect getTextureDimensions() {
            if (!mTextureUsed) {
                mDimensions.set(mWallpaperManager.peekBitmapDimensions());
            }
            return mDimensions;
        }

        @Override
        public String toString() {
            return "{" + getHash() + ", " + mRefCount.get() + "}";
        }
    }
}
