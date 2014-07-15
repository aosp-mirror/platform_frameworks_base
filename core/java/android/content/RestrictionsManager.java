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

import android.app.admin.DevicePolicyManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * Provides a mechanism for apps to query restrictions imposed by an entity that
 * manages the user. Apps can also send permission requests to a local or remote
 * device administrator to override default app-specific restrictions or any other
 * operation that needs explicit authorization from the administrator.
 * <p>
 * Apps can expose a set of restrictions via an XML file specified in the manifest.
 * <p>
 * If the user has an active Restrictions Provider, dynamic requests can be made in
 * addition to the statically imposed restrictions. Dynamic requests are app-specific
 * and can be expressed via a predefined set of request types.
 * <p>
 * The RestrictionsManager forwards the dynamic requests to the active
 * Restrictions Provider. The Restrictions Provider can respond back to requests by calling
 * {@link #notifyPermissionResponse(String, Bundle)}, when
 * a response is received from the administrator of the device or user.
 * The response is relayed back to the application via a protected broadcast,
 * {@link #ACTION_PERMISSION_RESPONSE_RECEIVED}.
 * <p>
 * Static restrictions are specified by an XML file referenced by a meta-data attribute
 * in the manifest. This enables applications as well as any web administration consoles
 * to be able to read the list of available restrictions from the apk.
 * <p>
 * The syntax of the XML format is as follows:
 * <pre>
 * &lt;restrictions&gt;
 *     &lt;restriction
 *         android:key="&lt;key&gt;"
 *         android:restrictionType="boolean|string|integer|multi-select|null"
 *         ... /&gt;
 *     &lt;restriction ... /&gt;
 * &lt;/restrictions&gt;
 * </pre>
 * <p>
 * The attributes for each restriction depend on the restriction type.
 *
 * @see RestrictionEntry
 * @see AbstractRestrictionsProvider
 */
public class RestrictionsManager {

    private static final String TAG = "RestrictionsManager";

    /**
     * Broadcast intent delivered when a response is received for a permission request. The
     * application should not interrupt the user by coming to the foreground if it isn't
     * currently in the foreground. It can either post a notification informing
     * the user of the response or wait until the next time the user launches the app.
     * <p>
     * For instance, if the user requested permission to make an in-app purchase,
     * the app can post a notification that the request had been approved or denied.
     * <p>
     * The broadcast Intent carries the following extra:
     * {@link #EXTRA_RESPONSE_BUNDLE}.
     */
    public static final String ACTION_PERMISSION_RESPONSE_RECEIVED =
            "android.intent.action.PERMISSION_RESPONSE_RECEIVED";

    /**
     * Broadcast intent sent to the Restrictions Provider to handle a permission request from
     * an app. It will have the following extras: {@link #EXTRA_PACKAGE_NAME},
     * {@link #EXTRA_REQUEST_TYPE} and {@link #EXTRA_REQUEST_BUNDLE}. The Restrictions Provider
     * will handle the request and respond back to the RestrictionsManager, when a response is
     * available, by calling {@link #notifyPermissionResponse}.
     * <p>
     * The BroadcastReceiver must require the {@link android.Manifest.permission#BIND_DEVICE_ADMIN}
     * permission to ensure that only the system can send the broadcast.
     */
    public static final String ACTION_REQUEST_PERMISSION =
            "android.content.action.REQUEST_PERMISSION";

    /**
     * The package name of the application making the request.
     */
    public static final String EXTRA_PACKAGE_NAME = "android.content.extra.PACKAGE_NAME";

    /**
     * The request type passed in the {@link #ACTION_REQUEST_PERMISSION} broadcast.
     */
    public static final String EXTRA_REQUEST_TYPE = "android.content.extra.REQUEST_TYPE";

    /**
     * The request bundle passed in the {@link #ACTION_REQUEST_PERMISSION} broadcast.
     */
    public static final String EXTRA_REQUEST_BUNDLE = "android.content.extra.REQUEST_BUNDLE";

    /**
     * Contains a response from the administrator for specific request.
     * The bundle contains the following information, at least:
     * <ul>
     * <li>{@link #REQUEST_KEY_ID}: The request ID.</li>
     * <li>{@link #RESPONSE_KEY_RESULT}: The response result.</li>
     * </ul>
     */
    public static final String EXTRA_RESPONSE_BUNDLE = "android.content.extra.RESPONSE_BUNDLE";

    /**
     * Request type for a simple question, with a possible title and icon.
     * <p>
     * Required keys are
     * {@link #REQUEST_KEY_ID} and {@link #REQUEST_KEY_MESSAGE}.
     * <p>
     * Optional keys are
     * {@link #REQUEST_KEY_DATA}, {@link #REQUEST_KEY_ICON}, {@link #REQUEST_KEY_TITLE},
     * {@link #REQUEST_KEY_APPROVE_LABEL} and {@link #REQUEST_KEY_DENY_LABEL}.
     */
    public static final String REQUEST_TYPE_APPROVAL = "android.request.type.approval";

    /**
     * Request type for a local password challenge. This is a way for an app to ask
     * the administrator to override an operation that is restricted on the device, such
     * as configuring Wi-Fi access points. It is most useful for situations where there
     * is no network connectivity for a remote administrator's response. The normal user of the
     * device is not expected to know the password. The challenge is meant for the administrator.
     * <p>
     * Required keys are
     * {@link #REQUEST_KEY_ID} and {@link #REQUEST_KEY_MESSAGE}.
     * <p>
     * Optional keys are
     * {@link #REQUEST_KEY_DATA}, {@link #REQUEST_KEY_ICON}, {@link #REQUEST_KEY_TITLE},
     * {@link #REQUEST_KEY_APPROVE_LABEL} and {@link #REQUEST_KEY_DENY_LABEL}.
     */
    public static final String REQUEST_TYPE_LOCAL_APPROVAL = "android.request.type.local_approval";

    /**
     * Key for request ID contained in the request bundle.
     * <p>
     * App-generated request ID to identify the specific request when receiving
     * a response. This value is returned in the {@link #EXTRA_RESPONSE_BUNDLE}.
     * <p>
     * Type: String
     */
    public static final String REQUEST_KEY_ID = "android.request.id";

    /**
     * Key for request data contained in the request bundle.
     * <p>
     * Optional, typically used to identify the specific data that is being referred to,
     * such as the unique identifier for a movie or book. This is not used for display
     * purposes and is more like a cookie. This value is returned in the
     * {@link #EXTRA_RESPONSE_BUNDLE}.
     * <p>
     * Type: String
     */
    public static final String REQUEST_KEY_DATA = "android.request.data";

    /**
     * Key for request title contained in the request bundle.
     * <p>
     * Optional, typically used as the title of any notification or dialog presented
     * to the administrator who approves the request.
     * <p>
     * Type: String
     */
    public static final String REQUEST_KEY_TITLE = "android.request.title";

    /**
     * Key for request message contained in the request bundle.
     * <p>
     * Required, shown as the actual message in a notification or dialog presented
     * to the administrator who approves the request.
     * <p>
     * Type: String
     */
    public static final String REQUEST_KEY_MESSAGE = "android.request.mesg";

    /**
     * Key for request icon contained in the request bundle.
     * <p>
     * Optional, shown alongside the request message presented to the administrator
     * who approves the request.
     * <p>
     * Type: Bitmap
     */
    public static final String REQUEST_KEY_ICON = "android.request.icon";

    /**
     * Key for request approval button label contained in the request bundle.
     * <p>
     * Optional, may be shown as a label on the positive button in a dialog or
     * notification presented to the administrator who approves the request.
     * <p>
     * Type: String
     */
    public static final String REQUEST_KEY_APPROVE_LABEL = "android.request.approve_label";

    /**
     * Key for request rejection button label contained in the request bundle.
     * <p>
     * Optional, may be shown as a label on the negative button in a dialog or
     * notification presented to the administrator who approves the request.
     * <p>
     * Type: String
     */
    public static final String REQUEST_KEY_DENY_LABEL = "android.request.deny_label";

    /**
     * Key for issuing a new request, contained in the request bundle. If this is set to true,
     * the Restrictions Provider must make a new request. If it is false or not specified, then
     * the Restrictions Provider can return a cached response that has the same requestId, if
     * available. If there's no cached response, it will issue a new one to the administrator.
     * <p>
     * Type: boolean
     */
    public static final String REQUEST_KEY_NEW_REQUEST = "android.request.new_request";

    /**
     * Key for the response in the response bundle sent to the application, for a permission
     * request.
     * <p>
     * Type: int
     * <p>
     * Possible values: {@link #RESULT_APPROVED}, {@link #RESULT_DENIED},
     * {@link #RESULT_NO_RESPONSE}, {@link #RESULT_UNKNOWN_REQUEST} or
     * {@link #RESULT_ERROR}.
     */
    public static final String RESPONSE_KEY_RESULT = "android.response.result";

    /**
     * Response result value indicating that the request was approved.
     */
    public static final int RESULT_APPROVED = 1;

    /**
     * Response result value indicating that the request was denied.
     */
    public static final int RESULT_DENIED = 2;

    /**
     * Response result value indicating that the request has not received a response yet.
     */
    public static final int RESULT_NO_RESPONSE = 3;

    /**
     * Response result value indicating that the request is unknown, when it's not a new
     * request.
     */
    public static final int RESULT_UNKNOWN_REQUEST = 4;

    /**
     * Response result value indicating an error condition. Additional error code might be available
     * in the response bundle, for the key {@link #RESPONSE_KEY_ERROR_CODE}. There might also be
     * an associated error message in the response bundle, for the key
     * {@link #RESPONSE_KEY_ERROR_MESSAGE}.
     */
    public static final int RESULT_ERROR = 5;

    /**
     * Error code indicating that there was a problem with the request.
     * <p>
     * Stored in {@link #RESPONSE_KEY_ERROR_CODE} field in the response bundle.
     */
    public static final int RESULT_ERROR_BAD_REQUEST = 1;

    /**
     * Error code indicating that there was a problem with the network.
     * <p>
     * Stored in {@link #RESPONSE_KEY_ERROR_CODE} field in the response bundle.
     */
    public static final int RESULT_ERROR_NETWORK = 2;

    /**
     * Error code indicating that there was an internal error.
     * <p>
     * Stored in {@link #RESPONSE_KEY_ERROR_CODE} field in the response bundle.
     */
    public static final int RESULT_ERROR_INTERNAL = 3;

    /**
     * Key for the optional error code in the response bundle sent to the application.
     * <p>
     * Type: int
     * <p>
     * Possible values: {@link #RESULT_ERROR_BAD_REQUEST}, {@link #RESULT_ERROR_NETWORK} or
     * {@link #RESULT_ERROR_INTERNAL}.
     */
    public static final String RESPONSE_KEY_ERROR_CODE = "android.response.errorcode";

    /**
     * Key for the optional error message in the response bundle sent to the application.
     * <p>
     * Type: String
     */
    public static final String RESPONSE_KEY_ERROR_MESSAGE = "android.response.errormsg";

    /**
     * Key for the optional timestamp of when the administrator responded to the permission
     * request. It is an represented in milliseconds since January 1, 1970 00:00:00.0 UTC.
     * <p>
     * Type: long
     */
    public static final String RESPONSE_KEY_RESPONSE_TIMESTAMP = "android.response.timestamp";

    private final Context mContext;
    private final IRestrictionsManager mService;

    /**
     * @hide
     */
    public RestrictionsManager(Context context, IRestrictionsManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Returns any available set of application-specific restrictions applicable
     * to this application.
     * @return the application restrictions as a Bundle. Returns null if there
     * are no restrictions.
     */
    public Bundle getApplicationRestrictions() {
        try {
            if (mService != null) {
                return mService.getApplicationRestrictions(mContext.getPackageName());
            }
        } catch (RemoteException re) {
            Log.w(TAG, "Couldn't reach service");
        }
        return null;
    }

    /**
     * Called by an application to check if there is an active Restrictions Provider. If
     * there isn't, {@link #requestPermission(String, Bundle)} is not available.
     *
     * @return whether there is an active Restrictions Provider.
     */
    public boolean hasRestrictionsProvider() {
        try {
            if (mService != null) {
                return mService.hasRestrictionsProvider();
            }
        } catch (RemoteException re) {
            Log.w(TAG, "Couldn't reach service");
        }
        return false;
    }

    /**
     * Called by an application to request permission for an operation. The contents of the
     * request are passed in a Bundle that contains several pieces of data depending on the
     * chosen request type.
     *
     * @param requestType The type of request. The type could be one of the
     * predefined types specified here or a custom type that the specific
     * Restrictions Provider might understand. For custom types, the type name should be
     * namespaced to avoid collisions with predefined types and types specified by
     * other Restrictions Providers.
     * @param request A Bundle containing the data corresponding to the specified request
     * type. The keys for the data in the bundle depend on the request type.
     *
     * @throws IllegalArgumentException if any of the required parameters are missing.
     */
    public void requestPermission(String requestType, Bundle request) {
        if (requestType == null) {
            throw new NullPointerException("requestType cannot be null");
        }
        if (request == null) {
            throw new NullPointerException("request cannot be null");
        }
        if (!request.containsKey(REQUEST_KEY_ID)) {
            throw new IllegalArgumentException("REQUEST_KEY_ID must be specified");
        }
        try {
            if (mService != null) {
                mService.requestPermission(mContext.getPackageName(), requestType, request);
            }
        } catch (RemoteException re) {
            Log.w(TAG, "Couldn't reach service");
        }
    }

    /**
     * Called by the Restrictions Provider to deliver a response to an application.
     *
     * @param packageName the application to deliver the response to. Cannot be null.
     * @param response the Bundle containing the response status, request ID and other information.
     *                 Cannot be null.
     *
     * @throws IllegalArgumentException if any of the required parameters are missing.
     */
    public void notifyPermissionResponse(String packageName, Bundle response) {
        if (packageName == null) {
            throw new NullPointerException("packageName cannot be null");
        }
        if (response == null) {
            throw new NullPointerException("request cannot be null");
        }
        if (!response.containsKey(REQUEST_KEY_ID)) {
            throw new IllegalArgumentException("REQUEST_KEY_ID must be specified");
        }
        if (!response.containsKey(RESPONSE_KEY_RESULT)) {
            throw new IllegalArgumentException("RESPONSE_KEY_RESULT must be specified");
        }
        try {
            if (mService != null) {
                mService.notifyPermissionResponse(packageName, response);
            }
        } catch (RemoteException re) {
            Log.w(TAG, "Couldn't reach service");
        }
    }

    /**
     * Parse and return the list of restrictions defined in the manifest for the specified
     * package, if any.
     *
     * @param packageName The application for which to fetch the restrictions list.
     * @return The list of RestrictionEntry objects created from the XML file specified
     * in the manifest, or null if none was specified.
     */
    public List<RestrictionEntry> getManifestRestrictions(String packageName) {
        // TODO:
        return null;
    }
}
