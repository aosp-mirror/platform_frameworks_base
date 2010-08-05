/**
 * Copyright (c) 2010, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.ArrayList;

/**
 * Representation of a clipped data on the clipboard.
 *
 * <p>ClippedData is a complex type containing one or Item instances,
 * each of which can hold one or more representations of an item of data.
 * For display to the user, it also has a label and iconic representation.</p>
 *
 * <p>The types than an individial item can currently contain are:</p>
 *
 * <ul>
 * <li> Text: a basic string of text.  This is actually a CharSequence,
 * so it can be formatted text supported by corresponding Android built-in
 * style spans.  (Custom application spans are not supported and will be
 * stripped when transporting through the clipboard.)
 * <li> Intent: an arbitrary Intent object.  A typical use is the shortcut
 * to create when pasting a clipped item on to the home screen.
 * <li> Uri: a URI reference.  Currently this should only be a content: URI.
 * This representation allows an application to share complex or large clips,
 * by providing a URI to a content provider holding the data.
 * </ul>
 */
public class ClippedData implements Parcelable {
    CharSequence mLabel;
    Bitmap mIcon;

    final ArrayList<Item> mItems = new ArrayList<Item>();

    public static class Item {
        CharSequence mText;
        Intent mIntent;
        Uri mUri;

        public Item(CharSequence text) {
            mText = text;
        }

        public Item(Intent intent) {
            mIntent = intent;
        }

        public Item(Uri uri) {
            mUri = uri;
        }

        public Item(CharSequence text, Intent intent, Uri uri) {
            mText = text;
            mIntent = intent;
            mUri = uri;
        }

        public CharSequence getText() {
            return mText;
        }

        public Intent getIntent() {
            return mIntent;
        }

        public Uri getUri() {
            return mUri;
        }
    }

    /**
     * Create a new clip.
     *
     * @param label Label to show to the user describing this clip.
     * @param icon Bitmap providing the user with an iconing representation of
     * the clip.
     * @param item The contents of the first item in the clip.
     */
    public ClippedData(CharSequence label, Bitmap icon, Item item) {
        if (item == null) {
            throw new NullPointerException("item is null");
        }
        mLabel = label;
        mIcon = icon;
        mItems.add(item);
    }

    public void addItem(Item item) {
        if (item == null) {
            throw new NullPointerException("item is null");
        }
        mItems.add(item);
    }

    public CharSequence getLabel() {
        return mLabel;
    }

    public Bitmap getIcon() {
        return mIcon;
    }

    public int getItemCount() {
        return mItems.size();
    }

    public Item getItem(int index) {
        return mItems.get(index);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        TextUtils.writeToParcel(mLabel, dest, flags);
        if (mIcon != null) {
            dest.writeInt(1);
            mIcon.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        final int N = mItems.size();
        dest.writeInt(N);
        for (int i=0; i<N; i++) {
            Item item = mItems.get(i);
            TextUtils.writeToParcel(item.mText, dest, flags);
            if (item.mIntent != null) {
                dest.writeInt(1);
                item.mIntent.writeToParcel(dest, flags);
            } else {
                dest.writeInt(0);
            }
            if (item.mUri != null) {
                dest.writeInt(1);
                item.mUri.writeToParcel(dest, flags);
            } else {
                dest.writeInt(0);
            }
        }
    }

    ClippedData(Parcel in) {
        mLabel = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        if (in.readInt() != 0) {
            mIcon = Bitmap.CREATOR.createFromParcel(in);
        }
        final int N = in.readInt();
        for (int i=0; i<N; i++) {
            CharSequence text = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            Intent intent = in.readInt() != 0 ? Intent.CREATOR.createFromParcel(in) : null;
            Uri uri = in.readInt() != 0 ? Uri.CREATOR.createFromParcel(in) : null;
            mItems.add(new Item(text, intent, uri));
        }
    }

    public static final Parcelable.Creator<ClippedData> CREATOR =
        new Parcelable.Creator<ClippedData>() {

            public ClippedData createFromParcel(Parcel source) {
                return new ClippedData(source);
            }

            public ClippedData[] newArray(int size) {
                return new ClippedData[size];
            }
        };
}
