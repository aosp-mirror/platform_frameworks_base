/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.imftest.samples;

import com.android.imftest.R;

import android.app.Activity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

public class InputTypeActivity extends Activity {

    private LinearLayout mLayout;
    private ScrollView mScrollView;
    private LayoutInflater mInflater;
    private ViewGroup mParent;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mScrollView = new ScrollView(this);
        
        mLayout = new LinearLayout(this);
        mLayout.setOrientation(LinearLayout.VERTICAL);
        mLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        mInflater = getLayoutInflater();
        mParent = mLayout;
        
        /* Normal Edit Text */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_TEXT|EditorInfo.TYPE_TEXT_VARIATION_NORMAL, 
                R.string.normal_edit_text_label));
        
        /* Normal Edit Text w/Cap Chars Flag*/
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_TEXT|EditorInfo.TYPE_TEXT_VARIATION_NORMAL|EditorInfo.TYPE_TEXT_FLAG_CAP_CHARACTERS, 
                R.string.cap_chars_edit_text_label));
        
        /* Normal Edit Text w/Cap Words Flag*/
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_TEXT|EditorInfo.TYPE_TEXT_VARIATION_NORMAL|EditorInfo.TYPE_TEXT_FLAG_CAP_WORDS, 
                R.string.cap_words_edit_text_label));
        
        /* Normal Edit Text w/Cap Multiline Flag */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_TEXT|EditorInfo.TYPE_TEXT_VARIATION_NORMAL|EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE, 
                R.string.multiline_edit_text_label));
        
        /* Normal Edit Text w/Cap Sentences Flag */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_TEXT|EditorInfo.TYPE_TEXT_VARIATION_NORMAL|EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES, 
                R.string.cap_sentences_edit_text_label));
        
        /* Normal Edit Text w/Auto-complete Flag */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_TEXT|EditorInfo.TYPE_TEXT_VARIATION_NORMAL|EditorInfo.TYPE_TEXT_FLAG_AUTO_COMPLETE, 
                R.string.auto_complete_edit_text_label));
        
        /* Normal Edit Text w/Auto-correct Flag */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_TEXT|EditorInfo.TYPE_TEXT_VARIATION_NORMAL|EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT, 
                R.string.auto_correct_edit_text_label));
        
        /* Uri Edit Text */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_TEXT|EditorInfo.TYPE_TEXT_VARIATION_URI, 
                R.string.uri_edit_text_label));
        
        /* Email Address Edit Text */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_TEXT|EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 
                R.string.email_address_edit_text_label));
        
        /* Email Subject Text */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_TEXT|EditorInfo.TYPE_TEXT_VARIATION_EMAIL_SUBJECT, 
                R.string.email_subject_edit_text_label));
        
        /* Email Content Edit Text */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_TEXT|EditorInfo.TYPE_TEXT_VARIATION_LONG_MESSAGE, 
                R.string.email_content_edit_text_label));
        
        /* Person Name Edit Text */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_TEXT|EditorInfo.TYPE_TEXT_VARIATION_PERSON_NAME, 
                R.string.person_name_edit_text_label));
        
        /* Postal Address Edit Text */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_TEXT|EditorInfo.TYPE_TEXT_VARIATION_POSTAL_ADDRESS, 
                R.string.postal_address_edit_text_label));
        
        /* Password Edit Text */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_TEXT|EditorInfo.TYPE_TEXT_VARIATION_PASSWORD, 
                R.string.password_edit_text_label));
        
        /* Web Edit Text */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_TEXT|EditorInfo.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT, 
                R.string.web_edit_text_label));
        
        /* Signed Number Edit Text */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_NUMBER|EditorInfo.TYPE_NUMBER_FLAG_SIGNED, 
                R.string.signed_number_edit_text_label));
        
        /* Decimal Number Edit Text */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_NUMBER|EditorInfo.TYPE_NUMBER_FLAG_DECIMAL, 
                R.string.decimal_number_edit_text_label));
        
        /* Phone Number Edit Text */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_PHONE, 
                R.string.phone_number_edit_text_label));
        
        /* Normal Datetime Edit Text */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_DATETIME|EditorInfo.TYPE_DATETIME_VARIATION_NORMAL, 
                R.string.normal_datetime_edit_text_label));
        
        /* Date Edit Text */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_DATETIME|EditorInfo.TYPE_DATETIME_VARIATION_DATE, 
                R.string.date_edit_text_label));
        
        /* Time Edit Text */
        mLayout.addView(buildEntryView(EditorInfo.TYPE_CLASS_DATETIME|EditorInfo.TYPE_DATETIME_VARIATION_TIME, 
                R.string.time_edit_text_label));
            
        mScrollView.addView(mLayout);
        setContentView(mScrollView);
    }
    
    private View buildEntryView(int inputType, int label) {

        
        View view = mInflater.inflate(R.layout.sample_edit_text, mParent, false);
        
        EditText editText = (EditText) view.findViewById(R.id.data);
        editText.setInputType(inputType);
        
        TextView textView = (TextView) view.findViewById(R.id.label);
        textView.setText(label);
        
        return view;
    }

}
