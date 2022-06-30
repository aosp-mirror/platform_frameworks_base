/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.DevicePolicyDrawableResource;
import android.app.admin.DevicePolicyResources;
import android.app.admin.DevicePolicyStringResource;
import android.app.admin.ParcelableResource;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.Log;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A helper class for {@link DevicePolicyManagerService} to store/retrieve updated device
 * management resources.
 */
class DeviceManagementResourcesProvider {
    private static final String TAG = "DevicePolicyManagerService";

    private static final String UPDATED_RESOURCES_XML = "updated_resources.xml";
    private static final String TAG_ROOT = "root";
    private static final String TAG_DRAWABLE_STYLE_ENTRY = "drawable-style-entry";
    private static final String TAG_DRAWABLE_SOURCE_ENTRY = "drawable-source-entry";
    private static final String ATTR_DRAWABLE_STYLE = "drawable-style";
    private static final String ATTR_DRAWABLE_SOURCE = "drawable-source";
    private static final String ATTR_DRAWABLE_ID = "drawable-id";
    private static final String TAG_STRING_ENTRY = "string-entry";
    private static final String ATTR_SOURCE_ID = "source-id";

    /**
     * Map of <drawable_id, <style_id, resource_value>>
     */
    private final Map<String, Map<String, ParcelableResource>>
            mUpdatedDrawablesForStyle = new HashMap<>();

    /**
     * Map of <drawable_id, <source_id, <style_id, resource_value>>>
     */
    private final Map<String, Map<String, Map<String, ParcelableResource>>>
            mUpdatedDrawablesForSource = new HashMap<>();

    /**
     * Map of <string_id, resource_value>
     */
    private final Map<String, ParcelableResource> mUpdatedStrings = new HashMap<>();

    private final Object mLock = new Object();
    private final Injector mInjector;

    DeviceManagementResourcesProvider() {
        this(new Injector());
    }

    DeviceManagementResourcesProvider(Injector injector) {
        mInjector = requireNonNull(injector);
    }

    /**
     * Returns {@code false} if no resources were updated.
     */
    boolean updateDrawables(@NonNull List<DevicePolicyDrawableResource> drawables) {
        boolean updated = false;
        for (int i = 0; i < drawables.size(); i++) {
            String drawableId = drawables.get(i).getDrawableId();
            String drawableStyle = drawables.get(i).getDrawableStyle();
            String drawableSource = drawables.get(i).getDrawableSource();
            ParcelableResource resource = drawables.get(i).getResource();

            Objects.requireNonNull(drawableId, "drawableId must be provided.");
            Objects.requireNonNull(drawableStyle, "drawableStyle must be provided.");
            Objects.requireNonNull(drawableSource, "drawableSource must be provided.");
            Objects.requireNonNull(resource, "ParcelableResource must be provided.");

            if (DevicePolicyResources.UNDEFINED.equals(drawableSource)) {
                updated |= updateDrawable(drawableId, drawableStyle, resource);
            } else {
                updated |= updateDrawableForSource(
                        drawableId, drawableSource, drawableStyle, resource);
            }
        }
        if (!updated) {
            return false;
        }
        synchronized (mLock) {
            write();
            return true;
        }
    }

    private boolean updateDrawable(
            String drawableId, String drawableStyle, ParcelableResource updatableResource) {
        synchronized (mLock) {
            if (!mUpdatedDrawablesForStyle.containsKey(drawableId)) {
                mUpdatedDrawablesForStyle.put(drawableId, new HashMap<>());
            }
            ParcelableResource current = mUpdatedDrawablesForStyle.get(drawableId).get(
                    drawableStyle);
            if (updatableResource.equals(current)) {
                return false;
            }
            mUpdatedDrawablesForStyle.get(drawableId).put(drawableStyle, updatableResource);
            return true;
        }
    }

