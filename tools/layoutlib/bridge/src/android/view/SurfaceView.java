/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.view;

import com.android.layoutlib.bridge.MockView;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;

/**
 * Mock version of the SurfaceView.
 * Only non override public methods from the real SurfaceView have been added in there.
 * Methods that take an unknown class as parameter or as return object, have been removed for now.
 *
 * TODO: generate automatically.
 *
 */
public class SurfaceView extends MockView {

    public SurfaceView(Context context) {
        this(context, null);
    }

    public SurfaceView(Context context, AttributeSet attrs) {
        this(context, attrs , 0);
    }

    public SurfaceView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public SurfaceView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public SurfaceHolder getHolder() {
        return mSurfaceHolder;
    }

    private SurfaceHolder mSurfaceHolder = new SurfaceHolder() {

        @Override
        public boolean isCreating() {
            return false;
        }

        @Override
        public void addCallback(Callback callback) {
        }

        @Override
        public void removeCallback(Callback callback) {
        }

        @Override
        public void setFixedSize(int width, int height) {
        }

        @Override
        public void setSizeFromLayout() {
        }

        @Override
        public void setFormat(int format) {
        }

        @Override
        public void setType(int type) {
        }

        @Override
        public void setKeepScreenOn(boolean screenOn) {
        }

        @Override
        public Canvas lockCanvas() {
            return null;
        }

        @Override
        public Canvas lockCanvas(Rect dirty) {
            return null;
        }

        @Override
        public void unlockCanvasAndPost(Canvas canvas) {
        }

        @Override
        public Surface getSurface() {
            return null;
        }

        @Override
        public Rect getSurfaceFrame() {
            return null;
        }
    };
}

