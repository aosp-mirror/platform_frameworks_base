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

package com.android.frameworktest.performance;

import android.app.Activity;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.content.Context;
import android.graphics.Canvas;

public class InvalidateCycle extends Activity {
    private boolean mStartProfiling;
    private InvalidateCycle.AutoInvalidateView mView;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mView = new AutoInvalidateView(this);
        mView.setLayoutParams(new ViewGroup.LayoutParams(16, 16));
        setContentView(mView);

        new Handler().postDelayed(new Runnable() {
            public void run() {
                mStartProfiling = true;
                android.util.Log.d("Performance", "Profiling started");
                Debug.startMethodTracing("invalidateCycle");
                mView.invalidate();
            }
        }, 15000);
    }

    private class AutoInvalidateView extends View {
        private boolean mFirstDraw;

        public AutoInvalidateView(Context context) {
            super(context);
        }

        protected void onDraw(Canvas canvas) {
            if (mStartProfiling && !mFirstDraw) {
                Debug.stopMethodTracing();
                android.util.Log.d("Performance", "Profiling ended");
                mFirstDraw = true;
            }
            canvas.drawColor(0xFFFF0000);            
        }
    }
}
