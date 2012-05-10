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

package com.android.internal.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageView;
import android.widget.Toast;

public class PlatLogoActivity extends Activity {
    Toast mToast;
    ImageView mContent;
    int mCount;
    final Handler mHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mToast = Toast.makeText(this, "Android X.X: Jelly Bean", Toast.LENGTH_SHORT);

        mContent = new ImageView(this);
        mContent.setImageResource(com.android.internal.R.drawable.platlogo);
        mContent.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        mContent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mToast.show();
                mContent.setImageResource(com.android.internal.R.drawable.platlogo_alt);
            }
        });

        mContent.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_MAIN)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                        .addCategory("com.android.internal.category.PLATLOGO"));
                        //.setClassName("com.android.systemui","com.android.systemui.BeanBag"));
                } catch (ActivityNotFoundException ex) {
                    android.util.Log.e("PlatLogoActivity", "Couldn't find a bag of beans.");
                }
                finish();
                return true;
            }
        });
        
        setContentView(mContent);
    }
}
