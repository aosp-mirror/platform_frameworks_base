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

import static android.view.autofill.Helper.DEBUG;
import static android.view.autofill.Helper.VERBOSE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.WindowManagerGlobal;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * App entry point to the AutoFill Framework.
 */
// TODO(b/33197203): improve this javadoc
//TODO(b/33197203): restrict manager calls to activity
public final class AutoFillManager {

    private static final String TAG = "AutoFillManager";

    /**
     * Intent extra: The assist structure which captures the filled screen.
     * <p>
     * Type: {@link android.app.assist.AssistStructure}
     * </p>
     */
    public static final String EXTRA_ASSIST_STRUCTURE =
            "android.view.autofill.extra.ASSIST_STRUCTURE";

    /**
     * Intent extra: The result of an authentication operation. It is
     * either a fully populated {@link android.service.autofill.FillResponse}
     * or a fully populated {@link android.service.autofill.Dataset} if
     * a response or a dataset is being authenticated respectively.
     *
     * <p>
     * Type: {@link android.service.autofill.FillResponse} or a
     * {@link android.service.autofill.Dataset}
     * </p>
     */
    public static final String EXTRA_AUTHENTICATION_RESULT =
            "android.view.autofill.extra.AUTHENTICATION_RESULT";

    /** @hide */ public static final int FLAG_START_SESSION = 0x1;
    /** @hide */ public static final int FLAG_FOCUS_GAINED = 0x2;
    /** @hide */ public static final int FLAG_FOCUS_LOST = 0x4;
    /** @hide */ public static final int FLAG_VALUE_CHANGED = 0x8;

    private final Rect mTempRect = new Rect();

    private final IAutoFillManager mService;
    private IAutoFillManagerClient mServiceClient;

    private AutofillCallback mCallback;

    private Context mContext;

    private boolean mHasSession;
    private boolean mEnabled;

    /** @hide */
    public interface AutoFillClient {
        /**
         * Asks the client to perform an auto-fill.
         *
         * @param ids The values to auto-fill
         * @param values The values to auto-fill
         */
        void autoFill(List<AutoFillId> ids, List<AutoFillValue> values);

        /**
         * Asks the client to start an authentication flow.
         *
         * @param intent The authentication intent.
         * @param fillInIntent The authentication fill-in intent.
         */
        void authenticate(IntentSender intent, Intent fillInIntent);

        /**
         * Tells the client this manager has state to be reset.
         */
        void resetableStateAvailable();
    }

