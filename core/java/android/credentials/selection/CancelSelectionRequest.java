/*
 * Copyright 2022 The Android Open Source Project
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

package android.credentials.selection;

import static android.credentials.flags.Flags.FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

/**
 * A request to cancel the ongoing selection UI matching the identifier token in this request.
 *
 * Upon receiving this request, the UI should gracefully finish itself if the given request token
 * {@link CancelSelectionRequest#getToken()} matches that of the selection UI is currently rendered
 * for. Also, the UI should display some informational cancellation message (e.g. "Request is
 * cancelled by the app") before closing when the
 * {@link CancelSelectionRequest#shouldShowCancellationExplanation()} is true.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED)
public final class CancelSelectionRequest implements Parcelable {

    /**
     * The intent extra key for the {@code CancelUiRequest} object when launching the UX
     * activities.
     *
     * @hide
     */
    @NonNull
    public static final String EXTRA_CANCEL_UI_REQUEST =
            "android.credentials.selection.extra.CANCEL_UI_REQUEST";

    @NonNull
    private final IBinder mToken;

    private final boolean mShouldShowCancellationExplanation;

    @NonNull
    private final String mPackageName;

    /**
     * Returns the request token matching the user request that should be cancelled.
     *
     * The request token for the current UI can be found from the UI launch intent, mapping to
     * {@link RequestInfo#getToken()}.
     *
     * @hide
     */
    @NonNull
    public IBinder getToken() {
        return mToken;
    }

    /** Returns the request token matching the app request that should be cancelled. */
    @NonNull
    public RequestToken getRequestToken() {
        return new RequestToken(mToken);
    }

    /**
     * Returns the app package name invoking this request, that can be used to derive display
     * metadata (e.g. "Cancelled by `App Name`").
     */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Returns whether the UI should display some informational cancellation message (e.g.
     * "Request is cancelled by the app") before closing. If false, the UI should be silently
     * cancelled.
     */
    public boolean shouldShowCancellationExplanation() {
        return mShouldShowCancellationExplanation;
    }


    /**
     * Constructs a {@link CancelSelectionRequest}.
     *
     * @param requestToken request token matching the app request that should be cancelled
     * @param shouldShowCancellationExplanation whether the UI should display some informational
     *                                          cancellation message before closing
     * @param packageName package that is invoking this request
     *
     */
    public CancelSelectionRequest(@NonNull RequestToken requestToken,
            boolean shouldShowCancellationExplanation, @NonNull String packageName) {
        mToken = requestToken.getToken();
        mShouldShowCancellationExplanation = shouldShowCancellationExplanation;
        mPackageName = packageName;
    }

    private CancelSelectionRequest(@NonNull Parcel in) {
        mToken = in.readStrongBinder();
        AnnotationValidations.validate(NonNull.class, null, mToken);
        mShouldShowCancellationExplanation = in.readBoolean();
        mPackageName = in.readString8();
        AnnotationValidations.validate(NonNull.class, null, mPackageName);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mToken);
        dest.writeBoolean(mShouldShowCancellationExplanation);
        dest.writeString8(mPackageName);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<CancelSelectionRequest> CREATOR = new Creator<>() {
        @Override
        public CancelSelectionRequest createFromParcel(@NonNull Parcel in) {
            return new CancelSelectionRequest(in);
        }

        @Override
        public CancelSelectionRequest[] newArray(int size) {
            return new CancelSelectionRequest[size];
        }
    };
}
