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

import android.compat.annotation.UnsupportedAppUsage;
import android.content.ClipData;
import android.content.ClipDescription;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.view.IDragAndDropPermissions;

//TODO: Improve Javadoc
/**
 * Represents an event that is sent out by the system at various times during a drag and drop
 * operation. It is a data structure that contains several important pieces of data about
 * the operation and the underlying data.
 * <p>
 *  View objects that receive a DragEvent call {@link #getAction()}, which returns
 *  an action type that indicates the state of the drag and drop operation. This allows a View
 *  object to react to a change in state by changing its appearance or performing other actions.
 *  For example, a View can react to the {@link #ACTION_DRAG_ENTERED} action type by
 *  by changing one or more colors in its displayed image.
 * </p>
 * <p>
 *  During a drag and drop operation, the system displays an image that the user drags. This image
 *  is called a drag shadow. Several action types reflect the position of the drag shadow relative
 *  to the View receiving the event.
 * </p>
 * <p>
 *  Most methods return valid data only for certain event actions. This is summarized in the
 *  following table. Each possible {@link #getAction()} value is listed in the first column. The
 *  other columns indicate which method or methods return valid data for that getAction() value:
 * </p>
 * <table>
 *  <tr>
 *      <th scope="col">getAction() Value</th>
 *      <th scope="col">getClipDescription()</th>
 *      <th scope="col">getLocalState()</th>
 *      <th scope="col">getX()</th>
 *      <th scope="col">getY()</th>
 *      <th scope="col">getClipData()</th>
 *      <th scope="col">getResult()</th>
 *  </tr>
 *  <tr>
 *      <td>ACTION_DRAG_STARTED</td>
 *      <td style="text-align: center;">X</td>
 *      <td style="text-align: center;">X</td>
 *      <td style="text-align: center;">X</td>
 *      <td style="text-align: center;">X</td>
 *      <td style="text-align: center;">&nbsp;</td>
 *      <td style="text-align: center;">&nbsp;</td>
 *  </tr>
 *  <tr>
 *      <td>ACTION_DRAG_ENTERED</td>
 *      <td style="text-align: center;">X</td>
 *      <td style="text-align: center;">X</td>
 *      <td style="text-align: center;">&nbsp;</td>
 *      <td style="text-align: center;">&nbsp;</td>
 *      <td style="text-align: center;">&nbsp;</td>
 *      <td style="text-align: center;">&nbsp;</td>
 *  </tr>
 *  <tr>
 *      <td>ACTION_DRAG_LOCATION</td>
 *      <td style="text-align: center;">X</td>
 *      <td style="text-align: center;">X</td>
 *      <td style="text-align: center;">X</td>
 *      <td style="text-align: center;">X</td>
 *      <td style="text-align: center;">&nbsp;</td>
 *      <td style="text-align: center;">&nbsp;</td>
 *  </tr>
 *  <tr>
 *      <td>ACTION_DRAG_EXITED</td>
 *      <td style="text-align: center;">X</td>
 *      <td style="text-align: center;">X</td>
 *      <td style="text-align: center;">&nbsp;</td>
 *      <td style="text-align: center;">&nbsp;</td>
 *      <td style="text-align: center;">&nbsp;</td>
 *      <td style="text-align: center;">&nbsp;</td>
 *  </tr>
 *  <tr>
 *      <td>ACTION_DROP</td>
 *      <td style="text-align: center;">X</td>
 *      <td style="text-align: center;">X</td>
 *      <td style="text-align: center;">X</td>
 *      <td style="text-align: center;">X</td>
 *      <td style="text-align: center;">X</td>
 *      <td style="text-align: center;">&nbsp;</td>
 *  </tr>
 *  <tr>
 *      <td>ACTION_DRAG_ENDED</td>
 *      <td style="text-align: center;">&nbsp;</td>
 *      <td style="text-align: center;">X</td>
 *      <td style="text-align: center;">&nbsp;</td>
 *      <td style="text-align: center;">&nbsp;</td>
 *      <td style="text-align: center;">&nbsp;</td>
 *      <td style="text-align: center;">X</td>
 *  </tr>
 * </table>
 * <p>
 *  The {@link android.view.DragEvent#getAction()},
 *  {@link android.view.DragEvent#getLocalState()}
 *  {@link android.view.DragEvent#describeContents()},
 *  {@link android.view.DragEvent#writeToParcel(Parcel,int)}, and
 *  {@link android.view.DragEvent#toString()} methods always return valid data.
 * </p>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For a guide to implementing drag and drop features, read the
 * <a href="{@docRoot}guide/topics/ui/drag-drop.html">Drag and Drop</a> developer guide.</p>
 * </div>
 */
