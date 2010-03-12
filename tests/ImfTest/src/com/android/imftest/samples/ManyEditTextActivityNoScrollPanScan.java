/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.imftest.samples;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ScrollView;

import com.android.internal.R;

/*
 * Full screen of EditTexts (Non-Scrollable, Pan&Scan)
 */
public class ManyEditTextActivityNoScrollPanScan extends Activity 
{
    public static final int NUM_EDIT_TEXTS = 9;

    private View mRootView;

    @Override
    public void onCreate(Bundle savedInstanceState) 
    {
        super.onCreate(savedInstanceState);

        mRootView = new LinearLayout(this);
        ((LinearLayout) mRootView).setOrientation(LinearLayout.VERTICAL);

        for (int i=0; i<NUM_EDIT_TEXTS; i++) 
        {
            final EditText editText = new EditText(this);
            editText.setText(String.valueOf(i));
            editText.setId(i);
            ((LinearLayout) mRootView).addView(editText);
        }
        setContentView(mRootView);
        this.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
    }  

    public View getRootView() {
        return mRootView;
    }

}
