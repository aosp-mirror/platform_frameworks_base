/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.AdapterView.OnItemSelectedListener;

import com.android.gesture.Gesture;
import com.android.gesture.GestureLib;
import com.android.gesture.GestureListener;
import com.android.gesture.GesturePad;
import com.android.gesture.R;
import com.android.gesture.recognizer.Prediction;

import java.util.ArrayList;

/**
 * The demo shows how to construct a gesture-based user interface on Android.
 */

public class GestureEntryDemo extends Activity {
  
    private static final int DIALOG_NEW_ENTRY = 1;
    private static final int NEW_ID = Menu.FIRST;
    private static final int VIEW_ID = Menu.FIRST + 1;

    GesturePad  mView;
    Spinner     mResult;
    GestureLib  mRecognizer;
    boolean     mChangedByRecognizer = false;
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.demo);
        
        // init the recognizer
        mRecognizer = new GestureLib("/sdcard/gestureentry");
        mRecognizer.load();
        
        // create the spinner for showing the recognition results
        // the spinner also allows a user to correct a prediction
        mResult = (Spinner) findViewById(R.id.spinner);
        mResult.setOnItemSelectedListener(new OnItemSelectedListener() {

            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // TODO Auto-generated method stub
                // correct the recognition result by adding the new example
                if (mChangedByRecognizer == false) {
                    mRecognizer.addGesture(parent.getSelectedItem().toString(), 
                      mView.getCurrentGesture());
                } else {
                    mChangedByRecognizer = false;
                }
            }
  
            public void onNothingSelected(AdapterView<?> parent) {
              // TODO Auto-generated method stub
              
            }
          
        });
        
        // create the area for drawing a gesture
        mView = (GesturePad)this.findViewById(R.id.drawingpad);
        mView.cacheGesture(false);
        mView.setFadingOut(false);
        mView.addGestureListener(new GestureListener() {
            public void onFinishGesture(GesturePad patch, MotionEvent event) {
                // TODO Auto-generated method stub
                recognize(patch.getCurrentGesture());
            }
            public void onGesture(GesturePad patch, MotionEvent event) {
              // TODO Auto-generated method stub
              
            }
            public void onStartGesture(GesturePad patch, MotionEvent event) {
              // TODO Auto-generated method stub
              
            }
        });
        
        
        if (savedInstanceState != null) {
            mView.setCurrentGesture(
                (Gesture)savedInstanceState.getParcelable("gesture"));
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
                      (EditText)((AlertDialog)dialog).findViewById(R.id.gesturename_edit);
                    String text = edittext.getText().toString().trim();
                    if (text.length() > 0) {
                        mRecognizer.addGesture(text, mView.getCurrentGesture());
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
                if (mView.getCurrentGesture() != null) {
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        mRecognizer.load();
        mView.clear();
    }
    
    @Override
    protected void onPause() {
        // TODO Auto-generated method stub
        super.onPause();
        mRecognizer.save();
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
        outState.putParcelable("gesture", mView.getCurrentGesture());
        mRecognizer.save();
    }

    public void recognize(Gesture ink) {
        mChangedByRecognizer = true;
        ArrayList<Prediction> predictions = mRecognizer.recognize(ink);
        ArrayAdapter adapter = new ArrayAdapter(this, 
                    android.R.layout.simple_spinner_item, predictions);
        adapter.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);
        mResult.setAdapter(adapter);
    }

}
