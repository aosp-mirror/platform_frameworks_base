/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.android.test.dynamic;

import android.app.Activity;
import android.graphics.drawable.VectorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.GridLayout;

@SuppressWarnings({"UnusedDeclaration"})
public class VectorDrawable01 extends Activity {
    private static final String LOGCAT = "VectorDrawable1";
    int[] icon = {
            R.drawable.vector_drawable01,
            R.drawable.vector_drawable02,
            R.drawable.vector_drawable03,
            R.drawable.vector_drawable04,
            R.drawable.vector_drawable05,
            R.drawable.vector_drawable06,
            R.drawable.vector_drawable07,
            R.drawable.vector_drawable08,
            R.drawable.vector_drawable09,
            R.drawable.vector_drawable10,
            R.drawable.vector_drawable11,
            R.drawable.vector_drawable12,
            R.drawable.vector_drawable13,
            R.drawable.vector_drawable14,
            R.drawable.vector_drawable15,
            R.drawable.vector_drawable16,
            R.drawable.vector_drawable17,
            R.drawable.vector_drawable18,
            R.drawable.vector_drawable19,
            R.drawable.vector_drawable20
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GridLayout container = new GridLayout(this);
        container.setColumnCount(5);
        container.setBackgroundColor(0xFF888888);
        final Button []bArray = new Button[icon.length];

        CheckBox toggle = new CheckBox(this);
        toggle.setText("Toggle");
        toggle.setChecked(true);
        toggle.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ViewGroup vg = (ViewGroup) buttonView.getParent();
                for (int i = 0, count = vg.getChildCount(); i < count; i++) {
                    View child = vg.getChildAt(i);
                    if (child != buttonView) {
                        child.setEnabled(isChecked);
                    }
                }
            }
        });
        container.addView(toggle);

        for (int i = 0; i < icon.length; i++) {
            Button button = new Button(this);
            bArray[i] = button;
            button.setWidth(200);
            button.setBackgroundResource(icon[i]);
            container.addView(button);
            VectorDrawable vd = (VectorDrawable) button.getBackground();
            vd.setAlpha((i + 1) * (0xFF / (icon.length + 1)));
        }

        setContentView(container);

    }

}
