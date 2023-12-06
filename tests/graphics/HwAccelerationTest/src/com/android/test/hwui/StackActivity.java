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
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.StackView;
import android.widget.TextView;

@SuppressWarnings({"UnusedDeclaration"})
public class StackActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.stack);

        StackView stack = findViewById(R.id.stack_view);
        stack.setAdapter(new ArrayAdapter<Drawable>(this, android.R.layout.simple_list_item_1,
                android.R.id.text1, new Drawable[] {
            getResources().getDrawable(R.drawable.sunset1),
            getResources().getDrawable(R.drawable.sunset2),
        }) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View item = convertView;
                if (item == null) {
                    item = LayoutInflater.from(getContext()).inflate(
                            R.layout.stack_item, null, false);                    
                }
                ((ImageView) item.findViewById(R.id.textview_icon)).setImageDrawable(
                        getItem(position % getCount()));
                ((TextView) item.findViewById(R.id.mini_text)).setText("" + position);
                return item;
            }
        });
        stack.setDisplayedChild(0);
    }
}
