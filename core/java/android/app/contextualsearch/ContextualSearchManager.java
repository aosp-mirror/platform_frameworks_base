/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.contextualsearch;

import static android.Manifest.permission.ACCESS_CONTEXTUAL_SEARCH;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.contextualsearch.flags.Flags;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.OutcomeReceiver;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * {@link ContextualSearchManager} is a system service to facilitate contextual search experience on
 * configured Android devices.
 * <p>
 * This class lets
 * <ul>
 *   <li> a caller start contextual search by calling {@link #startContextualSearch} method.
 *   <li> a handler request {@link ContextualSearchState} by calling the
 *   {@link #getContextualSearchState} method.
 * </ul>
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_SERVICE)
public class ContextualSearchManager {

    /**
     * Key to get the entrypoint from the extras of the activity launched by contextual search.
     * Only supposed to be used with ACTON_LAUNCH_CONTEXTUAL_SEARCH.
     */
    public static final String EXTRA_ENTRYPOINT =
            "android.app.contextualsearch.extra.ENTRYPOINT";
    /**
     * Key to get the flag_secure value from the extras of the activity launched by contextual
     * search. The value will be true if flag_secure is found in any of the visible activities.
     * Only supposed to be used with ACTON_LAUNCH_CONTEXTUAL_SEARCH.
     */
    public static final String EXTRA_FLAG_SECURE_FOUND =
            "android.app.contextualsearch.extra.FLAG_SECURE_FOUND";
    /**
     * Key to get the screenshot from the extras of the activity launched by contextual search.
     * Only supposed to be used with ACTON_LAUNCH_CONTEXTUAL_SEARCH.
     */
    public static final String EXTRA_SCREENSHOT =
            "android.app.contextualsearch.extra.SCREENSHOT";
    /**
     * Key to check whether managed profile is visible from the extras of the activity launched by
     * contextual search. The value will be true if any one of the visible apps is managed.
     * Only supposed to be used with ACTON_LAUNCH_CONTEXTUAL_SEARCH.
     */
    public static final String EXTRA_IS_MANAGED_PROFILE_VISIBLE =
            "android.app.contextualsearch.extra.IS_MANAGED_PROFILE_VISIBLE";
    /**
     * Key to get the list of visible packages from the extras of the activity launched by
     * contextual search.
     * Only supposed to be used with ACTON_LAUNCH_CONTEXTUAL_SEARCH.
     */
    public static final String EXTRA_VISIBLE_PACKAGE_NAMES =
            "android.app.contextualsearch.extra.VISIBLE_PACKAGE_NAMES";

    /**
     * Key to get the binder token from the extras of the activity launched by contextual search.
     * This token is needed to invoke {@link #getContextualSearchState} method.
     * Only supposed to be used with ACTON_LAUNCH_CONTEXTUAL_SEARCH.
     */
    public static final String EXTRA_TOKEN = "android.app.contextualsearch.extra.TOKEN";
    /**
     * Intent action for contextual search invocation. The app providing the contextual search
     * experience must add this intent filter action to the activity it wants to be launched.
     * <br>
     * <b>Note</b> This activity must not be exported.
     */
    public static final String ACTION_LAUNCH_CONTEXTUAL_SEARCH =
            "android.app.contextualsearch.action.LAUNCH_CONTEXTUAL_SEARCH";

    /** Entrypoint to be used when a user long presses on the nav handle. */
    public static final int ENTRYPOINT_LONG_PRESS_NAV_HANDLE = 1;
    /** Entrypoint to be used when a user long presses on the home button. */
    public static final int ENTRYPOINT_LONG_PRESS_HOME = 2;
    /** Entrypoint to be used when a user long presses on the overview button. */
    public static final int ENTRYPOINT_LONG_PRESS_OVERVIEW = 3;
    /** Entrypoint to be used when a user presses the action button in overview. */
    public static final int ENTRYPOINT_OVERVIEW_ACTION = 4;
    /** Entrypoint to be used when a user presses the context menu button in overview. */
    public static final int ENTRYPOINT_OVERVIEW_MENU = 5;
    /** Entrypoint to be used by system actions like TalkBack, Accessibility etc. */
    public static final int ENTRYPOINT_SYSTEM_ACTION = 9;
    /** Entrypoint to be used when a user long presses on the meta key. */
    public static final int ENTRYPOINT_LONG_PRESS_META = 10;
    /**
     * The {@link Entrypoint} annotation is used to standardize the entrypoints supported by
     * {@link #startContextualSearch} method.
     *
     * @hide
     */
    @IntDef(prefix = {"ENTRYPOINT_"}, value = {
            ENTRYPOINT_LONG_PRESS_NAV_HANDLE,
            ENTRYPOINT_LONG_PRESS_HOME,
            ENTRYPOINT_LONG_PRESS_OVERVIEW,
            ENTRYPOINT_OVERVIEW_ACTION,
            ENTRYPOINT_OVERVIEW_MENU,
            ENTRYPOINT_SYSTEM_ACTION,
            ENTRYPOINT_LONG_PRESS_META
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Entrypoint {
    }
    private static final String TAG = ContextualSearchManager.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final IContextualSearchManager mService;

    /** @hide */
    public ContextualSearchManager() {
        if (DEBUG) Log.d(TAG, "ContextualSearchManager created");
        IBinder b = ServiceManager.getService(Context.CONTEXTUAL_SEARCH_SERVICE);
        mService = IContextualSearchManager.Stub.asInterface(b);
    }

    /**
     * Used to start contextual search.
     * <p>
     *     When {@link #startContextualSearch} is called, the system server does the following:
     *     <ul>
     *         <li>Resolves the activity using the package name and intent filter. The package name
     *             is fetched from the config specified in ContextualSearchManagerService.
     *             The activity must have ACTION_LAUNCH_CONTEXTUAL_SEARCH specified in its manifest.
     *         <li>Puts the required extras in the launch intent.
     *         <li>Launches the activity.
     *     </ul>
     * </p>
     *
     * @param entrypoint the invocation entrypoint
     */
    @RequiresPermission(ACCESS_CONTEXTUAL_SEARCH)
    public void startContextualSearch(@Entrypoint int entrypoint) {
        if (DEBUG) Log.d(TAG, "startContextualSearch for entrypoint: " + entrypoint);
        try {
            mService.startContextualSearch(entrypoint);
        } catch (RemoteException e) {
            if (DEBUG) Log.d(TAG, "Failed to startContextualSearch", e);
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the {@link ContextualSearchState} to the handler via the provided callback.
     *
     * @param token The caller is expected to get the token from the launch extras of the handling
     *              activity using {@link Bundle#getIBinder} with {@link #EXTRA_TOKEN} key.
     *              <br>
     *              <b>Note</b> This token is for one time use only. Subsequent uses will invoke
     *              callback's {@link OutcomeReceiver#onError}.
     * @param executor The executor which will be used to invoke the callback.
     * @param callback The callback which will be used to return {@link ContextualSearchState}
     *                 if/when it is available via {@link OutcomeReceiver#onResult}. It will also be
     *                 used to return errors via {@link OutcomeReceiver#onError}.
     */
    public void getContextualSearchState(@NonNull IBinder token,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<ContextualSearchState, Throwable> callback) {
        if (DEBUG) Log.d(TAG, "getContextualSearchState for token:" + token);
        try {
            final CallbackWrapper wrapper = new CallbackWrapper(executor, callback);
            mService.getContextualSearchState(token, wrapper);
        } catch (RemoteException e) {
            if (DEBUG) Log.d(TAG, "Failed to getContextualSearchState", e);
            e.rethrowFromSystemServer();
        }
    }

    private static class CallbackWrapper extends IContextualSearchCallback.Stub {
        private final OutcomeReceiver<ContextualSearchState, Throwable> mCallback;
        private final Executor mExecutor;

        CallbackWrapper(@NonNull Executor callbackExecutor,
                        @NonNull OutcomeReceiver<ContextualSearchState, Throwable> callback) {
            mCallback = callback;
            mExecutor = callbackExecutor;
        }

        @Override
        public void onResult(ContextualSearchState state) {
            Binder.withCleanCallingIdentity(() -> {
                if (DEBUG) Log.d(TAG, "onResult state:" + state);
                mExecutor.execute(() -> mCallback.onResult(state));
            });
        }

        @Override
        public void onError(ParcelableException error) {
            Binder.withCleanCallingIdentity(() -> {
                if (DEBUG) Log.w(TAG, "onError", error);
                mExecutor.execute(() -> mCallback.onError(error));
            });
        }
    }
}
