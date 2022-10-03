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
import static android.view.contentcapture.ContentCaptureManager.DEBUG;
import static android.view.contentcapture.ContentCaptureManager.NO_SESSION_ID;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableString;
import android.util.Log;
import android.view.autofill.AutofillId;
import android.view.inputmethod.BaseInputConnection;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** @hide */
@SystemApi
public final class ContentCaptureEvent implements Parcelable {

    private static final String TAG = ContentCaptureEvent.class.getSimpleName();

    /** @hide */
    public static final int TYPE_SESSION_FINISHED = -2;
    /** @hide */
    public static final int TYPE_SESSION_STARTED = -1;

    /**
     * Called when a node has been added to the screen and is visible to the user.
     *
     * On API level 33, this event may be re-sent with additional information if a view's children
     * have changed, e.g. scrolling Views inside of a ListView. This information will be stored in
     * the extras Bundle associated with the event's ViewNode. Within the Bundle, the
     * "android.view.ViewStructure.extra.ACTIVE_CHILDREN_IDS" key may be used to get a list of
     * Autofill IDs of active child views, and the
     * "android.view.ViewStructure.extra.FIRST_ACTIVE_POSITION" key may be used to get the 0-based
     * position of the first active child view in the list relative to the positions of child views
     * in the container View's dataset.
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

    /**
     * Called when the view's insets are changed. The new insets associated with the
     * event may then be retrieved by calling {@link #getInsets()}
     */
    public static final int TYPE_VIEW_INSETS_CHANGED = 9;

    /**
     * Called before {@link #TYPE_VIEW_TREE_APPEARING}, or after the size of the window containing
     * the views changed.
     */
    public static final int TYPE_WINDOW_BOUNDS_CHANGED = 10;

    /** @hide */
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_VIEW_APPEARED,
            TYPE_VIEW_DISAPPEARED,
            TYPE_VIEW_TEXT_CHANGED,
            TYPE_VIEW_TREE_APPEARING,
            TYPE_VIEW_TREE_APPEARED,
            TYPE_CONTEXT_UPDATED,
            TYPE_SESSION_PAUSED,
            TYPE_SESSION_RESUMED,
            TYPE_VIEW_INSETS_CHANGED,
            TYPE_WINDOW_BOUNDS_CHANGED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventType{}

    /** @hide */
    public static final int MAX_INVALID_VALUE = -1;

    private final int mSessionId;
    private final int mType;
    private final long mEventTime;
    private @Nullable AutofillId mId;
    private @Nullable ArrayList<AutofillId> mIds;
    private @Nullable ViewNode mNode;
    private @Nullable CharSequence mText;
    private int mParentSessionId = NO_SESSION_ID;
    private @Nullable ContentCaptureContext mClientContext;
    private @Nullable Insets mInsets;
    private @Nullable Rect mBounds;

    private int mComposingStart = MAX_INVALID_VALUE;
    private int mComposingEnd = MAX_INVALID_VALUE;
    private int mSelectionStartIndex = MAX_INVALID_VALUE;
    private int mSelectionEndIndex = MAX_INVALID_VALUE;

    /** Only used in the main Content Capture session, no need to parcel */
    private boolean mTextHasComposingSpan;

    /** @hide */
    public ContentCaptureEvent(int sessionId, int type, long eventTime) {
        mSessionId = sessionId;
        mType = type;
        mEventTime = eventTime;
    }

    /** @hide */
    public ContentCaptureEvent(int sessionId, int type) {
        this(sessionId, type, System.currentTimeMillis());
    }

    /** @hide */
    public ContentCaptureEvent setAutofillId(@NonNull AutofillId id) {
        mId = Objects.requireNonNull(id);
        return this;
    }

    /** @hide */
    public ContentCaptureEvent setAutofillIds(@NonNull ArrayList<AutofillId> ids) {
        mIds = Objects.requireNonNull(ids);
        return this;
    }

