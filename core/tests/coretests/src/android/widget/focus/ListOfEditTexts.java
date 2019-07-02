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

package android.widget.focus;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;

import com.google.android.collect.Lists;

import java.util.List;

public class ListOfEditTexts extends Activity {

    private int mLinesPerEditText = 12;

    private ListView mListView;
    private LinearLayout mLinearLayout;

    public ListView getListView() {
        return mListView;
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // create linear layout
        mLinearLayout = new LinearLayout(this);
        mLinearLayout.setOrientation(LinearLayout.VERTICAL);
        mLinearLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // add a button above
        Button buttonAbove = new Button(this);
        buttonAbove.setLayoutParams(
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        buttonAbove.setText("button above list");
        mLinearLayout.addView(buttonAbove);

        // add a list view to it
        mListView = new ListView(this);
        mListView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        mListView.setDrawSelectorOnTop(false);
        mListView.setItemsCanFocus(true);
        mListView.setLayoutParams((new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f)));

        List<String> bodies = Lists.newArrayList(
                getBody("zero hello, my name is android"),
                getBody("one i'm a paranoid android"),
                getBody("two i robot.  huh huh."),
                getBody("three not the g-phone!"));

        mListView.setAdapter(new MyAdapter(this, bodies));
        mLinearLayout.addView(mListView);

        // add button below
        Button buttonBelow = new Button(this);
        buttonBelow.setLayoutParams(
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        buttonBelow.setText("button below list");
        mLinearLayout.addView(buttonBelow);
        
        setContentView(mLinearLayout);
    }

    String getBody(String line) {
        StringBuilder sb = new StringBuilder((line.length() + 5) * mLinesPerEditText);
        for (int i = 0; i < mLinesPerEditText; i++) {
            sb.append(i + 1).append(' ').append(line);
            if (i < mLinesPerEditText - 1) {
                sb.append('\n'); // all but last line
            }
        }
        return sb.toString();
    }


    private static class MyAdapter extends ArrayAdapter<String> {

        public MyAdapter(Context context, List<String> bodies) {
            super(context, 0, bodies);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            String body = getItem(position);

            if (convertView != null) {
                ((EditText) convertView).setText(body);
                return convertView;                
            }

            EditText editText = new EditText(getContext());
            editText.setText(body);
            return editText;
        }
    }
}
