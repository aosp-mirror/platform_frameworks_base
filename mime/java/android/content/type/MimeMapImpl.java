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
import java.util.Arrays;
import java.util.List;
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
public class MimeMapImpl {

    /**
     * Creates and returns a new {@link MimeMapImpl} instance that implements.
     * Android's default mapping between MIME types and extensions.
     */
    public static MimeMap createDefaultInstance() {
        return parseFromResources("/mime.types", "/android.mime.types");
    }

    private static final Pattern SPLIT_PATTERN = Pattern.compile("\\s+");

    static MimeMap parseFromResources(String... resourceNames) {
        MimeMap.Builder builder = MimeMap.builder();
        for (String resourceName : resourceNames) {
            parseTypes(builder, resourceName);
        }
        return builder.build();
    }

    private static void parseTypes(MimeMap.Builder builder, String resource) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(MimeMapImpl.class.getResourceAsStream(resource)))) {
            String line;
            while ((line = r.readLine()) != null) {
                int commentPos = line.indexOf('#');
                if (commentPos >= 0) {
                    line = line.substring(0, commentPos);
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                List<String> specs = Arrays.asList(SPLIT_PATTERN.split(line));
                builder.put(specs.get(0), specs.subList(1, specs.size()));
            }
        } catch (IOException | RuntimeException e) {
            throw new RuntimeException("Failed to parse " + resource, e);
        }
    }

}
