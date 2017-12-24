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

import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

/**
 * A fragment that displays a list of items by binding to a data source such as
 * an array or Cursor, and exposes event handlers when the user selects an item.
 * <p>
 * ListFragment hosts a {@link android.widget.ListView ListView} object that can
 * be bound to different data sources, typically either an array or a Cursor
 * holding query results. Binding, screen layout, and row layout are discussed
 * in the following sections.
 * <p>
 * <strong>Screen Layout</strong>
 * </p>
 * <p>
 * ListFragment has a default layout that consists of a single list view.
 * However, if you desire, you can customize the fragment layout by returning
 * your own view hierarchy from {@link #onCreateView}.
 * To do this, your view hierarchy <em>must</em> contain a ListView object with the
 * id "@android:id/list" (or {@link android.R.id#list} if it's in code)
 * <p>
 * Optionally, your view hierarchy can contain another view object of any type to
 * display when the list view is empty. This "empty list" notifier must have an
 * id "android:empty". Note that when an empty view is present, the list view
 * will be hidden when there is no data to display.
 * <p>
 * The following code demonstrates an (ugly) custom list layout. It has a list
 * with a green background, and an alternate red "no data" message.
 * </p>
 *
 * <pre>
 * &lt;?xml version=&quot;1.0&quot; encoding=&quot;utf-8&quot;?&gt;
 * &lt;LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
 *         android:orientation=&quot;vertical&quot;
 *         android:layout_width=&quot;match_parent&quot;
 *         android:layout_height=&quot;match_parent&quot;
 *         android:paddingLeft=&quot;8dp&quot;
 *         android:paddingRight=&quot;8dp&quot;&gt;
 *
 *     &lt;ListView android:id=&quot;@id/android:list&quot;
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
 * <p>
 * You can specify the layout of individual rows in the list. You do this by
 * specifying a layout resource in the ListAdapter object hosted by the fragment
 * (the ListAdapter binds the ListView to the data; more on this later).
 * <p>
 * A ListAdapter constructor takes a parameter that specifies a layout resource
 * for each row. It also has two additional parameters that let you specify
 * which data field to associate with which object in the row layout resource.
 * These two parameters are typically parallel arrays.
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
 * You bind the ListFragment's ListView object to data using a class that
 * implements the {@link android.widget.ListAdapter ListAdapter} interface.
 * Android provides two standard list adapters:
 * {@link android.widget.SimpleAdapter SimpleAdapter} for static data (Maps),
 * and {@link android.widget.SimpleCursorAdapter SimpleCursorAdapter} for Cursor
 * query results.
 * </p>
 * <p>
 * You <b>must</b> use
 * {@link #setListAdapter(ListAdapter) ListFragment.setListAdapter()} to
 * associate the list with an adapter.  Do not directly call
 * {@link ListView#setAdapter(ListAdapter) ListView.setAdapter()} or else
 * important initialization will be skipped.
 * </p>
 *
 * @see #setListAdapter
 * @see android.widget.ListView
 *
 * @deprecated Use the <a href="{@docRoot}tools/extras/support-library.html">Support Library</a>
 *      {@link android.support.v4.app.ListFragment} for consistent behavior across all devices
 *      and access to <a href="{@docRoot}topic/libraries/architecture/lifecycle.html">Lifecycle</a>.
 */
@Deprecated
public class ListFragment extends Fragment {
    final private Handler mHandler = new Handler();

    final private Runnable mRequestFocus = new Runnable() {
        public void run() {
            mList.focusableViewAvailable(mList);
        }
    };
    
