/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.database.DataSetObserver;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.IntProperty;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.View.OnAttachStateChangeListener;
import android.view.View.OnTouchListener;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.AccelerateDecelerateInterpolator;

import com.android.internal.widget.AutoScrollHelper.AbsListViewAutoScroller;

import java.util.Locale;

/**
 * A ListPopupWindow anchors itself to a host view and displays a
 * list of choices.
 * 
 * <p>ListPopupWindow contains a number of tricky behaviors surrounding
 * positioning, scrolling parents to fit the dropdown, interacting
 * sanely with the IME if present, and others.
 * 
 * @see android.widget.AutoCompleteTextView
 * @see android.widget.Spinner
 */
public class ListPopupWindow {
    private static final String TAG = "ListPopupWindow";
    private static final boolean DEBUG = false;

    /**
     * This value controls the length of time that the user
     * must leave a pointer down without scrolling to expand
     * the autocomplete dropdown list to cover the IME.
     */
    private static final int EXPAND_LIST_TIMEOUT = 250;

    private Context mContext;
    private PopupWindow mPopup;
    private ListAdapter mAdapter;
    private DropDownListView mDropDownList;

    private int mDropDownHeight = ViewGroup.LayoutParams.WRAP_CONTENT;
    private int mDropDownWidth = ViewGroup.LayoutParams.WRAP_CONTENT;
    private int mDropDownHorizontalOffset;
    private int mDropDownVerticalOffset;
    private boolean mDropDownVerticalOffsetSet;

    private int mDropDownGravity = Gravity.NO_GRAVITY;

    private boolean mDropDownAlwaysVisible = false;
    private boolean mForceIgnoreOutsideTouch = false;
    int mListItemExpandMaximum = Integer.MAX_VALUE;

    private View mPromptView;
    private int mPromptPosition = POSITION_PROMPT_ABOVE;

    private DataSetObserver mObserver;

    private View mDropDownAnchorView;

    private Drawable mDropDownListHighlight;

    private AdapterView.OnItemClickListener mItemClickListener;
    private AdapterView.OnItemSelectedListener mItemSelectedListener;

    private final ResizePopupRunnable mResizePopupRunnable = new ResizePopupRunnable();
    private final PopupTouchInterceptor mTouchInterceptor = new PopupTouchInterceptor();
    private final PopupScrollListener mScrollListener = new PopupScrollListener();
    private final ListSelectorHider mHideSelector = new ListSelectorHider();
    private Runnable mShowDropDownRunnable;

    private Handler mHandler = new Handler();

    private Rect mTempRect = new Rect();

    private boolean mModal;

    private int mLayoutDirection;

    /**
     * The provided prompt view should appear above list content.
     * 
     * @see #setPromptPosition(int)
     * @see #getPromptPosition()
     * @see #setPromptView(View)
     */
    public static final int POSITION_PROMPT_ABOVE = 0;

    /**
     * The provided prompt view should appear below list content.
     * 
     * @see #setPromptPosition(int)
     * @see #getPromptPosition()
     * @see #setPromptView(View)
     */
    public static final int POSITION_PROMPT_BELOW = 1;

    /**
     * Alias for {@link ViewGroup.LayoutParams#MATCH_PARENT}.
     * If used to specify a popup width, the popup will match the width of the anchor view.
     * If used to specify a popup height, the popup will fill available space.
     */
    public static final int MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT;
    
    /**
     * Alias for {@link ViewGroup.LayoutParams#WRAP_CONTENT}.
     * If used to specify a popup width, the popup will use the width of its content.
     */
    public static final int WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT;
    
    /**
     * Mode for {@link #setInputMethodMode(int)}: the requirements for the
     * input method should be based on the focusability of the popup.  That is
     * if it is focusable than it needs to work with the input method, else
     * it doesn't.
     */
    public static final int INPUT_METHOD_FROM_FOCUSABLE = PopupWindow.INPUT_METHOD_FROM_FOCUSABLE;
    
    /**
     * Mode for {@link #setInputMethodMode(int)}: this popup always needs to
     * work with an input method, regardless of whether it is focusable.  This
     * means that it will always be displayed so that the user can also operate
     * the input method while it is shown.
     */
    public static final int INPUT_METHOD_NEEDED = PopupWindow.INPUT_METHOD_NEEDED;
    
    /**
     * Mode for {@link #setInputMethodMode(int)}: this popup never needs to
     * work with an input method, regardless of whether it is focusable.  This
     * means that it will always be displayed to use as much space on the
     * screen as needed, regardless of whether this covers the input method.
     */
    public static final int INPUT_METHOD_NOT_NEEDED = PopupWindow.INPUT_METHOD_NOT_NEEDED;

    /**
     * Create a new, empty popup window capable of displaying items from a ListAdapter.
     * Backgrounds should be set using {@link #setBackgroundDrawable(Drawable)}.
     * 
     * @param context Context used for contained views.
     */
    public ListPopupWindow(Context context) {
        this(context, null, com.android.internal.R.attr.listPopupWindowStyle, 0);
    }

