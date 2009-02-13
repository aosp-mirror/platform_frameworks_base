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
 * Full screen of EditTexts (Scrollable, Pan&Scan)
 */
public class ManyEditTextActivityScrollPanScan extends Activity 
{
    public static final int NUM_EDIT_TEXTS = 12;

    private View mRootView;

    @Override
    public void onCreate(Bundle savedInstanceState) 
    {
        super.onCreate(savedInstanceState);
        mRootView = new ScrollView(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        for (int i=0; i<NUM_EDIT_TEXTS; i++) 
        {
            final EditText editText = new EditText(this);
            editText.setText(String.valueOf(i));
            editText.setId(i);
            layout.addView(editText);
        }

        ((ScrollView) mRootView).addView(layout);
        setContentView(mRootView);
        this.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
    }   

    public View getRootView() {
        return mRootView;
    }
}