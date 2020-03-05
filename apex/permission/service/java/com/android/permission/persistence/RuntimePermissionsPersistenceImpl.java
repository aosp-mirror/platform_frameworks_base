/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.permission.persistence;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ApexEnvironment;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Persistence implementation for runtime permissions.
 *
 * TODO(b/147914847): Remove @hide when it becomes the default.
 * @hide
 */
public class RuntimePermissionsPersistenceImpl implements RuntimePermissionsPersistence {

    private static final String LOG_TAG = RuntimePermissionsPersistenceImpl.class.getSimpleName();

    private static final String APEX_MODULE_NAME = "com.android.permission";

    private static final String RUNTIME_PERMISSIONS_FILE_NAME = "runtime-permissions.xml";

    private static final String TAG_PACKAGE = "package";
    private static final String TAG_PERMISSION = "permission";
    private static final String TAG_RUNTIME_PERMISSIONS = "runtime-permissions";
    private static final String TAG_SHARED_USER = "shared-user";

    private static final String ATTRIBUTE_FINGERPRINT = "fingerprint";
    private static final String ATTRIBUTE_FLAGS = "flags";
    private static final String ATTRIBUTE_GRANTED = "granted";
    private static final String ATTRIBUTE_NAME = "name";
    private static final String ATTRIBUTE_VERSION = "version";

