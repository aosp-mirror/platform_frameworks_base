/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static com.android.internal.util.CollectionUtils.isEmpty;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcelable;
import android.view.Display;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a record in an {@link AccessibilityEvent} and contains information
 * about state change of its source {@link android.view.View}. When a view fires
 * an accessibility event it requests from its parent to dispatch the
 * constructed event. The parent may optionally append a record for itself
 * for providing more context to
 * {@link android.accessibilityservice.AccessibilityService}s. Hence,
 * accessibility services can facilitate additional accessibility records
 * to enhance feedback.
 * </p>
 * <p>
 * Once the accessibility event containing a record is dispatched the record is
 * made immutable and calling a state mutation method generates an error.
 * </p>
 * <p>
 * <strong>Note:</strong> Not all properties are applicable to all accessibility
 * event types. For detailed information please refer to {@link AccessibilityEvent}.
 * </p>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about creating and processing AccessibilityRecords, read the
 * <a href="{@docRoot}guide/topics/ui/accessibility/index.html">Accessibility</a>
 * developer guide.</p>
 * </div>
 *
 * @see AccessibilityEvent
 * @see AccessibilityManager
 * @see android.accessibilityservice.AccessibilityService
 * @see AccessibilityNodeInfo
 */
public class AccessibilityRecord {
    /** @hide */
    protected static final boolean DEBUG_CONCISE_TOSTRING = false;

    private static final int UNDEFINED = -1;

    private static final int PROPERTY_CHECKED = 0x00000001;
    private static final int PROPERTY_ENABLED = 0x00000002;
    private static final int PROPERTY_PASSWORD = 0x00000004;
    private static final int PROPERTY_FULL_SCREEN = 0x00000080;
    private static final int PROPERTY_SCROLLABLE = 0x00000100;
    private static final int PROPERTY_IMPORTANT_FOR_ACCESSIBILITY = 0x00000200;

    private static final int GET_SOURCE_PREFETCH_FLAGS =
            AccessibilityNodeInfo.FLAG_PREFETCH_ANCESTORS
                    | AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS
                    | AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID;

    @UnsupportedAppUsage
    boolean mSealed;
    int mBooleanProperties = 0;
    int mCurrentItemIndex = UNDEFINED;
    int mItemCount = UNDEFINED;
    int mFromIndex = UNDEFINED;
    int mToIndex = UNDEFINED;
    int mScrollX = 0;
    int mScrollY = 0;

    int mScrollDeltaX = UNDEFINED;
    int mScrollDeltaY = UNDEFINED;
    int mMaxScrollX = 0;
    int mMaxScrollY = 0;

    int mAddedCount= UNDEFINED;
    int mRemovedCount = UNDEFINED;
    @UnsupportedAppUsage
    long mSourceNodeId = AccessibilityNodeInfo.UNDEFINED_NODE_ID;
    int mSourceWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
    int mSourceDisplayId = Display.INVALID_DISPLAY;

    CharSequence mClassName;
    CharSequence mContentDescription;
    CharSequence mBeforeText;
    Parcelable mParcelableData;

    final List<CharSequence> mText = new ArrayList<CharSequence>();

    int mConnectionId = UNDEFINED;

    /**
     * Creates a new {@link AccessibilityRecord}.
     */
    public AccessibilityRecord() {
    }

    /**
     * Copy constructor. Creates a new {@link AccessibilityRecord}, and this instance is initialized
     * with data from the given <code>record</code>.
     *
     * @param record The other record.
     */
    public AccessibilityRecord(@NonNull AccessibilityRecord record) {
        init(record);
    }

    /**
     * Sets the event source.
     *
     * @param source The source.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setSource(@Nullable View source) {
        setSource(source, AccessibilityNodeProvider.HOST_VIEW_ID);
    }

    /**
     * Sets the source to be a virtual descendant of the given <code>root</code>.
     * If <code>virtualDescendantId</code> equals to {@link View#NO_ID} the root
     * is set as the source.
     * <p>
     * A virtual descendant is an imaginary View that is reported as a part of the view
     * hierarchy for accessibility purposes. This enables custom views that draw complex
     * content to report them selves as a tree of virtual views, thus conveying their
     * logical structure.
     * </p>
     *
     * @param root The root of the virtual subtree.
     * @param virtualDescendantId The id of the virtual descendant.
     */
    public void setSource(@Nullable View root, int virtualDescendantId) {
        enforceNotSealed();
        boolean important = true;
        int rootViewId = AccessibilityNodeInfo.UNDEFINED_ITEM_ID;
        mSourceWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
        if (root != null) {
            important = root.isImportantForAccessibility();
            rootViewId = root.getAccessibilityViewId();
            mSourceWindowId = root.getAccessibilityWindowId();
        }
        setBooleanProperty(PROPERTY_IMPORTANT_FOR_ACCESSIBILITY, important);
        mSourceNodeId = AccessibilityNodeInfo.makeNodeId(rootViewId, virtualDescendantId);
    }

