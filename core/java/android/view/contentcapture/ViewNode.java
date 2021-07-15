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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.assist.AssistStructure;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.Parcel;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewStructure;
import android.view.ViewStructure.HtmlInfo.Builder;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;

import com.android.internal.util.Preconditions;

//TODO(b/122484602): add javadocs / implement Parcelable / implement
//TODO(b/122484602): for now it's extending ViewNode directly as it needs most of its properties,
// but it might be better to create a common, abstract android.view.ViewNode class that both extend
// instead
/** @hide */
@SystemApi
public final class ViewNode extends AssistStructure.ViewNode {

    private static final String TAG = ViewNode.class.getSimpleName();

    private static final long FLAGS_HAS_TEXT = 1L << 0;
    private static final long FLAGS_HAS_COMPLEX_TEXT = 1L << 1;
    private static final long FLAGS_VISIBILITY_MASK = View.VISIBLE | View.INVISIBLE | View.GONE;
    private static final long FLAGS_HAS_CLASSNAME = 1L << 4;
    private static final long FLAGS_HAS_AUTOFILL_ID = 1L << 5;
    private static final long FLAGS_HAS_AUTOFILL_PARENT_ID = 1L << 6;
    private static final long FLAGS_HAS_ID = 1L << 7;
    private static final long FLAGS_HAS_LARGE_COORDS = 1L << 8;
    private static final long FLAGS_HAS_SCROLL = 1L << 9;
    private static final long FLAGS_ASSIST_BLOCKED = 1L << 10;
    private static final long FLAGS_DISABLED = 1L << 11;
    private static final long FLAGS_CLICKABLE = 1L << 12;
    private static final long FLAGS_LONG_CLICKABLE = 1L << 13;
    private static final long FLAGS_CONTEXT_CLICKABLE = 1L << 14;
    private static final long FLAGS_FOCUSABLE = 1L << 15;
    private static final long FLAGS_FOCUSED = 1L << 16;
    private static final long FLAGS_ACCESSIBILITY_FOCUSED = 1L << 17;
    private static final long FLAGS_CHECKABLE = 1L << 18;
    private static final long FLAGS_CHECKED = 1L << 19;
    private static final long FLAGS_SELECTED = 1L << 20;
    private static final long FLAGS_ACTIVATED = 1L << 21;
    private static final long FLAGS_OPAQUE = 1L << 22;
    private static final long FLAGS_HAS_CONTENT_DESCRIPTION = 1L << 23;
    private static final long FLAGS_HAS_EXTRAS = 1L << 24;
    private static final long FLAGS_HAS_LOCALE_LIST = 1L << 25;
    private static final long FLAGS_HAS_INPUT_TYPE = 1L << 26;
    private static final long FLAGS_HAS_MIN_TEXT_EMS = 1L << 27;
    private static final long FLAGS_HAS_MAX_TEXT_EMS = 1L << 28;
    private static final long FLAGS_HAS_MAX_TEXT_LENGTH = 1L << 29;
    private static final long FLAGS_HAS_TEXT_ID_ENTRY = 1L << 30;
    private static final long FLAGS_HAS_AUTOFILL_TYPE = 1L << 31;
    private static final long FLAGS_HAS_AUTOFILL_VALUE = 1L << 32;
    private static final long FLAGS_HAS_AUTOFILL_HINTS = 1L << 33;
    private static final long FLAGS_HAS_AUTOFILL_OPTIONS = 1L << 34;
    private static final long FLAGS_HAS_HINT_ID_ENTRY = 1L << 35;
    private static final long FLAGS_HAS_MIME_TYPES = 1L << 36;

    /** Flags used to optimize what's written to the parcel */
    private long mFlags;

    private AutofillId mParentAutofillId;

    private AutofillId mAutofillId;
    private ViewNodeText mText;
    private String mClassName;
    private int mId = View.NO_ID;
    private String mIdPackage;
    private String mIdType;
    private String mIdEntry;
    private int mX;
    private int mY;
    private int mScrollX;
    private int mScrollY;
    private int mWidth;
    private int mHeight;
    private CharSequence mContentDescription;
    private Bundle mExtras;
    private LocaleList mLocaleList;
    private int mInputType;
    private int mMinEms = -1;
    private int mMaxEms = -1;
    private int mMaxLength = -1;
    private String mTextIdEntry;
    private String mHintIdEntry;
    private @View.AutofillType int mAutofillType = View.AUTOFILL_TYPE_NONE;
    private String[] mAutofillHints;
    private AutofillValue mAutofillValue;
    private CharSequence[] mAutofillOptions;
    private String[] mReceiveContentMimeTypes;

