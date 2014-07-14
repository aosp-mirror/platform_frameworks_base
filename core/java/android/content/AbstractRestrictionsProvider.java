/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.content;

import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.os.Bundle;
import android.os.IBinder;

/**
 * Abstract implementation of a Restrictions Provider Service. To implement a Restrictions Provider,
 * extend from this class and implement the abstract methods. Export this service in the
 * manifest. A profile owner device admin can then register this component as a Restrictions
 * Provider using {@link DevicePolicyManager#setRestrictionsProvider(ComponentName, ComponentName)}.
 * <p>
 * The function of a Restrictions Provider is to transport permission requests from apps on this
 * device to an administrator (most likely on a remote device or computer) and deliver back
 * responses. The response should be sent back to the app via
 * {@link RestrictionsManager#notifyPermissionResponse(String, Bundle)}.
 * <p>
 * Apps can also query previously received responses using
 * {@link #getPermissionResponse(String, String)}. The period for which previously received
 * responses are available is left to the implementation of the Restrictions Provider.
 */
public abstract class AbstractRestrictionsProvider extends Service {

    private static final String TAG = "AbstractRestrictionsProvider";

    @Override
    public final IBinder onBind(Intent intent) {
        return new RestrictionsProviderWrapper().asBinder();
    }

    /**
     * Checks to see if there is a response for a prior request and returns the response bundle if
     * it exists. If there is no response yet or if the request is not known, the returned bundle
     * should contain the response code in {@link RestrictionsManager#RESPONSE_KEY_RESULT}.
     *
     * @param packageName the application that is requesting a permission response.
     * @param requestId the id of the request for which the response is needed.
     * @return a bundle containing at a minimum the result of the request. It could contain other
     * optional information such as error codes and cookies.
     *
     * @see RestrictionsManager#RESPONSE_KEY_RESULT
     */
    public abstract Bundle getPermissionResponse(String packageName, String requestId);

    /**
     * An asynchronous permission request made by an application for an operation that requires
     * authorization by a local or remote administrator other than the user. The Restrictions
     * Provider must transfer the request to the administrator and deliver back a response, when
     * available. The calling application is aware that the response could take an indefinite
     * amount of time.
     *
     * @param packageName the application requesting permission.
     * @param requestType the type of request, which determines the content and presentation of
     * the request data.
     * @param request the request data bundle containing at a minimum a request id.
     *
     * @see RestrictionsManager#REQUEST_TYPE_QUESTION
     * @see RestrictionsManager#REQUEST_TYPE_LOCAL_APPROVAL
     * @see RestrictionsManager#REQUEST_KEY_ID
     */
    public abstract void requestPermission(String packageName, String requestType, Bundle request);

    private class RestrictionsProviderWrapper extends IRestrictionsProvider.Stub {

        @Override
        public Bundle getPermissionResponse(String packageName, String requestId) {
            return AbstractRestrictionsProvider.this
                    .getPermissionResponse(packageName, requestId);
        }

        @Override
        public void requestPermission(String packageName, String templateId, Bundle request) {
            AbstractRestrictionsProvider.this.requestPermission(packageName, templateId, request);
        }
    }
}