    /**
     * Set the source node ID directly
     *
     * @param sourceNodeId The source node Id
     * @hide
     */
    public void setSourceNodeId(long sourceNodeId) {
        mSourceNodeId = sourceNodeId;
    }

    /**
     * Gets the {@link AccessibilityNodeInfo} of the event source.
     * <p>
     *   <strong>Note:</strong> It is a client responsibility to recycle the received info
     *   by calling {@link AccessibilityNodeInfo#recycle() AccessibilityNodeInfo#recycle()}
     *   to avoid creating of multiple instances.
     * </p>
     * @return The info of the source.
     */
    public @Nullable AccessibilityNodeInfo getSource() {
        return getSource(GET_SOURCE_PREFETCH_FLAGS);
    }

    /**
     * Gets the {@link AccessibilityNodeInfo} of the event source.
     *
     * @param prefetchingStrategy the prefetching strategy.
     * @return The info of the source.
     *
     * @see AccessibilityNodeInfo#getParent(int) for a description of prefetching.
     */
    @Nullable
    public AccessibilityNodeInfo getSource(
            @AccessibilityNodeInfo.PrefetchingStrategy int prefetchingStrategy) {
        enforceSealed();
        if ((mConnectionId == UNDEFINED)
                || (mSourceWindowId == AccessibilityWindowInfo.UNDEFINED_WINDOW_ID)
                || (AccessibilityNodeInfo.getAccessibilityViewId(mSourceNodeId)
                == AccessibilityNodeInfo.UNDEFINED_ITEM_ID)) {
            return null;
        }
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.findAccessibilityNodeInfoByAccessibilityId(mConnectionId, mSourceWindowId,
                mSourceNodeId, false, prefetchingStrategy, null);
    }

    /**
     * Sets the display id.
     *
     * @param displayId The displayId id.
     *
     * @hide
     */
    @TestApi
    public void setDisplayId(int displayId) {
        mSourceDisplayId = displayId;
    }

    /**
     * Gets the id of the display from which the event comes from.
     *
     * @return The display id.
     */
    public int getDisplayId() {
        return mSourceDisplayId;
    }

    /**
     * Sets the window id.
     *
     * @param windowId The window id.
     *
     * @hide
     */
    public void setWindowId(int windowId) {
        mSourceWindowId = windowId;
    }

    /**
     * Gets the id of the window from which the event comes from.
     *
     * @return The window id.
     */
    public int getWindowId() {
        return mSourceWindowId;
    }

    /**
     * Gets if the source is checked.
     *
     * @return True if the view is checked, false otherwise.
     */
    public boolean isChecked() {
        return getBooleanProperty(PROPERTY_CHECKED);
    }

    /**
     * Sets if the source is checked.
     *
     * @param isChecked True if the view is checked, false otherwise.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setChecked(boolean isChecked) {
        enforceNotSealed();
        setBooleanProperty(PROPERTY_CHECKED, isChecked);
    }

    /**
     * Gets if the source is enabled.
     *
     * @return True if the view is enabled, false otherwise.
     */
    public boolean isEnabled() {
        return getBooleanProperty(PROPERTY_ENABLED);
    }

    /**
     * Sets if the source is enabled.
     *
     * @param isEnabled True if the view is enabled, false otherwise.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setEnabled(boolean isEnabled) {
        enforceNotSealed();
        setBooleanProperty(PROPERTY_ENABLED, isEnabled);
    }

    /**
     * Gets if the source is a password field.
     *
     * @return True if the view is a password field, false otherwise.
     */
    public boolean isPassword() {
        return getBooleanProperty(PROPERTY_PASSWORD);
    }

