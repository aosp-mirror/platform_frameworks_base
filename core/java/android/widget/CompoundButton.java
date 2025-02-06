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

import static android.view.accessibility.Flags.triStateChecked;

import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.RemotableViewMethod;
import android.view.SoundEffectConstants;
import android.view.ViewDebug;
import android.view.ViewHierarchyEncoder;
import android.view.ViewStructure;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.inspector.InspectableProperty;

import com.android.internal.R;

/**
 * <p>
 * A button with two states, checked and unchecked. When the button is pressed
 * or clicked, the state changes automatically.
 * </p>
 *
 * <p><strong>XML attributes</strong></p>
 * <p>
 * See {@link android.R.styleable#CompoundButton
 * CompoundButton Attributes}, {@link android.R.styleable#Button Button
 * Attributes}, {@link android.R.styleable#TextView TextView Attributes}, {@link
 * android.R.styleable#View View Attributes}
 * </p>
 */
public abstract class CompoundButton extends Button implements Checkable {
    private static final String LOG_TAG = CompoundButton.class.getSimpleName();

    private boolean mChecked;
    @UnsupportedAppUsage
    private boolean mBroadcasting;

    @UnsupportedAppUsage
    private Drawable mButtonDrawable;
    private ColorStateList mButtonTintList = null;
    private BlendMode mButtonBlendMode = null;
    private boolean mHasButtonTint = false;
    private boolean mHasButtonBlendMode = false;

    @UnsupportedAppUsage
    private OnCheckedChangeListener mOnCheckedChangeListener;
    private OnCheckedChangeListener mOnCheckedChangeWidgetListener;

    // Indicates whether the toggle state was set from resources or dynamically, so it can be used
    // to sanitize autofill requests.
    private boolean mCheckedFromResource = false;

    private CharSequence mCustomStateDescription = null;

    private static final int[] CHECKED_STATE_SET = {
        R.attr.state_checked
    };

    public CompoundButton(Context context) {
        this(context, null);
    }

