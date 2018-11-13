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
package android.view.intelligence;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.assist.AssistStructure;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.Parcel;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewStructure;
import android.view.ViewStructure.HtmlInfo.Builder;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;

import com.android.internal.util.Preconditions;

//TODO(b/111276913): add javadocs / implement Parcelable / implement
//TODO(b/111276913): for now it's extending ViewNode directly as it needs most of its properties,
// but it might be better to create a common, abstract android.view.ViewNode class that both extend
// instead
/** @hide */
@SystemApi
public final class ViewNode extends AssistStructure.ViewNode {

    private static final String TAG = "ViewNode";

    private AutofillId mParentAutofillId;

    // TODO(b/111276913): temporarily setting some fields here while they're not accessible from the
    // superclass
    private AutofillId mAutofillId;
    private CharSequence mText;
    private String mClassName;

    /** @hide */
    public ViewNode() {
    }

    /**
     * Returns the {@link AutofillId} of this view's parent, if the parent is also part of the
     * screen observation tree.
     */
    @Nullable
    public AutofillId getParentAutofillId() {
        return mParentAutofillId;
    }

    // TODO(b/111276913): temporarily overwriting some methods
    @Override
    public AutofillId getAutofillId() {
        return mAutofillId;
    }
    @Override
    public CharSequence getText() {
        return mText;
    }
    @Override
    public String getClassName() {
        return mClassName;
    }

    /** @hide */
    public static void writeToParcel(@NonNull Parcel parcel, @Nullable ViewNode node, int flags) {
        if (node == null) {
            parcel.writeParcelable(null, flags);
            return;
        }
        parcel.writeParcelable(node.mAutofillId, flags);
        parcel.writeParcelable(node.mParentAutofillId, flags);
        parcel.writeCharSequence(node.mText);
        parcel.writeString(node.mClassName);
    }

    /** @hide */
    public static @Nullable ViewNode readFromParcel(@NonNull Parcel parcel) {
        final AutofillId id = parcel.readParcelable(null);
        if (id == null) return null;

        final ViewNode node = new ViewNode();

        node.mAutofillId = id;
        node.mParentAutofillId = parcel.readParcelable(null);
        node.mText = parcel.readCharSequence();
        node.mClassName = parcel.readString();

        return node;
    }

    /** @hide */
    static final class ViewStructureImpl extends ViewStructure {

        final ViewNode mNode = new ViewNode();

        ViewStructureImpl(@NonNull View view) {
            mNode.mAutofillId = Preconditions.checkNotNull(view).getAutofillId();
            final ViewParent parent = view.getParent();
            if (parent instanceof View) {
                mNode.mParentAutofillId = ((View) parent).getAutofillId();
            }
        }

        ViewStructureImpl(@NonNull AutofillId parentId, int virtualId) {
            mNode.mParentAutofillId = Preconditions.checkNotNull(parentId);
            mNode.mAutofillId = new AutofillId(parentId, virtualId);
        }

        @Override
        public void setId(int id, String packageName, String typeName, String entryName) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setDimens(int left, int top, int scrollX, int scrollY, int width, int height) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setTransformation(Matrix matrix) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setElevation(float elevation) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setAlpha(float alpha) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setVisibility(int visibility) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setAssistBlocked(boolean state) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setEnabled(boolean state) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setClickable(boolean state) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setLongClickable(boolean state) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setContextClickable(boolean state) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setFocusable(boolean state) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setFocused(boolean state) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setAccessibilityFocused(boolean state) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setCheckable(boolean state) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setChecked(boolean state) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setSelected(boolean state) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setActivated(boolean state) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setOpaque(boolean opaque) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setClassName(String className) {
            // TODO(b/111276913): temporarily setting directly; should be done on superclass instead
            mNode.mClassName = className;
        }

        @Override
        public void setContentDescription(CharSequence contentDescription) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setText(CharSequence text) {
            // TODO(b/111276913): temporarily setting directly; should be done on superclass instead
            mNode.mText = text;
        }

        @Override
        public void setText(CharSequence text, int selectionStart, int selectionEnd) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setTextStyle(float size, int fgColor, int bgColor, int style) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setTextLines(int[] charOffsets, int[] baselines) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setHint(CharSequence hint) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public CharSequence getText() {
            // TODO(b/111276913): temporarily getting directly; should be done on superclass instead
            return mNode.mText;
        }

        @Override
        public int getTextSelectionStart() {
            // TODO(b/111276913): implement or move to superclass
            return 0;
        }

        @Override
        public int getTextSelectionEnd() {
            // TODO(b/111276913): implement or move to superclass
            return 0;
        }

        @Override
        public CharSequence getHint() {
            // TODO(b/111276913): implement or move to superclass
            return null;
        }

        @Override
        public Bundle getExtras() {
            // TODO(b/111276913): implement or move to superclass
            return null;
        }

        @Override
        public boolean hasExtras() {
            // TODO(b/111276913): implement or move to superclass
            return false;
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
            // TODO(b/111276913): temporarily getting directly; should be done on superclass instead
            return mNode.mAutofillId;
        }

        @Override
        public void setAutofillId(AutofillId id) {
            // TODO(b/111276913): temporarily setting directly; should be done on superclass instead
            mNode.mAutofillId = id;
        }

        @Override
        public void setAutofillId(AutofillId parentId, int virtualId) {
            // TODO(b/111276913): temporarily setting directly; should be done on superclass instead
            mNode.mAutofillId = new AutofillId(parentId, virtualId);
        }

        @Override
        public void setAutofillType(int type) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setAutofillHints(String[] hint) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setAutofillValue(AutofillValue value) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setAutofillOptions(CharSequence[] options) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setInputType(int inputType) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void setDataIsSensitive(boolean sensitive) {
            // TODO(b/111276913): implement or move to superclass
        }

        @Override
        public void asyncCommit() {
            Log.w(TAG, "asyncCommit() is not supported");
        }

        @Override
        public Rect getTempRect() {
            // TODO(b/111276913): implement or move to superclass
            return null;
        }

        @Override
        public void setWebDomain(String domain) {
            Log.w(TAG, "setWebDomain() is not supported");
        }

        @Override
        public void setLocaleList(LocaleList localeList) {
            // TODO(b/111276913): implement or move to superclass
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
    }
}