    /**
     * Adds an autofill id to the this event, merging the single id into a list if necessary.
     *
     * @hide
     */
    public ContentCaptureEvent addAutofillId(@NonNull AutofillId id) {
        Objects.requireNonNull(id);
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
    public ContentCaptureEvent setParentSessionId(int parentSessionId) {
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
    public int getSessionId() {
        return mSessionId;
    }

    /**
     * Used by {@link #TYPE_SESSION_STARTED} and {@link #TYPE_SESSION_FINISHED}.
     *
     * @hide
     */
    @Nullable
    public int getParentSessionId() {
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
        mNode = Objects.requireNonNull(node);
        return this;
    }

    /** @hide */
    @NonNull
    public ContentCaptureEvent setText(@Nullable CharSequence text) {
        mText = text;
        return this;
    }

    /** @hide */
    @NonNull
    public ContentCaptureEvent setComposingIndex(int start, int end) {
        mComposingStart = start;
        mComposingEnd = end;
        return this;
    }

    /** @hide */
    @NonNull
    public boolean hasComposingSpan() {
        return mComposingStart > MAX_INVALID_VALUE;
    }

    /** @hide */
    @NonNull
    public ContentCaptureEvent setSelectionIndex(int start, int end) {
        mSelectionStartIndex = start;
        mSelectionEndIndex = end;
        return this;
    }

    boolean hasSameComposingSpan(@NonNull ContentCaptureEvent other) {
        return mComposingStart == other.mComposingStart && mComposingEnd == other.mComposingEnd;
    }

    boolean hasSameSelectionSpan(@NonNull ContentCaptureEvent other) {
        return mSelectionStartIndex == other.mSelectionStartIndex
                && mSelectionEndIndex == other.mSelectionEndIndex;
    }

    private int getComposingStart() {
        return mComposingStart;
    }

    private int getComposingEnd() {
        return mComposingEnd;
    }

    private int getSelectionStart() {
        return mSelectionStartIndex;
    }

    private int getSelectionEnd() {
        return mSelectionEndIndex;
    }

    private void restoreComposingSpan() {
        if (mComposingStart <= MAX_INVALID_VALUE
                || mComposingEnd <= MAX_INVALID_VALUE) {
            return;
        }
        if (mText instanceof Spannable) {
            BaseInputConnection.setComposingSpans((Spannable) mText, mComposingStart,
                    mComposingEnd);
        } else {
            Log.w(TAG, "Text is not a Spannable.");
        }
    }

    private void restoreSelectionSpans() {
        if (mSelectionStartIndex <= MAX_INVALID_VALUE
                || mSelectionEndIndex <= MAX_INVALID_VALUE) {
            return;
        }

        if (mText instanceof SpannableString) {
            SpannableString ss = (SpannableString) mText;
            ss.setSpan(Selection.SELECTION_START, mSelectionStartIndex, mSelectionStartIndex, 0);
            ss.setSpan(Selection.SELECTION_END, mSelectionEndIndex, mSelectionEndIndex, 0);
        } else {
            Log.w(TAG, "Text is not a SpannableString.");
        }
    }

    /** @hide */
    @NonNull
    public ContentCaptureEvent setInsets(@NonNull Insets insets) {
        mInsets = insets;
        return this;
    }

    /** @hide */
    @NonNull
    public ContentCaptureEvent setBounds(@NonNull Rect bounds) {
        mBounds = bounds;
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

    /**
     * Gets the rectangle of the insets associated with the event. Valid insets will only be
     * returned if the type of the event is {@link #TYPE_VIEW_INSETS_CHANGED}, otherwise they
     * will be null.
     */
    @Nullable
    public Insets getInsets() {
        return mInsets;
    }

    /**
     * Gets the {@link Rect} bounds of the window associated with the event. Valid bounds will only
     * be returned if the type of the event is {@link #TYPE_WINDOW_BOUNDS_CHANGED}, otherwise they
     * will be null.
     */
    @Nullable
    public Rect getBounds() {
        return mBounds;
    }

    /**
     * Merges event of the same type, either {@link #TYPE_VIEW_TEXT_CHANGED}
     * or {@link #TYPE_VIEW_DISAPPEARED}.
     *
     * @hide
     */
    public void mergeEvent(@NonNull ContentCaptureEvent event) {
        Objects.requireNonNull(event);
        final int eventType = event.getType();
        if (mType != eventType) {
            Log.e(TAG, "mergeEvent(" + getTypeAsString(eventType) + ") cannot be merged "
                    + "with different eventType=" + getTypeAsString(mType));
            return;
        }

        if (eventType == TYPE_VIEW_DISAPPEARED) {
            final List<AutofillId> ids = event.getIds();
            final AutofillId id = event.getId();
            if (ids != null) {
                if (id != null) {
                    Log.w(TAG, "got TYPE_VIEW_DISAPPEARED event with both id and ids: " + event);
                }
                for (int i = 0; i < ids.size(); i++) {
                    addAutofillId(ids.get(i));
                }
                return;
            }
            if (id != null) {
                addAutofillId(id);
                return;
            }
            throw new IllegalArgumentException("mergeEvent(): got "
                    + "TYPE_VIEW_DISAPPEARED event with neither id or ids: " + event);
        } else if (eventType == TYPE_VIEW_TEXT_CHANGED) {
            setText(event.getText());
            setComposingIndex(event.getComposingStart(), event.getComposingEnd());
            setSelectionIndex(event.getSelectionStart(), event.getSelectionEnd());
        } else {
            Log.e(TAG, "mergeEvent(" + getTypeAsString(eventType)
                    + ") does not support this event type.");
        }
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
        if (mSessionId != NO_SESSION_ID) {
            pw.print(", sessionId="); pw.print(mSessionId);
        }
        if (mParentSessionId != NO_SESSION_ID) {
            pw.print(", parentSessionId="); pw.print(mParentSessionId);
        }
        if (mText != null) {
            pw.print(", text="); pw.println(getSanitizedString(mText));
        }
        if (mClientContext != null) {
            pw.print(", context="); mClientContext.dump(pw); pw.println();
        }
        if (mInsets != null) {
            pw.print(", insets="); pw.println(mInsets);
        }
        if (mBounds != null) {
            pw.print(", bounds="); pw.println(mBounds);
        }
        if (mComposingStart > MAX_INVALID_VALUE) {
            pw.print(", composing("); pw.print(mComposingStart);
            pw.print(", "); pw.print(mComposingEnd); pw.print(")");
        }
        if (mSelectionStartIndex > MAX_INVALID_VALUE) {
            pw.print(", selection("); pw.print(mSelectionStartIndex);
            pw.print(", "); pw.print(mSelectionEndIndex); pw.print(")");
        }
    }

    @NonNull
    @Override
    public String toString() {
        final StringBuilder string = new StringBuilder("ContentCaptureEvent[type=")
                .append(getTypeAsString(mType));
        string.append(", session=").append(mSessionId);
        if (mType == TYPE_SESSION_STARTED && mParentSessionId != NO_SESSION_ID) {
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
            string.append(", class=").append(className);
            string.append(", id=").append(mNode.getAutofillId());
            if (mNode.getText() != null) {
                string.append(", text=")
                        .append(DEBUG ? mNode.getText() : getSanitizedString(mNode.getText()));
            }
        }
        if (mText != null) {
            string.append(", text=")
                    .append(DEBUG ? mText : getSanitizedString(mText));
        }
        if (mClientContext != null) {
            string.append(", context=").append(mClientContext);
        }
        if (mInsets != null) {
            string.append(", insets=").append(mInsets);
        }
        if (mBounds != null) {
            string.append(", bounds=").append(mBounds);
        }
        if (mComposingStart > MAX_INVALID_VALUE) {
            string.append(", composing=[")
                    .append(mComposingStart).append(",").append(mComposingEnd).append("]");
        }
        if (mSelectionStartIndex > MAX_INVALID_VALUE) {
            string.append(", selection=[")
                    .append(mSelectionStartIndex).append(",")
                    .append(mSelectionEndIndex).append("]");
        }
        return string.append(']').toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mSessionId);
        parcel.writeInt(mType);
        parcel.writeLong(mEventTime);
        parcel.writeParcelable(mId, flags);
        parcel.writeTypedList(mIds);
        ViewNode.writeToParcel(parcel, mNode, flags);
        parcel.writeCharSequence(mText);
        if (mType == TYPE_SESSION_STARTED || mType == TYPE_SESSION_FINISHED) {
            parcel.writeInt(mParentSessionId);
        }
        if (mType == TYPE_SESSION_STARTED || mType == TYPE_CONTEXT_UPDATED) {
            parcel.writeParcelable(mClientContext, flags);
        }
        if (mType == TYPE_VIEW_INSETS_CHANGED) {
            parcel.writeParcelable(mInsets, flags);
        }
        if (mType == TYPE_WINDOW_BOUNDS_CHANGED) {
            parcel.writeParcelable(mBounds, flags);
        }
        if (mType == TYPE_VIEW_TEXT_CHANGED) {
            parcel.writeInt(mComposingStart);
            parcel.writeInt(mComposingEnd);
            parcel.writeInt(mSelectionStartIndex);
            parcel.writeInt(mSelectionEndIndex);
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ContentCaptureEvent> CREATOR =
            new Parcelable.Creator<ContentCaptureEvent>() {

        @Override
        @NonNull
        public ContentCaptureEvent createFromParcel(Parcel parcel) {
            final int sessionId = parcel.readInt();
            final int type = parcel.readInt();
            final long eventTime  = parcel.readLong();
            final ContentCaptureEvent event = new ContentCaptureEvent(sessionId, type, eventTime);
            final AutofillId id = parcel.readParcelable(null, android.view.autofill.AutofillId.class);
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
                event.setParentSessionId(parcel.readInt());
            }
            if (type == TYPE_SESSION_STARTED || type == TYPE_CONTEXT_UPDATED) {
                event.setClientContext(parcel.readParcelable(null, android.view.contentcapture.ContentCaptureContext.class));
            }
            if (type == TYPE_VIEW_INSETS_CHANGED) {
                event.setInsets(parcel.readParcelable(null, android.graphics.Insets.class));
            }
            if (type == TYPE_WINDOW_BOUNDS_CHANGED) {
                event.setBounds(parcel.readParcelable(null, android.graphics.Rect.class));
            }
            if (type == TYPE_VIEW_TEXT_CHANGED) {
                event.setComposingIndex(parcel.readInt(), parcel.readInt());
                event.restoreComposingSpan();
                event.setSelectionIndex(parcel.readInt(), parcel.readInt());
                event.restoreSelectionSpans();
            }
            return event;
        }

        @Override
        @NonNull
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
            case TYPE_VIEW_INSETS_CHANGED:
                return "VIEW_INSETS_CHANGED";
            case TYPE_WINDOW_BOUNDS_CHANGED:
                return "TYPE_WINDOW_BOUNDS_CHANGED";
            default:
                return "UKNOWN_TYPE: " + type;
        }
    }
}
