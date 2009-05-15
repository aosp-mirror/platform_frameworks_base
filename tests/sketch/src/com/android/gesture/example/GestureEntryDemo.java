/*
 * Copyright (C) 2009 Google Inc.
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

package com.android.gesture.example;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.AdapterView.OnItemSelectedListener;

import com.android.gesture.Gesture;
import com.android.gesture.GestureLibrary;
import com.android.gesture.GestureListener;
import com.android.gesture.GesturePad;
import com.android.gesture.Prediction;

import java.util.ArrayList;

/**
 * The demo shows how to construct a gesture-based user interface on Android.
 * 
 * @author liyang@google.com (Yang Li)
 *
 */

public class GestureEntryDemo extends Activity {
  
    private static final int DIALOG_NEW_ENTRY = 1;
    private static final int NEW_ID = Menu.FIRST;
    private static final int VIEW_ID = Menu.FIRST + 1;

    private GesturePad mGesturePad;
    private Spinner mRecognitionResult;
    private GestureLibrary mGestureLibrary;
    private boolean mChangedByRecognizer = false;
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.demo);
        
        // init the gesture library
        mGestureLibrary = new GestureLibrary(
            "/sdcard/gestureentry/gestures.xml");
        mGestureLibrary.load();
        
        // create the spinner for showing the recognition results
        // the spinner also allows a user to correct a prediction
        mRecognitionResult = (Spinner) findViewById(R.id.spinner);
        mRecognitionResult.setOnItemSelectedListener(
            new OnItemSelectedListener() {

            public void onItemSelected(
                AdapterView<?> parent, View view, int position, long id) {
                // TODO Auto-generated method stub
                // correct the recognition result by adding the new example
                if (mChangedByRecognizer == false) {
                    mGestureLibrary.addGesture(
                        parent.getSelectedItem().toString(), 
                        mGesturePad.getCurrentGesture());
                } else {
                    mChangedByRecognizer = false;
                }
            }
  
            public void onNothingSelected(AdapterView<?> parent) {
              // TODO Auto-generated method stub
              
            }
          
        });
        
        // create the area for drawing a gesture
        mGesturePad = (GesturePad)this.findViewById(R.id.drawingpad);
        mGesturePad.setBackgroundColor(Color.BLACK);
        mGesturePad.addGestureListener(new GestureListener() {
            public void onFinishGesture(GesturePad pad, MotionEvent event) {
                // TODO Auto-generated method stub
                recognize(pad.getCurrentGesture());
            }
            public void onGesture(GesturePad pad, MotionEvent event) {
              // TODO Auto-generated method stub
            }
            public void onStartGesture(GesturePad pad, MotionEvent event) {
                // TODO Auto-generated method stub
            }
        });
        
        Button clear = (Button)this.findViewById(R.id.clear);
        clear.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                // TODO Auto-generated method stub
                mGesturePad.clear(false);
                mGesturePad.invalidate();
            }
        });
        
        if (savedInstanceState != null) {
            Gesture g = (Gesture)savedInstanceState.getParcelable("gesture");
            if (g != null) {
                mGesturePad.setCurrentGesture(g);
            }
        }
    }
    
    @Override
    protected Dialog onCreateDialog(int id) {
        // create the dialog for adding a new entry
        LayoutInflater factory = LayoutInflater.from(this);
        final View textEntryView =
             factory.inflate(R.layout.newgesture_dialog, null);
        return new AlertDialog.Builder(GestureEntryDemo.this)
            .setTitle(R.string.newgesture_text_entry)
            .setView(textEntryView)
            .setPositiveButton(R.string.newgesture_dialog_ok, 
                new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    /* User clicked OK so do some stuff */
                    EditText edittext =
                      (EditText)((AlertDialog)dialog).findViewById(
                          R.id.gesturename_edit);
                    String text = edittext.getText().toString().trim();
                    if (text.length() > 0) {
                        mGestureLibrary.addGesture(
                            text, mGesturePad.getCurrentGesture());
                    }
                }
            })
            .setNegativeButton(R.string.newgesture_dialog_cancel,
                new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    /* User clicked cancel so do some stuff */
                }
            })
            .create();
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // TODO Auto-generated method stub
        super.onCreateOptionsMenu(menu);
        menu.add(0, NEW_ID, 0, R.string.newgesture)
                .setShortcut('0', 'n')
                .setIcon(android.R.drawable.ic_menu_add);
        menu.add(0, VIEW_ID, 0, R.string.viewgesture)
                .setShortcut('1', 'v')
                .setIcon(android.R.drawable.ic_menu_view);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle all of the possible menu actions.
        switch (item.getItemId()) {
            case NEW_ID:
                // if there has been a gesture on the canvas
                if (mGesturePad.getCurrentGesture() != null) {
                    showDialog(DIALOG_NEW_ENTRY);
                }
                break;
                
            case VIEW_ID:
                startActivityForResult(
                    new Intent(this, GestureLibViewer.class), VIEW_ID);
                break;
        }
        
        return super.onOptionsItemSelected(item);
    }
    
    
    @Override
    protected void onActivityResult(
        int requestCode, int resultCode, Intent data) {
        mGestureLibrary.load();
        mGesturePad.clear(false);
    }
    
    @Override
    protected void onPause() {
        // TODO Auto-generated method stub
        super.onPause();
        mGestureLibrary.save();
    }
    
    
    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
        // TODO Auto-generated method stub
        super.onPrepareDialog(id, dialog);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        // TODO Auto-generated method stub
        super.onSaveInstanceState(outState);
        Gesture gesture = mGesturePad.getCurrentGesture();
        if (gesture != null)
            outState.putParcelable("gesture", gesture);
        mGestureLibrary.save();
    }

    public void recognize(Gesture ink) {
        mChangedByRecognizer = true;
        ArrayList<Prediction> predictions = mGestureLibrary.recognize(ink);
        ArrayAdapter<Prediction> adapter = new ArrayAdapter<Prediction>(this, 
                    android.R.layout.simple_spinner_item, predictions);
        adapter.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);
        mRecognitionResult.setAdapter(adapter);
    }

}