    /**
     * @hide
     */
    public AutoFillManager(Context context, IAutoFillManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Called when an auto-fill operation on a {@link View} should start.
     *
     * @param view {@link View} that triggered the auto-fill request.
     */
    public void startAutoFillRequest(@NonNull View view) {
        ensureServiceClientAddedIfNeeded();

        if (!mEnabled) {
            return;
        }

        final Rect bounds = mTempRect;
        view.getBoundsOnScreen(bounds);
        final AutoFillId id = getAutoFillId(view);
        final AutoFillValue value = view.getAutoFillValue();

        if (!mHasSession) {
            // Starts new session.
            startSession(id, view.getWindowToken(), bounds, value);
        } else {
            // Update focus on existing session.
            updateSession(id, bounds, value, FLAG_FOCUS_GAINED);
        }
    }

    /**
     * Called when an auto-fill operation on a {@link View} should stop.
     *
     * @param view {@link View} that triggered the auto-fill request in
     *             {@link #startAutoFillRequest(View)}.
     */
    public void stopAutoFillRequest(@NonNull View view) {
        ensureServiceClientAddedIfNeeded();

        if (mEnabled && mHasSession) {
            final AutoFillId id = getAutoFillId(view);

            // Update focus on existing session.
            updateSession(id, null, null, FLAG_FOCUS_LOST);
        }
    }

    /**
     * Called when an auto-fill operation on a virtual {@link View} should start.
     *
     * @param parent parent of the {@link View} that triggered the auto-fill request.
     * @param childId id identifying the virtual child inside the parent view.
     * @param bounds child boundaries, relative to the top window.
     */
    public void startAutoFillRequestOnVirtualView(@NonNull View parent, int childId,
            @NonNull Rect bounds) {
        ensureServiceClientAddedIfNeeded();

        if (!mEnabled) {
            return;
        }

        final AutoFillId id = getAutoFillId(parent, childId);

        if (!mHasSession) {
            // Starts new session.
            startSession(id, parent.getWindowToken(), bounds, null);
        } else {
            // Update focus on existing session.
            updateSession(id, bounds, null, FLAG_FOCUS_GAINED);
        }
    }

    /**
     * Called when an auto-fill operation on a virtual {@link View} should stop.
     *
     * @param parent parent of the {@link View} that triggered the auto-fill request in
     *               {@link #startAutoFillRequestOnVirtualView(View, int, Rect)}.
     * @param childId id identifying the virtual child inside the parent view.
     */
    public void stopAutoFillRequestOnVirtualView(@NonNull View parent, int childId) {
        ensureServiceClientAddedIfNeeded();

        if (mEnabled && mHasSession) {
            final AutoFillId id = getAutoFillId(parent, childId);

            // Update focus on existing session.
            updateSession(id, null, null, FLAG_FOCUS_LOST);
        }
    }

    /**
     * Called to indicate the value of an auto-fillable {@link View} changed.
     *
     * @param view view whose value changed.
     */
    public void valueChanged(View view) {
        if (!mEnabled || !mHasSession) {
            return;
        }

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
        if (!mEnabled || !mHasSession) {
            return;
        }

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
        if (!mEnabled && !mHasSession) {
            return;
        }

        finishSession();
    }

    private AutoFillClient getClient() {
        if (mContext instanceof AutoFillClient) {
            return (AutoFillClient) mContext;
        }
        return null;
    }

    /** @hide */
    public void onAuthenticationResult(Intent data) {
        // TODO(b/33197203): the result code is being ignored, so this method is not reliably
        // handling the cases where it's not RESULT_OK: it works fine if the service does not
        // set the EXTRA_AUTHENTICATION_RESULT extra, but it could cause weird results if the
        // service set the extra and returned RESULT_CANCELED...

        if (DEBUG) Log.d(TAG, "onAuthenticationResult(): d=" + data);

        if (data == null) {
            return;
        }
        final Parcelable result = data.getParcelableExtra(EXTRA_AUTHENTICATION_RESULT);
        final Bundle responseData = new Bundle();
        responseData.putParcelable(EXTRA_AUTHENTICATION_RESULT, result);
        try {
            mService.setAuthenticationResult(responseData,
                    mContext.getActivityToken(), mContext.getUserId());
        } catch (RemoteException e) {
            Log.e(TAG, "Error delivering authentication result", e);
        }
    }

    private static AutoFillId getAutoFillId(View view) {
        return new AutoFillId(view.getAccessibilityViewId());
    }

    private static AutoFillId getAutoFillId(View parent, int childId) {
        return new AutoFillId(parent.getAccessibilityViewId(), childId);
    }

    private void startSession(AutoFillId id, IBinder windowToken,
            Rect bounds, AutoFillValue value) {
        if (DEBUG) {
            Log.d(TAG, "startSession(): id=" + id + ", bounds=" + bounds + ", value=" + value);
        }

        try {
            mService.startSession(mContext.getActivityToken(), windowToken,
                    mServiceClient.asBinder(), id, bounds, value, mContext.getUserId(),
                    mCallback != null);
            final AutoFillClient client = getClient();
            if (client != null) {
                client.resetableStateAvailable();
            }
            mHasSession = true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void finishSession() {
        if (DEBUG) {
            Log.d(TAG, "finishSession()");
        }
        mHasSession = false;
        try {
            mService.finishSession(mContext.getActivityToken(), mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void updateSession(AutoFillId id, Rect bounds, AutoFillValue value, int flags) {
        if (DEBUG) {
            if (VERBOSE || (flags & FLAG_FOCUS_LOST) != 0) {
                Log.d(TAG, "updateSession(): id=" + id + ", bounds=" + bounds + ", value=" + value
                    + ", flags=" + flags);
            }
        }

        try {
            mService.updateSession(mContext.getActivityToken(), id, bounds, value, flags,
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void ensureServiceClientAddedIfNeeded() {
        if (getClient() == null) {
            return;
        }
        if (mServiceClient == null) {
            mServiceClient = new AutoFillManagerClient(this);
            try {
                mEnabled = mService.addClient(mServiceClient, mContext.getUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Registers a {@link AutofillCallback} to receive autofill events.
     *
     * @param callback callback to receive events.
     */
    public void registerCallback(@Nullable AutofillCallback callback) {
        if (callback == null) return;

        final boolean hadCallback = mCallback != null;
        mCallback = callback;

        if (mHasSession && !hadCallback) {
            try {
                mService.setHasCallback(mContext.getActivityToken(), mContext.getUserId(), true);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Unregisters a {@link AutofillCallback} to receive autofill events.
     *
     * @param callback callback to stop receiving events.
     */
    public void unregisterCallback(@Nullable AutofillCallback callback) {
        if (callback == null || mCallback == null || callback != mCallback) return;

        mCallback = null;

        if (mHasSession) {
            try {
                mService.setHasCallback(mContext.getActivityToken(), mContext.getUserId(), false);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    private void onAutofillEvent(IBinder windowToken, AutoFillId id, int event) {
        if (mCallback == null) return;
        if (id == null) {
            Log.w(TAG, "onAutofillEvent(): no id for event " + event);
            return;
        }

        final View root = WindowManagerGlobal.getInstance().getWindowView(windowToken);
        if (root == null) {
            Log.w(TAG, "onAutofillEvent() for " + id + ": root view gone");
            return;
        }
        final View view = root.findViewByAccessibilityIdTraversal(id.getViewId());
        if (view == null) {
            Log.w(TAG, "onAutofillEvent() for " + id + ": view gone");
            return;
        }
        if (id.isVirtual()) {
            mCallback.onAutofillEventVirtual(view, id.getVirtualChildId(), event);
        } else {
            mCallback.onAutofillEvent(view, event);
        }
    }

    /**
     * Callback for auto-fill related events.
     *
     * <p>Typically used for applications that display their own "auto-complete" views, so they can
     * enable / disable such views when the auto-fill UI affordance is shown / hidden.
     */
    public abstract static class AutofillCallback {

        /** @hide */
        @IntDef({EVENT_INPUT_SHOWN, EVENT_INPUT_HIDDEN})
        @Retention(RetentionPolicy.SOURCE)
        public @interface AutofillEventType {}

        /**
         * The auto-fill input UI affordance associated with the view was shown.
         *
         * <p>If the view provides its own auto-complete UI affordance and its currently shown, it
         * should be hidden upon receiving this event.
         */
        public static final int EVENT_INPUT_SHOWN = 1;

        /**
         * The auto-fill input UI affordance associated with the view was hidden.
         *
         * <p>If the view provides its own auto-complete UI affordance that was hidden upon a
         * {@link #EVENT_INPUT_SHOWN} event, it could be shown again now.
         */
        public static final int EVENT_INPUT_HIDDEN = 2;

        /**
         * Called after a change in the autofill state associated with a view.
         *
         * @param view view associated with the change.
         *
         * @param event currently either {@link #EVENT_INPUT_SHOWN} or {@link #EVENT_INPUT_HIDDEN}.
         */
        public void onAutofillEvent(@NonNull View view, @AutofillEventType int event) {}

        /**
         * Called after a change in the autofill state associated with a virtual view.
         *
         * @param view parent view associated with the change.
         * @param childId id identifying the virtual child inside the parent view.
         *
         * @param event currently either {@link #EVENT_INPUT_SHOWN} or {@link #EVENT_INPUT_HIDDEN}.
         */
        public void onAutofillEventVirtual(@NonNull View view, int childId,
                @AutofillEventType int event) {}
    }

    private static final class AutoFillManagerClient extends IAutoFillManagerClient.Stub {
        private final WeakReference<AutoFillManager> mAutoFillManager;

        AutoFillManagerClient(AutoFillManager autoFillManager) {
            mAutoFillManager = new WeakReference<>(autoFillManager);
        }

        @Override
        public void setState(boolean enabled) {
            final AutoFillManager autoFillManager = mAutoFillManager.get();
            if (autoFillManager != null) {
                autoFillManager.mContext.getMainThreadHandler().post(() ->
                        autoFillManager.mEnabled = enabled);
            }
        }

        @Override
        public void autoFill(List<AutoFillId> ids, List<AutoFillValue> values) {
            // TODO(b/33197203): must keep the dataset so subsequent calls pass the same
            // dataset.extras to service
            final AutoFillManager autoFillManager = mAutoFillManager.get();
            if (autoFillManager != null) {
                autoFillManager.mContext.getMainThreadHandler().post(() -> {
                    if (autoFillManager.getClient() != null) {
                        autoFillManager.getClient().autoFill(ids, values);
                    }
                });
            }
        }

        @Override
        public void authenticate(IntentSender intent, Intent fillInIntent) {
            final AutoFillManager autoFillManager = mAutoFillManager.get();
            if (autoFillManager != null) {
                autoFillManager.mContext.getMainThreadHandler().post(() -> {
                    if (autoFillManager.getClient() != null) {
                        autoFillManager.getClient().authenticate(intent, fillInIntent);
                    }
                });
            }
        }

        @Override
        public void onAutofillEvent(IBinder windowToken, AutoFillId id, int event) {
            final AutoFillManager autoFillManager = mAutoFillManager.get();
            if (autoFillManager != null) {
                autoFillManager.mContext.getMainThreadHandler().post(() -> {
                    if (autoFillManager.getClient() != null) {
                        autoFillManager.onAutofillEvent(windowToken, id, event);
                    }
                });
            }
        }
    }
}
