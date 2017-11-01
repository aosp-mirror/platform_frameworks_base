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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StyleRes;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.view.ViewHierarchyEncoder;
import android.view.accessibility.AccessibilityEvent;

import com.android.internal.view.menu.ActionMenuItemView;
import com.android.internal.view.menu.MenuBuilder;
import com.android.internal.view.menu.MenuItemImpl;
import com.android.internal.view.menu.MenuPresenter;
import com.android.internal.view.menu.MenuView;

/**
 * ActionMenuView is a presentation of a series of menu options as a View. It provides
 * several top level options as action buttons while spilling remaining options over as
 * items in an overflow menu. This allows applications to present packs of actions inline with
 * specific or repeating content.
 */
public class ActionMenuView extends LinearLayout implements MenuBuilder.ItemInvoker, MenuView {
    private static final String TAG = "ActionMenuView";

    static final int MIN_CELL_SIZE = 56; // dips
    static final int GENERATED_ITEM_PADDING = 4; // dips

    private MenuBuilder mMenu;

    /** Context against which to inflate popup menus. */
    private Context mPopupContext;

    /** Theme resource against which to inflate popup menus. */
    private int mPopupTheme;

    private boolean mReserveOverflow;
    private ActionMenuPresenter mPresenter;
    private MenuPresenter.Callback mActionMenuPresenterCallback;
    private MenuBuilder.Callback mMenuBuilderCallback;
    private boolean mFormatItems;
    private int mFormatItemsWidth;
    private int mMinCellSize;
    private int mGeneratedItemPadding;

    private OnMenuItemClickListener mOnMenuItemClickListener;

    public ActionMenuView(Context context) {
        this(context, null);
    }

