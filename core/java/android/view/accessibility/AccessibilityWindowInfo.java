/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.view.accessibility;

import android.annotation.Nullable;
import android.annotation.TestApi;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.LongArray;
import android.util.Pools.SynchronizedPool;
import android.view.accessibility.AccessibilityEvent.WindowsChangeTypes;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class represents a state snapshot of a window for accessibility
 * purposes. The screen content contains one or more windows where some
 * windows can be descendants of other windows, which is the windows are
 * hierarchically ordered. Note that there is no root window. Hence, the
 * screen content can be seen as a collection of window trees.
 */
public final class AccessibilityWindowInfo implements Parcelable {

    private static final boolean DEBUG = false;

    /**
     * Window type: This is an application window. Such a window shows UI for
     * interacting with an application.
     */
    public static final int TYPE_APPLICATION = 1;

    /**
     * Window type: This is an input method window. Such a window shows UI for
     * inputting text such as keyboard, suggestions, etc.
     */
    public static final int TYPE_INPUT_METHOD = 2;

    /**
     * Window type: This is an system window. Such a window shows UI for
     * interacting with the system.
     */
    public static final int TYPE_SYSTEM = 3;

    /**
     * Window type: Windows that are overlaid <em>only</em> by an {@link
     * android.accessibilityservice.AccessibilityService} for interception of
     * user interactions without changing the windows an accessibility service
     * can introspect. In particular, an accessibility service can introspect
     * only windows that a sighted user can interact with which they can touch
     * these windows or can type into these windows. For example, if there
     * is a full screen accessibility overlay that is touchable, the windows
     * below it will be introspectable by an accessibility service regardless
     * they are covered by a touchable window.
     */
    public static final int TYPE_ACCESSIBILITY_OVERLAY = 4;

    /**
     * Window type: A system window used to divide the screen in split-screen mode.
     * This type of window is present only in split-screen mode.
     */
    public static final int TYPE_SPLIT_SCREEN_DIVIDER = 5;

    /* Special values for window IDs */
    /** @hide */
    public static final int ACTIVE_WINDOW_ID = Integer.MAX_VALUE;
    /** @hide */
    public static final int UNDEFINED_WINDOW_ID = -1;
    /** @hide */
    public static final int ANY_WINDOW_ID = -2;
    /** @hide */
    public static final int PICTURE_IN_PICTURE_ACTION_REPLACER_WINDOW_ID = -3;

    private static final int BOOLEAN_PROPERTY_ACTIVE = 1 << 0;
    private static final int BOOLEAN_PROPERTY_FOCUSED = 1 << 1;
    private static final int BOOLEAN_PROPERTY_ACCESSIBILITY_FOCUSED = 1 << 2;
    private static final int BOOLEAN_PROPERTY_PICTURE_IN_PICTURE = 1 << 3;

    // Housekeeping.
    private static final int MAX_POOL_SIZE = 10;
    private static final SynchronizedPool<AccessibilityWindowInfo> sPool =
            new SynchronizedPool<AccessibilityWindowInfo>(MAX_POOL_SIZE);
    private static AtomicInteger sNumInstancesInUse;

    // Data.
    private int mType = UNDEFINED_WINDOW_ID;
    private int mLayer = UNDEFINED_WINDOW_ID;
    private int mBooleanProperties;
    private int mId = UNDEFINED_WINDOW_ID;
    private int mParentId = UNDEFINED_WINDOW_ID;
    private final Rect mBoundsInScreen = new Rect();
    private LongArray mChildIds;
    private CharSequence mTitle;
    private long mAnchorId = AccessibilityNodeInfo.UNDEFINED_NODE_ID;

    private int mConnectionId = UNDEFINED_WINDOW_ID;

    private AccessibilityWindowInfo() {
        /* do nothing - hide constructor */
    }

    /**
     * Gets the title of the window.
     *
     * @return The title of the window, or {@code null} if none is available.
     */
    @Nullable
    public CharSequence getTitle() {
        return mTitle;
    }

    /**
     * Sets the title of the window.
     *
     * @param title The title.
     *
     * @hide
     */
    public void setTitle(CharSequence title) {
        mTitle = title;
    }

    /**
     * Gets the type of the window.
     *
     * @return The type.
     *
     * @see #TYPE_APPLICATION
     * @see #TYPE_INPUT_METHOD
     * @see #TYPE_SYSTEM
     * @see #TYPE_ACCESSIBILITY_OVERLAY
     */
    public int getType() {
        return mType;
    }

