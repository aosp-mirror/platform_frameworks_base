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
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Pair;
import android.widget.RemoteViews;

import com.android.internal.util.ArrayUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
 * <li>{@link #FORMAT_INT}</li>
 * <li>{@link #FORMAT_LONG}</li>
 * <li>{@link #FORMAT_REMOTE_INPUT}</li>
 * <li>{@link #FORMAT_BUNDLE}</li>
 *
 * The hints that a {@link SliceItem} are a set of strings which annotate
 * the content. The hints that are guaranteed to be understood by the system
 * are defined on {@link Slice}.
 * @deprecated Slice framework has been deprecated, it will not receive any updates from
 *          {@link android.os.Build.VANILLA_ICE_CREAM} and forward. If you are looking for a
 *          framework that sends displayable data from one app to another, consider using
 *          {@link android.app.appsearch.AppSearchManager}.
 */
@Deprecated
public final class SliceItem implements Parcelable {

    private static final String TAG = "SliceItem";

    /**
     * @hide
     */
    @StringDef(prefix = { "FORMAT_" }, value = {
            FORMAT_SLICE,
            FORMAT_TEXT,
            FORMAT_IMAGE,
            FORMAT_ACTION,
            FORMAT_INT,
            FORMAT_LONG,
            FORMAT_REMOTE_INPUT,
            FORMAT_BUNDLE,
    })
    @Retention(RetentionPolicy.SOURCE)
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
     * A {@link SliceItem} that contains an int.
     */
    public static final String FORMAT_INT = "int";
    /**
     * A {@link SliceItem} that contains a long.
     */
    public static final String FORMAT_LONG = "long";
    /**
     * A {@link SliceItem} that contains a {@link RemoteInput}.
     */
    public static final String FORMAT_REMOTE_INPUT = "input";
    /**
     * A {@link SliceItem} that contains a {@link Bundle}.
     */
    public static final String FORMAT_BUNDLE = "bundle";

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
            List<String> hints) {
        this(obj, format, subType, hints.toArray(new String[hints.size()]));
    }

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
     * Get the format of this SliceItem.
     * <p>
     * The format will be one of the following types supported by the platform:
     * <li>{@link #FORMAT_SLICE}</li>
     * <li>{@link #FORMAT_TEXT}</li>
     * <li>{@link #FORMAT_IMAGE}</li>
     * <li>{@link #FORMAT_ACTION}</li>
     * <li>{@link #FORMAT_INT}</li>
     * <li>{@link #FORMAT_LONG}</li>
     * <li>{@link #FORMAT_REMOTE_INPUT}</li>
     * <li>{@link #FORMAT_BUNDLE}</li>
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
     * @return The parcelable held by this {@link #FORMAT_BUNDLE} SliceItem
     */
    public Bundle getBundle() {
        return (Bundle) mObj;
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
     * @return The color held by this {@link #FORMAT_INT} SliceItem
     */
    public int getInt() {
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
     * @return The long held by this {@link #FORMAT_LONG} SliceItem
     */
    public long getLong() {
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
            case FORMAT_BUNDLE:
                ((Parcelable) obj).writeToParcel(dest, flags);
                break;
            case FORMAT_ACTION:
                ((Pair<PendingIntent, Slice>) obj).first.writeToParcel(dest, flags);
                ((Pair<PendingIntent, Slice>) obj).second.writeToParcel(dest, flags);
                break;
            case FORMAT_TEXT:
                TextUtils.writeToParcel((CharSequence) obj, dest, flags);
                break;
            case FORMAT_INT:
                dest.writeInt((Integer) obj);
                break;
            case FORMAT_LONG:
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
            case FORMAT_INT:
                return in.readInt();
            case FORMAT_LONG:
                return in.readLong();
            case FORMAT_REMOTE_INPUT:
                return RemoteInput.CREATOR.createFromParcel(in);
            case FORMAT_BUNDLE:
                return Bundle.CREATOR.createFromParcel(in);
        }
        throw new RuntimeException("Unsupported type " + type);
    }

    public static final @android.annotation.NonNull Creator<SliceItem> CREATOR = new Creator<SliceItem>() {
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
