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

package android.app.slice;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ContentResolver;
import android.content.IContentProvider;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.widget.RemoteViews;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A slice is a piece of app content and actions that can be surfaced outside of the app.
 *
 * <p>They are constructed using {@link Builder} in a tree structure
 * that provides the OS some information about how the content should be displayed.
 */
public final class Slice implements Parcelable {

    /**
     * @hide
     */
    @StringDef({HINT_TITLE, HINT_LIST, HINT_LIST_ITEM, HINT_LARGE, HINT_ACTIONS, HINT_SELECTED,
            HINT_SOURCE, HINT_MESSAGE, HINT_HORIZONTAL, HINT_NO_TINT, HINT_PARTIAL})
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
    /**
     * Hint to indicate that this slice is incomplete and an update will be sent once
     * loading is complete. Slices which contain HINT_PARTIAL will not be cached by the
     * OS and should not be cached by apps.
     */
    public static final String HINT_PARTIAL     = "partial";

    // These two are coming over from prototyping, but we probably don't want in
    // public API, at least not right now.
    /**
     * @hide
     */
    public static final String HINT_ALT         = "alt";

    private final SliceItem[] mItems;
    private final @SliceHint String[] mHints;
    private Uri mUri;

    Slice(ArrayList<SliceItem> items, @SliceHint String[] hints, Uri uri) {
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
     * @return The Uri that this Slice represents.
     */
    public Uri getUri() {
        return mUri;
    }

    /**
     * @return All child {@link SliceItem}s that this Slice contains.
     */
    public List<SliceItem> getItems() {
        return Arrays.asList(mItems);
    }

    /**
     * @return All hints associated with this Slice.
     */
    public @SliceHint List<String> getHints() {
        return Arrays.asList(mHints);
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
     * @hide
     */
    public boolean hasHint(@SliceHint String hint) {
        return ArrayUtils.contains(mHints, hint);
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
         * Add hints to the Slice being constructed
         */
        public Builder addHints(@SliceHint List<String> hints) {
            return addHints(hints.toArray(new String[hints.size()]));
        }

        /**
         * Add a sub-slice to the slice being constructed
         */
        public Builder addSubSlice(@NonNull Slice slice) {
            mItems.add(new SliceItem(slice, SliceItem.TYPE_SLICE, slice.getHints().toArray(
                    new String[slice.getHints().size()])));
            return this;
        }

        /**
         * Add an action to the slice being constructed
         */
        public Slice.Builder addAction(@NonNull PendingIntent action, @NonNull Slice s) {
            mItems.add(new SliceItem(action, s, SliceItem.TYPE_ACTION, new String[0]));
            return this;
        }

        /**
         * Add text to the slice being constructed
         */
        public Builder addText(CharSequence text, @SliceHint String... hints) {
            mItems.add(new SliceItem(text, SliceItem.TYPE_TEXT, hints));
            return this;
        }

        /**
         * Add text to the slice being constructed
         */
        public Builder addText(CharSequence text, @SliceHint List<String> hints) {
            return addText(text, hints.toArray(new String[hints.size()]));
        }

        /**
         * Add an image to the slice being constructed
         */
        public Builder addIcon(Icon icon, @SliceHint String... hints) {
            mItems.add(new SliceItem(icon, SliceItem.TYPE_IMAGE, hints));
            return this;
        }

        /**
         * Add an image to the slice being constructed
         */
        public Builder addIcon(Icon icon, @SliceHint List<String> hints) {
            return addIcon(icon, hints.toArray(new String[hints.size()]));
        }

        /**
         * @hide This isn't final
         */
        public Builder addRemoteView(RemoteViews remoteView, @SliceHint String... hints) {
            mItems.add(new SliceItem(remoteView, SliceItem.TYPE_REMOTE_VIEW, hints));
            return this;
        }

        /**
         * Add remote input to the slice being constructed
         */
        public Slice.Builder addRemoteInput(RemoteInput remoteInput,
                @SliceHint List<String> hints) {
            return addRemoteInput(remoteInput, hints.toArray(new String[hints.size()]));
        }

        /**
         * Add remote input to the slice being constructed
         */
        public Slice.Builder addRemoteInput(RemoteInput remoteInput, @SliceHint String... hints) {
            mItems.add(new SliceItem(remoteInput, SliceItem.TYPE_REMOTE_INPUT, hints));
            return this;
        }

        /**
         * Add a color to the slice being constructed
         */
        public Builder addColor(int color, @SliceHint String... hints) {
            mItems.add(new SliceItem(color, SliceItem.TYPE_COLOR, hints));
            return this;
        }

        /**
         * Add a color to the slice being constructed
         */
        public Builder addColor(int color, @SliceHint List<String> hints) {
            return addColor(color, hints.toArray(new String[hints.size()]));
        }

        /**
         * Add a timestamp to the slice being constructed
         */
        public Slice.Builder addTimestamp(long time, @SliceHint String... hints) {
            mItems.add(new SliceItem(time, SliceItem.TYPE_TIMESTAMP, hints));
            return this;
        }

        /**
         * Add a timestamp to the slice being constructed
         */
        public Slice.Builder addTimestamp(long time, @SliceHint List<String> hints) {
            return addTimestamp(time, hints.toArray(new String[hints.size()]));
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

    /**
     * @hide
     * @return A string representation of this slice.
     */
    public String toString() {
        return toString("");
    }

    private String toString(String indent) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mItems.length; i++) {
            sb.append(indent);
            if (mItems[i].getType() == SliceItem.TYPE_SLICE) {
                sb.append("slice:\n");
                sb.append(mItems[i].getSlice().toString(indent + "   "));
            } else if (mItems[i].getType() == SliceItem.TYPE_TEXT) {
                sb.append("text: ");
                sb.append(mItems[i].getText());
                sb.append("\n");
            } else {
                sb.append(SliceItem.typeToString(mItems[i].getType()));
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Turns a slice Uri into slice content.
     *
     * @param resolver ContentResolver to be used.
     * @param uri The URI to a slice provider
     * @return The Slice provided by the app or null if none is given.
     * @see Slice
     */
    public static @Nullable Slice bindSlice(ContentResolver resolver, @NonNull Uri uri) {
        Preconditions.checkNotNull(uri, "uri");
        IContentProvider provider = resolver.acquireProvider(uri);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
        try {
            Bundle extras = new Bundle();
            extras.putParcelable(SliceProvider.EXTRA_BIND_URI, uri);
            final Bundle res = provider.call(resolver.getPackageName(), SliceProvider.METHOD_SLICE,
                    null, extras);
            Bundle.setDefusable(res, true);
            if (res == null) {
                return null;
            }
            return res.getParcelable(SliceProvider.EXTRA_SLICE);
        } catch (RemoteException e) {
            // Arbitrary and not worth documenting, as Activity
            // Manager will kill this process shortly anyway.
            return null;
        } finally {
            resolver.releaseProvider(provider);
        }
    }
}
