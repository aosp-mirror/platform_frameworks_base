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

package android.service.restrictions;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.RestrictionsManager;
import android.os.IBinder;
import android.os.PersistableBundle;

/**
 * Abstract implementation of a Restrictions Provider BroadcastReceiver. To implement a
 * Restrictions Provider, extend from this class and implement the abstract methods.
 * Export this receiver in the manifest. A profile owner device admin can then register this
 * component as a Restrictions Provider using
 * {@link DevicePolicyManager#setRestrictionsProvider(ComponentName, ComponentName)}.
 * <p>
 * The function of a Restrictions Provider is to transport permission requests from apps on this
 * device to an administrator (most likely on a remote device or computer) and deliver back
 * responses. The response should be sent back to the app via
 * {@link RestrictionsManager#notifyPermissionResponse(String, PersistableBundle)}.
 *
 * @see RestrictionsManager
 */
public abstract class RestrictionsReceiver extends BroadcastReceiver {

    private static final String TAG = "RestrictionsReceiver";

    /**
     * An asynchronous permission request made by an application for an operation that requires
     * authorization by a local or remote administrator other than the user. The Restrictions
     * Provider should transfer the request to the administrator and deliver back a response, when
     * available. The calling application is aware that the response could take an indefinite
     * amount of time.
     * <p>
     * If the request bundle contains the key {@link RestrictionsManager#REQUEST_KEY_NEW_REQUEST},
     * then a new request must be sent. Otherwise the provider can look up any previous response
     * to the same requestId and return the cached response.
     *
     * @param packageName the application requesting permission.
     * @param requestType the type of request, which determines the content and presentation of
     * the request data.
     * @param request the request data bundle containing at a minimum a request id.
     *
     * @see RestrictionsManager#REQUEST_TYPE_APPROVAL
     * @see RestrictionsManager#REQUEST_TYPE_LOCAL_APPROVAL
     * @see RestrictionsManager#REQUEST_KEY_ID
     */
    public abstract void onRequestPermission(Context context,
            String packageName, String requestType, String requestId, PersistableBundle request);

    /**
     * Intercept standard Restrictions Provider broadcasts.  Implementations
     * should not override this method; it is better to implement the
     * convenience callbacks for each action.
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (RestrictionsManager.ACTION_REQUEST_PERMISSION.equals(action)) {
            String packageName = intent.getStringExtra(RestrictionsManager.EXTRA_PACKAGE_NAME);
            String requestType = intent.getStringExtra(RestrictionsManager.EXTRA_REQUEST_TYPE);
            String requestId = intent.getStringExtra(RestrictionsManager.EXTRA_REQUEST_ID);
            PersistableBundle request = (PersistableBundle)
                    intent.getParcelableExtra(RestrictionsManager.EXTRA_REQUEST_BUNDLE);
            onRequestPermission(context, packageName, requestType, requestId, request);
        }
    }
}
