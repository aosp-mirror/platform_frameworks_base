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

package android.view;

import com.android.internal.R;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Config;
import android.util.EventLog;
import android.util.Log;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.view.animation.Transformation;

import java.util.ArrayList;

/**
 * <p>
 * A <code>ViewGroup</code> is a special view that can contain other views
 * (called children.) The view group is the base class for layouts and views
 * containers. This class also defines the
 * {@link android.view.ViewGroup.LayoutParams} class which serves as the base
 * class for layouts parameters.
 * </p>
 *
 * <p>
 * Also see {@link LayoutParams} for layout attributes.
 * </p>
 *
 * @attr ref android.R.styleable#ViewGroup_clipChildren
 * @attr ref android.R.styleable#ViewGroup_clipToPadding
 * @attr ref android.R.styleable#ViewGroup_layoutAnimation
 * @attr ref android.R.styleable#ViewGroup_animationCache
 * @attr ref android.R.styleable#ViewGroup_persistentDrawingCache
 * @attr ref android.R.styleable#ViewGroup_alwaysDrawnWithCache
 * @attr ref android.R.styleable#ViewGroup_addStatesFromChildren
 * @attr ref android.R.styleable#ViewGroup_descendantFocusability
 */
public abstract class ViewGroup extends View implements ViewParent, ViewManager {
    private static final boolean DBG = false;

    /**
     * Views which have been hidden or removed which need to be animated on
     * their way out.
     * This field should be made private, so it is hidden from the SDK.
     * {@hide}
     */
    protected ArrayList<View> mDisappearingChildren;

    /**
     * Listener used to propagate events indicating when children are added
     * and/or removed from a view group.
     * This field should be made private, so it is hidden from the SDK.
     * {@hide}
     */
    protected OnHierarchyChangeListener mOnHierarchyChangeListener;

    // The view contained within this ViewGroup that has or contains focus.
    private View mFocused;

    // The current transformation to apply on the child being drawn
    private Transformation mChildTransformation;
    private RectF mInvalidateRegion;

    // Target of Motion events
    private View mMotionTarget;
    private final Rect mTempRect = new Rect();

    // Layout animation
    private LayoutAnimationController mLayoutAnimationController;
    private Animation.AnimationListener mAnimationListener;

    /**
     * Internal flags.
     *
     * This field should be made private, so it is hidden from the SDK.
     * {@hide}
     */
    protected int mGroupFlags;

    // When set, ViewGroup invalidates only the child's rectangle
    // Set by default
    private static final int FLAG_CLIP_CHILDREN = 0x1;

    // When set, ViewGroup excludes the padding area from the invalidate rectangle
    // Set by default
    private static final int FLAG_CLIP_TO_PADDING = 0x2;

    // When set, dispatchDraw() will invoke invalidate(); this is set by drawChild() when
    // a child needs to be invalidated and FLAG_OPTIMIZE_INVALIDATE is set
    private static final int FLAG_INVALIDATE_REQUIRED  = 0x4;

    // When set, dispatchDraw() will run the layout animation and unset the flag
    private static final int FLAG_RUN_ANIMATION = 0x8;

    // When set, there is either no layout animation on the ViewGroup or the layout
    // animation is over
    // Set by default
    private static final int FLAG_ANIMATION_DONE = 0x10;

    // If set, this ViewGroup has padding; if unset there is no padding and we don't need
    // to clip it, even if FLAG_CLIP_TO_PADDING is set
    private static final int FLAG_PADDING_NOT_NULL = 0x20;

    // When set, this ViewGroup caches its children in a Bitmap before starting a layout animation
    // Set by default
    private static final int FLAG_ANIMATION_CACHE = 0x40;

    // When set, this ViewGroup converts calls to invalidate(Rect) to invalidate() during a
    // layout animation; this avoid clobbering the hierarchy
    // Automatically set when the layout animation starts, depending on the animation's
    // characteristics
    private static final int FLAG_OPTIMIZE_INVALIDATE = 0x80;

    // When set, the next call to drawChild() will clear mChildTransformation's matrix
    private static final int FLAG_CLEAR_TRANSFORMATION = 0x100;

    // When set, this ViewGroup invokes mAnimationListener.onAnimationEnd() and removes
    // the children's Bitmap caches if necessary
    // This flag is set when the layout animation is over (after FLAG_ANIMATION_DONE is set)
    private static final int FLAG_NOTIFY_ANIMATION_LISTENER = 0x200;

    /**
     * When set, the drawing method will call {@link #getChildDrawingOrder(int, int)}
     * to get the index of the child to draw for that iteration.
     * 
     * @hide
     */
    protected static final int FLAG_USE_CHILD_DRAWING_ORDER = 0x400;

    /**
     * When set, this ViewGroup supports static transformations on children; this causes
     * {@link #getChildStaticTransformation(View, android.view.animation.Transformation)} to be
     * invoked when a child is drawn.
     *
     * Any subclass overriding
     * {@link #getChildStaticTransformation(View, android.view.animation.Transformation)} should
     * set this flags in {@link #mGroupFlags}.
     *
     * {@hide}
     */
    protected static final int FLAG_SUPPORT_STATIC_TRANSFORMATIONS = 0x800;

    // When the previous drawChild() invocation used an alpha value that was lower than
    // 1.0 and set it in mCachePaint
    private static final int FLAG_ALPHA_LOWER_THAN_ONE = 0x1000;

    /**
     * When set, this ViewGroup's drawable states also include those
     * of its children.
     */
    private static final int FLAG_ADD_STATES_FROM_CHILDREN = 0x2000;

    /**
     * When set, this ViewGroup tries to always draw its children using their drawing cache.
     */
    private static final int FLAG_ALWAYS_DRAWN_WITH_CACHE = 0x4000;

    /**
     * When set, and if FLAG_ALWAYS_DRAWN_WITH_CACHE is not set, this ViewGroup will try to
     * draw its children with their drawing cache.
     */
    private static final int FLAG_CHILDREN_DRAWN_WITH_CACHE = 0x8000;

    /**
     * When set, this group will go through its list of children to notify them of
     * any drawable state change.
     */
    private static final int FLAG_NOTIFY_CHILDREN_ON_DRAWABLE_STATE_CHANGE = 0x10000;

    private static final int FLAG_MASK_FOCUSABILITY = 0x60000;

    /**
     * This view will get focus before any of its descendants.
     */
    public static final int FOCUS_BEFORE_DESCENDANTS = 0x20000;

    /**
     * This view will get focus only if none of its descendants want it.
     */
    public static final int FOCUS_AFTER_DESCENDANTS = 0x40000;

    /**
     * This view will block any of its descendants from getting focus, even
     * if they are focusable.
     */
    public static final int FOCUS_BLOCK_DESCENDANTS = 0x60000;

    /**
     * Used to map between enum in attrubutes and flag values.
     */
    private static final int[] DESCENDANT_FOCUSABILITY_FLAGS =
            {FOCUS_BEFORE_DESCENDANTS, FOCUS_AFTER_DESCENDANTS,
                    FOCUS_BLOCK_DESCENDANTS};

    /**
     * When set, this ViewGroup should not intercept touch events.
     * {@hide}
     */
    protected static final int FLAG_DISALLOW_INTERCEPT = 0x80000;

    /**
     * Indicates which types of drawing caches are to be kept in memory.
     * This field should be made private, so it is hidden from the SDK.
     * {@hide}
     */
    protected int mPersistentDrawingCache;

    /**
     * Used to indicate that no drawing cache should be kept in memory.
     */
    public static final int PERSISTENT_NO_CACHE = 0x0;

    /**
     * Used to indicate that the animation drawing cache should be kept in memory.
     */
    public static final int PERSISTENT_ANIMATION_CACHE = 0x1;

    /**
     * Used to indicate that the scrolling drawing cache should be kept in memory.
     */
    public static final int PERSISTENT_SCROLLING_CACHE = 0x2;

    /**
     * Used to indicate that all drawing caches should be kept in memory.
     */
    public static final int PERSISTENT_ALL_CACHES = 0x3;

    /**
     * We clip to padding when FLAG_CLIP_TO_PADDING and FLAG_PADDING_NOT_NULL
     * are set at the same time.
     */
    protected static final int CLIP_TO_PADDING_MASK = FLAG_CLIP_TO_PADDING | FLAG_PADDING_NOT_NULL;

    // Index of the child's left position in the mLocation array
    private static final int CHILD_LEFT_INDEX = 0;
    // Index of the child's top position in the mLocation array
    private static final int CHILD_TOP_INDEX = 1;

    // Child views of this ViewGroup
    private View[] mChildren;
    // Number of valid children in the mChildren array, the rest should be null or not
    // considered as children
    private int mChildrenCount;

    private static final int ARRAY_INITIAL_CAPACITY = 12;
    private static final int ARRAY_CAPACITY_INCREMENT = 12;

    // Used to draw cached views
    private final Paint mCachePaint = new Paint();

    public ViewGroup(Context context) {
        super(context);
        initViewGroup();
    }

    public ViewGroup(Context context, AttributeSet attrs) {
        super(context, attrs);
        initViewGroup();
        initFromAttributes(context, attrs);
    }

    public ViewGroup(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initViewGroup();
        initFromAttributes(context, attrs);
    }

    private void initViewGroup() {
        // ViewGroup doesn't draw by default
        setFlags(WILL_NOT_DRAW, DRAW_MASK);
        mGroupFlags |= FLAG_CLIP_CHILDREN;
        mGroupFlags |= FLAG_CLIP_TO_PADDING;
        mGroupFlags |= FLAG_ANIMATION_DONE;
        mGroupFlags |= FLAG_ANIMATION_CACHE;
        mGroupFlags |= FLAG_ALWAYS_DRAWN_WITH_CACHE;

        setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);

        mChildren = new View[ARRAY_INITIAL_CAPACITY];
        mChildrenCount = 0;

        mCachePaint.setDither(false);

