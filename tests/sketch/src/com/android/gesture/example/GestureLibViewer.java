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
import android.graphics.Matrix;
import android.graphics.Path;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.AdapterView.OnItemSelectedListener;

import com.android.gesture.Gesture;
import com.android.gesture.GestureLib;
import com.android.gesture.GesturePad;
import com.android.gesture.R;
import com.android.gesture.recognizer.Instance;

import java.util.ArrayList;
import java.util.Collections;

/**
 * GestureLibViewer is for viewing existing gestures and 
 * removing unwanted gestures.
 */

public class GestureLibViewer  extends Activity {
  
    GesturePad          mView;
    Spinner             mResult;
    GestureLib          mRecognizer;
    ArrayList<Gesture>  mSamples;
    int                 mCurrentGestureIndex;
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gestureviewer);
        
        // create the area for drawing a glyph
        mView = (GesturePad)this.findViewById(R.id.drawingpad);
        mView.cacheGesture(false);
        mView.setFadingOut(false);
        mView.setEnableInput(false);
        
        // init the recognizer
        mRecognizer = new GestureLib("/sdcard/gestureentry");
        mRecognizer.load();

        mResult = (Spinner) findViewById(R.id.spinner);
        ArrayList<String> list = new ArrayList<String>();
        list.addAll(mRecognizer.getLabels());
        Collections.sort(list);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, 
                    android.R.layout.simple_spinner_item, 
                    list);
        adapter.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);
        mResult.setAdapter(adapter);
        mSamples = mRecognizer.getGestures(list.get(0));
        if (mSamples.isEmpty() == false) {
            mCurrentGestureIndex = 0;
            Gesture gesture = mSamples.get(mCurrentGestureIndex);
            mView.setCurrentGesture(gesture);
            mView.clearDebugPath();
            mView.addDebugPath(
                toPath(mRecognizer.getClassifier().getInstance(gesture.getID())));
        }
        
        mResult.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // TODO Auto-generated method stub
                mSamples = mRecognizer.getGestures(
                    (String)mResult.getSelectedItem());
                if (mSamples.isEmpty() == false) {
                    mCurrentGestureIndex = 0;
                    Gesture gesture = mSamples.get(mCurrentGestureIndex);
                    mView.setCurrentGesture(gesture);
                    mView.clearDebugPath();
                    mView.addDebugPath(
                        toPath(mRecognizer.getClassifier().getInstance(gesture.getID())));
                }
                mView.invalidate();
            }
  
            public void onNothingSelected(AdapterView<?> parent) {
              // TODO Auto-generated method stub
              
            }
          
        });
        
        Button remove = (Button)this.findViewById(R.id.remove);
        remove.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                // TODO Auto-generated method stub
                if (mSamples.isEmpty())
                    return;
                
                String name = (String)mResult.getSelectedItem();
                Gesture gesture = mSamples.get(mCurrentGestureIndex);
                mRecognizer.removeGesture(name, gesture);
                
                mSamples = mRecognizer.getGestures(name);
                
                if (mSamples == null) {
                    // delete the entire entry
                    mCurrentGestureIndex = 0;
                    ArrayList<String> list = new ArrayList<String>();
                    list.addAll(mRecognizer.getLabels());
                    Collections.sort(list);
                    ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                                GestureLibViewer.this, 
                                android.R.layout.simple_spinner_item, 
                                list);
                    adapter.setDropDownViewResource(
                                android.R.layout.simple_spinner_dropdown_item);
                    mResult.setAdapter(adapter);
                } else {
                    if (mCurrentGestureIndex > mSamples.size()-1) {
                        mCurrentGestureIndex--;
                    }
                    gesture = mSamples.get(mCurrentGestureIndex);
                    mView.setCurrentGesture(gesture);
                    mView.clearDebugPath();
                    mView.addDebugPath(
                        toPath(mRecognizer.getClassifier().getInstance(gesture.getID())));
                    mView.invalidate();
                }
            }
        });
        
        Button next = (Button)this.findViewById(R.id.next);
        next.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                // TODO Auto-generated method stub
                if (mCurrentGestureIndex >= mSamples.size()-1)
                    return;
                
                mCurrentGestureIndex++;
                Gesture gesture = mSamples.get(mCurrentGestureIndex);
                mView.setCurrentGesture(gesture);
                mView.clearDebugPath();
                mView.addDebugPath(
                    toPath(mRecognizer.getClassifier().getInstance(gesture.getID())));
                mView.invalidate();
            }
        });

        Button previous = (Button)this.findViewById(R.id.previous);
        previous.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                // TODO Auto-generated method stub
                if (mCurrentGestureIndex >= 1 &&
                    mSamples.isEmpty() == false) {
                    mCurrentGestureIndex--;
                    Gesture gesture = mSamples.get(mCurrentGestureIndex);
                    mView.setCurrentGesture(gesture);
                    mView.clearDebugPath();
                    mView.addDebugPath(
                        toPath(mRecognizer.getClassifier().getInstance(gesture.getID())));
                    mView.invalidate();
                }
            }
        });
    }
    
    public static ArrayList<Path> toPath(Instance instance) {
        ArrayList<Path> paths = new ArrayList();
        Path path = null;
        float minx = 0, miny = 0;
        float mX = 0, mY = 0;
        for (int i=0; i<instance.vector.length; i+=2) {
            float x = instance.vector[i];
            float y = instance.vector[i+1];
            if (x < minx)
                minx = x;
            if (y < miny)
                miny = y;
            if (path == null) {
              path = new Path();
              path.moveTo(x, y);
              mX = x;
              mY = y;
            } else {
              float dx = Math.abs(x - mX);
              float dy = Math.abs(y - mY);
              if (dx >= 3 || dy >= 3) {
                  path.quadTo(mX, mY, (x + mX)/2, (y + mY)/2);
                  mX = x;
                  mY = y;
              }
            }
        }
        Matrix matrix = new Matrix();
        matrix.setTranslate(-minx + 10, -miny + 10);
        path.transform(matrix);
        paths.add(path);
        
        path = new Path();
        path.moveTo(instance.vector[0]-5, instance.vector[1]-5);
        path.lineTo(instance.vector[0]-5, instance.vector[1]+5);
        path.lineTo(instance.vector[0]+5, instance.vector[1]+5);
        path.lineTo(instance.vector[0]+5, instance.vector[1]-5);
        path.close();
        path.transform(matrix);
        paths.add(path);
        
        return paths;
    }
    
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
      // TODO Auto-generated method stub
      if (keyCode == KeyEvent.KEYCODE_BACK) {
          mRecognizer.save();
          this.setResult(RESULT_OK);
          finish();
          return true;
      }
      else
        return false;
    }
    
    @Override
    protected void onPause() {
        // TODO Auto-generated method stub
        super.onPause();
        mRecognizer.save();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        // TODO Auto-generated method stub
        super.onSaveInstanceState(outState);
        mRecognizer.save();
    }
}
