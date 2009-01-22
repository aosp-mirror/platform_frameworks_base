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
    
    @Override
    public void onCreate(Bundle savedInstanceState) 
    {
        super.onCreate(savedInstanceState);
        final ButtonActivity instance = this;
             
        final Button myButton = new Button(this);
        myButton.setClickable(true);
        myButton.setText("Keyboard UP!");
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
                    imm.showSoftInput(null, 0);
                    myButton.setText("Keyboard DOWN!");
                }
               
                mKeyboardIsActive = !mKeyboardIsActive;
            }
        });
            
       LinearLayout layout = new LinearLayout(this);
       layout.setOrientation(LinearLayout.VERTICAL);
       layout.addView(myButton);
       setContentView(layout);
    }
}
