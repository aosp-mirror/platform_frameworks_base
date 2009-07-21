/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * This class represents accessibility events that are sent by the system when
 * something notable happens in the user interface. For example, when a
 * {@link android.widget.Button} is clicked, a {@link android.view.View} is focused, etc.
 * <p>
 * This class represents various semantically different accessibility event
 * types. Each event type has associated a set of related properties. In other
 * words, each event type is characterized via a subset of the properties exposed
 * by this class. For each event type there is a corresponding constant defined
 * in this class. Since some event types are semantically close there are mask
 * constants that group them together. Follows a specification of the event
 * types and their associated properties:
 * <p>
 * <b>VIEW TYPES</b> <br>
 * <p>
 * <b>View clicked</b> - represents the event of clicking on a {@link android.view.View}
 * like {@link android.widget.Button}, {@link android.widget.CompoundButton}, etc. <br>
 * Type:{@link #TYPE_VIEW_CLICKED} <br>
 * Properties:
 * {@link #getClassName()},
 * {@link #getPackageName()},
 * {@link #getEventTime()},
 * {@link #getText()},
 * {@link #isChecked()},
 * {@link #isEnabled()},
 * {@link #isPassword()},
 * {@link #getItemCount()},
 * {@link #getCurrentItemIndex()}
 * <p>
 * <b>View long clicked</b> - represents the event of long clicking on a {@link android.view.View}
 * like {@link android.widget.Button}, {@link android.widget.CompoundButton}, etc. <br>
 * Type:{@link #TYPE_VIEW_LONG_CLICKED} <br>
 * Properties:
 * {@link #getClassName()},
 * {@link #getPackageName()},
 * {@link #getEventTime()},
 * {@link #getText()},
 * {@link #isChecked()},
 * {@link #isEnabled()},
 * {@link #isPassword()},
 * {@link #getItemCount()},
 * {@link #getCurrentItemIndex()}
 * <p>
 * <b>View selected</b> - represents the event of selecting an item usually in
 * the context of an {@link android.widget.AdapterView}. <br>
 * Type: {@link #TYPE_VIEW_SELECTED} <br>
 * Properties:
 * {@link #getClassName()},
 * {@link #getPackageName()},
 * {@link #getEventTime()},
 * {@link #getText()},
 * {@link #isChecked()},
 * {@link #isEnabled()},
 * {@link #isPassword()},
 * {@link #getItemCount()},
 * {@link #getCurrentItemIndex()}
 * <p>
 * <b>View focused</b> - represents the event of focusing a
 * {@link android.view.View}. <br>
 * Type: {@link #TYPE_VIEW_FOCUSED} <br>
 * Properties:
 * {@link #getClassName()},
 * {@link #getPackageName()},
 * {@link #getEventTime()},
 * {@link #getText()},
 * {@link #isChecked()},
 * {@link #isEnabled()},
 * {@link #isPassword()},
 * {@link #getItemCount()},
 * {@link #getCurrentItemIndex()}
 * <p>
 * <b>View text changed</b> - represents the event of changing the text of an
 * {@link android.widget.EditText}. <br>
 * Type: {@link #TYPE_VIEW_TEXT_CHANGED} <br>
 * Properties:
 * {@link #getClassName()},
 * {@link #getPackageName()},
 * {@link #getEventTime()},
 * {@link #getText()},
 * {@link #isChecked()},
 * {@link #isEnabled()},
 * {@link #isPassword()},
 * {@link #getItemCount()},
 * {@link #getCurrentItemIndex()},
 * {@link #getFromIndex()},
 * {@link #getAddedCount()},
 * {@link #getRemovedCount()},
 * {@link #getBeforeText()}
 * <p>
 * <b>TRANSITION TYPES</b> <br>
 * <p>
 * <b>Window state changed</b> - represents the event of opening/closing a
 * {@link android.widget.PopupWindow}, {@link android.view.Menu},
 * {@link android.app.Dialog}, etc. <br>
 * Type: {@link #TYPE_WINDOW_STATE_CHANGED} <br>
 * Properties:
 * {@link #getClassName()},
 * {@link #getPackageName()},
 * {@link #getEventTime()},
 * {@link #getText()}
 * <p>
 * <b>NOTIFICATION TYPES</b> <br>
 * <p>
 * <b>Notification state changed</b> - represents the event showing/hiding
 * {@link android.app.Notification}.
 * Type: {@link #TYPE_NOTIFICATION_STATE_CHANGED} <br>
 * Properties:
 * {@link #getClassName()},
 * {@link #getPackageName()},
 * {@link #getEventTime()},
 * {@link #getText()}
 * {@link #getParcelableData()}
 * <p>
 * <b>Security note</b>
 * <p>
 * Since an event contains the text of its source privacy can be compromised by leaking of
 * sensitive information such as passwords. To address this issue any event fired in response
 * to manipulation of a PASSWORD field does NOT CONTAIN the text of the password.
 *
 * @see android.view.accessibility.AccessibilityManager
 * @see android.accessibilityservice.AccessibilityService
 */
public final class AccessibilityEvent implements Parcelable {

    /**
     * Invalid selection/focus position.
     *
     * @see #getCurrentItemIndex()
     */
    public static final int INVALID_POSITION = -1;

    /**
     * Maximum length of the text fields.
     *
     * @see #getBeforeText()
     * @see #getText()
     */
    public static final int MAX_TEXT_LENGTH = 500;

    /**
     * Represents the event of clicking on a {@link android.view.View} like
     * {@link android.widget.Button}, {@link android.widget.CompoundButton}, etc.
     */
    public static final int TYPE_VIEW_CLICKED = 0x00000001;

    /**
     * Represents the event of long clicking on a {@link android.view.View} like
     * {@link android.widget.Button}, {@link android.widget.CompoundButton}, etc.
     */
    public static final int TYPE_VIEW_LONG_CLICKED = 0x00000002;

    /**
     * Represents the event of selecting an item usually in the context of an
     * {@link android.widget.AdapterView}.
     */
    public static final int TYPE_VIEW_SELECTED = 0x00000004;

    /**
     * Represents the event of focusing a {@link android.view.View}.
     */
    public static final int TYPE_VIEW_FOCUSED = 0x00000008;

    /**
     * Represents the event of changing the text of an {@link android.widget.EditText}.
     */
    public static final int TYPE_VIEW_TEXT_CHANGED = 0x00000010;

    /**
     * Represents the event of opening/closing a {@link android.widget.PopupWindow},
     * {@link android.view.Menu}, {@link android.app.Dialog}, etc.
     */
    public static final int TYPE_WINDOW_STATE_CHANGED = 0x00000020;

    /**
     * Represents the event showing/hiding a {@link android.app.Notification}.
     */
    public static final int TYPE_NOTIFICATION_STATE_CHANGED = 0x00000040;

    /**
     * Mask for {@link AccessibilityEvent} all types.
     *
     * @see #TYPE_VIEW_CLICKED
     * @see #TYPE_VIEW_LONG_CLICKED
     * @see #TYPE_VIEW_SELECTED
     * @see #TYPE_VIEW_FOCUSED
     * @see #TYPE_VIEW_TEXT_CHANGED
     * @see #TYPE_WINDOW_STATE_CHANGED
     * @see #TYPE_NOTIFICATION_STATE_CHANGED
     */
    public static final int TYPES_ALL_MASK = 0xFFFFFFFF;

    private static final int MAX_POOL_SIZE = 2;
    private static final Object mPoolLock = new Object();
    private static AccessibilityEvent sPool;
    private static int sPoolSize;

    private static final int CHECKED = 0x00000001;
    private static final int ENABLED = 0x00000002;
    private static final int PASSWORD = 0x00000004;
    private static final int FULL_SCREEN = 0x00000080;

    private AccessibilityEvent mNext;

    private int mEventType;
    private int mBooleanProperties;
    private int mCurrentItemIndex;
    private int mItemCount;
    private int mFromIndex;
    private int mAddedCount;
    private int mRemovedCount;

    private long mEventTime;

    private CharSequence mClassName;
    private CharSequence mPackageName;
    private CharSequence mContentDescription;
    private CharSequence mBeforeText;

    private Parcelable mParcelableData;

    private final List<CharSequence> mText = new ArrayList<CharSequence>();

    private boolean mIsInPool;

    /*
     * Hide constructor from clients.
     */
    private AccessibilityEvent() {
        mCurrentItemIndex = INVALID_POSITION;
    }

    /**
     * Gets if the source is checked.
     *
     * @return True if the view is checked, false otherwise.
     */
    public boolean isChecked() {
        return getBooleanProperty(CHECKED);
    }

    /**
     * Sets if the source is checked.
     *
     * @param isChecked True if the view is checked, false otherwise.
     */
    public void setChecked(boolean isChecked) {
        setBooleanProperty(CHECKED, isChecked);
    }

    /**
     * Gets if the source is enabled.
     *
     * @return True if the view is enabled, false otherwise.
     */
    public boolean isEnabled() {
        return getBooleanProperty(ENABLED);
    }

    /**
     * Sets if the source is enabled.
     *
     * @param isEnabled True if the view is enabled, false otherwise.
     */
    public void setEnabled(boolean isEnabled) {
        setBooleanProperty(ENABLED, isEnabled);
    }

    /**
     * Gets if the source is a password field.
     *
     * @return True if the view is a password field, false otherwise.
     */
    public boolean isPassword() {
        return getBooleanProperty(PASSWORD);
    }

    /**
     * Sets if the source is a password field.
     *
     * @param isPassword True if the view is a password field, false otherwise.
     */
    public void setPassword(boolean isPassword) {
        setBooleanProperty(PASSWORD, isPassword);
    }

    /**
     * Sets if the source is taking the entire screen.
     *
     * @param isFullScreen True if the source is full screen, false otherwise.
     */
    public void setFullScreen(boolean isFullScreen) {
        setBooleanProperty(FULL_SCREEN, isFullScreen);
    }

    /**
     * Gets if the source is taking the entire screen.
     *
     * @return True if the source is full screen, false otherwise.
     */
    public boolean isFullScreen() {
        return getBooleanProperty(FULL_SCREEN);
    }

    /**
     * Gets the event type.
     *
     * @return The event type.
     */
    public int getEventType() {
        return mEventType;
    }

    /**
     * Sets the event type.
     *
     * @param eventType The event type.
     */
    public void setEventType(int eventType) {
        mEventType = eventType;
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
     * Gets the time in which this event was sent.
     *
     * @return The event time.
     */
    public long getEventTime() {
        return mEventTime;
    }

    /**
     * Sets the time in which this event was sent.
     *
     * @param eventTime The event time.
     */
    public void setEventTime(long eventTime) {
        mEventTime = eventTime;
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
     * Gets the package name of the source.
     *
     * @return The package name.
     */
    public CharSequence getPackageName() {
        return mPackageName;
    }

    /**
     * Sets the package name of the source.
     *
     * @param packageName The package name.
     */
    public void setPackageName(CharSequence packageName) {
        mPackageName = packageName;
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
     * Returns a cached instance if such is available or a new one is
     * instantiated with type property set.
     *
     * @param eventType The event type.
     * @return An instance.
     */
    public static AccessibilityEvent obtain(int eventType) {
        AccessibilityEvent event = AccessibilityEvent.obtain();
        event.setEventType(eventType);
        return event;
    }

    /**
     * Returns a cached instance if such is available or a new one is
     * instantiated.
     *
     * @return An instance.
     */
    public static AccessibilityEvent obtain() {
        synchronized (mPoolLock) {
            if (sPool != null) {
                AccessibilityEvent event = sPool;
                sPool = sPool.mNext;
                sPoolSize--;
                event.mNext = null;
                event.mIsInPool = false;
                return event;
            }
            return new AccessibilityEvent();
        }
    }

    /**
     * Return an instance back to be reused.
     * <p>
     * <b>Note: You must not touch the object after calling this function.</b>
     */
    public void recycle() {
        if (mIsInPool) {
            return;
        }

        clear();
        synchronized (mPoolLock) {
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
    private void clear() {
        mEventType = 0;
        mBooleanProperties = 0;
        mCurrentItemIndex = INVALID_POSITION;
        mItemCount = 0;
        mFromIndex = 0;
        mAddedCount = 0;
        mRemovedCount = 0;
        mEventTime = 0;
        mClassName = null;
        mPackageName = null;
        mContentDescription = null;
        mBeforeText = null;
        mText.clear();
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
     * Creates a new instance from a {@link Parcel}.
     *
     * @param parcel A parcel containing the state of a {@link AccessibilityEvent}.
     */
    public void initFromParcel(Parcel parcel) {
        mEventType = parcel.readInt();
        mBooleanProperties = parcel.readInt();
        mCurrentItemIndex = parcel.readInt();
        mItemCount = parcel.readInt();
        mFromIndex = parcel.readInt();
        mAddedCount = parcel.readInt();
        mRemovedCount = parcel.readInt();
        mEventTime = parcel.readLong();
        mClassName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        mPackageName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        mContentDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        mBeforeText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        mParcelableData = parcel.readParcelable(null);
        parcel.readList(mText, null);
    }

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mEventType);
        parcel.writeInt(mBooleanProperties);
        parcel.writeInt(mCurrentItemIndex);
        parcel.writeInt(mItemCount);
        parcel.writeInt(mFromIndex);
        parcel.writeInt(mAddedCount);
        parcel.writeInt(mRemovedCount);
        parcel.writeLong(mEventTime);
        TextUtils.writeToParcel(mClassName, parcel, 0);
        TextUtils.writeToParcel(mPackageName, parcel, 0);
        TextUtils.writeToParcel(mContentDescription, parcel, 0);
        TextUtils.writeToParcel(mBeforeText, parcel, 0);
        parcel.writeParcelable(mParcelableData, flags);
        parcel.writeList(mText);
    }

    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(super.toString());
        builder.append("; EventType: " + mEventType);
        builder.append("; EventTime: " + mEventTime);
        builder.append("; ClassName: " + mClassName);
        builder.append("; PackageName: " + mPackageName);
        builder.append("; Text: " + mText);
        builder.append("; ContentDescription: " + mContentDescription);
        builder.append("; ItemCount: " + mItemCount);
        builder.append("; CurrentItemIndex: " + mCurrentItemIndex);
        builder.append("; IsEnabled: " + isEnabled());
        builder.append("; IsPassword: " + isPassword());
        builder.append("; IsChecked: " + isChecked());
        builder.append("; IsFullScreen: " + isFullScreen());
        builder.append("; BeforeText: " + mBeforeText);
        builder.append("; FromIndex: " + mFromIndex);
        builder.append("; AddedCount: " + mAddedCount);
        builder.append("; RemovedCount: " + mRemovedCount);
        builder.append("; ParcelableData: " + mParcelableData);
        return builder.toString();
    }

    /**
     * @see Parcelable.Creator
     */
    public static final Parcelable.Creator<AccessibilityEvent> CREATOR =
            new Parcelable.Creator<AccessibilityEvent>() {
        public AccessibilityEvent createFromParcel(Parcel parcel) {
            AccessibilityEvent event = AccessibilityEvent.obtain();
            event.initFromParcel(parcel);
            return event;
        }

        public AccessibilityEvent[] newArray(int size) {
            return new AccessibilityEvent[size];
        }
    };
}