    public ActionMenuView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBaselineAligned(false);
        final float density = context.getResources().getDisplayMetrics().density;
        mMinCellSize = (int) (MIN_CELL_SIZE * density);
        mGeneratedItemPadding = (int) (GENERATED_ITEM_PADDING * density);
        mPopupContext = context;
        mPopupTheme = 0;
    }

    /**
     * Specifies the theme to use when inflating popup menus. By default, uses
     * the same theme as the action menu view itself.
     *
     * @param resId theme used to inflate popup menus
     * @see #getPopupTheme()
     */
    public void setPopupTheme(@StyleRes int resId) {
        if (mPopupTheme != resId) {
            mPopupTheme = resId;
            if (resId == 0) {
                mPopupContext = mContext;
            } else {
                mPopupContext = new ContextThemeWrapper(mContext, resId);
            }
        }
    }

    /**
     * @return resource identifier of the theme used to inflate popup menus, or
     *         0 if menus are inflated against the action menu view theme
     * @see #setPopupTheme(int)
     */
    public int getPopupTheme() {
        return mPopupTheme;
    }

    /**
     * @param presenter Menu presenter used to display popup menu
     * @hide
     */
    public void setPresenter(ActionMenuPresenter presenter) {
        mPresenter = presenter;
        mPresenter.setMenuView(this);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mPresenter != null) {
            mPresenter.updateMenuView(false);

            if (mPresenter.isOverflowMenuShowing()) {
                mPresenter.hideOverflowMenu();
                mPresenter.showOverflowMenu();
            }
        }
    }

    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
        mOnMenuItemClickListener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // If we've been given an exact size to match, apply special formatting during layout.
        final boolean wasFormatted = mFormatItems;
        mFormatItems = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY;

        if (wasFormatted != mFormatItems) {
            mFormatItemsWidth = 0; // Reset this when switching modes
        }

        // Special formatting can change whether items can fit as action buttons.
        // Kick the menu and update presenters when this changes.
        final int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        if (mFormatItems && mMenu != null && widthSize != mFormatItemsWidth) {
            mFormatItemsWidth = widthSize;
            mMenu.onItemsChanged(true);
        }

        final int childCount = getChildCount();
        if (mFormatItems && childCount > 0) {
            onMeasureExactFormat(widthMeasureSpec, heightMeasureSpec);
        } else {
            // Previous measurement at exact format may have set margins - reset them.
            for (int i = 0; i < childCount; i++) {
                final View child = getChildAt(i);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                lp.leftMargin = lp.rightMargin = 0;
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private void onMeasureExactFormat(int widthMeasureSpec, int heightMeasureSpec) {
        // We already know the width mode is EXACTLY if we're here.
        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        final int widthPadding = getPaddingLeft() + getPaddingRight();
        final int heightPadding = getPaddingTop() + getPaddingBottom();

        final int itemHeightSpec = getChildMeasureSpec(heightMeasureSpec, heightPadding,
                ViewGroup.LayoutParams.WRAP_CONTENT);

        widthSize -= widthPadding;

        // Divide the view into cells.
        final int cellCount = widthSize / mMinCellSize;
        final int cellSizeRemaining = widthSize % mMinCellSize;

        if (cellCount == 0) {
            // Give up, nothing fits.
            setMeasuredDimension(widthSize, 0);
            return;
        }

        final int cellSize = mMinCellSize + cellSizeRemaining / cellCount;

        int cellsRemaining = cellCount;
        int maxChildHeight = 0;
        int maxCellsUsed = 0;
        int expandableItemCount = 0;
        int visibleItemCount = 0;
        boolean hasOverflow = false;

        // This is used as a bitfield to locate the smallest items present. Assumes childCount < 64.
        long smallestItemsAt = 0;

        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;

            final boolean isGeneratedItem = child instanceof ActionMenuItemView;
            visibleItemCount++;

            if (isGeneratedItem) {
                // Reset padding for generated menu item views; it may change below
                // and views are recycled.
                child.setPadding(mGeneratedItemPadding, 0, mGeneratedItemPadding, 0);
            }

            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            lp.expanded = false;
            lp.extraPixels = 0;
            lp.cellsUsed = 0;
            lp.expandable = false;
            lp.leftMargin = 0;
            lp.rightMargin = 0;
            lp.preventEdgeOffset = isGeneratedItem && ((ActionMenuItemView) child).hasText();

            // Overflow always gets 1 cell. No more, no less.
            final int cellsAvailable = lp.isOverflowButton ? 1 : cellsRemaining;

            final int cellsUsed = measureChildForCells(child, cellSize, cellsAvailable,
                    itemHeightSpec, heightPadding);

            maxCellsUsed = Math.max(maxCellsUsed, cellsUsed);
            if (lp.expandable) expandableItemCount++;
            if (lp.isOverflowButton) hasOverflow = true;

            cellsRemaining -= cellsUsed;
            maxChildHeight = Math.max(maxChildHeight, child.getMeasuredHeight());
            if (cellsUsed == 1) smallestItemsAt |= (1 << i);
        }

        // When we have overflow and a single expanded (text) item, we want to try centering it
        // visually in the available space even though overflow consumes some of it.
        final boolean centerSingleExpandedItem = hasOverflow && visibleItemCount == 2;

        // Divide space for remaining cells if we have items that can expand.
        // Try distributing whole leftover cells to smaller items first.

        boolean needsExpansion = false;
        while (expandableItemCount > 0 && cellsRemaining > 0) {
            int minCells = Integer.MAX_VALUE;
            long minCellsAt = 0; // Bit locations are indices of relevant child views
            int minCellsItemCount = 0;
            for (int i = 0; i < childCount; i++) {
                final View child = getChildAt(i);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();

                // Don't try to expand items that shouldn't.
                if (!lp.expandable) continue;

                // Mark indices of children that can receive an extra cell.
                if (lp.cellsUsed < minCells) {
                    minCells = lp.cellsUsed;
                    minCellsAt = 1 << i;
                    minCellsItemCount = 1;
                } else if (lp.cellsUsed == minCells) {
                    minCellsAt |= 1 << i;
                    minCellsItemCount++;
                }
            }

            // Items that get expanded will always be in the set of smallest items when we're done.
            smallestItemsAt |= minCellsAt;

            if (minCellsItemCount > cellsRemaining) break; // Couldn't expand anything evenly. Stop.

            // We have enough cells, all minimum size items will be incremented.
            minCells++;

            for (int i = 0; i < childCount; i++) {
                final View child = getChildAt(i);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if ((minCellsAt & (1 << i)) == 0) {
                    // If this item is already at our small item count, mark it for later.
                    if (lp.cellsUsed == minCells) smallestItemsAt |= 1 << i;
                    continue;
                }

                if (centerSingleExpandedItem && lp.preventEdgeOffset && cellsRemaining == 1) {
                    // Add padding to this item such that it centers.
                    child.setPadding(mGeneratedItemPadding + cellSize, 0, mGeneratedItemPadding, 0);
                }
                lp.cellsUsed++;
                lp.expanded = true;
                cellsRemaining--;
            }

            needsExpansion = true;
        }

        // Divide any space left that wouldn't divide along cell boundaries
        // evenly among the smallest items

        final boolean singleItem = !hasOverflow && visibleItemCount == 1;
        if (cellsRemaining > 0 && smallestItemsAt != 0 &&
                (cellsRemaining < visibleItemCount - 1 || singleItem || maxCellsUsed > 1)) {
            float expandCount = Long.bitCount(smallestItemsAt);

            if (!singleItem) {
                // The items at the far edges may only expand by half in order to pin to either side.
                if ((smallestItemsAt & 1) != 0) {
                    LayoutParams lp = (LayoutParams) getChildAt(0).getLayoutParams();
                    if (!lp.preventEdgeOffset) expandCount -= 0.5f;
                }
                if ((smallestItemsAt & (1 << (childCount - 1))) != 0) {
                    LayoutParams lp = ((LayoutParams) getChildAt(childCount - 1).getLayoutParams());
                    if (!lp.preventEdgeOffset) expandCount -= 0.5f;
                }
            }

            final int extraPixels = expandCount > 0 ?
                    (int) (cellsRemaining * cellSize / expandCount) : 0;

            for (int i = 0; i < childCount; i++) {
                if ((smallestItemsAt & (1 << i)) == 0) continue;

                final View child = getChildAt(i);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (child instanceof ActionMenuItemView) {
                    // If this is one of our views, expand and measure at the larger size.
                    lp.extraPixels = extraPixels;
                    lp.expanded = true;
                    if (i == 0 && !lp.preventEdgeOffset) {
                        // First item gets part of its new padding pushed out of sight.
                        // The last item will get this implicitly from layout.
                        lp.leftMargin = -extraPixels / 2;
                    }
                    needsExpansion = true;
                } else if (lp.isOverflowButton) {
                    lp.extraPixels = extraPixels;
                    lp.expanded = true;
                    lp.rightMargin = -extraPixels / 2;
                    needsExpansion = true;
                } else {
                    // If we don't know what it is, give it some margins instead
                    // and let it center within its space. We still want to pin
                    // against the edges.
                    if (i != 0) {
                        lp.leftMargin = extraPixels / 2;
                    }
                    if (i != childCount - 1) {
                        lp.rightMargin = extraPixels / 2;
                    }
                }
            }

            cellsRemaining = 0;
        }

        // Remeasure any items that have had extra space allocated to them.
        if (needsExpansion) {
            for (int i = 0; i < childCount; i++) {
                final View child = getChildAt(i);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();

                if (!lp.expanded) continue;

                final int width = lp.cellsUsed * cellSize + lp.extraPixels;
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        itemHeightSpec);
            }
        }

        if (heightMode != MeasureSpec.EXACTLY) {
            heightSize = maxChildHeight;
        }

        setMeasuredDimension(widthSize, heightSize);
    }

    /**
     * Measure a child view to fit within cell-based formatting. The child's width
     * will be measured to a whole multiple of cellSize.
     *
     * <p>Sets the expandable and cellsUsed fields of LayoutParams.
     *
     * @param child Child to measure
     * @param cellSize Size of one cell
     * @param cellsRemaining Number of cells remaining that this view can expand to fill
     * @param parentHeightMeasureSpec MeasureSpec used by the parent view
     * @param parentHeightPadding Padding present in the parent view
     * @return Number of cells this child was measured to occupy
     */
    static int measureChildForCells(View child, int cellSize, int cellsRemaining,
            int parentHeightMeasureSpec, int parentHeightPadding) {
        final LayoutParams lp = (LayoutParams) child.getLayoutParams();

        final int childHeightSize = MeasureSpec.getSize(parentHeightMeasureSpec) -
                parentHeightPadding;
        final int childHeightMode = MeasureSpec.getMode(parentHeightMeasureSpec);
        final int childHeightSpec = MeasureSpec.makeMeasureSpec(childHeightSize, childHeightMode);

        final ActionMenuItemView itemView = child instanceof ActionMenuItemView ?
                (ActionMenuItemView) child : null;
        final boolean hasText = itemView != null && itemView.hasText();

        int cellsUsed = 0;
        if (cellsRemaining > 0 && (!hasText || cellsRemaining >= 2)) {
            final int childWidthSpec = MeasureSpec.makeMeasureSpec(
                    cellSize * cellsRemaining, MeasureSpec.AT_MOST);
            child.measure(childWidthSpec, childHeightSpec);

            final int measuredWidth = child.getMeasuredWidth();
            cellsUsed = measuredWidth / cellSize;
            if (measuredWidth % cellSize != 0) cellsUsed++;
            if (hasText && cellsUsed < 2) cellsUsed = 2;
        }

        final boolean expandable = !lp.isOverflowButton && hasText;
        lp.expandable = expandable;

        lp.cellsUsed = cellsUsed;
        final int targetWidth = cellsUsed * cellSize;
        child.measure(MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                childHeightSpec);
        return cellsUsed;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (!mFormatItems) {
            super.onLayout(changed, left, top, right, bottom);
            return;
        }

        final int childCount = getChildCount();
        final int midVertical = (bottom - top) / 2;
        final int dividerWidth = getDividerWidth();
        int overflowWidth = 0;
        int nonOverflowWidth = 0;
        int nonOverflowCount = 0;
        int widthRemaining = right - left - getPaddingRight() - getPaddingLeft();
        boolean hasOverflow = false;
        final boolean isLayoutRtl = isLayoutRtl();
        for (int i = 0; i < childCount; i++) {
            final View v = getChildAt(i);
            if (v.getVisibility() == GONE) {
                continue;
            }

            LayoutParams p = (LayoutParams) v.getLayoutParams();
            if (p.isOverflowButton) {
                overflowWidth = v.getMeasuredWidth();
                if (hasDividerBeforeChildAt(i)) {
                    overflowWidth += dividerWidth;
                }

                int height = v.getMeasuredHeight();
                int r;
                int l;
                if (isLayoutRtl) {
                    l = getPaddingLeft() + p.leftMargin;
                    r = l + overflowWidth;
                } else {
                    r = getWidth() - getPaddingRight() - p.rightMargin;
                    l = r - overflowWidth;
                }
                int t = midVertical - (height / 2);
                int b = t + height;
                v.layout(l, t, r, b);

                widthRemaining -= overflowWidth;
                hasOverflow = true;
            } else {
                final int size = v.getMeasuredWidth() + p.leftMargin + p.rightMargin;
                nonOverflowWidth += size;
                widthRemaining -= size;
                if (hasDividerBeforeChildAt(i)) {
                    nonOverflowWidth += dividerWidth;
                }
                nonOverflowCount++;
            }
        }

        if (childCount == 1 && !hasOverflow) {
            // Center a single child
            final View v = getChildAt(0);
            final int width = v.getMeasuredWidth();
            final int height = v.getMeasuredHeight();
            final int midHorizontal = (right - left) / 2;
            final int l = midHorizontal - width / 2;
            final int t = midVertical - height / 2;
            v.layout(l, t, l + width, t + height);
            return;
        }

        final int spacerCount = nonOverflowCount - (hasOverflow ? 0 : 1);
        final int spacerSize = Math.max(0, spacerCount > 0 ? widthRemaining / spacerCount : 0);

        if (isLayoutRtl) {
            int startRight = getWidth() - getPaddingRight();
            for (int i = 0; i < childCount; i++) {
                final View v = getChildAt(i);
                final LayoutParams lp = (LayoutParams) v.getLayoutParams();
                if (v.getVisibility() == GONE || lp.isOverflowButton) {
                    continue;
                }

                startRight -= lp.rightMargin;
                int width = v.getMeasuredWidth();
                int height = v.getMeasuredHeight();
                int t = midVertical - height / 2;
                v.layout(startRight - width, t, startRight, t + height);
                startRight -= width + lp.leftMargin + spacerSize;
            }
        } else {
            int startLeft = getPaddingLeft();
            for (int i = 0; i < childCount; i++) {
                final View v = getChildAt(i);
                final LayoutParams lp = (LayoutParams) v.getLayoutParams();
                if (v.getVisibility() == GONE || lp.isOverflowButton) {
                    continue;
                }

                startLeft += lp.leftMargin;
                int width = v.getMeasuredWidth();
                int height = v.getMeasuredHeight();
                int t = midVertical - height / 2;
                v.layout(startLeft, t, startLeft + width, t + height);
                startLeft += width + lp.rightMargin + spacerSize;
            }
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        dismissPopupMenus();
    }

    /**
     * Set the icon to use for the overflow button.
     *
     * @param icon Drawable to set, may be null to clear the icon
     */
    public void setOverflowIcon(@Nullable Drawable icon) {
        getMenu();
        mPresenter.setOverflowIcon(icon);
    }

    /**
     * Return the current drawable used as the overflow icon.
     *
     * @return The overflow icon drawable
     */
    @Nullable
    public Drawable getOverflowIcon() {
        getMenu();
        return mPresenter.getOverflowIcon();
    }

    /** @hide */
    public boolean isOverflowReserved() {
        return mReserveOverflow;
    }

    /** @hide */
    public void setOverflowReserved(boolean reserveOverflow) {
        mReserveOverflow = reserveOverflow;
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_VERTICAL;
        return params;
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        if (p != null) {
            final LayoutParams result = p instanceof LayoutParams
                    ? new LayoutParams((LayoutParams) p)
                    : new LayoutParams(p);
            if (result.gravity <= Gravity.NO_GRAVITY) {
                result.gravity = Gravity.CENTER_VERTICAL;
            }
            return result;
        }
        return generateDefaultLayoutParams();
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p != null && p instanceof LayoutParams;
    }

    /** @hide */
    public LayoutParams generateOverflowButtonLayoutParams() {
        LayoutParams result = generateDefaultLayoutParams();
        result.isOverflowButton = true;
        return result;
    }

    /** @hide */
    public boolean invokeItem(MenuItemImpl item) {
        return mMenu.performItemAction(item, 0);
    }

    /** @hide */
    public int getWindowAnimations() {
        return 0;
    }

    /** @hide */
    public void initialize(@Nullable MenuBuilder menu) {
        mMenu = menu;
    }

    /**
     * Returns the Menu object that this ActionMenuView is currently presenting.
     *
     * <p>Applications should use this method to obtain the ActionMenuView's Menu object
     * and inflate or add content to it as necessary.</p>
     *
     * @return the Menu presented by this view
     */
    public Menu getMenu() {
        if (mMenu == null) {
            final Context context = getContext();
            mMenu = new MenuBuilder(context);
            mMenu.setCallback(new MenuBuilderCallback());
            mPresenter = new ActionMenuPresenter(context);
            mPresenter.setReserveOverflow(true);
            mPresenter.setCallback(mActionMenuPresenterCallback != null
                    ? mActionMenuPresenterCallback : new ActionMenuPresenterCallback());
            mMenu.addMenuPresenter(mPresenter, mPopupContext);
            mPresenter.setMenuView(this);
        }

        return mMenu;
    }

    /**
     * Must be called before the first call to getMenu()
     * @hide
     */
    public void setMenuCallbacks(MenuPresenter.Callback pcb, MenuBuilder.Callback mcb) {
        mActionMenuPresenterCallback = pcb;
        mMenuBuilderCallback = mcb;
    }

    /**
     * Returns the current menu or null if one has not yet been configured.
     * @hide Internal use only for action bar integration
     */
    public MenuBuilder peekMenu() {
        return mMenu;
    }

    /**
     * Show the overflow items from the associated menu.
     *
     * @return true if the menu was able to be shown, false otherwise
     */
    public boolean showOverflowMenu() {
        return mPresenter != null && mPresenter.showOverflowMenu();
    }

    /**
     * Hide the overflow items from the associated menu.
     *
     * @return true if the menu was able to be hidden, false otherwise
     */
    public boolean hideOverflowMenu() {
        return mPresenter != null && mPresenter.hideOverflowMenu();
    }

    /**
     * Check whether the overflow menu is currently showing. This may not reflect
     * a pending show operation in progress.
     *
     * @return true if the overflow menu is currently showing
     */
    public boolean isOverflowMenuShowing() {
        return mPresenter != null && mPresenter.isOverflowMenuShowing();
    }

    /** @hide */
    public boolean isOverflowMenuShowPending() {
        return mPresenter != null && mPresenter.isOverflowMenuShowPending();
    }

    /**
     * Dismiss any popups associated with this menu view.
     */
    public void dismissPopupMenus() {
        if (mPresenter != null) {
            mPresenter.dismissPopupMenus();
        }
    }

    /**
     * @hide Private LinearLayout (superclass) API. Un-hide if LinearLayout API is made public.
     */
    @Override
    protected boolean hasDividerBeforeChildAt(int childIndex) {
        if (childIndex == 0) {
            return false;
        }
        final View childBefore = getChildAt(childIndex - 1);
        final View child = getChildAt(childIndex);
        boolean result = false;
        if (childIndex < getChildCount() && childBefore instanceof ActionMenuChildView) {
            result |= ((ActionMenuChildView) childBefore).needsDividerAfter();
        }
        if (childIndex > 0 && child instanceof ActionMenuChildView) {
            result |= ((ActionMenuChildView) child).needsDividerBefore();
        }
        return result;
    }

    /** @hide */
    public boolean dispatchPopulateAccessibilityEventInternal(AccessibilityEvent event) {
        return false;
    }

    /** @hide */
    public void setExpandedActionViewsExclusive(boolean exclusive) {
        mPresenter.setExpandedActionViewsExclusive(exclusive);
    }

    /**
     * Interface responsible for receiving menu item click events if the items themselves
     * do not have individual item click listeners.
     */
    public interface OnMenuItemClickListener {
        /**
         * This method will be invoked when a menu item is clicked if the item itself did
         * not already handle the event.
         *
         * @param item {@link MenuItem} that was clicked
         * @return <code>true</code> if the event was handled, <code>false</code> otherwise.
         */
        public boolean onMenuItemClick(MenuItem item);
    }

    private class MenuBuilderCallback implements MenuBuilder.Callback {
        @Override
        public boolean onMenuItemSelected(MenuBuilder menu, MenuItem item) {
            return mOnMenuItemClickListener != null &&
                    mOnMenuItemClickListener.onMenuItemClick(item);
        }

        @Override
        public void onMenuModeChange(MenuBuilder menu) {
            if (mMenuBuilderCallback != null) {
                mMenuBuilderCallback.onMenuModeChange(menu);
            }
        }
    }

    private class ActionMenuPresenterCallback implements ActionMenuPresenter.Callback {
        @Override
        public void onCloseMenu(MenuBuilder menu, boolean allMenusAreClosing) {
        }

        @Override
        public boolean onOpenSubMenu(MenuBuilder subMenu) {
            return false;
        }
    }

    /** @hide */
    public interface ActionMenuChildView {
        public boolean needsDividerBefore();
        public boolean needsDividerAfter();
    }

    public static class LayoutParams extends LinearLayout.LayoutParams {
        /** @hide */
        @ViewDebug.ExportedProperty(category = "layout")
        public boolean isOverflowButton;

        /** @hide */
        @ViewDebug.ExportedProperty(category = "layout")
        public int cellsUsed;

        /** @hide */
        @ViewDebug.ExportedProperty(category = "layout")
        public int extraPixels;

        /** @hide */
        @ViewDebug.ExportedProperty(category = "layout")
        public boolean expandable;

        /** @hide */
        @ViewDebug.ExportedProperty(category = "layout")
        public boolean preventEdgeOffset;

        /** @hide */
        public boolean expanded;

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

        public LayoutParams(ViewGroup.LayoutParams other) {
            super(other);
        }

        public LayoutParams(LayoutParams other) {
            super((LinearLayout.LayoutParams) other);
            isOverflowButton = other.isOverflowButton;
        }

        public LayoutParams(int width, int height) {
            super(width, height);
            isOverflowButton = false;
        }

        /** @hide */
        public LayoutParams(int width, int height, boolean isOverflowButton) {
            super(width, height);
            this.isOverflowButton = isOverflowButton;
        }

        /** @hide */
        @Override
        protected void encodeProperties(@NonNull ViewHierarchyEncoder encoder) {
            super.encodeProperties(encoder);

            encoder.addProperty("layout:overFlowButton", isOverflowButton);
            encoder.addProperty("layout:cellsUsed", cellsUsed);
            encoder.addProperty("layout:extraPixels", extraPixels);
            encoder.addProperty("layout:expandable", expandable);
            encoder.addProperty("layout:preventEdgeOffset", preventEdgeOffset);
        }
    }
}
