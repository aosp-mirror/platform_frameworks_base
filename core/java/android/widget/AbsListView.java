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

package android.widget;

import android.annotation.ColorInt;
import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.StrictMode;
import android.os.Trace;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.StateSet;
import android.view.ActionMode;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.view.ViewHierarchyEncoder;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityNodeInfo.CollectionInfo;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inspector.InspectableProperty;
import android.view.inspector.InspectableProperty.EnumEntry;
import android.widget.RemoteViews.OnClickHandler;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class that can be used to implement virtualized lists of items. A list does
 * not have a spatial definition here. For instance, subclasses of this class can
 * display the content of the list in a grid, in a carousel, as stack, etc.
 *
 * @attr ref android.R.styleable#AbsListView_listSelector
 * @attr ref android.R.styleable#AbsListView_drawSelectorOnTop
 * @attr ref android.R.styleable#AbsListView_stackFromBottom
 * @attr ref android.R.styleable#AbsListView_scrollingCache
 * @attr ref android.R.styleable#AbsListView_textFilterEnabled
 * @attr ref android.R.styleable#AbsListView_transcriptMode
 * @attr ref android.R.styleable#AbsListView_cacheColorHint
 * @attr ref android.R.styleable#AbsListView_fastScrollEnabled
 * @attr ref android.R.styleable#AbsListView_smoothScrollbar
 * @attr ref android.R.styleable#AbsListView_choiceMode
 */
