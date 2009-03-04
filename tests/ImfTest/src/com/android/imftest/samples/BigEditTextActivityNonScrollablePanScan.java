package com.android.imftest.samples;

import com.android.imftest.R;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

public class BigEditTextActivityNonScrollablePanScan extends Activity {
    
    private View mRootView;
    private View mDefaultFocusedView;
    
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        
        mRootView = new LinearLayout(this);
        ((LinearLayout) mRootView).setOrientation(LinearLayout.VERTICAL);
        mRootView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT,
                ViewGroup.LayoutParams.FILL_PARENT));
        
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
