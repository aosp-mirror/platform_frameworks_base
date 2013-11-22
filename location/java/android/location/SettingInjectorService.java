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

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

/**
 * Dynamically specifies the summary (subtitle) and enabled status of a preference injected into
 * the list of app settings displayed by the system settings app
 * <p/>
 * For use only by apps that are included in the system image, for preferences that affect multiple
 * apps. Location settings that apply only to one app should be shown within that app,
 * rather than in the system settings.
 * <p/>
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
 * To ensure a good user experience, your {@link android.app.Application#onCreate()},
 * {@link #onGetSummary()}, and {@link #onGetEnabled()} methods must all be fast. If any are slow,
 * it can delay the display of settings values for other apps as well. Note further that all are
 * called on your app's UI thread.
 * <p/>
 * For compactness, only one copy of a given setting should be injected. If each account has a
 * distinct value for the setting, then the {@link #onGetSummary()} value should represent a summary
 * of the state across all of the accounts and {@code settingsActivity} should display the value for
 * each account.
 */
public abstract class SettingInjectorService extends Service {

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
     * @param name used to identify your subclass in log messages
     */
    public SettingInjectorService(String name) {
        mName = name;
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public final void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
    }

    @Override
    public final int onStartCommand(Intent intent, int flags, int startId) {
        onHandleIntent(intent);
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    private void onHandleIntent(Intent intent) {

        String summary;
        try {
            summary = onGetSummary();
        } catch (RuntimeException e) {
            // Exception. Send status anyway, so that settings injector can immediately start
            // loading the status of the next setting.
            sendStatus(intent, null, true);
            throw e;
        }

        boolean enabled;
        try {
            enabled = onGetEnabled();
        } catch (RuntimeException e) {
            // Exception. Send status anyway, so that settings injector can immediately start
            // loading the status of the next setting.
            sendStatus(intent, summary, true);
            throw e;
        }

        sendStatus(intent, summary, enabled);
    }

    /**
     * Send the summary and enabled values back to the caller via the messenger encoded in the
     * intent.
     */
    private void sendStatus(Intent intent, String summary, boolean enabled) {
        Message message = Message.obtain();
        Bundle bundle = new Bundle();
        bundle.putString(SUMMARY_KEY, summary);
        bundle.putBoolean(ENABLED_KEY, enabled);
        message.setData(bundle);

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, mName + ": received " + intent + ", summary=" + summary
                    + ", enabled=" + enabled + ", sending message: " + message);
        }

        Messenger messenger = intent.getParcelableExtra(MESSENGER_KEY);
        try {
            messenger.send(message);
        } catch (RemoteException e) {
            Log.e(TAG, mName + ": sending dynamic status failed", e);
        }
    }

    /**
     * Returns the {@link android.preference.Preference#getSummary()} value (allowed to be null or
     * empty). Should not perform unpredictably-long operations such as network access--see the
     * running-time comments in the class-level javadoc.
     *
     * @return the {@link android.preference.Preference#getSummary()} value
     */
    protected abstract String onGetSummary();

    /**
     * Returns the {@link android.preference.Preference#isEnabled()} value. Should not perform
     * unpredictably-long operations such as network access--see the running-time comments in the
     * class-level javadoc.
     * <p/>
     * Note that to prevent churn in the settings list, there is no support for dynamically choosing
     * to hide a setting. Instead you should have this method return false, which will disable the
     * setting and its link to your setting activity. One reason why you might choose to do this is
     * if {@link android.provider.Settings.Secure#LOCATION_MODE} is {@link
     * android.provider.Settings.Secure#LOCATION_MODE_OFF}.
     * <p/>
     * It is possible that the user may click on the setting before this method returns, so your
     * settings activity must handle the case where it is invoked even though the setting is
     * disabled. The simplest approach may be to simply call {@link android.app.Activity#finish()}
     * when disabled.
     *
     * @return the {@link android.preference.Preference#isEnabled()} value
     */
    protected abstract boolean onGetEnabled();
}
