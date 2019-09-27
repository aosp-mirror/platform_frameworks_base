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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

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

    private static final Pattern SPLIT_PATTERN = Pattern.compile("\\s+");

    /**
     * Creates a {@link MimeMap} instance whose resources are loaded from the
     * InputStreams looked up in {@code resourceSupplier}.
     *
     * @hide
     */
    public static MimeMap create(Function<String, InputStream> resourceSupplier) {
        MimeMap.Builder builder = MimeMap.builder();
        parseTypes(builder, true, resourceSupplier, "mime.types");
        parseTypes(builder, true, resourceSupplier, "android.mime.types");
        parseTypes(builder, false, resourceSupplier, "vendor.mime.types");
        return builder.build();
    }

    private static void parseTypes(MimeMap.Builder builder, boolean allowOverwrite,
            Function<String, InputStream> resourceSupplier, String resourceName) {
        try (InputStream inputStream = Objects.requireNonNull(resourceSupplier.apply(resourceName));
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int commentPos = line.indexOf('#');
                if (commentPos >= 0) {
                    line = line.substring(0, commentPos);
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                List<String> specs = Arrays.asList(SPLIT_PATTERN.split(line));
                if (!allowOverwrite) {
                    // Pretend that the mimeType and each file extension listed in the line
                    // carries a "?" prefix, which means that it can add new mappings but
                    // not modify existing mappings (putIfAbsent() semantics).
                    specs = ensurePrefix("?", specs);
                }
                builder.put(specs.get(0), specs.subList(1, specs.size()));
            }
        } catch (IOException | RuntimeException e) {
            throw new RuntimeException("Failed to parse " + resourceName, e);
        }
    }

    private static List<String> ensurePrefix(String prefix, List<String> strings) {
        List<String> result = new ArrayList<>(strings.size());
        for (String s : strings) {
            if (!s.startsWith(prefix)) {
                s = prefix + s;
            }
            result.add(s);
        }
        return result;
    }

}
