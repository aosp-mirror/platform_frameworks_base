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

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.view.Gravity;
import android.view.ViewGroup;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.IBinder;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;

/**
 * <p>A popup window that can be used to display an arbitrary view. The popup
 * windows is a floating container that appears on top of the current
 * activity.</p>
 * 
 * @see android.widget.AutoCompleteTextView
 * @see android.widget.Spinner
 */
public class PopupWindow {
    /**
     * The height of the status bar so we know how much of the screen we can
     * actually be displayed in.
     * <p>
     * TODO: This IS NOT the right way to do this.
     * Instead of knowing how much of the screen is available, a popup that
     * wants anchor and maximize space shouldn't be setting a height, instead
     * the PopupViewContainer should have its layout height as fill_parent and
     * properly position the popup.
     */
    private static final int STATUS_BAR_HEIGHT = 30;
    
    private boolean mIsShowing;

    private View mContentView;
    private View mPopupView;
    private boolean mFocusable;

    private int mWidth;
    private int mHeight;

    private int[] mDrawingLocation = new int[2];
    private int[] mRootLocation = new int[2];
    private Rect mTempRect = new Rect();
    
    private Context mContext;
    private Drawable mBackground;

    private boolean mAboveAnchor;
    
    private OnDismissListener mOnDismissListener;
    private boolean mIgnoreCheekPress = false;

    private int mAnimationStyle = -1;
    
    private static final int[] ABOVE_ANCHOR_STATE_SET = new int[] {
        com.android.internal.R.attr.state_above_anchor
    };
    
    /**
     * <p>Create a new empty, non focusable popup window of dimension (0,0).</p>
     *
     * <p>The popup does provide a background.</p>
     */
    public PopupWindow(Context context) {
        this(context, null);
    }