    /** @hide */
    public ViewNode() {
    }

    private ViewNode(long nodeFlags, @NonNull Parcel parcel) {
        mFlags = nodeFlags;

        if ((nodeFlags & FLAGS_HAS_AUTOFILL_ID) != 0) {
            mAutofillId = parcel.readParcelable(null);
        }
        if ((nodeFlags & FLAGS_HAS_AUTOFILL_PARENT_ID) != 0) {
            mParentAutofillId = parcel.readParcelable(null);
        }
        if ((nodeFlags & FLAGS_HAS_TEXT) != 0) {
            mText = new ViewNodeText(parcel, (nodeFlags & FLAGS_HAS_COMPLEX_TEXT) == 0);
        }
        if ((nodeFlags & FLAGS_HAS_CLASSNAME) != 0) {
            mClassName = parcel.readString();
        }
        if ((nodeFlags & FLAGS_HAS_ID) != 0) {
            mId = parcel.readInt();
            if (mId != View.NO_ID) {
                mIdEntry = parcel.readString();
                if (mIdEntry != null) {
                    mIdType = parcel.readString();
                    mIdPackage = parcel.readString();
                }
            }
        }
        if ((nodeFlags & FLAGS_HAS_LARGE_COORDS) != 0) {
            mX = parcel.readInt();
            mY = parcel.readInt();
            mWidth = parcel.readInt();
            mHeight = parcel.readInt();
        } else {
            int val = parcel.readInt();
            mX = val & 0x7fff;
            mY = (val >> 16) & 0x7fff;
            val = parcel.readInt();
            mWidth = val & 0x7fff;
            mHeight = (val >> 16) & 0x7fff;
        }
        if ((nodeFlags & FLAGS_HAS_SCROLL) != 0) {
            mScrollX = parcel.readInt();
            mScrollY = parcel.readInt();
        }
        if ((nodeFlags & FLAGS_HAS_CONTENT_DESCRIPTION) != 0) {
            mContentDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        }
        if ((nodeFlags & FLAGS_HAS_EXTRAS) != 0) {
            mExtras = parcel.readBundle();
        }
        if ((nodeFlags & FLAGS_HAS_LOCALE_LIST) != 0) {
            mLocaleList = parcel.readParcelable(null);
        }
        if ((nodeFlags & FLAGS_HAS_MIME_TYPES) != 0) {
            mReceiveContentMimeTypes = parcel.readStringArray();
        }
        if ((nodeFlags & FLAGS_HAS_INPUT_TYPE) != 0) {
            mInputType = parcel.readInt();
        }
        if ((nodeFlags & FLAGS_HAS_MIN_TEXT_EMS) != 0) {
            mMinEms = parcel.readInt();
        }
        if ((nodeFlags & FLAGS_HAS_MAX_TEXT_EMS) != 0) {
            mMaxEms = parcel.readInt();
        }
        if ((nodeFlags & FLAGS_HAS_MAX_TEXT_LENGTH) != 0) {
            mMaxLength = parcel.readInt();
        }
        if ((nodeFlags & FLAGS_HAS_TEXT_ID_ENTRY) != 0) {
            mTextIdEntry = parcel.readString();
        }
        if ((nodeFlags & FLAGS_HAS_AUTOFILL_TYPE) != 0) {
            mAutofillType = parcel.readInt();
        }
        if ((nodeFlags & FLAGS_HAS_AUTOFILL_HINTS) != 0) {
            mAutofillHints = parcel.readStringArray();
        }
        if ((nodeFlags & FLAGS_HAS_AUTOFILL_VALUE) != 0) {
            mAutofillValue = parcel.readParcelable(null);
        }
        if ((nodeFlags & FLAGS_HAS_AUTOFILL_OPTIONS) != 0) {
            mAutofillOptions = parcel.readCharSequenceArray();
        }
        if ((nodeFlags & FLAGS_HAS_HINT_ID_ENTRY) != 0) {
            mHintIdEntry = parcel.readString();
        }
    }

