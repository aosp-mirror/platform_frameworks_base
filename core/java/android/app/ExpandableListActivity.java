/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.database.Cursor;
import android.os.Bundle;
import java.util.List;
import android.view.ContextMenu;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnCreateContextMenuListener;
import android.widget.ExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.SimpleCursorTreeAdapter;
import android.widget.SimpleExpandableListAdapter;
import android.widget.AdapterView.AdapterContextMenuInfo;

import java.util.Map;

/**
 * An activity that displays an expandable list of items by binding to a data
 * source implementing the ExpandableListAdapter, and exposes event handlers
 * when the user selects an item.
 * <p>
 * ExpandableListActivity hosts a
 * {@link android.widget.ExpandableListView ExpandableListView} object that can
 * be bound to different data sources that provide a two-levels of data (the
 * top-level is group, and below each group are children). Binding, screen
 * layout, and row layout are discussed in the following sections.
 * <p>
 * <strong>Screen Layout</strong>
 * </p>
 * <p>
 * ExpandableListActivity has a default layout that consists of a single,
 * full-screen, centered expandable list. However, if you desire, you can
 * customize the screen layout by setting your own view layout with
 * setContentView() in onCreate(). To do this, your own view MUST contain an
 * ExpandableListView object with the id "@android:id/list" (or
 * {@link android.R.id#list} if it's in code)
 * <p>
 * Optionally, your custom view can contain another view object of any type to
 * display when the list view is empty. This "empty list" notifier must have an
 * id "android:empty". Note that when an empty view is present, the expandable
 * list view will be hidden when there is no data to display.
 * <p>
 * The following code demonstrates an (ugly) custom screen layout. It has a list
 * with a green background, and an alternate red "no data" message.
 * </p>
 * 
 * <pre>
 * &lt;?xml version=&quot;1.0&quot; encoding=&quot;UTF-8&quot;?&gt;
 * &lt;LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
 *         android:orientation=&quot;vertical&quot;
 *         android:layout_width=&quot;match_parent&quot; 
 *         android:layout_height=&quot;match_parent&quot;
 *         android:paddingLeft=&quot;8dp&quot;
 *         android:paddingRight=&quot;8dp&quot;&gt;
 * 
 *     &lt;ExpandableListView android:id=&quot;@id/android:list&quot;
 *               android:layout_width=&quot;match_parent&quot; 
 *               android:layout_height=&quot;match_parent&quot;
 *               android:background=&quot;#00FF00&quot;
 *               android:layout_weight=&quot;1&quot;
 *               android:drawSelectorOnTop=&quot;false&quot;/&gt;
 * 
 *     &lt;TextView android:id=&quot;@id/android:empty&quot;
 *               android:layout_width=&quot;match_parent&quot; 
 *               android:layout_height=&quot;match_parent&quot;
 *               android:background=&quot;#FF0000&quot;
 *               android:text=&quot;No data&quot;/&gt;
 * &lt;/LinearLayout&gt;
 * </pre>
 * 
 * <p>
 * <strong>Row Layout</strong>
 * </p>
 * The {@link ExpandableListAdapter} set in the {@link ExpandableListActivity}
 * via {@link #setListAdapter(ExpandableListAdapter)} provides the {@link View}s
 * for each row. This adapter has separate methods for providing the group
 * {@link View}s and child {@link View}s. There are a couple provided
 * {@link ExpandableListAdapter}s that simplify use of adapters:
 * {@link SimpleCursorTreeAdapter} and {@link SimpleExpandableListAdapter}.
 * <p>
 * With these, you can specify the layout of individual rows for groups and
 * children in the list. These constructor takes a few parameters that specify
 * layout resources for groups and children. It also has additional parameters
 * that let you specify which data field to associate with which object in the
 * row layout resource. The {@link SimpleCursorTreeAdapter} fetches data from
 * {@link Cursor}s and the {@link SimpleExpandableListAdapter} fetches data
 * from {@link List}s of {@link Map}s.
 * </p>
 * <p>
 * Android provides some standard row layout resources. These are in the
 * {@link android.R.layout} class, and have names such as simple_list_item_1,
 * simple_list_item_2, and two_line_list_item. The following layout XML is the
 * source for the resource two_line_list_item, which displays two data
 * fields,one above the other, for each list row.
 * </p>
 * 
 * <pre>
 * &lt;?xml version=&quot;1.0&quot; encoding=&quot;utf-8&quot;?&gt;
 * &lt;LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
 *     android:layout_width=&quot;match_parent&quot;
 *     android:layout_height=&quot;wrap_content&quot;
 *     android:orientation=&quot;vertical&quot;&gt;
 * 
 *     &lt;TextView android:id=&quot;@+id/text1&quot;
 *         android:textSize=&quot;16sp&quot;
 *         android:textStyle=&quot;bold&quot;
 *         android:layout_width=&quot;match_parent&quot;
 *         android:layout_height=&quot;wrap_content&quot;/&gt;
 * 
 *     &lt;TextView android:id=&quot;@+id/text2&quot;
 *         android:textSize=&quot;16sp&quot;
 *         android:layout_width=&quot;match_parent&quot;
 *         android:layout_height=&quot;wrap_content&quot;/&gt;
 * &lt;/LinearLayout&gt;
 * </pre>
 * 
 * <p>
 * You must identify the data bound to each TextView object in this layout. The
 * syntax for this is discussed in the next section.
 * </p>
 * <p>
 * <strong>Binding to Data</strong>
 * </p>
 * <p>
 * You bind the ExpandableListActivity's ExpandableListView object to data using
 * a class that implements the
 * {@link android.widget.ExpandableListAdapter ExpandableListAdapter} interface.
 * Android provides two standard list adapters:
 * {@link android.widget.SimpleExpandableListAdapter SimpleExpandableListAdapter}
 * for static data (Maps), and
 * {@link android.widget.SimpleCursorTreeAdapter SimpleCursorTreeAdapter} for
 * Cursor query results.
 * </p>
 * 
 * @see #setListAdapter
 * @see android.widget.ExpandableListView
 */
