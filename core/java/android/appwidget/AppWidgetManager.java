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

import static android.appwidget.flags.Flags.remoteAdapterConversion;

import android.annotation.BroadcastBehavior;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UiThread;
import android.annotation.UserIdInt;
import android.app.IServiceConnection;
import android.app.PendingIntent;
import android.appwidget.flags.Flags;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ShortcutInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.RemoteViews;

import com.android.internal.appwidget.IAppWidgetService;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.FunctionalUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Updates AppWidget state; gets information about installed AppWidget providers and other
 * AppWidget related state.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about creating app widgets, read the
 * <a href="{@docRoot}guide/topics/appwidgets/index.html">App Widgets</a> developer guide.</p>
 * </div>
 */
@SystemService(Context.APPWIDGET_SERVICE)
@RequiresFeature(PackageManager.FEATURE_APP_WIDGETS)
public class AppWidgetManager {


    /**
     * Activity action to launch from your {@link AppWidgetHost} activity when you want to
     * pick an AppWidget to display.  The AppWidget picker activity will be launched.
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
     * check the AppWidgetProviderInfo for the returned AppWidget, and if it has one, launch its
     * configuration activity.  If {@link android.app.Activity#RESULT_CANCELED} is returned, you
     * should delete the appWidgetId.
     *
     * @see #ACTION_APPWIDGET_CONFIGURE
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APPWIDGET_PICK = "android.appwidget.action.APPWIDGET_PICK";

    /**
     * Similar to ACTION_APPWIDGET_PICK, but used from keyguard
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String
            ACTION_KEYGUARD_APPWIDGET_PICK = "android.appwidget.action.KEYGUARD_APPWIDGET_PICK";

    /**
     * Activity action to launch from your {@link AppWidgetHost} activity when you want to bind
     * an AppWidget to display and bindAppWidgetIdIfAllowed returns false.
     * <p>
     * You must supply the following extras:
     * <table>
     *   <tr>
     *     <td>{@link #EXTRA_APPWIDGET_ID}</td>
     *     <td>A newly allocated appWidgetId, which will be bound to the AppWidget provider
     *         you provide.</td>
     *  </tr>
     *  <tr>
     *     <td>{@link #EXTRA_APPWIDGET_PROVIDER}</td>
     *     <td>The BroadcastReceiver that will be the AppWidget provider for this AppWidget.
     *     </td>
     *  </tr>
     *  <tr>
     *     <td>{@link #EXTRA_APPWIDGET_PROVIDER_PROFILE}</td>
     *     <td>An optional handle to a user profile under which runs the provider
     *     for this AppWidget.
     *     </td>
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
     * When you receive the result from the AppWidget bind activity, if the resultCode is
     * {@link android.app.Activity#RESULT_OK}, the AppWidget has been bound.  You should then
     * check the AppWidgetProviderInfo for the returned AppWidget, and if it has one, launch its
     * configuration activity.  If {@link android.app.Activity#RESULT_CANCELED} is returned, you
     * should delete the appWidgetId.
     *
     * @see #ACTION_APPWIDGET_CONFIGURE
     *
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APPWIDGET_BIND = "android.appwidget.action.APPWIDGET_BIND";

    /**
     * Sent when it is time to configure your AppWidget while it is being added to a host.
     * This action is not sent as a broadcast to the AppWidget provider, but as a startActivity
     * to the activity specified in the {@link AppWidgetProviderInfo AppWidgetProviderInfo
     * meta-data}.
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
     * and not display this AppWidget, and you will receive a {@link #ACTION_APPWIDGET_DELETED}
     * broadcast.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APPWIDGET_CONFIGURE = "android.appwidget.action.APPWIDGET_CONFIGURE";

    /**
     * An intent extra (int) that contains one appWidgetId.
     * <p>
     * The value will be an int that can be retrieved like this:
     * {@sample frameworks/base/tests/appwidgets/AppWidgetHostTest/src/com/android/tests/appwidgethost/AppWidgetHostActivity.java getExtra_EXTRA_APPWIDGET_ID}
     */
    public static final String EXTRA_APPWIDGET_ID = "appWidgetId";

    /**
     * A bundle extra (boolean) that contains whether or not an app has finished restoring a widget.
     * <p> After restore, the app should set OPTION_APPWIDGET_RESTORE_COMPLETED to true on its
     * widgets followed by calling {@link #updateAppWidget} to update the views.
     *
     * @see #updateAppWidgetOptions(int, Bundle)
     */
    public static final String OPTION_APPWIDGET_RESTORE_COMPLETED = "appWidgetRestoreCompleted";


    /**
     * A bundle extra (int) that contains the lower bound on the current width, in dips, of a
     * widget instance.
     */
    public static final String OPTION_APPWIDGET_MIN_WIDTH = "appWidgetMinWidth";

    /**
     * A bundle extra (int) that contains the lower bound on the current height, in dips, of a
     * widget instance.
     */
    public static final String OPTION_APPWIDGET_MIN_HEIGHT = "appWidgetMinHeight";

    /**
     * A bundle extra (int) that contains the upper bound on the current width, in dips, of a
     * widget instance.
     */
    public static final String OPTION_APPWIDGET_MAX_WIDTH = "appWidgetMaxWidth";

    /**
     * A bundle extra (int) that contains the upper bound on the current width, in dips, of a
     * widget instance.
     */
    public static final String OPTION_APPWIDGET_MAX_HEIGHT = "appWidgetMaxHeight";

    /**
     * A bundle extra ({@code List<SizeF>}) that contains the list of possible sizes, in dips, a
     * widget instance can take.
     */
    public static final String OPTION_APPWIDGET_SIZES = "appWidgetSizes";

    /**
     * A bundle extra that hints to the AppWidgetProvider the category of host that owns this
     * this widget. Can have the value {@link
     * AppWidgetProviderInfo#WIDGET_CATEGORY_HOME_SCREEN} or {@link
     * AppWidgetProviderInfo#WIDGET_CATEGORY_KEYGUARD} or {@link
     * AppWidgetProviderInfo#WIDGET_CATEGORY_SEARCHBOX}.
     */
    public static final String OPTION_APPWIDGET_HOST_CATEGORY = "appWidgetCategory";

