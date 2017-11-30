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
import android.annotation.StringDef;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Pair;
import android.widget.RemoteViews;

import com.android.internal.util.ArrayUtils;

import java.util.Arrays;
import java.util.List;


/**
 * A SliceItem is a single unit in the tree structure of a {@link Slice}.
 *
 * A SliceItem a piece of content and some hints about what that content
 * means or how it should be displayed. The types of content can be:
 * <li>{@link #FORMAT_SLICE}</li>
 * <li>{@link #FORMAT_TEXT}</li>
 * <li>{@link #FORMAT_IMAGE}</li>
 * <li>{@link #FORMAT_ACTION}</li>
 * <li>{@link #FORMAT_COLOR}</li>
 * <li>{@link #FORMAT_TIMESTAMP}</li>
 * <li>{@link #FORMAT_REMOTE_INPUT}</li>
 *
 * The hints that a {@link SliceItem} are a set of strings which annotate
 * the content. The hints that are guaranteed to be understood by the system
 * are defined on {@link Slice}.
 */
public final class SliceItem implements Parcelable {

    /**
     * @hide
     */
    @StringDef({FORMAT_SLICE, FORMAT_TEXT, FORMAT_IMAGE, FORMAT_ACTION, FORMAT_COLOR,
            FORMAT_TIMESTAMP, FORMAT_REMOTE_INPUT})
    public @interface SliceType {}

    /**
     * A {@link SliceItem} that contains a {@link Slice}
     */
    public static final String FORMAT_SLICE = "slice";
    /**
     * A {@link SliceItem} that contains a {@link CharSequence}
     */
    public static final String FORMAT_TEXT = "text";
    /**
     * A {@link SliceItem} that contains an {@link Icon}
     */
    public static final String FORMAT_IMAGE = "image";
    /**
     * A {@link SliceItem} that contains a {@link PendingIntent}
     *
     * Note: Actions contain 2 pieces of data, In addition to the pending intent, the
     * item contains a {@link Slice} that the action applies to.
     */
    public static final String FORMAT_ACTION = "action";
    /**
     * A {@link SliceItem} that contains a Color int.
     */
    public static final String FORMAT_COLOR = "color";
    /**
     * A {@link SliceItem} that contains a timestamp.
     */
    public static final String FORMAT_TIMESTAMP = "timestamp";
    /**
     * A {@link SliceItem} that contains a {@link RemoteInput}.
     */
    public static final String FORMAT_REMOTE_INPUT = "input";

    /**
     * @hide
     */
    protected @Slice.SliceHint
    String[] mHints;
    private final String mFormat;
    private final String mSubType;
    private final Object mObj;

    /**
     * @hide
     */
    public SliceItem(Object obj, @SliceType String format, String subType,
            @Slice.SliceHint String[] hints) {
        mHints = hints;
        mFormat = format;
        mSubType = subType;
        mObj = obj;
    }

    /**
     * @hide
     */
    public SliceItem(PendingIntent intent, Slice slice, String format, String subType,
            @Slice.SliceHint String[] hints) {
        this(new Pair<>(intent, slice), format, subType, hints);
    }

    /**
     * Gets all hints associated with this SliceItem.
     * @return Array of hints.
     */
    public @NonNull @Slice.SliceHint List<String> getHints() {
        return Arrays.asList(mHints);
    }

    /**
     * @hide
     */
    public void addHint(@Slice.SliceHint String hint) {
        mHints = ArrayUtils.appendElement(String.class, mHints, hint);
    }

    /**
     * @hide
     */
    public void removeHint(String hint) {
        ArrayUtils.removeElement(String.class, mHints, hint);
    }

    /**
     * Get the format of this SliceItem.
     * <p>
     * The format will be one of the following types supported by the platform:
     * <li>{@link #FORMAT_SLICE}</li>
     * <li>{@link #FORMAT_TEXT}</li>
     * <li>{@link #FORMAT_IMAGE}</li>
     * <li>{@link #FORMAT_ACTION}</li>
     * <li>{@link #FORMAT_COLOR}</li>
     * <li>{@link #FORMAT_TIMESTAMP}</li>
     * <li>{@link #FORMAT_REMOTE_INPUT}</li>
     * @see #getSubType() ()
     */
    public String getFormat() {
        return mFormat;
    }