public class DragEvent implements Parcelable {
    private static final boolean TRACK_RECYCLED_LOCATION = false;

    int mAction;
    float mX, mY;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    ClipDescription mClipDescription;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    ClipData mClipData;
    IDragAndDropPermissions mDragAndDropPermissions;

    Object mLocalState;
    boolean mDragResult;
    boolean mEventHandlerWasCalled;

    /**
     * The drag surface containing the object being dragged. Only provided if the target window
     * has the {@link WindowManager.LayoutParams#PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP} flag
     * and is only sent with {@link #ACTION_DROP}.
     */
    private SurfaceControl mDragSurface;

    /**
     * The offsets from the touch that the surface is adjusted by as the surface is moved around the
     * screen. Necessary for the target using the drag surface to animate it properly once it takes
     * ownership of the drag surface after the drop.
     */
    private float mOffsetX;
    private float mOffsetY;

    private DragEvent mNext;
    private RuntimeException mRecycledLocation;
    private boolean mRecycled;

    private static final int MAX_RECYCLED = 10;
    private static final Object gRecyclerLock = new Object();
    private static int gRecyclerUsed = 0;
    private static DragEvent gRecyclerTop = null;

    /**
     * Action constant returned by {@link #getAction()}: Signals the start of a
     * drag and drop operation. The View should return {@code true} from its
     * {@link View#onDragEvent(DragEvent) onDragEvent()} handler method or
     * {@link View.OnDragListener#onDrag(View,DragEvent) OnDragListener.onDrag()} listener
     * if it can accept a drop. The onDragEvent() or onDrag() methods usually inspect the metadata
     * from {@link #getClipDescription()} to determine if they can accept the data contained in
     * this drag. For an operation that doesn't represent data transfer, these methods may
     * perform other actions to determine whether or not the View should accept the data.
     * If the View wants to indicate that it is a valid drop target, it can also react by
     * changing its appearance.
     * <p>
     *  Views added or becoming visible for the first time during a drag operation receive this
     *  event when they are added or becoming visible.
     * </p>
     * <p>
     *  A View only receives further drag events for the drag operation if it returns {@code true}
     *  in response to ACTION_DRAG_STARTED.
     * </p>
     * @see #ACTION_DRAG_ENDED
     * @see #getX()
     * @see #getY()
     */
    public static final int ACTION_DRAG_STARTED = 1;

    /**
     * Action constant returned by {@link #getAction()}: Sent to a View after
     * {@link #ACTION_DRAG_ENTERED} while the drag shadow is still within the View object's bounding
     * box, but not within a descendant view that can accept the data. The {@link #getX()} and
     * {@link #getY()} methods supply
     * the X and Y position of of the drag point within the View object's bounding box.
     * <p>
     * A View receives an {@link #ACTION_DRAG_ENTERED} event before receiving any
     * ACTION_DRAG_LOCATION events.
     * </p>
     * <p>
     * The system stops sending ACTION_DRAG_LOCATION events to a View once the user moves the
     * drag shadow out of the View object's bounding box or into a descendant view that can accept
     * the data. If the user moves the drag shadow back into the View object's bounding box or out
     * of a descendant view that can accept the data, the View receives an ACTION_DRAG_ENTERED again
     * before receiving any more ACTION_DRAG_LOCATION events.
     * </p>
     * @see #ACTION_DRAG_ENTERED
     * @see #getX()
     * @see #getY()
     */
    public static final int ACTION_DRAG_LOCATION = 2;

    /**
     * Action constant returned by {@link #getAction()}: Signals to a View that the user
     * has released the drag shadow, and the drag point is within the bounding box of the View and
     * not within a descendant view that can accept the data.
     * The View should retrieve the data from the DragEvent by calling {@link #getClipData()}.
     * The methods {@link #getX()} and {@link #getY()} return the X and Y position of the drop point
     * within the View object's bounding box.
     * <p>
     * The View should return {@code true} from its {@link View#onDragEvent(DragEvent)}
     * handler or {@link View.OnDragListener#onDrag(View,DragEvent) OnDragListener.onDrag()}
     * listener if it accepted the drop, and {@code false} if it ignored the drop.
     * </p>
     * <p>
     * The View can also react to this action by changing its appearance.
     * </p>
     * @see #getClipData()
     * @see #getX()
     * @see #getY()
     */
    public static final int ACTION_DROP = 3;

