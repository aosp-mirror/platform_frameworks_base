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
 * Activity with non-EditText view selected initially
 */
public class OneEditTextActivityNotSelected extends Activity 
{
   @Override
   public void onCreate(Bundle savedInstanceState) 
   {
       super.onCreate(savedInstanceState);
                
       LinearLayout layout = new LinearLayout(this);
       layout.setOrientation(LinearLayout.VERTICAL);
       
       final EditText editText = new EditText(this);
       final TextView textView = new TextView(this);
       textView.setText("The focus is here.");
       layout.addView(editText);
       layout.addView(textView);
  
       setContentView(layout);
       textView.requestFocus();
    }  
}
