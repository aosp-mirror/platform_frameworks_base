/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.LinearLayout;

import com.android.frameworks.coretests.R;


/**
 * Tests views with popupWindows becoming invisible
 */
public class PreDrawListener extends Activity implements OnClickListener {
    
    private MyLinearLayout mFrame;


    static public class MyLinearLayout extends LinearLayout implements
            ViewTreeObserver.OnPreDrawListener {

        public boolean mCancelNextDraw;
        
        public MyLinearLayout(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public MyLinearLayout(Context context) {
            super(context);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnPreDrawListener(this);
        }
        
        public boolean onPreDraw() {
            if (mCancelNextDraw) {
                Button b = new Button(this.getContext());
                b.setText("Hello");
                addView(b, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT));
                mCancelNextDraw = false;
                return false;
            }
            return true;
        }
        
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.pre_draw_listener);

        mFrame = findViewById(R.id.frame);

        Button mGoButton = findViewById(R.id.go);
        mGoButton.setOnClickListener(this);
    }


    public void onClick(View v) {
        mFrame.mCancelNextDraw = true;
        mFrame.invalidate();
    }



}
