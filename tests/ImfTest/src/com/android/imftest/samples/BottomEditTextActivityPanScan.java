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
    private LayoutInflater mInflater;
    @Override
    public void onCreate(Bundle savedInstanceState) 
    {
        super.onCreate(savedInstanceState);
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        
        mInflater = getLayoutInflater();
        
        View view = mInflater.inflate(R.layout.one_edit_text_activity, layout, false);
        layout.addView(view);
       
        setContentView(layout);
        this.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
    }  
}