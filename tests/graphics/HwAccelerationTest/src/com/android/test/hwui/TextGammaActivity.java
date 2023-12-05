/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.widget.ImageView;
import android.widget.LinearLayout;

@SuppressWarnings({"UnusedDeclaration"})
public class TextGammaActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        final GammaTextView gamma = new GammaTextView(this);
        layout.addView(gamma, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        setContentView(layout);

        layout.post(new Runnable() {
            public void run() {
                Bitmap b = Bitmap.createBitmap(gamma.getWidth(), gamma.getHeight(),
                        Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                c.drawColor(0, PorterDuff.Mode.CLEAR);
                gamma.draw(c);

                ImageView image = new ImageView(TextGammaActivity.this);
                image.setImageBitmap(b);

                layout.addView(image, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));

                startActivity(new Intent(TextGammaActivity.this, SubGammaActivity.class));
            }
        });

        getWindow().setBackgroundDrawable(new ColorDrawable(0xffffffff));
    }

    static class GammaTextView extends LinearLayout {
        GammaTextView(Context c) {
            super(c);

            setBackgroundColor(0xffffffff);

            final LayoutInflater inflater = LayoutInflater.from(c);
            inflater.inflate(R.layout.text_large, this, true);
            inflater.inflate(R.layout.text_medium, this, true);
            inflater.inflate(R.layout.text_small, this, true);
        }
    }

    public static class SubGammaActivity extends Activity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            final LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);

            final GammaTextView gamma = new GammaTextView(this);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMargins(0, 74, 0, 0);
            layout.addView(gamma, lp);

            setContentView(layout);
        }
    }
}
