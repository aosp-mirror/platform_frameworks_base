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
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.annotation.Widget;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inspector.InspectableProperty;
import android.widget.PopupWindow.OnDismissListener;

import com.android.internal.R;
import com.android.internal.view.menu.ShowableListMenu;

/**
 * A view that displays one child at a time and lets the user pick among them.
 * The items in the Spinner come from the {@link Adapter} associated with
 * this view.
 *
 * <p>See the <a href="{@docRoot}guide/topics/ui/controls/spinner.html">Spinners</a> guide.</p>
 *
 * @attr ref android.R.styleable#Spinner_dropDownSelector
 * @attr ref android.R.styleable#Spinner_dropDownWidth
 * @attr ref android.R.styleable#Spinner_gravity
 * @attr ref android.R.styleable#Spinner_popupBackground
 * @attr ref android.R.styleable#Spinner_prompt
 * @attr ref android.R.styleable#Spinner_spinnerMode
 * @attr ref android.R.styleable#ListPopupWindow_dropDownVerticalOffset
 * @attr ref android.R.styleable#ListPopupWindow_dropDownHorizontalOffset
 */
@Widget
public class Spinner extends AbsSpinner implements OnClickListener {
    private static final String TAG = "Spinner";

    // Only measure this many items to get a decent max width.
    private static final int MAX_ITEMS_MEASURED = 15;

    /**
     * Use a dialog window for selecting spinner options.
     */
    public static final int MODE_DIALOG = 0;

    /**
     * Use a dropdown anchored to the Spinner for selecting spinner options.
     */
    public static final int MODE_DROPDOWN = 1;

    /**
     * Use the theme-supplied value to select the dropdown mode.
     */
    private static final int MODE_THEME = -1;

    private final Rect mTempRect = new Rect();

    /** Context used to inflate the popup window or dialog. */
    private final Context mPopupContext;

    /** Forwarding listener used to implement drag-to-open. */
    @UnsupportedAppUsage
    private ForwardingListener mForwardingListener;

    /** Temporary holder for setAdapter() calls from the super constructor. */
    private SpinnerAdapter mTempAdapter;

    @UnsupportedAppUsage
    private SpinnerPopup mPopup;
    int mDropDownWidth;

    private int mGravity;
    private boolean mDisableChildrenWhenDisabled;

    /**
     * Constructs a new spinner with the given context's theme.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     */
    public Spinner(Context context) {
        this(context, null);
    }

    /**
     * Constructs a new spinner with the given context's theme and the supplied
     * mode of displaying choices. <code>mode</code> may be one of
     * {@link #MODE_DIALOG} or {@link #MODE_DROPDOWN}.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     * @param mode Constant describing how the user will select choices from
     *             the spinner.
     *
     * @see #MODE_DIALOG
     * @see #MODE_DROPDOWN
     */
    public Spinner(Context context, int mode) {
        this(context, null, com.android.internal.R.attr.spinnerStyle, mode);
    }