    /**
     * Returns the {@link AutofillId} of this view's parent, if the parent is also part of the
     * screen observation tree.
     */
    @Nullable
    public AutofillId getParentAutofillId() {
        return mParentAutofillId;
    }

    @Nullable
    @Override
    public AutofillId getAutofillId() {
        return mAutofillId;
    }

    @Nullable
    @Override
    public CharSequence getText() {
        return mText != null ? mText.mText : null;
    }

    @Nullable
    @Override
    public String getClassName() {
        return mClassName;
    }

    @Override
    public int getId() {
        return mId;
    }

    @Nullable
    @Override
    public String getIdPackage() {
        return mIdPackage;
    }

    @Nullable
    @Override
    public String getIdType() {
        return mIdType;
    }

    @Nullable
    @Override
    public String getIdEntry() {
        return mIdEntry;
    }

    @Override
    public int getLeft() {
        return mX;
    }

    @Override
    public int getTop() {
        return mY;
    }

    @Override
    public int getScrollX() {
        return mScrollX;
    }

    @Override
    public int getScrollY() {
        return mScrollY;
    }

    @Override
    public int getWidth() {
        return mWidth;
    }

    @Override
    public int getHeight() {
        return mHeight;
    }

    @Override
    public boolean isAssistBlocked() {
        return (mFlags & FLAGS_ASSIST_BLOCKED) != 0;
    }

    @Override
    public boolean isEnabled() {
        return (mFlags & FLAGS_DISABLED) == 0;
    }

    @Override
    public boolean isClickable() {
        return (mFlags & FLAGS_CLICKABLE) != 0;
    }

    @Override
    public boolean isLongClickable() {
        return (mFlags & FLAGS_LONG_CLICKABLE) != 0;
    }

    @Override
    public boolean isContextClickable() {
        return (mFlags & FLAGS_CONTEXT_CLICKABLE) != 0;
    }

    @Override
    public boolean isFocusable() {
        return (mFlags & FLAGS_FOCUSABLE) != 0;
    }

    @Override
    public boolean isFocused() {
        return (mFlags & FLAGS_FOCUSED) != 0;
    }

    @Override
    public boolean isAccessibilityFocused() {
        return (mFlags & FLAGS_ACCESSIBILITY_FOCUSED) != 0;
    }

    @Override
    public boolean isCheckable() {
        return (mFlags & FLAGS_CHECKABLE) != 0;
    }

    @Override
    public boolean isChecked() {
        return (mFlags & FLAGS_CHECKED) != 0;
    }

    @Override
    public boolean isSelected() {
        return (mFlags & FLAGS_SELECTED) != 0;
    }

    @Override
    public boolean isActivated() {
        return (mFlags & FLAGS_ACTIVATED) != 0;
    }

    @Override
    public boolean isOpaque() {
        return (mFlags & FLAGS_OPAQUE) != 0;
    }

    @Nullable
    @Override
    public CharSequence getContentDescription() {
        return mContentDescription;
    }

    @Nullable
    @Override
    public Bundle getExtras() {
        return mExtras;
    }

    @Nullable
    @Override
    public String getHint() {
        return mText != null ? mText.mHint : null;
    }

    @Nullable
    @Override
    public String getHintIdEntry() {
        return mHintIdEntry;
    }

    @Override
    public int getTextSelectionStart() {
        return mText != null ? mText.mTextSelectionStart : -1;
    }

    @Override
    public int getTextSelectionEnd() {
        return mText != null ? mText.mTextSelectionEnd : -1;
    }

    @Override
    public int getTextColor() {
        return mText != null ? mText.mTextColor : TEXT_COLOR_UNDEFINED;
    }

    @Override
    public int getTextBackgroundColor() {
        return mText != null ? mText.mTextBackgroundColor : TEXT_COLOR_UNDEFINED;
    }

    @Override
    public float getTextSize() {
        return mText != null ? mText.mTextSize : 0;
    }

    @Override
    public int getTextStyle() {
        return mText != null ? mText.mTextStyle : 0;
    }

    @Nullable
    @Override
    public int[] getTextLineCharOffsets() {
        return mText != null ? mText.mLineCharOffsets : null;
    }

