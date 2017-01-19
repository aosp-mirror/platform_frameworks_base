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
    public static final int FLAG_UPDATE_UI_SHOW = 1 << 0;

    /**
     * Flag used to hide the auto-fill UI affordance for a view.
     */
    public static final int FLAG_UPDATE_UI_HIDE = 1 << 1;

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
        if (DEBUG) {
            Log.v(TAG, "updateAutoFillInput(" + view.getAutoFillViewId() + "): flags=" + flags);
        }

        updateAutoFillInput(view, false, View.NO_ID, null, flags);
    }

    /**
     * Updates the auto-fill bar for a virtual child of a given {@link View}.
     *
     * <b>Typically called twice, with different flags ({@link #FLAG_UPDATE_UI_SHOW} and
     * {@link #FLAG_UPDATE_UI_HIDE} respectively), as the user "entered" and "exited" a view.
     *
     * @param parent parent view.
     * @param childId id identifying the virtual child inside the parent view.
     * @param boundaries boundaries of the child (inside the parent; could be {@code null} when
     * flag is {@link #FLAG_UPDATE_UI_HIDE}.
     * @param flags either {@link #FLAG_UPDATE_UI_SHOW} or
     * {@link #FLAG_UPDATE_UI_HIDE}.
     */
    public void updateAutoFillInput(View parent, int childId, @Nullable Rect boundaries,
            int flags) {
        if (DEBUG) {
            Log.v(TAG, "updateAutoFillInput(" + parent.getAutoFillViewId() + ", " + childId
                    + "): boundaries=" + boundaries + ", flags=" + flags);
        }
        updateAutoFillInput(parent, true, childId, boundaries, flags);
    }

    private void updateAutoFillInput(View view, boolean virtual, int childId, Rect boundaries,
            int flags) {
        if ((flags & FLAG_UPDATE_UI_SHOW) != 0) {
            final int viewId = view.getAutoFillViewId();
            final AutoFillId id = virtual
                    ? new AutoFillId(viewId, childId)
                    : new AutoFillId(viewId);
            showAutoFillInput(id, boundaries);
            return;
        }
        // TODO(b/33197203): handle FLAG_UPDATE_UI_HIDE
    }

    private void showAutoFillInput(AutoFillId id, Rect boundaries) {
        final int autoFillViewId = id.getViewId();
        /*
         * TODO(b/33197203): currently SHOW_AUTO_FILL_BAR is only set once per activity (i.e, when
         * the view does not have an auto-fill id), but it should be called again for views that
         * were not part of the initial auto-fill dataset returned by the service. For example:
         *
         * 1.Activity has 4 fields, `first_name`, `last_name`, and `address`.
         * 2.User taps `first_name`.
         * 3.Service returns a dataset with ids for `first_name` and `last_name`.
         * 4.When user taps `first_name` (again) or `last_name`, flag should not have
         *   SHOW_AUTO_FILL_BAR set, but when user taps `address`, it should (since that field was
         *   not part of the initial dataset).
         *
         * Similarly, once the activity is auto-filled, the flag logic should be reset (so if the
         * user taps the view again, a new auto-fill request is made)
         */
        if (autoFillViewId != View.NO_ID) {
            return;
        }

        try {
            mService.showAutoFillInput(id, boundaries);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