    /**
     * Sets the type of the window.
     *
     * @param type The type
     *
     * @hide
     */
    public void setType(int type) {
        mType = type;
    }

    /**
     * Gets the layer which determines the Z-order of the window. Windows
     * with greater layer appear on top of windows with lesser layer.
     *
     * @return The window layer.
     */
    public int getLayer() {
        return mLayer;
    }

    /**
     * Sets the layer which determines the Z-order of the window. Windows
     * with greater layer appear on top of windows with lesser layer.
     *
     * @param layer The window layer.
     *
     * @hide
     */
    public void setLayer(int layer) {
        mLayer = layer;
    }

    /**
     * Gets the root node in the window's hierarchy.
     *
     * @return The root node.
     */
    public AccessibilityNodeInfo getRoot() {
        if (mConnectionId == UNDEFINED_WINDOW_ID) {
            return null;
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.findAccessibilityNodeInfoByAccessibilityId(mConnectionId,
                mId, AccessibilityNodeInfo.ROOT_NODE_ID,
                true, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS, null);
    }

    /**
     * Sets the anchor node's ID.
     *
     * @param anchorId The anchor's accessibility id in its window.
     *
     * @hide
     */
    public void setAnchorId(long anchorId) {
        mAnchorId = anchorId;
    }

    /**
     * Gets the node that anchors this window to another.
     *
     * @return The anchor node, or {@code null} if none exists.
     */
    public AccessibilityNodeInfo getAnchor() {
        if ((mConnectionId == UNDEFINED_WINDOW_ID)
                || (mAnchorId == AccessibilityNodeInfo.UNDEFINED_NODE_ID)
                || (mParentId == UNDEFINED_WINDOW_ID)) {
            return null;
        }

        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.findAccessibilityNodeInfoByAccessibilityId(mConnectionId,
                mParentId, mAnchorId, true, 0, null);
    }

    /** @hide */
    public void setPictureInPicture(boolean pictureInPicture) {
        setBooleanProperty(BOOLEAN_PROPERTY_PICTURE_IN_PICTURE, pictureInPicture);
    }

    /**
     * Check if the window is in picture-in-picture mode.
     *
     * @return {@code true} if the window is in picture-in-picture mode, {@code false} otherwise.
     */
    public boolean isInPictureInPictureMode() {
        return getBooleanProperty(BOOLEAN_PROPERTY_PICTURE_IN_PICTURE);
    }

    /**
     * Gets the parent window.
     *
     * @return The parent window, or {@code null} if none exists.
     */
    public AccessibilityWindowInfo getParent() {
        if (mConnectionId == UNDEFINED_WINDOW_ID || mParentId == UNDEFINED_WINDOW_ID) {
            return null;
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.getWindow(mConnectionId, mParentId);
    }

    /**
     * Sets the parent window id.
     *
     * @param parentId The parent id.
     *
     * @hide
     */
    public void setParentId(int parentId) {
        mParentId = parentId;
    }

    /**
     * Gets the unique window id.
     *
     * @return windowId The window id.
     */
    public int getId() {
        return mId;
    }

    /**
     * Sets the unique window id.
     *
     * @param id The window id.
     *
     * @hide
     */
    public void setId(int id) {
        mId = id;
    }

    /**
     * Sets the unique id of the IAccessibilityServiceConnection over which
     * this instance can send requests to the system.
     *
     * @param connectionId The connection id.
     *
     * @hide
     */
    public void setConnectionId(int connectionId) {
        mConnectionId = connectionId;
    }

    /**
     * Gets the bounds of this window in the screen.
     *
     * @param outBounds The out window bounds.
     */
    public void getBoundsInScreen(Rect outBounds) {
        outBounds.set(mBoundsInScreen);
    }

    /**
     * Sets the bounds of this window in the screen.
     *
     * @param bounds The out window bounds.
     *
     * @hide
     */
    public void setBoundsInScreen(Rect bounds) {
        mBoundsInScreen.set(bounds);
    }

    /**
     * Gets if this window is active. An active window is the one
     * the user is currently touching or the window has input focus
     * and the user is not touching any window.
     *
     * @return Whether this is the active window.
     */
    public boolean isActive() {
        return getBooleanProperty(BOOLEAN_PROPERTY_ACTIVE);
    }

    /**
     * Sets if this window is active, which is this is the window
     * the user is currently touching or the window has input focus
     * and the user is not touching any window.
     *
     * @param active Whether this is the active window.
     *
     * @hide
     */
    public void setActive(boolean active) {
        setBooleanProperty(BOOLEAN_PROPERTY_ACTIVE, active);
    }

    /**
     * Gets if this window has input focus.
     *
     * @return Whether has input focus.
     */
    public boolean isFocused() {
        return getBooleanProperty(BOOLEAN_PROPERTY_FOCUSED);
    }

    /**
     * Sets if this window has input focus.
     *
     * @param focused Whether has input focus.
     *
     * @hide
     */
    public void setFocused(boolean focused) {
        setBooleanProperty(BOOLEAN_PROPERTY_FOCUSED, focused);
    }

    /**
     * Gets if this window has accessibility focus.
     *
     * @return Whether has accessibility focus.
     */
    public boolean isAccessibilityFocused() {
        return getBooleanProperty(BOOLEAN_PROPERTY_ACCESSIBILITY_FOCUSED);
    }

    /**
     * Sets if this window has accessibility focus.
     *
     * @param focused Whether has accessibility focus.
     *
     * @hide
     */
    public void setAccessibilityFocused(boolean focused) {
        setBooleanProperty(BOOLEAN_PROPERTY_ACCESSIBILITY_FOCUSED, focused);
    }

    /**
     * Gets the number of child windows.
     *
     * @return The child count.
     */
    public int getChildCount() {
        return (mChildIds != null) ? mChildIds.size() : 0;
    }

    /**
     * Gets the child window at a given index.
     *
     * @param index The index.
     * @return The child.
     */
    public AccessibilityWindowInfo getChild(int index) {
        if (mChildIds == null) {
            throw new IndexOutOfBoundsException();
        }
        if (mConnectionId == UNDEFINED_WINDOW_ID) {
            return null;
        }
        final int childId = (int) mChildIds.get(index);
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.getWindow(mConnectionId, childId);
    }

    /**
     * Adds a child window.
     *
     * @param childId The child window id.
     *
     * @hide
     */
    public void addChild(int childId) {
        if (mChildIds == null) {
            mChildIds = new LongArray();
        }
        mChildIds.add(childId);
    }

    /**
     * Returns a cached instance if such is available or a new one is
     * created.
     *
     * @return An instance.
     */
    public static AccessibilityWindowInfo obtain() {
        AccessibilityWindowInfo info = sPool.acquire();
        if (info == null) {
            info = new AccessibilityWindowInfo();
        }
        if (sNumInstancesInUse != null) {
            sNumInstancesInUse.incrementAndGet();
        }
        return info;
    }

    /**
     * Returns a cached instance if such is available or a new one is
     * created. The returned instance is initialized from the given
     * <code>info</code>.
     *
     * @param info The other info.
     * @return An instance.
     */
    public static AccessibilityWindowInfo obtain(AccessibilityWindowInfo info) {
        AccessibilityWindowInfo infoClone = obtain();

        infoClone.mType = info.mType;
        infoClone.mLayer = info.mLayer;
        infoClone.mBooleanProperties = info.mBooleanProperties;
        infoClone.mId = info.mId;
        infoClone.mParentId = info.mParentId;
        infoClone.mBoundsInScreen.set(info.mBoundsInScreen);
        infoClone.mTitle = info.mTitle;
        infoClone.mAnchorId = info.mAnchorId;

        if (info.mChildIds != null && info.mChildIds.size() > 0) {
            if (infoClone.mChildIds == null) {
                infoClone.mChildIds = info.mChildIds.clone();
            } else {
                infoClone.mChildIds.addAll(info.mChildIds);
            }
        }

        infoClone.mConnectionId = info.mConnectionId;

        return infoClone;
    }

    /**
     * Specify a counter that will be incremented on obtain() and decremented on recycle()
     *
     * @hide
     */
    @TestApi
    public static void setNumInstancesInUseCounter(AtomicInteger counter) {
        if (sNumInstancesInUse != null) {
            sNumInstancesInUse = counter;
        }
    }

    /**
     * Return an instance back to be reused.
     * <p>
     * <strong>Note:</strong> You must not touch the object after calling this function.
     * </p>
     *
     * @throws IllegalStateException If the info is already recycled.
     */
    public void recycle() {
        clear();
        sPool.release(this);
        if (sNumInstancesInUse != null) {
            sNumInstancesInUse.decrementAndGet();
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mType);
        parcel.writeInt(mLayer);
        parcel.writeInt(mBooleanProperties);
        parcel.writeInt(mId);
        parcel.writeInt(mParentId);
        mBoundsInScreen.writeToParcel(parcel, flags);
        parcel.writeCharSequence(mTitle);
        parcel.writeLong(mAnchorId);

        final LongArray childIds = mChildIds;
        if (childIds == null) {
            parcel.writeInt(0);
        } else {
            final int childCount = childIds.size();
            parcel.writeInt(childCount);
            for (int i = 0; i < childCount; i++) {
                parcel.writeInt((int) childIds.get(i));
            }
        }

        parcel.writeInt(mConnectionId);
    }

    private void initFromParcel(Parcel parcel) {
        mType = parcel.readInt();
        mLayer = parcel.readInt();
        mBooleanProperties = parcel.readInt();
        mId = parcel.readInt();
        mParentId = parcel.readInt();
        mBoundsInScreen.readFromParcel(parcel);
        mTitle = parcel.readCharSequence();
        mAnchorId = parcel.readLong();

        final int childCount = parcel.readInt();
        if (childCount > 0) {
            if (mChildIds == null) {
                mChildIds = new LongArray(childCount);
            }
            for (int i = 0; i < childCount; i++) {
                final int childId = parcel.readInt();
                mChildIds.add(childId);
            }
        }

        mConnectionId = parcel.readInt();
    }

    @Override
    public int hashCode() {
        return mId;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        AccessibilityWindowInfo other = (AccessibilityWindowInfo) obj;
        return (mId == other.mId);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("AccessibilityWindowInfo[");
        builder.append("title=").append(mTitle);
        builder.append(", id=").append(mId);
        builder.append(", type=").append(typeToString(mType));
        builder.append(", layer=").append(mLayer);
        builder.append(", bounds=").append(mBoundsInScreen);
        builder.append(", focused=").append(isFocused());
        builder.append(", active=").append(isActive());
        builder.append(", pictureInPicture=").append(isInPictureInPictureMode());
        if (DEBUG) {
            builder.append(", parent=").append(mParentId);
            builder.append(", children=[");
            if (mChildIds != null) {
                final int childCount = mChildIds.size();
                for (int i = 0; i < childCount; i++) {
                    builder.append(mChildIds.get(i));
                    if (i < childCount - 1) {
                        builder.append(',');
                    }
                }
            } else {
                builder.append("null");
            }
            builder.append(']');
        } else {
            builder.append(", hasParent=").append(mParentId != UNDEFINED_WINDOW_ID);
            builder.append(", isAnchored=")
                    .append(mAnchorId != AccessibilityNodeInfo.UNDEFINED_NODE_ID);
            builder.append(", hasChildren=").append(mChildIds != null
                    && mChildIds.size() > 0);
        }
        builder.append(']');
        return builder.toString();
    }

    /**
     * Clears the internal state.
     */
    private void clear() {
        mType = UNDEFINED_WINDOW_ID;
        mLayer = UNDEFINED_WINDOW_ID;
        mBooleanProperties = 0;
        mId = UNDEFINED_WINDOW_ID;
        mParentId = UNDEFINED_WINDOW_ID;
        mBoundsInScreen.setEmpty();
        if (mChildIds != null) {
            mChildIds.clear();
        }
        mConnectionId = UNDEFINED_WINDOW_ID;
        mAnchorId = AccessibilityNodeInfo.UNDEFINED_NODE_ID;
        mTitle = null;
    }

    /**
     * Gets the value of a boolean property.
     *
     * @param property The property.
     * @return The value.
     */
    private boolean getBooleanProperty(int property) {
        return (mBooleanProperties & property) != 0;
    }

    /**
     * Sets a boolean property.
     *
     * @param property The property.
     * @param value The value.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    private void setBooleanProperty(int property, boolean value) {
        if (value) {
            mBooleanProperties |= property;
        } else {
            mBooleanProperties &= ~property;
        }
    }

    private static String typeToString(int type) {
        switch (type) {
            case TYPE_APPLICATION: {
                return "TYPE_APPLICATION";
            }
            case TYPE_INPUT_METHOD: {
                return "TYPE_INPUT_METHOD";
            }
            case TYPE_SYSTEM: {
                return "TYPE_SYSTEM";
            }
            case TYPE_ACCESSIBILITY_OVERLAY: {
                return "TYPE_ACCESSIBILITY_OVERLAY";
            }
            case TYPE_SPLIT_SCREEN_DIVIDER: {
                return "TYPE_SPLIT_SCREEN_DIVIDER";
            }
            default:
                return "<UNKNOWN>";
        }
    }

    /**
     * Checks whether this window changed. The argument should be
     * another state of the same window, which is have the same id
     * and type as they never change.
     *
     * @param other The new state.
     * @return Whether something changed.
     *
     * @hide
     */
    public boolean changed(AccessibilityWindowInfo other) {
        if (other.mId != mId) {
            throw new IllegalArgumentException("Not same window.");
        }
        if (other.mType != mType) {
            throw new IllegalArgumentException("Not same type.");
        }
        if (!mBoundsInScreen.equals(other.mBoundsInScreen)) {
            return true;
        }
        if (mLayer != other.mLayer) {
            return true;
        }
        if (mBooleanProperties != other.mBooleanProperties) {
            return true;
        }
        if (mParentId != other.mParentId) {
            return true;
        }
        if (mChildIds == null) {
            if (other.mChildIds != null) {
                return true;
            }
        } else if (!mChildIds.equals(other.mChildIds)) {
            return true;
        }
        return false;
    }

    /**
     * Reports how this window differs from a possibly different state of the same window. The
     * argument must have the same id and type as neither of those properties may change.
     *
     * @param other The new state.
     * @return A set of flags showing how the window has changes, or 0 if the two states are the
     * same.
     *
     * @hide
     */
    @WindowsChangeTypes
    public int differenceFrom(AccessibilityWindowInfo other) {
        if (other.mId != mId) {
            throw new IllegalArgumentException("Not same window.");
        }
        if (other.mType != mType) {
            throw new IllegalArgumentException("Not same type.");
        }
        int changes = 0;
        if (!TextUtils.equals(mTitle, other.mTitle)) {
            changes |= AccessibilityEvent.WINDOWS_CHANGE_TITLE;
        }

        if (!mBoundsInScreen.equals(other.mBoundsInScreen)) {
            changes |= AccessibilityEvent.WINDOWS_CHANGE_BOUNDS;
        }
        if (mLayer != other.mLayer) {
            changes |= AccessibilityEvent.WINDOWS_CHANGE_LAYER;
        }
        if (getBooleanProperty(BOOLEAN_PROPERTY_ACTIVE)
                != other.getBooleanProperty(BOOLEAN_PROPERTY_ACTIVE)) {
            changes |= AccessibilityEvent.WINDOWS_CHANGE_ACTIVE;
        }
        if (getBooleanProperty(BOOLEAN_PROPERTY_FOCUSED)
                != other.getBooleanProperty(BOOLEAN_PROPERTY_FOCUSED)) {
            changes |= AccessibilityEvent.WINDOWS_CHANGE_FOCUSED;
        }
        if (getBooleanProperty(BOOLEAN_PROPERTY_ACCESSIBILITY_FOCUSED)
                != other.getBooleanProperty(BOOLEAN_PROPERTY_ACCESSIBILITY_FOCUSED)) {
            changes |= AccessibilityEvent.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED;
        }
        if (getBooleanProperty(BOOLEAN_PROPERTY_PICTURE_IN_PICTURE)
                != other.getBooleanProperty(BOOLEAN_PROPERTY_PICTURE_IN_PICTURE)) {
            changes |= AccessibilityEvent.WINDOWS_CHANGE_PIP;
        }
        if (mParentId != other.mParentId) {
            changes |= AccessibilityEvent.WINDOWS_CHANGE_PARENT;
        }
        if (!Objects.equals(mChildIds, other.mChildIds)) {
            changes |= AccessibilityEvent.WINDOWS_CHANGE_CHILDREN;
        }
        return changes;
    }

    public static final Parcelable.Creator<AccessibilityWindowInfo> CREATOR =
            new Creator<AccessibilityWindowInfo>() {
        @Override
        public AccessibilityWindowInfo createFromParcel(Parcel parcel) {
            AccessibilityWindowInfo info = obtain();
            info.initFromParcel(parcel);
            return info;
        }

        @Override
        public AccessibilityWindowInfo[] newArray(int size) {
            return new AccessibilityWindowInfo[size];
        }
    };
}
