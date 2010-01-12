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

package com.google.android.test.transform;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.TransformGestureDetector;
import android.view.View;
import android.widget.LinearLayout;

public class TransformTestActivity extends Activity {
    public TransformTestActivity() {
        super();
        init(false);
    }
    
    public TransformTestActivity(boolean noCompat) {
        super();
        init(noCompat);
    }
    
    public void init(boolean noCompat) {

    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final LayoutInflater li = (LayoutInflater)getSystemService(
                LAYOUT_INFLATER_SERVICE);
        
        this.setTitle(R.string.act_title);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TransformView view = new TransformView(getApplicationContext());
        Drawable drawable = getResources().getDrawable(R.drawable.logo);
        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicWidth());
        view.setDrawable(drawable);

        root.addView(view);
        setContentView(root);
    }
    
    private class TransformView extends View {
        private Drawable mDrawable;
        private float mPosX;
        private float mPosY;
        private float mScale = 1.f;
        private Matrix mMatrix;
        private TransformGestureDetector mDetector;
        
        private class Listener implements TransformGestureDetector.OnTransformGestureListener {

            public boolean onTransform(TransformGestureDetector detector) {
                Log.d("ttest", "Translation: (" + detector.getTranslateX() +
                        ", " + detector.getTranslateY() + ")");
                float scale = detector.getScaleFactor();
                Log.d("ttest", "Scale: " + scale);
                if (mScale * scale > 0.1f) {
                    if (mScale * scale < 10.f) {
                        mScale *= scale;
                    } else {
                        mScale = 10.f;
                    }
                } else {
                    mScale = 0.1f;
                }

                mPosX += detector.getTranslateX();
                mPosY += detector.getTranslateY();
                
                Log.d("ttest", "mScale: " + mScale + " mPos: (" + mPosX + ", " + mPosY + ")");
                
                float sizeX = mDrawable.getIntrinsicWidth()/2;
                float sizeY = mDrawable.getIntrinsicHeight()/2;
                float centerX = detector.getCenterX();
                float centerY = detector.getCenterY();
                float diffX = centerX - mPosX;
                float diffY = centerY - mPosY;
                diffX = diffX*scale - diffX;
                diffY = diffY*scale - diffY;
                mPosX -= diffX;
                mPosY -= diffY;
                mMatrix.reset();
                mMatrix.postTranslate(-sizeX, -sizeY);
                mMatrix.postScale(mScale, mScale);
                mMatrix.postTranslate(mPosX, mPosY);
                                
                invalidate();

                return true;
            }

            public boolean onTransformBegin(TransformGestureDetector detector) {
                return true;
            }

            public boolean onTransformEnd(TransformGestureDetector detector) {
                return true;
            }

            public boolean onTransformFling(TransformGestureDetector detector) {
                return false;
            }
            
        }
        
        public TransformView(Context context) {
            super(context);
            mMatrix = new Matrix();
            mDetector = new TransformGestureDetector(context, new Listener());
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            mPosX = metrics.widthPixels/2;
            mPosY = metrics.heightPixels/2;
        }
        
        public void setDrawable(Drawable d) {
            mDrawable = d;
            
            float sizeX = mDrawable.getIntrinsicWidth()/2;
            float sizeY = mDrawable.getIntrinsicHeight()/2;
            mMatrix.reset();
            mMatrix.postTranslate(-sizeX, -sizeY);
            mMatrix.postScale(mScale, mScale);
            mMatrix.postTranslate(mPosX, mPosY);
        }
        
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            boolean handled = mDetector.onTouchEvent(event);
            
            int pointerCount = event.getPointerCount();
            Log.d("ttest", "pointerCount: " + pointerCount);

            return handled;
        }
        
        @Override
        public void onDraw(Canvas canvas) {
            int saveCount = canvas.getSaveCount();
            canvas.save();
            canvas.concat(mMatrix);
            mDrawable.draw(canvas);
            canvas.restoreToCount(saveCount);
        }
    }
}