    private boolean updateDrawableForSource(
            String drawableId, String drawableSource, String drawableStyle,
            ParcelableResource updatableResource) {
        synchronized (mLock) {
            if (!mUpdatedDrawablesForSource.containsKey(drawableId)) {
                mUpdatedDrawablesForSource.put(drawableId, new HashMap<>());
            }
            Map<String, Map<String, ParcelableResource>> drawablesForId =
                    mUpdatedDrawablesForSource.get(drawableId);
            if (!drawablesForId.containsKey(drawableSource)) {
                mUpdatedDrawablesForSource.get(drawableId).put(drawableSource, new HashMap<>());
            }
            ParcelableResource current = drawablesForId.get(drawableSource).get(drawableStyle);
            if (updatableResource.equals(current)) {
                return false;
            }
            drawablesForId.get(drawableSource).put(drawableStyle, updatableResource);
            return true;
        }
    }

    /**
     * Returns {@code false} if no resources were removed.
     */
    boolean removeDrawables(@NonNull List<String> drawableIds) {
        synchronized (mLock) {
            boolean removed = false;
            for (int i = 0; i < drawableIds.size(); i++) {
                String drawableId = drawableIds.get(i);
                removed |= mUpdatedDrawablesForStyle.remove(drawableId) != null
                        || mUpdatedDrawablesForSource.remove(drawableId) != null;
            }
            if (!removed) {
                return false;
            }
            write();
            return true;
        }
    }

    @Nullable
    ParcelableResource getDrawable(String drawableId, String drawableStyle, String drawableSource) {
        synchronized (mLock) {
            ParcelableResource resource = getDrawableForSourceLocked(
                    drawableId, drawableStyle, drawableSource);
            if (resource != null) {
                return resource;
            }
            if (!mUpdatedDrawablesForStyle.containsKey(drawableId)) {
                return null;
            }
            return mUpdatedDrawablesForStyle.get(drawableId).get(drawableStyle);
        }
    }

    @Nullable
    ParcelableResource getDrawableForSourceLocked(
            String drawableId, String drawableStyle, String drawableSource) {
        if (!mUpdatedDrawablesForSource.containsKey(drawableId)) {
            return null;
        }
        if (!mUpdatedDrawablesForSource.get(drawableId).containsKey(drawableSource)) {
            return null;
        }
        return mUpdatedDrawablesForSource.get(drawableId).get(drawableSource).get(drawableStyle);
    }

    /**
     * Returns {@code false} if no resources were updated.
     */
    boolean updateStrings(@NonNull List<DevicePolicyStringResource> strings) {
        boolean updated = false;
        for (int i = 0; i < strings.size(); i++) {
            String stringId = strings.get(i).getStringId();
            ParcelableResource resource = strings.get(i).getResource();

            Objects.requireNonNull(stringId, "stringId must be provided.");
            Objects.requireNonNull(resource, "ParcelableResource must be provided.");

            updated |= updateString(stringId, resource);
        }
        if (!updated) {
            return false;
        }
        synchronized (mLock) {
            write();
            return true;
        }
    }

    private boolean updateString(String stringId, ParcelableResource updatableResource) {
        synchronized (mLock) {
            ParcelableResource current = mUpdatedStrings.get(stringId);
            if (updatableResource.equals(current)) {
                return false;
            }
            mUpdatedStrings.put(stringId, updatableResource);
            return true;
        }
    }

    /**
     * Returns {@code false} if no resources were removed.
     */
    boolean removeStrings(@NonNull List<String> stringIds) {
        synchronized (mLock) {
            boolean removed = false;
            for (int i = 0; i < stringIds.size(); i++) {
                String stringId = stringIds.get(i);
                removed |= mUpdatedStrings.remove(stringId) != null;
            }
            if (!removed) {
                return false;
            }
            write();
            return true;
        }
    }

    @Nullable
    ParcelableResource getString(String stringId) {
        synchronized (mLock) {
            return mUpdatedStrings.get(stringId);
        }
    }

    private void write() {
        Log.d(TAG, "Writing updated resources to file.");
        new ResourcesReaderWriter().writeToFileLocked();
    }

    void load() {
        synchronized (mLock) {
            new ResourcesReaderWriter().readFromFileLocked();
        }
    }