    /**
     * Sets if the source is a password field.
     *
     * @param isPassword True if the view is a password field, false otherwise.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setPassword(boolean isPassword) {
        enforceNotSealed();
        setBooleanProperty(PROPERTY_PASSWORD, isPassword);
    }

    /**
     * Gets if the source is taking the entire screen.
     *
     * @return True if the source is full screen, false otherwise.
     */
    public boolean isFullScreen() {
        return getBooleanProperty(PROPERTY_FULL_SCREEN);
    }

    /**
     * Sets if the source is taking the entire screen.
     *
     * @param isFullScreen True if the source is full screen, false otherwise.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setFullScreen(boolean isFullScreen) {
        enforceNotSealed();
        setBooleanProperty(PROPERTY_FULL_SCREEN, isFullScreen);
    }

    /**
     * Gets if the source is scrollable.
     *
     * @return True if the source is scrollable, false otherwise.
     */
    public boolean isScrollable() {
        return getBooleanProperty(PROPERTY_SCROLLABLE);
    }

    /**
     * Sets if the source is scrollable.
     *
     * @param scrollable True if the source is scrollable, false otherwise.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setScrollable(boolean scrollable) {
        enforceNotSealed();
        setBooleanProperty(PROPERTY_SCROLLABLE, scrollable);
    }

    /**
     * Gets if the source is important for accessibility.
     *
     * <strong>Note:</strong> Used only internally to determine whether
     * to deliver the event to a given accessibility service since some
     * services may want to regard all views for accessibility while others
     * may want to regard only the important views for accessibility.
     *
     * @return True if the source is important for accessibility,
     *        false otherwise.
     *
     * @hide
     */
    public boolean isImportantForAccessibility() {
        return getBooleanProperty(PROPERTY_IMPORTANT_FOR_ACCESSIBILITY);
    }

    /**
     * Sets if the source is important for accessibility.
     *
     * @param importantForAccessibility True if the source is important for accessibility,
     *                                  false otherwise.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     * @hide
     */
    public void setImportantForAccessibility(boolean importantForAccessibility) {
        enforceNotSealed();
        setBooleanProperty(PROPERTY_IMPORTANT_FOR_ACCESSIBILITY, importantForAccessibility);
    }

    /**
     * Gets the number of items that can be visited.
     *
     * @return The number of items.
     */
    public int getItemCount() {
        return mItemCount;
    }

    /**
     * Sets the number of items that can be visited.
     *
     * @param itemCount The number of items.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setItemCount(int itemCount) {
        enforceNotSealed();
        mItemCount = itemCount;
    }

    /**
     * Gets the index of the source in the list of items the can be visited.
     *
     * @return The current item index.
     */
    public int getCurrentItemIndex() {
        return mCurrentItemIndex;
    }

    /**
     * Sets the index of the source in the list of items that can be visited.
     *
     * @param currentItemIndex The current item index.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setCurrentItemIndex(int currentItemIndex) {
        enforceNotSealed();
        mCurrentItemIndex = currentItemIndex;
    }

    /**
     * Gets the index of the first character of the changed sequence,
     * or the beginning of a text selection or the index of the first
     * visible item when scrolling.
     *
     * @return The index of the first character or selection
     *        start or the first visible item.
     */
    public int getFromIndex() {
        return mFromIndex;
    }

    /**
     * Sets the index of the first character of the changed sequence
     * or the beginning of a text selection or the index of the first
     * visible item when scrolling.
     *
     * @param fromIndex The index of the first character or selection
     *        start or the first visible item.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setFromIndex(int fromIndex) {
        enforceNotSealed();
        mFromIndex = fromIndex;
    }

    /**
     * Gets the index of text selection end or the index of the last
     * visible item when scrolling.
     *
     * @return The index of selection end or last item index.
     */
    public int getToIndex() {
        return mToIndex;
    }

    /**
     * Sets the index of text selection end or the index of the last
     * visible item when scrolling.
     *
     * @param toIndex The index of selection end or last item index.
     */
    public void setToIndex(int toIndex) {
        enforceNotSealed();
        mToIndex = toIndex;
    }

    /**
     * Gets the scroll offset of the source left edge in pixels.
     *
     * @return The scroll.
     */
    public int getScrollX() {
        return mScrollX;
    }

    /**
     * Sets the scroll offset of the source left edge in pixels.
     *
     * @param scrollX The scroll.
     */
    public void setScrollX(int scrollX) {
        enforceNotSealed();
        mScrollX = scrollX;
    }

