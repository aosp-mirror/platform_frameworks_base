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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.slice.Slice;
import android.content.Context;
import android.content.Intent;
import android.credentials.GetCredentialRequest;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * An authentication entry.
 *
 * Applicable only for
 * {@link android.credentials.CredentialManager#getCredential(Context, GetCredentialRequest,
 * CancellationSignal, Executor, OutcomeReceiver)} flow, authentication entries are a special type
 * of entries that require the user to unlock the given provider before its credential options
 * can be fully rendered.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED)
public final class AuthenticationEntry implements Parcelable {
    @NonNull
    private final String mKey;
    @NonNull
    private final String mSubkey;
    @NonNull
    private final @Status int mStatus;
    @Nullable
    private Intent mFrameworkExtrasIntent;
    @NonNull
    private final Slice mSlice;

    /** @hide **/
    @IntDef(prefix = {"STATUS_"}, value = {
            STATUS_LOCKED,
            STATUS_UNLOCKED_BUT_EMPTY_LESS_RECENT,
            STATUS_UNLOCKED_BUT_EMPTY_MOST_RECENT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Status {
    }

    /**
     * This entry is still locked, as initially supplied by the provider.
     *
     * This entry should be rendered in a way to signal that it is still locked, and when chosen
     * will lead to an unlock challenge (e.g. draw a trailing lock icon on this entry).
     */
    public static final int STATUS_LOCKED = 0;
    /**
     * This entry was unlocked but didn't contain any credential. Meanwhile, "less recent" means
     * there is another such entry that was unlocked more recently.
     *
     * This entry should be rendered in a way to signal that it was unlocked but turns out to
     * contain no credential that can be used, and as a result, it should be unclickable.
     */
    public static final int STATUS_UNLOCKED_BUT_EMPTY_LESS_RECENT = 1;
    /**
     * This is the most recent entry that was unlocked but didn't contain any credential.
     *
     * There will be at most one authentication entry with this status.
     *
     * This entry should be rendered in a way to signal that it was unlocked but turns out to
     * contain no credential that can be used, and as a result, it should be unclickable.
     *
     * If this was the last clickable option prior to unlocking, then the UI should display an
     * information that all options are exhausted then gracefully finish itself.
     */
    public static final int STATUS_UNLOCKED_BUT_EMPTY_MOST_RECENT = 2;

    private AuthenticationEntry(@NonNull Parcel in) {
        mKey = in.readString8();
        mSubkey = in.readString8();
        mStatus = in.readInt();
        mSlice = in.readTypedObject(Slice.CREATOR);
        mFrameworkExtrasIntent = in.readTypedObject(Intent.CREATOR);

        AnnotationValidations.validate(NonNull.class, null, mKey);
        AnnotationValidations.validate(NonNull.class, null, mSubkey);
        AnnotationValidations.validate(NonNull.class, null, mSlice);
    }

    /**
     * Constructor to be used for an entry that requires a pending intent to be invoked
     * when clicked.
     *
     * @param key    the identifier of this entry that's unique within the context of the given
     *               CredentialManager request. This is used when constructing the
     *               {@link android.credentials.selection.UserSelectionResult#UserSelectionResult(
     *                String providerId, String entryKey, String entrySubkey,
     *                ProviderPendingIntentResponse providerPendingIntentResponse)}
     *
     * @param subkey the sub-identifier of this entry that's unique within the context of the
     *               {@code key}. This is used when constructing the
     *               {@link android.credentials.selection.UserSelectionResult#UserSelectionResult(
     *                String providerId, String entryKey, String entrySubkey,
     *                ProviderPendingIntentResponse providerPendingIntentResponse)}
     * @param intent the intent containing extra data that has to be filled in when launching this
     *               entry's provider PendingIntent
     * @param slice  the Slice to be displayed
     * @param status the entry status, depending on which the entry should be rendered differently
     */
    public AuthenticationEntry(@NonNull String key, @NonNull String subkey, @NonNull Slice slice,
            @Status int status, @NonNull Intent intent) {
        mKey = key;
        mSubkey = subkey;
        mSlice = slice;
        mStatus = status;
        mFrameworkExtrasIntent = intent;
    }

    /**
     * Returns the identifier of this entry that's unique within the context of the given
     * CredentialManager request.
     */
    @NonNull
    public String getKey() {
        return mKey;
    }

    /**
     * Returns the sub-identifier of this entry that's unique within the context of the {@code key}.
     */
    @NonNull
    public String getSubkey() {
        return mSubkey;
    }

    /** Returns the Slice to be rendered. */
    @NonNull
    public Slice getSlice() {
        return mSlice;
    }

    /** Returns the entry status, depending on which the entry should be rendered differently. */
    @NonNull
    @Status
    public int getStatus() {
        return mStatus;
    }

    /**
     * Returns the intent containing extra data that has to be filled in when launching this
     * entry's provider PendingIntent.
     *
     * If null, the provider PendingIntent can be launched without any fill in intent.
     */
    @Nullable
    @SuppressLint("IntentBuilderName") // Not building a new intent.
    public Intent getFrameworkExtrasIntent() {
        return mFrameworkExtrasIntent;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mKey);
        dest.writeString8(mSubkey);
        dest.writeInt(mStatus);
        dest.writeTypedObject(mSlice, flags);
        dest.writeTypedObject(mFrameworkExtrasIntent, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<AuthenticationEntry> CREATOR = new Creator<>() {
        @Override
        public AuthenticationEntry createFromParcel(@NonNull Parcel in) {
            return new AuthenticationEntry(in);
        }

        @Override
        public AuthenticationEntry[] newArray(int size) {
            return new AuthenticationEntry[size];
        }
    };
}
