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

import android.annotation.DrawableRes;
import android.annotation.IntDef;
import android.annotation.LayoutRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnFocusChangeListener;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.view.ViewHierarchyEncoder;
import android.view.ViewParent;
import android.view.Window;
import android.widget.SpinnerAdapter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A primary toolbar within the activity that may display the activity title, application-level
 * navigation affordances, and other interactive items.
 *
 * <p>Beginning with Android 3.0 (API level 11), the action bar appears at the top of an
 * activity's window when the activity uses the system's {@link
 * android.R.style#Theme_Holo Holo} theme (or one of its descendant themes), which is the default.
 * You may otherwise add the action bar by calling {@link
 * android.view.Window#requestFeature requestFeature(FEATURE_ACTION_BAR)} or by declaring it in a
 * custom theme with the {@link android.R.styleable#Theme_windowActionBar windowActionBar} property.
 * </p>
 *
 * <p>Beginning with Android L (API level 21), the action bar may be represented by any
 * Toolbar widget within the application layout. The application may signal to the Activity
 * which Toolbar should be treated as the Activity's action bar. Activities that use this
 * feature should use one of the supplied <code>.NoActionBar</code> themes, set the
 * {@link android.R.styleable#Theme_windowActionBar windowActionBar} attribute to <code>false</code>
 * or otherwise not request the window feature.</p>
 *
 * <p>By adjusting the window features requested by the theme and the layouts used for
 * an Activity's content view, an app can use the standard system action bar on older platform
 * releases and the newer inline toolbars on newer platform releases. The <code>ActionBar</code>
 * object obtained from the Activity can be used to control either configuration transparently.</p>
 *
 * <p>When using the Holo themes the action bar shows the application icon on
 * the left, followed by the activity title. If your activity has an options menu, you can make
 * select items accessible directly from the action bar as "action items". You can also
 * modify various characteristics of the action bar or remove it completely.</p>
 *
 * <p>When using the Material themes (default in API 21 or newer) the navigation button
 * (formerly "Home") takes over the space previously occupied by the application icon.
 * Apps wishing to express a stronger branding should use their brand colors heavily
 * in the action bar and other application chrome or use a {@link #setLogo(int) logo}
 * in place of their standard title text.</p>
 *
 * <p>From your activity, you can retrieve an instance of {@link ActionBar} by calling {@link
 * android.app.Activity#getActionBar getActionBar()}.</p>
 *
 * <p>In some cases, the action bar may be overlayed by another bar that enables contextual actions,
 * using an {@link android.view.ActionMode}. For example, when the user selects one or more items in
 * your activity, you can enable an action mode that offers actions specific to the selected
 * items, with a UI that temporarily replaces the action bar. Although the UI may occupy the
 * same space, the {@link android.view.ActionMode} APIs are distinct and independent from those for
 * {@link ActionBar}.</p>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For information about how to use the action bar, including how to add action items, navigation
 * modes and more, read the <a href="{@docRoot}guide/topics/ui/actionbar.html">Action
 * Bar</a> developer guide.</p>
 * </div>
 */
public abstract class ActionBar {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({NAVIGATION_MODE_STANDARD, NAVIGATION_MODE_LIST, NAVIGATION_MODE_TABS})
    public @interface NavigationMode {}

    /**
     * Standard navigation mode. Consists of either a logo or icon
     * and title text with an optional subtitle. Clicking any of these elements
     * will dispatch onOptionsItemSelected to the host Activity with
     * a MenuItem with item ID android.R.id.home.
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public static final int NAVIGATION_MODE_STANDARD = 0;
    
    /**
     * List navigation mode. Instead of static title text this mode
     * presents a list menu for navigation within the activity.
     * e.g. this might be presented to the user as a dropdown list.
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public static final int NAVIGATION_MODE_LIST = 1;
    
    /**
     * Tab navigation mode. Instead of static title text this mode
     * presents a series of tabs for navigation within the activity.
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public static final int NAVIGATION_MODE_TABS = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true,
            value = {
                    DISPLAY_USE_LOGO,
                    DISPLAY_SHOW_HOME,
                    DISPLAY_HOME_AS_UP,
                    DISPLAY_SHOW_TITLE,
                    DISPLAY_SHOW_CUSTOM,
                    DISPLAY_TITLE_MULTIPLE_LINES
            })
    public @interface DisplayOptions {}

    /**
     * Use logo instead of icon if available. This flag will cause appropriate
     * navigation modes to use a wider logo in place of the standard icon.
     *
     * @see #setDisplayOptions(int)
     * @see #setDisplayOptions(int, int)
     */
    public static final int DISPLAY_USE_LOGO = 0x1;
    
    /**
     * Show 'home' elements in this action bar, leaving more space for other
     * navigation elements. This includes logo and icon.
     *
     * @see #setDisplayOptions(int)
     * @see #setDisplayOptions(int, int)
     */
    public static final int DISPLAY_SHOW_HOME = 0x2;

    /**
     * Display the 'home' element such that it appears as an 'up' affordance.
     * e.g. show an arrow to the left indicating the action that will be taken.
     *
     * Set this flag if selecting the 'home' button in the action bar to return
     * up by a single level in your UI rather than back to the top level or front page.
     *
     * <p>Setting this option will implicitly enable interaction with the home/up
     * button. See {@link #setHomeButtonEnabled(boolean)}.
     *
     * @see #setDisplayOptions(int)
     * @see #setDisplayOptions(int, int)
     */
    public static final int DISPLAY_HOME_AS_UP = 0x4;

    /**
     * Show the activity title and subtitle, if present.
     *
     * @see #setTitle(CharSequence)
     * @see #setTitle(int)
     * @see #setSubtitle(CharSequence)
     * @see #setSubtitle(int)
     * @see #setDisplayOptions(int)
     * @see #setDisplayOptions(int, int)
     */
    public static final int DISPLAY_SHOW_TITLE = 0x8;

    /**
     * Show the custom view if one has been set.
     * @see #setCustomView(View)
     * @see #setDisplayOptions(int)
     * @see #setDisplayOptions(int, int)
     */
    public static final int DISPLAY_SHOW_CUSTOM = 0x10;

    /**
     * Allow the title to wrap onto multiple lines if space is available
     * @hide pending API approval
     */
    public static final int DISPLAY_TITLE_MULTIPLE_LINES = 0x20;

    /**
     * Set the action bar into custom navigation mode, supplying a view
     * for custom navigation.
     *
     * Custom navigation views appear between the application icon and
     * any action buttons and may use any space available there. Common
     * use cases for custom navigation views might include an auto-suggesting
     * address bar for a browser or other navigation mechanisms that do not
     * translate well to provided navigation modes.
     *
     * @param view Custom navigation view to place in the ActionBar.
     */
    public abstract void setCustomView(View view);

    /**
     * Set the action bar into custom navigation mode, supplying a view
     * for custom navigation.
     * 
     * <p>Custom navigation views appear between the application icon and
     * any action buttons and may use any space available there. Common
     * use cases for custom navigation views might include an auto-suggesting
     * address bar for a browser or other navigation mechanisms that do not
     * translate well to provided navigation modes.</p>
     *
     * <p>The display option {@link #DISPLAY_SHOW_CUSTOM} must be set for
     * the custom view to be displayed.</p>
     * 
     * @param view Custom navigation view to place in the ActionBar.
     * @param layoutParams How this custom view should layout in the bar.
     *
     * @see #setDisplayOptions(int, int)
     */
    public abstract void setCustomView(View view, LayoutParams layoutParams);

    /**
     * Set the action bar into custom navigation mode, supplying a view
     * for custom navigation.
     *
     * <p>Custom navigation views appear between the application icon and
     * any action buttons and may use any space available there. Common
     * use cases for custom navigation views might include an auto-suggesting
     * address bar for a browser or other navigation mechanisms that do not
     * translate well to provided navigation modes.</p>
     *
     * <p>The display option {@link #DISPLAY_SHOW_CUSTOM} must be set for
     * the custom view to be displayed.</p>
     *
     * @param resId Resource ID of a layout to inflate into the ActionBar.
     *
     * @see #setDisplayOptions(int, int)
     */
    public abstract void setCustomView(@LayoutRes int resId);

    /**
     * Set the icon to display in the 'home' section of the action bar.
     * The action bar will use an icon specified by its style or the
     * activity icon by default.
     *
     * Whether the home section shows an icon or logo is controlled
     * by the display option {@link #DISPLAY_USE_LOGO}.
     *
     * @param resId Resource ID of a drawable to show as an icon.
     *
     * @see #setDisplayUseLogoEnabled(boolean)
     * @see #setDisplayShowHomeEnabled(boolean)
     */
    public abstract void setIcon(@DrawableRes int resId);

    /**
     * Set the icon to display in the 'home' section of the action bar.
     * The action bar will use an icon specified by its style or the
     * activity icon by default.
     *
     * Whether the home section shows an icon or logo is controlled
     * by the display option {@link #DISPLAY_USE_LOGO}.
     *
     * @param icon Drawable to show as an icon.
     *
     * @see #setDisplayUseLogoEnabled(boolean)
     * @see #setDisplayShowHomeEnabled(boolean)
     */
    public abstract void setIcon(Drawable icon);

    /**
     * Set the logo to display in the 'home' section of the action bar.
     * The action bar will use a logo specified by its style or the
     * activity logo by default.
     *
     * Whether the home section shows an icon or logo is controlled
     * by the display option {@link #DISPLAY_USE_LOGO}.
     *
     * @param resId Resource ID of a drawable to show as a logo.
     *
     * @see #setDisplayUseLogoEnabled(boolean)
     * @see #setDisplayShowHomeEnabled(boolean)
     */
    public abstract void setLogo(@DrawableRes int resId);

    /**
     * Set the logo to display in the 'home' section of the action bar.
     * The action bar will use a logo specified by its style or the
     * activity logo by default.
     *
     * Whether the home section shows an icon or logo is controlled
     * by the display option {@link #DISPLAY_USE_LOGO}.
     *
     * @param logo Drawable to show as a logo.
     *
     * @see #setDisplayUseLogoEnabled(boolean)
     * @see #setDisplayShowHomeEnabled(boolean)
     */
    public abstract void setLogo(Drawable logo);

    /**
     * Set the adapter and navigation callback for list navigation mode.
     *
     * The supplied adapter will provide views for the expanded list as well as
     * the currently selected item. (These may be displayed differently.)
     *
     * The supplied OnNavigationListener will alert the application when the user
     * changes the current list selection.
     *
     * @param adapter An adapter that will provide views both to display
     *                the current navigation selection and populate views
     *                within the dropdown navigation menu.
     * @param callback An OnNavigationListener that will receive events when the user
     *                 selects a navigation item.
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public abstract void setListNavigationCallbacks(SpinnerAdapter adapter,
            OnNavigationListener callback);

    /**
     * Set the selected navigation item in list or tabbed navigation modes.
     *
     * @param position Position of the item to select.
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public abstract void setSelectedNavigationItem(int position);

    /**
     * Get the position of the selected navigation item in list or tabbed navigation modes.
     *
     * @return Position of the selected item.
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public abstract int getSelectedNavigationIndex();

    /**
     * Get the number of navigation items present in the current navigation mode.
     *
     * @return Number of navigation items.
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public abstract int getNavigationItemCount();

    /**
     * Set the action bar's title. This will only be displayed if
     * {@link #DISPLAY_SHOW_TITLE} is set.
     *
     * @param title Title to set
     *
     * @see #setTitle(int)
     * @see #setDisplayOptions(int, int)
     */
    public abstract void setTitle(CharSequence title);

    /**
     * Set the action bar's title. This will only be displayed if
     * {@link #DISPLAY_SHOW_TITLE} is set.
     *
     * @param resId Resource ID of title string to set
     *
     * @see #setTitle(CharSequence)
     * @see #setDisplayOptions(int, int)
     */
    public abstract void setTitle(@StringRes int resId);

    /**
     * Set the action bar's subtitle. This will only be displayed if
     * {@link #DISPLAY_SHOW_TITLE} is set. Set to null to disable the
     * subtitle entirely.
     *
     * @param subtitle Subtitle to set
     *
     * @see #setSubtitle(int)
     * @see #setDisplayOptions(int, int)
     */
    public abstract void setSubtitle(CharSequence subtitle);

    /**
     * Set the action bar's subtitle. This will only be displayed if
     * {@link #DISPLAY_SHOW_TITLE} is set.
     *
     * @param resId Resource ID of subtitle string to set
     *
     * @see #setSubtitle(CharSequence)
     * @see #setDisplayOptions(int, int)
     */
    public abstract void setSubtitle(@StringRes int resId);

    /**
     * Set display options. This changes all display option bits at once. To change
     * a limited subset of display options, see {@link #setDisplayOptions(int, int)}.
     * 
     * @param options A combination of the bits defined by the DISPLAY_ constants
     *                defined in ActionBar.
     */
    public abstract void setDisplayOptions(@DisplayOptions int options);
    
    /**
     * Set selected display options. Only the options specified by mask will be changed.
     * To change all display option bits at once, see {@link #setDisplayOptions(int)}.
     * 
     * <p>Example: setDisplayOptions(0, DISPLAY_SHOW_HOME) will disable the
     * {@link #DISPLAY_SHOW_HOME} option.
     * setDisplayOptions(DISPLAY_SHOW_HOME, DISPLAY_SHOW_HOME | DISPLAY_USE_LOGO)
     * will enable {@link #DISPLAY_SHOW_HOME} and disable {@link #DISPLAY_USE_LOGO}.
     * 
     * @param options A combination of the bits defined by the DISPLAY_ constants
     *                defined in ActionBar.
     * @param mask A bit mask declaring which display options should be changed.
     */
    public abstract void setDisplayOptions(@DisplayOptions int options, @DisplayOptions int mask);

    /**
     * Set whether to display the activity logo rather than the activity icon.
     * A logo is often a wider, more detailed image.
     *
     * <p>To set several display options at once, see the setDisplayOptions methods.
     *
     * @param useLogo true to use the activity logo, false to use the activity icon.
     *
     * @see #setDisplayOptions(int)
     * @see #setDisplayOptions(int, int)
     */
    public abstract void setDisplayUseLogoEnabled(boolean useLogo);

    /**
     * Set whether to include the application home affordance in the action bar.
     * Home is presented as either an activity icon or logo.
     *
     * <p>To set several display options at once, see the setDisplayOptions methods.
     *
     * @param showHome true to show home, false otherwise.
     *
     * @see #setDisplayOptions(int)
     * @see #setDisplayOptions(int, int)
     */
    public abstract void setDisplayShowHomeEnabled(boolean showHome);

    /**
     * Set whether home should be displayed as an "up" affordance.
     * Set this to true if selecting "home" returns up by a single level in your UI
     * rather than back to the top level or front page.
     *
     * <p>To set several display options at once, see the setDisplayOptions methods.
     *
     * @param showHomeAsUp true to show the user that selecting home will return one
     *                     level up rather than to the top level of the app.
     *
     * @see #setDisplayOptions(int)
     * @see #setDisplayOptions(int, int)
     */
    public abstract void setDisplayHomeAsUpEnabled(boolean showHomeAsUp);

    /**
     * Set whether an activity title/subtitle should be displayed.
     *
     * <p>To set several display options at once, see the setDisplayOptions methods.
     *
     * @param showTitle true to display a title/subtitle if present.
     *
     * @see #setDisplayOptions(int)
     * @see #setDisplayOptions(int, int)
     */
    public abstract void setDisplayShowTitleEnabled(boolean showTitle);

    /**
     * Set whether a custom view should be displayed, if set.
     *
     * <p>To set several display options at once, see the setDisplayOptions methods.
     *
     * @param showCustom true if the currently set custom view should be displayed, false otherwise.
     *
     * @see #setDisplayOptions(int)
     * @see #setDisplayOptions(int, int)
     */
    public abstract void setDisplayShowCustomEnabled(boolean showCustom);

    /**
     * Set the ActionBar's background. This will be used for the primary
     * action bar.
     * 
     * @param d Background drawable
     * @see #setStackedBackgroundDrawable(Drawable)
     * @see #setSplitBackgroundDrawable(Drawable)
     */
    public abstract void setBackgroundDrawable(@Nullable Drawable d);

    /**
     * Set the ActionBar's stacked background. This will appear
     * in the second row/stacked bar on some devices and configurations.
     *
     * @param d Background drawable for the stacked row
     */
    public void setStackedBackgroundDrawable(Drawable d) { }

    /**
     * Set the ActionBar's split background. This will appear in
     * the split action bar containing menu-provided action buttons
     * on some devices and configurations.
     * <p>You can enable split action bar with {@link android.R.attr#uiOptions}
     *
     * @param d Background drawable for the split bar
     */
    public void setSplitBackgroundDrawable(Drawable d) { }

    /**
     * @return The current custom view.
     */
    public abstract View getCustomView();

    /**
     * Returns the current ActionBar title in standard mode.
     * Returns null if {@link #getNavigationMode()} would not return
     * {@link #NAVIGATION_MODE_STANDARD}. 
     *
     * @return The current ActionBar title or null.
     */
    public abstract CharSequence getTitle();
    
    /**
     * Returns the current ActionBar subtitle in standard mode.
     * Returns null if {@link #getNavigationMode()} would not return
     * {@link #NAVIGATION_MODE_STANDARD}. 
     *
     * @return The current ActionBar subtitle or null.
     */
    public abstract CharSequence getSubtitle();
    
    /**
     * Returns the current navigation mode. The result will be one of:
     * <ul>
     * <li>{@link #NAVIGATION_MODE_STANDARD}</li>
     * <li>{@link #NAVIGATION_MODE_LIST}</li>
     * <li>{@link #NAVIGATION_MODE_TABS}</li>
     * </ul>
     *
     * @return The current navigation mode.
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    @NavigationMode
    public abstract int getNavigationMode();

    /**
     * Set the current navigation mode.
     *
     * @param mode The new mode to set.
     * @see #NAVIGATION_MODE_STANDARD
     * @see #NAVIGATION_MODE_LIST
     * @see #NAVIGATION_MODE_TABS
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public abstract void setNavigationMode(@NavigationMode int mode);

    /**
     * @return The current set of display options. 
     */
    public abstract int getDisplayOptions();

    /**
     * Create and return a new {@link Tab}.
     * This tab will not be included in the action bar until it is added.
     *
     * <p>Very often tabs will be used to switch between {@link Fragment}
     * objects.  Here is a typical implementation of such tabs:</p>
     *
     * {@sample development/samples/ApiDemos/src/com/example/android/apis/app/FragmentTabs.java
     *      complete}
     *
     * @return A new Tab
     *
     * @see #addTab(Tab)
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public abstract Tab newTab();

    /**
     * Add a tab for use in tabbed navigation mode. The tab will be added at the end of the list.
     * If this is the first tab to be added it will become the selected tab.
     *
     * @param tab Tab to add
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public abstract void addTab(Tab tab);

    /**
     * Add a tab for use in tabbed navigation mode. The tab will be added at the end of the list.
     *
     * @param tab Tab to add
     * @param setSelected True if the added tab should become the selected tab.
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public abstract void addTab(Tab tab, boolean setSelected);

    /**
     * Add a tab for use in tabbed navigation mode. The tab will be inserted at
     * <code>position</code>. If this is the first tab to be added it will become
     * the selected tab.
     *
     * @param tab The tab to add
     * @param position The new position of the tab
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public abstract void addTab(Tab tab, int position);

    /**
     * Add a tab for use in tabbed navigation mode. The tab will be insterted at
     * <code>position</code>.
     *
     * @param tab The tab to add
     * @param position The new position of the tab
     * @param setSelected True if the added tab should become the selected tab.
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public abstract void addTab(Tab tab, int position, boolean setSelected);

    /**
     * Remove a tab from the action bar. If the removed tab was selected it will be deselected
     * and another tab will be selected if present.
     *
     * @param tab The tab to remove
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public abstract void removeTab(Tab tab);

    /**
     * Remove a tab from the action bar. If the removed tab was selected it will be deselected
     * and another tab will be selected if present.
     *
     * @param position Position of the tab to remove
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public abstract void removeTabAt(int position);

    /**
     * Remove all tabs from the action bar and deselect the current tab.
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public abstract void removeAllTabs();

    /**
     * Select the specified tab. If it is not a child of this action bar it will be added.
     *
     * <p>Note: If you want to select by index, use {@link #setSelectedNavigationItem(int)}.</p>
     *
     * @param tab Tab to select
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public abstract void selectTab(Tab tab);

    /**
     * Returns the currently selected tab if in tabbed navigation mode and there is at least
     * one tab present.
     *
     * @return The currently selected tab or null
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public abstract Tab getSelectedTab();

    /**
     * Returns the tab at the specified index.
     *
     * @param index Index value in the range 0-get
     * @return
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public abstract Tab getTabAt(int index);

    /**
     * Returns the number of tabs currently registered with the action bar.
     * @return Tab count
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public abstract int getTabCount();

    /**
     * Retrieve the current height of the ActionBar.
     *
     * @return The ActionBar's height
     */
    public abstract int getHeight();

    /**
     * Show the ActionBar if it is not currently showing.
     * If the window hosting the ActionBar does not have the feature
     * {@link Window#FEATURE_ACTION_BAR_OVERLAY} it will resize application
     * content to fit the new space available.
     *
     * <p>If you are hiding the ActionBar through
     * {@link View#SYSTEM_UI_FLAG_FULLSCREEN View.SYSTEM_UI_FLAG_FULLSCREEN},
     * you should not call this function directly.
     */
    public abstract void show();

    /**
     * Hide the ActionBar if it is currently showing.
     * If the window hosting the ActionBar does not have the feature
     * {@link Window#FEATURE_ACTION_BAR_OVERLAY} it will resize application
     * content to fit the new space available.
     *
     * <p>Instead of calling this function directly, you can also cause an
     * ActionBar using the overlay feature to hide through
     * {@link View#SYSTEM_UI_FLAG_FULLSCREEN View.SYSTEM_UI_FLAG_FULLSCREEN}.
     * Hiding the ActionBar through this system UI flag allows you to more
     * seamlessly hide it in conjunction with other screen decorations.
     */
    public abstract void hide();

    /**
     * @return <code>true</code> if the ActionBar is showing, <code>false</code> otherwise.
     */
    public abstract boolean isShowing();

    /**
     * Add a listener that will respond to menu visibility change events.
     *
     * @param listener The new listener to add
     */
    public abstract void addOnMenuVisibilityListener(OnMenuVisibilityListener listener);

    /**
     * Remove a menu visibility listener. This listener will no longer receive menu
     * visibility change events.
     *
     * @param listener A listener to remove that was previously added
     */
    public abstract void removeOnMenuVisibilityListener(OnMenuVisibilityListener listener);

    /**
     * Enable or disable the "home" button in the corner of the action bar. (Note that this
     * is the application home/up affordance on the action bar, not the systemwide home
     * button.)
     *
     * <p>This defaults to true for packages targeting &lt; API 14. For packages targeting
     * API 14 or greater, the application should call this method to enable interaction
     * with the home/up affordance.
     *
     * <p>Setting the {@link #DISPLAY_HOME_AS_UP} display option will automatically enable
     * the home button.
     *
     * @param enabled true to enable the home button, false to disable the home button.
     */
    public void setHomeButtonEnabled(boolean enabled) { }

    /**
     * Returns a {@link Context} with an appropriate theme for creating views that
     * will appear in the action bar. If you are inflating or instantiating custom views
     * that will appear in an action bar, you should use the Context returned by this method.
     * (This includes adapters used for list navigation mode.)
     * This will ensure that views contrast properly against the action bar.
     *
     * @return A themed Context for creating views
     */
    public Context getThemedContext() { return null; }

    /**
     * Returns true if the Title field has been truncated during layout for lack
     * of available space.
     *
     * @return true if the Title field has been truncated
     * @hide pending API approval
     */
    public boolean isTitleTruncated() { return false; }

    /**
     * Set an alternate drawable to display next to the icon/logo/title
     * when {@link #DISPLAY_HOME_AS_UP} is enabled. This can be useful if you are using
     * this mode to display an alternate selection for up navigation, such as a sliding drawer.
     *
     * <p>If you pass <code>null</code> to this method, the default drawable from the theme
     * will be used.</p>
     *
     * <p>If you implement alternate or intermediate behavior around Up, you should also
     * call {@link #setHomeActionContentDescription(int) setHomeActionContentDescription()}
     * to provide a correct description of the action for accessibility support.</p>
     *
     * @param indicator A drawable to use for the up indicator, or null to use the theme's default
     *
     * @see #setDisplayOptions(int, int)
     * @see #setDisplayHomeAsUpEnabled(boolean)
     * @see #setHomeActionContentDescription(int)
     */
    public void setHomeAsUpIndicator(Drawable indicator) { }

    /**
     * Set an alternate drawable to display next to the icon/logo/title
     * when {@link #DISPLAY_HOME_AS_UP} is enabled. This can be useful if you are using
     * this mode to display an alternate selection for up navigation, such as a sliding drawer.
     *
     * <p>If you pass <code>0</code> to this method, the default drawable from the theme
     * will be used.</p>
     *
     * <p>If you implement alternate or intermediate behavior around Up, you should also
     * call {@link #setHomeActionContentDescription(int) setHomeActionContentDescription()}
     * to provide a correct description of the action for accessibility support.</p>
     *
     * @param resId Resource ID of a drawable to use for the up indicator, or null
     *              to use the theme's default
     *
     * @see #setDisplayOptions(int, int)
     * @see #setDisplayHomeAsUpEnabled(boolean)
     * @see #setHomeActionContentDescription(int)
     */
    public void setHomeAsUpIndicator(@DrawableRes int resId) { }

    /**
     * Set an alternate description for the Home/Up action, when enabled.
     *
     * <p>This description is commonly used for accessibility/screen readers when
     * the Home action is enabled. (See {@link #setDisplayHomeAsUpEnabled(boolean)}.)
     * Examples of this are, "Navigate Home" or "Navigate Up" depending on the
     * {@link #DISPLAY_HOME_AS_UP} display option. If you have changed the home-as-up
     * indicator using {@link #setHomeAsUpIndicator(int)} to indicate more specific
     * functionality such as a sliding drawer, you should also set this to accurately
     * describe the action.</p>
     *
     * <p>Setting this to <code>null</code> will use the system default description.</p>
     *
     * @param description New description for the Home action when enabled
     * @see #setHomeAsUpIndicator(int)
     * @see #setHomeAsUpIndicator(android.graphics.drawable.Drawable)
     */
    public void setHomeActionContentDescription(CharSequence description) { }

    /**
     * Set an alternate description for the Home/Up action, when enabled.
     *
     * <p>This description is commonly used for accessibility/screen readers when
     * the Home action is enabled. (See {@link #setDisplayHomeAsUpEnabled(boolean)}.)
     * Examples of this are, "Navigate Home" or "Navigate Up" depending on the
     * {@link #DISPLAY_HOME_AS_UP} display option. If you have changed the home-as-up
     * indicator using {@link #setHomeAsUpIndicator(int)} to indicate more specific
     * functionality such as a sliding drawer, you should also set this to accurately
     * describe the action.</p>
     *
     * <p>Setting this to <code>0</code> will use the system default description.</p>
     *
     * @param resId Resource ID of a string to use as the new description
     *              for the Home action when enabled
     * @see #setHomeAsUpIndicator(int)
     * @see #setHomeAsUpIndicator(android.graphics.drawable.Drawable)
     */
    public void setHomeActionContentDescription(@StringRes int resId) { }

    /**
     * Enable hiding the action bar on content scroll.
     *
     * <p>If enabled, the action bar will scroll out of sight along with a
     * {@link View#setNestedScrollingEnabled(boolean) nested scrolling child} view's content.
     * The action bar must be in {@link Window#FEATURE_ACTION_BAR_OVERLAY overlay mode}
     * to enable hiding on content scroll.</p>
     *
     * <p>When partially scrolled off screen the action bar is considered
     * {@link #hide() hidden}. A call to {@link #show() show} will cause it to return to full view.
     * </p>
     * @param hideOnContentScroll true to enable hiding on content scroll.
     */
    public void setHideOnContentScrollEnabled(boolean hideOnContentScroll) {
        if (hideOnContentScroll) {
            throw new UnsupportedOperationException("Hide on content scroll is not supported in " +
                    "this action bar configuration.");
        }
    }

    /**
     * Return whether the action bar is configured to scroll out of sight along with
     * a {@link View#setNestedScrollingEnabled(boolean) nested scrolling child}.
     *
     * @return true if hide-on-content-scroll is enabled
     * @see #setHideOnContentScrollEnabled(boolean)
     */
    public boolean isHideOnContentScrollEnabled() {
        return false;
    }

    /**
     * Return the current vertical offset of the action bar.
     *
     * <p>The action bar's current hide offset is the distance that the action bar is currently
     * scrolled offscreen in pixels. The valid range is 0 (fully visible) to the action bar's
     * current measured {@link #getHeight() height} (fully invisible).</p>
     *
     * @return The action bar's offset toward its fully hidden state in pixels
     */
    public int getHideOffset() {
        return 0;
    }

    /**
     * Set the current hide offset of the action bar.
     *
     * <p>The action bar's current hide offset is the distance that the action bar is currently
     * scrolled offscreen in pixels. The valid range is 0 (fully visible) to the action bar's
     * current measured {@link #getHeight() height} (fully invisible).</p>
     *
     * @param offset The action bar's offset toward its fully hidden state in pixels.
     */
    public void setHideOffset(int offset) {
        if (offset != 0) {
            throw new UnsupportedOperationException("Setting an explicit action bar hide offset " +
                    "is not supported in this action bar configuration.");
        }
    }

    /**
     * Set the Z-axis elevation of the action bar in pixels.
     *
     * <p>The action bar's elevation is the distance it is placed from its parent surface. Higher
     * values are closer to the user.</p>
     *
     * @param elevation Elevation value in pixels
     */
    public void setElevation(float elevation) {
        if (elevation != 0) {
            throw new UnsupportedOperationException("Setting a non-zero elevation is " +
                    "not supported in this action bar configuration.");
        }
    }

    /**
     * Get the Z-axis elevation of the action bar in pixels.
     *
     * <p>The action bar's elevation is the distance it is placed from its parent surface. Higher
     * values are closer to the user.</p>
     *
     * @return Elevation value in pixels
     */
    public float getElevation() {
        return 0;
    }

    /** @hide */
    public void setDefaultDisplayHomeAsUpEnabled(boolean enabled) {
    }

    /** @hide */
    public void setShowHideAnimationEnabled(boolean enabled) {
    }

    /** @hide */
    public void onConfigurationChanged(Configuration config) {
    }

    /** @hide */
    public void dispatchMenuVisibilityChanged(boolean visible) {
    }

    /** @hide */
    public ActionMode startActionMode(ActionMode.Callback callback) {
        return null;
    }

    /** @hide */
    public boolean openOptionsMenu() {
        return false;
    }

    /** @hide */
    public boolean invalidateOptionsMenu() {
        return false;
    }

    /** @hide */
    public boolean onMenuKeyEvent(KeyEvent event) {
        return false;
    }

    /** @hide */
    public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        return false;
    }

    /** @hide */
    public boolean collapseActionView() {
        return false;
    }

    /** @hide */
    public void setWindowTitle(CharSequence title) {
    }

    /**
     * Attempts to move focus to the ActionBar if it does not already contain the focus.
     *
     * @return {@code true} if focus changes or {@code false} if focus doesn't change.
     * @hide
     */
    public boolean requestFocus() {
        return false;
    }

    /** @hide */
    public void onDestroy() {
    }

    /**
     * Common implementation for requestFocus that takes in the Toolbar and moves focus
     * to the contents. This makes the ViewGroups containing the toolbar allow focus while it stays
     * in the ActionBar and then prevents it again once it leaves.
     *
     * @param viewGroup The toolbar ViewGroup
     * @return {@code true} if focus changes or {@code false} if focus doesn't change.
     * @hide
     */
    protected boolean requestFocus(ViewGroup viewGroup) {
        if (viewGroup != null && !viewGroup.hasFocus()) {
            final ViewGroup toolbar = viewGroup.getTouchscreenBlocksFocus() ? viewGroup : null;
            ViewParent parent = viewGroup.getParent();
            ViewGroup container = null;
            while (parent != null && parent instanceof ViewGroup) {
                final ViewGroup vgParent = (ViewGroup) parent;
                if (vgParent.getTouchscreenBlocksFocus()) {
                    container = vgParent;
                    break;
                }
                parent = vgParent.getParent();
            }
            if (container != null) {
                container.setTouchscreenBlocksFocus(false);
            }
            if (toolbar != null) {
                toolbar.setTouchscreenBlocksFocus(false);
            }
            viewGroup.requestFocus();
            final View focused = viewGroup.findFocus();
            if (focused != null) {
                focused.setOnFocusChangeListener(new FollowOutOfActionBar(viewGroup,
                        container, toolbar));
            } else {
                if (container != null) {
                    container.setTouchscreenBlocksFocus(true);
                }
                if (toolbar != null) {
                    toolbar.setTouchscreenBlocksFocus(true);
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Listener interface for ActionBar navigation events.
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public interface OnNavigationListener {
        /**
         * This method is called whenever a navigation item in your action bar
         * is selected.
         *    
         * @param itemPosition Position of the item clicked.
         * @param itemId ID of the item clicked.
         * @return True if the event was handled, false otherwise.
         */
        public boolean onNavigationItemSelected(int itemPosition, long itemId);
    }

    /**
     * Listener for receiving events when action bar menus are shown or hidden.
     */
    public interface OnMenuVisibilityListener {
        /**
         * Called when an action bar menu is shown or hidden. Applications may want to use
         * this to tune auto-hiding behavior for the action bar or pause/resume video playback,
         * gameplay, or other activity within the main content area.
         *
         * @param isVisible True if an action bar menu is now visible, false if no action bar
         *                  menus are visible.
         */
        public void onMenuVisibilityChanged(boolean isVisible);
    }

    /**
     * A tab in the action bar.
     *
     * <p>Tabs manage the hiding and showing of {@link Fragment}s.
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public static abstract class Tab {
        /**
         * An invalid position for a tab.
         *
         * @see #getPosition()
         */
        public static final int INVALID_POSITION = -1;

        /**
         * Return the current position of this tab in the action bar.
         *
         * @return Current position, or {@link #INVALID_POSITION} if this tab is not currently in
         *         the action bar.
         */
        public abstract int getPosition();

        /**
         * Return the icon associated with this tab.
         *
         * @return The tab's icon
         */
        public abstract Drawable getIcon();

        /**
         * Return the text of this tab.
         *
         * @return The tab's text
         */
        public abstract CharSequence getText();

        /**
         * Set the icon displayed on this tab.
         *
         * @param icon The drawable to use as an icon
         * @return The current instance for call chaining
         */
        public abstract Tab setIcon(Drawable icon);

        /**
         * Set the icon displayed on this tab.
         *
         * @param resId Resource ID referring to the drawable to use as an icon
         * @return The current instance for call chaining
         */
        public abstract Tab setIcon(@DrawableRes int resId);

        /**
         * Set the text displayed on this tab. Text may be truncated if there is not
         * room to display the entire string.
         *
         * @param text The text to display
         * @return The current instance for call chaining
         */
        public abstract Tab setText(CharSequence text);

        /**
         * Set the text displayed on this tab. Text may be truncated if there is not
         * room to display the entire string.
         *
         * @param resId A resource ID referring to the text that should be displayed
         * @return The current instance for call chaining
         */
        public abstract Tab setText(@StringRes int resId);

        /**
         * Set a custom view to be used for this tab. This overrides values set by
         * {@link #setText(CharSequence)} and {@link #setIcon(Drawable)}.
         *
         * @param view Custom view to be used as a tab.
         * @return The current instance for call chaining
         */
        public abstract Tab setCustomView(View view);

        /**
         * Set a custom view to be used for this tab. This overrides values set by
         * {@link #setText(CharSequence)} and {@link #setIcon(Drawable)}.
         *
         * @param layoutResId A layout resource to inflate and use as a custom tab view
         * @return The current instance for call chaining
         */
        public abstract Tab setCustomView(@LayoutRes int layoutResId);

        /**
         * Retrieve a previously set custom view for this tab.
         *
         * @return The custom view set by {@link #setCustomView(View)}.
         */
        public abstract View getCustomView();

        /**
         * Give this Tab an arbitrary object to hold for later use.
         *
         * @param obj Object to store
         * @return The current instance for call chaining
         */
        public abstract Tab setTag(Object obj);

        /**
         * @return This Tab's tag object.
         */
        public abstract Object getTag();

        /**
         * Set the {@link TabListener} that will handle switching to and from this tab.
         * All tabs must have a TabListener set before being added to the ActionBar.
         *
         * @param listener Listener to handle tab selection events
         * @return The current instance for call chaining
         */
        public abstract Tab setTabListener(TabListener listener);

        /**
         * Select this tab. Only valid if the tab has been added to the action bar.
         */
        public abstract void select();

        /**
         * Set a description of this tab's content for use in accessibility support.
         * If no content description is provided the title will be used.
         *
         * @param resId A resource ID referring to the description text
         * @return The current instance for call chaining
         * @see #setContentDescription(CharSequence)
         * @see #getContentDescription()
         */
        public abstract Tab setContentDescription(@StringRes int resId);

        /**
         * Set a description of this tab's content for use in accessibility support.
         * If no content description is provided the title will be used.
         *
         * @param contentDesc Description of this tab's content
         * @return The current instance for call chaining
         * @see #setContentDescription(int)
         * @see #getContentDescription()
         */
        public abstract Tab setContentDescription(CharSequence contentDesc);

        /**
         * Gets a brief description of this tab's content for use in accessibility support.
         *
         * @return Description of this tab's content
         * @see #setContentDescription(CharSequence)
         * @see #setContentDescription(int)
         */
        public abstract CharSequence getContentDescription();
    }

    /**
     * Callback interface invoked when a tab is focused, unfocused, added, or removed.
     *
     * @deprecated Action bar navigation modes are deprecated and not supported by inline
     * toolbar action bars. Consider using other
     * <a href="http://developer.android.com/design/patterns/navigation.html">common
     * navigation patterns</a> instead.
     */
    public interface TabListener {
        /**
         * Called when a tab enters the selected state.
         *
         * @param tab The tab that was selected
         * @param ft A {@link FragmentTransaction} for queuing fragment operations to execute
         *        during a tab switch. The previous tab's unselect and this tab's select will be
         *        executed in a single transaction. This FragmentTransaction does not support
         *        being added to the back stack.
         */
        public void onTabSelected(Tab tab, FragmentTransaction ft);

        /**
         * Called when a tab exits the selected state.
         *
         * @param tab The tab that was unselected
         * @param ft A {@link FragmentTransaction} for queuing fragment operations to execute
         *        during a tab switch. This tab's unselect and the newly selected tab's select
         *        will be executed in a single transaction. This FragmentTransaction does not
         *        support being added to the back stack.
         */
        public void onTabUnselected(Tab tab, FragmentTransaction ft);

        /**
         * Called when a tab that is already selected is chosen again by the user.
         * Some applications may use this action to return to the top level of a category.
         *
         * @param tab The tab that was reselected.
         * @param ft A {@link FragmentTransaction} for queuing fragment operations to execute
         *        once this method returns. This FragmentTransaction does not support
         *        being added to the back stack.
         */
        public void onTabReselected(Tab tab, FragmentTransaction ft);
    }

    /**
     * Per-child layout information associated with action bar custom views.
     *
     * @attr ref android.R.styleable#ActionBar_LayoutParams_layout_gravity
     */
    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        /**
         * Gravity for the view associated with these LayoutParams.
         *
         * @see android.view.Gravity
         */
        @ViewDebug.ExportedProperty(category = "layout", mapping = {
                @ViewDebug.IntToString(from =  -1,                       to = "NONE"),
                @ViewDebug.IntToString(from = Gravity.NO_GRAVITY,        to = "NONE"),
                @ViewDebug.IntToString(from = Gravity.TOP,               to = "TOP"),
                @ViewDebug.IntToString(from = Gravity.BOTTOM,            to = "BOTTOM"),
                @ViewDebug.IntToString(from = Gravity.LEFT,              to = "LEFT"),
                @ViewDebug.IntToString(from = Gravity.RIGHT,             to = "RIGHT"),
                @ViewDebug.IntToString(from = Gravity.START,             to = "START"),
                @ViewDebug.IntToString(from = Gravity.END,               to = "END"),
                @ViewDebug.IntToString(from = Gravity.CENTER_VERTICAL,   to = "CENTER_VERTICAL"),
                @ViewDebug.IntToString(from = Gravity.FILL_VERTICAL,     to = "FILL_VERTICAL"),
                @ViewDebug.IntToString(from = Gravity.CENTER_HORIZONTAL, to = "CENTER_HORIZONTAL"),
                @ViewDebug.IntToString(from = Gravity.FILL_HORIZONTAL,   to = "FILL_HORIZONTAL"),
                @ViewDebug.IntToString(from = Gravity.CENTER,            to = "CENTER"),
                @ViewDebug.IntToString(from = Gravity.FILL,              to = "FILL")
        })
        public int gravity = Gravity.NO_GRAVITY;

        public LayoutParams(@NonNull Context c, AttributeSet attrs) {
            super(c, attrs);

            TypedArray a = c.obtainStyledAttributes(attrs,
                    com.android.internal.R.styleable.ActionBar_LayoutParams);
            gravity = a.getInt(
                    com.android.internal.R.styleable.ActionBar_LayoutParams_layout_gravity,
                    Gravity.NO_GRAVITY);
            a.recycle();
        }

        public LayoutParams(int width, int height) {
            super(width, height);
            this.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        }

        public LayoutParams(int width, int height, int gravity) {
            super(width, height);

            this.gravity = gravity;
        }

        public LayoutParams(int gravity) {
            this(WRAP_CONTENT, MATCH_PARENT, gravity);
        }

        public LayoutParams(LayoutParams source) {
            super(source);
            this.gravity = source.gravity;
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }

        /*
         * Note for framework developers:
         *
         * You might notice that ActionBar.LayoutParams is missing a constructor overload
         * for MarginLayoutParams. While it may seem like a good idea to add one, at this
         * point it's dangerous for source compatibility. Upon building against a new
         * version of the SDK an app can end up statically linking to the new MarginLayoutParams
         * overload, causing a crash when running on older platform versions with no other changes.
         */

        /** @hide */
        @Override
        protected void encodeProperties(@NonNull ViewHierarchyEncoder encoder) {
            super.encodeProperties(encoder);

            encoder.addProperty("gravity", gravity);
        }
    }

    /**
     * Tracks the focused View until it leaves the ActionBar, then it resets the
     * touchscreenBlocksFocus value.
     */
    private static class FollowOutOfActionBar implements OnFocusChangeListener, Runnable {
        private final ViewGroup mFocusRoot;
        private final ViewGroup mContainer;
        private final ViewGroup mToolbar;

        public FollowOutOfActionBar(ViewGroup focusRoot, ViewGroup container, ViewGroup toolbar) {
            mContainer = container;
            mToolbar = toolbar;
            mFocusRoot = focusRoot;
        }

        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if (!hasFocus) {
                v.setOnFocusChangeListener(null);
                final View focused = mFocusRoot.findFocus();
                if (focused != null) {
                    focused.setOnFocusChangeListener(this);
                } else {
                    mFocusRoot.post(this);
                }
            }
        }

        @Override
        public void run() {
            if (mContainer != null) {
                mContainer.setTouchscreenBlocksFocus(true);
            }
            if (mToolbar != null) {
                mToolbar.setTouchscreenBlocksFocus(true);
            }
        }
    }
}
