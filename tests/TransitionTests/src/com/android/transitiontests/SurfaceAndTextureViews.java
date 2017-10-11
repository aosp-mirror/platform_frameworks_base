/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.transitiontests;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.transition.Crossfade;
import android.transition.ChangeBounds;
import android.transition.Scene;
import android.transition.TransitionSet;
import android.transition.TransitionManager;
import android.widget.Button;

import static android.widget.LinearLayout.LayoutParams;

public class SurfaceAndTextureViews extends Activity {

    SimpleView mView;
    SimpleSurfaceView mSurfaceView;
    SimpleTextureView mTextureView;
    private static final int SMALL_SIZE = 200;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.surface_texture_views);

        final ViewGroup container = findViewById(R.id.container);
        Button toggleButton = findViewById(R.id.toggleButton);

        mView = new SimpleView(this);
        mView.setId(0);
        mView.setLayoutParams(new LayoutParams(SMALL_SIZE, SMALL_SIZE));
        container.addView(mView);

        mSurfaceView = new SimpleSurfaceView(this);
        mSurfaceView.setId(1);
        mSurfaceView.setLayoutParams(new LayoutParams(SMALL_SIZE, SMALL_SIZE));
        container.addView(mSurfaceView);

        mTextureView = new SimpleTextureView(this);
        mTextureView.setId(2);
        mTextureView.setLayoutParams(new LayoutParams(SMALL_SIZE, SMALL_SIZE));
        container.addView(mTextureView);

        final TransitionSet transition = new TransitionSet();
        transition.addTransition(new ChangeBounds()).addTransition(new Crossfade().addTarget(0).
                addTarget(1).addTarget(2));

        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Scene newScene = new Scene(container);
                newScene.setEnterAction(new Runnable() {
                    @Override
                    public void run() {
                        if (mView.getWidth() <= SMALL_SIZE) {
                            mView.setLayoutParams(new LayoutParams(SMALL_SIZE * 2, SMALL_SIZE));
                            mSurfaceView.setLayoutParams(new LayoutParams(SMALL_SIZE * 2, SMALL_SIZE));
                            mTextureView.setLayoutParams(new LayoutParams(SMALL_SIZE * 2, SMALL_SIZE));
                            mView.mColor = SimpleView.LARGE_COLOR;
                            mSurfaceView.mColor = SimpleSurfaceView.LARGE_COLOR;
                            mTextureView.mColor = SimpleTextureView.LARGE_COLOR;
                        } else {
                            mView.setLayoutParams(new LayoutParams(SMALL_SIZE, SMALL_SIZE));
                            mSurfaceView.setLayoutParams(new LayoutParams(SMALL_SIZE, SMALL_SIZE));
                            mTextureView.setLayoutParams(new LayoutParams(SMALL_SIZE, SMALL_SIZE));
                            mView.mColor = SimpleView.SMALL_COLOR;
                            mSurfaceView.mColor = SimpleSurfaceView.SMALL_COLOR;
                            mTextureView.mColor = SimpleTextureView.SMALL_COLOR;
                        }
                    }
                });
                TransitionManager.go(newScene, transition);
            }
        });

    }

    static private class SimpleView extends View {
        static final int SMALL_COLOR = Color.BLUE;
        static final int LARGE_COLOR = Color.YELLOW;
        int mColor = SMALL_COLOR;

        private SimpleView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(mColor);
        }
    }

    static private class SimpleSurfaceView extends SurfaceView implements SurfaceHolder.Callback {

        static final int SMALL_COLOR = Color.GREEN;
        static final int LARGE_COLOR = Color.GRAY;
        int mColor = SMALL_COLOR;
        SurfaceHolder mHolder = null;

        private SimpleSurfaceView(Context context) {
            super(context);
            SurfaceHolder holder = getHolder();
            holder.addCallback(this);
        }


        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            System.out.println("surfaceCreated");
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            System.out.println("surfaceChanged: w h = " + width + ", " + height);
            Canvas canvas = holder.lockCanvas();
            canvas.drawColor(mColor);
            holder.unlockCanvasAndPost(canvas);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            System.out.println("surfaceDestroyed");
        }
    }

    static private class SimpleTextureView extends TextureView implements TextureView.SurfaceTextureListener {

        static final int SMALL_COLOR = Color.RED;
        static final int LARGE_COLOR = Color.CYAN;
        int mColor = SMALL_COLOR;

        private SimpleTextureView(Context context) {
            super(context);
            setSurfaceTextureListener(this);
        }

        private SimpleTextureView(Context context, AttributeSet attrs) {
            super(context, attrs);
            setSurfaceTextureListener(this);
        }

        private SimpleTextureView(Context context, AttributeSet attrs, int defStyle) {
            super(context, attrs, defStyle);
            setSurfaceTextureListener(this);
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            System.out.println("SurfaceTexture available");
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            System.out.println("SurfaceTexture size changed to " + width + ", " + height);
            Canvas canvas = lockCanvas();
            canvas.drawColor(mColor);
            unlockCanvasAndPost(canvas);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            System.out.println("SurfaceTexture updated");
        }
    }
}
