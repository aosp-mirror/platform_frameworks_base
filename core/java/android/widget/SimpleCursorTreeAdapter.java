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

package android.widget;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.view.View;

/**
 * An easy adapter to map columns from a cursor to TextViews or ImageViews
 * defined in an XML file. You can specify which columns you want, which views
 * you want to display the columns, and the XML file that defines the appearance
 * of these views. Separate XML files for child and groups are possible.
 *
 * Binding occurs in two phases. First, if a
 * {@link android.widget.SimpleCursorTreeAdapter.ViewBinder} is available,
 * {@link ViewBinder#setViewValue(android.view.View, android.database.Cursor, int)}
 * is invoked. If the returned value is true, binding has occurred. If the
 * returned value is false and the view to bind is a TextView,
 * {@link #setViewText(TextView, String)} is invoked. If the returned value
 * is false and the view to bind is an ImageView,
 * {@link #setViewImage(ImageView, String)} is invoked. If no appropriate
 * binding can be found, an {@link IllegalStateException} is thrown.
 */
public abstract class SimpleCursorTreeAdapter extends ResourceCursorTreeAdapter {
    
    /** The name of the columns that contain the data to display for a group. */
    private String[] mGroupFromNames;
    
    /** The indices of columns that contain data to display for a group. */
    private int[] mGroupFrom;
    /**
     * The View IDs that will display a group's data fetched from the
     * corresponding column.
     */
    private int[] mGroupTo;

    /** The name of the columns that contain the data to display for a child. */
    private String[] mChildFromNames;
    
    /** The indices of columns that contain data to display for a child. */
    private int[] mChildFrom;
    /**
     * The View IDs that will display a child's data fetched from the
     * corresponding column.
     */
    private int[] mChildTo;
    
    /**
     * View binder, if supplied
     */
    private ViewBinder mViewBinder;

    /**
     * Constructor.
     * 
     * @param context The context where the {@link ExpandableListView}
     *            associated with this {@link SimpleCursorTreeAdapter} is
     *            running
     * @param cursor The database cursor
     * @param collapsedGroupLayout The resource identifier of a layout file that
     *            defines the views for a collapsed group. The layout file
     *            should include at least those named views defined in groupTo.
     * @param expandedGroupLayout The resource identifier of a layout file that
     *            defines the views for an expanded group. The layout file
     *            should include at least those named views defined in groupTo.
     * @param groupFrom A list of column names that will be used to display the
     *            data for a group.
     * @param groupTo The group views (from the group layouts) that should
     *            display column in the "from" parameter. These should all be
     *            TextViews or ImageViews. The first N views in this list are
     *            given the values of the first N columns in the from parameter.
     * @param childLayout The resource identifier of a layout file that defines
     *            the views for a child (except the last). The layout file
     *            should include at least those named views defined in childTo.
     * @param lastChildLayout The resource identifier of a layout file that
     *            defines the views for the last child within a group. The
     *            layout file should include at least those named views defined
     *            in childTo.
     * @param childFrom A list of column names that will be used to display the
     *            data for a child.
     * @param childTo The child views (from the child layouts) that should
     *            display column in the "from" parameter. These should all be
     *            TextViews or ImageViews. The first N views in this list are
     *            given the values of the first N columns in the from parameter.
     */
    public SimpleCursorTreeAdapter(Context context, Cursor cursor, int collapsedGroupLayout,
            int expandedGroupLayout, String[] groupFrom, int[] groupTo, int childLayout,
            int lastChildLayout, String[] childFrom, int[] childTo) {
        super(context, cursor, collapsedGroupLayout, expandedGroupLayout, childLayout,
                lastChildLayout);
        init(groupFrom, groupTo, childFrom, childTo);
    }

    /**
     * Constructor.
     * 
     * @param context The context where the {@link ExpandableListView}
     *            associated with this {@link SimpleCursorTreeAdapter} is
     *            running
     * @param cursor The database cursor
     * @param collapsedGroupLayout The resource identifier of a layout file that
     *            defines the views for a collapsed group. The layout file
     *            should include at least those named views defined in groupTo.
     * @param expandedGroupLayout The resource identifier of a layout file that
     *            defines the views for an expanded group. The layout file
     *            should include at least those named views defined in groupTo.
     * @param groupFrom A list of column names that will be used to display the
     *            data for a group.
     * @param groupTo The group views (from the group layouts) that should
     *            display column in the "from" parameter. These should all be
     *            TextViews or ImageViews. The first N views in this list are
     *            given the values of the first N columns in the from parameter.
     * @param childLayout The resource identifier of a layout file that defines
     *            the views for a child. The layout file
     *            should include at least those named views defined in childTo.
     * @param childFrom A list of column names that will be used to display the
     *            data for a child.
     * @param childTo The child views (from the child layouts) that should
     *            display column in the "from" parameter. These should all be
     *            TextViews or ImageViews. The first N views in this list are
     *            given the values of the first N columns in the from parameter.
     */
    public SimpleCursorTreeAdapter(Context context, Cursor cursor, int collapsedGroupLayout,
            int expandedGroupLayout, String[] groupFrom, int[] groupTo,
            int childLayout, String[] childFrom, int[] childTo) {
        super(context, cursor, collapsedGroupLayout, expandedGroupLayout, childLayout);
        init(groupFrom, groupTo, childFrom, childTo);
    }

