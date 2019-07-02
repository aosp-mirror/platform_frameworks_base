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

import android.annotation.ColorInt;
import android.annotation.DimenRes;
import android.annotation.IntDef;
import android.annotation.LayoutRes;
import android.annotation.NonNull;
import android.annotation.StyleRes;
import android.annotation.UnsupportedAppUsage;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.ActivityThread;
import android.app.Application;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.StrictMode;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.LayoutInflater.Filter;
import android.view.RemotableViewMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.AdapterView.OnItemClickListener;

import com.android.internal.R;
import com.android.internal.util.ContrastColorUtil;
import com.android.internal.util.Preconditions;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * A class that describes a view hierarchy that can be displayed in
 * another process. The hierarchy is inflated from a layout resource
 * file, and this class provides some basic operations for modifying
 * the content of the inflated hierarchy.
 *
 * <p>{@code RemoteViews} is limited to support for the following layouts:</p>
 * <ul>
 *   <li>{@link android.widget.AdapterViewFlipper}</li>
 *   <li>{@link android.widget.FrameLayout}</li>
 *   <li>{@link android.widget.GridLayout}</li>
 *   <li>{@link android.widget.GridView}</li>
 *   <li>{@link android.widget.LinearLayout}</li>
 *   <li>{@link android.widget.ListView}</li>
 *   <li>{@link android.widget.RelativeLayout}</li>
 *   <li>{@link android.widget.StackView}</li>
 *   <li>{@link android.widget.ViewFlipper}</li>
 * </ul>
 * <p>And the following widgets:</p>
 * <ul>
 *   <li>{@link android.widget.AnalogClock}</li>
 *   <li>{@link android.widget.Button}</li>
 *   <li>{@link android.widget.Chronometer}</li>
 *   <li>{@link android.widget.ImageButton}</li>
 *   <li>{@link android.widget.ImageView}</li>
 *   <li>{@link android.widget.ProgressBar}</li>
 *   <li>{@link android.widget.TextClock}</li>
 *   <li>{@link android.widget.TextView}</li>
 * </ul>
 * <p>Descendants of these classes are not supported.</p>
 */
public class RemoteViews implements Parcelable, Filter {

    private static final String LOG_TAG = "RemoteViews";

    /**
     * The intent extra that contains the appWidgetId.
     * @hide
     */
    static final String EXTRA_REMOTEADAPTER_APPWIDGET_ID = "remoteAdapterAppWidgetId";

    /**
     * The intent extra that contains {@code true} if inflating as dak text theme.
     * @hide
     */
    static final String EXTRA_REMOTEADAPTER_ON_LIGHT_BACKGROUND = "remoteAdapterOnLightBackground";

    /**
     * The intent extra that contains the bounds for all shared elements.
     */
    public static final String EXTRA_SHARED_ELEMENT_BOUNDS =
            "android.widget.extra.SHARED_ELEMENT_BOUNDS";

    /**
     * Maximum depth of nested views calls from {@link #addView(int, RemoteViews)} and
     * {@link #RemoteViews(RemoteViews, RemoteViews)}.
     */
    private static final int MAX_NESTED_VIEWS = 10;

    // The unique identifiers for each custom {@link Action}.
    private static final int SET_ON_CLICK_RESPONSE_TAG = 1;
    private static final int REFLECTION_ACTION_TAG = 2;
    private static final int SET_DRAWABLE_TINT_TAG = 3;
    private static final int VIEW_GROUP_ACTION_ADD_TAG = 4;
    private static final int VIEW_CONTENT_NAVIGATION_TAG = 5;
    private static final int SET_EMPTY_VIEW_ACTION_TAG = 6;
    private static final int VIEW_GROUP_ACTION_REMOVE_TAG = 7;
    private static final int SET_PENDING_INTENT_TEMPLATE_TAG = 8;
    private static final int SET_REMOTE_VIEW_ADAPTER_INTENT_TAG = 10;
    private static final int TEXT_VIEW_DRAWABLE_ACTION_TAG = 11;
    private static final int BITMAP_REFLECTION_ACTION_TAG = 12;
    private static final int TEXT_VIEW_SIZE_ACTION_TAG = 13;
    private static final int VIEW_PADDING_ACTION_TAG = 14;
    private static final int SET_REMOTE_VIEW_ADAPTER_LIST_TAG = 15;
    private static final int SET_REMOTE_INPUTS_ACTION_TAG = 18;
    private static final int LAYOUT_PARAM_ACTION_TAG = 19;
    private static final int OVERRIDE_TEXT_COLORS_TAG = 20;
    private static final int SET_RIPPLE_DRAWABLE_COLOR_TAG = 21;
    private static final int SET_INT_TAG_TAG = 22;

    /** @hide **/
    @IntDef(flag = true, value = {
            FLAG_REAPPLY_DISALLOWED,
            FLAG_WIDGET_IS_COLLECTION_CHILD,
            FLAG_USE_LIGHT_BACKGROUND_LAYOUT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ApplyFlags {}
    /**
     * Whether reapply is disallowed on this remoteview. This maybe be true if some actions modify
     * the layout in a way that isn't recoverable, since views are being removed.
     * @hide
     */
    public static final int FLAG_REAPPLY_DISALLOWED = 1;
    /**
     * This flag indicates whether this RemoteViews object is being created from a
     * RemoteViewsService for use as a child of a widget collection. This flag is used
     * to determine whether or not certain features are available, in particular,
     * setting on click extras and setting on click pending intents. The former is enabled,
     * and the latter disabled when this flag is true.
     * @hide
     */
    public static final int FLAG_WIDGET_IS_COLLECTION_CHILD = 2;
    /**
     * When this flag is set, the views is inflated with {@link #mLightBackgroundLayoutId} instead
     * of {link #mLayoutId}
     * @hide
     */
    public static final int FLAG_USE_LIGHT_BACKGROUND_LAYOUT = 4;

    /**
     * Application that hosts the remote views.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public ApplicationInfo mApplication;

    /**
     * The resource ID of the layout file. (Added to the parcel)
     */
    @UnsupportedAppUsage
    private final int mLayoutId;

    /**
     * The resource ID of the layout file in dark text mode. (Added to the parcel)
     */
    private int mLightBackgroundLayoutId = 0;

    /**
     * An array of actions to perform on the view tree once it has been
     * inflated
     */
    @UnsupportedAppUsage
    private ArrayList<Action> mActions;

    /**
     * Maps bitmaps to unique indicies to avoid Bitmap duplication.
     */
    @UnsupportedAppUsage
    private BitmapCache mBitmapCache;

    /**
     * Indicates whether or not this RemoteViews object is contained as a child of any other
     * RemoteViews.
     */
    private boolean mIsRoot = true;

    /**
     * Constants to whether or not this RemoteViews is composed of a landscape and portrait
     * RemoteViews.
     */
    private static final int MODE_NORMAL = 0;
    private static final int MODE_HAS_LANDSCAPE_AND_PORTRAIT = 1;

    /**
     * Used in conjunction with the special constructor
     * {@link #RemoteViews(RemoteViews, RemoteViews)} to keep track of the landscape and portrait
     * RemoteViews.
     */
    private RemoteViews mLandscape = null;
    @UnsupportedAppUsage
    private RemoteViews mPortrait = null;

    @ApplyFlags
    private int mApplyFlags = 0;

    /** Class cookies of the Parcel this instance was read from. */
    private final Map<Class, Object> mClassCookies;

    private static final OnClickHandler DEFAULT_ON_CLICK_HANDLER = (view, pendingIntent, response)
            -> startPendingIntent(view, pendingIntent, response.getLaunchOptions(view));

    private static final ArrayMap<MethodKey, MethodArgs> sMethods = new ArrayMap<>();

    /**
     * This key is used to perform lookups in sMethods without causing allocations.
     */
    private static final MethodKey sLookupKey = new MethodKey();

    /**
     * @hide
     */
    public void setRemoteInputs(int viewId, RemoteInput[] remoteInputs) {
        mActions.add(new SetRemoteInputsAction(viewId, remoteInputs));
    }

    /**
     * Reduces all images and ensures that they are all below the given sizes.
     *
     * @param maxWidth the maximum width allowed
     * @param maxHeight the maximum height allowed
     *
     * @hide
     */
    public void reduceImageSizes(int maxWidth, int maxHeight) {
        ArrayList<Bitmap> cache = mBitmapCache.mBitmaps;
        for (int i = 0; i < cache.size(); i++) {
            Bitmap bitmap = cache.get(i);
            cache.set(i, Icon.scaleDownIfNecessary(bitmap, maxWidth, maxHeight));
        }
    }

    /**
     * Override all text colors in this layout and replace them by the given text color.
     *
     * @param textColor The color to use.
     *
     * @hide
     */
    public void overrideTextColors(int textColor) {
        addAction(new OverrideTextColorsAction(textColor));
    }

    /**
     * Sets an integer tag to the view.
     *
     * @hide
     */
    public void setIntTag(int viewId, int key, int tag) {
        addAction(new SetIntTagAction(viewId, key, tag));
    }

    /**
     * Set that it is disallowed to reapply another remoteview with the same layout as this view.
     * This should be done if an action is destroying the view tree of the base layout.
     *
     * @hide
     */
    public void addFlags(@ApplyFlags int flags) {
        mApplyFlags = mApplyFlags | flags;
    }

    /**
     * @hide
     */
    public boolean hasFlags(@ApplyFlags int flag) {
        return (mApplyFlags & flag) == flag;
    }

    /**
     * Stores information related to reflection method lookup.
     */
    static class MethodKey {
        public Class targetClass;
        public Class paramClass;
        public String methodName;

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof MethodKey)) {
                return false;
            }
            MethodKey p = (MethodKey) o;
            return Objects.equals(p.targetClass, targetClass)
                    && Objects.equals(p.paramClass, paramClass)
                    && Objects.equals(p.methodName, methodName);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(targetClass) ^ Objects.hashCode(paramClass)
                    ^ Objects.hashCode(methodName);
        }