    final private AdapterView.OnItemClickListener mOnClickListener
            = new AdapterView.OnItemClickListener() {
        public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
            onListItemClick((ListView)parent, v, position, id);
        }
    };

    ListAdapter mAdapter;
    ListView mList;
    View mEmptyView;
    TextView mStandardEmptyView;
    View mProgressContainer;
    View mListContainer;
    CharSequence mEmptyText;
    boolean mListShown;

    public ListFragment() {
    }

    /**
     * Provide default implementation to return a simple list view.  Subclasses
     * can override to replace with their own layout.  If doing so, the
     * returned view hierarchy <em>must</em> have a ListView whose id
     * is {@link android.R.id#list android.R.id.list} and can optionally
     * have a sibling view id {@link android.R.id#empty android.R.id.empty}
     * that is to be shown when the list is empty.
     * 
     * <p>If you are overriding this method with your own custom content,
     * consider including the standard layout {@link android.R.layout#list_content}
     * in your layout file, so that you continue to retain all of the standard
     * behavior of ListFragment.  In particular, this is currently the only
     * way to have the built-in indeterminant progress state be shown.
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(com.android.internal.R.layout.list_content,
                container, false);
    }

    /**
     * Attach to list view once the view hierarchy has been created.
     */
    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ensureList();
    }

    /**
     * Detach from list view.
     */
    @Override
    public void onDestroyView() {
        mHandler.removeCallbacks(mRequestFocus);
        mList = null;
        mListShown = false;
        mEmptyView = mProgressContainer = mListContainer = null;
        mStandardEmptyView = null;
        super.onDestroyView();
    }

    /**
     * This method will be called when an item in the list is selected.
     * Subclasses should override. Subclasses can call
     * getListView().getItemAtPosition(position) if they need to access the
     * data associated with the selected item.
     *
     * @param l The ListView where the click happened
     * @param v The view that was clicked within the ListView
     * @param position The position of the view in the list
     * @param id The row id of the item that was clicked
     */
    public void onListItemClick(ListView l, View v, int position, long id) {
    }

    /**
     * Provide the cursor for the list view.
     */
    public void setListAdapter(ListAdapter adapter) {
        boolean hadAdapter = mAdapter != null;
        mAdapter = adapter;
        if (mList != null) {
            mList.setAdapter(adapter);
            if (!mListShown && !hadAdapter) {
                // The list was hidden, and previously didn't have an
                // adapter.  It is now time to show it.
                setListShown(true, getView().getWindowToken() != null);
            }
        }
    }

    /**
     * Set the currently selected list item to the specified
     * position with the adapter's data
     *
     * @param position
     */
    public void setSelection(int position) {
        ensureList();
        mList.setSelection(position);
    }

    /**
     * Get the position of the currently selected list item.
     */
    public int getSelectedItemPosition() {
        ensureList();
        return mList.getSelectedItemPosition();
    }

    /**
     * Get the cursor row ID of the currently selected list item.
     */
    public long getSelectedItemId() {
        ensureList();
        return mList.getSelectedItemId();
    }

    /**
     * Get the fragment's list view widget.
     */
    public ListView getListView() {
        ensureList();
        return mList;
    }

    /**
     * The default content for a ListFragment has a TextView that can
     * be shown when the list is empty.  If you would like to have it
     * shown, call this method to supply the text it should use.
     */
    public void setEmptyText(CharSequence text) {
        ensureList();
        if (mStandardEmptyView == null) {
            throw new IllegalStateException("Can't be used with a custom content view");
        }
        mStandardEmptyView.setText(text);
        if (mEmptyText == null) {
            mList.setEmptyView(mStandardEmptyView);
        }
        mEmptyText = text;
    }
    
    /**
     * Control whether the list is being displayed.  You can make it not
     * displayed if you are waiting for the initial data to show in it.  During
     * this time an indeterminant progress indicator will be shown instead.
     * 
     * <p>Applications do not normally need to use this themselves.  The default
     * behavior of ListFragment is to start with the list not being shown, only
     * showing it once an adapter is given with {@link #setListAdapter(ListAdapter)}.
     * If the list at that point had not been shown, when it does get shown
     * it will be do without the user ever seeing the hidden state.
     * 
     * @param shown If true, the list view is shown; if false, the progress
     * indicator.  The initial value is true.
     */
    public void setListShown(boolean shown) {
        setListShown(shown, true);
    }
    
    /**
     * Like {@link #setListShown(boolean)}, but no animation is used when
     * transitioning from the previous state.
     */
    public void setListShownNoAnimation(boolean shown) {
        setListShown(shown, false);
    }
    
    /**
     * Control whether the list is being displayed.  You can make it not
     * displayed if you are waiting for the initial data to show in it.  During
     * this time an indeterminant progress indicator will be shown instead.
     * 
     * @param shown If true, the list view is shown; if false, the progress
     * indicator.  The initial value is true.
     * @param animate If true, an animation will be used to transition to the
     * new state.
     */
    private void setListShown(boolean shown, boolean animate) {
        ensureList();
        if (mProgressContainer == null) {
            throw new IllegalStateException("Can't be used with a custom content view");
        }
        if (mListShown == shown) {
            return;
        }
        mListShown = shown;
        if (shown) {
            if (animate) {
                mProgressContainer.startAnimation(AnimationUtils.loadAnimation(
                        getContext(), android.R.anim.fade_out));
                mListContainer.startAnimation(AnimationUtils.loadAnimation(
                        getContext(), android.R.anim.fade_in));
            } else {
                mProgressContainer.clearAnimation();
                mListContainer.clearAnimation();
            }
            mProgressContainer.setVisibility(View.GONE);
            mListContainer.setVisibility(View.VISIBLE);
        } else {
            if (animate) {
                mProgressContainer.startAnimation(AnimationUtils.loadAnimation(
                        getContext(), android.R.anim.fade_in));
                mListContainer.startAnimation(AnimationUtils.loadAnimation(
                        getContext(), android.R.anim.fade_out));
            } else {
                mProgressContainer.clearAnimation();
                mListContainer.clearAnimation();
            }
            mProgressContainer.setVisibility(View.VISIBLE);
            mListContainer.setVisibility(View.GONE);
        }
    }
    
    /**
     * Get the ListAdapter associated with this fragment's ListView.
     */
    public ListAdapter getListAdapter() {
        return mAdapter;
    }

    private void ensureList() {
        if (mList != null) {
            return;
        }
        View root = getView();
        if (root == null) {
            throw new IllegalStateException("Content view not yet created");
        }
        if (root instanceof ListView) {
            mList = (ListView)root;
        } else {
            mStandardEmptyView = (TextView)root.findViewById(
                    com.android.internal.R.id.internalEmpty);
            if (mStandardEmptyView == null) {
                mEmptyView = root.findViewById(android.R.id.empty);
            } else {
                mStandardEmptyView.setVisibility(View.GONE);
            }
            mProgressContainer = root.findViewById(com.android.internal.R.id.progressContainer);
            mListContainer = root.findViewById(com.android.internal.R.id.listContainer);
            View rawListView = root.findViewById(android.R.id.list);
            if (!(rawListView instanceof ListView)) {
                throw new RuntimeException(
                        "Content has view with id attribute 'android.R.id.list' "
                        + "that is not a ListView class");
            }
            mList = (ListView)rawListView;
            if (mList == null) {
                throw new RuntimeException(
                        "Your content must have a ListView whose id attribute is " +
                        "'android.R.id.list'");
            }
            if (mEmptyView != null) {
                mList.setEmptyView(mEmptyView);
            } else if (mEmptyText != null) {
                mStandardEmptyView.setText(mEmptyText);
                mList.setEmptyView(mStandardEmptyView);
            }
        }
        mListShown = true;
        mList.setOnItemClickListener(mOnClickListener);
        if (mAdapter != null) {
            ListAdapter adapter = mAdapter;
            mAdapter = null;
            setListAdapter(adapter);
        } else {
            // We are starting without an adapter, so assume we won't
            // have our data right away and start with the progress indicator.
            if (mProgressContainer != null) {
                setListShown(false, false);
            }
        }
        mHandler.post(mRequestFocus);
    }
}
