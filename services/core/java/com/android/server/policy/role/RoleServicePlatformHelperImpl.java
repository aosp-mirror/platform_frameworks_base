/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.policy.role;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.util.CollectionUtils;
import com.android.server.LocalServices;
import com.android.server.role.RoleServicePlatformHelper;

import libcore.util.HexEncoding;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Implementation of {@link RoleServicePlatformHelper}.
 */
public class RoleServicePlatformHelperImpl implements RoleServicePlatformHelper {
    private static final String LOG_TAG = RoleServicePlatformHelperImpl.class.getSimpleName();

    private static final String ROLES_FILE_NAME = "roles.xml";

    private static final String TAG_ROLES = "roles";
    private static final String TAG_ROLE = "role";
    private static final String TAG_HOLDER = "holder";
    private static final String ATTRIBUTE_NAME = "name";

    @NonNull
    private final Context mContext;

    public RoleServicePlatformHelperImpl(@NonNull Context context) {
        mContext = context;
    }

    @NonNull
    @Override
    public Map<String, Set<String>> getLegacyRoleState(@UserIdInt int userId) {
        Map<String, Set<String>> roles = readFile(userId);
        if (roles == null) {
            roles = readFromLegacySettings(userId);
        }
        return roles;
    }

    @Nullable
    private Map<String, Set<String>> readFile(@UserIdInt int userId) {
        File file = getFile(userId);
        try (FileInputStream in = new AtomicFile(file).openRead()) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            Map<String, Set<String>> roles = parseXml(parser);
            Slog.i(LOG_TAG, "Read legacy roles.xml successfully");
            return roles;
        } catch (FileNotFoundException e) {
            Slog.i(LOG_TAG, "Legacy roles.xml not found");
            return null;
        } catch (XmlPullParserException | IOException e) {
            Slog.wtf(LOG_TAG, "Failed to parse legacy roles.xml: " + file, e);
            return null;
        }
    }

    @NonNull
    private Map<String, Set<String>> parseXml(@NonNull XmlPullParser parser) throws IOException,
            XmlPullParserException {
        int type;
        int depth;
        int innerDepth = parser.getDepth() + 1;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && ((depth = parser.getDepth()) >= innerDepth || type != XmlPullParser.END_TAG)) {
            if (depth > innerDepth || type != XmlPullParser.START_TAG) {
                continue;
            }

            if (parser.getName().equals(TAG_ROLES)) {
                return parseRoles(parser);
            }
        }

        throw new IOException("Missing <" + TAG_ROLES + "> in roles.xml");
    }

    @NonNull
    private Map<String, Set<String>> parseRoles(@NonNull XmlPullParser parser) throws IOException,
            XmlPullParserException {
        Map<String, Set<String>> roles = new ArrayMap<>();

        int type;
        int depth;
        int innerDepth = parser.getDepth() + 1;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && ((depth = parser.getDepth()) >= innerDepth || type != XmlPullParser.END_TAG)) {
            if (depth > innerDepth || type != XmlPullParser.START_TAG) {
                continue;
            }

            if (parser.getName().equals(TAG_ROLE)) {
                String roleName = parser.getAttributeValue(null, ATTRIBUTE_NAME);
                Set<String> roleHolders = parseRoleHoldersLocked(parser);
                roles.put(roleName, roleHolders);
            }
        }

        return roles;
    }

    @NonNull
    private Set<String> parseRoleHoldersLocked(@NonNull XmlPullParser parser)
            throws IOException, XmlPullParserException {
        Set<String> roleHolders = new ArraySet<>();

        int type;
        int depth;
        int innerDepth = parser.getDepth() + 1;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && ((depth = parser.getDepth()) >= innerDepth || type != XmlPullParser.END_TAG)) {
            if (depth > innerDepth || type != XmlPullParser.START_TAG) {
                continue;
            }

            if (parser.getName().equals(TAG_HOLDER)) {
                String roleHolder = parser.getAttributeValue(null, ATTRIBUTE_NAME);
                roleHolders.add(roleHolder);
            }
        }

        return roleHolders;
    }

    @NonNull
    private static File getFile(@UserIdInt int userId) {
        return new File(Environment.getUserSystemDirectory(userId), ROLES_FILE_NAME);
    }

    @NonNull
    private Map<String, Set<String>> readFromLegacySettings(@UserIdInt int userId) {
        Map<String, Set<String>> roles = new ArrayMap<>();

        // Assistant
        ContentResolver contentResolver = mContext.getContentResolver();
        String assistantSetting = Settings.Secure.getStringForUser(contentResolver,
                Settings.Secure.ASSISTANT, userId);
        PackageManager packageManager = mContext.getPackageManager();
        String assistantPackageName;
        // AssistUtils was using the default assistant app if Settings.Secure.ASSISTANT is
        // null, while only an empty string means user selected "None".
        if (assistantSetting != null) {
            if (!assistantSetting.isEmpty()) {
                ComponentName componentName = ComponentName.unflattenFromString(assistantSetting);
                assistantPackageName = componentName != null ? componentName.getPackageName()
                        : null;
            } else {
                assistantPackageName = null;
            }
        } else if (packageManager.isDeviceUpgrading()) {
            String defaultAssistant = mContext.getString(R.string.config_defaultAssistant);
            assistantPackageName = !TextUtils.isEmpty(defaultAssistant) ? defaultAssistant : null;
        } else {
            assistantPackageName = null;
        }
        if (assistantPackageName != null) {
            roles.put(RoleManager.ROLE_ASSISTANT, Collections.singleton(assistantPackageName));
        }

        // Browser
        PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        String browserPackageName = packageManagerInternal.removeLegacyDefaultBrowserPackageName(
                userId);
        if (browserPackageName != null) {
            roles.put(RoleManager.ROLE_BROWSER, Collections.singleton(browserPackageName));
        }

        // Dialer
        String dialerSetting = Settings.Secure.getStringForUser(contentResolver,
                Settings.Secure.DIALER_DEFAULT_APPLICATION, userId);
        String dialerPackageName;
        if (!TextUtils.isEmpty(dialerSetting)) {
            dialerPackageName = dialerSetting;
        } else if (packageManager.isDeviceUpgrading()) {
            // DefaultDialerManager was using the default dialer app if
            // Settings.Secure.DIALER_DEFAULT_APPLICATION is invalid.
            // TelecomManager.getSystemDialerPackage() won't work because it might not
            // be ready.
            dialerPackageName = mContext.getString(R.string.config_defaultDialer);
        } else {
            dialerPackageName = null;
        }
        if (dialerPackageName != null) {
            roles.put(RoleManager.ROLE_DIALER, Collections.singleton(dialerPackageName));
        }

        // SMS
        String smsSetting = Settings.Secure.getStringForUser(contentResolver,
                Settings.Secure.SMS_DEFAULT_APPLICATION, userId);
        String smsPackageName;
        if (!TextUtils.isEmpty(smsSetting)) {
            smsPackageName = smsSetting;
        } else if (mContext.getPackageManager().isDeviceUpgrading()) {
            // SmsApplication was using the default SMS app if
            // Settings.Secure.DIALER_DEFAULT_APPLICATION is invalid.
            smsPackageName = mContext.getString(R.string.config_defaultSms);
        } else {
            smsPackageName = null;
        }
        if (smsPackageName != null) {
            roles.put(RoleManager.ROLE_SMS, Collections.singleton(smsPackageName));
        }

        // Home
        String homePackageName;
        if (packageManager.isDeviceUpgrading()) {
            ResolveInfo resolveInfo = packageManager.resolveActivityAsUser(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.MATCH_DEFAULT_ONLY
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userId);
            homePackageName = resolveInfo != null && resolveInfo.activityInfo != null
                    ? resolveInfo.activityInfo.packageName : null;
            if (homePackageName != null && isSettingsApplication(homePackageName, userId)) {
                homePackageName = null;
            }
        } else {
            homePackageName = null;
        }
        if (homePackageName != null) {
            roles.put(RoleManager.ROLE_HOME, Collections.singleton(homePackageName));
        }

        // Emergency
        String emergencyPackageName = Settings.Secure.getStringForUser(contentResolver,
                Settings.Secure.EMERGENCY_ASSISTANCE_APPLICATION, userId);
        if (emergencyPackageName != null) {
            roles.put(RoleManager.ROLE_EMERGENCY, Collections.singleton(emergencyPackageName));
        }

        return roles;
    }

    private boolean isSettingsApplication(@NonNull String packageName, @UserIdInt int userId) {
        PackageManager packageManager = mContext.getPackageManager();
        ResolveInfo resolveInfo = packageManager.resolveActivityAsUser(new Intent(
                Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userId);
        if (resolveInfo == null || resolveInfo.activityInfo == null) {
            return false;
        }
        return Objects.equals(packageName, resolveInfo.activityInfo.packageName);
    }

    @NonNull
    @Override
    public String computePackageStateHash(@UserIdInt int userId) {
        PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        final MessageDigestOutputStream mdos = new MessageDigestOutputStream();

        DataOutputStream dataOutputStream = new DataOutputStream(new BufferedOutputStream(mdos));
        packageManagerInternal.forEachInstalledPackage(pkg -> {
            try {
                dataOutputStream.writeUTF(pkg.getPackageName());
                dataOutputStream.writeLong(pkg.getLongVersionCode());
                dataOutputStream.writeInt(packageManagerInternal.getApplicationEnabledState(
                        pkg.getPackageName(), userId));

                final List<String> requestedPermissions = pkg.getRequestedPermissions();
                final int requestedPermissionsSize = requestedPermissions.size();
                dataOutputStream.writeInt(requestedPermissionsSize);
                for (int i = 0; i < requestedPermissionsSize; i++) {
                    dataOutputStream.writeUTF(requestedPermissions.get(i));
                }

                final ArraySet<String> enabledComponents =
                        packageManagerInternal.getEnabledComponents(pkg.getPackageName(), userId);
                final int enabledComponentsSize = CollectionUtils.size(enabledComponents);
                dataOutputStream.writeInt(enabledComponentsSize);
                for (int i = 0; i < enabledComponentsSize; i++) {
                    dataOutputStream.writeUTF(enabledComponents.valueAt(i));
                }

                final ArraySet<String> disabledComponents =
                        packageManagerInternal.getDisabledComponents(pkg.getPackageName(), userId);
                final int disabledComponentsSize = CollectionUtils.size(disabledComponents);
                for (int i = 0; i < disabledComponentsSize; i++) {
                    dataOutputStream.writeUTF(disabledComponents.valueAt(i));
                }

                for (final Signature signature : pkg.getSigningDetails().getSignatures()) {
                    dataOutputStream.write(signature.toByteArray());
                }
            } catch (IOException e) {
                // Never happens for MessageDigestOutputStream and DataOutputStream.
                throw new AssertionError(e);
            }
        }, userId);
        return mdos.getDigestAsString();
    }

    private static class MessageDigestOutputStream extends OutputStream {
        private final MessageDigest mMessageDigest;

        MessageDigestOutputStream() {
            try {
                mMessageDigest = MessageDigest.getInstance("SHA256");
            } catch (NoSuchAlgorithmException e) {
                /* can't happen */
                throw new RuntimeException("Failed to create MessageDigest", e);
            }
        }

        @NonNull
        String getDigestAsString() {
            return HexEncoding.encodeToString(mMessageDigest.digest(), true /* uppercase */);
        }

        @Override
        public void write(int b) throws IOException {
            mMessageDigest.update((byte) b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            mMessageDigest.update(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            mMessageDigest.update(b, off, len);
        }
    }
}