    @Nullable
    @Override
    public int[] getTextLineBaselines() {
        return mText != null ? mText.mLineBaselines : null;
    }

    @Override
    public int getVisibility() {
        return (int) (mFlags & FLAGS_VISIBILITY_MASK);
    }

    @Override
    public int getInputType() {
        return mInputType;
    }

    @Override
    public int getMinTextEms() {
        return mMinEms;
    }

    @Override
    public int getMaxTextEms() {
        return mMaxEms;
    }

    @Override
    public int getMaxTextLength() {
        return mMaxLength;
    }

    @Nullable
    @Override
    public String getTextIdEntry() {
        return mTextIdEntry;
    }

    @Override
    public @View.AutofillType int getAutofillType() {
        return mAutofillType;
    }

    @Override
    @Nullable public String[] getAutofillHints() {
        return mAutofillHints;
    }

    @Override
    @Nullable public AutofillValue getAutofillValue() {
        return mAutofillValue;
    }

    @Override
    @Nullable public CharSequence[] getAutofillOptions() {
        return mAutofillOptions;
    }

    @Override
    @Nullable
    public String[] getReceiveContentMimeTypes() {
        return mReceiveContentMimeTypes;
    }

    @Nullable
    @Override
    public LocaleList getLocaleList() {
        return mLocaleList;
    }