    /**
     * <p>Create a new empty, non focusable popup window of dimension (0,0).</p>
     *
     * <p>The popup does provide a background.</p>
     */
    public PopupWindow(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.popupWindowStyle);
    }

    /**
     * <p>Create a new empty, non focusable popup window of dimension (0,0).</p>
     *
     * <p>The popup does provide a background.</p>
     */
    public PopupWindow(Context context, AttributeSet attrs, int defStyle) {
        mContext = context;

        TypedArray a =
            context.obtainStyledAttributes(
                attrs, com.android.internal.R.styleable.PopupWindow, defStyle, 0);

        mBackground = a.getDrawable(R.styleable.PopupWindow_popupBackground);

        a.recycle();
    }

    /**
     * <p>Create a new empty, non focusable popup window of dimension (0,0).</p>
     *
     * <p>The popup does not provide any background. This should be handled
     * by the content view.</p>
     */
    public PopupWindow() {
        this(null, 0, 0);
    }

    /**
     * <p>Create a new non focusable popup window which can display the
     * <tt>contentView</tt>. The dimension of the window are (0,0).</p>
     *
     * <p>The popup does not provide any background. This should be handled
     * by the content view.</p>
     *
     * @param contentView the popup's content
     */
    public PopupWindow(View contentView) {
        this(contentView, 0, 0);
    }

    /**
     * <p>Create a new empty, non focusable popup window. The dimension of the
     * window must be passed to this constructor.</p>
     *
     * <p>The popup does not provide any background. This should be handled
     * by the content view.</p>
     *
     * @param width the popup's width
     * @param height the popup's height
     */
    public PopupWindow(int width, int height) {
        this(null, width, height);
    }

    /**
     * <p>Create a new non focusable popup window which can display the
     * <tt>contentView</tt>. The dimension of the window must be passed to
     * this constructor.</p>
     *
     * <p>The popup does not provide any background. This should be handled
     * by the content view.</p>
     *
     * @param contentView the popup's content
     * @param width the popup's width
     * @param height the popup's height
     */
    public PopupWindow(View contentView, int width, int height) {
        this(contentView, width, height, false);
    }

    /**
     * <p>Create a new popup window which can display the <tt>contentView</tt>.
     * The dimension of the window must be passed to this constructor.</p>
     *
     * <p>The popup does not provide any background. This should be handled
     * by the content view.</p>
     *
     * @param contentView the popup's content
     * @param width the popup's width
     * @param height the popup's height
     * @param focusable true if the popup can be focused, false otherwise
     */
    public PopupWindow(View contentView, int width, int height,
            boolean focusable) {
        setContentView(contentView);
        setWidth(width);
        setHeight(height);
        setFocusable(focusable);
    }

    /**
     * <p>Return the drawable used as the popup window's background.</p>
     *
     * @return the background drawable or null
     */
    public Drawable getBackground() {
        return mBackground;
    }

    /**
     * <p>Change the background drawable for this popup window. The background
     * can be set to null.</p>
     *
     * @param background the popup's background
     */
    public void setBackgroundDrawable(Drawable background) {
        mBackground = background;
    }

    /**
     * <p>Return the animation style to use the popup appears and disappears</p>
     *
     * @return the animation style to use the popup appears and disappears
     */
    public int getAnimationStyle() {
        return mAnimationStyle;
    }
    
    /**
     * set the flag on popup to ignore cheek press events
     * This method has to be invoked before displaying the content view
     * of the popup for the window flags to take effect and will be ignored
     * if the pop up is already displayed. By default this flag is set to false
     * which means the pop wont ignore cheek press dispatch events.
     */
    public void setIgnoreCheekPress() {
        mIgnoreCheekPress = true;
    }
    

    /**
     * <p>Change the animation style for this popup.</p>
     *
     * @param animationStyle animation style to use when the popup appears and disappears
     */
    public void setAnimationStyle(int animationStyle) {
        mAnimationStyle = animationStyle;
    }
    
    /**
     * <p>Return the view used as the content of the popup window.</p>
     *
     * @return a {@link android.view.View} representing the popup's content
     *
     * @see #setContentView(android.view.View)
     */
    public View getContentView() {
        return mContentView;
    }

    /**
     * <p>Change the popup's content. The content is represented by an instance
     * of {@link android.view.View}.</p>
     *
     * <p>This method has no effect if called when the popup is showing.</p>
     *
     * @param contentView the new content for the popup
     *
     * @see #getContentView()
     * @see #isShowing()
     */
    public void setContentView(View contentView) {
        if (isShowing()) {
            return;
        }

        mContentView = contentView;
    }

    /**
     * <p>Indicate whether the popup window can grab the focus.</p>
     *
     * @return true if the popup is focusable, false otherwise
     *
     * @see #setFocusable(boolean)
     */
    public boolean isFocusable() {
        return mFocusable;
    }

    /**
     * <p>Changes the focusability of the popup window. When focusable, the
     * window will grab the focus from the current focused widget if the popup
     * contains a focusable {@link android.view.View}.</p>
     *
     * <p>If the popup is showing, calling this method will take effect only
     * the next time the popup is shown.</p>
     *
     * @param focusable true if the popup should grab focus, false otherwise
     *
     * @see #isFocusable()
     * @see #isShowing() 
     */
    public void setFocusable(boolean focusable) {
        mFocusable = focusable;
    }

    /**
     * <p>Return this popup's height MeasureSpec</p>
     *
     * @return the height MeasureSpec of the popup
     *
     * @see #setHeight(int)
     */
    public int getHeight() {
        return mHeight;
    }

    /**
     * <p>Change the popup's height MeasureSpec</p>
     *
     * <p>If the popup is showing, calling this method will take effect only
     * the next time the popup is shown.</p>
     *
     * @param height the height MeasureSpec of the popup
     *
     * @see #getHeight()
     * @see #isShowing() 
     */
    public void setHeight(int height) {
        mHeight = height;
    }

    /**
     * <p>Return this popup's width MeasureSpec</p>
     *
     * @return the width MeasureSpec of the popup
     *
     * @see #setWidth(int) 
     */
    public int getWidth() {
        return mWidth;
    }

    /**
     * <p>Change the popup's width MeasureSpec</p>
     *
     * <p>If the popup is showing, calling this method will take effect only
     * the next time the popup is shown.</p>
     *
     * @param width the width MeasureSpec of the popup
     *
     * @see #getWidth()
     * @see #isShowing()
     */
    public void setWidth(int width) {
        mWidth = width;
    }

    /**
     * <p>Indicate whether this popup window is showing on screen.</p>
     *
     * @return true if the popup is showing, false otherwise
     */
    public boolean isShowing() {
        return mIsShowing;
    }

    /**
     * <p>
     * Display the content view in a popup window at the specified location. If the popup window
     * cannot fit on screen, it will be clipped. See {@link android.view.WindowManager.LayoutParams}
     * for more information on how gravity and the x and y parameters are related. Specifying
     * a gravity of {@link android.view.Gravity#NO_GRAVITY} is similar to specifying
     * <code>Gravity.LEFT | Gravity.TOP</code>.
     * </p>
     * 
     * @param parent a parent view to get the {@link android.view.View#getWindowToken()} token from
     * @param gravity the gravity which controls the placement of the popup window
     * @param x the popup's x location offset
     * @param y the popup's y location offset
     */
    public void showAtLocation(View parent, int gravity, int x, int y) {
        if (isShowing() || mContentView == null) {
            return;
        }

        mIsShowing = true;

        WindowManager.LayoutParams p = createPopupLayout(parent.getWindowToken());
        if (mAnimationStyle != -1) {
            p.windowAnimations = mAnimationStyle;
        }
       
        preparePopup(p);
        if (gravity == Gravity.NO_GRAVITY) {
            gravity = Gravity.TOP | Gravity.LEFT;
        }
        p.gravity = gravity;
        p.x = x;
        p.y = y;
        invokePopup(p);
    }

    /**
     * <p>Display the content view in a popup window anchored to the bottom-left
     * corner of the anchor view. If there is not enough room on screen to show
     * the popup in its entirety, this method tries to find a parent scroll
     * view to scroll. If no parent scroll view can be scrolled, the bottom-left
     * corner of the popup is pinned at the top left corner of the anchor view.</p>
     *
     * @param anchor the view on which to pin the popup window
     *
     * @see #dismiss()
     */
    public void showAsDropDown(View anchor) {
        showAsDropDown(anchor, 0, 0);
    }

    /**
     * <p>Display the content view in a popup window anchored to the bottom-left
     * corner of the anchor view offset by the specified x and y coordinates.
     * If there is not enough room on screen to show
     * the popup in its entirety, this method tries to find a parent scroll
     * view to scroll. If no parent scroll view can be scrolled, the bottom-left
     * corner of the popup is pinned at the top left corner of the anchor view.</p>
     *
     * @param anchor the view on which to pin the popup window
     *
     * @see #dismiss()
     */
    public void showAsDropDown(View anchor, int xoff, int yoff) {
        if (isShowing() || mContentView == null) {
            return;
        }

        mIsShowing = true;

        WindowManager.LayoutParams p = createPopupLayout(anchor.getWindowToken());
        preparePopup(p);
        if (mBackground != null) {
            mPopupView.refreshDrawableState();
        }
        mAboveAnchor = findDropDownPosition(anchor, p, xoff, yoff);
        if (mAnimationStyle == -1) {
            p.windowAnimations = mAboveAnchor
                    ? com.android.internal.R.style.Animation_DropDownUp
                    : com.android.internal.R.style.Animation_DropDownDown;
        } else {
            p.windowAnimations = mAnimationStyle;
        }
        invokePopup(p);
    }

    /**
     * <p>Prepare the popup by embedding in into a new ViewGroup if the
     * background drawable is not null. If embedding is required, the layout
     * parameters' height is mnodified to take into account the background's
     * padding.</p>
     *
     * @param p the layout parameters of the popup's content view
     */
    private void preparePopup(WindowManager.LayoutParams p) {
        if (mBackground != null) {
            // when a background is available, we embed the content view
            // within another view that owns the background drawable
            PopupViewContainer popupViewContainer = new PopupViewContainer(mContext);
            PopupViewContainer.LayoutParams listParams = new PopupViewContainer.LayoutParams(
                    ViewGroup.LayoutParams.FILL_PARENT,
                    ViewGroup.LayoutParams.FILL_PARENT
            );
            popupViewContainer.setBackgroundDrawable(mBackground);
            popupViewContainer.addView(mContentView, listParams);

            if (p.height >= 0) {
                // accomodate the popup's height to take into account the
                // background's padding
                p.height += popupViewContainer.getPaddingTop() +
                        popupViewContainer.getPaddingBottom();
            }
            if (p.width >= 0) {
                // accomodate the popup's width to take into account the
                // background's padding
                p.width += popupViewContainer.getPaddingLeft() +
                        popupViewContainer.getPaddingRight();
            }
            mPopupView = popupViewContainer;
        } else {
            mPopupView = mContentView;
        }
        
    }

    /**
     * <p>Invoke the popup window by adding the content view to the window
     * manager.</p>
     *
     * <p>The content view must be non-null when this method is invoked.</p>
     *
     * @param p the layout parameters of the popup's content view
     */
    private void invokePopup(WindowManager.LayoutParams p) {
        WindowManagerImpl wm = WindowManagerImpl.getDefault();
        wm.addView(mPopupView, p);
    }

    /**
     * <p>Generate the layout parameters for the popup window.</p>
     *
     * @param token the window token used to bind the popup's window
     *
     * @return the layout parameters to pass to the window manager
     */
    private WindowManager.LayoutParams createPopupLayout(IBinder token) {
        // generates the layout parameters for the drop down
        // we want a fixed size view located at the bottom left of the anchor
        WindowManager.LayoutParams p = new WindowManager.LayoutParams();
        // these gravity settings put the view at the top left corner of the
        // screen. The view is then positioned to the appropriate location
        // by setting the x and y offsets to match the anchor's bottom
        // left corner
        p.gravity = Gravity.LEFT | Gravity.TOP;
        p.width = mWidth;
        p.height = mHeight;
        if (mBackground != null) {
            p.format = mBackground.getOpacity();
        } else {
            p.format = PixelFormat.TRANSLUCENT;
        }
        if(mIgnoreCheekPress) {
            p.flags |= WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES;
        }
        if (!mFocusable) {
            p.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        p.type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
        p.token = token;
        
        return p;
    }

    /**
     * <p>Positions the popup window on screen. When the popup window is too
     * tall to fit under the anchor, a parent scroll view is seeked and scrolled
     * up to reclaim space. If scrolling is not possible or not enough, the
     * popup window gets moved on top of the anchor.</p>
     *
     * <p>The height must have been set on the layout parameters prior to
     * calling this method.</p>
     *
     * @param anchor the view on which the popup window must be anchored
     * @param p the layout parameters used to display the drop down
     *
     * @return true if the popup is translated upwards to fit on screen
     */
    private boolean findDropDownPosition(View anchor, WindowManager.LayoutParams p, int xoff, int yoff) {
        anchor.getLocationInWindow(mDrawingLocation);
        p.x = mDrawingLocation[0] + xoff;
        p.y = mDrawingLocation[1] + anchor.getMeasuredHeight() + yoff;

        boolean onTop = false;

        if (p.y + p.height > WindowManagerImpl.getDefault().getDefaultDisplay().getHeight()) {
            // if the drop down disappears at the bottom of the screen. we try to
            // scroll a parent scrollview or move the drop down back up on top of
            // the edit box
            View root = anchor.getRootView();
            root.getLocationInWindow(mRootLocation);
            int delta = p.y + p.height - mRootLocation[1] - root.getHeight();

            if (delta > 0 || p.x + p.width - mRootLocation[0] - root.getWidth() > 0) {
                Rect r = new Rect(anchor.getScrollX(), anchor.getScrollY(),
                        p.width, p.height + anchor.getMeasuredHeight());

                onTop = !anchor.requestRectangleOnScreen(r, true);

                if (onTop) {
                    p.y -= anchor.getMeasuredHeight() + p.height;
                } else {
                    anchor.getLocationOnScreen(mDrawingLocation);
                    p.x = mDrawingLocation[0] + xoff;
                    p.y = mDrawingLocation[1] + anchor.getMeasuredHeight() + yoff;
                }
            }
        }

        return onTop;
    }
    
    /**
     * Returns the maximum height that is available for the popup to be
     * completely shown. It is recommended that this height be the maximum for
     * the popup's height, otherwise it is possible that the popup will be
     * clipped.
     * 
     * @param anchor The view on which the popup window must be anchored.
     * @return The maximum available height for the popup to be completely
     *         shown.
     */
    public int getMaxAvailableHeight(View anchor) {
        // TODO: read comment on STATUS_BAR_HEIGHT
        final int screenHeight = WindowManagerImpl.getDefault().getDefaultDisplay().getHeight()
                - STATUS_BAR_HEIGHT;

        final int[] anchorPos = mDrawingLocation;
        anchor.getLocationOnScreen(anchorPos);
        anchorPos[1] -= STATUS_BAR_HEIGHT;

        final int distanceFromAnchorToBottom = screenHeight - (anchorPos[1] + anchor.getHeight());
        
        // anchorPos[1] is distance from anchor to top of screen
        int returnedHeight = Math.max(anchorPos[1], distanceFromAnchorToBottom);
        if (mBackground != null) {
            mBackground.getPadding(mTempRect);
            returnedHeight -= mTempRect.top + mTempRect.bottom; 
        }
        
        return returnedHeight;
    }
    
    /**
     * <p>Dispose of the popup window. This method can be invoked only after
     * {@link #showAsDropDown(android.view.View)} has been executed. Failing that, calling
     * this method will have no effect.</p>
     *
     * @see #showAsDropDown(android.view.View) 
     */
    public void dismiss() {
        if (isShowing() && mPopupView != null) {
            WindowManagerImpl wm = WindowManagerImpl.getDefault();
            wm.removeView(mPopupView);
            if (mPopupView != mContentView && mPopupView instanceof ViewGroup) {
                ((ViewGroup) mPopupView).removeView(mContentView);
            }
            mIsShowing = false;

            if (mOnDismissListener != null) {
                mOnDismissListener.onDismiss();
            }
        }
    }

    /**
     * Sets the listener to be called when the window is dismissed.
     * 
     * @param onDismissListener The listener.
     */
    public void setOnDismissListener(OnDismissListener onDismissListener) {
        mOnDismissListener = onDismissListener;
    }
    
    /**
     * <p>Updates the position and the dimension of the popup window. Width and
     * height can be set to -1 to update location only.</p>
     *
     * @param x the new x location
     * @param y the new y location
     * @param width the new width, can be -1 to ignore
     * @param height the new height, can be -1 to ignore
     */
    public void update(int x, int y, int width, int height) {
        if (width != -1) {
            setWidth(width);
        }

        if (height != -1) {
            setHeight(height);
        }

        if (!isShowing() || mContentView == null) {
            return;
        }

        WindowManager.LayoutParams p = (WindowManager.LayoutParams)
                mPopupView.getLayoutParams();

        boolean update = false;

        if (width != -1 && p.width != width) {
            p.width = width;
            update = true;
        }

        if (height != -1 && p.height != height) {
            p.height = height;
            update = true;
        }

        if (p.x != x) {
            p.x = x;
            update = true;
        }

        if (p.y != y) {
            p.y = y;
            update = true;
        }

        if (update) {
            if (mPopupView != mContentView) {
                final View popupViewContainer = mPopupView;
                if (p.height >= 0) {
                    // accomodate the popup's height to take into account the
                    // background's padding
                    p.height += popupViewContainer.getPaddingTop() +
                            popupViewContainer.getPaddingBottom();
                }
                if (p.width >= 0) {
                    // accomodate the popup's width to take into account the
                    // background's padding
                    p.width += popupViewContainer.getPaddingLeft() +
                            popupViewContainer.getPaddingRight();
                }
            }

            WindowManagerImpl wm = WindowManagerImpl.getDefault();
            wm.updateViewLayout(mPopupView, p);
        }
    }

    /**
     * <p>Updates the position and the dimension of the popup window. Width and
     * height can be set to -1 to update location only.</p>
     *
     * @param anchor the popup's anchor view
     * @param width the new width, can be -1 to ignore
     * @param height the new height, can be -1 to ignore
     */
    public void update(View anchor, int width, int height) {
        update(anchor, 0, 0, width, height);
    }

    /**
     * <p>Updates the position and the dimension of the popup window. Width and
     * height can be set to -1 to update location only.</p>
     *
     * @param anchor the popup's anchor view
     * @param xoff x offset from the view's left edge
     * @param yoff y offset from the view's bottom edge
     * @param width the new width, can be -1 to ignore
     * @param height the new height, can be -1 to ignore
     */
    public void update(View anchor, int xoff, int yoff, int width, int height) {
        if (!isShowing() || mContentView == null) {
            return;
        }

        WindowManager.LayoutParams p = (WindowManager.LayoutParams)
                mPopupView.getLayoutParams();

        int x = p.x;
        int y = p.y;
        findDropDownPosition(anchor, p, xoff, yoff);

        update(x, y, width, height);
    }
    
    /**
     * Listener that is called when this popup window is dismissed.
     */
    interface OnDismissListener {
        /**
         * Called when this popup window is dismissed.
         */
        public void onDismiss();
    }
    
    private class PopupViewContainer extends FrameLayout {

        public PopupViewContainer(Context context) {
            super(context);
        }

        @Override
        protected int[] onCreateDrawableState(int extraSpace) {
            if (mAboveAnchor) {
                // 1 more needed for the above anchor state
                final int[] drawableState = super.onCreateDrawableState(extraSpace + 1);
                View.mergeDrawableStates(drawableState, ABOVE_ANCHOR_STATE_SET);
                return drawableState;
            } else {
                return super.onCreateDrawableState(extraSpace);
            }
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                dismiss();
                return true;
            } else {
                return super.dispatchKeyEvent(event);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            final int x = (int) event.getX();
            final int y = (int) event.getY();
            
            if ((event.getAction() == MotionEvent.ACTION_DOWN)
                    && ((x < 0) || (x >= getWidth()) || (y < 0) || (y >= getHeight()))) {
                dismiss();
                return true;
            } else {
                return super.onTouchEvent(event);
            }
        }
        
    }
    
}
