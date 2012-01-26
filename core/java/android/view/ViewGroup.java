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

import android.animation.LayoutTransition;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Build;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.view.animation.Transformation;

import com.android.internal.R;
import com.android.internal.util.Predicate;

import java.util.ArrayList;
import java.util.HashSet;

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
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about creating user interface layouts, read the
 * <a href="{@docRoot}guide/topics/ui/declaring-layout.html">XML Layouts</a> developer
 * guide.</p></div>
 *
 * @attr ref android.R.styleable#ViewGroup_clipChildren
 * @attr ref android.R.styleable#ViewGroup_clipToPadding
 * @attr ref android.R.styleable#ViewGroup_layoutAnimation
 * @attr ref android.R.styleable#ViewGroup_animationCache
 * @attr ref android.R.styleable#ViewGroup_persistentDrawingCache
 * @attr ref android.R.styleable#ViewGroup_alwaysDrawnWithCache
 * @attr ref android.R.styleable#ViewGroup_addStatesFromChildren
 * @attr ref android.R.styleable#ViewGroup_descendantFocusability
 * @attr ref android.R.styleable#ViewGroup_animateLayoutChanges
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

    /**
     * A Transformation used when drawing children, to
     * apply on the child being drawn.
     */
    private final Transformation mChildTransformation = new Transformation();

    /**
     * Used to track the current invalidation region.
     */
    private RectF mInvalidateRegion;

    /**
     * A Transformation used to calculate a correct
     * invalidation area when the application is autoscaled.
     */
    private Transformation mInvalidationTransformation;

    // View currently under an ongoing drag
    private View mCurrentDragView;

    // Metadata about the ongoing drag
    private DragEvent mCurrentDrag;
    private HashSet<View> mDragNotifiedChildren;

    // Does this group have a child that can accept the current drag payload?
    private boolean mChildAcceptsDrag;

    // Used during drag dispatch
    private final PointF mLocalPoint = new PointF();

    // Layout animation
    private LayoutAnimationController mLayoutAnimationController;
    private Animation.AnimationListener mAnimationListener;

    // First touch target in the linked list of touch targets.
    private TouchTarget mFirstTouchTarget;

    // For debugging only.  You can see these in hierarchyviewer.
    @SuppressWarnings({"FieldCanBeLocal", "UnusedDeclaration"})
    @ViewDebug.ExportedProperty(category = "events")
    private long mLastTouchDownTime;
    @ViewDebug.ExportedProperty(category = "events")
    private int mLastTouchDownIndex = -1;
    @SuppressWarnings({"FieldCanBeLocal", "UnusedDeclaration"})
    @ViewDebug.ExportedProperty(category = "events")
    private float mLastTouchDownX;
    @SuppressWarnings({"FieldCanBeLocal", "UnusedDeclaration"})
    @ViewDebug.ExportedProperty(category = "events")
    private float mLastTouchDownY;

    // First hover target in the linked list of hover targets.
    // The hover targets are children which have received ACTION_HOVER_ENTER.
    // They might not have actually handled the hover event, but we will
    // continue sending hover events to them as long as the pointer remains over
    // their bounds and the view group does not intercept hover.
    private HoverTarget mFirstHoverTarget;

    // True if the view group itself received a hover event.
    // It might not have actually handled the hover event.
    private boolean mHoveredSelf;

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
     * When set, this ViewGroup will split MotionEvents to multiple child Views when appropriate.
     */
    private static final int FLAG_SPLIT_MOTION_EVENTS = 0x200000;

    /**
     * When set, this ViewGroup will not dispatch onAttachedToWindow calls
     * to children when adding new views. This is used to prevent multiple
     * onAttached calls when a ViewGroup adds children in its own onAttached method.
     */
    private static final int FLAG_PREVENT_DISPATCH_ATTACHED_TO_WINDOW = 0x400000;

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

    private boolean mLayoutSuppressed = false;

    private int mChildrenCount;

    private static final int ARRAY_INITIAL_CAPACITY = 12;
    private static final int ARRAY_CAPACITY_INCREMENT = 12;

    // Used to draw cached views
    private Paint mCachePaint;

    // Used to animate add/remove changes in layout
    private LayoutTransition mTransition;

    // The set of views that are currently being transitioned. This list is used to track views
    // being removed that should not actually be removed from the parent yet because they are
    // being animated.
    private ArrayList<View> mTransitioningViews;

    // List of children changing visibility. This is used to potentially keep rendering
    // views during a transition when they otherwise would have become gone/invisible
    private ArrayList<View> mVisibilityChangingChildren;

    // Indicates whether this container will use its children layers to draw
    @ViewDebug.ExportedProperty(category = "drawing")
    private boolean mDrawLayers = true;

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

        if (mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.HONEYCOMB) {
            mGroupFlags |= FLAG_SPLIT_MOTION_EVENTS;
        }

        setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);

        mChildren = new View[ARRAY_INITIAL_CAPACITY];
        mChildrenCount = 0;

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
                case R.styleable.ViewGroup_splitMotionEvents:
                    setMotionEventSplittingEnabled(a.getBoolean(attr, false));
                    break;
                case R.styleable.ViewGroup_animateLayoutChanges:
                    boolean animateLayoutChanges = a.getBoolean(attr, false);
                    if (animateLayoutChanges) {
                        setLayoutTransition(new LayoutTransition());
                    }
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
                // an ancestor of v; this will get checked for at ViewAncestor
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
     * {@inheritDoc}
     */
    public ActionMode startActionModeForChild(View originalView, ActionMode.Callback callback) {
        return mParent != null ? mParent.startActionModeForChild(originalView, callback) : null;
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
    public boolean requestSendAccessibilityEvent(View child, AccessibilityEvent event) {
        ViewParent parent = getParent();
        if (parent == null) {
            return false;
        }
        final boolean propagate = onRequestSendAccessibilityEvent(child, event);
        //noinspection SimplifiableIfStatement
        if (!propagate) {
            return false;
        }
        return parent.requestSendAccessibilityEvent(this, event);
    }

    /**
     * Called when a child has requested sending an {@link AccessibilityEvent} and
     * gives an opportunity to its parent to augment the event.
     * <p>
     * If an {@link android.view.View.AccessibilityDelegate} has been specified via calling
     * {@link android.view.View#setAccessibilityDelegate(android.view.View.AccessibilityDelegate)} its
     * {@link android.view.View.AccessibilityDelegate#onRequestSendAccessibilityEvent(ViewGroup, View, AccessibilityEvent)}
     * is responsible for handling this call.
     * </p>
     *
     * @param child The child which requests sending the event.
     * @param event The event to be sent.
     * @return True if the event should be sent.
     *
     * @see #requestSendAccessibilityEvent(View, AccessibilityEvent)
     */
    public boolean onRequestSendAccessibilityEvent(View child, AccessibilityEvent event) {
        if (mAccessibilityDelegate != null) {
            return mAccessibilityDelegate.onRequestSendAccessibilityEvent(this, child, event);
        } else {
            return onRequestSendAccessibilityEventInternal(child, event);
        }
    }

    /**
     * @see #onRequestSendAccessibilityEvent(View, AccessibilityEvent)
     *
     * Note: Called from the default {@link View.AccessibilityDelegate}.
     */
    boolean onRequestSendAccessibilityEventInternal(View child, AccessibilityEvent event) {
        return true;
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

    @Override
    public void findViewsWithText(ArrayList<View> outViews, CharSequence text, int flags) {
        super.findViewsWithText(outViews, text, flags);
        final int childrenCount = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < childrenCount; i++) {
            View child = children[i];
            if ((child.mViewFlags & VISIBILITY_MASK) == VISIBLE
                    && (child.mPrivateFlags & IS_ROOT_NAMESPACE) == 0) {
                child.findViewsWithText(outViews, text, flags);
            }
        }
    }

    @Override
    View findViewByAccessibilityIdTraversal(int accessibilityId) {
        View foundView = super.findViewByAccessibilityIdTraversal(accessibilityId);
        if (foundView != null) {
            return foundView;
        }
        final int childrenCount = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < childrenCount; i++) {
            View child = children[i];
            foundView = child.findViewByAccessibilityIdTraversal(accessibilityId);
            if (foundView != null) {
                return foundView;
            }
        }
        return null;
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
     * @hide
     * @param child
     * @param visibility
     */
    protected void onChildVisibilityChanged(View child, int visibility) {
        if (mTransition != null) {
            if (visibility == VISIBLE) {
                mTransition.showChild(this, child);
            } else {
                mTransition.hideChild(this, child);
            }
            if (visibility != VISIBLE) {
                // Only track this on disappearing views - appearing views are already visible
                // and don't need special handling during drawChild()
                if (mVisibilityChangingChildren == null) {
                    mVisibilityChangingChildren = new ArrayList<View>();
                }
                mVisibilityChangingChildren.add(child);
                if (mTransitioningViews != null && mTransitioningViews.contains(child)) {
                    addDisappearingView(child);
                }
            }
        }

        // in all cases, for drags
        if (mCurrentDrag != null) {
            if (visibility == VISIBLE) {
                notifyChildOfDrag(child);
            }
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
        if (mAttachInfo != null && !mAttachInfo.mRecomputeGlobalAttributes) {
            ViewParent parent = mParent;
            if (parent != null) parent.recomputeViewAttributes(this);
        }
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
     *
     * !!! TODO: write real docs
     */
    @Override
    public boolean dispatchDragEvent(DragEvent event) {
        boolean retval = false;
        final float tx = event.mX;
        final float ty = event.mY;

        ViewRootImpl root = getViewRootImpl();

        // Dispatch down the view hierarchy
        switch (event.mAction) {
        case DragEvent.ACTION_DRAG_STARTED: {
            // clear state to recalculate which views we drag over
            mCurrentDragView = null;

            // Set up our tracking of drag-started notifications
            mCurrentDrag = DragEvent.obtain(event);
            if (mDragNotifiedChildren == null) {
                mDragNotifiedChildren = new HashSet<View>();
            } else {
                mDragNotifiedChildren.clear();
            }

            // Now dispatch down to our children, caching the responses
            mChildAcceptsDrag = false;
            final int count = mChildrenCount;
            final View[] children = mChildren;
            for (int i = 0; i < count; i++) {
                final View child = children[i];
                child.mPrivateFlags2 &= ~View.DRAG_MASK;
                if (child.getVisibility() == VISIBLE) {
                    final boolean handled = notifyChildOfDrag(children[i]);
                    if (handled) {
                        mChildAcceptsDrag = true;
                    }
                }
            }

            // Return HANDLED if one of our children can accept the drag
            if (mChildAcceptsDrag) {
                retval = true;
            }
        } break;

        case DragEvent.ACTION_DRAG_ENDED: {
            // Release the bookkeeping now that the drag lifecycle has ended
            if (mDragNotifiedChildren != null) {
                for (View child : mDragNotifiedChildren) {
                    // If a child was notified about an ongoing drag, it's told that it's over
                    child.dispatchDragEvent(event);
                    child.mPrivateFlags2 &= ~View.DRAG_MASK;
                    child.refreshDrawableState();
                }

                mDragNotifiedChildren.clear();
                mCurrentDrag.recycle();
                mCurrentDrag = null;
            }

            // We consider drag-ended to have been handled if one of our children
            // had offered to handle the drag.
            if (mChildAcceptsDrag) {
                retval = true;
            }
        } break;

        case DragEvent.ACTION_DRAG_LOCATION: {
            // Find the [possibly new] drag target
            final View target = findFrontmostDroppableChildAt(event.mX, event.mY, mLocalPoint);

            // If we've changed apparent drag target, tell the view root which view
            // we're over now [for purposes of the eventual drag-recipient-changed
            // notifications to the framework] and tell the new target that the drag
            // has entered its bounds.  The root will see setDragFocus() calls all
            // the way down to the final leaf view that is handling the LOCATION event
            // before reporting the new potential recipient to the framework.
            if (mCurrentDragView != target) {
                root.setDragFocus(target);

                final int action = event.mAction;
                // If we've dragged off of a child view, send it the EXITED message
                if (mCurrentDragView != null) {
                    final View view = mCurrentDragView;
                    event.mAction = DragEvent.ACTION_DRAG_EXITED;
                    view.dispatchDragEvent(event);
                    view.mPrivateFlags2 &= ~View.DRAG_HOVERED;
                    view.refreshDrawableState();
                }
                mCurrentDragView = target;

                // If we've dragged over a new child view, send it the ENTERED message
                if (target != null) {
                    event.mAction = DragEvent.ACTION_DRAG_ENTERED;
                    target.dispatchDragEvent(event);
                    target.mPrivateFlags2 |= View.DRAG_HOVERED;
                    target.refreshDrawableState();
                }
                event.mAction = action;  // restore the event's original state
            }

            // Dispatch the actual drag location notice, localized into its coordinates
            if (target != null) {
                event.mX = mLocalPoint.x;
                event.mY = mLocalPoint.y;

                retval = target.dispatchDragEvent(event);

                event.mX = tx;
                event.mY = ty;
            }
        } break;

        /* Entered / exited dispatch
         *
         * DRAG_ENTERED is not dispatched downwards from ViewGroup.  The reason for this is
         * that we're about to get the corresponding LOCATION event, which we will use to
         * determine which of our children is the new target; at that point we will
         * push a DRAG_ENTERED down to the new target child [which may itself be a ViewGroup].
         *
         * DRAG_EXITED *is* dispatched all the way down immediately: once we know the
         * drag has left this ViewGroup, we know by definition that every contained subview
         * is also no longer under the drag point.
         */

        case DragEvent.ACTION_DRAG_EXITED: {
            if (mCurrentDragView != null) {
                final View view = mCurrentDragView;
                view.dispatchDragEvent(event);
                view.mPrivateFlags2 &= ~View.DRAG_HOVERED;
                view.refreshDrawableState();

                mCurrentDragView = null;
            }
        } break;

        case DragEvent.ACTION_DROP: {
            if (ViewDebug.DEBUG_DRAG) Log.d(View.VIEW_LOG_TAG, "Drop event: " + event);
            View target = findFrontmostDroppableChildAt(event.mX, event.mY, mLocalPoint);
            if (target != null) {
                if (ViewDebug.DEBUG_DRAG) Log.d(View.VIEW_LOG_TAG, "   dispatch drop to " + target);
                event.mX = mLocalPoint.x;
                event.mY = mLocalPoint.y;
                retval = target.dispatchDragEvent(event);
                event.mX = tx;
                event.mY = ty;
            } else {
                if (ViewDebug.DEBUG_DRAG) {
                    Log.d(View.VIEW_LOG_TAG, "   not dropped on an accepting view");
                }
            }
        } break;
        }

        // If none of our children could handle the event, try here
        if (!retval) {
            // Call up to the View implementation that dispatches to installed listeners
            retval = super.dispatchDragEvent(event);
        }
        return retval;
    }

    // Find the frontmost child view that lies under the given point, and calculate
    // the position within its own local coordinate system.
    View findFrontmostDroppableChildAt(float x, float y, PointF outLocalPoint) {
        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i = count - 1; i >= 0; i--) {
            final View child = children[i];
            if (!child.canAcceptDrag()) {
                continue;
            }

            if (isTransformedTouchPointInView(x, y, child, outLocalPoint)) {
                return child;
            }
        }
        return null;
    }

    boolean notifyChildOfDrag(View child) {
        if (ViewDebug.DEBUG_DRAG) {
            Log.d(View.VIEW_LOG_TAG, "Sending drag-started to view: " + child);
        }

        boolean canAccept = false;
        if (! mDragNotifiedChildren.contains(child)) {
            mDragNotifiedChildren.add(child);
            canAccept = child.dispatchDragEvent(mCurrentDrag);
            if (canAccept && !child.canAcceptDrag()) {
                child.mPrivateFlags2 |= View.DRAG_CAN_ACCEPT;
                child.refreshDrawableState();
            }
        }
        return canAccept;
    }

    @Override
    public void dispatchSystemUiVisibilityChanged(int visible) {
        super.dispatchSystemUiVisibilityChanged(visible);

        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i=0; i <count; i++) {
            final View child = children[i];
            child.dispatchSystemUiVisibilityChanged(visible);
        }
    }

    @Override
    void updateLocalSystemUiVisibility(int localValue, int localChanges) {
        super.updateLocalSystemUiVisibility(localValue, localChanges);

        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i=0; i <count; i++) {
            final View child = children[i];
            child.updateLocalSystemUiVisibility(localValue, localChanges);
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
        if (mInputEventConsistencyVerifier != null) {
            mInputEventConsistencyVerifier.onKeyEvent(event, 1);
        }

        if ((mPrivateFlags & (FOCUSED | HAS_BOUNDS)) == (FOCUSED | HAS_BOUNDS)) {
            if (super.dispatchKeyEvent(event)) {
                return true;
            }
        } else if (mFocused != null && (mFocused.mPrivateFlags & HAS_BOUNDS) == HAS_BOUNDS) {
            if (mFocused.dispatchKeyEvent(event)) {
                return true;
            }
        }

        if (mInputEventConsistencyVerifier != null) {
            mInputEventConsistencyVerifier.onUnhandledEvent(event, 1);
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
        if (mInputEventConsistencyVerifier != null) {
            mInputEventConsistencyVerifier.onTrackballEvent(event, 1);
        }

        if ((mPrivateFlags & (FOCUSED | HAS_BOUNDS)) == (FOCUSED | HAS_BOUNDS)) {
            if (super.dispatchTrackballEvent(event)) {
                return true;
            }
        } else if (mFocused != null && (mFocused.mPrivateFlags & HAS_BOUNDS) == HAS_BOUNDS) {
            if (mFocused.dispatchTrackballEvent(event)) {
                return true;
            }
        }

        if (mInputEventConsistencyVerifier != null) {
            mInputEventConsistencyVerifier.onUnhandledEvent(event, 1);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings({"ConstantConditions"})
    @Override
    protected boolean dispatchHoverEvent(MotionEvent event) {
        final int action = event.getAction();

        // First check whether the view group wants to intercept the hover event.
        final boolean interceptHover = onInterceptHoverEvent(event);
        event.setAction(action); // restore action in case it was changed

        MotionEvent eventNoHistory = event;
        boolean handled = false;

        // Send events to the hovered children and build a new list of hover targets until
        // one is found that handles the event.
        HoverTarget firstOldHoverTarget = mFirstHoverTarget;
        mFirstHoverTarget = null;
        if (!interceptHover && action != MotionEvent.ACTION_HOVER_EXIT) {
            final float x = event.getX();
            final float y = event.getY();
            final int childrenCount = mChildrenCount;
            if (childrenCount != 0) {
                final View[] children = mChildren;
                HoverTarget lastHoverTarget = null;
                for (int i = childrenCount - 1; i >= 0; i--) {
                    final View child = children[i];
                    if (!canViewReceivePointerEvents(child)
                            || !isTransformedTouchPointInView(x, y, child, null)) {
                        continue;
                    }

                    // Obtain a hover target for this child.  Dequeue it from the
                    // old hover target list if the child was previously hovered.
                    HoverTarget hoverTarget = firstOldHoverTarget;
                    final boolean wasHovered;
                    for (HoverTarget predecessor = null; ;) {
                        if (hoverTarget == null) {
                            hoverTarget = HoverTarget.obtain(child);
                            wasHovered = false;
                            break;
                        }

                        if (hoverTarget.child == child) {
                            if (predecessor != null) {
                                predecessor.next = hoverTarget.next;
                            } else {
                                firstOldHoverTarget = hoverTarget.next;
                            }
                            hoverTarget.next = null;
                            wasHovered = true;
                            break;
                        }

                        predecessor = hoverTarget;
                        hoverTarget = hoverTarget.next;
                    }

                    // Enqueue the hover target onto the new hover target list.
                    if (lastHoverTarget != null) {
                        lastHoverTarget.next = hoverTarget;
                    } else {
                        lastHoverTarget = hoverTarget;
                        mFirstHoverTarget = hoverTarget;
                    }

                    // Dispatch the event to the child.
                    if (action == MotionEvent.ACTION_HOVER_ENTER) {
                        if (!wasHovered) {
                            // Send the enter as is.
                            handled |= dispatchTransformedGenericPointerEvent(
                                    event, child); // enter
                        }
                    } else if (action == MotionEvent.ACTION_HOVER_MOVE) {
                        if (!wasHovered) {
                            // Synthesize an enter from a move.
                            eventNoHistory = obtainMotionEventNoHistoryOrSelf(eventNoHistory);
                            eventNoHistory.setAction(MotionEvent.ACTION_HOVER_ENTER);
                            handled |= dispatchTransformedGenericPointerEvent(
                                    eventNoHistory, child); // enter
                            eventNoHistory.setAction(action);

                            handled |= dispatchTransformedGenericPointerEvent(
                                    eventNoHistory, child); // move
                        } else {
                            // Send the move as is.
                            handled |= dispatchTransformedGenericPointerEvent(event, child);
                        }
                    }
                    if (handled) {
                        break;
                    }
                }
            }
        }

        // Send exit events to all previously hovered children that are no longer hovered.
        while (firstOldHoverTarget != null) {
            final View child = firstOldHoverTarget.child;

            // Exit the old hovered child.
            if (action == MotionEvent.ACTION_HOVER_EXIT) {
                // Send the exit as is.
                handled |= dispatchTransformedGenericPointerEvent(
                        event, child); // exit
            } else {
                // Synthesize an exit from a move or enter.
                // Ignore the result because hover focus has moved to a different view.
                if (action == MotionEvent.ACTION_HOVER_MOVE) {
                    dispatchTransformedGenericPointerEvent(
                            event, child); // move
                }
                eventNoHistory = obtainMotionEventNoHistoryOrSelf(eventNoHistory);
                eventNoHistory.setAction(MotionEvent.ACTION_HOVER_EXIT);
                dispatchTransformedGenericPointerEvent(
                        eventNoHistory, child); // exit
                eventNoHistory.setAction(action);
            }

            final HoverTarget nextOldHoverTarget = firstOldHoverTarget.next;
            firstOldHoverTarget.recycle();
            firstOldHoverTarget = nextOldHoverTarget;
        }

        // Send events to the view group itself if no children have handled it.
        boolean newHoveredSelf = !handled;
        if (newHoveredSelf == mHoveredSelf) {
            if (newHoveredSelf) {
                // Send event to the view group as before.
                handled |= super.dispatchHoverEvent(event);
            }
        } else {
            if (mHoveredSelf) {
                // Exit the view group.
                if (action == MotionEvent.ACTION_HOVER_EXIT) {
                    // Send the exit as is.
                    handled |= super.dispatchHoverEvent(event); // exit
                } else {
                    // Synthesize an exit from a move or enter.
                    // Ignore the result because hover focus is moving to a different view.
                    if (action == MotionEvent.ACTION_HOVER_MOVE) {
                        super.dispatchHoverEvent(event); // move
                    }
                    eventNoHistory = obtainMotionEventNoHistoryOrSelf(eventNoHistory);
                    eventNoHistory.setAction(MotionEvent.ACTION_HOVER_EXIT);
                    super.dispatchHoverEvent(eventNoHistory); // exit
                    eventNoHistory.setAction(action);
                }
                mHoveredSelf = false;
            }

            if (newHoveredSelf) {
                // Enter the view group.
                if (action == MotionEvent.ACTION_HOVER_ENTER) {
                    // Send the enter as is.
                    handled |= super.dispatchHoverEvent(event); // enter
                    mHoveredSelf = true;
                } else if (action == MotionEvent.ACTION_HOVER_MOVE) {
                    // Synthesize an enter from a move.
                    eventNoHistory = obtainMotionEventNoHistoryOrSelf(eventNoHistory);
                    eventNoHistory.setAction(MotionEvent.ACTION_HOVER_ENTER);
                    handled |= super.dispatchHoverEvent(eventNoHistory); // enter
                    eventNoHistory.setAction(action);

                    handled |= super.dispatchHoverEvent(eventNoHistory); // move
                    mHoveredSelf = true;
                }
            }
        }

        // Recycle the copy of the event that we made.
        if (eventNoHistory != event) {
            eventNoHistory.recycle();
        }

        // Done.
        return handled;
    }

    /** @hide */
    @Override
    protected boolean hasHoveredChild() {
        return mFirstHoverTarget != null;
    }

    /**
     * Implement this method to intercept hover events before they are handled
     * by child views.
     * <p>
     * This method is called before dispatching a hover event to a child of
     * the view group or to the view group's own {@link #onHoverEvent} to allow
     * the view group a chance to intercept the hover event.
     * This method can also be used to watch all pointer motions that occur within
     * the bounds of the view group even when the pointer is hovering over
     * a child of the view group rather than over the view group itself.
     * </p><p>
     * The view group can prevent its children from receiving hover events by
     * implementing this method and returning <code>true</code> to indicate
     * that it would like to intercept hover events.  The view group must
     * continuously return <code>true</code> from {@link #onInterceptHoverEvent}
     * for as long as it wishes to continue intercepting hover events from
     * its children.
     * </p><p>
     * Interception preserves the invariant that at most one view can be
     * hovered at a time by transferring hover focus from the currently hovered
     * child to the view group or vice-versa as needed.
     * </p><p>
     * If this method returns <code>true</code> and a child is already hovered, then the
     * child view will first receive a hover exit event and then the view group
     * itself will receive a hover enter event in {@link #onHoverEvent}.
     * Likewise, if this method had previously returned <code>true</code> to intercept hover
     * events and instead returns <code>false</code> while the pointer is hovering
     * within the bounds of one of a child, then the view group will first receive a
     * hover exit event in {@link #onHoverEvent} and then the hovered child will
     * receive a hover enter event.
     * </p><p>
     * The default implementation always returns false.
     * </p>
     *
     * @param event The motion event that describes the hover.
     * @return True if the view group would like to intercept the hover event
     * and prevent its children from receiving it.
     */
    public boolean onInterceptHoverEvent(MotionEvent event) {
        return false;
    }

    private static MotionEvent obtainMotionEventNoHistoryOrSelf(MotionEvent event) {
        if (event.getHistorySize() == 0) {
            return event;
        }
        return MotionEvent.obtainNoHistory(event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean dispatchGenericPointerEvent(MotionEvent event) {
        // Send the event to the child under the pointer.
        final int childrenCount = mChildrenCount;
        if (childrenCount != 0) {
            final View[] children = mChildren;
            final float x = event.getX();
            final float y = event.getY();

            for (int i = childrenCount - 1; i >= 0; i--) {
                final View child = children[i];
                if (!canViewReceivePointerEvents(child)
                        || !isTransformedTouchPointInView(x, y, child, null)) {
                    continue;
                }

                if (dispatchTransformedGenericPointerEvent(event, child)) {
                    return true;
                }
            }
        }

        // No child handled the event.  Send it to this view group.
        return super.dispatchGenericPointerEvent(event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean dispatchGenericFocusedEvent(MotionEvent event) {
        // Send the event to the focused child or to this view group if it has focus.
        if ((mPrivateFlags & (FOCUSED | HAS_BOUNDS)) == (FOCUSED | HAS_BOUNDS)) {
            return super.dispatchGenericFocusedEvent(event);
        } else if (mFocused != null && (mFocused.mPrivateFlags & HAS_BOUNDS) == HAS_BOUNDS) {
            return mFocused.dispatchGenericMotionEvent(event);
        }
        return false;
    }

    /**
     * Dispatches a generic pointer event to a child, taking into account
     * transformations that apply to the child.
     *
     * @param event The event to send.
     * @param child The view to send the event to.
     * @return {@code true} if the child handled the event.
     */
    private boolean dispatchTransformedGenericPointerEvent(MotionEvent event, View child) {
        final float offsetX = mScrollX - child.mLeft;
        final float offsetY = mScrollY - child.mTop;

        boolean handled;
        if (!child.hasIdentityMatrix()) {
            MotionEvent transformedEvent = MotionEvent.obtain(event);
            transformedEvent.offsetLocation(offsetX, offsetY);
            transformedEvent.transform(child.getInverseMatrix());
            handled = child.dispatchGenericMotionEvent(transformedEvent);
            transformedEvent.recycle();
        } else {
            event.offsetLocation(offsetX, offsetY);
            handled = child.dispatchGenericMotionEvent(event);
            event.offsetLocation(-offsetX, -offsetY);
        }
        return handled;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (mInputEventConsistencyVerifier != null) {
            mInputEventConsistencyVerifier.onTouchEvent(ev, 1);
        }

        boolean handled = false;
        if (onFilterTouchEventForSecurity(ev)) {
            final int action = ev.getAction();
            final int actionMasked = action & MotionEvent.ACTION_MASK;

            // Handle an initial down.
            if (actionMasked == MotionEvent.ACTION_DOWN) {
                // Throw away all previous state when starting a new touch gesture.
                // The framework may have dropped the up or cancel event for the previous gesture
                // due to an app switch, ANR, or some other state change.
                cancelAndClearTouchTargets(ev);
                resetTouchState();
            }

            // Check for interception.
            final boolean intercepted;
            if (actionMasked == MotionEvent.ACTION_DOWN
                    || mFirstTouchTarget != null) {
                final boolean disallowIntercept = (mGroupFlags & FLAG_DISALLOW_INTERCEPT) != 0;
                if (!disallowIntercept) {
                    intercepted = onInterceptTouchEvent(ev);
                    ev.setAction(action); // restore action in case it was changed
                } else {
                    intercepted = false;
                }
            } else {
                // There are no touch targets and this action is not an initial down
                // so this view group continues to intercept touches.
                intercepted = true;
            }

            // Check for cancelation.
            final boolean canceled = resetCancelNextUpFlag(this)
                    || actionMasked == MotionEvent.ACTION_CANCEL;

            // Update list of touch targets for pointer down, if needed.
            final boolean split = (mGroupFlags & FLAG_SPLIT_MOTION_EVENTS) != 0;
            TouchTarget newTouchTarget = null;
            boolean alreadyDispatchedToNewTouchTarget = false;
            if (!canceled && !intercepted) {
                if (actionMasked == MotionEvent.ACTION_DOWN
                        || (split && actionMasked == MotionEvent.ACTION_POINTER_DOWN)
                        || actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                    final int actionIndex = ev.getActionIndex(); // always 0 for down
                    final int idBitsToAssign = split ? 1 << ev.getPointerId(actionIndex)
                            : TouchTarget.ALL_POINTER_IDS;

                    // Clean up earlier touch targets for this pointer id in case they
                    // have become out of sync.
                    removePointersFromTouchTargets(idBitsToAssign);

                    final int childrenCount = mChildrenCount;
                    if (childrenCount != 0) {
                        // Find a child that can receive the event.
                        // Scan children from front to back.
                        final View[] children = mChildren;
                        final float x = ev.getX(actionIndex);
                        final float y = ev.getY(actionIndex);

                        for (int i = childrenCount - 1; i >= 0; i--) {
                            final View child = children[i];
                            if (!canViewReceivePointerEvents(child)
                                    || !isTransformedTouchPointInView(x, y, child, null)) {
                                continue;
                            }

                            newTouchTarget = getTouchTarget(child);
                            if (newTouchTarget != null) {
                                // Child is already receiving touch within its bounds.
                                // Give it the new pointer in addition to the ones it is handling.
                                newTouchTarget.pointerIdBits |= idBitsToAssign;
                                break;
                            }

                            resetCancelNextUpFlag(child);
                            if (dispatchTransformedTouchEvent(ev, false, child, idBitsToAssign)) {
                                // Child wants to receive touch within its bounds.
                                mLastTouchDownTime = ev.getDownTime();
                                mLastTouchDownIndex = i;
                                mLastTouchDownX = ev.getX();
                                mLastTouchDownY = ev.getY();
                                newTouchTarget = addTouchTarget(child, idBitsToAssign);
                                alreadyDispatchedToNewTouchTarget = true;
                                break;
                            }
                        }
                    }

                    if (newTouchTarget == null && mFirstTouchTarget != null) {
                        // Did not find a child to receive the event.
                        // Assign the pointer to the least recently added target.
                        newTouchTarget = mFirstTouchTarget;
                        while (newTouchTarget.next != null) {
                            newTouchTarget = newTouchTarget.next;
                        }
                        newTouchTarget.pointerIdBits |= idBitsToAssign;
                    }
                }
            }

            // Dispatch to touch targets.
            if (mFirstTouchTarget == null) {
                // No touch targets so treat this as an ordinary view.
                handled = dispatchTransformedTouchEvent(ev, canceled, null,
                        TouchTarget.ALL_POINTER_IDS);
            } else {
                // Dispatch to touch targets, excluding the new touch target if we already
                // dispatched to it.  Cancel touch targets if necessary.
                TouchTarget predecessor = null;
                TouchTarget target = mFirstTouchTarget;
                while (target != null) {
                    final TouchTarget next = target.next;
                    if (alreadyDispatchedToNewTouchTarget && target == newTouchTarget) {
                        handled = true;
                    } else {
                        final boolean cancelChild = resetCancelNextUpFlag(target.child)
                        || intercepted;
                        if (dispatchTransformedTouchEvent(ev, cancelChild,
                                target.child, target.pointerIdBits)) {
                            handled = true;
                        }
                        if (cancelChild) {
                            if (predecessor == null) {
                                mFirstTouchTarget = next;
                            } else {
                                predecessor.next = next;
                            }
                            target.recycle();
                            target = next;
                            continue;
                        }
                    }
                    predecessor = target;
                    target = next;
                }
            }

            // Update list of touch targets for pointer up or cancel, if needed.
            if (canceled
                    || actionMasked == MotionEvent.ACTION_UP
                    || actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                resetTouchState();
            } else if (split && actionMasked == MotionEvent.ACTION_POINTER_UP) {
                final int actionIndex = ev.getActionIndex();
                final int idBitsToRemove = 1 << ev.getPointerId(actionIndex);
                removePointersFromTouchTargets(idBitsToRemove);
            }
        }

        if (!handled && mInputEventConsistencyVerifier != null) {
            mInputEventConsistencyVerifier.onUnhandledEvent(ev, 1);
        }
        return handled;
    }

    /**
     * Resets all touch state in preparation for a new cycle.
     */
    private void resetTouchState() {
        clearTouchTargets();
        resetCancelNextUpFlag(this);
        mGroupFlags &= ~FLAG_DISALLOW_INTERCEPT;
    }

    /**
     * Resets the cancel next up flag.
     * Returns true if the flag was previously set.
     */
    private boolean resetCancelNextUpFlag(View view) {
        if ((view.mPrivateFlags & CANCEL_NEXT_UP_EVENT) != 0) {
            view.mPrivateFlags &= ~CANCEL_NEXT_UP_EVENT;
            return true;
        }
        return false;
    }

    /**
     * Clears all touch targets.
     */
    private void clearTouchTargets() {
        TouchTarget target = mFirstTouchTarget;
        if (target != null) {
            do {
                TouchTarget next = target.next;
                target.recycle();
                target = next;
            } while (target != null);
            mFirstTouchTarget = null;
        }
    }

    /**
     * Cancels and clears all touch targets.
     */
    private void cancelAndClearTouchTargets(MotionEvent event) {
        if (mFirstTouchTarget != null) {
            boolean syntheticEvent = false;
            if (event == null) {
                final long now = SystemClock.uptimeMillis();
                event = MotionEvent.obtain(now, now,
                        MotionEvent.ACTION_CANCEL, 0.0f, 0.0f, 0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                syntheticEvent = true;
            }

            for (TouchTarget target = mFirstTouchTarget; target != null; target = target.next) {
                resetCancelNextUpFlag(target.child);
                dispatchTransformedTouchEvent(event, true, target.child, target.pointerIdBits);
            }
            clearTouchTargets();

            if (syntheticEvent) {
                event.recycle();
            }
        }
    }

    /**
     * Gets the touch target for specified child view.
     * Returns null if not found.
     */
    private TouchTarget getTouchTarget(View child) {
        for (TouchTarget target = mFirstTouchTarget; target != null; target = target.next) {
            if (target.child == child) {
                return target;
            }
        }
        return null;
    }

    /**
     * Adds a touch target for specified child to the beginning of the list.
     * Assumes the target child is not already present.
     */
    private TouchTarget addTouchTarget(View child, int pointerIdBits) {
        TouchTarget target = TouchTarget.obtain(child, pointerIdBits);
        target.next = mFirstTouchTarget;
        mFirstTouchTarget = target;
        return target;
    }

    /**
     * Removes the pointer ids from consideration.
     */
    private void removePointersFromTouchTargets(int pointerIdBits) {
        TouchTarget predecessor = null;
        TouchTarget target = mFirstTouchTarget;
        while (target != null) {
            final TouchTarget next = target.next;
            if ((target.pointerIdBits & pointerIdBits) != 0) {
                target.pointerIdBits &= ~pointerIdBits;
                if (target.pointerIdBits == 0) {
                    if (predecessor == null) {
                        mFirstTouchTarget = next;
                    } else {
                        predecessor.next = next;
                    }
                    target.recycle();
                    target = next;
                    continue;
                }
            }
            predecessor = target;
            target = next;
        }
    }

    /**
     * Returns true if a child view can receive pointer events.
     * @hide
     */
    private static boolean canViewReceivePointerEvents(View child) {
        return (child.mViewFlags & VISIBILITY_MASK) == VISIBLE
                || child.getAnimation() != null;
    }

    /**
     * Returns true if a child view contains the specified point when transformed
     * into its coordinate space.
     * Child must not be null.
     * @hide
     */
    protected boolean isTransformedTouchPointInView(float x, float y, View child,
            PointF outLocalPoint) {
        float localX = x + mScrollX - child.mLeft;
        float localY = y + mScrollY - child.mTop;
        if (! child.hasIdentityMatrix() && mAttachInfo != null) {
            final float[] localXY = mAttachInfo.mTmpTransformLocation;
            localXY[0] = localX;
            localXY[1] = localY;
            child.getInverseMatrix().mapPoints(localXY);
            localX = localXY[0];
            localY = localXY[1];
        }
        final boolean isInView = child.pointInView(localX, localY);
        if (isInView && outLocalPoint != null) {
            outLocalPoint.set(localX, localY);
        }
        return isInView;
    }

    /**
     * Transforms a motion event into the coordinate space of a particular child view,
     * filters out irrelevant pointer ids, and overrides its action if necessary.
     * If child is null, assumes the MotionEvent will be sent to this ViewGroup instead.
     */
    private boolean dispatchTransformedTouchEvent(MotionEvent event, boolean cancel,
            View child, int desiredPointerIdBits) {
        final boolean handled;

        // Canceling motions is a special case.  We don't need to perform any transformations
        // or filtering.  The important part is the action, not the contents.
        final int oldAction = event.getAction();
        if (cancel || oldAction == MotionEvent.ACTION_CANCEL) {
            event.setAction(MotionEvent.ACTION_CANCEL);
            if (child == null) {
                handled = super.dispatchTouchEvent(event);
            } else {
                handled = child.dispatchTouchEvent(event);
            }
            event.setAction(oldAction);
            return handled;
        }

        // Calculate the number of pointers to deliver.
        final int oldPointerIdBits = event.getPointerIdBits();
        final int newPointerIdBits = oldPointerIdBits & desiredPointerIdBits;

        // If for some reason we ended up in an inconsistent state where it looks like we
        // might produce a motion event with no pointers in it, then drop the event.
        if (newPointerIdBits == 0) {
            return false;
        }

        // If the number of pointers is the same and we don't need to perform any fancy
        // irreversible transformations, then we can reuse the motion event for this
        // dispatch as long as we are careful to revert any changes we make.
        // Otherwise we need to make a copy.
        final MotionEvent transformedEvent;
        if (newPointerIdBits == oldPointerIdBits) {
            if (child == null || child.hasIdentityMatrix()) {
                if (child == null) {
                    handled = super.dispatchTouchEvent(event);
                } else {
                    final float offsetX = mScrollX - child.mLeft;
                    final float offsetY = mScrollY - child.mTop;
                    event.offsetLocation(offsetX, offsetY);

                    handled = child.dispatchTouchEvent(event);

                    event.offsetLocation(-offsetX, -offsetY);
                }
                return handled;
            }
            transformedEvent = MotionEvent.obtain(event);
        } else {
            transformedEvent = event.split(newPointerIdBits);
        }

        // Perform any necessary transformations and dispatch.
        if (child == null) {
            handled = super.dispatchTouchEvent(transformedEvent);
        } else {
            final float offsetX = mScrollX - child.mLeft;
            final float offsetY = mScrollY - child.mTop;
            transformedEvent.offsetLocation(offsetX, offsetY);
            if (! child.hasIdentityMatrix()) {
                transformedEvent.transform(child.getInverseMatrix());
            }

            handled = child.dispatchTouchEvent(transformedEvent);
        }

        // Done.
        transformedEvent.recycle();
        return handled;
    }

    /**
     * Enable or disable the splitting of MotionEvents to multiple children during touch event
     * dispatch. This behavior is enabled by default for applications that target an
     * SDK version of {@link Build.VERSION_CODES#HONEYCOMB} or newer.
     *
     * <p>When this option is enabled MotionEvents may be split and dispatched to different child
     * views depending on where each pointer initially went down. This allows for user interactions
     * such as scrolling two panes of content independently, chording of buttons, and performing
     * independent gestures on different pieces of content.
     *
     * @param split <code>true</code> to allow MotionEvents to be split and dispatched to multiple
     *              child views. <code>false</code> to only allow one child view to be the target of
     *              any MotionEvent received by this ViewGroup.
     */
    public void setMotionEventSplittingEnabled(boolean split) {
        // TODO Applications really shouldn't change this setting mid-touch event,
        // but perhaps this should handle that case and send ACTION_CANCELs to any child views
        // with gestures in progress when this is changed.
        if (split) {
            mGroupFlags |= FLAG_SPLIT_MOTION_EVENTS;
        } else {
            mGroupFlags &= ~FLAG_SPLIT_MOTION_EVENTS;
        }
    }

    /**
     * Returns true if MotionEvents dispatched to this ViewGroup can be split to multiple children.
     * @return true if MotionEvents dispatched to this ViewGroup can be split to multiple children.
     */
    public boolean isMotionEventSplittingEnabled() {
        return (mGroupFlags & FLAG_SPLIT_MOTION_EVENTS) == FLAG_SPLIT_MOTION_EVENTS;
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
     * @see #onRequestFocusInDescendants(int, android.graphics.Rect) 
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
        mGroupFlags |= FLAG_PREVENT_DISPATCH_ATTACHED_TO_WINDOW;
        super.dispatchAttachedToWindow(info, visibility);
        mGroupFlags &= ~FLAG_PREVENT_DISPATCH_ATTACHED_TO_WINDOW;

        visibility |= mViewFlags & VISIBILITY_MASK;

        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < count; i++) {
            children[i].dispatchAttachedToWindow(info, visibility);
        }
    }

    @Override
    boolean dispatchPopulateAccessibilityEventInternal(AccessibilityEvent event) {
        boolean handled = super.dispatchPopulateAccessibilityEventInternal(event);
        if (handled) {
            return handled;
        }
        // Let our children have a shot in populating the event.
        for (int i = 0, count = getChildCount(); i < count; i++) {
            View child = getChildAt(i);
            if ((child.mViewFlags & VISIBILITY_MASK) == VISIBLE) {
                handled = getChildAt(i).dispatchPopulateAccessibilityEvent(event);
                if (handled) {
                    return handled;
                }
            }
        }
        return false;
    }

    @Override
    void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);
        info.setClassName(ViewGroup.class.getName());
        for (int i = 0, count = mChildrenCount; i < count; i++) {
            View child = mChildren[i];
            if ((child.mViewFlags & VISIBILITY_MASK) == VISIBLE
                    && (child.mPrivateFlags & IS_ROOT_NAMESPACE) == 0) {
                info.addChild(child);
            }
        }
    }

    @Override
    void onInitializeAccessibilityEventInternal(AccessibilityEvent event) {
        super.onInitializeAccessibilityEventInternal(event);
        event.setClassName(ViewGroup.class.getName());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void dispatchDetachedFromWindow() {
        // If we still have a touch target, we are still in the process of
        // dispatching motion events to a child; we need to get rid of that
        // child to avoid dispatching events to it after the window is torn
        // down. To make sure we keep the child in a consistent state, we
        // first send it an ACTION_CANCEL motion event.
        cancelAndClearTouchTargets(null);

        // In case view is detached while transition is running
        mLayoutSuppressed = false;

        // Tear down our drag tracking
        mDragNotifiedChildren = null;
        if (mCurrentDrag != null) {
            mCurrentDrag.recycle();
            mCurrentDrag = null;
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

        if ((mPaddingLeft | mPaddingTop | mPaddingRight | mPaddingBottom) != 0) {
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
            View c = children[i];
            if ((c.mViewFlags & PARENT_SAVE_DISABLED_MASK) != PARENT_SAVE_DISABLED) {
                c.dispatchSaveInstanceState(container);
            }
        }
    }

    /**
     * Perform dispatching of a {@link #saveHierarchyState(android.util.SparseArray)}  freeze()}
     * to only this view, not to its children.  For use when overriding
     * {@link #dispatchSaveInstanceState(android.util.SparseArray)}  dispatchFreeze()} to allow
     * subclasses to freeze their own state but not the state of their children.
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
            View c = children[i];
            if ((c.mViewFlags & PARENT_SAVE_DISABLED_MASK) != PARENT_SAVE_DISABLED) {
                c.dispatchRestoreInstanceState(container);
            }
        }
    }

    /**
     * Perform dispatching of a {@link #restoreHierarchyState(android.util.SparseArray)}
     * to only this view, not to its children.  For use when overriding
     * {@link #dispatchRestoreInstanceState(android.util.SparseArray)} to allow
     * subclasses to thaw their own state but not the state of their children.
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
            final boolean buildCache = !isHardwareAccelerated();

            for (int i = 0; i < count; i++) {
                final View child = children[i];
                if ((child.mViewFlags & VISIBILITY_MASK) == VISIBLE) {
                    child.setDrawingCacheEnabled(true);
                    if (buildCache) {
                        child.buildDrawingCache(true);
                    }
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

            final boolean buildCache = !isHardwareAccelerated();
            for (int i = 0; i < count; i++) {
                final View child = children[i];
                if ((child.mViewFlags & VISIBILITY_MASK) == VISIBLE) {
                    final LayoutParams params = child.getLayoutParams();
                    attachLayoutAnimationParameters(child, params, i, count);
                    bindLayoutAnimation(child);
                    if (cache) {
                        child.setDrawingCacheEnabled(true);
                        if (buildCache) {                        
                            child.buildDrawingCache(true);
                        }
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
            invalidate(true);
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

        invalidate(true);
    }

    /**
     * This method is used to cause children of this ViewGroup to restore or recreate their
     * display lists. It is called by getDisplayList() when the parent ViewGroup does not need
     * to recreate its own display list, which would happen if it went through the normal
     * draw/dispatchDraw mechanisms.
     *
     * @hide
     */
    @Override
    protected void dispatchGetDisplayList() {
        final int count = mChildrenCount;
        final View[] children = mChildren;
        for (int i = 0; i < count; i++) {
            final View child = children[i];
            if (((child.mViewFlags & VISIBILITY_MASK) == VISIBLE || child.getAnimation() != null) &&
                    child.hasStaticLayer()) {
                child.mRecreateDisplayList = (child.mPrivateFlags & INVALIDATED) == INVALIDATED;
                child.mPrivateFlags &= ~INVALIDATED;
                child.getDisplayList();
                child.mRecreateDisplayList = false;
            }
        }
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

        final boolean childHasIdentityMatrix = child.hasIdentityMatrix();

        final int flags = mGroupFlags;

        if ((flags & FLAG_CLEAR_TRANSFORMATION) == FLAG_CLEAR_TRANSFORMATION) {
            mChildTransformation.clear();
            mGroupFlags &= ~FLAG_CLEAR_TRANSFORMATION;
        }

        Transformation transformToApply = null;
        Transformation invalidationTransform;
        final Animation a = child.getAnimation();
        boolean concatMatrix = false;

        boolean scalingRequired = false;
        boolean caching;
        int layerType = mDrawLayers ? child.getLayerType() : LAYER_TYPE_NONE;

        final boolean hardwareAccelerated = canvas.isHardwareAccelerated();
        if ((flags & FLAG_CHILDREN_DRAWN_WITH_CACHE) == FLAG_CHILDREN_DRAWN_WITH_CACHE ||
                (flags & FLAG_ALWAYS_DRAWN_WITH_CACHE) == FLAG_ALWAYS_DRAWN_WITH_CACHE) {
            caching = true;
            if (mAttachInfo != null) scalingRequired = mAttachInfo.mScalingRequired;
        } else {
            caching = (layerType != LAYER_TYPE_NONE) || hardwareAccelerated;
        }

        if (a != null) {
            final boolean initialized = a.isInitialized();
            if (!initialized) {
                a.initialize(cr - cl, cb - ct, getWidth(), getHeight());
                a.initializeInvalidateRegion(0, 0, cr - cl, cb - ct);
                child.onAnimationStart();
            }

            more = a.getTransformation(drawingTime, mChildTransformation, 1f);
            if (scalingRequired && mAttachInfo.mApplicationScale != 1f) {
                if (mInvalidationTransformation == null) {
                    mInvalidationTransformation = new Transformation();
                }
                invalidationTransform = mInvalidationTransformation;
                a.getTransformation(drawingTime, invalidationTransform, 1f);
            } else {
                invalidationTransform = mChildTransformation;
            }
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
                    if (mInvalidateRegion == null) {
                        mInvalidateRegion = new RectF();
                    }
                    final RectF region = mInvalidateRegion;
                    a.getInvalidateRegion(0, 0, cr - cl, cb - ct, region, invalidationTransform);

                    // The child need to draw an animation, potentially offscreen, so
                    // make sure we do not cancel invalidate requests
                    mPrivateFlags |= DRAW_ANIMATION;

                    final int left = cl + (int) region.left;
                    final int top = ct + (int) region.top;
                    invalidate(left, top, left + (int) (region.width() + .5f),
                            top + (int) (region.height() + .5f));
                }
            }
        } else if ((flags & FLAG_SUPPORT_STATIC_TRANSFORMATIONS) ==
                FLAG_SUPPORT_STATIC_TRANSFORMATIONS) {
            final boolean hasTransform = getChildStaticTransformation(child, mChildTransformation);
            if (hasTransform) {
                final int transformType = mChildTransformation.getTransformationType();
                transformToApply = transformType != Transformation.TYPE_IDENTITY ?
                        mChildTransformation : null;
                concatMatrix = (transformType & Transformation.TYPE_MATRIX) != 0;
            }
        }

        concatMatrix |= !childHasIdentityMatrix;

        // Sets the flag as early as possible to allow draw() implementations
        // to call invalidate() successfully when doing animations
        child.mPrivateFlags |= DRAWN;

        if (!concatMatrix && canvas.quickReject(cl, ct, cr, cb, Canvas.EdgeType.BW) &&
                (child.mPrivateFlags & DRAW_ANIMATION) == 0) {
            return more;
        }

        if (hardwareAccelerated) {
            // Clear INVALIDATED flag to allow invalidation to occur during rendering, but
            // retain the flag's value temporarily in the mRecreateDisplayList flag
            child.mRecreateDisplayList = (child.mPrivateFlags & INVALIDATED) == INVALIDATED;
            child.mPrivateFlags &= ~INVALIDATED;
        }

        child.computeScroll();

        final int sx = child.mScrollX;
        final int sy = child.mScrollY;

        DisplayList displayList = null;
        Bitmap cache = null;
        boolean hasDisplayList = false;
        if (caching) {
            if (!hardwareAccelerated) {
                if (layerType != LAYER_TYPE_NONE) {
                    layerType = LAYER_TYPE_SOFTWARE;
                    child.buildDrawingCache(true);
                }
                cache = child.getDrawingCache(true);
            } else {
                switch (layerType) {
                    case LAYER_TYPE_SOFTWARE:
                        child.buildDrawingCache(true);
                        cache = child.getDrawingCache(true);
                        break;
                    case LAYER_TYPE_NONE:
                        // Delay getting the display list until animation-driven alpha values are
                        // set up and possibly passed on to the view
                        hasDisplayList = child.canHaveDisplayList();
                        break;
                }
            }
        }

        final boolean hasNoCache = cache == null || hasDisplayList;
        final boolean offsetForScroll = cache == null && !hasDisplayList &&
                layerType != LAYER_TYPE_HARDWARE;

        final int restoreTo = canvas.save();
        if (offsetForScroll) {
            canvas.translate(cl - sx, ct - sy);
        } else {
            canvas.translate(cl, ct);
            if (scalingRequired) {
                // mAttachInfo cannot be null, otherwise scalingRequired == false
                final float scale = 1.0f / mAttachInfo.mApplicationScale;
                canvas.scale(scale, scale);
            }
        }

        float alpha = child.getAlpha();
        if (transformToApply != null || alpha < 1.0f || !child.hasIdentityMatrix()) {
            if (transformToApply != null || !childHasIdentityMatrix) {
                int transX = 0;
                int transY = 0;

                if (offsetForScroll) {
                    transX = -sx;
                    transY = -sy;
                }

                if (transformToApply != null) {
                    if (concatMatrix) {
                        // Undo the scroll translation, apply the transformation matrix,
                        // then redo the scroll translate to get the correct result.
                        canvas.translate(-transX, -transY);
                        canvas.concat(transformToApply.getMatrix());
                        canvas.translate(transX, transY);
                        mGroupFlags |= FLAG_CLEAR_TRANSFORMATION;
                    }

                    float transformAlpha = transformToApply.getAlpha();
                    if (transformAlpha < 1.0f) {
                        alpha *= transformToApply.getAlpha();
                        mGroupFlags |= FLAG_CLEAR_TRANSFORMATION;
                    }
                }

                if (!childHasIdentityMatrix) {
                    canvas.translate(-transX, -transY);
                    canvas.concat(child.getMatrix());
                    canvas.translate(transX, transY);
                }
            }

            if (alpha < 1.0f) {
                mGroupFlags |= FLAG_CLEAR_TRANSFORMATION;
                if (hasNoCache) {
                    final int multipliedAlpha = (int) (255 * alpha);
                    if (!child.onSetAlpha(multipliedAlpha)) {
                        int layerFlags = Canvas.HAS_ALPHA_LAYER_SAVE_FLAG;
                        if ((flags & FLAG_CLIP_CHILDREN) == FLAG_CLIP_CHILDREN ||
                                layerType != LAYER_TYPE_NONE) {
                            layerFlags |= Canvas.CLIP_TO_LAYER_SAVE_FLAG;
                        }
                        if (layerType == LAYER_TYPE_NONE) {
                            final int scrollX = hasDisplayList ? 0 : sx;
                            final int scrollY = hasDisplayList ? 0 : sy;
                            canvas.saveLayerAlpha(scrollX, scrollY, scrollX + cr - cl,
                                    scrollY + cb - ct, multipliedAlpha, layerFlags);
                        }
                    } else {
                        // Alpha is handled by the child directly, clobber the layer's alpha
                        child.mPrivateFlags |= ALPHA_SET;
                    }
                }
            }
        } else if ((child.mPrivateFlags & ALPHA_SET) == ALPHA_SET) {
            child.onSetAlpha(255);
            child.mPrivateFlags &= ~ALPHA_SET;
        }

        if ((flags & FLAG_CLIP_CHILDREN) == FLAG_CLIP_CHILDREN) {
            if (offsetForScroll) {
                canvas.clipRect(sx, sy, sx + (cr - cl), sy + (cb - ct));
            } else {
                if (!scalingRequired || cache == null) {
                    canvas.clipRect(0, 0, cr - cl, cb - ct);
                } else {
                    canvas.clipRect(0, 0, cache.getWidth(), cache.getHeight());
                }
            }
        }

        if (hasDisplayList) {
            displayList = child.getDisplayList();
            if (!displayList.isValid()) {
                // Uncommon, but possible. If a view is removed from the hierarchy during the call
                // to getDisplayList(), the display list will be marked invalid and we should not
                // try to use it again.
                displayList = null;
                hasDisplayList = false;
            }
        }

        if (hasNoCache) {
            boolean layerRendered = false;
            if (layerType == LAYER_TYPE_HARDWARE) {
                final HardwareLayer layer = child.getHardwareLayer();
                if (layer != null && layer.isValid()) {
                    child.mLayerPaint.setAlpha((int) (alpha * 255));                    
                    ((HardwareCanvas) canvas).drawHardwareLayer(layer, 0, 0, child.mLayerPaint);
                    layerRendered = true;
                } else {
                    final int scrollX = hasDisplayList ? 0 : sx;
                    final int scrollY = hasDisplayList ? 0 : sy;                    
                    canvas.saveLayer(scrollX, scrollY,
                            scrollX + cr - cl, scrollY + cb - ct, child.mLayerPaint,
                            Canvas.HAS_ALPHA_LAYER_SAVE_FLAG | Canvas.CLIP_TO_LAYER_SAVE_FLAG);
                }
            }

            if (!layerRendered) {
                if (!hasDisplayList) {
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
                    child.mPrivateFlags &= ~DIRTY_MASK;
                    ((HardwareCanvas) canvas).drawDisplayList(displayList, cr - cl, cb - ct, null);
                }
            }
        } else if (cache != null) {
            child.mPrivateFlags &= ~DIRTY_MASK;
            Paint cachePaint;

            if (layerType == LAYER_TYPE_NONE) {
                cachePaint = mCachePaint;
                if (cachePaint == null) {
                    cachePaint = new Paint();
                    cachePaint.setDither(false);
                    mCachePaint = cachePaint;
                }
                if (alpha < 1.0f) {
                    cachePaint.setAlpha((int) (alpha * 255));
                    mGroupFlags |= FLAG_ALPHA_LOWER_THAN_ONE;
                } else if  ((flags & FLAG_ALPHA_LOWER_THAN_ONE) == FLAG_ALPHA_LOWER_THAN_ONE) {
                    cachePaint.setAlpha(255);
                    mGroupFlags &= ~FLAG_ALPHA_LOWER_THAN_ONE;
                }
            } else {
                cachePaint = child.mLayerPaint;
                cachePaint.setAlpha((int) (alpha * 255));
            }
            canvas.drawBitmap(cache, 0.0f, 0.0f, cachePaint);
        }

        canvas.restoreToCount(restoreTo);

        if (a != null && !more) {
            if (!hardwareAccelerated && !a.getFillAfter()) {
                child.onSetAlpha(255);
            }
            finishAnimatingView(child, a);
        }

        if (more && hardwareAccelerated) {
            // invalidation is the trigger to recreate display lists, so if we're using
            // display lists to render, force an invalidate to allow the animation to
            // continue drawing another frame
            invalidate(true);
            if (a.hasAlpha() && (child.mPrivateFlags & ALPHA_SET) == ALPHA_SET) {
                // alpha animations should cause the child to recreate its display list
                child.invalidate(true);
            }
        }

        child.mRecreateDisplayList = false;

        return more;
    }

    /**
     * 
     * @param enabled True if children should be drawn with layers, false otherwise.
     * 
     * @hide
     */
    public void setChildrenLayersEnabled(boolean enabled) {
        if (enabled != mDrawLayers) {
            mDrawLayers = enabled;
            invalidate(true);

            boolean flushLayers = !enabled;
            AttachInfo info = mAttachInfo;
            if (info != null && info.mHardwareRenderer != null &&
                    info.mHardwareRenderer.isEnabled()) {
                if (!info.mHardwareRenderer.validate()) {
                    flushLayers = false;
                }
            } else {
                flushLayers = false;
            }

            // We need to invalidate any child with a layer. For instance,
            // if a child is backed by a hardware layer and we disable layers
            // the child is marked as not dirty (flags cleared the last time
            // the child was drawn inside its layer.) However, that child might
            // never have created its own display list or have an obsolete
            // display list. By invalidating the child we ensure the display
            // list is in sync with the content of the hardware layer.
            for (int i = 0; i < mChildrenCount; i++) {
                View child = mChildren[i];
                if (child.mLayerType != LAYER_TYPE_NONE) {
                    if (flushLayers) child.flushLayer();
                    child.invalidate(true);
                }
            }
        }
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void dispatchSetActivated(boolean activated) {
        final View[] children = mChildren;
        final int count = mChildrenCount;
        for (int i = 0; i < count; i++) {
            children[i].setActivated(activated);
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
     * Sets  <code>t</code> to be the static transformation of the child, if set, returning a
     * boolean to indicate whether a static transform was set. The default implementation
     * simply returns <code>false</code>; subclasses may override this method for different
     * behavior.
     *
     * @param child The child view whose static transform is being requested
     * @param t The Transformation which will hold the result
     * @return true if the transformation was set, false otherwise
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
     * {@hide}
     */
    @Override
    protected View findViewByPredicateTraversal(Predicate<View> predicate, View childToSkip) {
        if (predicate.apply(this)) {
            return this;
        }

        final View[] where = mChildren;
        final int len = mChildrenCount;

        for (int i = 0; i < len; i++) {
            View v = where[i];

            if (v != childToSkip && (v.mPrivateFlags & IS_ROOT_NAMESPACE) == 0) {
                v = v.findViewByPredicate(predicate);

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
        invalidate(true);
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
     * @hide
     */
    protected void onViewAdded(View child) {
        if (mOnHierarchyChangeListener != null) {
            mOnHierarchyChangeListener.onChildViewAdded(this, child);
        }
    }

    /**
     * @hide
     */
    protected void onViewRemoved(View child) {
        if (mOnHierarchyChangeListener != null) {
            mOnHierarchyChangeListener.onChildViewRemoved(this, child);
        }
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

        if (mTransition != null) {
            // Don't prevent other add transitions from completing, but cancel remove
            // transitions to let them complete the process before we add to the container
            mTransition.cancel(LayoutTransition.DISAPPEARING);
        }

        if (child.getParent() != null) {
            throw new IllegalStateException("The specified child already has a parent. " +
                    "You must call removeView() on the child's parent first.");
        }

        if (mTransition != null) {
            mTransition.addChild(this, child);
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
        if (ai != null && (mGroupFlags & FLAG_PREVENT_DISPATCH_ATTACHED_TO_WINDOW) == 0) {
            boolean lastKeepOn = ai.mKeepScreenOn;
            ai.mKeepScreenOn = false;
            child.dispatchAttachedToWindow(mAttachInfo, (mViewFlags&VISIBILITY_MASK));
            if (ai.mKeepScreenOn) {
                needGlobalAttributesUpdate(true);
            }
            ai.mKeepScreenOn = lastKeepOn;
        }

        onViewAdded(child);

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
            if (mLastTouchDownIndex >= index) {
                mLastTouchDownIndex++;
            }
        } else {
            throw new IndexOutOfBoundsException("index=" + index + " count=" + count);
        }
    }

    // This method also sets the child's mParent to null
    private void removeFromArray(int index) {
        final View[] children = mChildren;
        if (!(mTransitioningViews != null && mTransitioningViews.contains(children[index]))) {
            children[index].mParent = null;
        }
        final int count = mChildrenCount;
        if (index == count - 1) {
            children[--mChildrenCount] = null;
        } else if (index >= 0 && index < count) {
            System.arraycopy(children, index + 1, children, index, count - index - 1);
            children[--mChildrenCount] = null;
        } else {
            throw new IndexOutOfBoundsException();
        }
        if (mLastTouchDownIndex == index) {
            mLastTouchDownTime = 0;
            mLastTouchDownIndex = -1;
        } else if (mLastTouchDownIndex > index) {
            mLastTouchDownIndex--;
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
        invalidate(true);
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
        invalidate(true);
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
        invalidate(true);
    }

    private void removeViewInternal(View view) {
        final int index = indexOfChild(view);
        if (index >= 0) {
            removeViewInternal(index, view);
        }
    }

    private void removeViewInternal(int index, View view) {

        if (mTransition != null) {
            mTransition.removeChild(this, view);
        }

        boolean clearChildFocus = false;
        if (view == mFocused) {
            view.clearFocusForRemoval();
            clearChildFocus = true;
        }

        if (view.getAnimation() != null ||
                (mTransitioningViews != null && mTransitioningViews.contains(view))) {
            addDisappearingView(view);
        } else if (view.mAttachInfo != null) {
           view.dispatchDetachedFromWindow();
        }

        onViewRemoved(view);

        needGlobalAttributesUpdate(false);

        removeFromArray(index);

        if (clearChildFocus) {
            clearChildFocus(view);
        }
    }

    /**
     * Sets the LayoutTransition object for this ViewGroup. If the LayoutTransition object is
     * not null, changes in layout which occur because of children being added to or removed from
     * the ViewGroup will be animated according to the animations defined in that LayoutTransition
     * object. By default, the transition object is null (so layout changes are not animated).
     *
     * @param transition The LayoutTransition object that will animated changes in layout. A value
     * of <code>null</code> means no transition will run on layout changes.
     * @attr ref android.R.styleable#ViewGroup_animateLayoutChanges
     */
    public void setLayoutTransition(LayoutTransition transition) {
        if (mTransition != null) {
            mTransition.removeTransitionListener(mLayoutTransitionListener);
        }
        mTransition = transition;
        if (mTransition != null) {
            mTransition.addTransitionListener(mLayoutTransitionListener);
        }
    }

    /**
     * Gets the LayoutTransition object for this ViewGroup. If the LayoutTransition object is
     * not null, changes in layout which occur because of children being added to or removed from
     * the ViewGroup will be animated according to the animations defined in that LayoutTransition
     * object. By default, the transition object is null (so layout changes are not animated).
     *
     * @return LayoutTranstion The LayoutTransition object that will animated changes in layout.
     * A value of <code>null</code> means no transition will run on layout changes.
     */
    public LayoutTransition getLayoutTransition() {
        return mTransition;
    }

    private void removeViewsInternal(int start, int count) {
        final View focused = mFocused;
        final boolean detach = mAttachInfo != null;
        View clearChildFocus = null;

        final View[] children = mChildren;
        final int end = start + count;

        for (int i = start; i < end; i++) {
            final View view = children[i];

            if (mTransition != null) {
                mTransition.removeChild(this, view);
            }

            if (view == focused) {
                view.clearFocusForRemoval();
                clearChildFocus = view;
            }

            if (view.getAnimation() != null ||
                (mTransitioningViews != null && mTransitioningViews.contains(view))) {
                addDisappearingView(view);
            } else if (detach) {
               view.dispatchDetachedFromWindow();
            }

            needGlobalAttributesUpdate(false);

            onViewRemoved(view);
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
        invalidate(true);
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

        final View focused = mFocused;
        final boolean detach = mAttachInfo != null;
        View clearChildFocus = null;

        needGlobalAttributesUpdate(false);

        for (int i = count - 1; i >= 0; i--) {
            final View view = children[i];

            if (mTransition != null) {
                mTransition.removeChild(this, view);
            }

            if (view == focused) {
                view.clearFocusForRemoval();
                clearChildFocus = view;
            }

            if (view.getAnimation() != null ||
                    (mTransitioningViews != null && mTransitioningViews.contains(view))) {
                addDisappearingView(view);
            } else if (detach) {
               view.dispatchDetachedFromWindow();
            }

            onViewRemoved(view);

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
        if (mTransition != null) {
            mTransition.removeChild(this, child);
        }

        if (child == mFocused) {
            child.clearFocus();
        }

        if ((animate && child.getAnimation() != null) ||
                (mTransitioningViews != null && mTransitioningViews.contains(child))) {
            addDisappearingView(child);
        } else if (child.mAttachInfo != null) {
            child.dispatchDetachedFromWindow();
        }

        onViewRemoved(child);
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
        child.mPrivateFlags = (child.mPrivateFlags & ~DIRTY_MASK & ~DRAWING_CACHE_VALID) |
                DRAWN | INVALIDATED;
        this.mPrivateFlags |= INVALIDATED;

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
            // If the child is drawing an animation, we want to copy this flag onto
            // ourselves and the parent to make sure the invalidate request goes
            // through
            final boolean drawAnimation = (child.mPrivateFlags & DRAW_ANIMATION) == DRAW_ANIMATION;

            if (dirty == null) {
                if (child.mLayerType != LAYER_TYPE_NONE) {
                    mPrivateFlags |= INVALIDATED;
                    mPrivateFlags &= ~DRAWING_CACHE_VALID;
                    child.mLocalDirtyRect.setEmpty();
                }
                do {
                    View view = null;
                    if (parent instanceof View) {
                        view = (View) parent;
                        if (view.mLayerType != LAYER_TYPE_NONE) {
                            view.mLocalDirtyRect.setEmpty();
                            if (view.getParent() instanceof View) {
                                final View grandParent = (View) view.getParent();
                                grandParent.mPrivateFlags |= INVALIDATED;
                                grandParent.mPrivateFlags &= ~DRAWING_CACHE_VALID;
                            }
                        }
                        if ((view.mPrivateFlags & DIRTY_MASK) != 0) {
                            // already marked dirty - we're done
                            break;
                        }
                    }

                    if (drawAnimation) {
                        if (view != null) {
                            view.mPrivateFlags |= DRAW_ANIMATION;
                        } else if (parent instanceof ViewRootImpl) {
                            ((ViewRootImpl) parent).mIsAnimating = true;
                        }
                    }

                    if (parent instanceof ViewRootImpl) {
                        ((ViewRootImpl) parent).invalidate();
                        parent = null;
                    } else if (view != null) {
                        if ((view.mPrivateFlags & DRAWN) == DRAWN ||
                                (view.mPrivateFlags & DRAWING_CACHE_VALID) == DRAWING_CACHE_VALID) {
                            view.mPrivateFlags &= ~DRAWING_CACHE_VALID;
                            view.mPrivateFlags |= DIRTY;
                            parent = view.mParent;
                        } else {
                            parent = null;
                        }
                    }
                } while (parent != null);
            } else {
                // Check whether the child that requests the invalidate is fully opaque
                // Views being animated or transformed are not considered opaque because we may
                // be invalidating their old position and need the parent to paint behind them.
                Matrix childMatrix = child.getMatrix();
                final boolean isOpaque = child.isOpaque() && !drawAnimation &&
                        child.getAnimation() == null && childMatrix.isIdentity();
                // Mark the child as dirty, using the appropriate flag
                // Make sure we do not set both flags at the same time
                int opaqueFlag = isOpaque ? DIRTY_OPAQUE : DIRTY;

                if (child.mLayerType != LAYER_TYPE_NONE) {
                    mPrivateFlags |= INVALIDATED;
                    mPrivateFlags &= ~DRAWING_CACHE_VALID;
                    child.mLocalDirtyRect.union(dirty);
                }

                final int[] location = attachInfo.mInvalidateChildLocation;
                location[CHILD_LEFT_INDEX] = child.mLeft;
                location[CHILD_TOP_INDEX] = child.mTop;
                if (!childMatrix.isIdentity()) {
                    RectF boundingRect = attachInfo.mTmpTransformRect;
                    boundingRect.set(dirty);
                    //boundingRect.inset(-0.5f, -0.5f);
                    childMatrix.mapRect(boundingRect);
                    dirty.set((int) (boundingRect.left - 0.5f),
                            (int) (boundingRect.top - 0.5f),
                            (int) (boundingRect.right + 0.5f),
                            (int) (boundingRect.bottom + 0.5f));
                }

                do {
                    View view = null;
                    if (parent instanceof View) {
                        view = (View) parent;
                        if (view.mLayerType != LAYER_TYPE_NONE &&
                                view.getParent() instanceof View) {
                            final View grandParent = (View) view.getParent();
                            grandParent.mPrivateFlags |= INVALIDATED;
                            grandParent.mPrivateFlags &= ~DRAWING_CACHE_VALID;
                        }
                    }

                    if (drawAnimation) {
                        if (view != null) {
                            view.mPrivateFlags |= DRAW_ANIMATION;
                        } else if (parent instanceof ViewRootImpl) {
                            ((ViewRootImpl) parent).mIsAnimating = true;
                        }
                    }

                    // If the parent is dirty opaque or not dirty, mark it dirty with the opaque
                    // flag coming from the child that initiated the invalidate
                    if (view != null) {
                        if ((view.mViewFlags & FADING_EDGE_MASK) != 0 &&
                                view.getSolidColor() == 0) {
                            opaqueFlag = DIRTY;
                        }
                        if ((view.mPrivateFlags & DIRTY_MASK) != DIRTY) {
                            view.mPrivateFlags = (view.mPrivateFlags & ~DIRTY_MASK) | opaqueFlag;
                        }
                    }

                    parent = parent.invalidateChildInParent(location, dirty);
                    if (view != null) {
                        // Account for transform on current parent
                        Matrix m = view.getMatrix();
                        if (!m.isIdentity()) {
                            RectF boundingRect = attachInfo.mTmpTransformRect;
                            boundingRect.set(dirty);
                            m.mapRect(boundingRect);
                            dirty.set((int) boundingRect.left, (int) boundingRect.top,
                                    (int) (boundingRect.right + 0.5f),
                                    (int) (boundingRect.bottom + 0.5f));
                        }
                    }
                } while (parent != null);
            }
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

        if ((mPrivateFlags & DRAWN) == DRAWN ||
                (mPrivateFlags & DRAWING_CACHE_VALID) == DRAWING_CACHE_VALID) {
            if ((mGroupFlags & (FLAG_OPTIMIZE_INVALIDATE | FLAG_ANIMATION_DONE)) !=
                        FLAG_OPTIMIZE_INVALIDATE) {
                dirty.offset(location[CHILD_LEFT_INDEX] - mScrollX,
                        location[CHILD_TOP_INDEX] - mScrollY);

                final int left = mLeft;
                final int top = mTop;

                if ((mGroupFlags & FLAG_CLIP_CHILDREN) != FLAG_CLIP_CHILDREN ||
                        dirty.intersect(0, 0, mRight - left, mBottom - top) ||
                        (mPrivateFlags & DRAW_ANIMATION) == DRAW_ANIMATION) {
                    mPrivateFlags &= ~DRAWING_CACHE_VALID;

                    location[CHILD_LEFT_INDEX] = left;
                    location[CHILD_TOP_INDEX] = top;

                    if (mLayerType != LAYER_TYPE_NONE) {
                        mLocalDirtyRect.union(dirty);
                    }

                    return mParent;
                }
            } else {
                mPrivateFlags &= ~DRAWN & ~DRAWING_CACHE_VALID;

                location[CHILD_LEFT_INDEX] = mLeft;
                location[CHILD_TOP_INDEX] = mTop;
                if ((mGroupFlags & FLAG_CLIP_CHILDREN) == FLAG_CLIP_CHILDREN) {
                    dirty.set(0, 0, mRight - mLeft, mBottom - mTop);
                } else {
                    // in case the dirty rect extends outside the bounds of this container
                    dirty.union(0, 0, mRight - mLeft, mBottom - mTop);
                }

                if (mLayerType != LAYER_TYPE_NONE) {
                    mLocalDirtyRect.union(dirty);
                }

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
        // It doesn't make a whole lot of sense to call this on a view that isn't attached,
        // but for some simple tests it can be useful. If we don't have attach info this
        // will allocate memory.
        final RectF rect = mAttachInfo != null ? mAttachInfo.mTmpTransformRect : new RectF();
        rect.set(r);

        if (!child.hasIdentityMatrix()) {
           child.getMatrix().mapRect(rect);
        }

        int dx = child.mLeft - mScrollX;
        int dy = child.mTop - mScrollY;

        rect.offset(dx, dy);

        if (offset != null) {
            if (!child.hasIdentityMatrix()) {
                float[] position = mAttachInfo != null ? mAttachInfo.mTmpTransformLocation
                        : new float[2];
                position[0] = offset.x;
                position[1] = offset.y;
                child.getMatrix().mapPoints(position);
                offset.x = (int) (position[0] + 0.5f);
                offset.y = (int) (position[1] + 0.5f);
            }
            offset.x += dx;
            offset.y += dy;
        }

        if (rect.intersect(0, 0, mRight - mLeft, mBottom - mTop)) {
            if (mParent == null) return true;
            r.set((int) (rect.left + 0.5f), (int) (rect.top + 0.5f),
                    (int) (rect.right + 0.5f), (int) (rect.bottom + 0.5f));
            return mParent.getChildVisibleRect(this, r, offset);
        }

        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final void layout(int l, int t, int r, int b) {
        if (mTransition == null || !mTransition.isChangingLayout()) {
            super.layout(l, t, r, b);
        } else {
            // record the fact that we noop'd it; request layout when transition finishes
            mLayoutSuppressed = true;
        }
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
        @ViewDebug.IntToString(from = PERSISTENT_ANIMATION_CACHE, to = "ANIMATION"),
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
                if (parent != null && !(parent instanceof ViewRootImpl)) {
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
        if (index < 0 || index >= mChildrenCount) {
            return null;
        }
        return mChildren[index];
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
     * Utility function called by View during invalidation to determine whether a view that
     * is invisible or gone should still be invalidated because it is being transitioned (and
     * therefore still needs to be drawn).
     */
    boolean isViewTransitioning(View view) {
        return (mTransitioningViews != null && mTransitioningViews.contains(view));
    }

    /**
     * This method tells the ViewGroup that the given View object, which should have this
     * ViewGroup as its parent,
     * should be kept around  (re-displayed when the ViewGroup draws its children) even if it
     * is removed from its parent. This allows animations, such as those used by
     * {@link android.app.Fragment} and {@link android.animation.LayoutTransition} to animate
     * the removal of views. A call to this method should always be accompanied by a later call
     * to {@link #endViewTransition(View)}, such as after an animation on the View has finished,
     * so that the View finally gets removed.
     *
     * @param view The View object to be kept visible even if it gets removed from its parent.
     */
    public void startViewTransition(View view) {
        if (view.mParent == this) {
            if (mTransitioningViews == null) {
                mTransitioningViews = new ArrayList<View>();
            }
            mTransitioningViews.add(view);
        }
    }

    /**
     * This method should always be called following an earlier call to
     * {@link #startViewTransition(View)}. The given View is finally removed from its parent
     * and will no longer be displayed. Note that this method does not perform the functionality
     * of removing a view from its parent; it just discontinues the display of a View that
     * has previously been removed.
     *
     * @return view The View object that has been removed but is being kept around in the visible
     * hierarchy by an earlier call to {@link #startViewTransition(View)}.
     */
    public void endViewTransition(View view) {
        if (mTransitioningViews != null) {
            mTransitioningViews.remove(view);
            final ArrayList<View> disappearingChildren = mDisappearingChildren;
            if (disappearingChildren != null && disappearingChildren.contains(view)) {
                disappearingChildren.remove(view);
                if (mVisibilityChangingChildren != null &&
                        mVisibilityChangingChildren.contains(view)) {
                    mVisibilityChangingChildren.remove(view);
                } else {
                    if (view.mAttachInfo != null) {
                        view.dispatchDetachedFromWindow();
                    }
                    if (view.mParent != null) {
                        view.mParent = null;
                    }
                }
                mGroupFlags |= FLAG_INVALIDATE_REQUIRED;
            }
        }
    }

    private LayoutTransition.TransitionListener mLayoutTransitionListener =
            new LayoutTransition.TransitionListener() {
        @Override
        public void startTransition(LayoutTransition transition, ViewGroup container,
                View view, int transitionType) {
            // We only care about disappearing items, since we need special logic to keep
            // those items visible after they've been 'removed'
            if (transitionType == LayoutTransition.DISAPPEARING) {
                startViewTransition(view);
            }
        }

        @Override
        public void endTransition(LayoutTransition transition, ViewGroup container,
                View view, int transitionType) {
            if (mLayoutSuppressed && !transition.isChangingLayout()) {
                requestLayout();
                mLayoutSuppressed = false;
            }
            if (transitionType == LayoutTransition.DISAPPEARING && mTransitioningViews != null) {
                endViewTransition(view);
            }
        }
    };

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
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        final View[] children = mChildren;
        final int count = mChildrenCount;
        for (int i = 0; i < count; i++) {
            children[i].jumpDrawablesToCurrentState();
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
     * This method is called by LayoutTransition when there are 'changing' animations that need
     * to start after the layout/setup phase. The request is forwarded to the ViewAncestor, who
     * starts all pending transitions prior to the drawing phase in the current traversal.
     *
     * @param transition The LayoutTransition to be started on the next traversal.
     *
     * @hide
     */
    public void requestTransitionStart(LayoutTransition transition) {
        ViewRootImpl viewAncestor = getViewRootImpl();
        if (viewAncestor != null) {
            viewAncestor.requestTransitionStart(transition);
        }
    }

    @Override
    protected void resetResolvedLayoutDirection() {
        super.resetResolvedLayoutDirection();

        // Take care of resetting the children resolution too
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getLayoutDirection() == LAYOUT_DIRECTION_INHERIT) {
                child.resetResolvedLayoutDirection();
            }
        }
    }

    @Override
    protected void resetResolvedTextDirection() {
        super.resetResolvedTextDirection();

        // Take care of resetting the children resolution too
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getTextDirection() == TEXT_DIRECTION_INHERIT) {
                child.resetResolvedTextDirection();
            }
        }
    }

    /**
     * Return true if the pressed state should be delayed for children or descendants of this
     * ViewGroup. Generally, this should be done for containers that can scroll, such as a List.
     * This prevents the pressed state from appearing when the user is actually trying to scroll
     * the content.
     *
     * The default implementation returns true for compatibility reasons. Subclasses that do
     * not scroll should generally override this method and return false.
     */
    public boolean shouldDelayChildPressedState() {
        return true;
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
     * LayoutParams which adds an X and Y value.</p>
     *
     * <div class="special reference">
     * <h3>Developer Guides</h3>
     * <p>For more information about creating user interface layouts, read the
     * <a href="{@docRoot}guide/topics/ui/declaring-layout.html">XML Layouts</a> developer
     * guide.</p></div>
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
         * Resolve layout parameters depending on the layout direction. Subclasses that care about
         * layoutDirection changes should override this method. The default implementation does
         * nothing.
         *
         * @param layoutDirection the direction of the layout
         *
         * {@link View#LAYOUT_DIRECTION_LTR}
         * {@link View#LAYOUT_DIRECTION_RTL}
         *
         * @hide
         */
        protected void resolveWithDirection(int layoutDirection) {
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
         * The left margin in pixels of the child. Whenever this value is changed, a call to
         * {@link android.view.View#requestLayout()} needs to be done.
         */
        @ViewDebug.ExportedProperty(category = "layout")
        public int leftMargin;

        /**
         * The top margin in pixels of the child. Whenever this value is changed, a call to
         * {@link android.view.View#requestLayout()} needs to be done.
         */
        @ViewDebug.ExportedProperty(category = "layout")
        public int topMargin;

        /**
         * The right margin in pixels of the child. Whenever this value is changed, a call to
         * {@link android.view.View#requestLayout()} needs to be done.
         */
        @ViewDebug.ExportedProperty(category = "layout")
        public int rightMargin;

        /**
         * The bottom margin in pixels of the child. Whenever this value is changed, a call to
         * {@link android.view.View#requestLayout()} needs to be done.
         */
        @ViewDebug.ExportedProperty(category = "layout")
        public int bottomMargin;

        /**
         * The start margin in pixels of the child.
         *
         * @hide
         *
         */
        @ViewDebug.ExportedProperty(category = "layout")
        protected int startMargin = DEFAULT_RELATIVE;

        /**
         * The end margin in pixels of the child.
         *
         * @hide
         */
        @ViewDebug.ExportedProperty(category = "layout")
        protected int endMargin = DEFAULT_RELATIVE;

        /**
         * The default start and end margin.
         */
        static private final int DEFAULT_RELATIVE = Integer.MIN_VALUE;

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
                startMargin = a.getDimensionPixelSize(
                        R.styleable.ViewGroup_MarginLayout_layout_marginStart, DEFAULT_RELATIVE);
                endMargin = a.getDimensionPixelSize(
                        R.styleable.ViewGroup_MarginLayout_layout_marginEnd, DEFAULT_RELATIVE);
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
            this.startMargin = source.startMargin;
            this.endMargin = source.endMargin;
        }

        /**
         * {@inheritDoc}
         */
        public MarginLayoutParams(LayoutParams source) {
            super(source);
        }

        /**
         * Sets the margins, in pixels. A call to {@link android.view.View#requestLayout()} needs
         * to be done so that the new margins are taken into account. Left and right margins may be
         * overriden by {@link android.view.View#requestLayout()} depending on layout direction.
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

        /**
         * Sets the relative margins, in pixels. A call to {@link android.view.View#requestLayout()}
         * needs to be done so that the new relative margins are taken into account. Left and right
         * margins may be overriden by {@link android.view.View#requestLayout()} depending on layout
         * direction.
         *
         * @param start the start margin size
         * @param top the top margin size
         * @param end the right margin size
         * @param bottom the bottom margin size
         *
         * @attr ref android.R.styleable#ViewGroup_MarginLayout_layout_marginStart
         * @attr ref android.R.styleable#ViewGroup_MarginLayout_layout_marginTop
         * @attr ref android.R.styleable#ViewGroup_MarginLayout_layout_marginEnd
         * @attr ref android.R.styleable#ViewGroup_MarginLayout_layout_marginBottom
         *
         * @hide
         */
        public void setMarginsRelative(int start, int top, int end, int bottom) {
            startMargin = start;
            topMargin = top;
            endMargin = end;
            bottomMargin = bottom;
        }

        /**
         * Returns the start margin in pixels.
         *
         * @attr ref android.R.styleable#ViewGroup_MarginLayout_layout_marginStart
         *
         * @return the start margin in pixels.
         *
         * @hide
         */
        public int getMarginStart() {
            return startMargin;
        }

        /**
         * Returns the end margin in pixels.
         *
         * @attr ref android.R.styleable#ViewGroup_MarginLayout_layout_marginEnd
         *
         * @return the end margin in pixels.
         *
         * @hide
         */
        public int getMarginEnd() {
            return endMargin;
        }

        /**
         * Check if margins are relative.
         *
         * @attr ref android.R.styleable#ViewGroup_MarginLayout_layout_marginStart
         * @attr ref android.R.styleable#ViewGroup_MarginLayout_layout_marginEnd
         *
         * @return true if either marginStart or marginEnd has been set
         *
         * @hide
         */
        public boolean isMarginRelative() {
            return (startMargin != DEFAULT_RELATIVE) || (endMargin != DEFAULT_RELATIVE);
        }

        /**
         * This will be called by {@link android.view.View#requestLayout()}. Left and Right margins
         * maybe overriden depending on layout direction.
         *
         * @hide
         */
        @Override
        protected void resolveWithDirection(int layoutDirection) {
            switch(layoutDirection) {
                case View.LAYOUT_DIRECTION_RTL:
                    leftMargin = (endMargin > DEFAULT_RELATIVE) ? endMargin : leftMargin;
                    rightMargin = (startMargin > DEFAULT_RELATIVE) ? startMargin : rightMargin;
                    break;
                case View.LAYOUT_DIRECTION_LTR:
                default:
                    leftMargin = (startMargin > DEFAULT_RELATIVE) ? startMargin : leftMargin;
                    rightMargin = (endMargin > DEFAULT_RELATIVE) ? endMargin : rightMargin;
                    break;
            }
        }
    }

    /* Describes a touched view and the ids of the pointers that it has captured.
     *
     * This code assumes that pointer ids are always in the range 0..31 such that
     * it can use a bitfield to track which pointer ids are present.
     * As it happens, the lower layers of the input dispatch pipeline also use the
     * same trick so the assumption should be safe here...
     */
    private static final class TouchTarget {
        private static final int MAX_RECYCLED = 32;
        private static final Object sRecycleLock = new Object();
        private static TouchTarget sRecycleBin;
        private static int sRecycledCount;

        public static final int ALL_POINTER_IDS = -1; // all ones

        // The touched child view.
        public View child;

        // The combined bit mask of pointer ids for all pointers captured by the target.
        public int pointerIdBits;

        // The next target in the target list.
        public TouchTarget next;

        private TouchTarget() {
        }

        public static TouchTarget obtain(View child, int pointerIdBits) {
            final TouchTarget target;
            synchronized (sRecycleLock) {
                if (sRecycleBin == null) {
                    target = new TouchTarget();
                } else {
                    target = sRecycleBin;
                    sRecycleBin = target.next;
                     sRecycledCount--;
                    target.next = null;
                }
            }
            target.child = child;
            target.pointerIdBits = pointerIdBits;
            return target;
        }

        public void recycle() {
            synchronized (sRecycleLock) {
                if (sRecycledCount < MAX_RECYCLED) {
                    next = sRecycleBin;
                    sRecycleBin = this;
                    sRecycledCount += 1;
                } else {
                    next = null;
                }
                child = null;
            }
        }
    }

    /* Describes a hovered view. */
    private static final class HoverTarget {
        private static final int MAX_RECYCLED = 32;
        private static final Object sRecycleLock = new Object();
        private static HoverTarget sRecycleBin;
        private static int sRecycledCount;

        // The hovered child view.
        public View child;

        // The next target in the target list.
        public HoverTarget next;

        private HoverTarget() {
        }

        public static HoverTarget obtain(View child) {
            final HoverTarget target;
            synchronized (sRecycleLock) {
                if (sRecycleBin == null) {
                    target = new HoverTarget();
                } else {
                    target = sRecycleBin;
                    sRecycleBin = target.next;
                     sRecycledCount--;
                    target.next = null;
                }
            }
            target.child = child;
            return target;
        }

        public void recycle() {
            synchronized (sRecycleLock) {
                if (sRecycledCount < MAX_RECYCLED) {
                    next = sRecycleBin;
                    sRecycleBin = this;
                    sRecycledCount += 1;
                } else {
                    next = null;
                }
                child = null;
            }
        }
    }
}
