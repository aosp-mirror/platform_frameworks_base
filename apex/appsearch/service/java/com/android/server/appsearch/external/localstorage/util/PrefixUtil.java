/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.server.appsearch.external.localstorage.util;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.exceptions.AppSearchException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import com.google.android.icing.proto.DocumentProto;
import com.google.android.icing.proto.PropertyProto;

/**
 * Provides utility functions for working with package + database prefixes.
 *
 * @hide
 */
public class PrefixUtil {
    private static final String TAG = "AppSearchPrefixUtil";

    @VisibleForTesting public static final char DATABASE_DELIMITER = '/';

    @VisibleForTesting public static final char PACKAGE_DELIMITER = '$';

    private PrefixUtil() {}

    /** Creates prefix string for given package name and database name. */
    @NonNull
    public static String createPrefix(@NonNull String packageName, @NonNull String databaseName) {
        return packageName + PACKAGE_DELIMITER + databaseName + DATABASE_DELIMITER;
    }
    /** Creates prefix string for given package name. */
    @NonNull
    public static String createPackagePrefix(@NonNull String packageName) {
        return packageName + PACKAGE_DELIMITER;
    }

    /**
     * Returns the package name that's contained within the {@code prefix}.
     *
     * @param prefix Prefix string that contains the package name inside of it. The package name
     *     must be in the front of the string, and separated from the rest of the string by the
     *     {@link #PACKAGE_DELIMITER}.
     * @return Valid package name.
     */
    @NonNull
    public static String getPackageName(@NonNull String prefix) {
        int delimiterIndex = prefix.indexOf(PACKAGE_DELIMITER);
        if (delimiterIndex == -1) {
            // This should never happen if we construct our prefixes properly
            Log.wtf(TAG, "Malformed prefix doesn't contain package delimiter: " + prefix);
            return "";
        }
        return prefix.substring(0, delimiterIndex);
    }

    /**
     * Returns the database name that's contained within the {@code prefix}.
     *
     * @param prefix Prefix string that contains the database name inside of it. The database name
     *     must be between the {@link #PACKAGE_DELIMITER} and {@link #DATABASE_DELIMITER}
     * @return Valid database name.
     */
    @NonNull
    public static String getDatabaseName(@NonNull String prefix) {
        // TODO (b/184050178) Start database delimiter index search from after package delimiter
        int packageDelimiterIndex = prefix.indexOf(PACKAGE_DELIMITER);
        int databaseDelimiterIndex = prefix.indexOf(DATABASE_DELIMITER);
        if (packageDelimiterIndex == -1) {
            // This should never happen if we construct our prefixes properly
            Log.wtf(TAG, "Malformed prefix doesn't contain package delimiter: " + prefix);
            return "";
        }
        if (databaseDelimiterIndex == -1) {
            // This should never happen if we construct our prefixes properly
            Log.wtf(TAG, "Malformed prefix doesn't contain database delimiter: " + prefix);
            return "";
        }
        return prefix.substring(packageDelimiterIndex + 1, databaseDelimiterIndex);
    }

    /**
     * Creates a string with the package and database prefix removed from the input string.
     *
     * @param prefixedString a string containing a package and database prefix.
     * @return a string with the package and database prefix removed.
     * @throws AppSearchException if the prefixed value does not contain a valid database name.
     */
    @NonNull
    public static String removePrefix(@NonNull String prefixedString) throws AppSearchException {
        // The prefix is made up of the package, then the database. So we only need to find the
        // database cutoff.
        int delimiterIndex;
        if ((delimiterIndex = prefixedString.indexOf(DATABASE_DELIMITER)) != -1) {
            // Add 1 to include the char size of the DATABASE_DELIMITER
            return prefixedString.substring(delimiterIndex + 1);
        }
        throw new AppSearchException(
                AppSearchResult.RESULT_INTERNAL_ERROR,
                "The prefixed value \""
                        + prefixedString
                        + "\" doesn't contain a valid "
                        + "database name");
    }

