/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.server.selinux;

import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/** Builder for SelinuxAuditLogs. */
class SelinuxAuditLogBuilder {

    private static final String TAG = "SelinuxAuditLogs";

    // This config indicates which Selinux logs for source domains to collect. The string will be
    // inserted into a regex, so it must follow the regex syntax. For example, a valid value would
    // be "system_server|untrusted_app".
    @VisibleForTesting static final String CONFIG_SELINUX_AUDIT_DOMAIN = "selinux_audit_domain";
    private static final Matcher NO_OP_MATCHER = Pattern.compile("no-op^").matcher("");
    private static final String TCONTEXT_PATTERN =
            "u:object_r:(?<ttype>\\w+):s0(:c)?(?<tcategories>((,c)?\\d+)+)*";
    private static final String PATH_PATTERN = "\"(?<path>/\\w+(/\\w+)?)(/\\w+)*\"";

    @VisibleForTesting final Matcher mScontextMatcher;
    @VisibleForTesting final Matcher mTcontextMatcher;
    @VisibleForTesting final Matcher mPathMatcher;

    private Iterator<String> mTokens;
    private final SelinuxAuditLog mAuditLog = new SelinuxAuditLog();

    SelinuxAuditLogBuilder() {
        Matcher scontextMatcher = NO_OP_MATCHER;
        Matcher tcontextMatcher = NO_OP_MATCHER;
        Matcher pathMatcher = NO_OP_MATCHER;
        try {
            scontextMatcher =
                    Pattern.compile(
                                    TextUtils.formatSimple(
                                            "u:r:(?<stype>%s):s0(:c)?(?<scategories>((,c)?\\d+)+)*",
                                            DeviceConfig.getString(
                                                    DeviceConfig.NAMESPACE_ADSERVICES,
                                                    CONFIG_SELINUX_AUDIT_DOMAIN,
                                                    "no_match^")))
                            .matcher("");
            tcontextMatcher = Pattern.compile(TCONTEXT_PATTERN).matcher("");
            pathMatcher = Pattern.compile(PATH_PATTERN).matcher("");
        } catch (PatternSyntaxException e) {
            Slog.e(TAG, "Invalid pattern, setting every matcher to no-op.", e);
        }

        mScontextMatcher = scontextMatcher;
        mTcontextMatcher = tcontextMatcher;
        mPathMatcher = pathMatcher;
    }

    void reset(String denialString) {
        mTokens =
                Arrays.asList(
                                Optional.ofNullable(denialString)
                                        .map(s -> s.split("\\s+|="))
                                        .orElse(new String[0]))
                        .iterator();
        mAuditLog.reset();
    }

    SelinuxAuditLog build() {
        while (mTokens.hasNext()) {
            final String token = mTokens.next();

            switch (token) {
                case "granted":
                    mAuditLog.mGranted = true;
                    break;
                case "denied":
                    mAuditLog.mGranted = false;
                    break;
                case "{":
                    Stream.Builder<String> permissionsStream = Stream.builder();
                    boolean closed = false;
                    while (!closed && mTokens.hasNext()) {
                        String permission = mTokens.next();
                        if ("}".equals(permission)) {
                            closed = true;
                        } else {
                            permissionsStream.add(permission);
                        }
                    }
                    if (!closed) {
                        return null;
                    }
                    mAuditLog.mPermissions = permissionsStream.build().toArray(String[]::new);
                    break;
                case "scontext":
                    if (!nextTokenMatches(mScontextMatcher)) {
                        return null;
                    }
                    mAuditLog.mSType = mScontextMatcher.group("stype");
                    mAuditLog.mSCategories = toCategories(mScontextMatcher.group("scategories"));
                    break;
                case "tcontext":
                    if (!nextTokenMatches(mTcontextMatcher)) {
                        return null;
                    }
                    mAuditLog.mTType = mTcontextMatcher.group("ttype");
                    mAuditLog.mTCategories = toCategories(mTcontextMatcher.group("tcategories"));
                    break;
                case "tclass":
                    if (!mTokens.hasNext()) {
                        return null;
                    }
                    mAuditLog.mTClass = mTokens.next();
                    break;
                case "path":
                    if (nextTokenMatches(mPathMatcher)) {
                        mAuditLog.mPath = mPathMatcher.group("path");
                    }
                    break;
                case "permissive":
                    if (!mTokens.hasNext()) {
                        return null;
                    }
                    mAuditLog.mPermissive = "1".equals(mTokens.next());
                    break;
                default:
                    break;
            }
        }
        return mAuditLog;
    }

    boolean nextTokenMatches(Matcher matcher) {
        return mTokens.hasNext() && matcher.reset(mTokens.next()).matches();
    }

    static int[] toCategories(String categories) {
        return categories == null
                ? null
                : Arrays.stream(categories.split(",c")).mapToInt(Integer::parseInt).toArray();
    }

    static class SelinuxAuditLog {
        boolean mGranted = false;
        String[] mPermissions = null;
        String mSType = null;
        int[] mSCategories = null;
        String mTType = null;
        int[] mTCategories = null;
        String mTClass = null;
        String mPath = null;
        boolean mPermissive = false;

        private void reset() {
            mGranted = false;
            mPermissions = null;
            mSType = null;
            mSCategories = null;
            mTType = null;
            mTCategories = null;
            mTClass = null;
            mPath = null;
            mPermissive = false;
        }
    }
}
