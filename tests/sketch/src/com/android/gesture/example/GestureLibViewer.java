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
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.AdapterView.OnItemSelectedListener;
import android.gesture.Gesture;

import android.gesture.GestureOverlayView;
import android.gesture.GestureLibraries;
import android.gesture.GestureLibrary;

import java.util.ArrayList;
import java.util.Collections;

/**
 * GestureLibViewer gives an example on how to browse existing gestures and
 * removing unwanted gestures.
 */

public class GestureLibViewer extends Activity {

    private GestureOverlayView mGesturePad;

    private Spinner mGestureCategory;

    private GestureLibrary mGesureStore;

    private ArrayList<Gesture> mGestures;

    private int mCurrentGestureIndex;

    private class RemoveGestureListener implements OnClickListener {
        public void onClick(View v) {
            if (mGestures.isEmpty()) {
                return;
            }

            String name = (String) mGestureCategory.getSelectedItem();
            Gesture gesture = mGestures.get(mCurrentGestureIndex);
            mGesureStore.removeGesture(name, gesture);

            mGestures = mGesureStore.getGestures(name);

            if (mGestures == null) {
                // delete the entire entry
                mCurrentGestureIndex = 0;
                ArrayList<String> list = new ArrayList<String>();
                list.addAll(mGesureStore.getGestureEntries());
                Collections.sort(list);
                ArrayAdapter<String> adapter = new ArrayAdapter<String>(GestureLibViewer.this,
                        android.R.layout.simple_spinner_item, list);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                mGestureCategory.setAdapter(adapter);
            } else {
                if (mCurrentGestureIndex > mGestures.size() - 1) {
                    mCurrentGestureIndex--;
                }
                gesture = mGestures.get(mCurrentGestureIndex);
                mGesturePad.setGesture(gesture);
                mGesturePad.invalidate();
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gestureviewer);

        // create the area for drawing a gesture
        mGesturePad = (GestureOverlayView) findViewById(R.id.drawingpad);
        mGesturePad.setEnabled(false);

        // init the gesture library
        mGesureStore = GestureLibraries.fromFile(GestureEntry.GESTURE_FILE_NAME);
        mGesureStore.load();

        mGestureCategory = (Spinner) findViewById(R.id.spinner);
        ArrayList<String> list = new ArrayList<String>();
        if (!mGesureStore.getGestureEntries().isEmpty()) {
            list.addAll(mGesureStore.getGestureEntries());
            Collections.sort(list);
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                    android.R.layout.simple_spinner_item, list);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mGestureCategory.setAdapter(adapter);
            mGestures = mGesureStore.getGestures(list.get(0));
            mCurrentGestureIndex = 0;
            Gesture gesture = mGestures.get(mCurrentGestureIndex);
            mGesturePad.setGesture(gesture);
        }

        mGestureCategory.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mGestures = mGesureStore.getGestures((String) mGestureCategory.getSelectedItem());
                if (!mGestures.isEmpty()) {
                    mCurrentGestureIndex = 0;
                    Gesture gesture = mGestures.get(mCurrentGestureIndex);
                    mGesturePad.setGesture(gesture);
                }
                mGesturePad.invalidate();
            }

            public void onNothingSelected(AdapterView<?> parent) {
            }

        });

        Button remove = (Button) findViewById(R.id.remove);
        remove.setOnClickListener(new RemoveGestureListener());

        Button next = (Button) findViewById(R.id.next);
        next.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (mCurrentGestureIndex >= mGestures.size() - 1) {
                    return;
                }
                mCurrentGestureIndex++;
                Gesture gesture = mGestures.get(mCurrentGestureIndex);
                mGesturePad.setGesture(gesture);
                mGesturePad.invalidate();
            }
        });

        Button previous = (Button) findViewById(R.id.previous);
        previous.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (mCurrentGestureIndex >= 1 && !mGestures.isEmpty()) {
                    mCurrentGestureIndex--;
                    Gesture gesture = mGestures.get(mCurrentGestureIndex);
                    mGesturePad.setGesture(gesture);
                    mGesturePad.invalidate();
                }
            }
        });
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            mGesureStore.save();
            setResult(RESULT_OK);
            finish();
            return true;
        } else {
            return false;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mGesureStore.save();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mGesureStore.save();
    }
}
