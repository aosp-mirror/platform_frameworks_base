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

package android.widget;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemSelectedListener;

import com.android.frameworks.coretests.R;

public class AutoCompleteTextViewSimple extends Activity 
        implements OnItemClickListener, OnItemSelectedListener {

    private final String LOG_TAG = "AutoCompleteTextViewSimple";
    
    private AutoCompleteTextView mTextView;
    
    /** These are cleared by resetItemListeners(), and set by the callback listeners */
    public boolean mItemClickCalled;
    public int mItemClickPosition;
    public boolean mItemSelectedCalled;
    public int mItemSelectedPosition;
    public boolean mNothingSelectedCalled;

    @Override
    protected void onCreate(Bundle icicle)
    {
        // Be sure to call the super class.
        super.onCreate(icicle);

        // setup layout & views
        setContentView(R.layout.autocompletetextview_simple);
        mTextView = findViewById(R.id.autocompletetextview1);
        
        // configure callbacks used for monitoring
        mTextView.setOnItemClickListener(this);
        mTextView.setOnItemSelectedListener(this);
        resetItemListeners();
        
        setStringAdapter(5, "a");
    }

    /**
     * @return The AutoCompleteTextView used in this test activity.
     */
    public AutoCompleteTextView getTextView() {
        return mTextView;
    }

    /**
     * Set the autocomplete data to an adapter containing 0..n strings with a consistent prefix.
     */
    public void setStringAdapter(int numSuggestions, String prefix) {
        // generate the string array
        String[] strings = new String[numSuggestions];
        for (int i = 0; i < numSuggestions; ++i) {
            strings[i] = prefix + String.valueOf(i);
        }
        
        // install it with an adapter
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_dropdown_item_1line, strings);
        mTextView.setAdapter(adapter);
    }
    
    /**
     * For monitoring OnItemClickListener & OnItemSelectedListener
     * 
     * An alternative here would be to provide a set of pass-through callbacks
     */
    public void resetItemListeners() {
        mItemClickCalled = false;
        mItemClickPosition = -1;
        mItemSelectedCalled = false;
        mItemSelectedPosition = -1;
        mNothingSelectedCalled = false;
    }
    
    /**
     * Implements OnItemClickListener
     */
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Log.d(LOG_TAG, "onItemClick() position " + position);
        mItemClickCalled = true;
        mItemClickPosition = position;
    }

    /** 
     * Implements OnItemSelectedListener
     */
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        Log.d(LOG_TAG, "onItemSelected() position " + position);
        mItemSelectedCalled = true;
        mItemSelectedPosition = position;
    }

    /** 
     * Implements OnItemSelectedListener
     */
    public void onNothingSelected(AdapterView<?> parent) {
        Log.d(LOG_TAG, "onNothingSelected()");
        mNothingSelectedCalled = true;
    }

}
