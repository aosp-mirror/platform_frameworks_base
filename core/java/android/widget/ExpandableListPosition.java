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

package android.widget;

/**
 * ExpandableListPosition can refer to either a group's position or a child's
 * position. Referring to a child's position requires both a group position (the
 * group containing the child) and a child position (the child's position within
 * that group). To create objects, use {@link #obtainChildPosition(int, int)} or
 * {@link #obtainGroupPosition(int)}.
 */
class ExpandableListPosition {
    /**
     * This data type represents a child position
     */
    public final static int CHILD = 1;

    /**
     * This data type represents a group position
     */
    public final static int GROUP = 2;

    /**
     * The position of either the group being referred to, or the parent
     * group of the child being referred to
     */
    public int groupPos;

    /**
     * The position of the child within its parent group 
     */
    public int childPos;

    /**
     * The position of the item in the flat list (optional, used internally when
     * the corresponding flat list position for the group or child is known)
     */
    int flatListPos;
    
    /**
     * What type of position this ExpandableListPosition represents
     */
    public int type;
    
    ExpandableListPosition(int type, int groupPos, int childPos, int flatListPos) {
        this.type = type;
        this.flatListPos = flatListPos;
        this.groupPos = groupPos;
        this.childPos = childPos;
    }

    /**
     * Used internally by the {@link #obtainChildPosition} and
     * {@link #obtainGroupPosition} methods to construct a new object.
     */
    private ExpandableListPosition(int type, int groupPos, int childPos) {
        this.type = type;
        this.groupPos = groupPos;
        this.childPos = childPos;
    }
    
    long getPackedPosition() {
        if (type == CHILD) return ExpandableListView.getPackedPositionForChild(groupPos, childPos);
        else return ExpandableListView.getPackedPositionForGroup(groupPos);
    }

    static ExpandableListPosition obtainGroupPosition(int groupPosition) {
        return new ExpandableListPosition(GROUP, groupPosition, 0);
    }
    
    static ExpandableListPosition obtainChildPosition(int groupPosition, int childPosition) {
        return new ExpandableListPosition(CHILD, groupPosition, childPosition);
    }

    static ExpandableListPosition obtainPosition(long packedPosition) {
        if (packedPosition == ExpandableListView.PACKED_POSITION_VALUE_NULL) {
            return null;
        }
        
        final int type = ExpandableListView.getPackedPositionType(packedPosition) ==
            ExpandableListView.PACKED_POSITION_TYPE_CHILD ? CHILD : GROUP;
        
        return new ExpandableListPosition(type, ExpandableListView
                .getPackedPositionGroup(packedPosition), ExpandableListView
                .getPackedPositionChild(packedPosition));
    }
    
}
