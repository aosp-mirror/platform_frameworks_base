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

package com.android.test.hwui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.SuggestionSpan;
import android.text.style.UnderlineSpan;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class ViewPropertyAlphaActivity extends Activity {
    
    MyView myViewAlphaDefault, myViewAlphaHandled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.view_properties);

        getWindow().getDecorView().postDelayed(new Runnable() {
            @Override
            public void run() {
                startAnim(R.id.button);
                startAnim(R.id.textview);
                startAnim(R.id.spantext);
                startAnim(R.id.edittext);
                startAnim(R.id.selectedtext);
                startAnim(R.id.textviewbackground);
                startAnim(R.id.layout);
                startAnim(R.id.imageview);
                startAnim(myViewAlphaDefault);
                startAnim(myViewAlphaHandled);
                EditText selectedText = findViewById(R.id.selectedtext);
                selectedText.setSelection(3, 8);
            }
        }, 2000);
        
        Button invalidator = findViewById(R.id.invalidateButton);
        invalidator.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                findViewById(R.id.textview).invalidate();
                findViewById(R.id.spantext).invalidate();
            }
        });

        TextView textView = findViewById(R.id.spantext);
        if (textView != null) {
            SpannableStringBuilder text =
                    new SpannableStringBuilder("Now this is a short text message with spans");

            text.setSpan(new BackgroundColorSpan(Color.RED), 0, 3,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(Color.BLUE), 4, 9,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new SuggestionSpan(this, new String[]{"longer"}, 3), 11, 16,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new UnderlineSpan(), 17, 20,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ImageSpan(this, R.drawable.icon), 21, 22,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

            textView.setText(text);
        }
        
        LinearLayout container = findViewById(R.id.container);
        myViewAlphaDefault = new MyView(this, false);
        myViewAlphaDefault.setLayoutParams(new LinearLayout.LayoutParams(75, 75));
        container.addView(myViewAlphaDefault);
        myViewAlphaHandled = new MyView(this, true);
        myViewAlphaHandled.setLayoutParams(new LinearLayout.LayoutParams(75, 75));
        container.addView(myViewAlphaHandled);
    }

    private void startAnim(View target) {
        ObjectAnimator anim = ObjectAnimator.ofFloat(target, View.ALPHA, 0);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setRepeatMode(ValueAnimator.REVERSE);
        anim.setDuration(1000);
        anim.start();
    }
    private void startAnim(int id) {
        startAnim(findViewById(id));
    }
    
    private static class MyView extends View {
        private int mMyAlpha = 255;
        private boolean mHandleAlpha;
        private Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        
        private MyView(Context context, boolean handleAlpha) {
            super(context);
            mHandleAlpha = handleAlpha;
            mPaint.setColor(Color.RED);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mHandleAlpha) {
                mPaint.setAlpha(mMyAlpha);
            }
            canvas.drawCircle(30, 30, 30, mPaint);
        }

        @Override
        protected boolean onSetAlpha(int alpha) {
            if (mHandleAlpha) {
                mMyAlpha = alpha;
                return true;
            }
            return super.onSetAlpha(alpha);
        }
    }

}
