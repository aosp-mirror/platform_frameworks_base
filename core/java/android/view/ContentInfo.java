/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.view;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.content.ClipData;
import android.content.ClipDescription;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pair;
import android.view.inputmethod.InputContentInfo;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Holds all the relevant data for a request to {@link View#performReceiveContent}.
 */
public final class ContentInfo implements Parcelable {

    /**
     * Specifies the UI through which content is being inserted. Future versions of Android may
     * support additional values.
     *
     * @hide
     */
    @IntDef(prefix = {"SOURCE_"}, value = {SOURCE_APP, SOURCE_CLIPBOARD, SOURCE_INPUT_METHOD,
            SOURCE_DRAG_AND_DROP, SOURCE_AUTOFILL, SOURCE_PROCESS_TEXT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Source {}

    /**
     * Specifies that the operation was triggered by the app that contains the target view.
     */
    public static final int SOURCE_APP = 0;

    /**
     * Specifies that the operation was triggered by a paste from the clipboard (e.g. "Paste" or
     * "Paste as plain text" action in the insertion/selection menu).
     */
    public static final int SOURCE_CLIPBOARD = 1;

    /**
     * Specifies that the operation was triggered from the soft keyboard (also known as input
     * method editor or IME). See https://developer.android.com/guide/topics/text/image-keyboard
     * for more info.
     */
    public static final int SOURCE_INPUT_METHOD = 2;

    /**
     * Specifies that the operation was triggered by the drag/drop framework. See
     * https://developer.android.com/guide/topics/ui/drag-drop for more info.
     */
    public static final int SOURCE_DRAG_AND_DROP = 3;

    /**
     * Specifies that the operation was triggered by the autofill framework. See
     * https://developer.android.com/guide/topics/text/autofill for more info.
     */
    public static final int SOURCE_AUTOFILL = 4;

    /**
     * Specifies that the operation was triggered by a result from a
     * {@link android.content.Intent#ACTION_PROCESS_TEXT PROCESS_TEXT} action in the selection
     * menu.
     */
    public static final int SOURCE_PROCESS_TEXT = 5;

    /**
     * Returns the symbolic name of the given source.
     *
     * @hide
     */
    static String sourceToString(@Source int source) {
        switch (source) {
            case SOURCE_APP: return "SOURCE_APP";
            case SOURCE_CLIPBOARD: return "SOURCE_CLIPBOARD";
            case SOURCE_INPUT_METHOD: return "SOURCE_INPUT_METHOD";
            case SOURCE_DRAG_AND_DROP: return "SOURCE_DRAG_AND_DROP";
            case SOURCE_AUTOFILL: return "SOURCE_AUTOFILL";
            case SOURCE_PROCESS_TEXT: return "SOURCE_PROCESS_TEXT";
        }
        return String.valueOf(source);
    }

    /**
     * Flags to configure the insertion behavior.
     *
     * @hide
     */
    @IntDef(flag = true, prefix = {"FLAG_"}, value = {FLAG_CONVERT_TO_PLAIN_TEXT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Flags {}

    /**
     * Flag requesting that the content should be converted to plain text prior to inserting.
     */
    public static final int FLAG_CONVERT_TO_PLAIN_TEXT = 1 << 0;

    /**
     * Returns the symbolic names of the set flags or {@code "0"} if no flags are set.
     *
     * @hide
     */
    static String flagsToString(@Flags int flags) {
        if ((flags & FLAG_CONVERT_TO_PLAIN_TEXT) != 0) {
            return "FLAG_CONVERT_TO_PLAIN_TEXT";
        }
        return String.valueOf(flags);
    }

    @NonNull
    private final ClipData mClip;
    @Source
    private final int mSource;
    @Flags
    private final int mFlags;
    @Nullable
    private final Uri mLinkUri;
    @Nullable
    private final Bundle mExtras;
    @Nullable
    private final InputContentInfo mInputContentInfo;
    @Nullable
    private final DragAndDropPermissions mDragAndDropPermissions;

    private ContentInfo(Builder b) {
        this.mClip = Objects.requireNonNull(b.mClip);
        this.mSource = Preconditions.checkArgumentInRange(b.mSource, 0, SOURCE_PROCESS_TEXT,
                "source");
        this.mFlags = Preconditions.checkFlagsArgument(b.mFlags, FLAG_CONVERT_TO_PLAIN_TEXT);
        this.mLinkUri = b.mLinkUri;
        this.mExtras = b.mExtras;
        this.mInputContentInfo = b.mInputContentInfo;
        this.mDragAndDropPermissions = b.mDragAndDropPermissions;
    }

    /**
     * If the content came from a source that supports proactive release of URI permissions
     * (e.g. IME), releases permissions; otherwise a no-op.
     *
     * @hide
     */
    @TestApi
    public void releasePermissions() {
        if (mInputContentInfo != null) {
            mInputContentInfo.releasePermission();
        }
        if (mDragAndDropPermissions != null) {
            mDragAndDropPermissions.release();
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "ContentInfo{"
                + "clip=" + mClip
                + ", source=" + sourceToString(mSource)
                + ", flags=" + flagsToString(mFlags)
                + ", linkUri=" + mLinkUri
                + ", extras=" + mExtras
                + "}";
    }

    /**
     * The data to be inserted.
     */
    @NonNull
    public ClipData getClip() {
        return mClip;
    }

    /**
     * The source of the operation. See {@code SOURCE_} constants. Future versions of Android
     * may pass additional values.
     */
    @Source
    public int getSource() {
        return mSource;
    }

    /**
     * Optional flags that control the insertion behavior. See {@code FLAG_} constants.
     */
    @Flags
    public int getFlags() {
        return mFlags;
    }

    /**
     * Optional http/https URI for the content that may be provided by the IME. This is only
     * populated if the source is {@link #SOURCE_INPUT_METHOD} and if a non-empty
     * {@link android.view.inputmethod.InputContentInfo#getLinkUri linkUri} was passed by the
     * IME.
     */
    @Nullable
    public Uri getLinkUri() {
        return mLinkUri;
    }

    /**
     * Optional additional metadata. If the source is {@link #SOURCE_INPUT_METHOD}, this will
     * include the {@link android.view.inputmethod.InputConnection#commitContent opts} passed by
     * the IME.
     */
    @Nullable
    @SuppressLint("NullableCollection")
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * Partitions this content based on the given predicate.
     *
     * <p>This function classifies the content and organizes it into a pair, grouping the items
     * that matched vs didn't match the predicate.
     *
     * <p>Except for the {@link ClipData} items, the returned objects will contain all the same
     * metadata as this {@link ContentInfo}.
     *
     * @param itemPredicate The predicate to test each {@link ClipData.Item} to determine which
     *                      partition to place it into.
     * @return A pair containing the partitioned content. The pair's first object will have the
     * content that matched the predicate, or null if none of the items matched. The pair's
     * second object will have the content that didn't match the predicate, or null if all of
     * the items matched.
     *
     * @hide
     */
    @TestApi
    @NonNull
    public Pair<ContentInfo, ContentInfo> partition(
            @NonNull Predicate<ClipData.Item> itemPredicate) {
        if (mClip.getItemCount() == 1) {
            boolean matched = itemPredicate.test(mClip.getItemAt(0));
            return Pair.create(matched ? this : null, matched ? null : this);
        }
        ArrayList<ClipData.Item> acceptedItems = new ArrayList<>();
        ArrayList<ClipData.Item> remainingItems = new ArrayList<>();
        for (int i = 0; i < mClip.getItemCount(); i++) {
            ClipData.Item item = mClip.getItemAt(i);
            if (itemPredicate.test(item)) {
                acceptedItems.add(item);
            } else {
                remainingItems.add(item);
            }
        }
        if (acceptedItems.isEmpty()) {
            return Pair.create(null, this);
        }
        if (remainingItems.isEmpty()) {
            return Pair.create(this, null);
        }
        ContentInfo accepted = new Builder(this)
                .setClip(new ClipData(new ClipDescription(mClip.getDescription()), acceptedItems))
                .build();
        ContentInfo remaining = new Builder(this)
                .setClip(new ClipData(new ClipDescription(mClip.getDescription()), remainingItems))
                .build();
        return Pair.create(accepted, remaining);
    }

    /**
     * Builder for {@link ContentInfo}.
     */
    public static final class Builder {
        @NonNull
        private ClipData mClip;
        @Source
        private int mSource;
        @Flags
        private  int mFlags;
        @Nullable
        private Uri mLinkUri;
        @Nullable
        private Bundle mExtras;
        @Nullable
        private InputContentInfo mInputContentInfo;
        @Nullable
        private DragAndDropPermissions mDragAndDropPermissions;

        /**
         * Creates a new builder initialized with the data from the given builder.
         */
        public Builder(@NonNull ContentInfo other) {
            mClip = other.mClip;
            mSource = other.mSource;
            mFlags = other.mFlags;
            mLinkUri = other.mLinkUri;
            mExtras = other.mExtras;
            mInputContentInfo = other.mInputContentInfo;
            mDragAndDropPermissions = other.mDragAndDropPermissions;
        }

        /**
         * Creates a new builder.
         * @param clip   The data to insert.
         * @param source The source of the operation. See {@code SOURCE_} constants.
         */
        public Builder(@NonNull ClipData clip, @Source int source) {
            mClip = clip;
            mSource = source;
        }

        /**
         * Sets the data to be inserted.
         * @param clip The data to insert.
         * @return this builder
         */
        @NonNull
        public Builder setClip(@NonNull ClipData clip) {
            mClip = clip;
            return this;
        }

        /**
         * Sets the source of the operation.
         * @param source The source of the operation. See {@code SOURCE_} constants.
         * @return this builder
         */
        @NonNull
        public Builder setSource(@Source int source) {
            mSource = source;
            return this;
        }

        /**
         * Sets flags that control content insertion behavior.
         * @param flags Optional flags to configure the insertion behavior. Use 0 for default
         *              behavior. See {@code FLAG_} constants.
         * @return this builder
         */
        @NonNull
        public Builder setFlags(@Flags int flags) {
            mFlags = flags;
            return this;
        }

        /**
         * Sets the http/https URI for the content. See
         * {@link android.view.inputmethod.InputContentInfo#getLinkUri} for more info.
         * @param linkUri Optional http/https URI for the content.
         * @return this builder
         */
        @NonNull
        public Builder setLinkUri(@Nullable Uri linkUri) {
            mLinkUri = linkUri;
            return this;
        }

        /**
         * Sets additional metadata.
         * @param extras Optional bundle with additional metadata.
         * @return this builder
         */
        @NonNull
        public Builder setExtras(@SuppressLint("NullableCollection") @Nullable Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Set the {@link InputContentInfo} object if the content is coming from the IME. This can
         * be used for proactive cleanup of permissions.
         *
         * @hide
         */
        @TestApi
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setInputContentInfo(@Nullable InputContentInfo inputContentInfo) {
            mInputContentInfo = inputContentInfo;
            return this;
        }

        /**
         * Set the {@link DragAndDropPermissions} object if the content is coming via drag-and-drop.
         * This can be used for proactive cleanup of permissions.
         *
         * @hide
         */
        @TestApi
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setDragAndDropPermissions(@Nullable DragAndDropPermissions permissions) {
            mDragAndDropPermissions = permissions;
            return this;
        }


        /**
         * @return A new {@link ContentInfo} instance with the data from this builder.
         */
        @NonNull
        public ContentInfo build() {
            return new ContentInfo(this);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Writes this object into the given parcel.
     *
     * @param dest  The parcel to write into.
     * @param flags The flags to use for parceling.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mClip.writeToParcel(dest, flags);
        dest.writeInt(mSource);
        dest.writeInt(mFlags);
        Uri.writeToParcel(dest, mLinkUri);
        dest.writeBundle(mExtras);
        if (mInputContentInfo == null) {
            dest.writeInt(0);
        } else {
            dest.writeInt(1);
            mInputContentInfo.writeToParcel(dest, flags);
        }
        if (mDragAndDropPermissions == null) {
            dest.writeInt(0);
        } else {
            dest.writeInt(1);
            mDragAndDropPermissions.writeToParcel(dest, flags);
        }
    }

    /**
     * Creates {@link ContentInfo} instances from parcels.
     */
    @NonNull
    public static final Parcelable.Creator<ContentInfo> CREATOR =
            new Parcelable.Creator<ContentInfo>() {
        @Override
        public ContentInfo createFromParcel(Parcel parcel) {
            ClipData clip = ClipData.CREATOR.createFromParcel(parcel);
            int source = parcel.readInt();
            int flags = parcel.readInt();
            Uri linkUri = Uri.CREATOR.createFromParcel(parcel);
            Bundle extras = parcel.readBundle();
            InputContentInfo inputContentInfo = null;
            if (parcel.readInt() != 0) {
                inputContentInfo = InputContentInfo.CREATOR.createFromParcel(parcel);
            }
            DragAndDropPermissions dragAndDropPermissions = null;
            if (parcel.readInt() != 0) {
                dragAndDropPermissions = DragAndDropPermissions.CREATOR.createFromParcel(parcel);
            }
            return new ContentInfo.Builder(clip, source)
                    .setFlags(flags)
                    .setLinkUri(linkUri)
                    .setExtras(extras)
                    .setInputContentInfo(inputContentInfo)
                    .setDragAndDropPermissions(dragAndDropPermissions)
                    .build();
        }

        @Override
        public ContentInfo[] newArray(int size) {
            return new ContentInfo[size];
        }
    };
}
