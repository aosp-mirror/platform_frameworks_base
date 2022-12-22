/*
 * Copyright 2022 The Android Open Source Project
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

package android.credentials;

import static java.util.Objects.requireNonNull;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.IntentSender;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.provider.DeviceConfig;
import android.util.Log;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Manages user authentication flows.
 *
 * <p>Note that an application should call the Jetpack CredentialManager apis instead of directly
 * calling these framework apis.
 *
 * <p>The CredentialManager apis launch framework UI flows for a user to register a new credential
 * or to consent to a saved credential from supported credential providers, which can then be used
 * to authenticate to the app.
 */
@SystemService(Context.CREDENTIAL_SERVICE)
public final class CredentialManager {
    private static final String TAG = "CredentialManager";

    private final Context mContext;
    private final ICredentialManager mService;

    /**
     * Flag to enable and disable Credential Manager.
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_ENABLE_CREDENTIAL_MANAGER =
            "enable_credential_manager";

    /**
     * @hide instantiated by ContextImpl.
     */
    public CredentialManager(Context context, ICredentialManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Launches the necessary flows to retrieve an app credential from the user.
     *
     * <p>The execution can potentially launch UI flows to collect user consent to using a
     * credential, display a picker when multiple credentials exist, etc.
     *
     * @param request the request specifying type(s) of credentials to get from the user
     * @param activity the activity used to launch any UI needed
     * @param cancellationSignal an optional signal that allows for cancelling this call
     * @param executor the callback will take place on this {@link Executor}
     * @param callback the callback invoked when the request succeeds or fails
     */
    public void executeGetCredential(
            @NonNull GetCredentialRequest request,
            @NonNull Activity activity,
            @Nullable CancellationSignal cancellationSignal,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<GetCredentialResponse, GetCredentialException> callback) {
        requireNonNull(request, "request must not be null");
        requireNonNull(activity, "activity must not be null");
        requireNonNull(executor, "executor must not be null");
        requireNonNull(callback, "callback must not be null");

        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            Log.w(TAG, "executeGetCredential already canceled");
            return;
        }

        ICancellationSignal cancelRemote = null;
        try {
            cancelRemote =
                    mService.executeGetCredential(
                            request,
                            new GetCredentialTransport(activity, executor, callback),
                            mContext.getOpPackageName());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        if (cancellationSignal != null && cancelRemote != null) {
            cancellationSignal.setRemote(cancelRemote);
        }
    }

    /**
     * Launches the necessary flows to register an app credential for the user.
     *
     * <p>The execution can potentially launch UI flows to collect user consent to creating or
     * storing the new credential, etc.
     *
     * @param request the request specifying type(s) of credentials to get from the user
     * @param activity the activity used to launch any UI needed
     * @param cancellationSignal an optional signal that allows for cancelling this call
     * @param executor the callback will take place on this {@link Executor}
     * @param callback the callback invoked when the request succeeds or fails
     */
    public void executeCreateCredential(
            @NonNull CreateCredentialRequest request,
            @NonNull Activity activity,
            @Nullable CancellationSignal cancellationSignal,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull
                    OutcomeReceiver<CreateCredentialResponse, CreateCredentialException> callback) {
        requireNonNull(request, "request must not be null");
        requireNonNull(activity, "activity must not be null");
        requireNonNull(executor, "executor must not be null");
        requireNonNull(callback, "callback must not be null");

        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            Log.w(TAG, "executeCreateCredential already canceled");
            return;
        }

        ICancellationSignal cancelRemote = null;
        try {
            cancelRemote =
                    mService.executeCreateCredential(
                            request,
                            new CreateCredentialTransport(activity, executor, callback),
                            mContext.getOpPackageName());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        if (cancellationSignal != null && cancelRemote != null) {
            cancellationSignal.setRemote(cancelRemote);
        }
    }

    /**
     * Clears the current user credential state from all credential providers.
     *
     * <p>You should invoked this api after your user signs out of your app to notify all credential
     * providers that any stored credential session for the given app should be cleared.
     *
     * <p>A credential provider may have stored an active credential session and use it to limit
     * sign-in options for future get-credential calls. For example, it may prioritize the active
     * credential over any other available credential. When your user explicitly signs out of your
     * app and in order to get the holistic sign-in options the next time, you should call this API
     * to let the provider clear any stored credential session.
     *
     * @param request the request data
     * @param cancellationSignal an optional signal that allows for cancelling this call
     * @param executor the callback will take place on this {@link Executor}
     * @param callback the callback invoked when the request succeeds or fails
     */
    public void clearCredentialState(
            @NonNull ClearCredentialStateRequest request,
            @Nullable CancellationSignal cancellationSignal,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, ClearCredentialStateException> callback) {
        requireNonNull(executor, "executor must not be null");
        requireNonNull(callback, "callback must not be null");

        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            Log.w(TAG, "executeCreateCredential already canceled");
            return;
        }

        ICancellationSignal cancelRemote = null;
        try {
            cancelRemote =
                    mService.clearCredentialState(
                            request,
                            new ClearCredentialStateTransport(executor, callback),
                            mContext.getOpPackageName());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        if (cancellationSignal != null && cancelRemote != null) {
            cancellationSignal.setRemote(cancelRemote);
        }
    }

    /**
     * Gets a list of all user configurable credential providers registered on the system. This API
     * is intended for browsers and settings apps.
     *
     * @param cancellationSignal an optional signal that allows for cancelling this call
     * @param executor the callback will take place on this {@link Executor}
     * @param callback the callback invoked when the request succeeds or fails
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.LIST_ENABLED_CREDENTIAL_PROVIDERS)
    public void listEnabledProviders(
            @Nullable CancellationSignal cancellationSignal,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull
                    OutcomeReceiver<ListEnabledProvidersResponse, ListEnabledProvidersException>
                            callback) {
        requireNonNull(executor, "executor must not be null");
        requireNonNull(callback, "callback must not be null");

        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            Log.w(TAG, "listEnabledProviders already canceled");
            return;
        }

        ICancellationSignal cancelRemote = null;
        try {
            cancelRemote =
                    mService.listEnabledProviders(
                            new ListEnabledProvidersTransport(executor, callback));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        if (cancellationSignal != null && cancelRemote != null) {
            cancellationSignal.setRemote(cancelRemote);
        }
    }

    /**
     * Sets a list of all user configurable credential providers registered on the system. This API
     * is intended for settings apps.
     *
     * @param providers the list of enabled providers
     * @param userId the user ID to configure credential manager for
     * @param executor the callback will take place on this {@link Executor}
     * @param callback the callback invoked when the request succeeds or fails
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public void setEnabledProviders(
            @NonNull List<String> providers,
            int userId,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, SetEnabledProvidersException> callback) {
        requireNonNull(executor, "executor must not be null");
        requireNonNull(callback, "callback must not be null");
        requireNonNull(providers, "providers must not be null");

        try {
            mService.setEnabledProviders(
                    providers, userId, new SetEnabledProvidersTransport(executor, callback));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the service is enabled.
     *
     * @hide
     */
    public static boolean isServiceEnabled(Context context) {
        if (context == null) {
	    return false;
        }
        CredentialManager credentialManager =
                (CredentialManager) context.getSystemService(Context.CREDENTIAL_SERVICE);
        if (credentialManager != null) {
            return credentialManager.isServiceEnabled();
        }
        return false;
    }

    private boolean isServiceEnabled() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_CREDENTIAL, DEVICE_CONFIG_ENABLE_CREDENTIAL_MANAGER,
                true);
    }

    private static class GetCredentialTransport extends IGetCredentialCallback.Stub {
        // TODO: listen for cancellation to release callback.

        private final Activity mActivity;
        private final Executor mExecutor;
        private final OutcomeReceiver<GetCredentialResponse, GetCredentialException> mCallback;

        private GetCredentialTransport(
                Activity activity,
                Executor executor,
                OutcomeReceiver<GetCredentialResponse, GetCredentialException> callback) {
            mActivity = activity;
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onPendingIntent(PendingIntent pendingIntent) {
            try {
                mActivity.startIntentSender(pendingIntent.getIntentSender(), null, 0, 0, 0);
            } catch (IntentSender.SendIntentException e) {
                Log.e(
                        TAG,
                        "startIntentSender() failed for intent:" + pendingIntent.getIntentSender(),
                        e);
                // TODO: propagate the error.
            }
        }

        @Override
        public void onResponse(GetCredentialResponse response) {
            mExecutor.execute(() -> mCallback.onResult(response));
        }

        @Override
        public void onError(String errorType, String message) {
            mExecutor.execute(
                    () -> mCallback.onError(new GetCredentialException(errorType, message)));
        }
    }

    private static class CreateCredentialTransport extends ICreateCredentialCallback.Stub {
        // TODO: listen for cancellation to release callback.

        private final Activity mActivity;
        private final Executor mExecutor;
        private final OutcomeReceiver<CreateCredentialResponse, CreateCredentialException>
                mCallback;

        private CreateCredentialTransport(
                Activity activity,
                Executor executor,
                OutcomeReceiver<CreateCredentialResponse, CreateCredentialException> callback) {
            mActivity = activity;
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onPendingIntent(PendingIntent pendingIntent) {
            try {
                mActivity.startIntentSender(pendingIntent.getIntentSender(), null, 0, 0, 0);
            } catch (IntentSender.SendIntentException e) {
                Log.e(
                        TAG,
                        "startIntentSender() failed for intent:" + pendingIntent.getIntentSender(),
                        e);
                // TODO: propagate the error.
            }
        }

        @Override
        public void onResponse(CreateCredentialResponse response) {
            mExecutor.execute(() -> mCallback.onResult(response));
        }

        @Override
        public void onError(String errorType, String message) {
            mExecutor.execute(
                    () -> mCallback.onError(new CreateCredentialException(errorType, message)));
        }
    }

    private static class ClearCredentialStateTransport extends IClearCredentialStateCallback.Stub {
        // TODO: listen for cancellation to release callback.

        private final Executor mExecutor;
        private final OutcomeReceiver<Void, ClearCredentialStateException> mCallback;

        private ClearCredentialStateTransport(
                Executor executor, OutcomeReceiver<Void, ClearCredentialStateException> callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onSuccess() {
            mCallback.onResult(null);
        }

        @Override
        public void onError(String errorType, String message) {
            mExecutor.execute(
                    () -> mCallback.onError(new ClearCredentialStateException(errorType, message)));
        }
    }

    private static class ListEnabledProvidersTransport extends IListEnabledProvidersCallback.Stub {
        // TODO: listen for cancellation to release callback.

        private final Executor mExecutor;
        private final OutcomeReceiver<ListEnabledProvidersResponse, ListEnabledProvidersException>
                mCallback;

        private ListEnabledProvidersTransport(
                Executor executor,
                OutcomeReceiver<ListEnabledProvidersResponse, ListEnabledProvidersException>
                        callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onResponse(ListEnabledProvidersResponse response) {
            mExecutor.execute(() -> mCallback.onResult(response));
        }

        @Override
        public void onError(String errorType, String message) {
            mExecutor.execute(
                    () -> mCallback.onError(new ListEnabledProvidersException(errorType, message)));
        }
    }

    private static class SetEnabledProvidersTransport extends ISetEnabledProvidersCallback.Stub {
        // TODO: listen for cancellation to release callback.

        private final Executor mExecutor;
        private final OutcomeReceiver<Void, SetEnabledProvidersException> mCallback;

        private SetEnabledProvidersTransport(
                Executor executor, OutcomeReceiver<Void, SetEnabledProvidersException> callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        public void onResponse(Void result) {
            mExecutor.execute(() -> mCallback.onResult(result));
        }

        @Override
        public void onResponse() {
            mExecutor.execute(() -> mCallback.onResult(null));
        }

        @Override
        public void onError(String errorType, String message) {
            mExecutor.execute(
                    () -> mCallback.onError(new SetEnabledProvidersException(errorType, message)));
        }
    }
}
