/*
 * Copyright (C) 2009 The Android Open Source Project
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

import java.lang.ref.WeakReference;
import java.util.List;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.IntentSender;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.util.TypedValue;
import android.widget.RemoteViews;
import android.widget.RemoteViews.OnClickHandler;

import com.android.internal.appwidget.IAppWidgetHost;
import com.android.internal.appwidget.IAppWidgetService;

/**
 * AppWidgetHost provides the interaction with the AppWidget service for apps,
 * like the home screen, that want to embed AppWidgets in their UI.
 */
public class AppWidgetHost {

    static final int HANDLE_UPDATE = 1;
    static final int HANDLE_PROVIDER_CHANGED = 2;
    static final int HANDLE_PROVIDERS_CHANGED = 3;
    static final int HANDLE_VIEW_DATA_CHANGED = 4;

    final static Object sServiceLock = new Object();
    static IAppWidgetService sService;
    private DisplayMetrics mDisplayMetrics;

    private String mContextOpPackageName;
    private final Handler mHandler;
    private final int mHostId;
    private final Callbacks mCallbacks;
    private final SparseArray<AppWidgetHostView> mViews = new SparseArray<>();
    private OnClickHandler mOnClickHandler;

    static class Callbacks extends IAppWidgetHost.Stub {
        private final WeakReference<Handler> mWeakHandler;

        public Callbacks(Handler handler) {
            mWeakHandler = new WeakReference<>(handler);
        }

        public void updateAppWidget(int appWidgetId, RemoteViews views) {
            if (isLocalBinder() && views != null) {
                views = views.clone();
            }
            Handler handler = mWeakHandler.get();
            if (handler == null) {
                return;
            }
            Message msg = handler.obtainMessage(HANDLE_UPDATE, appWidgetId, 0, views);
            msg.sendToTarget();
        }

        public void providerChanged(int appWidgetId, AppWidgetProviderInfo info) {
            if (isLocalBinder() && info != null) {
                info = info.clone();
            }
            Handler handler = mWeakHandler.get();
            if (handler == null) {
                return;
            }
            Message msg = handler.obtainMessage(HANDLE_PROVIDER_CHANGED,
                    appWidgetId, 0, info);
            msg.sendToTarget();
        }

        public void providersChanged() {
            Handler handler = mWeakHandler.get();
            if (handler == null) {
                return;
            }
            handler.obtainMessage(HANDLE_PROVIDERS_CHANGED).sendToTarget();
        }

        public void viewDataChanged(int appWidgetId, int viewId) {
            Handler handler = mWeakHandler.get();
            if (handler == null) {
                return;
            }
            Message msg = handler.obtainMessage(HANDLE_VIEW_DATA_CHANGED,
                    appWidgetId, viewId);
            msg.sendToTarget();
        }

        private static boolean isLocalBinder() {
            return Process.myPid() == Binder.getCallingPid();
        }
    }