    /**
     * Constructs a new spinner with the given context's theme and the supplied
     * attribute set.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     * @param attrs The attributes of the XML tag that is inflating the view.
     */
    public Spinner(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.spinnerStyle);
    }

    /**
     * Constructs a new spinner with the given context's theme, the supplied
     * attribute set, and default style attribute.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     * @param attrs The attributes of the XML tag that is inflating the view.
     * @param defStyleAttr An attribute in the current theme that contains a
     *                     reference to a style resource that supplies default
     *                     values for the view. Can be 0 to not look for
     *                     defaults.
     */
    public Spinner(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0, MODE_THEME);
    }

    /**
     * Constructs a new spinner with the given context's theme, the supplied
     * attribute set, and default style attribute. <code>mode</code> may be one
     * of {@link #MODE_DIALOG} or {@link #MODE_DROPDOWN} and determines how the
     * user will select choices from the spinner.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     * @param attrs The attributes of the XML tag that is inflating the view.
     * @param defStyleAttr An attribute in the current theme that contains a
     *                     reference to a style resource that supplies default
     *                     values for the view. Can be 0 to not look for defaults.
     * @param mode Constant describing how the user will select choices from the
     *             spinner.
     *
     * @see #MODE_DIALOG
     * @see #MODE_DROPDOWN
     */
    public Spinner(Context context, AttributeSet attrs, int defStyleAttr, int mode) {
        this(context, attrs, defStyleAttr, 0, mode);
    }

    /**
     * Constructs a new spinner with the given context's theme, the supplied
     * attribute set, and default styles. <code>mode</code> may be one of
     * {@link #MODE_DIALOG} or {@link #MODE_DROPDOWN} and determines how the
     * user will select choices from the spinner.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     * @param attrs The attributes of the XML tag that is inflating the view.
     * @param defStyleAttr An attribute in the current theme that contains a
     *                     reference to a style resource that supplies default
     *                     values for the view. Can be 0 to not look for
     *                     defaults.
     * @param defStyleRes A resource identifier of a style resource that
     *                    supplies default values for the view, used only if
     *                    defStyleAttr is 0 or can not be found in the theme.
     *                    Can be 0 to not look for defaults.
     * @param mode Constant describing how the user will select choices from
     *             the spinner.
     *
     * @see #MODE_DIALOG
     * @see #MODE_DROPDOWN
     */
    public Spinner(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes,
            int mode) {
        this(context, attrs, defStyleAttr, defStyleRes, mode, null);
    }

    /**
     * Constructs a new spinner with the given context, the supplied attribute
     * set, default styles, popup mode (one of {@link #MODE_DIALOG} or
     * {@link #MODE_DROPDOWN}), and the theme against which the popup should be
     * inflated.
     *
     * @param context The context against which the view is inflated, which
     *                provides access to the current theme, resources, etc.
     * @param attrs The attributes of the XML tag that is inflating the view.
     * @param defStyleAttr An attribute in the current theme that contains a
     *                     reference to a style resource that supplies default
     *                     values for the view. Can be 0 to not look for
     *                     defaults.
     * @param defStyleRes A resource identifier of a style resource that
     *                    supplies default values for the view, used only if
     *                    defStyleAttr is 0 or can not be found in the theme.
     *                    Can be 0 to not look for defaults.
     * @param mode Constant describing how the user will select choices from
     *             the spinner.
     * @param popupTheme The theme against which the dialog or dropdown popup
     *                   should be inflated. May be {@code null} to use the
     *                   view theme. If set, this will override any value
     *                   specified by
     *                   {@link android.R.styleable#Spinner_popupTheme}.
     *
     * @see #MODE_DIALOG
     * @see #MODE_DROPDOWN
     */
    public Spinner(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes, int mode,
            Theme popupTheme) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.Spinner, defStyleAttr, defStyleRes);
        saveAttributeDataForStyleable(context, R.styleable.Spinner,
                attrs, a, defStyleAttr, defStyleRes);

        if (popupTheme != null) {
            mPopupContext = new ContextThemeWrapper(context, popupTheme);
        } else {
            final int popupThemeResId = a.getResourceId(R.styleable.Spinner_popupTheme, 0);
            if (popupThemeResId != 0) {
                mPopupContext = new ContextThemeWrapper(context, popupThemeResId);
            } else {
                mPopupContext = context;
            }
        }

        if (mode == MODE_THEME) {
            mode = a.getInt(R.styleable.Spinner_spinnerMode, MODE_DIALOG);
        }

        switch (mode) {
            case MODE_DIALOG: {
                mPopup = new DialogPopup();
                mPopup.setPromptText(a.getString(R.styleable.Spinner_prompt));
                break;
            }

            case MODE_DROPDOWN: {
                final DropdownPopup popup = new DropdownPopup(
                        mPopupContext, attrs, defStyleAttr, defStyleRes);
                final TypedArray pa = mPopupContext.obtainStyledAttributes(
                        attrs, R.styleable.Spinner, defStyleAttr, defStyleRes);
                mDropDownWidth = pa.getLayoutDimension(R.styleable.Spinner_dropDownWidth,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (pa.hasValueOrEmpty(R.styleable.Spinner_dropDownSelector)) {
                    popup.setListSelector(pa.getDrawable(
                            R.styleable.Spinner_dropDownSelector));
                }
                popup.setBackgroundDrawable(pa.getDrawable(R.styleable.Spinner_popupBackground));
                popup.setPromptText(a.getString(R.styleable.Spinner_prompt));
                pa.recycle();

                mPopup = popup;
                mForwardingListener = new ForwardingListener(this) {
                    @Override
                    public ShowableListMenu getPopup() {
                        return popup;
                    }

                    @Override
                    public boolean onForwardingStarted() {
                        if (!mPopup.isShowing()) {
                            mPopup.show(getTextDirection(), getTextAlignment());
                        }
                        return true;
                    }
                };
                break;
            }
        }

        mGravity = a.getInt(R.styleable.Spinner_gravity, Gravity.CENTER);
        mDisableChildrenWhenDisabled = a.getBoolean(
                R.styleable.Spinner_disableChildrenWhenDisabled, false);

        a.recycle();

        // Base constructor can call setAdapter before we initialize mPopup.
        // Finish setting things up if this happened.
        if (mTempAdapter != null) {
            setAdapter(mTempAdapter);
            mTempAdapter = null;
        }
    }

    /**
     * @return the context used to inflate the Spinner's popup or dialog window
     */
    public Context getPopupContext() {
        return mPopupContext;
    }

    /**
     * Set the background drawable for the spinner's popup window of choices.
     * Only valid in {@link #MODE_DROPDOWN}; this method is a no-op in other modes.
     *
     * @param background Background drawable
     *
     * @attr ref android.R.styleable#Spinner_popupBackground
     */
    public void setPopupBackgroundDrawable(Drawable background) {
        if (!(mPopup instanceof DropdownPopup)) {
            Log.e(TAG, "setPopupBackgroundDrawable: incompatible spinner mode; ignoring...");
            return;
        }
        mPopup.setBackgroundDrawable(background);
    }

    /**
     * Set the background drawable for the spinner's popup window of choices.
     * Only valid in {@link #MODE_DROPDOWN}; this method is a no-op in other modes.
     *
     * @param resId Resource ID of a background drawable
     *
     * @attr ref android.R.styleable#Spinner_popupBackground
     */
    public void setPopupBackgroundResource(@DrawableRes int resId) {
        setPopupBackgroundDrawable(getPopupContext().getDrawable(resId));
    }

    /**
     * Get the background drawable for the spinner's popup window of choices.
     * Only valid in {@link #MODE_DROPDOWN}; other modes will return null.
     *
     * @return background Background drawable
     *
     * @attr ref android.R.styleable#Spinner_popupBackground
     */
    @InspectableProperty
    public Drawable getPopupBackground() {
        return mPopup.getBackground();
    }

    /**
     * @hide
     */
    @TestApi
    public boolean isPopupShowing() {
        return (mPopup != null) && mPopup.isShowing();
    }

    /**
     * Set a vertical offset in pixels for the spinner's popup window of choices.
     * Only valid in {@link #MODE_DROPDOWN}; this method is a no-op in other modes.
     *
     * @param pixels Vertical offset in pixels
     *
     * @attr ref android.R.styleable#ListPopupWindow_dropDownVerticalOffset
     */
    public void setDropDownVerticalOffset(int pixels) {
        mPopup.setVerticalOffset(pixels);
    }

    /**
     * Get the configured vertical offset in pixels for the spinner's popup window of choices.
     * Only valid in {@link #MODE_DROPDOWN}; other modes will return 0.
     *
     * @return Vertical offset in pixels
     *
     * @attr ref android.R.styleable#ListPopupWindow_dropDownVerticalOffset
     */
    @InspectableProperty
    public int getDropDownVerticalOffset() {
        return mPopup.getVerticalOffset();
    }

    /**
     * Set a horizontal offset in pixels for the spinner's popup window of choices.
     * Only valid in {@link #MODE_DROPDOWN}; this method is a no-op in other modes.
     *
     * @param pixels Horizontal offset in pixels
     *
     * @attr ref android.R.styleable#ListPopupWindow_dropDownHorizontalOffset
     */
    public void setDropDownHorizontalOffset(int pixels) {
        mPopup.setHorizontalOffset(pixels);
    }

    /**
     * Get the configured horizontal offset in pixels for the spinner's popup window of choices.
     * Only valid in {@link #MODE_DROPDOWN}; other modes will return 0.
     *
     * @return Horizontal offset in pixels
     *
     * @attr ref android.R.styleable#ListPopupWindow_dropDownHorizontalOffset
     */
    @InspectableProperty
    public int getDropDownHorizontalOffset() {
        return mPopup.getHorizontalOffset();
    }

    /**
     * Set the width of the spinner's popup window of choices in pixels. This value
     * may also be set to {@link android.view.ViewGroup.LayoutParams#MATCH_PARENT}
     * to match the width of the Spinner itself, or
     * {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT} to wrap to the measured size
     * of contained dropdown list items.
     *
     * <p>Only valid in {@link #MODE_DROPDOWN}; this method is a no-op in other modes.</p>
     *
     * @param pixels Width in pixels, WRAP_CONTENT, or MATCH_PARENT
     *
     * @attr ref android.R.styleable#Spinner_dropDownWidth
     */
    public void setDropDownWidth(int pixels) {
        if (!(mPopup instanceof DropdownPopup)) {
            Log.e(TAG, "Cannot set dropdown width for MODE_DIALOG, ignoring");
            return;
        }
        mDropDownWidth = pixels;
    }

    /**
     * Get the configured width of the spinner's popup window of choices in pixels.
     * The returned value may also be {@link android.view.ViewGroup.LayoutParams#MATCH_PARENT}
     * meaning the popup window will match the width of the Spinner itself, or
     * {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT} to wrap to the measured size
     * of contained dropdown list items.
     *
     * @return Width in pixels, WRAP_CONTENT, or MATCH_PARENT
     *
     * @attr ref android.R.styleable#Spinner_dropDownWidth
     */
    @InspectableProperty
    public int getDropDownWidth() {
        return mDropDownWidth;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (mDisableChildrenWhenDisabled) {
            final int count = getChildCount();
            for (int i = 0; i < count; i++) {
                getChildAt(i).setEnabled(enabled);
            }
        }
    }

    /**
     * Describes how the selected item view is positioned. Currently only the horizontal component
     * is used. The default is determined by the current theme.
     *
     * @param gravity See {@link android.view.Gravity}
     *
     * @attr ref android.R.styleable#Spinner_gravity
     */
    public void setGravity(int gravity) {
        if (mGravity != gravity) {
            if ((gravity & Gravity.HORIZONTAL_GRAVITY_MASK) == 0) {
                gravity |= Gravity.START;
            }
            mGravity = gravity;
            requestLayout();
        }
    }

    /**
     * Describes how the selected item view is positioned. The default is determined by the
     * current theme.
     *
     * @return A {@link android.view.Gravity Gravity} value
     */
    @InspectableProperty(valueType = InspectableProperty.ValueType.GRAVITY)
    public int getGravity() {
        return mGravity;
    }

    /**
     * Sets the {@link SpinnerAdapter} used to provide the data which backs
     * this Spinner.
     * <p>
     * If this Spinner has a popup theme set in XML via the
     * {@link android.R.styleable#Spinner_popupTheme popupTheme} attribute, the
     * adapter should inflate drop-down views using the same theme. The easiest
     * way to achieve this is by using {@link #getPopupContext()} to obtain a
     * layout inflater for use in
     * {@link SpinnerAdapter#getDropDownView(int, View, ViewGroup)}.
     * <p>
     * Spinner overrides {@link Adapter#getViewTypeCount()} on the
     * Adapter associated with this view. Calling
     * {@link Adapter#getItemViewType(int) getItemViewType(int)} on the object
     * returned from {@link #getAdapter()} will always return 0. Calling
     * {@link Adapter#getViewTypeCount() getViewTypeCount()} will always return
     * 1. On API {@link Build.VERSION_CODES#LOLLIPOP} and above, attempting to set an
     * adapter with more than one view type will throw an
     * {@link IllegalArgumentException}.
     *
     * @param adapter the adapter to set
     *
     * @see AbsSpinner#setAdapter(SpinnerAdapter)
     * @throws IllegalArgumentException if the adapter has more than one view
     *         type
     */
    @Override
    public void setAdapter(SpinnerAdapter adapter) {
        // The super constructor may call setAdapter before we're prepared.
        // Postpone doing anything until we've finished construction.
        if (mPopup == null) {
            mTempAdapter = adapter;
            return;
        }

        super.setAdapter(adapter);

        mRecycler.clear();

        final int targetSdkVersion = mContext.getApplicationInfo().targetSdkVersion;
        if (targetSdkVersion >= Build.VERSION_CODES.LOLLIPOP
                && adapter != null && adapter.getViewTypeCount() != 1) {
            throw new IllegalArgumentException("Spinner adapter view type count must be 1");
        }

        final Context popupContext = mPopupContext == null ? mContext : mPopupContext;
        mPopup.setAdapter(new DropDownAdapter(adapter, popupContext.getTheme()));
    }

    @Override
    public int getBaseline() {
        View child = null;

        if (getChildCount() > 0) {
            child = getChildAt(0);
        } else if (mAdapter != null && mAdapter.getCount() > 0) {
            child = makeView(0, false);
            mRecycler.put(0, child);
        }

        if (child != null) {
            final int childBaseline = child.getBaseline();
            return childBaseline >= 0 ? child.getTop() + childBaseline : -1;
        } else {
            return -1;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (mPopup != null && mPopup.isShowing()) {
            mPopup.dismiss();
        }
    }

    /**
     * <p>A spinner does not support item click events. Calling this method
     * will raise an exception.</p>
     * <p>Instead use {@link AdapterView#setOnItemSelectedListener}.
     *
     * @param l this listener will be ignored
     */
    @Override
    public void setOnItemClickListener(OnItemClickListener l) {
        throw new RuntimeException("setOnItemClickListener cannot be used with a spinner.");
    }

    /**
     * @hide internal use only
     */
    @UnsupportedAppUsage
    public void setOnItemClickListenerInt(OnItemClickListener l) {
        super.setOnItemClickListener(l);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mForwardingListener != null && mForwardingListener.onTouch(this, event)) {
            return true;
        }

        return super.onTouchEvent(event);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mPopup != null && MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {
            final int measuredWidth = getMeasuredWidth();
            setMeasuredDimension(Math.min(Math.max(measuredWidth,
                    measureContentWidth(getAdapter(), getBackground())),
                    MeasureSpec.getSize(widthMeasureSpec)),
                    getMeasuredHeight());
        }
    }

    /**
     * @see android.view.View#onLayout(boolean,int,int,int,int)
     *
     * Creates and positions all views
     *
     */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mInLayout = true;
        layout(0, false);
        mInLayout = false;
    }

    /**
     * Creates and positions all views for this Spinner.
     *
     * @param delta Change in the selected position. +1 means selection is moving to the right,
     * so views are scrolling to the left. -1 means selection is moving to the left.
     */
    @Override
    void layout(int delta, boolean animate) {
        int childrenLeft = mSpinnerPadding.left;
        int childrenWidth = mRight - mLeft - mSpinnerPadding.left - mSpinnerPadding.right;

        if (mDataChanged) {
            handleDataChanged();
        }

        // Handle the empty set by removing all views
        if (mItemCount == 0) {
            resetList();
            return;
        }

        if (mNextSelectedPosition >= 0) {
            setSelectedPositionInt(mNextSelectedPosition);
        }

        recycleAllViews();

        // Clear out old views
        removeAllViewsInLayout();

        // Make selected view and position it
        mFirstPosition = mSelectedPosition;

        if (mAdapter != null) {
            View sel = makeView(mSelectedPosition, true);
            int width = sel.getMeasuredWidth();
            int selectedOffset = childrenLeft;
            final int layoutDirection = getLayoutDirection();
            final int absoluteGravity = Gravity.getAbsoluteGravity(mGravity, layoutDirection);
            switch (absoluteGravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
                case Gravity.CENTER_HORIZONTAL:
                    selectedOffset = childrenLeft + (childrenWidth / 2) - (width / 2);
                    break;
                case Gravity.RIGHT:
                    selectedOffset = childrenLeft + childrenWidth - width;
                    break;
            }
            sel.offsetLeftAndRight(selectedOffset);
        }

        // Flush any cached views that did not get reused above
        mRecycler.clear();

        invalidate();

        checkSelectionChanged();

        mDataChanged = false;
        mNeedSync = false;
        setNextSelectedPositionInt(mSelectedPosition);
    }

    /**
     * Obtain a view, either by pulling an existing view from the recycler or
     * by getting a new one from the adapter. If we are animating, make sure
     * there is enough information in the view's layout parameters to animate
     * from the old to new positions.
     *
     * @param position Position in the spinner for the view to obtain
     * @param addChild true to add the child to the spinner, false to obtain and configure only.
     * @return A view for the given position
     */
    private View makeView(int position, boolean addChild) {
        View child;

        if (!mDataChanged) {
            child = mRecycler.get(position);
            if (child != null) {
                // Position the view
                setUpChild(child, addChild);

                return child;
            }
        }

        // Nothing found in the recycler -- ask the adapter for a view
        child = mAdapter.getView(position, null, this);

        // Position the view
        setUpChild(child, addChild);

        return child;
    }

    /**
     * Helper for makeAndAddView to set the position of a view
     * and fill out its layout paramters.
     *
     * @param child The view to position
     * @param addChild true if the child should be added to the Spinner during setup
     */
    private void setUpChild(View child, boolean addChild) {

        // Respect layout params that are already in the view. Otherwise
        // make some up...
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        if (lp == null) {
            lp = generateDefaultLayoutParams();
        }

        addViewInLayout(child, 0, lp);

        child.setSelected(hasFocus());
        if (mDisableChildrenWhenDisabled) {
            child.setEnabled(isEnabled());
        }

        // Get measure specs
        int childHeightSpec = ViewGroup.getChildMeasureSpec(mHeightMeasureSpec,
                mSpinnerPadding.top + mSpinnerPadding.bottom, lp.height);
        int childWidthSpec = ViewGroup.getChildMeasureSpec(mWidthMeasureSpec,
                mSpinnerPadding.left + mSpinnerPadding.right, lp.width);

        // Measure child
        child.measure(childWidthSpec, childHeightSpec);

        int childLeft;
        int childRight;

        // Position vertically based on gravity setting
        int childTop = mSpinnerPadding.top
                + ((getMeasuredHeight() - mSpinnerPadding.bottom -
                        mSpinnerPadding.top - child.getMeasuredHeight()) / 2);
        int childBottom = childTop + child.getMeasuredHeight();

        int width = child.getMeasuredWidth();
        childLeft = 0;
        childRight = childLeft + width;

        child.layout(childLeft, childTop, childRight, childBottom);

        if (!addChild) {
            removeViewInLayout(child);
        }
    }

    @Override
    public boolean performClick() {
        boolean handled = super.performClick();

        if (!handled) {
            handled = true;

            if (!mPopup.isShowing()) {
                mPopup.show(getTextDirection(), getTextAlignment());
            }
        }

        return handled;
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        setSelection(which);
        dialog.dismiss();
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return Spinner.class.getName();
    }

    /** @hide */
    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);

        if (mAdapter != null) {
            info.setCanOpenPopup(true);
        }
    }

    /**
     * Sets the prompt to display when the dialog is shown.
     * @param prompt the prompt to set
     */
    public void setPrompt(CharSequence prompt) {
        mPopup.setPromptText(prompt);
    }

    /**
     * Sets the prompt to display when the dialog is shown.
     * @param promptId the resource ID of the prompt to display when the dialog is shown
     */
    public void setPromptId(int promptId) {
        setPrompt(getContext().getText(promptId));
    }

    /**
     * @return The prompt to display when the dialog is shown
     */
    @InspectableProperty
    public CharSequence getPrompt() {
        return mPopup.getHintText();
    }

    int measureContentWidth(SpinnerAdapter adapter, Drawable background) {
        if (adapter == null) {
            return 0;
        }

        int width = 0;
        View itemView = null;
        int itemType = 0;
        final int widthMeasureSpec =
            MeasureSpec.makeSafeMeasureSpec(getMeasuredWidth(), MeasureSpec.UNSPECIFIED);
        final int heightMeasureSpec =
            MeasureSpec.makeSafeMeasureSpec(getMeasuredHeight(), MeasureSpec.UNSPECIFIED);

        // Make sure the number of items we'll measure is capped. If it's a huge data set
        // with wildly varying sizes, oh well.
        int start = Math.max(0, getSelectedItemPosition());
        final int end = Math.min(adapter.getCount(), start + MAX_ITEMS_MEASURED);
        final int count = end - start;
        start = Math.max(0, start - (MAX_ITEMS_MEASURED - count));
        for (int i = start; i < end; i++) {
            final int positionType = adapter.getItemViewType(i);
            if (positionType != itemType) {
                itemType = positionType;
                itemView = null;
            }
            itemView = adapter.getView(i, itemView, this);
            if (itemView.getLayoutParams() == null) {
                itemView.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            itemView.measure(widthMeasureSpec, heightMeasureSpec);
            width = Math.max(width, itemView.getMeasuredWidth());
        }

        // Add background padding to measured width
        if (background != null) {
            background.getPadding(mTempRect);
            width += mTempRect.left + mTempRect.right;
        }

        return width;
    }

    @Override
    public Parcelable onSaveInstanceState() {
        final SavedState ss = new SavedState(super.onSaveInstanceState());
        ss.showDropdown = mPopup != null && mPopup.isShowing();
        return ss;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;

        super.onRestoreInstanceState(ss.getSuperState());

        if (ss.showDropdown) {
            ViewTreeObserver vto = getViewTreeObserver();
            if (vto != null) {
                final OnGlobalLayoutListener listener = new OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (!mPopup.isShowing()) {
                            mPopup.show(getTextDirection(), getTextAlignment());
                        }
                        final ViewTreeObserver vto = getViewTreeObserver();
                        if (vto != null) {
                            vto.removeOnGlobalLayoutListener(this);
                        }
                    }
                };
                vto.addOnGlobalLayoutListener(listener);
            }
        }
    }

    @Override
    public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
        if (getPointerIcon() == null && isClickable() && isEnabled()) {
            return PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_HAND);
        }
        return super.onResolvePointerIcon(event, pointerIndex);
    }

    static class SavedState extends AbsSpinner.SavedState {
        boolean showDropdown;

        SavedState(Parcelable superState) {
            super(superState);
        }

        private SavedState(Parcel in) {
            super(in);
            showDropdown = in.readByte() != 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeByte((byte) (showDropdown ? 1 : 0));
        }

        public static final @android.annotation.NonNull Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }

    /**
     * <p>Wrapper class for an Adapter. Transforms the embedded Adapter instance
     * into a ListAdapter.</p>
     */
    private static class DropDownAdapter implements ListAdapter, SpinnerAdapter {
        private SpinnerAdapter mAdapter;
        private ListAdapter mListAdapter;

        /**
         * Creates a new ListAdapter wrapper for the specified adapter.
         *
         * @param adapter the SpinnerAdapter to transform into a ListAdapter
         * @param dropDownTheme the theme against which to inflate drop-down
         *                      views, may be {@null} to use default theme
         */
        public DropDownAdapter(@Nullable SpinnerAdapter adapter,
                @Nullable Resources.Theme dropDownTheme) {
            mAdapter = adapter;

            if (adapter instanceof ListAdapter) {
                mListAdapter = (ListAdapter) adapter;
            }

            if (dropDownTheme != null && adapter instanceof ThemedSpinnerAdapter) {
                final ThemedSpinnerAdapter themedAdapter = (ThemedSpinnerAdapter) adapter;
                if (themedAdapter.getDropDownViewTheme() == null) {
                    themedAdapter.setDropDownViewTheme(dropDownTheme);
                }
            }
        }

        public int getCount() {
            return mAdapter == null ? 0 : mAdapter.getCount();
        }

        public Object getItem(int position) {
            return mAdapter == null ? null : mAdapter.getItem(position);
        }

        public long getItemId(int position) {
            return mAdapter == null ? -1 : mAdapter.getItemId(position);
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            return getDropDownView(position, convertView, parent);
        }

        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return (mAdapter == null) ? null : mAdapter.getDropDownView(position, convertView, parent);
        }

        public boolean hasStableIds() {
            return mAdapter != null && mAdapter.hasStableIds();
        }

        public void registerDataSetObserver(DataSetObserver observer) {
            if (mAdapter != null) {
                mAdapter.registerDataSetObserver(observer);
            }
        }

        public void unregisterDataSetObserver(DataSetObserver observer) {
            if (mAdapter != null) {
                mAdapter.unregisterDataSetObserver(observer);
            }
        }

        /**
         * If the wrapped SpinnerAdapter is also a ListAdapter, delegate this call.
         * Otherwise, return true.
         */
        public boolean areAllItemsEnabled() {
            final ListAdapter adapter = mListAdapter;
            if (adapter != null) {
                return adapter.areAllItemsEnabled();
            } else {
                return true;
            }
        }

        /**
         * If the wrapped SpinnerAdapter is also a ListAdapter, delegate this call.
         * Otherwise, return true.
         */
        public boolean isEnabled(int position) {
            final ListAdapter adapter = mListAdapter;
            if (adapter != null) {
                return adapter.isEnabled(position);
            } else {
                return true;
            }
        }

        public int getItemViewType(int position) {
            return 0;
        }

        public int getViewTypeCount() {
            return 1;
        }

        public boolean isEmpty() {
            return getCount() == 0;
        }
    }

    /**
     * Implements some sort of popup selection interface for selecting a spinner option.
     * Allows for different spinner modes.
     */
    private interface SpinnerPopup {
        public void setAdapter(ListAdapter adapter);

        /**
         * Show the popup
         */
        public void show(int textDirection, int textAlignment);

        /**
         * Dismiss the popup
         */
        public void dismiss();

        /**
         * @return true if the popup is showing, false otherwise.
         */
        @UnsupportedAppUsage
        public boolean isShowing();

        /**
         * Set hint text to be displayed to the user. This should provide
         * a description of the choice being made.
         * @param hintText Hint text to set.
         */
        public void setPromptText(CharSequence hintText);
        public CharSequence getHintText();

        public void setBackgroundDrawable(Drawable bg);
        public void setVerticalOffset(int px);
        public void setHorizontalOffset(int px);
        public Drawable getBackground();
        public int getVerticalOffset();
        public int getHorizontalOffset();
    }

    private class DialogPopup implements SpinnerPopup, DialogInterface.OnClickListener {
        private AlertDialog mPopup;
        private ListAdapter mListAdapter;
        private CharSequence mPrompt;

        public void dismiss() {
            if (mPopup != null) {
                mPopup.dismiss();
                mPopup = null;
            }
        }

        @UnsupportedAppUsage
        public boolean isShowing() {
            return mPopup != null ? mPopup.isShowing() : false;
        }

        public void setAdapter(ListAdapter adapter) {
            mListAdapter = adapter;
        }

        public void setPromptText(CharSequence hintText) {
            mPrompt = hintText;
        }

        public CharSequence getHintText() {
            return mPrompt;
        }

        public void show(int textDirection, int textAlignment) {
            if (mListAdapter == null) {
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getPopupContext());
            if (mPrompt != null) {
                builder.setTitle(mPrompt);
            }
            mPopup = builder.setSingleChoiceItems(mListAdapter,
                    getSelectedItemPosition(), this).create();
            final ListView listView = mPopup.getListView();
            listView.setTextDirection(textDirection);
            listView.setTextAlignment(textAlignment);
            mPopup.show();
        }

        public void onClick(DialogInterface dialog, int which) {
            setSelection(which);
            if (mOnItemClickListener != null) {
                performItemClick(null, which, mListAdapter.getItemId(which));
            }
            dismiss();
        }

        @Override
        public void setBackgroundDrawable(Drawable bg) {
            Log.e(TAG, "Cannot set popup background for MODE_DIALOG, ignoring");
        }

        @Override
        public void setVerticalOffset(int px) {
            Log.e(TAG, "Cannot set vertical offset for MODE_DIALOG, ignoring");
        }

        @Override
        public void setHorizontalOffset(int px) {
            Log.e(TAG, "Cannot set horizontal offset for MODE_DIALOG, ignoring");
        }

        @Override
        public Drawable getBackground() {
            return null;
        }

        @Override
        public int getVerticalOffset() {
            return 0;
        }

        @Override
        public int getHorizontalOffset() {
            return 0;
        }
    }

    private class DropdownPopup extends ListPopupWindow implements SpinnerPopup {
        private CharSequence mHintText;
        private ListAdapter mAdapter;

        public DropdownPopup(
                Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
            super(context, attrs, defStyleAttr, defStyleRes);

            setAnchorView(Spinner.this);
            setModal(true);
            setPromptPosition(POSITION_PROMPT_ABOVE);
            setOnItemClickListener(new OnItemClickListener() {
                public void onItemClick(AdapterView parent, View v, int position, long id) {
                    Spinner.this.setSelection(position);
                    if (mOnItemClickListener != null) {
                        Spinner.this.performItemClick(v, position, mAdapter.getItemId(position));
                    }
                    dismiss();
                }
            });
        }

        @Override
        public void setAdapter(ListAdapter adapter) {
            super.setAdapter(adapter);
            mAdapter = adapter;
        }

        public CharSequence getHintText() {
            return mHintText;
        }

        public void setPromptText(CharSequence hintText) {
            // Hint text is ignored for dropdowns, but maintain it here.
            mHintText = hintText;
        }

        void computeContentWidth() {
            final Drawable background = getBackground();
            int hOffset = 0;
            if (background != null) {
                background.getPadding(mTempRect);
                hOffset = isLayoutRtl() ? mTempRect.right : -mTempRect.left;
            } else {
                mTempRect.left = mTempRect.right = 0;
            }

            final int spinnerPaddingLeft = Spinner.this.getPaddingLeft();
            final int spinnerPaddingRight = Spinner.this.getPaddingRight();
            final int spinnerWidth = Spinner.this.getWidth();

            if (mDropDownWidth == WRAP_CONTENT) {
                int contentWidth =  measureContentWidth(
                        (SpinnerAdapter) mAdapter, getBackground());
                final int contentWidthLimit = mContext.getResources()
                        .getDisplayMetrics().widthPixels - mTempRect.left - mTempRect.right;
                if (contentWidth > contentWidthLimit) {
                    contentWidth = contentWidthLimit;
                }
                setContentWidth(Math.max(
                       contentWidth, spinnerWidth - spinnerPaddingLeft - spinnerPaddingRight));
            } else if (mDropDownWidth == MATCH_PARENT) {
                setContentWidth(spinnerWidth - spinnerPaddingLeft - spinnerPaddingRight);
            } else {
                setContentWidth(mDropDownWidth);
            }

            if (isLayoutRtl()) {
                hOffset += spinnerWidth - spinnerPaddingRight - getWidth();
            } else {
                hOffset += spinnerPaddingLeft;
            }
            setHorizontalOffset(hOffset);
        }

        public void show(int textDirection, int textAlignment) {
            final boolean wasShowing = isShowing();

            computeContentWidth();

            setInputMethodMode(ListPopupWindow.INPUT_METHOD_NOT_NEEDED);
            super.show();
            final ListView listView = getListView();
            listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            listView.setTextDirection(textDirection);
            listView.setTextAlignment(textAlignment);
            setSelection(Spinner.this.getSelectedItemPosition());

            if (wasShowing) {
                // Skip setting up the layout/dismiss listener below. If we were previously
                // showing it will still stick around.
                return;
            }

            // Make sure we hide if our anchor goes away.
            // TODO: This might be appropriate to push all the way down to PopupWindow,
            // but it may have other side effects to investigate first. (Text editing handles, etc.)
            final ViewTreeObserver vto = getViewTreeObserver();
            if (vto != null) {
                final OnGlobalLayoutListener layoutListener = new OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (!Spinner.this.isVisibleToUser()) {
                            dismiss();
                        } else {
                            computeContentWidth();

                            // Use super.show here to update; we don't want to move the selected
                            // position or adjust other things that would be reset otherwise.
                            DropdownPopup.super.show();
                        }
                    }
                };
                vto.addOnGlobalLayoutListener(layoutListener);
                setOnDismissListener(new OnDismissListener() {
                    @Override public void onDismiss() {
                        final ViewTreeObserver vto = getViewTreeObserver();
                        if (vto != null) {
                            vto.removeOnGlobalLayoutListener(layoutListener);
                        }
                    }
                });
            }
        }
    }

}
