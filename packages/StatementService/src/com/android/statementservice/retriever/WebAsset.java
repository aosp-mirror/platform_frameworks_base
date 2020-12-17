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

import com.android.statementservice.utils.StatementUtils;

import org.json.JSONObject;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

/**
 * Immutable value type that names a web asset.
 *
 * <p>A web asset can be named by its protocol, domain, and port using this JSON string:
 *     { "namespace": "web",
 *       "site": "[protocol]://[fully-qualified domain]{:[optional port]}" }
 *
 * <p>For example, a website hosted on a https server at www.test.com can be named using
 *     { "namespace": "web",
 *       "site": "https://www.test.com" }
 *
 * <p>The only protocol supported now are https and http. If the optional port is not specified,
 * the default for each protocol will be used (i.e. 80 for http and 443 for https).
 */
public final class WebAsset extends AbstractAsset {

    private static final String MISSING_FIELD_FORMAT_STRING = "Expected %s to be set.";
    private static final String SCHEME_HTTP = "http";

    private final URL mUrl;

    private WebAsset(URL url) {
        int port = url.getPort() != -1 ? url.getPort() : url.getDefaultPort();
        try {
            mUrl = new URL(url.getProtocol().toLowerCase(), url.getHost().toLowerCase(), port, "");
        } catch (MalformedURLException e) {
            throw new AssertionError(
                    "Url should always be validated before calling the constructor.");
        }
    }

    public String getDomain() {
        return mUrl.getHost();
    }

    public String getPath() {
        return mUrl.getPath();
    }

    public String getScheme() {
        return mUrl.getProtocol();
    }

    public int getPort() {
        return mUrl.getPort();
    }

    @Override
    public String toJson() {
        AssetJsonWriter writer = new AssetJsonWriter();

        writer.writeFieldLower(StatementUtils.NAMESPACE_FIELD, StatementUtils.NAMESPACE_WEB);
        writer.writeFieldLower(StatementUtils.WEB_ASSET_FIELD_SITE, mUrl.toExternalForm());

        return writer.closeAndGetString();
    }

    @Override
    public String toString() {
        StringBuilder asset = new StringBuilder();
        asset.append("WebAsset: ");
        asset.append(toJson());
        return asset.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof WebAsset)) {
            return false;
        }

        return ((WebAsset) o).toJson().equals(toJson());
    }

    @Override
    public int hashCode() {
        return toJson().hashCode();
    }

    @Override
    public int lookupKey() {
        return toJson().hashCode();
    }

    @Override
    public boolean followInsecureInclude() {
        // Only allow insecure include file if the asset scheme is http.
        return SCHEME_HTTP.equals(getScheme());
    }

    /**
     * Checks that the input is a valid web asset.
     *
     * @throws AssociationServiceException if the asset is not well formatted.
     */
    protected static WebAsset create(JSONObject asset)
            throws AssociationServiceException {
        if (asset.optString(StatementUtils.WEB_ASSET_FIELD_SITE).equals("")) {
            throw new AssociationServiceException(String.format(MISSING_FIELD_FORMAT_STRING,
                    StatementUtils.WEB_ASSET_FIELD_SITE));
        }

        URL url;
        try {
            url = new URL(asset.optString(StatementUtils.WEB_ASSET_FIELD_SITE));
        } catch (MalformedURLException e) {
            throw new AssociationServiceException("Url is not well formatted.", e);
        }

        String scheme = url.getProtocol().toLowerCase(Locale.US);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new AssociationServiceException("Expected scheme to be http or https.");
        }

        if (url.getUserInfo() != null) {
            throw new AssociationServiceException("The url should not contain user info.");
        }

        String path = url.getFile(); // This is url.getPath() + url.getQuery().
        if (!path.equals("/") && !path.equals("")) {
            throw new AssociationServiceException(
                    "Site should only have scheme, domain, and port.");
        }

        return new WebAsset(url);
    }
}
