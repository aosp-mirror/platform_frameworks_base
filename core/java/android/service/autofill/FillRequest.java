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

package android.service.autofill;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.View;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * This class represents a request to an autofill service
 * to interpret the screen and provide information to the system which views are
 * interesting for saving and what are the possible ways to fill the inputs on
 * the screen if applicable.
 *
 * @see AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal, FillCallback)
 */
public final class FillRequest implements Parcelable {

    /**
     * Indicates autofill was explicitly requested by the user.
     *
     * <p>Users typically make an explicit request to autofill a screen in two situations:
     * <ul>
     *   <li>The app disabled autofill (using {@link View#setImportantForAutofill(int)}.
     *   <li>The service could not figure out how to autofill a screen (but the user knows the
     *       service has data for that app).
     * </ul>
     *
     * <p>This flag is particularly useful for the second case. For example, the service could offer
     * a complex UI where the user can map which screen views belong to each user data, or it could
     * offer a simpler UI where the user picks the data for just the view used to trigger the
     * request (that would be the view whose
     * {@link android.app.assist.AssistStructure.ViewNode#isFocused()} method returns {@code true}).
     *
     * <p>An explicit autofill request is triggered when the
     * {@link android.view.autofill.AutofillManager#requestAutofill(View)} or
     * {@link android.view.autofill.AutofillManager#requestAutofill(View, int, android.graphics.Rect)}
     * is called. For example, standard {@link android.widget.TextView} views show an
     * {@code AUTOFILL} option in the overflow menu that triggers such request.
     */
    public static final int FLAG_MANUAL_REQUEST = 0x1;

    /** @hide */
    public static final int INVALID_REQUEST_ID = Integer.MIN_VALUE;

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_MANUAL_REQUEST
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface RequestFlags{}

    private final int mId;
    private final @RequestFlags int mFlags;
    private final @NonNull ArrayList<FillContext> mContexts;
    private final @Nullable Bundle mClientState;

    private FillRequest(@NonNull Parcel parcel) {
        mId = parcel.readInt();
        mContexts = new ArrayList<>();
        parcel.readParcelableList(mContexts, null);

        mClientState = parcel.readBundle();
        mFlags = parcel.readInt();
    }

    /** @hide */
    public FillRequest(int id, @NonNull ArrayList<FillContext> contexts,
            @Nullable Bundle clientState, @RequestFlags int flags) {
        mId = id;
        mFlags = Preconditions.checkFlagsArgument(flags, FLAG_MANUAL_REQUEST);
        mContexts = Preconditions.checkCollectionElementsNotNull(contexts, "contexts");
        mClientState = clientState;
    }

    /**
     * Gets the unique id of this request.
     */
    public int getId() {
        return mId;
    }

    /**
     * Gets the flags associated with this request.
     *
     * @see #FLAG_MANUAL_REQUEST
     */
    public @RequestFlags int getFlags() {
        return mFlags;
    }

    /**
     * Gets the contexts associated with each previous fill request.
     */
    public @NonNull List<FillContext> getFillContexts() {
        return mContexts;
    }

    @Override
    public String toString() {
        return "FillRequest: [id=" + mId + ", flags=" + mFlags + ", ctxts= " + mContexts + "]";
    }

    /**
     * Gets the latest client state bundle set by the service in a
     * {@link FillResponse.Builder#setClientState(Bundle) fill response}.
     *
     * <p><b>Note:</b> Prior to Android {@link android.os.Build.VERSION_CODES#P}, only client state
     * bundles set by {@link FillResponse.Builder#setClientState(Bundle)} were considered. On
     * Android {@link android.os.Build.VERSION_CODES#P} and higher, bundles set in the result of
     * an authenticated request through the
     * {@link android.view.autofill.AutofillManager#EXTRA_CLIENT_STATE} extra are
     * also considered (and take precedence when set).
     *
     * @return The client state.
     */
    public @Nullable Bundle getClientState() {
        return mClientState;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mId);
        parcel.writeParcelableList(mContexts, flags);
        parcel.writeBundle(mClientState);
        parcel.writeInt(mFlags);
    }

    public static final Parcelable.Creator<FillRequest> CREATOR =
            new Parcelable.Creator<FillRequest>() {
        @Override
        public FillRequest createFromParcel(Parcel parcel) {
            return new FillRequest(parcel);
        }

        @Override
        public FillRequest[] newArray(int size) {
            return new FillRequest[size];
        }
    };
}
