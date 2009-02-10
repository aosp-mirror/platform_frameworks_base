package com.android.imftest.samples;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.ScrollView;

/*
 * Full screen of EditTexts (Scrollable, Resize)
 */
public class ManyEditTextActivityScrollResize extends Activity 
{
    private ScrollView mScrollView;
    @Override
    public void onCreate(Bundle savedInstanceState) 
    {
        super.onCreate(savedInstanceState);
        mScrollView = new ScrollView(this);
       
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
       
        String string = new String();
        for (int i=0; i<12; i++) 
        {
            final EditText editText = new EditText(this);
            editText.setText(string.valueOf(i));
            layout.addView(editText);
        }

        mScrollView.addView(layout);
        setContentView(mScrollView);
        this.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }  
}