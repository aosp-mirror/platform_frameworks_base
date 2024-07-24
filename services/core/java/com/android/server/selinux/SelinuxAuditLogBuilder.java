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

import java.util.Arrays;
import java.util.Iterator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Builder for SelinuxAuditLogs. */
class SelinuxAuditLogBuilder {

    // Currently logs collection is hardcoded for the sdk_sandbox_audit.
    private static final String SDK_SANDBOX_AUDIT = "sdk_sandbox_audit";
    static final Matcher SCONTEXT_MATCHER =
            Pattern.compile(
                            "u:r:(?<stype>"
                                    + SDK_SANDBOX_AUDIT
                                    + "):s0(:c)?(?<scategories>((,c)?\\d+)+)*")
                    .matcher("");

    static final Matcher TCONTEXT_MATCHER =
            Pattern.compile("u:object_r:(?<ttype>\\w+):s0(:c)?(?<tcategories>((,c)?\\d+)+)*")
                    .matcher("");

    static final Matcher PATH_MATCHER =
            Pattern.compile("\"(?<path>/\\w+(/\\w+)?)(/\\w+)*\"").matcher("");

    private Iterator<String> mTokens;
    private final SelinuxAuditLog mAuditLog = new SelinuxAuditLog();

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
                    if (!nextTokenMatches(SCONTEXT_MATCHER)) {
                        return null;
                    }
                    mAuditLog.mSType = SCONTEXT_MATCHER.group("stype");
                    mAuditLog.mSCategories = toCategories(SCONTEXT_MATCHER.group("scategories"));
                    break;
                case "tcontext":
                    if (!nextTokenMatches(TCONTEXT_MATCHER)) {
                        return null;
                    }
                    mAuditLog.mTType = TCONTEXT_MATCHER.group("ttype");
                    mAuditLog.mTCategories = toCategories(TCONTEXT_MATCHER.group("tcategories"));
                    break;
                case "tclass":
                    if (!mTokens.hasNext()) {
                        return null;
                    }
                    mAuditLog.mTClass = mTokens.next();
                    break;
                case "path":
                    if (nextTokenMatches(PATH_MATCHER)) {
                        mAuditLog.mPath = PATH_MATCHER.group("path");
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