    /**
     * Action constant returned by {@link #getAction()}:  Signals to a View that the drag and drop
     * operation has concluded.  A View that changed its appearance during the operation should
     * return to its usual drawing state in response to this event.
     * <p>
     *  All views with listeners that returned boolean <code>true</code> for the ACTION_DRAG_STARTED
     *  event will receive the ACTION_DRAG_ENDED event even if they are not currently visible when
     *  the drag ends. Views removed during the drag operation won't receive the ACTION_DRAG_ENDED
     *  event.
     * </p>
     * <p>
     *  The View object can call {@link #getResult()} to see the result of the operation.
     *  If a View returned {@code true} in response to {@link #ACTION_DROP}, then
     *  getResult() returns {@code true}, otherwise it returns {@code false}.
     * </p>
     * @see #ACTION_DRAG_STARTED
     * @see #getResult()
     */
    public static final int ACTION_DRAG_ENDED = 4;

    /**
     * Action constant returned by {@link #getAction()}: Signals to a View that the drag point has
     * entered the bounding box of the View.
     * <p>
     *  If the View can accept a drop, it can react to ACTION_DRAG_ENTERED
     *  by changing its appearance in a way that tells the user that the View is the current
     *  drop target.
     * </p>
     * The system stops sending ACTION_DRAG_LOCATION events to a View once the user moves the
     * drag shadow out of the View object's bounding box or into a descendant view that can accept
     * the data. If the user moves the drag shadow back into the View object's bounding box or out
     * of a descendant view that can accept the data, the View receives an ACTION_DRAG_ENTERED again
     * before receiving any more ACTION_DRAG_LOCATION events.
     * </p>
     * @see #ACTION_DRAG_ENTERED
     * @see #ACTION_DRAG_LOCATION
     */
    public static final int ACTION_DRAG_ENTERED = 5;

    /**
     * Action constant returned by {@link #getAction()}: Signals that the user has moved the
     * drag shadow out of the bounding box of the View or into a descendant view that can accept
     * the data.
     * The View can react by changing its appearance in a way that tells the user that
     * View is no longer the immediate drop target.
     * <p>
     *  After the system sends an ACTION_DRAG_EXITED event to the View, the View receives no more
     *  ACTION_DRAG_LOCATION events until the user drags the drag shadow back over the View.
     * </p>
     *
     */
     public static final int ACTION_DRAG_EXITED = 6;

    private DragEvent() {
    }

    private void init(int action, float x, float y, float offsetX, float offsetY,
            ClipDescription description, ClipData data, SurfaceControl dragSurface,
            IDragAndDropPermissions dragAndDropPermissions, Object localState, boolean result) {
        mAction = action;
        mX = x;
        mY = y;
        mOffsetX = offsetX;
        mOffsetY = offsetY;
        mClipDescription = description;
        mClipData = data;
        mDragSurface = dragSurface;
        mDragAndDropPermissions = dragAndDropPermissions;
        mLocalState = localState;
        mDragResult = result;
    }

    static DragEvent obtain() {
        return DragEvent.obtain(0, 0f, 0f, 0f, 0f, null, null, null, null, null, false);
    }

