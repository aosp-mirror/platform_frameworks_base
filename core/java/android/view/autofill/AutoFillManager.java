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

import static android.view.autofill.Helper.VERBOSE;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.autofill.IAutoFillManagerService;
import android.util.Log;
import android.view.View;

/**
 * App entry point to the AutoFill Framework.
 */
// TODO(b/33197203): improve this javadoc
//TODO(b/33197203): restrict manager calls to activity
public final class AutoFillManager {

    private static final String TAG = "AutoFillManager";

    /** @hide */ public static final int FLAG_START_SESSION = 0x1;
    /** @hide */ public static final int FLAG_FOCUS_GAINED = 0x2;
    /** @hide */ public static final int FLAG_FOCUS_LOST = 0x4;
    /** @hide */ public static final int FLAG_VALUE_CHANGED = 0x8;

    private final IAutoFillManagerService mService;
    private final Context mContext;

    private AutoFillSession mSession;

    /**
     * @hide
     */
    public AutoFillManager(Context context, IAutoFillManagerService service) {
        mContext = context;
        mService = service;
    }

    /**
     * Called to indicate the focus on an auto-fillable {@link View} changed.
     *
     * @param view view whose focus changed.
     * @param gainFocus whether focus was gained or lost.
     */
    public void focusChanged(View view, boolean gainFocus) {
        if (mSession == null) {
            // Starts new session.
            final Rect bounds = new Rect();
            view.getBoundsOnScreen(bounds);
            final AutoFillId id = getAutoFillId(view);
            final AutoFillValue value = view.getAutoFillValue();
            startSession(id, bounds, value);
            return;
        }

        if (!mSession.isEnabled()) {
            // Auto-fill is disabled for this session.
            return;
        }

        // Update focus on existing session.
        final Rect bounds = new Rect();
        view.getBoundsOnScreen(bounds);
        final AutoFillId id = getAutoFillId(view);
        final AutoFillValue value = view.getAutoFillValue();
        updateSession(id, bounds, value, gainFocus ? FLAG_FOCUS_GAINED : FLAG_FOCUS_LOST);
    }

    /**
     * Called to indicate the focus on an auto-fillable virtual {@link View} changed.
     *
     * @param parent parent view whose focus changed.
     * @param childId id identifying the virtual child inside the parent view.
     * @param bounds child boundaries, relative to the top window.
     * @param gainFocus whether focus was gained or lost.
     */
    public void virtualFocusChanged(View parent, int childId, Rect bounds, boolean gainFocus) {
        if (mSession == null) {
            // Starts new session.
            final AutoFillId id = getAutoFillId(parent, childId);
            startSession(id, bounds, null);
            return;
        }

        if (!mSession.isEnabled()) {
            // Auto-fill is disabled for this session.
            return;
        }

        // Update focus on existing session.
        final AutoFillId id = getAutoFillId(parent, childId);
        updateSession(id, bounds, null, gainFocus ? FLAG_FOCUS_GAINED : FLAG_FOCUS_LOST);
    }

    /**
     * Called to indicate the value of an auto-fillable {@link View} changed.
     *
     * @param view view whose focus changed.
     */
    public void valueChanged(View view) {
        if (mSession == null) return;

        final AutoFillId id = getAutoFillId(view);
        final AutoFillValue value = view.getAutoFillValue();
        updateSession(id, null, value, FLAG_VALUE_CHANGED);
    }


    /**
     * Called to indicate the value of an auto-fillable virtual {@link View} changed.
     *
     * @param parent parent view whose value changed.
     * @param childId id identifying the virtual child inside the parent view.
     * @param value new value of the child.
     */
    public void virtualValueChanged(View parent, int childId, AutoFillValue value) {
        if (mSession == null) return;

        final AutoFillId id = getAutoFillId(parent, childId);
        updateSession(id, null, value, FLAG_VALUE_CHANGED);
    }

    /**
     * Called to indicate the current auto-fill context should be reset.
     *
     * <p>For example, when a virtual view is rendering an {@code HTML} page with a form, it should
     * call this method after the form is submitted and another page is rendered.
     */
    public void reset() {
        if (mSession == null) return;

        final IBinder activityToken = mSession.mToken.get();
        if (activityToken == null) {
            Log.wtf(TAG, "finishSession(): token already GC'ed");
            return;
        }
        try {
            mService.finishSession(activityToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } finally {
            mSession = null;
        }
    }

    /**
     * Gets the current session, if any.
     *
     * @hide
     */
    @Nullable
    public AutoFillSession getSession() {
        return mSession;
    }

    private AutoFillId getAutoFillId(View view) {
        return new AutoFillId(view.getAccessibilityViewId());
    }

    private AutoFillId getAutoFillId(View parent, int childId) {
        return new AutoFillId(parent.getAccessibilityViewId(), childId);
    }

    private void startSession(AutoFillId id, Rect bounds, AutoFillValue value) {
        if (VERBOSE) {
            Log.v(TAG, "startSession(): id=" + id + ", bounds=" + bounds + ", value=" + value);
        }

        final IBinder activityToken = mContext.getActivityToken();
        mSession = new AutoFillSession(this, activityToken);
        final IBinder appCallback = mSession.getCallback().asBinder();
        try {
            mService.startSession(activityToken, appCallback, id, bounds, value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void updateSession(AutoFillId id, Rect bounds, AutoFillValue value, int flags) {
        if (VERBOSE) {
            Log.v(TAG, "updateSession(): id=" + id + ", bounds=" + bounds + ", value=" + value
                    + ", flags=" + flags);
        }

        final IBinder activityToken = mSession.mToken.get();
        if (activityToken == null) {
            return;
        }
        try {
            mService.updateSession(activityToken, id, bounds, value, flags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
