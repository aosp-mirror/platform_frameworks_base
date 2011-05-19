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

import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a record in an accessibility event. This class encapsulates
 * the information for a {@link android.view.View}. Note that not all properties
 * are applicable to all view types. For detailed information please refer to
 * {@link AccessibilityEvent}.
 *
 * @see AccessibilityEvent
 */
public class AccessibilityRecord {

    private static final int INVALID_POSITION = -1;

    private static final int PROPERTY_CHECKED = 0x00000001;
    private static final int PROPERTY_ENABLED = 0x00000002;
    private static final int PROPERTY_PASSWORD = 0x00000004;
    private static final int PROPERTY_FULL_SCREEN = 0x00000080;

    private static final int MAX_POOL_SIZE = 10;
    private static final Object sPoolLock = new Object();
    private static AccessibilityRecord sPool;
    private static int sPoolSize;

    private AccessibilityRecord mNext;
    private boolean mIsInPool;

    protected int mBooleanProperties;
    protected int mCurrentItemIndex;
    protected int mItemCount;
    protected int mFromIndex;
    protected int mAddedCount;
    protected int mRemovedCount;

    protected CharSequence mClassName;
    protected CharSequence mContentDescription;
    protected CharSequence mBeforeText;
    protected Parcelable mParcelableData;

    protected final List<CharSequence> mText = new ArrayList<CharSequence>();

    /*
     * Hide constructor.
     */
    protected AccessibilityRecord() {

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
     */
    public void setChecked(boolean isChecked) {
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
     */
    public void setEnabled(boolean isEnabled) {
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
     */
    public void setPassword(boolean isPassword) {
        setBooleanProperty(PROPERTY_PASSWORD, isPassword);
    }

    /**
     * Sets if the source is taking the entire screen.
     *
     * @param isFullScreen True if the source is full screen, false otherwise.
     */
    public void setFullScreen(boolean isFullScreen) {
        setBooleanProperty(PROPERTY_FULL_SCREEN, isFullScreen);
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
     */
    public void setItemCount(int itemCount) {
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
     */
    public void setCurrentItemIndex(int currentItemIndex) {
        mCurrentItemIndex = currentItemIndex;
    }

    /**
     * Gets the index of the first character of the changed sequence.
     *
     * @return The index of the first character.
     */
    public int getFromIndex() {
        return mFromIndex;
    }

    /**
     * Sets the index of the first character of the changed sequence.
     *
     * @param fromIndex The index of the first character.
     */
    public void setFromIndex(int fromIndex) {
        mFromIndex = fromIndex;
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
     */
    public void setAddedCount(int addedCount) {
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
     */
    public void setRemovedCount(int removedCount) {
        mRemovedCount = removedCount;
    }

    /**
     * Gets the class name of the source.
     *
     * @return The class name.
     */
    public CharSequence getClassName() {
        return mClassName;
    }

    /**
     * Sets the class name of the source.
     *
     * @param className The lass name.
     */
    public void setClassName(CharSequence className) {
        mClassName = className;
    }

    /**
     * Gets the text of the event. The index in the list represents the priority
     * of the text. Specifically, the lower the index the higher the priority.
     *
     * @return The text.
     */
    public List<CharSequence> getText() {
        return mText;
    }

    /**
     * Sets the text before a change.
     *
     * @return The text before the change.
     */
    public CharSequence getBeforeText() {
        return mBeforeText;
    }

    /**
     * Sets the text before a change.
     *
     * @param beforeText The text before the change.
     */
    public void setBeforeText(CharSequence beforeText) {
        mBeforeText = beforeText;
    }

    /**
     * Gets the description of the source.
     *
     * @return The description.
     */
    public CharSequence getContentDescription() {
        return mContentDescription;
    }

    /**
     * Sets the description of the source.
     *
     * @param contentDescription The description.
     */
    public void setContentDescription(CharSequence contentDescription) {
        mContentDescription = contentDescription;
    }

    /**
     * Gets the {@link Parcelable} data.
     *
     * @return The parcelable data.
     */
    public Parcelable getParcelableData() {
        return mParcelableData;
    }

    /**
     * Sets the {@link Parcelable} data of the event.
     *
     * @param parcelableData The parcelable data.
     */
    public void setParcelableData(Parcelable parcelableData) {
        mParcelableData = parcelableData;
    }

    /**
     * Gets the value of a boolean property.
     *
     * @param property The property.
     * @return The value.
     */
    public boolean getBooleanProperty(int property) {
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
     * Returns a cached instance if such is available or a new one is
     * instantiated.
     *
     * @return An instance.
     */
    public static AccessibilityRecord obtain() {
        synchronized (sPoolLock) {
            if (sPool != null) {
                AccessibilityRecord record = sPool;
                sPool = sPool.mNext;
                sPoolSize--;
                record.mNext = null;
                record.mIsInPool = false;
                return record;
            }
            return new AccessibilityRecord();
        }
    }

    /**
     * Return an instance back to be reused.
     * <p>
     * <b>Note: You must not touch the object after calling this function.</b>
     *
     * @throws IllegalStateException If the record is already recycled.
     */
    public void recycle() {
        if (mIsInPool) {
            throw new IllegalStateException("Record already recycled!");
        }
        clear();
        synchronized (sPoolLock) {
            if (sPoolSize <= MAX_POOL_SIZE) {
                mNext = sPool;
                sPool = this;
                mIsInPool = true;
                sPoolSize++;
            }
        }
    }

    /**
     * Clears the state of this instance.
     */
    protected void clear() {
        mBooleanProperties = 0;
        mCurrentItemIndex = INVALID_POSITION;
        mItemCount = 0;
        mFromIndex = 0;
        mAddedCount = 0;
        mRemovedCount = 0;
        mClassName = null;
        mContentDescription = null;
        mBeforeText = null;
        mParcelableData = null;
        mText.clear();
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(" [ ClassName: " + mClassName);
        builder.append("; Text: " + mText);
        builder.append("; ContentDescription: " + mContentDescription);
        builder.append("; ItemCount: " + mItemCount);
        builder.append("; CurrentItemIndex: " + mCurrentItemIndex);
        builder.append("; IsEnabled: " + getBooleanProperty(PROPERTY_ENABLED));
        builder.append("; IsPassword: " + getBooleanProperty(PROPERTY_PASSWORD));
        builder.append("; IsChecked: " + getBooleanProperty(PROPERTY_CHECKED));
        builder.append("; IsFullScreen: " + getBooleanProperty(PROPERTY_FULL_SCREEN));
        builder.append("; BeforeText: " + mBeforeText);
        builder.append("; FromIndex: " + mFromIndex);
        builder.append("; AddedCount: " + mAddedCount);
        builder.append("; RemovedCount: " + mRemovedCount);
        builder.append("; ParcelableData: " + mParcelableData);
        builder.append(" ]");
        return builder.toString();
    }
}
