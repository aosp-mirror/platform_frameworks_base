package com.android.imftest.samples;

import android.app.Activity;
import android.os.Bundle;
import android.os.Debug;
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
 * Activity with non-EditText view selected initially
 */
public class OneEditTextActivityNotSelected extends Activity 
{
    private View mRootView;
    private View mDefaultFocusedView;
    
    @Override
    public void onCreate(Bundle savedInstanceState) 
    {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        mRootView = new ScrollView(this);

        EditText editText = new EditText(this);
        Button button = new Button(this);
        button.setText("The focus is here.");
        button.setFocusableInTouchMode(true);
        button.requestFocus();
        mDefaultFocusedView = button;
        layout.addView(button);
        layout.addView(editText);

        ((ScrollView) mRootView).addView(layout);
        setContentView(mRootView);
    }  

    public View getRootView() {
        return mRootView;
    }
    
    public View getDefaultFocusedView() {
        return mDefaultFocusedView;
    }
}
