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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.List;
import java.util.Map;

/**
 * An easy adapter to map static data to group and child views defined in an XML
 * file. You can separately specify the data backing the group as a List of
 * Maps. Each entry in the ArrayList corresponds to one group in the expandable
 * list. The Maps contain the data for each row. You also specify an XML file
 * that defines the views used to display a group, and a mapping from keys in
 * the Map to specific views. This process is similar for a child, except it is
 * one-level deeper so the data backing is specified as a List<List<Map>>,
 * where the first List corresponds to the group of the child, the second List
 * corresponds to the position of the child within the group, and finally the
 * Map holds the data for that particular child.
 */
public class SimpleExpandableListAdapter extends BaseExpandableListAdapter {
    private List<? extends Map<String, ?>> mGroupData;
    private int mExpandedGroupLayout;
    private int mCollapsedGroupLayout;
    private String[] mGroupFrom;
    private int[] mGroupTo;

    private List<? extends List<? extends Map<String, ?>>> mChildData;
    private int mChildLayout;
    private int mLastChildLayout;
    private String[] mChildFrom;
    private int[] mChildTo;

    private LayoutInflater mInflater;

    /**
     * Constructor
     *
     * @param context The context where the {@link ExpandableListView}
     *            associated with this {@link SimpleExpandableListAdapter} is
     *            running
     * @param groupData A List of Maps. Each entry in the List corresponds to
     *            one group in the list. The Maps contain the data for each
     *            group, and should include all the entries specified in
     *            "groupFrom"
     * @param groupFrom A list of keys that will be fetched from the Map
     *            associated with each group.
     * @param groupTo The group views that should display column in the
     *            "groupFrom" parameter. These should all be TextViews. The
     *            first N views in this list are given the values of the first N
     *            columns in the groupFrom parameter.
     * @param groupLayout resource identifier of a view layout that defines the
     *            views for a group. The layout file should include at least
     *            those named views defined in "groupTo"
     * @param childData A List of List of Maps. Each entry in the outer List
     *            corresponds to a group (index by group position), each entry
     *            in the inner List corresponds to a child within the group
     *            (index by child position), and the Map corresponds to the data
     *            for a child (index by values in the childFrom array). The Map
     *            contains the data for each child, and should include all the
     *            entries specified in "childFrom"
     * @param childFrom A list of keys that will be fetched from the Map
     *            associated with each child.
     * @param childTo The child views that should display column in the
     *            "childFrom" parameter. These should all be TextViews. The
     *            first N views in this list are given the values of the first N
     *            columns in the childFrom parameter.
     * @param childLayout resource identifier of a view layout that defines the
     *            views for a child. The layout file should include at least
     *            those named views defined in "childTo"
     */
    public SimpleExpandableListAdapter(Context context,
            List<? extends Map<String, ?>> groupData, int groupLayout,
            String[] groupFrom, int[] groupTo,
            List<? extends List<? extends Map<String, ?>>> childData,
            int childLayout, String[] childFrom, int[] childTo) {
        this(context, groupData, groupLayout, groupLayout, groupFrom, groupTo, childData,
                childLayout, childLayout, childFrom, childTo);
    }

    /**
     * Constructor
     *
     * @param context The context where the {@link ExpandableListView}
     *            associated with this {@link SimpleExpandableListAdapter} is
     *            running
     * @param groupData A List of Maps. Each entry in the List corresponds to
     *            one group in the list. The Maps contain the data for each
     *            group, and should include all the entries specified in
     *            "groupFrom"
     * @param groupFrom A list of keys that will be fetched from the Map
     *            associated with each group.
     * @param groupTo The group views that should display column in the
     *            "groupFrom" parameter. These should all be TextViews. The
     *            first N views in this list are given the values of the first N
     *            columns in the groupFrom parameter.
     * @param expandedGroupLayout resource identifier of a view layout that
     *            defines the views for an expanded group. The layout file
     *            should include at least those named views defined in "groupTo"
     * @param collapsedGroupLayout resource identifier of a view layout that
     *            defines the views for a collapsed group. The layout file
     *            should include at least those named views defined in "groupTo"
     * @param childData A List of List of Maps. Each entry in the outer List
     *            corresponds to a group (index by group position), each entry
     *            in the inner List corresponds to a child within the group
     *            (index by child position), and the Map corresponds to the data
     *            for a child (index by values in the childFrom array). The Map
     *            contains the data for each child, and should include all the
     *            entries specified in "childFrom"
     * @param childFrom A list of keys that will be fetched from the Map
     *            associated with each child.
     * @param childTo The child views that should display column in the
     *            "childFrom" parameter. These should all be TextViews. The
     *            first N views in this list are given the values of the first N
     *            columns in the childFrom parameter.
     * @param childLayout resource identifier of a view layout that defines the
     *            views for a child. The layout file should include at least
     *            those named views defined in "childTo"
     */
    public SimpleExpandableListAdapter(Context context,
            List<? extends Map<String, ?>> groupData, int expandedGroupLayout,
            int collapsedGroupLayout, String[] groupFrom, int[] groupTo,
            List<? extends List<? extends Map<String, ?>>> childData,
            int childLayout, String[] childFrom, int[] childTo) {
        this(context, groupData, expandedGroupLayout, collapsedGroupLayout,
                groupFrom, groupTo, childData, childLayout, childLayout,
                childFrom, childTo);
    }

