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

package android.view;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;

import com.android.frameworks.coretests.R;

/**
 * Exercise View's ability to change their visibility: GONE, INVISIBLE and
 * VISIBLE. 
 */
public class Visibility extends Activity {
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.visibility);

        // Find the view whose visibility will change
        mVictim = findViewById(R.id.victim);

        // Find our buttons
        Button visibleButton = findViewById(R.id.vis);
        Button invisibleButton = findViewById(R.id.invis);
        Button goneButton = findViewById(R.id.gone);

        // Wire each button to a click listener
        visibleButton.setOnClickListener(mVisibleListener);
        invisibleButton.setOnClickListener(mInvisibleListener);
        goneButton.setOnClickListener(mGoneListener);
    }


    View.OnClickListener mVisibleListener = new View.OnClickListener() {
        public void onClick(View v) {
            mVictim.setVisibility(View.VISIBLE);
        }
    };

    View.OnClickListener mInvisibleListener = new View.OnClickListener() {
        public void onClick(View v) {
            mVictim.setVisibility(View.INVISIBLE);
        }
    };

    View.OnClickListener mGoneListener = new View.OnClickListener() {
        public void onClick(View v) {
            mVictim.setVisibility(View.GONE);
        }
    };

    private View mVictim;
}
