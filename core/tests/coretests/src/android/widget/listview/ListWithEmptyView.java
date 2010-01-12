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

package android.widget.listview;

import com.android.frameworks.coretests.R;

import android.app.ListActivity;
import android.content.Context;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;


/**
 * Tests using an empty view with a list */
public class ListWithEmptyView extends ListActivity {
    
    private class CarefulAdapter<T> extends ArrayAdapter<T> {

        public CarefulAdapter(Context context, int textViewResourceId) {
            super(context, textViewResourceId);
            // TODO Auto-generated constructor stub
        }

        @Override
        public long getItemId(int position) {
            if (position <  0 || position >= this.getCount()) {
                throw new ArrayIndexOutOfBoundsException();
            }
            return super.getItemId(position);
        }
       
        
    }
    
    public static final int MENU_ADD = Menu.FIRST + 1;
    public static final int MENU_REMOVE = Menu.FIRST + 2;
    
    private CarefulAdapter<String> mAdapter;
    
    private int mNextItem = 0;
    
    private View mEmptyView;
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAdapter = new CarefulAdapter<String>(this,
                android.R.layout.simple_list_item_1);
        setContentView(R.layout.list_with_empty_view);
        setListAdapter(mAdapter);
        
        mEmptyView = findViewById(R.id.empty);
        getListView().setEmptyView(mEmptyView);
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, MENU_ADD, 0, R.string.menu_add)
                .setIcon(android.R.drawable.ic_menu_add);
        menu.add(0, MENU_REMOVE, 0, R.string.menu_remove)
                .setIcon(android.R.drawable.ic_menu_delete);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_ADD:
                String str = "Item + " + mNextItem++;
                mAdapter.add(str);
                return true;
            case MENU_REMOVE:
                if (mAdapter.getCount() > 0) {
                    mAdapter.remove(mAdapter.getItem(0));
                }
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public View getEmptyView() {
        return mEmptyView;
    }
   
}