public class ExpandableListActivity extends Activity implements
        OnCreateContextMenuListener,
        ExpandableListView.OnChildClickListener, ExpandableListView.OnGroupCollapseListener,
        ExpandableListView.OnGroupExpandListener {   
    ExpandableListAdapter mAdapter;
    ExpandableListView mList;
    boolean mFinishedStart = false;

    /**
     * Override this to populate the context menu when an item is long pressed. menuInfo
     * will contain an {@link android.widget.ExpandableListView.ExpandableListContextMenuInfo}
     * whose packedPosition is a packed position
     * that should be used with {@link ExpandableListView#getPackedPositionType(long)} and
     * the other similar methods.
     * <p>
     * {@inheritDoc}
     */
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
    }

    /**
     * Override this for receiving callbacks when a child has been clicked.
     * <p>
     * {@inheritDoc}
     */
    public boolean onChildClick(ExpandableListView parent, View v, int groupPosition,
            int childPosition, long id) {
        return false;
    }

    /**
     * Override this for receiving callbacks when a group has been collapsed.
     */
    public void onGroupCollapse(int groupPosition) {
    }

    /**
     * Override this for receiving callbacks when a group has been expanded.
     */
    public void onGroupExpand(int groupPosition) {
    }

    /**
     * Ensures the expandable list view has been created before Activity restores all
     * of the view states.
     * 
     *@see Activity#onRestoreInstanceState(Bundle)
     */
    @Override
    protected void onRestoreInstanceState(Bundle state) {
        ensureList();
        super.onRestoreInstanceState(state);
    }

    /**
     * Updates the screen state (current list and other views) when the
     * content changes.
     * 
     * @see Activity#onContentChanged()
     */
    @Override
    public void onContentChanged() {
        super.onContentChanged();
        View emptyView = findViewById(com.android.internal.R.id.empty);
        mList = (ExpandableListView)findViewById(com.android.internal.R.id.list);
        if (mList == null) {
            throw new RuntimeException(
                    "Your content must have a ExpandableListView whose id attribute is " +
                    "'android.R.id.list'");
        }
        if (emptyView != null) {
            mList.setEmptyView(emptyView);
        }
        mList.setOnChildClickListener(this);
        mList.setOnGroupExpandListener(this);
        mList.setOnGroupCollapseListener(this);
        
        if (mFinishedStart) {
            setListAdapter(mAdapter);
        }
        mFinishedStart = true;
    }

    /**
     * Provide the adapter for the expandable list.
     */
    public void setListAdapter(ExpandableListAdapter adapter) {
        synchronized (this) {
            ensureList();
            mAdapter = adapter;
            mList.setAdapter(adapter);
        }
    }

    /**
     * Get the activity's expandable list view widget.  This can be used to get the selection,
     * set the selection, and many other useful functions.
     * 
     * @see ExpandableListView
     */
    public ExpandableListView getExpandableListView() {
        ensureList();
        return mList;
    }
    
    /**
     * Get the ExpandableListAdapter associated with this activity's
     * ExpandableListView.
     */
    public ExpandableListAdapter getExpandableListAdapter() {
        return mAdapter;
    }

    private void ensureList() {
        if (mList != null) {
            return;
        }
        setContentView(com.android.internal.R.layout.expandable_list_content);
    }

    /**
     * Gets the ID of the currently selected group or child.
     * 
     * @return The ID of the currently selected group or child.
     */
    public long getSelectedId() {
        return mList.getSelectedId();
    }

    /**
     * Gets the position (in packed position representation) of the currently
     * selected group or child. Use
     * {@link ExpandableListView#getPackedPositionType},
     * {@link ExpandableListView#getPackedPositionGroup}, and
     * {@link ExpandableListView#getPackedPositionChild} to unpack the returned
     * packed position.
     * 
     * @return A packed position representation containing the currently
     *         selected group or child's position and type.
     */
    public long getSelectedPosition() {
        return mList.getSelectedPosition();
    }

    /**
     * Sets the selection to the specified child. If the child is in a collapsed
     * group, the group will only be expanded and child subsequently selected if
     * shouldExpandGroup is set to true, otherwise the method will return false.
     * 
     * @param groupPosition The position of the group that contains the child.
     * @param childPosition The position of the child within the group.
     * @param shouldExpandGroup Whether the child's group should be expanded if
     *            it is collapsed.
     * @return Whether the selection was successfully set on the child.
     */
    public boolean setSelectedChild(int groupPosition, int childPosition, boolean shouldExpandGroup) {
        return mList.setSelectedChild(groupPosition, childPosition, shouldExpandGroup);
    }

    /**
     * Sets the selection to the specified group.
     * @param groupPosition The position of the group that should be selected.
     */
    public void setSelectedGroup(int groupPosition) {
        mList.setSelectedGroup(groupPosition);
    }

}

