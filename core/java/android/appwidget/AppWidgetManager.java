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

package android.appwidget;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.widget.RemoteViews;

import com.android.internal.appwidget.IAppWidgetService;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Updates AppWidget state; gets information about installed AppWidget providers and other
 * AppWidget related state.
 */
public class AppWidgetManager {
    static final String TAG = "AppWidgetManager";

    /**
     * Send this from your {@link AppWidgetHost} activity when you want to pick an AppWidget to display.
     * The AppWidget picker activity will be launched.
     * <p>
     * You must supply the following extras:
     * <table>
     *   <tr>
     *     <td>{@link #EXTRA_APPWIDGET_ID}</td>
     *     <td>A newly allocated appWidgetId, which will be bound to the AppWidget provider
     *         once the user has selected one.</td>
     *  </tr>
     * </table>
     *
     * <p>
     * The system will respond with an onActivityResult call with the following extras in
     * the intent:
     * <table>
     *   <tr>
     *     <td>{@link #EXTRA_APPWIDGET_ID}</td>
     *     <td>The appWidgetId that you supplied in the original intent.</td>
     *  </tr>
     * </table>
     * <p>
     * When you receive the result from the AppWidget pick activity, if the resultCode is
     * {@link android.app.Activity#RESULT_OK}, an AppWidget has been selected.  You should then
     * check the AppWidgetProviderInfo for the returned AppWidget, and if it has one, launch its configuration
     * activity.  If {@link android.app.Activity#RESULT_CANCELED} is returned, you should delete
     * the appWidgetId.
     *
     * @see #ACTION_APPWIDGET_CONFIGURE
     */
    public static final String ACTION_APPWIDGET_PICK = "android.appwidget.action.APPWIDGET_PICK";

    /**
     * Sent when it is time to configure your AppWidget while it is being added to a host.
     * This action is not sent as a broadcast to the AppWidget provider, but as a startActivity
     * to the activity specified in the {@link AppWidgetProviderInfo AppWidgetProviderInfo meta-data}.
     *
     * <p>
     * The intent will contain the following extras:
     * <table>
     *   <tr>
     *     <td>{@link #EXTRA_APPWIDGET_ID}</td>
     *     <td>The appWidgetId to configure.</td>
     *  </tr>
     * </table>
     *
     * <p>If you return {@link android.app.Activity#RESULT_OK} using
     * {@link android.app.Activity#setResult Activity.setResult()}, the AppWidget will be added,
     * and you will receive an {@link #ACTION_APPWIDGET_UPDATE} broadcast for this AppWidget.
     * If you return {@link android.app.Activity#RESULT_CANCELED}, the host will cancel the add
     * and not display this AppWidget, and you will receive a {@link #ACTION_APPWIDGET_DELETED} broadcast.
     */
    public static final String ACTION_APPWIDGET_CONFIGURE = "android.appwidget.action.APPWIDGET_CONFIGURE";

    /**
     * An intent extra that contains one appWidgetId.
     * <p>
     * The value will be an int that can be retrieved like this:
     * {@sample frameworks/base/tests/appwidgets/AppWidgetHostTest/src/com/android/tests/appwidgethost/AppWidgetHostActivity.java getExtra_EXTRA_APPWIDGET_ID}
     */
    public static final String EXTRA_APPWIDGET_ID = "appWidgetId";

    /**
     * An intent extra that contains multiple appWidgetIds.
     * <p>
     * The value will be an int array that can be retrieved like this:
     * {@sample frameworks/base/tests/appwidgets/AppWidgetHostTest/src/com/android/tests/appwidgethost/TestAppWidgetProvider.java getExtra_EXTRA_APPWIDGET_IDS}
     */
    public static final String EXTRA_APPWIDGET_IDS = "appWidgetIds";

    /**
     * An intent extra to pass to the AppWidget picker containing a {@link java.util.List} of
     * {@link AppWidgetProviderInfo} objects to mix in to the list of AppWidgets that are
     * installed.  (This is how the launcher shows the search widget).
     */
    public static final String EXTRA_CUSTOM_INFO = "customInfo";

    /**
     * An intent extra to pass to the AppWidget picker containing a {@link java.util.List} of
     * {@link android.os.Bundle} objects to mix in to the list of AppWidgets that are
     * installed.  It will be added to the extras object on the {@link android.content.Intent}
     * that is returned from the picker activity.
     *
     * {@more}
     */
    public static final String EXTRA_CUSTOM_EXTRAS = "customExtras";

