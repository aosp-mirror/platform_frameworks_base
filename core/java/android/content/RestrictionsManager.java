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

import android.annotation.SystemService;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.service.restrictions.RestrictionsReceiver;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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
 * {@link #notifyPermissionResponse(String, PersistableBundle)}, when
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
 * &lt;?xml version="1.0" encoding="utf-8"?&gt;
 * &lt;restrictions xmlns:android="http://schemas.android.com/apk/res/android" &gt;
 *     &lt;restriction
 *         android:key="string"
 *         android:title="string resource"
 *         android:restrictionType=["bool" | "string" | "integer"
 *                                         | "choice" | "multi-select" | "hidden"
 *                                         | "bundle" | "bundle_array"]
 *         android:description="string resource"
 *         android:entries="string-array resource"
 *         android:entryValues="string-array resource"
 *         android:defaultValue="reference" &gt;
 *             &lt;restriction ... /&gt;
 *             ...
 *     &lt;/restriction&gt;
 *     &lt;restriction ... /&gt;
 *     ...
 * &lt;/restrictions&gt;
 * </pre>
 * <p>
 * The attributes for each restriction depend on the restriction type.
 * <p>
 * <ul>
 * <li><code>key</code>, <code>title</code> and <code>restrictionType</code> are mandatory.</li>
 * <li><code>entries</code> and <code>entryValues</code> are required if <code>restrictionType
 * </code> is <code>choice</code> or <code>multi-select</code>.</li>
 * <li><code>defaultValue</code> is optional and its type depends on the
 * <code>restrictionType</code></li>
 * <li><code>hidden</code> type must have a <code>defaultValue</code> and will
 * not be shown to the administrator. It can be used to pass along data that cannot be modified,
 * such as a version code.</li>
 * <li><code>description</code> is meant to describe the restriction in more detail to the
 * administrator controlling the values, if the title is not sufficient.</li>
 * </ul>
 * <p>
 * Only restrictions of type {@code bundle} and {@code bundle_array} can have one or multiple nested
 * restriction elements.
 * <p>
 * In your manifest's <code>application</code> section, add the meta-data tag to point to
 * the restrictions XML file as shown below:
 * <pre>
 * &lt;application ... &gt;
 *     &lt;meta-data android:name="android.content.APP_RESTRICTIONS"
 *                   android:resource="@xml/app_restrictions" /&gt;
 *     ...
 * &lt;/application&gt;
 * </pre>
 *
 * @see RestrictionEntry
 * @see RestrictionsReceiver
 * @see DevicePolicyManager#setRestrictionsProvider(ComponentName, ComponentName)
 * @see DevicePolicyManager#setApplicationRestrictions(ComponentName, String, Bundle)
 */
@SystemService(Context.RESTRICTIONS_SERVICE)
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
            "android.content.action.PERMISSION_RESPONSE_RECEIVED";

    /**
     * Broadcast intent sent to the Restrictions Provider to handle a permission request from
     * an app. It will have the following extras: {@link #EXTRA_PACKAGE_NAME},
     * {@link #EXTRA_REQUEST_TYPE}, {@link #EXTRA_REQUEST_ID} and {@link #EXTRA_REQUEST_BUNDLE}.
     * The Restrictions Provider will handle the request and respond back to the
     * RestrictionsManager, when a response is available, by calling
     * {@link #notifyPermissionResponse}.
     * <p>
     * The BroadcastReceiver must require the {@link android.Manifest.permission#BIND_DEVICE_ADMIN}
     * permission to ensure that only the system can send the broadcast.
     */
    public static final String ACTION_REQUEST_PERMISSION =
            "android.content.action.REQUEST_PERMISSION";

    /**
     * Activity intent that is optionally implemented by the Restrictions Provider package
     * to challenge for an administrator PIN or password locally on the device. Apps will
     * call this intent using {@link Activity#startActivityForResult}. On a successful
     * response, {@link Activity#onActivityResult} will return a resultCode of
     * {@link Activity#RESULT_OK}.
     * <p>
     * The intent must contain {@link #EXTRA_REQUEST_BUNDLE} as an extra and the bundle must
     * contain at least {@link #REQUEST_KEY_MESSAGE} for the activity to display.
     * <p>
     * @see #createLocalApprovalIntent()
     */
    public static final String ACTION_REQUEST_LOCAL_APPROVAL =
            "android.content.action.REQUEST_LOCAL_APPROVAL";

    /**
     * The package name of the application making the request.
     * <p>
     * Type: String
     */
    public static final String EXTRA_PACKAGE_NAME = "android.content.extra.PACKAGE_NAME";

    /**
     * The request type passed in the {@link #ACTION_REQUEST_PERMISSION} broadcast.
     * <p>
     * Type: String
     */
    public static final String EXTRA_REQUEST_TYPE = "android.content.extra.REQUEST_TYPE";

    /**
     * The request ID passed in the {@link #ACTION_REQUEST_PERMISSION} broadcast.
     * <p>
     * Type: String
     */
    public static final String EXTRA_REQUEST_ID = "android.content.extra.REQUEST_ID";

    /**
     * The request bundle passed in the {@link #ACTION_REQUEST_PERMISSION} broadcast.
     * <p>
     * Type: {@link PersistableBundle}
     */
    public static final String EXTRA_REQUEST_BUNDLE = "android.content.extra.REQUEST_BUNDLE";

    /**
     * Contains a response from the administrator for specific request.
     * The bundle contains the following information, at least:
     * <ul>
     * <li>{@link #REQUEST_KEY_ID}: The request ID.</li>
     * <li>{@link #RESPONSE_KEY_RESULT}: The response result.</li>
     * </ul>
     * <p>
     * Type: {@link PersistableBundle}
     */
    public static final String EXTRA_RESPONSE_BUNDLE = "android.content.extra.RESPONSE_BUNDLE";

    /**
     * Request type for a simple question, with a possible title and icon.
     * <p>
     * Required keys are: {@link #REQUEST_KEY_MESSAGE}
     * <p>
     * Optional keys are
     * {@link #REQUEST_KEY_DATA}, {@link #REQUEST_KEY_ICON}, {@link #REQUEST_KEY_TITLE},
     * {@link #REQUEST_KEY_APPROVE_LABEL} and {@link #REQUEST_KEY_DENY_LABEL}.
     */
    public static final String REQUEST_TYPE_APPROVAL = "android.request.type.approval";

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
     * who approves the request. The content must be a compressed image such as a
     * PNG or JPEG, as a byte array.
     * <p>
     * Type: byte[]
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
     * Key for the response result in the response bundle sent to the application, for a permission
     * request. It indicates the status of the request. In some cases an additional message might
     * be available in {@link #RESPONSE_KEY_MESSAGE}, to be displayed to the user.
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
     * {@link #RESPONSE_KEY_MESSAGE}.
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
     * Key for the optional message in the response bundle sent to the application.
     * <p>
     * Type: String
     */
    public static final String RESPONSE_KEY_MESSAGE = "android.response.msg";

    /**
     * Key for the optional timestamp of when the administrator responded to the permission
     * request. It is an represented in milliseconds since January 1, 1970 00:00:00.0 UTC.
     * <p>
     * Type: long
     */
    public static final String RESPONSE_KEY_RESPONSE_TIMESTAMP = "android.response.timestamp";

    /**
     * Name of the meta-data entry in the manifest that points to the XML file containing the
     * application's available restrictions.
     * @see #getManifestRestrictions(String)
     */
    public static final String META_DATA_APP_RESTRICTIONS = "android.content.APP_RESTRICTIONS";

    private static final String TAG_RESTRICTION = "restriction";

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
            throw re.rethrowFromSystemServer();
        }
        return null;
    }

    /**
     * Called by an application to check if there is an active Restrictions Provider. If
     * there isn't, {@link #requestPermission(String, String, PersistableBundle)} is not available.
     *
     * @return whether there is an active Restrictions Provider.
     */
    public boolean hasRestrictionsProvider() {
        try {
            if (mService != null) {
                return mService.hasRestrictionsProvider();
            }
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
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
     * @param requestId A unique id generated by the app that contains sufficient information
     * to identify the parameters of the request when it receives the id in the response.
     * @param request A PersistableBundle containing the data corresponding to the specified request
     * type. The keys for the data in the bundle depend on the request type.
     *
     * @throws IllegalArgumentException if any of the required parameters are missing.
     */
    public void requestPermission(String requestType, String requestId, PersistableBundle request) {
        if (requestType == null) {
            throw new NullPointerException("requestType cannot be null");
        }
        if (requestId == null) {
            throw new NullPointerException("requestId cannot be null");
        }
        if (request == null) {
            throw new NullPointerException("request cannot be null");
        }
        try {
            if (mService != null) {
                mService.requestPermission(mContext.getPackageName(), requestType, requestId,
                        request);
            }
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    public Intent createLocalApprovalIntent() {
        try {
            if (mService != null) {
                return mService.createLocalApprovalIntent();
            }
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
        return null;
    }

    /**
     * Called by the Restrictions Provider to deliver a response to an application.
     *
     * @param packageName the application to deliver the response to. Cannot be null.
     * @param response the bundle containing the response status, request ID and other information.
     *                 Cannot be null.
     *
     * @throws IllegalArgumentException if any of the required parameters are missing.
     */
    public void notifyPermissionResponse(String packageName, PersistableBundle response) {
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
            throw re.rethrowFromSystemServer();
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
        ApplicationInfo appInfo = null;
        try {
            appInfo = mContext.getPackageManager().getApplicationInfo(packageName,
                    PackageManager.GET_META_DATA);
        } catch (NameNotFoundException pnfe) {
            throw new IllegalArgumentException("No such package " + packageName);
        }
        if (appInfo == null || !appInfo.metaData.containsKey(META_DATA_APP_RESTRICTIONS)) {
            return null;
        }

        XmlResourceParser xml =
                appInfo.loadXmlMetaData(mContext.getPackageManager(), META_DATA_APP_RESTRICTIONS);
        return loadManifestRestrictions(packageName, xml);
    }

    private List<RestrictionEntry> loadManifestRestrictions(String packageName,
            XmlResourceParser xml) {
        Context appContext;
        try {
            appContext = mContext.createPackageContext(packageName, 0 /* flags */);
        } catch (NameNotFoundException nnfe) {
            return null;
        }
        ArrayList<RestrictionEntry> restrictions = new ArrayList<>();
        RestrictionEntry restriction;

        try {
            int tagType = xml.next();
            while (tagType != XmlPullParser.END_DOCUMENT) {
                if (tagType == XmlPullParser.START_TAG) {
                    restriction = loadRestrictionElement(appContext, xml);
                    if (restriction != null) {
                        restrictions.add(restriction);
                    }
                }
                tagType = xml.next();
            }
        } catch (XmlPullParserException e) {
            Log.w(TAG, "Reading restriction metadata for " + packageName, e);
            return null;
        } catch (IOException e) {
            Log.w(TAG, "Reading restriction metadata for " + packageName, e);
            return null;
        }

        return restrictions;
    }

    private RestrictionEntry loadRestrictionElement(Context appContext, XmlResourceParser xml)
            throws IOException, XmlPullParserException {
        if (xml.getName().equals(TAG_RESTRICTION)) {
            AttributeSet attrSet = Xml.asAttributeSet(xml);
            if (attrSet != null) {
                TypedArray a = appContext.obtainStyledAttributes(attrSet,
                        com.android.internal.R.styleable.RestrictionEntry);
                return loadRestriction(appContext, a, xml);
            }
        }
        return null;
    }

    private RestrictionEntry loadRestriction(Context appContext, TypedArray a, XmlResourceParser xml)
            throws IOException, XmlPullParserException {
        String key = a.getString(R.styleable.RestrictionEntry_key);
        int restrictionType = a.getInt(
                R.styleable.RestrictionEntry_restrictionType, -1);
        String title = a.getString(R.styleable.RestrictionEntry_title);
        String description = a.getString(R.styleable.RestrictionEntry_description);
        int entries = a.getResourceId(R.styleable.RestrictionEntry_entries, 0);
        int entryValues = a.getResourceId(R.styleable.RestrictionEntry_entryValues, 0);

        if (restrictionType == -1) {
            Log.w(TAG, "restrictionType cannot be omitted");
            return null;
        }

        if (key == null) {
            Log.w(TAG, "key cannot be omitted");
            return null;
        }

        RestrictionEntry restriction = new RestrictionEntry(restrictionType, key);
        restriction.setTitle(title);
        restriction.setDescription(description);
        if (entries != 0) {
            restriction.setChoiceEntries(appContext, entries);
        }
        if (entryValues != 0) {
            restriction.setChoiceValues(appContext, entryValues);
        }
        // Extract the default value based on the type
        switch (restrictionType) {
            case RestrictionEntry.TYPE_NULL: // hidden
            case RestrictionEntry.TYPE_STRING:
            case RestrictionEntry.TYPE_CHOICE:
                restriction.setSelectedString(
                        a.getString(R.styleable.RestrictionEntry_defaultValue));
                break;
            case RestrictionEntry.TYPE_INTEGER:
                restriction.setIntValue(
                        a.getInt(R.styleable.RestrictionEntry_defaultValue, 0));
                break;
            case RestrictionEntry.TYPE_MULTI_SELECT:
                int resId = a.getResourceId(R.styleable.RestrictionEntry_defaultValue, 0);
                if (resId != 0) {
                    restriction.setAllSelectedStrings(
                            appContext.getResources().getStringArray(resId));
                }
                break;
            case RestrictionEntry.TYPE_BOOLEAN:
                restriction.setSelectedState(
                        a.getBoolean(R.styleable.RestrictionEntry_defaultValue, false));
                break;
            case RestrictionEntry.TYPE_BUNDLE:
            case RestrictionEntry.TYPE_BUNDLE_ARRAY:
                final int outerDepth = xml.getDepth();
                List<RestrictionEntry> restrictionEntries = new ArrayList<>();
                while (XmlUtils.nextElementWithin(xml, outerDepth)) {
                    RestrictionEntry childEntry = loadRestrictionElement(appContext, xml);
                    if (childEntry == null) {
                        Log.w(TAG, "Child entry cannot be loaded for bundle restriction " + key);
                    } else {
                        restrictionEntries.add(childEntry);
                        if (restrictionType == RestrictionEntry.TYPE_BUNDLE_ARRAY
                                && childEntry.getType() != RestrictionEntry.TYPE_BUNDLE) {
                            Log.w(TAG, "bundle_array " + key
                                    + " can only contain entries of type bundle");
                        }
                    }
                }
                restriction.setRestrictions(restrictionEntries.toArray(new RestrictionEntry[
                        restrictionEntries.size()]));
                break;
            default:
                Log.w(TAG, "Unknown restriction type " + restrictionType);
        }
        return restriction;
    }

    /**
     * Converts a list of restrictions to the corresponding bundle, using the following mapping:
     * <table>
     *     <tr><th>RestrictionEntry</th><th>Bundle</th></tr>
     *     <tr><td>{@link RestrictionEntry#TYPE_BOOLEAN}</td><td>{@link Bundle#putBoolean}</td></tr>
     *     <tr><td>{@link RestrictionEntry#TYPE_CHOICE},
     *     {@link RestrictionEntry#TYPE_MULTI_SELECT}</td>
     *     <td>{@link Bundle#putStringArray}</td></tr>
     *     <tr><td>{@link RestrictionEntry#TYPE_INTEGER}</td><td>{@link Bundle#putInt}</td></tr>
     *     <tr><td>{@link RestrictionEntry#TYPE_STRING}</td><td>{@link Bundle#putString}</td></tr>
     *     <tr><td>{@link RestrictionEntry#TYPE_BUNDLE}</td><td>{@link Bundle#putBundle}</td></tr>
     *     <tr><td>{@link RestrictionEntry#TYPE_BUNDLE_ARRAY}</td>
     *     <td>{@link Bundle#putParcelableArray}</td></tr>
     * </table>
     * @param entries list of restrictions
     */
    public static Bundle convertRestrictionsToBundle(List<RestrictionEntry> entries) {
        final Bundle bundle = new Bundle();
        for (RestrictionEntry entry : entries) {
            addRestrictionToBundle(bundle, entry);
        }
        return bundle;
    }

    private static Bundle addRestrictionToBundle(Bundle bundle, RestrictionEntry entry) {
        switch (entry.getType()) {
            case RestrictionEntry.TYPE_BOOLEAN:
                bundle.putBoolean(entry.getKey(), entry.getSelectedState());
                break;
            case RestrictionEntry.TYPE_CHOICE:
            case RestrictionEntry.TYPE_CHOICE_LEVEL:
            case RestrictionEntry.TYPE_MULTI_SELECT:
                bundle.putStringArray(entry.getKey(), entry.getAllSelectedStrings());
                break;
            case RestrictionEntry.TYPE_INTEGER:
                bundle.putInt(entry.getKey(), entry.getIntValue());
                break;
            case RestrictionEntry.TYPE_STRING:
            case RestrictionEntry.TYPE_NULL:
                bundle.putString(entry.getKey(), entry.getSelectedString());
                break;
            case RestrictionEntry.TYPE_BUNDLE:
                RestrictionEntry[] restrictions = entry.getRestrictions();
                Bundle childBundle = convertRestrictionsToBundle(Arrays.asList(restrictions));
                bundle.putBundle(entry.getKey(), childBundle);
                break;
            case RestrictionEntry.TYPE_BUNDLE_ARRAY:
                RestrictionEntry[] bundleRestrictionArray = entry.getRestrictions();
                Bundle[] bundleArray = new Bundle[bundleRestrictionArray.length];
                for (int i = 0; i < bundleRestrictionArray.length; i++) {
                    RestrictionEntry[] bundleRestrictions =
                            bundleRestrictionArray[i].getRestrictions();
                    if (bundleRestrictions == null) {
                        // Non-bundle entry found in bundle array.
                        Log.w(TAG, "addRestrictionToBundle: " +
                                "Non-bundle entry found in bundle array");
                        bundleArray[i] = new Bundle();
                    } else {
                        bundleArray[i] = convertRestrictionsToBundle(Arrays.asList(
                                bundleRestrictions));
                    }
                }
                bundle.putParcelableArray(entry.getKey(), bundleArray);
                break;
            default:
                throw new IllegalArgumentException(
                        "Unsupported restrictionEntry type: " + entry.getType());
        }
        return bundle;
    }

}
