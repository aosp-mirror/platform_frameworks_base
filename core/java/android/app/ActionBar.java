/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app;

import android.graphics.drawable.Drawable;
import android.view.ActionBarView;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

/**
 * This is the public interface to the contextual ActionBar.
 * The ActionBar acts as a replacement for the title bar in Activities.
 * It provides facilities for creating toolbar actions as well as
 * methods of navigating around an application. 
 */
public abstract class ActionBar {
	/**
	 * Normal/standard navigation mode. Consists of either a logo or icon
	 * and title text with an optional subtitle. Clicking any of these elements
	 * will dispatch onActionItemSelected to the registered Callback with
	 * a MenuItem with item ID android.R.id.home.
	 */
    public static final int NAVIGATION_MODE_NORMAL = 0;
    
    /**
     * Dropdown list navigation mode. Instead of static title text this mode
     * presents a dropdown menu for navigation within the activity.
     */
    public static final int NAVIGATION_MODE_DROPDOWN_LIST = 1;
    
    /**
     * Tab navigation mode. Instead of static title text this mode
     * presents a series of tabs for navigation within the activity.
     */
    public static final int NAVIGATION_MODE_TABS = 2;
    
    /**
     * Custom navigation mode. This navigation mode is set implicitly whenever
     * a custom navigation view is set. See {@link #setCustomNavigationView(View)}.
     */
    public static final int NAVIGATION_MODE_CUSTOM = 3;

    /**
     * Use logo instead of icon if available. This flag will cause appropriate
     * navigation modes to use a wider logo in place of the standard icon.
     */
    public static final int DISPLAY_USE_LOGO = 0x1;
    
    /**
     * Hide 'home' elements in this action bar, leaving more space for other
     * navigation elements. This includes logo and icon.
     */
    public static final int DISPLAY_HIDE_HOME = 0x2;
    
    /**
     * Set the callback that the ActionBar will use to handle events
     * and populate menus.
     * @param callback Callback to use
     */
    public abstract void setCallback(Callback callback);
    
    /**
     * Set a custom navigation view.
     * 
     * Custom navigation views appear between the application icon and
     * any action buttons and may use any space available there. Common
     * use cases for custom navigation views might include an address bar
     * for a browser or other navigation mechanisms that do not translate
     * well to provided navigation modes.
     * 
     * Setting a non-null custom navigation view will also set the
     * navigation mode to NAVMODE_CUSTOM.
     * 
     * @param view Custom navigation view to place in the ActionBar.
     */
    public abstract void setCustomNavigationView(View view);
    
    /**
     * Set the ActionBar's title.
     * 
     * This is set automatically to the name of your Activity,
     * but may be changed here.
     * 
     * @param title Title text
     */
    public abstract void setTitle(CharSequence title);
    
    /**
     * Set the ActionBar's subtitle.
     * 
     * The subtitle is usually displayed as a second line of text
     * under the title. Good for extended descriptions of activity state.
     * 
     * @param subtitle Subtitle text. 
     */
    public abstract void setSubtitle(CharSequence subtitle);
    
    /**
     * Set the navigation mode.
     *
     * @param mode One of {@link #NAVIGATION_MODE_NORMAL}, {@link #NAVIGATION_MODE_DROPDOWN_LIST},
     * {@link #NAVIGATION_MODE_TABS}, or {@link #NAVIGATION_MODE_CUSTOM}.
     */
    public abstract void setNavigationMode(int mode);
    
    /**
     * Set display options.
     * 
     * @param options A combination of the bits defined by the DISPLAY_ constants
     *                defined in ActionBar.
     */
    public abstract void setDisplayOptions(int options);
    
    /**
     * Set the ActionBar's background.
     * 
     * @param d Background drawable
     */
    public abstract void setBackgroundDrawable(Drawable d);
    
    /**
     * Set a drawable to use as a divider between sections of the ActionBar.
     * 
     * @param d Divider drawable
     */
    public abstract void setDividerDrawable(Drawable d);
    
    /**
     * @return The current custom navigation view.
     */
    public abstract View getCustomNavigationView();
    
    /**
     * @return The current ActionBar title.
     */
    public abstract CharSequence getTitle();
    
    /**
     * @return The current ActionBar subtitle.
     */
    public abstract CharSequence getSubtitle();
    
    /**
     * @return The current navigation mode.
     */
    public abstract int getNavigationMode();
    
    /**
     * @return The current set of display options. 
     */
    public abstract int getDisplayOptions();
    
    /**
     * Request an update of the items in the action menu.
     * This will result in a call to Callback.onUpdateActionMenu(Menu)
     * and the ActionBar will update based on any changes made there.
     */
    public abstract void updateActionMenu();
    
    /**
     * Callback interface for ActionBar events. 
     */
    public interface Callback {
        /**
         * Initialize the always-visible contents of the action bar.
         * You should place your menu items into <var>menu</var>.
         * 
         * <p>This is only called once, the first time the action bar is displayed.
         *
         * @param menu The action menu in which to place your items.
         * @return You must return true for actions to be displayed;
         *         if you return false they will not be shown.
         *
         * @see #onActionItemSelected(MenuItem)
         */
        public boolean onCreateActionMenu(Menu menu);

        /**
         * Update the action bar. This is called in response to {@link #updateActionMenu()}
         * calls, which may be application-initiated or the result of changing fragment state.
         * 
         * @return true if the action bar should update based on altered menu contents,
         *         false if no changes are necessary.
         */
        public boolean onUpdateActionMenu(Menu menu);

        /**
         * This hook is called whenever an item in your action bar is selected.
         * The default implementation simply returns false to have the normal
         * processing happen (sending a message to its handler). You can use this
         * method for any items for which you would like to do processing without
         * those other facilities.
         * 
         * @param item The action bar item that was selected.
         * @return boolean Return false to allow normal menu processing to proceed,
         *         true to consume it here.
         */
        public boolean onActionItemSelected(MenuItem item);

        /*
         * In progress
         */
        public boolean onCreateContextMode(int modeId, Menu menu);
        public boolean onPrepareContextMode(int modeId, Menu menu);
        public boolean onContextItemSelected(int modeId, MenuItem item);
    }
    
    /**
     * Simple stub implementations of ActionBar.Callback methods.
     * Extend this if you only need a subset of Callback functionality.
     */
    public static class SimpleCallback implements Callback {
        public boolean onCreateActionMenu(Menu menu) {
            return false;
        }
        
        public boolean onUpdateActionMenu(Menu menu) {
            return false;
        }
        
        public boolean onActionItemSelected(MenuItem item) {
            return false;
        }
        
        public boolean onCreateContextMode(int modeId, Menu menu) {
            return false;
        }
        
        public boolean onPrepareContextMode(int modeId, Menu menu) {
            return false;
        }
        
        public boolean onContextItemSelected(int modeId, MenuItem item) {
            return false;
        }
    }

}
