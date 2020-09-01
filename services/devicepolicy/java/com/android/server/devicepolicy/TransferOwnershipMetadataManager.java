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

package com.android.server.devicepolicy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.os.Environment;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Handles reading and writing of the owner transfer metadata file.
 *
 * Before we perform a device or profile owner transfer, we save this xml file with information
 * about the current admin, target admin, user id and admin type (device owner or profile owner).
 * After {@link DevicePolicyManager#transferOwnership} completes, we delete the file. If after
 * device boot the file is still there, this indicates that the transfer was interrupted by a
 * reboot.
 *
 * Note that this class is not thread safe.
 */
class TransferOwnershipMetadataManager {
    final static String ADMIN_TYPE_DEVICE_OWNER = "device-owner";
    final static String ADMIN_TYPE_PROFILE_OWNER = "profile-owner";
    @VisibleForTesting
    final static String TAG_USER_ID = "user-id";
    @VisibleForTesting
    final static String TAG_SOURCE_COMPONENT = "source-component";
    @VisibleForTesting
    final static String TAG_TARGET_COMPONENT = "target-component";
    @VisibleForTesting
    final static String TAG_ADMIN_TYPE = "admin-type";
    private final static String TAG = TransferOwnershipMetadataManager.class.getName();
    public static final String OWNER_TRANSFER_METADATA_XML = "owner-transfer-metadata.xml";

    private final Injector mInjector;

    TransferOwnershipMetadataManager() {
        this(new Injector());
    }

    @VisibleForTesting
    TransferOwnershipMetadataManager(Injector injector) {
        mInjector = injector;
    }

    boolean saveMetadataFile(Metadata params) {
        final File transferOwnershipMetadataFile = new File(mInjector.getOwnerTransferMetadataDir(),
                OWNER_TRANSFER_METADATA_XML);
        final AtomicFile atomicFile = new AtomicFile(transferOwnershipMetadataFile);
        FileOutputStream stream = null;
        try {
            stream = atomicFile.startWrite();
            final XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(stream, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            insertSimpleTag(serializer, TAG_USER_ID, Integer.toString(params.userId));
            insertSimpleTag(serializer,
                    TAG_SOURCE_COMPONENT, params.sourceComponent.flattenToString());
            insertSimpleTag(serializer,
                    TAG_TARGET_COMPONENT, params.targetComponent.flattenToString());
            insertSimpleTag(serializer, TAG_ADMIN_TYPE, params.adminType);
            serializer.endDocument();
            atomicFile.finishWrite(stream);
            return true;
        } catch (IOException e) {
            Slog.e(TAG, "Caught exception while trying to save Owner Transfer "
                    + "Params to file " + transferOwnershipMetadataFile, e);
            transferOwnershipMetadataFile.delete();
            atomicFile.failWrite(stream);
        }
        return false;
    }

    private void insertSimpleTag(XmlSerializer serializer, String tagName, String value)
            throws IOException {
        serializer.startTag(null, tagName);
        serializer.text(value);
        serializer.endTag(null, tagName);
    }

    @Nullable
    Metadata loadMetadataFile() {
        final File transferOwnershipMetadataFile =
                new File(mInjector.getOwnerTransferMetadataDir(), OWNER_TRANSFER_METADATA_XML);
        if (!transferOwnershipMetadataFile.exists()) {
            return null;
        }
        Slog.d(TAG, "Loading TransferOwnershipMetadataManager from "
                + transferOwnershipMetadataFile);
        try (FileInputStream stream = new FileInputStream(transferOwnershipMetadataFile)) {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, null);
            return parseMetadataFile(parser);
        } catch (IOException | XmlPullParserException | IllegalArgumentException e) {
            Slog.e(TAG, "Caught exception while trying to load the "
                    + "owner transfer params from file " + transferOwnershipMetadataFile, e);
        }
        return null;
    }

    private Metadata parseMetadataFile(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int type;
        final int outerDepth = parser.getDepth();
        int userId = 0;
        String adminComponent = null;
        String targetComponent = null;
        String adminType = null;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            switch (parser.getName()) {
                case TAG_USER_ID:
                    parser.next();
                    userId = Integer.parseInt(parser.getText());
                    break;
                case TAG_TARGET_COMPONENT:
                    parser.next();
                    targetComponent = parser.getText();
                    break;
                case TAG_SOURCE_COMPONENT:
                    parser.next();
                    adminComponent = parser.getText();
                    break;
                case TAG_ADMIN_TYPE:
                    parser.next();
                    adminType = parser.getText();
                    break;
            }
        }
        return new Metadata(adminComponent, targetComponent, userId, adminType);
    }

    void deleteMetadataFile() {
        new File(mInjector.getOwnerTransferMetadataDir(), OWNER_TRANSFER_METADATA_XML).delete();
    }

    boolean metadataFileExists() {
        return new File(mInjector.getOwnerTransferMetadataDir(),
                OWNER_TRANSFER_METADATA_XML).exists();
    }

    static class Metadata {
        final int userId;
        final ComponentName sourceComponent;
        final ComponentName targetComponent;
        final String adminType;

        Metadata(@NonNull ComponentName sourceComponent, @NonNull ComponentName targetComponent,
                @NonNull int userId, @NonNull String adminType) {
            this.sourceComponent = sourceComponent;
            this.targetComponent = targetComponent;
            Objects.requireNonNull(sourceComponent);
            Objects.requireNonNull(targetComponent);
            Preconditions.checkStringNotEmpty(adminType);
            this.userId = userId;
            this.adminType = adminType;
        }

        Metadata(@NonNull String flatSourceComponent, @NonNull String flatTargetComponent,
                @NonNull int userId, @NonNull String adminType) {
            this(unflattenComponentUnchecked(flatSourceComponent),
                    unflattenComponentUnchecked(flatTargetComponent), userId, adminType);
        }

        private static ComponentName unflattenComponentUnchecked(String flatComponent) {
            Objects.requireNonNull(flatComponent);
            return ComponentName.unflattenFromString(flatComponent);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Metadata)) {
                return false;
            }
            Metadata params = (Metadata) obj;

            return userId == params.userId
                    && sourceComponent.equals(params.sourceComponent)
                    && targetComponent.equals(params.targetComponent)
                    && TextUtils.equals(adminType, params.adminType);
        }

        @Override
        public int hashCode() {
            int hashCode = 1;
            hashCode = 31 * hashCode + userId;
            hashCode = 31 * hashCode + sourceComponent.hashCode();
            hashCode = 31 * hashCode + targetComponent.hashCode();
            hashCode = 31 * hashCode + adminType.hashCode();
            return hashCode;
        }
    }

    @VisibleForTesting
    static class Injector {
        public File getOwnerTransferMetadataDir() {
            return Environment.getDataSystemDirectory();
        }
    }
}
