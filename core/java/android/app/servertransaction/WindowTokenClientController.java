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

package android.app.servertransaction;

import static android.view.WindowManager.LayoutParams.WindowType;
import static android.view.WindowManagerGlobal.getWindowManagerService;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import android.view.IWindowManager;
import android.window.WindowContext;
import android.window.WindowTokenClient;

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

    /** Mapping from a client defined token to the {@link WindowTokenClient} it represents. */
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, WindowTokenClient> mWindowTokenClientMap = new ArrayMap<>();

    /** Gets the singleton controller. */
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

    private WindowTokenClientController() {}

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
        final Configuration configuration;
        try {
            configuration = getWindowManagerService()
                    .attachWindowContextToDisplayArea(client, type, displayId, options);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        if (configuration == null) {
            return false;
        }
        onWindowContextTokenAttached(client, displayId, configuration);
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
        final Configuration configuration;
        try {
            configuration = wms.attachToDisplayContent(client, displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        if (configuration == null) {
            return false;
        }
        onWindowContextTokenAttached(client, displayId, configuration);
        return true;
    }

    /**
     * Attaches this {@link WindowTokenClient} to a {@code windowToken}.
     *
     * @param client The {@link WindowTokenClient} to attach.
     * @param windowToken the window token to associated with
     */
    public void attachToWindowToken(@NonNull WindowTokenClient client,
            @NonNull IBinder windowToken) {
        try {
            getWindowManagerService().attachWindowContextToWindowToken(client, windowToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        // We don't report configuration change for now.
        synchronized (mLock) {
            mWindowTokenClientMap.put(client.asBinder(), client);
        }
    }

    /** Detaches a {@link WindowTokenClient} from associated WindowContainer if there's one. */
    public void detachIfNeeded(@NonNull WindowTokenClient client) {
        synchronized (mLock) {
            if (mWindowTokenClientMap.remove(client.asBinder()) == null) {
                return;
            }
        }
        try {
            getWindowManagerService().detachWindowContextFromWindowContainer(client);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void onWindowContextTokenAttached(@NonNull WindowTokenClient client, int displayId,
            @NonNull Configuration configuration) {
        synchronized (mLock) {
            mWindowTokenClientMap.put(client.asBinder(), client);
        }
        client.onConfigurationChanged(configuration, displayId,
                false /* shouldReportConfigChange */);
    }

    /** Called when receives {@link WindowContextConfigurationChangeItem}. */
    public void onWindowContextConfigurationChanged(@NonNull IBinder clientToken,
            @NonNull Configuration configuration, int displayId) {
        final WindowTokenClient windowTokenClient = getWindowTokenClient(clientToken);
        if (windowTokenClient != null) {
            windowTokenClient.onConfigurationChanged(configuration, displayId);
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
}
