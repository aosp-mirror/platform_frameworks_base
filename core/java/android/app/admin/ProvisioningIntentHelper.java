/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app.admin;

import static android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_ALLOW_OFFLINE;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_KEEP_ACCOUNT_ON_MIGRATION;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_KEEP_SCREEN_ON;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_LOCAL_TIME;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_RETURN_BEFORE_POLICY_COMPLIANCE;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_ROLE_HOLDER_EXTRAS_BUNDLE;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_SHOULD_LAUNCH_RESULT_INTENT;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_SKIP_OWNERSHIP_DISCLAIMER;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_SUPPORTED_MODES;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_TRIGGER;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_USE_MOBILE_DATA;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_WIFI_HIDDEN;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_WIFI_PROXY_PORT;
import static android.app.admin.DevicePolicyManager.MIME_TYPE_PROVISIONING_NFC;
import static android.app.admin.DevicePolicyManager.PROVISIONING_TRIGGER_NFC;
import static android.nfc.NfcAdapter.EXTRA_NDEF_MESSAGES;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Intent;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.util.Log;

import java.io.IOException;
import java.io.StringReader;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Utility class that provides functionality to create provisioning intents from nfc intents.
 */
final class ProvisioningIntentHelper {

    private static final Map<String, Class> EXTRAS_TO_CLASS_MAP = createExtrasToClassMap();

    private static final String TAG = "ProvisioningIntentHelper";

    /**
     * This class is never instantiated
     */
    private ProvisioningIntentHelper() { }

    @Nullable
    public static Intent createProvisioningIntentFromNfcIntent(@NonNull Intent nfcIntent) {
        requireNonNull(nfcIntent);

        if (!NfcAdapter.ACTION_NDEF_DISCOVERED.equals(nfcIntent.getAction())) {
            Log.e(TAG, "Wrong Nfc action: " + nfcIntent.getAction());
            return null;
        }

        NdefRecord firstRecord = getFirstNdefRecord(nfcIntent);

        if (firstRecord != null) {
            return createProvisioningIntentFromNdefRecord(firstRecord);
        }

        return null;
    }


    private static Intent createProvisioningIntentFromNdefRecord(NdefRecord firstRecord) {
        requireNonNull(firstRecord);

        Properties properties = loadPropertiesFromPayload(firstRecord.getPayload());

        if (properties == null) {
            Log.e(TAG, "Failed to load NdefRecord properties.");
            return null;
        }

        Bundle bundle = createBundleFromProperties(properties);

        if (!containsRequiredProvisioningExtras(bundle)) {
            Log.e(TAG, "Bundle does not contain the required provisioning extras.");
            return null;
        }

        return createProvisioningIntentFromBundle(bundle);
    }

    private static Properties loadPropertiesFromPayload(byte[] payload) {
        Properties properties = new Properties();

        try {
            properties.load(new StringReader(new String(payload, UTF_8)));
        } catch (IOException e) {
            Log.e(TAG, "NFC Intent properties loading failed.");
            return null;
        }

        return properties;
    }

    private static Bundle createBundleFromProperties(Properties properties) {
        Enumeration propertyNames = properties.propertyNames();
        Bundle bundle = new Bundle();

        while (propertyNames.hasMoreElements()) {
            String propertyName = (String) propertyNames.nextElement();
            addPropertyToBundle(propertyName, properties, bundle);
        }
        return bundle;
    }

    private static void addPropertyToBundle(
            String propertyName, Properties properties, Bundle bundle) {
        if (EXTRAS_TO_CLASS_MAP.get(propertyName) == ComponentName.class) {
            ComponentName componentName = ComponentName.unflattenFromString(
                    properties.getProperty(propertyName));
            bundle.putParcelable(propertyName, componentName);
        } else if (EXTRAS_TO_CLASS_MAP.get(propertyName) == PersistableBundle.class) {
            try {
                bundle.putParcelable(propertyName,
                        deserializeExtrasBundle(properties, propertyName));
            } catch (IOException e) {
                Log.e(TAG,
                        "Failed to parse " + propertyName + ".", e);
            }
        } else if (EXTRAS_TO_CLASS_MAP.get(propertyName) == Boolean.class) {
            bundle.putBoolean(propertyName,
                    Boolean.parseBoolean(properties.getProperty(propertyName)));
        } else if (EXTRAS_TO_CLASS_MAP.get(propertyName) == Long.class) {
            bundle.putLong(propertyName, Long.parseLong(properties.getProperty(propertyName)));
        } else if (EXTRAS_TO_CLASS_MAP.get(propertyName) == Integer.class) {
            bundle.putInt(propertyName, Integer.parseInt(properties.getProperty(propertyName)));
        }
        else {
            bundle.putString(propertyName, properties.getProperty(propertyName));
        }
    }

