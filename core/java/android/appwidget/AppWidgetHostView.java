/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.appwidget;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityOptions;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.util.SizeF;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.RemoteViews.InteractionHandler;
import android.widget.RemoteViewsAdapter.RemoteAdapterConnectionCallback;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Provides the glue to show AppWidget views. This class offers automatic animation
 * between updates, and will try recycling old views for each incoming
 * {@link RemoteViews}.
 */
public class AppWidgetHostView extends FrameLayout {

    static final String TAG = "AppWidgetHostView";
    private static final String KEY_JAILED_ARRAY = "jail";
    private static final String KEY_INFLATION_ID = "inflation_id";

    static final boolean LOGD = false;

    static final int VIEW_MODE_NOINIT = 0;
    static final int VIEW_MODE_CONTENT = 1;
    static final int VIEW_MODE_ERROR = 2;
    static final int VIEW_MODE_DEFAULT = 3;

    // Set of valid colors resources.
    private static final int FIRST_RESOURCE_COLOR_ID = android.R.color.system_neutral1_0;
    private static final int LAST_RESOURCE_COLOR_ID = android.R.color.system_accent3_1000;

    // When we're inflating the initialLayout for a AppWidget, we only allow
    // views that are allowed in RemoteViews.
    private static final LayoutInflater.Filter INFLATER_FILTER =
            (clazz) -> clazz.isAnnotationPresent(RemoteViews.RemoteView.class);

    Context mContext;
    Context mRemoteContext;

    @UnsupportedAppUsage
    int mAppWidgetId;
    @UnsupportedAppUsage
    AppWidgetProviderInfo mInfo;
    View mView;
    int mViewMode = VIEW_MODE_NOINIT;
    int mLayoutId = -1;
    private InteractionHandler mInteractionHandler;
    private boolean mOnLightBackground;
    private SizeF mCurrentSize = null;
    private RemoteViews.ColorResources mColorResources = null;
    private SparseIntArray mColorMapping = null;
    // Stores the last remote views last inflated.
    private RemoteViews mLastInflatedRemoteViews = null;
    private long mLastInflatedRemoteViewsId = -1;

    private Executor mAsyncExecutor;
    private CancellationSignal mLastExecutionSignal;
    private SparseArray<Parcelable> mDelayedRestoredState;
    private long mDelayedRestoredInflationId;

    /**
     * Create a host view.  Uses default fade animations.
     */
    public AppWidgetHostView(Context context) {
        this(context, android.R.anim.fade_in, android.R.anim.fade_out);
    }

    /**
     * @hide
     */
    public AppWidgetHostView(Context context, InteractionHandler handler) {
        this(context, android.R.anim.fade_in, android.R.anim.fade_out);
        mInteractionHandler = getHandler(handler);
    }

    /**
     * Create a host view. Uses specified animations when pushing
     * {@link #updateAppWidget(RemoteViews)}.
     *
     * @param animationIn Resource ID of in animation to use
     * @param animationOut Resource ID of out animation to use
     */
    @SuppressWarnings({"UnusedDeclaration"})
    public AppWidgetHostView(Context context, int animationIn, int animationOut) {
        super(context);
        mContext = context;
        // We want to segregate the view ids within AppWidgets to prevent
        // problems when those ids collide with view ids in the AppWidgetHost.
        setIsRootNamespace(true);
    }

    /**
     * Pass the given handler to RemoteViews when updating this widget. Unless this
     * is done immediatly after construction, a call to {@link #updateAppWidget(RemoteViews)}
     * should be made.
     * @param handler
     * @hide
     */
    public void setInteractionHandler(InteractionHandler handler) {
        mInteractionHandler = getHandler(handler);
    }

