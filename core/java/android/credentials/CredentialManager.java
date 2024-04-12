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
import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.provider.DeviceConfig;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
@RequiresFeature(PackageManager.FEATURE_CREDENTIALS)
public final class CredentialManager {
    /** @hide **/
    @Hide
    public static final String TAG = "CredentialManager";
    private static final Bundle OPTIONS_SENDER_BAL_OPTIN = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();

    /** @hide */
    @IntDef(
            flag = true,
            prefix = {"PROVIDER_FILTER_"},
            value = {
                PROVIDER_FILTER_ALL_PROVIDERS,
                PROVIDER_FILTER_SYSTEM_PROVIDERS_ONLY,
                PROVIDER_FILTER_USER_PROVIDERS_ONLY,
                // By default the returned list of providers will not include any providers that
                // have been hidden by device policy. However, there are some cases where we want
                // them to show up (e.g. settings) so this will return the list of providers with
                // the hidden ones included.
                PROVIDER_FILTER_USER_PROVIDERS_INCLUDING_HIDDEN,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProviderFilter {}

    /**
     * Returns both system and user credential providers.
     *
     * @hide
     */
    @TestApi public static final int PROVIDER_FILTER_ALL_PROVIDERS = 0;

    /**
     * Returns system credential providers only.
     *
     * @hide
     */
    @TestApi public static final int PROVIDER_FILTER_SYSTEM_PROVIDERS_ONLY = 1;

    /**
     * Returns user credential providers only.
     *
     * @hide
     */
    @TestApi public static final int PROVIDER_FILTER_USER_PROVIDERS_ONLY = 2;

    /**
     * Returns user credential providers only. This will include providers that
     * have been disabled by the device policy.
     *
     * @hide
     */
    public static final int PROVIDER_FILTER_USER_PROVIDERS_INCLUDING_HIDDEN = 3;

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
     * Flag to enable and disable Credential Description api.
     *
     * @hide
     */
    private static final String DEVICE_CONFIG_ENABLE_CREDENTIAL_DESC_API =
            "enable_credential_description_api";

    /**
     * @hide
     */
    @Hide
    public static final String EXTRA_AUTOFILL_RESULT_RECEIVER =
            "android.credentials.AUTOFILL_RESULT_RECEIVER";

    /**
     * @hide instantiated by ContextImpl.
     */
    public CredentialManager(Context context, ICredentialManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Returns a list of candidate credentials returned from credential manager providers
     *
     * @param request the request specifying type(s) of credentials to get from the
     *                credential providers
     * @param cancellationSignal an optional signal that allows for cancelling this call
     * @param executor the callback will take place on this {@link Executor}
     * @param callback the callback invoked when the request succeeds or fails
     *
     * @hide
     */
    @Hide
    public void getCandidateCredentials(
            @NonNull GetCredentialRequest request,
            @NonNull String callingPackage,
            @Nullable CancellationSignal cancellationSignal,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<GetCandidateCredentialsResponse,
                    GetCandidateCredentialsException> callback,
            @NonNull IBinder clientCallback
    ) {
        requireNonNull(request, "request must not be null");
        requireNonNull(callingPackage, "callingPackage must not be null");
        requireNonNull(executor, "executor must not be null");
        requireNonNull(callback, "callback must not be null");

        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            Log.w(TAG, "getCandidateCredentials already canceled");
            return;
        }

        ICancellationSignal cancelRemote = null;
        try {
            cancelRemote =
                    mService.getCandidateCredentials(
                            request,
                            new GetCandidateCredentialsTransport(executor, callback),
                            clientCallback,
                            callingPackage);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        if (cancellationSignal != null && cancelRemote != null) {
            cancellationSignal.setRemote(cancelRemote);
        }
    }

    /**
     * Launches the necessary flows to retrieve an app credential from the user.
     *
     * <p>The execution can potentially launch UI flows to collect user consent to using a
     * credential, display a picker when multiple credentials exist, etc.
     * Callers (e.g. browsers) may optionally set origin in {@link GetCredentialRequest} for an
     * app different from their own, to be able to get credentials on behalf of that app. They would
     * need additional permission {@code CREDENTIAL_MANAGER_SET_ORIGIN}
     * to use this functionality
     *
     * @param context the context used to launch any UI needed; use an activity context to make sure
     *                the UI will be launched within the same task stack
     * @param request the request specifying type(s) of credentials to get from the user
     * @param cancellationSignal an optional signal that allows for cancelling this call
     * @param executor the callback will take place on this {@link Executor}
     * @param callback the callback invoked when the request succeeds or fails
     */
    public void getCredential(
            @NonNull Context context,
            @NonNull GetCredentialRequest request,
            @Nullable CancellationSignal cancellationSignal,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<GetCredentialResponse, GetCredentialException> callback) {
        requireNonNull(request, "request must not be null");
        requireNonNull(context, "context must not be null");
        requireNonNull(executor, "executor must not be null");
        requireNonNull(callback, "callback must not be null");

        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            Log.w(TAG, "getCredential already canceled");
            return;
        }

        ICancellationSignal cancelRemote = null;
        try {
            cancelRemote =
                    mService.executeGetCredential(
                            request,
                            new GetCredentialTransport(context, executor, callback),
                            mContext.getOpPackageName());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        if (cancellationSignal != null && cancelRemote != null) {
            cancellationSignal.setRemote(cancelRemote);
        }
    }

    /**
     * Launches the remaining flows to retrieve an app credential from the user, after the
     * completed prefetch work corresponding to the given {@code pendingGetCredentialHandle}.
     *
     * <p>The execution can potentially launch UI flows to collect user consent to using a
     * credential, display a picker when multiple credentials exist, etc.
     *
     * <p>Use this API to complete the full credential retrieval operation after you initiated a
     * request through the {@link #prepareGetCredential(
     * GetCredentialRequest, CancellationSignal, Executor, OutcomeReceiver)} API.
     *
     * @param context the context used to launch any UI needed; use an activity context to make sure
     *                the UI will be launched within the same task stack
     * @param pendingGetCredentialHandle the handle representing the pending operation to resume
     * @param cancellationSignal an optional signal that allows for cancelling this call
     * @param executor the callback will take place on this {@link Executor}
     * @param callback the callback invoked when the request succeeds or fails
     */
    public void getCredential(
            @NonNull Context context,
            @NonNull PrepareGetCredentialResponse.PendingGetCredentialHandle
            pendingGetCredentialHandle,
            @Nullable CancellationSignal cancellationSignal,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<GetCredentialResponse, GetCredentialException> callback) {
        requireNonNull(pendingGetCredentialHandle, "pendingGetCredentialHandle must not be null");
        requireNonNull(context, "context must not be null");
        requireNonNull(executor, "executor must not be null");
        requireNonNull(callback, "callback must not be null");

        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            Log.w(TAG, "getCredential already canceled");
            return;
        }

        pendingGetCredentialHandle.show(context, cancellationSignal, executor, callback);
    }

    /**
     * Prepare for a get-credential operation. Returns a {@link PrepareGetCredentialResponse} that
     * can launch the credential retrieval UI flow to request a user credential for your app.
     *
     * <p>This API doesn't invoke any UI. It only performs the preparation work so that you can
     * later launch the remaining get-credential operation (involves UIs) through the {@link
     * #getCredential(Context, PrepareGetCredentialResponse.PendingGetCredentialHandle,
     * CancellationSignal, Executor, OutcomeReceiver)} API which incurs less latency compared to
     * the {@link #getCredential(Context, GetCredentialRequest, CancellationSignal, Executor,
     * OutcomeReceiver)} API that executes the whole operation in one call.
     *
     * @param request            the request specifying type(s) of credentials to get from the user
     * @param cancellationSignal an optional signal that allows for cancelling this call
     * @param executor           the callback will take place on this {@link Executor}
     * @param callback           the callback invoked when the request succeeds or fails
     */
    public void prepareGetCredential(
            @NonNull GetCredentialRequest request,
            @Nullable CancellationSignal cancellationSignal,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<
                    PrepareGetCredentialResponse, GetCredentialException> callback) {
        requireNonNull(request, "request must not be null");
        requireNonNull(executor, "executor must not be null");
        requireNonNull(callback, "callback must not be null");

        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            Log.w(TAG, "prepareGetCredential already canceled");
            return;
        }

        ICancellationSignal cancelRemote = null;
        GetCredentialTransportPendingUseCase getCredentialTransport =
                new GetCredentialTransportPendingUseCase();
        try {
            cancelRemote =
                    mService.executePrepareGetCredential(
                            request,
                            new PrepareGetCredentialTransport(
                                    executor, callback, getCredentialTransport),
                            getCredentialTransport,
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
     * Callers (e.g. browsers) may optionally set origin in {@link CreateCredentialRequest} for an
     * app different from their own, to be able to get credentials on behalf of that app. They would
     * need additional permission {@code CREDENTIAL_MANAGER_SET_ORIGIN}
     * to use this functionality
     *
     * @param context the context used to launch any UI needed; use an activity context to make sure
     *                the UI will be launched within the same task stack
     * @param request the request specifying type(s) of credentials to get from the user
     * @param cancellationSignal an optional signal that allows for cancelling this call
     * @param executor the callback will take place on this {@link Executor}
     * @param callback the callback invoked when the request succeeds or fails
     */
    public void createCredential(
            @NonNull Context context,
            @NonNull CreateCredentialRequest request,
            @Nullable CancellationSignal cancellationSignal,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull
                    OutcomeReceiver<CreateCredentialResponse, CreateCredentialException> callback) {
        requireNonNull(request, "request must not be null");
        requireNonNull(context, "context must not be null");
        requireNonNull(executor, "executor must not be null");
        requireNonNull(callback, "callback must not be null");

        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            Log.w(TAG, "createCredential already canceled");
            return;
        }

        ICancellationSignal cancelRemote = null;
        try {
            cancelRemote =
                    mService.executeCreateCredential(
                            request,
                            new CreateCredentialTransport(context, executor, callback),
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
        requireNonNull(request, "request must not be null");
        requireNonNull(executor, "executor must not be null");
        requireNonNull(callback, "callback must not be null");

        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            Log.w(TAG, "clearCredentialState already canceled");
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
     * Sets a list of all user configurable credential providers registered on the system. This API
     * is intended for settings apps.
     *
     * @param primaryProviders the primary providers that user selected for saving credentials. In
     *                         the most case, there should be only one primary provider, However,
     *                         if there are more than one CredentialProviderService in the same APK,
     *                         they should be passed in altogether.
     * @param providers the list of enabled providers.
     * @param userId the user ID to configure credential manager for
     * @param executor the callback will take place on this {@link Executor}
     * @param callback the callback invoked when the request succeeds or fails
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    public void setEnabledProviders(
            @NonNull List<String> primaryProviders,
            @NonNull List<String> providers,
            int userId,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, SetEnabledProvidersException> callback) {
        requireNonNull(executor, "executor must not be null");
        requireNonNull(callback, "callback must not be null");
        requireNonNull(providers, "providers must not be null");
        requireNonNull(primaryProviders, "primaryProviders must not be null");

        try {
            mService.setEnabledProviders(
                    primaryProviders,
                    providers, userId, new SetEnabledProvidersTransport(executor, callback));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@code true} if the calling application provides a CredentialProviderService that is
     * enabled for the current user, or {@code false} otherwise. CredentialProviderServices are
     * enabled on a per-service basis so the individual component name of the service should be
     * passed in here. <strong>Usage of this API is discouraged as it is not fully functional, and
     * may throw a NullPointerException on certain devices and/or API versions.</strong>
     *
     * @throws IllegalArgumentException if the componentName package does not match the calling
     * package name this call will throw an exception
     *
     * @throws NullPointerException Usage of this API is discouraged as it is not fully
     * functional, and may throw a NullPointerException on certain devices and/or API versions
     *
     * @param componentName the component name to check is enabled
     */
    public boolean isEnabledCredentialProviderService(@NonNull ComponentName componentName) {
        requireNonNull(componentName, "componentName must not be null");

        try {
            return mService.isEnabledCredentialProviderService(
                    componentName, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of CredentialProviderInfo for all discovered credential providers on this
     * device but will include test system providers as well.
     *
     * @hide
     */
    @NonNull
    @TestApi
    @RequiresPermission(
      anyOf = {
        android.Manifest.permission.QUERY_ALL_PACKAGES,
        android.Manifest.permission.LIST_ENABLED_CREDENTIAL_PROVIDERS
      })
    public List<CredentialProviderInfo> getCredentialProviderServicesForTesting(
             @ProviderFilter int providerFilter) {
        try {
            return mService.getCredentialProviderServicesForTesting(providerFilter);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of CredentialProviderInfo for all discovered credential providers on this
     * device.
     *
     * @hide
     */
    @NonNull
    @RequiresPermission(
      anyOf = {
        android.Manifest.permission.QUERY_ALL_PACKAGES,
        android.Manifest.permission.LIST_ENABLED_CREDENTIAL_PROVIDERS
      })
    public List<CredentialProviderInfo> getCredentialProviderServices(
            int userId, @ProviderFilter int providerFilter) {
        try {
            return mService.getCredentialProviderServices(userId, providerFilter);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the service is enabled.
     *
     * @hide
     */
    @TestApi
    public static boolean isServiceEnabled(@NonNull Context context) {
        requireNonNull(context, "context must not be null");
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

    /**
     * Returns whether the service is enabled.
     *
     * @hide
     */
    private boolean isServiceEnabled() {
        try {
            return mService.isServiceEnabled();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Returns whether the credential description api is enabled.
     *
     * @hide
     */
    public static boolean isCredentialDescriptionApiEnabled(Context context) {
        if (context == null) {
            return false;
        }
        CredentialManager credentialManager =
                (CredentialManager) context.getSystemService(Context.CREDENTIAL_SERVICE);
        if (credentialManager != null) {
            return credentialManager.isCredentialDescriptionApiEnabled();
        }
        return false;
    }

    private boolean isCredentialDescriptionApiEnabled() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_CREDENTIAL, DEVICE_CONFIG_ENABLE_CREDENTIAL_DESC_API, false);
    }

    /**
     * Registers a {@link CredentialDescription} for an actively provisioned {@link Credential} a
     * CredentialProvider has. This registry will then be used to determine where to fetch the
     * requested {@link Credential} from. Not all credential types will be supported. The
     * distinction will be made by the JetPack layer. For the types that are supported, JetPack will
     * add a new key-value pair into {@link GetCredentialRequest}. These will not be persistent on
     * the device. The Credential Providers will need to call this API again upon device reboot.
     *
     * @param request the request data
     * @throws {@link UnsupportedOperationException} if the feature has not been enabled.
     * @throws {@link com.android.server.credentials.NonCredentialProviderCallerException} if the
     *     calling package name is not also listed as a Credential Provider.
     * @throws {@link IllegalArgumentException} if the calling Credential Provider can not handle
     *     one or more of the Credential Types that are sent for registration.
     */
    public void registerCredentialDescription(
            @NonNull RegisterCredentialDescriptionRequest request) {
        requireNonNull(request, "request must not be null");

        try {
            mService.registerCredentialDescription(request, mContext.getOpPackageName());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters a {@link CredentialDescription} for an actively provisioned {@link Credential}
     * that has been registered previously.
     *
     * @param request the request data
     * @throws {@link UnsupportedOperationException} if the feature has not been enabled.
     */
    public void unregisterCredentialDescription(
            @NonNull UnregisterCredentialDescriptionRequest request) {
        requireNonNull(request, "request must not be null");

        try {
            mService.unregisterCredentialDescription(request, mContext.getOpPackageName());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    private static class PrepareGetCredentialTransport extends IPrepareGetCredentialCallback.Stub {
        // TODO: listen for cancellation to release callback.

        private final Executor mExecutor;
        private final OutcomeReceiver<
                PrepareGetCredentialResponse, GetCredentialException> mCallback;
        private final GetCredentialTransportPendingUseCase mGetCredentialTransport;

        private PrepareGetCredentialTransport(
                Executor executor,
                OutcomeReceiver<PrepareGetCredentialResponse, GetCredentialException> callback,
                GetCredentialTransportPendingUseCase getCredentialTransport) {
            mExecutor = executor;
            mCallback = callback;
            mGetCredentialTransport = getCredentialTransport;
        }

        @Override
        public void onResponse(PrepareGetCredentialResponseInternal response) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onResult(
                        new PrepareGetCredentialResponse(response, mGetCredentialTransport)));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onError(String errorType, String message) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(
                        () -> mCallback.onError(new GetCredentialException(errorType, message)));
            }  finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    /** @hide */
    protected static class GetCredentialTransportPendingUseCase
            extends IGetCredentialCallback.Stub {
        @Nullable private PrepareGetCredentialResponse.GetPendingCredentialInternalCallback
                mCallback = null;

        private GetCredentialTransportPendingUseCase() {}

        public void setCallback(
                PrepareGetCredentialResponse.GetPendingCredentialInternalCallback callback) {
            if (mCallback == null) {
                mCallback = callback;
            } else {
                throw new IllegalStateException("callback has already been set once");
            }
        }

        @Override
        public void onPendingIntent(PendingIntent pendingIntent) {
            if (mCallback != null) {
                mCallback.onPendingIntent(pendingIntent);
            } else {
                Log.d(TAG, "Unexpected onPendingIntent call before the show invocation");
            }
        }

        @Override
        public void onResponse(GetCredentialResponse response) {
            if (mCallback != null) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    mCallback.onResponse(response);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            } else {
                Log.d(TAG, "Unexpected onResponse call before the show invocation");
            }
        }

        @Override
        public void onError(String errorType, String message) {
            if (mCallback != null) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    mCallback.onError(errorType, message);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            } else {
                Log.d(TAG, "Unexpected onError call before the show invocation");
            }
        }
    }

    private static class GetCandidateCredentialsTransport
            extends IGetCandidateCredentialsCallback.Stub {

        private final Executor mExecutor;
        private final OutcomeReceiver<GetCandidateCredentialsResponse,
                GetCandidateCredentialsException> mCallback;

        private GetCandidateCredentialsTransport(
                Executor executor,
                OutcomeReceiver<GetCandidateCredentialsResponse,
                        GetCandidateCredentialsException> callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onResponse(GetCandidateCredentialsResponse response) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onResult(response));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onError(String errorType, String message) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(
                        () -> mCallback.onError(new GetCandidateCredentialsException(
                                errorType, message)));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private static class GetCredentialTransport extends IGetCredentialCallback.Stub {
        // TODO: listen for cancellation to release callback.

        private final Context mContext;
        private final Executor mExecutor;
        private final OutcomeReceiver<GetCredentialResponse, GetCredentialException> mCallback;

        private GetCredentialTransport(
                Context context,
                Executor executor,
                OutcomeReceiver<GetCredentialResponse, GetCredentialException> callback) {
            mContext = context;
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onPendingIntent(PendingIntent pendingIntent) {
            try {
                mContext.startIntentSender(pendingIntent.getIntentSender(), null, 0, 0, 0,
                        OPTIONS_SENDER_BAL_OPTIN);
            } catch (IntentSender.SendIntentException e) {
                Log.e(
                        TAG,
                        "startIntentSender() failed for intent:" + pendingIntent.getIntentSender(),
                        e);
                final long identity = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mCallback.onError(
                            new GetCredentialException(GetCredentialException.TYPE_UNKNOWN)));
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @Override
        public void onResponse(GetCredentialResponse response) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onResult(response));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onError(String errorType, String message) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(
                        () -> mCallback.onError(new GetCredentialException(errorType, message)));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private static class CreateCredentialTransport extends ICreateCredentialCallback.Stub {
        // TODO: listen for cancellation to release callback.

        private final Context mContext;
        private final Executor mExecutor;
        private final OutcomeReceiver<CreateCredentialResponse, CreateCredentialException>
                mCallback;

        private CreateCredentialTransport(
                Context context,
                Executor executor,
                OutcomeReceiver<CreateCredentialResponse, CreateCredentialException> callback) {
            mContext = context;
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onPendingIntent(PendingIntent pendingIntent) {
            try {
                mContext.startIntentSender(pendingIntent.getIntentSender(), null, 0, 0, 0,
                        OPTIONS_SENDER_BAL_OPTIN);
            } catch (IntentSender.SendIntentException e) {
                Log.e(
                        TAG,
                        "startIntentSender() failed for intent:" + pendingIntent.getIntentSender(),
                        e);
                final long identity = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mCallback.onError(
                            new CreateCredentialException(CreateCredentialException.TYPE_UNKNOWN)));
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @Override
        public void onResponse(CreateCredentialResponse response) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onResult(response));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onError(String errorType, String message) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(
                        () -> mCallback.onError(new CreateCredentialException(errorType, message)));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
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
            final long identity = Binder.clearCallingIdentity();
            try {
                mCallback.onResult(null);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onError(String errorType, String message) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(
                        () -> mCallback.onError(
                                new ClearCredentialStateException(errorType, message)));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
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
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onResult(result));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onResponse() {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onResult(null));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onError(String errorType, String message) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(
                        () -> mCallback.onError(
                                new SetEnabledProvidersException(errorType, message)));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }
}
