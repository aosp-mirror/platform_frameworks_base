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

package android.slice;

import static android.slice.SliceItem.TYPE_ACTION;
import static android.slice.SliceItem.TYPE_COLOR;
import static android.slice.SliceItem.TYPE_IMAGE;
import static android.slice.SliceItem.TYPE_REMOTE_INPUT;
import static android.slice.SliceItem.TYPE_REMOTE_VIEW;
import static android.slice.SliceItem.TYPE_SLICE;
import static android.slice.SliceItem.TYPE_TEXT;
import static android.slice.SliceItem.TYPE_TIMESTAMP;

import android.annotation.NonNull;
import android.annotation.StringDef;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * A slice is a piece of app content and actions that can be surfaced outside of the app.
 *
 * <p>They are constructed using {@link Builder} in a tree structure
 * that provides the OS some information about how the content should be displayed.
 * @hide
 */
public final class Slice implements Parcelable {

    /**
     * @hide
     */
    @StringDef({HINT_TITLE, HINT_LIST, HINT_LIST_ITEM, HINT_LARGE, HINT_ACTIONS, HINT_SELECTED,
            HINT_SOURCE, HINT_MESSAGE, HINT_HORIZONTAL, HINT_NO_TINT})
    public @interface SliceHint{ }

    /**
     * Hint that this content is a title of other content in the slice.
     */
    public static final String HINT_TITLE       = "title";
    /**
     * Hint that all sub-items/sub-slices within this content should be considered
     * to have {@link #HINT_LIST_ITEM}.
     */
    public static final String HINT_LIST        = "list";
    /**
     * Hint that this item is part of a list and should be formatted as if is part
     * of a list.
     */
    public static final String HINT_LIST_ITEM   = "list_item";
    /**
     * Hint that this content is important and should be larger when displayed if
     * possible.
     */
    public static final String HINT_LARGE       = "large";
    /**
     * Hint that this slice contains a number of actions that can be grouped together
     * in a sort of controls area of the UI.
     */
    public static final String HINT_ACTIONS     = "actions";
    /**
     * Hint indicating that this item (and its sub-items) are the current selection.
     */
    public static final String HINT_SELECTED    = "selected";
    /**
     * Hint to indicate that this is a message as part of a communication
     * sequence in this slice.
     */
    public static final String HINT_MESSAGE     = "message";
    /**
     * Hint to tag the source (i.e. sender) of a {@link #HINT_MESSAGE}.
     */
    public static final String HINT_SOURCE      = "source";
    /**
     * Hint that list items within this slice or subslice would appear better
     * if organized horizontally.
     */
    public static final String HINT_HORIZONTAL  = "horizontal";
    /**
     * Hint to indicate that this content should not be tinted.
     */
    public static final String HINT_NO_TINT     = "no_tint";

    // These two are coming over from prototyping, but we probably don't want in
    // public API, at least not right now.
    /**
     * @hide
     */
    public static final String HINT_ALT         = "alt";
    /**
     * @hide
     */
    public static final String HINT_PARTIAL     = "partial";

    private final SliceItem[] mItems;
    private final @SliceHint String[] mHints;
    private Uri mUri;

    /**
     * @hide
     */
    public Slice(ArrayList<SliceItem> items, @SliceHint String[] hints, Uri uri) {
        mHints = hints;
        mItems = items.toArray(new SliceItem[items.size()]);
        mUri = uri;
    }

    protected Slice(Parcel in) {
        mHints = in.readStringArray();
        int n = in.readInt();
        mItems = new SliceItem[n];
        for (int i = 0; i < n; i++) {
            mItems[i] = SliceItem.CREATOR.createFromParcel(in);
        }
        mUri = Uri.CREATOR.createFromParcel(in);
    }

    /**
     * @return The Uri that this slice represents.
     */
    public Uri getUri() {
        return mUri;
    }

    /**
     * @return All child {@link SliceItem}s that this Slice contains.
     */
    public SliceItem[] getItems() {
        return mItems;
    }

    /**
     * @return All hints associated with this Slice.
     */
    public @SliceHint String[] getHints() {
        return mHints;
    }