    /**
     * Get a {@link PersistableBundle} from a {@code String} property in a {@link Properties}
     * object.
     * @param properties the source of the extra
     * @param extraName key into the {@link Properties} object
     * @return the {@link PersistableBundle} or {@code null} if there was no property with the
     * given name
     * @throws IOException if there was an error parsing the property
     */
    private static PersistableBundle deserializeExtrasBundle(
            Properties properties, String extraName) throws IOException {
        String serializedExtras = properties.getProperty(extraName);
        if (serializedExtras == null) {
            return null;
        }
        Properties bundleProperties = new Properties();
        bundleProperties.load(new StringReader(serializedExtras));
        PersistableBundle extrasBundle = new PersistableBundle(bundleProperties.size());
        Set<String> propertyNames = bundleProperties.stringPropertyNames();
        for (String propertyName : propertyNames) {
            extrasBundle.putString(propertyName, bundleProperties.getProperty(propertyName));
        }
        return extrasBundle;
    }

    private static Intent createProvisioningIntentFromBundle(Bundle bundle) {
        requireNonNull(bundle);

        Intent provisioningIntent = new Intent(ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE);

        provisioningIntent.putExtras(bundle);

        provisioningIntent.putExtra(EXTRA_PROVISIONING_TRIGGER, PROVISIONING_TRIGGER_NFC);

        return provisioningIntent;
    }

    private static boolean containsRequiredProvisioningExtras(Bundle bundle) {
        return bundle.containsKey(EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME) ||
                bundle.containsKey(EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME);
    }

    /**
     * Returns the first {@link NdefRecord} found with a recognized MIME-type
     */
    private static NdefRecord getFirstNdefRecord(Intent nfcIntent) {
        Parcelable[] ndefMessages = nfcIntent.getParcelableArrayExtra(EXTRA_NDEF_MESSAGES);
        if (ndefMessages == null) {
            Log.i(TAG, "No EXTRA_NDEF_MESSAGES from nfcIntent");
            return null;
        }

        for (Parcelable rawMsg : ndefMessages) {
            NdefMessage msg = (NdefMessage) rawMsg;
            for (NdefRecord record : msg.getRecords()) {
                String mimeType = new String(record.getType(), UTF_8);

                // Only one first message with NFC_MIME_TYPE is used.
                if (MIME_TYPE_PROVISIONING_NFC.equals(mimeType)) {
                    return record;
                }

                // Assume only first record of message is used.
                break;
            }
        }

        Log.i(TAG, "No compatible records found on nfcIntent");
        return null;
    }

    private static Map<String, Class> createExtrasToClassMap() {
        Map<String, Class> map = new HashMap<>();
        for (String extra : getBooleanExtras()) {
            map.put(extra, Boolean.class);
        }
        for (String extra : getLongExtras()) {
            map.put(extra, Long.class);
        }
        for (String extra : getIntExtras()) {
            map.put(extra, Integer.class);
        }
        for (String extra : getComponentNameExtras()) {
            map.put(extra, ComponentName.class);
        }
        for (String extra : getPersistableBundleExtras()) {
            map.put(extra, PersistableBundle.class);
        }
        return map;
    }

    private static Set<String> getPersistableBundleExtras() {
        return Set.of(
                EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                EXTRA_PROVISIONING_ROLE_HOLDER_EXTRAS_BUNDLE);
    }

    private static Set<String> getComponentNameExtras() {
        return Set.of(EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME);
    }

    private static Set<String> getIntExtras() {
        return Set.of(
                EXTRA_PROVISIONING_WIFI_PROXY_PORT,
                EXTRA_PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE,
                EXTRA_PROVISIONING_SUPPORTED_MODES);
    }

    private static Set<String> getLongExtras() {
        return Set.of(EXTRA_PROVISIONING_LOCAL_TIME);
    }

    private static Set<String> getBooleanExtras() {
        return Set.of(
                EXTRA_PROVISIONING_ALLOW_OFFLINE,
                EXTRA_PROVISIONING_SHOULD_LAUNCH_RESULT_INTENT,
                EXTRA_PROVISIONING_KEEP_ACCOUNT_ON_MIGRATION,
                EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED,
                EXTRA_PROVISIONING_WIFI_HIDDEN,
                EXTRA_PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT,
                EXTRA_PROVISIONING_SKIP_ENCRYPTION,
                EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS,
                EXTRA_PROVISIONING_USE_MOBILE_DATA,
                EXTRA_PROVISIONING_SKIP_OWNERSHIP_DISCLAIMER,
                EXTRA_PROVISIONING_RETURN_BEFORE_POLICY_COMPLIANCE,
                EXTRA_PROVISIONING_KEEP_SCREEN_ON);
    }
}
