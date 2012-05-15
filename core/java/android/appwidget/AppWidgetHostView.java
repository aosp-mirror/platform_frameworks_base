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

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.RemoteViewsAdapter.RemoteAdapterConnectionCallback;
import android.widget.TextView;

/**
 * Provides the glue to show AppWidget views. This class offers automatic animation
 * between updates, and will try recycling old views for each incoming
 * {@link RemoteViews}.
 */
public class AppWidgetHostView extends FrameLayout {
    static final String TAG = "AppWidgetHostView";
    static final boolean LOGD = false;
    static final boolean CROSSFADE = false;

    static final int VIEW_MODE_NOINIT = 0;
    static final int VIEW_MODE_CONTENT = 1;
    static final int VIEW_MODE_ERROR = 2;
    static final int VIEW_MODE_DEFAULT = 3;

    static final int FADE_DURATION = 1000;

    // When we're inflating the initialLayout for a AppWidget, we only allow
    // views that are allowed in RemoteViews.
    static final LayoutInflater.Filter sInflaterFilter = new LayoutInflater.Filter() {
        public boolean onLoadClass(Class clazz) {
            return clazz.isAnnotationPresent(RemoteViews.RemoteView.class);
        }
    };

    Context mContext;
    Context mRemoteContext;

    int mAppWidgetId;
    AppWidgetProviderInfo mInfo;
    View mView;
    int mViewMode = VIEW_MODE_NOINIT;
    int mLayoutId = -1;
    long mFadeStartTime = -1;
    Bitmap mOld;
    Paint mOldPaint = new Paint();
    
