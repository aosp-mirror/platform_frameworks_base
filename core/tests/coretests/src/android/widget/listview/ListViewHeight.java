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

package android.widget.listview;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.android.frameworks.coretests.R;

public class ListViewHeight extends Activity {

    private View mButton1;
    private View mButton2;
    private View mButton3;
    
    private View mOuterLayout;
    private ListView mInnerList;

    ArrayAdapter<String> mAdapter;
    private String[] mStrings = {
            "Abbaye de Belloc", "Abbaye du Mont des Cats", "Abertam", "Abondance", "Ackawi" };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.linear_layout_listview_height);

        mButton1 = findViewById(R.id.button1);
        mButton2 = findViewById(R.id.button2);
        mButton3 = findViewById(R.id.button3);
        
        mOuterLayout = findViewById(R.id.layout);
        mInnerList = (ListView)findViewById(R.id.inner_list);
        
        mAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, 
                                            mStrings);

        // Clicking this button will show the list view and set it to a fixed height
        // If you then hide the views, there is no problem.
        mButton1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // set listview to fixed height 
                ViewGroup.MarginLayoutParams lp;
                lp = (ViewGroup.MarginLayoutParams) mInnerList.getLayoutParams();
                lp.height = 200;
                mInnerList.setLayoutParams(lp);
                // enable list adapter
                mInnerList.setAdapter(mAdapter);
                // and show it
                mOuterLayout.setVisibility(View.VISIBLE);
            }
        });

        // Clicking this button will show the list view and set it match_parent height
        // If you then hide the views, there is an NPE when calculating the ListView height.
        mButton2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // set listview to fill screen
                ViewGroup.MarginLayoutParams lp;
                lp = (ViewGroup.MarginLayoutParams) mInnerList.getLayoutParams();
                lp.height = lp.MATCH_PARENT;
                mInnerList.setLayoutParams(lp);
                // enable list adapter
                mInnerList.setAdapter(mAdapter);
                // and show it
                mOuterLayout.setVisibility(View.VISIBLE);
            }
        });

        // Clicking this button will remove the list adapter and hide the outer enclosing view.
        // We have to climb all the way to the top because the bug (not checking visibility)
        // only occurs at the very outer loop of ViewAncestor.performTraversals and in the case of
        // an Activity, this means you have to crawl all the way out to the root view.
        // In the search manager, it's sufficient to simply show/hide the outer search manager
        // view to trigger the same bug.
        mButton3.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mInnerList.setAdapter(null);
                // hide listview's owner
                // as it turns out, the owner doesn't take us high enough
                // because our activity includes a title bar, thus another layer
                View parent = (View) mOuterLayout.getParent();      // FrameLayout (app container)
                View grandpa = (View) parent.getParent();           // LinearLayout (title+app)
                View great = (View) grandpa.getParent();            // PhoneWindow.DecorView
                great.setVisibility(View.GONE);
            }
        });
    }

}