    /**
     * Set the AppWidget that will be displayed by this view. This method also adds default padding
     * to widgets, as described in {@link #getDefaultPaddingForWidget(Context, ComponentName, Rect)}
     * and can be overridden in order to add custom padding.
     */
    public void setAppWidget(int appWidgetId, AppWidgetProviderInfo info) {
        mAppWidgetId = appWidgetId;
        mInfo = info;

        // We add padding to the AppWidgetHostView if necessary
        Rect padding = getDefaultPadding();
        setPadding(padding.left, padding.top, padding.right, padding.bottom);

        // Sometimes the AppWidgetManager returns a null AppWidgetProviderInfo object for
        // a widget, eg. for some widgets in safe mode.
        if (info != null) {
            String description = info.loadLabel(getContext().getPackageManager());
            if ((info.providerInfo.applicationInfo.flags & ApplicationInfo.FLAG_SUSPENDED) != 0) {
                description = Resources.getSystem().getString(
                        com.android.internal.R.string.suspended_widget_accessibility, description);
            }
            setContentDescription(description);
        }
    }

    /**
     * As of ICE_CREAM_SANDWICH we are automatically adding padding to widgets targeting
     * ICE_CREAM_SANDWICH and higher. The new widget design guidelines strongly recommend
     * that widget developers do not add extra padding to their widgets. This will help
     * achieve consistency among widgets.
     *
     * Note: this method is only needed by developers of AppWidgetHosts. The method is provided in
     * order for the AppWidgetHost to account for the automatic padding when computing the number
     * of cells to allocate to a particular widget.
     *
     * @param context the current context
     * @param component the component name of the widget
     * @param padding Rect in which to place the output, if null, a new Rect will be allocated and
     *                returned
     * @return default padding for this widget, in pixels
     */
    public static Rect getDefaultPaddingForWidget(Context context, ComponentName component,
            Rect padding) {
        return getDefaultPaddingForWidget(context, padding);
    }

    private static Rect getDefaultPaddingForWidget(Context context, Rect padding) {
        if (padding == null) {
            padding = new Rect(0, 0, 0, 0);
        } else {
            padding.set(0, 0, 0, 0);
        }
        Resources r = context.getResources();
        padding.left = r.getDimensionPixelSize(
                com.android.internal.R.dimen.default_app_widget_padding_left);
        padding.right = r.getDimensionPixelSize(
                com.android.internal.R.dimen.default_app_widget_padding_right);
        padding.top = r.getDimensionPixelSize(
                com.android.internal.R.dimen.default_app_widget_padding_top);
        padding.bottom = r.getDimensionPixelSize(
                com.android.internal.R.dimen.default_app_widget_padding_bottom);
        return padding;
    }

    private Rect getDefaultPadding() {
        return getDefaultPaddingForWidget(mContext, null);
    }

    public int getAppWidgetId() {
        return mAppWidgetId;
    }

    public AppWidgetProviderInfo getAppWidgetInfo() {
        return mInfo;
    }

    @Override
    protected void dispatchSaveInstanceState(SparseArray<Parcelable> container) {
        final SparseArray<Parcelable> jail = new SparseArray<>();
        super.dispatchSaveInstanceState(jail);

        Bundle bundle = new Bundle();
        bundle.putSparseParcelableArray(KEY_JAILED_ARRAY, jail);
        bundle.putLong(KEY_INFLATION_ID, mLastInflatedRemoteViewsId);
        container.put(generateId(), bundle);
        container.put(generateId(), bundle);
    }

    private int generateId() {
        final int id = getId();
        return id == View.NO_ID ? mAppWidgetId : id;
    }

    @Override
    protected void dispatchRestoreInstanceState(SparseArray<Parcelable> container) {
        final Parcelable parcelable = container.get(generateId());

        SparseArray<Parcelable> jail = null;
        long inflationId = -1;
        if (parcelable instanceof Bundle) {
            Bundle bundle = (Bundle) parcelable;
            jail = bundle.getSparseParcelableArray(KEY_JAILED_ARRAY);
            inflationId = bundle.getLong(KEY_INFLATION_ID, -1);
        }

        if (jail == null) jail = new SparseArray<>();

        mDelayedRestoredState = jail;
        mDelayedRestoredInflationId = inflationId;
        restoreInstanceState();
    }

