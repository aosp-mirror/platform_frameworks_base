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
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ViewFlipper;

@SuppressWarnings({"UnusedDeclaration"})
public class ViewFlipperActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final LayoutInflater inflater = getLayoutInflater();
        final View widget = inflater.inflate(R.layout.widget, null);
        widget.setLayoutParams(new FrameLayout.LayoutParams(180, 180, Gravity.CENTER));
        
        ViewFlipper flipper = (ViewFlipper) widget.findViewById(R.id.flipper);
        
        View view = inflater.inflate(R.layout.flipper_item, flipper, false);
        flipper.addView(view);
        ((ImageView) view.findViewById(R.id.widget_image)).setImageResource(R.drawable.sunset1);
        ((TextView) view.findViewById(R.id.widget_text)).setText("This is a long line of text, " 
                + "enjoy the wrapping and drawing");

        view = inflater.inflate(R.layout.flipper_item, flipper, false);
        flipper.addView(view);
        ((ImageView) view.findViewById(R.id.widget_image)).setImageResource(R.drawable.sunset3);
        ((TextView) view.findViewById(R.id.widget_text)).setText("Another very long line of text, " 
                + "enjoy the wrapping and drawing");

        FrameLayout layout = new FrameLayout(this);
        layout.addView(widget);
        
        setContentView(layout);
    }
}
