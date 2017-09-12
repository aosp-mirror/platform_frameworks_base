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
import android.annotation.Nullable;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.autofill.AutofillManager;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Describes what happened after the last
 * {@link AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal, FillCallback)}
 * call.
 *
 * <p>This history is typically used to keep track of previous user actions to optimize further
 * requests. For example, the service might return email addresses in alphabetical order by
 * default, but change that order based on the address the user picked on previous requests.
 *
 * <p>The history is not persisted over reboots, and it's cleared every time the service
 * replies to a
 * {@link AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal, FillCallback)}
 * by calling {@link FillCallback#onSuccess(FillResponse)} or
 * {@link FillCallback#onFailure(CharSequence)} (if the service doesn't call any of these methods,
 * the history will clear out after some pre-defined time).
 */
public final class FillEventHistory implements Parcelable {
    /**
     * Not in parcel. The UID of the {@link AutofillService} that created the {@link FillResponse}.
     */
    private final int mServiceUid;

    /**
     * Not in parcel. The ID of the autofill session that created the {@link FillResponse}.
     */
    private final int mSessionId;

    @Nullable private final Bundle mClientState;
    @Nullable List<Event> mEvents;

    /**
     * Gets the UID of the {@link AutofillService} that created the {@link FillResponse}.
     *
     * @return The UID of the {@link AutofillService}
     *
     * @hide
     */
    public int getServiceUid() {
        return mServiceUid;
    }

    /** @hide */
    public int getSessionId() {
        return mSessionId;
    }

    /**
     * Returns the client state set in the previous {@link FillResponse}.
     *
     * <p><b>Note: </b>the state is associated with the app that was autofilled in the previous
     * {@link AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal, FillCallback)}
     * , which is not necessary the same app being autofilled now.
     */
    @Nullable public Bundle getClientState() {
        return mClientState;
    }

    /**
     * Returns the events occurred after the latest call to
     * {@link FillCallback#onSuccess(FillResponse)}.
     *
     * @return The list of events or {@code null} if non occurred.
     */
    @Nullable public List<Event> getEvents() {
        return mEvents;
    }

    /**
     * @hide
     */
    public void addEvent(Event event) {
        if (mEvents == null) {
            mEvents = new ArrayList<>(1);
        }
        mEvents.add(event);
    }

    /**
     * @hide
     */
    public FillEventHistory(int serviceUid, int sessionId, @Nullable Bundle clientState) {
        mClientState = clientState;
        mServiceUid = serviceUid;
        mSessionId = sessionId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBundle(mClientState);

        if (mEvents == null) {
            dest.writeInt(0);
        } else {
            dest.writeInt(mEvents.size());

            int numEvents = mEvents.size();
            for (int i = 0; i < numEvents; i++) {
                Event event = mEvents.get(i);
                dest.writeInt(event.getType());
                dest.writeString(event.getDatasetId());
            }
        }
    }

    /**
     * Description of an event that occured after the latest call to
     * {@link FillCallback#onSuccess(FillResponse)}.
     */
    public static final class Event {
        /**
         * A dataset was selected. The dataset selected can be read from {@link #getDatasetId()}.
         *
         * <p><b>Note: </b>on Android {@link android.os.Build.VERSION_CODES#O}, this event was also
         * incorrectly reported after a
         * {@link Dataset.Builder#setAuthentication(IntentSender) dataset authentication} was
         * selected and the service returned a dataset in the
         * {@link AutofillManager#EXTRA_AUTHENTICATION_RESULT} of the activity launched from that
         * {@link IntentSender}. This behavior was fixed on Android
         * {@link android.os.Build.VERSION_CODES#O_MR1}.
         */
        public static final int TYPE_DATASET_SELECTED = 0;

        /**
         * A {@link Dataset.Builder#setAuthentication(IntentSender) dataset authentication} was
         * selected. The dataset authenticated can be read from {@link #getDatasetId()}.
         */
        public static final int TYPE_DATASET_AUTHENTICATION_SELECTED = 1;

        /**
         * A {@link FillResponse.Builder#setAuthentication(android.view.autofill.AutofillId[],
         * IntentSender, android.widget.RemoteViews) fill response authentication} was selected.
         */
        public static final int TYPE_AUTHENTICATION_SELECTED = 2;

        /** A save UI was shown. */
        public static final int TYPE_SAVE_SHOWN = 3;

        /** @hide */
        @IntDef(
                value = {TYPE_DATASET_SELECTED,
                        TYPE_DATASET_AUTHENTICATION_SELECTED,
                        TYPE_AUTHENTICATION_SELECTED,
                        TYPE_SAVE_SHOWN})
        @Retention(RetentionPolicy.SOURCE)
        @interface EventIds{}

        @EventIds private final int mEventType;
        @Nullable private final String mDatasetId;

        /**
         * Returns the type of the event.
         *
         * @return The type of the event
         */
        public int getType() {
            return mEventType;
        }

        /**
         * Returns the id of dataset the id was on.
         *
         * @return The id of dataset, or {@code null} the event is not associated with a dataset.
         */
        @Nullable public String getDatasetId() {
            return mDatasetId;
        }

        /**
         * Creates a new event.
         *
         * @param eventType The type of the event
         * @param datasetId The dataset the event was on, or {@code null} if the event was on the
         *                  whole response.
         *
         * @hide
         */
        public Event(int eventType, String datasetId) {
            mEventType = Preconditions.checkArgumentInRange(eventType, 0, TYPE_SAVE_SHOWN,
                    "eventType");
            mDatasetId = datasetId;
        }
    }

    public static final Parcelable.Creator<FillEventHistory> CREATOR =
            new Parcelable.Creator<FillEventHistory>() {
                @Override
                public FillEventHistory createFromParcel(Parcel parcel) {
                    FillEventHistory selection = new FillEventHistory(0, 0, parcel.readBundle());

                    int numEvents = parcel.readInt();
                    for (int i = 0; i < numEvents; i++) {
                        selection.addEvent(new Event(parcel.readInt(), parcel.readString()));
                    }

                    return selection;
                }

                @Override
                public FillEventHistory[] newArray(int size) {
                    return new FillEventHistory[size];
                }
            };
}