    /**
     * Gets the scroll offset of the source top edge in pixels.
     *
     * @return The scroll.
     */
    public int getScrollY() {
        return mScrollY;
    }

    /**
     * Sets the scroll offset of the source top edge in pixels.
     *
     * @param scrollY The scroll.
     */
    public void setScrollY(int scrollY) {
        enforceNotSealed();
        mScrollY = scrollY;
    }

    /**
     * Gets the difference in pixels between the horizontal position before the scroll and the
     * current horizontal position
     *
     * @return the scroll delta x
     */
    public int getScrollDeltaX() {
        return mScrollDeltaX;
    }

    /**
     * Sets the difference in pixels between the horizontal position before the scroll and the
     * current horizontal position
     *
     * @param scrollDeltaX the scroll delta x
     */
    public void setScrollDeltaX(int scrollDeltaX) {
        enforceNotSealed();
        mScrollDeltaX = scrollDeltaX;
    }

    /**
     * Gets the difference in pixels between the vertical position before the scroll and the
     * current vertical position
     *
     * @return the scroll delta y
     */
    public int getScrollDeltaY() {
        return mScrollDeltaY;
    }

    /**
     * Sets the difference in pixels between the vertical position before the scroll and the
     * current vertical position
     *
     * @param scrollDeltaY the scroll delta y
     */
    public void setScrollDeltaY(int scrollDeltaY) {
        enforceNotSealed();
        mScrollDeltaY = scrollDeltaY;
    }

    /**
     * Gets the max scroll offset of the source left edge in pixels.
     *
     * @return The max scroll.
     */
    public int getMaxScrollX() {
        return mMaxScrollX;
    }

    /**
     * Sets the max scroll offset of the source left edge in pixels.
     *
     * @param maxScrollX The max scroll.
     */
    public void setMaxScrollX(int maxScrollX) {
        enforceNotSealed();
        mMaxScrollX = maxScrollX;
    }

    /**
     * Gets the max scroll offset of the source top edge in pixels.
     *
     * @return The max scroll.
     */
    public int getMaxScrollY() {
        return mMaxScrollY;
    }

    /**
     * Sets the max scroll offset of the source top edge in pixels.
     *
     * @param maxScrollY The max scroll.
     */
    public void setMaxScrollY(int maxScrollY) {
        enforceNotSealed();
        mMaxScrollY = maxScrollY;
    }

    /**
     * Gets the number of added characters.
     *
     * @return The number of added characters.
     */
    public int getAddedCount() {
        return mAddedCount;
    }

    /**
     * Sets the number of added characters.
     *
     * @param addedCount The number of added characters.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setAddedCount(int addedCount) {
        enforceNotSealed();
        mAddedCount = addedCount;
    }

    /**
     * Gets the number of removed characters.
     *
     * @return The number of removed characters.
     */
    public int getRemovedCount() {
        return mRemovedCount;
    }

    /**
     * Sets the number of removed characters.
     *
     * @param removedCount The number of removed characters.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setRemovedCount(int removedCount) {
        enforceNotSealed();
        mRemovedCount = removedCount;
    }

    /**
     * Gets the class name of the source.
     *
     * @return The class name.
     */
    public @Nullable CharSequence getClassName() {
        return mClassName;
    }

    /**
     * Sets the class name of the source.
     *
     * @param className The lass name.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setClassName(@Nullable CharSequence className) {
        enforceNotSealed();
        mClassName = className;
    }

    /**
     * Gets the text of the event. The index in the list represents the priority
     * of the text. Specifically, the lower the index the higher the priority.
     *
     * @return The text.
     */
    public @NonNull List<CharSequence> getText() {
        return mText;
    }

    /**
     * Gets the text before a change.
     *
     * @return The text before the change.
     */
    public @Nullable CharSequence getBeforeText() {
        return mBeforeText;
    }

    /**
     * Sets the text before a change.
     *
     * @param beforeText The text before the change.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setBeforeText(@Nullable CharSequence beforeText) {
        enforceNotSealed();
        mBeforeText = (beforeText == null) ? null
                : beforeText.subSequence(0, beforeText.length());
    }

    /**
     * Gets the description of the source.
     *
     * @return The description.
     */
    public @Nullable CharSequence getContentDescription() {
        return mContentDescription;
    }

