/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.view.autofill;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.os.RemoteException;
import android.service.autofill.IAutoFillManagerService;
import android.util.Log;
import android.view.View;

/**
 * App entry point to the AutoFill Framework.
 */
// TODO(b/33197203): improve this javadoc
public final class AutoFillManager {

    private static final String TAG = "AutoFillManager";
    private static final boolean DEBUG = true; // TODO(b/33197203): change to false once stable

    /**
     * Flag used to show the auto-fill UI affordance for a view.
     */
    // TODO(b/33197203): cannot conflict with flags defined on View until they're removed (when
    // save is refactored).
    public static final int FLAG_UPDATE_UI_SHOW = 0x1;

    /**
     * Flag used to hide the auto-fill UI affordance for a view.
     */
    // TODO(b/33197203): cannot conflict with flags defined on View until they're removed (when
    // save is refactored).
    public static final int FLAG_UPDATE_UI_HIDE = 0x2;

    private final IAutoFillManagerService mService;

    /**
     * @hide
     */
    public AutoFillManager(@SuppressWarnings("unused") Context context,
            IAutoFillManagerService service) {
        mService = service;
    }

    /**
     * Updates the auto-fill bar for a given {@link View}.
     *
     * <b>Typically called twice, with different flags ({@link #FLAG_UPDATE_UI_SHOW} and
     * {@link #FLAG_UPDATE_UI_HIDE} respectively), as the user "entered" and "exited" a view.
     *
     * @param view view to be updated.
     * @param flags either {@link #FLAG_UPDATE_UI_SHOW} or
     * {@link #FLAG_UPDATE_UI_HIDE}.
     */
    public void updateAutoFillInput(View view, int flags) {
        final Rect bounds = new Rect();
        view.getBoundsOnScreen(bounds);

        requestAutoFill(new AutoFillId(view.getAccessibilityViewId()), bounds, flags);
    }

    /**
     * Updates the auto-fill bar for a virtual child of a given {@link View}.
     *
     * <b>Typically called twice, with different flags ({@link #FLAG_UPDATE_UI_SHOW} and
     * {@link #FLAG_UPDATE_UI_HIDE} respectively), as the user "entered" and "exited" a view.
     *
     * @param parent parent view.
     * @param childId id identifying the virtual child inside the parent view.
     * @param bounds absolute boundaries of the child in the window (could be {@code null} when
     * flag is {@link #FLAG_UPDATE_UI_HIDE}.
     * @param flags either {@link #FLAG_UPDATE_UI_SHOW} or
     * {@link #FLAG_UPDATE_UI_HIDE}.
     */
    public void updateAutoFillInput(View parent, int childId, @Nullable Rect bounds,
            int flags) {
        requestAutoFill(new AutoFillId(parent.getAccessibilityViewId(), childId), bounds, flags);
    }

    private void requestAutoFill(AutoFillId id, Rect bounds, int flags) {
        if (DEBUG) {
            Log.v(TAG, "requestAutoFill(): id=" + id + ", bounds=" + bounds + ", flags=" + flags);
        }
        try {
            mService.requestAutoFill(id, bounds, flags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