    private void writeSelfToParcel(@NonNull Parcel parcel, int parcelFlags) {
        long nodeFlags = mFlags;

        if (mAutofillId != null) {
            nodeFlags |= FLAGS_HAS_AUTOFILL_ID;
        }

        if (mParentAutofillId != null) {
            nodeFlags |= FLAGS_HAS_AUTOFILL_PARENT_ID;
        }

        if (mText != null) {
            nodeFlags |= FLAGS_HAS_TEXT;
            if (!mText.isSimple()) {
                nodeFlags |= FLAGS_HAS_COMPLEX_TEXT;
            }
        }
        if (mClassName != null) {
            nodeFlags |= FLAGS_HAS_CLASSNAME;
        }
        if (mId != View.NO_ID) {
            nodeFlags |= FLAGS_HAS_ID;
        }
        if ((mX & ~0x7fff) != 0 || (mY & ~0x7fff) != 0
                || (mWidth & ~0x7fff) != 0 | (mHeight & ~0x7fff) != 0) {
            nodeFlags |= FLAGS_HAS_LARGE_COORDS;
        }
        if (mScrollX != 0 || mScrollY != 0) {
            nodeFlags |= FLAGS_HAS_SCROLL;
        }
        if (mContentDescription != null) {
            nodeFlags |= FLAGS_HAS_CONTENT_DESCRIPTION;
        }
        if (mExtras != null) {
            nodeFlags |= FLAGS_HAS_EXTRAS;
        }
        if (mLocaleList != null) {
            nodeFlags |= FLAGS_HAS_LOCALE_LIST;
        }
        if (mReceiveContentMimeTypes != null) {
            nodeFlags |= FLAGS_HAS_MIME_TYPES;
        }
        if (mInputType != 0) {
            nodeFlags |= FLAGS_HAS_INPUT_TYPE;
        }
        if (mMinEms > -1) {
            nodeFlags |= FLAGS_HAS_MIN_TEXT_EMS;
        }
        if (mMaxEms > -1) {
            nodeFlags |= FLAGS_HAS_MAX_TEXT_EMS;
        }
        if (mMaxLength > -1) {
            nodeFlags |= FLAGS_HAS_MAX_TEXT_LENGTH;
        }
        if (mTextIdEntry != null) {
            nodeFlags |= FLAGS_HAS_TEXT_ID_ENTRY;
        }
        if (mAutofillValue != null) {
            nodeFlags |= FLAGS_HAS_AUTOFILL_VALUE;
        }
        if (mAutofillType != View.AUTOFILL_TYPE_NONE) {
            nodeFlags |= FLAGS_HAS_AUTOFILL_TYPE;
        }
        if (mAutofillHints != null) {
            nodeFlags |= FLAGS_HAS_AUTOFILL_HINTS;
        }
        if (mAutofillOptions != null) {
            nodeFlags |= FLAGS_HAS_AUTOFILL_OPTIONS;
        }
        if (mHintIdEntry != null) {
            nodeFlags |= FLAGS_HAS_HINT_ID_ENTRY;
        }
        parcel.writeLong(nodeFlags);

        if ((nodeFlags & FLAGS_HAS_AUTOFILL_ID) != 0) {
            parcel.writeParcelable(mAutofillId, parcelFlags);
        }
        if ((nodeFlags & FLAGS_HAS_AUTOFILL_PARENT_ID) != 0) {
            parcel.writeParcelable(mParentAutofillId, parcelFlags);
        }
        if ((nodeFlags & FLAGS_HAS_TEXT) != 0) {
            mText.writeToParcel(parcel, (nodeFlags & FLAGS_HAS_COMPLEX_TEXT) == 0);
        }
        if ((nodeFlags & FLAGS_HAS_CLASSNAME) != 0) {
            parcel.writeString(mClassName);
        }
        if ((nodeFlags & FLAGS_HAS_ID) != 0) {
            parcel.writeInt(mId);
            if (mId != View.NO_ID) {
                parcel.writeString(mIdEntry);
                if (mIdEntry != null) {
                    parcel.writeString(mIdType);
                    parcel.writeString(mIdPackage);
                }
            }
        }
        if ((nodeFlags & FLAGS_HAS_LARGE_COORDS) != 0) {
            parcel.writeInt(mX);
            parcel.writeInt(mY);
            parcel.writeInt(mWidth);
            parcel.writeInt(mHeight);
        } else {
            parcel.writeInt((mY << 16) | mX);
            parcel.writeInt((mHeight << 16) | mWidth);
        }
        if ((nodeFlags & FLAGS_HAS_SCROLL) != 0) {
            parcel.writeInt(mScrollX);
            parcel.writeInt(mScrollY);
        }
        if ((nodeFlags & FLAGS_HAS_CONTENT_DESCRIPTION) != 0) {
            TextUtils.writeToParcel(mContentDescription, parcel, 0);
        }
        if ((nodeFlags & FLAGS_HAS_EXTRAS) != 0) {
            parcel.writeBundle(mExtras);
        }
        if ((nodeFlags & FLAGS_HAS_LOCALE_LIST) != 0) {
            parcel.writeParcelable(mLocaleList, 0);
        }
        if ((nodeFlags & FLAGS_HAS_MIME_TYPES) != 0) {
            parcel.writeStringArray(mReceiveContentMimeTypes);
        }
        if ((nodeFlags & FLAGS_HAS_INPUT_TYPE) != 0) {
            parcel.writeInt(mInputType);
        }
        if ((nodeFlags & FLAGS_HAS_MIN_TEXT_EMS) != 0) {
            parcel.writeInt(mMinEms);
        }
        if ((nodeFlags & FLAGS_HAS_MAX_TEXT_EMS) != 0) {
            parcel.writeInt(mMaxEms);
        }
        if ((nodeFlags & FLAGS_HAS_MAX_TEXT_LENGTH) != 0) {
            parcel.writeInt(mMaxLength);
        }
        if ((nodeFlags & FLAGS_HAS_TEXT_ID_ENTRY) != 0) {
            parcel.writeString(mTextIdEntry);
        }
        if ((nodeFlags & FLAGS_HAS_AUTOFILL_TYPE) != 0) {
            parcel.writeInt(mAutofillType);
        }
        if ((nodeFlags & FLAGS_HAS_AUTOFILL_HINTS) != 0) {
            parcel.writeStringArray(mAutofillHints);
        }
        if ((nodeFlags & FLAGS_HAS_AUTOFILL_VALUE) != 0) {
            parcel.writeParcelable(mAutofillValue, 0);
        }
        if ((nodeFlags & FLAGS_HAS_AUTOFILL_OPTIONS) != 0) {
            parcel.writeCharSequenceArray(mAutofillOptions);
        }
        if ((nodeFlags & FLAGS_HAS_HINT_ID_ENTRY) != 0) {
            parcel.writeString(mHintIdEntry);
        }
    }

    /** @hide */
    @TestApi
    public static void writeToParcel(@NonNull Parcel parcel, @Nullable ViewNode node, int flags) {
        if (node == null) {
            parcel.writeLong(0);
        } else {
            node.writeSelfToParcel(parcel, flags);
        }
    }

