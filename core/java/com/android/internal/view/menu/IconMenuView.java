/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.view.menu;

import com.android.internal.view.menu.MenuBuilder.ItemInvoker;

import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.LayoutInflater;

import java.util.ArrayList;

/**
 * The icon menu view is an icon-based menu usually with a subset of all the menu items.
 * It is opened as the default menu, and shows either the first five or all six of the menu items
 * with text and icon.  In the situation of there being more than six items, the first five items
 * will be accompanied with a 'More' button that opens an {@link ExpandedMenuView} which lists
 * all the menu items. 
 * 
 * @attr ref android.R.styleable#IconMenuView_rowHeight
 * @attr ref android.R.styleable#IconMenuView_maxRows
 * @attr ref android.R.styleable#IconMenuView_maxItemsPerRow
 * 
 * @hide
 */
public final class IconMenuView extends ViewGroup implements ItemInvoker, MenuView, Runnable {
    private static final int ITEM_CAPTION_CYCLE_DELAY = 1000;

    @UnsupportedAppUsage
    private MenuBuilder mMenu;
    
    /** Height of each row */
    private int mRowHeight;
    /** Maximum number of rows to be shown */ 
    private int mMaxRows;
    /** Maximum number of items to show in the icon menu. */
    @UnsupportedAppUsage
    private int mMaxItems;
    /** Maximum number of items per row */
    private int mMaxItemsPerRow;
    /** Actual number of items (the 'More' view does not count as an item) shown */
    private int mNumActualItemsShown;
    
    /** Divider that is drawn between all rows */
    private Drawable mHorizontalDivider;
    /** Height of the horizontal divider */
    private int mHorizontalDividerHeight;
    /** Set of horizontal divider positions where the horizontal divider will be drawn */
    private ArrayList<Rect> mHorizontalDividerRects;

    /** Divider that is drawn between all columns */
    private Drawable mVerticalDivider;
    /** Width of the vertical divider */
    private int mVerticalDividerWidth;
    /** Set of vertical divider positions where the vertical divider will be drawn */
    private ArrayList<Rect> mVerticalDividerRects;
    
    /** Icon for the 'More' button */
    private Drawable mMoreIcon;

    /** Background of each item (should contain the selected and focused states) */
    @UnsupportedAppUsage
    private Drawable mItemBackground;

    /** Default animations for this menu */
    private int mAnimations;
    
    /**
     * Whether this IconMenuView has stale children and needs to update them.
     * Set true by {@link #markStaleChildren()} and reset to false by
     * {@link #onMeasure(int, int)}
     */
    private boolean mHasStaleChildren;

    /**
     * Longpress on MENU (while this is shown) switches to shortcut caption
     * mode. When the user releases the longpress, we do not want to pass the
     * key-up event up since that will dismiss the menu.
     */
    private boolean mMenuBeingLongpressed = false;

    /**
     * While {@link #mMenuBeingLongpressed}, we toggle the children's caption
     * mode between each's title and its shortcut. This is the last caption mode
     * we broadcasted to children.
     */
    private boolean mLastChildrenCaptionMode;

    /**
     * The layout to use for menu items. Each index is the row number (0 is the
     * top-most). Each value contains the number of items in that row.
     * <p>
     * The length of this array should not be used to get the number of rows in
     * the current layout, instead use {@link #mLayoutNumRows}.
     */
    private int[] mLayout;

    /**
     * The number of rows in the current layout. 
     */
    private int mLayoutNumRows;
    
    /**
     * Instantiates the IconMenuView that is linked with the provided MenuBuilder.
     */
    public IconMenuView(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = 
            context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.IconMenuView, 0, 0);
        mRowHeight = a.getDimensionPixelSize(com.android.internal.R.styleable.IconMenuView_rowHeight, 64);
        mMaxRows = a.getInt(com.android.internal.R.styleable.IconMenuView_maxRows, 2);
        mMaxItems = a.getInt(com.android.internal.R.styleable.IconMenuView_maxItems, 6);
        mMaxItemsPerRow = a.getInt(com.android.internal.R.styleable.IconMenuView_maxItemsPerRow, 3);
        mMoreIcon = a.getDrawable(com.android.internal.R.styleable.IconMenuView_moreIcon);
        a.recycle();
        
