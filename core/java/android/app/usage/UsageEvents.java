/**
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package android.app.usage;

import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.List;

/**
 * A result returned from {@link android.app.usage.UsageStatsManager#queryEvents(long, long)}
 * from which to read {@link android.app.usage.UsageEvents.Event} objects.
 */
public final class UsageEvents implements Parcelable {

    /**
     * An event representing a state change for a component.
     */
    public static final class Event {

        /**
         * No event type.
         */
        public static final int NONE = 0;

        /**
         * An event type denoting that a component moved to the foreground.
         */
        public static final int MOVE_TO_FOREGROUND = 1;

        /**
         * An event type denoting that a component moved to the background.
         */
        public static final int MOVE_TO_BACKGROUND = 2;

        /**
         * An event type denoting that a component was in the foreground when the stats
         * rolled-over. This is effectively treated as a {@link #MOVE_TO_BACKGROUND}.
         * {@hide}
         */
        public static final int END_OF_DAY = 3;

        /**
         * An event type denoting that a component was in the foreground the previous day.
         * This is effectively treated as a {@link #MOVE_TO_FOREGROUND}.
         * {@hide}
         */
        public static final int CONTINUE_PREVIOUS_DAY = 4;

        /**
         * {@hide}
         */
        public String mPackage;

        /**
         * {@hide}
         */
        public String mClass;

        /**
         * {@hide}
         */
        public long mTimeStamp;

        /**
         * {@hide}
         */
        public int mEventType;

        /**
         * TODO(adamlesinski): Removed before release.
         * {@hide}
         */
        public ComponentName getComponent() {
            return new ComponentName(mPackage, mClass);
        }

        /**
         * The package name of the source of this event.
         */
        public String getPackageName() {
            return mPackage;
        }

        /**
         * The class name of the source of this event. This may be null for
         * certain events.
         */
        public String getClassName() {
            return mClass;
        }

        /**
         * The time at which this event occurred, measured in milliseconds since the epoch.
         * <p/>
         * See {@link System#currentTimeMillis()}.
         */
        public long getTimeStamp() {
            return mTimeStamp;
        }

        /**
         * The event type.
         *
         * See {@link #MOVE_TO_BACKGROUND}
         * See {@link #MOVE_TO_FOREGROUND}
         */
        public int getEventType() {
            return mEventType;
        }
    }

    // Only used when creating the resulting events. Not used for reading/unparceling.
    private List<Event> mEventsToWrite = null;

    // Only used for reading/unparceling events.
    private Parcel mParcel = null;
    private final int mEventCount;

    private int mIndex = 0;

    /*
     * In order to save space, since ComponentNames will be duplicated everywhere,
     * we use a map and index into it.
     */
    private String[] mStringPool;

    /**
     * Construct the iterator from a parcel.
     * {@hide}
     */
    public UsageEvents(Parcel in) {
        mEventCount = in.readInt();
        mIndex = in.readInt();
        if (mEventCount > 0) {
            mStringPool = in.createStringArray();

            final int listByteLength = in.readInt();
            final int positionInParcel = in.readInt();
            mParcel = Parcel.obtain();
            mParcel.setDataPosition(0);
            mParcel.appendFrom(in, in.dataPosition(), listByteLength);
            mParcel.setDataSize(mParcel.dataPosition());
            mParcel.setDataPosition(positionInParcel);
        }
    }

    /**
     * Create an empty iterator.
     * {@hide}
     */
    UsageEvents() {
        mEventCount = 0;
    }

    /**
     * Construct the iterator in preparation for writing it to a parcel.
     * {@hide}
     */
    public UsageEvents(List<Event> events, String[] stringPool) {
        mStringPool = stringPool;
        mEventCount = events.size();
        mEventsToWrite = events;
    }

    /**
     * Returns whether or not there are more events to read using
     * {@link #getNextEvent(android.app.usage.UsageEvents.Event)}.
     *
     * @return true if there are more events, false otherwise.
     */
    public boolean hasNextEvent() {
        return mIndex < mEventCount;
    }

    /**
     * Retrieve the next {@link android.app.usage.UsageEvents.Event} from the collection and put the
     * resulting data into {@code eventOut}.
     *
     * @param eventOut The {@link android.app.usage.UsageEvents.Event} object that will receive the
     *                 next event data.
     * @return true if an event was available, false if there are no more events.
     */
    public boolean getNextEvent(Event eventOut) {
        if (mIndex >= mEventCount) {
            return false;
        }

        final int packageIndex = mParcel.readInt();
        if (packageIndex >= 0) {
            eventOut.mPackage = mStringPool[packageIndex];
        } else {
            eventOut.mPackage = null;
        }

        final int classIndex = mParcel.readInt();
        if (classIndex >= 0) {
            eventOut.mClass = mStringPool[classIndex];
        } else {
            eventOut.mClass = null;
        }
        eventOut.mEventType = mParcel.readInt();
        eventOut.mTimeStamp = mParcel.readLong();
        mIndex++;

        if (mIndex >= mEventCount) {
            mParcel.recycle();
            mParcel = null;
        }
        return true;
    }

    /**
     * Resets the collection so that it can be iterated over from the beginning.
     */
    public void resetToStart() {
        mIndex = 0;
        if (mParcel != null) {
            mParcel.setDataPosition(0);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private int findStringIndex(String str) {
        final int index = Arrays.binarySearch(mStringPool, str);
        if (index < 0) {
            throw new IllegalStateException("String '" + str + "' is not in the string pool");
        }
        return index;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mEventCount);
        dest.writeInt(mIndex);
        if (mEventCount > 0) {
            dest.writeStringArray(mStringPool);

            if (mEventsToWrite != null) {
                // Write out the events
                Parcel p = Parcel.obtain();
                try {
                    p.setDataPosition(0);
                    for (int i = 0; i < mEventCount; i++) {
                        final Event event = mEventsToWrite.get(i);

                        final int packageIndex;
                        if (event.mPackage != null) {
                            packageIndex = findStringIndex(event.mPackage);
                        } else {
                            packageIndex = -1;
                        }

                        final int classIndex;
                        if (event.mClass != null) {
                            classIndex = findStringIndex(event.mClass);
                        } else {
                            classIndex = -1;
                        }
                        p.writeInt(packageIndex);
                        p.writeInt(classIndex);
                        p.writeInt(event.getEventType());
                        p.writeLong(event.getTimeStamp());
                    }
                    final int listByteLength = p.dataPosition();

                    // Write the total length of the data.
                    dest.writeInt(listByteLength);

                    // Write our current position into the data.
                    dest.writeInt(0);

                    // Write the data.
                    dest.appendFrom(p, 0, listByteLength);
                } finally {
                    p.recycle();
                }

            } else if (mParcel != null) {
                // Write the total length of the data.
                dest.writeInt(mParcel.dataSize());

                // Write out current position into the data.
                dest.writeInt(mParcel.dataPosition());

                // Write the data.
                dest.appendFrom(mParcel, 0, mParcel.dataSize());
            } else {
                throw new IllegalStateException(
                        "Either mParcel or mEventsToWrite must not be null");
            }
        }
    }

    public static final Creator<UsageEvents> CREATOR = new Creator<UsageEvents>() {
        @Override
        public UsageEvents createFromParcel(Parcel source) {
            return new UsageEvents(source);
        }

        @Override
        public UsageEvents[] newArray(int size) {
            return new UsageEvents[size];
        }
    };

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        if (mParcel != null) {
            mParcel.recycle();
            mParcel = null;
        }
    }
}
