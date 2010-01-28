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

package android.util;

import java.util.ArrayList;
import java.util.List;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseExpandableListAdapter;
import android.widget.ExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.ListView;
import android.widget.TextView;

/**
 * Utility base class for creating various Expandable List scenarios.
 * <p>
 * WARNING: A lot of the features are mixed between ListView's expected position
 * (flat list position) and an ExpandableListView's expected position.  You must add/change
 * features as you need them.
 * 
 * @see ListScenario
 */
public abstract class ExpandableListScenario extends ListScenario {
    protected ExpandableListAdapter mAdapter; 
    protected List<MyGroup> mGroups;
    
    @Override
    protected ListView createListView() {
        return new ExpandableListView(this);
    }

    @Override
    protected Params createParams() {
        return new ExpandableParams();
    }

    @Override
    protected void setAdapter(ListView listView) {
        ((ExpandableListView) listView).setAdapter(mAdapter = createAdapter());
    }
    
    protected ExpandableListAdapter createAdapter() {
        return new MyAdapter();
    }
    
    @Override
    protected void readAndValidateParams(Params params) {
        ExpandableParams expandableParams = (ExpandableParams) params;
        
        int[] numChildren = expandableParams.mNumChildren;
        
        mGroups = new ArrayList<MyGroup>(numChildren.length);
        for (int i = 0; i < numChildren.length; i++) {
            mGroups.add(new MyGroup(numChildren[i]));
        }
        
        expandableParams.superSetNumItems();
        
        super.readAndValidateParams(params);
    }

    /**
     * Get the ExpandableListView widget.
     * @return The main widget.
     */
    public ExpandableListView getExpandableListView() {
        return (ExpandableListView) super.getListView();
    }

    public static class ExpandableParams extends Params {
        private int[] mNumChildren;
        
        /**
         * Sets the number of children per group.
         *  
         * @param numChildrenPerGroup The number of children per group.
         */
        public ExpandableParams setNumChildren(int[] numChildren) {
            mNumChildren = numChildren;
            return this;
        }

        /**
         * Sets the number of items on the superclass based on the number of
         * groups and children per group.
         */
        private ExpandableParams superSetNumItems() {
            int numItems = 0;
            
            if (mNumChildren != null) {
                for (int i = mNumChildren.length - 1; i >= 0; i--) {
                    numItems += mNumChildren[i];
                }
            }
            
            super.setNumItems(numItems);
            
            return this;
        }
        
        @Override
        public Params setNumItems(int numItems) {
            throw new IllegalStateException("Use setNumGroups and setNumChildren instead.");
        }

        @Override
        public ExpandableParams setFadingEdgeScreenSizeFactor(double fadingEdgeScreenSizeFactor) {
            return (ExpandableParams) super.setFadingEdgeScreenSizeFactor(fadingEdgeScreenSizeFactor);
        }

        @Override
        public ExpandableParams setItemScreenSizeFactor(double itemScreenSizeFactor) {
            return (ExpandableParams) super.setItemScreenSizeFactor(itemScreenSizeFactor);
        }

        @Override
        public ExpandableParams setItemsFocusable(boolean itemsFocusable) {
            return (ExpandableParams) super.setItemsFocusable(itemsFocusable);
        }

        @Override
        public ExpandableParams setMustFillScreen(boolean fillScreen) {
            return (ExpandableParams) super.setMustFillScreen(fillScreen);
        }

        @Override
        public ExpandableParams setPositionScreenSizeFactorOverride(int position, double itemScreenSizeFactor) {
            return (ExpandableParams) super.setPositionScreenSizeFactorOverride(position, itemScreenSizeFactor);
        }

        @Override
        public ExpandableParams setPositionUnselectable(int position) {
            return (ExpandableParams) super.setPositionUnselectable(position);
        }

        @Override
        public ExpandableParams setStackFromBottom(boolean stackFromBottom) {
            return (ExpandableParams) super.setStackFromBottom(stackFromBottom);
        }

        @Override
        public ExpandableParams setStartingSelectionPosition(int startingSelectionPosition) {
            return (ExpandableParams) super.setStartingSelectionPosition(startingSelectionPosition);
        }

        @Override
        public ExpandableParams setConnectAdapter(boolean connectAdapter) {
            return (ExpandableParams) super.setConnectAdapter(connectAdapter);
        }
    }

    /**
     * Gets a string for the value of some item.
     * @param packedPosition The position of the item.
     * @return The string.
     */
    public final String getValueAtPosition(long packedPosition) {
        final int type = ExpandableListView.getPackedPositionType(packedPosition);
        
        if (type == ExpandableListView.PACKED_POSITION_TYPE_CHILD) {
            return mGroups.get(ExpandableListView.getPackedPositionGroup(packedPosition))
                    .children.get(ExpandableListView.getPackedPositionChild(packedPosition))
                    .name;
        } else if (type == ExpandableListView.PACKED_POSITION_TYPE_GROUP) {
            return mGroups.get(ExpandableListView.getPackedPositionGroup(packedPosition))
                    .name;
        } else {
            throw new IllegalStateException("packedPosition is not a valid position.");
        }
    }

