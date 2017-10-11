/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.egg.octo;

import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.egg.R;

public class Ocquarium extends Activity {
    ImageView mImageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final float dp = getResources().getDisplayMetrics().density;

        getWindow().setBackgroundDrawableResource(R.drawable.octo_bg);

        FrameLayout bg = new FrameLayout(this);
        setContentView(bg);
        bg.setAlpha(0f);
        bg.animate().setStartDelay(500).setDuration(5000).alpha(1f).start();

        mImageView = new ImageView(this);
        bg.addView(mImageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final OctopusDrawable octo = new OctopusDrawable(getApplicationContext());
        octo.setSizePx((int) (OctopusDrawable.randfrange(40f,180f) * dp));
        mImageView.setImageDrawable(octo);
        octo.startDrift();

        mImageView.setOnTouchListener(new View.OnTouchListener() {
            boolean touching;
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                switch (motionEvent.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (octo.hitTest(motionEvent.getX(), motionEvent.getY())) {
                            touching = true;
                            octo.stopDrift();
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (touching) {
                            octo.moveTo(motionEvent.getX(), motionEvent.getY());
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        touching = false;
                        octo.startDrift();
                        break;
                }
                return true;
            }
        });
    }
}