    /**
     * A sentiel value that the AppWidget manager will never return as a appWidgetId.
     */
    public static final int INVALID_APPWIDGET_ID = 0;

    /**
     * Sent when it is time to update your AppWidget.
     *
     * <p>This may be sent in response to a new instance for this AppWidget provider having
     * been instantiated, the requested {@link AppWidgetProviderInfo#updatePeriodMillis update interval}
     * having lapsed, or the system booting.
     *
     * <p>
     * The intent will contain the following extras:
     * <table>
     *   <tr>
     *     <td>{@link #EXTRA_APPWIDGET_IDS}</td>
     *     <td>The appWidgetIds to update.  This may be all of the AppWidgets created for this
     *     provider, or just a subset.  The system tries to send updates for as few AppWidget
     *     instances as possible.</td>
     *  </tr>
     * </table>
     *
     * @see AppWidgetProvider#onUpdate AppWidgetProvider.onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds)
     */
    public static final String ACTION_APPWIDGET_UPDATE = "android.appwidget.action.APPWIDGET_UPDATE";

    /**
     * Sent when an instance of an AppWidget is deleted from its host.
     *
     * @see AppWidgetProvider#onDeleted AppWidgetProvider.onDeleted(Context context, int[] appWidgetIds)
     */
    public static final String ACTION_APPWIDGET_DELETED = "android.appwidget.action.APPWIDGET_DELETED";

    /**
     * Sent when an instance of an AppWidget is removed from the last host.
     *
     * @see AppWidgetProvider#onEnabled AppWidgetProvider.onEnabled(Context context)
     */
    public static final String ACTION_APPWIDGET_DISABLED = "android.appwidget.action.APPWIDGET_DISABLED";

    /**
     * Sent when an instance of an AppWidget is added to a host for the first time.
     * This broadcast is sent at boot time if there is a AppWidgetHost installed with
     * an instance for this provider.
     *
     * @see AppWidgetProvider#onEnabled AppWidgetProvider.onEnabled(Context context)
     */
    public static final String ACTION_APPWIDGET_ENABLED = "android.appwidget.action.APPWIDGET_ENABLED";

    /**
     * Field for the manifest meta-data tag.
     *
     * @see AppWidgetProviderInfo
     */
    public static final String META_DATA_APPWIDGET_PROVIDER = "android.appwidget.provider";

    static WeakHashMap<Context, WeakReference<AppWidgetManager>> sManagerCache =
        new WeakHashMap<Context, WeakReference<AppWidgetManager>>();
    static IAppWidgetService sService;

    Context mContext;

    private DisplayMetrics mDisplayMetrics;

    /**
     * Get the AppWidgetManager instance to use for the supplied {@link android.content.Context
     * Context} object.
     */
    public static AppWidgetManager getInstance(Context context) {
        synchronized (sManagerCache) {
            if (sService == null) {
                IBinder b = ServiceManager.getService(Context.APPWIDGET_SERVICE);
                sService = IAppWidgetService.Stub.asInterface(b);
            }

            WeakReference<AppWidgetManager> ref = sManagerCache.get(context);
            AppWidgetManager result = null;
            if (ref != null) {
                result = ref.get();
            }
            if (result == null) {
                result = new AppWidgetManager(context);
                sManagerCache.put(context, new WeakReference<AppWidgetManager>(result));
            }
            return result;
        }
    }

    private AppWidgetManager(Context context) {
        mContext = context;
        mDisplayMetrics = context.getResources().getDisplayMetrics();
    }

