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

/**
 * This class represents accessibility events that are sent by the system when
 * something notable happens in the user interface. For example, when a
 * {@link android.widget.Button} is clicked, a {@link android.view.View} is focused, etc.
 * <p>
 * An accessibility event is fired by an individual view which populates the event with
 * a record for its state and requests from its parent to send the event to interested
 * parties. The parent can optionally add a record for itself before dispatching a similar
 * request to its parent. A parent can also choose not to respect the request for sending
 * an event. The accessibility event is sent by the topmost view in the view tree.
 * Therefore, an {@link android.accessibilityservice.AccessibilityService} can explore
 * all records in an accessibility event to obtain more information about the context
 * in which the event was fired.
 * <p>
 * A client can add, remove, and modify records. The getters and setters for individual
 * properties operate on the current record which can be explicitly set by the client. By
 * default current is the first record. Thus, querying a record would require setting
 * it as the current one and interacting with the property getters and setters.
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
public final class AccessibilityEvent extends AccessibilityRecord implements Parcelable {

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
     * </br>
     * Note: This constant is no longer needed since there
     *       is no limit on the length of text that is contained
     *       in an accessibility event anymore.
     */
    @Deprecated
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
     * Represents the event of a hover enter over a {@link android.view.View}.
     */
    public static final int TYPE_VIEW_HOVER_ENTER = 0x00000080;

    /**
     * Represents the event of a hover exit over a {@link android.view.View}.
     */
    public static final int TYPE_VIEW_HOVER_EXIT = 0x00000100;

    /**
     * Represents the event of starting a touch exploration gesture.
     */
    public static final int TYPE_TOUCH_EXPLORATION_GESTURE_START = 0x00000200;

    /**
     * Represents the event of ending a touch exploration gesture.
     */
    public static final int TYPE_TOUCH_EXPLORATION_GESTURE_END = 0x00000400;

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

    private static final int MAX_POOL_SIZE = 10;
    private static final Object sPoolLock = new Object();
    private static AccessibilityEvent sPool;
    private static int sPoolSize;

    private AccessibilityEvent mNext;
    private boolean mIsInPool;

    private int mEventType;
    private CharSequence mPackageName;
    private long mEventTime;

    private final ArrayList<AccessibilityRecord> mRecords = new ArrayList<AccessibilityRecord>();

    /*
     * Hide constructor from clients.
     */
    private AccessibilityEvent() {

    }

    /**
     * Gets the number of records contained in the event.
     *
     * @return The number of records.
     */
    public int getRecordCount() {
        return mRecords.size();
    }

    /**
     * Appends an {@link AccessibilityRecord} to the end of event records.
     *
     * @param record The record to append.
     */
    public void appendRecord(AccessibilityRecord record) {
        mRecords.add(record);
    }

    /**
     * Gets the records at a given index.
     *
     * @param index The index.
     * @return The records at the specified index.
     */
    public AccessibilityRecord getRecord(int index) {
        return mRecords.get(index);
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
        synchronized (sPoolLock) {
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
     *
     * @throws IllegalStateException If the event is already recycled.
     */
    @Override
    public void recycle() {
        if (mIsInPool) {
            throw new IllegalStateException("Event already recycled!");
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
    @Override
    protected void clear() {
        super.clear();
        mEventType = 0;
        mPackageName = null;
        mEventTime = 0;
        while (!mRecords.isEmpty()) {
            AccessibilityRecord record = mRecords.remove(0);
            record.recycle();
        }
    }

    /**
     * Creates a new instance from a {@link Parcel}.
     *
     * @param parcel A parcel containing the state of a {@link AccessibilityEvent}.
     */
    public void initFromParcel(Parcel parcel) {
        mEventType = parcel.readInt();
        mPackageName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        mEventTime = parcel.readLong();
        readAccessibilityRecordFromParcel(this, parcel);

        // Read the records.
        final int recordCount = parcel.readInt();
        for (int i = 0; i < recordCount; i++) {
            AccessibilityRecord record = AccessibilityRecord.obtain();
            readAccessibilityRecordFromParcel(record, parcel);
            mRecords.add(record);
        }
    }

    /**
     * Reads an {@link AccessibilityRecord} from a parcel.
     *
     * @param record The record to initialize.
     * @param parcel The parcel to read from.
     */
    private void readAccessibilityRecordFromParcel(AccessibilityRecord record,
            Parcel parcel) {
        record.mBooleanProperties = parcel.readInt();
        record.mCurrentItemIndex = parcel.readInt();
        record.mItemCount = parcel.readInt();
        record.mFromIndex = parcel.readInt();
        record.mAddedCount = parcel.readInt();
        record.mRemovedCount = parcel.readInt();
        record.mClassName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        record.mContentDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        record.mBeforeText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        record.mParcelableData = parcel.readParcelable(null);
        parcel.readList(record.mText, null);
    }

    /**
     * {@inheritDoc}
     */
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mEventType);
        TextUtils.writeToParcel(mPackageName, parcel, 0);
        parcel.writeLong(mEventTime);
        writeAccessibilityRecordToParcel(this, parcel, flags);

        // Write the records.
        final int recordCount = getRecordCount();
        parcel.writeInt(recordCount);
        for (int i = 0; i < recordCount; i++) {
            AccessibilityRecord record = mRecords.get(i);
            writeAccessibilityRecordToParcel(record, parcel, flags);
        }
    }

    /**
     * Writes an {@link AccessibilityRecord} to a parcel.
     *
     * @param record The record to write.
     * @param parcel The parcel to which to write.
     */
    private void writeAccessibilityRecordToParcel(AccessibilityRecord record, Parcel parcel,
            int flags) {
        parcel.writeInt(record.mBooleanProperties);
        parcel.writeInt(record.mCurrentItemIndex);
        parcel.writeInt(record.mItemCount);
        parcel.writeInt(record.mFromIndex);
        parcel.writeInt(record.mAddedCount);
        parcel.writeInt(record.mRemovedCount);
        TextUtils.writeToParcel(record.mClassName, parcel, flags);
        TextUtils.writeToParcel(record.mContentDescription, parcel, flags);
        TextUtils.writeToParcel(record.mBeforeText, parcel, flags);
        parcel.writeParcelable(record.mParcelableData, flags);
        parcel.writeList(record.mText);
    }

    /**
     * {@inheritDoc}
     */
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("; EventType: " + mEventType);
        builder.append("; EventTime: " + mEventTime);
        builder.append("; PackageName: " + mPackageName);
        builder.append(" \n{\n");
        builder.append(super.toString());
        builder.append("\n");
        for (int i = 0; i < mRecords.size(); i++) {
            AccessibilityRecord record = mRecords.get(i);
            builder.append("  Record ");
            builder.append(i);
            builder.append(":");
            builder.append(record.toString());
            builder.append("\n");
        }
        builder.append("}\n");
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
