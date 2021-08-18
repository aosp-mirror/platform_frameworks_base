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

package android.view.autofill;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManagerGlobal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * A controller to manage the autofill requests for the {@link Activity}.
 *
 * @hide
 */
public final class AutofillClientController implements AutofillManager.AutofillClient {

    private static final String TAG = "AutofillClientController";

    private static final String LOG_TAG = "autofill_client";
    public static final boolean DEBUG = Log.isLoggable(LOG_TAG, Log.DEBUG);

    public static final String LAST_AUTOFILL_ID = "android:lastAutofillId";
    public static final String AUTOFILL_RESET_NEEDED = "@android:autofillResetNeeded";
    public static final String AUTO_FILL_AUTH_WHO_PREFIX = "@android:autoFillAuth:";

    /** The last autofill id that was returned from {@link #getNextAutofillId()} */
    public int mLastAutofillId = View.LAST_APP_AUTOFILL_ID;

    @NonNull
    private final Activity mActivity;
    /** The autofill manager. Always access via {@link #getAutofillManager()}. */
    @Nullable
    private AutofillManager mAutofillManager;
    /** The autofill dropdown fill ui. */
    @Nullable
    private AutofillPopupWindow mAutofillPopupWindow;
    private boolean mAutoFillResetNeeded;
    private boolean mAutoFillIgnoreFirstResumePause;

    /**
     * AutofillClientController constructor.
     */
    public AutofillClientController(Activity activity) {
        mActivity = activity;
    }

    private AutofillManager getAutofillManager() {
        if (mAutofillManager == null) {
            mAutofillManager = mActivity.getSystemService(AutofillManager.class);
        }
        return mAutofillManager;
    }

    // ------------------ Called for Activity events ------------------

    /**
     * Called when the Activity is attached.
     */
    public void onActivityAttached(Application application) {
        mActivity.setAutofillOptions(application.getAutofillOptions());
    }

    /**
     * Called when the {@link Activity#onCreate(Bundle)} is called.
     */
    public void onActivityCreated(@NonNull Bundle savedInstanceState) {
        mAutoFillResetNeeded = savedInstanceState.getBoolean(AUTOFILL_RESET_NEEDED, false);
        mLastAutofillId = savedInstanceState.getInt(LAST_AUTOFILL_ID, View.LAST_APP_AUTOFILL_ID);
        if (mAutoFillResetNeeded) {
            getAutofillManager().onCreate(savedInstanceState);
        }
    }

    /**
     * Called when the {@link Activity#onStart()} is called.
     */
    public void onActivityStarted() {
        if (mAutoFillResetNeeded) {
            getAutofillManager().onVisibleForAutofill();
        }
    }

    /**
     * Called when the {@link Activity#onResume()} is called.
     */
    public void onActivityResumed() {
        enableAutofillCompatibilityIfNeeded();
        if (mAutoFillResetNeeded) {
            if (!mAutoFillIgnoreFirstResumePause) {
                View focus = mActivity.getCurrentFocus();
                if (focus != null && focus.canNotifyAutofillEnterExitEvent()) {
                    // TODO(b/148815880): Bring up keyboard if resumed from inline authentication.
                    // TODO: in Activity killed/recreated case, i.e. SessionLifecycleTest#
                    // testDatasetVisibleWhileAutofilledAppIsLifecycled: the View's initial
                    // window visibility after recreation is INVISIBLE in onResume() and next frame
                    // ViewRootImpl.performTraversals() changes window visibility to VISIBLE.
                    // So we cannot call View.notifyEnterOrExited() which will do nothing
                    // when View.isVisibleToUser() is false.
                    getAutofillManager().notifyViewEntered(focus);
                }
            }
        }
    }

    /**
     * Called when the Activity is performing resume.
     */
    public void onActivityPerformResume(boolean followedByPause) {
        if (mAutoFillResetNeeded) {
            // When Activity is destroyed in paused state, and relaunch activity, there will be
            // extra onResume and onPause event,  ignore the first onResume and onPause.
            // see ActivityThread.handleRelaunchActivity()
            mAutoFillIgnoreFirstResumePause = followedByPause;
            if (mAutoFillIgnoreFirstResumePause && DEBUG) {
                Slog.v(TAG, "autofill will ignore first pause when relaunching " + this);
            }
        }
    }

