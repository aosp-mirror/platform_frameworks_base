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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.slice.Slice.SliceHint;
import android.text.TextUtils;
import android.util.Pair;
import android.widget.RemoteViews;

import com.android.internal.util.ArrayUtils;


/**
 * A SliceItem is a single unit in the tree structure of a {@link Slice}.
 *
 * A SliceItem a piece of content and some hints about what that content
 * means or how it should be displayed. The types of content can be:
 * <li>{@link #TYPE_SLICE}</li>
 * <li>{@link #TYPE_TEXT}</li>
 * <li>{@link #TYPE_IMAGE}</li>
 * <li>{@link #TYPE_ACTION}</li>
 * <li>{@link #TYPE_COLOR}</li>
 * <li>{@link #TYPE_TIMESTAMP}</li>
 * <li>{@link #TYPE_REMOTE_INPUT}</li>
 *
 * The hints that a {@link SliceItem} are a set of strings which annotate
 * the content. The hints that are guaranteed to be understood by the system
 * are defined on {@link Slice}.
 * @hide
 */
public final class SliceItem implements Parcelable {

    /**
     * @hide
     */
    @IntDef({TYPE_SLICE, TYPE_TEXT, TYPE_IMAGE, TYPE_ACTION, TYPE_COLOR,
            TYPE_TIMESTAMP, TYPE_REMOTE_INPUT})
    public @interface SliceType {}

    /**
     * A {@link SliceItem} that contains a {@link Slice}
     */
    public static final int TYPE_SLICE        = 1;
    /**
     * A {@link SliceItem} that contains a {@link CharSequence}
     */
    public static final int TYPE_TEXT         = 2;
    /**
     * A {@link SliceItem} that contains an {@link Icon}
     */
    public static final int TYPE_IMAGE        = 3;
    /**
     * A {@link SliceItem} that contains a {@link PendingIntent}
     *
     * Note: Actions contain 2 pieces of data, In addition to the pending intent, the
     * item contains a {@link Slice} that the action applies to.
     */
    public static final int TYPE_ACTION       = 4;
    /**
     * @hide This isn't final
     */
    public static final int TYPE_REMOTE_VIEW  = 5;
    /**
     * A {@link SliceItem} that contains a Color int.
     */
    public static final int TYPE_COLOR        = 6;
    /**
     * A {@link SliceItem} that contains a timestamp.
     */
    public static final int TYPE_TIMESTAMP    = 8;
    /**
     * A {@link SliceItem} that contains a {@link RemoteInput}.
     */
    public static final int TYPE_REMOTE_INPUT = 9;

    /**
     * @hide
     */
    protected @SliceHint String[] mHints;
    private final int mType;
    private final Object mObj;

    /**
     * @hide
     */
    public SliceItem(Object obj, @SliceType int type, @SliceHint String[] hints) {
        mHints = hints;
        mType = type;
        mObj = obj;
    }

    /**
     * @hide
     */
    public SliceItem(PendingIntent intent, Slice slice, int type, @SliceHint String[] hints) {
        this(new Pair<>(intent, slice), type, hints);
    }

    /**
     * Gets all hints associated with this SliceItem.
     * @return Array of hints.
     */
    public @NonNull @SliceHint String[] getHints() {
        return mHints;
    }

    /**
     * @hide
     */
    public void addHint(@SliceHint String hint) {
        mHints = ArrayUtils.appendElement(String.class, mHints, hint);
    }

    /**
     * @hide
     */
    public void removeHint(String hint) {
        ArrayUtils.removeElement(String.class, mHints, hint);
    }

    public @SliceType int getType() {
        return mType;
    }

    /**
     * @return The text held by this {@link #TYPE_TEXT} SliceItem
     */
    public CharSequence getText() {
        return (CharSequence) mObj;
    }

    /**
     * @return The icon held by this {@link #TYPE_IMAGE} SliceItem
     */
    public Icon getIcon() {
        return (Icon) mObj;
    }

    /**
     * @return The pending intent held by this {@link #TYPE_ACTION} SliceItem
     */
    public PendingIntent getAction() {
        return ((Pair<PendingIntent, Slice>) mObj).first;
    }

    /**
     * @hide This isn't final
     */
    public RemoteViews getRemoteView() {
        return (RemoteViews) mObj;
    }

    /**
     * @return The remote input held by this {@link #TYPE_REMOTE_INPUT} SliceItem
     */
    public RemoteInput getRemoteInput() {
        return (RemoteInput) mObj;
    }