    /**
     * Get the sub-type of this SliceItem.
     * <p>
     * Subtypes provide additional information about the type of this information beyond basic
     * interpretations inferred by {@link #getFormat()}. For example a slice may contain
     * many {@link #FORMAT_TEXT} items, but only some of them may be {@link Slice#SUBTYPE_MESSAGE}.
     * @see #getFormat()
     */
    public String getSubType() {
        return mSubType;
    }

    /**
     * @return The text held by this {@link #FORMAT_TEXT} SliceItem
     */
    public CharSequence getText() {
        return (CharSequence) mObj;
    }

    /**
     * @return The icon held by this {@link #FORMAT_IMAGE} SliceItem
     */
    public Icon getIcon() {
        return (Icon) mObj;
    }

    /**
     * @return The pending intent held by this {@link #FORMAT_ACTION} SliceItem
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
     * @return The remote input held by this {@link #FORMAT_REMOTE_INPUT} SliceItem
     */
    public RemoteInput getRemoteInput() {
        return (RemoteInput) mObj;
    }

    /**
     * @return The color held by this {@link #FORMAT_COLOR} SliceItem
     */
    public int getColor() {
        return (Integer) mObj;
    }

    /**
     * @return The slice held by this {@link #FORMAT_ACTION} or {@link #FORMAT_SLICE} SliceItem
     */
    public Slice getSlice() {
        if (FORMAT_ACTION.equals(getFormat())) {
            return ((Pair<PendingIntent, Slice>) mObj).second;
        }
        return (Slice) mObj;
    }

    /**
     * @return The timestamp held by this {@link #FORMAT_TIMESTAMP} SliceItem
     */
    public long getTimestamp() {
        return (Long) mObj;
    }

    /**
     * @param hint The hint to check for
     * @return true if this item contains the given hint
     */
    public boolean hasHint(@Slice.SliceHint String hint) {
        return ArrayUtils.contains(mHints, hint);
    }

    /**
     * @hide
     */
    public SliceItem(Parcel in) {
        mHints = in.readStringArray();
        mFormat = in.readString();
        mSubType = in.readString();
        mObj = readObj(mFormat, in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStringArray(mHints);
        dest.writeString(mFormat);
        dest.writeString(mSubType);
        writeObj(dest, flags, mObj, mFormat);
    }

    /**
     * @hide
     */
    public boolean hasHints(@Slice.SliceHint String[] hints) {
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
    public boolean hasAnyHints(@Slice.SliceHint String[] hints) {
        if (hints == null) return false;
        for (String hint : hints) {
            if (ArrayUtils.contains(mHints, hint)) {
                return true;
            }
        }
        return false;
    }

    private static String getBaseType(String type) {
        int index = type.indexOf('/');
        if (index >= 0) {
            return type.substring(0, index);
        }
        return type;
    }

    private static void writeObj(Parcel dest, int flags, Object obj, String type) {
        switch (getBaseType(type)) {
            case FORMAT_SLICE:
            case FORMAT_IMAGE:
            case FORMAT_REMOTE_INPUT:
                ((Parcelable) obj).writeToParcel(dest, flags);
                break;
            case FORMAT_ACTION:
                ((Pair<PendingIntent, Slice>) obj).first.writeToParcel(dest, flags);
                ((Pair<PendingIntent, Slice>) obj).second.writeToParcel(dest, flags);
                break;
            case FORMAT_TEXT:
                TextUtils.writeToParcel((CharSequence) obj, dest, flags);
                break;
            case FORMAT_COLOR:
                dest.writeInt((Integer) obj);
                break;
            case FORMAT_TIMESTAMP:
                dest.writeLong((Long) obj);
                break;
        }
    }

    private static Object readObj(String type, Parcel in) {
        switch (getBaseType(type)) {
            case FORMAT_SLICE:
                return Slice.CREATOR.createFromParcel(in);
            case FORMAT_TEXT:
                return TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            case FORMAT_IMAGE:
                return Icon.CREATOR.createFromParcel(in);
            case FORMAT_ACTION:
                return new Pair<>(
                        PendingIntent.CREATOR.createFromParcel(in),
                        Slice.CREATOR.createFromParcel(in));
            case FORMAT_COLOR:
                return in.readInt();
            case FORMAT_TIMESTAMP:
                return in.readLong();
            case FORMAT_REMOTE_INPUT:
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
}