    /**
     * Constructor.
     * 
     * @param context The context where the {@link ExpandableListView}
     *            associated with this {@link SimpleCursorTreeAdapter} is
     *            running
     * @param cursor The database cursor
     * @param groupLayout The resource identifier of a layout file that defines
     *            the views for a group. The layout file should include at least
     *            those named views defined in groupTo.
     * @param groupFrom A list of column names that will be used to display the
     *            data for a group.
     * @param groupTo The group views (from the group layouts) that should
     *            display column in the "from" parameter. These should all be
     *            TextViews or ImageViews. The first N views in this list are
     *            given the values of the first N columns in the from parameter.
     * @param childLayout The resource identifier of a layout file that defines
     *            the views for a child. The layout file should include at least
     *            those named views defined in childTo.
     * @param childFrom A list of column names that will be used to display the
     *            data for a child.
     * @param childTo The child views (from the child layouts) that should
     *            display column in the "from" parameter. These should all be
     *            TextViews or ImageViews. The first N views in this list are
     *            given the values of the first N columns in the from parameter.
     */
    public SimpleCursorTreeAdapter(Context context, Cursor cursor, int groupLayout,
            String[] groupFrom, int[] groupTo, int childLayout, String[] childFrom,
            int[] childTo) {
        super(context, cursor, groupLayout, childLayout);
        init(groupFrom, groupTo, childFrom, childTo);
    }

    private void init(String[] groupFromNames, int[] groupTo, String[] childFromNames,
            int[] childTo) {
        
        mGroupFromNames = groupFromNames;
        mGroupTo = groupTo;
        
        mChildFromNames = childFromNames;
        mChildTo = childTo;
    }
    
    /**
     * Returns the {@link ViewBinder} used to bind data to views.
     *
     * @return a ViewBinder or null if the binder does not exist
     *
     * @see #setViewBinder(android.widget.SimpleCursorTreeAdapter.ViewBinder)
     */
    public ViewBinder getViewBinder() {
        return mViewBinder;
    }

    /**
     * Sets the binder used to bind data to views.
     *
     * @param viewBinder the binder used to bind data to views, can be null to
     *        remove the existing binder
     *
     * @see #getViewBinder()
     */
    public void setViewBinder(ViewBinder viewBinder) {
        mViewBinder = viewBinder;
    }

    private void bindView(View view, Context context, Cursor cursor, int[] from, int[] to) {
        final ViewBinder binder = mViewBinder;
        
        for (int i = 0; i < to.length; i++) {
            View v = view.findViewById(to[i]);
            if (v != null) {
                boolean bound = false;
                if (binder != null) {
                    bound = binder.setViewValue(v, cursor, from[i]);
                }
                
                if (!bound) {
                    String text = cursor.getString(from[i]);
                    if (text == null) {
                        text = "";
                    }
                    if (v instanceof TextView) {
                        setViewText((TextView) v, text);
                    } else if (v instanceof ImageView) {
                        setViewImage((ImageView) v, text);
                    } else {
                        throw new IllegalStateException("SimpleCursorTreeAdapter can bind values" +
                                " only to TextView and ImageView!");
                    }
                }
            }
        }
    }
    
    private void initFromColumns(Cursor cursor, String[] fromColumnNames, int[] fromColumns) {
        for (int i = fromColumnNames.length - 1; i >= 0; i--) {
            fromColumns[i] = cursor.getColumnIndexOrThrow(fromColumnNames[i]);
        }
    }
    
    @Override
    protected void bindChildView(View view, Context context, Cursor cursor, boolean isLastChild) {
        if (mChildFrom == null) {
            mChildFrom = new int[mChildFromNames.length];
            initFromColumns(cursor, mChildFromNames, mChildFrom);
        }
        
        bindView(view, context, cursor, mChildFrom, mChildTo);
    }

    @Override
    protected void bindGroupView(View view, Context context, Cursor cursor, boolean isExpanded) {
        if (mGroupFrom == null) {
            mGroupFrom = new int[mGroupFromNames.length];
            initFromColumns(cursor, mGroupFromNames, mGroupFrom);
        }
        
        bindView(view, context, cursor, mGroupFrom, mGroupTo);
    }

    /**
     * Called by bindView() to set the image for an ImageView. By default, the
     * value will be treated as a Uri. Intended to be overridden by Adapters
     * that need to filter strings retrieved from the database.
     * 
     * @param v ImageView to receive an image
     * @param value the value retrieved from the cursor
     */
    protected void setViewImage(ImageView v, String value) {
        try {
            v.setImageResource(Integer.parseInt(value));
        } catch (NumberFormatException nfe) {
            v.setImageURI(Uri.parse(value));
        }
    }

    /**
     * Called by bindView() to set the text for a TextView but only if
     * there is no existing ViewBinder or if the existing ViewBinder cannot
     * handle binding to a TextView.
     *
     * Intended to be overridden by Adapters that need to filter strings
     * retrieved from the database.
     * 
     * @param v TextView to receive text
     * @param text the text to be set for the TextView
     */
    public void setViewText(TextView v, String text) {
        v.setText(text);
    }

    /**
     * This class can be used by external clients of SimpleCursorTreeAdapter
     * to bind values from the Cursor to views.
     *
     * You should use this class to bind values from the Cursor to views
     * that are not directly supported by SimpleCursorTreeAdapter or to
     * change the way binding occurs for views supported by
     * SimpleCursorTreeAdapter.
     *
     * @see SimpleCursorTreeAdapter#setViewImage(ImageView, String) 
     * @see SimpleCursorTreeAdapter#setViewText(TextView, String)
     */
    public static interface ViewBinder {
        /**
         * Binds the Cursor column defined by the specified index to the specified view.
         *
         * When binding is handled by this ViewBinder, this method must return true.
         * If this method returns false, SimpleCursorTreeAdapter will attempts to handle
         * the binding on its own.
         *
         * @param view the view to bind the data to
         * @param cursor the cursor to get the data from
         * @param columnIndex the column at which the data can be found in the cursor
         *
         * @return true if the data was bound to the view, false otherwise
         */
        boolean setViewValue(View view, Cursor cursor, int columnIndex);
    }
}