        a = context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.MenuView, 0, 0);
        mItemBackground = a.getDrawable(com.android.internal.R.styleable.MenuView_itemBackground);
        mHorizontalDivider = a.getDrawable(com.android.internal.R.styleable.MenuView_horizontalDivider);
        mHorizontalDividerRects = new ArrayList<Rect>();
        mVerticalDivider =  a.getDrawable(com.android.internal.R.styleable.MenuView_verticalDivider);
        mVerticalDividerRects = new ArrayList<Rect>();
        mAnimations = a.getResourceId(com.android.internal.R.styleable.MenuView_windowAnimationStyle, 0);
        a.recycle();
        
        if (mHorizontalDivider != null) {
            mHorizontalDividerHeight = mHorizontalDivider.getIntrinsicHeight();
            // Make sure to have some height for the divider
            if (mHorizontalDividerHeight == -1) mHorizontalDividerHeight = 1;
        }
        
        if (mVerticalDivider != null) {
            mVerticalDividerWidth = mVerticalDivider.getIntrinsicWidth();
            // Make sure to have some width for the divider
            if (mVerticalDividerWidth == -1) mVerticalDividerWidth = 1;
        }
        
        mLayout = new int[mMaxRows];
        
        // This view will be drawing the dividers        
        setWillNotDraw(false);
        
        // This is so we'll receive the MENU key in touch mode
        setFocusableInTouchMode(true);
        // This is so our children can still be arrow-key focused
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
    }

    int getMaxItems() {
        return mMaxItems;
    }

    /**
     * Figures out the layout for the menu items.
     * 
     * @param width The available width for the icon menu.
     */
    private void layoutItems(int width) {
        int numItems = getChildCount();
        if (numItems == 0) {
            mLayoutNumRows = 0;
            return;
        }
        
        // Start with the least possible number of rows
        int curNumRows =
                Math.min((int) Math.ceil(numItems / (float) mMaxItemsPerRow), mMaxRows);
        
        /*
         * Increase the number of rows until we find a configuration that fits
         * all of the items' titles. Worst case, we use mMaxRows.
         */
        for (; curNumRows <= mMaxRows; curNumRows++) {
            layoutItemsUsingGravity(curNumRows, numItems);
            
            if (curNumRows >= numItems) {
                // Can't have more rows than items
                break;
            }
            
            if (doItemsFit()) {
                // All the items fit, so this is a good configuration
                break;
            }
        }
    }

    /**
     * Figures out the layout for the menu items by equally distributing, and
     * adding any excess items equally to lower rows.
     * 
     * @param numRows The total number of rows for the menu view
     * @param numItems The total number of items (across all rows) contained in
     *            the menu view
     * @return int[] Where the value of index i contains the number of items for row i
     */
    private void layoutItemsUsingGravity(int numRows, int numItems) {
        int numBaseItemsPerRow = numItems / numRows;
        int numLeftoverItems = numItems % numRows;
        /**
         * The bottom rows will each get a leftover item. Rows (indexed at 0)
         * that are >= this get a leftover item. Note: if there are 0 leftover
         * items, no rows will get them since this value will be greater than
         * the last row.
         */
        int rowsThatGetALeftoverItem = numRows - numLeftoverItems;
        
        int[] layout = mLayout;
        for (int i = 0; i < numRows; i++) {
            layout[i] = numBaseItemsPerRow;

            // Fill the bottom rows with a leftover item each
            if (i >= rowsThatGetALeftoverItem) {
                layout[i]++;
            }
        }
        
        mLayoutNumRows = numRows;
    }

    /**
     * Checks whether each item's title is fully visible using the current
     * layout.
     * 
     * @return True if the items fit (each item's text is fully visible), false
     *         otherwise.
     */
    private boolean doItemsFit() {
        int itemPos = 0;
        
        int[] layout = mLayout;
        int numRows = mLayoutNumRows;
        for (int row = 0; row < numRows; row++) {
            int numItemsOnRow = layout[row];

            /*
             * If there is only one item on this row, increasing the
             * number of rows won't help.
             */ 
            if (numItemsOnRow == 1) {
                itemPos++;
                continue;
            }
            
            for (int itemsOnRowCounter = numItemsOnRow; itemsOnRowCounter > 0;
                    itemsOnRowCounter--) {
                View child = getChildAt(itemPos++);
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (lp.maxNumItemsOnRow < numItemsOnRow) {
                    return false;
                }
            }
        }
        
        return true;
    }

    Drawable getItemBackgroundDrawable() {
        return mItemBackground.getConstantState().newDrawable(getContext().getResources());
    }

    /**
     * Creates the item view for the 'More' button which is used to switch to
     * the expanded menu view. This button is a special case since it does not
     * have a MenuItemData backing it.
     * @return The IconMenuItemView for the 'More' button
     */
    @UnsupportedAppUsage
    IconMenuItemView createMoreItemView() {
        Context context = getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        
        final IconMenuItemView itemView = (IconMenuItemView) inflater.inflate(
                com.android.internal.R.layout.icon_menu_item_layout, null);
        
        Resources r = context.getResources();
        itemView.initialize(r.getText(com.android.internal.R.string.more_item_label), mMoreIcon);
        
        // Set up a click listener on the view since there will be no invocation sequence
        // due to the lack of a MenuItemData this view
        itemView.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                // Switches the menu to expanded mode. Requires support from
                // the menu's active callback.
                mMenu.changeMenuMode();
            }
        });
        
        return itemView;
    }
    
    
    public void initialize(MenuBuilder menu) {
        mMenu = menu;
    }

    /**
     * The positioning algorithm that gets called from onMeasure.  It
     * just computes positions for each child, and then stores them in the child's layout params.
     * @param menuWidth The width of this menu to assume for positioning
     * @param menuHeight The height of this menu to assume for positioning
     */
    private void positionChildren(int menuWidth, int menuHeight) {
        // Clear the containers for the positions where the dividers should be drawn
        if (mHorizontalDivider != null) mHorizontalDividerRects.clear();
        if (mVerticalDivider != null) mVerticalDividerRects.clear();

        // Get the minimum number of rows needed
        final int numRows = mLayoutNumRows;
        final int numRowsMinus1 = numRows - 1;
        final int numItemsForRow[] = mLayout;
        
        // The item position across all rows
        int itemPos = 0;
        View child;
        IconMenuView.LayoutParams childLayoutParams = null; 

        // Use float for this to get precise positions (uniform item widths
        // instead of last one taking any slack), and then convert to ints at last opportunity
        float itemLeft;
        float itemTop = 0;
        // Since each row can have a different number of items, this will be computed per row
        float itemWidth;
        // Subtract the space needed for the horizontal dividers
        final float itemHeight = (menuHeight - mHorizontalDividerHeight * (numRows - 1))
                / (float)numRows;
        
        for (int row = 0; row < numRows; row++) {
            // Start at the left
            itemLeft = 0;
            
            // Subtract the space needed for the vertical dividers, and divide by the number of items
            itemWidth = (menuWidth - mVerticalDividerWidth * (numItemsForRow[row] - 1))
                    / (float)numItemsForRow[row];
            
            for (int itemPosOnRow = 0; itemPosOnRow < numItemsForRow[row]; itemPosOnRow++) {
                // Tell the child to be exactly this size
                child = getChildAt(itemPos);
                child.measure(MeasureSpec.makeMeasureSpec((int) itemWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec((int) itemHeight, MeasureSpec.EXACTLY));
                
                // Remember the child's position for layout
                childLayoutParams = (IconMenuView.LayoutParams) child.getLayoutParams();
                childLayoutParams.left = (int) itemLeft;
                childLayoutParams.right = (int) (itemLeft + itemWidth);
                childLayoutParams.top = (int) itemTop;
                childLayoutParams.bottom = (int) (itemTop + itemHeight); 
                
                // Increment by item width
                itemLeft += itemWidth;
                itemPos++;

                // Add a vertical divider to draw
                if (mVerticalDivider != null) {
                    mVerticalDividerRects.add(new Rect((int) itemLeft,
                            (int) itemTop, (int) (itemLeft + mVerticalDividerWidth),
                            (int) (itemTop + itemHeight)));
                }

                // Increment by divider width (even if we're not computing
                // dividers, since we need to leave room for them when
                // calculating item positions)
                itemLeft += mVerticalDividerWidth;
            }
            
            // Last child on each row should extend to very right edge
            if (childLayoutParams != null) {
                childLayoutParams.right = menuWidth;
            }
            
            itemTop += itemHeight;

            // Add a horizontal divider to draw
            if ((mHorizontalDivider != null) && (row < numRowsMinus1)) {
                mHorizontalDividerRects.add(new Rect(0, (int) itemTop, menuWidth,
                        (int) (itemTop + mHorizontalDividerHeight)));

                itemTop += mHorizontalDividerHeight;
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int measuredWidth = resolveSize(Integer.MAX_VALUE, widthMeasureSpec);
        calculateItemFittingMetadata(measuredWidth);
        layoutItems(measuredWidth);
        
        // Get the desired height of the icon menu view (last row of items does
        // not have a divider below)
        final int layoutNumRows = mLayoutNumRows;
        final int desiredHeight = (mRowHeight + mHorizontalDividerHeight) *
                layoutNumRows - mHorizontalDividerHeight;
        
        // Maximum possible width and desired height
        setMeasuredDimension(measuredWidth,
                resolveSize(desiredHeight, heightMeasureSpec));

        // Position the children
        if (layoutNumRows > 0) {
            positionChildren(getMeasuredWidth(), getMeasuredHeight());
        }
    }


    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        View child;
        IconMenuView.LayoutParams childLayoutParams;
        
        for (int i = getChildCount() - 1; i >= 0; i--) {
            child = getChildAt(i);
            childLayoutParams = (IconMenuView.LayoutParams)child
                    .getLayoutParams();

            // Layout children according to positions set during the measure
            child.layout(childLayoutParams.left, childLayoutParams.top, childLayoutParams.right,
                    childLayoutParams.bottom);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable drawable = mHorizontalDivider;
        if (drawable != null) {
            // If we have a horizontal divider to draw, draw it at the remembered positions
            final ArrayList<Rect> rects = mHorizontalDividerRects;
            for (int i = rects.size() - 1; i >= 0; i--) {
                drawable.setBounds(rects.get(i));
                drawable.draw(canvas);
            }
        }

        drawable = mVerticalDivider;
        if (drawable != null) {
            // If we have a vertical divider to draw, draw it at the remembered positions
            final ArrayList<Rect> rects = mVerticalDividerRects;
            for (int i = rects.size() - 1; i >= 0; i--) {
                drawable.setBounds(rects.get(i));
                drawable.draw(canvas);
            }
        }
    }

    public boolean invokeItem(MenuItemImpl item) {
        return mMenu.performItemAction(item, 0);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new IconMenuView.LayoutParams(getContext(), attrs);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        // Override to allow type-checking of LayoutParams. 
        return p instanceof IconMenuView.LayoutParams;
    }

    /**
     * Marks as having stale children.
     */
    void markStaleChildren() {
        if (!mHasStaleChildren) {
            mHasStaleChildren = true;
            requestLayout();
        }
    }
    
    /**
     * @return The number of actual items shown (those that are backed by an
     *         {@link MenuView.ItemView} implementation--eg: excludes More
     *         item).
     */
    @UnsupportedAppUsage
    int getNumActualItemsShown() {
        return mNumActualItemsShown;
    }
    
    void setNumActualItemsShown(int count) {
        mNumActualItemsShown = count;
    }
    
    public int getWindowAnimations() {
        return mAnimations;
    }

    /**
     * Returns the number of items per row.
     * <p>
     * This should only be used for testing.
     * 
     * @return The length of the array is the number of rows. A value at a
     *         position is the number of items in that row.
     * @hide
     */
    public int[] getLayout() {
        return mLayout;
    }
    
    /**
     * Returns the number of rows in the layout.
     * <p>
     * This should only be used for testing.
     * 
     * @return The length of the array is the number of rows. A value at a
     *         position is the number of items in that row.
     * @hide
     */
    public int getLayoutNumRows() {
        return mLayoutNumRows;
    }
    
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {

        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                removeCallbacks(this);
                postDelayed(this, ViewConfiguration.getLongPressTimeout());
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                
                if (mMenuBeingLongpressed) {
                    // It was in cycle mode, so reset it (will also remove us
                    // from being called back)
                    setCycleShortcutCaptionMode(false);
                    return true;
                    
                } else {
                    // Just remove us from being called back
                    removeCallbacks(this);
                    // Fall through to normal processing too
                }
            }
        }
        
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        
        requestFocus();
    }

    @Override
    protected void onDetachedFromWindow() {
        setCycleShortcutCaptionMode(false);
        super.onDetachedFromWindow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {

        if (!hasWindowFocus) {
            setCycleShortcutCaptionMode(false);
        }

        super.onWindowFocusChanged(hasWindowFocus);
    }

    /**
     * Sets the shortcut caption mode for IconMenuView. This mode will
     * continuously cycle between a child's shortcut and its title.
     * 
     * @param cycleShortcutAndNormal Whether to go into cycling shortcut mode,
     *        or to go back to normal.
     */
    private void setCycleShortcutCaptionMode(boolean cycleShortcutAndNormal) {

        if (!cycleShortcutAndNormal) {
            /*
             * We're setting back to title, so remove any callbacks for setting
             * to shortcut
             */
            removeCallbacks(this);
            setChildrenCaptionMode(false);
            mMenuBeingLongpressed = false;
            
        } else {
            
            // Set it the first time (the cycle will be started in run()).
            setChildrenCaptionMode(true);
        }
        
    }

    /**
     * When this method is invoked if the menu is currently not being
     * longpressed, it means that the longpress has just been reached (so we set
     * longpress flag, and start cycling). If it is being longpressed, we cycle
     * to the next mode.
     */
    public void run() {
        
        if (mMenuBeingLongpressed) {

            // Cycle to other caption mode on the children
            setChildrenCaptionMode(!mLastChildrenCaptionMode);

        } else {
            
            // Switch ourselves to continuously cycle the items captions
            mMenuBeingLongpressed = true;
            setCycleShortcutCaptionMode(true);
        }

        // We should run again soon to cycle to the other caption mode
        postDelayed(this, ITEM_CAPTION_CYCLE_DELAY);
    }

    /**
     * Iterates children and sets the desired shortcut mode. Only
     * {@link #setCycleShortcutCaptionMode(boolean)} and {@link #run()} should call
     * this.
     * 
     * @param shortcut Whether to show shortcut or the title.
     */
    private void setChildrenCaptionMode(boolean shortcut) {
        
        // Set the last caption mode pushed to children
        mLastChildrenCaptionMode = shortcut;
        
        for (int i = getChildCount() - 1; i >= 0; i--) {
            ((IconMenuItemView) getChildAt(i)).setCaptionMode(shortcut);
        }
    }

    /**
     * For each item, calculates the most dense row that fully shows the item's
     * title.
     * 
     * @param width The available width of the icon menu.
     */
    private void calculateItemFittingMetadata(int width) {
        int maxNumItemsPerRow = mMaxItemsPerRow;
        int numItems = getChildCount();
        for (int i = 0; i < numItems; i++) {
            LayoutParams lp = (LayoutParams) getChildAt(i).getLayoutParams();
            // Start with 1, since that case does not get covered in the loop below
            lp.maxNumItemsOnRow = 1;
            for (int curNumItemsPerRow = maxNumItemsPerRow; curNumItemsPerRow > 0;
                    curNumItemsPerRow--) {
                // Check whether this item can fit into a row containing curNumItemsPerRow
                if (lp.desiredWidth < width / curNumItemsPerRow) {
                    // It can, mark this value as the most dense row it can fit into
                    lp.maxNumItemsOnRow = curNumItemsPerRow;
                    break;
                }
            }
        }
    }
    
    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        
        View focusedView = getFocusedChild();
        
        for (int i = getChildCount() - 1; i >= 0; i--) {
            if (getChildAt(i) == focusedView) {
                return new SavedState(superState, i);
            }
        }
        
        return new SavedState(superState, -1);
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());

        if (ss.focusedPosition >= getChildCount()) {
            return;
        }
        
        View v = getChildAt(ss.focusedPosition);
        if (v != null) {
            v.requestFocus();
        }
    }

    private static class SavedState extends BaseSavedState {
        int focusedPosition;

        /**
         * Constructor called from {@link IconMenuView#onSaveInstanceState()}
         */
        public SavedState(Parcelable superState, int focusedPosition) {
            super(superState);
            this.focusedPosition = focusedPosition;
        }
        
        /**
         * Constructor called from {@link #CREATOR}
         */
        @UnsupportedAppUsage
        private SavedState(Parcel in) {
            super(in);
            focusedPosition = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeInt(focusedPosition);
        }
        
        public static final Parcelable.Creator<SavedState> CREATOR = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
        
    }
    
    /**
     * Layout parameters specific to IconMenuView (stores the left, top, right, bottom from the
     * measure pass). 
     */
    public static class LayoutParams extends ViewGroup.MarginLayoutParams
    {
        int left, top, right, bottom;
        int desiredWidth;
        int maxNumItemsOnRow;
        
        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }
    }
}
