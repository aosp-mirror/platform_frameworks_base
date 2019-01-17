/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.widget.focus;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import com.android.frameworks.coretests.R;

/**
 * Exercises cases where elements of the UI are removed (and
 * focus should go somewhere).
 */
public class FocusAfterRemoval extends Activity {


    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.focus_after_removal);

        final LinearLayout left = findViewById(R.id.leftLayout);

        // top left makes parent layout GONE
        Button topLeftButton = findViewById(R.id.topLeftButton);
        topLeftButton.setOnClickListener(new View.OnClickListener() {

            public void onClick(View v) {
                left.setVisibility(View.GONE);
            }
        });

        // bottom left makes parent layout INVISIBLE
        // top left makes parent layout GONE
        Button bottomLeftButton = findViewById(R.id.bottomLeftButton);
        bottomLeftButton.setOnClickListener(new View.OnClickListener() {

            public void onClick(View v) {
                left.setVisibility(View.INVISIBLE);
            }
        });

        // top right button makes top right button GONE
        final Button topRightButton = findViewById(R.id.topRightButton);
        topRightButton.setOnClickListener(new View.OnClickListener() {

            public void onClick(View v) {
                topRightButton.setVisibility(View.GONE);
            }
        });

        // bottom right button makes bottom right button INVISIBLE
        final Button bottomRightButton = findViewById(R.id.bottomRightButton);
        bottomRightButton.setOnClickListener(new View.OnClickListener() {

            public void onClick(View v) {
                bottomRightButton.setVisibility(View.INVISIBLE);
            }
        });

    }
}
