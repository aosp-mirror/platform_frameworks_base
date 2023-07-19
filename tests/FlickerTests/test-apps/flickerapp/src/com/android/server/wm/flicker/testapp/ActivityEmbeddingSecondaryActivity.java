/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.wm.flicker.testapp;

import android.app.Activity;
import android.content.Intent;
import android.app.PictureInPictureParams;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;

/**
 * Activity to be used as the secondary activity to split with
 * {@link ActivityEmbeddingMainActivity}.
 */
public class ActivityEmbeddingSecondaryActivity extends Activity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_embedding_secondary_activity_layout);
        findViewById(R.id.secondary_activity_layout).setBackgroundColor(Color.YELLOW);
        findViewById(R.id.finish_secondary_activity_button).setOnClickListener(
              new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finish();
                    }
            });
        findViewById(R.id.secondary_enter_pip_button).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        PictureInPictureParams.Builder picInPicParamsBuilder =
                                new PictureInPictureParams.Builder();
                        enterPictureInPictureMode(picInPicParamsBuilder.build());
                    }
                }
        );
    }

    public void launchThirdActivity(View view) {
        startActivity(new Intent().setComponent(
                ActivityOptions.ActivityEmbedding.ThirdActivity.COMPONENT));
    }
}
