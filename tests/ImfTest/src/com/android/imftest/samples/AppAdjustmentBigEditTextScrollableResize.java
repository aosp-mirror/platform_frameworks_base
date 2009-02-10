package com.android.imftest.samples;

import com.android.imftest.R;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

public class AppAdjustmentBigEditTextScrollableResize extends Activity {
    
    private ScrollView mScrollView;
    private LinearLayout mLayout;
    
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        
        mScrollView = new ScrollView(this);
        mScrollView.setFillViewport(true);
        mScrollView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT,
                ViewGroup.LayoutParams.FILL_PARENT));
        
        mLayout = new LinearLayout(this);
        mLayout.setOrientation(LinearLayout.VERTICAL);
        mLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT,
                ViewGroup.LayoutParams.FILL_PARENT));
        
        EditText editText = (EditText) getLayoutInflater().inflate(R.layout.full_screen_edit_text, mScrollView, false);
        
        mLayout.addView(editText);
        mScrollView.addView(mLayout);
        
        setContentView(mScrollView);
    }

}