    /**
     * Creates a package and database prefix string from the input string.
     *
     * @param prefixedString a string containing a package and database prefix.
     * @return a string with the package and database prefix
     * @throws AppSearchException if the prefixed value does not contain a valid database name.
     */
    @NonNull
    public static String getPrefix(@NonNull String prefixedString) throws AppSearchException {
        int databaseDelimiterIndex = prefixedString.indexOf(DATABASE_DELIMITER);
        if (databaseDelimiterIndex == -1) {
            throw new AppSearchException(
                    AppSearchResult.RESULT_INTERNAL_ERROR,
                    "The prefixed value \""
                            + prefixedString
                            + "\" doesn't contain a valid "
                            + "database name");
        }

        // Add 1 to include the char size of the DATABASE_DELIMITER
        return prefixedString.substring(0, databaseDelimiterIndex + 1);
    }

    /**
     * Prepends {@code prefix} to all types and namespaces mentioned anywhere in {@code
     * documentBuilder}.
     *
     * @param documentBuilder The document to mutate
     * @param prefix The prefix to add
     */
    public static void addPrefixToDocument(
            @NonNull DocumentProto.Builder documentBuilder, @NonNull String prefix) {
        // Rewrite the type name to include/remove the prefix.
        String newSchema = prefix + documentBuilder.getSchema();
        documentBuilder.setSchema(newSchema);

        // Rewrite the namespace to include/remove the prefix.
        documentBuilder.setNamespace(prefix + documentBuilder.getNamespace());

        // Recurse into derived documents
        for (int propertyIdx = 0;
                propertyIdx < documentBuilder.getPropertiesCount();
                propertyIdx++) {
            int documentCount = documentBuilder.getProperties(propertyIdx).getDocumentValuesCount();
            if (documentCount > 0) {
                PropertyProto.Builder propertyBuilder =
                        documentBuilder.getProperties(propertyIdx).toBuilder();
                for (int documentIdx = 0; documentIdx < documentCount; documentIdx++) {
                    DocumentProto.Builder derivedDocumentBuilder =
                            propertyBuilder.getDocumentValues(documentIdx).toBuilder();
                    addPrefixToDocument(derivedDocumentBuilder, prefix);
                    propertyBuilder.setDocumentValues(documentIdx, derivedDocumentBuilder);
                }
                documentBuilder.setProperties(propertyIdx, propertyBuilder);
            }
        }
    }

    /**
     * Removes any prefixes from types and namespaces mentioned anywhere in {@code documentBuilder}.
     *
     * @param documentBuilder The document to mutate
     * @return Prefix name that was removed from the document.
     * @throws AppSearchException if there are unexpected database prefixing errors.
     */
    @NonNull
    public static String removePrefixesFromDocument(@NonNull DocumentProto.Builder documentBuilder)
            throws AppSearchException {
        // Rewrite the type name and namespace to remove the prefix.
        String schemaPrefix = getPrefix(documentBuilder.getSchema());
        String namespacePrefix = getPrefix(documentBuilder.getNamespace());

        if (!schemaPrefix.equals(namespacePrefix)) {
            throw new AppSearchException(
                    AppSearchResult.RESULT_INTERNAL_ERROR,
                    "Found unexpected"
                            + " multiple prefix names in document: "
                            + schemaPrefix
                            + ", "
                            + namespacePrefix);
        }

        documentBuilder.setSchema(removePrefix(documentBuilder.getSchema()));
        documentBuilder.setNamespace(removePrefix(documentBuilder.getNamespace()));

        // Recurse into derived documents
        for (int propertyIdx = 0;
                propertyIdx < documentBuilder.getPropertiesCount();
                propertyIdx++) {
            int documentCount = documentBuilder.getProperties(propertyIdx).getDocumentValuesCount();
            if (documentCount > 0) {
                PropertyProto.Builder propertyBuilder =
                        documentBuilder.getProperties(propertyIdx).toBuilder();
                for (int documentIdx = 0; documentIdx < documentCount; documentIdx++) {
                    DocumentProto.Builder derivedDocumentBuilder =
                            propertyBuilder.getDocumentValues(documentIdx).toBuilder();
                    String nestedPrefix = removePrefixesFromDocument(derivedDocumentBuilder);
                    if (!nestedPrefix.equals(schemaPrefix)) {
                        throw new AppSearchException(
                                AppSearchResult.RESULT_INTERNAL_ERROR,
                                "Found unexpected multiple prefix names in document: "
                                        + schemaPrefix
                                        + ", "
                                        + nestedPrefix);
                    }
                    propertyBuilder.setDocumentValues(documentIdx, derivedDocumentBuilder);
                }
                documentBuilder.setProperties(propertyIdx, propertyBuilder);
            }
        }

        return schemaPrefix;
    }
}
