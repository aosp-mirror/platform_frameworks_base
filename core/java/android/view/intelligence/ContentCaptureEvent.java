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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.view.autofill.AutofillId;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

// TODO(b/111276913): add javadocs / implement Parcelable / implement
/** @hide */
@SystemApi
public final class ContentCaptureEvent implements Parcelable {

    /** @hide */
    public static final int TYPE_ACTIVITY_DESTROYED = -2;
    /** @hide */
    public static final int TYPE_ACTIVITY_CREATED = -1;

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
    public static final int TYPE_VIEW_APPEARED = 5;

    /**
     * Called when a node has been removed from the screen and is not visible to the user anymore.
     *
     * <p>The id of the node is available through {@link #getId()}.
     */
    public static final int TYPE_VIEW_DISAPPEARED = 6;

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
            TYPE_VIEW_APPEARED,
            TYPE_VIEW_DISAPPEARED,
            TYPE_VIEW_TEXT_CHANGED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventType{}

    private final int mType;
    private final long mEventTime;
    private final int mFlags;
    private @Nullable AutofillId mId;
    private @Nullable ViewNode mNode;
    private @Nullable CharSequence mText;

    /** @hide */
    public ContentCaptureEvent(int type, long eventTime, int flags) {
        mType = type;
        mEventTime = eventTime;
        mFlags = flags;
    }


    /** @hide */
    public ContentCaptureEvent(int type, int flags) {
        this(type, SystemClock.uptimeMillis(), flags);
    }

    /** @hide */
    public ContentCaptureEvent(int type) {
        this(type, /* flags= */ 0);
    }

    /** @hide */
    public ContentCaptureEvent setAutofillId(@NonNull AutofillId id) {
        mId = Preconditions.checkNotNull(id);
        return this;
    }

    /** @hide */
    public ContentCaptureEvent setViewNode(@NonNull ViewNode node) {
        mNode = Preconditions.checkNotNull(node);
        return this;
    }

    /** @hide */
    public ContentCaptureEvent setText(@Nullable CharSequence text) {
        mText = text;
        return this;
    }

    /**
     * Gets the type of the event.
     *
     * @return one of {@link #TYPE_ACTIVITY_STARTED}, {@link #TYPE_ACTIVITY_RESUMED},
     * {@link #TYPE_ACTIVITY_PAUSED}, {@link #TYPE_ACTIVITY_STOPPED},
     * {@link #TYPE_VIEW_APPEARED}, {@link #TYPE_VIEW_DISAPPEARED},
     * or {@link #TYPE_VIEW_TEXT_CHANGED}.
     */
    public @EventType int getType() {
        return mType;
    }

    /**
     * Gets when the event was generated, in ms.
     */
    public long getEventTime() {
        return mEventTime;
    }

    /**
     * Gets optional flags associated with the event.
     *
     * @return either {@code 0} or
     * {@link android.view.intelligence.IntelligenceManager#FLAG_USER_INPUT}.
     */
    public int getFlags() {
        return mFlags;
    }

    /**
     * Gets the whole metadata of the node associated with the event.
     *
     * <p>Only set on {@link #TYPE_VIEW_APPEARED} events.
     */
    @Nullable
    public ViewNode getViewNode() {
        return mNode;
    }

    /**
     * Gets the {@link AutofillId} of the node associated with the event.
     *
     * <p>Only set on {@link #TYPE_VIEW_DISAPPEARED} and {@link #TYPE_VIEW_TEXT_CHANGED} events.
     */
    @Nullable
    public AutofillId getId() {
        return mId;
    }

    /**
     * Gets the current text of the node associated with the event.
     *
     * <p>Only set on {@link #TYPE_VIEW_TEXT_CHANGED} events.
     */
    @Nullable
    public CharSequence getText() {
        return mText;
    }

    /** @hide */
    public void dump(@NonNull PrintWriter pw) {
        pw.print("type="); pw.print(getTypeAsString(mType));
        pw.print(", time="); pw.print(mEventTime);
        if (mFlags > 0) {
            pw.print(", flags="); pw.print(mFlags);
        }
        if (mId != null) {
            pw.print(", id="); pw.print(mId);
        }
        if (mNode != null) {
            pw.print(", id="); pw.print(mNode.getAutofillId());
        }
    }

    @Override
    public String toString() {
        final StringBuilder string = new StringBuilder("ContentCaptureEvent[type=")
                .append(getTypeAsString(mType));
        if (mFlags > 0) {
            string.append(", flags=").append(mFlags);
        }
        if (mId != null) {
            string.append(", id=").append(mId);
        }
        if (mNode != null) {
            final String className = mNode.getClassName();
            if (mNode != null) {
                string.append(", class=").append(className);
            }
            string.append(", id=").append(mNode.getAutofillId());
        }
        return string.append(']').toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mType);
        parcel.writeLong(mEventTime);
        parcel.writeInt(mFlags);
        parcel.writeParcelable(mId, flags);
        ViewNode.writeToParcel(parcel, mNode, flags);
        parcel.writeCharSequence(mText);
    }

    public static final Parcelable.Creator<ContentCaptureEvent> CREATOR =
            new Parcelable.Creator<ContentCaptureEvent>() {

        @Override
        public ContentCaptureEvent createFromParcel(Parcel parcel) {
            final int type = parcel.readInt();
            final long eventTime  = parcel.readLong();
            final int flags = parcel.readInt();
            final ContentCaptureEvent event = new ContentCaptureEvent(type, eventTime, flags);
            final AutofillId id = parcel.readParcelable(null);
            if (id != null) {
                event.setAutofillId(id);
            }
            final ViewNode node = ViewNode.readFromParcel(parcel);
            if (node != null) {
                event.setViewNode(node);
            }
            event.setText(parcel.readCharSequence());
            return event;
        }

        @Override
        public ContentCaptureEvent[] newArray(int size) {
            return new ContentCaptureEvent[size];
        }
    };

    /** @hide */
    public static String getTypeAsString(@EventType int type) {
        switch (type) {
            case TYPE_ACTIVITY_STARTED:
                return "ACTIVITY_STARTED";
            case TYPE_ACTIVITY_RESUMED:
                return "ACTIVITY_RESUMED";
            case TYPE_ACTIVITY_PAUSED:
                return "ACTIVITY_PAUSED";
            case TYPE_ACTIVITY_STOPPED:
                return "ACTIVITY_STOPPED";
            case TYPE_VIEW_APPEARED:
                return "VIEW_APPEARED";
            case TYPE_VIEW_DISAPPEARED:
                return "VIEW_DISAPPEARED";
            case TYPE_VIEW_TEXT_CHANGED:
                return "VIEW_TEXT_CHANGED";
            default:
                return "UKNOWN_TYPE: " + type;
        }
    }
}