    /**
     * Sets the description of the source.
     *
     * @param contentDescription The description.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setContentDescription(@Nullable CharSequence contentDescription) {
        enforceNotSealed();
        mContentDescription = (contentDescription == null) ? null
                : contentDescription.subSequence(0, contentDescription.length());
    }

    /**
     * Gets the {@link Parcelable} data.
     *
     * @return The parcelable data.
     */
    public @Nullable Parcelable getParcelableData() {
        return mParcelableData;
    }

    /**
     * Sets the {@link Parcelable} data of the event.
     *
     * @param parcelableData The parcelable data.
     *
     * @throws IllegalStateException If called from an AccessibilityService.
     */
    public void setParcelableData(@Nullable Parcelable parcelableData) {
        enforceNotSealed();
        mParcelableData = parcelableData;
    }

    /**
     * Gets the id of the source node.
     *
     * @return The id.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public long getSourceNodeId() {
        return mSourceNodeId;
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
        enforceNotSealed();
        mConnectionId = connectionId;
    }

    /**
     * Sets if this instance is sealed.
     *
     * @param sealed Whether is sealed.
     *
     * @hide
     */
    public void setSealed(boolean sealed) {
        mSealed = sealed;
    }

    /**
     * Gets if this instance is sealed.
     *
     * @return Whether is sealed.
     */
    boolean isSealed() {
        return mSealed;
    }

    /**
     * Enforces that this instance is sealed.
     *
     * @throws IllegalStateException If this instance is not sealed.
     */
    void enforceSealed() {
        if (!isSealed()) {
            throw new IllegalStateException("Cannot perform this "
                    + "action on a not sealed instance.");
        }
    }

    /**
     * Enforces that this instance is not sealed.
     *
     * @throws IllegalStateException If this instance is sealed.
     */
    void enforceNotSealed() {
        if (isSealed()) {
            throw new IllegalStateException("Cannot perform this "
                    + "action on a sealed instance.");
        }
    }

    /**
     * Gets the value of a boolean property.
     *
     * @param property The property.
     * @return The value.
     */
    private boolean getBooleanProperty(int property) {
        return (mBooleanProperties & property) == property;
    }

    /**
     * Sets a boolean property.
     *
     * @param property The property.
     * @param value The value.
     */
    private void setBooleanProperty(int property, boolean value) {
        if (value) {
            mBooleanProperties |= property;
        } else {
            mBooleanProperties &= ~property;
        }
    }

    /**
     * Instantiates a new record initialized with data from the
     * given record.
     *
     * @deprecated Object pooling has been discontinued. Create a new instance using the
     * constructor {@link #AccessibilityRecord()} instead.
     * @return An instance.
     */
    @Deprecated
    public static @NonNull AccessibilityRecord obtain(@NonNull AccessibilityRecord record) {
       AccessibilityRecord clone = AccessibilityRecord.obtain();
       clone.init(record);
       return clone;
    }

    /**
     * Instantiates a new record.
     *
     * @deprecated Object pooling has been discontinued. Create a new instance using the
     * constructor {@link #AccessibilityRecord()} instead.
     * @return An instance.
     */
    @Deprecated
    public static @NonNull AccessibilityRecord obtain() {
        return new AccessibilityRecord();
    }

    /**
     * Would previously return an instance back to be reused.
     *
     * @deprecated Object pooling has been discontinued. Calling this function now will have
     * no effect.
     */
    @Deprecated
    public void recycle() { }

    /**
     * Initialize this record from another one.
     *
     * @param record The to initialize from.
     */
    void init(@NonNull AccessibilityRecord record) {
        mSealed = record.mSealed;
        mBooleanProperties = record.mBooleanProperties;
        mCurrentItemIndex = record.mCurrentItemIndex;
        mItemCount = record.mItemCount;
        mFromIndex = record.mFromIndex;
        mToIndex = record.mToIndex;
        mScrollX = record.mScrollX;
        mScrollY = record.mScrollY;
        mMaxScrollX = record.mMaxScrollX;
        mMaxScrollY = record.mMaxScrollY;
        mScrollDeltaX = record.mScrollDeltaX;
        mScrollDeltaY = record.mScrollDeltaY;
        mAddedCount = record.mAddedCount;
        mRemovedCount = record.mRemovedCount;
        mClassName = record.mClassName;
        mContentDescription = record.mContentDescription;
        mBeforeText = record.mBeforeText;
        mParcelableData = record.mParcelableData;
        mText.addAll(record.mText);
        mSourceWindowId = record.mSourceWindowId;
        mSourceNodeId = record.mSourceNodeId;
        mSourceDisplayId = record.mSourceDisplayId;
        mConnectionId = record.mConnectionId;
    }

