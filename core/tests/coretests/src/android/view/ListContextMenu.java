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

package android.view;

import android.app.ListActivity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.android.frameworks.coretests.R;

/**
 * Exercises context menus in lists
 */
public class ListContextMenu extends ListActivity implements View.OnCreateContextMenuListener
{
    static final String TAG = "ListContextMenu";
    
    ThrashListAdapter mAdapter; 
    
    private class ThrashListAdapter extends BaseAdapter {
        private LayoutInflater mInflater;
        
        private String[] mTitles = new String[100];

        public ThrashListAdapter(Context context) {
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mTitles = new String[100];
            
            int i;
            for (i=0; i<100; i++) {
                mTitles[i] = "[" + i + "]";
            }
        }

        public int getCount() {
            return mTitles.length;
        }

        public Object getItem(int position) {
            return position;
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view;

            if (convertView == null) {
                view = (TextView) mInflater.inflate(android.R.layout.simple_list_item_1, null);
            } else {
                view = (TextView) convertView;
            }
            view.setText("List item " + mTitles[position]);
            return view;
        }

    }
    
    @Override
    public void onCreate(Bundle icicle) 
    {
        super.onCreate(icicle);
        
        mAdapter = new ThrashListAdapter(this);
        getListView().setOnCreateContextMenuListener(this);
        setListAdapter(mAdapter);
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem item = menu.add(0, 0, 0, "Really long menu item name");
        item.setTitleCondensed("Long name");
        item.setIcon(R.drawable.black_square);
        
        SubMenu sm = menu.addSubMenu(0, 0, 0, "The 2nd item, a sub menu").setIcon(R.drawable.black_square_stretchable);
        item = sm.getItem();
        item.setTitleCondensed("Sub menu");
        sm.add(1, 0, 0, "Subitem 1");
        sm.add(1, 0, 0, "Subitem 2");
        sm.add(1, 0, 0, "Subitem 3");
        sm.setGroupCheckable(1, true, true);
        menu.add(0, 0, 0, "Item 3");
        menu.add(0, 0, 0, "Item 4");
        menu.add(0, 0, 0, "Item 5");
        menu.add(0, 0, 0, "Item 6");
        menu.add(0, 0, 0, "Item 7");
        menu.add(0, 0, 0, "Item 8");
        menu.add(0, 0, 0, "Item 9");
        sm = menu.addSubMenu(0, 0, 0, "Item 10 SM");
        sm.add(0, 0, 0, "Subitem 1");
        sm.add(0, 0, 0, "Subitem 2");
        sm.add(0, 0, 0, "Subitem 3");
        sm.add(0, 0, 0, "Subitem 4");
        sm.add(0, 0, 0, "Subitem 5");
        sm.add(0, 0, 0, "Subitem 6");
        sm.add(0, 0, 0, "Subitem 7");
        sm.add(0, 0, 0, "Subitem 8");
        
        return true;
    }

    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo)menuInfo;
        
        String text = ((TextView) info.targetView).getText().toString();
        if (text.contains("[0]")) {
            menu.setHeaderTitle("This is a test of the title and the icon").setHeaderIcon(
                    android.R.drawable.sym_def_app_icon);
        } else if (text.contains("[1]")) {
            menu.setHeaderTitle("This is a test of just the title");
        } else {
            TextView textView = new TextView(this);
            textView.setText("This is a test of a custom View");
            menu.setHeaderView(textView);
        }
        
        menu.add(0, 0, 0, "Test 1");
        SubMenu sm = menu.addSubMenu(0, 0, 0, "Test 1.5 SM");
        sm.add(0, 0, 0, "CM Subitem 1");
        sm.add(0, 0, 0, "CM Subitem 2");
        sm.add(0, 0, 0, "CM Subitem 3");
        menu.add(0, 0, 0, "Test 2");
        menu.add(0, 0, 0, "Test 3");
        menu.add(0, 0, 0, "Test 4");
        menu.add(0, 0, 0, "Test 5");
        menu.add(0, 0, 0, "Test 6");
        menu.add(0, 0, 0, "Test 7");
        menu.add(0, 0, 0, "Test 8");
        menu.add(0, 0, 0, "Test 9");
        menu.add(0, 0, 0, "Test 10");
        menu.add(0, 0, 0, "Test 11");
        menu.add(0, 0, 0, "Test 12");
        menu.add(0, 0, 0, "Test 13");
        menu.add(0, 0, 0, "Test 14");
        menu.add(0, 0, 0, "Test 15");
        menu.add(0, 0, 0, "Test 16");
        menu.add(0, 0, 0, "Test 17");
        menu.add(0, 0, 0, "Test 18");
        menu.add(0, 0, 0, "Test 19");
        menu.add(0, 0, 0, "Test 20");
        menu.add(0, 0, 0, "Test 21");
        menu.add(0, 0, 0, "Test 22");
        menu.add(0, 0, 0, "Test 23");
        menu.add(0, 0, 0, "Test 24");
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.i(TAG, "Options item " + item.toString() + " selected.");
        
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onOptionsMenuClosed(Menu menu) {
        Log.i(TAG, "Options menu closed");
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        Log.i(TAG, "Context item " + item.toString() + " selected.");
        
        return super.onContextItemSelected(item);
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        Log.i(TAG, "Context menu closed");
    }
    
    
}