    void restoreInstanceState() {
        long inflationId = mDelayedRestoredInflationId;
        SparseArray<Parcelable> state = mDelayedRestoredState;
        if (inflationId == -1 || inflationId != mLastInflatedRemoteViewsId) {
            return; // We don't restore.
        }
        mDelayedRestoredInflationId = -1;
        mDelayedRestoredState = null;
        try  {
            super.dispatchRestoreInstanceState(state);
        } catch (Exception e) {
            Log.e(TAG, "failed to restoreInstanceState for widget id: " + mAppWidgetId + ", "
                    + (mInfo == null ? "null" : mInfo.provider), e);
        }
    }

    private SizeF computeSizeFromLayout(int left, int top, int right, int bottom) {
        float density = getResources().getDisplayMetrics().density;
        return new SizeF(
                (right - left - getPaddingLeft() - getPaddingRight()) / density,
                (bottom - top - getPaddingTop() - getPaddingBottom()) / density
        );
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        try {
            SizeF oldSize = mCurrentSize;
            SizeF newSize = computeSizeFromLayout(left, top, right, bottom);
            mCurrentSize = newSize;
            if (mLastInflatedRemoteViews != null) {
                RemoteViews toApply = mLastInflatedRemoteViews.getRemoteViewsToApplyIfDifferent(
                        oldSize, newSize);
                if (toApply != null) {
                    applyRemoteViews(toApply, false);
                    measureChildWithMargins(mView,
                            MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                            0 /* widthUsed */,
                            MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY),
                            0 /* heightUsed */);
                }
            }
            super.onLayout(changed, left, top, right, bottom);
        } catch (final RuntimeException e) {
            Log.e(TAG, "Remote provider threw runtime exception, using error view instead.", e);
            removeViewInLayout(mView);
            View child = getErrorView();
            prepareView(child);
            addViewInLayout(child, 0, child.getLayoutParams());
            measureChild(child, MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
            child.layout(0, 0, child.getMeasuredWidth() + mPaddingLeft + mPaddingRight,
                    child.getMeasuredHeight() + mPaddingTop + mPaddingBottom);
            mView = child;
            mViewMode = VIEW_MODE_ERROR;
        }
    }

    /**
     * Provide guidance about the size of this widget to the AppWidgetManager. The widths and
     * heights should correspond to the full area the AppWidgetHostView is given. Padding added by
     * the framework will be accounted for automatically. This information gets embedded into the
     * AppWidget options and causes a callback to the AppWidgetProvider. In addition, the list of
     * sizes is explicitly set to an empty list.
     * @see AppWidgetProvider#onAppWidgetOptionsChanged(Context, AppWidgetManager, int, Bundle)
     *
     * @param newOptions The bundle of options, in addition to the size information,
     *          can be null.
     * @param minWidth The minimum width in dips that the widget will be displayed at.
     * @param minHeight The maximum height in dips that the widget will be displayed at.
     * @param maxWidth The maximum width in dips that the widget will be displayed at.
     * @param maxHeight The maximum height in dips that the widget will be displayed at.
     * @deprecated use {@link AppWidgetHostView#updateAppWidgetSize(Bundle, List)} instead.
     */
    @Deprecated
    public void updateAppWidgetSize(Bundle newOptions, int minWidth, int minHeight, int maxWidth,
            int maxHeight) {
        updateAppWidgetSize(newOptions, minWidth, minHeight, maxWidth, maxHeight, false);
    }