    /**
     * An intent extra which points to a bundle of extra information for a particular widget id.
     * In particular this bundle can contain {@link #OPTION_APPWIDGET_MIN_WIDTH},
     * {@link #OPTION_APPWIDGET_MIN_HEIGHT}, {@link #OPTION_APPWIDGET_MAX_WIDTH},
     * {@link #OPTION_APPWIDGET_MAX_HEIGHT}.
     */
    public static final String EXTRA_APPWIDGET_OPTIONS = "appWidgetOptions";

    /**
     * An intent extra that contains multiple appWidgetIds.
     * <p>
     * The value will be an int array that can be retrieved like this:
     * {@sample frameworks/base/tests/appwidgets/AppWidgetHostTest/src/com/android/tests/appwidgethost/TestAppWidgetProvider.java getExtra_EXTRA_APPWIDGET_IDS}
     */
    public static final String EXTRA_APPWIDGET_IDS = "appWidgetIds";

    /**
     * An intent extra that contains the component name of a AppWidget provider.
     * <p>
     * The value will be an {@link android.content.ComponentName}.
     */
    public static final String EXTRA_APPWIDGET_PROVIDER = "appWidgetProvider";

    /**
     * An intent extra that contains the user handle of the profile under
     * which an AppWidget provider is registered.
     * <p>
     * The value will be a {@link android.os.UserHandle}.
     */
    public static final String EXTRA_APPWIDGET_PROVIDER_PROFILE = "appWidgetProviderProfile";

    /**
     * An intent extra to pass to the AppWidget picker containing a {@link java.util.List} of
     * {@link AppWidgetProviderInfo} objects to mix in to the list of AppWidgets that are
     * installed.  (This is how the launcher shows the search widget).
     */
    public static final String EXTRA_CUSTOM_INFO = "customInfo";

