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

package com.google.android.test.hwui;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.StackView;

@SuppressWarnings({"UnusedDeclaration"})
public class StackActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        StackView stack = new StackView(this);
        stack.setAdapter(new ArrayAdapter<Drawable>(this, android.R.layout.simple_list_item_1,
                android.R.id.text1, new Drawable[] {
            getResources().getDrawable(R.drawable.sunset1),
            getResources().getDrawable(R.drawable.sunset2),
            getResources().getDrawable(R.drawable.sunset1),
            getResources().getDrawable(R.drawable.sunset2),
            getResources().getDrawable(R.drawable.sunset1),
            getResources().getDrawable(R.drawable.sunset2)                
        }) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ImageView image;
                if (convertView == null) {
                    image = new ImageView(StackActivity.this);
                } else {
                    image = (ImageView) convertView;
                }
                image.setImageDrawable(getItem(position % getCount()));
                return image;
            }
        });
        stack.setDisplayedChild(0);

        FrameLayout layout = new FrameLayout(this);
        layout.addView(stack, new FrameLayout.LayoutParams(500, 500, Gravity.CENTER));
        setContentView(layout);
    }
}
