/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.statementservice.retriever;

import android.content.Context;
import android.annotation.NonNull;

import java.util.List;

/**
 * Retrieves the statements made by assets. This class is the entry point of the package.
 * <p>
 * An asset is an identifiable and addressable online entity that typically
 * provides some service or content. Examples of assets are websites, Android
 * apps, Twitter feeds, and Plus Pages.
 * <p>
 * Ownership of an asset is defined by being able to control it and speak for it.
 * An asset owner may establish a relationship between the asset and another
 * asset by making a statement about an intended relationship between the two.
 * An example of a relationship is permission delegation. For example, the owner
 * of a website (the webmaster) may delegate the ability the handle URLs to a
 * particular mobile app. Relationships are considered public information.
 * <p>
 * A particular kind of relationship (like permission delegation) defines a binary
 * relation on assets. The relation is not symmetric or transitive, nor is it
 * antisymmetric or anti-transitive.
 * <p>
 * A statement S(r, a, b) is an assertion that the relation r holds for the
 * ordered pair of assets (a, b). For example, taking r = "delegates permission
 * to view user's location", a = New York Times mobile app,
 * b = nytimes.com website, S(r, a, b) would be an assertion that "the New York
 * Times mobile app delegates its ability to use the user's location to the
 * nytimes.com website".
 * <p>
 * A statement S(r, a, b) is considered <b>reliable</b> if we have confidence that
 * the statement is true; the exact criterion depends on the kind of statement,
 * since some kinds of statements may be true on their face whereas others may
 * require multiple parties to agree.
 * <p>
 * For example, to get the statements made by www.example.com use:
 * <pre>
 * result = retrieveStatements(AssetFactory.create(
 *     "{\"namespace\": \"web\", \"site\": \"https://www.google.com\"}"))
 * </pre>
 * {@code result} will contain the statements and the expiration time of this result. The statements
 * are considered reliable until the expiration time.
 */
public abstract class AbstractStatementRetriever {

    /**
     * Returns the statements made by the {@code source} asset with ttl.
     *
     * @throws AssociationServiceException if the asset namespace is not supported.
     */
    public abstract Result retrieveStatements(AbstractAsset source)
            throws AssociationServiceException;

    /**
     * The retrieved statements and the expiration date.
     */
    public interface Result {

        /**
         * @return the retrieved statements.
         */
        @NonNull
        public List<Statement> getStatements();

        /**
         * @return the expiration time in millisecond.
         */
        public long getExpireMillis();
    }

    /**
     * Creates a new StatementRetriever that directly retrieves statements from the asset.
     *
     * <p> For web assets, {@link AbstractStatementRetriever} will try to retrieve the statement
     * file from URL: {@code [webAsset.site]/.well-known/assetlinks.json"} where {@code
     * [webAsset.site]} is in the form {@code http{s}://[hostname]:[optional_port]}. The file
     * should contain one JSON array of statements.
     *
     * <p> For Android assets, {@link AbstractStatementRetriever} will try to retrieve the statement
     * from the AndroidManifest.xml. The developer should add a {@code meta-data} tag under
     * {@code application} tag where attribute {@code android:name} equals "associated_assets"
     * and {@code android:recourse} points to a string array resource. Each entry in the string
     * array should contain exactly one statement in JSON format. Note that this implementation
     * can only return statements made by installed apps.
     */
    public static AbstractStatementRetriever createDirectRetriever(Context context) {
        return new DirectStatementRetriever(new URLFetcher(),
                new AndroidPackageInfoFetcher(context));
    }
}
