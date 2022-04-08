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

import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Log;

import org.json.JSONException;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An implementation of {@link AbstractStatementRetriever} that directly retrieves statements from
 * the asset.
 */
/* package private */ final class DirectStatementRetriever extends AbstractStatementRetriever {

    private static final long DO_NOT_CACHE_RESULT = 0L;
    private static final int HTTP_CONNECTION_TIMEOUT_MILLIS = 5000;
    private static final int HTTP_CONNECTION_BACKOFF_MILLIS = 3000;
    private static final int HTTP_CONNECTION_RETRY = 3;
    private static final long HTTP_CONTENT_SIZE_LIMIT_IN_BYTES = 1024 * 1024;
    private static final int MAX_INCLUDE_LEVEL = 1;
    private static final String WELL_KNOWN_STATEMENT_PATH = "/.well-known/assetlinks.json";

    private final URLFetcher mUrlFetcher;
    private final AndroidPackageInfoFetcher mAndroidFetcher;

    /**
     * An immutable value type representing the retrieved statements and the expiration date.
     */
    public static class Result implements AbstractStatementRetriever.Result {

        private final List<Statement> mStatements;
        private final Long mExpireMillis;

        @Override
        public List<Statement> getStatements() {
            return mStatements;
        }

        @Override
        public long getExpireMillis() {
            return mExpireMillis;
        }

        private Result(List<Statement> statements, Long expireMillis) {
            mStatements = statements;
            mExpireMillis = expireMillis;
        }

        public static Result create(List<Statement> statements, Long expireMillis) {
            return new Result(statements, expireMillis);
        }

        @Override
        public String toString() {
            StringBuilder result = new StringBuilder();
            result.append("Result: ");
            result.append(mStatements.toString());
            result.append(", mExpireMillis=");
            result.append(mExpireMillis);
            return result.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            Result result = (Result) o;

            if (!mExpireMillis.equals(result.mExpireMillis)) {
                return false;
            }
            if (!mStatements.equals(result.mStatements)) {
                return false;
            }

            return true;
        }

        @Override
        public int hashCode() {
            int result = mStatements.hashCode();
            result = 31 * result + mExpireMillis.hashCode();
            return result;
        }
    }

    public DirectStatementRetriever(URLFetcher urlFetcher,
                                    AndroidPackageInfoFetcher androidFetcher) {
        this.mUrlFetcher = urlFetcher;
        this.mAndroidFetcher = androidFetcher;
    }

    @Override
    public Result retrieveStatements(AbstractAsset source) throws AssociationServiceException {
        if (source instanceof AndroidAppAsset) {
            return retrieveFromAndroid((AndroidAppAsset) source);
        } else if (source instanceof WebAsset) {
            return retrieveFromWeb((WebAsset) source);
        } else {
            throw new AssociationServiceException("Namespace is not supported.");
        }
    }

    private String computeAssociationJsonUrl(WebAsset asset) {
        try {
            return new URL(asset.getScheme(), asset.getDomain(), asset.getPort(),
                    WELL_KNOWN_STATEMENT_PATH)
                    .toExternalForm();
        } catch (MalformedURLException e) {
            throw new AssertionError("Invalid domain name in database.");
        }
    }

    private Result retrieveStatementFromUrl(String urlString, int maxIncludeLevel,
                                            AbstractAsset source)
            throws AssociationServiceException {
        List<Statement> statements = new ArrayList<Statement>();
        if (maxIncludeLevel < 0) {
            return Result.create(statements, DO_NOT_CACHE_RESULT);
        }

        WebContent webContent;
        try {
            URL url = new URL(urlString);
            if (!source.followInsecureInclude()
                    && !url.getProtocol().toLowerCase().equals("https")) {
                return Result.create(statements, DO_NOT_CACHE_RESULT);
            }
            webContent = mUrlFetcher.getWebContentFromUrlWithRetry(url,
                    HTTP_CONTENT_SIZE_LIMIT_IN_BYTES, HTTP_CONNECTION_TIMEOUT_MILLIS,
                    HTTP_CONNECTION_BACKOFF_MILLIS, HTTP_CONNECTION_RETRY);
        } catch (IOException | InterruptedException e) {
            return Result.create(statements, DO_NOT_CACHE_RESULT);
        }

        try {
            ParsedStatement result = StatementParser
                    .parseStatementList(webContent.getContent(), source);
            statements.addAll(result.getStatements());
            for (String delegate : result.getDelegates()) {
                statements.addAll(
                        retrieveStatementFromUrl(delegate, maxIncludeLevel - 1, source)
                                .getStatements());
            }
            return Result.create(statements, webContent.getExpireTimeMillis());
        } catch (JSONException | IOException e) {
            return Result.create(statements, DO_NOT_CACHE_RESULT);
        }
    }

    private Result retrieveFromWeb(WebAsset asset)
            throws AssociationServiceException {
        return retrieveStatementFromUrl(computeAssociationJsonUrl(asset), MAX_INCLUDE_LEVEL, asset);
    }

    private Result retrieveFromAndroid(AndroidAppAsset asset) throws AssociationServiceException {
        try {
            List<String> delegates = new ArrayList<String>();
            List<Statement> statements = new ArrayList<Statement>();

            List<String> certFps = mAndroidFetcher.getCertFingerprints(asset.getPackageName());
            if (!Utils.hasCommonString(certFps, asset.getCertFingerprints())) {
                throw new AssociationServiceException(
                        "Specified certs don't match the installed app.");
            }

            AndroidAppAsset actualSource = AndroidAppAsset.create(asset.getPackageName(), certFps);
            for (String statementJson : mAndroidFetcher.getStatements(asset.getPackageName())) {
                ParsedStatement result =
                        StatementParser.parseStatement(statementJson, actualSource);
                statements.addAll(result.getStatements());
                delegates.addAll(result.getDelegates());
            }

            for (String delegate : delegates) {
                statements.addAll(retrieveStatementFromUrl(delegate, MAX_INCLUDE_LEVEL,
                        actualSource).getStatements());
            }

            return Result.create(statements, DO_NOT_CACHE_RESULT);
        } catch (JSONException | IOException | NameNotFoundException e) {
            Log.w(DirectStatementRetriever.class.getSimpleName(), e);
            return Result.create(Collections.<Statement>emptyList(), DO_NOT_CACHE_RESULT);
        }
    }
}
