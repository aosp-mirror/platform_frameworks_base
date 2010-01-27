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

package android.widget.gridview;

import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ListAdapter;

import android.util.GridScenario;

import java.util.ArrayList;

/**
 * A grid with vertical spacing between rows
 */
public class GridDelete extends GridScenario {
    @Override
    protected void init(Params params) {
        params.setStartingSelectionPosition(-1)
                .setMustFillScreen(false)
                .setNumItems(1001)
                .setNumColumns(4)
                .setItemScreenSizeFactor(0.20)
                .setVerticalSpacing(20);
    }
    
    
    
    @Override
    protected ListAdapter createAdapter() {
        return new DeleteAdapter(getInitialNumItems());
    }

    
    
    
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            GridView g = getGridView();
            ((DeleteAdapter)g.getAdapter()).deletePosition(g.getSelectedItemPosition());
            return true;
        } else {
            return super.onKeyDown(keyCode, event);
        }
    }




    private class DeleteAdapter extends BaseAdapter {
        
        private ArrayList<Integer> mData;
        
        public DeleteAdapter(int initialNumItems) {
            super();
            mData = new ArrayList<Integer>(initialNumItems);
            
            int i;
            for (i=0; i<initialNumItems; ++i) {
                mData.add(new Integer(10000 + i));
            }
            
        }

        public void deletePosition(int selectedItemPosition) {
            if (selectedItemPosition >=0 && selectedItemPosition < mData.size()) {
                mData.remove(selectedItemPosition);
                notifyDataSetChanged();
            }
            
        }

        public int getCount() {
            return mData.size();
        }

        public Object getItem(int position) {
            return mData.get(position);
        }

        public long getItemId(int position) {
            return mData.get(position);
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            int desiredHeight = getDesiredItemHeight();
            return createView(mData.get(position), parent, desiredHeight);
        }
    }
}
