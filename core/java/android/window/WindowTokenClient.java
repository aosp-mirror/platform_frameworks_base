/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.window;

import static android.window.ConfigurationHelper.freeTextLayoutCachesIfNeeded;
import static android.window.ConfigurationHelper.isDifferentDisplay;
import static android.window.ConfigurationHelper.shouldUpdateResources;

import android.annotation.AnyThread;
import android.annotation.BinderThread;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.IWindowToken;
import android.app.ResourcesManager;
import android.content.Context;
import android.content.res.Configuration;
import android.inputmethodservice.AbstractInputMethodService;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.IWindowManager;
import android.view.WindowManager.LayoutParams.WindowType;
import android.view.WindowManagerGlobal;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.function.pooled.PooledLambda;

import java.lang.ref.WeakReference;

/**
 * This class is used to receive {@link Configuration} changes from the associated window manager
 * node on the server side, and apply the change to the {@link Context#getResources() associated
 * Resources} of the attached {@link Context}. It is also used as
 * {@link Context#getWindowContextToken() the token of non-Activity UI Contexts}.
 *
 * @see WindowContext
 * @see android.view.IWindowManager#attachWindowContextToDisplayArea(IBinder, int, int, Bundle)
 *
 * @hide
 */
public class WindowTokenClient extends IWindowToken.Stub {
    private static final String TAG = WindowTokenClient.class.getSimpleName();

    /**
     * Attached {@link Context} for this window token to update configuration and resources.
     * Initialized by {@link #attachContext(Context)}.
     */
    private WeakReference<Context> mContextRef = null;

    private final ResourcesManager mResourcesManager = ResourcesManager.getInstance();

    private IWindowManager mWms;

    @GuardedBy("itself")
    private final Configuration mConfiguration = new Configuration();

    private boolean mShouldDumpConfigForIme;

    private boolean mAttachToWindowContainer;

    private final Handler mHandler = ActivityThread.currentActivityThread().getHandler();

    /**
     * Attaches {@code context} to this {@link WindowTokenClient}. Each {@link WindowTokenClient}
     * can only attach one {@link Context}.
     * <p>This method must be called before invoking
     * {@link android.view.IWindowManager#attachWindowContextToDisplayArea(IBinder, int, int,
     * Bundle)}.<p/>
     *
     * @param context context to be attached
     * @throws IllegalStateException if attached context has already existed.
     */
    public void attachContext(@NonNull Context context) {
        if (mContextRef != null) {
            throw new IllegalStateException("Context is already attached.");
        }
        mContextRef = new WeakReference<>(context);
        mShouldDumpConfigForIme = Build.IS_DEBUGGABLE
                && context instanceof AbstractInputMethodService;
    }