        mPersistentDrawingCache = PERSISTENT_SCROLLING_CACHE;
    }

    private void initFromAttributes(Context context, AttributeSet attrs) {
        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.ViewGroup);

        final int N = a.getIndexCount();
        for (int i = 0; i < N; i++) {
            int attr = a.getIndex(i);
            switch (attr) {
                case R.styleable.ViewGroup_clipChildren:
                    setClipChildren(a.getBoolean(attr, true));
                    break;
                case R.styleable.ViewGroup_clipToPadding:
                    setClipToPadding(a.getBoolean(attr, true));
                    break;
                case R.styleable.ViewGroup_animationCache:
                    setAnimationCacheEnabled(a.getBoolean(attr, true));
                    break;
                case R.styleable.ViewGroup_persistentDrawingCache:
                    setPersistentDrawingCache(a.getInt(attr, PERSISTENT_SCROLLING_CACHE));
                    break;
                case R.styleable.ViewGroup_addStatesFromChildren:
                    setAddStatesFromChildren(a.getBoolean(attr, false));
                    break;
                case R.styleable.ViewGroup_alwaysDrawnWithCache:
                    setAlwaysDrawnWithCacheEnabled(a.getBoolean(attr, true));
                    break;
                case R.styleable.ViewGroup_layoutAnimation:
                    int id = a.getResourceId(attr, -1);
                    if (id > 0) {
                        setLayoutAnimation(AnimationUtils.loadLayoutAnimation(mContext, id));
                    }
                    break;
                case R.styleable.ViewGroup_descendantFocusability:
                    setDescendantFocusability(DESCENDANT_FOCUSABILITY_FLAGS[a.getInt(attr, 0)]);
                    break;
            }
        }

        a.recycle();
    }

    /**
     * Gets the descendant focusability of this view group.  The descendant
     * focusability defines the relationship between this view group and its
     * descendants when looking for a view to take focus in
     * {@link #requestFocus(int, android.graphics.Rect)}.
     *
     * @return one of {@link #FOCUS_BEFORE_DESCENDANTS}, {@link #FOCUS_AFTER_DESCENDANTS},
     *   {@link #FOCUS_BLOCK_DESCENDANTS}.
     */
    @ViewDebug.ExportedProperty(category = "focus", mapping = {
        @ViewDebug.IntToString(from = FOCUS_BEFORE_DESCENDANTS, to = "FOCUS_BEFORE_DESCENDANTS"),
        @ViewDebug.IntToString(from = FOCUS_AFTER_DESCENDANTS, to = "FOCUS_AFTER_DESCENDANTS"),
        @ViewDebug.IntToString(from = FOCUS_BLOCK_DESCENDANTS, to = "FOCUS_BLOCK_DESCENDANTS")
    })
    public int getDescendantFocusability() {
        return mGroupFlags & FLAG_MASK_FOCUSABILITY;
    }

    /**
     * Set the descendant focusability of this view group. This defines the relationship
     * between this view group and its descendants when looking for a view to
     * take focus in {@link #requestFocus(int, android.graphics.Rect)}.
     *
     * @param focusability one of {@link #FOCUS_BEFORE_DESCENDANTS}, {@link #FOCUS_AFTER_DESCENDANTS},
     *   {@link #FOCUS_BLOCK_DESCENDANTS}.
     */
    public void setDescendantFocusability(int focusability) {
        switch (focusability) {
            case FOCUS_BEFORE_DESCENDANTS:
            case FOCUS_AFTER_DESCENDANTS:
            case FOCUS_BLOCK_DESCENDANTS:
                break;
            default:
                throw new IllegalArgumentException("must be one of FOCUS_BEFORE_DESCENDANTS, "
                        + "FOCUS_AFTER_DESCENDANTS, FOCUS_BLOCK_DESCENDANTS");
        }
        mGroupFlags &= ~FLAG_MASK_FOCUSABILITY;
        mGroupFlags |= (focusability & FLAG_MASK_FOCUSABILITY);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void handleFocusGainInternal(int direction, Rect previouslyFocusedRect) {
        if (mFocused != null) {
            mFocused.unFocus();
            mFocused = null;
        }
        super.handleFocusGainInternal(direction, previouslyFocusedRect);
    }

    /**
     * {@inheritDoc}
     */
    public void requestChildFocus(View child, View focused) {
        if (DBG) {
            System.out.println(this + " requestChildFocus()");
        }
        if (getDescendantFocusability() == FOCUS_BLOCK_DESCENDANTS) {
            return;
        }

        // Unfocus us, if necessary
        super.unFocus();

        // We had a previous notion of who had focus. Clear it.
        if (mFocused != child) {
            if (mFocused != null) {
                mFocused.unFocus();
            }

            mFocused = child;
        }
        if (mParent != null) {
            mParent.requestChildFocus(this, focused);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void focusableViewAvailable(View v) {
        if (mParent != null
                // shortcut: don't report a new focusable view if we block our descendants from
                // getting focus
                && (getDescendantFocusability() != FOCUS_BLOCK_DESCENDANTS)
                // shortcut: don't report a new focusable view if we already are focused
                // (and we don't prefer our descendants)
                //
                // note: knowing that mFocused is non-null is not a good enough reason
                // to break the traversal since in that case we'd actually have to find
                // the focused view and make sure it wasn't FOCUS_AFTER_DESCENDANTS and
                // an ancestor of v; this will get checked for at ViewRoot
                && !(isFocused() && getDescendantFocusability() != FOCUS_AFTER_DESCENDANTS)) {
            mParent.focusableViewAvailable(v);
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean showContextMenuForChild(View originalView) {
        return mParent != null && mParent.showContextMenuForChild(originalView);
    }

    /**
     * Find the nearest view in the specified direction that wants to take
     * focus.
     *
     * @param focused The view that currently has focus
     * @param direction One of FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, and
     *        FOCUS_RIGHT, or 0 for not applicable.
     */
    public View focusSearch(View focused, int direction) {
        if (isRootNamespace()) {
            // root namespace means we should consider ourselves the top of the
            // tree for focus searching; otherwise we could be focus searching
            // into other tabs.  see LocalActivityManager and TabHost for more info
            return FocusFinder.getInstance().findNextFocus(this, focused, direction);
        } else if (mParent != null) {
            return mParent.focusSearch(focused, direction);
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean dispatchUnhandledMove(View focused, int direction) {
        return mFocused != null &&
                mFocused.dispatchUnhandledMove(focused, direction);
    }

    /**
     * {@inheritDoc}
     */
    public void clearChildFocus(View child) {
        if (DBG) {
            System.out.println(this + " clearChildFocus()");
        }

        mFocused = null;
        if (mParent != null) {
            mParent.clearChildFocus(this);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clearFocus() {
        super.clearFocus();

        // clear any child focus if it exists
        if (mFocused != null) {
            mFocused.clearFocus();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void unFocus() {
        if (DBG) {
            System.out.println(this + " unFocus()");
        }

        super.unFocus();
        if (mFocused != null) {
            mFocused.unFocus();
        }
        mFocused = null;
    }

    /**
     * Returns the focused child of this view, if any. The child may have focus
     * or contain focus.
     *
     * @return the focused child or null.
     */
    public View getFocusedChild() {
        return mFocused;
    }

    /**
     * Returns true if this view has or contains focus
     *
     * @return true if this view has or contains focus
     */
    @Override
    public boolean hasFocus() {
        return (mPrivateFlags & FOCUSED) != 0 || mFocused != null;
    }

    /*
     * (non-Javadoc)
     *
     * @see android.view.View#findFocus()
     */
    @Override
    public View findFocus() {
        if (DBG) {
            System.out.println("Find focus in " + this + ": flags="
                    + isFocused() + ", child=" + mFocused);
        }

        if (isFocused()) {
            return this;
        }

        if (mFocused != null) {
            return mFocused.findFocus();
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasFocusable() {
        if ((mViewFlags & VISIBILITY_MASK) != VISIBLE) {
            return false;
        }

        if (isFocusable()) {
            return true;
        }

        final int descendantFocusability = getDescendantFocusability();
        if (descendantFocusability != FOCUS_BLOCK_DESCENDANTS) {
            final int count = mChildrenCount;
            final View[] children = mChildren;

            for (int i = 0; i < count; i++) {
                final View child = children[i];
                if (child.hasFocusable()) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addFocusables(ArrayList<View> views, int direction) {
        addFocusables(views, direction, FOCUSABLES_TOUCH_MODE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        final int focusableCount = views.size();

        final int descendantFocusability = getDescendantFocusability();

        if (descendantFocusability != FOCUS_BLOCK_DESCENDANTS) {
            final int count = mChildrenCount;
            final View[] children = mChildren;

            for (int i = 0; i < count; i++) {
                final View child = children[i];
                if ((child.mViewFlags & VISIBILITY_MASK) == VISIBLE) {
                    child.addFocusables(views, direction, focusableMode);
                }
            }
        }

        // we add ourselves (if focusable) in all cases except for when we are
        // FOCUS_AFTER_DESCENDANTS and there are some descendants focusable.  this is
        // to avoid the focus search finding layouts when a more precise search
        // among the focusable children would be more interesting.
        if (
            descendantFocusability != FOCUS_AFTER_DESCENDANTS ||
                // No focusable descendants
                (focusableCount == views.size())) {
            super.addFocusables(views, direction, focusableMode);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void dispatchWindowFocusChanged(boolean hasFocus) {
        super.dispatchWindowFocusChanged(hasFocus);
        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < count; i++) {
            children[i].dispatchWindowFocusChanged(hasFocus);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addTouchables(ArrayList<View> views) {
        super.addTouchables(views);

        final int count = mChildrenCount;
        final View[] children = mChildren;

        for (int i = 0; i < count; i++) {
            final View child = children[i];
            if ((child.mViewFlags & VISIBILITY_MASK) == VISIBLE) {
                child.addTouchables(views);
            }
        }
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void dispatchDisplayHint(int hint) {
        super.dispatchDisplayHint(hint);
        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < count; i++) {
            children[i].dispatchDisplayHint(hint);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void dispatchVisibilityChanged(View changedView, int visibility) {
        super.dispatchVisibilityChanged(changedView, visibility);
        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < count; i++) {
            children[i].dispatchVisibilityChanged(changedView, visibility);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void dispatchWindowVisibilityChanged(int visibility) {
        super.dispatchWindowVisibilityChanged(visibility);
        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < count; i++) {
            children[i].dispatchWindowVisibilityChanged(visibility);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void dispatchConfigurationChanged(Configuration newConfig) {
        super.dispatchConfigurationChanged(newConfig);
        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < count; i++) {
            children[i].dispatchConfigurationChanged(newConfig);
        }
    }
    
    /**
     * {@inheritDoc}
     */
    public void recomputeViewAttributes(View child) {
        ViewParent parent = mParent;
        if (parent != null) parent.recomputeViewAttributes(this);
    }

    @Override
    void dispatchCollectViewAttributes(int visibility) {
        visibility |= mViewFlags&VISIBILITY_MASK;
        super.dispatchCollectViewAttributes(visibility);
        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < count; i++) {
            children[i].dispatchCollectViewAttributes(visibility);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void bringChildToFront(View child) {
        int index = indexOfChild(child);
        if (index >= 0) {
            removeFromArray(index);
            addInArray(child, mChildrenCount);
            child.mParent = this;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean dispatchKeyEventPreIme(KeyEvent event) {
        if ((mPrivateFlags & (FOCUSED | HAS_BOUNDS)) == (FOCUSED | HAS_BOUNDS)) {
            return super.dispatchKeyEventPreIme(event);
        } else if (mFocused != null && (mFocused.mPrivateFlags & HAS_BOUNDS) == HAS_BOUNDS) {
            return mFocused.dispatchKeyEventPreIme(event);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if ((mPrivateFlags & (FOCUSED | HAS_BOUNDS)) == (FOCUSED | HAS_BOUNDS)) {
            return super.dispatchKeyEvent(event);
        } else if (mFocused != null && (mFocused.mPrivateFlags & HAS_BOUNDS) == HAS_BOUNDS) {
            return mFocused.dispatchKeyEvent(event);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        if ((mPrivateFlags & (FOCUSED | HAS_BOUNDS)) == (FOCUSED | HAS_BOUNDS)) {
            return super.dispatchKeyShortcutEvent(event);
        } else if (mFocused != null && (mFocused.mPrivateFlags & HAS_BOUNDS) == HAS_BOUNDS) {
            return mFocused.dispatchKeyShortcutEvent(event);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        if ((mPrivateFlags & (FOCUSED | HAS_BOUNDS)) == (FOCUSED | HAS_BOUNDS)) {
            return super.dispatchTrackballEvent(event);
        } else if (mFocused != null && (mFocused.mPrivateFlags & HAS_BOUNDS) == HAS_BOUNDS) {
            return mFocused.dispatchTrackballEvent(event);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!onFilterTouchEventForSecurity(ev)) {
            return false;
        }

        final int action = ev.getAction();
        final float xf = ev.getX();
        final float yf = ev.getY();
        final float scrolledXFloat = xf + mScrollX;
        final float scrolledYFloat = yf + mScrollY;
        final Rect frame = mTempRect;

        boolean disallowIntercept = (mGroupFlags & FLAG_DISALLOW_INTERCEPT) != 0;

        if (action == MotionEvent.ACTION_DOWN) {
            if (mMotionTarget != null) {
                // this is weird, we got a pen down, but we thought it was
                // already down!
                // XXX: We should probably send an ACTION_UP to the current
                // target.
                mMotionTarget = null;
            }
            // If we're disallowing intercept or if we're allowing and we didn't
            // intercept
            if (disallowIntercept || !onInterceptTouchEvent(ev)) {
                // reset this event's action (just to protect ourselves)
                ev.setAction(MotionEvent.ACTION_DOWN);
                // We know we want to dispatch the event down, find a child
                // who can handle it, start with the front-most child.
                final int scrolledXInt = (int) scrolledXFloat;
                final int scrolledYInt = (int) scrolledYFloat;
                final View[] children = mChildren;
                final int count = mChildrenCount;

                for (int i = count - 1; i >= 0; i--) {
                    final View child = children[i];
                    if ((child.mViewFlags & VISIBILITY_MASK) == VISIBLE
                            || child.getAnimation() != null) {
                        child.getHitRect(frame);
                        if (frame.contains(scrolledXInt, scrolledYInt)) {
                            // offset the event to the view's coordinate system
                            final float xc = scrolledXFloat - child.mLeft;
                            final float yc = scrolledYFloat - child.mTop;
                            ev.setLocation(xc, yc);
                            child.mPrivateFlags &= ~CANCEL_NEXT_UP_EVENT;
                            if (child.dispatchTouchEvent(ev))  {
                                // Event handled, we have a target now.
                                mMotionTarget = child;
                                return true;
                            }
                            // The event didn't get handled, try the next view.
                            // Don't reset the event's location, it's not
                            // necessary here.
                        }
                    }
                }
            }
        }

        boolean isUpOrCancel = (action == MotionEvent.ACTION_UP) ||
                (action == MotionEvent.ACTION_CANCEL);

        if (isUpOrCancel) {
            // Note, we've already copied the previous state to our local
            // variable, so this takes effect on the next event
            mGroupFlags &= ~FLAG_DISALLOW_INTERCEPT;
        }

        // The event wasn't an ACTION_DOWN, dispatch it to our target if
        // we have one.
        final View target = mMotionTarget;
        if (target == null) {
            // We don't have a target, this means we're handling the
            // event as a regular view.
            ev.setLocation(xf, yf);
            if ((mPrivateFlags & CANCEL_NEXT_UP_EVENT) != 0) {
                ev.setAction(MotionEvent.ACTION_CANCEL);
                mPrivateFlags &= ~CANCEL_NEXT_UP_EVENT;
            }
            return super.dispatchTouchEvent(ev);
        }

        // if have a target, see if we're allowed to and want to intercept its
        // events
        if (!disallowIntercept && onInterceptTouchEvent(ev)) {
            final float xc = scrolledXFloat - (float) target.mLeft;
            final float yc = scrolledYFloat - (float) target.mTop;
            mPrivateFlags &= ~CANCEL_NEXT_UP_EVENT;
            ev.setAction(MotionEvent.ACTION_CANCEL);
            ev.setLocation(xc, yc);
            if (!target.dispatchTouchEvent(ev)) {
                // target didn't handle ACTION_CANCEL. not much we can do
                // but they should have.
            }
            // clear the target
            mMotionTarget = null;
            // Don't dispatch this event to our own view, because we already
            // saw it when intercepting; we just want to give the following
            // event to the normal onTouchEvent().
            return true;
        }

        if (isUpOrCancel) {
            mMotionTarget = null;
        }

        // finally offset the event to the target's coordinate system and
        // dispatch the event.
        final float xc = scrolledXFloat - (float) target.mLeft;
        final float yc = scrolledYFloat - (float) target.mTop;
        ev.setLocation(xc, yc);

        if ((target.mPrivateFlags & CANCEL_NEXT_UP_EVENT) != 0) {
            ev.setAction(MotionEvent.ACTION_CANCEL);
            target.mPrivateFlags &= ~CANCEL_NEXT_UP_EVENT;
            mMotionTarget = null;
        }

        return target.dispatchTouchEvent(ev);
    }

    /**
     * {@inheritDoc}
     */
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {

        if (disallowIntercept == ((mGroupFlags & FLAG_DISALLOW_INTERCEPT) != 0)) {
            // We're already in this state, assume our ancestors are too
            return;
        }

        if (disallowIntercept) {
            mGroupFlags |= FLAG_DISALLOW_INTERCEPT;
        } else {
            mGroupFlags &= ~FLAG_DISALLOW_INTERCEPT;
        }

        // Pass it up to our parent
        if (mParent != null) {
            mParent.requestDisallowInterceptTouchEvent(disallowIntercept);
        }
    }

    /**
     * Implement this method to intercept all touch screen motion events.  This
     * allows you to watch events as they are dispatched to your children, and
     * take ownership of the current gesture at any point.
     *
     * <p>Using this function takes some care, as it has a fairly complicated
     * interaction with {@link View#onTouchEvent(MotionEvent)
     * View.onTouchEvent(MotionEvent)}, and using it requires implementing
     * that method as well as this one in the correct way.  Events will be
     * received in the following order:
     *
     * <ol>
     * <li> You will receive the down event here.
     * <li> The down event will be handled either by a child of this view
     * group, or given to your own onTouchEvent() method to handle; this means
     * you should implement onTouchEvent() to return true, so you will
     * continue to see the rest of the gesture (instead of looking for
     * a parent view to handle it).  Also, by returning true from
     * onTouchEvent(), you will not receive any following
     * events in onInterceptTouchEvent() and all touch processing must
     * happen in onTouchEvent() like normal.
     * <li> For as long as you return false from this function, each following
     * event (up to and including the final up) will be delivered first here
     * and then to the target's onTouchEvent().
     * <li> If you return true from here, you will not receive any
     * following events: the target view will receive the same event but
     * with the action {@link MotionEvent#ACTION_CANCEL}, and all further
     * events will be delivered to your onTouchEvent() method and no longer
     * appear here.
     * </ol>
     *
     * @param ev The motion event being dispatched down the hierarchy.
     * @return Return true to steal motion events from the children and have
     * them dispatched to this ViewGroup through onTouchEvent().
     * The current target will receive an ACTION_CANCEL event, and no further
     * messages will be delivered here.
     */
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * Looks for a view to give focus to respecting the setting specified by
     * {@link #getDescendantFocusability()}.
     *
     * Uses {@link #onRequestFocusInDescendants(int, android.graphics.Rect)} to
     * find focus within the children of this group when appropriate.
     *
     * @see #FOCUS_BEFORE_DESCENDANTS
     * @see #FOCUS_AFTER_DESCENDANTS
     * @see #FOCUS_BLOCK_DESCENDANTS
     * @see #onRequestFocusInDescendants
     */
    @Override
    public boolean requestFocus(int direction, Rect previouslyFocusedRect) {
        if (DBG) {
            System.out.println(this + " ViewGroup.requestFocus direction="
                    + direction);
        }
        int descendantFocusability = getDescendantFocusability();

        switch (descendantFocusability) {
            case FOCUS_BLOCK_DESCENDANTS:
                return super.requestFocus(direction, previouslyFocusedRect);
            case FOCUS_BEFORE_DESCENDANTS: {
                final boolean took = super.requestFocus(direction, previouslyFocusedRect);
                return took ? took : onRequestFocusInDescendants(direction, previouslyFocusedRect);
            }
            case FOCUS_AFTER_DESCENDANTS: {
                final boolean took = onRequestFocusInDescendants(direction, previouslyFocusedRect);
                return took ? took : super.requestFocus(direction, previouslyFocusedRect);
            }
            default:
                throw new IllegalStateException("descendant focusability must be "
                        + "one of FOCUS_BEFORE_DESCENDANTS, FOCUS_AFTER_DESCENDANTS, FOCUS_BLOCK_DESCENDANTS "
                        + "but is " + descendantFocusability);
        }
    }

    /**
     * Look for a descendant to call {@link View#requestFocus} on.
     * Called by {@link ViewGroup#requestFocus(int, android.graphics.Rect)}
     * when it wants to request focus within its children.  Override this to
     * customize how your {@link ViewGroup} requests focus within its children.
     * @param direction One of FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, and FOCUS_RIGHT
     * @param previouslyFocusedRect The rectangle (in this View's coordinate system)
     *        to give a finer grained hint about where focus is coming from.  May be null
     *        if there is no hint.
     * @return Whether focus was taken.
     */
    @SuppressWarnings({"ConstantConditions"})
    protected boolean onRequestFocusInDescendants(int direction,
            Rect previouslyFocusedRect) {
        int index;
        int increment;
        int end;
        int count = mChildrenCount;
        if ((direction & FOCUS_FORWARD) != 0) {
            index = 0;
            increment = 1;
            end = count;
        } else {
            index = count - 1;
            increment = -1;
            end = -1;
        }
        final View[] children = mChildren;
        for (int i = index; i != end; i += increment) {
            View child = children[i];
            if ((child.mViewFlags & VISIBILITY_MASK) == VISIBLE) {
                if (child.requestFocus(direction, previouslyFocusedRect)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * {@inheritDoc}
     * 
     * @hide
     */
    @Override
    public void dispatchStartTemporaryDetach() {
        super.dispatchStartTemporaryDetach();
        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < count; i++) {
            children[i].dispatchStartTemporaryDetach();
        }
    }
    
    /**
     * {@inheritDoc}
     * 
     * @hide
     */
    @Override
    public void dispatchFinishTemporaryDetach() {
        super.dispatchFinishTemporaryDetach();
        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < count; i++) {
            children[i].dispatchFinishTemporaryDetach();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void dispatchAttachedToWindow(AttachInfo info, int visibility) {
        super.dispatchAttachedToWindow(info, visibility);
        visibility |= mViewFlags & VISIBILITY_MASK;
        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < count; i++) {
            children[i].dispatchAttachedToWindow(info, visibility);
        }
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        boolean populated = false;
        for (int i = 0, count = getChildCount(); i < count; i++) {
            populated |= getChildAt(i).dispatchPopulateAccessibilityEvent(event);
        }
        return populated;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void dispatchDetachedFromWindow() {
        // If we still have a motion target, we are still in the process of
        // dispatching motion events to a child; we need to get rid of that
        // child to avoid dispatching events to it after the window is torn
        // down. To make sure we keep the child in a consistent state, we
        // first send it an ACTION_CANCEL motion event.
        if (mMotionTarget != null) {
            final long now = SystemClock.uptimeMillis();
            final MotionEvent event = MotionEvent.obtain(now, now,
                    MotionEvent.ACTION_CANCEL, 0.0f, 0.0f, 0);
            mMotionTarget.dispatchTouchEvent(event);
            event.recycle();
            mMotionTarget = null;
        }

        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < count; i++) {
            children[i].dispatchDetachedFromWindow();
        }
        super.dispatchDetachedFromWindow();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        super.setPadding(left, top, right, bottom);

        if ((mPaddingLeft | mPaddingTop | mPaddingRight | mPaddingRight) != 0) {
            mGroupFlags |= FLAG_PADDING_NOT_NULL;
        } else {
            mGroupFlags &= ~FLAG_PADDING_NOT_NULL;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void dispatchSaveInstanceState(SparseArray<Parcelable> container) {
        super.dispatchSaveInstanceState(container);
        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < count; i++) {
            children[i].dispatchSaveInstanceState(container);
        }
    }

    /**
     * Perform dispatching of a {@link #saveHierarchyState freeze()} to only this view,
     * not to its children.  For use when overriding
     * {@link #dispatchSaveInstanceState dispatchFreeze()} to allow subclasses to freeze
     * their own state but not the state of their children.
     *
     * @param container the container
     */
    protected void dispatchFreezeSelfOnly(SparseArray<Parcelable> container) {
        super.dispatchSaveInstanceState(container);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void dispatchRestoreInstanceState(SparseArray<Parcelable> container) {
        super.dispatchRestoreInstanceState(container);
        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < count; i++) {
            children[i].dispatchRestoreInstanceState(container);
        }
    }

    /**
     * Perform dispatching of a {@link #restoreHierarchyState thaw()} to only this view,
     * not to its children.  For use when overriding
     * {@link #dispatchRestoreInstanceState dispatchThaw()} to allow subclasses to thaw
     * their own state but not the state of their children.
     *
     * @param container the container
     */
    protected void dispatchThawSelfOnly(SparseArray<Parcelable> container) {
        super.dispatchRestoreInstanceState(container);
    }

    /**
     * Enables or disables the drawing cache for each child of this view group.
     *
     * @param enabled true to enable the cache, false to dispose of it
     */
    protected void setChildrenDrawingCacheEnabled(boolean enabled) {
        if (enabled || (mPersistentDrawingCache & PERSISTENT_ALL_CACHES) != PERSISTENT_ALL_CACHES) {
            final View[] children = mChildren;
            final int count = mChildrenCount;
            for (int i = 0; i < count; i++) {
                children[i].setDrawingCacheEnabled(enabled);
            }
        }
    }

    @Override
    protected void onAnimationStart() {
        super.onAnimationStart();

        // When this ViewGroup's animation starts, build the cache for the children
        if ((mGroupFlags & FLAG_ANIMATION_CACHE) == FLAG_ANIMATION_CACHE) {
            final int count = mChildrenCount;
            final View[] children = mChildren;

            for (int i = 0; i < count; i++) {
                final View child = children[i];
                if ((child.mViewFlags & VISIBILITY_MASK) == VISIBLE) {
                    child.setDrawingCacheEnabled(true);
                    child.buildDrawingCache(true);
                }
            }

            mGroupFlags |= FLAG_CHILDREN_DRAWN_WITH_CACHE;
        }
    }

    @Override
    protected void onAnimationEnd() {
        super.onAnimationEnd();

        // When this ViewGroup's animation ends, destroy the cache of the children
        if ((mGroupFlags & FLAG_ANIMATION_CACHE) == FLAG_ANIMATION_CACHE) {
            mGroupFlags &= ~FLAG_CHILDREN_DRAWN_WITH_CACHE;

            if ((mPersistentDrawingCache & PERSISTENT_ANIMATION_CACHE) == 0) {
                setChildrenDrawingCacheEnabled(false);
            }
        }
    }

    @Override
    Bitmap createSnapshot(Bitmap.Config quality, int backgroundColor, boolean skipChildren) {
        int count = mChildrenCount;
        int[] visibilities = null;

        if (skipChildren) {
            visibilities = new int[count];
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                visibilities[i] = child.getVisibility();
                if (visibilities[i] == View.VISIBLE) {
                    child.setVisibility(INVISIBLE);
                }
            }
        }

        Bitmap b = super.createSnapshot(quality, backgroundColor, skipChildren);

        if (skipChildren) {
            for (int i = 0; i < count; i++) {
                getChildAt(i).setVisibility(visibilities[i]);
            }        
        }

        return b;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void dispatchDraw(Canvas canvas) {
        final int count = mChildrenCount;
        final View[] children = mChildren;
        int flags = mGroupFlags;

        if ((flags & FLAG_RUN_ANIMATION) != 0 && canAnimate()) {
            final boolean cache = (mGroupFlags & FLAG_ANIMATION_CACHE) == FLAG_ANIMATION_CACHE;

            for (int i = 0; i < count; i++) {
                final View child = children[i];
                if ((child.mViewFlags & VISIBILITY_MASK) == VISIBLE) {
                    final LayoutParams params = child.getLayoutParams();
                    attachLayoutAnimationParameters(child, params, i, count);
                    bindLayoutAnimation(child);
                    if (cache) {
                        child.setDrawingCacheEnabled(true);
                        child.buildDrawingCache(true);
                    }
                }
            }

            final LayoutAnimationController controller = mLayoutAnimationController;
            if (controller.willOverlap()) {
                mGroupFlags |= FLAG_OPTIMIZE_INVALIDATE;
            }

            controller.start();

            mGroupFlags &= ~FLAG_RUN_ANIMATION;
            mGroupFlags &= ~FLAG_ANIMATION_DONE;

            if (cache) {
                mGroupFlags |= FLAG_CHILDREN_DRAWN_WITH_CACHE;
            }

            if (mAnimationListener != null) {
                mAnimationListener.onAnimationStart(controller.getAnimation());
            }
        }

        int saveCount = 0;
        final boolean clipToPadding = (flags & CLIP_TO_PADDING_MASK) == CLIP_TO_PADDING_MASK;
        if (clipToPadding) {
            saveCount = canvas.save();
            canvas.clipRect(mScrollX + mPaddingLeft, mScrollY + mPaddingTop,
                    mScrollX + mRight - mLeft - mPaddingRight,
                    mScrollY + mBottom - mTop - mPaddingBottom);

        }

        // We will draw our child's animation, let's reset the flag
        mPrivateFlags &= ~DRAW_ANIMATION;
        mGroupFlags &= ~FLAG_INVALIDATE_REQUIRED;

        boolean more = false;
        final long drawingTime = getDrawingTime();

        if ((flags & FLAG_USE_CHILD_DRAWING_ORDER) == 0) {
            for (int i = 0; i < count; i++) {
                final View child = children[i];
                if ((child.mViewFlags & VISIBILITY_MASK) == VISIBLE || child.getAnimation() != null) {
                    more |= drawChild(canvas, child, drawingTime);
                }
            }
        } else {
            for (int i = 0; i < count; i++) {
                final View child = children[getChildDrawingOrder(count, i)];
                if ((child.mViewFlags & VISIBILITY_MASK) == VISIBLE || child.getAnimation() != null) {
                    more |= drawChild(canvas, child, drawingTime);
                }
            }
        }

        // Draw any disappearing views that have animations
        if (mDisappearingChildren != null) {
            final ArrayList<View> disappearingChildren = mDisappearingChildren;
            final int disappearingCount = disappearingChildren.size() - 1;
            // Go backwards -- we may delete as animations finish
            for (int i = disappearingCount; i >= 0; i--) {
                final View child = disappearingChildren.get(i);
                more |= drawChild(canvas, child, drawingTime);
            }
        }

        if (clipToPadding) {
            canvas.restoreToCount(saveCount);
        }

        // mGroupFlags might have been updated by drawChild()
        flags = mGroupFlags;

        if ((flags & FLAG_INVALIDATE_REQUIRED) == FLAG_INVALIDATE_REQUIRED) {
            invalidate();
        }

        if ((flags & FLAG_ANIMATION_DONE) == 0 && (flags & FLAG_NOTIFY_ANIMATION_LISTENER) == 0 &&
                mLayoutAnimationController.isDone() && !more) {
            // We want to erase the drawing cache and notify the listener after the
            // next frame is drawn because one extra invalidate() is caused by
            // drawChild() after the animation is over
            mGroupFlags |= FLAG_NOTIFY_ANIMATION_LISTENER;
            final Runnable end = new Runnable() {
               public void run() {
                   notifyAnimationListener();
               }
            };
            post(end);
        }
    }

    /**
     * Returns the index of the child to draw for this iteration. Override this
     * if you want to change the drawing order of children. By default, it
     * returns i.
     * <p>
     * NOTE: In order for this method to be called, you must enable child ordering
     * first by calling {@link #setChildrenDrawingOrderEnabled(boolean)}.
     *
     * @param i The current iteration.
     * @return The index of the child to draw this iteration.
     * 
     * @see #setChildrenDrawingOrderEnabled(boolean)
     * @see #isChildrenDrawingOrderEnabled()
     */
    protected int getChildDrawingOrder(int childCount, int i) {
        return i;
    }

    private void notifyAnimationListener() {
        mGroupFlags &= ~FLAG_NOTIFY_ANIMATION_LISTENER;
        mGroupFlags |= FLAG_ANIMATION_DONE;

        if (mAnimationListener != null) {
           final Runnable end = new Runnable() {
               public void run() {
                   mAnimationListener.onAnimationEnd(mLayoutAnimationController.getAnimation());
               }
           };
           post(end);
        }

        if ((mGroupFlags & FLAG_ANIMATION_CACHE) == FLAG_ANIMATION_CACHE) {
            mGroupFlags &= ~FLAG_CHILDREN_DRAWN_WITH_CACHE;
            if ((mPersistentDrawingCache & PERSISTENT_ANIMATION_CACHE) == 0) {
                setChildrenDrawingCacheEnabled(false);
            }
        }

        invalidate();
    }

    /**
     * Draw one child of this View Group. This method is responsible for getting
     * the canvas in the right state. This includes clipping, translating so
     * that the child's scrolled origin is at 0, 0, and applying any animation
     * transformations.
     *
     * @param canvas The canvas on which to draw the child
     * @param child Who to draw
     * @param drawingTime The time at which draw is occuring
     * @return True if an invalidate() was issued
     */
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        boolean more = false;

        final int cl = child.mLeft;
        final int ct = child.mTop;
        final int cr = child.mRight;
        final int cb = child.mBottom;

        final int flags = mGroupFlags;

        if ((flags & FLAG_CLEAR_TRANSFORMATION) == FLAG_CLEAR_TRANSFORMATION) {
            if (mChildTransformation != null) {
                mChildTransformation.clear();
            }
            mGroupFlags &= ~FLAG_CLEAR_TRANSFORMATION;
        }

        Transformation transformToApply = null;
        final Animation a = child.getAnimation();
        boolean concatMatrix = false;

        if (a != null) {
            if (mInvalidateRegion == null) {
                mInvalidateRegion = new RectF();
            }
            final RectF region = mInvalidateRegion;

            final boolean initialized = a.isInitialized();
            if (!initialized) {
                a.initialize(cr - cl, cb - ct, getWidth(), getHeight());
                a.initializeInvalidateRegion(0, 0, cr - cl, cb - ct);
                child.onAnimationStart();
            }

            if (mChildTransformation == null) {
                mChildTransformation = new Transformation();
            }
            more = a.getTransformation(drawingTime, mChildTransformation);
            transformToApply = mChildTransformation;

            concatMatrix = a.willChangeTransformationMatrix();

            if (more) {
                if (!a.willChangeBounds()) {
                    if ((flags & (FLAG_OPTIMIZE_INVALIDATE | FLAG_ANIMATION_DONE)) ==
                            FLAG_OPTIMIZE_INVALIDATE) {
                        mGroupFlags |= FLAG_INVALIDATE_REQUIRED;
                    } else if ((flags & FLAG_INVALIDATE_REQUIRED) == 0) {
                        // The child need to draw an animation, potentially offscreen, so
                        // make sure we do not cancel invalidate requests
                        mPrivateFlags |= DRAW_ANIMATION;
                        invalidate(cl, ct, cr, cb);
                    }
                } else {
                    a.getInvalidateRegion(0, 0, cr - cl, cb - ct, region, transformToApply);

                    // The child need to draw an animation, potentially offscreen, so
                    // make sure we do not cancel invalidate requests
                    mPrivateFlags |= DRAW_ANIMATION;

                    final int left = cl + (int) region.left;
                    final int top = ct + (int) region.top;
                    invalidate(left, top, left + (int) region.width(), top + (int) region.height());
                }
            }
        } else if ((flags & FLAG_SUPPORT_STATIC_TRANSFORMATIONS) ==
                FLAG_SUPPORT_STATIC_TRANSFORMATIONS) {
            if (mChildTransformation == null) {
                mChildTransformation = new Transformation();
            }
            final boolean hasTransform = getChildStaticTransformation(child, mChildTransformation);
            if (hasTransform) {
                final int transformType = mChildTransformation.getTransformationType();
                transformToApply = transformType != Transformation.TYPE_IDENTITY ?
                        mChildTransformation : null;
                concatMatrix = (transformType & Transformation.TYPE_MATRIX) != 0;
            }
        }

        // Sets the flag as early as possible to allow draw() implementations
        // to call invalidate() successfully when doing animations
        child.mPrivateFlags |= DRAWN;

        if (!concatMatrix && canvas.quickReject(cl, ct, cr, cb, Canvas.EdgeType.BW) &&
                (child.mPrivateFlags & DRAW_ANIMATION) == 0) {
            return more;
        }

        child.computeScroll();

        final int sx = child.mScrollX;
        final int sy = child.mScrollY;

        boolean scalingRequired = false;
        Bitmap cache = null;
        if ((flags & FLAG_CHILDREN_DRAWN_WITH_CACHE) == FLAG_CHILDREN_DRAWN_WITH_CACHE ||
                (flags & FLAG_ALWAYS_DRAWN_WITH_CACHE) == FLAG_ALWAYS_DRAWN_WITH_CACHE) {
            cache = child.getDrawingCache(true);
            if (mAttachInfo != null) scalingRequired = mAttachInfo.mScalingRequired;
        }

        final boolean hasNoCache = cache == null;

        final int restoreTo = canvas.save();
        if (hasNoCache) {
            canvas.translate(cl - sx, ct - sy);
        } else {
            canvas.translate(cl, ct);
            if (scalingRequired) {
                // mAttachInfo cannot be null, otherwise scalingRequired == false
                final float scale = 1.0f / mAttachInfo.mApplicationScale;
                canvas.scale(scale, scale);
            }
        }

        float alpha = 1.0f;

        if (transformToApply != null) {
            if (concatMatrix) {
                int transX = 0;
                int transY = 0;
                if (hasNoCache) {
                    transX = -sx;
                    transY = -sy;
                }
                // Undo the scroll translation, apply the transformation matrix,
                // then redo the scroll translate to get the correct result.
                canvas.translate(-transX, -transY);
                canvas.concat(transformToApply.getMatrix());
                canvas.translate(transX, transY);
                mGroupFlags |= FLAG_CLEAR_TRANSFORMATION;
            }

            alpha = transformToApply.getAlpha();
            if (alpha < 1.0f) {
                mGroupFlags |= FLAG_CLEAR_TRANSFORMATION;
            }

            if (alpha < 1.0f && hasNoCache) {
                final int multipliedAlpha = (int) (255 * alpha);
                if (!child.onSetAlpha(multipliedAlpha)) {
                    canvas.saveLayerAlpha(sx, sy, sx + cr - cl, sy + cb - ct, multipliedAlpha,
                            Canvas.HAS_ALPHA_LAYER_SAVE_FLAG | Canvas.CLIP_TO_LAYER_SAVE_FLAG);
                } else {
                    child.mPrivateFlags |= ALPHA_SET;
                }
            }
        } else if ((child.mPrivateFlags & ALPHA_SET) == ALPHA_SET) {
            child.onSetAlpha(255);
        }

        if ((flags & FLAG_CLIP_CHILDREN) == FLAG_CLIP_CHILDREN) {
            if (hasNoCache) {
                canvas.clipRect(sx, sy, sx + (cr - cl), sy + (cb - ct));
            } else {
                if (!scalingRequired) {
                    canvas.clipRect(0, 0, cr - cl, cb - ct);
                } else {
                    canvas.clipRect(0, 0, cache.getWidth(), cache.getHeight());
                }
            }
        }

        if (hasNoCache) {
            // Fast path for layouts with no backgrounds
            if ((child.mPrivateFlags & SKIP_DRAW) == SKIP_DRAW) {
                if (ViewDebug.TRACE_HIERARCHY) {
                    ViewDebug.trace(this, ViewDebug.HierarchyTraceType.DRAW);
                }
                child.mPrivateFlags &= ~DIRTY_MASK;
                child.dispatchDraw(canvas);
            } else {
                child.draw(canvas);
            }
        } else {
            final Paint cachePaint = mCachePaint;
            if (alpha < 1.0f) {
                cachePaint.setAlpha((int) (alpha * 255));
                mGroupFlags |= FLAG_ALPHA_LOWER_THAN_ONE;
            } else if  ((flags & FLAG_ALPHA_LOWER_THAN_ONE) == FLAG_ALPHA_LOWER_THAN_ONE) {
                cachePaint.setAlpha(255);
                mGroupFlags &= ~FLAG_ALPHA_LOWER_THAN_ONE;
            }
            if (Config.DEBUG && ViewDebug.profileDrawing) {
                EventLog.writeEvent(60003, hashCode());
            }
            canvas.drawBitmap(cache, 0.0f, 0.0f, cachePaint);
        }

        canvas.restoreToCount(restoreTo);

        if (a != null && !more) {
            child.onSetAlpha(255);
            finishAnimatingView(child, a);
        }

        return more;
    }

    /**
     * By default, children are clipped to their bounds before drawing. This
     * allows view groups to override this behavior for animations, etc.
     *
     * @param clipChildren true to clip children to their bounds,
     *        false otherwise
     * @attr ref android.R.styleable#ViewGroup_clipChildren
     */
    public void setClipChildren(boolean clipChildren) {
        setBooleanFlag(FLAG_CLIP_CHILDREN, clipChildren);
    }

    /**
     * By default, children are clipped to the padding of the ViewGroup. This
     * allows view groups to override this behavior
     *
     * @param clipToPadding true to clip children to the padding of the
     *        group, false otherwise
     * @attr ref android.R.styleable#ViewGroup_clipToPadding
     */
    public void setClipToPadding(boolean clipToPadding) {
        setBooleanFlag(FLAG_CLIP_TO_PADDING, clipToPadding);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void dispatchSetSelected(boolean selected) {
        final View[] children = mChildren;
        final int count = mChildrenCount;
        for (int i = 0; i < count; i++) {
            children[i].setSelected(selected);
        }
    }

    @Override
    protected void dispatchSetPressed(boolean pressed) {
        final View[] children = mChildren;
        final int count = mChildrenCount;
        for (int i = 0; i < count; i++) {
            children[i].setPressed(pressed);
        }
    }

    /**
     * When this property is set to true, this ViewGroup supports static transformations on
     * children; this causes
     * {@link #getChildStaticTransformation(View, android.view.animation.Transformation)} to be
     * invoked when a child is drawn.
     *
     * Any subclass overriding
     * {@link #getChildStaticTransformation(View, android.view.animation.Transformation)} should
     * set this property to true.
     *
     * @param enabled True to enable static transformations on children, false otherwise.
     *
     * @see #FLAG_SUPPORT_STATIC_TRANSFORMATIONS
     */
    protected void setStaticTransformationsEnabled(boolean enabled) {
        setBooleanFlag(FLAG_SUPPORT_STATIC_TRANSFORMATIONS, enabled);
    }

    /**
     * {@inheritDoc}
     *
     * @see #setStaticTransformationsEnabled(boolean)
     */
    protected boolean getChildStaticTransformation(View child, Transformation t) {
        return false;
    }

    /**
     * {@hide}
     */
    @Override
    protected View findViewTraversal(int id) {
        if (id == mID) {
            return this;
        }

        final View[] where = mChildren;
        final int len = mChildrenCount;

        for (int i = 0; i < len; i++) {
            View v = where[i];

            if ((v.mPrivateFlags & IS_ROOT_NAMESPACE) == 0) {
                v = v.findViewById(id);

                if (v != null) {
                    return v;
                }
            }
        }

        return null;
    }

    /**
     * {@hide}
     */
    @Override
    protected View findViewWithTagTraversal(Object tag) {
        if (tag != null && tag.equals(mTag)) {
            return this;
        }

        final View[] where = mChildren;
        final int len = mChildrenCount;

        for (int i = 0; i < len; i++) {
            View v = where[i];

            if ((v.mPrivateFlags & IS_ROOT_NAMESPACE) == 0) {
                v = v.findViewWithTag(tag);

                if (v != null) {
                    return v;
                }
            }
        }

        return null;
    }

    /**
     * Adds a child view. If no layout parameters are already set on the child, the
     * default parameters for this ViewGroup are set on the child.
     *
     * @param child the child view to add
     *
     * @see #generateDefaultLayoutParams()
     */
    public void addView(View child) {
        addView(child, -1);
    }

    /**
     * Adds a child view. If no layout parameters are already set on the child, the
     * default parameters for this ViewGroup are set on the child.
     *
     * @param child the child view to add
     * @param index the position at which to add the child
     *
     * @see #generateDefaultLayoutParams()
     */
    public void addView(View child, int index) {
        LayoutParams params = child.getLayoutParams();
        if (params == null) {
            params = generateDefaultLayoutParams();
            if (params == null) {
                throw new IllegalArgumentException("generateDefaultLayoutParams() cannot return null");
            }
        }
        addView(child, index, params);
    }

    /**
     * Adds a child view with this ViewGroup's default layout parameters and the
     * specified width and height.
     *
     * @param child the child view to add
     */
    public void addView(View child, int width, int height) {
        final LayoutParams params = generateDefaultLayoutParams();
        params.width = width;
        params.height = height;
        addView(child, -1, params);
    }

    /**
     * Adds a child view with the specified layout parameters.
     *
     * @param child the child view to add
     * @param params the layout parameters to set on the child
     */
    public void addView(View child, LayoutParams params) {
        addView(child, -1, params);
    }

    /**
     * Adds a child view with the specified layout parameters.
     *
     * @param child the child view to add
     * @param index the position at which to add the child
     * @param params the layout parameters to set on the child
     */
    public void addView(View child, int index, LayoutParams params) {
        if (DBG) {
            System.out.println(this + " addView");
        }

        // addViewInner() will call child.requestLayout() when setting the new LayoutParams
        // therefore, we call requestLayout() on ourselves before, so that the child's request
        // will be blocked at our level
        requestLayout();
        invalidate();
        addViewInner(child, index, params, false);
    }

    /**
     * {@inheritDoc}
     */
    public void updateViewLayout(View view, ViewGroup.LayoutParams params) {
        if (!checkLayoutParams(params)) {
            throw new IllegalArgumentException("Invalid LayoutParams supplied to " + this);
        }
        if (view.mParent != this) {
            throw new IllegalArgumentException("Given view not a child of " + this);
        }
        view.setLayoutParams(params);
    }

    /**
     * {@inheritDoc}
     */
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return  p != null;
    }

    /**
     * Interface definition for a callback to be invoked when the hierarchy
     * within this view changed. The hierarchy changes whenever a child is added
     * to or removed from this view.
     */
    public interface OnHierarchyChangeListener {
        /**
         * Called when a new child is added to a parent view.
         *
         * @param parent the view in which a child was added
         * @param child the new child view added in the hierarchy
         */
        void onChildViewAdded(View parent, View child);

        /**
         * Called when a child is removed from a parent view.
         *
         * @param parent the view from which the child was removed
         * @param child the child removed from the hierarchy
         */
        void onChildViewRemoved(View parent, View child);
    }

    /**
     * Register a callback to be invoked when a child is added to or removed
     * from this view.
     *
     * @param listener the callback to invoke on hierarchy change
     */
    public void setOnHierarchyChangeListener(OnHierarchyChangeListener listener) {
        mOnHierarchyChangeListener = listener;
    }

    /**
     * Adds a view during layout. This is useful if in your onLayout() method,
     * you need to add more views (as does the list view for example).
     *
     * If index is negative, it means put it at the end of the list.
     *
     * @param child the view to add to the group
     * @param index the index at which the child must be added
     * @param params the layout parameters to associate with the child
     * @return true if the child was added, false otherwise
     */
    protected boolean addViewInLayout(View child, int index, LayoutParams params) {
        return addViewInLayout(child, index, params, false);
    }

    /**
     * Adds a view during layout. This is useful if in your onLayout() method,
     * you need to add more views (as does the list view for example).
     *
     * If index is negative, it means put it at the end of the list.
     *
     * @param child the view to add to the group
     * @param index the index at which the child must be added
     * @param params the layout parameters to associate with the child
     * @param preventRequestLayout if true, calling this method will not trigger a
     *        layout request on child
     * @return true if the child was added, false otherwise
     */
    protected boolean addViewInLayout(View child, int index, LayoutParams params,
            boolean preventRequestLayout) {
        child.mParent = null;
        addViewInner(child, index, params, preventRequestLayout);
        child.mPrivateFlags = (child.mPrivateFlags & ~DIRTY_MASK) | DRAWN;
        return true;
    }

    /**
     * Prevents the specified child to be laid out during the next layout pass.
     *
     * @param child the child on which to perform the cleanup
     */
    protected void cleanupLayoutState(View child) {
        child.mPrivateFlags &= ~View.FORCE_LAYOUT;
    }

    private void addViewInner(View child, int index, LayoutParams params,
            boolean preventRequestLayout) {

        if (child.getParent() != null) {
            throw new IllegalStateException("The specified child already has a parent. " +
                    "You must call removeView() on the child's parent first.");
        }

        if (!checkLayoutParams(params)) {
            params = generateLayoutParams(params);
        }

        if (preventRequestLayout) {
            child.mLayoutParams = params;
        } else {
            child.setLayoutParams(params);
        }

        if (index < 0) {
            index = mChildrenCount;
        }

        addInArray(child, index);

        // tell our children
        if (preventRequestLayout) {
            child.assignParent(this);
        } else {
            child.mParent = this;
        }

        if (child.hasFocus()) {
            requestChildFocus(child, child.findFocus());
        }

        AttachInfo ai = mAttachInfo;
        if (ai != null) {
            boolean lastKeepOn = ai.mKeepScreenOn;
            ai.mKeepScreenOn = false;
            child.dispatchAttachedToWindow(mAttachInfo, (mViewFlags&VISIBILITY_MASK));
            if (ai.mKeepScreenOn) {
                needGlobalAttributesUpdate(true);
            }
            ai.mKeepScreenOn = lastKeepOn;
        }

        if (mOnHierarchyChangeListener != null) {
            mOnHierarchyChangeListener.onChildViewAdded(this, child);
        }

        if ((child.mViewFlags & DUPLICATE_PARENT_STATE) == DUPLICATE_PARENT_STATE) {
            mGroupFlags |= FLAG_NOTIFY_CHILDREN_ON_DRAWABLE_STATE_CHANGE;
        }
    }

    private void addInArray(View child, int index) {
        View[] children = mChildren;
        final int count = mChildrenCount;
        final int size = children.length;
        if (index == count) {
            if (size == count) {
                mChildren = new View[size + ARRAY_CAPACITY_INCREMENT];
                System.arraycopy(children, 0, mChildren, 0, size);
                children = mChildren;
            }
            children[mChildrenCount++] = child;
        } else if (index < count) {
            if (size == count) {
                mChildren = new View[size + ARRAY_CAPACITY_INCREMENT];
                System.arraycopy(children, 0, mChildren, 0, index);
                System.arraycopy(children, index, mChildren, index + 1, count - index);
                children = mChildren;
            } else {
                System.arraycopy(children, index, children, index + 1, count - index);
            }
            children[index] = child;
            mChildrenCount++;
        } else {
            throw new IndexOutOfBoundsException("index=" + index + " count=" + count);
        }
    }

    // This method also sets the child's mParent to null
    private void removeFromArray(int index) {
        final View[] children = mChildren;
        children[index].mParent = null;
        final int count = mChildrenCount;
        if (index == count - 1) {
            children[--mChildrenCount] = null;
        } else if (index >= 0 && index < count) {
            System.arraycopy(children, index + 1, children, index, count - index - 1);
            children[--mChildrenCount] = null;
        } else {
            throw new IndexOutOfBoundsException();
        }
    }

    // This method also sets the children's mParent to null
    private void removeFromArray(int start, int count) {
        final View[] children = mChildren;
        final int childrenCount = mChildrenCount;

        start = Math.max(0, start);
        final int end = Math.min(childrenCount, start + count);

        if (start == end) {
            return;
        }

        if (end == childrenCount) {
            for (int i = start; i < end; i++) {
                children[i].mParent = null;
                children[i] = null;
            }
        } else {
            for (int i = start; i < end; i++) {
                children[i].mParent = null;
            }

            // Since we're looping above, we might as well do the copy, but is arraycopy()
            // faster than the extra 2 bounds checks we would do in the loop?
            System.arraycopy(children, end, children, start, childrenCount - end);

            for (int i = childrenCount - (end - start); i < childrenCount; i++) {
                children[i] = null;
            }
        }

        mChildrenCount -= (end - start);
    }

    private void bindLayoutAnimation(View child) {
        Animation a = mLayoutAnimationController.getAnimationForView(child);
        child.setAnimation(a);
    }

    /**
     * Subclasses should override this method to set layout animation
     * parameters on the supplied child.
     *
     * @param child the child to associate with animation parameters
     * @param params the child's layout parameters which hold the animation
     *        parameters
     * @param index the index of the child in the view group
     * @param count the number of children in the view group
     */
    protected void attachLayoutAnimationParameters(View child,
            LayoutParams params, int index, int count) {
        LayoutAnimationController.AnimationParameters animationParams =
                    params.layoutAnimationParameters;
        if (animationParams == null) {
            animationParams = new LayoutAnimationController.AnimationParameters();
            params.layoutAnimationParameters = animationParams;
        }

        animationParams.count = count;
        animationParams.index = index;
    }

    /**
     * {@inheritDoc}
     */
    public void removeView(View view) {
        removeViewInternal(view);
        requestLayout();
        invalidate();
    }

    /**
     * Removes a view during layout. This is useful if in your onLayout() method,
     * you need to remove more views.
     *
     * @param view the view to remove from the group
     */
    public void removeViewInLayout(View view) {
        removeViewInternal(view);
    }

    /**
     * Removes a range of views during layout. This is useful if in your onLayout() method,
     * you need to remove more views.
     *
     * @param start the index of the first view to remove from the group
     * @param count the number of views to remove from the group
     */
    public void removeViewsInLayout(int start, int count) {
        removeViewsInternal(start, count);
    }

    /**
     * Removes the view at the specified position in the group.
     *
     * @param index the position in the group of the view to remove
     */
    public void removeViewAt(int index) {
        removeViewInternal(index, getChildAt(index));
        requestLayout();
        invalidate();
    }

    /**
     * Removes the specified range of views from the group.
     *
     * @param start the first position in the group of the range of views to remove
     * @param count the number of views to remove
     */
    public void removeViews(int start, int count) {
        removeViewsInternal(start, count);
        requestLayout();
        invalidate();
    }

    private void removeViewInternal(View view) {
        final int index = indexOfChild(view);
        if (index >= 0) {
            removeViewInternal(index, view);
        }
    }

    private void removeViewInternal(int index, View view) {
        boolean clearChildFocus = false;
        if (view == mFocused) {
            view.clearFocusForRemoval();
            clearChildFocus = true;
        }

        if (view.getAnimation() != null) {
            addDisappearingView(view);
        } else if (view.mAttachInfo != null) {
           view.dispatchDetachedFromWindow();
        }

        if (mOnHierarchyChangeListener != null) {
            mOnHierarchyChangeListener.onChildViewRemoved(this, view);
        }

        needGlobalAttributesUpdate(false);

        removeFromArray(index);

        if (clearChildFocus) {
            clearChildFocus(view);
        }
    }

    private void removeViewsInternal(int start, int count) {
        final OnHierarchyChangeListener onHierarchyChangeListener = mOnHierarchyChangeListener;
        final boolean notifyListener = onHierarchyChangeListener != null;
        final View focused = mFocused;
        final boolean detach = mAttachInfo != null;
        View clearChildFocus = null;

        final View[] children = mChildren;
        final int end = start + count;

        for (int i = start; i < end; i++) {
            final View view = children[i];

            if (view == focused) {
                view.clearFocusForRemoval();
                clearChildFocus = view;
            }

            if (view.getAnimation() != null) {
                addDisappearingView(view);
            } else if (detach) {
               view.dispatchDetachedFromWindow();
            }

            needGlobalAttributesUpdate(false);

            if (notifyListener) {
                onHierarchyChangeListener.onChildViewRemoved(this, view);
            }
        }

        removeFromArray(start, count);

        if (clearChildFocus != null) {
            clearChildFocus(clearChildFocus);
        }
    }

    /**
     * Call this method to remove all child views from the
     * ViewGroup.
     */
    public void removeAllViews() {
        removeAllViewsInLayout();
        requestLayout();
        invalidate();
    }

    /**
     * Called by a ViewGroup subclass to remove child views from itself,
     * when it must first know its size on screen before it can calculate how many
     * child views it will render. An example is a Gallery or a ListView, which
     * may "have" 50 children, but actually only render the number of children
     * that can currently fit inside the object on screen. Do not call
     * this method unless you are extending ViewGroup and understand the
     * view measuring and layout pipeline.
     */
    public void removeAllViewsInLayout() {
        final int count = mChildrenCount;
        if (count <= 0) {
            return;
        }

        final View[] children = mChildren;
        mChildrenCount = 0;

        final OnHierarchyChangeListener listener = mOnHierarchyChangeListener;
        final boolean notify = listener != null;
        final View focused = mFocused;
        final boolean detach = mAttachInfo != null;
        View clearChildFocus = null;

        needGlobalAttributesUpdate(false);

        for (int i = count - 1; i >= 0; i--) {
            final View view = children[i];

            if (view == focused) {
                view.clearFocusForRemoval();
                clearChildFocus = view;
            }

            if (view.getAnimation() != null) {
                addDisappearingView(view);
            } else if (detach) {
               view.dispatchDetachedFromWindow();
            }

            if (notify) {
                listener.onChildViewRemoved(this, view);
            }

            view.mParent = null;
            children[i] = null;
        }

        if (clearChildFocus != null) {
            clearChildFocus(clearChildFocus);
        }
    }

    /**
     * Finishes the removal of a detached view. This method will dispatch the detached from
     * window event and notify the hierarchy change listener.
     *
     * @param child the child to be definitely removed from the view hierarchy
     * @param animate if true and the view has an animation, the view is placed in the
     *                disappearing views list, otherwise, it is detached from the window
     *
     * @see #attachViewToParent(View, int, android.view.ViewGroup.LayoutParams)
     * @see #detachAllViewsFromParent()
     * @see #detachViewFromParent(View)
     * @see #detachViewFromParent(int)
     */
    protected void removeDetachedView(View child, boolean animate) {
        if (child == mFocused) {
            child.clearFocus();
        }

        if (animate && child.getAnimation() != null) {
            addDisappearingView(child);
        } else if (child.mAttachInfo != null) {
            child.dispatchDetachedFromWindow();
        }

        if (mOnHierarchyChangeListener != null) {
            mOnHierarchyChangeListener.onChildViewRemoved(this, child);
        }
    }

    /**
     * Attaches a view to this view group. Attaching a view assigns this group as the parent,
     * sets the layout parameters and puts the view in the list of children so it can be retrieved
     * by calling {@link #getChildAt(int)}.
     *
     * This method should be called only for view which were detached from their parent.
     *
     * @param child the child to attach
     * @param index the index at which the child should be attached
     * @param params the layout parameters of the child
     *
     * @see #removeDetachedView(View, boolean)
     * @see #detachAllViewsFromParent()
     * @see #detachViewFromParent(View)
     * @see #detachViewFromParent(int)
     */
    protected void attachViewToParent(View child, int index, LayoutParams params) {
        child.mLayoutParams = params;

        if (index < 0) {
            index = mChildrenCount;
        }

        addInArray(child, index);

        child.mParent = this;
        child.mPrivateFlags = (child.mPrivateFlags & ~DIRTY_MASK & ~DRAWING_CACHE_VALID) | DRAWN;

        if (child.hasFocus()) {
            requestChildFocus(child, child.findFocus());
        }
    }

    /**
     * Detaches a view from its parent. Detaching a view should be temporary and followed
     * either by a call to {@link #attachViewToParent(View, int, android.view.ViewGroup.LayoutParams)}
     * or a call to {@link #removeDetachedView(View, boolean)}. When a view is detached,
     * its parent is null and cannot be retrieved by a call to {@link #getChildAt(int)}.
     *
     * @param child the child to detach
     *
     * @see #detachViewFromParent(int)
     * @see #detachViewsFromParent(int, int)
     * @see #detachAllViewsFromParent()
     * @see #attachViewToParent(View, int, android.view.ViewGroup.LayoutParams)
     * @see #removeDetachedView(View, boolean)
     */
    protected void detachViewFromParent(View child) {
        removeFromArray(indexOfChild(child));
    }

    /**
     * Detaches a view from its parent. Detaching a view should be temporary and followed
     * either by a call to {@link #attachViewToParent(View, int, android.view.ViewGroup.LayoutParams)}
     * or a call to {@link #removeDetachedView(View, boolean)}. When a view is detached,
     * its parent is null and cannot be retrieved by a call to {@link #getChildAt(int)}.
     *
     * @param index the index of the child to detach
     *
     * @see #detachViewFromParent(View)
     * @see #detachAllViewsFromParent()
     * @see #detachViewsFromParent(int, int)
     * @see #attachViewToParent(View, int, android.view.ViewGroup.LayoutParams)
     * @see #removeDetachedView(View, boolean)
     */
    protected void detachViewFromParent(int index) {
        removeFromArray(index);
    }

    /**
     * Detaches a range of view from their parent. Detaching a view should be temporary and followed
     * either by a call to {@link #attachViewToParent(View, int, android.view.ViewGroup.LayoutParams)}
     * or a call to {@link #removeDetachedView(View, boolean)}. When a view is detached, its
     * parent is null and cannot be retrieved by a call to {@link #getChildAt(int)}.
     *
     * @param start the first index of the childrend range to detach
     * @param count the number of children to detach
     *
     * @see #detachViewFromParent(View)
     * @see #detachViewFromParent(int)
     * @see #detachAllViewsFromParent()
     * @see #attachViewToParent(View, int, android.view.ViewGroup.LayoutParams)
     * @see #removeDetachedView(View, boolean)
     */
    protected void detachViewsFromParent(int start, int count) {
        removeFromArray(start, count);
    }

    /**
     * Detaches all views from the parent. Detaching a view should be temporary and followed
     * either by a call to {@link #attachViewToParent(View, int, android.view.ViewGroup.LayoutParams)}
     * or a call to {@link #removeDetachedView(View, boolean)}. When a view is detached,
     * its parent is null and cannot be retrieved by a call to {@link #getChildAt(int)}.
     *
     * @see #detachViewFromParent(View)
     * @see #detachViewFromParent(int)
     * @see #detachViewsFromParent(int, int)
     * @see #attachViewToParent(View, int, android.view.ViewGroup.LayoutParams)
     * @see #removeDetachedView(View, boolean)
     */
    protected void detachAllViewsFromParent() {
        final int count = mChildrenCount;
        if (count <= 0) {
            return;
        }

        final View[] children = mChildren;
        mChildrenCount = 0;

        for (int i = count - 1; i >= 0; i--) {
            children[i].mParent = null;
            children[i] = null;
        }
    }

    /**
     * Don't call or override this method. It is used for the implementation of
     * the view hierarchy.
     */
    public final void invalidateChild(View child, final Rect dirty) {
        if (ViewDebug.TRACE_HIERARCHY) {
            ViewDebug.trace(this, ViewDebug.HierarchyTraceType.INVALIDATE_CHILD);
        }

        ViewParent parent = this;

        final AttachInfo attachInfo = mAttachInfo;
        if (attachInfo != null) {
            final int[] location = attachInfo.mInvalidateChildLocation;
            location[CHILD_LEFT_INDEX] = child.mLeft;
            location[CHILD_TOP_INDEX] = child.mTop;

            // If the child is drawing an animation, we want to copy this flag onto
            // ourselves and the parent to make sure the invalidate request goes
            // through
            final boolean drawAnimation = (child.mPrivateFlags & DRAW_ANIMATION) == DRAW_ANIMATION;

            // Check whether the child that requests the invalidate is fully opaque
            final boolean isOpaque = child.isOpaque() && !drawAnimation &&
                    child.getAnimation() != null;
            // Mark the child as dirty, using the appropriate flag
            // Make sure we do not set both flags at the same time
            final int opaqueFlag = isOpaque ? DIRTY_OPAQUE : DIRTY;

            do {
                View view = null;
                if (parent instanceof View) {
                    view = (View) parent;
                }

                if (drawAnimation) {
                    if (view != null) {
                        view.mPrivateFlags |= DRAW_ANIMATION;
                    } else if (parent instanceof ViewRoot) {
                        ((ViewRoot) parent).mIsAnimating = true;
                    }
                }

                // If the parent is dirty opaque or not dirty, mark it dirty with the opaque
                // flag coming from the child that initiated the invalidate
                if (view != null && (view.mPrivateFlags & DIRTY_MASK) != DIRTY) {
                    view.mPrivateFlags = (view.mPrivateFlags & ~DIRTY_MASK) | opaqueFlag;
                }

                parent = parent.invalidateChildInParent(location, dirty);
            } while (parent != null);
        }
    }

    /**
     * Don't call or override this method. It is used for the implementation of
     * the view hierarchy.
     *
     * This implementation returns null if this ViewGroup does not have a parent,
     * if this ViewGroup is already fully invalidated or if the dirty rectangle
     * does not intersect with this ViewGroup's bounds.
     */
    public ViewParent invalidateChildInParent(final int[] location, final Rect dirty) {
        if (ViewDebug.TRACE_HIERARCHY) {
            ViewDebug.trace(this, ViewDebug.HierarchyTraceType.INVALIDATE_CHILD_IN_PARENT);
        }

        if ((mPrivateFlags & DRAWN) == DRAWN) {
            if ((mGroupFlags & (FLAG_OPTIMIZE_INVALIDATE | FLAG_ANIMATION_DONE)) !=
                        FLAG_OPTIMIZE_INVALIDATE) {
                dirty.offset(location[CHILD_LEFT_INDEX] - mScrollX,
                        location[CHILD_TOP_INDEX] - mScrollY);

                final int left = mLeft;
                final int top = mTop;

                if (dirty.intersect(0, 0, mRight - left, mBottom - top) ||
                        (mPrivateFlags & DRAW_ANIMATION) == DRAW_ANIMATION) {
                    mPrivateFlags &= ~DRAWING_CACHE_VALID;

                    location[CHILD_LEFT_INDEX] = left;
                    location[CHILD_TOP_INDEX] = top;

                    return mParent;
                }
            } else {
                mPrivateFlags &= ~DRAWN & ~DRAWING_CACHE_VALID;

                location[CHILD_LEFT_INDEX] = mLeft;
                location[CHILD_TOP_INDEX] = mTop;

                dirty.set(0, 0, mRight - location[CHILD_LEFT_INDEX],
                        mBottom - location[CHILD_TOP_INDEX]);

                return mParent;
            }
        }

        return null;
    }

    /**
     * Offset a rectangle that is in a descendant's coordinate
     * space into our coordinate space.
     * @param descendant A descendant of this view
     * @param rect A rectangle defined in descendant's coordinate space.
     */
    public final void offsetDescendantRectToMyCoords(View descendant, Rect rect) {
        offsetRectBetweenParentAndChild(descendant, rect, true, false);
    }

    /**
     * Offset a rectangle that is in our coordinate space into an ancestor's
     * coordinate space.
     * @param descendant A descendant of this view
     * @param rect A rectangle defined in descendant's coordinate space.
     */
    public final void offsetRectIntoDescendantCoords(View descendant, Rect rect) {
        offsetRectBetweenParentAndChild(descendant, rect, false, false);
    }

    /**
     * Helper method that offsets a rect either from parent to descendant or
     * descendant to parent.
     */
    void offsetRectBetweenParentAndChild(View descendant, Rect rect,
            boolean offsetFromChildToParent, boolean clipToBounds) {

        // already in the same coord system :)
        if (descendant == this) {
            return;
        }

        ViewParent theParent = descendant.mParent;

        // search and offset up to the parent
        while ((theParent != null)
                && (theParent instanceof View)
                && (theParent != this)) {

            if (offsetFromChildToParent) {
                rect.offset(descendant.mLeft - descendant.mScrollX,
                        descendant.mTop - descendant.mScrollY);
                if (clipToBounds) {
                    View p = (View) theParent;
                    rect.intersect(0, 0, p.mRight - p.mLeft, p.mBottom - p.mTop);
                }
            } else {
                if (clipToBounds) {
                    View p = (View) theParent;
                    rect.intersect(0, 0, p.mRight - p.mLeft, p.mBottom - p.mTop);
                }
                rect.offset(descendant.mScrollX - descendant.mLeft,
                        descendant.mScrollY - descendant.mTop);
            }

            descendant = (View) theParent;
            theParent = descendant.mParent;
        }

        // now that we are up to this view, need to offset one more time
        // to get into our coordinate space
        if (theParent == this) {
            if (offsetFromChildToParent) {
                rect.offset(descendant.mLeft - descendant.mScrollX,
                        descendant.mTop - descendant.mScrollY);
            } else {
                rect.offset(descendant.mScrollX - descendant.mLeft,
                        descendant.mScrollY - descendant.mTop);
            }
        } else {
            throw new IllegalArgumentException("parameter must be a descendant of this view");
        }
    }

    /**
     * Offset the vertical location of all children of this view by the specified number of pixels.
     *
     * @param offset the number of pixels to offset
     *
     * @hide
     */
    public void offsetChildrenTopAndBottom(int offset) {
        final int count = mChildrenCount;
        final View[] children = mChildren;

        for (int i = 0; i < count; i++) {
            final View v = children[i];
            v.mTop += offset;
            v.mBottom += offset;
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean getChildVisibleRect(View child, Rect r, android.graphics.Point offset) {
        int dx = child.mLeft - mScrollX;
        int dy = child.mTop - mScrollY;
        if (offset != null) {
            offset.x += dx;
            offset.y += dy;
        }
        r.offset(dx, dy);
        return r.intersect(0, 0, mRight - mLeft, mBottom - mTop) &&
               (mParent == null || mParent.getChildVisibleRect(this, r, offset));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected abstract void onLayout(boolean changed,
            int l, int t, int r, int b);

    /**
     * Indicates whether the view group has the ability to animate its children
     * after the first layout.
     *
     * @return true if the children can be animated, false otherwise
     */
    protected boolean canAnimate() {
        return mLayoutAnimationController != null;
    }

    /**
     * Runs the layout animation. Calling this method triggers a relayout of
     * this view group.
     */
    public void startLayoutAnimation() {
        if (mLayoutAnimationController != null) {
            mGroupFlags |= FLAG_RUN_ANIMATION;
            requestLayout();
        }
    }

    /**
     * Schedules the layout animation to be played after the next layout pass
     * of this view group. This can be used to restart the layout animation
     * when the content of the view group changes or when the activity is
     * paused and resumed.
     */
    public void scheduleLayoutAnimation() {
        mGroupFlags |= FLAG_RUN_ANIMATION;
    }

    /**
     * Sets the layout animation controller used to animate the group's
     * children after the first layout.
     *
     * @param controller the animation controller
     */
    public void setLayoutAnimation(LayoutAnimationController controller) {
        mLayoutAnimationController = controller;
        if (mLayoutAnimationController != null) {
            mGroupFlags |= FLAG_RUN_ANIMATION;
        }
    }

    /**
     * Returns the layout animation controller used to animate the group's
     * children.
     *
     * @return the current animation controller
     */
    public LayoutAnimationController getLayoutAnimation() {
        return mLayoutAnimationController;
    }

    /**
     * Indicates whether the children's drawing cache is used during a layout
     * animation. By default, the drawing cache is enabled but this will prevent
     * nested layout animations from working. To nest animations, you must disable
     * the cache.
     *
     * @return true if the animation cache is enabled, false otherwise
     *
     * @see #setAnimationCacheEnabled(boolean)
     * @see View#setDrawingCacheEnabled(boolean)
     */
    @ViewDebug.ExportedProperty
    public boolean isAnimationCacheEnabled() {
        return (mGroupFlags & FLAG_ANIMATION_CACHE) == FLAG_ANIMATION_CACHE;
    }

    /**
     * Enables or disables the children's drawing cache during a layout animation.
     * By default, the drawing cache is enabled but this will prevent nested
     * layout animations from working. To nest animations, you must disable the
     * cache.
     *
     * @param enabled true to enable the animation cache, false otherwise
     *
     * @see #isAnimationCacheEnabled()
     * @see View#setDrawingCacheEnabled(boolean)
     */
    public void setAnimationCacheEnabled(boolean enabled) {
        setBooleanFlag(FLAG_ANIMATION_CACHE, enabled);
    }

    /**
     * Indicates whether this ViewGroup will always try to draw its children using their
     * drawing cache. By default this property is enabled.
     *
     * @return true if the animation cache is enabled, false otherwise
     *
     * @see #setAlwaysDrawnWithCacheEnabled(boolean)
     * @see #setChildrenDrawnWithCacheEnabled(boolean)
     * @see View#setDrawingCacheEnabled(boolean)
     */
    @ViewDebug.ExportedProperty(category = "drawing")
    public boolean isAlwaysDrawnWithCacheEnabled() {
        return (mGroupFlags & FLAG_ALWAYS_DRAWN_WITH_CACHE) == FLAG_ALWAYS_DRAWN_WITH_CACHE;
    }

    /**
     * Indicates whether this ViewGroup will always try to draw its children using their
     * drawing cache. This property can be set to true when the cache rendering is
     * slightly different from the children's normal rendering. Renderings can be different,
     * for instance, when the cache's quality is set to low.
     *
     * When this property is disabled, the ViewGroup will use the drawing cache of its
     * children only when asked to. It's usually the task of subclasses to tell ViewGroup
     * when to start using the drawing cache and when to stop using it.
     *
     * @param always true to always draw with the drawing cache, false otherwise
     *
     * @see #isAlwaysDrawnWithCacheEnabled()
     * @see #setChildrenDrawnWithCacheEnabled(boolean)
     * @see View#setDrawingCacheEnabled(boolean)
     * @see View#setDrawingCacheQuality(int)
     */
    public void setAlwaysDrawnWithCacheEnabled(boolean always) {
        setBooleanFlag(FLAG_ALWAYS_DRAWN_WITH_CACHE, always);
    }

    /**
     * Indicates whether the ViewGroup is currently drawing its children using
     * their drawing cache.
     *
     * @return true if children should be drawn with their cache, false otherwise
     *
     * @see #setAlwaysDrawnWithCacheEnabled(boolean)
     * @see #setChildrenDrawnWithCacheEnabled(boolean)
     */
    @ViewDebug.ExportedProperty(category = "drawing")
    protected boolean isChildrenDrawnWithCacheEnabled() {
        return (mGroupFlags & FLAG_CHILDREN_DRAWN_WITH_CACHE) == FLAG_CHILDREN_DRAWN_WITH_CACHE;
    }

    /**
     * Tells the ViewGroup to draw its children using their drawing cache. This property
     * is ignored when {@link #isAlwaysDrawnWithCacheEnabled()} is true. A child's drawing cache
     * will be used only if it has been enabled.
     *
     * Subclasses should call this method to start and stop using the drawing cache when
     * they perform performance sensitive operations, like scrolling or animating.
     *
     * @param enabled true if children should be drawn with their cache, false otherwise
     *
     * @see #setAlwaysDrawnWithCacheEnabled(boolean)
     * @see #isChildrenDrawnWithCacheEnabled()
     */
    protected void setChildrenDrawnWithCacheEnabled(boolean enabled) {
        setBooleanFlag(FLAG_CHILDREN_DRAWN_WITH_CACHE, enabled);
    }

    /**
     * Indicates whether the ViewGroup is drawing its children in the order defined by
     * {@link #getChildDrawingOrder(int, int)}.
     *
     * @return true if children drawing order is defined by {@link #getChildDrawingOrder(int, int)},
     *         false otherwise
     *
     * @see #setChildrenDrawingOrderEnabled(boolean)
     * @see #getChildDrawingOrder(int, int)
     */
    @ViewDebug.ExportedProperty(category = "drawing")
    protected boolean isChildrenDrawingOrderEnabled() {
        return (mGroupFlags & FLAG_USE_CHILD_DRAWING_ORDER) == FLAG_USE_CHILD_DRAWING_ORDER;
    }

    /**
     * Tells the ViewGroup whether to draw its children in the order defined by the method
     * {@link #getChildDrawingOrder(int, int)}.
     *
     * @param enabled true if the order of the children when drawing is determined by
     *        {@link #getChildDrawingOrder(int, int)}, false otherwise
     *
     * @see #isChildrenDrawingOrderEnabled()
     * @see #getChildDrawingOrder(int, int)
     */
    protected void setChildrenDrawingOrderEnabled(boolean enabled) {
        setBooleanFlag(FLAG_USE_CHILD_DRAWING_ORDER, enabled);
    }

    private void setBooleanFlag(int flag, boolean value) {
        if (value) {
            mGroupFlags |= flag;
        } else {
            mGroupFlags &= ~flag;
        }
    }

    /**
     * Returns an integer indicating what types of drawing caches are kept in memory.
     *
     * @see #setPersistentDrawingCache(int)
     * @see #setAnimationCacheEnabled(boolean)
     *
     * @return one or a combination of {@link #PERSISTENT_NO_CACHE},
     *         {@link #PERSISTENT_ANIMATION_CACHE}, {@link #PERSISTENT_SCROLLING_CACHE}
     *         and {@link #PERSISTENT_ALL_CACHES}
     */
    @ViewDebug.ExportedProperty(category = "drawing", mapping = {
        @ViewDebug.IntToString(from = PERSISTENT_NO_CACHE,        to = "NONE"),
        @ViewDebug.IntToString(from = PERSISTENT_ALL_CACHES,      to = "ANIMATION"),
        @ViewDebug.IntToString(from = PERSISTENT_SCROLLING_CACHE, to = "SCROLLING"),
        @ViewDebug.IntToString(from = PERSISTENT_ALL_CACHES,      to = "ALL")
    })
    public int getPersistentDrawingCache() {
        return mPersistentDrawingCache;
    }

    /**
     * Indicates what types of drawing caches should be kept in memory after
     * they have been created.
     *
     * @see #getPersistentDrawingCache()
     * @see #setAnimationCacheEnabled(boolean)
     *
     * @param drawingCacheToKeep one or a combination of {@link #PERSISTENT_NO_CACHE},
     *        {@link #PERSISTENT_ANIMATION_CACHE}, {@link #PERSISTENT_SCROLLING_CACHE}
     *        and {@link #PERSISTENT_ALL_CACHES}
     */
    public void setPersistentDrawingCache(int drawingCacheToKeep) {
        mPersistentDrawingCache = drawingCacheToKeep & PERSISTENT_ALL_CACHES;
    }

    /**
     * Returns a new set of layout parameters based on the supplied attributes set.
     *
     * @param attrs the attributes to build the layout parameters from
     *
     * @return an instance of {@link android.view.ViewGroup.LayoutParams} or one
     *         of its descendants
     */
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    /**
     * Returns a safe set of layout parameters based on the supplied layout params.
     * When a ViewGroup is passed a View whose layout params do not pass the test of
     * {@link #checkLayoutParams(android.view.ViewGroup.LayoutParams)}, this method
     * is invoked. This method should return a new set of layout params suitable for
     * this ViewGroup, possibly by copying the appropriate attributes from the
     * specified set of layout params.
     *
     * @param p The layout parameters to convert into a suitable set of layout parameters
     *          for this ViewGroup.
     *
     * @return an instance of {@link android.view.ViewGroup.LayoutParams} or one
     *         of its descendants
     */
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return p;
    }

    /**
     * Returns a set of default layout parameters. These parameters are requested
     * when the View passed to {@link #addView(View)} has no layout parameters
     * already set. If null is returned, an exception is thrown from addView.
     *
     * @return a set of default layout parameters or null
     */
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    /**
     * @hide
     */
    @Override
    protected boolean dispatchConsistencyCheck(int consistency) {
        boolean result = super.dispatchConsistencyCheck(consistency);

        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < count; i++) {
            if (!children[i].dispatchConsistencyCheck(consistency)) result = false;
        }

        return result;
    }

    /**
     * @hide
     */
    @Override
    protected boolean onConsistencyCheck(int consistency) {
        boolean result = super.onConsistencyCheck(consistency);

        final boolean checkLayout = (consistency & ViewDebug.CONSISTENCY_LAYOUT) != 0;
        final boolean checkDrawing = (consistency & ViewDebug.CONSISTENCY_DRAWING) != 0;

        if (checkLayout) {
            final int count = mChildrenCount;
            final View[] children = mChildren;
            for (int i = 0; i < count; i++) {
                if (children[i].getParent() != this) {
                    result = false;
                    android.util.Log.d(ViewDebug.CONSISTENCY_LOG_TAG,
                            "View " + children[i] + " has no parent/a parent that is not " + this);
                }
            }
        }

        if (checkDrawing) {
            // If this group is dirty, check that the parent is dirty as well
            if ((mPrivateFlags & DIRTY_MASK) != 0) {
                final ViewParent parent = getParent();
                if (parent != null && !(parent instanceof ViewRoot)) {
                    if ((((View) parent).mPrivateFlags & DIRTY_MASK) == 0) {
                        result = false;
                        android.util.Log.d(ViewDebug.CONSISTENCY_LOG_TAG,
                                "ViewGroup " + this + " is dirty but its parent is not: " + this);
                    }
                }
            }
        }

        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void debug(int depth) {
        super.debug(depth);
        String output;

        if (mFocused != null) {
            output = debugIndent(depth);
            output += "mFocused";
            Log.d(VIEW_LOG_TAG, output);
        }
        if (mChildrenCount != 0) {
            output = debugIndent(depth);
            output += "{";
            Log.d(VIEW_LOG_TAG, output);
        }
        int count = mChildrenCount;
        for (int i = 0; i < count; i++) {
            View child = mChildren[i];
            child.debug(depth + 1);
        }

        if (mChildrenCount != 0) {
            output = debugIndent(depth);
            output += "}";
            Log.d(VIEW_LOG_TAG, output);
        }
    }

    /**
     * Returns the position in the group of the specified child view.
     *
     * @param child the view for which to get the position
     * @return a positive integer representing the position of the view in the
     *         group, or -1 if the view does not exist in the group
     */
    public int indexOfChild(View child) {
        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < count; i++) {
            if (children[i] == child) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the number of children in the group.
     *
     * @return a positive integer representing the number of children in
     *         the group
     */
    public int getChildCount() {
        return mChildrenCount;
    }

    /**
     * Returns the view at the specified position in the group.
     *
     * @param index the position at which to get the view from
     * @return the view at the specified position or null if the position
     *         does not exist within the group
     */
    public View getChildAt(int index) {
        try {
            return mChildren[index];
        } catch (IndexOutOfBoundsException ex) {
            return null;
        }
    }

    /**
     * Ask all of the children of this view to measure themselves, taking into
     * account both the MeasureSpec requirements for this view and its padding.
     * We skip children that are in the GONE state The heavy lifting is done in
     * getChildMeasureSpec.
     *
     * @param widthMeasureSpec The width requirements for this view
     * @param heightMeasureSpec The height requirements for this view
     */
    protected void measureChildren(int widthMeasureSpec, int heightMeasureSpec) {
        final int size = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < size; ++i) {
            final View child = children[i];
            if ((child.mViewFlags & VISIBILITY_MASK) != GONE) {
                measureChild(child, widthMeasureSpec, heightMeasureSpec);
            }
        }
    }

    /**
     * Ask one of the children of this view to measure itself, taking into
     * account both the MeasureSpec requirements for this view and its padding.
     * The heavy lifting is done in getChildMeasureSpec.
     *
     * @param child The child to measure
     * @param parentWidthMeasureSpec The width requirements for this view
     * @param parentHeightMeasureSpec The height requirements for this view
     */
    protected void measureChild(View child, int parentWidthMeasureSpec,
            int parentHeightMeasureSpec) {
        final LayoutParams lp = child.getLayoutParams();

        final int childWidthMeasureSpec = getChildMeasureSpec(parentWidthMeasureSpec,
                mPaddingLeft + mPaddingRight, lp.width);
        final int childHeightMeasureSpec = getChildMeasureSpec(parentHeightMeasureSpec,
                mPaddingTop + mPaddingBottom, lp.height);

        child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
    }

    /**
     * Ask one of the children of this view to measure itself, taking into
     * account both the MeasureSpec requirements for this view and its padding
     * and margins. The child must have MarginLayoutParams The heavy lifting is
     * done in getChildMeasureSpec.
     *
     * @param child The child to measure
     * @param parentWidthMeasureSpec The width requirements for this view
     * @param widthUsed Extra space that has been used up by the parent
     *        horizontally (possibly by other children of the parent)
     * @param parentHeightMeasureSpec The height requirements for this view
     * @param heightUsed Extra space that has been used up by the parent
     *        vertically (possibly by other children of the parent)
     */
    protected void measureChildWithMargins(View child,
            int parentWidthMeasureSpec, int widthUsed,
            int parentHeightMeasureSpec, int heightUsed) {
        final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();

        final int childWidthMeasureSpec = getChildMeasureSpec(parentWidthMeasureSpec,
                mPaddingLeft + mPaddingRight + lp.leftMargin + lp.rightMargin
                        + widthUsed, lp.width);
        final int childHeightMeasureSpec = getChildMeasureSpec(parentHeightMeasureSpec,
                mPaddingTop + mPaddingBottom + lp.topMargin + lp.bottomMargin
                        + heightUsed, lp.height);

        child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
    }

    /**
     * Does the hard part of measureChildren: figuring out the MeasureSpec to
     * pass to a particular child. This method figures out the right MeasureSpec
     * for one dimension (height or width) of one child view.
     *
     * The goal is to combine information from our MeasureSpec with the
     * LayoutParams of the child to get the best possible results. For example,
     * if the this view knows its size (because its MeasureSpec has a mode of
     * EXACTLY), and the child has indicated in its LayoutParams that it wants
     * to be the same size as the parent, the parent should ask the child to
     * layout given an exact size.
     *
     * @param spec The requirements for this view
     * @param padding The padding of this view for the current dimension and
     *        margins, if applicable
     * @param childDimension How big the child wants to be in the current
     *        dimension
     * @return a MeasureSpec integer for the child
     */
    public static int getChildMeasureSpec(int spec, int padding, int childDimension) {
        int specMode = MeasureSpec.getMode(spec);
        int specSize = MeasureSpec.getSize(spec);

        int size = Math.max(0, specSize - padding);

        int resultSize = 0;
        int resultMode = 0;

        switch (specMode) {
        // Parent has imposed an exact size on us
        case MeasureSpec.EXACTLY:
            if (childDimension >= 0) {
                resultSize = childDimension;
                resultMode = MeasureSpec.EXACTLY;
            } else if (childDimension == LayoutParams.MATCH_PARENT) {
                // Child wants to be our size. So be it.
                resultSize = size;
                resultMode = MeasureSpec.EXACTLY;
            } else if (childDimension == LayoutParams.WRAP_CONTENT) {
                // Child wants to determine its own size. It can't be
                // bigger than us.
                resultSize = size;
                resultMode = MeasureSpec.AT_MOST;
            }
            break;

        // Parent has imposed a maximum size on us
        case MeasureSpec.AT_MOST:
            if (childDimension >= 0) {
                // Child wants a specific size... so be it
                resultSize = childDimension;
                resultMode = MeasureSpec.EXACTLY;
            } else if (childDimension == LayoutParams.MATCH_PARENT) {
                // Child wants to be our size, but our size is not fixed.
                // Constrain child to not be bigger than us.
                resultSize = size;
                resultMode = MeasureSpec.AT_MOST;
            } else if (childDimension == LayoutParams.WRAP_CONTENT) {
                // Child wants to determine its own size. It can't be
                // bigger than us.
                resultSize = size;
                resultMode = MeasureSpec.AT_MOST;
            }
            break;

        // Parent asked to see how big we want to be
        case MeasureSpec.UNSPECIFIED:
            if (childDimension >= 0) {
                // Child wants a specific size... let him have it
                resultSize = childDimension;
                resultMode = MeasureSpec.EXACTLY;
            } else if (childDimension == LayoutParams.MATCH_PARENT) {
                // Child wants to be our size... find out how big it should
                // be
                resultSize = 0;
                resultMode = MeasureSpec.UNSPECIFIED;
            } else if (childDimension == LayoutParams.WRAP_CONTENT) {
                // Child wants to determine its own size.... find out how
                // big it should be
                resultSize = 0;
                resultMode = MeasureSpec.UNSPECIFIED;
            }
            break;
        }
        return MeasureSpec.makeMeasureSpec(resultSize, resultMode);
    }


    /**
     * Removes any pending animations for views that have been removed. Call
     * this if you don't want animations for exiting views to stack up.
     */
    public void clearDisappearingChildren() {
        if (mDisappearingChildren != null) {
            mDisappearingChildren.clear();
        }
    }

    /**
     * Add a view which is removed from mChildren but still needs animation
     *
     * @param v View to add
     */
    private void addDisappearingView(View v) {
        ArrayList<View> disappearingChildren = mDisappearingChildren;

        if (disappearingChildren == null) {
            disappearingChildren = mDisappearingChildren = new ArrayList<View>();
        }

        disappearingChildren.add(v);
    }

    /**
     * Cleanup a view when its animation is done. This may mean removing it from
     * the list of disappearing views.
     *
     * @param view The view whose animation has finished
     * @param animation The animation, cannot be null
     */
    private void finishAnimatingView(final View view, Animation animation) {
        final ArrayList<View> disappearingChildren = mDisappearingChildren;
        if (disappearingChildren != null) {
            if (disappearingChildren.contains(view)) {
                disappearingChildren.remove(view);

                if (view.mAttachInfo != null) {
                    view.dispatchDetachedFromWindow();
                }

                view.clearAnimation();
                mGroupFlags |= FLAG_INVALIDATE_REQUIRED;
            }
        }

        if (animation != null && !animation.getFillAfter()) {
            view.clearAnimation();
        }

        if ((view.mPrivateFlags & ANIMATION_STARTED) == ANIMATION_STARTED) {
            view.onAnimationEnd();
            // Should be performed by onAnimationEnd() but this avoid an infinite loop,
            // so we'd rather be safe than sorry
            view.mPrivateFlags &= ~ANIMATION_STARTED;
            // Draw one more frame after the animation is done
            mGroupFlags |= FLAG_INVALIDATE_REQUIRED;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean gatherTransparentRegion(Region region) {
        // If no transparent regions requested, we are always opaque.
        final boolean meOpaque = (mPrivateFlags & View.REQUEST_TRANSPARENT_REGIONS) == 0;
        if (meOpaque && region == null) {
            // The caller doesn't care about the region, so stop now.
            return true;
        }
        super.gatherTransparentRegion(region);
        final View[] children = mChildren;
        final int count = mChildrenCount;
        boolean noneOfTheChildrenAreTransparent = true;
        for (int i = 0; i < count; i++) {
            final View child = children[i];
            if ((child.mViewFlags & VISIBILITY_MASK) == VISIBLE || child.getAnimation() != null) {
                if (!child.gatherTransparentRegion(region)) {
                    noneOfTheChildrenAreTransparent = false;
                }
            }
        }
        return meOpaque || noneOfTheChildrenAreTransparent;
    }

    /**
     * {@inheritDoc}
     */
    public void requestTransparentRegion(View child) {
        if (child != null) {
            child.mPrivateFlags |= View.REQUEST_TRANSPARENT_REGIONS;
            if (mParent != null) {
                mParent.requestTransparentRegion(this);
            }
        }
    }


    @Override
    protected boolean fitSystemWindows(Rect insets) {
        boolean done = super.fitSystemWindows(insets);
        if (!done) {
            final int count = mChildrenCount;
            final View[] children = mChildren;
            for (int i = 0; i < count; i++) {
                done = children[i].fitSystemWindows(insets);
                if (done) {
                    break;
                }
            }
        }
        return done;
    }

    /**
     * Returns the animation listener to which layout animation events are
     * sent.
     *
     * @return an {@link android.view.animation.Animation.AnimationListener}
     */
    public Animation.AnimationListener getLayoutAnimationListener() {
        return mAnimationListener;
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();

        if ((mGroupFlags & FLAG_NOTIFY_CHILDREN_ON_DRAWABLE_STATE_CHANGE) != 0) {
            if ((mGroupFlags & FLAG_ADD_STATES_FROM_CHILDREN) != 0) {
                throw new IllegalStateException("addStateFromChildren cannot be enabled if a"
                        + " child has duplicateParentState set to true");
            }

            final View[] children = mChildren;
            final int count = mChildrenCount;

            for (int i = 0; i < count; i++) {
                final View child = children[i];
                if ((child.mViewFlags & DUPLICATE_PARENT_STATE) != 0) {
                    child.refreshDrawableState();
                }
            }
        }
    }

    @Override
    protected int[] onCreateDrawableState(int extraSpace) {
        if ((mGroupFlags & FLAG_ADD_STATES_FROM_CHILDREN) == 0) {
            return super.onCreateDrawableState(extraSpace);
        }

        int need = 0;
        int n = getChildCount();
        for (int i = 0; i < n; i++) {
            int[] childState = getChildAt(i).getDrawableState();

            if (childState != null) {
                need += childState.length;
            }
        }

        int[] state = super.onCreateDrawableState(extraSpace + need);

        for (int i = 0; i < n; i++) {
            int[] childState = getChildAt(i).getDrawableState();

            if (childState != null) {
                state = mergeDrawableStates(state, childState);
            }
        }

        return state;
    }

    /**
     * Sets whether this ViewGroup's drawable states also include
     * its children's drawable states.  This is used, for example, to
     * make a group appear to be focused when its child EditText or button
     * is focused.
     */
    public void setAddStatesFromChildren(boolean addsStates) {
        if (addsStates) {
            mGroupFlags |= FLAG_ADD_STATES_FROM_CHILDREN;
        } else {
            mGroupFlags &= ~FLAG_ADD_STATES_FROM_CHILDREN;
        }

        refreshDrawableState();
    }

    /**
     * Returns whether this ViewGroup's drawable states also include
     * its children's drawable states.  This is used, for example, to
     * make a group appear to be focused when its child EditText or button
     * is focused.
     */
    public boolean addStatesFromChildren() {
        return (mGroupFlags & FLAG_ADD_STATES_FROM_CHILDREN) != 0;
    }

    /**
     * If {link #addStatesFromChildren} is true, refreshes this group's
     * drawable state (to include the states from its children).
     */
    public void childDrawableStateChanged(View child) {
        if ((mGroupFlags & FLAG_ADD_STATES_FROM_CHILDREN) != 0) {
            refreshDrawableState();
        }
    }

    /**
     * Specifies the animation listener to which layout animation events must
     * be sent. Only
     * {@link android.view.animation.Animation.AnimationListener#onAnimationStart(Animation)}
     * and
     * {@link android.view.animation.Animation.AnimationListener#onAnimationEnd(Animation)}
     * are invoked.
     *
     * @param animationListener the layout animation listener
     */
    public void setLayoutAnimationListener(Animation.AnimationListener animationListener) {
        mAnimationListener = animationListener;
    }

    /**
     * LayoutParams are used by views to tell their parents how they want to be
     * laid out. See
     * {@link android.R.styleable#ViewGroup_Layout ViewGroup Layout Attributes}
     * for a list of all child view attributes that this class supports.
     *
     * <p>
     * The base LayoutParams class just describes how big the view wants to be
     * for both width and height. For each dimension, it can specify one of:
     * <ul>
     * <li>FILL_PARENT (renamed MATCH_PARENT in API Level 8 and higher), which
     * means that the view wants to be as big as its parent (minus padding)
     * <li> WRAP_CONTENT, which means that the view wants to be just big enough
     * to enclose its content (plus padding)
     * <li> an exact number
     * </ul>
     * There are subclasses of LayoutParams for different subclasses of
     * ViewGroup. For example, AbsoluteLayout has its own subclass of
     * LayoutParams which adds an X and Y value.
     *
     * @attr ref android.R.styleable#ViewGroup_Layout_layout_height
     * @attr ref android.R.styleable#ViewGroup_Layout_layout_width
     */
    public static class LayoutParams {
        /**
         * Special value for the height or width requested by a View.
         * FILL_PARENT means that the view wants to be as big as its parent,
         * minus the parent's padding, if any. This value is deprecated
         * starting in API Level 8 and replaced by {@link #MATCH_PARENT}.
         */
        @SuppressWarnings({"UnusedDeclaration"})
        @Deprecated
        public static final int FILL_PARENT = -1;

        /**
         * Special value for the height or width requested by a View.
         * MATCH_PARENT means that the view wants to be as big as its parent,
         * minus the parent's padding, if any. Introduced in API Level 8.
         */
        public static final int MATCH_PARENT = -1;

        /**
         * Special value for the height or width requested by a View.
         * WRAP_CONTENT means that the view wants to be just large enough to fit
         * its own internal content, taking its own padding into account.
         */
        public static final int WRAP_CONTENT = -2;

        /**
         * Information about how wide the view wants to be. Can be one of the
         * constants FILL_PARENT (replaced by MATCH_PARENT ,
         * in API Level 8) or WRAP_CONTENT. or an exact size.
         */
        @ViewDebug.ExportedProperty(category = "layout", mapping = {
            @ViewDebug.IntToString(from = MATCH_PARENT, to = "MATCH_PARENT"),
            @ViewDebug.IntToString(from = WRAP_CONTENT, to = "WRAP_CONTENT")
        })
        public int width;

        /**
         * Information about how tall the view wants to be. Can be one of the
         * constants FILL_PARENT (replaced by MATCH_PARENT ,
         * in API Level 8) or WRAP_CONTENT. or an exact size.
         */
        @ViewDebug.ExportedProperty(category = "layout", mapping = {
            @ViewDebug.IntToString(from = MATCH_PARENT, to = "MATCH_PARENT"),
            @ViewDebug.IntToString(from = WRAP_CONTENT, to = "WRAP_CONTENT")
        })
        public int height;

        /**
         * Used to animate layouts.
         */
        public LayoutAnimationController.AnimationParameters layoutAnimationParameters;

        /**
         * Creates a new set of layout parameters. The values are extracted from
         * the supplied attributes set and context. The XML attributes mapped
         * to this set of layout parameters are:
         *
         * <ul>
         *   <li><code>layout_width</code>: the width, either an exact value,
         *   {@link #WRAP_CONTENT}, or {@link #FILL_PARENT} (replaced by
         *   {@link #MATCH_PARENT} in API Level 8)</li>
         *   <li><code>layout_height</code>: the height, either an exact value,
         *   {@link #WRAP_CONTENT}, or {@link #FILL_PARENT} (replaced by
         *   {@link #MATCH_PARENT} in API Level 8)</li>
         * </ul>
         *
         * @param c the application environment
         * @param attrs the set of attributes from which to extract the layout
         *              parameters' values
         */
        public LayoutParams(Context c, AttributeSet attrs) {
            TypedArray a = c.obtainStyledAttributes(attrs, R.styleable.ViewGroup_Layout);
            setBaseAttributes(a,
                    R.styleable.ViewGroup_Layout_layout_width,
                    R.styleable.ViewGroup_Layout_layout_height);
            a.recycle();
        }

        /**
         * Creates a new set of layout parameters with the specified width
         * and height.
         *
         * @param width the width, either {@link #WRAP_CONTENT},
         *        {@link #FILL_PARENT} (replaced by {@link #MATCH_PARENT} in
         *        API Level 8), or a fixed size in pixels
         * @param height the height, either {@link #WRAP_CONTENT},
         *        {@link #FILL_PARENT} (replaced by {@link #MATCH_PARENT} in
         *        API Level 8), or a fixed size in pixels
         */
        public LayoutParams(int width, int height) {
            this.width = width;
            this.height = height;
        }

        /**
         * Copy constructor. Clones the width and height values of the source.
         *
         * @param source The layout params to copy from.
         */
        public LayoutParams(LayoutParams source) {
            this.width = source.width;
            this.height = source.height;
        }

        /**
         * Used internally by MarginLayoutParams.
         * @hide
         */
        LayoutParams() {
        }

        /**
         * Extracts the layout parameters from the supplied attributes.
         *
         * @param a the style attributes to extract the parameters from
         * @param widthAttr the identifier of the width attribute
         * @param heightAttr the identifier of the height attribute
         */
        protected void setBaseAttributes(TypedArray a, int widthAttr, int heightAttr) {
            width = a.getLayoutDimension(widthAttr, "layout_width");
            height = a.getLayoutDimension(heightAttr, "layout_height");
        }

        /**
         * Returns a String representation of this set of layout parameters.
         *
         * @param output the String to prepend to the internal representation
         * @return a String with the following format: output +
         *         "ViewGroup.LayoutParams={ width=WIDTH, height=HEIGHT }"
         *
         * @hide
         */
        public String debug(String output) {
            return output + "ViewGroup.LayoutParams={ width="
                    + sizeToString(width) + ", height=" + sizeToString(height) + " }";
        }

        /**
         * Converts the specified size to a readable String.
         *
         * @param size the size to convert
         * @return a String instance representing the supplied size
         *
         * @hide
         */
        protected static String sizeToString(int size) {
            if (size == WRAP_CONTENT) {
                return "wrap-content";
            }
            if (size == MATCH_PARENT) {
                return "match-parent";
            }
            return String.valueOf(size);
        }
    }

    /**
     * Per-child layout information for layouts that support margins.
     * See
     * {@link android.R.styleable#ViewGroup_MarginLayout ViewGroup Margin Layout Attributes}
     * for a list of all child view attributes that this class supports.
     */
    public static class MarginLayoutParams extends ViewGroup.LayoutParams {
        /**
         * The left margin in pixels of the child.
         */
        @ViewDebug.ExportedProperty(category = "layout")
        public int leftMargin;

        /**
         * The top margin in pixels of the child.
         */
        @ViewDebug.ExportedProperty(category = "layout")
        public int topMargin;

        /**
         * The right margin in pixels of the child.
         */
        @ViewDebug.ExportedProperty(category = "layout")
        public int rightMargin;

        /**
         * The bottom margin in pixels of the child.
         */
        @ViewDebug.ExportedProperty(category = "layout")
        public int bottomMargin;

        /**
         * Creates a new set of layout parameters. The values are extracted from
         * the supplied attributes set and context.
         *
         * @param c the application environment
         * @param attrs the set of attributes from which to extract the layout
         *              parameters' values
         */
        public MarginLayoutParams(Context c, AttributeSet attrs) {
            super();

            TypedArray a = c.obtainStyledAttributes(attrs, R.styleable.ViewGroup_MarginLayout);
            setBaseAttributes(a,
                    R.styleable.ViewGroup_MarginLayout_layout_width,
                    R.styleable.ViewGroup_MarginLayout_layout_height);

            int margin = a.getDimensionPixelSize(
                    com.android.internal.R.styleable.ViewGroup_MarginLayout_layout_margin, -1);
            if (margin >= 0) {
                leftMargin = margin;
                topMargin = margin;
                rightMargin= margin;
                bottomMargin = margin;
            } else {
                leftMargin = a.getDimensionPixelSize(
                        R.styleable.ViewGroup_MarginLayout_layout_marginLeft, 0);
                topMargin = a.getDimensionPixelSize(
                        R.styleable.ViewGroup_MarginLayout_layout_marginTop, 0);
                rightMargin = a.getDimensionPixelSize(
                        R.styleable.ViewGroup_MarginLayout_layout_marginRight, 0);
                bottomMargin = a.getDimensionPixelSize(
                        R.styleable.ViewGroup_MarginLayout_layout_marginBottom, 0);
            }

            a.recycle();
        }

        /**
         * {@inheritDoc}
         */
        public MarginLayoutParams(int width, int height) {
            super(width, height);
        }

        /**
         * Copy constructor. Clones the width, height and margin values of the source.
         *
         * @param source The layout params to copy from.
         */
        public MarginLayoutParams(MarginLayoutParams source) {
            this.width = source.width;
            this.height = source.height;

            this.leftMargin = source.leftMargin;
            this.topMargin = source.topMargin;
            this.rightMargin = source.rightMargin;
            this.bottomMargin = source.bottomMargin;
        }

        /**
         * {@inheritDoc}
         */
        public MarginLayoutParams(LayoutParams source) {
            super(source);
        }

        /**
         * Sets the margins, in pixels.
         *
         * @param left the left margin size
         * @param top the top margin size
         * @param right the right margin size
         * @param bottom the bottom margin size
         *
         * @attr ref android.R.styleable#ViewGroup_MarginLayout_layout_marginLeft
         * @attr ref android.R.styleable#ViewGroup_MarginLayout_layout_marginTop
         * @attr ref android.R.styleable#ViewGroup_MarginLayout_layout_marginRight
         * @attr ref android.R.styleable#ViewGroup_MarginLayout_layout_marginBottom
         */
        public void setMargins(int left, int top, int right, int bottom) {
            leftMargin = left;
            topMargin = top;
            rightMargin = right;
            bottomMargin = bottom;
        }
    }
}