    /** @hide */
    @TestApi
    public static @Nullable ViewNode readFromParcel(@NonNull Parcel parcel) {
        final long nodeFlags = parcel.readLong();
        return nodeFlags == 0 ? null : new ViewNode(nodeFlags, parcel);
    }

    /** @hide */
    @TestApi
    public static final class ViewStructureImpl extends ViewStructure {

        final ViewNode mNode = new ViewNode();

        /** @hide */
        @TestApi
        public ViewStructureImpl(@NonNull View view) {
            mNode.mAutofillId = Preconditions.checkNotNull(view).getAutofillId();
            final ViewParent parent = view.getParent();
            if (parent instanceof View) {
                mNode.mParentAutofillId = ((View) parent).getAutofillId();
            }
        }

        /** @hide */
        @TestApi
        public ViewStructureImpl(@NonNull AutofillId parentId, long virtualId, int sessionId) {
            mNode.mParentAutofillId = Preconditions.checkNotNull(parentId);
            mNode.mAutofillId = new AutofillId(parentId, virtualId, sessionId);
        }

        /** @hide */
        @TestApi
        public ViewNode getNode() {
            return mNode;
        }

        @Override
        public void setId(int id, String packageName, String typeName, String entryName) {
            mNode.mId = id;
            mNode.mIdPackage = packageName;
            mNode.mIdType = typeName;
            mNode.mIdEntry = entryName;
        }

        @Override
        public void setDimens(int left, int top, int scrollX, int scrollY, int width, int height) {
            mNode.mX = left;
            mNode.mY = top;
            mNode.mScrollX = scrollX;
            mNode.mScrollY = scrollY;
            mNode.mWidth = width;
            mNode.mHeight = height;
        }

        @Override
        public void setTransformation(Matrix matrix) {
            Log.w(TAG, "setTransformation() is not supported");
        }

        @Override
        public void setElevation(float elevation) {
            Log.w(TAG, "setElevation() is not supported");
        }

        @Override
        public void setAlpha(float alpha) {
            Log.w(TAG, "setAlpha() is not supported");
        }

        @Override
        public void setVisibility(int visibility) {
            mNode.mFlags = (mNode.mFlags & ~FLAGS_VISIBILITY_MASK)
                    | (visibility & FLAGS_VISIBILITY_MASK);
        }

        @Override
        public void setAssistBlocked(boolean state) {
            mNode.mFlags = (mNode.mFlags & ~FLAGS_ASSIST_BLOCKED)
                    | (state ? FLAGS_ASSIST_BLOCKED : 0);
        }

        @Override
        public void setEnabled(boolean state) {
            mNode.mFlags = (mNode.mFlags & ~FLAGS_DISABLED) | (state ? 0 : FLAGS_DISABLED);
        }

        @Override
        public void setClickable(boolean state) {
            mNode.mFlags = (mNode.mFlags & ~FLAGS_CLICKABLE) | (state ? FLAGS_CLICKABLE : 0);
        }

        @Override
        public void setLongClickable(boolean state) {
            mNode.mFlags = (mNode.mFlags & ~FLAGS_LONG_CLICKABLE)
                    | (state ? FLAGS_LONG_CLICKABLE : 0);
        }

        @Override
        public void setContextClickable(boolean state) {
            mNode.mFlags = (mNode.mFlags & ~FLAGS_CONTEXT_CLICKABLE)
                    | (state ? FLAGS_CONTEXT_CLICKABLE : 0);
        }

        @Override
        public void setFocusable(boolean state) {
            mNode.mFlags = (mNode.mFlags & ~FLAGS_FOCUSABLE) | (state ? FLAGS_FOCUSABLE : 0);
        }

        @Override
        public void setFocused(boolean state) {
            mNode.mFlags = (mNode.mFlags & ~FLAGS_FOCUSED) | (state ? FLAGS_FOCUSED : 0);
        }

        @Override
        public void setAccessibilityFocused(boolean state) {
            mNode.mFlags = (mNode.mFlags & ~FLAGS_ACCESSIBILITY_FOCUSED)
                    | (state ? FLAGS_ACCESSIBILITY_FOCUSED : 0);
        }

