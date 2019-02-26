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
package android.view.contentcapture;

import static android.view.contentcapture.ContentCaptureHelper.getSanitizedString;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.view.autofill.AutofillId;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/** @hide */
@SystemApi
@TestApi
public final class ContentCaptureEvent implements Parcelable {

    private static final String TAG = ContentCaptureEvent.class.getSimpleName();

    /** @hide */
    public static final int TYPE_SESSION_FINISHED = -2;
    /** @hide */
    public static final int TYPE_SESSION_STARTED = -1;

    /**
     * Called when a node has been added to the screen and is visible to the user.
     *
     * <p>The metadata of the node is available through {@link #getViewNode()}.
     */
    public static final int TYPE_VIEW_APPEARED = 1;

    /**
     * Called when one or more nodes have been removed from the screen and is not visible to the
     * user anymore.
     *
     * <p>To get the id(s), first call {@link #getIds()} - if it returns {@code null}, then call
     * {@link #getId()}.
     */
    public static final int TYPE_VIEW_DISAPPEARED = 2;

    /**
     * Called when the text of a node has been changed.
     *
     * <p>The id of the node is available through {@link #getId()}, and the new text is
     * available through {@link #getText()}.
     */
    public static final int TYPE_VIEW_TEXT_CHANGED = 3;

    /**
     * Called before events (such as {@link #TYPE_VIEW_APPEARED} and/or
     * {@link #TYPE_VIEW_DISAPPEARED}) representing a view hierarchy are sent.
     *
     * <p><b>NOTE</b>: there is no guarantee this event will be sent. For example, it's not sent
     * if the initial view hierarchy doesn't initially have any view that's important for content
     * capture.
     */
    public static final int TYPE_VIEW_TREE_APPEARING = 4;

    /**
     * Called after events (such as {@link #TYPE_VIEW_APPEARED} and/or
     * {@link #TYPE_VIEW_DISAPPEARED}) representing a view hierarchy were sent.
     *
     * <p><b>NOTE</b>: there is no guarantee this event will be sent. For example, it's not sent
     * if the initial view hierarchy doesn't initially have any view that's important for content
     * capture.
     */
    public static final int TYPE_VIEW_TREE_APPEARED = 5;

    /**
     * Called after a call to
     * {@link ContentCaptureSession#setContentCaptureContext(ContentCaptureContext)}.
     *
     * <p>The passed context is available through {@link #getContentCaptureContext()}.
     */
    public static final int TYPE_CONTEXT_UPDATED = 6;

    /**
     * Called after the session is ready, typically after the activity resumed and the
     * initial views appeared
     */
    public static final int TYPE_SESSION_RESUMED = 7;

    /**
     * Called after the session is paused, typically after the activity paused and the
     * views disappeared.
     */
    public static final int TYPE_SESSION_PAUSED = 8;


    /** @hide */
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_VIEW_APPEARED,
            TYPE_VIEW_DISAPPEARED,
            TYPE_VIEW_TEXT_CHANGED,
            TYPE_VIEW_TREE_APPEARING,
            TYPE_VIEW_TREE_APPEARED,
            TYPE_CONTEXT_UPDATED,
            TYPE_SESSION_PAUSED,
            TYPE_SESSION_RESUMED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventType{}

    private final @NonNull String mSessionId;
    private final int mType;
    private final long mEventTime;
    private @Nullable AutofillId mId;
    private @Nullable ArrayList<AutofillId> mIds;
    private @Nullable ViewNode mNode;
    private @Nullable CharSequence mText;
    private @Nullable String mParentSessionId;
    private @Nullable ContentCaptureContext mClientContext;

    /** @hide */
    public ContentCaptureEvent(@NonNull String sessionId, int type, long eventTime) {
        mSessionId = sessionId;
        mType = type;
        mEventTime = eventTime;
    }

    /** @hide */
    public ContentCaptureEvent(@NonNull String sessionId, int type) {
        this(sessionId, type, System.currentTimeMillis());
    }

    /** @hide */
    public ContentCaptureEvent setAutofillId(@NonNull AutofillId id) {
        mId = Preconditions.checkNotNull(id);
        return this;
    }

