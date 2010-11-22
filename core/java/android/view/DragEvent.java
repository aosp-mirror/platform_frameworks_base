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
    boolean mDragResult;

    private DragEvent mNext;
    private RuntimeException mRecycledLocation;
    private boolean mRecycled;

    private static final int MAX_RECYCLED = 10;
    private static final Object gRecyclerLock = new Object();
    private static int gRecyclerUsed = 0;
    private static DragEvent gRecyclerTop = null;

    /**
     * Action constant returned by {@link #getAction()}.  Delivery of a DragEvent whose
     * action is ACTION_DRAG_STARTED means that a drag operation has been initiated.  The
     * view receiving this DragEvent should inspect the metadata of the dragged content,
     * available via {@link #getClipDescription()}, and return {@code true} from
     * {@link View#onDragEvent(DragEvent)} if the view is prepared to accept a drop of
     * that clip data.  If the view chooses to present a visual indication that it is
     * a valid target of the ongoing drag, then it should draw that indication in response
     * to this event.
     * <p>
     * A view will only receive ACTION_DRAG_ENTERED, ACTION_DRAG_LOCATION, ACTION_DRAG_EXITED,
     * and ACTION_DRAG_LOCATION events if it returns {@code true} in response to the
     * ACTION_DRAG_STARTED event.
     */
    public static final int ACTION_DRAG_STARTED = 1;

    /**
     * Action constant returned by {@link #getAction()}.  Delivery of a DragEvent whose
     * action is ACTION_DRAG_LOCATION means that the drag operation is currently hovering
     * over the view.  The {@link #getX()} and {@link #getY()} methods supply the location
     * of the drag point within the view's coordinate system.
     * <p>
     * A view will receive an ACTION_DRAG_ENTERED event before receiving any
     * ACTION_DRAG_LOCATION events.  If the drag point leaves the view, then an
     * ACTION_DRAG_EXITED event is delivered to the view, after which no more
     * ACTION_DRAG_LOCATION events will be sent (unless the drag re-enters the view,
     * of course).
     */
    public static final int ACTION_DRAG_LOCATION = 2;

    /**
     * Action constant returned by {@link #getAction()}.  Delivery of a DragEvent whose
     * action is ACTION_DROP means that the dragged content has been dropped on this view.
     * The view should retrieve the content via {@link #getClipData()} and act on it
     * appropriately.  The {@link #getX()} and {@link #getY()} methods supply the location
     * of the drop point within the view's coordinate system.
     * <p>
     * The view should return {@code true} from its {@link View#onDragEvent(DragEvent)}
     * method in response to this event if it accepted the content, and {@code false}
     * if it ignored the drop.
     */
    public static final int ACTION_DROP = 3;

    /**
     * Action constant returned by {@link #getAction()}.  Delivery of a DragEvent whose
     * action is ACTION_DRAG_ENDED means that the drag operation has concluded.  A view
     * that is drawing a visual indication of drag acceptance should return to its usual
     * drawing state in response to this event.
     * <p>
     * All views that received an ACTION_DRAG_STARTED event will receive the
     * ACTION_DRAG_ENDED event even if they are not currently visible when the drag
     * ends.
     */
    public static final int ACTION_DRAG_ENDED = 4;

    /**
     * Action constant returned by {@link #getAction()}.  Delivery of a DragEvent whose
     * action is ACTION_DRAG_ENTERED means that the drag point has entered the view's
     * bounds.  If the view changed its visual state in response to the ACTION_DRAG_ENTERED
     * event, it should return to its normal drag-in-progress visual state in response to
     * this event.
     * <p>
     * A view will receive an ACTION_DRAG_ENTERED event before receiving any
     * ACTION_DRAG_LOCATION events.  If the drag point leaves the view, then an
     * ACTION_DRAG_EXITED event is delivered to the view, after which no more
     * ACTION_DRAG_LOCATION events will be sent (unless the drag re-enters the view,
     * of course).
     */
    public static final int ACTION_DRAG_ENTERED = 5;

    /**
     * Action constant returned by {@link #getAction()}.  Delivery of a DragEvent whose
     * action is ACTION_DRAG_ENTERED means that the drag point has entered the view's
     * bounds.  If the view chooses to present a visual indication that it will receive
     * the drop if it occurs now, then it should draw that indication in response to
     * this event.
     * <p>
     * A view will receive an ACTION_DRAG_ENTERED event before receiving any
     * ACTION_DRAG_LOCATION events.  If the drag point leaves the view, then an
     * ACTION_DRAG_EXITED event is delivered to the view, after which no more
     * ACTION_DRAG_LOCATION events will be sent (unless the drag re-enters the view,
     * of course).
     */