    /**
     * An intent extra attached to the {@link #ACTION_APPWIDGET_HOST_RESTORED} broadcast,
     * indicating the integer ID of the host whose widgets have just been restored.
     */
    public static final String EXTRA_HOST_ID = "hostId";

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
     * An intent extra to pass to the AppWidget picker which allows the picker to filter
     * the list based on the {@link AppWidgetProviderInfo#widgetCategory}.
     *
     * @hide
     */
    public static final String EXTRA_CATEGORY_FILTER = "categoryFilter";

    /**
     * An intent extra to pass to the AppWidget picker to specify whether or not to sort
     * the list of caller-specified extra AppWidgets along with the rest of the AppWidgets
     * @hide
     */
    public static final String EXTRA_CUSTOM_SORT = "customSort";

    /**
     * A sentinel value that the AppWidget manager will never return as a appWidgetId.
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
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_APPWIDGET_UPDATE = "android.appwidget.action.APPWIDGET_UPDATE";

    /**
     * A combination broadcast of APPWIDGET_ENABLED and APPWIDGET_UPDATE.
     * Sent during boot time and when the host is binding the widget for the very first time
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_APPWIDGET_ENABLE_AND_UPDATE = "android.appwidget.action"
            + ".APPWIDGET_ENABLE_AND_UPDATE";

    /**
     * Sent when the custom extras for an AppWidget change.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * @see AppWidgetProvider#onAppWidgetOptionsChanged
     *      AppWidgetProvider.onAppWidgetOptionsChanged(Context context,
     *      AppWidgetManager appWidgetManager, int appWidgetId, Bundle newExtras)
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_APPWIDGET_OPTIONS_CHANGED = "android.appwidget.action.APPWIDGET_UPDATE_OPTIONS";

    /**
     * Sent when an instance of an AppWidget is deleted from its host.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * @see AppWidgetProvider#onDeleted AppWidgetProvider.onDeleted(Context context, int[] appWidgetIds)
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_APPWIDGET_DELETED = "android.appwidget.action.APPWIDGET_DELETED";

    /**
     * Sent when the last AppWidget of this provider is removed from the last host.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * @see AppWidgetProvider#onEnabled AppWidgetProvider.onDisabled(Context context)
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_APPWIDGET_DISABLED = "android.appwidget.action.APPWIDGET_DISABLED";

    /**
     * Sent when an instance of an AppWidget is added to a host for the first time.
     * This broadcast is sent at boot time if there is a AppWidgetHost installed with
     * an instance for this provider.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * @see AppWidgetProvider#onEnabled AppWidgetProvider.onEnabled(Context context)
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_APPWIDGET_ENABLED = "android.appwidget.action.APPWIDGET_ENABLED";

    /**
     * Sent to an {@link AppWidgetProvider} after AppWidget state related to that provider has
     * been restored from backup. The intent contains information about how to translate AppWidget
     * ids from the restored data to their new equivalents.
     *
     * <p>The intent will contain the following extras:
     *
     * <table>
     *   <tr>
     *     <td>{@link #EXTRA_APPWIDGET_OLD_IDS}</td>
     *     <td>The set of appWidgetIds represented in a restored backup that have been successfully
     *     incorporated into the current environment.  This may be all of the AppWidgets known
     *     to this application, or just a subset.  Each entry in this array of appWidgetIds has
     *     a corresponding entry in the {@link #EXTRA_APPWIDGET_IDS} extra.</td>
     *  </tr>
     *   <tr>
     *     <td>{@link #EXTRA_APPWIDGET_IDS}</td>
     *     <td>The set of appWidgetIds now valid for this application.  The app should look at
     *     its restored widget configuration and translate each appWidgetId in the
     *     {@link #EXTRA_APPWIDGET_OLD_IDS} array to its new value found at the corresponding
     *     index within this array.</td>
     *  </tr>
     * </table>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * @see #ACTION_APPWIDGET_HOST_RESTORED
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_APPWIDGET_RESTORED
            = "android.appwidget.action.APPWIDGET_RESTORED";

    /**
     * Sent to widget hosts after AppWidget state related to the host has been restored from
     * backup. The intent contains information about how to translate AppWidget ids from the
     * restored data to their new equivalents.  If an application maintains multiple separate
     * widget host instances, it will receive this broadcast separately for each one.
     *
     * <p>The intent will contain the following extras:
     *
     * <table>
     *   <tr>
     *     <td>{@link #EXTRA_APPWIDGET_OLD_IDS}</td>
     *     <td>The set of appWidgetIds represented in a restored backup that have been successfully
     *     incorporated into the current environment.  This may be all of the AppWidgets known
     *     to this application, or just a subset.  Each entry in this array of appWidgetIds has
     *     a corresponding entry in the {@link #EXTRA_APPWIDGET_IDS} extra.</td>
     *  </tr>
     *   <tr>
     *     <td>{@link #EXTRA_APPWIDGET_IDS}</td>
     *     <td>The set of appWidgetIds now valid for this application.  The app should look at
     *     its restored widget configuration and translate each appWidgetId in the
     *     {@link #EXTRA_APPWIDGET_OLD_IDS} array to its new value found at the corresponding
     *     index within this array.</td>
     *  </tr>
     *  <tr>
     *     <td>{@link #EXTRA_HOST_ID}</td>
     *     <td>The integer ID of the widget host instance whose state has just been restored.</td>
     *  </tr>
     * </table>
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * @see #ACTION_APPWIDGET_RESTORED
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_APPWIDGET_HOST_RESTORED
            = "android.appwidget.action.APPWIDGET_HOST_RESTORED";

    private static final String TAG = "AppWidgetManager";

    private static Executor sUpdateExecutor;

    /**
     * An intent extra that contains multiple appWidgetIds.  These are id values as
     * they were provided to the application during a recent restore from backup.  It is
     * attached to the {@link #ACTION_APPWIDGET_RESTORED} broadcast intent.
     *
     * <p>
     * The value will be an int array that can be retrieved like this:
     * {@sample frameworks/base/tests/appwidgets/AppWidgetHostTest/src/com/android/tests/appwidgethost/TestAppWidgetProvider.java getExtra_EXTRA_APPWIDGET_IDS}
     */
    public static final String EXTRA_APPWIDGET_OLD_IDS = "appWidgetOldIds";

    /**
     * An extra that can be passed to
     * {@link #requestPinAppWidget(ComponentName, Bundle, PendingIntent)}. This would allow the
     * launcher app to present a custom preview to the user.
     *
     * <p>
     * The value should be a {@link RemoteViews} similar to what is used with
     * {@link #updateAppWidget} calls.
     */
    public static final String EXTRA_APPWIDGET_PREVIEW = "appWidgetPreview";

    /**
     * Field for the manifest meta-data tag.
     *
     * @see AppWidgetProviderInfo
     */
    public static final String META_DATA_APPWIDGET_PROVIDER = "android.appwidget.provider";

    private final Context mContext;
    private final String mPackageName;
    @UnsupportedAppUsage
    private final IAppWidgetService mService;
    private final DisplayMetrics mDisplayMetrics;

    private boolean mHasPostedLegacyLists = false;

    /**
     * Get the AppWidgetManager instance to use for the supplied {@link android.content.Context
     * Context} object.
     */
    public static AppWidgetManager getInstance(Context context) {
        return (AppWidgetManager) context.getSystemService(Context.APPWIDGET_SERVICE);
    }

    /**
     * Creates a new instance.
     *
     * @param context The current context in which to operate.
     * @param service The backing system service.
     * @hide
     */
    public AppWidgetManager(Context context, IAppWidgetService service) {
        mContext = context;
        mPackageName = context.getOpPackageName();
        mService = service;
        mDisplayMetrics = context.getResources().getDisplayMetrics();
        if (mService == null) {
            return;
        }
        BackgroundThread.getExecutor().execute(() -> {
            try {
                mService.notifyProviderInheritance(getInstalledProvidersForPackage(mPackageName,
                        null)
                        .stream().filter(Objects::nonNull)
                        .map(info -> info.provider).filter(p -> {
                            try {
                                Class clazz = Class.forName(p.getClassName());
                                return AppWidgetProvider.class.isAssignableFrom(clazz);
                            } catch (Exception e) {
                                return false;
                            }
                        }).toArray(ComponentName[]::new));
            } catch (Exception e) {
                Log.e(TAG, "Notify service of inheritance info", e);
            }
        });
    }

    private void tryAdapterConversion(
            FunctionalUtils.RemoteExceptionIgnoringConsumer<RemoteViews> action,
            RemoteViews original, String failureMsg) {
        if (remoteAdapterConversion()
                && (mHasPostedLegacyLists = mHasPostedLegacyLists
                        || (original != null && original.hasLegacyLists()))) {
            final RemoteViews viewsCopy = new RemoteViews(original);
            Runnable updateWidgetWithTask = () -> {
                try {
                    viewsCopy.collectAllIntents().get();
                    action.acceptOrThrow(viewsCopy);
                } catch (Exception e) {
                    Log.e(TAG, failureMsg, e);
                }
            };

            if (Looper.getMainLooper() == Looper.myLooper()) {
                createUpdateExecutorIfNull().execute(updateWidgetWithTask);
                return;
            }

            updateWidgetWithTask.run();
        } else {
            try {
                action.acceptOrThrow(original);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Set the RemoteViews to use for the specified appWidgetIds.
     * <p>
     * Note that the RemoteViews parameter will be cached by the AppWidgetService, and hence should
     * contain a complete representation of the widget. For performing partial widget updates, see
     * {@link #partiallyUpdateAppWidget(int[], RemoteViews)}.
     *
     * <p>
     * It is okay to call this method both inside an {@link #ACTION_APPWIDGET_UPDATE} broadcast,
     * and outside of the handler.
     * This method will only work when called from the uid that owns the AppWidget provider.
     *
     * <p>
     * The total Bitmap memory used by the RemoteViews object cannot exceed that required to
     * fill the screen 1.5 times, ie. (screen width x screen height x 4 x 1.5) bytes.
     *
     * @param appWidgetIds The AppWidget instances for which to set the RemoteViews.
     * @param views The RemoteViews object to show.
     */
    public void updateAppWidget(int[] appWidgetIds, RemoteViews views) {
        if (mService == null) {
            return;
        }

        tryAdapterConversion(view -> mService.updateAppWidgetIds(mPackageName, appWidgetIds,
                view), views, "Error updating app widget views in background");
    }

    /**
     * Update the extras for a given widget instance.
     * <p>
     * The extras can be used to embed additional information about this widget to be accessed
     * by the associated widget's AppWidgetProvider.
     *
     * <p>
     * The new options are merged into existing options using {@link Bundle#putAll} semantics.
     *
     * @see #getAppWidgetOptions(int)
     *
     * @param appWidgetId The AppWidget instances for which to set the RemoteViews.
     * @param options The options to associate with this widget
     */
    public void updateAppWidgetOptions(int appWidgetId, Bundle options) {
        if (mService == null) {
            return;
        }
        try {
            mService.updateAppWidgetOptions(mPackageName, appWidgetId, options);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the extras associated with a given widget instance.
     * <p>
     * The extras can be used to embed additional information about this widget to be accessed
     * by the associated widget's AppWidgetProvider.
     *
     * @see #updateAppWidgetOptions(int, Bundle)
     *
     * @param appWidgetId The AppWidget instances for which to set the RemoteViews.
     * @return The options associated with the given widget instance.
     */
    public Bundle getAppWidgetOptions(int appWidgetId) {
        if (mService == null) {
            return Bundle.EMPTY;
        }
        try {
            return mService.getAppWidgetOptions(mPackageName, appWidgetId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the RemoteViews to use for the specified appWidgetId.
     * <p>
     * Note that the RemoteViews parameter will be cached by the AppWidgetService, and hence should
     * contain a complete representation of the widget. For performing partial widget updates, see
     * {@link #partiallyUpdateAppWidget(int, RemoteViews)}.
     *
     * <p>
     * It is okay to call this method both inside an {@link #ACTION_APPWIDGET_UPDATE} broadcast,
     * and outside of the handler.
     * This method will only work when called from the uid that owns the AppWidget provider.
     *
     * <p>
     * The total Bitmap memory used by the RemoteViews object cannot exceed that required to
     * fill the screen 1.5 times, ie. (screen width x screen height x 4 x 1.5) bytes.
     *
     * @param appWidgetId      The AppWidget instance for which to set the RemoteViews.
     * @param views         The RemoteViews object to show.
     */
    public void updateAppWidget(int appWidgetId, RemoteViews views) {
        if (mService == null) {
            return;
        }
        updateAppWidget(new int[] { appWidgetId }, views);
    }

    /**
     * Perform an incremental update or command on the widget(s) specified by appWidgetIds.
     * <p>
     * This update  differs from {@link #updateAppWidget(int[], RemoteViews)} in that the
     * RemoteViews object which is passed is understood to be an incomplete representation of the
     * widget, and hence does not replace the cached representation of the widget. As of API
     * level 17, the new properties set within the views objects will be appended to the cached
     * representation of the widget, and hence will persist.
     *
     * Use with {@link RemoteViews#showNext(int)}, {@link RemoteViews#showPrevious(int)},
     * {@link RemoteViews#setScrollPosition(int, int)} and similar commands.
     *
     * <p>
     * It is okay to call this method both inside an {@link #ACTION_APPWIDGET_UPDATE} broadcast,
     * and outside of the handler.
     * This method will only work when called from the uid that owns the AppWidget provider.
     *
     * <p>
     * This method will be ignored if a widget has not received a full update via
     * {@link #updateAppWidget(int[], RemoteViews)}.
     *
     * @param appWidgetIds     The AppWidget instances for which to set the RemoteViews.
     * @param views            The RemoteViews object containing the incremental update / command.
     */
    public void partiallyUpdateAppWidget(int[] appWidgetIds, RemoteViews views) {
        if (mService == null) {
            return;
        }

        tryAdapterConversion(view -> mService.partiallyUpdateAppWidgetIds(mPackageName,
                appWidgetIds, view), views,
                "Error partially updating app widget views in background");
    }

    /**
     * Perform an incremental update or command on the widget specified by appWidgetId.
     * <p>
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
     * <p>
     * This method will be ignored if a widget has not received a full update via
     * {@link #updateAppWidget(int[], RemoteViews)}.
     *
     * @param appWidgetId      The AppWidget instance for which to set the RemoteViews.
     * @param views            The RemoteViews object containing the incremental update / command.
     */
    public void partiallyUpdateAppWidget(int appWidgetId, RemoteViews views) {
        if (mService == null) {
            return;
        }
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
        if (mService == null) {
            return;
        }

        tryAdapterConversion(view -> mService.updateAppWidgetProvider(provider, view), views,
                "Error updating app widget view using provider in background");
    }

    /**
     * Updates the info for the supplied AppWidget provider. Apps can use this to change the default
     * behavior of the widget based on the state of the app (for e.g., if the user is logged in
     * or not). Calling this API completely replaces the previous definition.
     *
     * <p>
     * The manifest entry of the provider should contain an additional meta-data tag similar to
     * {@link #META_DATA_APPWIDGET_PROVIDER} which should point to any alternative definitions for
     * the provider.
     *
     * <p>
     * This is persisted across device reboots and app updates. If this meta-data key is not
     * present in the manifest entry, the info reverts to default.
     *
     * @param provider {@link ComponentName} for the {@link
     *    android.content.BroadcastReceiver BroadcastReceiver} provider for your AppWidget.
     * @param metaDataKey key for the meta-data tag pointing to the new provider info. Use null
     *    to reset any previously set info.
     */
    public void updateAppWidgetProviderInfo(ComponentName provider, @Nullable String metaDataKey) {
        if (mService == null) {
            return;
        }
        try {
            mService.updateAppWidgetProviderInfo(provider, metaDataKey);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies the specified collection view in all the specified AppWidget instances
     * to invalidate their data.
     *
     * @param appWidgetIds  The AppWidget instances to notify of view data changes.
     * @param viewId        The collection view id.
     * @deprecated The corresponding API
     * {@link RemoteViews#setRemoteAdapter(int, Intent)} associated with this method has been
     * deprecated. Moving forward please use
     * {@link RemoteViews#setRemoteAdapter(int, android.widget.RemoteViews.RemoteCollectionItems)}
     * instead to set {@link android.widget.RemoteViews.RemoteCollectionItems} for the remote
     * adapter and update the widget views by calling {@link #updateAppWidget(int[], RemoteViews)},
     * {@link #updateAppWidget(int, RemoteViews)},
     * {@link #updateAppWidget(ComponentName, RemoteViews)},
     * {@link #partiallyUpdateAppWidget(int[], RemoteViews)},
     * or {@link #partiallyUpdateAppWidget(int, RemoteViews)}, whichever applicable.
     */
    @Deprecated
    public void notifyAppWidgetViewDataChanged(int[] appWidgetIds, int viewId) {
        if (mService == null) {
            return;
        }

        if (remoteAdapterConversion()) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                mHasPostedLegacyLists = true;
                createUpdateExecutorIfNull().execute(() -> notifyCollectionWidgetChange(
                        appWidgetIds, viewId));
            } else {
                notifyCollectionWidgetChange(appWidgetIds, viewId);
            }
        } else {
            try {
                mService.notifyAppWidgetViewDataChanged(mPackageName, appWidgetIds, viewId);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    private void notifyCollectionWidgetChange(int[] appWidgetIds, int viewId) {
        try {
            List<CompletableFuture<Void>> updateFutures = new ArrayList<>();
            for (int i = 0; i < appWidgetIds.length; i++) {
                final int widgetId = appWidgetIds[i];
                updateFutures.add(CompletableFuture.runAsync(() -> {
                    try {
                        RemoteViews views = mService.getAppWidgetViews(mPackageName, widgetId);
                        if (views.replaceRemoteCollections(viewId)) {
                            updateAppWidget(widgetId, views);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error notifying changes in RemoteViews", e);
                    }
                }));
            }
            CompletableFuture.allOf(updateFutures.toArray(CompletableFuture[]::new)).join();
        } catch (Exception e) {
            Log.e(TAG, "Error notifying changes for all widgets", e);
        }
    }

    /**
     * Notifies the specified collection view in the specified AppWidget instance
     * to invalidate its data.
     *
     * @param appWidgetId  The AppWidget instance to notify of view data changes.
     * @param viewId       The collection view id.
     * @deprecated The corresponding API
     * {@link RemoteViews#setRemoteAdapter(int, Intent)} associated with this method has been
     * deprecated. Moving forward please use
     * {@link RemoteViews#setRemoteAdapter(int, android.widget.RemoteViews.RemoteCollectionItems)}
     * instead to set {@link android.widget.RemoteViews.RemoteCollectionItems} for the remote
     * adapter and update the widget views by calling {@link #updateAppWidget(int[], RemoteViews)},
     * {@link #updateAppWidget(int, RemoteViews)},
     * {@link #updateAppWidget(ComponentName, RemoteViews)},
     * {@link #partiallyUpdateAppWidget(int[], RemoteViews)},
     * or {@link #partiallyUpdateAppWidget(int, RemoteViews)}, whichever applicable.
     */
    @Deprecated
    public void notifyAppWidgetViewDataChanged(int appWidgetId, int viewId) {
        if (mService == null) {
            return;
        }
        notifyAppWidgetViewDataChanged(new int[] { appWidgetId }, viewId);
    }

    /**
     * Gets the AppWidget providers for the given user profile. User profile can only
     * be the current user or a profile of the current user. For example, the current
     * user may have a corporate profile. In this case the parent user profile has a
     * child profile, the corporate one.
     *
     * @param profile The profile for which to get providers. Passing null is equivalent
     *        to querying for only the calling user.
     * @return The installed providers, or an empty list if none are found for the given user.
     *
     * @see android.os.Process#myUserHandle()
     * @see android.os.UserManager#getUserProfiles()
     */
    public @NonNull List<AppWidgetProviderInfo> getInstalledProvidersForProfile(
            @Nullable UserHandle profile) {
        if (mService == null) {
            return Collections.emptyList();
        }
        return getInstalledProvidersForProfile(AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                profile, null);
    }

    /**
     * Gets the AppWidget providers for the given package and user profile. User
     * profile can only be the current user or a profile of the current user. For
     * example, the current user may have a corporate profile. In this case the
     * parent user profile has a child profile, the corporate one.
     *
     * @param packageName The package for which to get providers. If null, this method is
     *        equivalent to {@link #getInstalledProvidersForProfile(UserHandle)}.
     * @param profile The profile for which to get providers. Passing null is equivalent
     *        to querying for only the calling user.
     * @return The installed providers, or an empty list if none are found for the given
     *         package and user.
     * @throws NullPointerException if the provided package name is null
     *
     * @see android.os.Process#myUserHandle()
     * @see android.os.UserManager#getUserProfiles()
     */
    public @NonNull List<AppWidgetProviderInfo> getInstalledProvidersForPackage(
            @NonNull String packageName, @Nullable UserHandle profile) {
        if (packageName == null) {
            throw new NullPointerException("A non-null package must be passed to this method. " +
                    "If you want all widgets regardless of package, see " +
                    "getInstalledProvidersForProfile(UserHandle)");
        }
        if (mService == null) {
            return Collections.emptyList();
        }
        return getInstalledProvidersForProfile(AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                profile, packageName);
    }

    /**
     * Return a list of the AppWidget providers that are currently installed.
     */
    public List<AppWidgetProviderInfo> getInstalledProviders() {
        if (mService == null) {
            return Collections.emptyList();
        }
        return getInstalledProvidersForProfile(AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                null, null);
    }

    /**
     * Gets the AppWidget providers for the current user.
     *
     * @param categoryFilter Will only return providers which register as any of the specified
     *        specified categories. See {@link AppWidgetProviderInfo#widgetCategory}.
     * @return The intalled providers.
     *
     * @see android.os.Process#myUserHandle()
     * @see android.os.UserManager#getUserProfiles()
     *
     * @hide
     */
    @UnsupportedAppUsage
    public List<AppWidgetProviderInfo> getInstalledProviders(int categoryFilter) {
        if (mService == null) {
            return Collections.emptyList();
        }
        return getInstalledProvidersForProfile(categoryFilter, null, null);
    }

    /**
     * Gets the AppWidget providers for the given user profile. User profile can only
     * be the current user or a profile of the current user. For example, the current
     * user may have a corporate profile. In this case the parent user profile has a
     * child profile, the corporate one.
     *
     * @param categoryFilter Will only return providers which register as any of the specified
     *        specified categories. See {@link AppWidgetProviderInfo#widgetCategory}.
     * @param profile A profile of the current user which to be queried. The user
     *        is itself also a profile. If null, the providers only for the current user
     *        are returned.
     * @param packageName If specified, will only return providers from the given package.
     * @return The intalled providers.
     *
     * @see android.os.Process#myUserHandle()
     * @see android.os.UserManager#getUserProfiles()
     *
     * @hide
     */
    @UnsupportedAppUsage
    public List<AppWidgetProviderInfo> getInstalledProvidersForProfile(int categoryFilter,
            @Nullable UserHandle profile, @Nullable String packageName) {
        if (mService == null) {
            return Collections.emptyList();
        }

        if (profile == null) {
            profile = mContext.getUser();
        }

        try {
            ParceledListSlice<AppWidgetProviderInfo> providers = mService.getInstalledProvidersForProfile(
                    categoryFilter, profile.getIdentifier(), packageName);
            if (providers == null) {
                return Collections.emptyList();
            }
            for (AppWidgetProviderInfo info : providers.getList()) {
                // Converting complex to dp.
                info.updateDimensions(mDisplayMetrics);
            }
            return providers.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the available info about the AppWidget.
     *
     * @return A appWidgetId.  If the appWidgetId has not been bound to a provider yet, or
     * you don't have access to that appWidgetId, null is returned.
     */
    public AppWidgetProviderInfo getAppWidgetInfo(int appWidgetId) {
        if (mService == null) {
            return null;
        }
        try {
            AppWidgetProviderInfo info = mService.getAppWidgetInfo(mPackageName, appWidgetId);
            if (info != null) {
                // Converting complex to dp.
                info.updateDimensions(mDisplayMetrics);
            }
            return info;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the component for a given appWidgetId.
     *
     * <p class="note">You need the BIND_APPWIDGET permission or the user must have enabled binding
     *         widgets always for your component. This method is used by the AppWidget picker and
     *         should not be used by other apps.
     *
     * @param appWidgetId     The AppWidget instance for which to set the RemoteViews.
     * @param provider      The {@link android.content.BroadcastReceiver} that will be the AppWidget
     *                      provider for this AppWidget.
     * @hide
     */
    @UnsupportedAppUsage
    public void bindAppWidgetId(int appWidgetId, ComponentName provider) {
        if (mService == null) {
            return;
        }
        bindAppWidgetId(appWidgetId, provider, null);
    }

    /**
     * Set the component for a given appWidgetId.
     *
     * <p class="note">You need the BIND_APPWIDGET permission or the user must have enabled binding
     *         widgets always for your component. This method is used by the AppWidget picker and
     *         should not be used by other apps.
     *
     * @param appWidgetId     The AppWidget instance for which to set the RemoteViews.
     * @param provider      The {@link android.content.BroadcastReceiver} that will be the AppWidget
     *                      provider for this AppWidget.
     * @param options       Bundle containing options for the AppWidget. See also
     *                      {@link #updateAppWidgetOptions(int, Bundle)}
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void bindAppWidgetId(int appWidgetId, ComponentName provider, Bundle options) {
        if (mService == null) {
            return;
        }
        bindAppWidgetIdIfAllowed(appWidgetId, mContext.getUser(), provider, options);
    }

    /**
     * Set the component for a given appWidgetId.
     *
     * If successful, the app widget provider will receive a {@link #ACTION_APPWIDGET_UPDATE}
     * broadcast.
     *
     * <p class="note">You need the BIND_APPWIDGET permission or the user must have enabled binding
     *         widgets always for your component. Should be used by apps that host widgets; if this
     *         method returns false, call {@link #ACTION_APPWIDGET_BIND} to request permission to
     *         bind
     *
     * @param appWidgetId   The AppWidget id under which to bind the provider.
     * @param provider      The {@link android.content.BroadcastReceiver} that will be the AppWidget
     *                      provider for this AppWidget.
     * @return true if this component has permission to bind the AppWidget
     */
    public boolean bindAppWidgetIdIfAllowed(int appWidgetId, ComponentName provider) {
        if (mService == null) {
            return false;
        }
        return bindAppWidgetIdIfAllowed(appWidgetId, mContext.getUserId(), provider, null);
    }

    /**
     * Set the component for a given appWidgetId.
     *
     * If successful, the app widget provider will receive a {@link #ACTION_APPWIDGET_UPDATE}
     * broadcast.
     *
     * <p class="note">You need the BIND_APPWIDGET permission or the user must have enabled binding
     *         widgets always for your component. Should be used by apps that host widgets; if this
     *         method returns false, call {@link #ACTION_APPWIDGET_BIND} to request permission to
     *         bind
     *
     * @param appWidgetId The AppWidget id under which to bind the provider.
     * @param provider      The {@link android.content.BroadcastReceiver} that will be the AppWidget
     *                      provider for this AppWidget.
     * @param options       Bundle containing options for the AppWidget. See also
     *                      {@link #updateAppWidgetOptions(int, Bundle)}
     *
     * @return true if this component has permission to bind the AppWidget
     */
    public boolean bindAppWidgetIdIfAllowed(int appWidgetId, ComponentName provider,
            Bundle options) {
        if (mService == null) {
            return false;
        }
        return bindAppWidgetIdIfAllowed(appWidgetId, mContext.getUserId(), provider, options);
    }

    /**
     * Set the provider for a given appWidgetId if the caller has a permission.
     *
     * If successful, the app widget provider will receive a {@link #ACTION_APPWIDGET_UPDATE}
     * broadcast.
     *
     * <p>
     * <strong>Note:</strong> You need the {@link android.Manifest.permission#BIND_APPWIDGET}
     * permission or the user must have enabled binding widgets always for your component.
     * Should be used by apps that host widgets. If this method returns false, call {@link
     * #ACTION_APPWIDGET_BIND} to request permission to bind.
     * </p>
     *
     * @param appWidgetId The AppWidget id under which to bind the provider.
     * @param user The user id in which the provider resides.
     * @param provider The component name of the provider.
     * @param options An optional Bundle containing options for the AppWidget.
     *
     * @return true if this component has permission to bind the AppWidget
     */
    public boolean bindAppWidgetIdIfAllowed(int appWidgetId, UserHandle user,
            ComponentName provider, Bundle options) {
        if (mService == null) {
            return false;
        }
        return bindAppWidgetIdIfAllowed(appWidgetId, user.getIdentifier(), provider, options);
    }

    /**
     * Query if a given package was granted permission by the user to bind app widgets
     *
     * <p class="note">You need the MODIFY_APPWIDGET_BIND_PERMISSIONS permission
     *
     * @param packageName The package for which the permission is being queried
     * @param userId The user id of the user under which the package runs.
     * @return true if the package was granted permission by the user to bind app widgets
     * @hide
     */
    public boolean hasBindAppWidgetPermission(String packageName, int userId) {
        if (mService == null) {
            return false;
        }
        try {
            return mService.hasBindAppWidgetPermission(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Query if a given package was granted permission by the user to bind app widgets
     *
     * <p class="note">You need the MODIFY_APPWIDGET_BIND_PERMISSIONS permission
     *
     * @param packageName        The package for which the permission is being queried
     * @return true if the package was granted permission by the user to bind app widgets
     * @hide
     */
    public boolean hasBindAppWidgetPermission(String packageName) {
        if (mService == null) {
            return false;
        }
        try {
            return mService.hasBindAppWidgetPermission(packageName, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Changes any user-granted permission for the given package to bind app widgets
     *
     * <p class="note">You need the MODIFY_APPWIDGET_BIND_PERMISSIONS permission
     *
     * @param packageName The package whose permission is being changed
     * @param permission Whether to give the package permission to bind widgets
     *
     * @hide
     */
    public void setBindAppWidgetPermission(String packageName, boolean permission) {
        if (mService == null) {
            return;
        }
        setBindAppWidgetPermission(packageName, mContext.getUserId(), permission);
    }

    /**
     * Changes any user-granted permission for the given package to bind app widgets
     *
     * <p class="note">You need the MODIFY_APPWIDGET_BIND_PERMISSIONS permission
     *
     * @param packageName The package whose permission is being changed
     * @param userId The user under which the package is running.
     * @param permission Whether to give the package permission to bind widgets
     *
     * @hide
     */
    @TestApi
    public void setBindAppWidgetPermission(
            @NonNull String packageName, @UserIdInt int userId, boolean permission) {
        if (mService == null) {
            return;
        }
        try {
            mService.setBindAppWidgetPermission(packageName, userId, permission);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
     * @param flags         Flags used for binding to the service. Currently only
     *                     {@link Context#BIND_AUTO_CREATE} and
     *                     {@link Context#BIND_FOREGROUND_SERVICE_WHILE_AWAKE} are supported.
     *
     * @see Context#getServiceDispatcher(ServiceConnection, Handler, long)
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean bindRemoteViewsService(Context context, int appWidgetId, Intent intent,
            IServiceConnection connection, @Context.BindServiceFlagsBits int flags) {
        if (mService == null) {
            return false;
        }
        try {
            return mService.bindRemoteViewsService(context.getOpPackageName(), appWidgetId, intent,
                    context.getIApplicationThread(), context.getActivityToken(), connection,
                    Integer.toUnsignedLong(flags));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
        if (mService == null) {
            return new int[0];
        }
        try {
            return mService.getAppWidgetIds(provider);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public boolean isBoundWidgetPackage(String packageName, int userId) {
        if (mService == null) {
            return false;
        }
        try {
            return mService.isBoundWidgetPackage(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @UnsupportedAppUsage
    private boolean bindAppWidgetIdIfAllowed(int appWidgetId, int profileId,
            ComponentName provider, Bundle options) {
        if (mService == null) {
            return false;
        }
        try {
            return mService.bindAppWidgetId(mPackageName, appWidgetId,
                    profileId, provider, options);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return {@code TRUE} if the default launcher supports
     * {@link #requestPinAppWidget(ComponentName, Bundle, PendingIntent)}
     */
    public boolean isRequestPinAppWidgetSupported() {
        try {
            return mService.isRequestPinAppWidgetSupported();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Only used during development. Can be deleted before release.
     * @hide
     */
    public boolean requestPinAppWidget(@NonNull ComponentName provider,
            @Nullable PendingIntent successCallback) {
        return requestPinAppWidget(provider, null, successCallback);
    }

    /**
     * Request to pin an app widget on the current launcher. It's up to the launcher to accept this
     * request (optionally showing a user confirmation). If the request is accepted, the caller will
     * get a confirmation with extra {@link #EXTRA_APPWIDGET_ID}.
     *
     * <p>When a request is denied by the user, the caller app will not get any response.
     *
     * <p>Only apps with a foreground activity or a foreground service can call it.  Otherwise
     * it'll throw {@link IllegalStateException}.
     *
     * <p>It's up to the launcher how to handle previous pending requests when the same package
     * calls this API multiple times in a row.  It may ignore the previous requests,
     * for example.
     *
     * <p>Launcher will not show the configuration activity associated with the provider in this
     * case. The app could either show the configuration activity as a response to the callback,
     * or show if before calling the API (various configurations can be encapsulated in
     * {@code successCallback} to avoid persisting them before the widgetId is known).
     *
     * @param provider The {@link ComponentName} for the {@link
     *    android.content.BroadcastReceiver BroadcastReceiver} provider for your AppWidget.
     * @param extras In not null, this is passed to the launcher app. For eg {@link
     *    #EXTRA_APPWIDGET_PREVIEW} can be used for a custom preview.
     * @param successCallback If not null, this intent will be sent when the widget is created.
     *
     * @return {@code TRUE} if the launcher supports this feature. Note the API will return without
     *    waiting for the user to respond, so getting {@code TRUE} from this API does *not* mean
     *    the shortcut is pinned. {@code FALSE} if the launcher doesn't support this feature.
     *
     * @see android.content.pm.ShortcutManager#isRequestPinShortcutSupported()
     * @see android.content.pm.ShortcutManager#requestPinShortcut(ShortcutInfo, IntentSender)
     * @see #isRequestPinAppWidgetSupported()
     *
     * @throws IllegalStateException The caller doesn't have a foreground activity or a foreground
     * service or when the user is locked.
     */
    public boolean requestPinAppWidget(@NonNull ComponentName provider,
            @Nullable Bundle extras, @Nullable PendingIntent successCallback) {
        try {
            return mService.requestPinAppWidget(mPackageName, provider, extras,
                    successCallback == null ? null : successCallback.getIntentSender());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Note an app widget is tapped on.
     *
     * @param appWidgetId App widget id.
     * @hide
     */
    public void noteAppWidgetTapped(int appWidgetId) {
        try {
            mService.noteAppWidgetTapped(mPackageName, appWidgetId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set a preview for this widget. This preview will be used instead of the provider's {@link
     * AppWidgetProviderInfo#previewLayout previewLayout} or {@link
     * AppWidgetProviderInfo#previewImage previewImage} for previewing the widget in the widget
     * picker and pin app widget flow.
     *
     * @param provider The {@link ComponentName} for the {@link android.content.BroadcastReceiver
     *    BroadcastReceiver} provider for the AppWidget you intend to provide a preview for.
     * @param widgetCategories The categories that this preview should be used for. This can be a
     *    single category or combination of categories. If multiple categories are specified,
     *    then this preview will be used for each of those categories. For example, if you
     *    set a preview for WIDGET_CATEGORY_HOME_SCREEN | WIDGET_CATEGORY_KEYGUARD, the preview will
     *    be used when picking widgets for the home screen and keyguard.
     *
     *    <p>Note: You should only use the widget categories that the provider supports, as defined
     *    in {@link AppWidgetProviderInfo#widgetCategory}.
     * @param preview This preview will be used for previewing the provider when picking widgets for
     *    the selected categories.
     *
     * @see AppWidgetProviderInfo#WIDGET_CATEGORY_HOME_SCREEN
     * @see AppWidgetProviderInfo#WIDGET_CATEGORY_KEYGUARD
     * @see AppWidgetProviderInfo#WIDGET_CATEGORY_SEARCHBOX
     */
    @FlaggedApi(Flags.FLAG_GENERATED_PREVIEWS)
    public void setWidgetPreview(@NonNull ComponentName provider,
            @AppWidgetProviderInfo.CategoryFlags int widgetCategories,
            @NonNull RemoteViews preview) {
        try {
            mService.setWidgetPreview(provider, widgetCategories, preview);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the RemoteViews previews for this widget.
     *
     * @param provider The {@link ComponentName} for the {@link android.content.BroadcastReceiver
     *    BroadcastReceiver} provider for the AppWidget you intend to get a preview for.
     * @param profile The profile in which the provider resides. Passing null is equivalent
     *        to querying for only the calling user.
     * @param widgetCategory The widget category for which you want to display previews. This should
     *    be a single category. If a combination of categories is provided, this function will
     *    return a preview that matches at least one of the categories.
     *
     * @return The widget preview for the selected category, if available.
     * @see AppWidgetProviderInfo#generatedPreviewCategories
     */
    @Nullable
    @FlaggedApi(Flags.FLAG_GENERATED_PREVIEWS)
    public RemoteViews getWidgetPreview(@NonNull ComponentName provider,
            @Nullable UserHandle profile, @AppWidgetProviderInfo.CategoryFlags int widgetCategory) {
        try {
            if (profile == null) {
                profile = mContext.getUser();
            }
            return mService.getWidgetPreview(mPackageName, provider, profile.getIdentifier(),
                    widgetCategory);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove this provider's preview for the specified widget categories. If the provider does not
     * have a preview for the specified widget category, this is a no-op.
     *
     * @param provider The AppWidgetProvider to remove previews for.
     * @param widgetCategories The categories of the preview to remove. For example, removing the
     *    preview for WIDGET_CATEGORY_HOME_SCREEN | WIDGET_CATEGORY_KEYGUARD will remove the
     *    previews for both categories.
     */
    @FlaggedApi(Flags.FLAG_GENERATED_PREVIEWS)
    public void removeWidgetPreview(@NonNull ComponentName provider,
            @AppWidgetProviderInfo.CategoryFlags int widgetCategories) {
        try {
            mService.removeWidgetPreview(provider, widgetCategories);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    @UiThread
    private static @NonNull Executor createUpdateExecutorIfNull() {
        if (sUpdateExecutor == null) {
            sUpdateExecutor = new HandlerExecutor(createAndStartNewHandler(
                    "widget_manager_update_helper_thread", Process.THREAD_PRIORITY_FOREGROUND));
        }

        return sUpdateExecutor;
    }

    private static @NonNull Handler createAndStartNewHandler(@NonNull String name, int priority) {
        HandlerThread thread = new HandlerThread(name, priority);
        thread.start();
        return thread.getThreadHandler();
    }
}
