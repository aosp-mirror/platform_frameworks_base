/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.content.type;

import libcore.net.MimeMap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Default implementation of {@link MimeMap}, a bidirectional mapping between
 * MIME types and file extensions.
 *
 * This default mapping is loaded from data files that start with some mappings
 * recognized by IANA plus some custom extensions and overrides.
 *
 * @hide
 */
public class MimeMapImpl extends MimeMap {

    /**
     * Creates and returns a new {@link MimeMapImpl} instance that implements.
     * Android's default mapping between MIME types and extensions.
     */
    public static MimeMapImpl createDefaultInstance() {
        return parseFromResources("/mime.types", "/android.mime.types");
    }

    private static final Pattern SPLIT_PATTERN = Pattern.compile("\\s+");

    /**
     * Note: These maps only contain lowercase keys/values, regarded as the
     * {@link #toLowerCase(String) canonical form}.
     *
     * <p>This is the case for both extensions and MIME types. The mime.types
     * data file contains examples of mixed-case MIME types, but some applications
     * use the lowercase version of these same types. RFC 2045 section 2 states
     * that MIME types are case insensitive.
     */
    private final Map<String, String> mMimeTypeToExtension;
    private final Map<String, String> mExtensionToMimeType;

    public MimeMapImpl(Map<String, String> mimeTypeToExtension,
            Map<String, String> extensionToMimeType) {
        this.mMimeTypeToExtension = new HashMap<>(mimeTypeToExtension);
        for (Map.Entry<String, String> entry : mimeTypeToExtension.entrySet()) {
            checkValidMimeType(entry.getKey());
            checkValidExtension(entry.getValue());
        }
        this.mExtensionToMimeType = new HashMap<>(extensionToMimeType);
        for (Map.Entry<String, String> entry : extensionToMimeType.entrySet()) {
            checkValidExtension(entry.getKey());
            checkValidMimeType(entry.getValue());
        }
    }

    private static void checkValidMimeType(String s) {
        if (MimeMap.isNullOrEmpty(s) || !s.equals(MimeMap.toLowerCase(s))) {
            throw new IllegalArgumentException("Invalid MIME type: " + s);
        }
    }

    private static void checkValidExtension(String s) {
        if (MimeMap.isNullOrEmpty(s) || !s.equals(MimeMap.toLowerCase(s))) {
            throw new IllegalArgumentException("Invalid extension: " + s);
        }
    }

    static MimeMapImpl parseFromResources(String... resourceNames) {
        Map<String, String> mimeTypeToExtension = new HashMap<>();
        Map<String, String> extensionToMimeType = new HashMap<>();
        for (String resourceName : resourceNames) {
            parseTypes(mimeTypeToExtension, extensionToMimeType, resourceName);
        }
        return new MimeMapImpl(mimeTypeToExtension, extensionToMimeType);
    }

    /**
     * An element of a *mime.types file: A MIME type or an extension, with an optional
     * prefix of "?" (if not overriding an earlier value).
     */
    private static class Element {
        public final boolean keepExisting;
        public final String s;

        Element(boolean keepExisting, String value) {
            this.keepExisting = keepExisting;
            this.s = toLowerCase(value);
            if (value.isEmpty()) {
                throw new IllegalArgumentException();
            }
        }

        public String toString() {
            return keepExisting ? ("?" + s) : s;
        }
    }

    private static String maybePut(Map<String, String> map, Element keyElement, String value) {
        if (keyElement.keepExisting) {
            return map.putIfAbsent(keyElement.s, value);
        } else {
            return map.put(keyElement.s, value);
        }
    }

    private static void parseTypes(Map<String, String> mimeTypeToExtension,
            Map<String, String> extensionToMimeType, String resource) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(MimeMapImpl.class.getResourceAsStream(resource)))) {
            String line;
            while ((line = r.readLine()) != null) {
                int commentPos = line.indexOf('#');
                if (commentPos >= 0) {
                    line = line.substring(0, commentPos);
                }
                line = line.trim();
                // The first time a MIME type is encountered it is mapped to the first extension
                // listed in its line. The first time an extension is encountered it is mapped
                // to the MIME type.
                //
                // When encountering a previously seen MIME type or extension, then by default
                // the later ones override earlier mappings (put() semantics); however if a MIME
                // type or extension is prefixed with '?' then any earlier mapping _from_ that
                // MIME type / extension is kept (putIfAbsent() semantics).
                final String[] split = SPLIT_PATTERN.split(line);
                if (split.length <= 1) {
                    // Need mimeType + at least one extension to make a mapping.
                    // "mime.types" files may also contain lines with just a mimeType without
                    // an extension but we skip them as they provide no mapping info.
                    continue;
                }
                List<Element> lineElements = new ArrayList<>(split.length);
                for (String s : split) {
                    boolean keepExisting = s.startsWith("?");
                    if (keepExisting) {
                        s = s.substring(1);
                    }
                    if (s.isEmpty()) {
                        throw new IllegalArgumentException("Invalid entry in '" + line + "'");
                    }
                    lineElements.add(new Element(keepExisting, s));
                }

                // MIME type -> first extension (one mapping)
                // This will override any earlier mapping from this MIME type to another
                // extension, unless this MIME type was prefixed with '?'.
                Element mimeElement = lineElements.get(0);
                List<Element> extensionElements = lineElements.subList(1, lineElements.size());
                String firstExtension = extensionElements.get(0).s;
                maybePut(mimeTypeToExtension, mimeElement, firstExtension);

                // extension -> MIME type (one or more mappings).
                // This will override any earlier mapping from this extension to another
                // MIME type, unless this extension was prefixed with '?'.
                for (Element extensionElement : extensionElements) {
                    maybePut(extensionToMimeType, extensionElement, mimeElement.s);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new RuntimeException("Failed to parse " + resource, e);
        }
    }

    @Override
    protected String guessExtensionFromLowerCaseMimeType(String mimeType) {
        return mMimeTypeToExtension.get(mimeType);
    }

    @Override
    protected String guessMimeTypeFromLowerCaseExtension(String extension) {
        return mExtensionToMimeType.get(extension);
    }
}
