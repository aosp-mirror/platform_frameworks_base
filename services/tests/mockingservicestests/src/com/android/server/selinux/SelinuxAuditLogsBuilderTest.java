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

import static com.android.server.selinux.SelinuxAuditLogBuilder.PATH_MATCHER;
import static com.android.server.selinux.SelinuxAuditLogBuilder.SCONTEXT_MATCHER;
import static com.android.server.selinux.SelinuxAuditLogBuilder.TCONTEXT_MATCHER;
import static com.android.server.selinux.SelinuxAuditLogBuilder.toCategories;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.selinux.SelinuxAuditLogBuilder.SelinuxAuditLog;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SelinuxAuditLogsBuilderTest {

    private final SelinuxAuditLogBuilder mAuditLogBuilder = new SelinuxAuditLogBuilder();

    @Test
    public void testMatcher_scontext() {
        assertThat(SCONTEXT_MATCHER.reset("u:r:sdk_sandbox_audit:s0").matches()).isTrue();
        assertThat(SCONTEXT_MATCHER.group("stype")).isEqualTo("sdk_sandbox_audit");
        assertThat(SCONTEXT_MATCHER.group("scategories")).isNull();

        assertThat(SCONTEXT_MATCHER.reset("u:r:sdk_sandbox_audit:s0:c123,c456").matches()).isTrue();
        assertThat(SCONTEXT_MATCHER.group("stype")).isEqualTo("sdk_sandbox_audit");
        assertThat(toCategories(SCONTEXT_MATCHER.group("scategories")))
                .isEqualTo(new int[] {123, 456});

        assertThat(SCONTEXT_MATCHER.reset("u:r:not_sdk_sandbox:s0").matches()).isFalse();
        assertThat(SCONTEXT_MATCHER.reset("u:object_r:sdk_sandbox_audit:s0").matches()).isFalse();
        assertThat(SCONTEXT_MATCHER.reset("u:r:sdk_sandbox_audit:s0:p123").matches()).isFalse();
    }

    @Test
    public void testMatcher_tcontext() {
        assertThat(TCONTEXT_MATCHER.reset("u:object_r:target_type:s0").matches()).isTrue();
        assertThat(TCONTEXT_MATCHER.group("ttype")).isEqualTo("target_type");
        assertThat(TCONTEXT_MATCHER.group("tcategories")).isNull();

        assertThat(TCONTEXT_MATCHER.reset("u:object_r:target_type2:s0:c666").matches()).isTrue();
        assertThat(TCONTEXT_MATCHER.group("ttype")).isEqualTo("target_type2");
        assertThat(toCategories(TCONTEXT_MATCHER.group("tcategories"))).isEqualTo(new int[] {666});

        assertThat(TCONTEXT_MATCHER.reset("u:r:target_type:s0").matches()).isFalse();
        assertThat(TCONTEXT_MATCHER.reset("u:r:sdk_sandbox_audit:s0:x456").matches()).isFalse();
    }

    @Test
    public void testMatcher_path() {
        assertThat(PATH_MATCHER.reset("\"/data\"").matches()).isTrue();
        assertThat(PATH_MATCHER.group("path")).isEqualTo("/data");
        assertThat(PATH_MATCHER.reset("\"/data/local\"").matches()).isTrue();
        assertThat(PATH_MATCHER.group("path")).isEqualTo("/data/local");
        assertThat(PATH_MATCHER.reset("\"/data/local/tmp\"").matches()).isTrue();
        assertThat(PATH_MATCHER.group("path")).isEqualTo("/data/local");

        assertThat(PATH_MATCHER.reset("\"/data/local").matches()).isFalse();
        assertThat(PATH_MATCHER.reset("\"_data_local\"").matches()).isFalse();
    }

    @Test
    public void testSelinuxAuditLogsBuilder_noOptionals() {
        mAuditLogBuilder.reset(
                "granted { p } scontext=u:r:sdk_sandbox_audit:s0 tcontext=u:object_r:t:s0"
                        + " tclass=c");
        assertAuditLog(
                mAuditLogBuilder.build(), true, new String[] {"p"}, "sdk_sandbox_audit", "t", "c");

        mAuditLogBuilder.reset(
                "tclass=c2 granted { p2 } tcontext=u:object_r:t2:s0"
                        + " scontext=u:r:sdk_sandbox_audit:s0");
        assertAuditLog(
                mAuditLogBuilder.build(),
                true,
                new String[] {"p2"},
                "sdk_sandbox_audit",
                "t2",
                "c2");
    }

    @Test
    public void testSelinuxAuditLogsBuilder_withCategories() {
        mAuditLogBuilder.reset(
                "granted { p } scontext=u:r:sdk_sandbox_audit:s0:c123"
                        + " tcontext=u:object_r:t:s0:c456,c666 tclass=c");
        assertAuditLog(
                mAuditLogBuilder.build(),
                true,
                new String[] {"p"},
                "sdk_sandbox_audit",
                new int[] {123},
                "t",
                new int[] {456, 666},
                "c",
                null,
                false);
    }

    @Test
    public void testSelinuxAuditLogsBuilder_withPath() {
        mAuditLogBuilder.reset(
                "granted { p } scontext=u:r:sdk_sandbox_audit:s0 path=\"/very/long/path\""
                        + " tcontext=u:object_r:t:s0 tclass=c");
        assertAuditLog(
                mAuditLogBuilder.build(),
                true,
                new String[] {"p"},
                "sdk_sandbox_audit",
                null,
                "t",
                null,
                "c",
                "/very/long",
                false);
    }

    @Test
    public void testSelinuxAuditLogsBuilder_withPermissive() {
        mAuditLogBuilder.reset(
                "granted { p } scontext=u:r:sdk_sandbox_audit:s0 permissive=0"
                        + " tcontext=u:object_r:t:s0 tclass=c");
        assertAuditLog(
                mAuditLogBuilder.build(),
                true,
                new String[] {"p"},
                "sdk_sandbox_audit",
                null,
                "t",
                null,
                "c",
                null,
                false);

        mAuditLogBuilder.reset(
                "granted { p } scontext=u:r:sdk_sandbox_audit:s0 tcontext=u:object_r:t:s0 tclass=c"
                        + " permissive=1");
        assertAuditLog(
                mAuditLogBuilder.build(),
                true,
                new String[] {"p"},
                "sdk_sandbox_audit",
                null,
                "t",
                null,
                "c",
                null,
                true);
    }

    private void assertAuditLog(
            SelinuxAuditLog auditLog,
            boolean granted,
            String[] permissions,
            String sType,
            String tType,
            String tClass) {
        assertAuditLog(
                auditLog, granted, permissions, sType, null, tType, null, tClass, null, false);
    }

    private void assertAuditLog(
            SelinuxAuditLog auditLog,
            boolean granted,
            String[] permissions,
            String sType,
            int[] sCategories,
            String tType,
            int[] tCategories,
            String tClass,
            String path,
            boolean permissive) {
        assertThat(auditLog).isNotNull();
        assertThat(auditLog.mGranted).isEqualTo(granted);
        assertThat(auditLog.mPermissions).isEqualTo(permissions);
        assertThat(auditLog.mSType).isEqualTo(sType);
        assertThat(auditLog.mSCategories).isEqualTo(sCategories);
        assertThat(auditLog.mTType).isEqualTo(tType);
        assertThat(auditLog.mTCategories).isEqualTo(tCategories);
        assertThat(auditLog.mTClass).isEqualTo(tClass);
        assertThat(auditLog.mPath).isEqualTo(path);
        assertThat(auditLog.mPermissive).isEqualTo(permissive);
    }
}