    /**
     * Called when the {@link Activity#onPause()} is called.
     */
    public void onActivityPaused() {
        if (mAutoFillResetNeeded) {
            if (!mAutoFillIgnoreFirstResumePause) {
                if (DEBUG) Log.v(TAG, "autofill notifyViewExited " + this);
                View focus = mActivity.getCurrentFocus();
                if (focus != null && focus.canNotifyAutofillEnterExitEvent()) {
                    getAutofillManager().notifyViewExited(focus);
                }
            } else {
                // reset after first pause()
                if (DEBUG) Log.v(TAG, "autofill got first pause " + this);
                mAutoFillIgnoreFirstResumePause = false;
            }
        }
    }

    /**
     * Called when the {@link Activity#onStop()} is called.
     */
    public void onActivityStopped(Intent intent, boolean changingConfigurations) {
        if (mAutoFillResetNeeded) {
            // If stopped without changing the configurations, the response should expire.
            getAutofillManager().onInvisibleForAutofill(!changingConfigurations);
        } else if (intent != null
                && intent.hasExtra(AutofillManager.EXTRA_RESTORE_SESSION_TOKEN)
                && intent.hasExtra(AutofillManager.EXTRA_RESTORE_CROSS_ACTIVITY)) {
            restoreAutofillSaveUi(intent);
        }
    }

    /**
     * Called when the {@link Activity#onDestroy()} is called.
     */
    public void onActivityDestroyed() {
        if (mActivity.isFinishing() && mAutoFillResetNeeded) {
            getAutofillManager().onActivityFinishing();
        }
    }

    /**
     * Called when the {@link Activity#onSaveInstanceState(Bundle)} is called.
     */
    public void onSaveInstanceState(Bundle outState) {
        outState.putInt(LAST_AUTOFILL_ID, mLastAutofillId);
        if (mAutoFillResetNeeded) {
            outState.putBoolean(AUTOFILL_RESET_NEEDED, true);
            getAutofillManager().onSaveInstanceState(outState);
        }
    }

    /**
     * Called when the {@link Activity#finish()} is called.
     */
    public void onActivityFinish(Intent intent) {
        // Activity was launched when user tapped a link in the Autofill Save UI - Save UI must
        // be restored now.
        if (intent != null && intent.hasExtra(AutofillManager.EXTRA_RESTORE_SESSION_TOKEN)) {
            restoreAutofillSaveUi(intent);
        }
    }

    /**
     * Called when the {@link Activity#onBackPressed()} is called.
     */
    public void onActivityBackPressed(Intent intent) {
        // Activity was launched when user tapped a link in the Autofill Save UI - Save UI must
        // be restored now.
        if (intent != null && intent.hasExtra(AutofillManager.EXTRA_RESTORE_SESSION_TOKEN)) {
            restoreAutofillSaveUi(intent);
        }
    }

    /**
     * Called when the Activity is dispatching the result.
     */
    public void onDispatchActivityResult(int requestCode, int resultCode, Intent data) {
        Intent resultData = (resultCode == Activity.RESULT_OK) ? data : null;
        getAutofillManager().onAuthenticationResult(requestCode, resultData,
                mActivity.getCurrentFocus());
    }

    /**
     * Called when the {@link Activity#startActivity(Intent, Bundle)} is called.
     */
    public void onStartActivity(Intent startIntent, Intent cachedIntent) {
        if (cachedIntent != null
                && cachedIntent.hasExtra(AutofillManager.EXTRA_RESTORE_SESSION_TOKEN)
                && cachedIntent.hasExtra(AutofillManager.EXTRA_RESTORE_CROSS_ACTIVITY)) {
            if (TextUtils.equals(mActivity.getPackageName(),
                    startIntent.resolveActivity(mActivity.getPackageManager()).getPackageName())) {
                // Apply Autofill restore mechanism on the started activity by startActivity()
                final IBinder token =
                        cachedIntent.getIBinderExtra(AutofillManager.EXTRA_RESTORE_SESSION_TOKEN);
                // Remove restore ability from current activity
                cachedIntent.removeExtra(AutofillManager.EXTRA_RESTORE_SESSION_TOKEN);
                cachedIntent.removeExtra(AutofillManager.EXTRA_RESTORE_CROSS_ACTIVITY);
                // Put restore token
                startIntent.putExtra(AutofillManager.EXTRA_RESTORE_SESSION_TOKEN, token);
                startIntent.putExtra(AutofillManager.EXTRA_RESTORE_CROSS_ACTIVITY, true);
            }
        }
    }

