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

import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;
import static com.android.server.selinux.SelinuxAuditLogBuilder.toCategories;

import static com.google.common.truth.Truth.assertThat;

import android.provider.DeviceConfig;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.selinux.SelinuxAuditLogBuilder.SelinuxAuditLog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.regex.Matcher;

@RunWith(AndroidJUnit4.class)
public class SelinuxAuditLogsBuilderTest {

    private static final String TEST_DOMAIN = "test_domain";

    private SelinuxAuditLogBuilder mAuditLogBuilder;
    private Matcher mScontextMatcher;
    private Matcher mTcontextMatcher;
    private Matcher mPathMatcher;

    @Before
    public void setUp() {
        runWithShellPermissionIdentity(
                () ->
                        DeviceConfig.setLocalOverride(
                                DeviceConfig.NAMESPACE_ADSERVICES,
                                SelinuxAuditLogBuilder.CONFIG_SELINUX_AUDIT_DOMAIN,
                                TEST_DOMAIN));

        mAuditLogBuilder = new SelinuxAuditLogBuilder();
        mScontextMatcher = mAuditLogBuilder.mScontextMatcher;
        mTcontextMatcher = mAuditLogBuilder.mTcontextMatcher;
        mPathMatcher = mAuditLogBuilder.mPathMatcher;
    }

    @After
    public void tearDown() {
        runWithShellPermissionIdentity(() -> DeviceConfig.clearAllLocalOverrides());
    }

    @Test
    public void testMatcher_scontext() {
        assertThat(mScontextMatcher.reset("u:r:" + TEST_DOMAIN + ":s0").matches()).isTrue();
        assertThat(mScontextMatcher.group("stype")).isEqualTo(TEST_DOMAIN);
        assertThat(mScontextMatcher.group("scategories")).isNull();

        assertThat(mScontextMatcher.reset("u:r:" + TEST_DOMAIN + ":s0:c123,c456").matches())
                .isTrue();
        assertThat(mScontextMatcher.group("stype")).isEqualTo(TEST_DOMAIN);
        assertThat(toCategories(mScontextMatcher.group("scategories")))
                .isEqualTo(new int[] {123, 456});

        assertThat(mScontextMatcher.reset("u:r:wrong_domain:s0").matches()).isFalse();
        assertThat(mScontextMatcher.reset("u:object_r:" + TEST_DOMAIN + ":s0").matches()).isFalse();
        assertThat(mScontextMatcher.reset("u:r:" + TEST_DOMAIN + ":s0:p123").matches()).isFalse();
    }

    @Test
    public void testMatcher_tcontext() {
        assertThat(mTcontextMatcher.reset("u:object_r:target_type:s0").matches()).isTrue();
        assertThat(mTcontextMatcher.group("ttype")).isEqualTo("target_type");
        assertThat(mTcontextMatcher.group("tcategories")).isNull();

        assertThat(mTcontextMatcher.reset("u:object_r:target_type2:s0:c666").matches()).isTrue();
        assertThat(mTcontextMatcher.group("ttype")).isEqualTo("target_type2");
        assertThat(toCategories(mTcontextMatcher.group("tcategories"))).isEqualTo(new int[] {666});

        assertThat(mTcontextMatcher.reset("u:r:target_type:s0").matches()).isFalse();
        assertThat(mTcontextMatcher.reset("u:r:" + TEST_DOMAIN + ":s0:x456").matches()).isFalse();
    }

    @Test
    public void testMatcher_path() {
        assertThat(mPathMatcher.reset("\"/data\"").matches()).isTrue();
        assertThat(mPathMatcher.group("path")).isEqualTo("/data");
        assertThat(mPathMatcher.reset("\"/data/local\"").matches()).isTrue();
        assertThat(mPathMatcher.group("path")).isEqualTo("/data/local");
        assertThat(mPathMatcher.reset("\"/data/local/tmp\"").matches()).isTrue();
        assertThat(mPathMatcher.group("path")).isEqualTo("/data/local");

        assertThat(mPathMatcher.reset("\"/data/local").matches()).isFalse();
        assertThat(mPathMatcher.reset("\"_data_local\"").matches()).isFalse();
    }

