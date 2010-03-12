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
import android.widget.LinearLayout;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Button;
import android.widget.TextView;

public class ButtonActivity extends Activity 
{
    static boolean mKeyboardIsActive = false;
    public static final int BUTTON_ID = 0;
    private View mRootView;
    
    @Override
    public void onCreate(Bundle savedInstanceState) 
    {
        super.onCreate(savedInstanceState);
        final ButtonActivity instance = this;
             
        final Button myButton = new Button(this);
        myButton.setClickable(true);
        myButton.setText("Keyboard UP!");
        myButton.setId(BUTTON_ID);
        myButton.setFocusableInTouchMode(true);
        myButton.setOnClickListener(new View.OnClickListener()
        {
            public void onClick (View v)
            {
                InputMethodManager imm = InputMethodManager.getInstance(instance);
                if (mKeyboardIsActive)
                {
                    imm.hideSoftInputFromInputMethod(v.getWindowToken(), 0);
                    myButton.setText("Keyboard UP!");
            
                }
                else
                {
                    myButton.requestFocusFromTouch();
                    imm.showSoftInput(v, 0);
                    myButton.setText("Keyboard DOWN!");
                }
               
                mKeyboardIsActive = !mKeyboardIsActive;
            }
        });
            
       LinearLayout layout = new LinearLayout(this);
       layout.setOrientation(LinearLayout.VERTICAL);
       layout.addView(myButton);
       setContentView(layout);
       mRootView = layout;
    }
    
    public View getRootView() {
        return mRootView;
    }
}