    /**
     * Restore the autofill save ui.
     */
    public void restoreAutofillSaveUi(Intent intent) {
        final IBinder token =
                intent.getIBinderExtra(AutofillManager.EXTRA_RESTORE_SESSION_TOKEN);
        // Make only restore Autofill once
        intent.removeExtra(AutofillManager.EXTRA_RESTORE_SESSION_TOKEN);
        intent.removeExtra(AutofillManager.EXTRA_RESTORE_CROSS_ACTIVITY);
        getAutofillManager().onPendingSaveUi(AutofillManager.PENDING_UI_OPERATION_RESTORE,
                token);
    }

    /**
     * Enable autofill compatibility mode for the Activity if the compatibility mode is enabled
     * for the package.
     */
    public void enableAutofillCompatibilityIfNeeded() {
        if (mActivity.isAutofillCompatibilityEnabled()) {
            final AutofillManager afm = mActivity.getSystemService(AutofillManager.class);
            if (afm != null) {
                afm.enableCompatibilityMode();
            }
        }
    }

    /**
     * Prints autofill related information for the Activity.
     */
    public void dumpAutofillManager(String prefix, PrintWriter writer) {
        final AutofillManager afm = getAutofillManager();
        if (afm != null) {
            afm.dump(prefix, writer);
            writer.print(prefix); writer.print("Autofill Compat Mode: ");
            writer.println(mActivity.isAutofillCompatibilityEnabled());
        } else {
            writer.print(prefix); writer.println("No AutofillManager");
        }
    }

    /**
     * Returns the next autofill ID that is unique in the activity
     *
     * <p>All IDs will be bigger than {@link View#LAST_APP_AUTOFILL_ID}. All IDs returned
     * will be unique.
     */
    public int getNextAutofillId() {
        if (mLastAutofillId == Integer.MAX_VALUE - 1) {
            mLastAutofillId = View.LAST_APP_AUTOFILL_ID;
        }

        mLastAutofillId++;

        return mLastAutofillId;
    }

    // ------------------ AutofillClient implementation ------------------

    @Override
    public AutofillId autofillClientGetNextAutofillId() {
        return new AutofillId(getNextAutofillId());
    }

    @Override
    public boolean autofillClientIsCompatibilityModeEnabled() {
        return mActivity.isAutofillCompatibilityEnabled();
    }

    @Override
    public boolean autofillClientIsVisibleForAutofill() {
        return !mActivity.isVisibleForAutofill();
    }

    @Override
    public ComponentName autofillClientGetComponentName() {
        return mActivity.getComponentName();
    }

    @Override
    public IBinder autofillClientGetActivityToken() {
        return mActivity.getActivityToken();
    }

    @Override
    public boolean[] autofillClientGetViewVisibility(AutofillId[] autofillIds) {
        final int autofillIdCount = autofillIds.length;
        final boolean[] visible = new boolean[autofillIdCount];
        for (int i = 0; i < autofillIdCount; i++) {
            final AutofillId autofillId = autofillIds[i];
            final View view = autofillClientFindViewByAutofillIdTraversal(autofillId);
            if (view != null) {
                if (!autofillId.isVirtualInt()) {
                    visible[i] = view.isVisibleToUser();
                } else {
                    visible[i] = view.isVisibleToUserForAutofill(autofillId.getVirtualChildIntId());
                }
            }
        }
        if (android.view.autofill.Helper.sVerbose) {
            Log.v(TAG, "autofillClientGetViewVisibility(): " + Arrays.toString(visible));
        }
        return visible;
    }

