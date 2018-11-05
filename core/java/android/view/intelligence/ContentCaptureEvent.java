/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.view.intelligence;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.autofill.AutofillId;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

// TODO(b/111276913): add javadocs / implement Parcelable / implement
/** @hide */
@SystemApi
public final class ContentCaptureEvent implements Parcelable {

    /**
     * Called when the activity is started.
     */
    public static final int TYPE_ACTIVITY_STARTED  = 1;

    /**
     * Called when the activity is resumed.
     */
    public static final int TYPE_ACTIVITY_RESUMED = 2;

    /**
     * Called when the activity is paused.
     */
    public static final int TYPE_ACTIVITY_PAUSED = 3;

    /**
     * Called when the activity is stopped.
     */
    public static final int TYPE_ACTIVITY_STOPPED  = 4;

    /**
     * Called when a node has been added to the screen and is visible to the user.
     *
     * <p>The metadata of the node is available through {@link #getViewNode()}.
     */
    public static final int TYPE_VIEW_ADDED = 5;

    /**
     * Called when a node has been removed from the screen and is not visible to the user anymore.
     *
     * <p>The id of the node is available through {@link #getId()}.
     */
    public static final int TYPE_VIEW_REMOVED = 6;

    /**
     * Called when the text of a node has been changed.
     *
     * <p>The id of the node is available through {@link #getId()}, and the new text is
     * available through {@link #getText()}.
     */
    public static final int TYPE_VIEW_TEXT_CHANGED = 7;

    // TODO(b/111276913): add event to indicate when FLAG_SECURE was changed?

    /** @hide */
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_ACTIVITY_STARTED,
            TYPE_ACTIVITY_PAUSED,
            TYPE_ACTIVITY_RESUMED,
            TYPE_ACTIVITY_STOPPED,
            TYPE_VIEW_ADDED,
            TYPE_VIEW_REMOVED,
            TYPE_VIEW_TEXT_CHANGED
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface EventType{}

    /** @hide */
    ContentCaptureEvent() {
    }

    /**
     * Gets the type of the event.
     *
     * @return one of {@link #TYPE_ACTIVITY_STARTED}, {@link #TYPE_ACTIVITY_RESUMED},
     * {@link #TYPE_ACTIVITY_PAUSED}, {@link #TYPE_ACTIVITY_STOPPED},
     * {@link #TYPE_VIEW_ADDED}, {@link #TYPE_VIEW_REMOVED}, or {@link #TYPE_VIEW_TEXT_CHANGED}.
     */
    public @EventType int getType() {
        return 42;
    }

    /**
     * Gets when the event was generated, in ms.
     */
    public long getEventTime() {
        return 48151623;
    }

    /**
     * Gets optional flags associated with the event.
     *
     * @return either {@code 0} or
     * {@link android.view.intelligence.IntelligenceManager#FLAG_USER_INPUT}.
     */
    public int getFlags() {
        return 0;
    }

    /**
     * Gets the whole metadata of the node associated with the event.
     *
     * <p>Only set on {@link #TYPE_VIEW_ADDED} events.
     */
    @Nullable
    public ViewNode getViewNode() {
        return null;
    }

    /**
     * Gets the {@link AutofillId} of the node associated with the event.
     *
     * <p>Only set on {@link #TYPE_VIEW_REMOVED} and {@link #TYPE_VIEW_TEXT_CHANGED} events.
     */
    @Nullable
    public AutofillId getId() {
        return null;
    }

    /**
     * Gets the current text of the node associated with the event.
     *
     * <p>Only set on {@link #TYPE_VIEW_TEXT_CHANGED} events.
     */
    @Nullable
    public CharSequence getText() {
        return null;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        // TODO(b/111276913): implement
    }

    public static final Parcelable.Creator<ContentCaptureEvent> CREATOR =
            new Parcelable.Creator<ContentCaptureEvent>() {

        @Override
        public ContentCaptureEvent createFromParcel(Parcel parcel) {
            // TODO(b/111276913): implement
            return null;
        }

        @Override
        public ContentCaptureEvent[] newArray(int size) {
            return new ContentCaptureEvent[size];
        }
    };
}