    /**
     * Create a new, empty popup window capable of displaying items from a ListAdapter.
     * Backgrounds should be set using {@link #setBackgroundDrawable(Drawable)}.
     * 
     * @param context Context used for contained views.
     * @param attrs Attributes from inflating parent views used to style the popup.
     */
    public ListPopupWindow(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.listPopupWindowStyle, 0);
    }

    /**
     * Create a new, empty popup window capable of displaying items from a ListAdapter.
     * Backgrounds should be set using {@link #setBackgroundDrawable(Drawable)}.
     * 
     * @param context Context used for contained views.
     * @param attrs Attributes from inflating parent views used to style the popup.
     * @param defStyleAttr Default style attribute to use for popup content.
     */
    public ListPopupWindow(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    /**
     * Create a new, empty popup window capable of displaying items from a ListAdapter.
     * Backgrounds should be set using {@link #setBackgroundDrawable(Drawable)}.
     * 
     * @param context Context used for contained views.
     * @param attrs Attributes from inflating parent views used to style the popup.
     * @param defStyleAttr Style attribute to read for default styling of popup content.
     * @param defStyleRes Style resource ID to use for default styling of popup content.
     */
    public ListPopupWindow(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        mContext = context;
        mPopup = new PopupWindow(context, attrs, defStyleAttr, defStyleRes);
        mPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        // Set the default layout direction to match the default locale one
        final Locale locale = mContext.getResources().getConfiguration().locale;
        mLayoutDirection = TextUtils.getLayoutDirectionFromLocale(locale);
    }

    /**
     * Sets the adapter that provides the data and the views to represent the data
     * in this popup window.
     *
     * @param adapter The adapter to use to create this window's content.
     */
    public void setAdapter(ListAdapter adapter) {
        if (mObserver == null) {
            mObserver = new PopupDataSetObserver();
        } else if (mAdapter != null) {
            mAdapter.unregisterDataSetObserver(mObserver);
        }
        mAdapter = adapter;
        if (mAdapter != null) {
            adapter.registerDataSetObserver(mObserver);
        }
        
        if (mDropDownList != null) {
            mDropDownList.setAdapter(mAdapter);
        }
    }

    /**
     * Set where the optional prompt view should appear. The default is
     * {@link #POSITION_PROMPT_ABOVE}.
     * 
     * @param position A position constant declaring where the prompt should be displayed.
     * 
     * @see #POSITION_PROMPT_ABOVE
     * @see #POSITION_PROMPT_BELOW
     */
    public void setPromptPosition(int position) {
        mPromptPosition = position;
    }

    /**
     * @return Where the optional prompt view should appear.
     * 
     * @see #POSITION_PROMPT_ABOVE
     * @see #POSITION_PROMPT_BELOW
     */
    public int getPromptPosition() {
        return mPromptPosition;
    }

    /**
     * Set whether this window should be modal when shown.
     * 
     * <p>If a popup window is modal, it will receive all touch and key input.
     * If the user touches outside the popup window's content area the popup window
     * will be dismissed.
     * 
     * @param modal {@code true} if the popup window should be modal, {@code false} otherwise.
     */
    public void setModal(boolean modal) {
        mModal = true;
        mPopup.setFocusable(modal);
    }

    /**
     * Returns whether the popup window will be modal when shown.
     * 
     * @return {@code true} if the popup window will be modal, {@code false} otherwise.
     */
    public boolean isModal() {
        return mModal;
    }

    /**
     * Forces outside touches to be ignored. Normally if {@link #isDropDownAlwaysVisible()} is
     * false, we allow outside touch to dismiss the dropdown. If this is set to true, then we
     * ignore outside touch even when the drop down is not set to always visible.
     * 
     * @hide Used only by AutoCompleteTextView to handle some internal special cases.
     */
    public void setForceIgnoreOutsideTouch(boolean forceIgnoreOutsideTouch) {
        mForceIgnoreOutsideTouch = forceIgnoreOutsideTouch;
    }

    /**
     * Sets whether the drop-down should remain visible under certain conditions.
     *
     * The drop-down will occupy the entire screen below {@link #getAnchorView} regardless
     * of the size or content of the list.  {@link #getBackground()} will fill any space
     * that is not used by the list.
     *
     * @param dropDownAlwaysVisible Whether to keep the drop-down visible.
     *
     * @hide Only used by AutoCompleteTextView under special conditions.
     */
    public void setDropDownAlwaysVisible(boolean dropDownAlwaysVisible) {
        mDropDownAlwaysVisible = dropDownAlwaysVisible;
    }

    /**
     * @return Whether the drop-down is visible under special conditions.
     *
     * @hide Only used by AutoCompleteTextView under special conditions.
     */
    public boolean isDropDownAlwaysVisible() {
        return mDropDownAlwaysVisible;
    }

    /**
     * Sets the operating mode for the soft input area.
     *
     * @param mode The desired mode, see
     *        {@link android.view.WindowManager.LayoutParams#softInputMode}
     *        for the full list
     *
     * @see android.view.WindowManager.LayoutParams#softInputMode
     * @see #getSoftInputMode()
     */
    public void setSoftInputMode(int mode) {
        mPopup.setSoftInputMode(mode);
    }

    /**
     * Returns the current value in {@link #setSoftInputMode(int)}.
     *
     * @see #setSoftInputMode(int)
     * @see android.view.WindowManager.LayoutParams#softInputMode
     */
    public int getSoftInputMode() {
        return mPopup.getSoftInputMode();
    }

    /**
     * Sets a drawable to use as the list item selector.
     * 
     * @param selector List selector drawable to use in the popup.
     */
    public void setListSelector(Drawable selector) {
        mDropDownListHighlight = selector;
    }

    /**
     * @return The background drawable for the popup window.
     */
    public Drawable getBackground() {
        return mPopup.getBackground();
    }

    /**
     * Sets a drawable to be the background for the popup window.
     * 
     * @param d A drawable to set as the background.
     */
    public void setBackgroundDrawable(Drawable d) {
        mPopup.setBackgroundDrawable(d);
    }

    /**
     * Set an animation style to use when the popup window is shown or dismissed.
     * 
     * @param animationStyle Animation style to use.
     */
    public void setAnimationStyle(int animationStyle) {
        mPopup.setAnimationStyle(animationStyle);
    }

    /**
     * Returns the animation style that will be used when the popup window is
     * shown or dismissed.
     * 
     * @return Animation style that will be used.
     */
    public int getAnimationStyle() {
        return mPopup.getAnimationStyle();
    }

    /**
     * Returns the view that will be used to anchor this popup.
     * 
     * @return The popup's anchor view
     */
    public View getAnchorView() {
        return mDropDownAnchorView;
    }

    /**
     * Sets the popup's anchor view. This popup will always be positioned relative to
     * the anchor view when shown.
     * 
     * @param anchor The view to use as an anchor.
     */
    public void setAnchorView(View anchor) {
        mDropDownAnchorView = anchor;
    }

    /**
     * @return The horizontal offset of the popup from its anchor in pixels.
     */
    public int getHorizontalOffset() {
        return mDropDownHorizontalOffset;
    }

    /**
     * Set the horizontal offset of this popup from its anchor view in pixels.
     * 
     * @param offset The horizontal offset of the popup from its anchor.
     */
    public void setHorizontalOffset(int offset) {
        mDropDownHorizontalOffset = offset;
    }

    /**
     * @return The vertical offset of the popup from its anchor in pixels.
     */
    public int getVerticalOffset() {
        if (!mDropDownVerticalOffsetSet) {
            return 0;
        }
        return mDropDownVerticalOffset;
    }

    /**
     * Set the vertical offset of this popup from its anchor view in pixels.
     * 
     * @param offset The vertical offset of the popup from its anchor.
     */
    public void setVerticalOffset(int offset) {
        mDropDownVerticalOffset = offset;
        mDropDownVerticalOffsetSet = true;
    }

    /**
     * Set the gravity of the dropdown list. This is commonly used to
     * set gravity to START or END for alignment with the anchor.
     *
     * @param gravity Gravity value to use
     */
    public void setDropDownGravity(int gravity) {
        mDropDownGravity = gravity;
    }

    /**
     * @return The width of the popup window in pixels.
     */
    public int getWidth() {
        return mDropDownWidth;
    }

    /**
     * Sets the width of the popup window in pixels. Can also be {@link #MATCH_PARENT}
     * or {@link #WRAP_CONTENT}.
     * 
     * @param width Width of the popup window.
     */
    public void setWidth(int width) {
        mDropDownWidth = width;
    }

    /**
     * Sets the width of the popup window by the size of its content. The final width may be
     * larger to accommodate styled window dressing.
     *
     * @param width Desired width of content in pixels.
     */
    public void setContentWidth(int width) {
        Drawable popupBackground = mPopup.getBackground();
        if (popupBackground != null) {
            popupBackground.getPadding(mTempRect);
            mDropDownWidth = mTempRect.left + mTempRect.right + width;
        } else {
            setWidth(width);
        }
    }

    /**
     * @return The height of the popup window in pixels.
     */
    public int getHeight() {
        return mDropDownHeight;
    }

    /**
     * Sets the height of the popup window in pixels. Can also be {@link #MATCH_PARENT}.
     * 
     * @param height Height of the popup window.
     */
    public void setHeight(int height) {
        mDropDownHeight = height;
    }

    /**
     * Sets a listener to receive events when a list item is clicked.
     * 
     * @param clickListener Listener to register
     * 
     * @see ListView#setOnItemClickListener(android.widget.AdapterView.OnItemClickListener)
     */
    public void setOnItemClickListener(AdapterView.OnItemClickListener clickListener) {
        mItemClickListener = clickListener;
    }

    /**
     * Sets a listener to receive events when a list item is selected.
     * 
     * @param selectedListener Listener to register.
     * 
     * @see ListView#setOnItemSelectedListener(android.widget.AdapterView.OnItemSelectedListener)
     */
    public void setOnItemSelectedListener(AdapterView.OnItemSelectedListener selectedListener) {
        mItemSelectedListener = selectedListener;
    }

    /**
     * Set a view to act as a user prompt for this popup window. Where the prompt view will appear
     * is controlled by {@link #setPromptPosition(int)}.
     * 
     * @param prompt View to use as an informational prompt.
     */
    public void setPromptView(View prompt) {
        boolean showing = isShowing();
        if (showing) {
            removePromptView();
        }
        mPromptView = prompt;
        if (showing) {
            show();
        }
    }

    /**
     * Post a {@link #show()} call to the UI thread.
     */
    public void postShow() {
        mHandler.post(mShowDropDownRunnable);
    }

    /**
     * Show the popup list. If the list is already showing, this method
     * will recalculate the popup's size and position.
     */
    public void show() {
        int height = buildDropDown();

        int widthSpec = 0;
        int heightSpec = 0;

        boolean noInputMethod = isInputMethodNotNeeded();
        mPopup.setAllowScrollingAnchorParent(!noInputMethod);

        if (mPopup.isShowing()) {
            if (mDropDownWidth == ViewGroup.LayoutParams.MATCH_PARENT) {
                // The call to PopupWindow's update method below can accept -1 for any
                // value you do not want to update.
                widthSpec = -1;
            } else if (mDropDownWidth == ViewGroup.LayoutParams.WRAP_CONTENT) {
                widthSpec = getAnchorView().getWidth();
            } else {
                widthSpec = mDropDownWidth;
            }

            if (mDropDownHeight == ViewGroup.LayoutParams.MATCH_PARENT) {
                // The call to PopupWindow's update method below can accept -1 for any
                // value you do not want to update.
                heightSpec = noInputMethod ? height : ViewGroup.LayoutParams.MATCH_PARENT;
                if (noInputMethod) {
                    mPopup.setWindowLayoutMode(
                            mDropDownWidth == ViewGroup.LayoutParams.MATCH_PARENT ?
                                    ViewGroup.LayoutParams.MATCH_PARENT : 0, 0);
                } else {
                    mPopup.setWindowLayoutMode(
                            mDropDownWidth == ViewGroup.LayoutParams.MATCH_PARENT ?
                                    ViewGroup.LayoutParams.MATCH_PARENT : 0,
                            ViewGroup.LayoutParams.MATCH_PARENT);
                }
            } else if (mDropDownHeight == ViewGroup.LayoutParams.WRAP_CONTENT) {
                heightSpec = height;
            } else {
                heightSpec = mDropDownHeight;
            }

            mPopup.setOutsideTouchable(!mForceIgnoreOutsideTouch && !mDropDownAlwaysVisible);

            mPopup.update(getAnchorView(), mDropDownHorizontalOffset,
                    mDropDownVerticalOffset, widthSpec, heightSpec);
        } else {
            if (mDropDownWidth == ViewGroup.LayoutParams.MATCH_PARENT) {
                widthSpec = ViewGroup.LayoutParams.MATCH_PARENT;
            } else {
                if (mDropDownWidth == ViewGroup.LayoutParams.WRAP_CONTENT) {
                    mPopup.setWidth(getAnchorView().getWidth());
                } else {
                    mPopup.setWidth(mDropDownWidth);
                }
            }

            if (mDropDownHeight == ViewGroup.LayoutParams.MATCH_PARENT) {
                heightSpec = ViewGroup.LayoutParams.MATCH_PARENT;
            } else {
                if (mDropDownHeight == ViewGroup.LayoutParams.WRAP_CONTENT) {
                    mPopup.setHeight(height);
                } else {
                    mPopup.setHeight(mDropDownHeight);
                }
            }

            mPopup.setWindowLayoutMode(widthSpec, heightSpec);
            mPopup.setClipToScreenEnabled(true);
            
            // use outside touchable to dismiss drop down when touching outside of it, so
            // only set this if the dropdown is not always visible
            mPopup.setOutsideTouchable(!mForceIgnoreOutsideTouch && !mDropDownAlwaysVisible);
            mPopup.setTouchInterceptor(mTouchInterceptor);
            mPopup.showAsDropDown(getAnchorView(),
                    mDropDownHorizontalOffset, mDropDownVerticalOffset, mDropDownGravity);
            mDropDownList.setSelection(ListView.INVALID_POSITION);
            
            if (!mModal || mDropDownList.isInTouchMode()) {
                clearListSelection();
            }
            if (!mModal) {
                mHandler.post(mHideSelector);
            }
        }
    }

    /**
     * Dismiss the popup window.
     */
    public void dismiss() {
        mPopup.dismiss();
        removePromptView();
        mPopup.setContentView(null);
        mDropDownList = null;
        mHandler.removeCallbacks(mResizePopupRunnable);
    }

    /**
     * Set a listener to receive a callback when the popup is dismissed.
     *
     * @param listener Listener that will be notified when the popup is dismissed.
     */
    public void setOnDismissListener(PopupWindow.OnDismissListener listener) {
        mPopup.setOnDismissListener(listener);
    }

    private void removePromptView() {
        if (mPromptView != null) {
            final ViewParent parent = mPromptView.getParent();
            if (parent instanceof ViewGroup) {
                final ViewGroup group = (ViewGroup) parent;
                group.removeView(mPromptView);
            }
        }
    }

    /**
     * Control how the popup operates with an input method: one of
     * {@link #INPUT_METHOD_FROM_FOCUSABLE}, {@link #INPUT_METHOD_NEEDED},
     * or {@link #INPUT_METHOD_NOT_NEEDED}.
     * 
     * <p>If the popup is showing, calling this method will take effect only
     * the next time the popup is shown or through a manual call to the {@link #show()}
     * method.</p>
     * 
     * @see #getInputMethodMode()
     * @see #show()
     */
    public void setInputMethodMode(int mode) {
        mPopup.setInputMethodMode(mode);
    }

    /**
     * Return the current value in {@link #setInputMethodMode(int)}.
     * 
     * @see #setInputMethodMode(int)
     */
    public int getInputMethodMode() {
        return mPopup.getInputMethodMode();
    }

    /**
     * Set the selected position of the list.
     * Only valid when {@link #isShowing()} == {@code true}.
     * 
     * @param position List position to set as selected.
     */
    public void setSelection(int position) {
        DropDownListView list = mDropDownList;
        if (isShowing() && list != null) {
            list.mListSelectionHidden = false;
            list.setSelection(position);
            if (list.getChoiceMode() != ListView.CHOICE_MODE_NONE) {
                list.setItemChecked(position, true);
            }
        }
    }

    /**
     * Clear any current list selection.
     * Only valid when {@link #isShowing()} == {@code true}.
     */
    public void clearListSelection() {
        final DropDownListView list = mDropDownList;
        if (list != null) {
            // WARNING: Please read the comment where mListSelectionHidden is declared
            list.mListSelectionHidden = true;
            list.hideSelector();
            list.requestLayout();
        }
    }

    /**
     * @return {@code true} if the popup is currently showing, {@code false} otherwise.
     */
    public boolean isShowing() {
        return mPopup.isShowing();
    }

    /**
     * @return {@code true} if this popup is configured to assume the user does not need
     * to interact with the IME while it is showing, {@code false} otherwise.
     */
    public boolean isInputMethodNotNeeded() {
        return mPopup.getInputMethodMode() == INPUT_METHOD_NOT_NEEDED;
    }

    /**
     * Perform an item click operation on the specified list adapter position.
     * 
     * @param position Adapter position for performing the click
     * @return true if the click action could be performed, false if not.
     *         (e.g. if the popup was not showing, this method would return false.)
     */
    public boolean performItemClick(int position) {
        if (isShowing()) {
            if (mItemClickListener != null) {
                final DropDownListView list = mDropDownList;
                final View child = list.getChildAt(position - list.getFirstVisiblePosition());
                final ListAdapter adapter = list.getAdapter();
                mItemClickListener.onItemClick(list, child, position, adapter.getItemId(position));
            }
            return true;
        }
        return false;
    }

    /**
     * @return The currently selected item or null if the popup is not showing.
     */
    public Object getSelectedItem() {
        if (!isShowing()) {
            return null;
        }
        return mDropDownList.getSelectedItem();
    }

    /**
     * @return The position of the currently selected item or {@link ListView#INVALID_POSITION}
     * if {@link #isShowing()} == {@code false}.
     * 
     * @see ListView#getSelectedItemPosition()
     */
    public int getSelectedItemPosition() {
        if (!isShowing()) {
            return ListView.INVALID_POSITION;
        }
        return mDropDownList.getSelectedItemPosition();
    }

    /**
     * @return The ID of the currently selected item or {@link ListView#INVALID_ROW_ID}
     * if {@link #isShowing()} == {@code false}.
     * 
     * @see ListView#getSelectedItemId()
     */
    public long getSelectedItemId() {
        if (!isShowing()) {
            return ListView.INVALID_ROW_ID;
        }
        return mDropDownList.getSelectedItemId();
    }

    /**
     * @return The View for the currently selected item or null if
     * {@link #isShowing()} == {@code false}.
     * 
     * @see ListView#getSelectedView()
     */
    public View getSelectedView() {
        if (!isShowing()) {
            return null;
        }
        return mDropDownList.getSelectedView();
    }

    /**
     * @return The {@link ListView} displayed within the popup window.
     * Only valid when {@link #isShowing()} == {@code true}.
     */
    public ListView getListView() {
        return mDropDownList;
    }

    /**
     * The maximum number of list items that can be visible and still have
     * the list expand when touched.
     *
     * @param max Max number of items that can be visible and still allow the list to expand.
     */
    void setListItemExpandMax(int max) {
        mListItemExpandMaximum = max;
    }

    /**
     * Filter key down events. By forwarding key down events to this function,
     * views using non-modal ListPopupWindow can have it handle key selection of items.
     *  
     * @param keyCode keyCode param passed to the host view's onKeyDown
     * @param event event param passed to the host view's onKeyDown
     * @return true if the event was handled, false if it was ignored.
     * 
     * @see #setModal(boolean)
     */
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // when the drop down is shown, we drive it directly
        if (isShowing()) {
            // the key events are forwarded to the list in the drop down view
            // note that ListView handles space but we don't want that to happen
            // also if selection is not currently in the drop down, then don't
            // let center or enter presses go there since that would cause it
            // to select one of its items
            if (keyCode != KeyEvent.KEYCODE_SPACE
                    && (mDropDownList.getSelectedItemPosition() >= 0
                            || !KeyEvent.isConfirmKey(keyCode))) {
                int curIndex = mDropDownList.getSelectedItemPosition();
                boolean consumed;

                final boolean below = !mPopup.isAboveAnchor();

                final ListAdapter adapter = mAdapter;
                
                boolean allEnabled;
                int firstItem = Integer.MAX_VALUE;
                int lastItem = Integer.MIN_VALUE;

                if (adapter != null) {
                    allEnabled = adapter.areAllItemsEnabled();
                    firstItem = allEnabled ? 0 :
                            mDropDownList.lookForSelectablePosition(0, true);
                    lastItem = allEnabled ? adapter.getCount() - 1 :
                            mDropDownList.lookForSelectablePosition(adapter.getCount() - 1, false);                    
                }
                
                if ((below && keyCode == KeyEvent.KEYCODE_DPAD_UP && curIndex <= firstItem) ||
                        (!below && keyCode == KeyEvent.KEYCODE_DPAD_DOWN && curIndex >= lastItem)) {
                    // When the selection is at the top, we block the key
                    // event to prevent focus from moving.
                    clearListSelection();
                    mPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
                    show();
                    return true;
                } else {
                    // WARNING: Please read the comment where mListSelectionHidden
                    //          is declared
                    mDropDownList.mListSelectionHidden = false;
                }

                consumed = mDropDownList.onKeyDown(keyCode, event);
                if (DEBUG) Log.v(TAG, "Key down: code=" + keyCode + " list consumed=" + consumed);

                if (consumed) {
                    // If it handled the key event, then the user is
                    // navigating in the list, so we should put it in front.
                    mPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
                    // Here's a little trick we need to do to make sure that
                    // the list view is actually showing its focus indicator,
                    // by ensuring it has focus and getting its window out
                    // of touch mode.
                    mDropDownList.requestFocusFromTouch();
                    show();

                    switch (keyCode) {
                        // avoid passing the focus from the text view to the
                        // next component
                        case KeyEvent.KEYCODE_ENTER:
                        case KeyEvent.KEYCODE_DPAD_CENTER:
                        case KeyEvent.KEYCODE_DPAD_DOWN:
                        case KeyEvent.KEYCODE_DPAD_UP:
                            return true;
                    }
                } else {
                    if (below && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        // when the selection is at the bottom, we block the
                        // event to avoid going to the next focusable widget
                        if (curIndex == lastItem) {
                            return true;
                        }
                    } else if (!below && keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                            curIndex == firstItem) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Filter key down events. By forwarding key up events to this function,
     * views using non-modal ListPopupWindow can have it handle key selection of items.
     *  
     * @param keyCode keyCode param passed to the host view's onKeyUp
     * @param event event param passed to the host view's onKeyUp
     * @return true if the event was handled, false if it was ignored.
     * 
     * @see #setModal(boolean)
     */
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isShowing() && mDropDownList.getSelectedItemPosition() >= 0) {
            boolean consumed = mDropDownList.onKeyUp(keyCode, event);
            if (consumed && KeyEvent.isConfirmKey(keyCode)) {
                // if the list accepts the key events and the key event was a click, the text view
                // gets the selected item from the drop down as its content
                dismiss();
            }
            return consumed;
        }
        return false;
    }

    /**
     * Filter pre-IME key events. By forwarding {@link View#onKeyPreIme(int, KeyEvent)}
     * events to this function, views using ListPopupWindow can have it dismiss the popup
     * when the back key is pressed.
     *  
     * @param keyCode keyCode param passed to the host view's onKeyPreIme
     * @param event event param passed to the host view's onKeyPreIme
     * @return true if the event was handled, false if it was ignored.
     * 
     * @see #setModal(boolean)
     */
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && isShowing()) {
            // special case for the back key, we do not even try to send it
            // to the drop down list but instead, consume it immediately
            final View anchorView = mDropDownAnchorView;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                KeyEvent.DispatcherState state = anchorView.getKeyDispatcherState();
                if (state != null) {
                    state.startTracking(event, this);
                }
                return true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                KeyEvent.DispatcherState state = anchorView.getKeyDispatcherState();
                if (state != null) {
                    state.handleUpEvent(event);
                }
                if (event.isTracking() && !event.isCanceled()) {
                    dismiss();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns an {@link OnTouchListener} that can be added to the source view
     * to implement drag-to-open behavior. Generally, the source view should be
     * the same view that was passed to {@link #setAnchorView}.
     * <p>
     * When the listener is set on a view, touching that view and dragging
     * outside of its bounds will open the popup window. Lifting will select the
     * currently touched list item.
     * <p>
     * Example usage:
     * <pre>
     * ListPopupWindow myPopup = new ListPopupWindow(context);
     * myPopup.setAnchor(myAnchor);
     * OnTouchListener dragListener = myPopup.createDragToOpenListener(myAnchor);
     * myAnchor.setOnTouchListener(dragListener);
     * </pre>
     *
     * @param src the view on which the resulting listener will be set
     * @return a touch listener that controls drag-to-open behavior
     */
    public OnTouchListener createDragToOpenListener(View src) {
        return new ForwardingListener(src) {
            @Override
            public ListPopupWindow getPopup() {
                return ListPopupWindow.this;
            }
        };
    }

    /**
     * <p>Builds the popup window's content and returns the height the popup
     * should have. Returns -1 when the content already exists.</p>
     *
     * @return the content's height or -1 if content already exists
     */
    private int buildDropDown() {
        ViewGroup dropDownView;
        int otherHeights = 0;

        if (mDropDownList == null) {
            Context context = mContext;

            /**
             * This Runnable exists for the sole purpose of checking if the view layout has got
             * completed and if so call showDropDown to display the drop down. This is used to show
             * the drop down as soon as possible after user opens up the search dialog, without
             * waiting for the normal UI pipeline to do it's job which is slower than this method.
             */
            mShowDropDownRunnable = new Runnable() {
                public void run() {
                    // View layout should be all done before displaying the drop down.
                    View view = getAnchorView();
                    if (view != null && view.getWindowToken() != null) {
                        show();
                    }
                }
            };

            mDropDownList = new DropDownListView(context, !mModal);
            if (mDropDownListHighlight != null) {
                mDropDownList.setSelector(mDropDownListHighlight);
            }
            mDropDownList.setAdapter(mAdapter);
            mDropDownList.setOnItemClickListener(mItemClickListener);
            mDropDownList.setFocusable(true);
            mDropDownList.setFocusableInTouchMode(true);
            mDropDownList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> parent, View view,
                        int position, long id) {

                    if (position != -1) {
                        DropDownListView dropDownList = mDropDownList;

                        if (dropDownList != null) {
                            dropDownList.mListSelectionHidden = false;
                        }
                    }
                }

                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
            mDropDownList.setOnScrollListener(mScrollListener);

            if (mItemSelectedListener != null) {
                mDropDownList.setOnItemSelectedListener(mItemSelectedListener);
            }

            dropDownView = mDropDownList;

            View hintView = mPromptView;
            if (hintView != null) {
                // if a hint has been specified, we accomodate more space for it and
                // add a text view in the drop down menu, at the bottom of the list
                LinearLayout hintContainer = new LinearLayout(context);
                hintContainer.setOrientation(LinearLayout.VERTICAL);

                LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f
                );
                
                switch (mPromptPosition) {
                case POSITION_PROMPT_BELOW:
                    hintContainer.addView(dropDownView, hintParams);
                    hintContainer.addView(hintView);
                    break;
                    
                case POSITION_PROMPT_ABOVE:
                    hintContainer.addView(hintView);
                    hintContainer.addView(dropDownView, hintParams);
                    break;
                    
                default:
                    Log.e(TAG, "Invalid hint position " + mPromptPosition);
                    break;
                }

                // measure the hint's height to find how much more vertical space
                // we need to add to the drop down's height
                int widthSpec = MeasureSpec.makeMeasureSpec(mDropDownWidth, MeasureSpec.AT_MOST);
                int heightSpec = MeasureSpec.UNSPECIFIED;
                hintView.measure(widthSpec, heightSpec);

                hintParams = (LinearLayout.LayoutParams) hintView.getLayoutParams();
                otherHeights = hintView.getMeasuredHeight() + hintParams.topMargin
                        + hintParams.bottomMargin;

                dropDownView = hintContainer;
            }

            mPopup.setContentView(dropDownView);
        } else {
            dropDownView = (ViewGroup) mPopup.getContentView();
            final View view = mPromptView;
            if (view != null) {
                LinearLayout.LayoutParams hintParams =
                        (LinearLayout.LayoutParams) view.getLayoutParams();
                otherHeights = view.getMeasuredHeight() + hintParams.topMargin
                        + hintParams.bottomMargin;
            }
        }

        // getMaxAvailableHeight() subtracts the padding, so we put it back
        // to get the available height for the whole window
        int padding = 0;
        Drawable background = mPopup.getBackground();
        if (background != null) {
            background.getPadding(mTempRect);
            padding = mTempRect.top + mTempRect.bottom;

            // If we don't have an explicit vertical offset, determine one from the window
            // background so that content will line up.
            if (!mDropDownVerticalOffsetSet) {
                mDropDownVerticalOffset = -mTempRect.top;
            }
        } else {
            mTempRect.setEmpty();
        }

        // Max height available on the screen for a popup.
        boolean ignoreBottomDecorations =
                mPopup.getInputMethodMode() == PopupWindow.INPUT_METHOD_NOT_NEEDED;
        final int maxHeight = mPopup.getMaxAvailableHeight(
                getAnchorView(), mDropDownVerticalOffset, ignoreBottomDecorations);

        if (mDropDownAlwaysVisible || mDropDownHeight == ViewGroup.LayoutParams.MATCH_PARENT) {
            return maxHeight + padding;
        }

        final int childWidthSpec;
        switch (mDropDownWidth) {
            case ViewGroup.LayoutParams.WRAP_CONTENT:
                childWidthSpec = MeasureSpec.makeMeasureSpec(
                        mContext.getResources().getDisplayMetrics().widthPixels -
                        (mTempRect.left + mTempRect.right),
                        MeasureSpec.AT_MOST);
                break;
            case ViewGroup.LayoutParams.MATCH_PARENT:
                childWidthSpec = MeasureSpec.makeMeasureSpec(
                        mContext.getResources().getDisplayMetrics().widthPixels -
                        (mTempRect.left + mTempRect.right),
                        MeasureSpec.EXACTLY);
                break;
            default:
                childWidthSpec = MeasureSpec.makeMeasureSpec(mDropDownWidth, MeasureSpec.EXACTLY);
                break;
        }
        final int listContent = mDropDownList.measureHeightOfChildren(childWidthSpec,
                0, ListView.NO_POSITION, maxHeight - otherHeights, -1);
        // add padding only if the list has items in it, that way we don't show
        // the popup if it is not needed
        if (listContent > 0) otherHeights += padding;

        return listContent + otherHeights;
    }

    /**
     * Abstract class that forwards touch events to a {@link ListPopupWindow}.
     *
     * @hide
     */
    public static abstract class ForwardingListener
            implements View.OnTouchListener, View.OnAttachStateChangeListener {
        /** Scaled touch slop, used for detecting movement outside bounds. */
        private final float mScaledTouchSlop;

        /** Timeout before disallowing intercept on the source's parent. */
        private final int mTapTimeout;

        /** Source view from which events are forwarded. */
        private final View mSrc;

        /** Runnable used to prevent conflicts with scrolling parents. */
        private Runnable mDisallowIntercept;

        /** Whether this listener is currently forwarding touch events. */
        private boolean mForwarding;

        /** The id of the first pointer down in the current event stream. */
        private int mActivePointerId;

        public ForwardingListener(View src) {
            mSrc = src;
            mScaledTouchSlop = ViewConfiguration.get(src.getContext()).getScaledTouchSlop();
            mTapTimeout = ViewConfiguration.getTapTimeout();

            src.addOnAttachStateChangeListener(this);
        }

        /**
         * Returns the popup to which this listener is forwarding events.
         * <p>
         * Override this to return the correct popup. If the popup is displayed
         * asynchronously, you may also need to override
         * {@link #onForwardingStopped} to prevent premature cancelation of
         * forwarding.
         *
         * @return the popup to which this listener is forwarding events
         */
        public abstract ListPopupWindow getPopup();

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            final boolean wasForwarding = mForwarding;
            final boolean forwarding;
            if (wasForwarding) {
                forwarding = onTouchForwarded(event) || !onForwardingStopped();
            } else {
                forwarding = onTouchObserved(event) && onForwardingStarted();
            }

            mForwarding = forwarding;
            return forwarding || wasForwarding;
        }

        @Override
        public void onViewAttachedToWindow(View v) {
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            mForwarding = false;
            mActivePointerId = MotionEvent.INVALID_POINTER_ID;

            if (mDisallowIntercept != null) {
                mSrc.removeCallbacks(mDisallowIntercept);
            }
        }

        /**
         * Called when forwarding would like to start.
         * <p>
         * By default, this will show the popup returned by {@link #getPopup()}.
         * It may be overridden to perform another action, like clicking the
         * source view or preparing the popup before showing it.
         *
         * @return true to start forwarding, false otherwise
         */
        protected boolean onForwardingStarted() {
            final ListPopupWindow popup = getPopup();
            if (popup != null && !popup.isShowing()) {
                popup.show();
            }
            return true;
        }

        /**
         * Called when forwarding would like to stop.
         * <p>
         * By default, this will dismiss the popup returned by
         * {@link #getPopup()}. It may be overridden to perform some other
         * action.
         *
         * @return true to stop forwarding, false otherwise
         */
        protected boolean onForwardingStopped() {
            final ListPopupWindow popup = getPopup();
            if (popup != null && popup.isShowing()) {
                popup.dismiss();
            }
            return true;
        }

        /**
         * Observes motion events and determines when to start forwarding.
         *
         * @param srcEvent motion event in source view coordinates
         * @return true to start forwarding motion events, false otherwise
         */
        private boolean onTouchObserved(MotionEvent srcEvent) {
            final View src = mSrc;
            if (!src.isEnabled()) {
                return false;
            }

            final int actionMasked = srcEvent.getActionMasked();
            switch (actionMasked) {
                case MotionEvent.ACTION_DOWN:
                    mActivePointerId = srcEvent.getPointerId(0);
                    if (mDisallowIntercept == null) {
                        mDisallowIntercept = new DisallowIntercept();
                    }
                    src.postDelayed(mDisallowIntercept, mTapTimeout);
                    break;
                case MotionEvent.ACTION_MOVE:
                    final int activePointerIndex = srcEvent.findPointerIndex(mActivePointerId);
                    if (activePointerIndex >= 0) {
                        final float x = srcEvent.getX(activePointerIndex);
                        final float y = srcEvent.getY(activePointerIndex);
                        if (!src.pointInView(x, y, mScaledTouchSlop)) {
                            // The pointer has moved outside of the view.
                            if (mDisallowIntercept != null) {
                                src.removeCallbacks(mDisallowIntercept);
                            }
                            src.getParent().requestDisallowInterceptTouchEvent(true);
                            return true;
                        }
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    if (mDisallowIntercept != null) {
                        src.removeCallbacks(mDisallowIntercept);
                    }
                    break;
            }

            return false;
        }

        /**
         * Handled forwarded motion events and determines when to stop
         * forwarding.
         *
         * @param srcEvent motion event in source view coordinates
         * @return true to continue forwarding motion events, false to cancel
         */
        private boolean onTouchForwarded(MotionEvent srcEvent) {
            final View src = mSrc;
            final ListPopupWindow popup = getPopup();
            if (popup == null || !popup.isShowing()) {
                return false;
            }

            final DropDownListView dst = popup.mDropDownList;
            if (dst == null || !dst.isShown()) {
                return false;
            }

            // Convert event to destination-local coordinates.
            final MotionEvent dstEvent = MotionEvent.obtainNoHistory(srcEvent);
            src.toGlobalMotionEvent(dstEvent);
            dst.toLocalMotionEvent(dstEvent);

            // Forward converted event to destination view, then recycle it.
            final boolean handled = dst.onForwardedEvent(dstEvent, mActivePointerId);
            dstEvent.recycle();
            return handled;
        }

        private class DisallowIntercept implements Runnable {
            @Override
            public void run() {
                final ViewParent parent = mSrc.getParent();
                parent.requestDisallowInterceptTouchEvent(true);
            }
        }
    }

    /**
     * <p>Wrapper class for a ListView. This wrapper can hijack the focus to
     * make sure the list uses the appropriate drawables and states when
     * displayed on screen within a drop down. The focus is never actually
     * passed to the drop down in this mode; the list only looks focused.</p>
     */
    private static class DropDownListView extends ListView {
        /** Duration in milliseconds of the drag-to-open click animation. */
        private static final long CLICK_ANIM_DURATION = 150;

        /** Target alpha value for drag-to-open click animation. */
        private static final int CLICK_ANIM_ALPHA = 0x80;

        /** Wrapper around Drawable's <code>alpha</code> property. */
        private static final IntProperty<Drawable> DRAWABLE_ALPHA =
                new IntProperty<Drawable>("alpha") {
                    @Override
                    public void setValue(Drawable object, int value) {
                        object.setAlpha(value);
                    }

                    @Override
                    public Integer get(Drawable object) {
                        return object.getAlpha();
                    }
                };

        /*
         * WARNING: This is a workaround for a touch mode issue.
         *
         * Touch mode is propagated lazily to windows. This causes problems in
         * the following scenario:
         * - Type something in the AutoCompleteTextView and get some results
         * - Move down with the d-pad to select an item in the list
         * - Move up with the d-pad until the selection disappears
         * - Type more text in the AutoCompleteTextView *using the soft keyboard*
         *   and get new results; you are now in touch mode
         * - The selection comes back on the first item in the list, even though
         *   the list is supposed to be in touch mode
         *
         * Using the soft keyboard triggers the touch mode change but that change
         * is propagated to our window only after the first list layout, therefore
         * after the list attempts to resurrect the selection.
         *
         * The trick to work around this issue is to pretend the list is in touch
         * mode when we know that the selection should not appear, that is when
         * we know the user moved the selection away from the list.
         *
         * This boolean is set to true whenever we explicitly hide the list's
         * selection and reset to false whenever we know the user moved the
         * selection back to the list.
         *
         * When this boolean is true, isInTouchMode() returns true, otherwise it
         * returns super.isInTouchMode().
         */
        private boolean mListSelectionHidden;
        
        /**
         * True if this wrapper should fake focus.
         */
        private boolean mHijackFocus;

        /** Whether to force drawing of the pressed state selector. */
        private boolean mDrawsInPressedState;

        /** Current drag-to-open click animation, if any. */
        private Animator mClickAnimation;

        /** Helper for drag-to-open auto scrolling. */
        private AbsListViewAutoScroller mScrollHelper;

        /**
         * <p>Creates a new list view wrapper.</p>
         *
         * @param context this view's context
         */
        public DropDownListView(Context context, boolean hijackFocus) {
            super(context, null, com.android.internal.R.attr.dropDownListViewStyle);
            mHijackFocus = hijackFocus;
            // TODO: Add an API to control this
            setCacheColorHint(0); // Transparent, since the background drawable could be anything.
        }

        /**
         * Handles forwarded events.
         *
         * @param activePointerId id of the pointer that activated forwarding
         * @return whether the event was handled
         */
        public boolean onForwardedEvent(MotionEvent event, int activePointerId) {
            boolean handledEvent = true;
            boolean clearPressedItem = false;

            final int actionMasked = event.getActionMasked();
            switch (actionMasked) {
                case MotionEvent.ACTION_CANCEL:
                    handledEvent = false;
                    break;
                case MotionEvent.ACTION_UP:
                    handledEvent = false;
                    // $FALL-THROUGH$
                case MotionEvent.ACTION_MOVE:
                    final int activeIndex = event.findPointerIndex(activePointerId);
                    if (activeIndex < 0) {
                        handledEvent = false;
                        break;
                    }

                    final int x = (int) event.getX(activeIndex);
                    final int y = (int) event.getY(activeIndex);
                    final int position = pointToPosition(x, y);
                    if (position == INVALID_POSITION) {
                        clearPressedItem = true;
                        break;
                    }

                    final View child = getChildAt(position - getFirstVisiblePosition());
                    setPressedItem(child, position);
                    handledEvent = true;

                    if (actionMasked == MotionEvent.ACTION_UP) {
                        clickPressedItem(child, position);
                    }
                    break;
            }

            // Failure to handle the event cancels forwarding.
            if (!handledEvent || clearPressedItem) {
                clearPressedItem();
            }

            // Manage automatic scrolling.
            if (handledEvent) {
                if (mScrollHelper == null) {
                    mScrollHelper = new AbsListViewAutoScroller(this);
                }
                mScrollHelper.setEnabled(true);
                mScrollHelper.onTouch(this, event);
            } else if (mScrollHelper != null) {
                mScrollHelper.setEnabled(false);
            }

            return handledEvent;
        }

        /**
         * Starts an alpha animation on the selector. When the animation ends,
         * the list performs a click on the item.
         */
        private void clickPressedItem(final View child, final int position) {
            final long id = getItemIdAtPosition(position);
            final Animator anim = ObjectAnimator.ofInt(
                    mSelector, DRAWABLE_ALPHA, 0xFF, CLICK_ANIM_ALPHA, 0xFF);
            anim.setDuration(CLICK_ANIM_DURATION);
            anim.setInterpolator(new AccelerateDecelerateInterpolator());
            anim.addListener(new AnimatorListenerAdapter() {
                    @Override
                public void onAnimationEnd(Animator animation) {
                    performItemClick(child, position, id);
                }
            });
            anim.start();

            if (mClickAnimation != null) {
                mClickAnimation.cancel();
            }
            mClickAnimation = anim;
        }

        private void clearPressedItem() {
            mDrawsInPressedState = false;
            setPressed(false);
            updateSelectorState();

            if (mClickAnimation != null) {
                mClickAnimation.cancel();
                mClickAnimation = null;
            }
        }

        private void setPressedItem(View child, int position) {
            mDrawsInPressedState = true;

            // Ordering is essential. First update the pressed state and layout
            // the children. This will ensure the selector actually gets drawn.
            setPressed(true);
            layoutChildren();

            // Ensure that keyboard focus starts from the last touched position.
            setSelectedPositionInt(position);
            positionSelector(position, child);

            // Refresh the drawable state to reflect the new pressed state,
            // which will also update the selector state.
            refreshDrawableState();

            if (mClickAnimation != null) {
                mClickAnimation.cancel();
                mClickAnimation = null;
            }
        }

        @Override
        boolean touchModeDrawsInPressedState() {
            return mDrawsInPressedState || super.touchModeDrawsInPressedState();
        }

        /**
         * <p>Avoids jarring scrolling effect by ensuring that list elements
         * made of a text view fit on a single line.</p>
         *
         * @param position the item index in the list to get a view for
         * @return the view for the specified item
         */
        @Override
        View obtainView(int position, boolean[] isScrap) {
            View view = super.obtainView(position, isScrap);

            if (view instanceof TextView) {
                ((TextView) view).setHorizontallyScrolling(true);
            }

            return view;
        }

        @Override
        public boolean isInTouchMode() {
            // WARNING: Please read the comment where mListSelectionHidden is declared
            return (mHijackFocus && mListSelectionHidden) || super.isInTouchMode();
        }

        /**
         * <p>Returns the focus state in the drop down.</p>
         *
         * @return true always if hijacking focus
         */
        @Override
        public boolean hasWindowFocus() {
            return mHijackFocus || super.hasWindowFocus();
        }

        /**
         * <p>Returns the focus state in the drop down.</p>
         *
         * @return true always if hijacking focus
         */
        @Override
        public boolean isFocused() {
            return mHijackFocus || super.isFocused();
        }

        /**
         * <p>Returns the focus state in the drop down.</p>
         *
         * @return true always if hijacking focus
         */
        @Override
        public boolean hasFocus() {
            return mHijackFocus || super.hasFocus();
        }
    }

    private class PopupDataSetObserver extends DataSetObserver {
        @Override
        public void onChanged() {
            if (isShowing()) {
                // Resize the popup to fit new content
                show();
            }
        }
        
        @Override
        public void onInvalidated() {
            dismiss();
        }
    }

    private class ListSelectorHider implements Runnable {
        public void run() {
            clearListSelection();
        }
    }

    private class ResizePopupRunnable implements Runnable {
        public void run() {
            if (mDropDownList != null && mDropDownList.getCount() > mDropDownList.getChildCount() &&
                    mDropDownList.getChildCount() <= mListItemExpandMaximum) {
                mPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
                show();
            }
        }
    }

    private class PopupTouchInterceptor implements OnTouchListener {
        public boolean onTouch(View v, MotionEvent event) {
            final int action = event.getAction();
            final int x = (int) event.getX();
            final int y = (int) event.getY();
            
            if (action == MotionEvent.ACTION_DOWN &&
                    mPopup != null && mPopup.isShowing() &&
                    (x >= 0 && x < mPopup.getWidth() && y >= 0 && y < mPopup.getHeight())) {
                mHandler.postDelayed(mResizePopupRunnable, EXPAND_LIST_TIMEOUT);
            } else if (action == MotionEvent.ACTION_UP) {
                mHandler.removeCallbacks(mResizePopupRunnable);
            }
            return false;
        }
    }

    private class PopupScrollListener implements ListView.OnScrollListener {
        public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                int totalItemCount) {

        }

        public void onScrollStateChanged(AbsListView view, int scrollState) {
            if (scrollState == SCROLL_STATE_TOUCH_SCROLL &&
                    !isInputMethodNotNeeded() && mPopup.getContentView() != null) {
                mHandler.removeCallbacks(mResizePopupRunnable);
                mResizePopupRunnable.run();
            }
        }
    }
}