    /**
     * @hide
     */
    public SliceItem getPrimaryIcon() {
        for (SliceItem item : getItems()) {
            if (item.getType() == TYPE_IMAGE) {
                return item;
            }
            if (!(item.getType() == TYPE_SLICE && item.hasHint(Slice.HINT_LIST))
                    && !item.hasHint(Slice.HINT_ACTIONS)
                    && !item.hasHint(Slice.HINT_LIST_ITEM)
                    && (item.getType() != TYPE_ACTION)) {
                SliceItem icon = SliceQuery.find(item, TYPE_IMAGE);
                if (icon != null) return icon;
            }
        }
        return null;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStringArray(mHints);
        dest.writeInt(mItems.length);
        for (int i = 0; i < mItems.length; i++) {
            mItems[i].writeToParcel(dest, flags);
        }
        mUri.writeToParcel(dest, 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * A Builder used to construct {@link Slice}s
     */
    public static class Builder {

        private final Uri mUri;
        private ArrayList<SliceItem> mItems = new ArrayList<>();
        private @SliceHint ArrayList<String> mHints = new ArrayList<>();

        /**
         * Create a builder which will construct a {@link Slice} for the Given Uri.
         * @param uri Uri to tag for this slice.
         */
        public Builder(@NonNull Uri uri) {
            mUri = uri;
        }

        /**
         * Create a builder for a {@link Slice} that is a sub-slice of the slice
         * being constructed by the provided builder.
         * @param parent The builder constructing the parent slice
         */
        public Builder(@NonNull Slice.Builder parent) {
            mUri = parent.mUri.buildUpon().appendPath("_gen")
                    .appendPath(String.valueOf(mItems.size())).build();
        }

        /**
         * Add hints to the Slice being constructed
         */
        public Builder addHints(@SliceHint String... hints) {
            mHints.addAll(Arrays.asList(hints));
            return this;
        }

        /**
         * Add a sub-slice to the slice being constructed
         */
        public Builder addSubSlice(@NonNull Slice slice) {
            mItems.add(new SliceItem(slice, TYPE_SLICE, slice.getHints()));
            return this;
        }

        /**
         * Add an action to the slice being constructed
         */
        public Slice.Builder addAction(@NonNull PendingIntent action, @NonNull Slice s) {
            mItems.add(new SliceItem(action, s, TYPE_ACTION, new String[0]));
            return this;
        }

        /**
         * Add text to the slice being constructed
         */
        public Builder addText(CharSequence text, @SliceHint String... hints) {
            mItems.add(new SliceItem(text, TYPE_TEXT, hints));
            return this;
        }

        /**
         * Add an image to the slice being constructed
         */
        public Builder addIcon(Icon icon, @SliceHint String... hints) {
            mItems.add(new SliceItem(icon, TYPE_IMAGE, hints));
            return this;
        }

        /**
         * @hide This isn't final
         */
        public Builder addRemoteView(RemoteViews remoteView, @SliceHint String... hints) {
            mItems.add(new SliceItem(remoteView, TYPE_REMOTE_VIEW, hints));
            return this;
        }

        /**
         * Add remote input to the slice being constructed
         */
        public Slice.Builder addRemoteInput(RemoteInput remoteInput, @SliceHint String... hints) {
            mItems.add(new SliceItem(remoteInput, TYPE_REMOTE_INPUT, hints));
            return this;
        }

        /**
         * Add a color to the slice being constructed
         */
        public Builder addColor(int color, @SliceHint String... hints) {
            mItems.add(new SliceItem(color, TYPE_COLOR, hints));
            return this;
        }

        /**
         * Add a timestamp to the slice being constructed
         */
        public Slice.Builder addTimestamp(long time, @SliceHint String... hints) {
            mItems.add(new SliceItem(time, TYPE_TIMESTAMP, hints));
            return this;
        }

        /**
         * Construct the slice.
         */
        public Slice build() {
            return new Slice(mItems, mHints.toArray(new String[mHints.size()]), mUri);
        }
    }

    public static final Creator<Slice> CREATOR = new Creator<Slice>() {
        @Override
        public Slice createFromParcel(Parcel in) {
            return new Slice(in);
        }

        @Override
        public Slice[] newArray(int size) {
            return new Slice[size];
        }
    };
}
