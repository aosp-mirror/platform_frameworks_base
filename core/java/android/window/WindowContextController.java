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

package android.window;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.IWindowManager;
import android.view.WindowManager.LayoutParams.WindowType;
import android.view.WindowManagerGlobal;

import com.android.internal.annotations.VisibleForTesting;

/**
 * The controller to manage {@link WindowContext}, such as attaching to a window manager node or
 * detaching from the current attached node. The user must call
 * {@link #attachToDisplayArea(int, int, Bundle)}, call {@link #attachToWindowToken(IBinder)}
 * after that if necessary, and then call {@link #detachIfNeeded()} for release.
 *
 * @hide
 */
public class WindowContextController {
    private final IWindowManager mWms;
    /**
     * {@code true} to indicate that the {@code mToken} is associated with a
     * {@link com.android.server.wm.DisplayArea}. Note that {@code mToken} is able to attach a
     * WindowToken after this flag sets to {@code true}.
     */
    @VisibleForTesting
    public boolean mAttachedToDisplayArea;
    @NonNull
    private final WindowTokenClient mToken;

    /**
     * Window Context Controller constructor
     *
     * @param token The token used to attach to a window manager node. It is usually from
     *              {@link Context#getWindowContextToken()}.
     */
    public WindowContextController(@NonNull WindowTokenClient token) {
        this(token, WindowManagerGlobal.getWindowManagerService());
    }

    /** Used for test only. DO NOT USE it in production code. */
    @VisibleForTesting
    public WindowContextController(@NonNull WindowTokenClient token, IWindowManager mockWms) {
        mToken = token;
        mWms = mockWms;
    }

    /**
     * Attaches the {@code mToken} to a {@link com.android.server.wm.DisplayArea}.
     *
     * @param type The window type of the {@link WindowContext}
     * @param displayId The {@link Context#getDisplayId() ID of display} to associate with
     * @param options The window context launched option
     * @throws IllegalStateException if the {@code mToken} has already been attached to a
     * DisplayArea.
     */
    public void attachToDisplayArea(@WindowType int type, int displayId, @Nullable Bundle options) {
        if (mAttachedToDisplayArea) {
            throw new IllegalStateException("A Window Context can be only attached to "
                    + "a DisplayArea once.");
        }
        try {
            final Configuration configuration = mWms.attachWindowContextToDisplayArea(mToken, type,
                    displayId, options);
            if (configuration != null) {
                mAttachedToDisplayArea = true;
                // Send the DisplayArea's configuration to WindowContext directly instead of
                // waiting for dispatching from WMS.
                mToken.onConfigurationChanged(configuration, displayId,
                        false /* shouldReportConfigChange */);
            }
        }  catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Switches to attach the window context to a window token.
     * <p>
     * Note that the context should have been attached to a
     * {@link com.android.server.wm.DisplayArea} by {@link #attachToDisplayArea(int, int, Bundle)}
     * before attaching to a window token, and the window token's type must match the window
     * context's type.
     * </p><p>
     * A {@link WindowContext} can only attach to a specific window manager node, which is either a
     * {@link com.android.server.wm.DisplayArea} by calling
     * {@link #attachToDisplayArea(int, int, Bundle)} or the latest attached {@code windowToken}
     * although this API is allowed to be called multiple times.
     * </p>
     * @throws IllegalStateException if the {@code mClientToken} has not yet attached to
     * a {@link com.android.server.wm.DisplayArea} by
     * {@link #attachToDisplayArea(int, int, Bundle)}.
     *
     * @see WindowProviderService#attachToWindowToken(IBinder))
     * @see IWindowManager#attachWindowContextToWindowToken(IBinder, IBinder)
     */
    public void attachToWindowToken(IBinder windowToken) {
        if (!mAttachedToDisplayArea) {
            throw new IllegalStateException("The Window Context should have been attached"
                    + " to a DisplayArea.");
        }
        try {
            mWms.attachWindowContextToWindowToken(mToken, windowToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Detaches the window context from the node it's currently associated with. */
    public void detachIfNeeded() {
        if (mAttachedToDisplayArea) {
            try {
                mWms.detachWindowContextFromWindowContainer(mToken);
                mAttachedToDisplayArea = false;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
