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

import libcore.content.type.MimeMap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Creates the framework default {@link MimeMap}, a bidirectional mapping
 * between MIME types and file extensions.
 *
 * This default mapping is loaded from data files that start with some mappings
 * recognized by IANA plus some custom extensions and overrides.
 *
 * @hide
 */
public class DefaultMimeMapFactory {

    private DefaultMimeMapFactory() {
    }

    /**
     * Creates and returns a new {@link MimeMap} instance that implements.
     * Android's default mapping between MIME types and extensions.
     */
    public static MimeMap create() {
        Class c = DefaultMimeMapFactory.class;
        // The resources are placed into the res/ path by the "mimemap-res.jar" genrule.
        return create(resourceName -> c.getResourceAsStream("/res/" + resourceName));
    }

    /**
     * Creates a {@link MimeMap} instance whose resources are loaded from the
     * InputStreams looked up in {@code resourceSupplier}.
     *
     * @hide
     */
    public static MimeMap create(Function<String, InputStream> resourceSupplier) {
        MimeMap.Builder builder = MimeMap.builder();
        // The files loaded here must be in minimized format with lines of the
        // form "mime/type ext1 ext2 ext3", i.e. no comments, no empty lines, no
        // leading/trailing whitespace and with a single space between entries on
        // each line.  See http://b/142267887
        //
        // Note: the order here matters - later entries can overwrite earlier ones
        // (except that vendor.mime.types entries are prefixed with '?' which makes
        // them never overwrite).
        parseTypes(builder, resourceSupplier, "debian.mime.types");
        parseTypes(builder, resourceSupplier, "android.mime.types");
        parseTypes(builder, resourceSupplier, "vendor.mime.types");
        return builder.build();
    }

    private static void parseTypes(MimeMap.Builder builder,
            Function<String, InputStream> resourceSupplier, String resourceName) {
        try (InputStream inputStream = Objects.requireNonNull(resourceSupplier.apply(resourceName));
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            List<String> specs = new ArrayList<>(10); // re-use for each line
            while ((line = reader.readLine()) != null) {
                specs.clear();
                // Lines are of the form "mimeSpec extSpec extSpec[...]" with a single space
                // separating them and no leading/trailing spaces and no empty lines.
                int startIdx = 0;
                do {
                    int endIdx = line.indexOf(' ', startIdx);
                    if (endIdx < 0) {
                        endIdx = line.length();
                    }
                    String spec = line.substring(startIdx, endIdx);
                    if (spec.isEmpty()) {
                        throw new IllegalArgumentException("Malformed line: " + line);
                    }
                    specs.add(spec);
                    startIdx = endIdx + 1; // skip over the space
                } while (startIdx < line.length());
                builder.addMimeMapping(specs.get(0), specs.subList(1, specs.size()));
            }
        } catch (IOException | RuntimeException e) {
            throw new RuntimeException("Failed to parse " + resourceName, e);
        }
    }

}
