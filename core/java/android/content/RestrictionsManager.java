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
 * Apps can expose a set of restrictions via a runtime receiver mechanism or via
 * static meta data in the manifest.
 * <p>
 * If the user has an active restrictions provider, dynamic requests can be made in
 * addition to the statically imposed restrictions. Dynamic requests are app-specific
 * and can be expressed via a predefined set of templates.
 * <p>
 * The RestrictionsManager forwards the dynamic requests to the active
 * restrictions provider. The restrictions provider can respond back to requests by calling
 * {@link #notifyPermissionResponse(String, Bundle)}, when
 * a response is received from the administrator of the device or user 
 * The response is relayed back to the application via a protected broadcast,
 * {@link #ACTION_PERMISSION_RESPONSE_RECEIVED}.
 * <p>
 * Static restrictions are specified by an XML file referenced by a meta-data attribute
 * in the manifest. This enables applications as well as any web administration consoles
 * to be able to read the template from the apk.
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
 */
public class RestrictionsManager {

    /**
     * Broadcast intent delivered when a response is received for a permission
     * request. The response is not available for later query, so the receiver
     * must persist and/or immediately act upon the response. The application
     * should not interrupt the user by coming to the foreground if it isn't
     * currently in the foreground. It can post a notification instead, informing
     * the user of a change in state.
     * <p>
     * For instance, if the user requested permission to make an in-app purchase,
     * the app can post a notification that the request had been granted or denied,
     * and allow the purchase to go through.
     * <p>
     * The broadcast Intent carries the following extra:
     * {@link #EXTRA_RESPONSE_BUNDLE}.
     */
    public static final String ACTION_PERMISSION_RESPONSE_RECEIVED =
            "android.intent.action.PERMISSION_RESPONSE_RECEIVED";

    /**
     * Protected broadcast intent sent to the active restrictions provider. The intent
     * contains the following extras:<p>
     * <ul>
     * <li>{@link #EXTRA_PACKAGE_NAME} : String; the package name of the application requesting
     * permission.</li>
     * <li>{@link #EXTRA_TEMPLATE_ID} : String; the template of the request.</li>
     * <li>{@link #EXTRA_REQUEST_BUNDLE} : Bundle; contains the template-specific keys and values
     * for the request.
     * </ul>
     * @see DevicePolicyManager#setRestrictionsProvider(ComponentName, ComponentName)
     * @see #requestPermission(String, String, Bundle)
     */
    public static final String ACTION_REQUEST_PERMISSION =
            "android.intent.action.REQUEST_PERMISSION";

    /**
     * The package name of the application making the request.
     */
    public static final String EXTRA_PACKAGE_NAME = "package_name";

    /**
     * The template id that specifies what kind of a request it is and may indicate
     * how the request is to be presented to the administrator. Must be either one of
     * the predefined templates or a custom one specified by the application that the
     * restrictions provider is familiar with.
     */
    public static final String EXTRA_TEMPLATE_ID = "template_id";

    /**
     * A bundle containing the details about the request. The contents depend on the
     * template id.
     * @see #EXTRA_TEMPLATE_ID
     */
    public static final String EXTRA_REQUEST_BUNDLE = "request_bundle";

    /**
     * Contains a response from the administrator for specific request.
     * The bundle contains the following information, at least:
     * <ul>
     * <li>{@link #REQUEST_KEY_ID}: The request id.</li>
     * <li>{@link #REQUEST_KEY_DATA}: The request reference data.</li>
     * </ul>
     * <p>
     * And depending on what the request template was, the bundle will contain the actual
     * result of the request. For {@link #REQUEST_TEMPLATE_QUESTION}, the result will be in
     * {@link #RESPONSE_KEY_BOOLEAN}, which is of type boolean; true if the administrator
     * approved the request, false otherwise.
     */
    public static final String EXTRA_RESPONSE_BUNDLE = "response_bundle";


    /**
     * Request template that presents a simple question, with a possible title and icon.
     * <p>
     * Required keys are
     * {@link #REQUEST_KEY_ID} and {@link #REQUEST_KEY_MESSAGE}.
     * <p>
     * Optional keys are
     * {@link #REQUEST_KEY_DATA}, {@link #REQUEST_KEY_ICON}, {@link #REQUEST_KEY_TITLE},
     * {@link #REQUEST_KEY_APPROVE_LABEL} and {@link #REQUEST_KEY_DENY_LABEL}.
     */
    public static final String REQUEST_TEMPLATE_QUESTION = "android.req_template.type.simple";

    /**
     * Key for request ID contained in the request bundle.
     * <p>
     * App-generated request id to identify the specific request when receiving
     * a response. This value is returned in the {@link #EXTRA_RESPONSE_BUNDLE}.
     * <p>
     * Type: String
     */
    public static final String REQUEST_KEY_ID = "android.req_template.req_id";

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
    public static final String REQUEST_KEY_DATA = "android.req_template.data";

    /**
     * Key for request title contained in the request bundle.
     * <p>
     * Optional, typically used as the title of any notification or dialog presented
     * to the administrator who approves the request.
     * <p>
     * Type: String
     */
    public static final String REQUEST_KEY_TITLE = "android.req_template.title";

    /**
     * Key for request message contained in the request bundle.
     * <p>
     * Required, shown as the actual message in a notification or dialog presented
     * to the administrator who approves the request.
     * <p>
     * Type: String
     */
    public static final String REQUEST_KEY_MESSAGE = "android.req_template.mesg";

    /**
     * Key for request icon contained in the request bundle.
     * <p>
     * Optional, shown alongside the request message presented to the administrator
     * who approves the request.
     * <p>
     * Type: Bitmap
     */
    public static final String REQUEST_KEY_ICON = "android.req_template.icon";

    /**
     * Key for request approval button label contained in the request bundle.
     * <p>
     * Optional, may be shown as a label on the positive button in a dialog or
     * notification presented to the administrator who approves the request.
     * <p>
     * Type: String
     */
    public static final String REQUEST_KEY_APPROVE_LABEL = "android.req_template.accept";

    /**
     * Key for request rejection button label contained in the request bundle.
     * <p>
     * Optional, may be shown as a label on the negative button in a dialog or
     * notification presented to the administrator who approves the request.
     * <p>
     * Type: String
     */
    public static final String REQUEST_KEY_DENY_LABEL = "android.req_template.reject";

    /**
     * Key for requestor's name contained in the request bundle. This value is not specified by
     * the application. It is automatically inserted into the Bundle by the Restrictions Provider
     * before it is sent to the administrator.
     * <p>
     * Type: String
     */
    public static final String REQUEST_KEY_REQUESTOR_NAME = "android.req_template.requestor";

    /**
     * Key for requestor's device name contained in the request bundle. This value is not specified
     * by the application. It is automatically inserted into the Bundle by the Restrictions Provider
     * before it is sent to the administrator.
     * <p>
     * Type: String
     */
    public static final String REQUEST_KEY_DEVICE_NAME = "android.req_template.device";

    /**
     * Key for the response in the response bundle sent to the application, for a permission
     * request.
     * <p>
     * Type: boolean
     */
    public static final String RESPONSE_KEY_BOOLEAN = "android.req_template.response";

    private static final String TAG = "RestrictionsManager";

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
     * @return
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
     * Called by an application to check if permission requests can be made. If false,
     * there is no need to request permission for an operation, unless a static
     * restriction applies to that operation.
     * @return
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
     * chosen request template.
     *
     * @param requestTemplate The request template to use. The template could be one of the
     * predefined templates specified in this class or a custom template that the specific
     * Restrictions Provider might understand. For custom templates, the template name should be
     * namespaced to avoid collisions with predefined templates and templates specified by
     * other Restrictions Provider vendors.
     * @param requestData A Bundle containing the data corresponding to the specified request
     * template. The keys for the data in the bundle depend on the kind of template chosen.
     */
    public void requestPermission(String requestTemplate, Bundle requestData) {
        try {
            if (mService != null) {
                mService.requestPermission(mContext.getPackageName(), requestTemplate, requestData);
            }
        } catch (RemoteException re) {
            Log.w(TAG, "Couldn't reach service");
        }
    }

    /**
     * Called by the Restrictions Provider when a response is available to be
     * delivered to an application.
     * @param packageName
     * @param response
     */
    public void notifyPermissionResponse(String packageName, Bundle response) {
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
     * @param packageName The application for which to fetch the restrictions list.
     * @return The list of RestrictionEntry objects created from the XML file specified
     * in the manifest, or null if none was specified.
     */
    public List<RestrictionEntry> getManifestRestrictions(String packageName) {
        // TODO:
        return null;
    }
}