    private File getResourcesFile() {
        return new File(mInjector.environmentGetDataSystemDirectory(), UPDATED_RESOURCES_XML);
    }

    private class ResourcesReaderWriter {
        private final File mFile;
        private ResourcesReaderWriter() {
            mFile = getResourcesFile();
        }

        void writeToFileLocked() {
            Log.d(TAG, "Writing to " + mFile);

            AtomicFile f = new AtomicFile(mFile);
            FileOutputStream outputStream = null;
            try {
                outputStream = f.startWrite();
                TypedXmlSerializer out = Xml.resolveSerializer(outputStream);

                // Root tag
                out.startDocument(null, true);
                out.startTag(null, TAG_ROOT);

                // Actual content
                writeInner(out);

                // Close root
                out.endTag(null, TAG_ROOT);
                out.endDocument();
                out.flush();

                // Commit the content.
                f.finishWrite(outputStream);
                outputStream = null;

            } catch (IOException e) {
                Log.e(TAG, "Exception when writing", e);
                if (outputStream != null) {
                    f.failWrite(outputStream);
                }
            }
        }

        void readFromFileLocked() {
            if (!mFile.exists()) {
                Log.d(TAG, "" + mFile + " doesn't exist");
                return;
            }

            Log.d(TAG, "Reading from " + mFile);
            AtomicFile f = new AtomicFile(mFile);
            InputStream input = null;
            try {
                input = f.openRead();
                TypedXmlPullParser parser = Xml.resolvePullParser(input);

                int type;
                int depth = 0;
                while ((type = parser.next()) != TypedXmlPullParser.END_DOCUMENT) {
                    switch (type) {
                        case TypedXmlPullParser.START_TAG:
                            depth++;
                            break;
                        case TypedXmlPullParser.END_TAG:
                            depth--;
                            // fallthrough
                        default:
                            continue;
                    }
                    // Check the root tag
                    String tag = parser.getName();
                    if (depth == 1) {
                        if (!TAG_ROOT.equals(tag)) {
                            Log.e(TAG, "Invalid root tag: " + tag);
                            return;
                        }
                        continue;
                    }
                    // readInner() will only see START_TAG at depth >= 2.
                    if (!readInner(parser, depth, tag)) {
                        return; // Error
                    }
                }
            } catch (XmlPullParserException | IOException e) {
                Log.e(TAG, "Error parsing resources file", e);
            } finally {
                IoUtils.closeQuietly(input);
            }
        }

        void writeInner(TypedXmlSerializer out) throws IOException {
            writeDrawablesForStylesInner(out);
            writeDrawablesForSourcesInner(out);
            writeStringsInner(out);
        }

        private void writeDrawablesForStylesInner(TypedXmlSerializer out) throws IOException {
            if (mUpdatedDrawablesForStyle != null && !mUpdatedDrawablesForStyle.isEmpty()) {
                for (Map.Entry<String, Map<String, ParcelableResource>> drawableEntry
                        : mUpdatedDrawablesForStyle.entrySet()) {
                    for (Map.Entry<String, ParcelableResource> styleEntry
                            : drawableEntry.getValue().entrySet()) {
                        out.startTag(/* namespace= */ null, TAG_DRAWABLE_STYLE_ENTRY);
                        out.attribute(
                                /* namespace= */ null, ATTR_DRAWABLE_ID, drawableEntry.getKey());
                        out.attribute(
                                /* namespace= */ null,
                                ATTR_DRAWABLE_STYLE,
                                styleEntry.getKey());
                        styleEntry.getValue().writeToXmlFile(out);
                        out.endTag(/* namespace= */ null, TAG_DRAWABLE_STYLE_ENTRY);
                    }
                }
            }
        }

