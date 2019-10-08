/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.test.uibench;

import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import android.widget.ImageView;

import com.android.test.uibench.ActivityTransition;
import com.android.test.uibench.R;


public class ActivityTransitionDetails extends AppCompatActivity {
    private static final String KEY_ID = "ViewTransitionValues:id";
    private int mImageResourceId = R.drawable.ducky;
    private String mName = "ducky";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.DKGRAY));
        setContentView(R.layout.activity_transition_details);
        ImageView titleImage = findViewById(R.id.titleImage);
        titleImage.setImageDrawable(getHeroDrawable());
    }

    private Drawable getHeroDrawable() {
        String name = getIntent().getStringExtra(KEY_ID);
        if (name != null) {
            mName = name;
            mImageResourceId = ActivityTransition.getDrawableIdForKey(name);
        }

        return getResources().getDrawable(mImageResourceId);
    }

    public void clicked(View v) {
        Intent intent = new Intent(this, ActivityTransition.class);
        intent.putExtra(KEY_ID, mName);
        ActivityOptions activityOptions = ActivityOptions.makeSceneTransitionAnimation(
                this, v, "hero");
        startActivity(intent, activityOptions.toBundle());
    }
}
