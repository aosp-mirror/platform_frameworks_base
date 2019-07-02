/**
 * Copyright (c) 2018 The Android Open Source Project
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

package com.google.android.test.activityview;

import android.app.Activity;
import android.app.ActivityView;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;

public class ActivityViewResizeActivity extends Activity {
    private static final int SMALL_SIZE = 600;
    private static final int LARGE_SIZE = 1200;

    private ActivityView mActivityView;

    private boolean mFlipSize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_view_resize_activity);

        mActivityView = findViewById(R.id.activity_view);

        final Button launchButton = findViewById(R.id.activity_launch_button);
        launchButton.setOnClickListener(v -> {
            final Intent intent = new Intent(this, ActivityViewTestActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            mActivityView.startActivity(intent);
        });
        final Button resizeButton = findViewById(R.id.activity_resize_button);
        if (resizeButton != null) {
            resizeButton.setOnClickListener(v -> {
                LinearLayout.LayoutParams params =
                        (LinearLayout.LayoutParams) mActivityView.getLayoutParams();
                params.height = mFlipSize ? SMALL_SIZE : LARGE_SIZE;
                mFlipSize = !mFlipSize;
                mActivityView.setLayoutParams(params);
            });
        }
        final SeekBar seekBar = findViewById(R.id.activity_view_seek_bar);
        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    final LinearLayout.LayoutParams params =
                            (LinearLayout.LayoutParams) mActivityView.getLayoutParams();
                    params.height = SMALL_SIZE + progress * 10;
                    mActivityView.setLayoutParams(params);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }
    }
}
