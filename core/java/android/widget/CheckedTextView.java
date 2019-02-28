/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.widget;

import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.RemotableViewMethod;
import android.view.ViewDebug;
import android.view.ViewHierarchyEncoder;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inspector.InspectableProperty;

import com.android.internal.R;

/**
 * An extension to {@link TextView} that supports the {@link Checkable}
 * interface and displays.
 * <p>
 * This is useful when used in a {@link android.widget.ListView ListView} where
 * the {@link android.widget.ListView#setChoiceMode(int) setChoiceMode} has
 * been set to something other than
 * {@link android.widget.ListView#CHOICE_MODE_NONE CHOICE_MODE_NONE}.
 *
 * @attr ref android.R.styleable#CheckedTextView_checked
 * @attr ref android.R.styleable#CheckedTextView_checkMark
 */
public class CheckedTextView extends TextView implements Checkable {
    private boolean mChecked;

    private int mCheckMarkResource;
    @UnsupportedAppUsage
    private Drawable mCheckMarkDrawable;
    private ColorStateList mCheckMarkTintList = null;
    private PorterDuff.Mode mCheckMarkTintMode = null;
    private boolean mHasCheckMarkTint = false;
    private boolean mHasCheckMarkTintMode = false;

    private int mBasePadding;
    private int mCheckMarkWidth;
    @UnsupportedAppUsage
    private int mCheckMarkGravity = Gravity.END;

    private boolean mNeedRequestlayout;

    private static final int[] CHECKED_STATE_SET = {
        R.attr.state_checked
    };

    public CheckedTextView(Context context) {
        this(context, null);
    }

