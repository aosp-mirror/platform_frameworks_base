/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import static android.app.servertransaction.ActivityLifecycleItem.ON_RESUME;
import static android.app.servertransaction.ActivityLifecycleItem.UNDEFINED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Trace;

import com.android.internal.content.ReferrerIntent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * New intent message.
 *
 * @hide
 */
public class NewIntentItem extends ActivityTransactionItem {

    // TODO(b/170729553): Mark this with @NonNull and final once @UnsupportedAppUsage removed.
    //  We cannot do it now to avoid app compatibility regression.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private List<ReferrerIntent> mIntents;

    private final boolean mResume;

    public NewIntentItem(@NonNull IBinder activityToken,
            @NonNull List<ReferrerIntent> intents, boolean resume) {
        super(activityToken);
        mIntents = new ArrayList<>(intents);
        mResume = resume;
    }

    @Override
    public int getPostExecutionState() {
        return mResume ? ON_RESUME : UNDEFINED;
    }

    @Override
    public void execute(@NonNull ClientTransactionHandler client, @NonNull ActivityClientRecord r,
            @NonNull PendingTransactionActions pendingActions) {
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "activityNewIntent");
        client.handleNewIntent(r, mIntents);
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    // Parcelable implementation

    /** Writes to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeBoolean(mResume);
        dest.writeTypedList(mIntents, flags);
    }

    /** Reads from Parcel. */
    private NewIntentItem(@NonNull Parcel in) {
        super(in);
        mResume = in.readBoolean();
        // TODO(b/170729553): Wrap with requireNonNull once @UnsupportedAppUsage removed.
        mIntents = in.createTypedArrayList(ReferrerIntent.CREATOR);
    }

    public static final @NonNull Parcelable.Creator<NewIntentItem> CREATOR =
            new Parcelable.Creator<>() {
                public NewIntentItem createFromParcel(@NonNull Parcel in) {
                    return new NewIntentItem(in);
                }

                public NewIntentItem[] newArray(int size) {
                    return new NewIntentItem[size];
                }
            };

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!super.equals(o)) {
            return false;
        }
        final NewIntentItem other = (NewIntentItem) o;
        return mResume == other.mResume && Objects.equals(mIntents, other.mIntents);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + super.hashCode();
        result = 31 * result + (mResume ? 1 : 0);
        result = 31 * result + mIntents.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "NewIntentItem{" + super.toString()
                + ",intents=" + mIntents
                + ",resume=" + mResume + "}";
    }
}