    /**
     * Provide guidance about the size of this widget to the AppWidgetManager. The sizes should
     * correspond to the full area the AppWidgetHostView is given. Padding added by the framework
     * will be accounted for automatically.
     *
     * This method will update the option bundle with the list of sizes and the min/max bounds for
     * width and height.
     *
     * @see AppWidgetProvider#onAppWidgetOptionsChanged(Context, AppWidgetManager, int, Bundle)
     *
     * @param newOptions The bundle of options, in addition to the size information.
     * @param sizes Sizes, in dips, the widget may be displayed at without calling the provider
     *              again. Typically, this will be size of the widget in landscape and portrait.
     *              On some foldables, this might include the size on the outer and inner screens.
     */
    public void updateAppWidgetSize(@NonNull Bundle newOptions, @NonNull List<SizeF> sizes) {
        AppWidgetManager widgetManager = AppWidgetManager.getInstance(mContext);

        Rect padding = getDefaultPadding();
        float density = getResources().getDisplayMetrics().density;

        float xPaddingDips = (padding.left + padding.right) / density;
        float yPaddingDips = (padding.top + padding.bottom) / density;

        ArrayList<SizeF> paddedSizes = new ArrayList<>(sizes.size());
        float minWidth = Float.MAX_VALUE;
        float maxWidth = 0;
        float minHeight = Float.MAX_VALUE;
        float maxHeight = 0;
        for (int i = 0; i < sizes.size(); i++) {
            SizeF size = sizes.get(i);
            SizeF paddedSize = new SizeF(Math.max(0.f, size.getWidth() - xPaddingDips),
                    Math.max(0.f, size.getHeight() - yPaddingDips));
            paddedSizes.add(paddedSize);
            minWidth = Math.min(minWidth, paddedSize.getWidth());
            maxWidth = Math.max(maxWidth, paddedSize.getWidth());
            minHeight = Math.min(minHeight, paddedSize.getHeight());
            maxHeight = Math.max(maxHeight, paddedSize.getHeight());
        }
        if (paddedSizes.equals(
                widgetManager.getAppWidgetOptions(mAppWidgetId).<SizeF>getParcelableArrayList(
                        AppWidgetManager.OPTION_APPWIDGET_SIZES))) {
            return;
        }
        Bundle options = newOptions.deepCopy();
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, (int) minWidth);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, (int) minHeight);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, (int) maxWidth);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, (int) maxHeight);
        options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, paddedSizes);
        updateAppWidgetOptions(options);
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void updateAppWidgetSize(Bundle newOptions, int minWidth, int minHeight, int maxWidth,
            int maxHeight, boolean ignorePadding) {
        if (newOptions == null) {
            newOptions = new Bundle();
        }

        Rect padding = getDefaultPadding();
        float density = getResources().getDisplayMetrics().density;

        int xPaddingDips = (int) ((padding.left + padding.right) / density);
        int yPaddingDips = (int) ((padding.top + padding.bottom) / density);

        int newMinWidth = minWidth - (ignorePadding ? 0 : xPaddingDips);
        int newMinHeight = minHeight - (ignorePadding ? 0 : yPaddingDips);
        int newMaxWidth = maxWidth - (ignorePadding ? 0 : xPaddingDips);
        int newMaxHeight = maxHeight - (ignorePadding ? 0 : yPaddingDips);

        AppWidgetManager widgetManager = AppWidgetManager.getInstance(mContext);

        // We get the old options to see if the sizes have changed
        Bundle oldOptions = widgetManager.getAppWidgetOptions(mAppWidgetId);
        boolean needsUpdate = false;
        if (newMinWidth != oldOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ||
                newMinHeight != oldOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ||
                newMaxWidth != oldOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) ||
                newMaxHeight != oldOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)) {
            needsUpdate = true;
        }

        if (needsUpdate) {
            newOptions.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, newMinWidth);
            newOptions.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, newMinHeight);
            newOptions.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, newMaxWidth);
            newOptions.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, newMaxHeight);
            newOptions.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,
                    new ArrayList<PointF>());
            updateAppWidgetOptions(newOptions);
        }
    }

    /**
     * Specify some extra information for the widget provider. Causes a callback to the
     * AppWidgetProvider.
     * @see AppWidgetProvider#onAppWidgetOptionsChanged(Context, AppWidgetManager, int, Bundle)
     *
     * @param options The bundle of options information.
     */
    public void updateAppWidgetOptions(Bundle options) {
        AppWidgetManager.getInstance(mContext).updateAppWidgetOptions(mAppWidgetId, options);
    }

    /** {@inheritDoc} */
    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        // We're being asked to inflate parameters, probably by a LayoutInflater
        // in a remote Context. To help resolve any remote references, we
        // inflate through our last mRemoteContext when it exists.
        final Context context = mRemoteContext != null ? mRemoteContext : mContext;
        return new FrameLayout.LayoutParams(context, attrs);
    }

    /**
     * Sets an executor which can be used for asynchronously inflating. CPU intensive tasks like
     * view inflation or loading images will be performed on the executor. The updates will still
     * be applied on the UI thread.
     *
     * @param executor the executor to use or null.
     */
    public void setExecutor(Executor executor) {
        if (mLastExecutionSignal != null) {
            mLastExecutionSignal.cancel();
            mLastExecutionSignal = null;
        }

        mAsyncExecutor = executor;
    }

    /**
     * Sets whether the widget is being displayed on a light/white background and use an
     * alternate UI if available.
     * @see RemoteViews#setLightBackgroundLayoutId(int)
     */
    public void setOnLightBackground(boolean onLightBackground) {
        mOnLightBackground = onLightBackground;
    }

    /**
     * Update the AppWidgetProviderInfo for this view, and reset it to the
     * initial layout.
     */
    void resetAppWidget(AppWidgetProviderInfo info) {
        setAppWidget(mAppWidgetId, info);
        mViewMode = VIEW_MODE_NOINIT;
        updateAppWidget(null);
    }

    /**
     * Process a set of {@link RemoteViews} coming in as an update from the
     * AppWidget provider. Will animate into these new views as needed
     */
    public void updateAppWidget(RemoteViews remoteViews) {
        mLastInflatedRemoteViews = remoteViews;
        applyRemoteViews(remoteViews, true);
    }

    /**
     * Reapply the last inflated remote views, or the default view is none was inflated.
     */
    private void reapplyLastRemoteViews() {
        SparseArray<Parcelable> savedState = new SparseArray<>();
        saveHierarchyState(savedState);
        applyRemoteViews(mLastInflatedRemoteViews, true);
        restoreHierarchyState(savedState);
    }

    /**
     * @hide
     */
    protected void applyRemoteViews(@Nullable RemoteViews remoteViews, boolean useAsyncIfPossible) {
        boolean recycled = false;
        View content = null;
        Exception exception = null;

        // Block state restore until the end of the apply.
        mLastInflatedRemoteViewsId = -1;

        if (mLastExecutionSignal != null) {
            mLastExecutionSignal.cancel();
            mLastExecutionSignal = null;
        }

        if (remoteViews == null) {
            if (mViewMode == VIEW_MODE_DEFAULT) {
                // We've already done this -- nothing to do.
                return;
            }
            content = getDefaultView();
            mLayoutId = -1;
            mViewMode = VIEW_MODE_DEFAULT;
        } else {
            // Select the remote view we are actually going to apply.
            RemoteViews rvToApply = remoteViews.getRemoteViewsToApply(mContext, mCurrentSize);
            if (mOnLightBackground) {
                rvToApply = rvToApply.getDarkTextViews();
            }

            if (mAsyncExecutor != null && useAsyncIfPossible) {
                inflateAsync(rvToApply);
                return;
            }
            int layoutId = rvToApply.getLayoutId();
            if (rvToApply.canRecycleView(mView)) {
                try {
                    rvToApply.reapply(mContext, mView, mInteractionHandler, mCurrentSize,
                            mColorResources);
                    content = mView;
                    mLastInflatedRemoteViewsId = rvToApply.computeUniqueId(remoteViews);
                    recycled = true;
                    if (LOGD) Log.d(TAG, "was able to recycle existing layout");
                } catch (RuntimeException e) {
                    exception = e;
                }
            }

            // Try normal RemoteView inflation
            if (content == null) {
                try {
                    content = rvToApply.apply(mContext, this, mInteractionHandler,
                            mCurrentSize, mColorResources);
                    mLastInflatedRemoteViewsId = rvToApply.computeUniqueId(remoteViews);
                    if (LOGD) Log.d(TAG, "had to inflate new layout");
                } catch (RuntimeException e) {
                    exception = e;
                }
            }

            mLayoutId = layoutId;
            mViewMode = VIEW_MODE_CONTENT;
        }

        applyContent(content, recycled, exception);
    }

    private void applyContent(View content, boolean recycled, Exception exception) {
        if (content == null) {
            if (mViewMode == VIEW_MODE_ERROR) {
                // We've already done this -- nothing to do.
                return ;
            }
            if (exception != null) {
                Log.w(TAG, "Error inflating RemoteViews", exception);
            }
            content = getErrorView();
            mViewMode = VIEW_MODE_ERROR;
        }

        if (!recycled) {
            prepareView(content);
            addView(content);
        }

        if (mView != content) {
            removeView(mView);
            mView = content;
        }
    }

    private void inflateAsync(@NonNull RemoteViews remoteViews) {
        // Prepare a local reference to the remote Context so we're ready to
        // inflate any requested LayoutParams.
        mRemoteContext = getRemoteContext();
        int layoutId = remoteViews.getLayoutId();

        if (mLastExecutionSignal != null) {
            mLastExecutionSignal.cancel();
        }

        // If our stale view has been prepared to match active, and the new
        // layout matches, try recycling it
        if (layoutId == mLayoutId && mView != null) {
            try {
                mLastExecutionSignal = remoteViews.reapplyAsync(mContext,
                        mView,
                        mAsyncExecutor,
                        new ViewApplyListener(remoteViews, layoutId, true),
                        mInteractionHandler,
                        mCurrentSize,
                        mColorResources);
            } catch (Exception e) {
                // Reapply failed. Try apply
            }
        }
        if (mLastExecutionSignal == null) {
            mLastExecutionSignal = remoteViews.applyAsync(mContext,
                    this,
                    mAsyncExecutor,
                    new ViewApplyListener(remoteViews, layoutId, false),
                    mInteractionHandler,
                    mCurrentSize,
                    mColorResources);
        }
    }

    private class ViewApplyListener implements RemoteViews.OnViewAppliedListener {
        private final RemoteViews mViews;
        private final boolean mIsReapply;
        private final int mLayoutId;

        ViewApplyListener(
                RemoteViews views,
                int layoutId,
                boolean isReapply) {
            mViews = views;
            mLayoutId = layoutId;
            mIsReapply = isReapply;
        }

        @Override
        public void onViewApplied(View v) {
            AppWidgetHostView.this.mLayoutId = mLayoutId;
            mViewMode = VIEW_MODE_CONTENT;

            applyContent(v, mIsReapply, null);

            mLastInflatedRemoteViewsId = mViews.computeUniqueId(mLastInflatedRemoteViews);
            restoreInstanceState();
            mLastExecutionSignal = null;
        }

        @Override
        public void onError(Exception e) {
            if (mIsReapply) {
                // Try a fresh replay
                mLastExecutionSignal = mViews.applyAsync(mContext,
                        AppWidgetHostView.this,
                        mAsyncExecutor,
                        new ViewApplyListener(mViews, mLayoutId, false),
                        mInteractionHandler,
                        mCurrentSize);
            } else {
                applyContent(null, false, e);
            }
            mLastExecutionSignal = null;
        }
    }

    /**
     * Process data-changed notifications for the specified view in the specified
     * set of {@link RemoteViews} views.
     */
    void viewDataChanged(int viewId) {
        View v = findViewById(viewId);
        if ((v != null) && (v instanceof AdapterView<?>)) {
            AdapterView<?> adapterView = (AdapterView<?>) v;
            Adapter adapter = adapterView.getAdapter();
            if (adapter instanceof BaseAdapter) {
                BaseAdapter baseAdapter = (BaseAdapter) adapter;
                baseAdapter.notifyDataSetChanged();
            }  else if (adapter == null && adapterView instanceof RemoteAdapterConnectionCallback) {
                // If the adapter is null, it may mean that the RemoteViewsAapter has not yet
                // connected to its associated service, and hence the adapter hasn't been set.
                // In this case, we need to defer the notify call until it has been set.
                ((RemoteAdapterConnectionCallback) adapterView).deferNotifyDataSetChanged();
            }
        }
    }

    /**
     * Build a {@link Context} cloned into another package name, usually for the
     * purposes of reading remote resources.
     * @hide
     */
    protected Context getRemoteContext() {
        try {
            // Return if cloned successfully, otherwise default
            Context newContext = mContext.createApplicationContext(
                    mInfo.providerInfo.applicationInfo,
                    Context.CONTEXT_RESTRICTED);
            if (mColorResources != null) {
                mColorResources.apply(newContext);
            }
            return newContext;
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Package name " +  mInfo.providerInfo.packageName + " not found");
            return mContext;
        }
    }

    /**
     * Prepare the given view to be shown. This might include adjusting
     * {@link FrameLayout.LayoutParams} before inserting.
     */
    protected void prepareView(View view) {
        // Take requested dimensions from child, but apply default gravity.
        FrameLayout.LayoutParams requested = (FrameLayout.LayoutParams)view.getLayoutParams();
        if (requested == null) {
            requested = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT);
        }

        requested.gravity = Gravity.CENTER;
        view.setLayoutParams(requested);
    }

    /**
     * Inflate and return the default layout requested by AppWidget provider.
     */
    protected View getDefaultView() {
        if (LOGD) {
            Log.d(TAG, "getDefaultView");
        }
        View defaultView = null;
        Exception exception = null;

        try {
            if (mInfo != null) {
                Context theirContext = getRemoteContext();
                mRemoteContext = theirContext;
                LayoutInflater inflater = (LayoutInflater)
                        theirContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                inflater = inflater.cloneInContext(theirContext);
                inflater.setFilter(INFLATER_FILTER);
                AppWidgetManager manager = AppWidgetManager.getInstance(mContext);
                Bundle options = manager.getAppWidgetOptions(mAppWidgetId);

                int layoutId = mInfo.initialLayout;
                if (options.containsKey(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY)) {
                    int category = options.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY);
                    if (category == AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD) {
                        int kgLayoutId = mInfo.initialKeyguardLayout;
                        // If a default keyguard layout is not specified, use the standard
                        // default layout.
                        layoutId = kgLayoutId == 0 ? layoutId : kgLayoutId;
                    }
                }
                defaultView = inflater.inflate(layoutId, this, false);
                if (!(defaultView instanceof AdapterView)) {
                    // AdapterView does not support onClickListener
                    defaultView.setOnClickListener(this::onDefaultViewClicked);
                }
            } else {
                Log.w(TAG, "can't inflate defaultView because mInfo is missing");
            }
        } catch (RuntimeException e) {
            exception = e;
        }

        if (exception != null) {
            Log.w(TAG, "Error inflating AppWidget " + mInfo, exception);
        }

        if (defaultView == null) {
            if (LOGD) Log.d(TAG, "getDefaultView couldn't find any view, so inflating error");
            defaultView = getErrorView();
        }

        return defaultView;
    }

    private void onDefaultViewClicked(View view) {
        if (mInfo != null) {
            LauncherApps launcherApps = getContext().getSystemService(LauncherApps.class);
            List<LauncherActivityInfo> activities = launcherApps.getActivityList(
                    mInfo.provider.getPackageName(), mInfo.getProfile());
            if (!activities.isEmpty()) {
                LauncherActivityInfo ai = activities.get(0);
                launcherApps.startMainActivity(ai.getComponentName(), ai.getUser(),
                        RemoteViews.getSourceBounds(view), null);
            }
        }
    }

    /**
     * Inflate and return a view that represents an error state.
     */
    protected View getErrorView() {
        TextView tv = new TextView(mContext);
        tv.setText(com.android.internal.R.string.gadget_host_error_inflating);
        // TODO: get this color from somewhere.
        tv.setBackgroundColor(Color.argb(127, 0, 0, 0));
        return tv;
    }

    /** @hide */
    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);
        info.setClassName(AppWidgetHostView.class.getName());
    }

    /** @hide */
    public ActivityOptions createSharedElementActivityOptions(
            int[] sharedViewIds, String[] sharedViewNames, Intent fillInIntent) {
        Context parentContext = getContext();
        while ((parentContext instanceof ContextWrapper)
                && !(parentContext instanceof Activity)) {
            parentContext = ((ContextWrapper) parentContext).getBaseContext();
        }
        if (!(parentContext instanceof Activity)) {
            return null;
        }

        List<Pair<View, String>> sharedElements = new ArrayList<>();
        Bundle extras = new Bundle();

        for (int i = 0; i < sharedViewIds.length; i++) {
            View view = findViewById(sharedViewIds[i]);
            if (view != null) {
                sharedElements.add(Pair.create(view, sharedViewNames[i]));

                extras.putParcelable(sharedViewNames[i], RemoteViews.getSourceBounds(view));
            }
        }

        if (!sharedElements.isEmpty()) {
            fillInIntent.putExtra(RemoteViews.EXTRA_SHARED_ELEMENT_BOUNDS, extras);
            final ActivityOptions opts = ActivityOptions.makeSceneTransitionAnimation(
                    (Activity) parentContext,
                    sharedElements.toArray(new Pair[sharedElements.size()]));
            opts.setPendingIntentLaunchFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return opts;
        }
        return null;
    }

    private InteractionHandler getHandler(InteractionHandler handler) {
        return (view, pendingIntent, response) -> {
            AppWidgetManager.getInstance(mContext).noteAppWidgetTapped(mAppWidgetId);
            if (handler != null) {
                return handler.onInteraction(view, pendingIntent, response);
            } else {
                return RemoteViews.startPendingIntent(view, pendingIntent,
                        response.getLaunchOptions(view));
            }
        };
    }

    /**
     * Set the dynamically overloaded color resources.
     *
     * {@code colorMapping} maps a predefined set of color resources to their ARGB
     * representation. Any entry not in the predefined set of colors will be ignored.
     *
     * Calling this method will trigger a full re-inflation of the App Widget.
     *
     * The color resources that can be overloaded are the ones whose name is prefixed with
     * {@code system_neutral} or {@code system_accent}, for example
     * {@link android.R.color#system_neutral1_500}.
     */
    public void setColorResources(@NonNull SparseIntArray colorMapping) {
        if (mColorMapping != null && isSameColorMapping(mColorMapping, colorMapping)) {
            return;
        }
        mColorMapping = colorMapping.clone();
        mColorResources = RemoteViews.ColorResources.create(mContext, mColorMapping);
        mLayoutId = -1;
        mViewMode = VIEW_MODE_NOINIT;
        reapplyLastRemoteViews();
    }

    /** Check if, in the current context, the two color mappings are equivalent. */
    private boolean isSameColorMapping(SparseIntArray oldColors, SparseIntArray newColors) {
        if (oldColors.size() != newColors.size()) {
            return false;
        }
        for (int i = 0; i < oldColors.size(); i++) {
            if (oldColors.keyAt(i) != newColors.keyAt(i)
                    || oldColors.valueAt(i) != newColors.valueAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reset the dynamically overloaded resources, reverting to the default values for
     * all the colors.
     *
     * If colors were defined before, calling this method will trigger a full re-inflation of the
     * App Widget.
     */
    public void resetColorResources() {
        if (mColorResources != null) {
            mColorResources = null;
            mColorMapping = null;
            mLayoutId = -1;
            mViewMode = VIEW_MODE_NOINIT;
            reapplyLastRemoteViews();
        }
    }
}
