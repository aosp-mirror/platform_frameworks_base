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
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.imftest.R;

/*
 * Activity with EditText at the bottom (Pan&Scan)
 */
public class BottomEditTextActivityPanScan extends Activity 
{
    private View mRootView;
    private View mDefaultFocusedView;
    
    @Override
    public void onCreate(Bundle savedInstanceState) 
    {
        super.onCreate(savedInstanceState);
        
        mRootView = new LinearLayout(this);
        ((LinearLayout) mRootView).setOrientation(LinearLayout.VERTICAL);
        
        View view = getLayoutInflater().inflate(R.layout.one_edit_text_activity, ((LinearLayout) mRootView), false);
        mDefaultFocusedView = view.findViewById(R.id.dialog_edit_text);
        ((LinearLayout) mRootView).addView(view);
       
        setContentView(mRootView);
        this.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
    }  

    public View getRootView() {
        return mRootView;
    }
    
    public View getDefaultFocusedView() {
        return mDefaultFocusedView;
    }
}
