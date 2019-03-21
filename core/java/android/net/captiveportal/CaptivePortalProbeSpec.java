/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.captiveportal;

import static android.net.captiveportal.CaptivePortalProbeResult.PORTAL_CODE;
import static android.net.captiveportal.CaptivePortalProbeResult.SUCCESS_CODE;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** @hide */
@SystemApi
@TestApi
public abstract class CaptivePortalProbeSpec {
    private static final String TAG = CaptivePortalProbeSpec.class.getSimpleName();
    private static final String REGEX_SEPARATOR = "@@/@@";
    private static final String SPEC_SEPARATOR = "@@,@@";

    private final String mEncodedSpec;
    private final URL mUrl;

    CaptivePortalProbeSpec(@NonNull String encodedSpec, @NonNull URL url)
            throws NullPointerException {
        mEncodedSpec = checkNotNull(encodedSpec);
        mUrl = checkNotNull(url);
    }

    /**
     * Parse a {@link CaptivePortalProbeSpec} from a {@link String}.
     *
     * <p>The valid format is a URL followed by two regular expressions, each separated by "@@/@@".
     * @throws MalformedURLException The URL has invalid format for {@link URL#URL(String)}.
     * @throws ParseException The string is empty, does not match the above format, or a regular
     * expression is invalid for {@link Pattern#compile(String)}.
     * @hide
     */
    @VisibleForTesting
    @NonNull
    public static CaptivePortalProbeSpec parseSpec(@NonNull String spec) throws ParseException,
            MalformedURLException {
        if (TextUtils.isEmpty(spec)) {
            throw new ParseException("Empty probe spec", 0 /* errorOffset */);
        }

        String[] splits = TextUtils.split(spec, REGEX_SEPARATOR);
        if (splits.length != 3) {
            throw new ParseException("Probe spec does not have 3 parts", 0 /* errorOffset */);
        }

        final int statusRegexPos = splits[0].length() + REGEX_SEPARATOR.length();
        final int locationRegexPos = statusRegexPos + splits[1].length() + REGEX_SEPARATOR.length();
        final Pattern statusRegex = parsePatternIfNonEmpty(splits[1], statusRegexPos);
        final Pattern locationRegex = parsePatternIfNonEmpty(splits[2], locationRegexPos);

        return new RegexMatchProbeSpec(spec, new URL(splits[0]), statusRegex, locationRegex);
    }

    @Nullable
    private static Pattern parsePatternIfNonEmpty(@Nullable String pattern, int pos)
            throws ParseException {
        if (TextUtils.isEmpty(pattern)) {
            return null;
        }
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new ParseException(
                    String.format("Invalid status pattern [%s]: %s", pattern, e),
                    pos /* errorOffset */);
        }
    }

    /**
     * Parse a {@link CaptivePortalProbeSpec} from a {@link String}, or return a fallback spec
     * based on the status code of the provided URL if the spec cannot be parsed.
     */
    @Nullable
    public static CaptivePortalProbeSpec parseSpecOrNull(@Nullable String spec) {
        if (spec != null) {
            try {
                return parseSpec(spec);
            } catch (ParseException | MalformedURLException e) {
                Log.e(TAG, "Invalid probe spec: " + spec, e);
                // Fall through
            }
        }
        return null;
    }

    /**
     * Parse a config String to build an array of {@link CaptivePortalProbeSpec}.
     *
     * <p>Each spec is separated by @@,@@ and follows the format for {@link #parseSpec(String)}.
     * <p>This method does not throw but ignores any entry that could not be parsed.
     */
    @NonNull
    public static Collection<CaptivePortalProbeSpec> parseCaptivePortalProbeSpecs(
            @NonNull String settingsVal) {
        List<CaptivePortalProbeSpec> specs = new ArrayList<>();
        if (settingsVal != null) {
            for (String spec : TextUtils.split(settingsVal, SPEC_SEPARATOR)) {
                try {
                    specs.add(parseSpec(spec));
                } catch (ParseException | MalformedURLException e) {
                    Log.e(TAG, "Invalid probe spec: " + spec, e);
                }
            }
        }

        if (specs.isEmpty()) {
            Log.e(TAG, String.format("could not create any validation spec from %s", settingsVal));
        }
        return specs;
    }

    /**
     * Get the probe result from HTTP status and location header.
     */
    @NonNull
    public abstract CaptivePortalProbeResult getResult(int status, @Nullable String locationHeader);

    @NonNull
    public String getEncodedSpec() {
        return mEncodedSpec;
    }

    @NonNull
    public URL getUrl() {
        return mUrl;
    }

    /**
     * Implementation of {@link CaptivePortalProbeSpec} that is based on configurable regular
     * expressions for the HTTP status code and location header (if any). Matches indicate that
     * the page is not a portal.
     * This probe cannot fail: it always returns SUCCESS_CODE or PORTAL_CODE
     */
    private static class RegexMatchProbeSpec extends CaptivePortalProbeSpec {
        @Nullable
        final Pattern mStatusRegex;
        @Nullable
        final Pattern mLocationHeaderRegex;

        RegexMatchProbeSpec(
                String spec, URL url, Pattern statusRegex, Pattern locationHeaderRegex) {
            super(spec, url);
            mStatusRegex = statusRegex;
            mLocationHeaderRegex = locationHeaderRegex;
        }

        @Override
        public CaptivePortalProbeResult getResult(int status, String locationHeader) {
            final boolean statusMatch = safeMatch(String.valueOf(status), mStatusRegex);
            final boolean locationMatch = safeMatch(locationHeader, mLocationHeaderRegex);
            final int returnCode = statusMatch && locationMatch ? SUCCESS_CODE : PORTAL_CODE;
            return new CaptivePortalProbeResult(
                    returnCode, locationHeader, getUrl().toString(), this);
        }
    }

    private static boolean safeMatch(@Nullable String value, @Nullable Pattern pattern) {
        // No value is a match ("no location header" passes the location rule for non-redirects)
        return pattern == null || TextUtils.isEmpty(value) || pattern.matcher(value).matches();
    }
}
