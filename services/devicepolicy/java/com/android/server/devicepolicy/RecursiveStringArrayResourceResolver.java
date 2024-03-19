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

package com.android.server.devicepolicy;

import android.annotation.SuppressLint;
import android.content.res.Resources;

import androidx.annotation.ArrayRes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A class encapsulating all the logic for recursive string-array resource resolution.
 */
public class RecursiveStringArrayResourceResolver {
    private static final String IMPORT_PREFIX = "#import:";
    private static final String SEPARATOR = "/";
    private static final String PWP = ".";

    private final Resources mResources;

    /**
     * @param resources Android resource access object to use when resolving resources
     */
    public RecursiveStringArrayResourceResolver(Resources resources) {
        this.mResources = resources;
    }

    /**
     * Resolves a given {@code <string-array/>} resource specified via
     * {@param rootId} in {@param pkg}. During resolution all values prefixed with
     * {@link #IMPORT_PREFIX} are expanded and injected
     * into the final list at the position of the import statement,
     * pushing all the following values (and their expansions) down.
     * Circular imports are tracked and skipped to avoid infinite resolution loops without losing
     * data.
     *
     * <p>
     * The import statements are expected in a form of
     * "{@link #IMPORT_PREFIX}{package}{@link #SEPARATOR}{resourceName}"
     * If the resource being imported is from the same package, its package can be specified as a
     * {@link #PWP} shorthand `.`
     * > e.g.:
     * >   {@code "#import:com.android.internal/disallowed_apps_managed_user"}
     * >   {@code "#import:./disallowed_apps_managed_user"}
     *
     * <p>
     * Any incorrect or unresolvable import statement
     * will cause the entire resolution to fail with an error.
     *
     * @param pkg    the package owning the resource
     * @param rootId the id of the {@code <string-array>} resource within {@param pkg} to start the
     *               resolution from
     * @return a flattened list of all the resolved string array values from the root resource
     * as well as all the imported arrays
     */
    public Set<String> resolve(String pkg, @ArrayRes int rootId) {
        return resolve(List.of(), pkg, rootId);
    }

    /**
     * A version of resolve that tracks already imported resources
     * to avoid circular imports and wasted work.
     *
     * @param cache a list of already resolved packages to be skipped for further resolution
     */
    private Set<String> resolve(Collection<String> cache, String pkg, @ArrayRes int rootId) {
        final var strings = mResources.getStringArray(rootId);
        final var runningCache = new ArrayList<>(cache);

        final var result = new HashSet<String>();
        for (var string : strings) {
            final String ref;
            if (string.startsWith(IMPORT_PREFIX)) {
                ref = string.substring(IMPORT_PREFIX.length());
            } else {
                ref = null;
            }

            if (ref == null) {
                result.add(string);
            } else if (!runningCache.contains(ref)) {
                final var next = resolveImport(runningCache, pkg, ref);
                runningCache.addAll(next);
                result.addAll(next);
            }
        }
        return result;
    }

    /**
     * Resolves an import of the {@code <string-array>} resource
     * in the context of {@param importingPackage} by the provided {@param ref}.
     *
     * @param cache            a list of already resolved packages to be passed along into chained
     *                         {@link #resolve} calls
     * @param importingPackage the package that owns the resource which defined the import being
     *                         processed.
     *                         It is also used to expand all {@link #PWP} shorthands in
     *                         {@param ref}
     * @param ref              reference to the resource to be imported in a form of
     *                         "{package}{@link #SEPARATOR}{resourceName}".
     *                         e.g.: {@code com.android.internal/disallowed_apps_managed_user}
     */
    private Set<String> resolveImport(
            Collection<String> cache,
            String importingPackage,
            String ref) {
        final var chunks = ref.split(SEPARATOR, 2);
        final var pkg = chunks[0];
        final var name = chunks[1];
        final String resolvedPkg;
        if (Objects.equals(pkg, PWP)) {
            resolvedPkg = importingPackage;
        } else {
            resolvedPkg = pkg;
        }
        @SuppressLint("DiscouragedApi") final var importId = mResources.getIdentifier(
                /* name = */ name,
                /* defType = */ "array",
                /* defPackage = */ resolvedPkg);
        if (importId == 0) {
            throw new Resources.NotFoundException(
                    /* name= */ String.format("%s:array/%s", resolvedPkg, name));
        }
        return resolve(cache, resolvedPkg, importId);
    }
}