    @Test
    public void testMatcher_scontextDefaultConfig() {
        runWithShellPermissionIdentity(
                () ->
                        DeviceConfig.clearLocalOverride(
                                DeviceConfig.NAMESPACE_ADSERVICES,
                                SelinuxAuditLogBuilder.CONFIG_SELINUX_AUDIT_DOMAIN));

        Matcher scontexMatcher = new SelinuxAuditLogBuilder().mScontextMatcher;

        assertThat(scontexMatcher.reset("u:r:" + TEST_DOMAIN + ":s0").matches()).isFalse();
        assertThat(scontexMatcher.reset("u:r:" + TEST_DOMAIN + ":s0:c123,c456").matches())
                .isFalse();
        assertThat(scontexMatcher.reset("u:r:wrong_domain:s0").matches()).isFalse();
    }

    @Test
    public void testSelinuxAuditLogsBuilder_noOptionals() {
        mAuditLogBuilder.reset(
                "granted { p } scontext=u:r:"
                        + TEST_DOMAIN
                        + ":s0 tcontext=u:object_r:t:s0"
                        + " tclass=c");
        assertAuditLog(mAuditLogBuilder.build(), true, new String[] {"p"}, TEST_DOMAIN, "t", "c");

        mAuditLogBuilder.reset(
                "tclass=c2 granted { p2 } tcontext=u:object_r:t2:s0"
                        + " scontext=u:r:"
                        + TEST_DOMAIN
                        + ":s0");
        assertAuditLog(
                mAuditLogBuilder.build(), true, new String[] {"p2"}, TEST_DOMAIN, "t2", "c2");
    }

    @Test
    public void testSelinuxAuditLogsBuilder_withCategories() {
        mAuditLogBuilder.reset(
                "granted { p } scontext=u:r:"
                        + TEST_DOMAIN
                        + ":s0:c123"
                        + " tcontext=u:object_r:t:s0:c456,c666 tclass=c");
        assertAuditLog(
                mAuditLogBuilder.build(),
                true,
                new String[] {"p"},
                TEST_DOMAIN,
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
                "granted { p } scontext=u:r:"
                        + TEST_DOMAIN
                        + ":s0 path=\"/very/long/path\""
                        + " tcontext=u:object_r:t:s0 tclass=c");
        assertAuditLog(
                mAuditLogBuilder.build(),
                true,
                new String[] {"p"},
                TEST_DOMAIN,
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
                "granted { p } scontext=u:r:"
                        + TEST_DOMAIN
                        + ":s0 permissive=0"
                        + " tcontext=u:object_r:t:s0 tclass=c");
        assertAuditLog(
                mAuditLogBuilder.build(),
                true,
                new String[] {"p"},
                TEST_DOMAIN,
                null,
                "t",
                null,
                "c",
                null,
                false);

        mAuditLogBuilder.reset(
                "granted { p } scontext=u:r:"
                        + TEST_DOMAIN
                        + ":s0 tcontext=u:object_r:t:s0 tclass=c"
                        + " permissive=1");
        assertAuditLog(
                mAuditLogBuilder.build(),
                true,
                new String[] {"p"},
                TEST_DOMAIN,
                null,
                "t",
                null,
                "c",
                null,
                true);
    }

    @Test
    public void testSelinuxAuditLogsBuilder_wrongConfig() {
        String notARegexDomain = "not]a[regex";
        runWithShellPermissionIdentity(
                () ->
                        DeviceConfig.setLocalOverride(
                                DeviceConfig.NAMESPACE_ADSERVICES,
                                SelinuxAuditLogBuilder.CONFIG_SELINUX_AUDIT_DOMAIN,
                                notARegexDomain));
        SelinuxAuditLogBuilder noOpBuilder = new SelinuxAuditLogBuilder();

        noOpBuilder.reset(
                "granted { p } scontext=u:r:"
                        + TEST_DOMAIN
                        + ":s0 tcontext=u:object_r:t:s0 tclass=c");
        assertThat(noOpBuilder.build()).isNull();
        noOpBuilder.reset(
                "granted { p } scontext=u:r:"
                        + TEST_DOMAIN
                        + ":s0:c123 tcontext=u:object_r:t:s0:c456,c666 tclass=c");
        assertThat(noOpBuilder.build()).isNull();
        noOpBuilder.reset(
                "granted { p } scontext=u:r:"
                        + TEST_DOMAIN
                        + ":s0 path=\"/very/long/path\""
                        + " tcontext=u:object_r:t:s0 tclass=c");
        assertThat(noOpBuilder.build()).isNull();
        noOpBuilder.reset(
                "granted { p } scontext=u:r:"
                        + TEST_DOMAIN
                        + ":s0 permissive=0 tcontext=u:object_r:t:s0 tclass=c");
        assertThat(noOpBuilder.build()).isNull();
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