    /**
     * Set the RemoteViews to use for the specified appWidgetIds.
     *
     * Note that the RemoteViews parameter will be cached by the AppWidgetService, and hence should
     * contain a complete representation of the widget. For performing partial widget updates, see
     * {@link #partiallyUpdateAppWidget(int[], RemoteViews)}.
     *
     * <p>
     * It is okay to call this method both inside an {@link #ACTION_APPWIDGET_UPDATE} broadcast,
     * and outside of the handler.
     * This method will only work when called from the uid that owns the AppWidget provider.
     *
     * @param appWidgetIds     The AppWidget instances for which to set the RemoteViews.
     * @param views         The RemoteViews object to show.
     */
    public void updateAppWidget(int[] appWidgetIds, RemoteViews views) {
        try {
            sService.updateAppWidgetIds(appWidgetIds, views);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Set the RemoteViews to use for the specified appWidgetId.
     *
     * Note that the RemoteViews parameter will be cached by the AppWidgetService, and hence should
     * contain a complete representation of the widget. For performing partial widget updates, see
     * {@link #partiallyUpdateAppWidget(int, RemoteViews)}.
     *
     * <p>
     * It is okay to call this method both inside an {@link #ACTION_APPWIDGET_UPDATE} broadcast,
     * and outside of the handler.
     * This method will only work when called from the uid that owns the AppWidget provider.
     *
     * @param appWidgetId      The AppWidget instance for which to set the RemoteViews.
     * @param views         The RemoteViews object to show.
     */
    public void updateAppWidget(int appWidgetId, RemoteViews views) {
        updateAppWidget(new int[] { appWidgetId }, views);
    }

    /**
     * Perform an incremental update or command on the widget(s) specified by appWidgetIds.
     *
     * This update  differs from {@link #updateAppWidget(int[], RemoteViews)} in that the
     * RemoteViews object which is passed is understood to be an incomplete representation of the 
     * widget, and hence is not cached by the AppWidgetService. Note that because these updates are 
     * not cached, any state that they modify that is not restored by restoreInstanceState will not
     * persist in the case that the widgets are restored using the cached version in
     * AppWidgetService.
     *
     * Use with {@link RemoteViews#showNext(int)}, {@link RemoteViews#showPrevious(int)},
     * {@link RemoteViews#setScrollPosition(int, int)} and similar commands.
     *
     * <p>
     * It is okay to call this method both inside an {@link #ACTION_APPWIDGET_UPDATE} broadcast,
     * and outside of the handler.
     * This method will only work when called from the uid that owns the AppWidget provider.
     *
     * @param appWidgetIds     The AppWidget instances for which to set the RemoteViews.
     * @param views            The RemoteViews object containing the incremental update / command.
     */
    public void partiallyUpdateAppWidget(int[] appWidgetIds, RemoteViews views) {
        try {
            sService.partiallyUpdateAppWidgetIds(appWidgetIds, views);
        } catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Perform an incremental update or command on the widget specified by appWidgetId.
     *
     * This update  differs from {@link #updateAppWidget(int, RemoteViews)} in that the RemoteViews
     * object which is passed is understood to be an incomplete representation of the widget, and
     * hence is not cached by the AppWidgetService. Note that because these updates are not cached,
     * any state that they modify that is not restored by restoreInstanceState will not persist in
     * the case that the widgets are restored using the cached version in AppWidgetService.
     *
     * Use with {@link RemoteViews#showNext(int)}, {@link RemoteViews#showPrevious(int)},
     * {@link RemoteViews#setScrollPosition(int, int)} and similar commands.
     *
     * <p>
     * It is okay to call this method both inside an {@link #ACTION_APPWIDGET_UPDATE} broadcast,
     * and outside of the handler.
     * This method will only work when called from the uid that owns the AppWidget provider.
     *
     * @param appWidgetId      The AppWidget instance for which to set the RemoteViews.
     * @param views            The RemoteViews object containing the incremental update / command.
     */
    public void partiallyUpdateAppWidget(int appWidgetId, RemoteViews views) {
        partiallyUpdateAppWidget(new int[] { appWidgetId }, views);
    }

    /**
     * Set the RemoteViews to use for all AppWidget instances for the supplied AppWidget provider.
     *
     * <p>
     * It is okay to call this method both inside an {@link #ACTION_APPWIDGET_UPDATE} broadcast,
     * and outside of the handler.
     * This method will only work when called from the uid that owns the AppWidget provider.
     *
     * @param provider      The {@link ComponentName} for the {@link
     * android.content.BroadcastReceiver BroadcastReceiver} provider
     *                      for your AppWidget.
     * @param views         The RemoteViews object to show.
     */
    public void updateAppWidget(ComponentName provider, RemoteViews views) {
        try {
            sService.updateAppWidgetProvider(provider, views);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Notifies the specified collection view in all the specified AppWidget instances
     * to invalidate their currently data.
     *
     * @param appWidgetIds  The AppWidget instances for which to notify of view data changes.
     * @param viewId        The collection view id.
     */
    public void notifyAppWidgetViewDataChanged(int[] appWidgetIds, int viewId) {
        try {
            sService.notifyAppWidgetViewDataChanged(appWidgetIds, viewId);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Notifies the specified collection view in all the specified AppWidget instance
     * to invalidate it's currently data.
     *
     * @param appWidgetId  The AppWidget instance for which to notify of view data changes.
     * @param viewId        The collection view id.
     */
    public void notifyAppWidgetViewDataChanged(int appWidgetId, int viewId) {
        notifyAppWidgetViewDataChanged(new int[] { appWidgetId }, viewId);
    }

    /**
     * Return a list of the AppWidget providers that are currently installed.
     */
    public List<AppWidgetProviderInfo> getInstalledProviders() {
        try {
            List<AppWidgetProviderInfo> providers = sService.getInstalledProviders();
            for (AppWidgetProviderInfo info : providers) {
                // Converting complex to dp.
                info.minWidth =
                        TypedValue.complexToDimensionPixelSize(info.minWidth, mDisplayMetrics);
                info.minHeight =
                        TypedValue.complexToDimensionPixelSize(info.minHeight, mDisplayMetrics);
                info.minResizeWidth =
                    TypedValue.complexToDimensionPixelSize(info.minResizeWidth, mDisplayMetrics);
                info.minResizeHeight =
                    TypedValue.complexToDimensionPixelSize(info.minResizeHeight, mDisplayMetrics);
            }
            return providers;
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Get the available info about the AppWidget.
     *
     * @return A appWidgetId.  If the appWidgetId has not been bound to a provider yet, or
     * you don't have access to that appWidgetId, null is returned.
     */
    public AppWidgetProviderInfo getAppWidgetInfo(int appWidgetId) {
        try {
            AppWidgetProviderInfo info = sService.getAppWidgetInfo(appWidgetId);
            if (info != null) {
                // Converting complex to dp.
                info.minWidth =
                        TypedValue.complexToDimensionPixelSize(info.minWidth, mDisplayMetrics);
                info.minHeight =
                        TypedValue.complexToDimensionPixelSize(info.minHeight, mDisplayMetrics);
                info.minResizeWidth =
                    TypedValue.complexToDimensionPixelSize(info.minResizeWidth, mDisplayMetrics);
                info.minResizeHeight =
                    TypedValue.complexToDimensionPixelSize(info.minResizeHeight, mDisplayMetrics);
            }
            return info;
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Set the component for a given appWidgetId.
     *
     * <p class="note">You need the APPWIDGET_LIST permission.  This method is to be used by the
     * AppWidget picker.
     *
     * @param appWidgetId     The AppWidget instance for which to set the RemoteViews.
     * @param provider      The {@link android.content.BroadcastReceiver} that will be the AppWidget
     *                      provider for this AppWidget.
     */
    public void bindAppWidgetId(int appWidgetId, ComponentName provider) {
        try {
            sService.bindAppWidgetId(appWidgetId, provider);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Binds the RemoteViewsService for a given appWidgetId and intent.
     *
     * The appWidgetId specified must already be bound to the calling AppWidgetHost via
     * {@link android.appwidget.AppWidgetManager#bindAppWidgetId AppWidgetManager.bindAppWidgetId()}.
     *
     * @param appWidgetId   The AppWidget instance for which to bind the RemoteViewsService.
     * @param intent        The intent of the service which will be providing the data to the
     *                      RemoteViewsAdapter.
     * @param connection    The callback interface to be notified when a connection is made or lost.
     * @hide
     */
    public void bindRemoteViewsService(int appWidgetId, Intent intent, IBinder connection) {
        try {
            sService.bindRemoteViewsService(appWidgetId, intent, connection);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Unbinds the RemoteViewsService for a given appWidgetId and intent.
     *
     * The appWidgetId specified muse already be bound to the calling AppWidgetHost via
     * {@link android.appwidget.AppWidgetManager#bindAppWidgetId AppWidgetManager.bindAppWidgetId()}.
     *
     * @param appWidgetId   The AppWidget instance for which to bind the RemoteViewsService.
     * @param intent        The intent of the service which will be providing the data to the
     *                      RemoteViewsAdapter.
     * @hide
     */
    public void unbindRemoteViewsService(int appWidgetId, Intent intent) {
        try {
            sService.unbindRemoteViewsService(appWidgetId, intent);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Get the list of appWidgetIds that have been bound to the given AppWidget
     * provider.
     *
     * @param provider The {@link android.content.BroadcastReceiver} that is the
     *            AppWidget provider to find appWidgetIds for.
     */
    public int[] getAppWidgetIds(ComponentName provider) {
        try {
            return sService.getAppWidgetIds(provider);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }
}

