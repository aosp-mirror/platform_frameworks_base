/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.verify.domain;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.parsing.component.ParsedActivity;
import android.content.pm.parsing.component.ParsedIntentInfo;
import android.os.Build;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Patterns;

import com.android.server.SystemConfig;
import com.android.server.compat.PlatformCompat;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DomainVerificationCollector {

    // The default domain name matcher doesn't account for wildcards, so prefix with *.
    private static final Pattern DOMAIN_NAME_WITH_WILDCARD =
            Pattern.compile("(\\*\\.)?" + Patterns.DOMAIN_NAME.pattern());

    private static final int MAX_DOMAINS_BYTE_SIZE = 1024 * 1024;

    private static final BiFunction<ArraySet<String>, String, Boolean> ARRAY_SET_COLLECTOR =
            (set, domain) -> {
                set.add(domain);
                return null;
            };

    @NonNull
    private final PlatformCompat mPlatformCompat;

    @NonNull
    private final SystemConfig mSystemConfig;

    @NonNull
    private final Matcher mDomainMatcher;

    public DomainVerificationCollector(@NonNull PlatformCompat platformCompat,
            @NonNull SystemConfig systemConfig) {
        mPlatformCompat = platformCompat;
        mSystemConfig = systemConfig;

        // Cache the matcher to avoid calling into native on each check
        mDomainMatcher = DOMAIN_NAME_WITH_WILDCARD.matcher("");
    }

    /**
     * With the updated form of the app links verification APIs, an app will be required to declare
     * domains inside an intent filter which includes all of the following:
     * <ul>
     *     <li>- android:autoVerify="true"</li>
     *     <li>- Intent.ACTION_VIEW</li>
     *     <li>- Intent.CATEGORY_BROWSABLE</li>
     *     <li>- Intent.CATEGORY_DEFAULT</li>
     *     <li>- Only IntentFilter.SCHEME_HTTP and/or IntentFilter.SCHEME_HTTPS,
     *           with no other schemes</li>
     * </ul>
     * <p>
     * On prior versions of Android, Intent.CATEGORY_BROWSABLE was not a requirement, other
     * schemes were allowed, and setting autoVerify to true in any intent filter would implicitly
     * pretend that all intent filters were set to autoVerify="true".
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S)
    public static final long RESTRICT_DOMAINS = 175408749L;

    @NonNull
    public ArraySet<String> collectAllWebDomains(@NonNull AndroidPackage pkg) {
        return collectDomains(pkg, false /* checkAutoVerify */, true /* valid */);
    }

    /**
     * Effectively {@link #collectAllWebDomains(AndroidPackage)}, but requires {@link
     * IntentFilter#getAutoVerify()} == true.
     */
    @NonNull
    public ArraySet<String> collectValidAutoVerifyDomains(@NonNull AndroidPackage pkg) {
        return collectDomains(pkg, true /* checkAutoVerify */, true /* valid */);
    }

    /**
     * Returns all the domains that are configured to be auto verified, but aren't actually valid
     * HTTP domains, per {@link #DOMAIN_NAME_WITH_WILDCARD}.
     */
    @NonNull
    public ArraySet<String> collectInvalidAutoVerifyDomains(@NonNull AndroidPackage pkg) {
        return collectDomains(pkg, true /* checkAutoVerify */, false /* valid */);
    }

    public boolean containsWebDomain(@NonNull AndroidPackage pkg, @NonNull String targetDomain) {
        return collectDomains(pkg, false /* checkAutoVerify */, true /* valid */, null,
                (BiFunction<Void, String, Boolean>) (unused, domain) -> {
                    if (Objects.equals(targetDomain, domain)) {
                        return true;
                    }
                    return null;
                }) != null;
    }

    public boolean containsAutoVerifyDomain(@NonNull AndroidPackage pkg,
            @NonNull String targetDomain) {
        return collectDomains(pkg, true /* checkAutoVerify */, true /* valid */, null,
                (BiFunction<Void, String, Boolean>) (unused, domain) -> {
                    if (Objects.equals(targetDomain, domain)) {
                        return true;
                    }
                    return null;
                }) != null;
    }

    @NonNull
    private ArraySet<String> collectDomains(@NonNull AndroidPackage pkg,
            boolean checkAutoVerify, boolean valid) {
        ArraySet<String> domains = new ArraySet<>();
        collectDomains(pkg, checkAutoVerify, valid, domains, ARRAY_SET_COLLECTOR);
        return domains;
    }

    @NonNull
    private <InitialValue, ReturnValue> ReturnValue collectDomains(@NonNull AndroidPackage pkg,
            boolean checkAutoVerify, boolean valid, @Nullable InitialValue initialValue,
            @NonNull BiFunction<InitialValue, String, ReturnValue> domainCollector) {
        boolean restrictDomains =
                DomainVerificationUtils.isChangeEnabled(mPlatformCompat, pkg, RESTRICT_DOMAINS);

        if (restrictDomains) {
            return collectDomainsInternal(pkg, checkAutoVerify, valid, initialValue,
                    domainCollector);
        } else {
            return collectDomainsLegacy(pkg, checkAutoVerify, valid, initialValue, domainCollector);
        }
    }

    /**
     * @see #RESTRICT_DOMAINS
     */
    @Nullable
    private <InitialValue, ReturnValue> ReturnValue collectDomainsLegacy(
            @NonNull AndroidPackage pkg, boolean checkAutoVerify, boolean valid,
            @Nullable InitialValue initialValue,
            @NonNull BiFunction<InitialValue, String, ReturnValue> domainCollector) {
        if (!checkAutoVerify) {
            // Per-domain user selection state doesn't have a V1 equivalent on S, so just use V2
            return collectDomainsInternal(pkg, false /* checkAutoVerify */, true /* valid */,
                    initialValue, domainCollector);
        }

        List<ParsedActivity> activities = pkg.getActivities();
        int activitiesSize = activities.size();

        // Due to a bug in the platform, for backwards compatibility, assume that all linked apps
        // require auto verification, even if they forget to mark their manifest as such.
        boolean needsAutoVerify = mSystemConfig.getLinkedApps().contains(pkg.getPackageName());
        if (!needsAutoVerify) {
            for (int activityIndex = 0; activityIndex < activitiesSize && !needsAutoVerify;
                    activityIndex++) {
                ParsedActivity activity = activities.get(activityIndex);
                List<ParsedIntentInfo> intents = activity.getIntents();
                int intentsSize = intents.size();
                for (int intentIndex = 0; intentIndex < intentsSize && !needsAutoVerify;
                        intentIndex++) {
                    ParsedIntentInfo intent = intents.get(intentIndex);
                    needsAutoVerify = intent.needsVerification();
                }
            }

            if (!needsAutoVerify) {
                return null;
            }
        }

        int totalSize = 0;
        boolean underMaxSize = true;
        for (int activityIndex = 0; activityIndex < activitiesSize && underMaxSize;
                activityIndex++) {
            ParsedActivity activity = activities.get(activityIndex);
            List<ParsedIntentInfo> intents = activity.getIntents();
            int intentsSize = intents.size();
            for (int intentIndex = 0; intentIndex < intentsSize && underMaxSize; intentIndex++) {
                ParsedIntentInfo intent = intents.get(intentIndex);
                if (intent.handlesWebUris(false)) {
                    int authorityCount = intent.countDataAuthorities();
                    for (int index = 0; index < authorityCount; index++) {
                        String host = intent.getDataAuthority(index).getHost();
                        if (isValidHost(host) == valid) {
                            totalSize += byteSizeOf(host);
                            underMaxSize = totalSize < MAX_DOMAINS_BYTE_SIZE;
                            ReturnValue returnValue = domainCollector.apply(initialValue, host);
                            if (returnValue != null) {
                                return returnValue;
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * @see #RESTRICT_DOMAINS
     * @param domainCollector Function to call with initialValue and a valid host. Should return
     *                        a non-null value if the function should return immediately
     *                        after the currently processed host.
     */
    @Nullable
    private <InitialValue, ReturnValue> ReturnValue collectDomainsInternal(
            @NonNull AndroidPackage pkg, boolean checkAutoVerify, boolean valid,
            @Nullable InitialValue initialValue,
            @NonNull BiFunction<InitialValue, String, ReturnValue> domainCollector) {
        int totalSize = 0;
        boolean underMaxSize = true;

        List<ParsedActivity> activities = pkg.getActivities();
        int activitiesSize = activities.size();
        for (int activityIndex = 0; activityIndex < activitiesSize && underMaxSize;
                activityIndex++) {
            ParsedActivity activity = activities.get(activityIndex);
            List<ParsedIntentInfo> intents = activity.getIntents();
            int intentsSize = intents.size();
            for (int intentIndex = 0; intentIndex < intentsSize && underMaxSize; intentIndex++) {
                ParsedIntentInfo intent = intents.get(intentIndex);
                if (checkAutoVerify && !intent.getAutoVerify()) {
                    continue;
                }

                if (!intent.hasCategory(Intent.CATEGORY_DEFAULT)
                        || !intent.handlesWebUris(checkAutoVerify)) {
                    continue;
                }

                // TODO(b/159952358): There seems to be no way to associate the exact host
                //  with its scheme, meaning all hosts have to be verified as if they were
                //  web schemes. This means that given the following:
                //  <intent-filter android:autoVerify="true">
                //      ...
                //      <data android:scheme="https" android:host="one.example.com"/>
                //      <data android:scheme="https" android:host="two.example.com"/>
                //      <data android:host="three.example.com"/>
                //      <data android:scheme="nonWeb" android:host="four.example.com"/>
                //  </intent-filter>
                //  The verification agent will be asked to verify four.example.com, which the
                //  app will probably fail. This can be re-configured to work properly by the
                //  app developer by declaring a separate intent-filter. This may not be worth
                //  fixing.
                int authorityCount = intent.countDataAuthorities();
                for (int index = 0; index < authorityCount && underMaxSize; index++) {
                    String host = intent.getDataAuthority(index).getHost();
                    if (isValidHost(host) == valid) {
                        totalSize += byteSizeOf(host);
                        underMaxSize = totalSize < MAX_DOMAINS_BYTE_SIZE;
                        ReturnValue returnValue = domainCollector.apply(initialValue, host);
                        if (returnValue != null) {
                            return returnValue;
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Ballpark the size of domains to avoid a ridiculous amount of domains that could slow
     * down client-server communication.
     */
    private int byteSizeOf(String string) {
        // Use the same method from core for the data objects so that restrictions are consistent
        return android.content.pm.verify.domain.DomainVerificationUtils.estimatedByteSizeOf(string);
    }

    /**
     * It's easy to mis-configure autoVerify intent filters, so to avoid adding unintended hosts,
     * check if the host is an HTTP domain. This applies for both legacy and modern versions of
     * the API, which will strip invalid hosts from the legacy parsing result. This is done to
     * improve the reliability of any legacy verifiers.
     */
    private boolean isValidHost(String host) {
        if (TextUtils.isEmpty(host)) {
            return false;
        }

        mDomainMatcher.reset(host);
        return mDomainMatcher.matches();
    }
}