        @Override
        public void setCheckable(boolean state) {
            mNode.mFlags = (mNode.mFlags & ~FLAGS_CHECKABLE) | (state ? FLAGS_CHECKABLE : 0);
        }

        @Override
        public void setChecked(boolean state) {
            mNode.mFlags = (mNode.mFlags & ~FLAGS_CHECKED) | (state ? FLAGS_CHECKED : 0);
        }

        @Override
        public void setSelected(boolean state) {
            mNode.mFlags = (mNode.mFlags & ~FLAGS_SELECTED) | (state ? FLAGS_SELECTED : 0);
        }

        @Override
        public void setActivated(boolean state) {
            mNode.mFlags = (mNode.mFlags & ~FLAGS_ACTIVATED) | (state ? FLAGS_ACTIVATED : 0);
        }

        @Override
        public void setOpaque(boolean opaque) {
            mNode.mFlags = (mNode.mFlags & ~FLAGS_OPAQUE) | (opaque ? FLAGS_OPAQUE : 0);
        }

        @Override
        public void setClassName(String className) {
            mNode.mClassName = className;
        }

        @Override
        public void setContentDescription(CharSequence contentDescription) {
            mNode.mContentDescription = contentDescription;
        }

        @Override
        public void setText(CharSequence text) {
            final ViewNodeText t = getNodeText();
            t.mText = TextUtils.trimNoCopySpans(text);
            t.mTextSelectionStart = t.mTextSelectionEnd = -1;
        }

        @Override
        public void setText(CharSequence text, int selectionStart, int selectionEnd) {
            final ViewNodeText t = getNodeText();
            t.mText = TextUtils.trimNoCopySpans(text);
            t.mTextSelectionStart = selectionStart;
            t.mTextSelectionEnd = selectionEnd;
        }

        @Override
        public void setTextStyle(float size, int fgColor, int bgColor, int style) {
            final ViewNodeText t = getNodeText();
            t.mTextColor = fgColor;
            t.mTextBackgroundColor = bgColor;
            t.mTextSize = size;
            t.mTextStyle = style;
        }

        @Override
        public void setTextLines(int[] charOffsets, int[] baselines) {
            final ViewNodeText t = getNodeText();
            t.mLineCharOffsets = charOffsets;
            t.mLineBaselines = baselines;
        }

        @Override
        public void setTextIdEntry(@NonNull String entryName) {
            mNode.mTextIdEntry = Preconditions.checkNotNull(entryName);
        }

        @Override
        public void setHint(CharSequence hint) {
            getNodeText().mHint = hint != null ? hint.toString() : null;
        }

        @Override
        public void setHintIdEntry(String entryName) {
            mNode.mHintIdEntry = Preconditions.checkNotNull(entryName);
        }

        @Override
        public CharSequence getText() {
            return mNode.getText();
        }

        @Override
        public int getTextSelectionStart() {
            return mNode.getTextSelectionStart();
        }

        @Override
        public int getTextSelectionEnd() {
            return mNode.getTextSelectionEnd();
        }

        @Override
        public CharSequence getHint() {
            return mNode.getHint();
        }

        @Override
        public Bundle getExtras() {
            if (mNode.mExtras != null) {
                return mNode.mExtras;
            }
            mNode.mExtras = new Bundle();
            return mNode.mExtras;
        }

        @Override
        public boolean hasExtras() {
            return mNode.mExtras != null;
        }

        @Override
        public void setChildCount(int num) {
            Log.w(TAG, "setChildCount() is not supported");
        }

        @Override
        public int addChildCount(int num) {
            Log.w(TAG, "addChildCount() is not supported");
            return 0;
        }

        @Override
        public int getChildCount() {
            Log.w(TAG, "getChildCount() is not supported");
            return 0;
        }

        @Override
        public ViewStructure newChild(int index) {
            Log.w(TAG, "newChild() is not supported");
            return null;
        }

        @Override
        public ViewStructure asyncNewChild(int index) {
            Log.w(TAG, "asyncNewChild() is not supported");
            return null;
        }

        @Override
        public AutofillId getAutofillId() {
            return mNode.mAutofillId;
        }

        @Override
        public void setAutofillId(AutofillId id) {
            mNode.mAutofillId = Preconditions.checkNotNull(id);
        }