    /**
     * @return The color held by this {@link #TYPE_COLOR} SliceItem
     */
    public int getColor() {
        return (Integer) mObj;
    }

    /**
     * @return The slice held by this {@link #TYPE_ACTION} or {@link #TYPE_SLICE} SliceItem
     */
    public Slice getSlice() {
        if (getType() == TYPE_ACTION) {
            return ((Pair<PendingIntent, Slice>) mObj).second;
        }
        return (Slice) mObj;
    }

    /**
     * @return The timestamp held by this {@link #TYPE_TIMESTAMP} SliceItem
     */
    public long getTimestamp() {
        return (Long) mObj;
    }

    /**
     * @param hint The hint to check for
     * @return true if this item contains the given hint
     */
    public boolean hasHint(@SliceHint String hint) {
        return ArrayUtils.contains(mHints, hint);
    }

    /**
     * @hide
     */
    public SliceItem(Parcel in) {
        mHints = in.readStringArray();
        mType = in.readInt();
        mObj = readObj(mType, in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStringArray(mHints);
        dest.writeInt(mType);
        writeObj(dest, flags, mObj, mType);
    }

    /**
     * @hide
     */
    public boolean hasHints(@SliceHint String[] hints) {
        if (hints == null) return true;
        for (String hint : hints) {
            if (!TextUtils.isEmpty(hint) && !ArrayUtils.contains(mHints, hint)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @hide
     */
    public boolean hasAnyHints(@SliceHint String[] hints) {
        if (hints == null) return false;
        for (String hint : hints) {
            if (ArrayUtils.contains(mHints, hint)) {
                return true;
            }
        }
        return false;
    }

    private void writeObj(Parcel dest, int flags, Object obj, int type) {
        switch (type) {
            case TYPE_SLICE:
            case TYPE_REMOTE_VIEW:
            case TYPE_IMAGE:
            case TYPE_REMOTE_INPUT:
                ((Parcelable) obj).writeToParcel(dest, flags);
                break;
            case TYPE_ACTION:
                ((Pair<PendingIntent, Slice>) obj).first.writeToParcel(dest, flags);
                ((Pair<PendingIntent, Slice>) obj).second.writeToParcel(dest, flags);
                break;
            case TYPE_TEXT:
                TextUtils.writeToParcel((CharSequence) mObj, dest, flags);
                break;
            case TYPE_COLOR:
                dest.writeInt((Integer) mObj);
                break;
            case TYPE_TIMESTAMP:
                dest.writeLong((Long) mObj);
                break;
        }
    }

    private static Object readObj(int type, Parcel in) {
        switch (type) {
            case TYPE_SLICE:
                return Slice.CREATOR.createFromParcel(in);
            case TYPE_TEXT:
                return TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            case TYPE_IMAGE:
                return Icon.CREATOR.createFromParcel(in);
            case TYPE_ACTION:
                return new Pair<PendingIntent, Slice>(
                        PendingIntent.CREATOR.createFromParcel(in),
                        Slice.CREATOR.createFromParcel(in));
            case TYPE_REMOTE_VIEW:
                return RemoteViews.CREATOR.createFromParcel(in);
            case TYPE_COLOR:
                return in.readInt();
            case TYPE_TIMESTAMP:
                return in.readLong();
            case TYPE_REMOTE_INPUT:
                return RemoteInput.CREATOR.createFromParcel(in);
        }
        throw new RuntimeException("Unsupported type " + type);
    }

    public static final Creator<SliceItem> CREATOR = new Creator<SliceItem>() {
        @Override
        public SliceItem createFromParcel(Parcel in) {
            return new SliceItem(in);
        }

        @Override
        public SliceItem[] newArray(int size) {
            return new SliceItem[size];
        }
    };

    /**
     * @hide
     */
    public static String typeToString(int type) {
        switch (type) {
            case TYPE_SLICE:
                return "Slice";
            case TYPE_TEXT:
                return "Text";
            case TYPE_IMAGE:
                return "Image";
            case TYPE_ACTION:
                return "Action";
            case TYPE_REMOTE_VIEW:
                return "RemoteView";
            case TYPE_COLOR:
                return "Color";
            case TYPE_TIMESTAMP:
                return "Timestamp";
            case TYPE_REMOTE_INPUT:
                return "RemoteInput";
        }
        return "Unrecognized type: " + type;
    }
}