        public void set(Class targetClass, Class paramClass, String methodName) {
            this.targetClass = targetClass;
            this.paramClass = paramClass;
            this.methodName = methodName;
        }
    }


    /**
     * Stores information related to reflection method lookup result.
     */
    static class MethodArgs {
        public MethodHandle syncMethod;
        public MethodHandle asyncMethod;
        public String asyncMethodName;
    }

    /**
     * This annotation indicates that a subclass of View is allowed to be used
     * with the {@link RemoteViews} mechanism.
     */
    @Target({ ElementType.TYPE })
    @Retention(RetentionPolicy.RUNTIME)
    public @interface RemoteView {
    }

    /**
     * Exception to send when something goes wrong executing an action
     *
     */
    public static class ActionException extends RuntimeException {
        public ActionException(Exception ex) {
            super(ex);
        }
        public ActionException(String message) {
            super(message);
        }
        /**
         * @hide
         */
        public ActionException(Throwable t) {
            super(t);
        }
    }

    /** @hide */
    public interface OnClickHandler {

        /** @hide */
        boolean onClickHandler(View view, PendingIntent pendingIntent, RemoteResponse response);
    }

    /**
     * Base class for all actions that can be performed on an
     * inflated view.
     *
     *  SUBCLASSES MUST BE IMMUTABLE SO CLONE WORKS!!!!!
     */
    private abstract static class Action implements Parcelable {
        public abstract void apply(View root, ViewGroup rootParent,
                OnClickHandler handler) throws ActionException;

        public static final int MERGE_REPLACE = 0;
        public static final int MERGE_APPEND = 1;
        public static final int MERGE_IGNORE = 2;

        public int describeContents() {
            return 0;
        }

        public void setBitmapCache(BitmapCache bitmapCache) {
            // Do nothing
        }

        @UnsupportedAppUsage
        public int mergeBehavior() {
            return MERGE_REPLACE;
        }

        public abstract int getActionTag();

        public String getUniqueKey() {
            return (getActionTag() + "_" + viewId);
        }

        /**
         * This is called on the background thread. It should perform any non-ui computations
         * and return the final action which will run on the UI thread.
         * Override this if some of the tasks can be performed async.
         */
        public Action initActionAsync(ViewTree root, ViewGroup rootParent, OnClickHandler handler) {
            return this;
        }

        public boolean prefersAsyncApply() {
            return false;
        }

        /**
         * Overridden by subclasses which have (or inherit) an ApplicationInfo instance
         * as member variable
         */
        public boolean hasSameAppInfo(ApplicationInfo parentInfo) {
            return true;
        }

        public void visitUris(@NonNull Consumer<Uri> visitor) {
            // Nothing to visit by default
        }

        @UnsupportedAppUsage
        int viewId;
    }

    /**
     * Action class used during async inflation of RemoteViews. Subclasses are not parcelable.
     */
    private static abstract class RuntimeAction extends Action {
        @Override
        public final int getActionTag() {
            return 0;
        }

        @Override
        public final void writeToParcel(Parcel dest, int flags) {
            throw new UnsupportedOperationException();
        }
    }

    // Constant used during async execution. It is not parcelable.
    private static final Action ACTION_NOOP = new RuntimeAction() {
        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) { }
    };

    /**
     * Merges the passed RemoteViews actions with this RemoteViews actions according to
     * action-specific merge rules.
     *
     * @param newRv
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void mergeRemoteViews(RemoteViews newRv) {
        if (newRv == null) return;
        // We first copy the new RemoteViews, as the process of merging modifies the way the actions
        // reference the bitmap cache. We don't want to modify the object as it may need to
        // be merged and applied multiple times.
        RemoteViews copy = new RemoteViews(newRv);

        HashMap<String, Action> map = new HashMap<String, Action>();
        if (mActions == null) {
            mActions = new ArrayList<Action>();
        }

        int count = mActions.size();
        for (int i = 0; i < count; i++) {
            Action a = mActions.get(i);
            map.put(a.getUniqueKey(), a);
        }

        ArrayList<Action> newActions = copy.mActions;
        if (newActions == null) return;
        count = newActions.size();
        for (int i = 0; i < count; i++) {
            Action a = newActions.get(i);
            String key = newActions.get(i).getUniqueKey();
            int mergeBehavior = newActions.get(i).mergeBehavior();
            if (map.containsKey(key) && mergeBehavior == Action.MERGE_REPLACE) {
                mActions.remove(map.get(key));
                map.remove(key);
            }

            // If the merge behavior is ignore, we don't bother keeping the extra action
            if (mergeBehavior == Action.MERGE_REPLACE || mergeBehavior == Action.MERGE_APPEND) {
                mActions.add(a);
            }
        }

        // Because pruning can remove the need for bitmaps, we reconstruct the bitmap cache
        mBitmapCache = new BitmapCache();
        setBitmapCache(mBitmapCache);
    }

    /**
     * Note all {@link Uri} that are referenced internally, with the expectation
     * that Uri permission grants will need to be issued to ensure the recipient
     * of this object is able to render its contents.
     *
     * @hide
     */
    public void visitUris(@NonNull Consumer<Uri> visitor) {
        if (mActions != null) {
            for (int i = 0; i < mActions.size(); i++) {
                mActions.get(i).visitUris(visitor);
            }
        }
    }

    private static void visitIconUri(Icon icon, @NonNull Consumer<Uri> visitor) {
        if (icon != null && icon.getType() == Icon.TYPE_URI) {
            visitor.accept(icon.getUri());
        }
    }

    private static class RemoteViewsContextWrapper extends ContextWrapper {
        private final Context mContextForResources;

        RemoteViewsContextWrapper(Context context, Context contextForResources) {
            super(context);
            mContextForResources = contextForResources;
        }

        @Override
        public Resources getResources() {
            return mContextForResources.getResources();
        }

        @Override
        public Resources.Theme getTheme() {
            return mContextForResources.getTheme();
        }

        @Override
        public String getPackageName() {
            return mContextForResources.getPackageName();
        }
    }

    private class SetEmptyView extends Action {
        int emptyViewId;

        SetEmptyView(int viewId, int emptyViewId) {
            this.viewId = viewId;
            this.emptyViewId = emptyViewId;
        }

        SetEmptyView(Parcel in) {
            this.viewId = in.readInt();
            this.emptyViewId = in.readInt();
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(this.viewId);
            out.writeInt(this.emptyViewId);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View view = root.findViewById(viewId);
            if (!(view instanceof AdapterView<?>)) return;

            AdapterView<?> adapterView = (AdapterView<?>) view;

            final View emptyView = root.findViewById(emptyViewId);
            if (emptyView == null) return;

            adapterView.setEmptyView(emptyView);
        }

        @Override
        public int getActionTag() {
            return SET_EMPTY_VIEW_ACTION_TAG;
        }
    }

    private class SetPendingIntentTemplate extends Action {
        public SetPendingIntentTemplate(int id, PendingIntent pendingIntentTemplate) {
            this.viewId = id;
            this.pendingIntentTemplate = pendingIntentTemplate;
        }

        public SetPendingIntentTemplate(Parcel parcel) {
            viewId = parcel.readInt();
            pendingIntentTemplate = PendingIntent.readPendingIntentOrNullFromParcel(parcel);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            PendingIntent.writePendingIntentOrNullToParcel(pendingIntentTemplate, dest);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, final OnClickHandler handler) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            // If the view isn't an AdapterView, setting a PendingIntent template doesn't make sense
            if (target instanceof AdapterView<?>) {
                AdapterView<?> av = (AdapterView<?>) target;
                // The PendingIntent template is stored in the view's tag.
                OnItemClickListener listener = new OnItemClickListener() {
                    public void onItemClick(AdapterView<?> parent, View view,
                            int position, long id) {
                        // The view should be a frame layout
                        if (view instanceof ViewGroup) {
                            ViewGroup vg = (ViewGroup) view;

                            // AdapterViews contain their children in a frame
                            // so we need to go one layer deeper here.
                            if (parent instanceof AdapterViewAnimator) {
                                vg = (ViewGroup) vg.getChildAt(0);
                            }
                            if (vg == null) return;

                            RemoteResponse response = null;
                            int childCount = vg.getChildCount();
                            for (int i = 0; i < childCount; i++) {
                                Object tag = vg.getChildAt(i).getTag(com.android.internal.R.id.fillInIntent);
                                if (tag instanceof RemoteResponse) {
                                    response = (RemoteResponse) tag;
                                    break;
                                }
                            }
                            if (response == null) return;
                            response.handleViewClick(view, handler);
                        }
                    }
                };
                av.setOnItemClickListener(listener);
                av.setTag(pendingIntentTemplate);
            } else {
                Log.e(LOG_TAG, "Cannot setPendingIntentTemplate on a view which is not" +
                        "an AdapterView (id: " + viewId + ")");
                return;
            }
        }

        @Override
        public int getActionTag() {
            return SET_PENDING_INTENT_TEMPLATE_TAG;
        }

        @UnsupportedAppUsage
        PendingIntent pendingIntentTemplate;
    }

    private class SetRemoteViewsAdapterList extends Action {
        public SetRemoteViewsAdapterList(int id, ArrayList<RemoteViews> list, int viewTypeCount) {
            this.viewId = id;
            this.list = list;
            this.viewTypeCount = viewTypeCount;
        }

        public SetRemoteViewsAdapterList(Parcel parcel) {
            viewId = parcel.readInt();
            viewTypeCount = parcel.readInt();
            list = parcel.createTypedArrayList(RemoteViews.CREATOR);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeInt(viewTypeCount);
            dest.writeTypedList(list, flags);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            // Ensure that we are applying to an AppWidget root
            if (!(rootParent instanceof AppWidgetHostView)) {
                Log.e(LOG_TAG, "SetRemoteViewsAdapterIntent action can only be used for " +
                        "AppWidgets (root id: " + viewId + ")");
                return;
            }
            // Ensure that we are calling setRemoteAdapter on an AdapterView that supports it
            if (!(target instanceof AbsListView) && !(target instanceof AdapterViewAnimator)) {
                Log.e(LOG_TAG, "Cannot setRemoteViewsAdapter on a view which is not " +
                        "an AbsListView or AdapterViewAnimator (id: " + viewId + ")");
                return;
            }

            if (target instanceof AbsListView) {
                AbsListView v = (AbsListView) target;
                Adapter a = v.getAdapter();
                if (a instanceof RemoteViewsListAdapter && viewTypeCount <= a.getViewTypeCount()) {
                    ((RemoteViewsListAdapter) a).setViewsList(list);
                } else {
                    v.setAdapter(new RemoteViewsListAdapter(v.getContext(), list, viewTypeCount));
                }
            } else if (target instanceof AdapterViewAnimator) {
                AdapterViewAnimator v = (AdapterViewAnimator) target;
                Adapter a = v.getAdapter();
                if (a instanceof RemoteViewsListAdapter && viewTypeCount <= a.getViewTypeCount()) {
                    ((RemoteViewsListAdapter) a).setViewsList(list);
                } else {
                    v.setAdapter(new RemoteViewsListAdapter(v.getContext(), list, viewTypeCount));
                }
            }
        }

        @Override
        public int getActionTag() {
            return SET_REMOTE_VIEW_ADAPTER_LIST_TAG;
        }

        int viewTypeCount;
        ArrayList<RemoteViews> list;
    }

    private class SetRemoteViewsAdapterIntent extends Action {
        public SetRemoteViewsAdapterIntent(int id, Intent intent) {
            this.viewId = id;
            this.intent = intent;
        }

        public SetRemoteViewsAdapterIntent(Parcel parcel) {
            viewId = parcel.readInt();
            intent = parcel.readTypedObject(Intent.CREATOR);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeTypedObject(intent, flags);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            // Ensure that we are applying to an AppWidget root
            if (!(rootParent instanceof AppWidgetHostView)) {
                Log.e(LOG_TAG, "SetRemoteViewsAdapterIntent action can only be used for " +
                        "AppWidgets (root id: " + viewId + ")");
                return;
            }
            // Ensure that we are calling setRemoteAdapter on an AdapterView that supports it
            if (!(target instanceof AbsListView) && !(target instanceof AdapterViewAnimator)) {
                Log.e(LOG_TAG, "Cannot setRemoteViewsAdapter on a view which is not " +
                        "an AbsListView or AdapterViewAnimator (id: " + viewId + ")");
                return;
            }

            // Embed the AppWidget Id for use in RemoteViewsAdapter when connecting to the intent
            // RemoteViewsService
            AppWidgetHostView host = (AppWidgetHostView) rootParent;
            intent.putExtra(EXTRA_REMOTEADAPTER_APPWIDGET_ID, host.getAppWidgetId())
                    .putExtra(EXTRA_REMOTEADAPTER_ON_LIGHT_BACKGROUND,
                            hasFlags(FLAG_USE_LIGHT_BACKGROUND_LAYOUT));

            if (target instanceof AbsListView) {
                AbsListView v = (AbsListView) target;
                v.setRemoteViewsAdapter(intent, isAsync);
                v.setRemoteViewsOnClickHandler(handler);
            } else if (target instanceof AdapterViewAnimator) {
                AdapterViewAnimator v = (AdapterViewAnimator) target;
                v.setRemoteViewsAdapter(intent, isAsync);
                v.setRemoteViewsOnClickHandler(handler);
            }
        }

        @Override
        public Action initActionAsync(ViewTree root, ViewGroup rootParent,
                OnClickHandler handler) {
            SetRemoteViewsAdapterIntent copy = new SetRemoteViewsAdapterIntent(viewId, intent);
            copy.isAsync = true;
            return copy;
        }

        @Override
        public int getActionTag() {
            return SET_REMOTE_VIEW_ADAPTER_INTENT_TAG;
        }

        Intent intent;
        boolean isAsync = false;
    }

    /**
     * Equivalent to calling
     * {@link android.view.View#setOnClickListener(android.view.View.OnClickListener)}
     * to launch the provided {@link PendingIntent}.
     */
    private class SetOnClickResponse extends Action {

        SetOnClickResponse(int id, RemoteResponse response) {
            this.viewId = id;
            this.mResponse = response;
        }

        SetOnClickResponse(Parcel parcel) {
            viewId = parcel.readInt();
            mResponse = new RemoteResponse();
            mResponse.readFromParcel(parcel);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            mResponse.writeToParcel(dest, flags);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, final OnClickHandler handler) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            if (mResponse.mPendingIntent != null) {
                // If the view is an AdapterView, setting a PendingIntent on click doesn't make
                // much sense, do they mean to set a PendingIntent template for the
                // AdapterView's children?
                if (hasFlags(FLAG_WIDGET_IS_COLLECTION_CHILD)) {
                    Log.w(LOG_TAG, "Cannot SetOnClickResponse for collection item "
                            + "(id: " + viewId + ")");
                    ApplicationInfo appInfo = root.getContext().getApplicationInfo();

                    // We let this slide for HC and ICS so as to not break compatibility. It should
                    // have been disabled from the outset, but was left open by accident.
                    if (appInfo != null
                            && appInfo.targetSdkVersion >= Build.VERSION_CODES.JELLY_BEAN) {
                        return;
                    }
                }
                target.setTagInternal(R.id.pending_intent_tag, mResponse.mPendingIntent);
            } else if (mResponse.mFillIntent != null) {
                if (!hasFlags(FLAG_WIDGET_IS_COLLECTION_CHILD)) {
                    Log.e(LOG_TAG, "The method setOnClickFillInIntent is available "
                            + "only from RemoteViewsFactory (ie. on collection items).");
                    return;
                }
                if (target == root) {
                    // Target is a root node of an AdapterView child. Set the response in the tag.
                    // Actual click handling is done by OnItemClickListener in
                    // SetPendingIntentTemplate, which uses this tag information.
                    target.setTagInternal(com.android.internal.R.id.fillInIntent, mResponse);
                    return;
                }
            } else {
                // No intent to apply
                target.setOnClickListener(null);
                return;
            }
            target.setOnClickListener(v -> mResponse.handleViewClick(v, handler));
        }

        @Override
        public int getActionTag() {
            return SET_ON_CLICK_RESPONSE_TAG;
        }

        final RemoteResponse mResponse;
    }

    /** @hide **/
    public static Rect getSourceBounds(View v) {
        final float appScale = v.getContext().getResources()
                .getCompatibilityInfo().applicationScale;
        final int[] pos = new int[2];
        v.getLocationOnScreen(pos);

        final Rect rect = new Rect();
        rect.left = (int) (pos[0] * appScale + 0.5f);
        rect.top = (int) (pos[1] * appScale + 0.5f);
        rect.right = (int) ((pos[0] + v.getWidth()) * appScale + 0.5f);
        rect.bottom = (int) ((pos[1] + v.getHeight()) * appScale + 0.5f);
        return rect;
    }

    private MethodHandle getMethod(View view, String methodName, Class<?> paramType,
            boolean async) {
        MethodArgs result;
        Class<? extends View> klass = view.getClass();

        synchronized (sMethods) {
            // The key is defined by the view class, param class and method name.
            sLookupKey.set(klass, paramType, methodName);
            result = sMethods.get(sLookupKey);

            if (result == null) {
                Method method;
                try {
                    if (paramType == null) {
                        method = klass.getMethod(methodName);
                    } else {
                        method = klass.getMethod(methodName, paramType);
                    }
                    if (!method.isAnnotationPresent(RemotableViewMethod.class)) {
                        throw new ActionException("view: " + klass.getName()
                                + " can't use method with RemoteViews: "
                                + methodName + getParameters(paramType));
                    }

                    result = new MethodArgs();
                    result.syncMethod = MethodHandles.publicLookup().unreflect(method);
                    result.asyncMethodName =
                            method.getAnnotation(RemotableViewMethod.class).asyncImpl();
                } catch (NoSuchMethodException | IllegalAccessException ex) {
                    throw new ActionException("view: " + klass.getName() + " doesn't have method: "
                            + methodName + getParameters(paramType));
                }

                MethodKey key = new MethodKey();
                key.set(klass, paramType, methodName);
                sMethods.put(key, result);
            }

            if (!async) {
                return result.syncMethod;
            }
            // Check this so see if async method is implemented or not.
            if (result.asyncMethodName.isEmpty()) {
                return null;
            }
            // Async method is lazily loaded. If it is not yet loaded, load now.
            if (result.asyncMethod == null) {
                MethodType asyncType = result.syncMethod.type()
                        .dropParameterTypes(0, 1).changeReturnType(Runnable.class);
                try {
                    result.asyncMethod = MethodHandles.publicLookup().findVirtual(
                            klass, result.asyncMethodName, asyncType);
                } catch (NoSuchMethodException | IllegalAccessException ex) {
                    throw new ActionException("Async implementation declared as "
                            + result.asyncMethodName + " but not defined for " + methodName
                            + ": public Runnable " + result.asyncMethodName + " ("
                            + TextUtils.join(",", asyncType.parameterArray()) + ")");
                }
            }
            return result.asyncMethod;
        }
    }

    private static String getParameters(Class<?> paramType) {
        if (paramType == null) return "()";
        return "(" + paramType + ")";
    }

    /**
     * Equivalent to calling
     * {@link Drawable#setColorFilter(int, android.graphics.PorterDuff.Mode)},
     * on the {@link Drawable} of a given view.
     * <p>
     * The operation will be performed on the {@link Drawable} returned by the
     * target {@link View#getBackground()} by default.  If targetBackground is false,
     * we assume the target is an {@link ImageView} and try applying the operations
     * to {@link ImageView#getDrawable()}.
     * <p>
     */
    private class SetDrawableTint extends Action {
        SetDrawableTint(int id, boolean targetBackground,
                int colorFilter, @NonNull PorterDuff.Mode mode) {
            this.viewId = id;
            this.targetBackground = targetBackground;
            this.colorFilter = colorFilter;
            this.filterMode = mode;
        }

        SetDrawableTint(Parcel parcel) {
            viewId = parcel.readInt();
            targetBackground = parcel.readInt() != 0;
            colorFilter = parcel.readInt();
            filterMode = PorterDuff.intToMode(parcel.readInt());
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeInt(targetBackground ? 1 : 0);
            dest.writeInt(colorFilter);
            dest.writeInt(PorterDuff.modeToInt(filterMode));
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            // Pick the correct drawable to modify for this view
            Drawable targetDrawable = null;
            if (targetBackground) {
                targetDrawable = target.getBackground();
            } else if (target instanceof ImageView) {
                ImageView imageView = (ImageView) target;
                targetDrawable = imageView.getDrawable();
            }

            if (targetDrawable != null) {
                targetDrawable.mutate().setColorFilter(colorFilter, filterMode);
            }
        }

        @Override
        public int getActionTag() {
            return SET_DRAWABLE_TINT_TAG;
        }

        boolean targetBackground;
        int colorFilter;
        PorterDuff.Mode filterMode;
    }

    /**
     * Equivalent to calling
     * {@link RippleDrawable#setColor(ColorStateList)},
     * on the {@link Drawable} of a given view.
     * <p>
     * The operation will be performed on the {@link Drawable} returned by the
     * target {@link View#getBackground()}.
     * <p>
     */
    private class SetRippleDrawableColor extends Action {

        ColorStateList mColorStateList;

        SetRippleDrawableColor(int id, ColorStateList colorStateList) {
            this.viewId = id;
            this.mColorStateList = colorStateList;
        }

        SetRippleDrawableColor(Parcel parcel) {
            viewId = parcel.readInt();
            mColorStateList = parcel.readParcelable(null);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeParcelable(mColorStateList, 0);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            // Pick the correct drawable to modify for this view
            Drawable targetDrawable = target.getBackground();

            if (targetDrawable instanceof RippleDrawable) {
                ((RippleDrawable) targetDrawable.mutate()).setColor(mColorStateList);
            }
        }

        @Override
        public int getActionTag() {
            return SET_RIPPLE_DRAWABLE_COLOR_TAG;
        }
    }

    private final class ViewContentNavigation extends Action {
        final boolean mNext;

        ViewContentNavigation(int viewId, boolean next) {
            this.viewId = viewId;
            this.mNext = next;
        }

        ViewContentNavigation(Parcel in) {
            this.viewId = in.readInt();
            this.mNext = in.readBoolean();
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(this.viewId);
            out.writeBoolean(this.mNext);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View view = root.findViewById(viewId);
            if (view == null) return;

            try {
                getMethod(view,
                        mNext ? "showNext" : "showPrevious", null, false /* async */).invoke(view);
            } catch (Throwable ex) {
                throw new ActionException(ex);
            }
        }

        public int mergeBehavior() {
            return MERGE_IGNORE;
        }

        @Override
        public int getActionTag() {
            return VIEW_CONTENT_NAVIGATION_TAG;
        }
    }

    private static class BitmapCache {

        @UnsupportedAppUsage
        ArrayList<Bitmap> mBitmaps;
        int mBitmapMemory = -1;

        public BitmapCache() {
            mBitmaps = new ArrayList<>();
        }

        public BitmapCache(Parcel source) {
            mBitmaps = source.createTypedArrayList(Bitmap.CREATOR);
        }

        public int getBitmapId(Bitmap b) {
            if (b == null) {
                return -1;
            } else {
                if (mBitmaps.contains(b)) {
                    return mBitmaps.indexOf(b);
                } else {
                    mBitmaps.add(b);
                    mBitmapMemory = -1;
                    return (mBitmaps.size() - 1);
                }
            }
        }

        public Bitmap getBitmapForId(int id) {
            if (id == -1 || id >= mBitmaps.size()) {
                return null;
            } else {
                return mBitmaps.get(id);
            }
        }

        public void writeBitmapsToParcel(Parcel dest, int flags) {
            dest.writeTypedList(mBitmaps, flags);
        }

        public int getBitmapMemory() {
            if (mBitmapMemory < 0) {
                mBitmapMemory = 0;
                int count = mBitmaps.size();
                for (int i = 0; i < count; i++) {
                    mBitmapMemory += mBitmaps.get(i).getAllocationByteCount();
                }
            }
            return mBitmapMemory;
        }
    }

    private class BitmapReflectionAction extends Action {
        int bitmapId;
        @UnsupportedAppUsage
        Bitmap bitmap;
        @UnsupportedAppUsage
        String methodName;

        BitmapReflectionAction(int viewId, String methodName, Bitmap bitmap) {
            this.bitmap = bitmap;
            this.viewId = viewId;
            this.methodName = methodName;
            bitmapId = mBitmapCache.getBitmapId(bitmap);
        }

        BitmapReflectionAction(Parcel in) {
            viewId = in.readInt();
            methodName = in.readString();
            bitmapId = in.readInt();
            bitmap = mBitmapCache.getBitmapForId(bitmapId);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeString(methodName);
            dest.writeInt(bitmapId);
        }

        @Override
        public void apply(View root, ViewGroup rootParent,
                OnClickHandler handler) throws ActionException {
            ReflectionAction ra = new ReflectionAction(viewId, methodName, ReflectionAction.BITMAP,
                    bitmap);
            ra.apply(root, rootParent, handler);
        }

        @Override
        public void setBitmapCache(BitmapCache bitmapCache) {
            bitmapId = bitmapCache.getBitmapId(bitmap);
        }

        @Override
        public int getActionTag() {
            return BITMAP_REFLECTION_ACTION_TAG;
        }
    }

    /**
     * Base class for the reflection actions.
     */
    private final class ReflectionAction extends Action {
        static final int BOOLEAN = 1;
        static final int BYTE = 2;
        static final int SHORT = 3;
        static final int INT = 4;
        static final int LONG = 5;
        static final int FLOAT = 6;
        static final int DOUBLE = 7;
        static final int CHAR = 8;
        static final int STRING = 9;
        static final int CHAR_SEQUENCE = 10;
        static final int URI = 11;
        // BITMAP actions are never stored in the list of actions. They are only used locally
        // to implement BitmapReflectionAction, which eliminates duplicates using BitmapCache.
        static final int BITMAP = 12;
        static final int BUNDLE = 13;
        static final int INTENT = 14;
        static final int COLOR_STATE_LIST = 15;
        static final int ICON = 16;

        @UnsupportedAppUsage
        String methodName;
        int type;
        @UnsupportedAppUsage
        Object value;

        ReflectionAction(int viewId, String methodName, int type, Object value) {
            this.viewId = viewId;
            this.methodName = methodName;
            this.type = type;
            this.value = value;
        }

        ReflectionAction(Parcel in) {
            this.viewId = in.readInt();
            this.methodName = in.readString();
            this.type = in.readInt();
            //noinspection ConstantIfStatement
            if (false) {
                Log.d(LOG_TAG, "read viewId=0x" + Integer.toHexString(this.viewId)
                        + " methodName=" + this.methodName + " type=" + this.type);
            }

            // For some values that may have been null, we first check a flag to see if they were
            // written to the parcel.
            switch (this.type) {
                case BOOLEAN:
                    this.value = in.readBoolean();
                    break;
                case BYTE:
                    this.value = in.readByte();
                    break;
                case SHORT:
                    this.value = (short)in.readInt();
                    break;
                case INT:
                    this.value = in.readInt();
                    break;
                case LONG:
                    this.value = in.readLong();
                    break;
                case FLOAT:
                    this.value = in.readFloat();
                    break;
                case DOUBLE:
                    this.value = in.readDouble();
                    break;
                case CHAR:
                    this.value = (char)in.readInt();
                    break;
                case STRING:
                    this.value = in.readString();
                    break;
                case CHAR_SEQUENCE:
                    this.value = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
                    break;
                case URI:
                    this.value = in.readTypedObject(Uri.CREATOR);
                    break;
                case BITMAP:
                    this.value = in.readTypedObject(Bitmap.CREATOR);
                    break;
                case BUNDLE:
                    this.value = in.readBundle();
                    break;
                case INTENT:
                    this.value = in.readTypedObject(Intent.CREATOR);
                    break;
                case COLOR_STATE_LIST:
                    this.value = in.readTypedObject(ColorStateList.CREATOR);
                    break;
                case ICON:
                    this.value = in.readTypedObject(Icon.CREATOR);
                default:
                    break;
            }
        }

        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(this.viewId);
            out.writeString(this.methodName);
            out.writeInt(this.type);
            //noinspection ConstantIfStatement
            if (false) {
                Log.d(LOG_TAG, "write viewId=0x" + Integer.toHexString(this.viewId)
                        + " methodName=" + this.methodName + " type=" + this.type);
            }

            // For some values which are null, we record an integer flag to indicate whether
            // we have written a valid value to the parcel.
            switch (this.type) {
                case BOOLEAN:
                    out.writeBoolean((Boolean) this.value);
                    break;
                case BYTE:
                    out.writeByte((Byte) this.value);
                    break;
                case SHORT:
                    out.writeInt((Short) this.value);
                    break;
                case INT:
                    out.writeInt((Integer) this.value);
                    break;
                case LONG:
                    out.writeLong((Long) this.value);
                    break;
                case FLOAT:
                    out.writeFloat((Float) this.value);
                    break;
                case DOUBLE:
                    out.writeDouble((Double) this.value);
                    break;
                case CHAR:
                    out.writeInt((int)((Character)this.value).charValue());
                    break;
                case STRING:
                    out.writeString((String)this.value);
                    break;
                case CHAR_SEQUENCE:
                    TextUtils.writeToParcel((CharSequence)this.value, out, flags);
                    break;
                case BUNDLE:
                    out.writeBundle((Bundle) this.value);
                    break;
                case URI:
                case BITMAP:
                case INTENT:
                case COLOR_STATE_LIST:
                case ICON:
                    out.writeTypedObject((Parcelable) this.value, flags);
                    break;
                default:
                    break;
            }
        }

        private Class<?> getParameterType() {
            switch (this.type) {
                case BOOLEAN:
                    return boolean.class;
                case BYTE:
                    return byte.class;
                case SHORT:
                    return short.class;
                case INT:
                    return int.class;
                case LONG:
                    return long.class;
                case FLOAT:
                    return float.class;
                case DOUBLE:
                    return double.class;
                case CHAR:
                    return char.class;
                case STRING:
                    return String.class;
                case CHAR_SEQUENCE:
                    return CharSequence.class;
                case URI:
                    return Uri.class;
                case BITMAP:
                    return Bitmap.class;
                case BUNDLE:
                    return Bundle.class;
                case INTENT:
                    return Intent.class;
                case COLOR_STATE_LIST:
                    return ColorStateList.class;
                case ICON:
                    return Icon.class;
                default:
                    return null;
            }
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View view = root.findViewById(viewId);
            if (view == null) return;

            Class<?> param = getParameterType();
            if (param == null) {
                throw new ActionException("bad type: " + this.type);
            }
            try {
                getMethod(view, this.methodName, param, false /* async */).invoke(view, this.value);
            } catch (Throwable ex) {
                throw new ActionException(ex);
            }
        }

        @Override
        public Action initActionAsync(ViewTree root, ViewGroup rootParent, OnClickHandler handler) {
            final View view = root.findViewById(viewId);
            if (view == null) return ACTION_NOOP;

            Class<?> param = getParameterType();
            if (param == null) {
                throw new ActionException("bad type: " + this.type);
            }

            try {
                MethodHandle method = getMethod(view, this.methodName, param, true /* async */);

                if (method != null) {
                    Runnable endAction = (Runnable) method.invoke(view, this.value);
                    if (endAction == null) {
                        return ACTION_NOOP;
                    } else {
                        // Special case view stub
                        if (endAction instanceof ViewStub.ViewReplaceRunnable) {
                            root.createTree();
                            // Replace child tree
                            root.findViewTreeById(viewId).replaceView(
                                    ((ViewStub.ViewReplaceRunnable) endAction).view);
                        }
                        return new RunnableAction(endAction);
                    }
                }
            } catch (Throwable ex) {
                throw new ActionException(ex);
            }

            return this;
        }

        public int mergeBehavior() {
            // smoothScrollBy is cumulative, everything else overwites.
            if (methodName.equals("smoothScrollBy")) {
                return MERGE_APPEND;
            } else {
                return MERGE_REPLACE;
            }
        }

        @Override
        public int getActionTag() {
            return REFLECTION_ACTION_TAG;
        }

        @Override
        public String getUniqueKey() {
            // Each type of reflection action corresponds to a setter, so each should be seen as
            // unique from the standpoint of merging.
            return super.getUniqueKey() + this.methodName + this.type;
        }

        @Override
        public boolean prefersAsyncApply() {
            return this.type == URI || this.type == ICON;
        }

        @Override
        public void visitUris(@NonNull Consumer<Uri> visitor) {
            switch (this.type) {
                case URI:
                    final Uri uri = (Uri) this.value;
                    visitor.accept(uri);
                    break;
                case ICON:
                    final Icon icon = (Icon) this.value;
                    visitIconUri(icon, visitor);
                    break;
            }
        }
    }

    /**
     * This is only used for async execution of actions and it not parcelable.
     */
    private static final class RunnableAction extends RuntimeAction {
        private final Runnable mRunnable;

        RunnableAction(Runnable r) {
            mRunnable = r;
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            mRunnable.run();
        }
    }

    private void configureRemoteViewsAsChild(RemoteViews rv) {
        rv.setBitmapCache(mBitmapCache);
        rv.setNotRoot();
    }

    void setNotRoot() {
        mIsRoot = false;
    }

    /**
     * ViewGroup methods that are related to adding Views.
     */
    private class ViewGroupActionAdd extends Action {
        @UnsupportedAppUsage
        private RemoteViews mNestedViews;
        private int mIndex;

        ViewGroupActionAdd(int viewId, RemoteViews nestedViews) {
            this(viewId, nestedViews, -1 /* index */);
        }

        ViewGroupActionAdd(int viewId, RemoteViews nestedViews, int index) {
            this.viewId = viewId;
            mNestedViews = nestedViews;
            mIndex = index;
            if (nestedViews != null) {
                configureRemoteViewsAsChild(nestedViews);
            }
        }

        ViewGroupActionAdd(Parcel parcel, BitmapCache bitmapCache, ApplicationInfo info,
                int depth, Map<Class, Object> classCookies) {
            viewId = parcel.readInt();
            mIndex = parcel.readInt();
            mNestedViews = new RemoteViews(parcel, bitmapCache, info, depth, classCookies);
            mNestedViews.addFlags(mApplyFlags);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeInt(mIndex);
            mNestedViews.writeToParcel(dest, flags);
        }

        @Override
        public boolean hasSameAppInfo(ApplicationInfo parentInfo) {
            return mNestedViews.hasSameAppInfo(parentInfo);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final Context context = root.getContext();
            final ViewGroup target = root.findViewById(viewId);

            if (target == null) {
                return;
            }

            // Inflate nested views and add as children
            target.addView(mNestedViews.apply(context, target, handler), mIndex);
        }

        @Override
        public Action initActionAsync(ViewTree root, ViewGroup rootParent, OnClickHandler handler) {
            // In the async implementation, update the view tree so that subsequent calls to
            // findViewById return the current view.
            root.createTree();
            ViewTree target = root.findViewTreeById(viewId);
            if ((target == null) || !(target.mRoot instanceof ViewGroup)) {
                return ACTION_NOOP;
            }
            final ViewGroup targetVg = (ViewGroup) target.mRoot;

            // Inflate nested views and perform all the async tasks for the child remoteView.
            final Context context = root.mRoot.getContext();
            final AsyncApplyTask task = mNestedViews.getAsyncApplyTask(
                    context, targetVg, null, handler);
            final ViewTree tree = task.doInBackground();

            if (tree == null) {
                throw new ActionException(task.mError);
            }

            // Update the global view tree, so that next call to findViewTreeById
            // goes through the subtree as well.
            target.addChild(tree, mIndex);

            return new RuntimeAction() {
                @Override
                public void apply(View root, ViewGroup rootParent, OnClickHandler handler)
                        throws ActionException {
                    task.onPostExecute(tree);
                    targetVg.addView(task.mResult, mIndex);
                }
            };
        }

        @Override
        public void setBitmapCache(BitmapCache bitmapCache) {
            mNestedViews.setBitmapCache(bitmapCache);
        }

        @Override
        public int mergeBehavior() {
            return MERGE_APPEND;
        }

        @Override
        public boolean prefersAsyncApply() {
            return mNestedViews.prefersAsyncApply();
        }

        @Override
        public int getActionTag() {
            return VIEW_GROUP_ACTION_ADD_TAG;
        }
    }

    /**
     * ViewGroup methods related to removing child views.
     */
    private class ViewGroupActionRemove extends Action {
        /**
         * Id that indicates that all child views of the affected ViewGroup should be removed.
         *
         * <p>Using -2 because the default id is -1. This avoids accidentally matching that.
         */
        private static final int REMOVE_ALL_VIEWS_ID = -2;

        private int mViewIdToKeep;

        ViewGroupActionRemove(int viewId) {
            this(viewId, REMOVE_ALL_VIEWS_ID);
        }

        ViewGroupActionRemove(int viewId, int viewIdToKeep) {
            this.viewId = viewId;
            mViewIdToKeep = viewIdToKeep;
        }

        ViewGroupActionRemove(Parcel parcel) {
            viewId = parcel.readInt();
            mViewIdToKeep = parcel.readInt();
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeInt(mViewIdToKeep);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final ViewGroup target = root.findViewById(viewId);

            if (target == null) {
                return;
            }

            if (mViewIdToKeep == REMOVE_ALL_VIEWS_ID) {
                target.removeAllViews();
                return;
            }

            removeAllViewsExceptIdToKeep(target);
        }

        @Override
        public Action initActionAsync(ViewTree root, ViewGroup rootParent, OnClickHandler handler) {
            // In the async implementation, update the view tree so that subsequent calls to
            // findViewById return the current view.
            root.createTree();
            ViewTree target = root.findViewTreeById(viewId);

            if ((target == null) || !(target.mRoot instanceof ViewGroup)) {
                return ACTION_NOOP;
            }

            final ViewGroup targetVg = (ViewGroup) target.mRoot;

            // Clear all children when nested views omitted
            target.mChildren = null;
            return new RuntimeAction() {
                @Override
                public void apply(View root, ViewGroup rootParent, OnClickHandler handler)
                        throws ActionException {
                    if (mViewIdToKeep == REMOVE_ALL_VIEWS_ID) {
                        targetVg.removeAllViews();
                        return;
                    }

                    removeAllViewsExceptIdToKeep(targetVg);
                }
            };
        }

        /**
         * Iterates through the children in the given ViewGroup and removes all the views that
         * do not have an id of {@link #mViewIdToKeep}.
         */
        private void removeAllViewsExceptIdToKeep(ViewGroup viewGroup) {
            // Otherwise, remove all the views that do not match the id to keep.
            int index = viewGroup.getChildCount() - 1;
            while (index >= 0) {
                if (viewGroup.getChildAt(index).getId() != mViewIdToKeep) {
                    viewGroup.removeViewAt(index);
                }
                index--;
            }
        }

        @Override
        public int getActionTag() {
            return VIEW_GROUP_ACTION_REMOVE_TAG;
        }

        @Override
        public int mergeBehavior() {
            return MERGE_APPEND;
        }
    }

    /**
     * Helper action to set compound drawables on a TextView. Supports relative
     * (s/t/e/b) or cardinal (l/t/r/b) arrangement.
     */
    private class TextViewDrawableAction extends Action {
        public TextViewDrawableAction(int viewId, boolean isRelative, int d1, int d2, int d3, int d4) {
            this.viewId = viewId;
            this.isRelative = isRelative;
            this.useIcons = false;
            this.d1 = d1;
            this.d2 = d2;
            this.d3 = d3;
            this.d4 = d4;
        }

        public TextViewDrawableAction(int viewId, boolean isRelative,
                Icon i1, Icon i2, Icon i3, Icon i4) {
            this.viewId = viewId;
            this.isRelative = isRelative;
            this.useIcons = true;
            this.i1 = i1;
            this.i2 = i2;
            this.i3 = i3;
            this.i4 = i4;
        }

        public TextViewDrawableAction(Parcel parcel) {
            viewId = parcel.readInt();
            isRelative = (parcel.readInt() != 0);
            useIcons = (parcel.readInt() != 0);
            if (useIcons) {
                i1 = parcel.readTypedObject(Icon.CREATOR);
                i2 = parcel.readTypedObject(Icon.CREATOR);
                i3 = parcel.readTypedObject(Icon.CREATOR);
                i4 = parcel.readTypedObject(Icon.CREATOR);
            } else {
                d1 = parcel.readInt();
                d2 = parcel.readInt();
                d3 = parcel.readInt();
                d4 = parcel.readInt();
            }
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeInt(isRelative ? 1 : 0);
            dest.writeInt(useIcons ? 1 : 0);
            if (useIcons) {
                dest.writeTypedObject(i1, 0);
                dest.writeTypedObject(i2, 0);
                dest.writeTypedObject(i3, 0);
                dest.writeTypedObject(i4, 0);
            } else {
                dest.writeInt(d1);
                dest.writeInt(d2);
                dest.writeInt(d3);
                dest.writeInt(d4);
            }
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final TextView target = root.findViewById(viewId);
            if (target == null) return;
            if (drawablesLoaded) {
                if (isRelative) {
                    target.setCompoundDrawablesRelativeWithIntrinsicBounds(id1, id2, id3, id4);
                } else {
                    target.setCompoundDrawablesWithIntrinsicBounds(id1, id2, id3, id4);
                }
            } else if (useIcons) {
                final Context ctx = target.getContext();
                final Drawable id1 = i1 == null ? null : i1.loadDrawable(ctx);
                final Drawable id2 = i2 == null ? null : i2.loadDrawable(ctx);
                final Drawable id3 = i3 == null ? null : i3.loadDrawable(ctx);
                final Drawable id4 = i4 == null ? null : i4.loadDrawable(ctx);
                if (isRelative) {
                    target.setCompoundDrawablesRelativeWithIntrinsicBounds(id1, id2, id3, id4);
                } else {
                    target.setCompoundDrawablesWithIntrinsicBounds(id1, id2, id3, id4);
                }
            } else {
                if (isRelative) {
                    target.setCompoundDrawablesRelativeWithIntrinsicBounds(d1, d2, d3, d4);
                } else {
                    target.setCompoundDrawablesWithIntrinsicBounds(d1, d2, d3, d4);
                }
            }
        }

        @Override
        public Action initActionAsync(ViewTree root, ViewGroup rootParent, OnClickHandler handler) {
            final TextView target = root.findViewById(viewId);
            if (target == null) return ACTION_NOOP;

            TextViewDrawableAction copy = useIcons ?
                    new TextViewDrawableAction(viewId, isRelative, i1, i2, i3, i4) :
                    new TextViewDrawableAction(viewId, isRelative, d1, d2, d3, d4);

            // Load the drawables on the background thread.
            copy.drawablesLoaded = true;
            final Context ctx = target.getContext();

            if (useIcons) {
                copy.id1 = i1 == null ? null : i1.loadDrawable(ctx);
                copy.id2 = i2 == null ? null : i2.loadDrawable(ctx);
                copy.id3 = i3 == null ? null : i3.loadDrawable(ctx);
                copy.id4 = i4 == null ? null : i4.loadDrawable(ctx);
            } else {
                copy.id1 = d1 == 0 ? null : ctx.getDrawable(d1);
                copy.id2 = d2 == 0 ? null : ctx.getDrawable(d2);
                copy.id3 = d3 == 0 ? null : ctx.getDrawable(d3);
                copy.id4 = d4 == 0 ? null : ctx.getDrawable(d4);
            }
            return copy;
        }

        @Override
        public boolean prefersAsyncApply() {
            return useIcons;
        }

        @Override
        public int getActionTag() {
            return TEXT_VIEW_DRAWABLE_ACTION_TAG;
        }

        @Override
        public void visitUris(@NonNull Consumer<Uri> visitor) {
            if (useIcons) {
                visitIconUri(i1, visitor);
                visitIconUri(i2, visitor);
                visitIconUri(i3, visitor);
                visitIconUri(i4, visitor);
            }
        }

        boolean isRelative = false;
        boolean useIcons = false;
        int d1, d2, d3, d4;
        Icon i1, i2, i3, i4;

        boolean drawablesLoaded = false;
        Drawable id1, id2, id3, id4;
    }

    /**
     * Helper action to set text size on a TextView in any supported units.
     */
    private class TextViewSizeAction extends Action {
        public TextViewSizeAction(int viewId, int units, float size) {
            this.viewId = viewId;
            this.units = units;
            this.size = size;
        }

        public TextViewSizeAction(Parcel parcel) {
            viewId = parcel.readInt();
            units = parcel.readInt();
            size  = parcel.readFloat();
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeInt(units);
            dest.writeFloat(size);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final TextView target = root.findViewById(viewId);
            if (target == null) return;
            target.setTextSize(units, size);
        }

        @Override
        public int getActionTag() {
            return TEXT_VIEW_SIZE_ACTION_TAG;
        }

        int units;
        float size;
    }

    /**
     * Helper action to set padding on a View.
     */
    private class ViewPaddingAction extends Action {
        public ViewPaddingAction(int viewId, int left, int top, int right, int bottom) {
            this.viewId = viewId;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public ViewPaddingAction(Parcel parcel) {
            viewId = parcel.readInt();
            left = parcel.readInt();
            top = parcel.readInt();
            right = parcel.readInt();
            bottom = parcel.readInt();
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeInt(left);
            dest.writeInt(top);
            dest.writeInt(right);
            dest.writeInt(bottom);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View target = root.findViewById(viewId);
            if (target == null) return;
            target.setPadding(left, top, right, bottom);
        }

        @Override
        public int getActionTag() {
            return VIEW_PADDING_ACTION_TAG;
        }

        int left, top, right, bottom;
    }

    /**
     * Helper action to set layout params on a View.
     */
    private static class LayoutParamAction extends Action {

        /** Set marginEnd */
        public static final int LAYOUT_MARGIN_END_DIMEN = 1;
        /** Set width */
        public static final int LAYOUT_WIDTH = 2;
        public static final int LAYOUT_MARGIN_BOTTOM_DIMEN = 3;
        public static final int LAYOUT_MARGIN_END = 4;

        final int mProperty;
        final int mValue;

        /**
         * @param viewId ID of the view alter
         * @param property which layout parameter to alter
         * @param value new value of the layout parameter
         */
        public LayoutParamAction(int viewId, int property, int value) {
            this.viewId = viewId;
            this.mProperty = property;
            this.mValue = value;
        }

        public LayoutParamAction(Parcel parcel) {
            viewId = parcel.readInt();
            mProperty = parcel.readInt();
            mValue = parcel.readInt();
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeInt(mProperty);
            dest.writeInt(mValue);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View target = root.findViewById(viewId);
            if (target == null) {
                return;
            }
            ViewGroup.LayoutParams layoutParams = target.getLayoutParams();
            if (layoutParams == null) {
                return;
            }
            int value = mValue;
            switch (mProperty) {
                case LAYOUT_MARGIN_END_DIMEN:
                    value = resolveDimenPixelOffset(target, mValue);
                    // fall-through
                case LAYOUT_MARGIN_END:
                    if (layoutParams instanceof ViewGroup.MarginLayoutParams) {
                        ((ViewGroup.MarginLayoutParams) layoutParams).setMarginEnd(value);
                        target.setLayoutParams(layoutParams);
                    }
                    break;
                case LAYOUT_MARGIN_BOTTOM_DIMEN:
                    if (layoutParams instanceof ViewGroup.MarginLayoutParams) {
                        int resolved = resolveDimenPixelOffset(target, mValue);
                        ((ViewGroup.MarginLayoutParams) layoutParams).bottomMargin = resolved;
                        target.setLayoutParams(layoutParams);
                    }
                    break;
                case LAYOUT_WIDTH:
                    layoutParams.width = mValue;
                    target.setLayoutParams(layoutParams);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown property " + mProperty);
            }
        }

        private static int resolveDimenPixelOffset(View target, int value) {
            if (value == 0) {
                return 0;
            }
            return target.getContext().getResources().getDimensionPixelOffset(value);
        }

        @Override
        public int getActionTag() {
            return LAYOUT_PARAM_ACTION_TAG;
        }

        @Override
        public String getUniqueKey() {
            return super.getUniqueKey() + mProperty;
        }
    }

    /**
     * Helper action to add a view tag with RemoteInputs.
     */
    private class SetRemoteInputsAction extends Action {

        public SetRemoteInputsAction(int viewId, RemoteInput[] remoteInputs) {
            this.viewId = viewId;
            this.remoteInputs = remoteInputs;
        }

        public SetRemoteInputsAction(Parcel parcel) {
            viewId = parcel.readInt();
            remoteInputs = parcel.createTypedArray(RemoteInput.CREATOR);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(viewId);
            dest.writeTypedArray(remoteInputs, flags);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View target = root.findViewById(viewId);
            if (target == null) return;

            target.setTagInternal(R.id.remote_input_tag, remoteInputs);
        }

        @Override
        public int getActionTag() {
            return SET_REMOTE_INPUTS_ACTION_TAG;
        }

        final Parcelable[] remoteInputs;
    }

    /**
     * Helper action to override all textViewColors
     */
    private class OverrideTextColorsAction extends Action {

        private final int textColor;

        public OverrideTextColorsAction(int textColor) {
            this.textColor = textColor;
        }

        public OverrideTextColorsAction(Parcel parcel) {
            textColor = parcel.readInt();
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(textColor);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            // Let's traverse the viewtree and override all textColors!
            Stack<View> viewsToProcess = new Stack<>();
            viewsToProcess.add(root);
            while (!viewsToProcess.isEmpty()) {
                View v = viewsToProcess.pop();
                if (v instanceof TextView) {
                    TextView textView = (TextView) v;
                    textView.setText(ContrastColorUtil.clearColorSpans(textView.getText()));
                    textView.setTextColor(textColor);
                }
                if (v instanceof ViewGroup) {
                    ViewGroup viewGroup = (ViewGroup) v;
                    for (int i = 0; i < viewGroup.getChildCount(); i++) {
                        viewsToProcess.push(viewGroup.getChildAt(i));
                    }
                }
            }
        }

        @Override
        public int getActionTag() {
            return OVERRIDE_TEXT_COLORS_TAG;
        }
    }

    private class SetIntTagAction extends Action {
        private final int mViewId;
        private final int mKey;
        private final int mTag;

        SetIntTagAction(int viewId, int key, int tag) {
            mViewId = viewId;
            mKey = key;
            mTag = tag;
        }

        SetIntTagAction(Parcel parcel) {
            mViewId = parcel.readInt();
            mKey = parcel.readInt();
            mTag = parcel.readInt();
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mViewId);
            dest.writeInt(mKey);
            dest.writeInt(mTag);
        }

        @Override
        public void apply(View root, ViewGroup rootParent, OnClickHandler handler) {
            final View target = root.findViewById(mViewId);
            if (target == null) return;

            target.setTagInternal(mKey, mTag);
        }

        @Override
        public int getActionTag() {
            return SET_INT_TAG_TAG;
        }
    }

    /**
     * Create a new RemoteViews object that will display the views contained
     * in the specified layout file.
     *
     * @param packageName Name of the package that contains the layout resource
     * @param layoutId The id of the layout resource
     */
    public RemoteViews(String packageName, int layoutId) {
        this(getApplicationInfo(packageName, UserHandle.myUserId()), layoutId);
    }

    /**
     * Create a new RemoteViews object that will display the views contained
     * in the specified layout file.
     *
     * @param packageName Name of the package that contains the layout resource.
     * @param userId The user under which the package is running.
     * @param layoutId The id of the layout resource.
     *
     * @hide
     */
    public RemoteViews(String packageName, int userId, @LayoutRes int layoutId) {
        this(getApplicationInfo(packageName, userId), layoutId);
    }

    /**
     * Create a new RemoteViews object that will display the views contained
     * in the specified layout file.
     *
     * @param application The application whose content is shown by the views.
     * @param layoutId The id of the layout resource.
     *
     * @hide
     */
    protected RemoteViews(ApplicationInfo application, @LayoutRes int layoutId) {
        mApplication = application;
        mLayoutId = layoutId;
        mBitmapCache = new BitmapCache();
        mClassCookies = null;
    }

    private boolean hasLandscapeAndPortraitLayouts() {
        return (mLandscape != null) && (mPortrait != null);
    }

    /**
     * Create a new RemoteViews object that will inflate as the specified
     * landspace or portrait RemoteViews, depending on the current configuration.
     *
     * @param landscape The RemoteViews to inflate in landscape configuration
     * @param portrait The RemoteViews to inflate in portrait configuration
     */
    public RemoteViews(RemoteViews landscape, RemoteViews portrait) {
        if (landscape == null || portrait == null) {
            throw new RuntimeException("Both RemoteViews must be non-null");
        }
        if (!landscape.hasSameAppInfo(portrait.mApplication)) {
            throw new RuntimeException("Both RemoteViews must share the same package and user");
        }
        mApplication = portrait.mApplication;
        mLayoutId = portrait.mLayoutId;
        mLightBackgroundLayoutId = portrait.mLightBackgroundLayoutId;

        mLandscape = landscape;
        mPortrait = portrait;

        mBitmapCache = new BitmapCache();
        configureRemoteViewsAsChild(landscape);
        configureRemoteViewsAsChild(portrait);

        mClassCookies = (portrait.mClassCookies != null)
                ? portrait.mClassCookies : landscape.mClassCookies;
    }

    /**
     * Creates a copy of another RemoteViews.
     */
    public RemoteViews(RemoteViews src) {
        mBitmapCache = src.mBitmapCache;
        mApplication = src.mApplication;
        mIsRoot = src.mIsRoot;
        mLayoutId = src.mLayoutId;
        mLightBackgroundLayoutId = src.mLightBackgroundLayoutId;
        mApplyFlags = src.mApplyFlags;
        mClassCookies = src.mClassCookies;

        if (src.hasLandscapeAndPortraitLayouts()) {
            mLandscape = new RemoteViews(src.mLandscape);
            mPortrait = new RemoteViews(src.mPortrait);
        }

        if (src.mActions != null) {
            Parcel p = Parcel.obtain();
            p.putClassCookies(mClassCookies);
            src.writeActionsToParcel(p);
            p.setDataPosition(0);
            // Since src is already in memory, we do not care about stack overflow as it has
            // already been read once.
            readActionsFromParcel(p, 0);
            p.recycle();
        }

        // Now that everything is initialized and duplicated, setting a new BitmapCache will
        // re-initialize the cache.
        setBitmapCache(new BitmapCache());
    }

    /**
     * Reads a RemoteViews object from a parcel.
     *
     * @param parcel
     */
    public RemoteViews(Parcel parcel) {
        this(parcel, null, null, 0, null);
    }

    private RemoteViews(Parcel parcel, BitmapCache bitmapCache, ApplicationInfo info, int depth,
            Map<Class, Object> classCookies) {
        if (depth > MAX_NESTED_VIEWS
                && (UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID)) {
            throw new IllegalArgumentException("Too many nested views.");
        }
        depth++;

        int mode = parcel.readInt();

        // We only store a bitmap cache in the root of the RemoteViews.
        if (bitmapCache == null) {
            mBitmapCache = new BitmapCache(parcel);
            // Store the class cookies such that they are available when we clone this RemoteView.
            mClassCookies = parcel.copyClassCookies();
        } else {
            setBitmapCache(bitmapCache);
            mClassCookies = classCookies;
            setNotRoot();
        }

        if (mode == MODE_NORMAL) {
            mApplication = parcel.readInt() == 0 ? info :
                    ApplicationInfo.CREATOR.createFromParcel(parcel);
            mLayoutId = parcel.readInt();
            mLightBackgroundLayoutId = parcel.readInt();

            readActionsFromParcel(parcel, depth);
        } else {
            // MODE_HAS_LANDSCAPE_AND_PORTRAIT
            mLandscape = new RemoteViews(parcel, mBitmapCache, info, depth, mClassCookies);
            mPortrait = new RemoteViews(parcel, mBitmapCache, mLandscape.mApplication, depth,
                    mClassCookies);
            mApplication = mPortrait.mApplication;
            mLayoutId = mPortrait.mLayoutId;
            mLightBackgroundLayoutId = mPortrait.mLightBackgroundLayoutId;
        }
        mApplyFlags = parcel.readInt();
    }

    private void readActionsFromParcel(Parcel parcel, int depth) {
        int count = parcel.readInt();
        if (count > 0) {
            mActions = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                mActions.add(getActionFromParcel(parcel, depth));
            }
        }
    }

    private Action getActionFromParcel(Parcel parcel, int depth) {
        int tag = parcel.readInt();
        switch (tag) {
            case SET_ON_CLICK_RESPONSE_TAG:
                return new SetOnClickResponse(parcel);
            case SET_DRAWABLE_TINT_TAG:
                return new SetDrawableTint(parcel);
            case REFLECTION_ACTION_TAG:
                return new ReflectionAction(parcel);
            case VIEW_GROUP_ACTION_ADD_TAG:
                return new ViewGroupActionAdd(parcel, mBitmapCache, mApplication, depth,
                        mClassCookies);
            case VIEW_GROUP_ACTION_REMOVE_TAG:
                return new ViewGroupActionRemove(parcel);
            case VIEW_CONTENT_NAVIGATION_TAG:
                return new ViewContentNavigation(parcel);
            case SET_EMPTY_VIEW_ACTION_TAG:
                return new SetEmptyView(parcel);
            case SET_PENDING_INTENT_TEMPLATE_TAG:
                return new SetPendingIntentTemplate(parcel);
            case SET_REMOTE_VIEW_ADAPTER_INTENT_TAG:
                return new SetRemoteViewsAdapterIntent(parcel);
            case TEXT_VIEW_DRAWABLE_ACTION_TAG:
                return new TextViewDrawableAction(parcel);
            case TEXT_VIEW_SIZE_ACTION_TAG:
                return new TextViewSizeAction(parcel);
            case VIEW_PADDING_ACTION_TAG:
                return new ViewPaddingAction(parcel);
            case BITMAP_REFLECTION_ACTION_TAG:
                return new BitmapReflectionAction(parcel);
            case SET_REMOTE_VIEW_ADAPTER_LIST_TAG:
                return new SetRemoteViewsAdapterList(parcel);
            case SET_REMOTE_INPUTS_ACTION_TAG:
                return new SetRemoteInputsAction(parcel);
            case LAYOUT_PARAM_ACTION_TAG:
                return new LayoutParamAction(parcel);
            case OVERRIDE_TEXT_COLORS_TAG:
                return new OverrideTextColorsAction(parcel);
            case SET_RIPPLE_DRAWABLE_COLOR_TAG:
                return new SetRippleDrawableColor(parcel);
            case SET_INT_TAG_TAG:
                return new SetIntTagAction(parcel);
            default:
                throw new ActionException("Tag " + tag + " not found");
        }
    };

    /**
     * Returns a deep copy of the RemoteViews object. The RemoteView may not be
     * attached to another RemoteView -- it must be the root of a hierarchy.
     *
     * @deprecated use {@link #RemoteViews(RemoteViews)} instead.
     * @throws IllegalStateException if this is not the root of a RemoteView
     *         hierarchy
     */
    @Override
    @Deprecated
    public RemoteViews clone() {
        Preconditions.checkState(mIsRoot, "RemoteView has been attached to another RemoteView. "
                + "May only clone the root of a RemoteView hierarchy.");

        return new RemoteViews(this);
    }

    public String getPackage() {
        return (mApplication != null) ? mApplication.packageName : null;
    }

    /**
     * Returns the layout id of the root layout associated with this RemoteViews. In the case
     * that the RemoteViews has both a landscape and portrait root, this will return the layout
     * id associated with the portrait layout.
     *
     * @return the layout id.
     */
    public int getLayoutId() {
        return hasFlags(FLAG_USE_LIGHT_BACKGROUND_LAYOUT) && (mLightBackgroundLayoutId != 0)
                ? mLightBackgroundLayoutId : mLayoutId;
    }

    /**
     * Recursively sets BitmapCache in the hierarchy and update the bitmap ids.
     */
    private void setBitmapCache(BitmapCache bitmapCache) {
        mBitmapCache = bitmapCache;
        if (!hasLandscapeAndPortraitLayouts()) {
            if (mActions != null) {
                final int count = mActions.size();
                for (int i= 0; i < count; ++i) {
                    mActions.get(i).setBitmapCache(bitmapCache);
                }
            }
        } else {
            mLandscape.setBitmapCache(bitmapCache);
            mPortrait.setBitmapCache(bitmapCache);
        }
    }

    /**
     * Returns an estimate of the bitmap heap memory usage for this RemoteViews.
     */
    /** @hide */
    @UnsupportedAppUsage
    public int estimateMemoryUsage() {
        return mBitmapCache.getBitmapMemory();
    }

    /**
     * Add an action to be executed on the remote side when apply is called.
     *
     * @param a The action to add
     */
    private void addAction(Action a) {
        if (hasLandscapeAndPortraitLayouts()) {
            throw new RuntimeException("RemoteViews specifying separate landscape and portrait" +
                    " layouts cannot be modified. Instead, fully configure the landscape and" +
                    " portrait layouts individually before constructing the combined layout.");
        }
        if (mActions == null) {
            mActions = new ArrayList<>();
        }
        mActions.add(a);
    }

    /**
     * Equivalent to calling {@link ViewGroup#addView(View)} after inflating the
     * given {@link RemoteViews}. This allows users to build "nested"
     * {@link RemoteViews}. In cases where consumers of {@link RemoteViews} may
     * recycle layouts, use {@link #removeAllViews(int)} to clear any existing
     * children.
     *
     * @param viewId The id of the parent {@link ViewGroup} to add child into.
     * @param nestedView {@link RemoteViews} that describes the child.
     */
    public void addView(int viewId, RemoteViews nestedView) {
        addAction(nestedView == null
                ? new ViewGroupActionRemove(viewId)
                : new ViewGroupActionAdd(viewId, nestedView));
    }

    /**
     * Equivalent to calling {@link ViewGroup#addView(View, int)} after inflating the
     * given {@link RemoteViews}.
     *
     * @param viewId The id of the parent {@link ViewGroup} to add the child into.
     * @param nestedView {@link RemoteViews} of the child to add.
     * @param index The position at which to add the child.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void addView(int viewId, RemoteViews nestedView, int index) {
        addAction(new ViewGroupActionAdd(viewId, nestedView, index));
    }

    /**
     * Equivalent to calling {@link ViewGroup#removeAllViews()}.
     *
     * @param viewId The id of the parent {@link ViewGroup} to remove all
     *            children from.
     */
    public void removeAllViews(int viewId) {
        addAction(new ViewGroupActionRemove(viewId));
    }

    /**
     * Removes all views in the {@link ViewGroup} specified by the {@code viewId} except for any
     * child that has the {@code viewIdToKeep} as its id.
     *
     * @param viewId The id of the parent {@link ViewGroup} to remove children from.
     * @param viewIdToKeep The id of a child that should not be removed.
     *
     * @hide
     */
    public void removeAllViewsExceptId(int viewId, int viewIdToKeep) {
        addAction(new ViewGroupActionRemove(viewId, viewIdToKeep));
    }

    /**
     * Equivalent to calling {@link AdapterViewAnimator#showNext()}
     *
     * @param viewId The id of the view on which to call {@link AdapterViewAnimator#showNext()}
     */
    public void showNext(int viewId) {
        addAction(new ViewContentNavigation(viewId, true /* next */));
    }

    /**
     * Equivalent to calling {@link AdapterViewAnimator#showPrevious()}
     *
     * @param viewId The id of the view on which to call {@link AdapterViewAnimator#showPrevious()}
     */
    public void showPrevious(int viewId) {
        addAction(new ViewContentNavigation(viewId, false /* next */));
    }

    /**
     * Equivalent to calling {@link AdapterViewAnimator#setDisplayedChild(int)}
     *
     * @param viewId The id of the view on which to call
     *               {@link AdapterViewAnimator#setDisplayedChild(int)}
     */
    public void setDisplayedChild(int viewId, int childIndex) {
        setInt(viewId, "setDisplayedChild", childIndex);
    }

    /**
     * Equivalent to calling {@link View#setVisibility(int)}
     *
     * @param viewId The id of the view whose visibility should change
     * @param visibility The new visibility for the view
     */
    public void setViewVisibility(int viewId, int visibility) {
        setInt(viewId, "setVisibility", visibility);
    }

    /**
     * Equivalent to calling {@link TextView#setText(CharSequence)}
     *
     * @param viewId The id of the view whose text should change
     * @param text The new text for the view
     */
    public void setTextViewText(int viewId, CharSequence text) {
        setCharSequence(viewId, "setText", text);
    }

    /**
     * Equivalent to calling {@link TextView#setTextSize(int, float)}
     *
     * @param viewId The id of the view whose text size should change
     * @param units The units of size (e.g. COMPLEX_UNIT_SP)
     * @param size The size of the text
     */
    public void setTextViewTextSize(int viewId, int units, float size) {
        addAction(new TextViewSizeAction(viewId, units, size));
    }

    /**
     * Equivalent to calling
     * {@link TextView#setCompoundDrawablesWithIntrinsicBounds(int, int, int, int)}.
     *
     * @param viewId The id of the view whose text should change
     * @param left The id of a drawable to place to the left of the text, or 0
     * @param top The id of a drawable to place above the text, or 0
     * @param right The id of a drawable to place to the right of the text, or 0
     * @param bottom The id of a drawable to place below the text, or 0
     */
    public void setTextViewCompoundDrawables(int viewId, int left, int top, int right, int bottom) {
        addAction(new TextViewDrawableAction(viewId, false, left, top, right, bottom));
    }

    /**
     * Equivalent to calling {@link
     * TextView#setCompoundDrawablesRelativeWithIntrinsicBounds(int, int, int, int)}.
     *
     * @param viewId The id of the view whose text should change
     * @param start The id of a drawable to place before the text (relative to the
     * layout direction), or 0
     * @param top The id of a drawable to place above the text, or 0
     * @param end The id of a drawable to place after the text, or 0
     * @param bottom The id of a drawable to place below the text, or 0
     */
    public void setTextViewCompoundDrawablesRelative(int viewId, int start, int top, int end, int bottom) {
        addAction(new TextViewDrawableAction(viewId, true, start, top, end, bottom));
    }

    /**
     * Equivalent to calling {@link
     * TextView#setCompoundDrawablesWithIntrinsicBounds(Drawable, Drawable, Drawable, Drawable)}
     * using the drawables yielded by {@link Icon#loadDrawable(Context)}.
     *
     * @param viewId The id of the view whose text should change
     * @param left an Icon to place to the left of the text, or 0
     * @param top an Icon to place above the text, or 0
     * @param right an Icon to place to the right of the text, or 0
     * @param bottom an Icon to place below the text, or 0
     *
     * @hide
     */
    public void setTextViewCompoundDrawables(int viewId, Icon left, Icon top, Icon right, Icon bottom) {
        addAction(new TextViewDrawableAction(viewId, false, left, top, right, bottom));
    }

    /**
     * Equivalent to calling {@link
     * TextView#setCompoundDrawablesRelativeWithIntrinsicBounds(Drawable, Drawable, Drawable, Drawable)}
     * using the drawables yielded by {@link Icon#loadDrawable(Context)}.
     *
     * @param viewId The id of the view whose text should change
     * @param start an Icon to place before the text (relative to the
     * layout direction), or 0
     * @param top an Icon to place above the text, or 0
     * @param end an Icon to place after the text, or 0
     * @param bottom an Icon to place below the text, or 0
     *
     * @hide
     */
    public void setTextViewCompoundDrawablesRelative(int viewId, Icon start, Icon top, Icon end, Icon bottom) {
        addAction(new TextViewDrawableAction(viewId, true, start, top, end, bottom));
    }

    /**
     * Equivalent to calling {@link ImageView#setImageResource(int)}
     *
     * @param viewId The id of the view whose drawable should change
     * @param srcId The new resource id for the drawable
     */
    public void setImageViewResource(int viewId, int srcId) {
        setInt(viewId, "setImageResource", srcId);
    }

    /**
     * Equivalent to calling {@link ImageView#setImageURI(Uri)}
     *
     * @param viewId The id of the view whose drawable should change
     * @param uri The Uri for the image
     */
    public void setImageViewUri(int viewId, Uri uri) {
        setUri(viewId, "setImageURI", uri);
    }

    /**
     * Equivalent to calling {@link ImageView#setImageBitmap(Bitmap)}
     *
     * @param viewId The id of the view whose bitmap should change
     * @param bitmap The new Bitmap for the drawable
     */
    public void setImageViewBitmap(int viewId, Bitmap bitmap) {
        setBitmap(viewId, "setImageBitmap", bitmap);
    }

    /**
     * Equivalent to calling {@link ImageView#setImageIcon(Icon)}
     *
     * @param viewId The id of the view whose bitmap should change
     * @param icon The new Icon for the ImageView
     */
    public void setImageViewIcon(int viewId, Icon icon) {
        setIcon(viewId, "setImageIcon", icon);
    }

    /**
     * Equivalent to calling {@link AdapterView#setEmptyView(View)}
     *
     * @param viewId The id of the view on which to set the empty view
     * @param emptyViewId The view id of the empty view
     */
    public void setEmptyView(int viewId, int emptyViewId) {
        addAction(new SetEmptyView(viewId, emptyViewId));
    }

    /**
     * Equivalent to calling {@link Chronometer#setBase Chronometer.setBase},
     * {@link Chronometer#setFormat Chronometer.setFormat},
     * and {@link Chronometer#start Chronometer.start()} or
     * {@link Chronometer#stop Chronometer.stop()}.
     *
     * @param viewId The id of the {@link Chronometer} to change
     * @param base The time at which the timer would have read 0:00.  This
     *             time should be based off of
     *             {@link android.os.SystemClock#elapsedRealtime SystemClock.elapsedRealtime()}.
     * @param format The Chronometer format string, or null to
     *               simply display the timer value.
     * @param started True if you want the clock to be started, false if not.
     *
     * @see #setChronometerCountDown(int, boolean)
     */
    public void setChronometer(int viewId, long base, String format, boolean started) {
        setLong(viewId, "setBase", base);
        setString(viewId, "setFormat", format);
        setBoolean(viewId, "setStarted", started);
    }

    /**
     * Equivalent to calling {@link Chronometer#setCountDown(boolean) Chronometer.setCountDown} on
     * the chronometer with the given viewId.
     *
     * @param viewId The id of the {@link Chronometer} to change
     * @param isCountDown True if you want the chronometer to count down to base instead of
     *                    counting up.
     */
    public void setChronometerCountDown(int viewId, boolean isCountDown) {
        setBoolean(viewId, "setCountDown", isCountDown);
    }

    /**
     * Equivalent to calling {@link ProgressBar#setMax ProgressBar.setMax},
     * {@link ProgressBar#setProgress ProgressBar.setProgress}, and
     * {@link ProgressBar#setIndeterminate ProgressBar.setIndeterminate}
     *
     * If indeterminate is true, then the values for max and progress are ignored.
     *
     * @param viewId The id of the {@link ProgressBar} to change
     * @param max The 100% value for the progress bar
     * @param progress The current value of the progress bar.
     * @param indeterminate True if the progress bar is indeterminate,
     *                false if not.
     */
    public void setProgressBar(int viewId, int max, int progress,
            boolean indeterminate) {
        setBoolean(viewId, "setIndeterminate", indeterminate);
        if (!indeterminate) {
            setInt(viewId, "setMax", max);
            setInt(viewId, "setProgress", progress);
        }
    }

    /**
     * Equivalent to calling
     * {@link android.view.View#setOnClickListener(android.view.View.OnClickListener)}
     * to launch the provided {@link PendingIntent}. The source bounds
     * ({@link Intent#getSourceBounds()}) of the intent will be set to the bounds of the clicked
     * view in screen space.
     * Note that any activity options associated with the mPendingIntent may get overridden
     * before starting the intent.
     *
     * When setting the on-click action of items within collections (eg. {@link ListView},
     * {@link StackView} etc.), this method will not work. Instead, use {@link
     * RemoteViews#setPendingIntentTemplate(int, PendingIntent)} in conjunction with
     * {@link RemoteViews#setOnClickFillInIntent(int, Intent)}.
     *
     * @param viewId The id of the view that will trigger the {@link PendingIntent} when clicked
     * @param pendingIntent The {@link PendingIntent} to send when user clicks
     */
    public void setOnClickPendingIntent(int viewId, PendingIntent pendingIntent) {
        setOnClickResponse(viewId, RemoteResponse.fromPendingIntent(pendingIntent));
    }

    /**
     * Equivalent of calling
     * {@link android.view.View#setOnClickListener(android.view.View.OnClickListener)}
     * to launch the provided {@link RemoteResponse}.
     *
     * @param viewId The id of the view that will trigger the {@link RemoteResponse} when clicked
     * @param response The {@link RemoteResponse} to send when user clicks
     */
    public void setOnClickResponse(int viewId, @NonNull RemoteResponse response) {
        addAction(new SetOnClickResponse(viewId, response));
    }

    /**
     * When using collections (eg. {@link ListView}, {@link StackView} etc.) in widgets, it is very
     * costly to set PendingIntents on the individual items, and is hence not permitted. Instead
     * this method should be used to set a single PendingIntent template on the collection, and
     * individual items can differentiate their on-click behavior using
     * {@link RemoteViews#setOnClickFillInIntent(int, Intent)}.
     *
     * @param viewId The id of the collection who's children will use this PendingIntent template
     *          when clicked
     * @param pendingIntentTemplate The {@link PendingIntent} to be combined with extras specified
     *          by a child of viewId and executed when that child is clicked
     */
    public void setPendingIntentTemplate(int viewId, PendingIntent pendingIntentTemplate) {
        addAction(new SetPendingIntentTemplate(viewId, pendingIntentTemplate));
    }

    /**
     * When using collections (eg. {@link ListView}, {@link StackView} etc.) in widgets, it is very
     * costly to set PendingIntents on the individual items, and is hence not permitted. Instead
     * a single PendingIntent template can be set on the collection, see {@link
     * RemoteViews#setPendingIntentTemplate(int, PendingIntent)}, and the individual on-click
     * action of a given item can be distinguished by setting a fillInIntent on that item. The
     * fillInIntent is then combined with the PendingIntent template in order to determine the final
     * intent which will be executed when the item is clicked. This works as follows: any fields
     * which are left blank in the PendingIntent template, but are provided by the fillInIntent
     * will be overwritten, and the resulting PendingIntent will be used. The rest
     * of the PendingIntent template will then be filled in with the associated fields that are
     * set in fillInIntent. See {@link Intent#fillIn(Intent, int)} for more details.
     *
     * @param viewId The id of the view on which to set the fillInIntent
     * @param fillInIntent The intent which will be combined with the parent's PendingIntent
     *        in order to determine the on-click behavior of the view specified by viewId
     */
    public void setOnClickFillInIntent(int viewId, Intent fillInIntent) {
        setOnClickResponse(viewId, RemoteResponse.fromFillInIntent(fillInIntent));
    }

    /**
     * @hide
     * Equivalent to calling
     * {@link Drawable#setColorFilter(int, android.graphics.PorterDuff.Mode)},
     * on the {@link Drawable} of a given view.
     * <p>
     *
     * @param viewId The id of the view that contains the target
     *            {@link Drawable}
     * @param targetBackground If true, apply these parameters to the
     *            {@link Drawable} returned by
     *            {@link android.view.View#getBackground()}. Otherwise, assume
     *            the target view is an {@link ImageView} and apply them to
     *            {@link ImageView#getDrawable()}.
     * @param colorFilter Specify a color for a
     *            {@link android.graphics.ColorFilter} for this drawable. This will be ignored if
     *            {@code mode} is {@code null}.
     * @param mode Specify a PorterDuff mode for this drawable, or null to leave
     *            unchanged.
     */
    public void setDrawableTint(int viewId, boolean targetBackground,
            int colorFilter, @NonNull PorterDuff.Mode mode) {
        addAction(new SetDrawableTint(viewId, targetBackground, colorFilter, mode));
    }

    /**
     * @hide
     * Equivalent to calling
     * {@link RippleDrawable#setColor(ColorStateList)} on the {@link Drawable} of a given view,
     * assuming it's a {@link RippleDrawable}.
     * <p>
     *
     * @param viewId The id of the view that contains the target
     *            {@link RippleDrawable}
     * @param colorStateList Specify a color for a
     *            {@link ColorStateList} for this drawable.
     */
    public void setRippleDrawableColor(int viewId, ColorStateList colorStateList) {
        addAction(new SetRippleDrawableColor(viewId, colorStateList));
    }

    /**
     * @hide
     * Equivalent to calling {@link android.widget.ProgressBar#setProgressTintList}.
     *
     * @param viewId The id of the view whose tint should change
     * @param tint the tint to apply, may be {@code null} to clear tint
     */
    public void setProgressTintList(int viewId, ColorStateList tint) {
        addAction(new ReflectionAction(viewId, "setProgressTintList",
                ReflectionAction.COLOR_STATE_LIST, tint));
    }

    /**
     * @hide
     * Equivalent to calling {@link android.widget.ProgressBar#setProgressBackgroundTintList}.
     *
     * @param viewId The id of the view whose tint should change
     * @param tint the tint to apply, may be {@code null} to clear tint
     */
    public void setProgressBackgroundTintList(int viewId, ColorStateList tint) {
        addAction(new ReflectionAction(viewId, "setProgressBackgroundTintList",
                ReflectionAction.COLOR_STATE_LIST, tint));
    }

    /**
     * @hide
     * Equivalent to calling {@link android.widget.ProgressBar#setIndeterminateTintList}.
     *
     * @param viewId The id of the view whose tint should change
     * @param tint the tint to apply, may be {@code null} to clear tint
     */
    public void setProgressIndeterminateTintList(int viewId, ColorStateList tint) {
        addAction(new ReflectionAction(viewId, "setIndeterminateTintList",
                ReflectionAction.COLOR_STATE_LIST, tint));
    }

    /**
     * Equivalent to calling {@link android.widget.TextView#setTextColor(int)}.
     *
     * @param viewId The id of the view whose text color should change
     * @param color Sets the text color for all the states (normal, selected,
     *            focused) to be this color.
     */
    public void setTextColor(int viewId, @ColorInt int color) {
        setInt(viewId, "setTextColor", color);
    }

    /**
     * @hide
     * Equivalent to calling {@link android.widget.TextView#setTextColor(ColorStateList)}.
     *
     * @param viewId The id of the view whose text color should change
     * @param colors the text colors to set
     */
    public void setTextColor(int viewId, @ColorInt ColorStateList colors) {
        addAction(new ReflectionAction(viewId, "setTextColor", ReflectionAction.COLOR_STATE_LIST,
                colors));
    }

    /**
     * Equivalent to calling {@link android.widget.AbsListView#setRemoteViewsAdapter(Intent)}.
     *
     * @param appWidgetId The id of the app widget which contains the specified view. (This
     *      parameter is ignored in this deprecated method)
     * @param viewId The id of the {@link AdapterView}
     * @param intent The intent of the service which will be
     *            providing data to the RemoteViewsAdapter
     * @deprecated This method has been deprecated. See
     *      {@link android.widget.RemoteViews#setRemoteAdapter(int, Intent)}
     */
    @Deprecated
    public void setRemoteAdapter(int appWidgetId, int viewId, Intent intent) {
        setRemoteAdapter(viewId, intent);
    }

    /**
     * Equivalent to calling {@link android.widget.AbsListView#setRemoteViewsAdapter(Intent)}.
     * Can only be used for App Widgets.
     *
     * @param viewId The id of the {@link AdapterView}
     * @param intent The intent of the service which will be
     *            providing data to the RemoteViewsAdapter
     */
    public void setRemoteAdapter(int viewId, Intent intent) {
        addAction(new SetRemoteViewsAdapterIntent(viewId, intent));
    }

    /**
     * Creates a simple Adapter for the viewId specified. The viewId must point to an AdapterView,
     * ie. {@link ListView}, {@link GridView}, {@link StackView} or {@link AdapterViewAnimator}.
     * This is a simpler but less flexible approach to populating collection widgets. Its use is
     * encouraged for most scenarios, as long as the total memory within the list of RemoteViews
     * is relatively small (ie. doesn't contain large or numerous Bitmaps, see {@link
     * RemoteViews#setImageViewBitmap}). In the case of numerous images, the use of API is still
     * possible by setting image URIs instead of Bitmaps, see {@link RemoteViews#setImageViewUri}.
     *
     * This API is supported in the compatibility library for previous API levels, see
     * RemoteViewsCompat.
     *
     * @param viewId The id of the {@link AdapterView}
     * @param list The list of RemoteViews which will populate the view specified by viewId.
     * @param viewTypeCount The maximum number of unique layout id's used to construct the list of
     *      RemoteViews. This count cannot change during the life-cycle of a given widget, so this
     *      parameter should account for the maximum possible number of types that may appear in the
     *      See {@link Adapter#getViewTypeCount()}.
     *
     * @hide
     * @deprecated this appears to have no users outside of UnsupportedAppUsage?
     */
    @UnsupportedAppUsage
    @Deprecated
    public void setRemoteAdapter(int viewId, ArrayList<RemoteViews> list, int viewTypeCount) {
        addAction(new SetRemoteViewsAdapterList(viewId, list, viewTypeCount));
    }

    /**
     * Equivalent to calling {@link ListView#smoothScrollToPosition(int)}.
     *
     * @param viewId The id of the view to change
     * @param position Scroll to this adapter position
     */
    public void setScrollPosition(int viewId, int position) {
        setInt(viewId, "smoothScrollToPosition", position);
    }

    /**
     * Equivalent to calling {@link ListView#smoothScrollByOffset(int)}.
     *
     * @param viewId The id of the view to change
     * @param offset Scroll by this adapter position offset
     */
    public void setRelativeScrollPosition(int viewId, int offset) {
        setInt(viewId, "smoothScrollByOffset", offset);
    }

    /**
     * Equivalent to calling {@link android.view.View#setPadding(int, int, int, int)}.
     *
     * @param viewId The id of the view to change
     * @param left the left padding in pixels
     * @param top the top padding in pixels
     * @param right the right padding in pixels
     * @param bottom the bottom padding in pixels
     */
    public void setViewPadding(int viewId, int left, int top, int right, int bottom) {
        addAction(new ViewPaddingAction(viewId, left, top, right, bottom));
    }

    /**
     * @hide
     * Equivalent to calling {@link android.view.ViewGroup.MarginLayoutParams#setMarginEnd(int)}.
     * Only works if the {@link View#getLayoutParams()} supports margins.
     * Hidden for now since we don't want to support this for all different layout margins yet.
     *
     * @param viewId The id of the view to change
     * @param endMarginDimen a dimen resource to read the margin from or 0 to clear the margin.
     */
    public void setViewLayoutMarginEndDimen(int viewId, @DimenRes int endMarginDimen) {
        addAction(new LayoutParamAction(viewId, LayoutParamAction.LAYOUT_MARGIN_END_DIMEN,
                endMarginDimen));
    }

    /**
     * Equivalent to calling {@link android.view.ViewGroup.MarginLayoutParams#setMarginEnd(int)}.
     * Only works if the {@link View#getLayoutParams()} supports margins.
     * Hidden for now since we don't want to support this for all different layout margins yet.
     *
     * @param viewId The id of the view to change
     * @param endMargin a value in pixels for the end margin.
     * @hide
     */
    public void setViewLayoutMarginEnd(int viewId, @DimenRes int endMargin) {
        addAction(new LayoutParamAction(viewId, LayoutParamAction.LAYOUT_MARGIN_END,
                endMargin));
    }

    /**
     * Equivalent to setting {@link android.view.ViewGroup.MarginLayoutParams#bottomMargin}.
     *
     * @param bottomMarginDimen a dimen resource to read the margin from or 0 to clear the margin.
     * @hide
     */
    public void setViewLayoutMarginBottomDimen(int viewId, @DimenRes int bottomMarginDimen) {
        addAction(new LayoutParamAction(viewId, LayoutParamAction.LAYOUT_MARGIN_BOTTOM_DIMEN,
                bottomMarginDimen));
    }

    /**
     * Equivalent to setting {@link android.view.ViewGroup.LayoutParams#width}.
     *
     * @param layoutWidth one of 0, MATCH_PARENT or WRAP_CONTENT. Other sizes are not allowed
     *                    because they behave poorly when the density changes.
     * @hide
     */
    public void setViewLayoutWidth(int viewId, int layoutWidth) {
        if (layoutWidth != 0 && layoutWidth != ViewGroup.LayoutParams.MATCH_PARENT
                && layoutWidth != ViewGroup.LayoutParams.WRAP_CONTENT) {
            throw new IllegalArgumentException("Only supports 0, WRAP_CONTENT and MATCH_PARENT");
        }
        mActions.add(new LayoutParamAction(viewId, LayoutParamAction.LAYOUT_WIDTH, layoutWidth));
    }

    /**
     * Call a method taking one boolean on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setBoolean(int viewId, String methodName, boolean value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.BOOLEAN, value));
    }

    /**
     * Call a method taking one byte on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setByte(int viewId, String methodName, byte value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.BYTE, value));
    }

    /**
     * Call a method taking one short on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setShort(int viewId, String methodName, short value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.SHORT, value));
    }

    /**
     * Call a method taking one int on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setInt(int viewId, String methodName, int value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.INT, value));
    }

    /**
     * Call a method taking one ColorStateList on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     *
     * @hide
     */
    public void setColorStateList(int viewId, String methodName, ColorStateList value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.COLOR_STATE_LIST,
                value));
    }


    /**
     * Call a method taking one long on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setLong(int viewId, String methodName, long value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.LONG, value));
    }

    /**
     * Call a method taking one float on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setFloat(int viewId, String methodName, float value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.FLOAT, value));
    }

    /**
     * Call a method taking one double on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setDouble(int viewId, String methodName, double value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.DOUBLE, value));
    }

    /**
     * Call a method taking one char on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setChar(int viewId, String methodName, char value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.CHAR, value));
    }

    /**
     * Call a method taking one String on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setString(int viewId, String methodName, String value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.STRING, value));
    }

    /**
     * Call a method taking one CharSequence on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setCharSequence(int viewId, String methodName, CharSequence value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.CHAR_SEQUENCE, value));
    }

    /**
     * Call a method taking one Uri on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setUri(int viewId, String methodName, Uri value) {
        if (value != null) {
            // Resolve any filesystem path before sending remotely
            value = value.getCanonicalUri();
            if (StrictMode.vmFileUriExposureEnabled()) {
                value.checkFileUriExposed("RemoteViews.setUri()");
            }
        }
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.URI, value));
    }

    /**
     * Call a method taking one Bitmap on a view in the layout for this RemoteViews.
     * @more
     * <p class="note">The bitmap will be flattened into the parcel if this object is
     * sent across processes, so it may end up using a lot of memory, and may be fairly slow.</p>
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setBitmap(int viewId, String methodName, Bitmap value) {
        addAction(new BitmapReflectionAction(viewId, methodName, value));
    }

    /**
     * Call a method taking one Bundle on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The value to pass to the method.
     */
    public void setBundle(int viewId, String methodName, Bundle value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.BUNDLE, value));
    }

    /**
     * Call a method taking one Intent on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The {@link android.content.Intent} to pass the method.
     */
    public void setIntent(int viewId, String methodName, Intent value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.INTENT, value));
    }

    /**
     * Call a method taking one Icon on a view in the layout for this RemoteViews.
     *
     * @param viewId The id of the view on which to call the method.
     * @param methodName The name of the method to call.
     * @param value The {@link android.graphics.drawable.Icon} to pass the method.
     */
    public void setIcon(int viewId, String methodName, Icon value) {
        addAction(new ReflectionAction(viewId, methodName, ReflectionAction.ICON, value));
    }

    /**
     * Equivalent to calling View.setContentDescription(CharSequence).
     *
     * @param viewId The id of the view whose content description should change.
     * @param contentDescription The new content description for the view.
     */
    public void setContentDescription(int viewId, CharSequence contentDescription) {
        setCharSequence(viewId, "setContentDescription", contentDescription);
    }

    /**
     * Equivalent to calling {@link android.view.View#setAccessibilityTraversalBefore(int)}.
     *
     * @param viewId The id of the view whose before view in accessibility traversal to set.
     * @param nextId The id of the next in the accessibility traversal.
     **/
    public void setAccessibilityTraversalBefore(int viewId, int nextId) {
        setInt(viewId, "setAccessibilityTraversalBefore", nextId);
    }

    /**
     * Equivalent to calling {@link android.view.View#setAccessibilityTraversalAfter(int)}.
     *
     * @param viewId The id of the view whose after view in accessibility traversal to set.
     * @param nextId The id of the next in the accessibility traversal.
     **/
    public void setAccessibilityTraversalAfter(int viewId, int nextId) {
        setInt(viewId, "setAccessibilityTraversalAfter", nextId);
    }

    /**
     * Equivalent to calling {@link View#setLabelFor(int)}.
     *
     * @param viewId The id of the view whose property to set.
     * @param labeledId The id of a view for which this view serves as a label.
     */
    public void setLabelFor(int viewId, int labeledId) {
        setInt(viewId, "setLabelFor", labeledId);
    }

    /**
     * Provides an alternate layout ID, which can be used to inflate this view. This layout will be
     * used by the host when the widgets displayed on a light-background where foreground elements
     * and text can safely draw using a dark color without any additional background protection.
     */
    public void setLightBackgroundLayoutId(@LayoutRes int layoutId) {
        mLightBackgroundLayoutId = layoutId;
    }

    /**
     * If this view supports dark text versions, creates a copy representing that version,
     * otherwise returns itself.
     * @hide
     */
    public RemoteViews getDarkTextViews() {
        if (hasFlags(FLAG_USE_LIGHT_BACKGROUND_LAYOUT)) {
            return this;
        }

        try {
            addFlags(FLAG_USE_LIGHT_BACKGROUND_LAYOUT);
            return new RemoteViews(this);
        } finally {
            mApplyFlags &= ~FLAG_USE_LIGHT_BACKGROUND_LAYOUT;
        }
    }

    private RemoteViews getRemoteViewsToApply(Context context) {
        if (hasLandscapeAndPortraitLayouts()) {
            int orientation = context.getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return mLandscape;
            } else {
                return mPortrait;
            }
        }
        return this;
    }

    /**
     * Inflates the view hierarchy represented by this object and applies
     * all of the actions.
     *
     * <p><strong>Caller beware: this may throw</strong>
     *
     * @param context Default context to use
     * @param parent Parent that the resulting view hierarchy will be attached to. This method
     * does <strong>not</strong> attach the hierarchy. The caller should do so when appropriate.
     * @return The inflated view hierarchy
     */
    public View apply(Context context, ViewGroup parent) {
        return apply(context, parent, null);
    }

    /** @hide */
    public View apply(Context context, ViewGroup parent, OnClickHandler handler) {
        RemoteViews rvToApply = getRemoteViewsToApply(context);

        View result = inflateView(context, rvToApply, parent);
        rvToApply.performApply(result, parent, handler);
        return result;
    }

    /** @hide */
    public View applyWithTheme(Context context, ViewGroup parent, OnClickHandler handler,
            @StyleRes int applyThemeResId) {
        RemoteViews rvToApply = getRemoteViewsToApply(context);

        View result = inflateView(context, rvToApply, parent, applyThemeResId);
        rvToApply.performApply(result, parent, handler);
        return result;
    }

    private View inflateView(Context context, RemoteViews rv, ViewGroup parent) {
        return inflateView(context, rv, parent, 0);
    }

    private View inflateView(Context context, RemoteViews rv, ViewGroup parent,
            @StyleRes int applyThemeResId) {
        // RemoteViews may be built by an application installed in another
        // user. So build a context that loads resources from that user but
        // still returns the current users userId so settings like data / time formats
        // are loaded without requiring cross user persmissions.
        final Context contextForResources = getContextForResources(context);
        Context inflationContext = new RemoteViewsContextWrapper(context, contextForResources);

        // If mApplyThemeResId is not given, Theme.DeviceDefault will be used.
        if (applyThemeResId != 0) {
            inflationContext = new ContextThemeWrapper(inflationContext, applyThemeResId);
        }
        LayoutInflater inflater = (LayoutInflater)
                context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        // Clone inflater so we load resources from correct context and
        // we don't add a filter to the static version returned by getSystemService.
        inflater = inflater.cloneInContext(inflationContext);
        inflater.setFilter(this);
        View v = inflater.inflate(rv.getLayoutId(), parent, false);
        v.setTagInternal(R.id.widget_frame, rv.getLayoutId());
        return v;
    }

    /**
     * Implement this interface to receive a callback when
     * {@link #applyAsync} or {@link #reapplyAsync} is finished.
     * @hide
     */
    public interface OnViewAppliedListener {
        /**
         * Callback when the RemoteView has finished inflating,
         * but no actions have been applied yet.
         */
        default void onViewInflated(View v) {};

        void onViewApplied(View v);

        void onError(Exception e);
    }

    /**
     * Applies the views asynchronously, moving as much of the task on the background
     * thread as possible.
     *
     * @see #apply(Context, ViewGroup)
     * @param context Default context to use
     * @param parent Parent that the resulting view hierarchy will be attached to. This method
     * does <strong>not</strong> attach the hierarchy. The caller should do so when appropriate.
     * @param listener the callback to run when all actions have been applied. May be null.
     * @param executor The executor to use. If null {@link AsyncTask#THREAD_POOL_EXECUTOR} is used.
     * @return CancellationSignal
     * @hide
     */
    public CancellationSignal applyAsync(
            Context context, ViewGroup parent, Executor executor, OnViewAppliedListener listener) {
        return applyAsync(context, parent, executor, listener, null);
    }

    private CancellationSignal startTaskOnExecutor(AsyncApplyTask task, Executor executor) {
        CancellationSignal cancelSignal = new CancellationSignal();
        cancelSignal.setOnCancelListener(task);

        task.executeOnExecutor(executor == null ? AsyncTask.THREAD_POOL_EXECUTOR : executor);
        return cancelSignal;
    }

    /** @hide */
    public CancellationSignal applyAsync(Context context, ViewGroup parent,
            Executor executor, OnViewAppliedListener listener, OnClickHandler handler) {
        return startTaskOnExecutor(getAsyncApplyTask(context, parent, listener, handler), executor);
    }

    private AsyncApplyTask getAsyncApplyTask(Context context, ViewGroup parent,
            OnViewAppliedListener listener, OnClickHandler handler) {
        return new AsyncApplyTask(getRemoteViewsToApply(context), parent, context, listener,
                handler, null);
    }

    private class AsyncApplyTask extends AsyncTask<Void, Void, ViewTree>
            implements CancellationSignal.OnCancelListener {
        final RemoteViews mRV;
        final ViewGroup mParent;
        final Context mContext;
        final OnViewAppliedListener mListener;
        final OnClickHandler mHandler;

        private View mResult;
        private ViewTree mTree;
        private Action[] mActions;
        private Exception mError;

        private AsyncApplyTask(
                RemoteViews rv, ViewGroup parent, Context context, OnViewAppliedListener listener,
                OnClickHandler handler, View result) {
            mRV = rv;
            mParent = parent;
            mContext = context;
            mListener = listener;
            mHandler = handler;

            mResult = result;
        }

        @Override
        protected ViewTree doInBackground(Void... params) {
            try {
                if (mResult == null) {
                    mResult = inflateView(mContext, mRV, mParent);
                }

                mTree = new ViewTree(mResult);
                if (mRV.mActions != null) {
                    int count = mRV.mActions.size();
                    mActions = new Action[count];
                    for (int i = 0; i < count && !isCancelled(); i++) {
                        // TODO: check if isCancelled in nested views.
                        mActions[i] = mRV.mActions.get(i).initActionAsync(mTree, mParent, mHandler);
                    }
                } else {
                    mActions = null;
                }
                return mTree;
            } catch (Exception e) {
                mError = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(ViewTree viewTree) {
            if (mError == null) {
                if (mListener != null) {
                    mListener.onViewInflated(viewTree.mRoot);
                }

                try {
                    if (mActions != null) {
                        OnClickHandler handler = mHandler == null
                                ? DEFAULT_ON_CLICK_HANDLER : mHandler;
                        for (Action a : mActions) {
                            a.apply(viewTree.mRoot, mParent, handler);
                        }
                    }
                } catch (Exception e) {
                    mError = e;
                }
            }

            if (mListener != null) {
                if (mError != null) {
                    mListener.onError(mError);
                } else {
                    mListener.onViewApplied(viewTree.mRoot);
                }
            } else if (mError != null) {
                if (mError instanceof ActionException) {
                    throw (ActionException) mError;
                } else {
                    throw new ActionException(mError);
                }
            }
        }

        @Override
        public void onCancel() {
            cancel(true);
        }
    }

    /**
     * Applies all of the actions to the provided view.
     *
     * <p><strong>Caller beware: this may throw</strong>
     *
     * @param v The view to apply the actions to.  This should be the result of
     * the {@link #apply(Context,ViewGroup)} call.
     */
    public void reapply(Context context, View v) {
        reapply(context, v, null);
    }

    /** @hide */
    public void reapply(Context context, View v, OnClickHandler handler) {
        RemoteViews rvToApply = getRemoteViewsToApply(context);

        // In the case that a view has this RemoteViews applied in one orientation, is persisted
        // across orientation change, and has the RemoteViews re-applied in the new orientation,
        // we throw an exception, since the layouts may be completely unrelated.
        if (hasLandscapeAndPortraitLayouts()) {
            if ((Integer) v.getTag(R.id.widget_frame) != rvToApply.getLayoutId()) {
                throw new RuntimeException("Attempting to re-apply RemoteViews to a view that" +
                        " that does not share the same root layout id.");
            }
        }

        rvToApply.performApply(v, (ViewGroup) v.getParent(), handler);
    }

    /**
     * Applies all the actions to the provided view, moving as much of the task on the background
     * thread as possible.
     *
     * @see #reapply(Context, View)
     * @param context Default context to use
     * @param v The view to apply the actions to.  This should be the result of
     * the {@link #apply(Context,ViewGroup)} call.
     * @param listener the callback to run when all actions have been applied. May be null.
     * @param executor The executor to use. If null {@link AsyncTask#THREAD_POOL_EXECUTOR} is used
     * @return CancellationSignal
     * @hide
     */
    public CancellationSignal reapplyAsync(
            Context context, View v, Executor executor, OnViewAppliedListener listener) {
        return reapplyAsync(context, v, executor, listener, null);
    }

    /** @hide */
    public CancellationSignal reapplyAsync(Context context, View v, Executor executor,
            OnViewAppliedListener listener, OnClickHandler handler) {
        RemoteViews rvToApply = getRemoteViewsToApply(context);

        // In the case that a view has this RemoteViews applied in one orientation, is persisted
        // across orientation change, and has the RemoteViews re-applied in the new orientation,
        // we throw an exception, since the layouts may be completely unrelated.
        if (hasLandscapeAndPortraitLayouts()) {
            if ((Integer) v.getTag(R.id.widget_frame) != rvToApply.getLayoutId()) {
                throw new RuntimeException("Attempting to re-apply RemoteViews to a view that" +
                        " that does not share the same root layout id.");
            }
        }

        return startTaskOnExecutor(new AsyncApplyTask(rvToApply, (ViewGroup) v.getParent(),
                context, listener, handler, v), executor);
    }

    private void performApply(View v, ViewGroup parent, OnClickHandler handler) {
        if (mActions != null) {
            handler = handler == null ? DEFAULT_ON_CLICK_HANDLER : handler;
            final int count = mActions.size();
            for (int i = 0; i < count; i++) {
                Action a = mActions.get(i);
                a.apply(v, parent, handler);
            }
        }
    }

    /**
     * Returns true if the RemoteViews contains potentially costly operations and should be
     * applied asynchronously.
     *
     * @hide
     */
    public boolean prefersAsyncApply() {
        if (mActions != null) {
            final int count = mActions.size();
            for (int i = 0; i < count; i++) {
                if (mActions.get(i).prefersAsyncApply()) {
                    return true;
                }
            }
        }
        return false;
    }

    private Context getContextForResources(Context context) {
        if (mApplication != null) {
            if (context.getUserId() == UserHandle.getUserId(mApplication.uid)
                    && context.getPackageName().equals(mApplication.packageName)) {
                return context;
            }
            try {
                return context.createApplicationContext(mApplication,
                        Context.CONTEXT_RESTRICTED);
            } catch (NameNotFoundException e) {
                Log.e(LOG_TAG, "Package name " + mApplication.packageName + " not found");
            }
        }

        return context;
    }

    /**
     * Returns the number of actions in this RemoteViews. Can be used as a sequence number.
     *
     * @hide
     */
    public int getSequenceNumber() {
        return (mActions == null) ? 0 : mActions.size();
    }

    /* (non-Javadoc)
     * Used to restrict the views which can be inflated
     *
     * @see android.view.LayoutInflater.Filter#onLoadClass(java.lang.Class)
     */
    public boolean onLoadClass(Class clazz) {
        return clazz.isAnnotationPresent(RemoteView.class);
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        if (!hasLandscapeAndPortraitLayouts()) {
            dest.writeInt(MODE_NORMAL);
            // We only write the bitmap cache if we are the root RemoteViews, as this cache
            // is shared by all children.
            if (mIsRoot) {
                mBitmapCache.writeBitmapsToParcel(dest, flags);
            }
            if (!mIsRoot && (flags & PARCELABLE_ELIDE_DUPLICATES) != 0) {
                dest.writeInt(0);
            } else {
                dest.writeInt(1);
                mApplication.writeToParcel(dest, flags);
            }
            dest.writeInt(mLayoutId);
            dest.writeInt(mLightBackgroundLayoutId);
            writeActionsToParcel(dest);
        } else {
            dest.writeInt(MODE_HAS_LANDSCAPE_AND_PORTRAIT);
            // We only write the bitmap cache if we are the root RemoteViews, as this cache
            // is shared by all children.
            if (mIsRoot) {
                mBitmapCache.writeBitmapsToParcel(dest, flags);
            }
            mLandscape.writeToParcel(dest, flags);
            // Both RemoteViews already share the same package and user
            mPortrait.writeToParcel(dest, flags | PARCELABLE_ELIDE_DUPLICATES);
        }
        dest.writeInt(mApplyFlags);
    }

    private void writeActionsToParcel(Parcel parcel) {
        int count;
        if (mActions != null) {
            count = mActions.size();
        } else {
            count = 0;
        }
        parcel.writeInt(count);
        for (int i = 0; i < count; i++) {
            Action a = mActions.get(i);
            parcel.writeInt(a.getActionTag());
            a.writeToParcel(parcel, a.hasSameAppInfo(mApplication)
                    ? PARCELABLE_ELIDE_DUPLICATES : 0);
        }
    }

    private static ApplicationInfo getApplicationInfo(String packageName, int userId) {
        if (packageName == null) {
            return null;
        }

        // Get the application for the passed in package and user.
        Application application = ActivityThread.currentApplication();
        if (application == null) {
            throw new IllegalStateException("Cannot create remote views out of an aplication.");
        }

        ApplicationInfo applicationInfo = application.getApplicationInfo();
        if (UserHandle.getUserId(applicationInfo.uid) != userId
                || !applicationInfo.packageName.equals(packageName)) {
            try {
                Context context = application.getBaseContext().createPackageContextAsUser(
                        packageName, 0, new UserHandle(userId));
                applicationInfo = context.getApplicationInfo();
            } catch (NameNotFoundException nnfe) {
                throw new IllegalArgumentException("No such package " + packageName);
            }
        }

        return applicationInfo;
    }

    /**
     * Returns true if the {@link #mApplication} is same as the provided info.
     *
     * @hide
     */
    public boolean hasSameAppInfo(ApplicationInfo info) {
        return mApplication.packageName.equals(info.packageName) && mApplication.uid == info.uid;
    }

    /**
     * Parcelable.Creator that instantiates RemoteViews objects
     */
    public static final @android.annotation.NonNull Parcelable.Creator<RemoteViews> CREATOR = new Parcelable.Creator<RemoteViews>() {
        public RemoteViews createFromParcel(Parcel parcel) {
            return new RemoteViews(parcel);
        }

        public RemoteViews[] newArray(int size) {
            return new RemoteViews[size];
        }
    };

    /**
     * A representation of the view hierarchy. Only views which have a valid ID are added
     * and can be searched.
     */
    private static class ViewTree {
        private static final int INSERT_AT_END_INDEX = -1;
        private View mRoot;
        private ArrayList<ViewTree> mChildren;

        private ViewTree(View root) {
            mRoot = root;
        }

        public void createTree() {
            if (mChildren != null) {
                return;
            }

            mChildren = new ArrayList<>();
            if (mRoot instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) mRoot;
                int count = vg.getChildCount();
                for (int i = 0; i < count; i++) {
                    addViewChild(vg.getChildAt(i));
                }
            }
        }

        public ViewTree findViewTreeById(int id) {
            if (mRoot.getId() == id) {
                return this;
            }
            if (mChildren == null) {
                return null;
            }
            for (ViewTree tree : mChildren) {
                ViewTree result = tree.findViewTreeById(id);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }

        public void replaceView(View v) {
            mRoot = v;
            mChildren = null;
            createTree();
        }

        public <T extends View> T findViewById(int id) {
            if (mChildren == null) {
                return mRoot.findViewById(id);
            }
            ViewTree tree = findViewTreeById(id);
            return tree == null ? null : (T) tree.mRoot;
        }

        public void addChild(ViewTree child) {
            addChild(child, INSERT_AT_END_INDEX);
        }

        /**
         * Adds the given {@link ViewTree} as a child at the given index.
         *
         * @param index The position at which to add the child or -1 to add last.
         */
        public void addChild(ViewTree child, int index) {
            if (mChildren == null) {
                mChildren = new ArrayList<>();
            }
            child.createTree();

            if (index == INSERT_AT_END_INDEX) {
                mChildren.add(child);
                return;
            }

            mChildren.add(index, child);
        }

        private void addViewChild(View v) {
            // ViewTree only contains Views which can be found using findViewById.
            // If isRootNamespace is true, this view is skipped.
            // @see ViewGroup#findViewTraversal(int)
            if (v.isRootNamespace()) {
                return;
            }
            final ViewTree target;

            // If the view has a valid id, i.e., if can be found using findViewById, add it to the
            // tree, otherwise skip this view and add its children instead.
            if (v.getId() != 0) {
                ViewTree tree = new ViewTree(v);
                mChildren.add(tree);
                target = tree;
            } else {
                target = this;
            }

            if (v instanceof ViewGroup) {
                if (target.mChildren == null) {
                    target.mChildren = new ArrayList<>();
                    ViewGroup vg = (ViewGroup) v;
                    int count = vg.getChildCount();
                    for (int i = 0; i < count; i++) {
                        target.addViewChild(vg.getChildAt(i));
                    }
                }
            }
        }
    }

    /**
     * Class representing a response to an action performed on any element of a RemoteViews.
     */
    public static class RemoteResponse {

        private PendingIntent mPendingIntent;
        private Intent mFillIntent;

        private IntArray mViewIds;
        private ArrayList<String> mElementNames;

        /**
         * Creates a response which sends a pending intent as part of the response. The source
         * bounds ({@link Intent#getSourceBounds()}) of the intent will be set to the bounds of the
         * target view in screen space.
         * Note that any activity options associated with the mPendingIntent may get overridden
         * before starting the intent.
         *
         * @param pendingIntent The {@link PendingIntent} to send as part of the response
         */
        @NonNull
        public static RemoteResponse fromPendingIntent(@NonNull PendingIntent pendingIntent) {
            RemoteResponse response = new RemoteResponse();
            response.mPendingIntent = pendingIntent;
            return response;
        }

        /**
         * When using collections (eg. {@link ListView}, {@link StackView} etc.) in widgets, it is
         * very costly to set PendingIntents on the individual items, and is hence not permitted.
         * Instead a single PendingIntent template can be set on the collection, see {@link
         * RemoteViews#setPendingIntentTemplate(int, PendingIntent)}, and the individual on-click
         * action of a given item can be distinguished by setting a fillInIntent on that item. The
         * fillInIntent is then combined with the PendingIntent template in order to determine the
         * final intent which will be executed when the item is clicked. This works as follows: any
         * fields which are left blank in the PendingIntent template, but are provided by the
         * fillInIntent will be overwritten, and the resulting PendingIntent will be used. The rest
         * of the PendingIntent template will then be filled in with the associated fields that are
         * set in fillInIntent. See {@link Intent#fillIn(Intent, int)} for more details.
         * Creates a response which sends a pending intent as part of the response. The source
         * bounds ({@link Intent#getSourceBounds()}) of the intent will be set to the bounds of the
         * target view in screen space.
         * Note that any activity options associated with the mPendingIntent may get overridden
         * before starting the intent.
         *
         * @param fillIntent The intent which will be combined with the parent's PendingIntent in
         *                  order to determine the behavior of the response
         *
         * @see RemoteViews#setPendingIntentTemplate(int, PendingIntent)
         * @see RemoteViews#setOnClickFillInIntent(int, Intent)
         * @return
         */
        @NonNull
        public static RemoteResponse fromFillInIntent(@NonNull Intent fillIntent) {
            RemoteResponse response = new RemoteResponse();
            response.mFillIntent = fillIntent;
            return response;
        }

        /**
         * Adds a shared element to be transferred as part of the transition between Activities
         * using cross-Activity scene animations. The position of the first element will be used as
         * the epicenter for the exit Transition. The position of the associated shared element in
         * the launched Activity will be the epicenter of its entering Transition.
         *
         * @param viewId The id of the view to be shared as part of the transition
         * @param sharedElementName The shared element name for this view
         *
         * @see ActivityOptions#makeSceneTransitionAnimation(Activity, Pair[])
         */
        @NonNull
        public RemoteResponse addSharedElement(int viewId, @NonNull String sharedElementName) {
            if (mViewIds == null) {
                mViewIds = new IntArray();
                mElementNames = new ArrayList<>();
            }
            mViewIds.add(viewId);
            mElementNames.add(sharedElementName);
            return this;
        }

        private void writeToParcel(Parcel dest, int flags) {
            PendingIntent.writePendingIntentOrNullToParcel(mPendingIntent, dest);
            if (mPendingIntent == null) {
                // Only write the intent if pending intent is null
                dest.writeTypedObject(mFillIntent, flags);
            }
            dest.writeIntArray(mViewIds == null ? null : mViewIds.toArray());
            dest.writeStringList(mElementNames);
        }

        private void readFromParcel(Parcel parcel) {
            mPendingIntent = PendingIntent.readPendingIntentOrNullFromParcel(parcel);
            if (mPendingIntent == null) {
                mFillIntent = parcel.readTypedObject(Intent.CREATOR);
            }
            int[] viewIds = parcel.createIntArray();
            mViewIds = viewIds == null ? null : IntArray.wrap(viewIds);
            mElementNames = parcel.createStringArrayList();
        }

        private void handleViewClick(View v, OnClickHandler handler) {
            final PendingIntent pi;
            if (mPendingIntent != null) {
                pi = mPendingIntent;
            } else if (mFillIntent != null) {
                // Insure that this view is a child of an AdapterView
                View parent = (View) v.getParent();
                // Break the for loop on the first encounter of:
                //    1) an AdapterView,
                //    2) an AppWidgetHostView that is not a RemoteViewsFrameLayout, or
                //    3) a null parent.
                // 2) and 3) are unexpected and catch the case where a child is not
                // correctly parented in an AdapterView.
                while (parent != null && !(parent instanceof AdapterView<?>)
                        && !((parent instanceof AppWidgetHostView)
                        && !(parent instanceof RemoteViewsAdapter.RemoteViewsFrameLayout))) {
                    parent = (View) parent.getParent();
                }

                if (!(parent instanceof AdapterView<?>)) {
                    // Somehow they've managed to get this far without having
                    // and AdapterView as a parent.
                    Log.e(LOG_TAG, "Collection item doesn't have AdapterView parent");
                    return;
                }
                // Insure that a template pending intent has been set on an ancestor
                if (!(parent.getTag() instanceof PendingIntent)) {
                    Log.e(LOG_TAG, "Attempting setOnClickFillInIntent without"
                            + " calling setPendingIntentTemplate on parent.");
                    return;
                }

                pi = (PendingIntent) parent.getTag();
            } else {
                Log.e(LOG_TAG, "Response has neither pendingIntent nor fillInIntent");
                return;
            }

            handler.onClickHandler(v, pi, this);
        }

        /** @hide */
        public Pair<Intent, ActivityOptions> getLaunchOptions(View view) {
            Intent intent = mPendingIntent != null ? new Intent() : new Intent(mFillIntent);
            intent.setSourceBounds(getSourceBounds(view));

            ActivityOptions opts = null;

            Context context = view.getContext();
            if (context.getResources().getBoolean(
                    com.android.internal.R.bool.config_overrideRemoteViewsActivityTransition)) {
                TypedArray windowStyle = context.getTheme().obtainStyledAttributes(
                        com.android.internal.R.styleable.Window);
                int windowAnimations = windowStyle.getResourceId(
                        com.android.internal.R.styleable.Window_windowAnimationStyle, 0);
                TypedArray windowAnimationStyle = context.obtainStyledAttributes(
                        windowAnimations, com.android.internal.R.styleable.WindowAnimation);
                int enterAnimationId = windowAnimationStyle.getResourceId(com.android.internal.R
                        .styleable.WindowAnimation_activityOpenRemoteViewsEnterAnimation, 0);
                windowStyle.recycle();
                windowAnimationStyle.recycle();

                if (enterAnimationId != 0) {
                    opts = ActivityOptions.makeCustomAnimation(context,
                            enterAnimationId, 0);
                    opts.setPendingIntentLaunchFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
            }

            if (opts == null && mViewIds != null && mElementNames != null) {
                View parent = (View) view.getParent();
                while (parent != null && !(parent instanceof AppWidgetHostView)) {
                    parent = (View) parent.getParent();
                }
                if (parent instanceof AppWidgetHostView) {
                    opts = ((AppWidgetHostView) parent).createSharedElementActivityOptions(
                            mViewIds.toArray(),
                            mElementNames.toArray(new String[mElementNames.size()]), intent);
                }
            }

            if (opts == null) {
                opts = ActivityOptions.makeBasic();
                opts.setPendingIntentLaunchFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            return Pair.create(intent, opts);
        }
    }

    /** @hide */
    public static boolean startPendingIntent(View view, PendingIntent pendingIntent,
            Pair<Intent, ActivityOptions> options) {
        try {
            // TODO: Unregister this handler if PendingIntent.FLAG_ONE_SHOT?
            Context context = view.getContext();
            // The NEW_TASK flags are applied through the activity options and not as a part of
            // the call to startIntentSender() to ensure that they are consistently applied to
            // both mutable and immutable PendingIntents.
            context.startIntentSender(
                    pendingIntent.getIntentSender(), options.first,
                    0, 0, 0, options.second.toBundle());
        } catch (IntentSender.SendIntentException e) {
            Log.e(LOG_TAG, "Cannot send pending intent: ", e);
            return false;
        } catch (Exception e) {
            Log.e(LOG_TAG, "Cannot send pending intent due to unknown exception: ", e);
            return false;
        }
        return true;
    }
}
