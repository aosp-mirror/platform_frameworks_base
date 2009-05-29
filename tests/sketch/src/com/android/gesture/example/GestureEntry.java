/*
 * Copyright (C) 2008-2009 The Android Open Source Project
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
import android.os.Environment;
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
import android.gesture.Gesture;

import android.gesture.GestureOverlayView;
import android.gesture.Prediction;
import android.gesture.GestureLibraries;
import android.gesture.GestureLibrary;

import java.io.File;
import java.util.ArrayList;

public class GestureEntry extends Activity {

    private static final String PARCEL_KEY = "gesture";

    static final String GESTURE_FILE_NAME =
            Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator +
                    "demo_library.gestures";

    private static final int DIALOG_NEW_ENTRY = 1;

    private static final int NEW_ID = Menu.FIRST;

    private static final int VIEW_ID = Menu.FIRST + 1;

    private GestureOverlayView mGesturePad;

    private Spinner mRecognitionResult;

    private GestureLibrary mGestureStore;

    private boolean mChangedByRecognizer = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.demo);

        // init the gesture library
        mGestureStore = GestureLibraries.fromFile(GESTURE_FILE_NAME);
        mGestureStore.load();

        // create the spinner for showing the recognition results
        // the spinner also allows a user to correct a prediction
        mRecognitionResult = (Spinner) findViewById(R.id.spinner);
        mRecognitionResult.setOnItemSelectedListener(new OnItemSelectedListener() {

            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // correct the recognition result by adding the new example
                if (!mChangedByRecognizer) {
                    mGestureStore.addGesture(parent.getSelectedItem().toString(), mGesturePad
                            .getGesture());
                } else {
                    mChangedByRecognizer = false;
                }
            }

            public void onNothingSelected(AdapterView<?> parent) {

            }

        });

        // create the area for drawing a gesture
        mGesturePad = (GestureOverlayView) findViewById(R.id.drawingpad);
        mGesturePad.setBackgroundColor(Color.BLACK);
        mGesturePad.addOnGestureListener(new GestureOverlayView.OnGestureListener() {
            public void onGestureEnded(GestureOverlayView overlay, MotionEvent event) {
                recognize(overlay.getGesture());
            }

            public void onGesture(GestureOverlayView overlay, MotionEvent event) {
            }

            public void onGestureStarted(GestureOverlayView overlay, MotionEvent event) {
                overlay.clear(false);
            }

            public void onGestureCancelled(GestureOverlayView overlay, MotionEvent event) {
            }
        });

        if (savedInstanceState != null) {
            Gesture gesture = (Gesture) savedInstanceState.getParcelable(PARCEL_KEY);
            if (gesture != null) {
                mGesturePad.setGesture(gesture);
            }
        }
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        LayoutInflater factory = LayoutInflater.from(this);
        final View textEntryView = factory.inflate(R.layout.newgesture_dialog, null);
        return new AlertDialog.Builder(GestureEntry.this).setTitle(
                R.string.newgesture_text_entry).setView(textEntryView).setPositiveButton(
                R.string.newgesture_dialog_ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        EditText edittext = (EditText) ((AlertDialog) dialog)
                                .findViewById(R.id.gesturename_edit);
                        String text = edittext.getText().toString().trim();
                        if (text.length() > 0) {
                            mGestureStore.addGesture(text, mGesturePad.getGesture());
                        }
                    }
                }).setNegativeButton(R.string.newgesture_dialog_cancel,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                    }
                }).create();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, NEW_ID, 0, R.string.newgesture).setShortcut('0', 'n').setIcon(
                android.R.drawable.ic_menu_add);
        menu.add(0, VIEW_ID, 0, R.string.viewgesture).setShortcut('1', 'v').setIcon(
                android.R.drawable.ic_menu_view);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case NEW_ID:
                if (mGesturePad.getGesture() != null) {
                    showDialog(DIALOG_NEW_ENTRY);
                }
                break;

            case VIEW_ID:
                startActivityForResult(new Intent(this, GestureLibViewer.class), VIEW_ID);
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        mGestureStore.load();
        mGesturePad.clear(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mGestureStore.save();
    }

    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
        super.onPrepareDialog(id, dialog);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Gesture gesture = mGesturePad.getGesture();
        if (gesture != null) {
            outState.putParcelable(PARCEL_KEY, gesture);
        }
        mGestureStore.save();
    }

    private void recognize(Gesture gesture) {
        mChangedByRecognizer = true;
        ArrayList<Prediction> predictions = mGestureStore.recognize(gesture);
        ArrayAdapter<Prediction> adapter = new ArrayAdapter<Prediction>(this,
                android.R.layout.simple_spinner_item, predictions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mRecognitionResult.setAdapter(adapter);
    }

}
