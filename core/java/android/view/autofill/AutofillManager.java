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
public final class AutofillManager {

    private static final String TAG = "AutofillManager";

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

    // Public flags start from the lowest bit
    /**
     * Indicates autofill was explicitly requested by the user.
     */
    public static final int FLAG_MANUAL_REQUEST = 0x1;

    // Private flags start from the highest bit
    /** @hide */ public static final int FLAG_START_SESSION = 0x80000000;
    /** @hide */ public static final int FLAG_VIEW_ENTERED =  0x40000000;
    /** @hide */ public static final int FLAG_VIEW_EXITED =   0x20000000;
    /** @hide */ public static final int FLAG_VALUE_CHANGED = 0x10000000;

    @NonNull private final Rect mTempRect = new Rect();

    private final IAutoFillManager mService;
    private IAutoFillManagerClient mServiceClient;

    private AutofillCallback mCallback;

    private Context mContext;

    private boolean mHasSession;
    private boolean mEnabled;

    /** @hide */
    public interface AutofillClient {
        /**
         * Asks the client to perform an autofill.
         *
         * @param ids The values to autofill
         * @param values The values to autofill
         */
        void autofill(List<AutofillId> ids, List<AutofillValue> values);

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
    public AutofillManager(Context context, IAutoFillManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Checkes whether autofill is enabled for the current user.
     *
     * <p>Typically used to determine whether the option to explicitly request autofill should
     * be offered - see {@link #requestAutofill(View)}.
     *
     * @return whether autofill is enabled for the current user.
     */
    public boolean isEnabled() {
        ensureServiceClientAddedIfNeeded();
        return mEnabled;
    }

    /**
     * Explicitly requests a new autofill context.
     *
     * <p>Normally, the autofill context is automatically started when autofillable views are
     * focused, but this method should be used in the cases where it must be explicitly requested,
     * like a view that provides a contextual menu allowing users to autofill the activity.
     *
     * @param view view requesting the new autofill context.
     */
    public void requestAutofill(@NonNull View view) {
        ensureServiceClientAddedIfNeeded();

        if (!mEnabled) {
            return;
        }

        final Rect bounds = mTempRect;
        view.getBoundsOnScreen(bounds);
        final AutofillId id = getAutofillId(view);
        final AutofillValue value = view.getAutofillValue();

        startSession(id, view.getWindowToken(), bounds, value, FLAG_MANUAL_REQUEST);
    }

    /**
     * Explicitly requests a new autofill context for virtual views.
     *
     * <p>Normally, the autofill context is automatically started when autofillable views are
     * focused, but this method should be used in the cases where it must be explicitly requested,
     * like a virtual view that provides a contextual menu allowing users to autofill the activity.
     *
     * @param view the {@link View} whose descendant is the virtual view.
     * @param childId id identifying the virtual child inside the view.
     * @param bounds child boundaries, relative to the top window.
     */
    public void requestAutofill(@NonNull View view, int childId, @NonNull Rect bounds) {
        ensureServiceClientAddedIfNeeded();

        if (!mEnabled) {
            return;
        }

        final AutofillId id = getAutofillId(view, childId);
        startSession(id, view.getWindowToken(), bounds, null, FLAG_MANUAL_REQUEST);
    }


    /**
     * Called when a {@link View} that supports autofill is entered.
     *
     * @param view {@link View} that was entered.
     */
    public void notifyViewEntered(@NonNull View view) {
        ensureServiceClientAddedIfNeeded();

        if (!mEnabled) {
            return;
        }

        final Rect bounds = mTempRect;
        view.getBoundsOnScreen(bounds);
        final AutofillId id = getAutofillId(view);
        final AutofillValue value = view.getAutofillValue();

        if (!mHasSession) {
            // Starts new session.
            startSession(id, view.getWindowToken(), bounds, value, 0);
        } else {
            // Update focus on existing session.
            updateSession(id, bounds, value, FLAG_VIEW_ENTERED);
        }
    }

    /**
     * Called when a {@link View} that supports autofill is exited.
     *
     * @param view {@link View} that was exited.
     */
    public void notifyViewExited(@NonNull View view) {
        ensureServiceClientAddedIfNeeded();

        if (mEnabled && mHasSession) {
            final AutofillId id = getAutofillId(view);

            // Update focus on existing session.
            updateSession(id, null, null, FLAG_VIEW_EXITED);
        }
    }

    /**
     * Called when a virtual view that supports autofill is entered.
     *
     * @param view the {@link View} whose descendant is the virtual view.
     * @param childId id identifying the virtual child inside the view.
     * @param bounds child boundaries, relative to the top window.
     */
    public void notifyVirtualViewEntered(@NonNull View view, int childId,
            @NonNull Rect bounds) {
        ensureServiceClientAddedIfNeeded();

        if (!mEnabled) {
            return;
        }

        final AutofillId id = getAutofillId(view, childId);

        if (!mHasSession) {
            // Starts new session.
            startSession(id, view.getWindowToken(), bounds, null, 0);
        } else {
            // Update focus on existing session.
            updateSession(id, bounds, null, FLAG_VIEW_ENTERED);
        }
    }

    /**
     * Called when a virtual view that supports autofill is exited.
     *
     * @param view the {@link View} whose descendant is the virtual view.
     * @param childId id identifying the virtual child inside the view.
     */
    public void notifyVirtualViewExited(@NonNull View view, int childId) {
        ensureServiceClientAddedIfNeeded();

        if (mEnabled && mHasSession) {
            final AutofillId id = getAutofillId(view, childId);

            // Update focus on existing session.
            updateSession(id, null, null, FLAG_VIEW_EXITED);
        }
    }

    /**
     * Called to indicate the value of an autofillable {@link View} changed.
     *
     * @param view view whose value changed.
     */
    public void notifyValueChanged(View view) {
        if (!mEnabled || !mHasSession) {
            return;
        }

        final AutofillId id = getAutofillId(view);
        final AutofillValue value = view.getAutofillValue();
        updateSession(id, null, value, FLAG_VALUE_CHANGED);
    }


    /**
     * Called to indicate the value of an autofillable virtual {@link View} changed.
     *
     * @param view the {@link View} whose descendant is the virtual view.
     * @param childId id identifying the virtual child inside the parent view.
     * @param value new value of the child.
     */
    public void notifyVirtualValueChanged(View view, int childId, AutofillValue value) {
        if (!mEnabled || !mHasSession) {
            return;
        }

        final AutofillId id = getAutofillId(view, childId);
        updateSession(id, null, value, FLAG_VALUE_CHANGED);
    }

    /**
     * Called to indicate the current autofill context should be commited.
     *
     * <p>For example, when a virtual view is rendering an {@code HTML} page with a form, it should
     * call this method after the form is submitted and another page is rendered.
     */
    public void commit() {
        if (!mEnabled && !mHasSession) {
            return;
        }

        finishSession();
    }

    /**
     * Called to indicate the current autofill context should be cancelled.
     *
     * <p>For example, when a virtual view is rendering an {@code HTML} page with a form, it should
     * call this method if the user does not post the form but moves to another form in this page.
     */
    public void cancel() {
        if (!mEnabled && !mHasSession) {
            return;
        }

        cancelSession();
    }

    private AutofillClient getClient() {
        if (mContext instanceof AutofillClient) {
            return (AutofillClient) mContext;
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


    private static AutofillId getAutofillId(View view) {
        return new AutofillId(view.getAccessibilityViewId());
    }

    private static AutofillId getAutofillId(View parent, int childId) {
        return new AutofillId(parent.getAccessibilityViewId(), childId);
    }

    private void startSession(@NonNull AutofillId id, @NonNull IBinder windowToken,
            @NonNull Rect bounds, @NonNull AutofillValue value, int flags) {
        if (DEBUG) {
            Log.d(TAG, "startSession(): id=" + id + ", bounds=" + bounds + ", value=" + value
                    + ", flags=" + flags);
        }

        try {
            mService.startSession(mContext.getActivityToken(), windowToken,
                    mServiceClient.asBinder(), id, bounds, value, mContext.getUserId(),
                    mCallback != null, flags, mContext.getOpPackageName());
            AutofillClient client = getClient();
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

    private void cancelSession() {
        if (DEBUG) {
            Log.d(TAG, "cancelSession()");
        }
        mHasSession = false;
        try {
            mService.cancelSession(mContext.getActivityToken(), mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void updateSession(AutofillId id, Rect bounds, AutofillValue value, int flags) {
        if (DEBUG) {
            if (VERBOSE || (flags & FLAG_VIEW_EXITED) != 0) {
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
            mServiceClient = new AutofillManagerClient(this);
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

    private void onAutofillEvent(IBinder windowToken, AutofillId id, int event) {
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

    private static final class AutofillManagerClient extends IAutoFillManagerClient.Stub {
        private final WeakReference<AutofillManager> mAfm;

        AutofillManagerClient(AutofillManager autofillManager) {
            mAfm = new WeakReference<>(autofillManager);
        }

        @Override
        public void setState(boolean enabled) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.mContext.getMainThreadHandler().post(() -> afm.mEnabled = enabled);
            }
        }

        @Override
        public void autofill(List<AutofillId> ids, List<AutofillValue> values) {
            // TODO(b/33197203): must keep the dataset so subsequent calls pass the same
            // dataset.extras to service
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.mContext.getMainThreadHandler().post(() -> {
                    if (afm.getClient() != null) {
                        afm.getClient().autofill(ids, values);
                    }
                });
            }
        }

        @Override
        public void authenticate(IntentSender intent, Intent fillInIntent) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.mContext.getMainThreadHandler().post(() -> {
                    if (afm.getClient() != null) {
                        afm.getClient().authenticate(intent, fillInIntent);
                    }
                });
            }
        }

        @Override
        public void onAutofillEvent(IBinder windowToken, AutofillId id, int event) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.mContext.getMainThreadHandler().post(() -> {
                    if (afm.getClient() != null) {
                        afm.onAutofillEvent(windowToken, id, event);
                    }
                });
            }
        }
    }
}