        @Override
        public void setAutofillId(AutofillId parentId, int virtualId) {
            mNode.mParentAutofillId = Preconditions.checkNotNull(parentId);
            mNode.mAutofillId = new AutofillId(parentId, virtualId);
        }

        @Override
        public void setAutofillType(@View.AutofillType int type) {
            mNode.mAutofillType = type;
        }

        @Override
        public void setReceiveContentMimeTypes(@Nullable String[] mimeTypes) {
            mNode.mReceiveContentMimeTypes = mimeTypes;
        }

        @Override
        public void setAutofillHints(String[] hints) {
            mNode.mAutofillHints = hints;
        }

        @Override
        public void setAutofillValue(AutofillValue value) {
            mNode.mAutofillValue = value;
        }

        @Override
        public void setAutofillOptions(CharSequence[] options) {
            mNode.mAutofillOptions = options;
        }

        @Override
        public void setInputType(int inputType) {
            mNode.mInputType = inputType;
        }

        @Override
        public void setMinTextEms(int minEms) {
            mNode.mMinEms = minEms;
        }

        @Override
        public void setMaxTextEms(int maxEms) {
            mNode.mMaxEms = maxEms;
        }

        @Override
        public void setMaxTextLength(int maxLength) {
            mNode.mMaxLength = maxLength;
        }

        @Override
        public void setDataIsSensitive(boolean sensitive) {
            Log.w(TAG, "setDataIsSensitive() is not supported");
        }

        @Override
        public void asyncCommit() {
            Log.w(TAG, "asyncCommit() is not supported");
        }

        @Override
        public Rect getTempRect() {
            Log.w(TAG, "getTempRect() is not supported");
            return null;
        }

        @Override
        public void setWebDomain(String domain) {
            Log.w(TAG, "setWebDomain() is not supported");
        }

        @Override
        public void setLocaleList(LocaleList localeList) {
            mNode.mLocaleList = localeList;
        }

        @Override
        public Builder newHtmlInfoBuilder(String tagName) {
            Log.w(TAG, "newHtmlInfoBuilder() is not supported");
            return null;
        }

        @Override
        public void setHtmlInfo(HtmlInfo htmlInfo) {
            Log.w(TAG, "setHtmlInfo() is not supported");
        }

        private ViewNodeText getNodeText() {
            if (mNode.mText != null) {
                return mNode.mText;
            }
            mNode.mText = new ViewNodeText();
            return mNode.mText;
        }
    }

    //TODO(b/122484602): copied 'as-is' from AssistStructure, except for writeSensitive
    static final class ViewNodeText {
        CharSequence mText;
        float mTextSize;
        int mTextStyle;
        int mTextColor = ViewNode.TEXT_COLOR_UNDEFINED;
        int mTextBackgroundColor = ViewNode.TEXT_COLOR_UNDEFINED;
        int mTextSelectionStart;
        int mTextSelectionEnd;
        int[] mLineCharOffsets;
        int[] mLineBaselines;
        String mHint;

        ViewNodeText() {
        }

        boolean isSimple() {
            return mTextBackgroundColor == ViewNode.TEXT_COLOR_UNDEFINED
                    && mTextSelectionStart == 0 && mTextSelectionEnd == 0
                    && mLineCharOffsets == null && mLineBaselines == null && mHint == null;
        }

        ViewNodeText(Parcel in, boolean simple) {
            mText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            mTextSize = in.readFloat();
            mTextStyle = in.readInt();
            mTextColor = in.readInt();
            if (!simple) {
                mTextBackgroundColor = in.readInt();
                mTextSelectionStart = in.readInt();
                mTextSelectionEnd = in.readInt();
                mLineCharOffsets = in.createIntArray();
                mLineBaselines = in.createIntArray();
                mHint = in.readString();
            }
        }

        void writeToParcel(Parcel out, boolean simple) {
            TextUtils.writeToParcel(mText, out, 0);
            out.writeFloat(mTextSize);
            out.writeInt(mTextStyle);
            out.writeInt(mTextColor);
            if (!simple) {
                out.writeInt(mTextBackgroundColor);
                out.writeInt(mTextSelectionStart);
                out.writeInt(mTextSelectionEnd);
                out.writeIntArray(mLineCharOffsets);
                out.writeIntArray(mLineBaselines);
                out.writeString(mHint);
            }
        }
    }
}