    /**
     * Attaches this {@link WindowTokenClient} to a {@link com.android.server.wm.DisplayArea}.
     *
     * @param type The window type of the {@link WindowContext}
     * @param displayId The {@link Context#getDisplayId() ID of display} to associate with
     * @param options The window context launched option
     * @return {@code true} if attaching successfully.
     */
    public boolean attachToDisplayArea(@WindowType int type, int displayId,
            @Nullable Bundle options) {
        try {
            final Configuration configuration = getWindowManagerService()
                    .attachWindowContextToDisplayArea(this, type, displayId, options);
            if (configuration == null) {
                return false;
            }
            onConfigurationChanged(configuration, displayId, false /* shouldReportConfigChange */);
            mAttachToWindowContainer = true;
            return true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Attaches this {@link WindowTokenClient} to a {@code DisplayContent}.
     *
     * @param displayId The {@link Context#getDisplayId() ID of display} to associate with
     * @return {@code true} if attaching successfully.
     */
    public boolean attachToDisplayContent(int displayId) {
        final IWindowManager wms = getWindowManagerService();
        // #createSystemUiContext may call this method before WindowManagerService is initialized.
        if (wms == null) {
            return false;
        }
        try {
            final Configuration configuration = wms.attachToDisplayContent(this, displayId);
            if (configuration == null) {
                return false;
            }
            onConfigurationChanged(configuration, displayId, false /* shouldReportConfigChange */);
            mAttachToWindowContainer = true;
            return true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Attaches this {@link WindowTokenClient} to a {@code windowToken}.
     *
     * @param windowToken the window token to associated with
     */
    public void attachToWindowToken(IBinder windowToken) {
        try {
            getWindowManagerService().attachWindowContextToWindowToken(this, windowToken);
            mAttachToWindowContainer = true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Detaches this {@link WindowTokenClient} from associated WindowContainer if there's one. */
    public void detachFromWindowContainerIfNeeded() {
        if (!mAttachToWindowContainer) {
            return;
        }
        try {
            getWindowManagerService().detachWindowContextFromWindowContainer(this);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private IWindowManager getWindowManagerService() {
        if (mWms == null) {
            mWms = WindowManagerGlobal.getWindowManagerService();
        }
        return mWms;
    }

    /**
     * Called when {@link Configuration} updates from the server side receive.
     *
     * @param newConfig the updated {@link Configuration}
     * @param newDisplayId the updated {@link android.view.Display} ID
     */
    @BinderThread
    @Override
    public void onConfigurationChanged(Configuration newConfig, int newDisplayId) {
        mHandler.post(PooledLambda.obtainRunnable(this::onConfigurationChanged, newConfig,
                newDisplayId, true /* shouldReportConfigChange */).recycleOnUse());
    }

    // TODO(b/192048581): rewrite this method based on WindowContext and WindowProviderService
    //  are inherited from WindowProvider.
    /**
     * Called when {@link Configuration} updates from the server side receive.
     *
     * Similar to {@link #onConfigurationChanged(Configuration, int)}, but adds a flag to control
     * whether to dispatch configuration update or not.
     * <p>
     * Note that this method must be executed on the main thread if
     * {@code shouldReportConfigChange} is {@code true}, which is usually from
     * {@link IWindowToken#onConfigurationChanged(Configuration, int)}
     * directly, while this method could be run on any thread if it is used to initialize
     * Context's {@code Configuration} via {@link #attachToDisplayArea(int, int, Bundle)}
     * or {@link #attachToDisplayContent(int)}.
     *
     * @param shouldReportConfigChange {@code true} to indicate that the {@code Configuration}
     *                                 should be dispatched to listeners.
     *
     */
    @AnyThread
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void onConfigurationChanged(Configuration newConfig, int newDisplayId,
            boolean shouldReportConfigChange) {
        final Context context = mContextRef.get();
        if (context == null) {
            return;
        }
        final boolean displayChanged;
        final boolean shouldUpdateResources;
        final int diff;
        final Configuration currentConfig;

        synchronized (mConfiguration) {
            displayChanged = isDifferentDisplay(context.getDisplayId(), newDisplayId);
            shouldUpdateResources = shouldUpdateResources(this, mConfiguration,
                    newConfig, newConfig /* overrideConfig */, displayChanged,
                    null /* configChanged */);
            diff = mConfiguration.diffPublicOnly(newConfig);
            currentConfig = mShouldDumpConfigForIme ? new Configuration(mConfiguration) : null;
            if (shouldUpdateResources) {
                mConfiguration.setTo(newConfig);
            }
        }

        if (!shouldUpdateResources && mShouldDumpConfigForIme) {
            Log.d(TAG, "Configuration not dispatch to IME because configuration is up"
                    + " to date. Current config=" + context.getResources().getConfiguration()
                    + ", reported config=" + currentConfig
                    + ", updated config=" + newConfig);
        }
        if (shouldUpdateResources) {
            // TODO(ag/9789103): update resource manager logic to track non-activity tokens
            mResourcesManager.updateResourcesForActivity(this, newConfig, newDisplayId);

            if (shouldReportConfigChange && context instanceof WindowContext) {
                final WindowContext windowContext = (WindowContext) context;
                windowContext.dispatchConfigurationChanged(newConfig);
            }


            if (shouldReportConfigChange && diff != 0
                    && context instanceof WindowProviderService) {
                final WindowProviderService windowProviderService = (WindowProviderService) context;
                windowProviderService.onConfigurationChanged(newConfig);
            }
            freeTextLayoutCachesIfNeeded(diff);
            if (mShouldDumpConfigForIme) {
                if (!shouldReportConfigChange) {
                    Log.d(TAG, "Only apply configuration update to Resources because "
                            + "shouldReportConfigChange is false.\n" + Debug.getCallers(5));
                } else if (diff == 0) {
                    Log.d(TAG, "Configuration not dispatch to IME because configuration has no "
                            + " public difference with updated config. "
                            + " Current config=" + context.getResources().getConfiguration()
                            + ", reported config=" + currentConfig
                            + ", updated config=" + newConfig);
                }
            }
        }
        if (displayChanged) {
            context.updateDisplay(newDisplayId);
        }
    }

    @BinderThread
    @Override
    public void onWindowTokenRemoved() {
        mHandler.post(PooledLambda.obtainRunnable(
                WindowTokenClient::onWindowTokenRemovedInner, this).recycleOnUse());
    }

    @MainThread
    private void onWindowTokenRemovedInner() {
        final Context context = mContextRef.get();
        if (context != null) {
            context.destroy();
            mContextRef.clear();
        }
    }
}
