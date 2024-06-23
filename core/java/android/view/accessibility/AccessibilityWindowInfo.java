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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.annotation.UptimeMillisLong;
import android.app.ActivityTaskManager;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.LongArray;
import android.util.Pools.SynchronizedPool;
import android.util.SparseArray;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent.WindowsChangeTypes;

import java.util.ArrayList;
import java.util.List;
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
     * Window type: This is a system window. Such a window shows UI for
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

    /**
     * Window type: A system window used to show the UI for the interaction with
     * window-based magnification, which includes the magnified content and the option menu.
     */
    public static final int TYPE_MAGNIFICATION_OVERLAY = 6;

    /**
     * Window type: A system window that has the function to control an associated window.
     */
    @FlaggedApi(Flags.FLAG_ADD_TYPE_WINDOW_CONTROL)
    public static final int TYPE_WINDOW_CONTROL = 7;

    /* Special values for window IDs */
    /** @hide */
    public static final int ACTIVE_WINDOW_ID = Integer.MAX_VALUE;
    /** @hide */
    public static final int UNDEFINED_CONNECTION_ID = -1;
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
    // TODO(b/129300068): Remove sNumInstancesInUse.
    private static AtomicInteger sNumInstancesInUse;

    // Data.
    private int mDisplayId = Display.INVALID_DISPLAY;
    private int mType = UNDEFINED_WINDOW_ID;
    private int mLayer = UNDEFINED_WINDOW_ID;
    private int mBooleanProperties;
    private int mId = UNDEFINED_WINDOW_ID;
    private int mParentId = UNDEFINED_WINDOW_ID;
    private int mTaskId = ActivityTaskManager.INVALID_TASK_ID;
    private Region mRegionInScreen = new Region();
    private LongArray mChildIds;
    private CharSequence mTitle;
    private long mAnchorId = AccessibilityNodeInfo.UNDEFINED_NODE_ID;
    private long mTransitionTime;

    private int mConnectionId = UNDEFINED_CONNECTION_ID;

    private LocaleList mLocales = LocaleList.getEmptyLocaleList();

    /**
     * Creates a new {@link AccessibilityWindowInfo}.
     */
    public AccessibilityWindowInfo() {
    }

    /**
     * Copy constructor. Creates a new {@link AccessibilityWindowInfo}, and this new instance is
     * initialized from given <code>info</code>.
     *
     * @param info The other info.
     */
    public AccessibilityWindowInfo(@NonNull AccessibilityWindowInfo info) {
        init(info);
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
        return getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID);
    }

    /**
     * Gets the root node in the window's hierarchy.
     *
     * @param prefetchingStrategy the prefetching strategy.
     * @return The root node.
     *
     * @see AccessibilityNodeInfo#getParent(int) for a description of prefetching.
     */
    @Nullable
    public AccessibilityNodeInfo getRoot(
            @AccessibilityNodeInfo.PrefetchingStrategy int prefetchingStrategy) {
        if (mConnectionId == UNDEFINED_WINDOW_ID) {
            return null;
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.findAccessibilityNodeInfoByAccessibilityId(mConnectionId,
                mId, AccessibilityNodeInfo.ROOT_NODE_ID,
                true, prefetchingStrategy, null);
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
     * Gets the task ID.
     *
     * @return The task ID.
     *
     * @hide
     */
    public int getTaskId() {
        return mTaskId;
    }

    /**
     * Sets the task ID.
     *
     * @param taskId The task ID.
     *
     * @hide
     */
    public void setTaskId(int taskId) {
        mTaskId = taskId;
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
     * Gets the touchable region of this window in the screen.
     *
     * @param outRegion The out window region.
     */
    public void getRegionInScreen(@NonNull Region outRegion) {
        outRegion.set(mRegionInScreen);
    }

    /**
     * Sets the touchable region of this window in the screen.
     *
     * @param region The window region.
     *
     * @hide
     */
    public void setRegionInScreen(Region region) {
        mRegionInScreen.set(region);
    }

    /**
     * Gets the bounds of this window in the screen. This is equivalent to get the bounds of the
     * Region from {@link #getRegionInScreen(Region)}.
     *
     * @param outBounds The out window bounds.
     */
    public void getBoundsInScreen(Rect outBounds) {
        outBounds.set(mRegionInScreen.getBounds());
    }

    /**
     * Gets if this window is active. An active window is the one
     * the user is currently touching or the window has input focus
     * and the user is not touching any window.
     * <p>
     * This is defined as the window that most recently fired one
     * of the following events:
     * {@link AccessibilityEvent#TYPE_WINDOW_STATE_CHANGED},
     * {@link AccessibilityEvent#TYPE_VIEW_HOVER_ENTER},
     * {@link AccessibilityEvent#TYPE_VIEW_HOVER_EXIT}.
     * In other words, the last window shown that also has input focus.
     * </p>
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
     * Sets the display Id.
     *
     * @param displayId The display id.
     *
     * @hide
     */
    public void setDisplayId(int displayId) {
        mDisplayId = displayId;
    }

    /**
     * Returns the ID of the display this window is on, for use with
     * {@link android.hardware.display.DisplayManager#getDisplay(int)}.
     *
     * @return The logical display id.
     */
    public int getDisplayId() {
        return mDisplayId;
    }


    /**
     * Sets the timestamp of the transition.
     *
     * @param transitionTime The timestamp from {@link SystemClock#uptimeMillis()} at which the
     *                       transition happens.
     *
     * @hide
     */
    @UptimeMillisLong
    public void setTransitionTimeMillis(long transitionTime) {
        mTransitionTime = transitionTime;
    }

    /**
     * Return the {@link SystemClock#uptimeMillis()} at which the last transition happens.
     * A transition happens when {@link #getBoundsInScreen(Rect)} is changed.
     *
     * @return The transition timestamp.
     */
    @UptimeMillisLong
    public long getTransitionTimeMillis() {
        return mTransitionTime;
    }

    /**
     * Sets the locales of the window. Locales are populated by the view root by default.
     *
     * @param locales The {@link android.os.LocaleList}.
     *
     * @hide
     */
    public void setLocales(@NonNull LocaleList locales) {
        mLocales = locales;
    }

    /**
     * Return the {@link android.os.LocaleList} of the window.
     *
     * @return the locales of the window.
     */
    public @NonNull LocaleList getLocales() {
        return mLocales;
    }

    /**
     * Returns a cached instance if such is available or a new one is
     * created.
     *
     * <p>In most situations object pooling is not beneficial. Create a new instance using the
     * constructor {@link #AccessibilityWindowInfo()} instead.
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
     * <p>In most situations object pooling is not beneficial. Create a new instance using the
     * constructor {@link #AccessibilityWindowInfo(AccessibilityWindowInfo)} instead.
     *
     * @param info The other info.
     * @return An instance.
     */
    public static AccessibilityWindowInfo obtain(AccessibilityWindowInfo info) {
        AccessibilityWindowInfo infoClone = obtain();
        infoClone.init(info);
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
     * <p>In most situations object pooling is not beneficial, and recycling is not necessary.
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

    /**
     * Refreshes this window with the latest state of the window it represents.
     * <p>
     * <strong>Note:</strong> If this method returns false this info is obsolete
     * since it represents a window that is no longer exist.
     * </p>
     *
     * @hide
     */
    public boolean refresh() {
        if (mConnectionId == UNDEFINED_CONNECTION_ID || mId == UNDEFINED_WINDOW_ID) {
            return false;
        }
        final AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        final AccessibilityWindowInfo refreshedInfo = client.getWindow(mConnectionId,
                mId, /* bypassCache */true);
        if (refreshedInfo == null) {
            return false;
        }
        init(refreshedInfo);
        refreshedInfo.recycle();
        return true;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mDisplayId);
        parcel.writeInt(mType);
        parcel.writeInt(mLayer);
        parcel.writeInt(mBooleanProperties);
        parcel.writeInt(mId);
        parcel.writeInt(mParentId);
        parcel.writeInt(mTaskId);
        mRegionInScreen.writeToParcel(parcel, flags);
        parcel.writeCharSequence(mTitle);
        parcel.writeLong(mAnchorId);
        parcel.writeLong(mTransitionTime);

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
        parcel.writeParcelable(mLocales, flags);
    }

    /**
     * Initializes this instance from another one.
     *
     * @param other The other instance.
     */
    private void init(AccessibilityWindowInfo other) {
        mDisplayId = other.mDisplayId;
        mType = other.mType;
        mLayer = other.mLayer;
        mBooleanProperties = other.mBooleanProperties;
        mId = other.mId;
        mParentId = other.mParentId;
        mTaskId = other.mTaskId;
        mRegionInScreen.set(other.mRegionInScreen);
        mTitle = other.mTitle;
        mAnchorId = other.mAnchorId;
        mTransitionTime = other.mTransitionTime;

        if (mChildIds != null) mChildIds.clear();
        if (other.mChildIds != null && other.mChildIds.size() > 0) {
            if (mChildIds == null) {
                mChildIds = other.mChildIds.clone();
            } else {
                mChildIds.addAll(other.mChildIds);
            }
        }

        mConnectionId = other.mConnectionId;
        mLocales = other.mLocales;
    }

    private void initFromParcel(Parcel parcel) {
        mDisplayId = parcel.readInt();
        mType = parcel.readInt();
        mLayer = parcel.readInt();
        mBooleanProperties = parcel.readInt();
        mId = parcel.readInt();
        mParentId = parcel.readInt();
        mTaskId = parcel.readInt();
        mRegionInScreen = Region.CREATOR.createFromParcel(parcel);
        mTitle = parcel.readCharSequence();
        mAnchorId = parcel.readLong();
        mTransitionTime = parcel.readLong();

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
        mLocales = parcel.readParcelable(null, LocaleList.class);
    }

    @Override
    public int hashCode() {
        return mId;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
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
        builder.append(", displayId=").append(mDisplayId);
        builder.append(", id=").append(mId);
        builder.append(", taskId=").append(mTaskId);
        builder.append(", type=").append(typeToString(mType));
        builder.append(", layer=").append(mLayer);
        builder.append(", region=").append(mRegionInScreen);
        builder.append(", bounds=").append(mRegionInScreen.getBounds());
        builder.append(", focused=").append(isFocused());
        builder.append(", active=").append(isActive());
        builder.append(", pictureInPicture=").append(isInPictureInPictureMode());
        builder.append(", transitionTime=").append(mTransitionTime);
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
        mDisplayId = Display.INVALID_DISPLAY;
        mType = UNDEFINED_WINDOW_ID;
        mLayer = UNDEFINED_WINDOW_ID;
        mBooleanProperties = 0;
        mId = UNDEFINED_WINDOW_ID;
        mParentId = UNDEFINED_WINDOW_ID;
        mTaskId = ActivityTaskManager.INVALID_TASK_ID;
        mRegionInScreen.setEmpty();
        mChildIds = null;
        mConnectionId = UNDEFINED_WINDOW_ID;
        mAnchorId = AccessibilityNodeInfo.UNDEFINED_NODE_ID;
        mTitle = null;
        mTransitionTime = 0;
        mLocales = LocaleList.getEmptyLocaleList();
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

    /**
     * @hide
     */
    public static String typeToString(int type) {
        if (Flags.addTypeWindowControl() && type == TYPE_WINDOW_CONTROL) {
            return "TYPE_WINDOW_CONTROL";
        }

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
            case TYPE_MAGNIFICATION_OVERLAY: {
                return "TYPE_MAGNIFICATION_OVERLAY";
            }
            default:
                return "<UNKNOWN:" + type + ">";
        }
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
        if (!mRegionInScreen.equals(other.mRegionInScreen)) {
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
        //TODO(b/1338122): Add DISPLAY_CHANGED type for multi-display
        return changes;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<AccessibilityWindowInfo> CREATOR =
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

    /**
     * Transfers a sparsearray with lists having {@link AccessibilityWindowInfo}s across an IPC.
     * The key of this sparsearray is display Id.
     *
     * @hide
     */
    public static final class WindowListSparseArray
            extends SparseArray<List<AccessibilityWindowInfo>> implements Parcelable {

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            final int count = size();
            dest.writeInt(count);
            for (int i = 0; i < count; i++) {
                dest.writeParcelableList(valueAt(i), 0);
                dest.writeInt(keyAt(i));
            }
        }

        public static final Parcelable.Creator<WindowListSparseArray> CREATOR =
                new Parcelable.Creator<WindowListSparseArray>() {
            public WindowListSparseArray createFromParcel(
                    Parcel source) {
                final WindowListSparseArray array = new WindowListSparseArray();
                final ClassLoader loader = array.getClass().getClassLoader();
                final int count = source.readInt();
                for (int i = 0; i < count; i++) {
                    List<AccessibilityWindowInfo> windows = new ArrayList<>();
                    source.readParcelableList(windows, loader, android.view.accessibility.AccessibilityWindowInfo.class);
                    array.put(source.readInt(), windows);
                }
                return array;
            }

            public WindowListSparseArray[] newArray(int size) {
                return new WindowListSparseArray[size];
            }
        };
    }
}