    public CompoundButton(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CompoundButton(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public CompoundButton(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, com.android.internal.R.styleable.CompoundButton, defStyleAttr, defStyleRes);
        saveAttributeDataForStyleable(context, com.android.internal.R.styleable.CompoundButton,
                attrs, a, defStyleAttr, defStyleRes);

        final Drawable d = a.getDrawable(com.android.internal.R.styleable.CompoundButton_button);
        if (d != null) {
            setButtonDrawable(d);
        }

        if (a.hasValue(R.styleable.CompoundButton_buttonTintMode)) {
            mButtonBlendMode = Drawable.parseBlendMode(a.getInt(
                    R.styleable.CompoundButton_buttonTintMode, -1), mButtonBlendMode);
            mHasButtonBlendMode = true;
        }

        if (a.hasValue(R.styleable.CompoundButton_buttonTint)) {
            mButtonTintList = a.getColorStateList(R.styleable.CompoundButton_buttonTint);
            mHasButtonTint = true;
        }

        final boolean checked = a.getBoolean(
                com.android.internal.R.styleable.CompoundButton_checked, false);
        setChecked(checked);
        mCheckedFromResource = true;

        a.recycle();

        applyButtonTint();
    }

    @Override
    public void toggle() {
        setChecked(!mChecked);
    }

    @Override
    public boolean performClick() {
        toggle();

        final boolean handled = super.performClick();
        if (!handled) {
            // View only makes a sound effect if the onClickListener was
            // called, so we'll need to make one here instead.
            playSoundEffect(SoundEffectConstants.CLICK);
        }

        return handled;
    }

    @InspectableProperty
    @ViewDebug.ExportedProperty
    @Override
    public boolean isChecked() {
        return mChecked;
    }

    /** @hide */
    @NonNull
    protected CharSequence getButtonStateDescription() {
        if (isChecked()) {
            return getResources().getString(R.string.checked);
        } else {
            return getResources().getString(R.string.not_checked);
        }
    }

    /**
     * This function is called when an instance or subclass sets the state description. Once this
     * is called and the argument is not null, the app developer will be responsible for updating
     * state description when checked state changes and we will not set state description
     * in {@link #setChecked}. App developers can restore the default behavior by setting the
     * argument to null. If {@link #setChecked} is called first and then setStateDescription is
     * called, two state change events will be merged by event throttling and we can still get
     * the correct state description.
     *
     * @param stateDescription The state description.
     */
    @Override
    public void setStateDescription(@Nullable CharSequence stateDescription) {
        mCustomStateDescription = stateDescription;
        if (stateDescription == null) {
            setDefaultStateDescription();
        } else {
            super.setStateDescription(stateDescription);
        }
    }

    /** @hide **/
    protected void setDefaultStateDescription() {
        if (mCustomStateDescription == null) {
            super.setStateDescription(getButtonStateDescription());
        }
    }

    /**
     * <p>Changes the checked state of this button.</p>
     *
     * @param checked true to check the button, false to uncheck it
     */
    @Override
    public void setChecked(boolean checked) {
        if (mChecked != checked) {
            mCheckedFromResource = false;
            mChecked = checked;
            refreshDrawableState();
            if (triStateChecked()) {
                notifyViewAccessibilityStateChangedIfNeeded(
                        AccessibilityEvent.CONTENT_CHANGE_TYPE_CHECKED);
            }

            // Avoid infinite recursions if setChecked() is called from a listener
            if (mBroadcasting) {
                // setStateDescription will not send out event if the description is unchanged.
                setDefaultStateDescription();
                return;
            }

            mBroadcasting = true;
            if (mOnCheckedChangeListener != null) {
                mOnCheckedChangeListener.onCheckedChanged(this, mChecked);
            }
            if (mOnCheckedChangeWidgetListener != null) {
                mOnCheckedChangeWidgetListener.onCheckedChanged(this, mChecked);
            }
            final AutofillManager afm = mContext.getSystemService(AutofillManager.class);
            if (afm != null) {
                afm.notifyValueChanged(this);
            }

            mBroadcasting = false;
        }
        // setStateDescription will not send out event if the description is unchanged.
        setDefaultStateDescription();
    }

    /**
     * Register a callback to be invoked when the checked state of this button
     * changes.
     *
     * @param listener the callback to call on checked state change
     */
    public void setOnCheckedChangeListener(@Nullable OnCheckedChangeListener listener) {
        mOnCheckedChangeListener = listener;
    }

    /**
     * Register a callback to be invoked when the checked state of this button
     * changes. This callback is used for internal purpose only.
     *
     * @param listener the callback to call on checked state change
     * @hide
     */
    void setOnCheckedChangeWidgetListener(OnCheckedChangeListener listener) {
        mOnCheckedChangeWidgetListener = listener;
    }

    /**
     * Interface definition for a callback to be invoked when the checked state
     * of a compound button changed.
     */
    public static interface OnCheckedChangeListener {
        /**
         * Called when the checked state of a compound button has changed.
         *
         * @param buttonView The compound button view whose state has changed.
         * @param isChecked  The new checked state of buttonView.
         */
        void onCheckedChanged(@NonNull CompoundButton buttonView, boolean isChecked);
    }

    /**
     * Sets a drawable as the compound button image given its resource
     * identifier.
     *
     * @param resId the resource identifier of the drawable
     * @attr ref android.R.styleable#CompoundButton_button
     */
    @RemotableViewMethod(asyncImpl = "setButtonDrawableAsync")
    public void setButtonDrawable(@DrawableRes int resId) {
        final Drawable d;
        if (resId != 0) {
            d = getContext().getDrawable(resId);
        } else {
            d = null;
        }
        setButtonDrawable(d);
    }

    /** @hide **/
    public Runnable setButtonDrawableAsync(@DrawableRes int resId) {
        Drawable drawable = resId == 0 ? null : getContext().getDrawable(resId);
        return () -> setButtonDrawable(drawable);
    }

    /**
     * Sets a drawable as the compound button image.
     *
     * @param drawable the drawable to set
     * @attr ref android.R.styleable#CompoundButton_button
     */
    public void setButtonDrawable(@Nullable Drawable drawable) {
        if (mButtonDrawable != drawable) {
            if (mButtonDrawable != null) {
                mButtonDrawable.setCallback(null);
                unscheduleDrawable(mButtonDrawable);
            }

            mButtonDrawable = drawable;

            if (drawable != null) {
                drawable.setCallback(this);
                drawable.setLayoutDirection(getLayoutDirection());
                if (drawable.isStateful()) {
                    drawable.setState(getDrawableState());
                }
                drawable.setVisible(getVisibility() == VISIBLE, false);
                setMinHeight(drawable.getIntrinsicHeight());
                applyButtonTint();
            }
        }
    }

    /**
     * @hide
     */
    @Override
    public void onResolveDrawables(@ResolvedLayoutDir int layoutDirection) {
        super.onResolveDrawables(layoutDirection);
        if (mButtonDrawable != null) {
            mButtonDrawable.setLayoutDirection(layoutDirection);
        }
    }

    /**
     * @return the drawable used as the compound button image
     * @see #setButtonDrawable(Drawable)
     * @see #setButtonDrawable(int)
     */
    @InspectableProperty(name = "button")
    @Nullable
    public Drawable getButtonDrawable() {
        return mButtonDrawable;
    }

    /**
     * Sets the button of this CompoundButton to the specified Icon.
     *
     * @param icon an Icon holding the desired button, or {@code null} to clear
     *             the button
     */
    @RemotableViewMethod(asyncImpl = "setButtonIconAsync")
    public void setButtonIcon(@Nullable Icon icon) {
        setButtonDrawable(icon == null ? null : icon.loadDrawable(getContext()));
    }

    /** @hide **/
    public Runnable setButtonIconAsync(@Nullable Icon icon) {
        Drawable button = icon == null ? null : icon.loadDrawable(getContext());
        return () -> setButtonDrawable(button);
    }

    /**
     * Applies a tint to the button drawable. Does not modify the current tint
     * mode, which is {@link PorterDuff.Mode#SRC_IN} by default.
     * <p>
     * Subsequent calls to {@link #setButtonDrawable(Drawable)} will
     * automatically mutate the drawable and apply the specified tint and tint
     * mode using
     * {@link Drawable#setTintList(ColorStateList)}.
     *
     * @param tint the tint to apply, may be {@code null} to clear tint
     *
     * @attr ref android.R.styleable#CompoundButton_buttonTint
     * @see #setButtonTintList(ColorStateList)
     * @see Drawable#setTintList(ColorStateList)
     */
    @RemotableViewMethod
    public void setButtonTintList(@Nullable ColorStateList tint) {
        mButtonTintList = tint;
        mHasButtonTint = true;

        applyButtonTint();
    }

    /**
     * @return the tint applied to the button drawable
     * @attr ref android.R.styleable#CompoundButton_buttonTint
     * @see #setButtonTintList(ColorStateList)
     */
    @InspectableProperty(name = "buttonTint")
    @Nullable
    public ColorStateList getButtonTintList() {
        return mButtonTintList;
    }

    /**
     * Specifies the blending mode used to apply the tint specified by
     * {@link #setButtonTintList(ColorStateList)}} to the button drawable. The
     * default mode is {@link PorterDuff.Mode#SRC_IN}.
     *
     * @param tintMode the blending mode used to apply the tint, may be
     *                 {@code null} to clear tint
     * @attr ref android.R.styleable#CompoundButton_buttonTintMode
     * @see #getButtonTintMode()
     * @see Drawable#setTintMode(PorterDuff.Mode)
     */
    public void setButtonTintMode(@Nullable PorterDuff.Mode tintMode) {
        setButtonTintBlendMode(tintMode != null ? BlendMode.fromValue(tintMode.nativeInt) : null);
    }

    /**
     * Specifies the blending mode used to apply the tint specified by
     * {@link #setButtonTintList(ColorStateList)}} to the button drawable. The
     * default mode is {@link PorterDuff.Mode#SRC_IN}.
     *
     * @param tintMode the blending mode used to apply the tint, may be
     *                 {@code null} to clear tint
     * @attr ref android.R.styleable#CompoundButton_buttonTintMode
     * @see #getButtonTintMode()
     * @see Drawable#setTintBlendMode(BlendMode)
     */
    @RemotableViewMethod
    public void setButtonTintBlendMode(@Nullable BlendMode tintMode) {
        mButtonBlendMode = tintMode;
        mHasButtonBlendMode = true;

        applyButtonTint();
    }

    /**
     * @return the blending mode used to apply the tint to the button drawable
     * @attr ref android.R.styleable#CompoundButton_buttonTintMode
     * @see #setButtonTintMode(PorterDuff.Mode)
     */
    @InspectableProperty(name = "buttonTintMode")
    @Nullable
    public PorterDuff.Mode getButtonTintMode() {
        return mButtonBlendMode != null ? BlendMode.blendModeToPorterDuffMode(mButtonBlendMode) :
                null;
    }

    /**
     * @return the blending mode used to apply the tint to the button drawable
     * @attr ref android.R.styleable#CompoundButton_buttonTintMode
     * @see #setButtonTintBlendMode(BlendMode)
     */
    @InspectableProperty(name = "buttonBlendMode",
            attributeId = R.styleable.CompoundButton_buttonTintMode)
    @Nullable
    public BlendMode getButtonTintBlendMode() {
        return mButtonBlendMode;
    }

    private void applyButtonTint() {
        if (mButtonDrawable != null && (mHasButtonTint || mHasButtonBlendMode)) {
            mButtonDrawable = mButtonDrawable.mutate();

            if (mHasButtonTint) {
                mButtonDrawable.setTintList(mButtonTintList);
            }

            if (mHasButtonBlendMode) {
                mButtonDrawable.setTintBlendMode(mButtonBlendMode);
            }

            // The drawable (or one of its children) may not have been
            // stateful before applying the tint, so let's try again.
            if (mButtonDrawable.isStateful()) {
                mButtonDrawable.setState(getDrawableState());
            }
        }
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return CompoundButton.class.getName();
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
        if (triStateChecked()) {
            info.setChecked(mChecked ? AccessibilityNodeInfo.CHECKED_STATE_TRUE :
                    AccessibilityNodeInfo.CHECKED_STATE_FALSE);
        } else {
            info.setChecked(mChecked);
        }
    }

    @Override
    public int getCompoundPaddingLeft() {
        int padding = super.getCompoundPaddingLeft();
        if (!isLayoutRtl()) {
            final Drawable buttonDrawable = mButtonDrawable;
            if (buttonDrawable != null) {
                padding += buttonDrawable.getIntrinsicWidth();
            }
        }
        return padding;
    }

    @Override
    public int getCompoundPaddingRight() {
        int padding = super.getCompoundPaddingRight();
        if (isLayoutRtl()) {
            final Drawable buttonDrawable = mButtonDrawable;
            if (buttonDrawable != null) {
                padding += buttonDrawable.getIntrinsicWidth();
            }
        }
        return padding;
    }

    /**
     * @hide
     */
    @Override
    public int getHorizontalOffsetForDrawables() {
        final Drawable buttonDrawable = mButtonDrawable;
        return (buttonDrawable != null) ? buttonDrawable.getIntrinsicWidth() : 0;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final Drawable buttonDrawable = mButtonDrawable;
        if (buttonDrawable != null) {
            final int verticalGravity = getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
            final int drawableHeight = buttonDrawable.getIntrinsicHeight();
            final int drawableWidth = buttonDrawable.getIntrinsicWidth();

            final int top;
            switch (verticalGravity) {
                case Gravity.BOTTOM:
                    top = getHeight() - drawableHeight;
                    break;
                case Gravity.CENTER_VERTICAL:
                    top = (getHeight() - drawableHeight) / 2;
                    break;
                default:
                    top = 0;
            }
            final int bottom = top + drawableHeight;
            final int left = isLayoutRtl() ? getWidth() - drawableWidth : 0;
            final int right = isLayoutRtl() ? getWidth() : drawableWidth;

            buttonDrawable.setBounds(left, top, right, bottom);

            final Drawable background = getBackground();
            if (background != null) {
                background.setHotspotBounds(left, top, right, bottom);
            }
        }

        super.onDraw(canvas);

        if (buttonDrawable != null) {
            final int scrollX = mScrollX;
            final int scrollY = mScrollY;
            if (scrollX == 0 && scrollY == 0) {
                buttonDrawable.draw(canvas);
            } else {
                canvas.translate(scrollX, scrollY);
                buttonDrawable.draw(canvas);
                canvas.translate(-scrollX, -scrollY);
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

        final Drawable buttonDrawable = mButtonDrawable;
        if (buttonDrawable != null && buttonDrawable.isStateful()
                && buttonDrawable.setState(getDrawableState())) {
            invalidateDrawable(buttonDrawable);
        }
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        super.drawableHotspotChanged(x, y);

        if (mButtonDrawable != null) {
            mButtonDrawable.setHotspot(x, y);
        }
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return super.verifyDrawable(who) || who == mButtonDrawable;
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        if (mButtonDrawable != null) mButtonDrawable.jumpToCurrentState();
    }

    static class SavedState extends BaseSavedState {
        boolean checked;

        /**
         * Constructor called from {@link CompoundButton#onSaveInstanceState()}
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
            return "CompoundButton.SavedState{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " checked=" + checked + "}";
        }

        @SuppressWarnings("hiding")
        public static final @android.annotation.NonNull Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<SavedState>() {
            @Override
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            @Override
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
    protected void encodeProperties(@NonNull ViewHierarchyEncoder stream) {
        super.encodeProperties(stream);
        stream.addProperty("checked", isChecked());
    }


    /** @hide */
    @Override
    protected void onProvideStructure(@NonNull ViewStructure structure,
            @ViewStructureType int viewFor, int flags) {
        super.onProvideStructure(structure, viewFor, flags);

        if (viewFor == VIEW_STRUCTURE_FOR_AUTOFILL) {
            structure.setDataIsSensitive(!mCheckedFromResource);
        }
    }

    @Override
    public void autofill(AutofillValue value) {
        if (!isEnabled()) return;

        if (!value.isToggle()) {
            Log.w(LOG_TAG, value + " could not be autofilled into " + this);
            return;
        }

        setChecked(value.getToggleValue());
    }

    @Override
    public @AutofillType int getAutofillType() {
        return isEnabled() ? AUTOFILL_TYPE_TOGGLE : AUTOFILL_TYPE_NONE;
    }

    @Override
    public AutofillValue getAutofillValue() {
        return isEnabled() ? AutofillValue.forToggle(isChecked()) : null;
    }
}