public static final int ACTION_DRAG_EXITED = 6;

    private DragEvent() {
    }

    private void init(int action, float x, float y, ClipDescription description, ClipData data,
            boolean result) {
        mAction = action;
        mX = x;
        mY = y;
        mClipDescription = description;
        mClipData = data;
        mDragResult = result;
    }

    static DragEvent obtain() {
        return DragEvent.obtain(0, 0f, 0f, null, null, false);
    }

    /** @hide */
    public static DragEvent obtain(int action, float x, float y,
            ClipDescription description, ClipData data, boolean result) {
        final DragEvent ev;
        synchronized (gRecyclerLock) {
            if (gRecyclerTop == null) {
                ev = new DragEvent();
                ev.init(action, x, y, description, data, result);
                return ev;
            }
            ev = gRecyclerTop;
            gRecyclerTop = ev.mNext;
            gRecyclerUsed -= 1;
        }
        ev.mRecycledLocation = null;
        ev.mRecycled = false;
        ev.mNext = null;

        ev.init(action, x, y, description, data, result);

        return ev;
    }

    /** @hide */
    public static DragEvent obtain(DragEvent source) {
        return obtain(source.mAction, source.mX, source.mY,
                source.mClipDescription, source.mClipData, source.mDragResult);
    }

    /**
     * Inspect the action value of this event.
     * @return One of {@link #ACTION_DRAG_STARTED}, {@link #ACTION_DRAG_ENDED},
     *         {@link #ACTION_DROP}, {@link #ACTION_DRAG_ENTERED}, {@link #ACTION_DRAG_EXITED},
     *         or {@link #ACTION_DRAG_LOCATION}.
     */
    public int getAction() {
        return mAction;
    }

    /**
     * For ACTION_DRAG_LOCATION and ACTION_DROP events, returns the x coordinate of the
     * drag point.
     * @return The current drag point's x coordinate, when relevant.
     */
    public float getX() {
        return mX;
    }

    /**
     * For ACTION_DRAG_LOCATION and ACTION_DROP events, returns the y coordinate of the
     * drag point.
     * @return The current drag point's y coordinate, when relevant.
     */
    public float getY() {
        return mY;
    }

    /**
     * Provides the data payload of the drag operation.  This payload is only available
     * for events whose action value is ACTION_DROP.
     * @return The ClipData containing the data being dropped on the view.
     */
    public ClipData getClipData() {
        return mClipData;
    }

    /**
     * Provides a description of the drag operation's data payload.  This payload is
     * available for all DragEvents other than ACTION_DROP.
     * @return A ClipDescription describing the contents of the data being dragged.
     */
    public ClipDescription getClipDescription() {
        return mClipDescription;
    }

    /**
     * Provides an indication of whether the drag operation concluded successfully.
     * This method is only available on ACTION_DRAG_ENDED events.
     * @return {@code true} if the drag operation ended with an accepted drop; {@code false}
     *         otherwise.
     */
    public boolean getResult() {
        return mDragResult;
    }

    /**
     * Recycle the DragEvent, to be re-used by a later caller.  After calling
     * this function you must never touch the event again.
     *
     * @hide
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
        + " data=" + mClipData + " result=" + mDragResult
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
        dest.writeInt(mDragResult ? 1 : 0);
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
            event.mDragResult = (in.readInt() != 0);
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
