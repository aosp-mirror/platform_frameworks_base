/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.content;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.contentcapture.ContentCaptureManager;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;

/**
 * An identifier for an unique state (locus) in the application. Should be stable across reboots and
 * backup / restore.
 *
 * <p>Locus is a new concept introduced on
 * {@link android.os.Build.VERSION_CODES#Q Android Q} and it lets the Android system correlate
 * state between different subsystems such as content capture, shortcuts, and notifications.
 *
 * <p>For example, if your app provides an activiy representing a chat between 2 users
 * (say {@code A} and {@code B}, this chat state could be represented by:
 *
 * <pre><code>
 * LocusId chatId = new LocusId("Chat_A_B");
 * </code></pre>
 *
 * <p>And then you should use that {@code chatId} by:
 *
 * <ul>
 *   <li>Setting it in the chat notification (through
 *   {@link android.app.Notification.Builder#setLocusId(LocusId)
 *   Notification.Builder.setLocusId(chatId)}).
 *   <li>Setting it into the {@link android.content.pm.ShortcutInfo} (through
 *   {@link android.content.pm.ShortcutInfo.Builder#setLocusId(LocusId)
 *   ShortcutInfo.Builder.setLocusId(chatId)}), if you provide a launcher shortcut for that chat
 *   conversation.
 *   <li>Associating it with the {@link android.view.contentcapture.ContentCaptureContext} of the
 *   root view of the chat conversation activity (through
 *   {@link android.view.View#getContentCaptureSession()}, then
 *   {@link android.view.contentcapture.ContentCaptureContext.Builder
 *   new ContentCaptureContext.Builder(chatId).build()} and
 *   {@link android.view.contentcapture.ContentCaptureSession#setContentCaptureContext(
 *   android.view.contentcapture.ContentCaptureContext)} - see {@link ContentCaptureManager}
 *   for more info about content capture).
 *   <li>Configuring your app to launch the chat conversation through the
 *   {@link Intent#ACTION_VIEW_LOCUS} intent.
 * </ul>
 */
public final class LocusId implements Parcelable {

    private final String mId;

    /**
     * Default constructor.
     *
     * @throws IllegalArgumentException if {@code id} is empty or {@code null}.
     */
    public LocusId(@NonNull String id) {
        mId = Preconditions.checkStringNotEmpty(id, "id cannot be empty");
    }

    /**
     * Gets the canonical {@code id} associated with the locus.
     */
    @NonNull
    public String getId() {
        return mId;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((mId == null) ? 0 : mId.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        final LocusId other = (LocusId) obj;
        if (mId == null) {
            if (other.mId != null) return false;
        } else {
            if (!mId.equals(other.mId)) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "LocusId[" + getSanitizedId() + "]";
    }

    /** @hide */
    public void dump(@NonNull PrintWriter pw) {
        pw.print("id:"); pw.println(getSanitizedId());
    }

    @NonNull
    private String getSanitizedId() {
        final int size = mId.length();
        return size + "_chars";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mId);
    }

    public static final @NonNull Parcelable.Creator<LocusId> CREATOR =
            new Parcelable.Creator<LocusId>() {

        @NonNull
        @Override
        public LocusId createFromParcel(Parcel parcel) {
            return new LocusId(parcel.readString());
        }

        @NonNull
        @Override
        public LocusId[] newArray(int size) {
            return new LocusId[size];
        }
    };
}
