package com.android.imftest.samples;

import com.android.imftest.R;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;

public class AppAdjustmentBigEditTextNonScrollableResize extends Activity {
    
    private LinearLayout mLayout;
    
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        
        mLayout = new LinearLayout(this);
        mLayout.setOrientation(LinearLayout.VERTICAL);
        mLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT,
                ViewGroup.LayoutParams.FILL_PARENT));
        
        EditText editText = (EditText) getLayoutInflater().inflate(R.layout.full_screen_edit_text, mLayout, false);
        
        mLayout.addView(editText);
        
        setContentView(mLayout);
    }

}
