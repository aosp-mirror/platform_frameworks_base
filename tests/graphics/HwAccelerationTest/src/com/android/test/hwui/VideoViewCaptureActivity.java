/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.PixelCopy;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.FileOutputStream;

public class VideoViewCaptureActivity extends Activity {
    private VideoView mVideoView;
    private int mVideoWidth, mVideoHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mVideoView = new VideoView(this);
        mVideoView.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            mVideoWidth = mp.getVideoWidth();
            mVideoHeight = mp.getVideoHeight();
            mVideoView.start();
        });

        Uri uri = Uri.parse("android.resource://com.android.test.hwui/" + R.raw.colorgrid_video);
        mVideoView.setVideoURI(uri);

        Button button = new Button(this);
        button.setText("Copy bitmap to /sdcard/surfaceview.png");
        button.setOnClickListener((View v) -> {
            final Bitmap b = Bitmap.createBitmap(
                    mVideoWidth, mVideoHeight,
                    Bitmap.Config.ARGB_8888);
            PixelCopy.request(mVideoView, b,
                    (int result) -> {
                        if (result != PixelCopy.SUCCESS) {
                            Toast.makeText(VideoViewCaptureActivity.this,
                                    "Failed to copy", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        try {
                            try (FileOutputStream out = new FileOutputStream(
                                    Environment.getExternalStorageDirectory() + "/surfaceview.png");) {
                                b.compress(Bitmap.CompressFormat.PNG, 100, out);
                            }
                        } catch (Exception e) {
                            // Ignore
                        }
                    }, mVideoView.getHandler());
        });

        FrameLayout content = new FrameLayout(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(button, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.addView(mVideoView, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT);

        content.addView(layout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(content);
    }
}
