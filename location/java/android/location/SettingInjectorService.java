/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.location;

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

/**
 * Dynamically specifies the summary (subtitle) and enabled status of a preference injected into
 * the list of location services displayed by the system settings app.
 *
 * The location services list is intended for use only by preferences that affect multiple apps from
 * the same developer. Location settings that apply only to one app should be shown within that app,
 * rather than in the system settings.
 *
 * To add a preference to the list, a subclass of {@link SettingInjectorService} must be declared in
 * the manifest as so:
 *
 * <pre>
 *     &lt;service android:name="com.example.android.injector.MyInjectorService" &gt;
 *         &lt;intent-filter&gt;
 *             &lt;action android:name="android.location.SettingInjectorService" /&gt;
 *         &lt;/intent-filter&gt;
 *
 *         &lt;meta-data
 *             android:name="android.location.SettingInjectorService"
 *             android:resource="@xml/my_injected_location_setting" /&gt;
 *     &lt;/service&gt;
 * </pre>
 * The resource file specifies the static data for the setting:
 * <pre>
 *     &lt;injected-location-setting xmlns:android="http://schemas.android.com/apk/res/android"
 *         android:title="@string/injected_setting_title"
 *         android:icon="@drawable/ic_acme_corp"
 *         android:settingsActivity="com.example.android.injector.MySettingActivity"
 *     /&gt;
 * </pre>
 * Here:
 * <ul>
 *     <li>title: The {@link android.preference.Preference#getTitle()} value. The title should make
 *     it clear which apps are affected by the setting, typically by including the name of the
 *     developer. For example, "Acme Corp. ads preferences." </li>
 *
 *     <li>icon: The {@link android.preference.Preference#getIcon()} value. Typically this will be a
 *     generic icon for the developer rather than the icon for an individual app.</li>
 *
 *     <li>settingsActivity: the activity which is launched to allow the user to modify the setting
 *     value.  The activity must be in the same package as the subclass of
 *     {@link SettingInjectorService}. The activity should use your own branding to help emphasize
 *     to the user that it is not part of the system settings.</li>
 * </ul>
 *
 * To ensure a good user experience, the average time from the start of
 * {@link #startService(Intent)} to the end of {@link #onHandleIntent(Intent)} should be less
 * than 300 msec even if your app is not already in memory. This means that both your
 * {@link android.app.Application#onCreate()} and your {@link #getStatus()} must be fast. If
 * either is slow, it can delay the display of settings values for other apps as well.
 *
 * For compactness, only one copy of a given setting should be injected. If each account has a
 * distinct value for the setting, then the {@link #getStatus()} value should represent a summary of
 * the state across all of the accounts and {@code settingsActivity} should display the value for
 * each account.
 */
// TODO: is there a public list of supported locales?
// TODO: is there a public list of guidelines for settings text?
// TODO: would a bound service be better? E.g., we could just disconnect if a service took too long
public abstract class SettingInjectorService extends IntentService {

    private static final String TAG = "SettingInjectorService";

    /**
     * Intent action that must be declared in the manifest for the subclass. Used to start the
     * service to read the dynamic status for the setting.
     */
    public static final String ACTION_SERVICE_INTENT = "android.location.SettingInjectorService";

    /**
     * Name of the meta-data tag used to specify the resource file that includes the settings
     * attributes.
     */
    public static final String META_DATA_NAME = "android.location.SettingInjectorService";

    /**
     * Name of the XML tag that includes the attributes for the setting.
     */
    public static final String ATTRIBUTES_NAME = "injected-location-setting";

    /**
     * Intent action a client should broadcast when the value of one of its injected settings has
     * changed, so that the setting can be updated in the UI.
     */
    public static final String ACTION_INJECTED_SETTING_CHANGED =
            "android.location.InjectedSettingChanged";

    /**
     * Name of the bundle key for the string specifying the summary for the setting (e.g., "ON" or
     * "OFF").
     *
     * @hide
     */
    public static final String SUMMARY_KEY = "summary";

    /**
     * Name of the bundle key for the string specifying whether the setting is currently enabled.
     *
     * @hide
     */
    public static final String ENABLED_KEY = "enabled";

    /**
     * Name of the intent key used to specify the messenger
     *
     * @hide
     */
    public static final String MESSENGER_KEY = "messenger";

    private final String mName;

    /**
     * Constructor.
     *
     * @param name used to name the worker thread and in log messages
     */
    public SettingInjectorService(String name) {
        super(name);
        mName = name;
    }

    @Override
    final protected void onHandleIntent(Intent intent) {
        // Get messenger first to ensure intent doesn't get messed with (in case we later decide
        // to pass intent into getStatus())
        Messenger messenger = intent.getParcelableExtra(MESSENGER_KEY);

        Status status;
        try {
            status = getStatus();
        } catch (RuntimeException e) {
            Log.e(TAG, mName + ": error getting status", e);
            status = null;
        }

        // Send the status back to the caller via the messenger
        Message message = Message.obtain();
        Bundle bundle = new Bundle();
        if (status != null) {
            bundle.putString(SUMMARY_KEY, status.summary);
            bundle.putBoolean(ENABLED_KEY, status.enabled);
        }
        message.setData(bundle);

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, mName + ": received " + intent + " and " + status
                    + ", sending message: " + message);
        }
        try {
            messenger.send(message);
        } catch (RemoteException e) {
            Log.e(TAG, mName + ": sending status failed", e);
        }
    }

    /**
     * Reads the status of the setting. Should not perform unpredictably-long operations such as
     * network access--see the running-time comments in the class-level javadoc.
     *
     * @return the status of the setting value
     */
    protected abstract Status getStatus();

    /**
     * Dynamic characteristics of an injected location setting.
     */
    public static final class Status {

        /**
         * The {@link android.preference.Preference#getSummary()} value.
         *
         * @hide
         */
        public final String summary;

        /**
         * The {@link android.preference.Preference#isEnabled()} value.
         *
         * @hide
         */
        public final boolean enabled;

        /**
         * Constructor.
         * <p/>
         * Note that to prevent churn in the settings list, there is no support for dynamically
         * choosing to hide a setting. Instead you should provide a {@code enabled} value of false,
         * which will disable the setting and its link to your setting activity. One reason why you
         * might choose to do this is if {@link android.provider.Settings.Secure#LOCATION_MODE}
         * is {@link android.provider.Settings.Secure#LOCATION_MODE_OFF}.
         *
         * It is possible that the user may click on the setting before you return a false value for
         * {@code enabled}, so your settings activity must handle the case where it is invoked even
         * though the setting is disabled. The simplest approach may be to simply call
         * {@link android.app.Activity#finish()} when disabled.
         *
         * @param summary the {@link android.preference.Preference#getSummary()} value (allowed to
         *                be null or empty)
         * @param enabled the {@link android.preference.Preference#isEnabled()} value
         */
        public Status(String summary, boolean enabled) {
            this.summary = summary;
            this.enabled = enabled;
        }

        @Override
        public String toString() {
            return "Status{summary='" + summary + '\'' + ", enabled=" + enabled + '}';
        }
    }
}
