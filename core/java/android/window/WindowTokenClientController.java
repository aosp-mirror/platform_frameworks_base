/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.view.WindowManager.LayoutParams.WindowType;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.IApplicationThread;
import android.app.servertransaction.WindowContextInfoChangeItem;
import android.app.servertransaction.WindowContextWindowRemovalItem;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Singleton controller to manage the attached {@link WindowTokenClient}s, and to dispatch
 * corresponding window configuration change from server side.
 * @hide
 */
public class WindowTokenClientController {

    private static final String TAG = WindowTokenClientController.class.getSimpleName();
    private static WindowTokenClientController sController;

    private final Object mLock = new Object();
    private final IApplicationThread mAppThread = ActivityThread.currentActivityThread()
            .getApplicationThread();

    /** Mapping from a client defined token to the {@link WindowTokenClient} it represents. */
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, WindowTokenClient> mWindowTokenClientMap = new ArrayMap<>();

    /** Gets the singleton controller. */
    @NonNull
    public static WindowTokenClientController getInstance() {
        synchronized (WindowTokenClientController.class) {
            if (sController == null) {
                sController = new WindowTokenClientController();
            }
            return sController;
        }
    }

    /** Overrides the {@link #getInstance()} for test only. */
    @VisibleForTesting
    public static void overrideForTesting(@NonNull WindowTokenClientController controller) {
        synchronized (WindowTokenClientController.class) {
            sController = controller;
        }
    }

    /** Creates a new instance for test only. */
    @VisibleForTesting
    @NonNull
    public static WindowTokenClientController createInstanceForTesting() {
        return new WindowTokenClientController();
    }

    private WindowTokenClientController() {}

    /** Gets the {@link WindowContext} instance for the token. */
    @Nullable
    public Context getWindowContext(@NonNull IBinder clientToken) {
        final WindowTokenClient windowTokenClient;
        synchronized (mLock) {
            windowTokenClient = mWindowTokenClientMap.get(clientToken);
        }
        return windowTokenClient != null ? windowTokenClient.getContext() : null;
    }

    /**
     * Attaches a {@link WindowTokenClient} to a {@link com.android.server.wm.DisplayArea}.
     *
     * @param client The {@link WindowTokenClient} to attach.
     * @param type The window type of the {@link WindowContext}
     * @param displayId The {@link Context#getDisplayId() ID of display} to associate with
     * @param options The window context launched option
     * @return {@code true} if attaching successfully.
     */
    public boolean attachToDisplayArea(@NonNull WindowTokenClient client,
            @WindowType int type, int displayId, @Nullable Bundle options) {
        final WindowContextInfo info;
        try {
            info = getWindowManagerService().attachWindowContextToDisplayArea(
                    mAppThread, client, type, displayId, options);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        if (info == null) {
            return false;
        }
        onWindowContextTokenAttached(client, info, false /* shouldReportConfigChange */);
        return true;
    }

    /**
     * Attaches a {@link WindowTokenClient} to a {@code DisplayContent}.
     *
     * @param client The {@link WindowTokenClient} to attach.
     * @param displayId The {@link Context#getDisplayId() ID of display} to associate with
     * @return {@code true} if attaching successfully.
     */
    public boolean attachToDisplayContent(@NonNull WindowTokenClient client, int displayId) {
        final IWindowManager wms = getWindowManagerService();
        // #createSystemUiContext may call this method before WindowManagerService is initialized.
        if (wms == null) {
            return false;
        }
        final WindowContextInfo info;
        try {
            info = wms.attachWindowContextToDisplayContent(mAppThread, client, displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        if (info == null) {
            return false;
        }
        onWindowContextTokenAttached(client, info, false /* shouldReportConfigChange */);
        return true;
    }

    /**
     * Attaches this {@link WindowTokenClient} to a {@code windowToken}.
     *
     * @param client The {@link WindowTokenClient} to attach.
     * @param windowToken the window token to associated with
     * @return {@code true} if attaching successfully.
     */
    public boolean attachToWindowToken(@NonNull WindowTokenClient client,
            @NonNull IBinder windowToken) {
        final WindowContextInfo info;
        try {
            info = getWindowManagerService().attachWindowContextToWindowToken(
                    mAppThread, client, windowToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        if (info == null) {
            return false;
        }
        // We currently report configuration for WindowToken after attached.
        onWindowContextTokenAttached(client, info, true /* shouldReportConfigChange */);
        return true;
    }

    /** Detaches a {@link WindowTokenClient} from associated WindowContainer if there's one. */
    public void detachIfNeeded(@NonNull WindowTokenClient client) {
        synchronized (mLock) {
            if (mWindowTokenClientMap.remove(client) == null) {
                return;
            }
        }
        try {
            getWindowManagerService().detachWindowContext(client);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void onWindowContextTokenAttached(@NonNull WindowTokenClient client,
            @NonNull WindowContextInfo info, boolean shouldReportConfigChange) {
        synchronized (mLock) {
            mWindowTokenClientMap.put(client, client);
        }
        if (shouldReportConfigChange) {
            // Should trigger an #onConfigurationChanged callback to the WindowContext. Post the
            // dispatch in the next loop to prevent the callback from being dispatched before
            // #onCreate or WindowContext creation..
            client.postOnConfigurationChanged(info.getConfiguration(), info.getDisplayId());
        } else {
            // Apply the config change directly in case users get stale values after WindowContext
            // creation.
            client.onConfigurationChanged(info.getConfiguration(), info.getDisplayId(),
                    false /* shouldReportConfigChange */);
        }
    }

    /** Called when receives {@link WindowContextInfoChangeItem}. */
    public void onWindowContextInfoChanged(@NonNull IBinder clientToken,
            @NonNull WindowContextInfo info) {
        final WindowTokenClient windowTokenClient = getWindowTokenClient(clientToken);
        if (windowTokenClient != null) {
            windowTokenClient.onConfigurationChanged(info.getConfiguration(), info.getDisplayId());
        }
    }

    /** Called when receives {@link WindowContextWindowRemovalItem}. */
    public void onWindowContextWindowRemoved(@NonNull IBinder clientToken) {
        final WindowTokenClient windowTokenClient = getWindowTokenClient(clientToken);
        if (windowTokenClient != null) {
            windowTokenClient.onWindowTokenRemoved();
        }
    }

    @Nullable
    private WindowTokenClient getWindowTokenClient(@NonNull IBinder clientToken) {
        final WindowTokenClient windowTokenClient;
        synchronized (mLock) {
            windowTokenClient = mWindowTokenClientMap.get(clientToken);
        }
        if (windowTokenClient == null) {
            Log.w(TAG, "Can't find attached WindowTokenClient for " + clientToken);
        }
        return windowTokenClient;
    }

    /** Gets the {@link IWindowManager}. */
    @VisibleForTesting
    @Nullable
    public IWindowManager getWindowManagerService() {
        return WindowManagerGlobal.getWindowManagerService();
    }
}