    @Nullable
    @Override
    public RuntimePermissionsState readAsUser(@NonNull UserHandle user) {
        File file = getFile(user);
        try (FileInputStream inputStream = new AtomicFile(file).openRead()) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(inputStream, null);
            return parseXml(parser);
        } catch (FileNotFoundException e) {
            Log.i(LOG_TAG, "runtime-permissions.xml not found");
            return null;
        } catch (XmlPullParserException | IOException e) {
            throw new IllegalStateException("Failed to read runtime-permissions.xml: " + file , e);
        }
    }

    @NonNull
    private static RuntimePermissionsState parseXml(@NonNull XmlPullParser parser)
            throws IOException, XmlPullParserException {
        int type;
        int depth;
        int innerDepth = parser.getDepth() + 1;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && ((depth = parser.getDepth()) >= innerDepth || type != XmlPullParser.END_TAG)) {
            if (depth > innerDepth || type != XmlPullParser.START_TAG) {
                continue;
            }

            if (parser.getName().equals(TAG_RUNTIME_PERMISSIONS)) {
                return parseRuntimePermissions(parser);
            }
        }
        throw new IllegalStateException("Missing <" + TAG_RUNTIME_PERMISSIONS
                + "> in runtime-permissions.xml");
    }

    @NonNull
    private static RuntimePermissionsState parseRuntimePermissions(@NonNull XmlPullParser parser)
            throws IOException, XmlPullParserException {
        String versionValue = parser.getAttributeValue(null, ATTRIBUTE_VERSION);
        int version = versionValue != null ? Integer.parseInt(versionValue)
                : RuntimePermissionsState.NO_VERSION;
        String fingerprint = parser.getAttributeValue(null, ATTRIBUTE_FINGERPRINT);

        Map<String, List<RuntimePermissionsState.PermissionState>> packagePermissions =
                new ArrayMap<>();
        Map<String, List<RuntimePermissionsState.PermissionState>> sharedUserPermissions =
                new ArrayMap<>();
        int type;
        int depth;
        int innerDepth = parser.getDepth() + 1;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && ((depth = parser.getDepth()) >= innerDepth || type != XmlPullParser.END_TAG)) {
            if (depth > innerDepth || type != XmlPullParser.START_TAG) {
                continue;
            }

            switch (parser.getName()) {
                case TAG_PACKAGE: {
                    String packageName = parser.getAttributeValue(null, ATTRIBUTE_NAME);
                    List<RuntimePermissionsState.PermissionState> permissions = parsePermissions(
                            parser);
                    packagePermissions.put(packageName, permissions);
                    break;
                }
                case TAG_SHARED_USER: {
                    String sharedUserName = parser.getAttributeValue(null, ATTRIBUTE_NAME);
                    List<RuntimePermissionsState.PermissionState> permissions = parsePermissions(
                            parser);
                    sharedUserPermissions.put(sharedUserName, permissions);
                    break;
                }
            }
        }

        return new RuntimePermissionsState(version, fingerprint, packagePermissions,
                sharedUserPermissions);
    }

    @NonNull
    private static List<RuntimePermissionsState.PermissionState> parsePermissions(
            @NonNull XmlPullParser parser) throws IOException, XmlPullParserException {
        List<RuntimePermissionsState.PermissionState> permissions = new ArrayList<>();
        int type;
        int depth;
        int innerDepth = parser.getDepth() + 1;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && ((depth = parser.getDepth()) >= innerDepth || type != XmlPullParser.END_TAG)) {
            if (depth > innerDepth || type != XmlPullParser.START_TAG) {
                continue;
            }

            if (parser.getName().equals(TAG_PERMISSION)) {
                String name = parser.getAttributeValue(null, ATTRIBUTE_NAME);
                boolean granted = Boolean.parseBoolean(parser.getAttributeValue(null,
                        ATTRIBUTE_GRANTED));
                int flags = Integer.parseInt(parser.getAttributeValue(null,
                        ATTRIBUTE_FLAGS), 16);
                RuntimePermissionsState.PermissionState permission =
                        new RuntimePermissionsState.PermissionState(name, granted, flags);
                permissions.add(permission);
            }
        }
        return permissions;
    }

    @Override
    public void writeAsUser(@NonNull RuntimePermissionsState runtimePermissions,
            @NonNull UserHandle user) {
        File file = getFile(user);
        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream outputStream = null;
        try {
            outputStream = atomicFile.startWrite();

            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(outputStream, StandardCharsets.UTF_8.name());
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startDocument(null, true);

            serializeRuntimePermissions(serializer, runtimePermissions);

            serializer.endDocument();
            atomicFile.finishWrite(outputStream);
        } catch (Exception e) {
            Log.wtf(LOG_TAG, "Failed to write runtime-permissions.xml, restoring backup: " + file,
                    e);
            atomicFile.failWrite(outputStream);
        } finally {
            IoUtils.closeQuietly(outputStream);
        }
    }

    private static void serializeRuntimePermissions(@NonNull XmlSerializer serializer,
            @NonNull RuntimePermissionsState runtimePermissions) throws IOException {
        serializer.startTag(null, TAG_RUNTIME_PERMISSIONS);

        int version = runtimePermissions.getVersion();
        serializer.attribute(null, ATTRIBUTE_VERSION, Integer.toString(version));
        String fingerprint = runtimePermissions.getFingerprint();
        if (fingerprint != null) {
            serializer.attribute(null, ATTRIBUTE_FINGERPRINT, fingerprint);
        }

        for (Map.Entry<String, List<RuntimePermissionsState.PermissionState>> entry
                : runtimePermissions.getPackagePermissions().entrySet()) {
            String packageName = entry.getKey();
            List<RuntimePermissionsState.PermissionState> permissions = entry.getValue();

            serializer.startTag(null, TAG_PACKAGE);
            serializer.attribute(null, ATTRIBUTE_NAME, packageName);
            serializePermissions(serializer, permissions);
            serializer.endTag(null, TAG_PACKAGE);
        }

        for (Map.Entry<String, List<RuntimePermissionsState.PermissionState>> entry
                : runtimePermissions.getSharedUserPermissions().entrySet()) {
            String sharedUserName = entry.getKey();
            List<RuntimePermissionsState.PermissionState> permissions = entry.getValue();

            serializer.startTag(null, TAG_SHARED_USER);
            serializer.attribute(null, ATTRIBUTE_NAME, sharedUserName);
            serializePermissions(serializer, permissions);
            serializer.endTag(null, TAG_SHARED_USER);
        }

        serializer.endTag(null, TAG_RUNTIME_PERMISSIONS);
    }

    private static void serializePermissions(@NonNull XmlSerializer serializer,
            @NonNull List<RuntimePermissionsState.PermissionState> permissions) throws IOException {
        int permissionsSize = permissions.size();
        for (int i = 0; i < permissionsSize; i++) {
            RuntimePermissionsState.PermissionState permissionState = permissions.get(i);

            serializer.startTag(null, TAG_PERMISSION);
            serializer.attribute(null, ATTRIBUTE_NAME, permissionState.getName());
            serializer.attribute(null, ATTRIBUTE_GRANTED, Boolean.toString(
                    permissionState.isGranted() && (permissionState.getFlags()
                            & PackageManager.FLAG_PERMISSION_ONE_TIME) == 0));
            serializer.attribute(null, ATTRIBUTE_FLAGS, Integer.toHexString(
                    permissionState.getFlags()));
            serializer.endTag(null, TAG_PERMISSION);
        }
    }

    @Override
    public void deleteAsUser(@NonNull UserHandle user) {
        getFile(user).delete();
    }

    @NonNull
    private static File getFile(@NonNull UserHandle user) {
        ApexEnvironment apexEnvironment = ApexEnvironment.getApexEnvironment(APEX_MODULE_NAME);
        File dataDirectory = apexEnvironment.getDeviceProtectedDataDirForUser(user);
        return new File(dataDirectory, RUNTIME_PERMISSIONS_FILE_NAME);
    }
}
