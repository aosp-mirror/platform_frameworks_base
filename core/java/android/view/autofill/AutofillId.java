/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.view.autofill;

import static android.service.autofill.Flags.FLAG_AUTOFILL_W_METRICS;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.View;

import java.util.Objects;

/**
 * A unique identifier for an autofill node inside an {@link android.app.Activity}.
 */
public final class AutofillId implements Parcelable {

    /** @hide */
    public static final int NO_SESSION = 0;

    private static final int FLAG_IS_VIRTUAL_INT = 0x1;
    private static final int FLAG_IS_VIRTUAL_LONG = 0x2;
    private static final int FLAG_HAS_SESSION = 0x4;

    private final int mViewId;
    private int mFlags;
    private final int mVirtualIntId;
    private final long mVirtualLongId;
    private int mSessionId;

    /** @hide */
    @TestApi
    public AutofillId(int id) {
        this(/* flags= */ 0, id, View.NO_ID, NO_SESSION);
    }

    /** @hide */
    @TestApi
    public AutofillId(@NonNull AutofillId hostId, int virtualChildId) {
        this(FLAG_IS_VIRTUAL_INT, hostId.mViewId, virtualChildId, NO_SESSION);
    }

    /** @hide */
    @TestApi
    public AutofillId(int hostId, int virtualChildId) {
        this(FLAG_IS_VIRTUAL_INT, hostId, virtualChildId, NO_SESSION);
    }

    /** @hide */
    public AutofillId(@NonNull AutofillId hostId, int virtualChildId, int sessionId) {
        this(FLAG_IS_VIRTUAL_INT | FLAG_HAS_SESSION, hostId.mViewId, virtualChildId, sessionId);
    }

    /** @hide */
    @TestApi
    public AutofillId(@NonNull AutofillId hostId, long virtualChildId, int sessionId) {
        this(FLAG_IS_VIRTUAL_LONG | FLAG_HAS_SESSION, hostId.mViewId, virtualChildId, sessionId);
    }

    private AutofillId(int flags, int parentId, long virtualChildId, int sessionId) {
        mFlags = flags;
        mViewId = parentId;
        mVirtualIntId = ((flags & FLAG_IS_VIRTUAL_INT) != 0) ? (int) virtualChildId : View.NO_ID;
        mVirtualLongId = ((flags & FLAG_IS_VIRTUAL_LONG) != 0) ? virtualChildId : View.NO_ID;
        mSessionId = sessionId;
    }

    /** @hide */
    @NonNull
    public static final AutofillId NO_AUTOFILL_ID = new AutofillId(0);

    /**
     * Creates an {@link AutofillId} with the virtual id.
     *
     * This method is used by a {@link View} that contains the virtual view hierarchy. Use this
     * method to create the {@link AutofillId} for each virtual view.
     *
     * @param host the view hosting the virtual view hierarchy which is used to show autofill
     *            suggestions.
     * @param virtualId id identifying the virtual view inside the host view.
     * @return an {@link AutofillId} for the virtual view
     */
    @NonNull
    public static AutofillId create(@NonNull View host, int virtualId) {
        Objects.requireNonNull(host);
        return new AutofillId(host.getAutofillId(), virtualId);
    }

    /** @hide */
    @NonNull
    @TestApi
    public static AutofillId withoutSession(@NonNull AutofillId id) {
        final int flags = id.mFlags & ~FLAG_HAS_SESSION;
        final long virtualChildId =
                ((id.mFlags & FLAG_IS_VIRTUAL_LONG) != 0) ? id.mVirtualLongId
                        : id.mVirtualIntId;
        return new AutofillId(flags, id.mViewId, virtualChildId, NO_SESSION);
    }

    /**
     * Returns the assigned unique identifier of this AutofillID.
     *
     * See @link{android.view.View#getAutofillId()} for more information on
     * how this is generated for native Views.
     */
    @FlaggedApi(FLAG_AUTOFILL_W_METRICS)
    public int getViewId() {
        return mViewId;
    }

    /**
     * Gets the virtual id. This is set if the view is a virtual view, most commonly set if the View
     * is of {@link android.webkit.WebView}.
     */
    @FlaggedApi(FLAG_AUTOFILL_W_METRICS)
    public int getAutofillVirtualId() {
        return mVirtualIntId;
    }

    /** Checks whether this AutofillId represents a virtual view. */
    @FlaggedApi(FLAG_AUTOFILL_W_METRICS)
    public boolean isVirtual() {
        return !isNonVirtual();
    }

    /**
     * Checks if this node is generate as part of a {@link android.app.assist.AssistStructure}. This
     * will usually return true if it should be used by an autofill service provider, and false
     * otherwise.
     */
    @FlaggedApi(FLAG_AUTOFILL_W_METRICS)
    public boolean isInAutofillSession() {
        return hasSession();
    }

