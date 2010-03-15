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
import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

public class EditTextActivityDialog extends Activity {
    
    private static final int SCROLLABLE_DIALOG_ID = 0;
    private static final int NONSCROLLABLE_DIALOG_ID = 1;
    
    private LinearLayout mLayout;
    private ScrollView mScrollView;
    private LayoutInflater mInflater;
    private Button mButton1;
    private Button mButton2;
    
    
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        
        mLayout = new LinearLayout(this);
        mLayout.setOrientation(LinearLayout.VERTICAL);
        mLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        
        mButton1 = new Button(this);
        mButton1.setText(R.string.open_dialog_scrollable);
        mButton1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showDialog(SCROLLABLE_DIALOG_ID);
            }
        });
        
        mButton2 = new Button(this);
        mButton2.setText(R.string.open_dialog_nonscrollable);
        mButton2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showDialog(NONSCROLLABLE_DIALOG_ID);
            }
        });
        
        mLayout.addView(mButton1);
        mLayout.addView(mButton2);
        
        setContentView(mLayout);
    }
    
    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case SCROLLABLE_DIALOG_ID:
                return createDialog(true);
            case NONSCROLLABLE_DIALOG_ID:
                return createDialog(false);
        }

        return super.onCreateDialog(id);
    }
    
    protected Dialog createDialog(boolean scrollable) {
        View layout;
        EditText editText;
        
        if (scrollable) {
            layout = new ScrollView(EditTextActivityDialog.this);
            ((ScrollView) layout).setMinimumHeight(mLayout.getHeight());
            
            ((ScrollView) layout).addView((
                    LinearLayout) View.inflate(EditTextActivityDialog.this, 
                    R.layout.dialog_edit_text_no_scroll, null));
        } else {
            layout = View.inflate(EditTextActivityDialog.this, 
                    R.layout.dialog_edit_text_no_scroll, null);
        }
        
        Dialog d = new Dialog(EditTextActivityDialog.this);
        d.setTitle(getString(R.string.test_dialog));
        d.setCancelable(true);
        d.setContentView(layout);
        return d;
    }

}