    /** @hide */
    public ContentCaptureEvent setAutofillIds(@NonNull ArrayList<AutofillId> ids) {
        mIds = Preconditions.checkNotNull(ids);
        return this;
    }

    /**
     * Adds an autofill id to the this event, merging the single id into a list if necessary.
     *
     * @hide
     */
    public ContentCaptureEvent addAutofillId(@NonNull AutofillId id) {
        Preconditions.checkNotNull(id);
        if (mIds == null) {
            mIds = new ArrayList<>();
            if (mId == null) {
                Log.w(TAG, "addAutofillId(" + id + ") called without an initial id");
            } else {
                mIds.add(mId);
                mId = null;
            }
        }
        mIds.add(id);
        return this;
    }

    /**
     * Used by {@link #TYPE_SESSION_STARTED} and {@link #TYPE_SESSION_FINISHED}.
     *
     * @hide
     */
    public ContentCaptureEvent setParentSessionId(@NonNull String parentSessionId) {
        mParentSessionId = parentSessionId;
        return this;
    }

    /**
     * Used by {@link #TYPE_SESSION_STARTED} and {@link #TYPE_SESSION_FINISHED}.
     *
     * @hide
     */
    public ContentCaptureEvent setClientContext(@NonNull ContentCaptureContext clientContext) {
        mClientContext = clientContext;
        return this;
    }

    /** @hide */
    @NonNull
    public String getSessionId() {
        return mSessionId;
    }

    /**
     * Used by {@link #TYPE_SESSION_STARTED} and {@link #TYPE_SESSION_FINISHED}.
     *
     * @hide
     */
    @Nullable
    public String getParentSessionId() {
        return mParentSessionId;
    }

    /**
     * Gets the {@link ContentCaptureContext} set calls to
     * {@link ContentCaptureSession#setContentCaptureContext(ContentCaptureContext)}.
     *
     * <p>Only set on {@link #TYPE_CONTEXT_UPDATED} events.
     */
    @Nullable
    public ContentCaptureContext getContentCaptureContext() {
        return mClientContext;
    }

    /** @hide */
    @NonNull
    public ContentCaptureEvent setViewNode(@NonNull ViewNode node) {
        mNode = Preconditions.checkNotNull(node);
        return this;
    }

    /** @hide */
    @NonNull
    public ContentCaptureEvent setText(@Nullable CharSequence text) {
        mText = text;
        return this;
    }

    /**
     * Gets the type of the event.
     *
     * @return one of {@link #TYPE_VIEW_APPEARED}, {@link #TYPE_VIEW_DISAPPEARED},
     * {@link #TYPE_VIEW_TEXT_CHANGED}, {@link #TYPE_VIEW_TREE_APPEARING},
     * {@link #TYPE_VIEW_TREE_APPEARED}, {@link #TYPE_CONTEXT_UPDATED},
     * {@link #TYPE_SESSION_RESUMED}, or {@link #TYPE_SESSION_PAUSED}.
     */
    public @EventType int getType() {
        return mType;
    }

