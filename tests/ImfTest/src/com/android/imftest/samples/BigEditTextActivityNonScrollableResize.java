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

import com.android.imftest.R;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;

public class BigEditTextActivityNonScrollableResize extends Activity {
    
    private View mRootView;
    private View mDefaultFocusedView;
    
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        
        mRootView = new LinearLayout(this);
        ((LinearLayout) mRootView).setOrientation(LinearLayout.VERTICAL);
        mRootView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        
        View view = getLayoutInflater().inflate(
                R.layout.full_screen_edit_text, ((LinearLayout) mRootView), false);
        
        ((LinearLayout) mRootView).addView(view);
        
        mDefaultFocusedView = view.findViewById(R.id.data);
        
        setContentView(mRootView);
    }

    public View getRootView() {
        return mRootView;
    }
    
    public View getDefaultFocusedView() {
        return mDefaultFocusedView;
    }

}