    class UpdateHandler extends Handler {
        public UpdateHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case HANDLE_UPDATE: {
                    updateAppWidgetView(msg.arg1, (RemoteViews)msg.obj);
                    break;
                }
                case HANDLE_PROVIDER_CHANGED: {
                    onProviderChanged(msg.arg1, (AppWidgetProviderInfo)msg.obj);
                    break;
                }
                case HANDLE_PROVIDERS_CHANGED: {
                    onProvidersChanged();
                    break;
                }
                case HANDLE_VIEW_DATA_CHANGED: {
                    viewDataChanged(msg.arg1, msg.arg2);
                    break;
                }
            }
        }
    }

    public AppWidgetHost(Context context, int hostId) {
        this(context, hostId, null, context.getMainLooper());
    }

    /**
     * @hide
     */
    public AppWidgetHost(Context context, int hostId, OnClickHandler handler, Looper looper) {
        mContextOpPackageName = context.getOpPackageName();
        mHostId = hostId;
        mOnClickHandler = handler;
        mHandler = new UpdateHandler(looper);
        mCallbacks = new Callbacks(mHandler);
        mDisplayMetrics = context.getResources().getDisplayMetrics();
        bindService();
    }

    private static void bindService() {
        synchronized (sServiceLock) {
            if (sService == null) {
                IBinder b = ServiceManager.getService(Context.APPWIDGET_SERVICE);
                sService = IAppWidgetService.Stub.asInterface(b);
            }
        }
    }

    /**
     * Start receiving onAppWidgetChanged calls for your AppWidgets.  Call this when your activity
     * becomes visible, i.e. from onStart() in your Activity.
     */
    public void startListening() {
        final int[] idsToUpdate;
        synchronized (mViews) {
            int N = mViews.size();
            idsToUpdate = new int[N];
            for (int i = 0; i < N; i++) {
                idsToUpdate[i] = mViews.keyAt(i);
            }
        }
        List<RemoteViews> updatedViews;
        int[] updatedIds = new int[idsToUpdate.length];
        try {
            updatedViews = sService.startListening(
                    mCallbacks, mContextOpPackageName, mHostId, idsToUpdate, updatedIds).getList();
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }

        int N = updatedViews.size();
        for (int i = 0; i < N; i++) {
            updateAppWidgetView(updatedIds[i], updatedViews.get(i));
        }
    }

    /**
     * Stop receiving onAppWidgetChanged calls for your AppWidgets.  Call this when your activity is
     * no longer visible, i.e. from onStop() in your Activity.
     */
    public void stopListening() {
        try {
            sService.stopListening(mContextOpPackageName, mHostId);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Get a appWidgetId for a host in the calling process.
     *
     * @return a appWidgetId
     */
    public int allocateAppWidgetId() {
        try {
            return sService.allocateAppWidgetId(mContextOpPackageName, mHostId);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Starts an app widget provider configure activity for result on behalf of the caller.
     * Use this method if the provider is in another profile as you are not allowed to start
     * an activity in another profile. You can optionally provide a request code that is
     * returned in {@link Activity#onActivityResult(int, int, android.content.Intent)} and
     * an options bundle to be passed to the started activity.
     * <p>
     * Note that the provided app widget has to be bound for this method to work.
     * </p>
     *
     * @param activity The activity from which to start the configure one.
     * @param appWidgetId The bound app widget whose provider's config activity to start.
     * @param requestCode Optional request code retuned with the result.
     * @param intentFlags Optional intent flags.
     *
     * @throws android.content.ActivityNotFoundException If the activity is not found.
     *
     * @see AppWidgetProviderInfo#getProfile()
     */
    public final void startAppWidgetConfigureActivityForResult(@NonNull Activity activity,
            int appWidgetId, int intentFlags, int requestCode, @Nullable Bundle options) {
        try {
            IntentSender intentSender = sService.createAppWidgetConfigIntentSender(
                    mContextOpPackageName, appWidgetId, intentFlags);
            if (intentSender != null) {
                activity.startIntentSenderForResult(intentSender, requestCode, null, 0, 0, 0,
                        options);
            } else {
                throw new ActivityNotFoundException();
            }
        } catch (IntentSender.SendIntentException e) {
            throw new ActivityNotFoundException();
        } catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Gets a list of all the appWidgetIds that are bound to the current host
     *
     * @hide
     */
    public int[] getAppWidgetIds() {
        try {
            if (sService == null) {
                bindService();
            }
            return sService.getAppWidgetIdsForHost(mContextOpPackageName, mHostId);
        } catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Stop listening to changes for this AppWidget.
     */
    public void deleteAppWidgetId(int appWidgetId) {
        synchronized (mViews) {
            mViews.remove(appWidgetId);
            try {
                sService.deleteAppWidgetId(mContextOpPackageName, appWidgetId);
            }
            catch (RemoteException e) {
                throw new RuntimeException("system server dead?", e);
            }
        }
    }

    /**
     * Remove all records about this host from the AppWidget manager.
     * <ul>
     *   <li>Call this when initializing your database, as it might be because of a data wipe.</li>
     *   <li>Call this to have the AppWidget manager release all resources associated with your
     *   host.  Any future calls about this host will cause the records to be re-allocated.</li>
     * </ul>
     */
    public void deleteHost() {
        try {
            sService.deleteHost(mContextOpPackageName, mHostId);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Remove all records about all hosts for your package.
     * <ul>
     *   <li>Call this when initializing your database, as it might be because of a data wipe.</li>
     *   <li>Call this to have the AppWidget manager release all resources associated with your
     *   host.  Any future calls about this host will cause the records to be re-allocated.</li>
     * </ul>
     */
    public static void deleteAllHosts() {
        try {
            sService.deleteAllHosts();
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Create the AppWidgetHostView for the given widget.
     * The AppWidgetHost retains a pointer to the newly-created View.
     */
    public final AppWidgetHostView createView(Context context, int appWidgetId,
            AppWidgetProviderInfo appWidget) {
        AppWidgetHostView view = onCreateView(context, appWidgetId, appWidget);
        view.setOnClickHandler(mOnClickHandler);
        view.setAppWidget(appWidgetId, appWidget);
        synchronized (mViews) {
            mViews.put(appWidgetId, view);
        }
        RemoteViews views;
        try {
            views = sService.getAppWidgetViews(mContextOpPackageName, appWidgetId);
        } catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
        view.updateAppWidget(views);

        return view;
    }

    /**
     * Called to create the AppWidgetHostView.  Override to return a custom subclass if you
     * need it.  {@more}
     */
    protected AppWidgetHostView onCreateView(Context context, int appWidgetId,
            AppWidgetProviderInfo appWidget) {
        return new AppWidgetHostView(context, mOnClickHandler);
    }

    /**
     * Called when the AppWidget provider for a AppWidget has been upgraded to a new apk.
     */
    protected void onProviderChanged(int appWidgetId, AppWidgetProviderInfo appWidget) {
        AppWidgetHostView v;

        // Convert complex to dp -- we are getting the AppWidgetProviderInfo from the
        // AppWidgetService, which doesn't have our context, hence we need to do the
        // conversion here.
        appWidget.minWidth =
            TypedValue.complexToDimensionPixelSize(appWidget.minWidth, mDisplayMetrics);
        appWidget.minHeight =
            TypedValue.complexToDimensionPixelSize(appWidget.minHeight, mDisplayMetrics);
        appWidget.minResizeWidth =
            TypedValue.complexToDimensionPixelSize(appWidget.minResizeWidth, mDisplayMetrics);
        appWidget.minResizeHeight =
            TypedValue.complexToDimensionPixelSize(appWidget.minResizeHeight, mDisplayMetrics);

        synchronized (mViews) {
            v = mViews.get(appWidgetId);
        }
        if (v != null) {
            v.resetAppWidget(appWidget);
        }
    }

    /**
     * Called when the set of available widgets changes (ie. widget containing packages
     * are added, updated or removed, or widget components are enabled or disabled.)
     */
    protected void onProvidersChanged() {
        // Does nothing
    }

    void updateAppWidgetView(int appWidgetId, RemoteViews views) {
        AppWidgetHostView v;
        synchronized (mViews) {
            v = mViews.get(appWidgetId);
        }
        if (v != null) {
            v.updateAppWidget(views);
        }
    }

    void viewDataChanged(int appWidgetId, int viewId) {
        AppWidgetHostView v;
        synchronized (mViews) {
            v = mViews.get(appWidgetId);
        }
        if (v != null) {
            v.viewDataChanged(viewId);
        }
    }

    /**
     * Clear the list of Views that have been created by this AppWidgetHost.
     */
    protected void clearViews() {
        synchronized (mViews) {
            mViews.clear();
        }
    }
}


