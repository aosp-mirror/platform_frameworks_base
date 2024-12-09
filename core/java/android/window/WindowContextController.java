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

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.IWindowManager;
import android.view.WindowManager.LayoutParams.WindowType;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;

/**
 * The controller to manage {@link WindowContext}, such as attaching to a window manager node or
 * detaching from the current attached node. The user must call
 * {@link #attachToDisplayArea(int, int, Bundle)}, call {@link #attachToWindowToken(IBinder)}
 * after that if necessary, and then call {@link #detachIfNeeded()} for release.
 *
 * @hide
 */
public class WindowContextController {
    private static final boolean DEBUG_ATTACH = false;
    private static final String TAG = "WindowContextController";

    /**
     * {@link AttachStatus#STATUS_ATTACHED} to indicate that the {@code mToken} is associated with a
     * {@link com.android.server.wm.DisplayArea}. Note that {@code mToken} is able to attach a
     * WindowToken after this flag sets to {@link AttachStatus#STATUS_ATTACHED}.
     */
    @VisibleForTesting
    public int mAttachedToDisplayArea = AttachStatus.STATUS_INITIALIZED;

    /**
     * Status to indicate that the Window Context attach to a
     * {@link com.android.server.wm.DisplayArea}.
     */
    @Retention(SOURCE)
    @IntDef({AttachStatus.STATUS_INITIALIZED, AttachStatus.STATUS_ATTACHED,
            AttachStatus.STATUS_DETACHED, AttachStatus.STATUS_FAILED})
    public @interface AttachStatus{
        /**
         * The Window Context haven't attached to a {@link com.android.server.wm.DisplayArea}.
         */
        int STATUS_INITIALIZED = 0;
        /**
         * The Window Context has already attached to a {@link com.android.server.wm.DisplayArea}.
         */
        int STATUS_ATTACHED = 1;
        /**
         * The Window Context has detached from a {@link com.android.server.wm.DisplayArea}.
         */
        int STATUS_DETACHED = 2;
        /**
         * The Window Context fails to attach to a {@link com.android.server.wm.DisplayArea}.
         */
        int STATUS_FAILED = 3;
    }
    @NonNull
    private final WindowTokenClient mToken;

    /**
     * Window Context Controller constructor
     *
     * @param token The token used to attach to a window manager node. It is usually from
     *              {@link Context#getWindowContextToken()}.
     */
    @VisibleForTesting
    public WindowContextController(@NonNull WindowTokenClient token) {
        mToken = token;
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
        if (mAttachedToDisplayArea == AttachStatus.STATUS_ATTACHED) {
            throw new IllegalStateException("A Window Context can be only attached to "
                    + "a DisplayArea once.");
        }
        mAttachedToDisplayArea = getWindowTokenClientController().attachToDisplayArea(
                mToken, type, displayId, options)
                ? AttachStatus.STATUS_ATTACHED : AttachStatus.STATUS_FAILED;
        if (mAttachedToDisplayArea == AttachStatus.STATUS_FAILED) {
            Log.w(TAG, "attachToDisplayArea fail, type:" + type + ", displayId:"
                    + displayId);
        } else if (DEBUG_ATTACH) {
            Log.d(TAG, "attachToDisplayArea success, type:" + type + ", displayId:"
                    + displayId);
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
     * @see IWindowManager#attachWindowContextToWindowToken
     */
    public void attachToWindowToken(@NonNull IBinder windowToken) {
        if (mAttachedToDisplayArea != AttachStatus.STATUS_ATTACHED) {
            throw new IllegalStateException("The Window Context should have been attached"
                    + " to a DisplayArea. AttachToDisplayArea:" + mAttachedToDisplayArea);
        }
        if (!getWindowTokenClientController().attachToWindowToken(mToken, windowToken)) {
            Log.e(TAG, "attachToWindowToken fail");
        }
    }

    /** Detaches the window context from the node it's currently associated with. */
    public void detachIfNeeded() {
        if (mAttachedToDisplayArea == AttachStatus.STATUS_ATTACHED) {
            getWindowTokenClientController().detachIfNeeded(mToken);
            mAttachedToDisplayArea = AttachStatus.STATUS_DETACHED;
            if (DEBUG_ATTACH) {
                Log.d(TAG, "Detach Window Context.");
            }
        }
    }

    /** Gets the {@link WindowTokenClientController}. */
    @VisibleForTesting
    @NonNull
    public WindowTokenClientController getWindowTokenClientController() {
        return WindowTokenClientController.getInstance();
    }
}