    /**
     * Clears the state of this instance.
     */
    void clear() {
        mSealed = false;
        mBooleanProperties = 0;
        mCurrentItemIndex = UNDEFINED;
        mItemCount = UNDEFINED;
        mFromIndex = UNDEFINED;
        mToIndex = UNDEFINED;
        mScrollX = 0;
        mScrollY = 0;
        mMaxScrollX = 0;
        mMaxScrollY = 0;
        mScrollDeltaX = UNDEFINED;
        mScrollDeltaY = UNDEFINED;
        mAddedCount = UNDEFINED;
        mRemovedCount = UNDEFINED;
        mClassName = null;
        mContentDescription = null;
        mBeforeText = null;
        mParcelableData = null;
        mText.clear();
        mSourceNodeId = AccessibilityNodeInfo.UNDEFINED_ITEM_ID;
        mSourceWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
        mSourceDisplayId = Display.INVALID_DISPLAY;
        mConnectionId = UNDEFINED;
    }

    @Override
    public String toString() {
        return appendTo(new StringBuilder()).toString();
    }

    StringBuilder appendTo(StringBuilder builder) {
        builder.append(" [ ClassName: ").append(mClassName);
        if (!DEBUG_CONCISE_TOSTRING || !isEmpty(mText)) {
            appendPropName(builder, "Text").append(mText);
        }
        append(builder, "ContentDescription", mContentDescription);
        append(builder, "ItemCount", mItemCount);
        append(builder, "CurrentItemIndex", mCurrentItemIndex);

        appendUnless(true, PROPERTY_ENABLED, builder);
        appendUnless(false, PROPERTY_PASSWORD, builder);
        appendUnless(false, PROPERTY_CHECKED, builder);
        appendUnless(false, PROPERTY_FULL_SCREEN, builder);
        appendUnless(false, PROPERTY_SCROLLABLE, builder);

        append(builder, "BeforeText", mBeforeText);
        append(builder, "FromIndex", mFromIndex);
        append(builder, "ToIndex", mToIndex);
        append(builder, "ScrollX", mScrollX);
        append(builder, "ScrollY", mScrollY);
        append(builder, "MaxScrollX", mMaxScrollX);
        append(builder, "MaxScrollY", mMaxScrollY);
        append(builder, "ScrollDeltaX", mScrollDeltaX);
        append(builder, "ScrollDeltaY", mScrollDeltaY);
        append(builder, "AddedCount", mAddedCount);
        append(builder, "RemovedCount", mRemovedCount);
        append(builder, "ParcelableData", mParcelableData);
        builder.append(" ]");
        return builder;
    }

    private void appendUnless(boolean defValue, int prop, StringBuilder builder) {
        boolean value = getBooleanProperty(prop);
        if (DEBUG_CONCISE_TOSTRING && value == defValue) return;
        appendPropName(builder, singleBooleanPropertyToString(prop))
                .append(value);
    }

    private static String singleBooleanPropertyToString(int prop) {
        switch (prop) {
            case PROPERTY_CHECKED: return "Checked";
            case PROPERTY_ENABLED: return "Enabled";
            case PROPERTY_PASSWORD: return "Password";
            case PROPERTY_FULL_SCREEN: return "FullScreen";
            case PROPERTY_SCROLLABLE: return "Scrollable";
            case PROPERTY_IMPORTANT_FOR_ACCESSIBILITY:
                return "ImportantForAccessibility";
            default: return Integer.toHexString(prop);
        }
    }

    private void append(StringBuilder builder, String propName, int propValue) {
        if (DEBUG_CONCISE_TOSTRING && propValue == UNDEFINED) return;
        appendPropName(builder, propName).append(propValue);
    }

    private void append(StringBuilder builder, String propName, Object propValue) {
        if (DEBUG_CONCISE_TOSTRING && propValue == null) return;
        appendPropName(builder, propName).append(propValue);
    }

    private StringBuilder appendPropName(StringBuilder builder, String propName) {
        return builder.append("; ").append(propName).append(": ");
    }
}