    /**
     * Gets when the event was generated, in millis since epoch.
     */
    public long getEventTime() {
        return mEventTime;
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
     * <p>Only set on {@link #TYPE_VIEW_DISAPPEARED} (when the event contains just one node - if
     * it contains more than one, this method returns {@code null} and the actual ids should be
     * retrived by {@link #getIds()}) and {@link #TYPE_VIEW_TEXT_CHANGED} events.
     */
    @Nullable
    public AutofillId getId() {
        return mId;
    }

    /**
     * Gets the {@link AutofillId AutofillIds} of the nodes associated with the event.
     *
     * <p>Only set on {@link #TYPE_VIEW_DISAPPEARED}, when the event contains more than one node
     * (if it contains just one node, it's returned by {@link #getId()} instead.
     */
    @Nullable
    public List<AutofillId> getIds() {
        return mIds;
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
        if (mId != null) {
            pw.print(", id="); pw.print(mId);
        }
        if (mIds != null) {
            pw.print(", ids="); pw.print(mIds);
        }
        if (mNode != null) {
            pw.print(", mNode.id="); pw.print(mNode.getAutofillId());
        }
        if (mSessionId != null) {
            pw.print(", sessionId="); pw.print(mSessionId);
        }
        if (mParentSessionId != null) {
            pw.print(", parentSessionId="); pw.print(mParentSessionId);
        }
        if (mText != null) {
            pw.print(", text="); pw.println(getSanitizedString(mText));
        }
        if (mClientContext != null) {
            pw.print(", context="); mClientContext.dump(pw); pw.println();

        }
    }

    @Override
    public String toString() {
        final StringBuilder string = new StringBuilder("ContentCaptureEvent[type=")
                .append(getTypeAsString(mType));
        string.append(", session=").append(mSessionId);
        if (mType == TYPE_SESSION_STARTED && mParentSessionId != null) {
            string.append(", parent=").append(mParentSessionId);
        }
        if (mId != null) {
            string.append(", id=").append(mId);
        }
        if (mIds != null) {
            string.append(", ids=").append(mIds);
        }
        if (mNode != null) {
            final String className = mNode.getClassName();
            if (mNode != null) {
                string.append(", class=").append(className);
            }
            string.append(", id=").append(mNode.getAutofillId());
        }
        if (mText != null) {
            string.append(", text=").append(getSanitizedString(mText));
        }
        if (mClientContext != null) {
            string.append(", context=").append(mClientContext);
        }
        return string.append(']').toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mSessionId);
        parcel.writeInt(mType);
        parcel.writeLong(mEventTime);
        parcel.writeParcelable(mId, flags);
        parcel.writeTypedList(mIds);
        ViewNode.writeToParcel(parcel, mNode, flags);
        parcel.writeCharSequence(mText);
        if (mType == TYPE_SESSION_STARTED || mType == TYPE_SESSION_FINISHED) {
            parcel.writeString(mParentSessionId);
        }
        if (mType == TYPE_SESSION_STARTED || mType == TYPE_CONTEXT_UPDATED) {
            parcel.writeParcelable(mClientContext, flags);
        }
    }

    public static final Parcelable.Creator<ContentCaptureEvent> CREATOR =
            new Parcelable.Creator<ContentCaptureEvent>() {

        @Override
        public ContentCaptureEvent createFromParcel(Parcel parcel) {
            final String sessionId = parcel.readString();
            final int type = parcel.readInt();
            final long eventTime  = parcel.readLong();
            final ContentCaptureEvent event = new ContentCaptureEvent(sessionId, type, eventTime);
            final AutofillId id = parcel.readParcelable(null);
            if (id != null) {
                event.setAutofillId(id);
            }
            final ArrayList<AutofillId> ids = parcel.createTypedArrayList(AutofillId.CREATOR);
            if (ids != null) {
                event.setAutofillIds(ids);
            }
            final ViewNode node = ViewNode.readFromParcel(parcel);
            if (node != null) {
                event.setViewNode(node);
            }
            event.setText(parcel.readCharSequence());
            if (type == TYPE_SESSION_STARTED || type == TYPE_SESSION_FINISHED) {
                event.setParentSessionId(parcel.readString());
            }
            if (type == TYPE_SESSION_STARTED || type == TYPE_CONTEXT_UPDATED) {
                event.setClientContext(parcel.readParcelable(null));
            }
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
            case TYPE_SESSION_STARTED:
                return "SESSION_STARTED";
            case TYPE_SESSION_FINISHED:
                return "SESSION_FINISHED";
            case TYPE_SESSION_RESUMED:
                return "SESSION_RESUMED";
            case TYPE_SESSION_PAUSED:
                return "SESSION_PAUSED";
            case TYPE_VIEW_APPEARED:
                return "VIEW_APPEARED";
            case TYPE_VIEW_DISAPPEARED:
                return "VIEW_DISAPPEARED";
            case TYPE_VIEW_TEXT_CHANGED:
                return "VIEW_TEXT_CHANGED";
            case TYPE_VIEW_TREE_APPEARING:
                return "VIEW_TREE_APPEARING";
            case TYPE_VIEW_TREE_APPEARED:
                return "VIEW_TREE_APPEARED";
            case TYPE_CONTEXT_UPDATED:
                return "CONTEXT_UPDATED";
            default:
                return "UKNOWN_TYPE: " + type;
        }
    }
}