    /**
     * Whether a particular position is out of bounds.
     * 
     * @param packedPosition The packed position.
     * @return Whether it's out of bounds.
     */
    private boolean isOutOfBounds(long packedPosition) {
        final int type = ExpandableListView.getPackedPositionType(packedPosition);
        
        if (type == ExpandableListView.PACKED_POSITION_TYPE_NULL) {
            throw new IllegalStateException("packedPosition is not a valid position.");
        }

        final int group = ExpandableListView.getPackedPositionGroup(packedPosition); 
        if (group >= mGroups.size() || group < 0) {
            return true;
        }
        
        if (type == ExpandableListView.PACKED_POSITION_TYPE_CHILD) {
            final int child = ExpandableListView.getPackedPositionChild(packedPosition); 
            if (child >= mGroups.get(group).children.size() || child < 0) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Gets a view for the packed position, possibly reusing the convertView.
     * 
     * @param packedPosition The position to get a view for.
     * @param convertView Optional view to convert.
     * @param parent The future parent.
     * @return A view.
     */
    private View getView(long packedPosition, View convertView, ViewGroup parent) {
        if (isOutOfBounds(packedPosition)) {
            throw new IllegalStateException("position out of range for adapter!");
        }
        
        final ExpandableListView elv = getExpandableListView();
        final int flPos = elv.getFlatListPosition(packedPosition); 
        
        if (convertView != null) {
            ((TextView) convertView).setText(getValueAtPosition(packedPosition));
            convertView.setId(flPos);
            return convertView;
        }

        int desiredHeight = getHeightForPosition(flPos);
        return createView(packedPosition, flPos, parent, desiredHeight);
    }
    
    /**
     * Create a view for a group or child position.
     * 
     * @param packedPosition The packed position (has type, group pos, and optionally child pos).
     * @param flPos The flat list position (the position that the ListView goes by).
     * @param parent The parent view.
     * @param desiredHeight The desired height.
     * @return A view.
     */
    protected View createView(long packedPosition, int flPos, ViewGroup parent, int desiredHeight) {
        TextView result = new TextView(parent.getContext());
        result.setHeight(desiredHeight);
        result.setText(getValueAtPosition(packedPosition));
        final ViewGroup.LayoutParams lp = new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        result.setLayoutParams(lp);
        result.setGravity(Gravity.CENTER_VERTICAL);
        result.setPadding(36, 0, 0, 0);
        result.setId(flPos);
        return result;
    }
    
    /**
     * Returns a group index containing either the number of children or at
     * least one child.
     * 
     * @param numChildren The group must have this amount, or -1 if using
     *            atLeastOneChild.
     * @param atLeastOneChild The group must have at least one child, or false
     *            if using numChildren.
     * @return A group index with the requirements.
     */
    public int findGroupWithNumChildren(int numChildren, boolean atLeastOneChild) {
        final ExpandableListAdapter adapter = mAdapter;
        
        for (int i = adapter.getGroupCount() - 1; i >= 0; i--) {
            final int curNumChildren = adapter.getChildrenCount(i);
            
            if (numChildren == curNumChildren || atLeastOneChild && curNumChildren > 0) {
                return i;
            }
        }
        
        return -1;
    }
    
    public List<MyGroup> getGroups() {
        return mGroups;
    }
    
    public ExpandableListAdapter getAdapter() {
        return mAdapter;
    }

    /**
     * Simple expandable list adapter.
     */
    protected class MyAdapter extends BaseExpandableListAdapter {
        public Object getChild(int groupPosition, int childPosition) {
            return getValueAtPosition(ExpandableListView.getPackedPositionForChild(groupPosition,
                    childPosition));
        }

        public long getChildId(int groupPosition, int childPosition) {
            return mGroups.get(groupPosition).children.get(childPosition).id;
        }

        public int getChildrenCount(int groupPosition) {
            return mGroups.get(groupPosition).children.size();
        }

        public View getChildView(int groupPosition, int childPosition, boolean isLastChild,
                View convertView, ViewGroup parent) {
            return getView(ExpandableListView.getPackedPositionForChild(groupPosition,
                    childPosition), convertView, parent);
        }

        public Object getGroup(int groupPosition) {
            return getValueAtPosition(ExpandableListView.getPackedPositionForGroup(groupPosition));
        }

        public int getGroupCount() {
            return mGroups.size();
        }

        public long getGroupId(int groupPosition) {
            return mGroups.get(groupPosition).id;
        }

        public View getGroupView(int groupPosition, boolean isExpanded, View convertView,
                ViewGroup parent) {
            return getView(ExpandableListView.getPackedPositionForGroup(groupPosition),
                    convertView, parent);
        }

        public boolean isChildSelectable(int groupPosition, int childPosition) {
            return true;
        }

        public boolean hasStableIds() {
            return true;
        }
        
    }

    public static class MyGroup {
        private static long mNextId = 1000;
        
        String name;
        long id = mNextId++;
        List<MyChild> children;
        
        public MyGroup(int numChildren) {
            name = "Group " + id;
            children = new ArrayList<MyChild>(numChildren);
            for (int i = 0; i < numChildren; i++) {
                children.add(new MyChild());
            }
        }
    }
    
    public static class MyChild {
        private static long mNextId = 2000;
        
        String name;
        long id = mNextId++;
        
        public MyChild() {
            name = "Child " + id;
        }
    }
    
    @Override
    protected final void init(Params params) {
        init((ExpandableParams) params);
    }

    /**
     * @see ListScenario#init
     */
    protected abstract void init(ExpandableParams params);
}
