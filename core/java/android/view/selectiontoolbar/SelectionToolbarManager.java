/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.view.selectiontoolbar;

import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.provider.DeviceConfig;

import java.util.Objects;

/**
 * The {@link SelectionToolbarManager} class provides ways for apps to control the
 * selection toolbar.
 *
 * @hide
 */
@SystemService(Context.SELECTION_TOOLBAR_SERVICE)
public final class SelectionToolbarManager {

    private static final String TAG = "SelectionToolbar";

    /**
     * The tag which uses for enabling debug log dump. To enable it, we can use command "adb shell
     * setprop log.tag.UiTranslation DEBUG".
     */
    public static final String LOG_TAG = "SelectionToolbar";

    /**
     * Whether system selection toolbar is enabled.
     */
    private static final String REMOTE_SELECTION_TOOLBAR_ENABLED =
            "remote_selection_toolbar_enabled";

    /**
     * Used to mark a toolbar that has no toolbar token id.
     */
    public static final long NO_TOOLBAR_ID = 0;

    /**
     * The error code that do not allow to create multiple toolbar.
     */
    public static final int ERROR_DO_NOT_ALLOW_MULTIPLE_TOOL_BAR = 1;

    @NonNull
    private final Context mContext;
    private final ISelectionToolbarManager mService;

    public SelectionToolbarManager(@NonNull Context context,
            @NonNull ISelectionToolbarManager service) {
        mContext = Objects.requireNonNull(context);
        mService = service;
    }

    /**
     * Request to show selection toolbar for a given View.
     */
    public void showToolbar(@NonNull ShowInfo showInfo,
            @NonNull ISelectionToolbarCallback callback) {
        try {
            Objects.requireNonNull(showInfo);
            Objects.requireNonNull(callback);
            mService.showToolbar(showInfo, callback, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request to hide selection toolbar.
     */
    public void hideToolbar(long widgetToken) {
        try {
            mService.hideToolbar(widgetToken, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Dismiss to dismiss selection toolbar.
     */
    public void dismissToolbar(long widgetToken) {
        try {
            mService.dismissToolbar(widgetToken, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private boolean isRemoteSelectionToolbarEnabled() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SELECTION_TOOLBAR,
                REMOTE_SELECTION_TOOLBAR_ENABLED, false);
    }

    /**
     * Returns {@code true} if remote render selection toolbar enabled, otherwise
     * returns {@code false}.
     */
    public static boolean isRemoteSelectionToolbarEnabled(Context context) {
        SelectionToolbarManager manager = context.getSystemService(SelectionToolbarManager.class);
        if (manager != null) {
            return manager.isRemoteSelectionToolbarEnabled();
        }
        return false;
    }
}
