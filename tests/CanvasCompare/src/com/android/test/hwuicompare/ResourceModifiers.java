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

import com.android.test.hwuicompare.R;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.SweepGradient;
import android.graphics.Matrix;
import android.graphics.Shader;

public class ResourceModifiers {
        public final BitmapShader mRepeatShader;
        public final BitmapShader mTranslatedShader;
        public final BitmapShader mScaledShader;
        private final int mTexWidth;
        private final int mTexHeight;
        private final float mDrawWidth;
        private final float mDrawHeight;
        public final LinearGradient mHorGradient;
        public final LinearGradient mDiagGradient;
        public final LinearGradient mVertGradient;
        public final RadialGradient mRadGradient;
        public final SweepGradient mSweepGradient;
        public final ComposeShader mComposeShader;
        public final ComposeShader mBadComposeShader;
        public final ComposeShader mAnotherBadComposeShader;
        public final Bitmap mBitmap;
        private final Matrix mMtx1;
        private final Matrix mMtx2;
        private final Matrix mMtx3;

        public final float[] mBitmapVertices;
        public final int[] mBitmapColors;

        private static ResourceModifiers sInstance = null;
        public static ResourceModifiers instance() { return sInstance; }
        public static void init(Resources resources) {
            sInstance = new ResourceModifiers(resources);
        }

        public ResourceModifiers(Resources resources) {
            mBitmap = BitmapFactory.decodeResource(resources, R.drawable.sunset1);
            mTexWidth = mBitmap.getWidth();
            mTexHeight = mBitmap.getHeight();

            mDrawWidth = resources.getDimensionPixelSize(R.dimen.layer_width);
            mDrawHeight = resources.getDimensionPixelSize(R.dimen.layer_height);

            mRepeatShader = new BitmapShader(mBitmap, Shader.TileMode.REPEAT,
                    Shader.TileMode.REPEAT);

            mTranslatedShader = new BitmapShader(mBitmap, Shader.TileMode.REPEAT,
                    Shader.TileMode.REPEAT);
            mMtx1 = new Matrix();
            mMtx1.setTranslate(mTexWidth / 2.0f, mTexHeight / 2.0f);
            mMtx1.postRotate(45, 0, 0);
            mTranslatedShader.setLocalMatrix(mMtx1);

            mScaledShader = new BitmapShader(mBitmap, Shader.TileMode.MIRROR,
                    Shader.TileMode.MIRROR);
            mMtx2 = new Matrix();
            mMtx2.setScale(0.5f, 0.5f);
            mScaledShader.setLocalMatrix(mMtx2);

            mHorGradient = new LinearGradient(0.0f, 0.0f, 1.0f, 0.0f,
                    Color.RED, Color.GREEN, Shader.TileMode.CLAMP);
            mMtx3 = new Matrix();
            mMtx3.setScale(mDrawHeight, 1.0f);
            mMtx3.postRotate(-90.0f);
            mMtx3.postTranslate(0.0f, mDrawHeight);
            mHorGradient.setLocalMatrix(mMtx3);

            mDiagGradient = new LinearGradient(0.0f, 0.0f, mDrawWidth / 2.0f, mDrawHeight / 2.0f,
                    Color.BLUE, Color.RED, Shader.TileMode.CLAMP);

            mVertGradient = new LinearGradient(0.0f, 0.0f, 0.0f, mDrawHeight / 2.0f,
                    Color.YELLOW, Color.MAGENTA, Shader.TileMode.MIRROR);

            mSweepGradient = new SweepGradient(mDrawWidth / 2.0f, mDrawHeight / 2.0f,
                    Color.YELLOW, Color.MAGENTA);

            mComposeShader = new ComposeShader(mRepeatShader, mHorGradient,
                    PorterDuff.Mode.MULTIPLY);

            final float width = mBitmap.getWidth() / 8.0f;
            final float height = mBitmap.getHeight() / 8.0f;

            mBitmapVertices = new float[] {
                0.0f, 0.0f, width, 0.0f, width * 2, 0.0f, width * 3, 0.0f,
                0.0f, height, width, height, width * 2, height, width * 4, height,
                0.0f, height * 2, width, height * 2, width * 2, height * 2, width * 3, height * 2,
                0.0f, height * 4, width, height * 4, width * 2, height * 4, width * 4, height * 4,
            };

            mBitmapColors = new int[] {
                0xffff0000, 0xff00ff00, 0xff0000ff, 0xffff0000,
                0xff0000ff, 0xffff0000, 0xff00ff00, 0xff00ff00,
                0xff00ff00, 0xff0000ff, 0xffff0000, 0xff00ff00,
                0x00ff0000, 0x0000ff00, 0x000000ff, 0x00ff0000,
            };

            // Use a repeating gradient with many colors to test the non simple case.
            mRadGradient = new RadialGradient(mDrawWidth / 4.0f, mDrawHeight / 4.0f, 4.0f,
                    mBitmapColors, null, Shader.TileMode.REPEAT);

            mBadComposeShader = new ComposeShader(mRadGradient, mComposeShader,
                    PorterDuff.Mode.MULTIPLY);

            mAnotherBadComposeShader = new ComposeShader(mRadGradient, mVertGradient,
                    PorterDuff.Mode.MULTIPLY);
        }

}