    /**
     * Create a host view.  Uses default fade animations.
     */
    public AppWidgetHostView(Context context) {
        this(context, android.R.anim.fade_in, android.R.anim.fade_out);
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
     * Set the AppWidget that will be displayed by this view. This method also adds default padding
     * to widgets, as described in {@link #getDefaultPaddingForWidget(Context, ComponentName, Rect)}
     * and can be overridden in order to add custom padding.
     */
    public void setAppWidget(int appWidgetId, AppWidgetProviderInfo info) {
        mAppWidgetId = appWidgetId;
        mInfo = info;

        // Sometimes the AppWidgetManager returns a null AppWidgetProviderInfo object for
        // a widget, eg. for some widgets in safe mode.
        if (info != null) {
            // We add padding to the AppWidgetHostView if necessary
            Rect padding = getDefaultPaddingForWidget(mContext, info.provider, null);
            setPadding(padding.left, padding.top, padding.right, padding.bottom);
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
     * @return default padding for this widget
     */
    public static Rect getDefaultPaddingForWidget(Context context, ComponentName component,
            Rect padding) {
        PackageManager packageManager = context.getPackageManager();
        ApplicationInfo appInfo;

        if (padding == null) {
            padding = new Rect(0, 0, 0, 0);
        } else {
            padding.set(0, 0, 0, 0);
        }

        try {
            appInfo = packageManager.getApplicationInfo(component.getPackageName(), 0);
        } catch (NameNotFoundException e) {
            // if we can't find the package, return 0 padding
            return padding;
        }

        if (appInfo.targetSdkVersion >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            Resources r = context.getResources();
            padding.left = r.getDimensionPixelSize(com.android.internal.
                    R.dimen.default_app_widget_padding_left);
            padding.right = r.getDimensionPixelSize(com.android.internal.
                    R.dimen.default_app_widget_padding_right);
            padding.top = r.getDimensionPixelSize(com.android.internal.
                    R.dimen.default_app_widget_padding_top);
            padding.bottom = r.getDimensionPixelSize(com.android.internal.
                    R.dimen.default_app_widget_padding_bottom);
        }
        return padding;
    }

    public int getAppWidgetId() {
        return mAppWidgetId;
    }
    
    public AppWidgetProviderInfo getAppWidgetInfo() {
        return mInfo;
    }

    @Override
    protected void dispatchSaveInstanceState(SparseArray<Parcelable> container) {
        final ParcelableSparseArray jail = new ParcelableSparseArray();
        super.dispatchSaveInstanceState(jail);
        container.put(generateId(), jail);
    }

    private int generateId() {
        final int id = getId();
        return id == View.NO_ID ? mAppWidgetId : id;
    }

    @Override
    protected void dispatchRestoreInstanceState(SparseArray<Parcelable> container) {
        final Parcelable parcelable = container.get(generateId());

        ParcelableSparseArray jail = null;
        if (parcelable != null && parcelable instanceof ParcelableSparseArray) {
            jail = (ParcelableSparseArray) parcelable;
        }

        if (jail == null) jail = new ParcelableSparseArray();

        super.dispatchRestoreInstanceState(jail);
    }

    /**
     * Provide guidance about the size of this widget to the AppWidgetManager. The widths and
     * heights should correspond to the full area the AppWidgetHostView is given. Padding added by
     * the framework will be accounted for automatically. This information gets embedded into the
     * AppWidget options and causes a callback to the AppWidgetProvider.
     * @see AppWidgetProvider#onAppWidgetOptionsChanged(Context, AppWidgetManager, int, Bundle)
     *
     * @param options The bundle of options, in addition to the size information,
     *          can be null.
     * @param minWidth The minimum width that the widget will be displayed at.
     * @param minHeight The maximum height that the widget will be displayed at.
     * @param maxWidth The maximum width that the widget will be displayed at.
     * @param maxHeight The maximum height that the widget will be displayed at.
     *
     */
    public void updateAppWidgetSize(Bundle options, int minWidth, int minHeight, int maxWidth,
            int maxHeight) {
        if (options == null) {
            options = new Bundle();
        }

        Rect padding = new Rect();
        if (mInfo != null) {
            padding = getDefaultPaddingForWidget(mContext, mInfo.provider, padding);
        }
        float density = getResources().getDisplayMetrics().density;

        int xPaddingDips = (int) ((padding.left + padding.right) / density);
        int yPaddingDips = (int) ((padding.top + padding.bottom) / density);

        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, minWidth - xPaddingDips);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, minHeight - yPaddingDips);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, maxWidth - xPaddingDips);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, maxHeight - yPaddingDips);
        updateAppWidgetOptions(options);
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
     * Update the AppWidgetProviderInfo for this view, and reset it to the
     * initial layout.
     */
    void resetAppWidget(AppWidgetProviderInfo info) {
        mInfo = info;
        mViewMode = VIEW_MODE_NOINIT;
        updateAppWidget(null);
    }

    /**
     * Process a set of {@link RemoteViews} coming in as an update from the
     * AppWidget provider. Will animate into these new views as needed
     */
    public void updateAppWidget(RemoteViews remoteViews) {
        if (LOGD) Log.d(TAG, "updateAppWidget called mOld=" + mOld);

        boolean recycled = false;
        View content = null;
        Exception exception = null;
        
        // Capture the old view into a bitmap so we can do the crossfade.
        if (CROSSFADE) {
            if (mFadeStartTime < 0) {
                if (mView != null) {
                    final int width = mView.getWidth();
                    final int height = mView.getHeight();
                    try {
                        mOld = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    } catch (OutOfMemoryError e) {
                        // we just won't do the fade
                        mOld = null;
                    }
                    if (mOld != null) {
                        //mView.drawIntoBitmap(mOld);
                    }
                }
            }
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
            // Prepare a local reference to the remote Context so we're ready to
            // inflate any requested LayoutParams.
            mRemoteContext = getRemoteContext(remoteViews);
            int layoutId = remoteViews.getLayoutId();

            // If our stale view has been prepared to match active, and the new
            // layout matches, try recycling it
            if (content == null && layoutId == mLayoutId) {
                try {
                    remoteViews.reapply(mContext, mView);
                    content = mView;
                    recycled = true;
                    if (LOGD) Log.d(TAG, "was able to recycled existing layout");
                } catch (RuntimeException e) {
                    exception = e;
                }
            }
            
            // Try normal RemoteView inflation
            if (content == null) {
                try {
                    content = remoteViews.apply(mContext, this);
                    if (LOGD) Log.d(TAG, "had to inflate new layout");
                } catch (RuntimeException e) {
                    exception = e;
                }
            }

            mLayoutId = layoutId;
            mViewMode = VIEW_MODE_CONTENT;
        }
        
        if (content == null) {
            if (mViewMode == VIEW_MODE_ERROR) {
                // We've already done this -- nothing to do.
                return ;
            }
            Log.w(TAG, "updateAppWidget couldn't find any view, using error view", exception);
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

        if (CROSSFADE) {
            if (mFadeStartTime < 0) {
                // if there is already an animation in progress, don't do anything --
                // the new view will pop in on top of the old one during the cross fade,
                // and that looks okay.
                mFadeStartTime = SystemClock.uptimeMillis();
                invalidate();
            }
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
     */
    private Context getRemoteContext(RemoteViews views) {
        // Bail if missing package name
        final String packageName = views.getPackage();
        if (packageName == null) return mContext;

        try {
            // Return if cloned successfully, otherwise default
            return mContext.createPackageContext(packageName, Context.CONTEXT_RESTRICTED);
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Package name " + packageName + " not found");
            return mContext;
        }
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (CROSSFADE) {
            int alpha;
            int l = child.getLeft();
            int t = child.getTop();
            if (mFadeStartTime > 0) {
                alpha = (int)(((drawingTime-mFadeStartTime)*255)/FADE_DURATION);
                if (alpha > 255) {
                    alpha = 255;
                }
                Log.d(TAG, "drawChild alpha=" + alpha + " l=" + l + " t=" + t
                        + " w=" + child.getWidth());
                if (alpha != 255 && mOld != null) {
                    mOldPaint.setAlpha(255-alpha);
                    //canvas.drawBitmap(mOld, l, t, mOldPaint);
                }
            } else {
                alpha = 255;
            }
            int restoreTo = canvas.saveLayerAlpha(l, t, child.getWidth(), child.getHeight(), alpha,
                    Canvas.HAS_ALPHA_LAYER_SAVE_FLAG | Canvas.CLIP_TO_LAYER_SAVE_FLAG);
            boolean rv = super.drawChild(canvas, child, drawingTime);
            canvas.restoreToCount(restoreTo);
            if (alpha < 255) {
                invalidate();
            } else {
                mFadeStartTime = -1;
                if (mOld != null) {
                    mOld.recycle();
                    mOld = null;
                }
            }
            return rv;
        } else {
            return super.drawChild(canvas, child, drawingTime);
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
                Context theirContext = mContext.createPackageContext(
                        mInfo.provider.getPackageName(), Context.CONTEXT_RESTRICTED);
                mRemoteContext = theirContext;
                LayoutInflater inflater = (LayoutInflater)
                        theirContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                inflater = inflater.cloneInContext(theirContext);
                inflater.setFilter(sInflaterFilter);
                defaultView = inflater.inflate(mInfo.initialLayout, this, false);
            } else {
                Log.w(TAG, "can't inflate defaultView because mInfo is missing");
            }
        } catch (PackageManager.NameNotFoundException e) {
            exception = e;
        } catch (RuntimeException e) {
            exception = e;
        }
        
        if (exception != null) {
            Log.w(TAG, "Error inflating AppWidget " + mInfo + ": " + exception.toString());
        }
        
        if (defaultView == null) {
            if (LOGD) Log.d(TAG, "getDefaultView couldn't find any view, so inflating error");
            defaultView = getErrorView();
        }
        
        return defaultView;
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

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(AppWidgetHostView.class.getName());
    }

    private static class ParcelableSparseArray extends SparseArray<Parcelable> implements Parcelable {
        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            final int count = size();
            dest.writeInt(count);
            for (int i = 0; i < count; i++) {
                dest.writeInt(keyAt(i));
                dest.writeParcelable(valueAt(i), 0);
            }
        }

        public static final Parcelable.Creator<ParcelableSparseArray> CREATOR =
                new Parcelable.Creator<ParcelableSparseArray>() {
                    public ParcelableSparseArray createFromParcel(Parcel source) {
                        final ParcelableSparseArray array = new ParcelableSparseArray();
                        final ClassLoader loader = array.getClass().getClassLoader();
                        final int count = source.readInt();
                        for (int i = 0; i < count; i++) {
                            array.put(source.readInt(), source.readParcelable(loader));
                        }
                        return array;
                    }

                    public ParcelableSparseArray[] newArray(int size) {
                        return new ParcelableSparseArray[size];
                    }
                };
    }
}
