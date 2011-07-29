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

import com.android.internal.R;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.ViewDebug;
import android.view.accessibility.AccessibilityEvent;


/**
 * An extension to TextView that supports the {@link android.widget.Checkable} interface.
 * This is useful when used in a {@link android.widget.ListView ListView} where the it's 
 * {@link android.widget.ListView#setChoiceMode(int) setChoiceMode} has been set to
 * something other than {@link android.widget.ListView#CHOICE_MODE_NONE CHOICE_MODE_NONE}.
 *
 */
public class CheckedTextView extends TextView implements Checkable {
    private boolean mChecked;
    private int mCheckMarkResource;
    private Drawable mCheckMarkDrawable;
    private int mBasePadding;
    private int mCheckMarkWidth;
    private boolean mNeedRequestlayout;

    private static final int[] CHECKED_STATE_SET = {
        R.attr.state_checked
    };

    public CheckedTextView(Context context) {
        this(context, null);
    }

    public CheckedTextView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CheckedTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.CheckedTextView, defStyle, 0);

        Drawable d = a.getDrawable(R.styleable.CheckedTextView_checkMark);
        if (d != null) {
            setCheckMarkDrawable(d);
        }

        boolean checked = a.getBoolean(R.styleable.CheckedTextView_checked, false);
        setChecked(checked);

        a.recycle();
    }

    public void toggle() {
        setChecked(!mChecked);
    }

    @ViewDebug.ExportedProperty
    public boolean isChecked() {
        return mChecked;
    }

    /**
     * <p>Changes the checked state of this text view.</p>
     *
     * @param checked true to check the text, false to uncheck it
     */
    public void setChecked(boolean checked) {
        if (mChecked != checked) {
            mChecked = checked;
            refreshDrawableState();
        }
    }


    /**
     * Set the checkmark to a given Drawable, identified by its resourece id. This will be drawn
     * when {@link #isChecked()} is true.
     * 
     * @param resid The Drawable to use for the checkmark.
     */
    public void setCheckMarkDrawable(int resid) {
        if (resid != 0 && resid == mCheckMarkResource) {
            return;
        }

        mCheckMarkResource = resid;

        Drawable d = null;
        if (mCheckMarkResource != 0) {
            d = getResources().getDrawable(mCheckMarkResource);
        }
        setCheckMarkDrawable(d);
    }

    /**
     * Set the checkmark to a given Drawable. This will be drawn when {@link #isChecked()} is true.
     *
     * @param d The Drawable to use for the checkmark.
     */
    public void setCheckMarkDrawable(Drawable d) {
        if (mCheckMarkDrawable != null) {
            mCheckMarkDrawable.setCallback(null);
            unscheduleDrawable(mCheckMarkDrawable);
        }
        mNeedRequestlayout = (d != mCheckMarkDrawable);
        if (d != null) {
            d.setCallback(this);
            d.setVisible(getVisibility() == VISIBLE, false);
            d.setState(CHECKED_STATE_SET);
            setMinHeight(d.getIntrinsicHeight());
            
            mCheckMarkWidth = d.getIntrinsicWidth();
            d.setState(getDrawableState());
        } else {
            mCheckMarkWidth = 0;
        }
        mCheckMarkDrawable = d;
        // Do padding resolution. This will call setPadding() and do a requestLayout() if needed.
        resolvePadding();
    }

    /**
     * @hide
     */
    @Override
    protected void resolvePadding() {
        super.resolvePadding();
        int newPadding = (mCheckMarkDrawable != null) ?
                mCheckMarkWidth + mBasePadding : mBasePadding;
        mNeedRequestlayout |= (mPaddingRight != newPadding);
        mPaddingRight = newPadding;
        if (mNeedRequestlayout) {
            requestLayout();
            mNeedRequestlayout = false;
        }
    }
    
    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        super.setPadding(left, top, right, bottom);
        mBasePadding = mPaddingRight;
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
            
            int right = getWidth();
            checkMarkDrawable.setBounds(
                    right - mPaddingRight,
                    y, 
                    right - mPaddingRight + mCheckMarkWidth,
                    y + height);
            checkMarkDrawable.draw(canvas);
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
        
        if (mCheckMarkDrawable != null) {
            int[] myDrawableState = getDrawableState();
            
            // Set the state of the Drawable
            mCheckMarkDrawable.setState(myDrawableState);
            
            invalidate();
        }
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setChecked(mChecked);
    }

    @Override
    public void onPopulateAccessibilityEvent(AccessibilityEvent event) {
        super.onPopulateAccessibilityEvent(event);
        if (isChecked()) {
            event.getText().add(mContext.getString(R.string.radiobutton_selected));
        } else {
            event.getText().add(mContext.getString(R.string.radiobutton_not_selected));
        }
    }
}
