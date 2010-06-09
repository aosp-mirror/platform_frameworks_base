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

package android.view;

import java.util.ArrayList;

import android.app.ActionBar;
import android.app.Activity;
import android.app.ActionBar.Callback;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.view.menu.ActionMenu;
import com.android.internal.view.menu.ActionMenuItem;

/**
 * @hide
 */
public class ActionBarView extends ViewGroup {
    private static final String TAG = "ActionBarView";
    
    // TODO: This must be defined in the default theme
    private static final int CONTENT_HEIGHT_DIP = 50;
    private static final int CONTENT_PADDING_DIP = 3;
    private static final int CONTENT_SPACING_DIP = 6;
    private static final int CONTENT_ACTION_SPACING_DIP = 12;
    
    /**
     * Display options applied by default
     */
    public static final int DISPLAY_DEFAULT = 0;

    /**
     * Display options that require re-layout as opposed to a simple invalidate
     */
    private static final int DISPLAY_RELAYOUT_MASK =
            ActionBar.DISPLAY_HIDE_HOME |
            ActionBar.DISPLAY_USE_LOGO;
    
    private final int mContentHeight;

    private int mNavigationMode;
    private int mDisplayOptions;
    private int mSpacing;
    private int mActionSpacing;
    private CharSequence mTitle;
    private CharSequence mSubtitle;
    private Drawable mIcon;
    private Drawable mLogo;
    private Drawable mDivider;

    private ImageView mIconView;
    private ImageView mLogoView;
    private TextView mTitleView;
    private TextView mSubtitleView;
    private Spinner mSpinner;
    private View mCustomNavView;
    
    private boolean mShowMenu;

    private ActionMenuItem mLogoNavItem;
    private ActionMenu mActionMenu;
    private ActionMenu mOptionsMenu;
    
    private SparseArray<ActionMenu> mContextMenus;
    
    private Callback mCallback;
    
    private final ArrayList<ActionView> mActions = new ArrayList<ActionView>();
    private final OnClickListener mActionClickHandler = new OnClickListener() {
        public void onClick(View v) {
            ActionView av = (ActionView) v;
            ActionMenuItem item = (ActionMenuItem) av.menuItem;

            if (mCallback == null || !mCallback.onActionItemClicked(item)) {
                item.invoke();
            }
        }
    };

    private final AdapterView.OnItemSelectedListener mNavItemSelectedListener =
            new AdapterView.OnItemSelectedListener() {
        public void onItemSelected(AdapterView parent, View view, int position, long id) {
            if (mCallback != null) {
                mCallback.onNavigationItemSelected(position, id);
            }
        }
        public void onNothingSelected(AdapterView parent) {
            // Do nothing
        }
    };

    private OnClickListener mHomeClickListener = null;

