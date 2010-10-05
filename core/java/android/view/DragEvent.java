/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.view;

import android.content.ClipData;
import android.content.ClipDescription;
import android.os.Parcel;
import android.os.Parcelable;

/** !!! TODO: real docs */
public class DragEvent implements Parcelable {
    private static final boolean TRACK_RECYCLED_LOCATION = false;

    int mAction;
    float mX, mY;
    ClipDescription mClipDescription;
    ClipData mClipData;

    private DragEvent mNext;
    private RuntimeException mRecycledLocation;
    private boolean mRecycled;

    private static final int MAX_RECYCLED = 10;
    private static final Object gRecyclerLock = new Object();
    private static int gRecyclerUsed = 0;
    private static DragEvent gRecyclerTop = null;

    /**
     * action constants for DragEvent dispatch
     */
    public static final int ACTION_DRAG_STARTED = 1;
    public static final int ACTION_DRAG_LOCATION = 2;
    public static final int ACTION_DROP = 3;
    public static final int ACTION_DRAG_ENDED = 4;
    public static final int ACTION_DRAG_ENTERED = 5;
    public static final int ACTION_DRAG_EXITED = 6;

    /* hide the constructor behind package scope */
    private DragEvent() {
    }

    static DragEvent obtain() {
        return DragEvent.obtain(0, 0f, 0f, null, null);
    }

    public static DragEvent obtain(int action, float x, float y,
            ClipDescription description, ClipData data) {
        final DragEvent ev;
        synchronized (gRecyclerLock) {
            if (gRecyclerTop == null) {
                return new DragEvent();
            }
            ev = gRecyclerTop;
            gRecyclerTop = ev.mNext;
            gRecyclerUsed -= 1;
        }
        ev.mRecycledLocation = null;
        ev.mRecycled = false;
        ev.mNext = null;

        ev.mAction = action;
        ev.mX = x;
        ev.mY = y;
        ev.mClipDescription = description;
        ev.mClipData = data;

        return ev;
    }

    public static DragEvent obtain(DragEvent source) {
        return obtain(source.mAction, source.mX, source.mY,
                source.mClipDescription, source.mClipData);
    }

    public int getAction() {
        return mAction;
    }

    public float getX() {
        return mX;
    }

    public float getY() {
        return mY;
    }

    public ClipData getClipData() {
        return mClipData;
    }

    public ClipDescription getClipDescription() {
        return mClipDescription;
    }

    /**
     * Recycle the DragEvent, to be re-used by a later caller.  After calling
     * this function you must never touch the event again.
     */
    public final void recycle() {
        // Ensure recycle is only called once!
        if (TRACK_RECYCLED_LOCATION) {
            if (mRecycledLocation != null) {
                throw new RuntimeException(toString() + " recycled twice!", mRecycledLocation);
            }
            mRecycledLocation = new RuntimeException("Last recycled here");
        } else {
            if (mRecycled) {
                throw new RuntimeException(toString() + " recycled twice!");
            }
            mRecycled = true;
        }

        mClipData = null;
        mClipDescription = null;

        synchronized (gRecyclerLock) {
            if (gRecyclerUsed < MAX_RECYCLED) {
                gRecyclerUsed++;
                mNext = gRecyclerTop;
                gRecyclerTop = this;
            }
        }
    }

    @Override
    public String toString() {
        return "DragEvent{" + Integer.toHexString(System.identityHashCode(this))
        + " action=" + mAction + " @ (" + mX + ", " + mY + ") desc=" + mClipDescription
        + " data=" + mClipData
        + "}";
    }

    /* Parcelable interface */

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mAction);
        dest.writeFloat(mX);
        dest.writeFloat(mY);
        if (mClipData == null) {
            dest.writeInt(0);
        } else {
            dest.writeInt(1);
            mClipData.writeToParcel(dest, flags);
        }
        if (mClipDescription == null) {
            dest.writeInt(0);
        } else {
            dest.writeInt(1);
            mClipDescription.writeToParcel(dest, flags);
        }
    }

    public static final Parcelable.Creator<DragEvent> CREATOR =
        new Parcelable.Creator<DragEvent>() {
        public DragEvent createFromParcel(Parcel in) {
            DragEvent event = DragEvent.obtain();
            event.mAction = in.readInt();
            event.mX = in.readFloat();
            event.mY = in.readFloat();
            if (in.readInt() != 0) {
                event.mClipData = ClipData.CREATOR.createFromParcel(in);
            }
            if (in.readInt() != 0) {
                event.mClipDescription = ClipDescription.CREATOR.createFromParcel(in);
            }
            return event;
        }

        public DragEvent[] newArray(int size) {
            return new DragEvent[size];
        }
    };
}