    @Override
    public View autofillClientFindViewByAccessibilityIdTraversal(int viewId, int windowId) {
        final ArrayList<ViewRootImpl> roots = WindowManagerGlobal.getInstance()
                .getRootViews(mActivity.getActivityToken());
        for (int rootNum = 0; rootNum < roots.size(); rootNum++) {
            final View rootView = roots.get(rootNum).getView();
            if (rootView != null && rootView.getAccessibilityWindowId() == windowId) {
                final View view = rootView.findViewByAccessibilityIdTraversal(viewId);
                if (view != null) {
                    return view;
                }
            }
        }
        return null;
    }

    @Override
    public View autofillClientFindViewByAutofillIdTraversal(AutofillId autofillId) {
        final ArrayList<ViewRootImpl> roots =
                WindowManagerGlobal.getInstance().getRootViews(mActivity.getActivityToken());
        for (int rootNum = 0; rootNum < roots.size(); rootNum++) {
            final View rootView = roots.get(rootNum).getView();

            if (rootView != null) {
                final View view = rootView.findViewByAutofillIdTraversal(autofillId.getViewId());
                if (view != null) {
                    return view;
                }
            }
        }
        return null;
    }

    @Override
    public View[] autofillClientFindViewsByAutofillIdTraversal(AutofillId[] autofillIds) {
        final View[] views = new View[autofillIds.length];
        final ArrayList<ViewRootImpl> roots =
                WindowManagerGlobal.getInstance().getRootViews(mActivity.getActivityToken());

        for (int rootNum = 0; rootNum < roots.size(); rootNum++) {
            final View rootView = roots.get(rootNum).getView();

            if (rootView != null) {
                final int viewCount = autofillIds.length;
                for (int viewNum = 0; viewNum < viewCount; viewNum++) {
                    if (views[viewNum] == null) {
                        views[viewNum] = rootView.findViewByAutofillIdTraversal(
                                autofillIds[viewNum].getViewId());
                    }
                }
            }
        }
        return views;
    }

    @Override
    public boolean autofillClientIsFillUiShowing() {
        return mAutofillPopupWindow != null && mAutofillPopupWindow.isShowing();
    }

    @Override
    public boolean autofillClientRequestHideFillUi() {
        if (mAutofillPopupWindow == null) {
            return false;
        }
        mAutofillPopupWindow.dismiss();
        mAutofillPopupWindow = null;
        return true;
    }

    @Override
    public boolean autofillClientRequestShowFillUi(@NonNull View anchor, int width,
            int height, @Nullable Rect anchorBounds, IAutofillWindowPresenter presenter) {
        final boolean wasShowing;

        if (mAutofillPopupWindow == null) {
            wasShowing = false;
            mAutofillPopupWindow = new AutofillPopupWindow(presenter);
        } else {
            wasShowing = mAutofillPopupWindow.isShowing();
        }
        mAutofillPopupWindow.update(anchor, 0, 0, width, height, anchorBounds);

        return !wasShowing && mAutofillPopupWindow.isShowing();
    }

    @Override
    public void autofillClientDispatchUnhandledKey(View anchor, KeyEvent keyEvent) {
        ViewRootImpl rootImpl = anchor.getViewRootImpl();
        if (rootImpl != null) {
            // don't care if anchorView is current focus, for example a custom view may only receive
            // touchEvent, not focusable but can still trigger autofill window. The Key handling
            // might be inside parent of the custom view.
            rootImpl.dispatchKeyFromAutofill(keyEvent);
        }
    }

    @Override
    public boolean isDisablingEnterExitEventForAutofill() {
        return mAutoFillIgnoreFirstResumePause || !mActivity.isResumed();
    }

    @Override
    public void autofillClientResetableStateAvailable() {
        mAutoFillResetNeeded = true;
    }

    @Override
    public void autofillClientRunOnUiThread(Runnable action) {
        mActivity.runOnUiThread(action);
    }

    @Override
    public void autofillClientAuthenticate(int authenticationId, IntentSender intent,
            Intent fillInIntent, boolean authenticateInline) {
        try {
            mActivity.startIntentSenderForResult(intent, AUTO_FILL_AUTH_WHO_PREFIX,
                    authenticationId, fillInIntent, 0, 0, null);
        } catch (IntentSender.SendIntentException e) {
            Log.e(TAG, "authenticate() failed for intent:" + intent, e);
        }
    }
}