    public CheckedTextView(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.checkedTextViewStyle);
    }

    public CheckedTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public CheckedTextView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.CheckedTextView, defStyleAttr, defStyleRes);
        saveAttributeDataForStyleable(context,  R.styleable.CheckedTextView,
                attrs, a, defStyleAttr, defStyleRes);

        final Drawable d = a.getDrawable(R.styleable.CheckedTextView_checkMark);
        if (d != null) {
            setCheckMarkDrawable(d);
        }

        if (a.hasValue(R.styleable.CheckedTextView_checkMarkTintMode)) {
            mCheckMarkTintMode = Drawable.parseTintMode(a.getInt(
                    R.styleable.CheckedTextView_checkMarkTintMode, -1), mCheckMarkTintMode);
            mHasCheckMarkTintMode = true;
        }

        if (a.hasValue(R.styleable.CheckedTextView_checkMarkTint)) {
            mCheckMarkTintList = a.getColorStateList(R.styleable.CheckedTextView_checkMarkTint);
            mHasCheckMarkTint = true;
        }

        mCheckMarkGravity = a.getInt(R.styleable.CheckedTextView_checkMarkGravity, Gravity.END);

        final boolean checked = a.getBoolean(R.styleable.CheckedTextView_checked, false);
        setChecked(checked);

        a.recycle();

        applyCheckMarkTint();
    }

    public void toggle() {
        setChecked(!mChecked);
    }

    @ViewDebug.ExportedProperty
    @InspectableProperty
    public boolean isChecked() {
        return mChecked;
    }

    /**
     * Sets the checked state of this view.
     *
     * @param checked {@code true} set the state to checked, {@code false} to
     *                uncheck
     */
    public void setChecked(boolean checked) {
        if (mChecked != checked) {
            mChecked = checked;
            refreshDrawableState();
            notifyViewAccessibilityStateChangedIfNeeded(
                    AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED);
        }
    }

    /**
     * Sets the check mark to the drawable with the specified resource ID.
     * <p>
     * When this view is checked, the drawable's state set will include
     * {@link android.R.attr#state_checked}.
     *
     * @param resId the resource identifier of drawable to use as the check
     *              mark
     * @attr ref android.R.styleable#CheckedTextView_checkMark
     * @see #setCheckMarkDrawable(Drawable)
     * @see #getCheckMarkDrawable()
     */
    public void setCheckMarkDrawable(@DrawableRes int resId) {
        if (resId != 0 && resId == mCheckMarkResource) {
            return;
        }

        final Drawable d = resId != 0 ? getContext().getDrawable(resId) : null;
        setCheckMarkDrawableInternal(d, resId);
    }

    /**
     * Set the check mark to the specified drawable.
     * <p>
     * When this view is checked, the drawable's state set will include
     * {@link android.R.attr#state_checked}.
     *
     * @param d the drawable to use for the check mark
     * @attr ref android.R.styleable#CheckedTextView_checkMark
     * @see #setCheckMarkDrawable(int)
     * @see #getCheckMarkDrawable()
     */
    public void setCheckMarkDrawable(@Nullable Drawable d) {
        setCheckMarkDrawableInternal(d, 0);
    }

    private void setCheckMarkDrawableInternal(@Nullable Drawable d, @DrawableRes int resId) {
        if (mCheckMarkDrawable != null) {
            mCheckMarkDrawable.setCallback(null);
            unscheduleDrawable(mCheckMarkDrawable);
        }

        mNeedRequestlayout = (d != mCheckMarkDrawable);

        if (d != null) {
            d.setCallback(this);
            d.setVisible(getVisibility() == VISIBLE, false);
            d.setState(CHECKED_STATE_SET);

            // Record the intrinsic dimensions when in "checked" state.
            setMinHeight(d.getIntrinsicHeight());
            mCheckMarkWidth = d.getIntrinsicWidth();

            d.setState(getDrawableState());
        } else {
            mCheckMarkWidth = 0;
        }

        mCheckMarkDrawable = d;
        mCheckMarkResource = resId;

        applyCheckMarkTint();

        // Do padding resolution. This will call internalSetPadding() and do a
        // requestLayout() if needed.
        resolvePadding();
    }

    /**
     * Applies a tint to the check mark drawable. Does not modify the
     * current tint mode, which is {@link PorterDuff.Mode#SRC_IN} by default.
     * <p>
     * Subsequent calls to {@link #setCheckMarkDrawable(Drawable)} will
     * automatically mutate the drawable and apply the specified tint and
     * tint mode using
     * {@link Drawable#setTintList(ColorStateList)}.
     *
     * @param tint the tint to apply, may be {@code null} to clear tint
     *
     * @attr ref android.R.styleable#CheckedTextView_checkMarkTint
     * @see #getCheckMarkTintList()
     * @see Drawable#setTintList(ColorStateList)
     */
    public void setCheckMarkTintList(@Nullable ColorStateList tint) {
        mCheckMarkTintList = tint;
        mHasCheckMarkTint = true;

        applyCheckMarkTint();
    }

    /**
     * Returns the tint applied to the check mark drawable, if specified.
     *
     * @return the tint applied to the check mark drawable
     * @attr ref android.R.styleable#CheckedTextView_checkMarkTint
     * @see #setCheckMarkTintList(ColorStateList)
     */
    @InspectableProperty(name = "checkMarkTint")
    @Nullable
    public ColorStateList getCheckMarkTintList() {
        return mCheckMarkTintList;
    }

    /**
     * Specifies the blending mode used to apply the tint specified by
     * {@link #setCheckMarkTintList(ColorStateList)} to the check mark
     * drawable. The default mode is {@link PorterDuff.Mode#SRC_IN}.
     *
     * @param tintMode the blending mode used to apply the tint, may be
     *                 {@code null} to clear tint
     * @attr ref android.R.styleable#CheckedTextView_checkMarkTintMode
     * @see #setCheckMarkTintList(ColorStateList)
     * @see Drawable#setTintMode(PorterDuff.Mode)
     */
    public void setCheckMarkTintMode(@Nullable PorterDuff.Mode tintMode) {
        mCheckMarkTintMode = tintMode;
        mHasCheckMarkTintMode = true;

        applyCheckMarkTint();
    }

    /**
     * Returns the blending mode used to apply the tint to the check mark
     * drawable, if specified.
     *
     * @return the blending mode used to apply the tint to the check mark
     *         drawable
     * @attr ref android.R.styleable#CheckedTextView_checkMarkTintMode
     * @see #setCheckMarkTintMode(PorterDuff.Mode)
     */
    @InspectableProperty
    @Nullable
    public PorterDuff.Mode getCheckMarkTintMode() {
        return mCheckMarkTintMode;
    }

    private void applyCheckMarkTint() {
        if (mCheckMarkDrawable != null && (mHasCheckMarkTint || mHasCheckMarkTintMode)) {
            mCheckMarkDrawable = mCheckMarkDrawable.mutate();

            if (mHasCheckMarkTint) {
                mCheckMarkDrawable.setTintList(mCheckMarkTintList);
            }

            if (mHasCheckMarkTintMode) {
                mCheckMarkDrawable.setTintMode(mCheckMarkTintMode);
            }

            // The drawable (or one of its children) may not have been
            // stateful before applying the tint, so let's try again.
            if (mCheckMarkDrawable.isStateful()) {
                mCheckMarkDrawable.setState(getDrawableState());
            }
        }
    }

    @RemotableViewMethod
    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);

        if (mCheckMarkDrawable != null) {
            mCheckMarkDrawable.setVisible(visibility == VISIBLE, false);
        }
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();

        if (mCheckMarkDrawable != null) {
            mCheckMarkDrawable.jumpToCurrentState();
        }
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == mCheckMarkDrawable || super.verifyDrawable(who);
    }

    /**
     * Gets the checkmark drawable
     *
     * @return The drawable use to represent the checkmark, if any.
     *
     * @see #setCheckMarkDrawable(Drawable)
     * @see #setCheckMarkDrawable(int)
     *
     * @attr ref android.R.styleable#CheckedTextView_checkMark
     */
    @InspectableProperty(name = "checkMark")
    public Drawable getCheckMarkDrawable() {
        return mCheckMarkDrawable;
    }

    /**
     * @hide
     */
    @Override
    protected void internalSetPadding(int left, int top, int right, int bottom) {
        super.internalSetPadding(left, top, right, bottom);
        setBasePadding(isCheckMarkAtStart());
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updatePadding();
    }

    private void updatePadding() {
        resetPaddingToInitialValues();
        int newPadding = (mCheckMarkDrawable != null) ?
                mCheckMarkWidth + mBasePadding : mBasePadding;
        if (isCheckMarkAtStart()) {
            mNeedRequestlayout |= (mPaddingLeft != newPadding);
            mPaddingLeft = newPadding;
        } else {
            mNeedRequestlayout |= (mPaddingRight != newPadding);
            mPaddingRight = newPadding;
        }
        if (mNeedRequestlayout) {
            requestLayout();
            mNeedRequestlayout = false;
        }
    }

    private void setBasePadding(boolean checkmarkAtStart) {
        if (checkmarkAtStart) {
            mBasePadding = mPaddingLeft;
        } else {
            mBasePadding = mPaddingRight;
        }
    }

    private boolean isCheckMarkAtStart() {
        final int gravity = Gravity.getAbsoluteGravity(mCheckMarkGravity, getLayoutDirection());
        final int hgrav = gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
        return hgrav == Gravity.LEFT;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final Drawable checkMarkDrawable = mCheckMarkDrawable;
        if (checkMarkDrawable != null) {
            final int verticalGravity = getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
            final int height = checkMarkDrawable.getIntrinsicHeight();

            int y = 0;

            switch (verticalGravity) {
                case Gravity.BOTTOM:
                    y = getHeight() - height;
                    break;
                case Gravity.CENTER_VERTICAL:
                    y = (getHeight() - height) / 2;
                    break;
            }

            final boolean checkMarkAtStart = isCheckMarkAtStart();
            final int width = getWidth();
            final int top = y;
            final int bottom = top + height;
            final int left;
            final int right;
            if (checkMarkAtStart) {
                left = mBasePadding;
                right = left + mCheckMarkWidth;
            } else {
                right = width - mBasePadding;
                left = right - mCheckMarkWidth;
            }
            checkMarkDrawable.setBounds(mScrollX + left, top, mScrollX + right, bottom);
            checkMarkDrawable.draw(canvas);

            final Drawable background = getBackground();
            if (background != null) {
                background.setHotspotBounds(mScrollX + left, top, mScrollX + right, bottom);
            }
        }
    }

    @Override
    protected int[] onCreateDrawableState(int extraSpace) {
        final int[] drawableState = super.onCreateDrawableState(extraSpace + 1);
        if (isChecked()) {
            mergeDrawableStates(drawableState, CHECKED_STATE_SET);
        }
        return drawableState;
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();

        final Drawable checkMarkDrawable = mCheckMarkDrawable;
        if (checkMarkDrawable != null && checkMarkDrawable.isStateful()
                && checkMarkDrawable.setState(getDrawableState())) {
            invalidateDrawable(checkMarkDrawable);
        }
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        super.drawableHotspotChanged(x, y);

        if (mCheckMarkDrawable != null) {
            mCheckMarkDrawable.setHotspot(x, y);
        }
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return CheckedTextView.class.getName();
    }

    static class SavedState extends BaseSavedState {
        boolean checked;

        /**
         * Constructor called from {@link CheckedTextView#onSaveInstanceState()}
         */
        SavedState(Parcelable superState) {
            super(superState);
        }

        /**
         * Constructor called from {@link #CREATOR}
         */
        private SavedState(Parcel in) {
            super(in);
            checked = (Boolean)in.readValue(null);
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeValue(checked);
        }

        @Override
        public String toString() {
            return "CheckedTextView.SavedState{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " checked=" + checked + "}";
        }

        public static final @android.annotation.NonNull Parcelable.Creator<SavedState> CREATOR
                = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();

        SavedState ss = new SavedState(superState);

        ss.checked = isChecked();
        return ss;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;

        super.onRestoreInstanceState(ss.getSuperState());
        setChecked(ss.checked);
        requestLayout();
    }

    /** @hide */
    @Override
    public void onInitializeAccessibilityEventInternal(AccessibilityEvent event) {
        super.onInitializeAccessibilityEventInternal(event);
        event.setChecked(mChecked);
    }

    /** @hide */
    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);
        info.setCheckable(true);
        info.setChecked(mChecked);
    }

    /** @hide */
    @Override
    protected void encodeProperties(@NonNull ViewHierarchyEncoder stream) {
        super.encodeProperties(stream);
        stream.addProperty("text:checked", isChecked());
    }
}
