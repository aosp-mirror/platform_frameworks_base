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

package android.view.menu;

import android.app.Activity;
import android.os.Bundle;
import android.util.ListScenario;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;

/**
 * Utility base class for creating various Menu scenarios. Configurable by the
 * number of menu items. Used @link {@link ListScenario} as a reference.
 */
public class MenuScenario extends Activity implements MenuItem.OnMenuItemClickListener {
    private Params mParams = new Params();
    private Menu mMenu;
    private MenuItem[] mItems;
    private boolean[] mWasItemClicked;
    
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        dispatchInitParams();
    }

    private void dispatchInitParams() {
        onInitParams(mParams);
        onParamsChanged();
    }
    
    public void setParams(Params params) {
        mParams = params;
        onParamsChanged();
    }
    
    public void onParamsChanged() {
        mItems = new MenuItem[mParams.numItems];
        mWasItemClicked = new boolean[mParams.numItems];
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Safe to hold on to
        mMenu = menu;
        
        if (!mParams.shouldShowMenu) return false;
        
        MenuItem item;
        for (int i = 0; i < mParams.numItems; i++) {
            if ((item = onAddMenuItem(menu, i)) == null) {
                // Add a default item for this position if the subclasses
                // haven't
                CharSequence givenTitle = mParams.itemTitles.get(i);
                item = menu.add(0, 0, 0, (givenTitle != null) ? givenTitle : ("Item " + i));
            }
    
            if (item != null) {
                mItems[i] = item;
                
                if (mParams.listenForClicks) {
                    item.setOnMenuItemClickListener(this);
                }
            }
                
        }
        
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // Safe to hold on to
        mMenu = menu;

        return mParams.shouldShowMenu;
    }

    /**
     * Override this to add an item to the menu.
     * 
     * @param itemPosition The position of the item to add (only for your
     *            reference).
     * @return The item that was added to the menu, or null if nothing was
     *         added.
     */
    protected MenuItem onAddMenuItem(Menu menu, int itemPosition) {
        return null;
    }
    
    /**
     * Override this to set the parameters for the scenario. Call through to super first.
     * 
     * @param params
     */
    protected void onInitParams(Params params) {
    }
    
    public Menu getMenu() {
        return mMenu;
    }
    
    public boolean onMenuItemClick(MenuItem item) {
        final int position = findItemPosition(item);
        if (position < 0) return false;
        
        mWasItemClicked[position] = true;
        
        return true;
    }

    public boolean wasItemClicked(int position) {
        return mWasItemClicked[position];
    }

    /**
     * Finds the position for a given Item.
     * 
     * @param item The item to find.
     * @return The position, or -1 if not found.
     */
    public int findItemPosition(MenuItem item) {
        // Could create reverse mapping, but optimizations aren't important (yet :P)
        for (int i = 0; i < mParams.numItems; i++) {
            if (mItems[i] == item) return i;
        }
        
        return -1;
    }
    
    public static class Params {
        // Using as data structure, so no m prefix
        private boolean shouldShowMenu = true;
        private int numItems = 10;
        private boolean listenForClicks = true;
        private SparseArray<CharSequence> itemTitles = new SparseArray<CharSequence>();

        public Params setShouldShowMenu(boolean shouldShowMenu) {
            this.shouldShowMenu = shouldShowMenu;
            return this;
        }
        
        public Params setNumItems(int numItems) {
            this.numItems = numItems;
            return this;
        }
        
        public Params setListenForClicks(boolean listenForClicks) {
            this.listenForClicks = listenForClicks;
            return this;
        }
        
        public Params setItemTitle(int itemPos, CharSequence title) {
            itemTitles.put(itemPos, title);
            return this;
        }
    }
}
