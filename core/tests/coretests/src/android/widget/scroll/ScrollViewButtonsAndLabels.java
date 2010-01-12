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

package android.widget.scroll;

import com.android.frameworks.coretests.R;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Button;


/**
 * Basic scroll view example
 */
public class ScrollViewButtonsAndLabels extends Activity {

    private ScrollView mScrollView;
    private LinearLayout mLinearLayout;

    private int mNumGroups = 10;


    public ScrollView getScrollView() {
        return mScrollView;
    }

    public LinearLayout getLinearLayout() {
        return mLinearLayout;
    }

    public int getNumButtons() {
        return mNumGroups;
    }

    public Button getButton(int groupNum) {
        if (groupNum > mNumGroups) {
            throw new IllegalArgumentException("groupNum > " + mNumGroups);
        }
        return (Button) mLinearLayout.getChildAt(2*groupNum);
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.scrollview_linear_layout);


        // estimated ratio to get enough buttons so a couple are off screen
        int screenHeight = getWindowManager().getDefaultDisplay().getHeight();
        mNumGroups = screenHeight / 30;

        mScrollView = (ScrollView) findViewById(R.id.scrollView);
        mLinearLayout = (LinearLayout) findViewById(R.id.layout);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );

        for (int i = 0; i < mNumGroups; i++) {
            // want button to be first and last
            if (i > 0) {
                TextView textView = new TextView(this);
                textView.setText("Text View " + i);
                mLinearLayout.addView(textView, p);
            }

            Button button = new Button(this);
            button.setText("Button " + (i + 1));
            mLinearLayout.addView(button, p);
        }
    }

}
