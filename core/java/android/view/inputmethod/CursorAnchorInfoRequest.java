/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.view.inputmethod;

import android.inputmethodservice.InputMethodService;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.View;

/**
 * Used to enable or disable event notification for
 * {@link InputMethodService#onUpdateCursorAnchorInfo(CursorAnchorInfo)}. This class is also used to
 * enable {@link InputMethodService#onUpdateCursor(android.graphics.Rect)} for existing editors
 * that have not supported {@link InputMethodService#onUpdateCursorAnchorInfo(CursorAnchorInfo)}.
 */
public final class CursorAnchorInfoRequest implements Parcelable {
    private final int mRequestType;
    private final int mRequestFlags;

    /**
     * Not handled by the editor.
     */
    public static final int RESULT_NOT_HANDLED = 0x00;
    /**
     * Request is scheduled in the editor task queue.
     */
    public static final int RESULT_SCHEDULED = 0x01;

    /**
     * The request is for {@link InputMethodService#onUpdateCursorAnchorInfo(CursorAnchorInfo)}.
     * This mechanism is powerful enough to retrieve fine-grained positional information of
     * characters in the editor.
     */
    public static final int TYPE_CURSOR_ANCHOR_INFO = 0x01;
    /**
     * The editor is requested to call
     * {@link InputMethodManager#updateCursorAnchorInfo(android.view.View, CursorAnchorInfo)}
     * whenever cursor/anchor position is changed. To disable monitoring, call
     * {@link InputConnection#requestCursorAnchorInfo(CursorAnchorInfoRequest)} again with
     * {@link #TYPE_CURSOR_ANCHOR_INFO} and this flag off.
     * <p>
     * This flag can be used together with {@link #FLAG_CURSOR_ANCHOR_INFO_IMMEDIATE}.
     * </p>
     */
    public static final int FLAG_CURSOR_ANCHOR_INFO_MONITOR = 0x01;
    /**
     * The editor is requested to call
     * {@link InputMethodManager#updateCursorAnchorInfo(android.view.View, CursorAnchorInfo)} at
     * once, as soon as possible, regardless of cursor/anchor position changes. This flag can be
     * used together with {@link #FLAG_CURSOR_ANCHOR_INFO_MONITOR}.
     */
    public static final int FLAG_CURSOR_ANCHOR_INFO_IMMEDIATE = 0x02;

    /**
     * The request is for {@link InputMethodService#onUpdateCursor(android.graphics.Rect)}. This
     * mechanism has been available since API Level 3 (CUPCAKE) but only the cursor rectangle can
     * be retrieved with this mechanism.
     */
    public static final int TYPE_CURSOR_RECT = 0x02;
    /**
     * The editor is requested to call
     * {@link InputMethodManager#updateCursor(android.view.View, int, int, int, int)}
     * whenever the cursor position is changed. To disable monitoring, call
     * {@link InputConnection#requestCursorAnchorInfo(CursorAnchorInfoRequest)} again with
     * {@link #TYPE_CURSOR_RECT} and this flag off.
     * <p>
     * This flag can be used together with {@link #FLAG_CURSOR_RECT_IN_SCREEN_COORDINATES}.
     * </p>
     */
    public static final int FLAG_CURSOR_RECT_MONITOR = 0x01;
    /**
     * {@link InputMethodManager#updateCursor(android.view.View, int, int, int, int)} should be
     * called back in screen coordinates. To receive cursor position in local coordinates, call
     * {@link InputConnection#requestCursorAnchorInfo(CursorAnchorInfoRequest)} again with
     * {@link #TYPE_CURSOR_RECT} and this flag off.
     */
    public static final int FLAG_CURSOR_RECT_IN_SCREEN_COORDINATES = 0x02;
    /**
     * {@link InputMethodManager#updateCursor(android.view.View, int, int, int, int)} should be
     * called back in screen coordinates after coordinate conversion with {@link View#getMatrix()}.
     * To disable coordinate conversion with {@link View#getMatrix()} again, call
     * {@link InputConnection#requestCursorAnchorInfo(CursorAnchorInfoRequest)} with
     * {@link #TYPE_CURSOR_RECT} and this flag off.
     *
     * <p>
     * The flag is ignored if {@link #FLAG_CURSOR_RECT_IN_SCREEN_COORDINATES} is off.
     * </p>
     */
    public static final int FLAG_CURSOR_RECT_WITH_VIEW_MATRIX = 0x04;

    /**
     * Constructs the object with request type and type-specific flags.
     *
     * @param requestType the type of this request. Currently {@link #TYPE_CURSOR_ANCHOR_INFO} or
     * {@link #TYPE_CURSOR_RECT} is supported.
     * @param requestFlags the flags for the given request type.
     */
    public CursorAnchorInfoRequest(int requestType, int requestFlags) {
        mRequestType = requestType;
        mRequestFlags = requestFlags;
    }

    /**
     * Used to make this class parcelable.
     *
     * @param source the parcel from which the object is unmarshalled.
     */
    public CursorAnchorInfoRequest(Parcel source) {
        mRequestType = source.readInt();
        mRequestFlags = source.readInt();
    }

    /**
     * @return the type of this request.
     */
    public int getRequestType() {
        return mRequestType;
    }

    /**
     * @return the flags that are specific to the type of this request.
     */
    public int getRequestFlags() {
        return mRequestFlags;
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mRequestType);
        dest.writeInt(mRequestFlags);
    }

    @Override
    public int hashCode(){
        return mRequestType * 31 + mRequestFlags;
    }

    @Override
    public boolean equals(Object obj){
        if (obj == null) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof CursorAnchorInfoRequest)) {
            return false;
        }
        final CursorAnchorInfoRequest that = (CursorAnchorInfoRequest) obj;
        if (hashCode() != that.hashCode()) {
            return false;
        }
        return mRequestType != that.mRequestType && mRequestFlags == that.mRequestFlags;
    }

    @Override
    public String toString() {
        return "CursorAnchorInfoRequest{mRequestType=" + mRequestType
                + " mRequestFlags=" + mRequestFlags
                + "}";
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<CursorAnchorInfoRequest> CREATOR =
            new Parcelable.Creator<CursorAnchorInfoRequest>() {
        @Override
        public CursorAnchorInfoRequest createFromParcel(Parcel source) {
            return new CursorAnchorInfoRequest(source);
        }

        @Override
        public CursorAnchorInfoRequest[] newArray(int size) {
            return new CursorAnchorInfoRequest[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }
}