    public ActionBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        mContentHeight = (int) (CONTENT_HEIGHT_DIP * metrics.density + 0.5f);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ActionBar);

        final int colorFilter = a.getColor(R.styleable.ActionBar_colorFilter, 0);

        if (colorFilter != 0) {
            final Drawable d = getBackground();
            d.setDither(true);
            d.setColorFilter(new PorterDuffColorFilter(colorFilter, PorterDuff.Mode.OVERLAY));
        }

        ApplicationInfo info = context.getApplicationInfo();
        PackageManager pm = context.getPackageManager();
        mNavigationMode = a.getInt(R.styleable.ActionBar_navigationMode, ActionBar.NAVIGATION_MODE_STANDARD);
        mTitle = a.getText(R.styleable.ActionBar_title);
        mSubtitle = a.getText(R.styleable.ActionBar_subtitle);
        mDisplayOptions = a.getInt(R.styleable.ActionBar_displayOptions, DISPLAY_DEFAULT);
        
        mLogo = a.getDrawable(R.styleable.ActionBar_logo);
        if (mLogo == null) {
            mLogo = info.loadLogo(pm);
        }
        mIcon = a.getDrawable(R.styleable.ActionBar_icon);
        if (mIcon == null) {
            mIcon = info.loadIcon(pm);
        }
        mDivider = a.getDrawable(R.styleable.ActionBar_divider);
        
        Drawable background = a.getDrawable(R.styleable.ActionBar_background);
        if (background != null) {
            setBackgroundDrawable(background);
        }
        
        final int customNavId = a.getResourceId(R.styleable.ActionBar_customNavigationLayout, 0);
        if (customNavId != 0) {
            LayoutInflater inflater = LayoutInflater.from(context);
            mCustomNavView = (View) inflater.inflate(customNavId, null);
            mNavigationMode = ActionBar.NAVIGATION_MODE_CUSTOM;
        }

        a.recycle();

        // TODO: Set this in the theme
        int padding = (int) (CONTENT_PADDING_DIP * metrics.density + 0.5f);
        setPadding(padding, padding, padding, padding);

        mSpacing = (int) (CONTENT_SPACING_DIP * metrics.density + 0.5f);
        mActionSpacing = (int) (CONTENT_ACTION_SPACING_DIP * metrics.density + 0.5f);
        
        if (mLogo != null || mIcon != null || mTitle != null) {
            mLogoNavItem = new ActionMenuItem(context, 0, android.R.id.home, 0, 0, mTitle);
            mHomeClickListener = new OnClickListener() {
                public void onClick(View v) {
                    if (mCallback != null) {
                        mCallback.onActionItemClicked(mLogoNavItem);
                    }
                }
            };
        }
        
        mContextMenus = new SparseArray<ActionMenu>();
    }
    
    private boolean initOptionsMenu() {
        final Context context = getContext();
        if (!(context instanceof Activity)) {
            return false;
        }
        
        final Activity activity = (Activity) context;
        ActionMenu optionsMenu = new ActionMenu(context);
        if (activity.onCreateOptionsMenu(optionsMenu)) {
            mOptionsMenu = optionsMenu;
            return true;
        }
        
        return false;
    }
    
    public void setCallback(Callback callback) {
        final Context context = getContext();
        mCallback = callback;
        
        ActionMenu actionMenu = new ActionMenu(context);
        if (callback.onCreateActionMenu(actionMenu)) {
            mActionMenu = actionMenu;
            performUpdateActionMenu();
        }
    }
    
    public void setCustomNavigationView(View view) {
        mCustomNavView = view;
        if (view != null) {
            setNavigationMode(ActionBar.NAVIGATION_MODE_CUSTOM);
        }
    }
    
    public void setDividerDrawable(Drawable d) {
        mDivider = d;
    }
    
    public CharSequence getTitle() {
        return mTitle;
    }
    
    public void setTitle(CharSequence title) {
        mTitle = title;
        if (mTitleView != null) {
            mTitleView.setText(title);
        }
        if (mLogoNavItem != null) {
            mLogoNavItem.setTitle(title);
        }
    }
    
    public CharSequence getSubtitle() {
        return mSubtitle;
    }
    
    public void setSubtitle(CharSequence subtitle) {
        mSubtitle = subtitle;
        if (mSubtitleView != null) {
            mSubtitleView.setText(subtitle);
        }
    }
    
    public void setDisplayOptions(int options) {
        final int flagsChanged = options ^ mDisplayOptions;
        mDisplayOptions = options;
        if ((flagsChanged & DISPLAY_RELAYOUT_MASK) != 0) {
            final int vis = (options & ActionBar.DISPLAY_HIDE_HOME) != 0 ? GONE : VISIBLE;
            if (mLogoView != null) {
                mLogoView.setVisibility(vis);
            }
            if (mIconView != null) {
                mIconView.setVisibility(vis);
            }
            
            requestLayout();
        } else {
            invalidate();
        }
    }

    public void setNavigationMode(int mode) {
        final int oldMode = mNavigationMode;
        if (mode != oldMode) {
            switch (oldMode) {
            case ActionBar.NAVIGATION_MODE_STANDARD:
                if (mTitleView != null) {
                    removeView(mTitleView);
                    mTitleView = null;
                }
                break;
            case ActionBar.NAVIGATION_MODE_DROPDOWN_LIST:
                if (mSpinner != null) {
                    removeView(mSpinner);
                    mSpinner = null;
                }
                break;
            case ActionBar.NAVIGATION_MODE_CUSTOM:
                if (mCustomNavView != null) {
                    removeView(mCustomNavView);
                    mCustomNavView = null;
                }
                break;
            }
            
            switch (mode) {
            case ActionBar.NAVIGATION_MODE_STANDARD:
                initTitle();
                break;
            case ActionBar.NAVIGATION_MODE_DROPDOWN_LIST:
                mSpinner = new Spinner(mContext, null,
                        com.android.internal.R.attr.dropDownSpinnerStyle);
                mSpinner.setOnItemSelectedListener(mNavItemSelectedListener);
                addView(mSpinner);
                break;
            case ActionBar.NAVIGATION_MODE_CUSTOM:
                addView(mCustomNavView);
                break;
            }
            mNavigationMode = mode;
            requestLayout();
        }
    }
    
    public void setDropdownAdapter(SpinnerAdapter adapter) {
        mSpinner.setAdapter(adapter);
    }
    
    public View getCustomNavigationView() {
        return mCustomNavView;
    }
    
    public int getNavigationMode() {
        return mNavigationMode;
    }
    
    public int getDisplayOptions() {
        return mDisplayOptions;
    }
    
    private ActionView findActionViewForItem(MenuItem item) {
        final ArrayList<ActionView> actions = mActions;
        final int actionCount = actions.size();
        for (int i = 0; i < actionCount; i++) {
            ActionView av = actions.get(i);
            if (av.menuItem.equals(item)) {
                return av;
            }
        }
        return null;
    }
    
    public void setContextMode(int mode) {
        Callback callback = mCallback;
        if (callback == null) {
            throw new IllegalStateException(
                    "Attempted to set ActionBar context mode with no callback");
        }
        
        ActionMenu menu = mContextMenus.get(mode);
        if (menu == null) {
            // Initialize the new mode
            menu = new ActionMenu(getContext());

            if (!callback.onCreateContextMode(mode, menu)) {
                throw new IllegalArgumentException(
                        "ActionBar callback does not know how to create context mode " + mode);
            }
            mContextMenus.put(mode, menu);
        }
        
        if (callback.onPrepareContextMode(mode, menu)) {
            // TODO Set mode, animate, etc.
        }
    }
    
    public void exitContextMode() {
        // TODO Turn off context mode; go back to normal.
    }
    
    public void updateActionMenu() {
        final ActionMenu menu = mActionMenu;
        if (menu == null || mCallback == null || !mCallback.onUpdateActionMenu(menu)) {
            return;
        }
        performUpdateActionMenu();
    }
    
    private void performUpdateActionMenu() {
        final ActionMenu menu = mActionMenu;
        if (menu == null) {
            return;
        }
        final Context context = getContext();

        int childCount = getChildCount();
        int childIndex = 0;
        while (childIndex < childCount) {
            View v = getChildAt(childIndex);
            if (v instanceof ActionView) {
                detachViewFromParent(childIndex);
                childCount--;
            } else {
                childIndex++;
            }
        }
        
        ArrayList<ActionView> detachedViews = new ArrayList<ActionView>(mActions); 
        final int itemCount = menu.size();
        for (int i = 0; i < itemCount; i++) {
            final MenuItem item = menu.getItem(i);
            
            boolean newView = false;
            ActionView actionView = findActionViewForItem(item);
            if (actionView == null) {
                actionView = new ActionView(context);
                newView = true;
            }
            actionView.actionId = item.getItemId();
            actionView.menuItem = item;
            actionView.actionLabel = item.getTitle();
            actionView.setAdjustViewBounds(true);
            actionView.setImageDrawable(item.getIcon());
            actionView.setFocusable(true);
            actionView.setOnClickListener(mActionClickHandler);
            
            LayoutParams layoutParams = new LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.MATCH_PARENT, LayoutParams.ITEM_TYPE_ACTION);
            actionView.setLayoutParams(layoutParams);
            
            if (newView) {
                addView(actionView);
                mActions.add(actionView);
            } else {
                attachViewToParent(actionView, -1, layoutParams);
                detachedViews.remove(actionView);
                actionView.invalidate();
            }
        }
        
        final int detachedCount = detachedViews.size();
        for (int i = 0; i < detachedCount; i++) {
            removeDetachedView(detachedViews.get(i), false);
        }
        
        requestLayout();
    }

    public void addAction(int id, Drawable icon, CharSequence label, OnActionListener listener) {
        ActionView actionView = new ActionView(getContext());
        actionView.actionId = id;
        actionView.actionLabel = label;
        actionView.actionListener = listener;
        actionView.setAdjustViewBounds(true);
        actionView.setImageDrawable(icon);
        actionView.setOnClickListener(mActionClickHandler);

        actionView.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT, LayoutParams.ITEM_TYPE_ACTION));

        addView(actionView);
        mActions.add(actionView);
        
        requestLayout();
    }
    
    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        if ((mDisplayOptions & ActionBar.DISPLAY_HIDE_HOME) == 0) {
            if (mLogo != null && (mDisplayOptions & ActionBar.DISPLAY_USE_LOGO) != 0) {
                mLogoView = new ImageView(getContext());
                mLogoView.setAdjustViewBounds(true);
                mLogoView.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT,
                        LayoutParams.MATCH_PARENT, LayoutParams.ITEM_TYPE_ICON));
                mLogoView.setImageDrawable(mLogo);
                mLogoView.setClickable(true);
                mLogoView.setFocusable(true);
                mLogoView.setOnClickListener(mHomeClickListener);
                addView(mLogoView);
            } else if (mIcon != null) {
                mIconView = new ImageView(getContext());
                mIconView.setAdjustViewBounds(true);
                mIconView.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT,
                        LayoutParams.MATCH_PARENT, LayoutParams.ITEM_TYPE_ICON));
                mIconView.setImageDrawable(mIcon);
                mIconView.setClickable(true);
                mIconView.setFocusable(true);
                mIconView.setOnClickListener(mHomeClickListener);
                addView(mIconView);
            }
        }

        switch (mNavigationMode) {
        case ActionBar.NAVIGATION_MODE_STANDARD:
            if (mLogoView == null) {
                initTitle();
            }
            break;
            
        case ActionBar.NAVIGATION_MODE_DROPDOWN_LIST:
            throw new UnsupportedOperationException(
                    "Inflating dropdown list navigation isn't supported yet!");
            
        case ActionBar.NAVIGATION_MODE_TABS:
            throw new UnsupportedOperationException(
                    "Tab navigation isn't supported yet!");
            
        case ActionBar.NAVIGATION_MODE_CUSTOM:
            if (mCustomNavView != null) {
                addView(mCustomNavView);
            }
            break;
        }
    }
    
    private void initTitle() {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        mTitleView = (TextView) inflater.inflate(R.layout.action_bar_title_item, null);
        mTitleView.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT, LayoutParams.ITEM_TYPE_TITLE));
        if (mTitle != null) {
            mTitleView.setText(mTitle);
        }
        addView(mTitleView);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        if (widthMode != MeasureSpec.EXACTLY) {
            throw new IllegalStateException(getClass().getSimpleName() + " can only be used " +
                    "with android:layout_width=\"match_parent\" (or fill_parent)");
        }
        
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        if (heightMode != MeasureSpec.AT_MOST) {
            throw new IllegalStateException(getClass().getSimpleName() + " can only be used " +
                    "with android:layout_height=\"wrap_content\"");
        }

        int contentWidth = MeasureSpec.getSize(widthMeasureSpec);
        
        int availableWidth = contentWidth - getPaddingLeft() - getPaddingRight();
        final int height = mContentHeight - getPaddingTop() - getPaddingBottom();
        final int childSpecHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST);

        if (mLogoView != null && mLogoView.getVisibility() != GONE) {
            availableWidth = measureChildView(mLogoView, availableWidth, childSpecHeight, mSpacing);
        }
        if (mIconView != null && mIconView.getVisibility() != GONE) {
            availableWidth = measureChildView(mIconView, availableWidth, childSpecHeight, mSpacing);
        }

        final ArrayList<ActionView> actions = mActions;
        final int actionCount = actions.size();
        for (int i = 0; i < actionCount; i++) {
            ActionView action = actions.get(i);
            availableWidth = measureChildView(action, availableWidth,
                    childSpecHeight, mActionSpacing);
        }
        
        switch (mNavigationMode) {
        case ActionBar.NAVIGATION_MODE_STANDARD:
            if (mTitleView != null) {
                measureChildView(mTitleView, availableWidth, childSpecHeight, mSpacing);
            }
            break;
        case ActionBar.NAVIGATION_MODE_DROPDOWN_LIST:
            if (mSpinner != null) {
                mSpinner.measure(
                        MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
            break;
        case ActionBar.NAVIGATION_MODE_CUSTOM:
            if (mCustomNavView != null) {
                mCustomNavView.measure(
                        MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
            break;
        }

        setMeasuredDimension(contentWidth, mContentHeight);
    }

    private int measureChildView(View child, int availableWidth, int childSpecHeight, int spacing) {
        child.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST),
                childSpecHeight);

        availableWidth -= child.getMeasuredWidth();
        availableWidth -= spacing;

        return availableWidth;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int x = getPaddingLeft();
        final int y = getPaddingTop();
        final int contentHeight = b - t - getPaddingTop() - getPaddingBottom();

        if (mLogoView != null && mLogoView.getVisibility() != GONE) {
            x += positionChild(mLogoView, x, y, contentHeight) + mSpacing;
        }
        if (mIconView != null && mIconView.getVisibility() != GONE) {
            x += positionChild(mIconView, x, y, contentHeight) + mSpacing;
        }
        
        switch (mNavigationMode) {
        case ActionBar.NAVIGATION_MODE_STANDARD:
            if (mTitleView != null) {
                x += positionChild(mTitleView, x, y, contentHeight) + mSpacing;
            }
            break;
        case ActionBar.NAVIGATION_MODE_DROPDOWN_LIST:
            if (mSpinner != null) {
                x += positionChild(mSpinner, x, y, contentHeight) + mSpacing;
            }
            break;
        case ActionBar.NAVIGATION_MODE_CUSTOM:
            if (mCustomNavView != null) {
                x += positionChild(mCustomNavView, x, y, contentHeight) + mSpacing;
            }
            break;
        }

        x = r - l - getPaddingRight();

        final int count = mActions.size();
        for (int i = count - 1; i >= 0; i--) {
            ActionView action = mActions.get(i);
            x -= (positionChildInverse(action, x, y, contentHeight) + mActionSpacing);
        }
    }

    private int positionChild(View child, int x, int y, int contentHeight) {
        int childWidth = child.getMeasuredWidth();
        int childHeight = child.getMeasuredHeight();
        int childTop = y + (contentHeight - childHeight) / 2;

        child.layout(x, childTop, x + childWidth, childTop + childHeight);

        return childWidth;
    }
    
    private int positionChildInverse(View child, int x, int y, int contentHeight) {
        int childWidth = child.getMeasuredWidth();
        int childHeight = child.getMeasuredHeight();
        int childTop = y + (contentHeight - childHeight) / 2;

        child.layout(x - childWidth, childTop, x, childTop + childHeight);

        return childWidth;
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new ViewGroup.LayoutParams(getContext(), attrs);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p != null && p instanceof LayoutParams;
    }

    private static class LayoutParams extends ViewGroup.LayoutParams {
        static final int ITEM_TYPE_UNKNOWN = -1;
        static final int ITEM_TYPE_ICON = 0;
        static final int ITEM_TYPE_TITLE = 1;
        static final int ITEM_TYPE_CUSTOM_NAV = 2;
        static final int ITEM_TYPE_ACTION = 3;
        static final int ITEM_TYPE_MORE = 4;

        int type = ITEM_TYPE_UNKNOWN;

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }
        
        public LayoutParams(int width, int height, int type) {
            this(width, height);
            this.type = type;
        }
        

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }
    }

    public interface OnActionListener {
        void onAction(int id);
    }
    
    private static class ActionView extends ImageView {
        int actionId;
        CharSequence actionLabel;
        OnActionListener actionListener;
        MenuItem menuItem;

        public ActionView(Context context) {
            super(context);
        }
    }
}