public abstract class AbsListView extends AdapterView<ListAdapter> implements TextWatcher,
        ViewTreeObserver.OnGlobalLayoutListener, Filter.FilterListener,
        ViewTreeObserver.OnTouchModeChangeListener,
        RemoteViewsAdapter.RemoteAdapterConnectionCallback {

    @SuppressWarnings("UnusedDeclaration")
    private static final String TAG = "AbsListView";

    /**
     * Disables the transcript mode.
     *
     * @see #setTranscriptMode(int)
     */
    public static final int TRANSCRIPT_MODE_DISABLED = 0;

    /**
     * The list will automatically scroll to the bottom when a data set change
     * notification is received and only if the last item is already visible
     * on screen.
     *
     * @see #setTranscriptMode(int)
     */
    public static final int TRANSCRIPT_MODE_NORMAL = 1;

    /**
     * The list will automatically scroll to the bottom, no matter what items
     * are currently visible.
     *
     * @see #setTranscriptMode(int)
     */
    public static final int TRANSCRIPT_MODE_ALWAYS_SCROLL = 2;

    /**
     * Indicates that we are not in the middle of a touch gesture
     */
    static final int TOUCH_MODE_REST = -1;

    /**
     * Indicates we just received the touch event and we are waiting to see if the it is a tap or a
     * scroll gesture.
     */
    static final int TOUCH_MODE_DOWN = 0;

    /**
     * Indicates the touch has been recognized as a tap and we are now waiting to see if the touch
     * is a longpress
     */
    static final int TOUCH_MODE_TAP = 1;

    /**
     * Indicates we have waited for everything we can wait for, but the user's finger is still down
     */
    static final int TOUCH_MODE_DONE_WAITING = 2;

    /**
     * Indicates the touch gesture is a scroll
     */
    static final int TOUCH_MODE_SCROLL = 3;

    /**
     * Indicates the view is in the process of being flung
     */
    static final int TOUCH_MODE_FLING = 4;

    /**
     * Indicates the touch gesture is an overscroll - a scroll beyond the beginning or end.
     */
    static final int TOUCH_MODE_OVERSCROLL = 5;

    /**
     * Indicates the view is being flung outside of normal content bounds
     * and will spring back.
     */
    static final int TOUCH_MODE_OVERFLING = 6;

    /**
     * Regular layout - usually an unsolicited layout from the view system
     */
    static final int LAYOUT_NORMAL = 0;

    /**
     * Show the first item
     */
    static final int LAYOUT_FORCE_TOP = 1;

    /**
     * Force the selected item to be on somewhere on the screen
     */
    static final int LAYOUT_SET_SELECTION = 2;

    /**
     * Show the last item
     */
    static final int LAYOUT_FORCE_BOTTOM = 3;

    /**
     * Make a mSelectedItem appear in a specific location and build the rest of
     * the views from there. The top is specified by mSpecificTop.
     */
    static final int LAYOUT_SPECIFIC = 4;

    /**
     * Layout to sync as a result of a data change. Restore mSyncPosition to have its top
     * at mSpecificTop
     */
    static final int LAYOUT_SYNC = 5;

    /**
     * Layout as a result of using the navigation keys
     */
    static final int LAYOUT_MOVE_SELECTION = 6;

    /**
     * Normal list that does not indicate choices
     */
    public static final int CHOICE_MODE_NONE = 0;

    /**
     * The list allows up to one choice
     */
    public static final int CHOICE_MODE_SINGLE = 1;

    /**
     * The list allows multiple choices
     */
    public static final int CHOICE_MODE_MULTIPLE = 2;

    /**
     * The list allows multiple choices in a modal selection mode
     */
    public static final int CHOICE_MODE_MULTIPLE_MODAL = 3;

    /**
     * The thread that created this view.
     */
    private final Thread mOwnerThread;

    /**
     * Controls if/how the user may choose/check items in the list
     */
    int mChoiceMode = CHOICE_MODE_NONE;

    /**
     * Controls CHOICE_MODE_MULTIPLE_MODAL. null when inactive.
     */
    @UnsupportedAppUsage
    ActionMode mChoiceActionMode;

    /**
     * Wrapper for the multiple choice mode callback; AbsListView needs to perform
     * a few extra actions around what application code does.
     */
    MultiChoiceModeWrapper mMultiChoiceModeCallback;

    /**
     * Running count of how many items are currently checked
     */
    int mCheckedItemCount;

    /**
     * Running state of which positions are currently checked
     */
    SparseBooleanArray mCheckStates;

    /**
     * Running state of which IDs are currently checked.
     * If there is a value for a given key, the checked state for that ID is true
     * and the value holds the last known position in the adapter for that id.
     */
    LongSparseArray<Integer> mCheckedIdStates;

    /**
     * Controls how the next layout will happen
     */
    @UnsupportedAppUsage
    int mLayoutMode = LAYOUT_NORMAL;

    /**
     * Should be used by subclasses to listen to changes in the dataset
     */
    @UnsupportedAppUsage
    AdapterDataSetObserver mDataSetObserver;

    /**
     * The adapter containing the data to be displayed by this view
     */
    @UnsupportedAppUsage
    ListAdapter mAdapter;

    /**
     * The remote adapter containing the data to be displayed by this view to be set
     */
    private RemoteViewsAdapter mRemoteAdapter;

    /**
     * If mAdapter != null, whenever this is true the adapter has stable IDs.
     */
    boolean mAdapterHasStableIds;

    /**
     * This flag indicates the a full notify is required when the RemoteViewsAdapter connects
     */
    private boolean mDeferNotifyDataSetChanged = false;

    /**
     * Indicates whether the list selector should be drawn on top of the children or behind
     */
    boolean mDrawSelectorOnTop = false;

    /**
     * The drawable used to draw the selector
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    Drawable mSelector;

    /**
     * The current position of the selector in the list.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    int mSelectorPosition = INVALID_POSITION;

    /**
     * Defines the selector's location and dimension at drawing time
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    Rect mSelectorRect = new Rect();

    /**
     * The data set used to store unused views that should be reused during the next layout
     * to avoid creating new ones
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 123769398)
    final RecycleBin mRecycler = new RecycleBin();

    /**
     * The selection's left padding
     */
    int mSelectionLeftPadding = 0;

    /**
     * The selection's top padding
     */
    @UnsupportedAppUsage
    int mSelectionTopPadding = 0;

    /**
     * The selection's right padding
     */
    int mSelectionRightPadding = 0;

    /**
     * The selection's bottom padding
     */
    @UnsupportedAppUsage
    int mSelectionBottomPadding = 0;

    /**
     * This view's padding
     */
    Rect mListPadding = new Rect();

    /**
     * Subclasses must retain their measure spec from onMeasure() into this member
     */
    int mWidthMeasureSpec = 0;

    /**
     * The top scroll indicator
     */
    View mScrollUp;

    /**
     * The down scroll indicator
     */
    View mScrollDown;

    /**
     * When the view is scrolling, this flag is set to true to indicate subclasses that
     * the drawing cache was enabled on the children
     */
    boolean mCachingStarted;
    boolean mCachingActive;

    /**
     * The position of the view that received the down motion event
     */
    @UnsupportedAppUsage
    int mMotionPosition;

    /**
     * The offset to the top of the mMotionPosition view when the down motion event was received
     */
    int mMotionViewOriginalTop;

    /**
     * The desired offset to the top of the mMotionPosition view after a scroll
     */
    int mMotionViewNewTop;

    /**
     * The X value associated with the the down motion event
     */
    int mMotionX;

    /**
     * The Y value associated with the the down motion event
     */
    @UnsupportedAppUsage
    int mMotionY;

    /**
     * One of TOUCH_MODE_REST, TOUCH_MODE_DOWN, TOUCH_MODE_TAP, TOUCH_MODE_SCROLL, or
     * TOUCH_MODE_DONE_WAITING
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 123769413)
    int mTouchMode = TOUCH_MODE_REST;

    /**
     * Y value from on the previous motion event (if any)
     */
    int mLastY;

    /**
     * How far the finger moved before we started scrolling
     */
    int mMotionCorrection;

    /**
     * Determines speed during touch scrolling
     */
    @UnsupportedAppUsage
    private VelocityTracker mVelocityTracker;

    /**
     * Handles one frame of a fling
     *
     * To interrupt a fling early you should use smoothScrollBy(0,0) instead
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private FlingRunnable mFlingRunnable;

    /**
     * Handles scrolling between positions within the list.
     */
    @UnsupportedAppUsage
    AbsPositionScroller mPositionScroller;

    /**
     * The offset in pixels form the top of the AdapterView to the top
     * of the currently selected view. Used to save and restore state.
     */
    int mSelectedTop = 0;

    /**
     * Indicates whether the list is stacked from the bottom edge or
     * the top edge.
     */
    boolean mStackFromBottom;

    /**
     * When set to true, the list automatically discards the children's
     * bitmap cache after scrolling.
     */
    boolean mScrollingCacheEnabled;

    /**
     * Whether or not to enable the fast scroll feature on this list
     */
    boolean mFastScrollEnabled;

    /**
     * Whether or not to always show the fast scroll feature on this list
     */
    boolean mFastScrollAlwaysVisible;

    /**
     * Optional callback to notify client when scroll position has changed
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 123769353)
    private OnScrollListener mOnScrollListener;

    /**
     * Keeps track of our accessory window
     */
    @UnsupportedAppUsage
    PopupWindow mPopup;

    /**
     * Used with type filter window
     */
    EditText mTextFilter;

    /**
     * Indicates whether to use pixels-based or position-based scrollbar
     * properties.
     */
    private boolean mSmoothScrollbarEnabled = true;

    /**
     * Indicates that this view supports filtering
     */
    private boolean mTextFilterEnabled;

    /**
     * Indicates that this view is currently displaying a filtered view of the data
     */
    private boolean mFiltered;

    /**
     * Rectangle used for hit testing children
     */
    private Rect mTouchFrame;

    /**
     * The position to resurrect the selected position to.
     */
    int mResurrectToPosition = INVALID_POSITION;

    @UnsupportedAppUsage
    private ContextMenuInfo mContextMenuInfo = null;

    /**
     * Maximum distance to record overscroll
     */
    int mOverscrollMax;

    /**
     * Content height divided by this is the overscroll limit.
     */
    static final int OVERSCROLL_LIMIT_DIVISOR = 3;

    /**
     * How many positions in either direction we will search to try to
     * find a checked item with a stable ID that moved position across
     * a data set change. If the item isn't found it will be unselected.
     */
    private static final int CHECK_POSITION_SEARCH_DISTANCE = 20;

    /**
     * Used to request a layout when we changed touch mode
     */
    private static final int TOUCH_MODE_UNKNOWN = -1;
    private static final int TOUCH_MODE_ON = 0;
    private static final int TOUCH_MODE_OFF = 1;

    private int mLastTouchMode = TOUCH_MODE_UNKNOWN;

    private static final boolean PROFILE_SCROLLING = false;
    private boolean mScrollProfilingStarted = false;

    private static final boolean PROFILE_FLINGING = false;
    private boolean mFlingProfilingStarted = false;

    /**
     * The StrictMode "critical time span" objects to catch animation
     * stutters.  Non-null when a time-sensitive animation is
     * in-flight.  Must call finish() on them when done animating.
     * These are no-ops on user builds.
     */
    private StrictMode.Span mScrollStrictSpan = null;
    private StrictMode.Span mFlingStrictSpan = null;

    /**
     * The last CheckForLongPress runnable we posted, if any
     */
    @UnsupportedAppUsage
    private CheckForLongPress mPendingCheckForLongPress;

    /**
     * The last CheckForTap runnable we posted, if any
     */
    @UnsupportedAppUsage
    private CheckForTap mPendingCheckForTap;

    /**
     * The last CheckForKeyLongPress runnable we posted, if any
     */
    private CheckForKeyLongPress mPendingCheckForKeyLongPress;

    /**
     * Acts upon click
     */
    private AbsListView.PerformClick mPerformClick;

    /**
     * Delayed action for touch mode.
     */
    private Runnable mTouchModeReset;

    /**
     * Whether the most recent touch event stream resulted in a successful
     * long-press action. This is reset on TOUCH_DOWN.
     */
    private boolean mHasPerformedLongPress;

    /**
     * This view is in transcript mode -- it shows the bottom of the list when the data
     * changes
     */
    private int mTranscriptMode;

    /**
     * Indicates that this list is always drawn on top of a solid, single-color, opaque
     * background
     */
    private int mCacheColorHint;

    /**
     * The select child's view (from the adapter's getView) is enabled.
     */
    @UnsupportedAppUsage
    private boolean mIsChildViewEnabled;

    /**
     * The cached drawable state for the selector. Accounts for child enabled
     * state, but otherwise identical to the view's own drawable state.
     */
    private int[] mSelectorState;

    /**
     * The last scroll state reported to clients through {@link OnScrollListener}.
     */
    private int mLastScrollState = OnScrollListener.SCROLL_STATE_IDLE;

    /**
     * Helper object that renders and controls the fast scroll thumb.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 123768941)
    private FastScroller mFastScroll;

    /**
     * Temporary holder for fast scroller style until a FastScroller object
     * is created.
     */
    private int mFastScrollStyle;

    private boolean mGlobalLayoutListenerAddedFilter;

    @UnsupportedAppUsage
    private int mTouchSlop;
    private float mDensityScale;

    private float mVerticalScrollFactor;

    private InputConnection mDefInputConnection;
    private InputConnectionWrapper mPublicInputConnection;

    private Runnable mClearScrollingCache;
    Runnable mPositionScrollAfterLayout;
    private int mMinimumVelocity;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 124051740)
    private int mMaximumVelocity;
    private float mVelocityScale = 1.0f;

    final boolean[] mIsScrap = new boolean[1];

    private final int[] mScrollOffset = new int[2];
    private final int[] mScrollConsumed = new int[2];

    private final float[] mTmpPoint = new float[2];

    // Used for offsetting MotionEvents that we feed to the VelocityTracker.
    // In the future it would be nice to be able to give this to the VelocityTracker
    // directly, or alternatively put a VT into absolute-positioning mode that only
    // reads the raw screen-coordinate x/y values.
    private int mNestedYOffset = 0;

    // True when the popup should be hidden because of a call to
    // dispatchDisplayHint()
    private boolean mPopupHidden;

    /**
     * ID of the active pointer. This is used to retain consistency during
     * drags/flings if multiple pointers are used.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mActivePointerId = INVALID_POINTER;

    /**
     * Sentinel value for no current active pointer.
     * Used by {@link #mActivePointerId}.
     */
    private static final int INVALID_POINTER = -1;

    /**
     * Maximum distance to overscroll by during edge effects
     */
    @UnsupportedAppUsage
    int mOverscrollDistance;

    /**
     * Maximum distance to overfling during edge effects
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 123769379)
    int mOverflingDistance;

    // These two EdgeGlows are always set and used together.
    // Checking one for null is as good as checking both.

    /**
     * Tracks the state of the top edge glow.
     *
     * Even though this field is practically final, we cannot make it final because there are apps
     * setting it via reflection and they need to keep working until they target Q.
     */
    @NonNull
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 123769408)
    private EdgeEffect mEdgeGlowTop = new EdgeEffect(mContext);

    /**
     * Tracks the state of the bottom edge glow.
     *
     * Even though this field is practically final, we cannot make it final because there are apps
     * setting it via reflection and they need to keep working until they target Q.
     */
    @NonNull
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 123768444)
    private EdgeEffect mEdgeGlowBottom = new EdgeEffect(mContext);

    /**
     * An estimate of how many pixels are between the top of the list and
     * the top of the first position in the adapter, based on the last time
     * we saw it. Used to hint where to draw edge glows.
     */
    private int mFirstPositionDistanceGuess;

    /**
     * An estimate of how many pixels are between the bottom of the list and
     * the bottom of the last position in the adapter, based on the last time
     * we saw it. Used to hint where to draw edge glows.
     */
    private int mLastPositionDistanceGuess;

    /**
     * Used for determining when to cancel out of overscroll.
     */
    private int mDirection = 0;

    /**
     * Tracked on measurement in transcript mode. Makes sure that we can still pin to
     * the bottom correctly on resizes.
     */
    private boolean mForceTranscriptScroll;

    /**
     * Used for interacting with list items from an accessibility service.
     */
    private ListItemAccessibilityDelegate mAccessibilityDelegate;

    private int mLastAccessibilityScrollEventFromIndex;
    private int mLastAccessibilityScrollEventToIndex;

    /**
     * Track the item count from the last time we handled a data change.
     */
    private int mLastHandledItemCount;

    /**
     * Used for smooth scrolling at a consistent rate
     */
    static final Interpolator sLinearInterpolator = new LinearInterpolator();

    /**
     * The saved state that we will be restoring from when we next sync.
     * Kept here so that if we happen to be asked to save our state before
     * the sync happens, we can return this existing data rather than losing
     * it.
     */
    private SavedState mPendingSync;

    /**
     * Whether the view is in the process of detaching from its window.
     */
    private boolean mIsDetaching;

    /**
     * Interface definition for a callback to be invoked when the list or grid
     * has been scrolled.
     */
    public interface OnScrollListener {

        /**
         * The view is not scrolling. Note navigating the list using the trackball counts as
         * being in the idle state since these transitions are not animated.
         */
        public static int SCROLL_STATE_IDLE = 0;

        /**
         * The user is scrolling using touch, and their finger is still on the screen
         */
        public static int SCROLL_STATE_TOUCH_SCROLL = 1;

        /**
         * The user had previously been scrolling using touch and had performed a fling. The
         * animation is now coasting to a stop
         */
        public static int SCROLL_STATE_FLING = 2;

        /**
         * Callback method to be invoked while the list view or grid view is being scrolled. If the
         * view is being scrolled, this method will be called before the next frame of the scroll is
         * rendered. In particular, it will be called before any calls to
         * {@link Adapter#getView(int, View, ViewGroup)}.
         *
         * @param view The view whose scroll state is being reported
         *
         * @param scrollState The current scroll state. One of
         * {@link #SCROLL_STATE_TOUCH_SCROLL} or {@link #SCROLL_STATE_IDLE}.
         */
        public void onScrollStateChanged(AbsListView view, int scrollState);

        /**
         * Callback method to be invoked when the list or grid has been scrolled. This will be
         * called after the scroll has completed
         * @param view The view whose scroll state is being reported
         * @param firstVisibleItem the index of the first visible cell (ignore if
         *        visibleItemCount == 0)
         * @param visibleItemCount the number of visible cells
         * @param totalItemCount the number of items in the list adapter
         */
        public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                int totalItemCount);
    }

    /**
     * The top-level view of a list item can implement this interface to allow
     * itself to modify the bounds of the selection shown for that item.
     */
    public interface SelectionBoundsAdjuster {
        /**
         * Called to allow the list item to adjust the bounds shown for
         * its selection.
         *
         * @param bounds On call, this contains the bounds the list has
         * selected for the item (that is the bounds of the entire view).  The
         * values can be modified as desired.
         */
        public void adjustListItemSelectionBounds(Rect bounds);
    }

    public AbsListView(Context context) {
        super(context);
        initAbsListView();

        mOwnerThread = Thread.currentThread();

        setVerticalScrollBarEnabled(true);
        TypedArray a = context.obtainStyledAttributes(R.styleable.View);
        initializeScrollbarsInternal(a);
        a.recycle();
    }

    public AbsListView(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.absListViewStyle);
    }

    public AbsListView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public AbsListView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initAbsListView();

        mOwnerThread = Thread.currentThread();

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.AbsListView, defStyleAttr, defStyleRes);
        saveAttributeDataForStyleable(context, R.styleable.AbsListView, attrs, a, defStyleAttr,
                defStyleRes);

        final Drawable selector = a.getDrawable(R.styleable.AbsListView_listSelector);
        if (selector != null) {
            setSelector(selector);
        }

        mDrawSelectorOnTop = a.getBoolean(R.styleable.AbsListView_drawSelectorOnTop, false);

        setStackFromBottom(a.getBoolean(
                R.styleable.AbsListView_stackFromBottom, false));
        setScrollingCacheEnabled(a.getBoolean(
                R.styleable.AbsListView_scrollingCache, true));
        setTextFilterEnabled(a.getBoolean(
                R.styleable.AbsListView_textFilterEnabled, false));
        setTranscriptMode(a.getInt(
                R.styleable.AbsListView_transcriptMode, TRANSCRIPT_MODE_DISABLED));
        setCacheColorHint(a.getColor(
                R.styleable.AbsListView_cacheColorHint, 0));
        setSmoothScrollbarEnabled(a.getBoolean(
                R.styleable.AbsListView_smoothScrollbar, true));
        setChoiceMode(a.getInt(
                R.styleable.AbsListView_choiceMode, CHOICE_MODE_NONE));

        setFastScrollEnabled(a.getBoolean(
                R.styleable.AbsListView_fastScrollEnabled, false));
        setFastScrollStyle(a.getResourceId(
                R.styleable.AbsListView_fastScrollStyle, 0));
        setFastScrollAlwaysVisible(a.getBoolean(
                R.styleable.AbsListView_fastScrollAlwaysVisible, false));

        a.recycle();

        if (context.getResources().getConfiguration().uiMode == Configuration.UI_MODE_TYPE_WATCH) {
            setRevealOnFocusHint(false);
        }
    }

    private void initAbsListView() {
        // Setting focusable in touch mode will set the focusable property to true
        setClickable(true);
        setFocusableInTouchMode(true);
        setWillNotDraw(false);
        setAlwaysDrawnWithCacheEnabled(false);
        setScrollingCacheEnabled(true);

        final ViewConfiguration configuration = ViewConfiguration.get(mContext);
        mTouchSlop = configuration.getScaledTouchSlop();
        mVerticalScrollFactor = configuration.getScaledVerticalScrollFactor();
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mOverscrollDistance = configuration.getScaledOverscrollDistance();
        mOverflingDistance = configuration.getScaledOverflingDistance();

        mDensityScale = getContext().getResources().getDisplayMetrics().density;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAdapter(ListAdapter adapter) {
        if (adapter != null) {
            mAdapterHasStableIds = mAdapter.hasStableIds();
            if (mChoiceMode != CHOICE_MODE_NONE && mAdapterHasStableIds &&
                    mCheckedIdStates == null) {
                mCheckedIdStates = new LongSparseArray<Integer>();
            }
        }
        clearChoices();
    }

    /**
     * Returns the number of items currently selected. This will only be valid
     * if the choice mode is not {@link #CHOICE_MODE_NONE} (default).
     *
     * <p>To determine the specific items that are currently selected, use one of
     * the <code>getChecked*</code> methods.
     *
     * @return The number of items currently selected
     *
     * @see #getCheckedItemPosition()
     * @see #getCheckedItemPositions()
     * @see #getCheckedItemIds()
     */
    public int getCheckedItemCount() {
        return mCheckedItemCount;
    }

    /**
     * Returns the checked state of the specified position. The result is only
     * valid if the choice mode has been set to {@link #CHOICE_MODE_SINGLE}
     * or {@link #CHOICE_MODE_MULTIPLE}.
     *
     * @param position The item whose checked state to return
     * @return The item's checked state or <code>false</code> if choice mode
     *         is invalid
     *
     * @see #setChoiceMode(int)
     */
    public boolean isItemChecked(int position) {
        if (mChoiceMode != CHOICE_MODE_NONE && mCheckStates != null) {
            return mCheckStates.get(position);
        }

        return false;
    }

    /**
     * Returns the currently checked item. The result is only valid if the choice
     * mode has been set to {@link #CHOICE_MODE_SINGLE}.
     *
     * @return The position of the currently checked item or
     *         {@link #INVALID_POSITION} if nothing is selected
     *
     * @see #setChoiceMode(int)
     */
    public int getCheckedItemPosition() {
        if (mChoiceMode == CHOICE_MODE_SINGLE && mCheckStates != null && mCheckStates.size() == 1) {
            return mCheckStates.keyAt(0);
        }

        return INVALID_POSITION;
    }

    /**
     * Returns the set of checked items in the list. The result is only valid if
     * the choice mode has not been set to {@link #CHOICE_MODE_NONE}.
     *
     * @return  A SparseBooleanArray which will return true for each call to
     *          get(int position) where position is a checked position in the
     *          list and false otherwise, or <code>null</code> if the choice
     *          mode is set to {@link #CHOICE_MODE_NONE}.
     */
    public SparseBooleanArray getCheckedItemPositions() {
        if (mChoiceMode != CHOICE_MODE_NONE) {
            return mCheckStates;
        }
        return null;
    }

    /**
     * Returns the set of checked items ids. The result is only valid if the
     * choice mode has not been set to {@link #CHOICE_MODE_NONE} and the adapter
     * has stable IDs. ({@link ListAdapter#hasStableIds()} == {@code true})
     *
     * @return A new array which contains the id of each checked item in the
     *         list.
     */
    public long[] getCheckedItemIds() {
        if (mChoiceMode == CHOICE_MODE_NONE || mCheckedIdStates == null || mAdapter == null) {
            return new long[0];
        }

        final LongSparseArray<Integer> idStates = mCheckedIdStates;
        final int count = idStates.size();
        final long[] ids = new long[count];

        for (int i = 0; i < count; i++) {
            ids[i] = idStates.keyAt(i);
        }

        return ids;
    }

    /**
     * Clear any choices previously set
     */
    public void clearChoices() {
        if (mCheckStates != null) {
            mCheckStates.clear();
        }
        if (mCheckedIdStates != null) {
            mCheckedIdStates.clear();
        }
        mCheckedItemCount = 0;
    }

    /**
     * Sets the checked state of the specified position. The is only valid if
     * the choice mode has been set to {@link #CHOICE_MODE_SINGLE} or
     * {@link #CHOICE_MODE_MULTIPLE}.
     *
     * @param position The item whose checked state is to be checked
     * @param value The new checked state for the item
     */
    public void setItemChecked(int position, boolean value) {
        if (mChoiceMode == CHOICE_MODE_NONE) {
            return;
        }

        // Start selection mode if needed. We don't need to if we're unchecking something.
        if (value && mChoiceMode == CHOICE_MODE_MULTIPLE_MODAL && mChoiceActionMode == null) {
            if (mMultiChoiceModeCallback == null ||
                    !mMultiChoiceModeCallback.hasWrappedCallback()) {
                throw new IllegalStateException("AbsListView: attempted to start selection mode " +
                        "for CHOICE_MODE_MULTIPLE_MODAL but no choice mode callback was " +
                        "supplied. Call setMultiChoiceModeListener to set a callback.");
            }
            mChoiceActionMode = startActionMode(mMultiChoiceModeCallback);
        }

        final boolean itemCheckChanged;
        if (mChoiceMode == CHOICE_MODE_MULTIPLE || mChoiceMode == CHOICE_MODE_MULTIPLE_MODAL) {
            boolean oldValue = mCheckStates.get(position);
            mCheckStates.put(position, value);
            if (mCheckedIdStates != null && mAdapter.hasStableIds()) {
                if (value) {
                    mCheckedIdStates.put(mAdapter.getItemId(position), position);
                } else {
                    mCheckedIdStates.delete(mAdapter.getItemId(position));
                }
            }
            itemCheckChanged = oldValue != value;
            if (itemCheckChanged) {
                if (value) {
                    mCheckedItemCount++;
                } else {
                    mCheckedItemCount--;
                }
            }
            if (mChoiceActionMode != null) {
                final long id = mAdapter.getItemId(position);
                mMultiChoiceModeCallback.onItemCheckedStateChanged(mChoiceActionMode,
                        position, id, value);
            }
        } else {
            boolean updateIds = mCheckedIdStates != null && mAdapter.hasStableIds();
            // Clear all values if we're checking something, or unchecking the currently
            // selected item
            itemCheckChanged = isItemChecked(position) != value;
            if (value || isItemChecked(position)) {
                mCheckStates.clear();
                if (updateIds) {
                    mCheckedIdStates.clear();
                }
            }
            // this may end up selecting the value we just cleared but this way
            // we ensure length of mCheckStates is 1, a fact getCheckedItemPosition relies on
            if (value) {
                mCheckStates.put(position, true);
                if (updateIds) {
                    mCheckedIdStates.put(mAdapter.getItemId(position), position);
                }
                mCheckedItemCount = 1;
            } else if (mCheckStates.size() == 0 || !mCheckStates.valueAt(0)) {
                mCheckedItemCount = 0;
            }
        }

        // Do not generate a data change while we are in the layout phase or data has not changed
        if (!mInLayout && !mBlockLayoutRequests && itemCheckChanged) {
            mDataChanged = true;
            rememberSyncState();
            requestLayout();
        }
    }

    @Override
    public boolean performItemClick(View view, int position, long id) {
        boolean handled = false;
        boolean dispatchItemClick = true;

        if (mChoiceMode != CHOICE_MODE_NONE) {
            handled = true;
            boolean checkedStateChanged = false;

            if (mChoiceMode == CHOICE_MODE_MULTIPLE ||
                    (mChoiceMode == CHOICE_MODE_MULTIPLE_MODAL && mChoiceActionMode != null)) {
                boolean checked = !mCheckStates.get(position, false);
                mCheckStates.put(position, checked);
                if (mCheckedIdStates != null && mAdapter.hasStableIds()) {
                    if (checked) {
                        mCheckedIdStates.put(mAdapter.getItemId(position), position);
                    } else {
                        mCheckedIdStates.delete(mAdapter.getItemId(position));
                    }
                }
                if (checked) {
                    mCheckedItemCount++;
                } else {
                    mCheckedItemCount--;
                }
                if (mChoiceActionMode != null) {
                    mMultiChoiceModeCallback.onItemCheckedStateChanged(mChoiceActionMode,
                            position, id, checked);
                    dispatchItemClick = false;
                }
                checkedStateChanged = true;
            } else if (mChoiceMode == CHOICE_MODE_SINGLE) {
                boolean checked = !mCheckStates.get(position, false);
                if (checked) {
                    mCheckStates.clear();
                    mCheckStates.put(position, true);
                    if (mCheckedIdStates != null && mAdapter.hasStableIds()) {
                        mCheckedIdStates.clear();
                        mCheckedIdStates.put(mAdapter.getItemId(position), position);
                    }
                    mCheckedItemCount = 1;
                } else if (mCheckStates.size() == 0 || !mCheckStates.valueAt(0)) {
                    mCheckedItemCount = 0;
                }
                checkedStateChanged = true;
            }

            if (checkedStateChanged) {
                updateOnScreenCheckedViews();
            }
        }

        if (dispatchItemClick) {
            handled |= super.performItemClick(view, position, id);
        }

        return handled;
    }

    /**
     * Perform a quick, in-place update of the checked or activated state
     * on all visible item views. This should only be called when a valid
     * choice mode is active.
     */
    private void updateOnScreenCheckedViews() {
        final int firstPos = mFirstPosition;
        final int count = getChildCount();
        final boolean useActivated = getContext().getApplicationInfo().targetSdkVersion
                >= android.os.Build.VERSION_CODES.HONEYCOMB;
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            final int position = firstPos + i;

            if (child instanceof Checkable) {
                ((Checkable) child).setChecked(mCheckStates.get(position));
            } else if (useActivated) {
                child.setActivated(mCheckStates.get(position));
            }
        }
    }

    /**
     * @see #setChoiceMode(int)
     *
     * @return The current choice mode
     */
    @InspectableProperty(enumMapping = {
            @EnumEntry(value = CHOICE_MODE_NONE, name = "none"),
            @EnumEntry(value = CHOICE_MODE_SINGLE, name = "singleChoice"),
            @InspectableProperty.EnumEntry(value = CHOICE_MODE_MULTIPLE, name = "multipleChoice"),
            @EnumEntry(value = CHOICE_MODE_MULTIPLE_MODAL, name = "multipleChoiceModal")
    })
    public int getChoiceMode() {
        return mChoiceMode;
    }

    /**
     * Defines the choice behavior for the List. By default, Lists do not have any choice behavior
     * ({@link #CHOICE_MODE_NONE}). By setting the choiceMode to {@link #CHOICE_MODE_SINGLE}, the
     * List allows up to one item to  be in a chosen state. By setting the choiceMode to
     * {@link #CHOICE_MODE_MULTIPLE}, the list allows any number of items to be chosen.
     *
     * @param choiceMode One of {@link #CHOICE_MODE_NONE}, {@link #CHOICE_MODE_SINGLE}, or
     * {@link #CHOICE_MODE_MULTIPLE}
     */
    public void setChoiceMode(int choiceMode) {
        mChoiceMode = choiceMode;
        if (mChoiceActionMode != null) {
            mChoiceActionMode.finish();
            mChoiceActionMode = null;
        }
        if (mChoiceMode != CHOICE_MODE_NONE) {
            if (mCheckStates == null) {
                mCheckStates = new SparseBooleanArray(0);
            }
            if (mCheckedIdStates == null && mAdapter != null && mAdapter.hasStableIds()) {
                mCheckedIdStates = new LongSparseArray<Integer>(0);
            }
            // Modal multi-choice mode only has choices when the mode is active. Clear them.
            if (mChoiceMode == CHOICE_MODE_MULTIPLE_MODAL) {
                clearChoices();
                setLongClickable(true);
            }
        }
    }

    /**
     * Set a {@link MultiChoiceModeListener} that will manage the lifecycle of the
     * selection {@link ActionMode}. Only used when the choice mode is set to
     * {@link #CHOICE_MODE_MULTIPLE_MODAL}.
     *
     * @param listener Listener that will manage the selection mode
     *
     * @see #setChoiceMode(int)
     */
    public void setMultiChoiceModeListener(MultiChoiceModeListener listener) {
        if (mMultiChoiceModeCallback == null) {
            mMultiChoiceModeCallback = new MultiChoiceModeWrapper();
        }
        mMultiChoiceModeCallback.setWrapped(listener);
    }

    /**
     * @return true if all list content currently fits within the view boundaries
     */
    private boolean contentFits() {
        final int childCount = getChildCount();
        if (childCount == 0) return true;
        if (childCount != mItemCount) return false;

        return getChildAt(0).getTop() >= mListPadding.top &&
                getChildAt(childCount - 1).getBottom() <= getHeight() - mListPadding.bottom;
    }

    /**
     * Specifies whether fast scrolling is enabled or disabled.
     * <p>
     * When fast scrolling is enabled, the user can quickly scroll through lists
     * by dragging the fast scroll thumb.
     * <p>
     * If the adapter backing this list implements {@link SectionIndexer}, the
     * fast scroller will display section header previews as the user scrolls.
     * Additionally, the user will be able to quickly jump between sections by
     * tapping along the length of the scroll bar.
     *
     * @see SectionIndexer
     * @see #isFastScrollEnabled()
     * @param enabled true to enable fast scrolling, false otherwise
     */
    public void setFastScrollEnabled(final boolean enabled) {
        if (mFastScrollEnabled != enabled) {
            mFastScrollEnabled = enabled;

            if (isOwnerThread()) {
                setFastScrollerEnabledUiThread(enabled);
            } else {
                post(new Runnable() {
                    @Override
                    public void run() {
                        setFastScrollerEnabledUiThread(enabled);
                    }
                });
            }
        }
    }

    private void setFastScrollerEnabledUiThread(boolean enabled) {
        if (mFastScroll != null) {
            mFastScroll.setEnabled(enabled);
        } else if (enabled) {
            mFastScroll = new FastScroller(this, mFastScrollStyle);
            mFastScroll.setEnabled(true);
        }

        resolvePadding();

        if (mFastScroll != null) {
            mFastScroll.updateLayout();
        }
    }

    /**
     * Specifies the style of the fast scroller decorations.
     *
     * @param styleResId style resource containing fast scroller properties
     * @see android.R.styleable#FastScroll
     */
    public void setFastScrollStyle(int styleResId) {
        if (mFastScroll == null) {
            mFastScrollStyle = styleResId;
        } else {
            mFastScroll.setStyle(styleResId);
        }
    }

    /**
     * Set whether or not the fast scroller should always be shown in place of
     * the standard scroll bars. This will enable fast scrolling if it is not
     * already enabled.
     * <p>
     * Fast scrollers shown in this way will not fade out and will be a
     * permanent fixture within the list. This is best combined with an inset
     * scroll bar style to ensure the scroll bar does not overlap content.
     *
     * @param alwaysShow true if the fast scroller should always be displayed,
     *            false otherwise
     * @see #setScrollBarStyle(int)
     * @see #setFastScrollEnabled(boolean)
     */
    public void setFastScrollAlwaysVisible(final boolean alwaysShow) {
        if (mFastScrollAlwaysVisible != alwaysShow) {
            if (alwaysShow && !mFastScrollEnabled) {
                setFastScrollEnabled(true);
            }

            mFastScrollAlwaysVisible = alwaysShow;

            if (isOwnerThread()) {
                setFastScrollerAlwaysVisibleUiThread(alwaysShow);
            } else {
                post(new Runnable() {
                    @Override
                    public void run() {
                        setFastScrollerAlwaysVisibleUiThread(alwaysShow);
                    }
                });
            }
        }
    }

    private void setFastScrollerAlwaysVisibleUiThread(boolean alwaysShow) {
        if (mFastScroll != null) {
            mFastScroll.setAlwaysShow(alwaysShow);
        }
    }

    /**
     * @return whether the current thread is the one that created the view
     */
    private boolean isOwnerThread() {
        return mOwnerThread == Thread.currentThread();
    }

    /**
     * Returns true if the fast scroller is set to always show on this view.
     *
     * @return true if the fast scroller will always show
     * @see #setFastScrollAlwaysVisible(boolean)
     */
    public boolean isFastScrollAlwaysVisible() {
        if (mFastScroll == null) {
            return mFastScrollEnabled && mFastScrollAlwaysVisible;
        } else {
            return mFastScroll.isEnabled() && mFastScroll.isAlwaysShowEnabled();
        }
    }

    @Override
    public int getVerticalScrollbarWidth() {
        if (mFastScroll != null && mFastScroll.isEnabled()) {
            return Math.max(super.getVerticalScrollbarWidth(), mFastScroll.getWidth());
        }
        return super.getVerticalScrollbarWidth();
    }

    /**
     * Returns true if the fast scroller is enabled.
     *
     * @see #setFastScrollEnabled(boolean)
     * @return true if fast scroll is enabled, false otherwise
     */
    @ViewDebug.ExportedProperty
    @InspectableProperty
    public boolean isFastScrollEnabled() {
        if (mFastScroll == null) {
            return mFastScrollEnabled;
        } else {
            return mFastScroll.isEnabled();
        }
    }

    @Override
    public void setVerticalScrollbarPosition(int position) {
        super.setVerticalScrollbarPosition(position);
        if (mFastScroll != null) {
            mFastScroll.setScrollbarPosition(position);
        }
    }

    @Override
    public void setScrollBarStyle(int style) {
        super.setScrollBarStyle(style);
        if (mFastScroll != null) {
            mFastScroll.setScrollBarStyle(style);
        }
    }

    /**
     * If fast scroll is enabled, then don't draw the vertical scrollbar.
     * @hide
     */
    @Override
    @UnsupportedAppUsage
    protected boolean isVerticalScrollBarHidden() {
        return isFastScrollEnabled();
    }

    /**
     * When smooth scrollbar is enabled, the position and size of the scrollbar thumb
     * is computed based on the number of visible pixels in the visible items. This
     * however assumes that all list items have the same height. If you use a list in
     * which items have different heights, the scrollbar will change appearance as the
     * user scrolls through the list. To avoid this issue, you need to disable this
     * property.
     *
     * When smooth scrollbar is disabled, the position and size of the scrollbar thumb
     * is based solely on the number of items in the adapter and the position of the
     * visible items inside the adapter. This provides a stable scrollbar as the user
     * navigates through a list of items with varying heights.
     *
     * @param enabled Whether or not to enable smooth scrollbar.
     *
     * @see #setSmoothScrollbarEnabled(boolean)
     * @attr ref android.R.styleable#AbsListView_smoothScrollbar
     */
    public void setSmoothScrollbarEnabled(boolean enabled) {
        mSmoothScrollbarEnabled = enabled;
    }

    /**
     * Returns the current state of the fast scroll feature.
     *
     * @return True if smooth scrollbar is enabled is enabled, false otherwise.
     *
     * @see #setSmoothScrollbarEnabled(boolean)
     */
    @ViewDebug.ExportedProperty
    @InspectableProperty(name = "smoothScrollbar")
    public boolean isSmoothScrollbarEnabled() {
        return mSmoothScrollbarEnabled;
    }

    /**
     * Set the listener that will receive notifications every time the list scrolls.
     *
     * @param l the scroll listener
     */
    public void setOnScrollListener(OnScrollListener l) {
        mOnScrollListener = l;
        invokeOnItemScrollListener();
    }

    /**
     * Notify our scroll listener (if there is one) of a change in scroll state
     */
    @UnsupportedAppUsage
    void invokeOnItemScrollListener() {
        if (mFastScroll != null) {
            mFastScroll.onScroll(mFirstPosition, getChildCount(), mItemCount);
        }
        if (mOnScrollListener != null) {
            mOnScrollListener.onScroll(this, mFirstPosition, getChildCount(), mItemCount);
        }
        onScrollChanged(0, 0, 0, 0); // dummy values, View's implementation does not use these.
    }

    /** @hide */
    @Override
    public void sendAccessibilityEventUnchecked(AccessibilityEvent event) {
        // Since this class calls onScrollChanged even if the mFirstPosition and the
        // child count have not changed we will avoid sending duplicate accessibility
        // events.
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            final int firstVisiblePosition = getFirstVisiblePosition();
            final int lastVisiblePosition = getLastVisiblePosition();
            if (mLastAccessibilityScrollEventFromIndex == firstVisiblePosition
                    && mLastAccessibilityScrollEventToIndex == lastVisiblePosition) {
                return;
            } else {
                mLastAccessibilityScrollEventFromIndex = firstVisiblePosition;
                mLastAccessibilityScrollEventToIndex = lastVisiblePosition;
            }
        }
        super.sendAccessibilityEventUnchecked(event);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return AbsListView.class.getName();
    }

    /** @hide */
    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);
        if (isEnabled()) {
            if (canScrollUp()) {
                info.addAction(AccessibilityAction.ACTION_SCROLL_BACKWARD);
                info.addAction(AccessibilityAction.ACTION_SCROLL_UP);
                info.setScrollable(true);
            }
            if (canScrollDown()) {
                info.addAction(AccessibilityAction.ACTION_SCROLL_FORWARD);
                info.addAction(AccessibilityAction.ACTION_SCROLL_DOWN);
                info.setScrollable(true);
            }
        }

        info.removeAction(AccessibilityAction.ACTION_CLICK);
        info.setClickable(false);
    }

    int getSelectionModeForAccessibility() {
        final int choiceMode = getChoiceMode();
        switch (choiceMode) {
            case CHOICE_MODE_NONE:
                return CollectionInfo.SELECTION_MODE_NONE;
            case CHOICE_MODE_SINGLE:
                return CollectionInfo.SELECTION_MODE_SINGLE;
            case CHOICE_MODE_MULTIPLE:
            case CHOICE_MODE_MULTIPLE_MODAL:
                return CollectionInfo.SELECTION_MODE_MULTIPLE;
            default:
                return CollectionInfo.SELECTION_MODE_NONE;
        }
    }

    /** @hide */
    @Override
    public boolean performAccessibilityActionInternal(int action, Bundle arguments) {
        if (super.performAccessibilityActionInternal(action, arguments)) {
            return true;
        }
        switch (action) {
            case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:
            case R.id.accessibilityActionScrollDown: {
                if (isEnabled() && canScrollDown()) {
                    final int viewportHeight = getHeight() - mListPadding.top - mListPadding.bottom;
                    smoothScrollBy(viewportHeight, PositionScroller.SCROLL_DURATION);
                    return true;
                }
            } return false;
            case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:
            case R.id.accessibilityActionScrollUp: {
                if (isEnabled() && canScrollUp()) {
                    final int viewportHeight = getHeight() - mListPadding.top - mListPadding.bottom;
                    smoothScrollBy(-viewportHeight, PositionScroller.SCROLL_DURATION);
                    return true;
                }
            } return false;
        }
        return false;
    }

    /**
     * Indicates whether the children's drawing cache is used during a scroll.
     * By default, the drawing cache is enabled but this will consume more memory.
     *
     * @return true if the scrolling cache is enabled, false otherwise
     *
     * @see #setScrollingCacheEnabled(boolean)
     * @see View#setDrawingCacheEnabled(boolean)
     */
    @ViewDebug.ExportedProperty
    @InspectableProperty(name = "scrollingCache")
    public boolean isScrollingCacheEnabled() {
        return mScrollingCacheEnabled;
    }

    /**
     * Enables or disables the children's drawing cache during a scroll.
     * By default, the drawing cache is enabled but this will use more memory.
     *
     * When the scrolling cache is enabled, the caches are kept after the
     * first scrolling. You can manually clear the cache by calling
     * {@link android.view.ViewGroup#setChildrenDrawingCacheEnabled(boolean)}.
     *
     * @param enabled true to enable the scroll cache, false otherwise
     *
     * @see #isScrollingCacheEnabled()
     * @see View#setDrawingCacheEnabled(boolean)
     */
    public void setScrollingCacheEnabled(boolean enabled) {
        if (mScrollingCacheEnabled && !enabled) {
            clearScrollingCache();
        }
        mScrollingCacheEnabled = enabled;
    }

    /**
     * Enables or disables the type filter window. If enabled, typing when
     * this view has focus will filter the children to match the users input.
     * Note that the {@link Adapter} used by this view must implement the
     * {@link Filterable} interface.
     *
     * @param textFilterEnabled true to enable type filtering, false otherwise
     *
     * @see Filterable
     */
    public void setTextFilterEnabled(boolean textFilterEnabled) {
        mTextFilterEnabled = textFilterEnabled;
    }

    /**
     * Indicates whether type filtering is enabled for this view
     *
     * @return true if type filtering is enabled, false otherwise
     *
     * @see #setTextFilterEnabled(boolean)
     * @see Filterable
     */
    @ViewDebug.ExportedProperty
    @InspectableProperty
    public boolean isTextFilterEnabled() {
        return mTextFilterEnabled;
    }

    @Override
    public void getFocusedRect(Rect r) {
        View view = getSelectedView();
        if (view != null && view.getParent() == this) {
            // the focused rectangle of the selected view offset into the
            // coordinate space of this view.
            view.getFocusedRect(r);
            offsetDescendantRectToMyCoords(view, r);
        } else {
            // otherwise, just the norm
            super.getFocusedRect(r);
        }
    }

    private void useDefaultSelector() {
        setSelector(getContext().getDrawable(
                com.android.internal.R.drawable.list_selector_background));
    }

    /**
     * Indicates whether the content of this view is pinned to, or stacked from,
     * the bottom edge.
     *
     * @return true if the content is stacked from the bottom edge, false otherwise
     */
    @ViewDebug.ExportedProperty
    @InspectableProperty
    public boolean isStackFromBottom() {
        return mStackFromBottom;
    }

    /**
     * When stack from bottom is set to true, the list fills its content starting from
     * the bottom of the view.
     *
     * @param stackFromBottom true to pin the view's content to the bottom edge,
     *        false to pin the view's content to the top edge
     */
    public void setStackFromBottom(boolean stackFromBottom) {
        if (mStackFromBottom != stackFromBottom) {
            mStackFromBottom = stackFromBottom;
            requestLayoutIfNecessary();
        }
    }

    void requestLayoutIfNecessary() {
        if (getChildCount() > 0) {
            resetList();
            requestLayout();
            invalidate();
        }
    }

    static class SavedState extends BaseSavedState {
        long selectedId;
        @UnsupportedAppUsage
        long firstId;
        @UnsupportedAppUsage
        int viewTop;
        int position;
        int height;
        String filter;
        boolean inActionMode;
        int checkedItemCount;
        SparseBooleanArray checkState;
        LongSparseArray<Integer> checkIdState;

        /**
         * Constructor called from {@link AbsListView#onSaveInstanceState()}
         */
        SavedState(Parcelable superState) {
            super(superState);
        }

        /**
         * Constructor called from {@link #CREATOR}
         */
        private SavedState(Parcel in) {
            super(in);
            selectedId = in.readLong();
            firstId = in.readLong();
            viewTop = in.readInt();
            position = in.readInt();
            height = in.readInt();
            filter = in.readString();
            inActionMode = in.readByte() != 0;
            checkedItemCount = in.readInt();
            checkState = in.readSparseBooleanArray();
            final int N = in.readInt();
            if (N > 0) {
                checkIdState = new LongSparseArray<Integer>();
                for (int i=0; i<N; i++) {
                    final long key = in.readLong();
                    final int value = in.readInt();
                    checkIdState.put(key, value);
                }
            }
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeLong(selectedId);
            out.writeLong(firstId);
            out.writeInt(viewTop);
            out.writeInt(position);
            out.writeInt(height);
            out.writeString(filter);
            out.writeByte((byte) (inActionMode ? 1 : 0));
            out.writeInt(checkedItemCount);
            out.writeSparseBooleanArray(checkState);
            final int N = checkIdState != null ? checkIdState.size() : 0;
            out.writeInt(N);
            for (int i=0; i<N; i++) {
                out.writeLong(checkIdState.keyAt(i));
                out.writeInt(checkIdState.valueAt(i));
            }
        }

        @Override
        public String toString() {
            return "AbsListView.SavedState{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " selectedId=" + selectedId
                    + " firstId=" + firstId
                    + " viewTop=" + viewTop
                    + " position=" + position
                    + " height=" + height
                    + " filter=" + filter
                    + " checkState=" + checkState + "}";
        }

        public static final @android.annotation.NonNull Parcelable.Creator<SavedState> CREATOR
                = new Parcelable.Creator<SavedState>() {
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
        /*
         * This doesn't really make sense as the place to dismiss the
         * popups, but there don't seem to be any other useful hooks
         * that happen early enough to keep from getting complaints
         * about having leaked the window.
         */
        dismissPopup();

        Parcelable superState = super.onSaveInstanceState();

        SavedState ss = new SavedState(superState);

        if (mPendingSync != null) {
            // Just keep what we last restored.
            ss.selectedId = mPendingSync.selectedId;
            ss.firstId = mPendingSync.firstId;
            ss.viewTop = mPendingSync.viewTop;
            ss.position = mPendingSync.position;
            ss.height = mPendingSync.height;
            ss.filter = mPendingSync.filter;
            ss.inActionMode = mPendingSync.inActionMode;
            ss.checkedItemCount = mPendingSync.checkedItemCount;
            ss.checkState = mPendingSync.checkState;
            ss.checkIdState = mPendingSync.checkIdState;
            return ss;
        }

        boolean haveChildren = getChildCount() > 0 && mItemCount > 0;
        long selectedId = getSelectedItemId();
        ss.selectedId = selectedId;
        ss.height = getHeight();

        if (selectedId >= 0) {
            // Remember the selection
            ss.viewTop = mSelectedTop;
            ss.position = getSelectedItemPosition();
            ss.firstId = INVALID_POSITION;
        } else {
            if (haveChildren && mFirstPosition > 0) {
                // Remember the position of the first child.
                // We only do this if we are not currently at the top of
                // the list, for two reasons:
                // (1) The list may be in the process of becoming empty, in
                // which case mItemCount may not be 0, but if we try to
                // ask for any information about position 0 we will crash.
                // (2) Being "at the top" seems like a special case, anyway,
                // and the user wouldn't expect to end up somewhere else when
                // they revisit the list even if its content has changed.
                View v = getChildAt(0);
                ss.viewTop = v.getTop();
                int firstPos = mFirstPosition;
                if (firstPos >= mItemCount) {
                    firstPos = mItemCount - 1;
                }
                ss.position = firstPos;
                ss.firstId = mAdapter.getItemId(firstPos);
            } else {
                ss.viewTop = 0;
                ss.firstId = INVALID_POSITION;
                ss.position = 0;
            }
        }

        ss.filter = null;
        if (mFiltered) {
            final EditText textFilter = mTextFilter;
            if (textFilter != null) {
                Editable filterText = textFilter.getText();
                if (filterText != null) {
                    ss.filter = filterText.toString();
                }
            }
        }

        ss.inActionMode = mChoiceMode == CHOICE_MODE_MULTIPLE_MODAL && mChoiceActionMode != null;

        if (mCheckStates != null) {
            ss.checkState = mCheckStates.clone();
        }
        if (mCheckedIdStates != null) {
            final LongSparseArray<Integer> idState = new LongSparseArray<Integer>();
            final int count = mCheckedIdStates.size();
            for (int i = 0; i < count; i++) {
                idState.put(mCheckedIdStates.keyAt(i), mCheckedIdStates.valueAt(i));
            }
            ss.checkIdState = idState;
        }
        ss.checkedItemCount = mCheckedItemCount;

        if (mRemoteAdapter != null) {
            mRemoteAdapter.saveRemoteViewsCache();
        }

        return ss;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;

        super.onRestoreInstanceState(ss.getSuperState());
        mDataChanged = true;

        mSyncHeight = ss.height;

        if (ss.selectedId >= 0) {
            mNeedSync = true;
            mPendingSync = ss;
            mSyncRowId = ss.selectedId;
            mSyncPosition = ss.position;
            mSpecificTop = ss.viewTop;
            mSyncMode = SYNC_SELECTED_POSITION;
        } else if (ss.firstId >= 0) {
            setSelectedPositionInt(INVALID_POSITION);
            // Do this before setting mNeedSync since setNextSelectedPosition looks at mNeedSync
            setNextSelectedPositionInt(INVALID_POSITION);
            mSelectorPosition = INVALID_POSITION;
            mNeedSync = true;
            mPendingSync = ss;
            mSyncRowId = ss.firstId;
            mSyncPosition = ss.position;
            mSpecificTop = ss.viewTop;
            mSyncMode = SYNC_FIRST_POSITION;
        }

        setFilterText(ss.filter);

        if (ss.checkState != null) {
            mCheckStates = ss.checkState;
        }

        if (ss.checkIdState != null) {
            mCheckedIdStates = ss.checkIdState;
        }

        mCheckedItemCount = ss.checkedItemCount;

        if (ss.inActionMode && mChoiceMode == CHOICE_MODE_MULTIPLE_MODAL &&
                mMultiChoiceModeCallback != null) {
            mChoiceActionMode = startActionMode(mMultiChoiceModeCallback);
        }

        requestLayout();
    }

    private boolean acceptFilter() {
        return mTextFilterEnabled && getAdapter() instanceof Filterable &&
                ((Filterable) getAdapter()).getFilter() != null;
    }

    /**
     * Sets the initial value for the text filter.
     * @param filterText The text to use for the filter.
     *
     * @see #setTextFilterEnabled
     */
    public void setFilterText(String filterText) {
        // TODO: Should we check for acceptFilter()?
        if (mTextFilterEnabled && !TextUtils.isEmpty(filterText)) {
            createTextFilter(false);
            // This is going to call our listener onTextChanged, but we might not
            // be ready to bring up a window yet
            mTextFilter.setText(filterText);
            mTextFilter.setSelection(filterText.length());
            if (mAdapter instanceof Filterable) {
                // if mPopup is non-null, then onTextChanged will do the filtering
                if (mPopup == null) {
                    Filter f = ((Filterable) mAdapter).getFilter();
                    f.filter(filterText);
                }
                // Set filtered to true so we will display the filter window when our main
                // window is ready
                mFiltered = true;
                mDataSetObserver.clearSavedState();
            }
        }
    }

    /**
     * Returns the list's text filter, if available.
     * @return the list's text filter or null if filtering isn't enabled
     */
    public CharSequence getTextFilter() {
        if (mTextFilterEnabled && mTextFilter != null) {
            return mTextFilter.getText();
        }
        return null;
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (gainFocus && mSelectedPosition < 0 && !isInTouchMode()) {
            if (!isAttachedToWindow() && mAdapter != null) {
                // Data may have changed while we were detached and it's valid
                // to change focus while detached. Refresh so we don't die.
                mDataChanged = true;
                mOldItemCount = mItemCount;
                mItemCount = mAdapter.getCount();
            }
            resurrectSelection();
        }
    }

    @Override
    public void requestLayout() {
        if (!mBlockLayoutRequests && !mInLayout) {
            super.requestLayout();
        }
    }

    /**
     * The list is empty. Clear everything out.
     */
    void resetList() {
        removeAllViewsInLayout();
        mFirstPosition = 0;
        mDataChanged = false;
        mPositionScrollAfterLayout = null;
        mNeedSync = false;
        mPendingSync = null;
        mOldSelectedPosition = INVALID_POSITION;
        mOldSelectedRowId = INVALID_ROW_ID;
        setSelectedPositionInt(INVALID_POSITION);
        setNextSelectedPositionInt(INVALID_POSITION);
        mSelectedTop = 0;
        mSelectorPosition = INVALID_POSITION;
        mSelectorRect.setEmpty();
        invalidate();
    }

    @Override
    protected int computeVerticalScrollExtent() {
        final int count = getChildCount();
        if (count > 0) {
            if (mSmoothScrollbarEnabled) {
                int extent = count * 100;

                View view = getChildAt(0);
                final int top = view.getTop();
                int height = view.getHeight();
                if (height > 0) {
                    extent += (top * 100) / height;
                }

                view = getChildAt(count - 1);
                final int bottom = view.getBottom();
                height = view.getHeight();
                if (height > 0) {
                    extent -= ((bottom - getHeight()) * 100) / height;
                }

                return extent;
            } else {
                return 1;
            }
        }
        return 0;
    }

    @Override
    protected int computeVerticalScrollOffset() {
        final int firstPosition = mFirstPosition;
        final int childCount = getChildCount();
        if (firstPosition >= 0 && childCount > 0) {
            if (mSmoothScrollbarEnabled) {
                final View view = getChildAt(0);
                final int top = view.getTop();
                int height = view.getHeight();
                if (height > 0) {
                    return Math.max(firstPosition * 100 - (top * 100) / height +
                            (int)((float)mScrollY / getHeight() * mItemCount * 100), 0);
                }
            } else {
                int index;
                final int count = mItemCount;
                if (firstPosition == 0) {
                    index = 0;
                } else if (firstPosition + childCount == count) {
                    index = count;
                } else {
                    index = firstPosition + childCount / 2;
                }
                return (int) (firstPosition + childCount * (index / (float) count));
            }
        }
        return 0;
    }

    @Override
    protected int computeVerticalScrollRange() {
        int result;
        if (mSmoothScrollbarEnabled) {
            result = Math.max(mItemCount * 100, 0);
            if (mScrollY != 0) {
                // Compensate for overscroll
                result += Math.abs((int) ((float) mScrollY / getHeight() * mItemCount * 100));
            }
        } else {
            result = mItemCount;
        }
        return result;
    }

    @Override
    protected float getTopFadingEdgeStrength() {
        final int count = getChildCount();
        final float fadeEdge = super.getTopFadingEdgeStrength();
        if (count == 0) {
            return fadeEdge;
        } else {
            if (mFirstPosition > 0) {
                return 1.0f;
            }

            final int top = getChildAt(0).getTop();
            final float fadeLength = getVerticalFadingEdgeLength();
            return top < mPaddingTop ? -(top - mPaddingTop) / fadeLength : fadeEdge;
        }
    }

    @Override
    protected float getBottomFadingEdgeStrength() {
        final int count = getChildCount();
        final float fadeEdge = super.getBottomFadingEdgeStrength();
        if (count == 0) {
            return fadeEdge;
        } else {
            if (mFirstPosition + count - 1 < mItemCount - 1) {
                return 1.0f;
            }

            final int bottom = getChildAt(count - 1).getBottom();
            final int height = getHeight();
            final float fadeLength = getVerticalFadingEdgeLength();
            return bottom > height - mPaddingBottom ?
                    (bottom - height + mPaddingBottom) / fadeLength : fadeEdge;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mSelector == null) {
            useDefaultSelector();
        }
        final Rect listPadding = mListPadding;
        listPadding.left = mSelectionLeftPadding + mPaddingLeft;
        listPadding.top = mSelectionTopPadding + mPaddingTop;
        listPadding.right = mSelectionRightPadding + mPaddingRight;
        listPadding.bottom = mSelectionBottomPadding + mPaddingBottom;

        // Check if our previous measured size was at a point where we should scroll later.
        if (mTranscriptMode == TRANSCRIPT_MODE_NORMAL) {
            final int childCount = getChildCount();
            final int listBottom = getHeight() - getPaddingBottom();
            final View lastChild = getChildAt(childCount - 1);
            final int lastBottom = lastChild != null ? lastChild.getBottom() : listBottom;
            mForceTranscriptScroll = mFirstPosition + childCount >= mLastHandledItemCount &&
                    lastBottom <= listBottom;
        }
    }

    /**
     * Subclasses should NOT override this method but
     *  {@link #layoutChildren()} instead.
     */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        mInLayout = true;

        final int childCount = getChildCount();
        if (changed) {
            for (int i = 0; i < childCount; i++) {
                getChildAt(i).forceLayout();
            }
            mRecycler.markChildrenDirty();
        }

        layoutChildren();

        mOverscrollMax = (b - t) / OVERSCROLL_LIMIT_DIVISOR;

        // TODO: Move somewhere sane. This doesn't belong in onLayout().
        if (mFastScroll != null) {
            mFastScroll.onItemCountChanged(getChildCount(), mItemCount);
        }
        mInLayout = false;
    }

    /**
     * @hide
     */
    @Override
    protected boolean setFrame(int left, int top, int right, int bottom) {
        final boolean changed = super.setFrame(left, top, right, bottom);

        if (changed) {
            // Reposition the popup when the frame has changed. This includes
            // translating the widget, not just changing its dimension. The
            // filter popup needs to follow the widget.
            final boolean visible = getWindowVisibility() == View.VISIBLE;
            if (mFiltered && visible && mPopup != null && mPopup.isShowing()) {
                positionPopup();
            }
        }

        return changed;
    }

    /**
     * Subclasses must override this method to layout their children.
     */
    protected void layoutChildren() {
    }

    /**
     * @param focusedView view that holds accessibility focus
     * @return direct child that contains accessibility focus, or null if no
     *         child contains accessibility focus
     */
    View getAccessibilityFocusedChild(View focusedView) {
        ViewParent viewParent = focusedView.getParent();
        while ((viewParent instanceof View) && (viewParent != this)) {
            focusedView = (View) viewParent;
            viewParent = viewParent.getParent();
        }

        if (!(viewParent instanceof View)) {
            return null;
        }

        return focusedView;
    }

    void updateScrollIndicators() {
        if (mScrollUp != null) {
            mScrollUp.setVisibility(canScrollUp() ? View.VISIBLE : View.INVISIBLE);
        }

        if (mScrollDown != null) {
            mScrollDown.setVisibility(canScrollDown() ? View.VISIBLE : View.INVISIBLE);
        }
    }

    @UnsupportedAppUsage
    private boolean canScrollUp() {
        boolean canScrollUp;
        // 0th element is not visible
        canScrollUp = mFirstPosition > 0;

        // ... Or top of 0th element is not visible
        if (!canScrollUp) {
            if (getChildCount() > 0) {
                View child = getChildAt(0);
                canScrollUp = child.getTop() < mListPadding.top;
            }
        }

        return canScrollUp;
    }

    @UnsupportedAppUsage
    private boolean canScrollDown() {
        boolean canScrollDown;
        int count = getChildCount();

        // Last item is not visible
        canScrollDown = (mFirstPosition + count) < mItemCount;

        // ... Or bottom of the last element is not visible
        if (!canScrollDown && count > 0) {
            View child = getChildAt(count - 1);
            canScrollDown = child.getBottom() > mBottom - mListPadding.bottom;
        }

        return canScrollDown;
    }

    @Override
    @ViewDebug.ExportedProperty
    public View getSelectedView() {
        if (mItemCount > 0 && mSelectedPosition >= 0) {
            return getChildAt(mSelectedPosition - mFirstPosition);
        } else {
            return null;
        }
    }

    /**
     * List padding is the maximum of the normal view's padding and the padding of the selector.
     *
     * @see android.view.View#getPaddingTop()
     * @see #getSelector()
     *
     * @return The top list padding.
     */
    public int getListPaddingTop() {
        return mListPadding.top;
    }

    /**
     * List padding is the maximum of the normal view's padding and the padding of the selector.
     *
     * @see android.view.View#getPaddingBottom()
     * @see #getSelector()
     *
     * @return The bottom list padding.
     */
    public int getListPaddingBottom() {
        return mListPadding.bottom;
    }

    /**
     * List padding is the maximum of the normal view's padding and the padding of the selector.
     *
     * @see android.view.View#getPaddingLeft()
     * @see #getSelector()
     *
     * @return The left list padding.
     */
    public int getListPaddingLeft() {
        return mListPadding.left;
    }

    /**
     * List padding is the maximum of the normal view's padding and the padding of the selector.
     *
     * @see android.view.View#getPaddingRight()
     * @see #getSelector()
     *
     * @return The right list padding.
     */
    public int getListPaddingRight() {
        return mListPadding.right;
    }

    /**
     * Gets a view and have it show the data associated with the specified
     * position. This is called when we have already discovered that the view
     * is not available for reuse in the recycle bin. The only choices left are
     * converting an old view or making a new one.
     *
     * @param position the position to display
     * @param outMetadata an array of at least 1 boolean where the first entry
     *                    will be set {@code true} if the view is currently
     *                    attached to the window, {@code false} otherwise (e.g.
     *                    newly-inflated or remained scrap for multiple layout
     *                    passes)
     *
     * @return A view displaying the data associated with the specified position
     */
    View obtainView(int position, boolean[] outMetadata) {
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "obtainView");

        outMetadata[0] = false;

        // Check whether we have a transient state view. Attempt to re-bind the
        // data and discard the view if we fail.
        final View transientView = mRecycler.getTransientStateView(position);
        if (transientView != null) {
            final LayoutParams params = (LayoutParams) transientView.getLayoutParams();

            // If the view type hasn't changed, attempt to re-bind the data.
            if (params.viewType == mAdapter.getItemViewType(position)) {
                final View updatedView = mAdapter.getView(position, transientView, this);

                // If we failed to re-bind the data, scrap the obtained view.
                if (updatedView != transientView) {
                    setItemViewLayoutParams(updatedView, position);
                    mRecycler.addScrapView(updatedView, position);
                }
            }

            outMetadata[0] = true;

            // Finish the temporary detach started in addScrapView().
            transientView.dispatchFinishTemporaryDetach();
            return transientView;
        }

        final View scrapView = mRecycler.getScrapView(position);
        final View child = mAdapter.getView(position, scrapView, this);
        if (scrapView != null) {
            if (child != scrapView) {
                // Failed to re-bind the data, return scrap to the heap.
                mRecycler.addScrapView(scrapView, position);
            } else if (child.isTemporarilyDetached()) {
                outMetadata[0] = true;

                // Finish the temporary detach started in addScrapView().
                child.dispatchFinishTemporaryDetach();
            }
        }

        if (mCacheColorHint != 0) {
            child.setDrawingCacheBackgroundColor(mCacheColorHint);
        }

        if (child.getImportantForAccessibility() == IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            child.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        setItemViewLayoutParams(child, position);

        if (AccessibilityManager.getInstance(mContext).isEnabled()) {
            if (mAccessibilityDelegate == null) {
                mAccessibilityDelegate = new ListItemAccessibilityDelegate();
            }
            if (child.getAccessibilityDelegate() == null) {
                child.setAccessibilityDelegate(mAccessibilityDelegate);
            }
        }

        Trace.traceEnd(Trace.TRACE_TAG_VIEW);

        return child;
    }

    private void setItemViewLayoutParams(View child, int position) {
        final ViewGroup.LayoutParams vlp = child.getLayoutParams();
        LayoutParams lp;
        if (vlp == null) {
            lp = (LayoutParams) generateDefaultLayoutParams();
        } else if (!checkLayoutParams(vlp)) {
            lp = (LayoutParams) generateLayoutParams(vlp);
        } else {
            lp = (LayoutParams) vlp;
        }

        if (mAdapterHasStableIds) {
            lp.itemId = mAdapter.getItemId(position);
        }
        lp.viewType = mAdapter.getItemViewType(position);
        lp.isEnabled = mAdapter.isEnabled(position);
        if (lp != vlp) {
          child.setLayoutParams(lp);
        }
    }

    class ListItemAccessibilityDelegate extends AccessibilityDelegate {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);

            final int position = getPositionForView(host);
            onInitializeAccessibilityNodeInfoForItem(host, position, info);
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
            if (super.performAccessibilityAction(host, action, arguments)) {
                return true;
            }

            final int position = getPositionForView(host);
            if (position == INVALID_POSITION || mAdapter == null) {
                // Cannot perform actions on invalid items.
                return false;
            }

            if (position >= mAdapter.getCount()) {
                // The position is no longer valid, likely due to a data set
                // change. We could fail here for all data set changes, since
                // there is a chance that the data bound to the view may no
                // longer exist at the same position within the adapter, but
                // it's more consistent with the standard touch interaction to
                // click at whatever may have moved into that position.
                return false;
            }

            final boolean isItemEnabled;
            final ViewGroup.LayoutParams lp = host.getLayoutParams();
            if (lp instanceof AbsListView.LayoutParams) {
                isItemEnabled = ((AbsListView.LayoutParams) lp).isEnabled;
            } else {
                isItemEnabled = false;
            }

            if (!isEnabled() || !isItemEnabled) {
                // Cannot perform actions on disabled items.
                return false;
            }

            switch (action) {
                case AccessibilityNodeInfo.ACTION_CLEAR_SELECTION: {
                    if (getSelectedItemPosition() == position) {
                        setSelection(INVALID_POSITION);
                        return true;
                    }
                } return false;
                case AccessibilityNodeInfo.ACTION_SELECT: {
                    if (getSelectedItemPosition() != position) {
                        setSelection(position);
                        return true;
                    }
                } return false;
                case AccessibilityNodeInfo.ACTION_CLICK: {
                    if (isItemClickable(host)) {
                        final long id = getItemIdAtPosition(position);
                        return performItemClick(host, position, id);
                    }
                } return false;
                case AccessibilityNodeInfo.ACTION_LONG_CLICK: {
                    if (isLongClickable()) {
                        final long id = getItemIdAtPosition(position);
                        return performLongPress(host, position, id);
                    }
                } return false;
            }

            return false;
        }
    }

    /**
     * Initializes an {@link AccessibilityNodeInfo} with information about a
     * particular item in the list.
     *
     * @param view View representing the list item.
     * @param position Position of the list item within the adapter.
     * @param info Node info to populate.
     */
    public void onInitializeAccessibilityNodeInfoForItem(
            View view, int position, AccessibilityNodeInfo info) {
        if (position == INVALID_POSITION) {
            // The item doesn't exist, so there's not much we can do here.
            return;
        }

        final boolean isItemEnabled;
        final ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp instanceof AbsListView.LayoutParams) {
            isItemEnabled = ((AbsListView.LayoutParams) lp).isEnabled && isEnabled();
        } else {
            isItemEnabled = false;
        }

        info.setEnabled(isItemEnabled);

        if (position == getSelectedItemPosition()) {
            info.setSelected(true);
            addAccessibilityActionIfEnabled(info, isItemEnabled,
                    AccessibilityAction.ACTION_CLEAR_SELECTION);
        } else  {
            addAccessibilityActionIfEnabled(info, isItemEnabled,
                    AccessibilityAction.ACTION_SELECT);
        }

        if (isItemClickable(view)) {
            addAccessibilityActionIfEnabled(info, isItemEnabled, AccessibilityAction.ACTION_CLICK);
            info.setClickable(true);
        }

        if (isLongClickable()) {
            addAccessibilityActionIfEnabled(info, isItemEnabled,
                    AccessibilityAction.ACTION_LONG_CLICK);
            info.setLongClickable(true);
        }
    }


    private void addAccessibilityActionIfEnabled(AccessibilityNodeInfo info, boolean enabled,
            AccessibilityAction action) {
        if (enabled) {
            info.addAction(action);
        }
    }

    private boolean isItemClickable(View view) {
        return !view.hasExplicitFocusable();
    }

    /**
     * Positions the selector in a way that mimics touch.
     */
    void positionSelectorLikeTouch(int position, View sel, float x, float y) {
        positionSelector(position, sel, true, x, y);
    }

    /**
     * Positions the selector in a way that mimics keyboard focus.
     */
    void positionSelectorLikeFocus(int position, View sel) {
        if (mSelector != null && mSelectorPosition != position && position != INVALID_POSITION) {
            final Rect bounds = mSelectorRect;
            final float x = bounds.exactCenterX();
            final float y = bounds.exactCenterY();
            positionSelector(position, sel, true, x, y);
        } else {
            positionSelector(position, sel);
        }
    }

    void positionSelector(int position, View sel) {
        positionSelector(position, sel, false, -1, -1);
    }

    @UnsupportedAppUsage
    private void positionSelector(int position, View sel, boolean manageHotspot, float x, float y) {
        final boolean positionChanged = position != mSelectorPosition;
        if (position != INVALID_POSITION) {
            mSelectorPosition = position;
        }

        final Rect selectorRect = mSelectorRect;
        selectorRect.set(sel.getLeft(), sel.getTop(), sel.getRight(), sel.getBottom());
        if (sel instanceof SelectionBoundsAdjuster) {
            ((SelectionBoundsAdjuster)sel).adjustListItemSelectionBounds(selectorRect);
        }

        // Adjust for selection padding.
        selectorRect.left -= mSelectionLeftPadding;
        selectorRect.top -= mSelectionTopPadding;
        selectorRect.right += mSelectionRightPadding;
        selectorRect.bottom += mSelectionBottomPadding;

        // Update the child enabled state prior to updating the selector.
        final boolean isChildViewEnabled = sel.isEnabled();
        if (mIsChildViewEnabled != isChildViewEnabled) {
            mIsChildViewEnabled = isChildViewEnabled;
        }

        // Update the selector drawable's state and position.
        final Drawable selector = mSelector;
        if (selector != null) {
            if (positionChanged) {
                // Wipe out the current selector state so that we can start
                // over in the new position with a fresh state.
                selector.setVisible(false, false);
                selector.setState(StateSet.NOTHING);
            }
            selector.setBounds(selectorRect);
            if (positionChanged) {
                if (getVisibility() == VISIBLE) {
                    selector.setVisible(true, false);
                }
                updateSelectorState();
            }
            if (manageHotspot) {
                selector.setHotspot(x, y);
            }
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int saveCount = 0;
        final boolean clipToPadding = (mGroupFlags & CLIP_TO_PADDING_MASK) == CLIP_TO_PADDING_MASK;
        if (clipToPadding) {
            saveCount = canvas.save();
            final int scrollX = mScrollX;
            final int scrollY = mScrollY;
            canvas.clipRect(scrollX + mPaddingLeft, scrollY + mPaddingTop,
                    scrollX + mRight - mLeft - mPaddingRight,
                    scrollY + mBottom - mTop - mPaddingBottom);
            mGroupFlags &= ~CLIP_TO_PADDING_MASK;
        }

        final boolean drawSelectorOnTop = mDrawSelectorOnTop;
        if (!drawSelectorOnTop) {
            drawSelector(canvas);
        }

        super.dispatchDraw(canvas);

        if (drawSelectorOnTop) {
            drawSelector(canvas);
        }

        if (clipToPadding) {
            canvas.restoreToCount(saveCount);
            mGroupFlags |= CLIP_TO_PADDING_MASK;
        }
    }

    @Override
    protected boolean isPaddingOffsetRequired() {
        return (mGroupFlags & CLIP_TO_PADDING_MASK) != CLIP_TO_PADDING_MASK;
    }

    @Override
    protected int getLeftPaddingOffset() {
        return (mGroupFlags & CLIP_TO_PADDING_MASK) == CLIP_TO_PADDING_MASK ? 0 : -mPaddingLeft;
    }

    @Override
    protected int getTopPaddingOffset() {
        return (mGroupFlags & CLIP_TO_PADDING_MASK) == CLIP_TO_PADDING_MASK ? 0 : -mPaddingTop;
    }

    @Override
    protected int getRightPaddingOffset() {
        return (mGroupFlags & CLIP_TO_PADDING_MASK) == CLIP_TO_PADDING_MASK ? 0 : mPaddingRight;
    }

    @Override
    protected int getBottomPaddingOffset() {
        return (mGroupFlags & CLIP_TO_PADDING_MASK) == CLIP_TO_PADDING_MASK ? 0 : mPaddingBottom;
    }

    /**
     * @hide
     */
    @Override
    protected void internalSetPadding(int left, int top, int right, int bottom) {
        super.internalSetPadding(left, top, right, bottom);
        if (isLayoutRequested()) {
            handleBoundsChange();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        handleBoundsChange();
        if (mFastScroll != null) {
            mFastScroll.onSizeChanged(w, h, oldw, oldh);
        }
    }

    /**
     * Called when bounds of the AbsListView are changed. AbsListView marks data set as changed
     * and force layouts all children that don't have exact measure specs.
     * <p>
     * This invalidation is necessary, otherwise, AbsListView may think the children are valid and
     * fail to relayout them properly to accommodate for new bounds.
     */
    void handleBoundsChange() {
        if (mInLayout) {
            return;
        }
        final int childCount = getChildCount();
        if (childCount > 0) {
            mDataChanged = true;
            rememberSyncState();
            for (int i = 0; i < childCount; i++) {
                final View child = getChildAt(i);
                final ViewGroup.LayoutParams lp = child.getLayoutParams();
                // force layout child unless it has exact specs
                if (lp == null || lp.width < 1 || lp.height < 1) {
                    child.forceLayout();
                }
            }
        }
    }

    /**
     * @return True if the current touch mode requires that we draw the selector in the pressed
     *         state.
     */
    boolean touchModeDrawsInPressedState() {
        // FIXME use isPressed for this
        switch (mTouchMode) {
        case TOUCH_MODE_TAP:
        case TOUCH_MODE_DONE_WAITING:
            return true;
        default:
            return false;
        }
    }

    /**
     * Indicates whether this view is in a state where the selector should be drawn. This will
     * happen if we have focus but are not in touch mode, or we are in the middle of displaying
     * the pressed state for an item.
     *
     * @return True if the selector should be shown
     */
    boolean shouldShowSelector() {
        return (isFocused() && !isInTouchMode()) || (touchModeDrawsInPressedState() && isPressed());
    }

    private void drawSelector(Canvas canvas) {
        if (shouldDrawSelector()) {
            final Drawable selector = mSelector;
            selector.setBounds(mSelectorRect);
            selector.draw(canvas);
        }
    }

    /**
     * @hide
     */
    @TestApi
    public final boolean shouldDrawSelector() {
        return !mSelectorRect.isEmpty();
    }

    /**
     * Controls whether the selection highlight drawable should be drawn on top of the item or
     * behind it.
     *
     * @param onTop If true, the selector will be drawn on the item it is highlighting. The default
     *        is false.
     *
     * @attr ref android.R.styleable#AbsListView_drawSelectorOnTop
     */
    public void setDrawSelectorOnTop(boolean onTop) {
        mDrawSelectorOnTop = onTop;
    }

    /**
     * Returns whether the selection highlight drawable should be drawn on top of the item or
     * behind it.
     *
     * @return true if selector is drawn on top, false otherwise
     * @attr ref android.R.styleable#AbsListView_drawSelectorOnTop
     */
    @InspectableProperty
    public boolean isDrawSelectorOnTop() {
        return mDrawSelectorOnTop;
    }

    /**
     * Set a Drawable that should be used to highlight the currently selected item.
     *
     * @param resID A Drawable resource to use as the selection highlight.
     *
     * @attr ref android.R.styleable#AbsListView_listSelector
     */
    public void setSelector(@DrawableRes int resID) {
        setSelector(getContext().getDrawable(resID));
    }

    public void setSelector(Drawable sel) {
        if (mSelector != null) {
            mSelector.setCallback(null);
            unscheduleDrawable(mSelector);
        }
        mSelector = sel;
        Rect padding = new Rect();
        sel.getPadding(padding);
        mSelectionLeftPadding = padding.left;
        mSelectionTopPadding = padding.top;
        mSelectionRightPadding = padding.right;
        mSelectionBottomPadding = padding.bottom;
        sel.setCallback(this);
        updateSelectorState();
    }

    /**
     * Returns the selector {@link android.graphics.drawable.Drawable} that is used to draw the
     * selection in the list.
     *
     * @return the drawable used to display the selector
     */
    @InspectableProperty(name = "listSelector")
    public Drawable getSelector() {
        return mSelector;
    }

    /**
     * Sets the selector state to "pressed" and posts a CheckForKeyLongPress to see if
     * this is a long press.
     */
    void keyPressed() {
        if (!isEnabled() || !isClickable()) {
            return;
        }

        Drawable selector = mSelector;
        Rect selectorRect = mSelectorRect;
        if (selector != null && (isFocused() || touchModeDrawsInPressedState())
                && !selectorRect.isEmpty()) {

            final View v = getChildAt(mSelectedPosition - mFirstPosition);

            if (v != null) {
                if (v.hasExplicitFocusable()) return;
                v.setPressed(true);
            }
            setPressed(true);

            final boolean longClickable = isLongClickable();
            Drawable d = selector.getCurrent();
            if (d != null && d instanceof TransitionDrawable) {
                if (longClickable) {
                    ((TransitionDrawable) d).startTransition(
                            ViewConfiguration.getLongPressTimeout());
                } else {
                    ((TransitionDrawable) d).resetTransition();
                }
            }
            if (longClickable && !mDataChanged) {
                if (mPendingCheckForKeyLongPress == null) {
                    mPendingCheckForKeyLongPress = new CheckForKeyLongPress();
                }
                mPendingCheckForKeyLongPress.rememberWindowAttachCount();
                postDelayed(mPendingCheckForKeyLongPress, ViewConfiguration.getLongPressTimeout());
            }
        }
    }

    public void setScrollIndicators(View up, View down) {
        mScrollUp = up;
        mScrollDown = down;
    }

    @UnsupportedAppUsage
    void updateSelectorState() {
        final Drawable selector = mSelector;
        if (selector != null && selector.isStateful()) {
            if (shouldShowSelector()) {
                if (selector.setState(getDrawableStateForSelector())) {
                    invalidateDrawable(selector);
                }
            } else {
                selector.setState(StateSet.NOTHING);
            }
        }
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        updateSelectorState();
    }

    private int[] getDrawableStateForSelector() {
        // If the child view is enabled then do the default behavior.
        if (mIsChildViewEnabled) {
            // Common case
            return super.getDrawableState();
        }

        // The selector uses this View's drawable state. The selected child view
        // is disabled, so we need to remove the enabled state from the drawable
        // states.
        final int enabledState = ENABLED_STATE_SET[0];

        // If we don't have any extra space, it will return one of the static
        // state arrays, and clearing the enabled state on those arrays is a
        // bad thing! If we specify we need extra space, it will create+copy
        // into a new array that is safely mutable.
        final int[] state = onCreateDrawableState(1);

        int enabledPos = -1;
        for (int i = state.length - 1; i >= 0; i--) {
            if (state[i] == enabledState) {
                enabledPos = i;
                break;
            }
        }

        // Remove the enabled state
        if (enabledPos >= 0) {
            System.arraycopy(state, enabledPos + 1, state, enabledPos,
                    state.length - enabledPos - 1);
        }

        return state;
    }

    @Override
    public boolean verifyDrawable(@NonNull Drawable dr) {
        return mSelector == dr || super.verifyDrawable(dr);
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        if (mSelector != null) mSelector.jumpToCurrentState();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        final ViewTreeObserver treeObserver = getViewTreeObserver();
        treeObserver.addOnTouchModeChangeListener(this);
        if (mTextFilterEnabled && mPopup != null && !mGlobalLayoutListenerAddedFilter) {
            treeObserver.addOnGlobalLayoutListener(this);
        }

        if (mAdapter != null && mDataSetObserver == null) {
            mDataSetObserver = new AdapterDataSetObserver();
            mAdapter.registerDataSetObserver(mDataSetObserver);

            // Data may have changed while we were detached. Refresh.
            mDataChanged = true;
            mOldItemCount = mItemCount;
            mItemCount = mAdapter.getCount();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        mIsDetaching = true;

        // Dismiss the popup in case onSaveInstanceState() was not invoked
        dismissPopup();

        // Detach any view left in the scrap heap
        mRecycler.clear();

        final ViewTreeObserver treeObserver = getViewTreeObserver();
        treeObserver.removeOnTouchModeChangeListener(this);
        if (mTextFilterEnabled && mPopup != null) {
            treeObserver.removeOnGlobalLayoutListener(this);
            mGlobalLayoutListenerAddedFilter = false;
        }

        if (mAdapter != null && mDataSetObserver != null) {
            mAdapter.unregisterDataSetObserver(mDataSetObserver);
            mDataSetObserver = null;
        }

        if (mScrollStrictSpan != null) {
            mScrollStrictSpan.finish();
            mScrollStrictSpan = null;
        }

        if (mFlingStrictSpan != null) {
            mFlingStrictSpan.finish();
            mFlingStrictSpan = null;
        }

        if (mFlingRunnable != null) {
            removeCallbacks(mFlingRunnable);
        }

        if (mPositionScroller != null) {
            mPositionScroller.stop();
        }

        if (mClearScrollingCache != null) {
            removeCallbacks(mClearScrollingCache);
        }

        if (mPerformClick != null) {
            removeCallbacks(mPerformClick);
        }

        if (mTouchModeReset != null) {
            removeCallbacks(mTouchModeReset);
            mTouchModeReset.run();
        }

        mIsDetaching = false;
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);

        final int touchMode = isInTouchMode() ? TOUCH_MODE_ON : TOUCH_MODE_OFF;

        if (!hasWindowFocus) {
            setChildrenDrawingCacheEnabled(false);
            if (mFlingRunnable != null) {
                removeCallbacks(mFlingRunnable);
                // let the fling runnable report its new state which
                // should be idle
                mFlingRunnable.mSuppressIdleStateChangeCall = false;
                mFlingRunnable.endFling();
                if (mPositionScroller != null) {
                    mPositionScroller.stop();
                }
                if (mScrollY != 0) {
                    mScrollY = 0;
                    invalidateParentCaches();
                    finishGlows();
                    invalidate();
                }
            }
            // Always hide the type filter
            dismissPopup();

            if (touchMode == TOUCH_MODE_OFF) {
                // Remember the last selected element
                mResurrectToPosition = mSelectedPosition;
            }
        } else {
            if (mFiltered && !mPopupHidden) {
                // Show the type filter only if a filter is in effect
                showPopup();
            }

            // If we changed touch mode since the last time we had focus
            if (touchMode != mLastTouchMode && mLastTouchMode != TOUCH_MODE_UNKNOWN) {
                // If we come back in trackball mode, we bring the selection back
                if (touchMode == TOUCH_MODE_OFF) {
                    // This will trigger a layout
                    resurrectSelection();

                // If we come back in touch mode, then we want to hide the selector
                } else {
                    hideSelector();
                    mLayoutMode = LAYOUT_NORMAL;
                    layoutChildren();
                }
            }
        }

        mLastTouchMode = touchMode;
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        if (mFastScroll != null) {
           mFastScroll.setScrollbarPosition(getVerticalScrollbarPosition());
        }
    }

    /**
     * Creates the ContextMenuInfo returned from {@link #getContextMenuInfo()}. This
     * methods knows the view, position and ID of the item that received the
     * long press.
     *
     * @param view The view that received the long press.
     * @param position The position of the item that received the long press.
     * @param id The ID of the item that received the long press.
     * @return The extra information that should be returned by
     *         {@link #getContextMenuInfo()}.
     */
    ContextMenuInfo createContextMenuInfo(View view, int position, long id) {
        return new AdapterContextMenuInfo(view, position, id);
    }

    @Override
    public void onCancelPendingInputEvents() {
        super.onCancelPendingInputEvents();
        if (mPerformClick != null) {
            removeCallbacks(mPerformClick);
        }
        if (mPendingCheckForTap != null) {
            removeCallbacks(mPendingCheckForTap);
        }
        if (mPendingCheckForLongPress != null) {
            removeCallbacks(mPendingCheckForLongPress);
        }
        if (mPendingCheckForKeyLongPress != null) {
            removeCallbacks(mPendingCheckForKeyLongPress);
        }
    }

    /**
     * A base class for Runnables that will check that their view is still attached to
     * the original window as when the Runnable was created.
     *
     */
    private class WindowRunnnable {
        private int mOriginalAttachCount;

        public void rememberWindowAttachCount() {
            mOriginalAttachCount = getWindowAttachCount();
        }

        public boolean sameWindow() {
            return getWindowAttachCount() == mOriginalAttachCount;
        }
    }

    private class PerformClick extends WindowRunnnable implements Runnable {
        int mClickMotionPosition;

        @Override
        public void run() {
            // The data has changed since we posted this action in the event queue,
            // bail out before bad things happen
            if (mDataChanged) return;

            final ListAdapter adapter = mAdapter;
            final int motionPosition = mClickMotionPosition;
            if (adapter != null && mItemCount > 0 &&
                    motionPosition != INVALID_POSITION &&
                    motionPosition < adapter.getCount() && sameWindow() &&
                    adapter.isEnabled(motionPosition)) {
                final View view = getChildAt(motionPosition - mFirstPosition);
                // If there is no view, something bad happened (the view scrolled off the
                // screen, etc.) and we should cancel the click
                if (view != null) {
                    performItemClick(view, motionPosition, adapter.getItemId(motionPosition));
                }
            }
        }
    }

    private class CheckForLongPress extends WindowRunnnable implements Runnable {
        private static final int INVALID_COORD = -1;
        private float mX = INVALID_COORD;
        private float mY = INVALID_COORD;

        private void setCoords(float x, float y) {
            mX = x;
            mY = y;
        }

        @Override
        public void run() {
            final int motionPosition = mMotionPosition;
            final View child = getChildAt(motionPosition - mFirstPosition);
            if (child != null) {
                final int longPressPosition = mMotionPosition;
                final long longPressId = mAdapter.getItemId(mMotionPosition);

                boolean handled = false;
                if (sameWindow() && !mDataChanged) {
                    if (mX != INVALID_COORD && mY != INVALID_COORD) {
                        handled = performLongPress(child, longPressPosition, longPressId, mX, mY);
                    } else {
                        handled = performLongPress(child, longPressPosition, longPressId);
                    }
                }

                if (handled) {
                    mHasPerformedLongPress = true;
                    mTouchMode = TOUCH_MODE_REST;
                    setPressed(false);
                    child.setPressed(false);
                } else {
                    mTouchMode = TOUCH_MODE_DONE_WAITING;
                }
            }
        }
    }

    private class CheckForKeyLongPress extends WindowRunnnable implements Runnable {
        @Override
        public void run() {
            if (isPressed() && mSelectedPosition >= 0) {
                int index = mSelectedPosition - mFirstPosition;
                View v = getChildAt(index);

                if (!mDataChanged) {
                    boolean handled = false;
                    if (sameWindow()) {
                        handled = performLongPress(v, mSelectedPosition, mSelectedRowId);
                    }
                    if (handled) {
                        setPressed(false);
                        v.setPressed(false);
                    }
                } else {
                    setPressed(false);
                    if (v != null) v.setPressed(false);
                }
            }
        }
    }

    private boolean performStylusButtonPressAction(MotionEvent ev) {
        if (mChoiceMode == CHOICE_MODE_MULTIPLE_MODAL && mChoiceActionMode == null) {
            final View child = getChildAt(mMotionPosition - mFirstPosition);
            if (child != null) {
                final int longPressPosition = mMotionPosition;
                final long longPressId = mAdapter.getItemId(mMotionPosition);
                if (performLongPress(child, longPressPosition, longPressId)) {
                    mTouchMode = TOUCH_MODE_REST;
                    setPressed(false);
                    child.setPressed(false);
                    return true;
                }
            }
        }
        return false;
    }

    @UnsupportedAppUsage
    boolean performLongPress(final View child,
            final int longPressPosition, final long longPressId) {
        return performLongPress(
                child,
                longPressPosition,
                longPressId,
                CheckForLongPress.INVALID_COORD,
                CheckForLongPress.INVALID_COORD);
    }

    @UnsupportedAppUsage
    boolean performLongPress(final View child,
            final int longPressPosition, final long longPressId, float x, float y) {
        // CHOICE_MODE_MULTIPLE_MODAL takes over long press.
        if (mChoiceMode == CHOICE_MODE_MULTIPLE_MODAL) {
            if (mChoiceActionMode == null &&
                    (mChoiceActionMode = startActionMode(mMultiChoiceModeCallback)) != null) {
                setItemChecked(longPressPosition, true);
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
            return true;
        }

        boolean handled = false;
        if (mOnItemLongClickListener != null) {
            handled = mOnItemLongClickListener.onItemLongClick(AbsListView.this, child,
                    longPressPosition, longPressId);
        }
        if (!handled) {
            mContextMenuInfo = createContextMenuInfo(child, longPressPosition, longPressId);
            if (x != CheckForLongPress.INVALID_COORD && y != CheckForLongPress.INVALID_COORD) {
                handled = super.showContextMenuForChild(AbsListView.this, x, y);
            } else {
                handled = super.showContextMenuForChild(AbsListView.this);
            }
        }
        if (handled) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
        return handled;
    }

    @Override
    protected ContextMenuInfo getContextMenuInfo() {
        return mContextMenuInfo;
    }

    @Override
    public boolean showContextMenu() {
        return showContextMenuInternal(0, 0, false);
    }

    @Override
    public boolean showContextMenu(float x, float y) {
        return showContextMenuInternal(x, y, true);
    }

    private boolean showContextMenuInternal(float x, float y, boolean useOffsets) {
        final int position = pointToPosition((int)x, (int)y);
        if (position != INVALID_POSITION) {
            final long id = mAdapter.getItemId(position);
            View child = getChildAt(position - mFirstPosition);
            if (child != null) {
                mContextMenuInfo = createContextMenuInfo(child, position, id);
                if (useOffsets) {
                    return super.showContextMenuForChild(this, x, y);
                } else {
                    return super.showContextMenuForChild(this);
                }
            }
        }
        if (useOffsets) {
            return super.showContextMenu(x, y);
        } else {
            return super.showContextMenu();
        }
    }

    @Override
    public boolean showContextMenuForChild(View originalView) {
        if (isShowingContextMenuWithCoords()) {
            return false;
        }
        return showContextMenuForChildInternal(originalView, 0, 0, false);
    }

    @Override
    public boolean showContextMenuForChild(View originalView, float x, float y) {
        return showContextMenuForChildInternal(originalView,x, y, true);
    }

    private boolean showContextMenuForChildInternal(View originalView, float x, float y,
            boolean useOffsets) {
        final int longPressPosition = getPositionForView(originalView);
        if (longPressPosition < 0) {
            return false;
        }

        final long longPressId = mAdapter.getItemId(longPressPosition);
        boolean handled = false;

        if (mOnItemLongClickListener != null) {
            handled = mOnItemLongClickListener.onItemLongClick(this, originalView,
                    longPressPosition, longPressId);
        }

        if (!handled) {
            final View child = getChildAt(longPressPosition - mFirstPosition);
            mContextMenuInfo = createContextMenuInfo(child, longPressPosition, longPressId);

            if (useOffsets) {
                handled = super.showContextMenuForChild(originalView, x, y);
            } else {
                handled = super.showContextMenuForChild(originalView);
            }
        }

        return handled;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (KeyEvent.isConfirmKey(keyCode)) {
            if (!isEnabled()) {
                return true;
            }
            if (isClickable() && isPressed() &&
                    mSelectedPosition >= 0 && mAdapter != null &&
                    mSelectedPosition < mAdapter.getCount()) {

                final View view = getChildAt(mSelectedPosition - mFirstPosition);
                if (view != null) {
                    performItemClick(view, mSelectedPosition, mSelectedRowId);
                    view.setPressed(false);
                }
                setPressed(false);
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void dispatchSetPressed(boolean pressed) {
        // Don't dispatch setPressed to our children. We call setPressed on ourselves to
        // get the selector in the right state, but we don't want to press each child.
    }

    @Override
    public void dispatchDrawableHotspotChanged(float x, float y) {
        // Don't dispatch hotspot changes to children. We'll manually handle
        // calling drawableHotspotChanged on the correct child.
    }

    /**
     * Maps a point to a position in the list.
     *
     * @param x X in local coordinate
     * @param y Y in local coordinate
     * @return The position of the item which contains the specified point, or
     *         {@link #INVALID_POSITION} if the point does not intersect an item.
     */
    public int pointToPosition(int x, int y) {
        Rect frame = mTouchFrame;
        if (frame == null) {
            mTouchFrame = new Rect();
            frame = mTouchFrame;
        }

        final int count = getChildCount();
        for (int i = count - 1; i >= 0; i--) {
            final View child = getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) {
                child.getHitRect(frame);
                if (frame.contains(x, y)) {
                    return mFirstPosition + i;
                }
            }
        }
        return INVALID_POSITION;
    }


    /**
     * Maps a point to a the rowId of the item which intersects that point.
     *
     * @param x X in local coordinate
     * @param y Y in local coordinate
     * @return The rowId of the item which contains the specified point, or {@link #INVALID_ROW_ID}
     *         if the point does not intersect an item.
     */
    public long pointToRowId(int x, int y) {
        int position = pointToPosition(x, y);
        if (position >= 0) {
            return mAdapter.getItemId(position);
        }
        return INVALID_ROW_ID;
    }

    private final class CheckForTap implements Runnable {
        float x;
        float y;

        @Override
        public void run() {
            if (mTouchMode == TOUCH_MODE_DOWN) {
                mTouchMode = TOUCH_MODE_TAP;
                final View child = getChildAt(mMotionPosition - mFirstPosition);
                if (child != null && !child.hasExplicitFocusable()) {
                    mLayoutMode = LAYOUT_NORMAL;

                    if (!mDataChanged) {
                        final float[] point = mTmpPoint;
                        point[0] = x;
                        point[1] = y;
                        transformPointToViewLocal(point, child);
                        child.drawableHotspotChanged(point[0], point[1]);
                        child.setPressed(true);
                        setPressed(true);
                        layoutChildren();
                        positionSelector(mMotionPosition, child);
                        refreshDrawableState();

                        final int longPressTimeout = ViewConfiguration.getLongPressTimeout();
                        final boolean longClickable = isLongClickable();

                        if (mSelector != null) {
                            final Drawable d = mSelector.getCurrent();
                            if (d != null && d instanceof TransitionDrawable) {
                                if (longClickable) {
                                    ((TransitionDrawable) d).startTransition(longPressTimeout);
                                } else {
                                    ((TransitionDrawable) d).resetTransition();
                                }
                            }
                            mSelector.setHotspot(x, y);
                        }

                        if (longClickable) {
                            if (mPendingCheckForLongPress == null) {
                                mPendingCheckForLongPress = new CheckForLongPress();
                            }
                            mPendingCheckForLongPress.setCoords(x, y);
                            mPendingCheckForLongPress.rememberWindowAttachCount();
                            postDelayed(mPendingCheckForLongPress, longPressTimeout);
                        } else {
                            mTouchMode = TOUCH_MODE_DONE_WAITING;
                        }
                    } else {
                        mTouchMode = TOUCH_MODE_DONE_WAITING;
                    }
                }
            }
        }
    }

    private boolean startScrollIfNeeded(int x, int y, MotionEvent vtev) {
        // Check if we have moved far enough that it looks more like a
        // scroll than a tap
        final int deltaY = y - mMotionY;
        final int distance = Math.abs(deltaY);
        final boolean overscroll = mScrollY != 0;
        if ((overscroll || distance > mTouchSlop) &&
                (getNestedScrollAxes() & SCROLL_AXIS_VERTICAL) == 0) {
            createScrollingCache();
            if (overscroll) {
                mTouchMode = TOUCH_MODE_OVERSCROLL;
                mMotionCorrection = 0;
            } else {
                mTouchMode = TOUCH_MODE_SCROLL;
                mMotionCorrection = deltaY > 0 ? mTouchSlop : -mTouchSlop;
            }
            removeCallbacks(mPendingCheckForLongPress);
            setPressed(false);
            final View motionView = getChildAt(mMotionPosition - mFirstPosition);
            if (motionView != null) {
                motionView.setPressed(false);
            }
            reportScrollStateChange(OnScrollListener.SCROLL_STATE_TOUCH_SCROLL);
            // Time to start stealing events! Once we've stolen them, don't let anyone
            // steal from us
            final ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(true);
            }
            scrollIfNeeded(x, y, vtev);
            return true;
        }

        return false;
    }

    private void scrollIfNeeded(int x, int y, MotionEvent vtev) {
        int rawDeltaY = y - mMotionY;
        int scrollOffsetCorrection = 0;
        int scrollConsumedCorrection = 0;
        if (mLastY == Integer.MIN_VALUE) {
            rawDeltaY -= mMotionCorrection;
        }
        if (dispatchNestedPreScroll(0, mLastY != Integer.MIN_VALUE ? mLastY - y : -rawDeltaY,
                mScrollConsumed, mScrollOffset)) {
            rawDeltaY += mScrollConsumed[1];
            scrollOffsetCorrection = -mScrollOffset[1];
            scrollConsumedCorrection = mScrollConsumed[1];
            if (vtev != null) {
                vtev.offsetLocation(0, mScrollOffset[1]);
                mNestedYOffset += mScrollOffset[1];
            }
        }
        final int deltaY = rawDeltaY;
        int incrementalDeltaY =
                mLastY != Integer.MIN_VALUE ? y - mLastY + scrollConsumedCorrection : deltaY;
        int lastYCorrection = 0;

        if (mTouchMode == TOUCH_MODE_SCROLL) {
            if (PROFILE_SCROLLING) {
                if (!mScrollProfilingStarted) {
                    Debug.startMethodTracing("AbsListViewScroll");
                    mScrollProfilingStarted = true;
                }
            }

            if (mScrollStrictSpan == null) {
                // If it's non-null, we're already in a scroll.
                mScrollStrictSpan = StrictMode.enterCriticalSpan("AbsListView-scroll");
            }

            if (y != mLastY) {
                // We may be here after stopping a fling and continuing to scroll.
                // If so, we haven't disallowed intercepting touch events yet.
                // Make sure that we do so in case we're in a parent that can intercept.
                if ((mGroupFlags & FLAG_DISALLOW_INTERCEPT) == 0 &&
                        Math.abs(rawDeltaY) > mTouchSlop) {
                    final ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                }

                final int motionIndex;
                if (mMotionPosition >= 0) {
                    motionIndex = mMotionPosition - mFirstPosition;
                } else {
                    // If we don't have a motion position that we can reliably track,
                    // pick something in the middle to make a best guess at things below.
                    motionIndex = getChildCount() / 2;
                }

                int motionViewPrevTop = 0;
                View motionView = this.getChildAt(motionIndex);
                if (motionView != null) {
                    motionViewPrevTop = motionView.getTop();
                }

                // No need to do all this work if we're not going to move anyway
                boolean atEdge = false;
                if (incrementalDeltaY != 0) {
                    atEdge = trackMotionScroll(deltaY, incrementalDeltaY);
                }

                // Check to see if we have bumped into the scroll limit
                motionView = this.getChildAt(motionIndex);
                if (motionView != null) {
                    // Check if the top of the motion view is where it is
                    // supposed to be
                    final int motionViewRealTop = motionView.getTop();
                    if (atEdge) {
                        // Apply overscroll

                        int overscroll = -incrementalDeltaY -
                                (motionViewRealTop - motionViewPrevTop);
                        if (dispatchNestedScroll(0, overscroll - incrementalDeltaY, 0, overscroll,
                                mScrollOffset)) {
                            lastYCorrection -= mScrollOffset[1];
                            if (vtev != null) {
                                vtev.offsetLocation(0, mScrollOffset[1]);
                                mNestedYOffset += mScrollOffset[1];
                            }
                        } else {
                            final boolean atOverscrollEdge = overScrollBy(0, overscroll,
                                    0, mScrollY, 0, 0, 0, mOverscrollDistance, true);

                            if (atOverscrollEdge && mVelocityTracker != null) {
                                // Don't allow overfling if we're at the edge
                                mVelocityTracker.clear();
                            }

                            final int overscrollMode = getOverScrollMode();
                            if (overscrollMode == OVER_SCROLL_ALWAYS ||
                                    (overscrollMode == OVER_SCROLL_IF_CONTENT_SCROLLS &&
                                            !contentFits())) {
                                if (!atOverscrollEdge) {
                                    mDirection = 0; // Reset when entering overscroll.
                                    mTouchMode = TOUCH_MODE_OVERSCROLL;
                                }
                                if (incrementalDeltaY > 0) {
                                    mEdgeGlowTop.onPull((float) -overscroll / getHeight(),
                                            (float) x / getWidth());
                                    if (!mEdgeGlowBottom.isFinished()) {
                                        mEdgeGlowBottom.onRelease();
                                    }
                                    invalidateTopGlow();
                                } else if (incrementalDeltaY < 0) {
                                    mEdgeGlowBottom.onPull((float) overscroll / getHeight(),
                                            1.f - (float) x / getWidth());
                                    if (!mEdgeGlowTop.isFinished()) {
                                        mEdgeGlowTop.onRelease();
                                    }
                                    invalidateBottomGlow();
                                }
                            }
                        }
                    }
                    mMotionY = y + lastYCorrection + scrollOffsetCorrection;
                }
                mLastY = y + lastYCorrection + scrollOffsetCorrection;
            }
        } else if (mTouchMode == TOUCH_MODE_OVERSCROLL) {
            if (y != mLastY) {
                final int oldScroll = mScrollY;
                final int newScroll = oldScroll - incrementalDeltaY;
                int newDirection = y > mLastY ? 1 : -1;

                if (mDirection == 0) {
                    mDirection = newDirection;
                }

                int overScrollDistance = -incrementalDeltaY;
                if ((newScroll < 0 && oldScroll >= 0) || (newScroll > 0 && oldScroll <= 0)) {
                    overScrollDistance = -oldScroll;
                    incrementalDeltaY += overScrollDistance;
                } else {
                    incrementalDeltaY = 0;
                }

                if (overScrollDistance != 0) {
                    overScrollBy(0, overScrollDistance, 0, mScrollY, 0, 0,
                            0, mOverscrollDistance, true);
                    final int overscrollMode = getOverScrollMode();
                    if (overscrollMode == OVER_SCROLL_ALWAYS ||
                            (overscrollMode == OVER_SCROLL_IF_CONTENT_SCROLLS &&
                                    !contentFits())) {
                        if (rawDeltaY > 0) {
                            mEdgeGlowTop.onPull((float) overScrollDistance / getHeight(),
                                    (float) x / getWidth());
                            if (!mEdgeGlowBottom.isFinished()) {
                                mEdgeGlowBottom.onRelease();
                            }
                            invalidateTopGlow();
                        } else if (rawDeltaY < 0) {
                            mEdgeGlowBottom.onPull((float) overScrollDistance / getHeight(),
                                    1.f - (float) x / getWidth());
                            if (!mEdgeGlowTop.isFinished()) {
                                mEdgeGlowTop.onRelease();
                            }
                            invalidateBottomGlow();
                        }
                    }
                }

                if (incrementalDeltaY != 0) {
                    // Coming back to 'real' list scrolling
                    if (mScrollY != 0) {
                        mScrollY = 0;
                        invalidateParentIfNeeded();
                    }

                    trackMotionScroll(incrementalDeltaY, incrementalDeltaY);

                    mTouchMode = TOUCH_MODE_SCROLL;

                    // We did not scroll the full amount. Treat this essentially like the
                    // start of a new touch scroll
                    final int motionPosition = findClosestMotionRow(y);

                    mMotionCorrection = 0;
                    View motionView = getChildAt(motionPosition - mFirstPosition);
                    mMotionViewOriginalTop = motionView != null ? motionView.getTop() : 0;
                    mMotionY =  y + scrollOffsetCorrection;
                    mMotionPosition = motionPosition;
                }
                mLastY = y + lastYCorrection + scrollOffsetCorrection;
                mDirection = newDirection;
            }
        }
    }

    private void invalidateTopGlow() {
        if (!shouldDisplayEdgeEffects()) {
            return;
        }
        final boolean clipToPadding = getClipToPadding();
        final int top = clipToPadding ? mPaddingTop : 0;
        final int left = clipToPadding ? mPaddingLeft : 0;
        final int right = clipToPadding ? getWidth() - mPaddingRight : getWidth();
        invalidate(left, top, right, top + mEdgeGlowTop.getMaxHeight());
    }

    private void invalidateBottomGlow() {
        if (!shouldDisplayEdgeEffects()) {
            return;
        }
        final boolean clipToPadding = getClipToPadding();
        final int bottom = clipToPadding ? getHeight() - mPaddingBottom : getHeight();
        final int left = clipToPadding ? mPaddingLeft : 0;
        final int right = clipToPadding ? getWidth() - mPaddingRight : getWidth();
        invalidate(left, bottom - mEdgeGlowBottom.getMaxHeight(), right, bottom);
    }

    @Override
    public void onTouchModeChanged(boolean isInTouchMode) {
        if (isInTouchMode) {
            // Get rid of the selection when we enter touch mode
            hideSelector();
            // Layout, but only if we already have done so previously.
            // (Otherwise may clobber a LAYOUT_SYNC layout that was requested to restore
            // state.)
            if (getHeight() > 0 && getChildCount() > 0) {
                // We do not lose focus initiating a touch (since AbsListView is focusable in
                // touch mode). Force an initial layout to get rid of the selection.
                layoutChildren();
            }
            updateSelectorState();
        } else {
            int touchMode = mTouchMode;
            if (touchMode == TOUCH_MODE_OVERSCROLL || touchMode == TOUCH_MODE_OVERFLING) {
                if (mFlingRunnable != null) {
                    mFlingRunnable.endFling();
                }
                if (mPositionScroller != null) {
                    mPositionScroller.stop();
                }

                if (mScrollY != 0) {
                    mScrollY = 0;
                    invalidateParentCaches();
                    finishGlows();
                    invalidate();
                }
            }
        }
    }

    /** @hide */
    @Override
    protected boolean handleScrollBarDragging(MotionEvent event) {
        // Doesn't support normal scroll bar dragging. Use FastScroller.
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!isEnabled()) {
            // A disabled view that is clickable still consumes the touch
            // events, it just doesn't respond to them.
            return isClickable() || isLongClickable();
        }

        if (mPositionScroller != null) {
            mPositionScroller.stop();
        }

        if (mIsDetaching || !isAttachedToWindow()) {
            // Something isn't right.
            // Since we rely on being attached to get data set change notifications,
            // don't risk doing anything where we might try to resync and find things
            // in a bogus state.
            return false;
        }

        startNestedScroll(SCROLL_AXIS_VERTICAL);

        if (mFastScroll != null && mFastScroll.onTouchEvent(ev)) {
            return true;
        }

        initVelocityTrackerIfNotExists();
        final MotionEvent vtev = MotionEvent.obtain(ev);

        final int actionMasked = ev.getActionMasked();
        if (actionMasked == MotionEvent.ACTION_DOWN) {
            mNestedYOffset = 0;
        }
        vtev.offsetLocation(0, mNestedYOffset);
        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN: {
                onTouchDown(ev);
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                onTouchMove(ev, vtev);
                break;
            }

            case MotionEvent.ACTION_UP: {
                onTouchUp(ev);
                break;
            }

            case MotionEvent.ACTION_CANCEL: {
                onTouchCancel();
                break;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                onSecondaryPointerUp(ev);
                final int x = mMotionX;
                final int y = mMotionY;
                final int motionPosition = pointToPosition(x, y);
                if (motionPosition >= 0) {
                    // Remember where the motion event started
                    final View child = getChildAt(motionPosition - mFirstPosition);
                    mMotionViewOriginalTop = child.getTop();
                    mMotionPosition = motionPosition;
                }
                mLastY = y;
                break;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                // New pointers take over dragging duties
                final int index = ev.getActionIndex();
                final int id = ev.getPointerId(index);
                final int x = (int) ev.getX(index);
                final int y = (int) ev.getY(index);
                mMotionCorrection = 0;
                mActivePointerId = id;
                mMotionX = x;
                mMotionY = y;
                final int motionPosition = pointToPosition(x, y);
                if (motionPosition >= 0) {
                    // Remember where the motion event started
                    final View child = getChildAt(motionPosition - mFirstPosition);
                    mMotionViewOriginalTop = child.getTop();
                    mMotionPosition = motionPosition;
                }
                mLastY = y;
                break;
            }
        }

        if (mVelocityTracker != null) {
            mVelocityTracker.addMovement(vtev);
        }
        vtev.recycle();
        return true;
    }

    private void onTouchDown(MotionEvent ev) {
        mHasPerformedLongPress = false;
        mActivePointerId = ev.getPointerId(0);
        hideSelector();

        if (mTouchMode == TOUCH_MODE_OVERFLING) {
            // Stopped the fling. It is a scroll.
            mFlingRunnable.endFling();
            if (mPositionScroller != null) {
                mPositionScroller.stop();
            }
            mTouchMode = TOUCH_MODE_OVERSCROLL;
            mMotionX = (int) ev.getX();
            mMotionY = (int) ev.getY();
            mLastY = mMotionY;
            mMotionCorrection = 0;
            mDirection = 0;
        } else {
            final int x = (int) ev.getX();
            final int y = (int) ev.getY();
            int motionPosition = pointToPosition(x, y);

            if (!mDataChanged) {
                if (mTouchMode == TOUCH_MODE_FLING) {
                    // Stopped a fling. It is a scroll.
                    createScrollingCache();
                    mTouchMode = TOUCH_MODE_SCROLL;
                    mMotionCorrection = 0;
                    motionPosition = findMotionRow(y);
                    mFlingRunnable.flywheelTouch();
                } else if ((motionPosition >= 0) && getAdapter().isEnabled(motionPosition)) {
                    // User clicked on an actual view (and was not stopping a
                    // fling). It might be a click or a scroll. Assume it is a
                    // click until proven otherwise.
                    mTouchMode = TOUCH_MODE_DOWN;

                    // FIXME Debounce
                    if (mPendingCheckForTap == null) {
                        mPendingCheckForTap = new CheckForTap();
                    }

                    mPendingCheckForTap.x = ev.getX();
                    mPendingCheckForTap.y = ev.getY();
                    postDelayed(mPendingCheckForTap, ViewConfiguration.getTapTimeout());
                }
            }

            if (motionPosition >= 0) {
                // Remember where the motion event started
                final View v = getChildAt(motionPosition - mFirstPosition);
                mMotionViewOriginalTop = v.getTop();
            }

            mMotionX = x;
            mMotionY = y;
            mMotionPosition = motionPosition;
            mLastY = Integer.MIN_VALUE;
        }

        if (mTouchMode == TOUCH_MODE_DOWN && mMotionPosition != INVALID_POSITION
                && performButtonActionOnTouchDown(ev)) {
                removeCallbacks(mPendingCheckForTap);
        }
    }

    private void onTouchMove(MotionEvent ev, MotionEvent vtev) {
        if (mHasPerformedLongPress) {
            // Consume all move events following a successful long press.
            return;
        }

        int pointerIndex = ev.findPointerIndex(mActivePointerId);
        if (pointerIndex == -1) {
            pointerIndex = 0;
            mActivePointerId = ev.getPointerId(pointerIndex);
        }

        if (mDataChanged) {
            // Re-sync everything if data has been changed
            // since the scroll operation can query the adapter.
            layoutChildren();
        }

        final int y = (int) ev.getY(pointerIndex);

        switch (mTouchMode) {
            case TOUCH_MODE_DOWN:
            case TOUCH_MODE_TAP:
            case TOUCH_MODE_DONE_WAITING:
                // Check if we have moved far enough that it looks more like a
                // scroll than a tap. If so, we'll enter scrolling mode.
                if (startScrollIfNeeded((int) ev.getX(pointerIndex), y, vtev)) {
                    break;
                }
                // Otherwise, check containment within list bounds. If we're
                // outside bounds, cancel any active presses.
                final View motionView = getChildAt(mMotionPosition - mFirstPosition);
                final float x = ev.getX(pointerIndex);
                if (!pointInView(x, y, mTouchSlop)) {
                    setPressed(false);
                    if (motionView != null) {
                        motionView.setPressed(false);
                    }
                    removeCallbacks(mTouchMode == TOUCH_MODE_DOWN ?
                            mPendingCheckForTap : mPendingCheckForLongPress);
                    mTouchMode = TOUCH_MODE_DONE_WAITING;
                    updateSelectorState();
                } else if (motionView != null) {
                    // Still within bounds, update the hotspot.
                    final float[] point = mTmpPoint;
                    point[0] = x;
                    point[1] = y;
                    transformPointToViewLocal(point, motionView);
                    motionView.drawableHotspotChanged(point[0], point[1]);
                }
                break;
            case TOUCH_MODE_SCROLL:
            case TOUCH_MODE_OVERSCROLL:
                scrollIfNeeded((int) ev.getX(pointerIndex), y, vtev);
                break;
        }
    }

    private void onTouchUp(MotionEvent ev) {
        switch (mTouchMode) {
        case TOUCH_MODE_DOWN:
        case TOUCH_MODE_TAP:
        case TOUCH_MODE_DONE_WAITING:
            final int motionPosition = mMotionPosition;
            final View child = getChildAt(motionPosition - mFirstPosition);
            if (child != null) {
                if (mTouchMode != TOUCH_MODE_DOWN) {
                    child.setPressed(false);
                }

                final float x = ev.getX();
                final boolean inList = x > mListPadding.left && x < getWidth() - mListPadding.right;
                if (inList && !child.hasExplicitFocusable()) {
                    if (mPerformClick == null) {
                        mPerformClick = new PerformClick();
                    }

                    final AbsListView.PerformClick performClick = mPerformClick;
                    performClick.mClickMotionPosition = motionPosition;
                    performClick.rememberWindowAttachCount();

                    mResurrectToPosition = motionPosition;

                    if (mTouchMode == TOUCH_MODE_DOWN || mTouchMode == TOUCH_MODE_TAP) {
                        removeCallbacks(mTouchMode == TOUCH_MODE_DOWN ?
                                mPendingCheckForTap : mPendingCheckForLongPress);
                        mLayoutMode = LAYOUT_NORMAL;
                        if (!mDataChanged && mAdapter.isEnabled(motionPosition)) {
                            mTouchMode = TOUCH_MODE_TAP;
                            setSelectedPositionInt(mMotionPosition);
                            layoutChildren();
                            child.setPressed(true);
                            positionSelector(mMotionPosition, child);
                            setPressed(true);
                            if (mSelector != null) {
                                Drawable d = mSelector.getCurrent();
                                if (d != null && d instanceof TransitionDrawable) {
                                    ((TransitionDrawable) d).resetTransition();
                                }
                                mSelector.setHotspot(x, ev.getY());
                            }
                            if (mTouchModeReset != null) {
                                removeCallbacks(mTouchModeReset);
                            }
                            mTouchModeReset = new Runnable() {
                                @Override
                                public void run() {
                                    mTouchModeReset = null;
                                    mTouchMode = TOUCH_MODE_REST;
                                    child.setPressed(false);
                                    setPressed(false);
                                    if (!mDataChanged && !mIsDetaching && isAttachedToWindow()) {
                                        performClick.run();
                                    }
                                }
                            };
                            postDelayed(mTouchModeReset,
                                    ViewConfiguration.getPressedStateDuration());
                        } else {
                            mTouchMode = TOUCH_MODE_REST;
                            updateSelectorState();
                        }
                        return;
                    } else if (!mDataChanged && mAdapter.isEnabled(motionPosition)) {
                        performClick.run();
                    }
                }
            }
            mTouchMode = TOUCH_MODE_REST;
            updateSelectorState();
            break;
        case TOUCH_MODE_SCROLL:
            final int childCount = getChildCount();
            if (childCount > 0) {
                final int firstChildTop = getChildAt(0).getTop();
                final int lastChildBottom = getChildAt(childCount - 1).getBottom();
                final int contentTop = mListPadding.top;
                final int contentBottom = getHeight() - mListPadding.bottom;
                if (mFirstPosition == 0 && firstChildTop >= contentTop &&
                        mFirstPosition + childCount < mItemCount &&
                        lastChildBottom <= getHeight() - contentBottom) {
                    mTouchMode = TOUCH_MODE_REST;
                    reportScrollStateChange(OnScrollListener.SCROLL_STATE_IDLE);
                } else {
                    final VelocityTracker velocityTracker = mVelocityTracker;
                    velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);

                    final int initialVelocity = (int)
                            (velocityTracker.getYVelocity(mActivePointerId) * mVelocityScale);
                    // Fling if we have enough velocity and we aren't at a boundary.
                    // Since we can potentially overfling more than we can overscroll, don't
                    // allow the weird behavior where you can scroll to a boundary then
                    // fling further.
                    boolean flingVelocity = Math.abs(initialVelocity) > mMinimumVelocity;
                    if (flingVelocity &&
                            !((mFirstPosition == 0 &&
                                    firstChildTop == contentTop - mOverscrollDistance) ||
                              (mFirstPosition + childCount == mItemCount &&
                                    lastChildBottom == contentBottom + mOverscrollDistance))) {
                        if (!dispatchNestedPreFling(0, -initialVelocity)) {
                            if (mFlingRunnable == null) {
                                mFlingRunnable = new FlingRunnable();
                            }
                            reportScrollStateChange(OnScrollListener.SCROLL_STATE_FLING);
                            mFlingRunnable.start(-initialVelocity);
                            dispatchNestedFling(0, -initialVelocity, true);
                        } else {
                            mTouchMode = TOUCH_MODE_REST;
                            reportScrollStateChange(OnScrollListener.SCROLL_STATE_IDLE);
                        }
                    } else {
                        mTouchMode = TOUCH_MODE_REST;
                        reportScrollStateChange(OnScrollListener.SCROLL_STATE_IDLE);
                        if (mFlingRunnable != null) {
                            mFlingRunnable.endFling();
                        }
                        if (mPositionScroller != null) {
                            mPositionScroller.stop();
                        }
                        if (flingVelocity && !dispatchNestedPreFling(0, -initialVelocity)) {
                            dispatchNestedFling(0, -initialVelocity, false);
                        }
                    }
                }
            } else {
                mTouchMode = TOUCH_MODE_REST;
                reportScrollStateChange(OnScrollListener.SCROLL_STATE_IDLE);
            }
            break;

        case TOUCH_MODE_OVERSCROLL:
            if (mFlingRunnable == null) {
                mFlingRunnable = new FlingRunnable();
            }
            final VelocityTracker velocityTracker = mVelocityTracker;
            velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
            final int initialVelocity = (int) velocityTracker.getYVelocity(mActivePointerId);

            reportScrollStateChange(OnScrollListener.SCROLL_STATE_FLING);
            if (Math.abs(initialVelocity) > mMinimumVelocity) {
                mFlingRunnable.startOverfling(-initialVelocity);
            } else {
                mFlingRunnable.startSpringback();
            }

            break;
        }

        setPressed(false);

        if (shouldDisplayEdgeEffects()) {
            mEdgeGlowTop.onRelease();
            mEdgeGlowBottom.onRelease();
        }

        // Need to redraw since we probably aren't drawing the selector anymore
        invalidate();
        removeCallbacks(mPendingCheckForLongPress);
        recycleVelocityTracker();

        mActivePointerId = INVALID_POINTER;

        if (PROFILE_SCROLLING) {
            if (mScrollProfilingStarted) {
                Debug.stopMethodTracing();
                mScrollProfilingStarted = false;
            }
        }

        if (mScrollStrictSpan != null) {
            mScrollStrictSpan.finish();
            mScrollStrictSpan = null;
        }
    }

    private boolean shouldDisplayEdgeEffects() {
        return getOverScrollMode() != OVER_SCROLL_NEVER;
    }

    private void onTouchCancel() {
        switch (mTouchMode) {
        case TOUCH_MODE_OVERSCROLL:
            if (mFlingRunnable == null) {
                mFlingRunnable = new FlingRunnable();
            }
            mFlingRunnable.startSpringback();
            break;

        case TOUCH_MODE_OVERFLING:
            // Do nothing - let it play out.
            break;

        default:
            mTouchMode = TOUCH_MODE_REST;
            setPressed(false);
            final View motionView = this.getChildAt(mMotionPosition - mFirstPosition);
            if (motionView != null) {
                motionView.setPressed(false);
            }
            clearScrollingCache();
            removeCallbacks(mPendingCheckForLongPress);
            recycleVelocityTracker();
        }

        if (shouldDisplayEdgeEffects()) {
            mEdgeGlowTop.onRelease();
            mEdgeGlowBottom.onRelease();
        }
        mActivePointerId = INVALID_POINTER;
    }

    @Override
    protected void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY) {
        if (mScrollY != scrollY) {
            onScrollChanged(mScrollX, scrollY, mScrollX, mScrollY);
            mScrollY = scrollY;
            invalidateParentIfNeeded();

            awakenScrollBars();
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_SCROLL:
                final float axisValue;
                if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
                    axisValue = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                } else if (event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                    axisValue = event.getAxisValue(MotionEvent.AXIS_SCROLL);
                } else {
                    axisValue = 0;
                }

                final int delta = Math.round(axisValue * mVerticalScrollFactor);
                if (delta != 0) {
                    if (!trackMotionScroll(delta, delta)) {
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_BUTTON_PRESS:
                if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
                    int actionButton = event.getActionButton();
                    if ((actionButton == MotionEvent.BUTTON_STYLUS_PRIMARY
                            || actionButton == MotionEvent.BUTTON_SECONDARY)
                            && (mTouchMode == TOUCH_MODE_DOWN || mTouchMode == TOUCH_MODE_TAP)) {
                        if (performStylusButtonPressAction(event)) {
                            removeCallbacks(mPendingCheckForLongPress);
                            removeCallbacks(mPendingCheckForTap);
                        }
                    }
                }
                break;
        }

        return super.onGenericMotionEvent(event);
    }

    /**
     * Initiate a fling with the given velocity.
     *
     * <p>Applications can use this method to manually initiate a fling as if the user
     * initiated it via touch interaction.</p>
     *
     * @param velocityY Vertical velocity in pixels per second. Note that this is velocity of
     *                  content, not velocity of a touch that initiated the fling.
     */
    public void fling(int velocityY) {
        if (mFlingRunnable == null) {
            mFlingRunnable = new FlingRunnable();
        }
        reportScrollStateChange(OnScrollListener.SCROLL_STATE_FLING);
        mFlingRunnable.start(velocityY);
    }

    @Override
    public boolean onStartNestedScroll(View child, View target, int nestedScrollAxes) {
        return ((nestedScrollAxes & SCROLL_AXIS_VERTICAL) != 0);
    }

    @Override
    public void onNestedScrollAccepted(View child, View target, int axes) {
        super.onNestedScrollAccepted(child, target, axes);
        startNestedScroll(SCROLL_AXIS_VERTICAL);
    }

    @Override
    public void onNestedScroll(View target, int dxConsumed, int dyConsumed,
            int dxUnconsumed, int dyUnconsumed) {
        final int motionIndex = getChildCount() / 2;
        final View motionView = getChildAt(motionIndex);
        final int oldTop = motionView != null ? motionView.getTop() : 0;
        if (motionView == null || trackMotionScroll(-dyUnconsumed, -dyUnconsumed)) {
            int myUnconsumed = dyUnconsumed;
            int myConsumed = 0;
            if (motionView != null) {
                myConsumed = motionView.getTop() - oldTop;
                myUnconsumed -= myConsumed;
            }
            dispatchNestedScroll(0, myConsumed, 0, myUnconsumed, null);
        }
    }

    @Override
    public boolean onNestedFling(View target, float velocityX, float velocityY, boolean consumed) {
        final int childCount = getChildCount();
        if (!consumed && childCount > 0 && canScrollList((int) velocityY) &&
                Math.abs(velocityY) > mMinimumVelocity) {
            reportScrollStateChange(OnScrollListener.SCROLL_STATE_FLING);
            if (mFlingRunnable == null) {
                mFlingRunnable = new FlingRunnable();
            }
            if (!dispatchNestedPreFling(0, velocityY)) {
                mFlingRunnable.start((int) velocityY);
            }
            return true;
        }
        return dispatchNestedFling(velocityX, velocityY, consumed);
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        if (shouldDisplayEdgeEffects()) {
            final int scrollY = mScrollY;
            final boolean clipToPadding = getClipToPadding();
            final int width;
            final int height;
            final int translateX;
            final int translateY;

            if (clipToPadding) {
                width = getWidth() - mPaddingLeft - mPaddingRight;
                height = getHeight() - mPaddingTop - mPaddingBottom;
                translateX = mPaddingLeft;
                translateY = mPaddingTop;
            } else {
                width = getWidth();
                height = getHeight();
                translateX = 0;
                translateY = 0;
            }
            mEdgeGlowTop.setSize(width, height);
            mEdgeGlowBottom.setSize(width, height);
            if (!mEdgeGlowTop.isFinished()) {
                final int restoreCount = canvas.save();
                canvas.clipRect(translateX, translateY,
                         translateX + width ,translateY + mEdgeGlowTop.getMaxHeight());
                final int edgeY = Math.min(0, scrollY + mFirstPositionDistanceGuess) + translateY;
                canvas.translate(translateX, edgeY);
                if (mEdgeGlowTop.draw(canvas)) {
                    invalidateTopGlow();
                }
                canvas.restoreToCount(restoreCount);
            }
            if (!mEdgeGlowBottom.isFinished()) {
                final int restoreCount = canvas.save();
                canvas.clipRect(translateX, translateY + height - mEdgeGlowBottom.getMaxHeight(),
                        translateX + width, translateY + height);
                final int edgeX = -width + translateX;
                final int edgeY = Math.max(getHeight(), scrollY + mLastPositionDistanceGuess)
                        - (clipToPadding ? mPaddingBottom : 0);
                canvas.translate(edgeX, edgeY);
                canvas.rotate(180, width, 0);
                if (mEdgeGlowBottom.draw(canvas)) {
                    invalidateBottomGlow();
                }
                canvas.restoreToCount(restoreCount);
            }
        }
    }

    private void initOrResetVelocityTracker() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        } else {
            mVelocityTracker.clear();
        }
    }

    private void initVelocityTrackerIfNotExists() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
    }

    private void recycleVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        if (disallowIntercept) {
            recycleVelocityTracker();
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
    }

    @Override
    public boolean onInterceptHoverEvent(MotionEvent event) {
        if (mFastScroll != null && mFastScroll.onInterceptHoverEvent(event)) {
            return true;
        }

        return super.onInterceptHoverEvent(event);
    }

    @Override
    public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
        if (mFastScroll != null) {
            PointerIcon pointerIcon = mFastScroll.onResolvePointerIcon(event, pointerIndex);
            if (pointerIcon != null) {
                return pointerIcon;
            }
        }
        return super.onResolvePointerIcon(event, pointerIndex);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        final int actionMasked = ev.getActionMasked();
        View v;

        if (mPositionScroller != null) {
            mPositionScroller.stop();
        }

        if (mIsDetaching || !isAttachedToWindow()) {
            // Something isn't right.
            // Since we rely on being attached to get data set change notifications,
            // don't risk doing anything where we might try to resync and find things
            // in a bogus state.
            return false;
        }

        if (mFastScroll != null && mFastScroll.onInterceptTouchEvent(ev)) {
            return true;
        }

        switch (actionMasked) {
        case MotionEvent.ACTION_DOWN: {
            int touchMode = mTouchMode;
            if (touchMode == TOUCH_MODE_OVERFLING || touchMode == TOUCH_MODE_OVERSCROLL) {
                mMotionCorrection = 0;
                return true;
            }

            final int x = (int) ev.getX();
            final int y = (int) ev.getY();
            mActivePointerId = ev.getPointerId(0);

            int motionPosition = findMotionRow(y);
            if (touchMode != TOUCH_MODE_FLING && motionPosition >= 0) {
                // User clicked on an actual view (and was not stopping a fling).
                // Remember where the motion event started
                v = getChildAt(motionPosition - mFirstPosition);
                mMotionViewOriginalTop = v.getTop();
                mMotionX = x;
                mMotionY = y;
                mMotionPosition = motionPosition;
                mTouchMode = TOUCH_MODE_DOWN;
                clearScrollingCache();
            }
            mLastY = Integer.MIN_VALUE;
            initOrResetVelocityTracker();
            mVelocityTracker.addMovement(ev);
            mNestedYOffset = 0;
            startNestedScroll(SCROLL_AXIS_VERTICAL);
            if (touchMode == TOUCH_MODE_FLING) {
                return true;
            }
            break;
        }

        case MotionEvent.ACTION_MOVE: {
            switch (mTouchMode) {
            case TOUCH_MODE_DOWN:
                int pointerIndex = ev.findPointerIndex(mActivePointerId);
                if (pointerIndex == -1) {
                    pointerIndex = 0;
                    mActivePointerId = ev.getPointerId(pointerIndex);
                }
                final int y = (int) ev.getY(pointerIndex);
                initVelocityTrackerIfNotExists();
                mVelocityTracker.addMovement(ev);
                if (startScrollIfNeeded((int) ev.getX(pointerIndex), y, null)) {
                    return true;
                }
                break;
            }
            break;
        }

        case MotionEvent.ACTION_CANCEL:
        case MotionEvent.ACTION_UP: {
            mTouchMode = TOUCH_MODE_REST;
            mActivePointerId = INVALID_POINTER;
            recycleVelocityTracker();
            reportScrollStateChange(OnScrollListener.SCROLL_STATE_IDLE);
            stopNestedScroll();
            break;
        }

        case MotionEvent.ACTION_POINTER_UP: {
            onSecondaryPointerUp(ev);
            break;
        }
        }

        return false;
    }

    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = (ev.getAction() & MotionEvent.ACTION_POINTER_INDEX_MASK) >>
                MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        final int pointerId = ev.getPointerId(pointerIndex);
        if (pointerId == mActivePointerId) {
            // This was our active pointer going up. Choose a new
            // active pointer and adjust accordingly.
            // TODO: Make this decision more intelligent.
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mMotionX = (int) ev.getX(newPointerIndex);
            mMotionY = (int) ev.getY(newPointerIndex);
            mMotionCorrection = 0;
            mActivePointerId = ev.getPointerId(newPointerIndex);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addTouchables(ArrayList<View> views) {
        final int count = getChildCount();
        final int firstPosition = mFirstPosition;
        final ListAdapter adapter = mAdapter;

        if (adapter == null) {
            return;
        }

        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (adapter.isEnabled(firstPosition + i)) {
                views.add(child);
            }
            child.addTouchables(views);
        }
    }

    /**
     * Fires an "on scroll state changed" event to the registered
     * {@link android.widget.AbsListView.OnScrollListener}, if any. The state change
     * is fired only if the specified state is different from the previously known state.
     *
     * @param newState The new scroll state.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 123769710)
    void reportScrollStateChange(int newState) {
        if (newState != mLastScrollState) {
            if (mOnScrollListener != null) {
                mLastScrollState = newState;
                mOnScrollListener.onScrollStateChanged(this, newState);
            }
        }
    }

    /**
     * Responsible for fling behavior. Use {@link #start(int)} to
     * initiate a fling. Each frame of the fling is handled in {@link #run()}.
     * A FlingRunnable will keep re-posting itself until the fling is done.
     *
     */
    private class FlingRunnable implements Runnable {
        /**
         * Tracks the decay of a fling scroll
         */
        @UnsupportedAppUsage
        private final OverScroller mScroller;

        /**
         * Y value reported by mScroller on the previous fling
         */
        private int mLastFlingY;

        /**
         * If true, {@link #endFling()} will not report scroll state change to
         * {@link OnScrollListener#SCROLL_STATE_IDLE}.
         */
        private boolean mSuppressIdleStateChangeCall;

        private final Runnable mCheckFlywheel = new Runnable() {
            @Override
            public void run() {
                final int activeId = mActivePointerId;
                final VelocityTracker vt = mVelocityTracker;
                final OverScroller scroller = mScroller;
                if (vt == null || activeId == INVALID_POINTER) {
                    return;
                }

                vt.computeCurrentVelocity(1000, mMaximumVelocity);
                final float yvel = -vt.getYVelocity(activeId);

                if (Math.abs(yvel) >= mMinimumVelocity
                        && scroller.isScrollingInDirection(0, yvel)) {
                    // Keep the fling alive a little longer
                    postDelayed(this, FLYWHEEL_TIMEOUT);
                } else {
                    endFling();
                    mTouchMode = TOUCH_MODE_SCROLL;
                    reportScrollStateChange(OnScrollListener.SCROLL_STATE_TOUCH_SCROLL);
                }
            }
        };

        private static final int FLYWHEEL_TIMEOUT = 40; // milliseconds

        FlingRunnable() {
            mScroller = new OverScroller(getContext());
        }

        // Use AbsListView#fling(int) instead
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
        void start(int initialVelocity) {
            int initialY = initialVelocity < 0 ? Integer.MAX_VALUE : 0;
            mLastFlingY = initialY;
            mScroller.setInterpolator(null);
            mScroller.fling(0, initialY, 0, initialVelocity,
                    0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE);
            mTouchMode = TOUCH_MODE_FLING;
            mSuppressIdleStateChangeCall = false;
            postOnAnimation(this);

            if (PROFILE_FLINGING) {
                if (!mFlingProfilingStarted) {
                    Debug.startMethodTracing("AbsListViewFling");
                    mFlingProfilingStarted = true;
                }
            }

            if (mFlingStrictSpan == null) {
                mFlingStrictSpan = StrictMode.enterCriticalSpan("AbsListView-fling");
            }
        }

        void startSpringback() {
            mSuppressIdleStateChangeCall = false;
            if (mScroller.springBack(0, mScrollY, 0, 0, 0, 0)) {
                mTouchMode = TOUCH_MODE_OVERFLING;
                invalidate();
                postOnAnimation(this);
            } else {
                mTouchMode = TOUCH_MODE_REST;
                reportScrollStateChange(OnScrollListener.SCROLL_STATE_IDLE);
            }
        }

        void startOverfling(int initialVelocity) {
            mScroller.setInterpolator(null);
            mScroller.fling(0, mScrollY, 0, initialVelocity, 0, 0,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, 0, getHeight());
            mTouchMode = TOUCH_MODE_OVERFLING;
            mSuppressIdleStateChangeCall = false;
            invalidate();
            postOnAnimation(this);
        }

        void edgeReached(int delta) {
            mScroller.notifyVerticalEdgeReached(mScrollY, 0, mOverflingDistance);
            final int overscrollMode = getOverScrollMode();
            if (overscrollMode == OVER_SCROLL_ALWAYS ||
                    (overscrollMode == OVER_SCROLL_IF_CONTENT_SCROLLS && !contentFits())) {
                mTouchMode = TOUCH_MODE_OVERFLING;
                final int vel = (int) mScroller.getCurrVelocity();
                if (delta > 0) {
                    mEdgeGlowTop.onAbsorb(vel);
                } else {
                    mEdgeGlowBottom.onAbsorb(vel);
                }
            } else {
                mTouchMode = TOUCH_MODE_REST;
                if (mPositionScroller != null) {
                    mPositionScroller.stop();
                }
            }
            invalidate();
            postOnAnimation(this);
        }

        void startScroll(int distance, int duration, boolean linear,
                boolean suppressEndFlingStateChangeCall) {
            int initialY = distance < 0 ? Integer.MAX_VALUE : 0;
            mLastFlingY = initialY;
            mScroller.setInterpolator(linear ? sLinearInterpolator : null);
            mScroller.startScroll(0, initialY, 0, distance, duration);
            mTouchMode = TOUCH_MODE_FLING;
            mSuppressIdleStateChangeCall = suppressEndFlingStateChangeCall;
            postOnAnimation(this);
        }

        // To interrupt a fling early you should use smoothScrollBy(0,0) instead
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
        void endFling() {
            mTouchMode = TOUCH_MODE_REST;

            removeCallbacks(this);
            removeCallbacks(mCheckFlywheel);

            if (!mSuppressIdleStateChangeCall) {
                reportScrollStateChange(OnScrollListener.SCROLL_STATE_IDLE);
            }
            clearScrollingCache();
            mScroller.abortAnimation();

            if (mFlingStrictSpan != null) {
                mFlingStrictSpan.finish();
                mFlingStrictSpan = null;
            }
        }

        void flywheelTouch() {
            postDelayed(mCheckFlywheel, FLYWHEEL_TIMEOUT);
        }

        @Override
        public void run() {
            switch (mTouchMode) {
            default:
                endFling();
                return;

            case TOUCH_MODE_SCROLL:
                if (mScroller.isFinished()) {
                    return;
                }
                // Fall through
            case TOUCH_MODE_FLING: {
                if (mDataChanged) {
                    layoutChildren();
                }

                if (mItemCount == 0 || getChildCount() == 0) {
                    endFling();
                    return;
                }

                final OverScroller scroller = mScroller;
                boolean more = scroller.computeScrollOffset();
                final int y = scroller.getCurrY();

                // Flip sign to convert finger direction to list items direction
                // (e.g. finger moving down means list is moving towards the top)
                int delta = mLastFlingY - y;

                // Pretend that each frame of a fling scroll is a touch scroll
                if (delta > 0) {
                    // List is moving towards the top. Use first view as mMotionPosition
                    mMotionPosition = mFirstPosition;
                    final View firstView = getChildAt(0);
                    mMotionViewOriginalTop = firstView.getTop();

                    // Don't fling more than 1 screen
                    delta = Math.min(getHeight() - mPaddingBottom - mPaddingTop - 1, delta);
                } else {
                    // List is moving towards the bottom. Use last view as mMotionPosition
                    int offsetToLast = getChildCount() - 1;
                    mMotionPosition = mFirstPosition + offsetToLast;

                    final View lastView = getChildAt(offsetToLast);
                    mMotionViewOriginalTop = lastView.getTop();

                    // Don't fling more than 1 screen
                    delta = Math.max(-(getHeight() - mPaddingBottom - mPaddingTop - 1), delta);
                }

                // Check to see if we have bumped into the scroll limit
                View motionView = getChildAt(mMotionPosition - mFirstPosition);
                int oldTop = 0;
                if (motionView != null) {
                    oldTop = motionView.getTop();
                }

                // Don't stop just because delta is zero (it could have been rounded)
                final boolean atEdge = trackMotionScroll(delta, delta);
                final boolean atEnd = atEdge && (delta != 0);
                if (atEnd) {
                    if (motionView != null) {
                        // Tweak the scroll for how far we overshot
                        int overshoot = -(delta - (motionView.getTop() - oldTop));
                        overScrollBy(0, overshoot, 0, mScrollY, 0, 0,
                                0, mOverflingDistance, false);
                    }
                    if (more) {
                        edgeReached(delta);
                    }
                    break;
                }

                if (more && !atEnd) {
                    if (atEdge) invalidate();
                    mLastFlingY = y;
                    postOnAnimation(this);
                } else {
                    endFling();

                    if (PROFILE_FLINGING) {
                        if (mFlingProfilingStarted) {
                            Debug.stopMethodTracing();
                            mFlingProfilingStarted = false;
                        }

                        if (mFlingStrictSpan != null) {
                            mFlingStrictSpan.finish();
                            mFlingStrictSpan = null;
                        }
                    }
                }
                break;
            }

            case TOUCH_MODE_OVERFLING: {
                final OverScroller scroller = mScroller;
                if (scroller.computeScrollOffset()) {
                    final int scrollY = mScrollY;
                    final int currY = scroller.getCurrY();
                    final int deltaY = currY - scrollY;
                    if (overScrollBy(0, deltaY, 0, scrollY, 0, 0,
                            0, mOverflingDistance, false)) {
                        final boolean crossDown = scrollY <= 0 && currY > 0;
                        final boolean crossUp = scrollY >= 0 && currY < 0;
                        if (crossDown || crossUp) {
                            int velocity = (int) scroller.getCurrVelocity();
                            if (crossUp) velocity = -velocity;

                            // Don't flywheel from this; we're just continuing things.
                            scroller.abortAnimation();
                            start(velocity);
                        } else {
                            startSpringback();
                        }
                    } else {
                        invalidate();
                        postOnAnimation(this);
                    }
                } else {
                    endFling();
                }
                break;
            }
            }
        }
    }

    /**
     * The amount of friction applied to flings. The default value
     * is {@link ViewConfiguration#getScrollFriction}.
     */
    public void setFriction(float friction) {
        if (mFlingRunnable == null) {
            mFlingRunnable = new FlingRunnable();
        }
        mFlingRunnable.mScroller.setFriction(friction);
    }

    /**
     * Sets a scale factor for the fling velocity. The initial scale
     * factor is 1.0.
     *
     * @param scale The scale factor to multiply the velocity by.
     */
    public void setVelocityScale(float scale) {
        mVelocityScale = scale;
    }

    /**
     * Override this for better control over position scrolling.
     */
    AbsPositionScroller createPositionScroller() {
        return new PositionScroller();
    }

    /**
     * Smoothly scroll to the specified adapter position. The view will
     * scroll such that the indicated position is displayed.
     * @param position Scroll to this adapter position.
     */
    public void smoothScrollToPosition(int position) {
        if (mPositionScroller == null) {
            mPositionScroller = createPositionScroller();
        }
        mPositionScroller.start(position);
    }

    /**
     * Smoothly scroll to the specified adapter position. The view will scroll
     * such that the indicated position is displayed <code>offset</code> pixels below
     * the top edge of the view. If this is impossible, (e.g. the offset would scroll
     * the first or last item beyond the boundaries of the list) it will get as close
     * as possible. The scroll will take <code>duration</code> milliseconds to complete.
     *
     * @param position Position to scroll to
     * @param offset Desired distance in pixels of <code>position</code> from the top
     *               of the view when scrolling is finished
     * @param duration Number of milliseconds to use for the scroll
     */
    public void smoothScrollToPositionFromTop(int position, int offset, int duration) {
        if (mPositionScroller == null) {
            mPositionScroller = createPositionScroller();
        }
        mPositionScroller.startWithOffset(position, offset, duration);
    }

    /**
     * Smoothly scroll to the specified adapter position. The view will scroll
     * such that the indicated position is displayed <code>offset</code> pixels below
     * the top edge of the view. If this is impossible, (e.g. the offset would scroll
     * the first or last item beyond the boundaries of the list) it will get as close
     * as possible.
     *
     * @param position Position to scroll to
     * @param offset Desired distance in pixels of <code>position</code> from the top
     *               of the view when scrolling is finished
     */
    public void smoothScrollToPositionFromTop(int position, int offset) {
        if (mPositionScroller == null) {
            mPositionScroller = createPositionScroller();
        }
        mPositionScroller.startWithOffset(position, offset);
    }

    /**
     * Smoothly scroll to the specified adapter position. The view will
     * scroll such that the indicated position is displayed, but it will
     * stop early if scrolling further would scroll boundPosition out of
     * view.
     *
     * @param position Scroll to this adapter position.
     * @param boundPosition Do not scroll if it would move this adapter
     *          position out of view.
     */
    public void smoothScrollToPosition(int position, int boundPosition) {
        if (mPositionScroller == null) {
            mPositionScroller = createPositionScroller();
        }
        mPositionScroller.start(position, boundPosition);
    }

    /**
     * Smoothly scroll by distance pixels over duration milliseconds.
     * @param distance Distance to scroll in pixels.
     * @param duration Duration of the scroll animation in milliseconds.
     */
    public void smoothScrollBy(int distance, int duration) {
        smoothScrollBy(distance, duration, false, false);
    }

    @UnsupportedAppUsage
    void smoothScrollBy(int distance, int duration, boolean linear,
            boolean suppressEndFlingStateChangeCall) {
        if (mFlingRunnable == null) {
            mFlingRunnable = new FlingRunnable();
        }

        // No sense starting to scroll if we're not going anywhere
        final int firstPos = mFirstPosition;
        final int childCount = getChildCount();
        final int lastPos = firstPos + childCount;
        final int topLimit = getPaddingTop();
        final int bottomLimit = getHeight() - getPaddingBottom();

        if (distance == 0 || mItemCount == 0 || childCount == 0 ||
                (firstPos == 0 && getChildAt(0).getTop() == topLimit && distance < 0) ||
                (lastPos == mItemCount &&
                        getChildAt(childCount - 1).getBottom() == bottomLimit && distance > 0)) {
            mFlingRunnable.endFling();
            if (mPositionScroller != null) {
                mPositionScroller.stop();
            }
        } else {
            reportScrollStateChange(OnScrollListener.SCROLL_STATE_FLING);
            mFlingRunnable.startScroll(distance, duration, linear, suppressEndFlingStateChangeCall);
        }
    }

    /**
     * Allows RemoteViews to scroll relatively to a position.
     */
    void smoothScrollByOffset(int position) {
        int index = -1;
        if (position < 0) {
            index = getFirstVisiblePosition();
        } else if (position > 0) {
            index = getLastVisiblePosition();
        }

        if (index > -1) {
            View child = getChildAt(index - getFirstVisiblePosition());
            if (child != null) {
                Rect visibleRect = new Rect();
                if (child.getGlobalVisibleRect(visibleRect)) {
                    // the child is partially visible
                    int childRectArea = child.getWidth() * child.getHeight();
                    int visibleRectArea = visibleRect.width() * visibleRect.height();
                    float visibleArea = (visibleRectArea / (float) childRectArea);
                    final float visibleThreshold = 0.75f;
                    if ((position < 0) && (visibleArea < visibleThreshold)) {
                        // the top index is not perceivably visible so offset
                        // to account for showing that top index as well
                        ++index;
                    } else if ((position > 0) && (visibleArea < visibleThreshold)) {
                        // the bottom index is not perceivably visible so offset
                        // to account for showing that bottom index as well
                        --index;
                    }
                }
                smoothScrollToPosition(Math.max(0, Math.min(getCount(), index + position)));
            }
        }
    }

    private void createScrollingCache() {
        if (mScrollingCacheEnabled && !mCachingStarted && !isHardwareAccelerated()) {
            setChildrenDrawnWithCacheEnabled(true);
            setChildrenDrawingCacheEnabled(true);
            mCachingStarted = mCachingActive = true;
        }
    }

    private void clearScrollingCache() {
        if (!isHardwareAccelerated()) {
            if (mClearScrollingCache == null) {
                mClearScrollingCache = new Runnable() {
                    @Override
                    public void run() {
                        if (mCachingStarted) {
                            mCachingStarted = mCachingActive = false;
                            setChildrenDrawnWithCacheEnabled(false);
                            if ((mPersistentDrawingCache & PERSISTENT_SCROLLING_CACHE) == 0) {
                                setChildrenDrawingCacheEnabled(false);
                            }
                            if (!isAlwaysDrawnWithCacheEnabled()) {
                                invalidate();
                            }
                        }
                    }
                };
            }
            post(mClearScrollingCache);
        }
    }

    /**
     * Scrolls the list items within the view by a specified number of pixels.
     *
     * <p>The actual amount of scroll is capped by the list content viewport height
     * which is the list height minus top and bottom paddings minus one pixel.</p>
     *
     * @param y the amount of pixels to scroll by vertically
     * @see #canScrollList(int)
     */
    public void scrollListBy(int y) {
        trackMotionScroll(-y, -y);
    }

    /**
     * Check if the items in the list can be scrolled in a certain direction.
     *
     * @param direction Negative to check scrolling up, positive to check
     *            scrolling down.
     * @return true if the list can be scrolled in the specified direction,
     *         false otherwise.
     * @see #scrollListBy(int)
     */
    public boolean canScrollList(int direction) {
        final int childCount = getChildCount();
        if (childCount == 0) {
            return false;
        }

        final int firstPosition = mFirstPosition;
        final Rect listPadding = mListPadding;
        if (direction > 0) {
            final int lastBottom = getChildAt(childCount - 1).getBottom();
            final int lastPosition = firstPosition + childCount;
            return lastPosition < mItemCount || lastBottom > getHeight() - listPadding.bottom;
        } else {
            final int firstTop = getChildAt(0).getTop();
            return firstPosition > 0 || firstTop < listPadding.top;
        }
    }

    /**
     * Track a motion scroll
     *
     * @param deltaY Amount to offset mMotionView. This is the accumulated delta since the motion
     *        began. Positive numbers mean the user's finger is moving down the screen.
     * @param incrementalDeltaY Change in deltaY from the previous event.
     * @return true if we're already at the beginning/end of the list and have nothing to do.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 124051739)
    boolean trackMotionScroll(int deltaY, int incrementalDeltaY) {
        final int childCount = getChildCount();
        if (childCount == 0) {
            return true;
        }

        final int firstTop = getChildAt(0).getTop();
        final int lastBottom = getChildAt(childCount - 1).getBottom();

        final Rect listPadding = mListPadding;

        // "effective padding" In this case is the amount of padding that affects
        // how much space should not be filled by items. If we don't clip to padding
        // there is no effective padding.
        int effectivePaddingTop = 0;
        int effectivePaddingBottom = 0;
        if ((mGroupFlags & CLIP_TO_PADDING_MASK) == CLIP_TO_PADDING_MASK) {
            effectivePaddingTop = listPadding.top;
            effectivePaddingBottom = listPadding.bottom;
        }

         // FIXME account for grid vertical spacing too?
        final int spaceAbove = effectivePaddingTop - firstTop;
        final int end = getHeight() - effectivePaddingBottom;
        final int spaceBelow = lastBottom - end;

        final int height = getHeight() - mPaddingBottom - mPaddingTop;
        if (deltaY < 0) {
            deltaY = Math.max(-(height - 1), deltaY);
        } else {
            deltaY = Math.min(height - 1, deltaY);
        }

        if (incrementalDeltaY < 0) {
            incrementalDeltaY = Math.max(-(height - 1), incrementalDeltaY);
        } else {
            incrementalDeltaY = Math.min(height - 1, incrementalDeltaY);
        }

        final int firstPosition = mFirstPosition;

        // Update our guesses for where the first and last views are
        if (firstPosition == 0) {
            mFirstPositionDistanceGuess = firstTop - listPadding.top;
        } else {
            mFirstPositionDistanceGuess += incrementalDeltaY;
        }
        if (firstPosition + childCount == mItemCount) {
            mLastPositionDistanceGuess = lastBottom + listPadding.bottom;
        } else {
            mLastPositionDistanceGuess += incrementalDeltaY;
        }

        final boolean cannotScrollDown = (firstPosition == 0 &&
                firstTop >= listPadding.top && incrementalDeltaY >= 0);
        final boolean cannotScrollUp = (firstPosition + childCount == mItemCount &&
                lastBottom <= getHeight() - listPadding.bottom && incrementalDeltaY <= 0);

        if (cannotScrollDown || cannotScrollUp) {
            return incrementalDeltaY != 0;
        }

        final boolean down = incrementalDeltaY < 0;

        final boolean inTouchMode = isInTouchMode();
        if (inTouchMode) {
            hideSelector();
        }

        final int headerViewsCount = getHeaderViewsCount();
        final int footerViewsStart = mItemCount - getFooterViewsCount();

        int start = 0;
        int count = 0;

        if (down) {
            int top = -incrementalDeltaY;
            if ((mGroupFlags & CLIP_TO_PADDING_MASK) == CLIP_TO_PADDING_MASK) {
                top += listPadding.top;
            }
            for (int i = 0; i < childCount; i++) {
                final View child = getChildAt(i);
                if (child.getBottom() >= top) {
                    break;
                } else {
                    count++;
                    int position = firstPosition + i;
                    if (position >= headerViewsCount && position < footerViewsStart) {
                        // The view will be rebound to new data, clear any
                        // system-managed transient state.
                        child.clearAccessibilityFocus();
                        mRecycler.addScrapView(child, position);
                    }
                }
            }
        } else {
            int bottom = getHeight() - incrementalDeltaY;
            if ((mGroupFlags & CLIP_TO_PADDING_MASK) == CLIP_TO_PADDING_MASK) {
                bottom -= listPadding.bottom;
            }
            for (int i = childCount - 1; i >= 0; i--) {
                final View child = getChildAt(i);
                if (child.getTop() <= bottom) {
                    break;
                } else {
                    start = i;
                    count++;
                    int position = firstPosition + i;
                    if (position >= headerViewsCount && position < footerViewsStart) {
                        // The view will be rebound to new data, clear any
                        // system-managed transient state.
                        child.clearAccessibilityFocus();
                        mRecycler.addScrapView(child, position);
                    }
                }
            }
        }

        mMotionViewNewTop = mMotionViewOriginalTop + deltaY;

        mBlockLayoutRequests = true;

        if (count > 0) {
            detachViewsFromParent(start, count);
            mRecycler.removeSkippedScrap();
        }

        // invalidate before moving the children to avoid unnecessary invalidate
        // calls to bubble up from the children all the way to the top
        if (!awakenScrollBars()) {
           invalidate();
        }

        offsetChildrenTopAndBottom(incrementalDeltaY);

        if (down) {
            mFirstPosition += count;
        }

        final int absIncrementalDeltaY = Math.abs(incrementalDeltaY);
        if (spaceAbove < absIncrementalDeltaY || spaceBelow < absIncrementalDeltaY) {
            fillGap(down);
        }

        mRecycler.fullyDetachScrapViews();
        boolean selectorOnScreen = false;
        if (!inTouchMode && mSelectedPosition != INVALID_POSITION) {
            final int childIndex = mSelectedPosition - mFirstPosition;
            if (childIndex >= 0 && childIndex < getChildCount()) {
                positionSelector(mSelectedPosition, getChildAt(childIndex));
                selectorOnScreen = true;
            }
        } else if (mSelectorPosition != INVALID_POSITION) {
            final int childIndex = mSelectorPosition - mFirstPosition;
            if (childIndex >= 0 && childIndex < getChildCount()) {
                positionSelector(mSelectorPosition, getChildAt(childIndex));
                selectorOnScreen = true;
            }
        }
        if (!selectorOnScreen) {
            mSelectorRect.setEmpty();
        }

        mBlockLayoutRequests = false;

        invokeOnItemScrollListener();

        return false;
    }

    /**
     * Returns the number of header views in the list. Header views are special views
     * at the top of the list that should not be recycled during a layout.
     *
     * @return The number of header views, 0 in the default implementation.
     */
    int getHeaderViewsCount() {
        return 0;
    }

    /**
     * Returns the number of footer views in the list. Footer views are special views
     * at the bottom of the list that should not be recycled during a layout.
     *
     * @return The number of footer views, 0 in the default implementation.
     */
    int getFooterViewsCount() {
        return 0;
    }

    /**
     * Fills the gap left open by a touch-scroll. During a touch scroll, children that
     * remain on screen are shifted and the other ones are discarded. The role of this
     * method is to fill the gap thus created by performing a partial layout in the
     * empty space.
     *
     * @param down true if the scroll is going down, false if it is going up
     */
    abstract void fillGap(boolean down);

    void hideSelector() {
        if (mSelectedPosition != INVALID_POSITION) {
            if (mLayoutMode != LAYOUT_SPECIFIC) {
                mResurrectToPosition = mSelectedPosition;
            }
            if (mNextSelectedPosition >= 0 && mNextSelectedPosition != mSelectedPosition) {
                mResurrectToPosition = mNextSelectedPosition;
            }
            setSelectedPositionInt(INVALID_POSITION);
            setNextSelectedPositionInt(INVALID_POSITION);
            mSelectedTop = 0;
        }
    }

    /**
     * @return A position to select. First we try mSelectedPosition. If that has been clobbered by
     * entering touch mode, we then try mResurrectToPosition. Values are pinned to the range
     * of items available in the adapter
     */
    int reconcileSelectedPosition() {
        int position = mSelectedPosition;
        if (position < 0) {
            position = mResurrectToPosition;
        }
        position = Math.max(0, position);
        position = Math.min(position, mItemCount - 1);
        return position;
    }

    /**
     * Find the row closest to y. This row will be used as the motion row when scrolling
     *
     * @param y Where the user touched
     * @return The position of the first (or only) item in the row containing y
     */
    @UnsupportedAppUsage
    abstract int findMotionRow(int y);

    /**
     * Find the row closest to y. This row will be used as the motion row when scrolling.
     *
     * @param y Where the user touched
     * @return The position of the first (or only) item in the row closest to y
     */
    int findClosestMotionRow(int y) {
        final int childCount = getChildCount();
        if (childCount == 0) {
            return INVALID_POSITION;
        }

        final int motionRow = findMotionRow(y);
        return motionRow != INVALID_POSITION ? motionRow : mFirstPosition + childCount - 1;
    }

    /**
     * Causes all the views to be rebuilt and redrawn.
     */
    public void invalidateViews() {
        mDataChanged = true;
        rememberSyncState();
        requestLayout();
        invalidate();
    }

    /**
     * If there is a selection returns false.
     * Otherwise resurrects the selection and returns true if resurrected.
     */
    @UnsupportedAppUsage
    boolean resurrectSelectionIfNeeded() {
        if (mSelectedPosition < 0 && resurrectSelection()) {
            updateSelectorState();
            return true;
        }
        return false;
    }

    /**
     * Makes the item at the supplied position selected.
     *
     * @param position the position of the new selection
     */
    abstract void setSelectionInt(int position);

    /**
     * Attempt to bring the selection back if the user is switching from touch
     * to trackball mode
     * @return Whether selection was set to something.
     */
    boolean resurrectSelection() {
        final int childCount = getChildCount();

        if (childCount <= 0) {
            return false;
        }

        int selectedTop = 0;
        int selectedPos;
        int childrenTop = mListPadding.top;
        int childrenBottom = mBottom - mTop - mListPadding.bottom;
        final int firstPosition = mFirstPosition;
        final int toPosition = mResurrectToPosition;
        boolean down = true;

        if (toPosition >= firstPosition && toPosition < firstPosition + childCount) {
            selectedPos = toPosition;

            final View selected = getChildAt(selectedPos - mFirstPosition);
            selectedTop = selected.getTop();
            int selectedBottom = selected.getBottom();

            // We are scrolled, don't get in the fade
            if (selectedTop < childrenTop) {
                selectedTop = childrenTop + getVerticalFadingEdgeLength();
            } else if (selectedBottom > childrenBottom) {
                selectedTop = childrenBottom - selected.getMeasuredHeight()
                        - getVerticalFadingEdgeLength();
            }
        } else {
            if (toPosition < firstPosition) {
                // Default to selecting whatever is first
                selectedPos = firstPosition;
                for (int i = 0; i < childCount; i++) {
                    final View v = getChildAt(i);
                    final int top = v.getTop();

                    if (i == 0) {
                        // Remember the position of the first item
                        selectedTop = top;
                        // See if we are scrolled at all
                        if (firstPosition > 0 || top < childrenTop) {
                            // If we are scrolled, don't select anything that is
                            // in the fade region
                            childrenTop += getVerticalFadingEdgeLength();
                        }
                    }
                    if (top >= childrenTop) {
                        // Found a view whose top is fully visisble
                        selectedPos = firstPosition + i;
                        selectedTop = top;
                        break;
                    }
                }
            } else {
                final int itemCount = mItemCount;
                down = false;
                selectedPos = firstPosition + childCount - 1;

                for (int i = childCount - 1; i >= 0; i--) {
                    final View v = getChildAt(i);
                    final int top = v.getTop();
                    final int bottom = v.getBottom();

                    if (i == childCount - 1) {
                        selectedTop = top;
                        if (firstPosition + childCount < itemCount || bottom > childrenBottom) {
                            childrenBottom -= getVerticalFadingEdgeLength();
                        }
                    }

                    if (bottom <= childrenBottom) {
                        selectedPos = firstPosition + i;
                        selectedTop = top;
                        break;
                    }
                }
            }
        }

        mResurrectToPosition = INVALID_POSITION;
        removeCallbacks(mFlingRunnable);
        if (mPositionScroller != null) {
            mPositionScroller.stop();
        }
        mTouchMode = TOUCH_MODE_REST;
        clearScrollingCache();
        mSpecificTop = selectedTop;
        selectedPos = lookForSelectablePosition(selectedPos, down);
        if (selectedPos >= firstPosition && selectedPos <= getLastVisiblePosition()) {
            mLayoutMode = LAYOUT_SPECIFIC;
            updateSelectorState();
            setSelectionInt(selectedPos);
            invokeOnItemScrollListener();
        } else {
            selectedPos = INVALID_POSITION;
        }
        reportScrollStateChange(OnScrollListener.SCROLL_STATE_IDLE);

        return selectedPos >= 0;
    }

    void confirmCheckedPositionsById() {
        // Clear out the positional check states, we'll rebuild it below from IDs.
        mCheckStates.clear();

        boolean checkedCountChanged = false;
        for (int checkedIndex = 0; checkedIndex < mCheckedIdStates.size(); checkedIndex++) {
            final long id = mCheckedIdStates.keyAt(checkedIndex);
            final int lastPos = mCheckedIdStates.valueAt(checkedIndex);

            final long lastPosId = mAdapter.getItemId(lastPos);
            if (id != lastPosId) {
                // Look around to see if the ID is nearby. If not, uncheck it.
                final int start = Math.max(0, lastPos - CHECK_POSITION_SEARCH_DISTANCE);
                final int end = Math.min(lastPos + CHECK_POSITION_SEARCH_DISTANCE, mItemCount);
                boolean found = false;
                for (int searchPos = start; searchPos < end; searchPos++) {
                    final long searchId = mAdapter.getItemId(searchPos);
                    if (id == searchId) {
                        found = true;
                        mCheckStates.put(searchPos, true);
                        mCheckedIdStates.setValueAt(checkedIndex, searchPos);
                        break;
                    }
                }

                if (!found) {
                    mCheckedIdStates.delete(id);
                    checkedIndex--;
                    mCheckedItemCount--;
                    checkedCountChanged = true;
                    if (mChoiceActionMode != null && mMultiChoiceModeCallback != null) {
                        mMultiChoiceModeCallback.onItemCheckedStateChanged(mChoiceActionMode,
                                lastPos, id, false);
                    }
                }
            } else {
                mCheckStates.put(lastPos, true);
            }
        }

        if (checkedCountChanged && mChoiceActionMode != null) {
            mChoiceActionMode.invalidate();
        }
    }

    @Override
    protected void handleDataChanged() {
        int count = mItemCount;
        int lastHandledItemCount = mLastHandledItemCount;
        mLastHandledItemCount = mItemCount;

        if (mChoiceMode != CHOICE_MODE_NONE && mAdapter != null && mAdapter.hasStableIds()) {
            confirmCheckedPositionsById();
        }

        // TODO: In the future we can recycle these views based on stable ID instead.
        mRecycler.clearTransientStateViews();

        if (count > 0) {
            int newPos;
            int selectablePos;

            // Find the row we are supposed to sync to
            if (mNeedSync) {
                // Update this first, since setNextSelectedPositionInt inspects it
                mNeedSync = false;
                mPendingSync = null;

                if (mTranscriptMode == TRANSCRIPT_MODE_ALWAYS_SCROLL) {
                    mLayoutMode = LAYOUT_FORCE_BOTTOM;
                    return;
                } else if (mTranscriptMode == TRANSCRIPT_MODE_NORMAL) {
                    if (mForceTranscriptScroll) {
                        mForceTranscriptScroll = false;
                        mLayoutMode = LAYOUT_FORCE_BOTTOM;
                        return;
                    }
                    final int childCount = getChildCount();
                    final int listBottom = getHeight() - getPaddingBottom();
                    final View lastChild = getChildAt(childCount - 1);
                    final int lastBottom = lastChild != null ? lastChild.getBottom() : listBottom;
                    if (mFirstPosition + childCount >= lastHandledItemCount &&
                            lastBottom <= listBottom) {
                        mLayoutMode = LAYOUT_FORCE_BOTTOM;
                        return;
                    }
                    // Something new came in and we didn't scroll; give the user a clue that
                    // there's something new.
                    awakenScrollBars();
                }

                switch (mSyncMode) {
                case SYNC_SELECTED_POSITION:
                    if (isInTouchMode()) {
                        // We saved our state when not in touch mode. (We know this because
                        // mSyncMode is SYNC_SELECTED_POSITION.) Now we are trying to
                        // restore in touch mode. Just leave mSyncPosition as it is (possibly
                        // adjusting if the available range changed) and return.
                        mLayoutMode = LAYOUT_SYNC;
                        mSyncPosition = Math.min(Math.max(0, mSyncPosition), count - 1);

                        return;
                    } else {
                        // See if we can find a position in the new data with the same
                        // id as the old selection. This will change mSyncPosition.
                        newPos = findSyncPosition();
                        if (newPos >= 0) {
                            // Found it. Now verify that new selection is still selectable
                            selectablePos = lookForSelectablePosition(newPos, true);
                            if (selectablePos == newPos) {
                                // Same row id is selected
                                mSyncPosition = newPos;

                                if (mSyncHeight == getHeight()) {
                                    // If we are at the same height as when we saved state, try
                                    // to restore the scroll position too.
                                    mLayoutMode = LAYOUT_SYNC;
                                } else {
                                    // We are not the same height as when the selection was saved, so
                                    // don't try to restore the exact position
                                    mLayoutMode = LAYOUT_SET_SELECTION;
                                }

                                // Restore selection
                                setNextSelectedPositionInt(newPos);
                                return;
                            }
                        }
                    }
                    break;
                case SYNC_FIRST_POSITION:
                    // Leave mSyncPosition as it is -- just pin to available range
                    mLayoutMode = LAYOUT_SYNC;
                    mSyncPosition = Math.min(Math.max(0, mSyncPosition), count - 1);

                    return;
                }
            }

            if (!isInTouchMode()) {
                // We couldn't find matching data -- try to use the same position
                newPos = getSelectedItemPosition();

                // Pin position to the available range
                if (newPos >= count) {
                    newPos = count - 1;
                }
                if (newPos < 0) {
                    newPos = 0;
                }

                // Make sure we select something selectable -- first look down
                selectablePos = lookForSelectablePosition(newPos, true);

                if (selectablePos >= 0) {
                    setNextSelectedPositionInt(selectablePos);
                    return;
                } else {
                    // Looking down didn't work -- try looking up
                    selectablePos = lookForSelectablePosition(newPos, false);
                    if (selectablePos >= 0) {
                        setNextSelectedPositionInt(selectablePos);
                        return;
                    }
                }
            } else {

                // We already know where we want to resurrect the selection
                if (mResurrectToPosition >= 0) {
                    return;
                }
            }

        }

        // Nothing is selected. Give up and reset everything.
        mLayoutMode = mStackFromBottom ? LAYOUT_FORCE_BOTTOM : LAYOUT_FORCE_TOP;
        mSelectedPosition = INVALID_POSITION;
        mSelectedRowId = INVALID_ROW_ID;
        mNextSelectedPosition = INVALID_POSITION;
        mNextSelectedRowId = INVALID_ROW_ID;
        mNeedSync = false;
        mPendingSync = null;
        mSelectorPosition = INVALID_POSITION;
        checkSelectionChanged();
    }

    @Override
    protected void onDisplayHint(int hint) {
        super.onDisplayHint(hint);
        switch (hint) {
            case INVISIBLE:
                if (mPopup != null && mPopup.isShowing()) {
                    dismissPopup();
                }
                break;
            case VISIBLE:
                if (mFiltered && mPopup != null && !mPopup.isShowing()) {
                    showPopup();
                }
                break;
        }
        mPopupHidden = hint == INVISIBLE;
    }

    /**
     * Removes the filter window
     */
    private void dismissPopup() {
        if (mPopup != null) {
            mPopup.dismiss();
        }
    }

    /**
     * Shows the filter window
     */
    private void showPopup() {
        // Make sure we have a window before showing the popup
        if (getWindowVisibility() == View.VISIBLE) {
            createTextFilter(true);
            positionPopup();
            // Make sure we get focus if we are showing the popup
            checkFocus();
        }
    }

    private void positionPopup() {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        final int[] xy = new int[2];
        getLocationOnScreen(xy);
        // TODO: The 20 below should come from the theme
        // TODO: And the gravity should be defined in the theme as well
        final int bottomGap = screenHeight - xy[1] - getHeight() + (int) (mDensityScale * 20);
        if (!mPopup.isShowing()) {
            mPopup.showAtLocation(this, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                    xy[0], bottomGap);
        } else {
            mPopup.update(xy[0], bottomGap, -1, -1);
        }
    }

    /**
     * What is the distance between the source and destination rectangles given the direction of
     * focus navigation between them? The direction basically helps figure out more quickly what is
     * self evident by the relationship between the rects...
     *
     * @param source the source rectangle
     * @param dest the destination rectangle
     * @param direction the direction
     * @return the distance between the rectangles
     */
    static int getDistance(Rect source, Rect dest, int direction) {
        int sX, sY; // source x, y
        int dX, dY; // dest x, y
        switch (direction) {
        case View.FOCUS_RIGHT:
            sX = source.right;
            sY = source.top + source.height() / 2;
            dX = dest.left;
            dY = dest.top + dest.height() / 2;
            break;
        case View.FOCUS_DOWN:
            sX = source.left + source.width() / 2;
            sY = source.bottom;
            dX = dest.left + dest.width() / 2;
            dY = dest.top;
            break;
        case View.FOCUS_LEFT:
            sX = source.left;
            sY = source.top + source.height() / 2;
            dX = dest.right;
            dY = dest.top + dest.height() / 2;
            break;
        case View.FOCUS_UP:
            sX = source.left + source.width() / 2;
            sY = source.top;
            dX = dest.left + dest.width() / 2;
            dY = dest.bottom;
            break;
        case View.FOCUS_FORWARD:
        case View.FOCUS_BACKWARD:
            sX = source.right + source.width() / 2;
            sY = source.top + source.height() / 2;
            dX = dest.left + dest.width() / 2;
            dY = dest.top + dest.height() / 2;
            break;
        default:
            throw new IllegalArgumentException("direction must be one of "
                    + "{FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, FOCUS_RIGHT, "
                    + "FOCUS_FORWARD, FOCUS_BACKWARD}.");
        }
        int deltaX = dX - sX;
        int deltaY = dY - sY;
        return deltaY * deltaY + deltaX * deltaX;
    }

    @Override
    protected boolean isInFilterMode() {
        return mFiltered;
    }

    /**
     * Sends a key to the text filter window
     *
     * @param keyCode The keycode for the event
     * @param event The actual key event
     *
     * @return True if the text filter handled the event, false otherwise.
     */
    boolean sendToTextFilter(int keyCode, int count, KeyEvent event) {
        if (!acceptFilter()) {
            return false;
        }

        boolean handled = false;
        boolean okToSend = true;
        switch (keyCode) {
        case KeyEvent.KEYCODE_DPAD_UP:
        case KeyEvent.KEYCODE_DPAD_DOWN:
        case KeyEvent.KEYCODE_DPAD_LEFT:
        case KeyEvent.KEYCODE_DPAD_RIGHT:
        case KeyEvent.KEYCODE_DPAD_CENTER:
        case KeyEvent.KEYCODE_ENTER:
            okToSend = false;
            break;
        case KeyEvent.KEYCODE_BACK:
            if (mFiltered && mPopup != null && mPopup.isShowing()) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) {
                    KeyEvent.DispatcherState state = getKeyDispatcherState();
                    if (state != null) {
                        state.startTracking(event, this);
                    }
                    handled = true;
                } else if (event.getAction() == KeyEvent.ACTION_UP
                        && event.isTracking() && !event.isCanceled()) {
                    handled = true;
                    mTextFilter.setText("");
                }
            }
            okToSend = false;
            break;
        case KeyEvent.KEYCODE_SPACE:
            // Only send spaces once we are filtered
            okToSend = mFiltered;
            break;
        }

        if (okToSend) {
            createTextFilter(true);

            KeyEvent forwardEvent = event;
            if (forwardEvent.getRepeatCount() > 0) {
                forwardEvent = KeyEvent.changeTimeRepeat(event, event.getEventTime(), 0);
            }

            int action = event.getAction();
            switch (action) {
                case KeyEvent.ACTION_DOWN:
                    handled = mTextFilter.onKeyDown(keyCode, forwardEvent);
                    break;

                case KeyEvent.ACTION_UP:
                    handled = mTextFilter.onKeyUp(keyCode, forwardEvent);
                    break;

                case KeyEvent.ACTION_MULTIPLE:
                    handled = mTextFilter.onKeyMultiple(keyCode, count, event);
                    break;
            }
        }
        return handled;
    }

    /**
     * Return an InputConnection for editing of the filter text.
     */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (isTextFilterEnabled()) {
            if (mPublicInputConnection == null) {
                mDefInputConnection = new BaseInputConnection(this, false);
                mPublicInputConnection = new InputConnectionWrapper(outAttrs);
            }
            outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_FILTER;
            outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE;
            return mPublicInputConnection;
        }
        return null;
    }

    private class InputConnectionWrapper implements InputConnection {
        private final EditorInfo mOutAttrs;
        private InputConnection mTarget;

        public InputConnectionWrapper(EditorInfo outAttrs) {
            mOutAttrs = outAttrs;
        }

        private InputConnection getTarget() {
            if (mTarget == null) {
                mTarget = getTextFilterInput().onCreateInputConnection(mOutAttrs);
            }
            return mTarget;
        }

        @Override
        public boolean reportFullscreenMode(boolean enabled) {
            // Use our own input connection, since it is
            // the "real" one the IME is talking with.
            return mDefInputConnection.reportFullscreenMode(enabled);
        }

        @Override
        public boolean performEditorAction(int editorAction) {
            // The editor is off in its own window; we need to be
            // the one that does this.
            if (editorAction == EditorInfo.IME_ACTION_DONE) {
                InputMethodManager imm =
                        getContext().getSystemService(InputMethodManager.class);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(getWindowToken(), 0);
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            // Use our own input connection, since the filter
            // text view may not be shown in a window so has
            // no ViewAncestor to dispatch events with.
            return mDefInputConnection.sendKeyEvent(event);
        }

        @Override
        public CharSequence getTextBeforeCursor(int n, int flags) {
            if (mTarget == null) return "";
            return mTarget.getTextBeforeCursor(n, flags);
        }

        @Override
        public CharSequence getTextAfterCursor(int n, int flags) {
            if (mTarget == null) return "";
            return mTarget.getTextAfterCursor(n, flags);
        }

        @Override
        public CharSequence getSelectedText(int flags) {
            if (mTarget == null) return "";
            return mTarget.getSelectedText(flags);
        }

        @Override
        public int getCursorCapsMode(int reqModes) {
            if (mTarget == null) return InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
            return mTarget.getCursorCapsMode(reqModes);
        }

        @Override
        public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            return getTarget().getExtractedText(request, flags);
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            return getTarget().deleteSurroundingText(beforeLength, afterLength);
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
            return getTarget().deleteSurroundingTextInCodePoints(beforeLength, afterLength);
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            return getTarget().setComposingText(text, newCursorPosition);
        }

        @Override
        public boolean setComposingRegion(int start, int end) {
            return getTarget().setComposingRegion(start, end);
        }

        @Override
        public boolean finishComposingText() {
            return mTarget == null || mTarget.finishComposingText();
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            return getTarget().commitText(text, newCursorPosition);
        }

        @Override
        public boolean commitCompletion(CompletionInfo text) {
            return getTarget().commitCompletion(text);
        }

        @Override
        public boolean commitCorrection(CorrectionInfo correctionInfo) {
            return getTarget().commitCorrection(correctionInfo);
        }

        @Override
        public boolean setSelection(int start, int end) {
            return getTarget().setSelection(start, end);
        }

        @Override
        public boolean performContextMenuAction(int id) {
            return getTarget().performContextMenuAction(id);
        }

        @Override
        public boolean beginBatchEdit() {
            return getTarget().beginBatchEdit();
        }

        @Override
        public boolean endBatchEdit() {
            return getTarget().endBatchEdit();
        }

        @Override
        public boolean clearMetaKeyStates(int states) {
            return getTarget().clearMetaKeyStates(states);
        }

        @Override
        public boolean performPrivateCommand(String action, Bundle data) {
            return getTarget().performPrivateCommand(action, data);
        }

        @Override
        public boolean requestCursorUpdates(int cursorUpdateMode) {
            return getTarget().requestCursorUpdates(cursorUpdateMode);
        }

        @Override
        public Handler getHandler() {
            return getTarget().getHandler();
        }

        @Override
        public void closeConnection() {
            getTarget().closeConnection();
        }

        @Override
        public boolean commitContent(InputContentInfo inputContentInfo, int flags, Bundle opts) {
            return getTarget().commitContent(inputContentInfo, flags, opts);
        }
    }

    /**
     * For filtering we proxy an input connection to an internal text editor,
     * and this allows the proxying to happen.
     */
    @Override
    public boolean checkInputConnectionProxy(View view) {
        return view == mTextFilter;
    }

    /**
     * Creates the window for the text filter and populates it with an EditText field;
     *
     * @param animateEntrance true if the window should appear with an animation
     */
    private void createTextFilter(boolean animateEntrance) {
        if (mPopup == null) {
            PopupWindow p = new PopupWindow(getContext());
            p.setFocusable(false);
            p.setTouchable(false);
            p.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
            p.setContentView(getTextFilterInput());
            p.setWidth(LayoutParams.WRAP_CONTENT);
            p.setHeight(LayoutParams.WRAP_CONTENT);
            p.setBackgroundDrawable(null);
            mPopup = p;
            getViewTreeObserver().addOnGlobalLayoutListener(this);
            mGlobalLayoutListenerAddedFilter = true;
        }
        if (animateEntrance) {
            mPopup.setAnimationStyle(com.android.internal.R.style.Animation_TypingFilter);
        } else {
            mPopup.setAnimationStyle(com.android.internal.R.style.Animation_TypingFilterRestore);
        }
    }

    private EditText getTextFilterInput() {
        if (mTextFilter == null) {
            final LayoutInflater layoutInflater = LayoutInflater.from(getContext());
            mTextFilter = (EditText) layoutInflater.inflate(
                    com.android.internal.R.layout.typing_filter, null);
            // For some reason setting this as the "real" input type changes
            // the text view in some way that it doesn't work, and I don't
            // want to figure out why this is.
            mTextFilter.setRawInputType(EditorInfo.TYPE_CLASS_TEXT
                    | EditorInfo.TYPE_TEXT_VARIATION_FILTER);
            mTextFilter.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            mTextFilter.addTextChangedListener(this);
        }
        return mTextFilter;
    }

    /**
     * Clear the text filter.
     */
    public void clearTextFilter() {
        if (mFiltered) {
            getTextFilterInput().setText("");
            mFiltered = false;
            if (mPopup != null && mPopup.isShowing()) {
                dismissPopup();
            }
        }
    }

    /**
     * Returns if the ListView currently has a text filter.
     */
    public boolean hasTextFilter() {
        return mFiltered;
    }

    @Override
    public void onGlobalLayout() {
        if (isShown()) {
            // Show the popup if we are filtered
            if (mFiltered && mPopup != null && !mPopup.isShowing() && !mPopupHidden) {
                showPopup();
            }
        } else {
            // Hide the popup when we are no longer visible
            if (mPopup != null && mPopup.isShowing()) {
                dismissPopup();
            }
        }

    }

    /**
     * For our text watcher that is associated with the text filter.  Does
     * nothing.
     */
    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    /**
     * For our text watcher that is associated with the text filter. Performs
     * the actual filtering as the text changes, and takes care of hiding and
     * showing the popup displaying the currently entered filter text.
     */
    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        if (isTextFilterEnabled()) {
            createTextFilter(true);
            int length = s.length();
            boolean showing = mPopup.isShowing();
            if (!showing && length > 0) {
                // Show the filter popup if necessary
                showPopup();
                mFiltered = true;
            } else if (showing && length == 0) {
                // Remove the filter popup if the user has cleared all text
                dismissPopup();
                mFiltered = false;
            }
            if (mAdapter instanceof Filterable) {
                Filter f = ((Filterable) mAdapter).getFilter();
                // Filter should not be null when we reach this part
                if (f != null) {
                    f.filter(s, this);
                } else {
                    throw new IllegalStateException("You cannot call onTextChanged with a non "
                            + "filterable adapter");
                }
            }
        }
    }

    /**
     * For our text watcher that is associated with the text filter.  Does
     * nothing.
     */
    @Override
    public void afterTextChanged(Editable s) {
    }

    @Override
    public void onFilterComplete(int count) {
        if (mSelectedPosition < 0 && count > 0) {
            mResurrectToPosition = INVALID_POSITION;
            resurrectSelection();
        }
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new LayoutParams(p);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new AbsListView.LayoutParams(getContext(), attrs);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof AbsListView.LayoutParams;
    }

    /**
     * Puts the list or grid into transcript mode. In this mode the list or grid will always scroll
     * to the bottom to show new items.
     *
     * @param mode the transcript mode to set
     *
     * @see #TRANSCRIPT_MODE_DISABLED
     * @see #TRANSCRIPT_MODE_NORMAL
     * @see #TRANSCRIPT_MODE_ALWAYS_SCROLL
     */
    public void setTranscriptMode(int mode) {
        mTranscriptMode = mode;
    }

    /**
     * Returns the current transcript mode.
     *
     * @return {@link #TRANSCRIPT_MODE_DISABLED}, {@link #TRANSCRIPT_MODE_NORMAL} or
     *         {@link #TRANSCRIPT_MODE_ALWAYS_SCROLL}
     */
    @InspectableProperty(enumMapping = {
            @EnumEntry(value = TRANSCRIPT_MODE_DISABLED, name = "disabled"),
            @EnumEntry(value = TRANSCRIPT_MODE_NORMAL, name = "normal"),
            @EnumEntry(value = TRANSCRIPT_MODE_ALWAYS_SCROLL, name = "alwaysScroll")
    })
    public int getTranscriptMode() {
        return mTranscriptMode;
    }

    @Override
    public int getSolidColor() {
        return mCacheColorHint;
    }

    /**
     * When set to a non-zero value, the cache color hint indicates that this list is always drawn
     * on top of a solid, single-color, opaque background.
     *
     * Zero means that what's behind this object is translucent (non solid) or is not made of a
     * single color. This hint will not affect any existing background drawable set on this view (
     * typically set via {@link #setBackgroundDrawable(Drawable)}).
     *
     * @param color The background color
     */
    public void setCacheColorHint(@ColorInt int color) {
        if (color != mCacheColorHint) {
            mCacheColorHint = color;
            int count = getChildCount();
            for (int i = 0; i < count; i++) {
                getChildAt(i).setDrawingCacheBackgroundColor(color);
            }
            mRecycler.setCacheColorHint(color);
        }
    }

    /**
     * When set to a non-zero value, the cache color hint indicates that this list is always drawn
     * on top of a solid, single-color, opaque background
     *
     * @return The cache color hint
     */
    @ViewDebug.ExportedProperty(category = "drawing")
    @InspectableProperty
    @ColorInt
    public int getCacheColorHint() {
        return mCacheColorHint;
    }

    /**
     * Move all views (excluding headers and footers) held by this AbsListView into the supplied
     * List. This includes views displayed on the screen as well as views stored in AbsListView's
     * internal view recycler.
     *
     * @param views A list into which to put the reclaimed views
     */
    public void reclaimViews(List<View> views) {
        int childCount = getChildCount();
        RecyclerListener listener = mRecycler.mRecyclerListener;

        // Reclaim views on screen
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            AbsListView.LayoutParams lp = (AbsListView.LayoutParams) child.getLayoutParams();
            // Don't reclaim header or footer views, or views that should be ignored
            if (lp != null && mRecycler.shouldRecycleViewType(lp.viewType)) {
                views.add(child);
                child.setAccessibilityDelegate(null);
                if (listener != null) {
                    // Pretend they went through the scrap heap
                    listener.onMovedToScrapHeap(child);
                }
            }
        }
        mRecycler.reclaimScrapViews(views);
        removeAllViewsInLayout();
    }

    private void finishGlows() {
        if (shouldDisplayEdgeEffects()) {
            mEdgeGlowTop.finish();
            mEdgeGlowBottom.finish();
        }
    }

    /**
     * Sets up this AbsListView to use a remote views adapter which connects to a RemoteViewsService
     * through the specified intent.
     * @param intent the intent used to identify the RemoteViewsService for the adapter to connect to.
     */
    public void setRemoteViewsAdapter(Intent intent) {
        setRemoteViewsAdapter(intent, false);
    }

    /** @hide **/
    public Runnable setRemoteViewsAdapterAsync(final Intent intent) {
        return new RemoteViewsAdapter.AsyncRemoteAdapterAction(this, intent);
    }

    /** @hide **/
    @Override
    public void setRemoteViewsAdapter(Intent intent, boolean isAsync) {
        // Ensure that we don't already have a RemoteViewsAdapter that is bound to an existing
        // service handling the specified intent.
        if (mRemoteAdapter != null) {
            Intent.FilterComparison fcNew = new Intent.FilterComparison(intent);
            Intent.FilterComparison fcOld = new Intent.FilterComparison(
                    mRemoteAdapter.getRemoteViewsServiceIntent());
            if (fcNew.equals(fcOld)) {
                return;
            }
        }
        mDeferNotifyDataSetChanged = false;
        // Otherwise, create a new RemoteViewsAdapter for binding
        mRemoteAdapter = new RemoteViewsAdapter(getContext(), intent, this, isAsync);
        if (mRemoteAdapter.isDataReady()) {
            setAdapter(mRemoteAdapter);
        }
    }

    /**
     * Sets up the onClickHandler to be used by the RemoteViewsAdapter when inflating RemoteViews
     *
     * @param handler The OnClickHandler to use when inflating RemoteViews.
     *
     * @hide
     */
    public void setRemoteViewsOnClickHandler(OnClickHandler handler) {
        // Ensure that we don't already have a RemoteViewsAdapter that is bound to an existing
        // service handling the specified intent.
        if (mRemoteAdapter != null) {
            mRemoteAdapter.setRemoteViewsOnClickHandler(handler);
        }
    }

    /**
     * This defers a notifyDataSetChanged on the pending RemoteViewsAdapter if it has not
     * connected yet.
     */
    @Override
    public void deferNotifyDataSetChanged() {
        mDeferNotifyDataSetChanged = true;
    }

    /**
     * Called back when the adapter connects to the RemoteViewsService.
     */
    @Override
    public boolean onRemoteAdapterConnected() {
        if (mRemoteAdapter != mAdapter) {
            setAdapter(mRemoteAdapter);
            if (mDeferNotifyDataSetChanged) {
                mRemoteAdapter.notifyDataSetChanged();
                mDeferNotifyDataSetChanged = false;
            }
            return false;
        } else if (mRemoteAdapter != null) {
            mRemoteAdapter.superNotifyDataSetChanged();
            return true;
        }
        return false;
    }

    /**
     * Called back when the adapter disconnects from the RemoteViewsService.
     */
    @Override
    public void onRemoteAdapterDisconnected() {
        // If the remote adapter disconnects, we keep it around
        // since the currently displayed items are still cached.
        // Further, we want the service to eventually reconnect
        // when necessary, as triggered by this view requesting
        // items from the Adapter.
    }

    /**
     * Hints the RemoteViewsAdapter, if it exists, about which views are currently
     * being displayed by the AbsListView.
     */
    void setVisibleRangeHint(int start, int end) {
        if (mRemoteAdapter != null) {
            mRemoteAdapter.setVisibleRangeHint(start, end);
        }
    }

    /**
     * Sets the edge effect color for both top and bottom edge effects.
     *
     * @param color The color for the edge effects.
     * @see #setTopEdgeEffectColor(int)
     * @see #setBottomEdgeEffectColor(int)
     * @see #getTopEdgeEffectColor()
     * @see #getBottomEdgeEffectColor()
     */
    public void setEdgeEffectColor(@ColorInt int color) {
        setTopEdgeEffectColor(color);
        setBottomEdgeEffectColor(color);
    }

    /**
     * Sets the bottom edge effect color.
     *
     * @param color The color for the bottom edge effect.
     * @see #setTopEdgeEffectColor(int)
     * @see #setEdgeEffectColor(int)
     * @see #getTopEdgeEffectColor()
     * @see #getBottomEdgeEffectColor()
     */
    public void setBottomEdgeEffectColor(@ColorInt int color) {
        mEdgeGlowBottom.setColor(color);
        invalidateBottomGlow();
    }

    /**
     * Sets the top edge effect color.
     *
     * @param color The color for the top edge effect.
     * @see #setBottomEdgeEffectColor(int)
     * @see #setEdgeEffectColor(int)
     * @see #getTopEdgeEffectColor()
     * @see #getBottomEdgeEffectColor()
     */
    public void setTopEdgeEffectColor(@ColorInt int color) {
        mEdgeGlowTop.setColor(color);
        invalidateTopGlow();
    }

    /**
     * Returns the top edge effect color.
     *
     * @return The top edge effect color.
     * @see #setEdgeEffectColor(int)
     * @see #setTopEdgeEffectColor(int)
     * @see #setBottomEdgeEffectColor(int)
     * @see #getBottomEdgeEffectColor()
     */
    @ColorInt
    public int getTopEdgeEffectColor() {
        return mEdgeGlowTop.getColor();
    }

    /**
     * Returns the bottom edge effect color.
     *
     * @return The bottom edge effect color.
     * @see #setEdgeEffectColor(int)
     * @see #setTopEdgeEffectColor(int)
     * @see #setBottomEdgeEffectColor(int)
     * @see #getTopEdgeEffectColor()
     */
    @ColorInt
    public int getBottomEdgeEffectColor() {
        return mEdgeGlowBottom.getColor();
    }

    /**
     * Sets the recycler listener to be notified whenever a View is set aside in
     * the recycler for later reuse. This listener can be used to free resources
     * associated to the View.
     *
     * @param listener The recycler listener to be notified of views set aside
     *        in the recycler.
     *
     * @see android.widget.AbsListView.RecyclerListener
     */
    public void setRecyclerListener(RecyclerListener listener) {
        mRecycler.mRecyclerListener = listener;
    }

    class AdapterDataSetObserver extends AdapterView<ListAdapter>.AdapterDataSetObserver {
        @Override
        public void onChanged() {
            super.onChanged();
            if (mFastScroll != null) {
                mFastScroll.onSectionsChanged();
            }
        }

        @Override
        public void onInvalidated() {
            super.onInvalidated();
            if (mFastScroll != null) {
                mFastScroll.onSectionsChanged();
            }
        }
    }

    /**
     * A MultiChoiceModeListener receives events for {@link AbsListView#CHOICE_MODE_MULTIPLE_MODAL}.
     * It acts as the {@link ActionMode.Callback} for the selection mode and also receives
     * {@link #onItemCheckedStateChanged(ActionMode, int, long, boolean)} events when the user
     * selects and deselects list items.
     */
    public interface MultiChoiceModeListener extends ActionMode.Callback {
        /**
         * Called when an item is checked or unchecked during selection mode.
         *
         * @param mode The {@link ActionMode} providing the selection mode
         * @param position Adapter position of the item that was checked or unchecked
         * @param id Adapter ID of the item that was checked or unchecked
         * @param checked <code>true</code> if the item is now checked, <code>false</code>
         *                if the item is now unchecked.
         */
        public void onItemCheckedStateChanged(ActionMode mode,
                int position, long id, boolean checked);
    }

    class MultiChoiceModeWrapper implements MultiChoiceModeListener {
        private MultiChoiceModeListener mWrapped;

        public void setWrapped(MultiChoiceModeListener wrapped) {
            mWrapped = wrapped;
        }

        public boolean hasWrappedCallback() {
            return mWrapped != null;
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            if (mWrapped.onCreateActionMode(mode, menu)) {
                // Initialize checked graphic state?
                setLongClickable(false);
                return true;
            }
            return false;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return mWrapped.onPrepareActionMode(mode, menu);
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return mWrapped.onActionItemClicked(mode, item);
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            mWrapped.onDestroyActionMode(mode);
            mChoiceActionMode = null;

            // Ending selection mode means deselecting everything.
            clearChoices();

            mDataChanged = true;
            rememberSyncState();
            requestLayout();

            setLongClickable(true);
        }

        @Override
        public void onItemCheckedStateChanged(ActionMode mode,
                int position, long id, boolean checked) {
            mWrapped.onItemCheckedStateChanged(mode, position, id, checked);

            // If there are no items selected we no longer need the selection mode.
            if (getCheckedItemCount() == 0) {
                mode.finish();
            }
        }
    }

    /**
     * AbsListView extends LayoutParams to provide a place to hold the view type.
     */
    public static class LayoutParams extends ViewGroup.LayoutParams {
        /**
         * View type for this view, as returned by
         * {@link android.widget.Adapter#getItemViewType(int) }
         */
        @ViewDebug.ExportedProperty(category = "list", mapping = {
            @ViewDebug.IntToString(from = ITEM_VIEW_TYPE_IGNORE, to = "ITEM_VIEW_TYPE_IGNORE"),
            @ViewDebug.IntToString(from = ITEM_VIEW_TYPE_HEADER_OR_FOOTER, to = "ITEM_VIEW_TYPE_HEADER_OR_FOOTER")
        })
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        int viewType;

        /**
         * When this boolean is set, the view has been added to the AbsListView
         * at least once. It is used to know whether headers/footers have already
         * been added to the list view and whether they should be treated as
         * recycled views or not.
         */
        @ViewDebug.ExportedProperty(category = "list")
        boolean recycledHeaderFooter;

        /**
         * When an AbsListView is measured with an AT_MOST measure spec, it needs
         * to obtain children views to measure itself. When doing so, the children
         * are not attached to the window, but put in the recycler which assumes
         * they've been attached before. Setting this flag will force the reused
         * view to be attached to the window rather than just attached to the
         * parent.
         */
        @ViewDebug.ExportedProperty(category = "list")
        boolean forceAdd;

        /**
         * The position the view was removed from when pulled out of the
         * scrap heap.
         * @hide
         */
        @UnsupportedAppUsage
        int scrappedFromPosition;

        /**
         * The ID the view represents
         */
        long itemId = -1;

        /** Whether the adapter considers the item enabled. */
        boolean isEnabled;

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

        public LayoutParams(int w, int h) {
            super(w, h);
        }

        public LayoutParams(int w, int h, int viewType) {
            super(w, h);
            this.viewType = viewType;
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }

        /** @hide */
        @Override
        protected void encodeProperties(@NonNull ViewHierarchyEncoder encoder) {
            super.encodeProperties(encoder);

            encoder.addProperty("list:viewType", viewType);
            encoder.addProperty("list:recycledHeaderFooter", recycledHeaderFooter);
            encoder.addProperty("list:forceAdd", forceAdd);
            encoder.addProperty("list:isEnabled", isEnabled);
        }
    }

    /**
     * A RecyclerListener is used to receive a notification whenever a View is placed
     * inside the RecycleBin's scrap heap. This listener is used to free resources
     * associated to Views placed in the RecycleBin.
     *
     * @see android.widget.AbsListView#setRecyclerListener(android.widget.AbsListView.RecyclerListener)
     */
    public static interface RecyclerListener {
        /**
         * Indicates that the specified View was moved into the recycler's scrap heap.
         * The view is not displayed on screen any more and any expensive resource
         * associated with the view should be discarded.
         *
         * @param view
         */
        void onMovedToScrapHeap(View view);
    }

    /**
     * The RecycleBin facilitates reuse of views across layouts. The RecycleBin has two levels of
     * storage: ActiveViews and ScrapViews. ActiveViews are those views which were onscreen at the
     * start of a layout. By construction, they are displaying current information. At the end of
     * layout, all views in ActiveViews are demoted to ScrapViews. ScrapViews are old views that
     * could potentially be used by the adapter to avoid allocating views unnecessarily.
     *
     * @see android.widget.AbsListView#setRecyclerListener(android.widget.AbsListView.RecyclerListener)
     * @see android.widget.AbsListView.RecyclerListener
     */
    class RecycleBin {
        @UnsupportedAppUsage
        private RecyclerListener mRecyclerListener;

        /**
         * The position of the first view stored in mActiveViews.
         */
        private int mFirstActivePosition;

        /**
         * Views that were on screen at the start of layout. This array is populated at the start of
         * layout, and at the end of layout all view in mActiveViews are moved to mScrapViews.
         * Views in mActiveViews represent a contiguous range of Views, with position of the first
         * view store in mFirstActivePosition.
         */
        private View[] mActiveViews = new View[0];

        /**
         * Unsorted views that can be used by the adapter as a convert view.
         */
        private ArrayList<View>[] mScrapViews;

        private int mViewTypeCount;

        private ArrayList<View> mCurrentScrap;

        private ArrayList<View> mSkippedScrap;

        private SparseArray<View> mTransientStateViews;
        private LongSparseArray<View> mTransientStateViewsById;

        public void setViewTypeCount(int viewTypeCount) {
            if (viewTypeCount < 1) {
                throw new IllegalArgumentException("Can't have a viewTypeCount < 1");
            }
            //noinspection unchecked
            ArrayList<View>[] scrapViews = new ArrayList[viewTypeCount];
            for (int i = 0; i < viewTypeCount; i++) {
                scrapViews[i] = new ArrayList<View>();
            }
            mViewTypeCount = viewTypeCount;
            mCurrentScrap = scrapViews[0];
            mScrapViews = scrapViews;
        }

        public void markChildrenDirty() {
            if (mViewTypeCount == 1) {
                final ArrayList<View> scrap = mCurrentScrap;
                final int scrapCount = scrap.size();
                for (int i = 0; i < scrapCount; i++) {
                    scrap.get(i).forceLayout();
                }
            } else {
                final int typeCount = mViewTypeCount;
                for (int i = 0; i < typeCount; i++) {
                    final ArrayList<View> scrap = mScrapViews[i];
                    final int scrapCount = scrap.size();
                    for (int j = 0; j < scrapCount; j++) {
                        scrap.get(j).forceLayout();
                    }
                }
            }
            if (mTransientStateViews != null) {
                final int count = mTransientStateViews.size();
                for (int i = 0; i < count; i++) {
                    mTransientStateViews.valueAt(i).forceLayout();
                }
            }
            if (mTransientStateViewsById != null) {
                final int count = mTransientStateViewsById.size();
                for (int i = 0; i < count; i++) {
                    mTransientStateViewsById.valueAt(i).forceLayout();
                }
            }
        }

        public boolean shouldRecycleViewType(int viewType) {
            return viewType >= 0;
        }

        /**
         * Clears the scrap heap.
         */
        @UnsupportedAppUsage
        void clear() {
            if (mViewTypeCount == 1) {
                final ArrayList<View> scrap = mCurrentScrap;
                clearScrap(scrap);
            } else {
                final int typeCount = mViewTypeCount;
                for (int i = 0; i < typeCount; i++) {
                    final ArrayList<View> scrap = mScrapViews[i];
                    clearScrap(scrap);
                }
            }

            clearTransientStateViews();
        }

        /**
         * Fill ActiveViews with all of the children of the AbsListView.
         *
         * @param childCount The minimum number of views mActiveViews should hold
         * @param firstActivePosition The position of the first view that will be stored in
         *        mActiveViews
         */
        void fillActiveViews(int childCount, int firstActivePosition) {
            if (mActiveViews.length < childCount) {
                mActiveViews = new View[childCount];
            }
            mFirstActivePosition = firstActivePosition;

            //noinspection MismatchedReadAndWriteOfArray
            final View[] activeViews = mActiveViews;
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                AbsListView.LayoutParams lp = (AbsListView.LayoutParams) child.getLayoutParams();
                // Don't put header or footer views into the scrap heap
                if (lp != null && lp.viewType != ITEM_VIEW_TYPE_HEADER_OR_FOOTER) {
                    // Note:  We do place AdapterView.ITEM_VIEW_TYPE_IGNORE in active views.
                    //        However, we will NOT place them into scrap views.
                    activeViews[i] = child;
                    // Remember the position so that setupChild() doesn't reset state.
                    lp.scrappedFromPosition = firstActivePosition + i;
                }
            }
        }

        /**
         * Get the view corresponding to the specified position. The view will be removed from
         * mActiveViews if it is found.
         *
         * @param position The position to look up in mActiveViews
         * @return The view if it is found, null otherwise
         */
        View getActiveView(int position) {
            int index = position - mFirstActivePosition;
            final View[] activeViews = mActiveViews;
            if (index >=0 && index < activeViews.length) {
                final View match = activeViews[index];
                activeViews[index] = null;
                return match;
            }
            return null;
        }

        View getTransientStateView(int position) {
            if (mAdapter != null && mAdapterHasStableIds && mTransientStateViewsById != null) {
                long id = mAdapter.getItemId(position);
                View result = mTransientStateViewsById.get(id);
                mTransientStateViewsById.remove(id);
                return result;
            }
            if (mTransientStateViews != null) {
                final int index = mTransientStateViews.indexOfKey(position);
                if (index >= 0) {
                    View result = mTransientStateViews.valueAt(index);
                    mTransientStateViews.removeAt(index);
                    return result;
                }
            }
            return null;
        }

        /**
         * Dumps and fully detaches any currently saved views with transient
         * state.
         */
        void clearTransientStateViews() {
            final SparseArray<View> viewsByPos = mTransientStateViews;
            if (viewsByPos != null) {
                final int N = viewsByPos.size();
                for (int i = 0; i < N; i++) {
                    removeDetachedView(viewsByPos.valueAt(i), false);
                }
                viewsByPos.clear();
            }

            final LongSparseArray<View> viewsById = mTransientStateViewsById;
            if (viewsById != null) {
                final int N = viewsById.size();
                for (int i = 0; i < N; i++) {
                    removeDetachedView(viewsById.valueAt(i), false);
                }
                viewsById.clear();
            }
        }

        /**
         * @return A view from the ScrapViews collection. These are unordered.
         */
        View getScrapView(int position) {
            final int whichScrap = mAdapter.getItemViewType(position);
            if (whichScrap < 0) {
                return null;
            }
            if (mViewTypeCount == 1) {
                return retrieveFromScrap(mCurrentScrap, position);
            } else if (whichScrap < mScrapViews.length) {
                return retrieveFromScrap(mScrapViews[whichScrap], position);
            }
            return null;
        }

        /**
         * Puts a view into the list of scrap views.
         * <p>
         * If the list data hasn't changed or the adapter has stable IDs, views
         * with transient state will be preserved for later retrieval.
         *
         * @param scrap The view to add
         * @param position The view's position within its parent
         */
        void addScrapView(View scrap, int position) {
            final AbsListView.LayoutParams lp = (AbsListView.LayoutParams) scrap.getLayoutParams();
            if (lp == null) {
                // Can't recycle, but we don't know anything about the view.
                // Ignore it completely.
                return;
            }

            lp.scrappedFromPosition = position;

            // Remove but don't scrap header or footer views, or views that
            // should otherwise not be recycled.
            final int viewType = lp.viewType;
            if (!shouldRecycleViewType(viewType)) {
                // Can't recycle. If it's not a header or footer, which have
                // special handling and should be ignored, then skip the scrap
                // heap and we'll fully detach the view later.
                if (viewType != ITEM_VIEW_TYPE_HEADER_OR_FOOTER) {
                    getSkippedScrap().add(scrap);
                }
                return;
            }

            scrap.dispatchStartTemporaryDetach();

            // The the accessibility state of the view may change while temporary
            // detached and we do not allow detached views to fire accessibility
            // events. So we are announcing that the subtree changed giving a chance
            // to clients holding on to a view in this subtree to refresh it.
            notifyViewAccessibilityStateChangedIfNeeded(
                    AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);

            // Don't scrap views that have transient state.
            final boolean scrapHasTransientState = scrap.hasTransientState();
            if (scrapHasTransientState) {
                if (mAdapter != null && mAdapterHasStableIds) {
                    // If the adapter has stable IDs, we can reuse the view for
                    // the same data.
                    if (mTransientStateViewsById == null) {
                        mTransientStateViewsById = new LongSparseArray<>();
                    }
                    mTransientStateViewsById.put(lp.itemId, scrap);
                } else if (!mDataChanged) {
                    // If the data hasn't changed, we can reuse the views at
                    // their old positions.
                    if (mTransientStateViews == null) {
                        mTransientStateViews = new SparseArray<>();
                    }
                    mTransientStateViews.put(position, scrap);
                } else {
                    // Otherwise, we'll have to remove the view and start over.
                    clearScrapForRebind(scrap);
                    getSkippedScrap().add(scrap);
                }
            } else {
                clearScrapForRebind(scrap);
                if (mViewTypeCount == 1) {
                    mCurrentScrap.add(scrap);
                } else {
                    mScrapViews[viewType].add(scrap);
                }

                if (mRecyclerListener != null) {
                    mRecyclerListener.onMovedToScrapHeap(scrap);
                }
            }
        }

        private ArrayList<View> getSkippedScrap() {
            if (mSkippedScrap == null) {
                mSkippedScrap = new ArrayList<>();
            }
            return mSkippedScrap;
        }

        /**
         * Finish the removal of any views that skipped the scrap heap.
         */
        void removeSkippedScrap() {
            if (mSkippedScrap == null) {
                return;
            }
            final int count = mSkippedScrap.size();
            for (int i = 0; i < count; i++) {
                removeDetachedView(mSkippedScrap.get(i), false);
            }
            mSkippedScrap.clear();
        }

        /**
         * Move all views remaining in mActiveViews to mScrapViews.
         */
        void scrapActiveViews() {
            final View[] activeViews = mActiveViews;
            final boolean hasListener = mRecyclerListener != null;
            final boolean multipleScraps = mViewTypeCount > 1;

            ArrayList<View> scrapViews = mCurrentScrap;
            final int count = activeViews.length;
            for (int i = count - 1; i >= 0; i--) {
                final View victim = activeViews[i];
                if (victim != null) {
                    final AbsListView.LayoutParams lp
                            = (AbsListView.LayoutParams) victim.getLayoutParams();
                    final int whichScrap = lp.viewType;

                    activeViews[i] = null;

                    if (victim.hasTransientState()) {
                        // Store views with transient state for later use.
                        victim.dispatchStartTemporaryDetach();

                        if (mAdapter != null && mAdapterHasStableIds) {
                            if (mTransientStateViewsById == null) {
                                mTransientStateViewsById = new LongSparseArray<View>();
                            }
                            long id = mAdapter.getItemId(mFirstActivePosition + i);
                            mTransientStateViewsById.put(id, victim);
                        } else if (!mDataChanged) {
                            if (mTransientStateViews == null) {
                                mTransientStateViews = new SparseArray<View>();
                            }
                            mTransientStateViews.put(mFirstActivePosition + i, victim);
                        } else if (whichScrap != ITEM_VIEW_TYPE_HEADER_OR_FOOTER) {
                            // The data has changed, we can't keep this view.
                            removeDetachedView(victim, false);
                        }
                    } else if (!shouldRecycleViewType(whichScrap)) {
                        // Discard non-recyclable views except headers/footers.
                        if (whichScrap != ITEM_VIEW_TYPE_HEADER_OR_FOOTER) {
                            removeDetachedView(victim, false);
                        }
                    } else {
                        // Store everything else on the appropriate scrap heap.
                        if (multipleScraps) {
                            scrapViews = mScrapViews[whichScrap];
                        }

                        lp.scrappedFromPosition = mFirstActivePosition + i;
                        removeDetachedView(victim, false);
                        scrapViews.add(victim);

                        if (hasListener) {
                            mRecyclerListener.onMovedToScrapHeap(victim);
                        }
                    }
                }
            }
            pruneScrapViews();
        }

        /**
         * At the end of a layout pass, all temp detached views should either be re-attached or
         * completely detached. This method ensures that any remaining view in the scrap list is
         * fully detached.
         */
        void fullyDetachScrapViews() {
            final int viewTypeCount = mViewTypeCount;
            final ArrayList<View>[] scrapViews = mScrapViews;
            for (int i = 0; i < viewTypeCount; ++i) {
                final ArrayList<View> scrapPile = scrapViews[i];
                for (int j = scrapPile.size() - 1; j >= 0; j--) {
                    final View view = scrapPile.get(j);
                    if (view.isTemporarilyDetached()) {
                        removeDetachedView(view, false);
                    }
                }
            }
        }

        /**
         * Makes sure that the size of mScrapViews does not exceed the size of
         * mActiveViews, which can happen if an adapter does not recycle its
         * views. Removes cached transient state views that no longer have
         * transient state.
         */
        private void pruneScrapViews() {
            final int maxViews = mActiveViews.length;
            final int viewTypeCount = mViewTypeCount;
            final ArrayList<View>[] scrapViews = mScrapViews;
            for (int i = 0; i < viewTypeCount; ++i) {
                final ArrayList<View> scrapPile = scrapViews[i];
                int size = scrapPile.size();
                while (size > maxViews) {
                    scrapPile.remove(--size);
                }
            }

            final SparseArray<View> transViewsByPos = mTransientStateViews;
            if (transViewsByPos != null) {
                for (int i = 0; i < transViewsByPos.size(); i++) {
                    final View v = transViewsByPos.valueAt(i);
                    if (!v.hasTransientState()) {
                        removeDetachedView(v, false);
                        transViewsByPos.removeAt(i);
                        i--;
                    }
                }
            }

            final LongSparseArray<View> transViewsById = mTransientStateViewsById;
            if (transViewsById != null) {
                for (int i = 0; i < transViewsById.size(); i++) {
                    final View v = transViewsById.valueAt(i);
                    if (!v.hasTransientState()) {
                        removeDetachedView(v, false);
                        transViewsById.removeAt(i);
                        i--;
                    }
                }
            }
        }

        /**
         * Puts all views in the scrap heap into the supplied list.
         */
        void reclaimScrapViews(List<View> views) {
            if (mViewTypeCount == 1) {
                views.addAll(mCurrentScrap);
            } else {
                final int viewTypeCount = mViewTypeCount;
                final ArrayList<View>[] scrapViews = mScrapViews;
                for (int i = 0; i < viewTypeCount; ++i) {
                    final ArrayList<View> scrapPile = scrapViews[i];
                    views.addAll(scrapPile);
                }
            }
        }

        /**
         * Updates the cache color hint of all known views.
         *
         * @param color The new cache color hint.
         */
        void setCacheColorHint(int color) {
            if (mViewTypeCount == 1) {
                final ArrayList<View> scrap = mCurrentScrap;
                final int scrapCount = scrap.size();
                for (int i = 0; i < scrapCount; i++) {
                    scrap.get(i).setDrawingCacheBackgroundColor(color);
                }
            } else {
                final int typeCount = mViewTypeCount;
                for (int i = 0; i < typeCount; i++) {
                    final ArrayList<View> scrap = mScrapViews[i];
                    final int scrapCount = scrap.size();
                    for (int j = 0; j < scrapCount; j++) {
                        scrap.get(j).setDrawingCacheBackgroundColor(color);
                    }
                }
            }
            // Just in case this is called during a layout pass
            final View[] activeViews = mActiveViews;
            final int count = activeViews.length;
            for (int i = 0; i < count; ++i) {
                final View victim = activeViews[i];
                if (victim != null) {
                    victim.setDrawingCacheBackgroundColor(color);
                }
            }
        }

        private View retrieveFromScrap(ArrayList<View> scrapViews, int position) {
            final int size = scrapViews.size();
            if (size > 0) {
                // See if we still have a view for this position or ID.
                // Traverse backwards to find the most recently used scrap view
                for (int i = size - 1; i >= 0; i--) {
                    final View view = scrapViews.get(i);
                    final AbsListView.LayoutParams params =
                            (AbsListView.LayoutParams) view.getLayoutParams();

                    if (mAdapterHasStableIds) {
                        final long id = mAdapter.getItemId(position);
                        if (id == params.itemId) {
                            return scrapViews.remove(i);
                        }
                    } else if (params.scrappedFromPosition == position) {
                        final View scrap = scrapViews.remove(i);
                        clearScrapForRebind(scrap);
                        return scrap;
                    }
                }
                final View scrap = scrapViews.remove(size - 1);
                clearScrapForRebind(scrap);
                return scrap;
            } else {
                return null;
            }
        }

        private void clearScrap(final ArrayList<View> scrap) {
            final int scrapCount = scrap.size();
            for (int j = 0; j < scrapCount; j++) {
                removeDetachedView(scrap.remove(scrapCount - 1 - j), false);
            }
        }

        private void clearScrapForRebind(View view) {
            view.clearAccessibilityFocus();
            view.setAccessibilityDelegate(null);
        }

        private void removeDetachedView(View child, boolean animate) {
            child.setAccessibilityDelegate(null);
            AbsListView.this.removeDetachedView(child, animate);
        }
    }

    /**
     * Returns the height of the view for the specified position.
     *
     * @param position the item position
     * @return view height in pixels
     */
    int getHeightForPosition(int position) {
        final int firstVisiblePosition = getFirstVisiblePosition();
        final int childCount = getChildCount();
        final int index = position - firstVisiblePosition;
        if (index >= 0 && index < childCount) {
            // Position is on-screen, use existing view.
            final View view = getChildAt(index);
            return view.getHeight();
        } else {
            // Position is off-screen, obtain & recycle view.
            final View view = obtainView(position, mIsScrap);
            view.measure(mWidthMeasureSpec, MeasureSpec.UNSPECIFIED);
            final int height = view.getMeasuredHeight();
            mRecycler.addScrapView(view, position);
            return height;
        }
    }

    /**
     * Sets the selected item and positions the selection y pixels from the top edge
     * of the ListView. (If in touch mode, the item will not be selected but it will
     * still be positioned appropriately.)
     *
     * @param position Index (starting at 0) of the data item to be selected.
     * @param y The distance from the top edge of the ListView (plus padding) that the
     *        item will be positioned.
     */
    public void setSelectionFromTop(int position, int y) {
        if (mAdapter == null) {
            return;
        }

        if (!isInTouchMode()) {
            position = lookForSelectablePosition(position, true);
            if (position >= 0) {
                setNextSelectedPositionInt(position);
            }
        } else {
            mResurrectToPosition = position;
        }

        if (position >= 0) {
            mLayoutMode = LAYOUT_SPECIFIC;
            mSpecificTop = mListPadding.top + y;

            if (mNeedSync) {
                mSyncPosition = position;
                mSyncRowId = mAdapter.getItemId(position);
            }

            if (mPositionScroller != null) {
                mPositionScroller.stop();
            }
            requestLayout();
        }
    }

    /** @hide */
    @Override
    protected void encodeProperties(@NonNull ViewHierarchyEncoder encoder) {
        super.encodeProperties(encoder);

        encoder.addProperty("drawing:cacheColorHint", getCacheColorHint());
        encoder.addProperty("list:fastScrollEnabled", isFastScrollEnabled());
        encoder.addProperty("list:scrollingCacheEnabled", isScrollingCacheEnabled());
        encoder.addProperty("list:smoothScrollbarEnabled", isSmoothScrollbarEnabled());
        encoder.addProperty("list:stackFromBottom", isStackFromBottom());
        encoder.addProperty("list:textFilterEnabled", isTextFilterEnabled());

        View selectedView = getSelectedView();
        if (selectedView != null) {
            encoder.addPropertyKey("selectedView");
            selectedView.encode(encoder);
        }
    }

    /**
     * Abstract positon scroller used to handle smooth scrolling.
     */
    static abstract class AbsPositionScroller {
        public abstract void start(int position);
        public abstract void start(int position, int boundPosition);
        public abstract void startWithOffset(int position, int offset);
        public abstract void startWithOffset(int position, int offset, int duration);
        public abstract void stop();
    }

    /**
     * Default position scroller that simulates a fling.
     */
    class PositionScroller extends AbsPositionScroller implements Runnable {
        private static final int SCROLL_DURATION = 200;

        private static final int MOVE_DOWN_POS = 1;
        private static final int MOVE_UP_POS = 2;
        private static final int MOVE_DOWN_BOUND = 3;
        private static final int MOVE_UP_BOUND = 4;
        private static final int MOVE_OFFSET = 5;

        private int mMode;
        private int mTargetPos;
        private int mBoundPos;
        private int mLastSeenPos;
        private int mScrollDuration;
        private final int mExtraScroll;

        private int mOffsetFromTop;

        PositionScroller() {
            mExtraScroll = ViewConfiguration.get(mContext).getScaledFadingEdgeLength();
        }

        @Override
        public void start(final int position) {
            stop();

            if (mDataChanged) {
                // Wait until we're back in a stable state to try this.
                mPositionScrollAfterLayout = new Runnable() {
                    @Override public void run() {
                        start(position);
                    }
                };
                return;
            }

            final int childCount = getChildCount();
            if (childCount == 0) {
                // Can't scroll without children.
                return;
            }

            final int firstPos = mFirstPosition;
            final int lastPos = firstPos + childCount - 1;

            int viewTravelCount;
            int clampedPosition = Math.max(0, Math.min(getCount() - 1, position));
            if (clampedPosition < firstPos) {
                viewTravelCount = firstPos - clampedPosition + 1;
                mMode = MOVE_UP_POS;
            } else if (clampedPosition > lastPos) {
                viewTravelCount = clampedPosition - lastPos + 1;
                mMode = MOVE_DOWN_POS;
            } else {
                scrollToVisible(clampedPosition, INVALID_POSITION, SCROLL_DURATION);
                return;
            }

            if (viewTravelCount > 0) {
                mScrollDuration = SCROLL_DURATION / viewTravelCount;
            } else {
                mScrollDuration = SCROLL_DURATION;
            }
            mTargetPos = clampedPosition;
            mBoundPos = INVALID_POSITION;
            mLastSeenPos = INVALID_POSITION;

            postOnAnimation(this);
        }

        @Override
        public void start(final int position, final int boundPosition) {
            stop();

            if (boundPosition == INVALID_POSITION) {
                start(position);
                return;
            }

            if (mDataChanged) {
                // Wait until we're back in a stable state to try this.
                mPositionScrollAfterLayout = new Runnable() {
                    @Override public void run() {
                        start(position, boundPosition);
                    }
                };
                return;
            }

            final int childCount = getChildCount();
            if (childCount == 0) {
                // Can't scroll without children.
                return;
            }

            final int firstPos = mFirstPosition;
            final int lastPos = firstPos + childCount - 1;

            int viewTravelCount;
            int clampedPosition = Math.max(0, Math.min(getCount() - 1, position));
            if (clampedPosition < firstPos) {
                final int boundPosFromLast = lastPos - boundPosition;
                if (boundPosFromLast < 1) {
                    // Moving would shift our bound position off the screen. Abort.
                    return;
                }

                final int posTravel = firstPos - clampedPosition + 1;
                final int boundTravel = boundPosFromLast - 1;
                if (boundTravel < posTravel) {
                    viewTravelCount = boundTravel;
                    mMode = MOVE_UP_BOUND;
                } else {
                    viewTravelCount = posTravel;
                    mMode = MOVE_UP_POS;
                }
            } else if (clampedPosition > lastPos) {
                final int boundPosFromFirst = boundPosition - firstPos;
                if (boundPosFromFirst < 1) {
                    // Moving would shift our bound position off the screen. Abort.
                    return;
                }

                final int posTravel = clampedPosition - lastPos + 1;
                final int boundTravel = boundPosFromFirst - 1;
                if (boundTravel < posTravel) {
                    viewTravelCount = boundTravel;
                    mMode = MOVE_DOWN_BOUND;
                } else {
                    viewTravelCount = posTravel;
                    mMode = MOVE_DOWN_POS;
                }
            } else {
                scrollToVisible(clampedPosition, boundPosition, SCROLL_DURATION);
                return;
            }

            if (viewTravelCount > 0) {
                mScrollDuration = SCROLL_DURATION / viewTravelCount;
            } else {
                mScrollDuration = SCROLL_DURATION;
            }
            mTargetPos = clampedPosition;
            mBoundPos = boundPosition;
            mLastSeenPos = INVALID_POSITION;

            postOnAnimation(this);
        }

        @Override
        public void startWithOffset(int position, int offset) {
            startWithOffset(position, offset, SCROLL_DURATION);
        }

        @Override
        public void startWithOffset(final int position, int offset, final int duration) {
            stop();

            if (mDataChanged) {
                // Wait until we're back in a stable state to try this.
                final int postOffset = offset;
                mPositionScrollAfterLayout = new Runnable() {
                    @Override public void run() {
                        startWithOffset(position, postOffset, duration);
                    }
                };
                return;
            }

            final int childCount = getChildCount();
            if (childCount == 0) {
                // Can't scroll without children.
                return;
            }

            offset += getPaddingTop();

            mTargetPos = Math.max(0, Math.min(getCount() - 1, position));
            mOffsetFromTop = offset;
            mBoundPos = INVALID_POSITION;
            mLastSeenPos = INVALID_POSITION;
            mMode = MOVE_OFFSET;

            final int firstPos = mFirstPosition;
            final int lastPos = firstPos + childCount - 1;

            int viewTravelCount;
            if (mTargetPos < firstPos) {
                viewTravelCount = firstPos - mTargetPos;
            } else if (mTargetPos > lastPos) {
                viewTravelCount = mTargetPos - lastPos;
            } else {
                // On-screen, just scroll.
                final int targetTop = getChildAt(mTargetPos - firstPos).getTop();
                smoothScrollBy(targetTop - offset, duration, true, false);
                return;
            }

            // Estimate how many screens we should travel
            final float screenTravelCount = (float) viewTravelCount / childCount;
            mScrollDuration = screenTravelCount < 1 ?
                    duration : (int) (duration / screenTravelCount);
            mLastSeenPos = INVALID_POSITION;

            postOnAnimation(this);
        }

        /**
         * Scroll such that targetPos is in the visible padded region without scrolling
         * boundPos out of view. Assumes targetPos is onscreen.
         */
        private void scrollToVisible(int targetPos, int boundPos, int duration) {
            final int firstPos = mFirstPosition;
            final int childCount = getChildCount();
            final int lastPos = firstPos + childCount - 1;
            final int paddedTop = mListPadding.top;
            final int paddedBottom = getHeight() - mListPadding.bottom;

            if (targetPos < firstPos || targetPos > lastPos) {
                Log.w(TAG, "scrollToVisible called with targetPos " + targetPos +
                        " not visible [" + firstPos + ", " + lastPos + "]");
            }
            if (boundPos < firstPos || boundPos > lastPos) {
                // boundPos doesn't matter, it's already offscreen.
                boundPos = INVALID_POSITION;
            }

            final View targetChild = getChildAt(targetPos - firstPos);
            final int targetTop = targetChild.getTop();
            final int targetBottom = targetChild.getBottom();
            int scrollBy = 0;

            if (targetBottom > paddedBottom) {
                scrollBy = targetBottom - paddedBottom;
            }
            if (targetTop < paddedTop) {
                scrollBy = targetTop - paddedTop;
            }

            if (scrollBy == 0) {
                return;
            }

            if (boundPos >= 0) {
                final View boundChild = getChildAt(boundPos - firstPos);
                final int boundTop = boundChild.getTop();
                final int boundBottom = boundChild.getBottom();
                final int absScroll = Math.abs(scrollBy);

                if (scrollBy < 0 && boundBottom + absScroll > paddedBottom) {
                    // Don't scroll the bound view off the bottom of the screen.
                    scrollBy = Math.max(0, boundBottom - paddedBottom);
                } else if (scrollBy > 0 && boundTop - absScroll < paddedTop) {
                    // Don't scroll the bound view off the top of the screen.
                    scrollBy = Math.min(0, boundTop - paddedTop);
                }
            }

            smoothScrollBy(scrollBy, duration);
        }

        @Override
        public void stop() {
            removeCallbacks(this);
        }

        @Override
        public void run() {
            final int listHeight = getHeight();
            final int firstPos = mFirstPosition;

            switch (mMode) {
            case MOVE_DOWN_POS: {
                final int lastViewIndex = getChildCount() - 1;
                final int lastPos = firstPos + lastViewIndex;

                if (lastViewIndex < 0) {
                    return;
                }

                if (lastPos == mLastSeenPos) {
                    // No new views, let things keep going.
                    postOnAnimation(this);
                    return;
                }

                final View lastView = getChildAt(lastViewIndex);
                final int lastViewHeight = lastView.getHeight();
                final int lastViewTop = lastView.getTop();
                final int lastViewPixelsShowing = listHeight - lastViewTop;
                final int extraScroll = lastPos < mItemCount - 1 ?
                        Math.max(mListPadding.bottom, mExtraScroll) : mListPadding.bottom;

                final int scrollBy = lastViewHeight - lastViewPixelsShowing + extraScroll;
                smoothScrollBy(scrollBy, mScrollDuration, true, lastPos < mTargetPos);

                mLastSeenPos = lastPos;
                if (lastPos < mTargetPos) {
                    postOnAnimation(this);
                }
                break;
            }

            case MOVE_DOWN_BOUND: {
                final int nextViewIndex = 1;
                final int childCount = getChildCount();

                if (firstPos == mBoundPos || childCount <= nextViewIndex
                        || firstPos + childCount >= mItemCount) {
                    reportScrollStateChange(OnScrollListener.SCROLL_STATE_IDLE);
                    return;
                }
                final int nextPos = firstPos + nextViewIndex;

                if (nextPos == mLastSeenPos) {
                    // No new views, let things keep going.
                    postOnAnimation(this);
                    return;
                }

                final View nextView = getChildAt(nextViewIndex);
                final int nextViewHeight = nextView.getHeight();
                final int nextViewTop = nextView.getTop();
                final int extraScroll = Math.max(mListPadding.bottom, mExtraScroll);
                if (nextPos < mBoundPos) {
                    smoothScrollBy(Math.max(0, nextViewHeight + nextViewTop - extraScroll),
                            mScrollDuration, true, true);

                    mLastSeenPos = nextPos;

                    postOnAnimation(this);
                } else  {
                    if (nextViewTop > extraScroll) {
                        smoothScrollBy(nextViewTop - extraScroll, mScrollDuration, true, false);
                    } else {
                        reportScrollStateChange(OnScrollListener.SCROLL_STATE_IDLE);
                    }
                }
                break;
            }

            case MOVE_UP_POS: {
                if (firstPos == mLastSeenPos) {
                    // No new views, let things keep going.
                    postOnAnimation(this);
                    return;
                }

                final View firstView = getChildAt(0);
                if (firstView == null) {
                    return;
                }
                final int firstViewTop = firstView.getTop();
                final int extraScroll = firstPos > 0 ?
                        Math.max(mExtraScroll, mListPadding.top) : mListPadding.top;

                smoothScrollBy(firstViewTop - extraScroll, mScrollDuration, true,
                        firstPos > mTargetPos);

                mLastSeenPos = firstPos;

                if (firstPos > mTargetPos) {
                    postOnAnimation(this);
                }
                break;
            }

            case MOVE_UP_BOUND: {
                final int lastViewIndex = getChildCount() - 2;
                if (lastViewIndex < 0) {
                    return;
                }
                final int lastPos = firstPos + lastViewIndex;

                if (lastPos == mLastSeenPos) {
                    // No new views, let things keep going.
                    postOnAnimation(this);
                    return;
                }

                final View lastView = getChildAt(lastViewIndex);
                final int lastViewHeight = lastView.getHeight();
                final int lastViewTop = lastView.getTop();
                final int lastViewPixelsShowing = listHeight - lastViewTop;
                final int extraScroll = Math.max(mListPadding.top, mExtraScroll);
                mLastSeenPos = lastPos;
                if (lastPos > mBoundPos) {
                    smoothScrollBy(-(lastViewPixelsShowing - extraScroll), mScrollDuration, true,
                            true);
                    postOnAnimation(this);
                } else {
                    final int bottom = listHeight - extraScroll;
                    final int lastViewBottom = lastViewTop + lastViewHeight;
                    if (bottom > lastViewBottom) {
                        smoothScrollBy(-(bottom - lastViewBottom), mScrollDuration, true, false);
                    } else {
                        reportScrollStateChange(OnScrollListener.SCROLL_STATE_IDLE);
                    }
                }
                break;
            }

            case MOVE_OFFSET: {
                if (mLastSeenPos == firstPos) {
                    // No new views, let things keep going.
                    postOnAnimation(this);
                    return;
                }

                mLastSeenPos = firstPos;

                final int childCount = getChildCount();

                if (childCount <= 0) {
                    return;
                }

                final int position = mTargetPos;
                final int lastPos = firstPos + childCount - 1;

                // Account for the visible "portion" of the first / last child when we estimate
                // how many screens we should travel to reach our target
                final View firstChild = getChildAt(0);
                final int firstChildHeight = firstChild.getHeight();
                final View lastChild = getChildAt(childCount - 1);
                final int lastChildHeight = lastChild.getHeight();
                final float firstPositionVisiblePart = (firstChildHeight == 0.0f) ? 1.0f
                        : (float) (firstChildHeight + firstChild.getTop()) / firstChildHeight;
                final float lastPositionVisiblePart = (lastChildHeight == 0.0f) ? 1.0f
                        : (float) (lastChildHeight + getHeight() - lastChild.getBottom())
                                / lastChildHeight;

                float viewTravelCount = 0;
                if (position < firstPos) {
                    viewTravelCount = firstPos - position + (1.0f - firstPositionVisiblePart) + 1;
                } else if (position > lastPos) {
                    viewTravelCount = position - lastPos + (1.0f - lastPositionVisiblePart);
                }

                // Estimate how many screens we should travel
                final float screenTravelCount = viewTravelCount / childCount;

                final float modifier = Math.min(Math.abs(screenTravelCount), 1.f);
                if (position < firstPos) {
                    final int distance = (int) (-getHeight() * modifier);
                    final int duration = (int) (mScrollDuration * modifier);
                    smoothScrollBy(distance, duration, true, true);
                    postOnAnimation(this);
                } else if (position > lastPos) {
                    final int distance = (int) (getHeight() * modifier);
                    final int duration = (int) (mScrollDuration * modifier);
                    smoothScrollBy(distance, duration, true, true);
                    postOnAnimation(this);
                } else {
                    // On-screen, just scroll.
                    final int targetTop = getChildAt(position - firstPos).getTop();
                    final int distance = targetTop - mOffsetFromTop;
                    final int duration = (int) (mScrollDuration *
                            ((float) Math.abs(distance) / getHeight()));
                    smoothScrollBy(distance, duration, true, false);
                }
                break;
            }

            default:
                break;
            }
        }
    }
}