        private void writeDrawablesForSourcesInner(TypedXmlSerializer out) throws IOException {
            if (mUpdatedDrawablesForSource != null && !mUpdatedDrawablesForSource.isEmpty()) {
                for (Map.Entry<String, Map<String, Map<String, ParcelableResource>>> drawableEntry
                        : mUpdatedDrawablesForSource.entrySet()) {
                    for (Map.Entry<String, Map<String, ParcelableResource>> sourceEntry
                            : drawableEntry.getValue().entrySet()) {
                        for (Map.Entry<String, ParcelableResource> styleEntry
                                : sourceEntry.getValue().entrySet()) {
                            out.startTag(/* namespace= */ null, TAG_DRAWABLE_SOURCE_ENTRY);
                            out.attribute(/* namespace= */ null, ATTR_DRAWABLE_ID,
                                    drawableEntry.getKey());
                            out.attribute(/* namespace= */ null, ATTR_DRAWABLE_SOURCE,
                                    sourceEntry.getKey());
                            out.attribute(/* namespace= */ null, ATTR_DRAWABLE_STYLE,
                                    styleEntry.getKey());
                            styleEntry.getValue().writeToXmlFile(out);
                            out.endTag(/* namespace= */ null, TAG_DRAWABLE_SOURCE_ENTRY);
                        }
                    }
                }
            }
        }

        private void writeStringsInner(TypedXmlSerializer out) throws IOException {
            if (mUpdatedStrings != null && !mUpdatedStrings.isEmpty()) {
                for (Map.Entry<String, ParcelableResource> entry
                        : mUpdatedStrings.entrySet()) {
                    out.startTag(/* namespace= */ null, TAG_STRING_ENTRY);
                    out.attribute(
                            /* namespace= */ null,
                            ATTR_SOURCE_ID,
                            entry.getKey());
                    entry.getValue().writeToXmlFile(out);
                    out.endTag(/* namespace= */ null, TAG_STRING_ENTRY);
                }
            }
        }

        private boolean readInner(TypedXmlPullParser parser, int depth, String tag)
                throws XmlPullParserException, IOException {
            if (depth > 2) {
                return true; // Ignore
            }
            switch (tag) {
                case TAG_DRAWABLE_STYLE_ENTRY: {
                    String id = parser.getAttributeValue(/* namespace= */ null, ATTR_DRAWABLE_ID);
                    String style = parser.getAttributeValue(
                            /* namespace= */ null, ATTR_DRAWABLE_STYLE);
                    ParcelableResource resource = ParcelableResource.createFromXml(parser);
                    if (!mUpdatedDrawablesForStyle.containsKey(id)) {
                        mUpdatedDrawablesForStyle.put(id, new HashMap<>());
                    }
                    mUpdatedDrawablesForStyle.get(id).put(style, resource);
                    break;
                }
                case TAG_DRAWABLE_SOURCE_ENTRY: {
                    String id = parser.getAttributeValue(/* namespace= */ null, ATTR_DRAWABLE_ID);
                    String source = parser.getAttributeValue(
                            /* namespace= */ null, ATTR_DRAWABLE_SOURCE);
                    String style = parser.getAttributeValue(
                            /* namespace= */ null, ATTR_DRAWABLE_STYLE);
                    ParcelableResource resource = ParcelableResource.createFromXml(parser);
                    if (!mUpdatedDrawablesForSource.containsKey(id)) {
                        mUpdatedDrawablesForSource.put(id, new HashMap<>());
                    }
                    if (!mUpdatedDrawablesForSource.get(id).containsKey(source)) {
                        mUpdatedDrawablesForSource.get(id).put(source, new HashMap<>());
                    }
                    mUpdatedDrawablesForSource.get(id).get(source).put(style, resource);
                    break;
                }
                case TAG_STRING_ENTRY: {
                    String id = parser.getAttributeValue(/* namespace= */ null, ATTR_SOURCE_ID);
                    mUpdatedStrings.put(id, ParcelableResource.createFromXml(parser));
                    break;
                }
                default: {
                    Log.e(TAG, "Unexpected tag: " + tag);
                    return false;
                }
            }
            return true;
        }
    }

    public static class Injector {
        File environmentGetDataSystemDirectory() {
            return Environment.getDataSystemDirectory();
        }
    }
}