    /**
     * Constructor
     *
     * @param context The context where the {@link ExpandableListView}
     *            associated with this {@link SimpleExpandableListAdapter} is
     *            running
     * @param groupData A List of Maps. Each entry in the List corresponds to
     *            one group in the list. The Maps contain the data for each
     *            group, and should include all the entries specified in
     *            "groupFrom"
     * @param groupFrom A list of keys that will be fetched from the Map
     *            associated with each group.
     * @param groupTo The group views that should display column in the
     *            "groupFrom" parameter. These should all be TextViews. The
     *            first N views in this list are given the values of the first N
     *            columns in the groupFrom parameter.
     * @param expandedGroupLayout resource identifier of a view layout that
     *            defines the views for an expanded group. The layout file
     *            should include at least those named views defined in "groupTo"
     * @param collapsedGroupLayout resource identifier of a view layout that
     *            defines the views for a collapsed group. The layout file
     *            should include at least those named views defined in "groupTo"
     * @param childData A List of List of Maps. Each entry in the outer List
     *            corresponds to a group (index by group position), each entry
     *            in the inner List corresponds to a child within the group
     *            (index by child position), and the Map corresponds to the data
     *            for a child (index by values in the childFrom array). The Map
     *            contains the data for each child, and should include all the
     *            entries specified in "childFrom"
     * @param childFrom A list of keys that will be fetched from the Map
     *            associated with each child.
     * @param childTo The child views that should display column in the
     *            "childFrom" parameter. These should all be TextViews. The
     *            first N views in this list are given the values of the first N
     *            columns in the childFrom parameter.
     * @param childLayout resource identifier of a view layout that defines the
     *            views for a child (unless it is the last child within a group,
     *            in which case the lastChildLayout is used). The layout file
     *            should include at least those named views defined in "childTo"
     * @param lastChildLayout resource identifier of a view layout that defines
     *            the views for the last child within each group. The layout
     *            file should include at least those named views defined in
     *            "childTo"
     */
    public SimpleExpandableListAdapter(Context context,
            List<? extends Map<String, ?>> groupData, int expandedGroupLayout,
            int collapsedGroupLayout, String[] groupFrom, int[] groupTo,
            List<? extends List<? extends Map<String, ?>>> childData,
            int childLayout, int lastChildLayout, String[] childFrom,
            int[] childTo) {
        mGroupData = groupData;
        mExpandedGroupLayout = expandedGroupLayout;
        mCollapsedGroupLayout = collapsedGroupLayout;
        mGroupFrom = groupFrom;
        mGroupTo = groupTo;

        mChildData = childData;
        mChildLayout = childLayout;
        mLastChildLayout = lastChildLayout;
        mChildFrom = childFrom;
        mChildTo = childTo;

        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    public Object getChild(int groupPosition, int childPosition) {
        return mChildData.get(groupPosition).get(childPosition);
    }

    public long getChildId(int groupPosition, int childPosition) {
        return childPosition;
    }

    public View getChildView(int groupPosition, int childPosition, boolean isLastChild,
            View convertView, ViewGroup parent) {
        View v;
        if (convertView == null) {
            v = newChildView(isLastChild, parent);
        } else {
            v = convertView;
        }
        bindView(v, mChildData.get(groupPosition).get(childPosition), mChildFrom, mChildTo);
        return v;
    }

    /**
     * Instantiates a new View for a child.
     * @param isLastChild Whether the child is the last child within its group.
     * @param parent The eventual parent of this new View.
     * @return A new child View
     */
    public View newChildView(boolean isLastChild, ViewGroup parent) {
        return mInflater.inflate((isLastChild) ? mLastChildLayout : mChildLayout, parent, false);
    }

    private void bindView(View view, Map<String, ?> data, String[] from, int[] to) {
        int len = to.length;

        for (int i = 0; i < len; i++) {
            TextView v = (TextView)view.findViewById(to[i]);
            if (v != null) {
                v.setText((String)data.get(from[i]));
            }
        }
    }

    public int getChildrenCount(int groupPosition) {
        return mChildData.get(groupPosition).size();
    }

    public Object getGroup(int groupPosition) {
        return mGroupData.get(groupPosition);
    }

    public int getGroupCount() {
        return mGroupData.size();
    }

    public long getGroupId(int groupPosition) {
        return groupPosition;
    }

    public View getGroupView(int groupPosition, boolean isExpanded, View convertView,
            ViewGroup parent) {
        View v;
        if (convertView == null) {
            v = newGroupView(isExpanded, parent);
        } else {
            v = convertView;
        }
        bindView(v, mGroupData.get(groupPosition), mGroupFrom, mGroupTo);
        return v;
    }

    /**
     * Instantiates a new View for a group.
     * @param isExpanded Whether the group is currently expanded.
     * @param parent The eventual parent of this new View.
     * @return A new group View
     */
    public View newGroupView(boolean isExpanded, ViewGroup parent) {
        return mInflater.inflate((isExpanded) ? mExpandedGroupLayout : mCollapsedGroupLayout,
                parent, false);
    }

    public boolean isChildSelectable(int groupPosition, int childPosition) {
        return true;
    }

    public boolean hasStableIds() {
        return true;
    }

}