    /**
     * Gets the virtual child id.
     *
     * <p>Should only be used on subsystems where such id is represented by an {@code int}
     * (Assist and Autofill).
     *
     * @hide
     */
    public int getVirtualChildIntId() {
        return mVirtualIntId;
    }

    /**
     * Gets the virtual child id.
     *
     * <p>Should only be used on subsystems where such id is represented by a {@code long}
     * (ContentCapture).
     *
     * @hide
     */
    public long getVirtualChildLongId() {
        return mVirtualLongId;
    }

    /**
     * Checks whether this node represents a virtual child, whose id is represented by an
     * {@code int}.
     *
     * <p>Should only be used on subsystems where such id is represented by an {@code int}
     * (Assist and Autofill).
     *
     * @hide
     */
    public boolean isVirtualInt() {
        return (mFlags & FLAG_IS_VIRTUAL_INT) != 0;
    }

    /**
     * Checks whether this node represents a virtual child, whose id is represented by an
     * {@code long}.
     *
     * <p>Should only be used on subsystems where such id is represented by a {@code long}
     * (ContentCapture).
     *
     * @hide
     */
    public boolean isVirtualLong() {
        return (mFlags & FLAG_IS_VIRTUAL_LONG) != 0;
    }

    /**
     * Checks whether this node represents a non-virtual child.
     *
     * @hide
     */
    @TestApi
    public boolean isNonVirtual() {
        return !isVirtualInt() && !isVirtualLong();
    }

    /** @hide */
    public boolean hasSession() {
        return (mFlags & FLAG_HAS_SESSION) != 0;
    }

    /**
     * Used to get the Session identifier associated with this AutofillId.
     *
     * @return a non-zero integer if {@link #isInAutofillSession()} returns true
     */
    @FlaggedApi(FLAG_AUTOFILL_W_METRICS)
    public int getSessionId() {
        return mSessionId;
    }

    /** @hide */
    public void setSessionId(int sessionId) {
        mFlags |= FLAG_HAS_SESSION;
        mSessionId = sessionId;
    }

    /** @hide */
    public void resetSessionId() {
        mFlags &= ~FLAG_HAS_SESSION;
        mSessionId = NO_SESSION;
    }

    /////////////////////////////////
    //  Object "contract" methods. //
    /////////////////////////////////

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + mViewId;
        result = prime * result + mVirtualIntId;
        result = prime * result + (int) (mVirtualLongId ^ (mVirtualLongId >>> 32));
        result = prime * result + mSessionId;
        return result;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        final AutofillId other = (AutofillId) obj;
        if (mViewId != other.mViewId) return false;
        if (mVirtualIntId != other.mVirtualIntId) return false;
        if (mVirtualLongId != other.mVirtualLongId) return false;
        if (mSessionId != other.mSessionId) return false;
        return true;
    }

    /** @hide */
    @TestApi
    public boolean equalsIgnoreSession(@Nullable AutofillId other) {
        if (this == other) return true;
        if (other == null) return false;
        if (mViewId != other.mViewId) return false;
        if (mVirtualIntId != other.mVirtualIntId) return false;
        if (mVirtualLongId != other.mVirtualLongId) return false;
        return true;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder().append(mViewId);
        if (isVirtualInt()) {
            builder.append(":i").append(mVirtualIntId);
        } else if (isVirtualLong()) {
            builder.append(":l").append(mVirtualLongId);
        }

        if (hasSession()) {
            builder.append('@').append(mSessionId);
        }
        return builder.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mViewId);
        parcel.writeInt(mFlags);
        if (hasSession()) {
            parcel.writeInt(mSessionId);
        }
        if (isVirtualInt()) {
            parcel.writeInt(mVirtualIntId);
        } else if (isVirtualLong()) {
            parcel.writeLong(mVirtualLongId);
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<AutofillId> CREATOR =
            new Parcelable.Creator<AutofillId>() {
        @Override
        public AutofillId createFromParcel(Parcel source) {
            final int viewId = source.readInt();
            final int flags = source.readInt();
            final int sessionId = (flags & FLAG_HAS_SESSION) != 0 ? source.readInt() : NO_SESSION;
            if ((flags & FLAG_IS_VIRTUAL_INT) != 0) {
                return new AutofillId(flags, viewId, source.readInt(), sessionId);
            }
            if ((flags & FLAG_IS_VIRTUAL_LONG) != 0) {
                return new AutofillId(flags, viewId, source.readLong(), sessionId);
            }
            return new AutofillId(flags, viewId, View.NO_ID, sessionId);
        }

        @Override
        public AutofillId[] newArray(int size) {
            return new AutofillId[size];
        }
    };
}