    /** @hide */
    public static DragEvent obtain(int action, float x, float y, float offsetX, float offsetY,
            Object localState, ClipDescription description, ClipData data,
            SurfaceControl dragSurface, IDragAndDropPermissions dragAndDropPermissions,
            boolean result) {
        final DragEvent ev;
        synchronized (gRecyclerLock) {
            if (gRecyclerTop == null) {
                ev = new DragEvent();
                ev.init(action, x, y, offsetX, offsetY, description, data, dragSurface,
                        dragAndDropPermissions, localState, result);
                return ev;
            }
            ev = gRecyclerTop;
            gRecyclerTop = ev.mNext;
            gRecyclerUsed -= 1;
        }
        ev.mRecycledLocation = null;
        ev.mRecycled = false;
        ev.mNext = null;

        ev.init(action, x, y, offsetX, offsetY, description, data, dragSurface,
                dragAndDropPermissions, localState, result);

        return ev;
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static DragEvent obtain(DragEvent source) {
        return obtain(source.mAction, source.mX, source.mY, source.mOffsetX, source.mOffsetY,
                source.mLocalState, source.mClipDescription, source.mClipData, source.mDragSurface,
                source.mDragAndDropPermissions, source.mDragResult);
    }

    /**
     * Inspect the action value of this event.
     * @return One of the following action constants, in the order in which they usually occur
     * during a drag and drop operation:
     * <ul>
     *  <li>{@link #ACTION_DRAG_STARTED}</li>
     *  <li>{@link #ACTION_DRAG_ENTERED}</li>
     *  <li>{@link #ACTION_DRAG_LOCATION}</li>
     *  <li>{@link #ACTION_DROP}</li>
     *  <li>{@link #ACTION_DRAG_EXITED}</li>
     *  <li>{@link #ACTION_DRAG_ENDED}</li>
     * </ul>
     */
    public int getAction() {
        return mAction;
    }

    /**
     * Gets the X coordinate of the drag point. The value is only valid if the event action is
     * {@link #ACTION_DRAG_STARTED}, {@link #ACTION_DRAG_LOCATION} or {@link #ACTION_DROP}.
     * @return The current drag point's X coordinate
     */
    public float getX() {
        return mX;
    }

    /**
     * Gets the Y coordinate of the drag point. The value is only valid if the event action is
     * {@link #ACTION_DRAG_STARTED}, {@link #ACTION_DRAG_LOCATION} or {@link #ACTION_DROP}.
     * @return The current drag point's Y coordinate
     */
    public float getY() {
        return mY;
    }

    /** @hide */
    public float getOffsetX() {
        return mOffsetX;
    }

    /** @hide */
    public float getOffsetY() {
        return mOffsetY;
    }

    /**
     * Returns the {@link android.content.ClipData} object sent to the system as part of the call
     * to
     * {@link android.view.View#startDragAndDrop(ClipData,View.DragShadowBuilder,Object,int)
     * startDragAndDrop()}.
     * This method only returns valid data if the event action is {@link #ACTION_DROP}.
     * @return The ClipData sent to the system by startDragAndDrop().
     */
    public ClipData getClipData() {
        return mClipData;
    }

    /**
     * Returns the {@link android.content.ClipDescription} object contained in the
     * {@link android.content.ClipData} object sent to the system as part of the call to
     * {@link android.view.View#startDragAndDrop(ClipData,View.DragShadowBuilder,Object,int)
     * startDragAndDrop()}.
     * The drag handler or listener for a View can use the metadata in this object to decide if the
     * View can accept the dragged View object's data.
     * <p>
     * This method returns valid data for all event actions except for {@link #ACTION_DRAG_ENDED}.
     * @return The ClipDescription that was part of the ClipData sent to the system by
     *     startDragAndDrop().
     */
    public ClipDescription getClipDescription() {
        return mClipDescription;
    }

    /** @hide */
    public SurfaceControl getDragSurface() {
        return mDragSurface;
    }

    /** @hide */
    public IDragAndDropPermissions getDragAndDropPermissions() {
        return mDragAndDropPermissions;
    }

    /**
     * Returns the local state object sent to the system as part of the call to
     * {@link android.view.View#startDragAndDrop(ClipData,View.DragShadowBuilder,Object,int)
     * startDragAndDrop()}.
     * The object is intended to provide local information about the drag and drop operation. For
     * example, it can indicate whether the drag and drop operation is a copy or a move.
     * <p>
     * The local state is available only to views in the activity which has started the drag
     * operation. In all other activities this method will return null
     * </p>
     * <p>
     *  This method returns valid data for all event actions.
     * </p>
     * @return The local state object sent to the system by startDragAndDrop().
     */
    public Object getLocalState() {
        return mLocalState;
    }

    /**
     * <p>
     * Returns an indication of the result of the drag and drop operation.
     * This method only returns valid data if the action type is {@link #ACTION_DRAG_ENDED}.
     * The return value depends on what happens after the user releases the drag shadow.
     * </p>
     * <p>
     * If the user releases the drag shadow on a View that can accept a drop, the system sends an
     * {@link #ACTION_DROP} event to the View object's drag event listener. If the listener
     * returns {@code true}, then getResult() will return {@code true}.
     * If the listener returns {@code false}, then getResult() returns {@code false}.
     * </p>
     * <p>
     * Notice that getResult() also returns {@code false} if no {@link #ACTION_DROP} is sent. This
     * happens, for example, when the user releases the drag shadow over an area outside of the
     * application. In this case, the system sends out {@link #ACTION_DRAG_ENDED} for the current
     * operation, but never sends out {@link #ACTION_DROP}.
     * </p>
     * @return {@code true} if a drag event listener returned {@code true} in response to
     * {@link #ACTION_DROP}. If the system did not send {@link #ACTION_DROP} before
     * {@link #ACTION_DRAG_ENDED}, or if the listener returned {@code false} in response to
     * {@link #ACTION_DROP}, then {@code false} is returned.
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
        mLocalState = null;
        mEventHandlerWasCalled = false;

        synchronized (gRecyclerLock) {
            if (gRecyclerUsed < MAX_RECYCLED) {
                gRecyclerUsed++;
                mNext = gRecyclerTop;
                gRecyclerTop = this;
            }
        }
    }

    /**
     * Returns a string that represents the symbolic name of the specified unmasked action
     * such as "ACTION_DRAG_START", "ACTION_DRAG_END" or an equivalent numeric constant
     * such as "35" if unknown.
     *
     * @param action The action.
     * @return The symbolic name of the specified action.
     * @see #getAction()
     * @hide
     */
    public static String actionToString(int action) {
        switch (action) {
            case ACTION_DRAG_STARTED:
                return "ACTION_DRAG_STARTED";
            case ACTION_DRAG_LOCATION:
                return "ACTION_DRAG_LOCATION";
            case ACTION_DROP:
                return "ACTION_DROP";
            case ACTION_DRAG_ENDED:
                return "ACTION_DRAG_ENDED";
            case ACTION_DRAG_ENTERED:
                return "ACTION_DRAG_ENTERED";
            case ACTION_DRAG_EXITED:
                return "ACTION_DRAG_EXITED";
        }
        return Integer.toString(action);
    }

    /**
     * Returns a string containing a concise, human-readable representation of this DragEvent
     * object.
     * @return A string representation of the DragEvent object.
     */
    @Override
    public String toString() {
        return "DragEvent{" + Integer.toHexString(System.identityHashCode(this))
        + " action=" + mAction + " @ (" + mX + ", " + mY + ") desc=" + mClipDescription
        + " data=" + mClipData + " local=" + mLocalState + " result=" + mDragResult
        + "}";
    }

    /* Parcelable interface */

    /**
     * Returns information about the {@link android.os.Parcel} representation of this DragEvent
     * object.
     * @return Information about the {@link android.os.Parcel} representation.
     */
    public int describeContents() {
        return 0;
    }

    /**
     * Creates a {@link android.os.Parcel} object from this DragEvent object.
     * @param dest A {@link android.os.Parcel} object in which to put the DragEvent object.
     * @param flags Flags to store in the Parcel.
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mAction);
        dest.writeFloat(mX);
        dest.writeFloat(mY);
        dest.writeFloat(mOffsetX);
        dest.writeFloat(mOffsetY);
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
        if (mDragSurface == null) {
            dest.writeInt(0);
        } else {
            dest.writeInt(1);
            mDragSurface.writeToParcel(dest, flags);
        }
        if (mDragAndDropPermissions == null) {
            dest.writeInt(0);
        } else {
            dest.writeInt(1);
            dest.writeStrongBinder(mDragAndDropPermissions.asBinder());
        }
    }

    /**
     * A container for creating a DragEvent from a Parcel.
     */
    public static final @android.annotation.NonNull Parcelable.Creator<DragEvent> CREATOR =
        new Parcelable.Creator<DragEvent>() {
        public DragEvent createFromParcel(Parcel in) {
            DragEvent event = DragEvent.obtain();
            event.mAction = in.readInt();
            event.mX = in.readFloat();
            event.mY = in.readFloat();
            event.mOffsetX = in.readFloat();
            event.mOffsetY = in.readFloat();
            event.mDragResult = (in.readInt() != 0);
            if (in.readInt() != 0) {
                event.mClipData = ClipData.CREATOR.createFromParcel(in);
            }
            if (in.readInt() != 0) {
                event.mClipDescription = ClipDescription.CREATOR.createFromParcel(in);
            }
            if (in.readInt() != 0) {
                event.mDragSurface = SurfaceControl.CREATOR.createFromParcel(in);
            }
            if (in.readInt() != 0) {
                event.mDragAndDropPermissions =
                        IDragAndDropPermissions.Stub.asInterface(in.readStrongBinder());;
            }
            return event;
        }

        public DragEvent[] newArray(int size) {
            return new DragEvent[size];
        }
    };
}
