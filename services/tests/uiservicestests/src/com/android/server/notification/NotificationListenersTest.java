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
package com.android.server.notification;

import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_ALERTING;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_CONVERSATIONS;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_ONGOING;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_SILENT;

import static com.android.server.notification.NotificationManagerService.NotificationListeners.TAG_REQUESTED_LISTENERS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.content.ComponentName;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.VersionedPackage;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.NotificationListenerFilter;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.testing.TestableContext;
import android.util.ArraySet;
import android.util.Pair;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.internal.util.reflection.FieldSetter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

public class NotificationListenersTest extends UiServiceTestCase {

    @Mock
    private PackageManager mPm;
    @Mock
    private IPackageManager miPm;

    @Mock
    NotificationManagerService mNm;
    @Mock
    private INotificationManager mINm;
    private TestableContext mContext = spy(getContext());

    NotificationManagerService.NotificationListeners mListeners;

    private ComponentName mCn1 = new ComponentName("pkg", "pkg.cmp");
    private ComponentName mCn2 = new ComponentName("pkg2", "pkg2.cmp2");


    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        getContext().setMockPackageManager(mPm);
        doNothing().when(mContext).sendBroadcastAsUser(any(), any(), any());

        mListeners = spy(mNm.new NotificationListeners(
                mContext, new Object(), mock(ManagedServices.UserProfiles.class), miPm));
        when(mNm.getBinderService()).thenReturn(mINm);
    }

    @Test
    public void testReadExtraTag() throws Exception {
        String xml = "<" + TAG_REQUESTED_LISTENERS+ ">"
                + "<listener component=\"" + mCn1.flattenToString() + "\" user=\"0\">"
                + "<allowed types=\"7\" />"
                + "</listener>"
                + "<listener component=\"" + mCn2.flattenToString() + "\" user=\"10\">"
                + "<allowed types=\"4\" />"
                + "<disallowed pkg=\"pkg1\" uid=\"243\"/>"
                + "</listener>"
                + "</" + TAG_REQUESTED_LISTENERS + ">";

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.getBytes())), null);
        parser.nextTag();
        mListeners.readExtraTag(TAG_REQUESTED_LISTENERS, parser);

        validateListenersFromXml();
    }

    @Test
    public void testWriteExtraTag() throws Exception {
        NotificationListenerFilter nlf = new NotificationListenerFilter(7, new ArraySet<>());
        VersionedPackage a1 = new VersionedPackage("pkg1", 243);
        NotificationListenerFilter nlf2 =
                new NotificationListenerFilter(4, new ArraySet<>(new VersionedPackage[] {a1}));
        mListeners.setNotificationListenerFilter(Pair.create(mCn1, 0), nlf);
        mListeners.setNotificationListenerFilter(Pair.create(mCn2, 10), nlf2);

        TypedXmlSerializer serializer = Xml.newFastSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        serializer.startDocument(null, true);
        mListeners.writeExtraXmlTags(serializer);
        serializer.endDocument();
        serializer.flush();

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mListeners.readExtraTag("req_listeners", parser);

        validateListenersFromXml();
    }

    private void validateListenersFromXml() {
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn1, 0)).getTypes())
                .isEqualTo(7);
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn1, 0))
                .getDisallowedPackages())
                .isEmpty();

        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn2, 10)).getTypes())
                .isEqualTo(4);
        VersionedPackage a1 = new VersionedPackage("pkg1", 243);
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn2, 10))
                .getDisallowedPackages())
                .contains(a1);
    }

    @Test
    public void testOnUserRemoved() {
        NotificationListenerFilter nlf = new NotificationListenerFilter(7, new ArraySet<>());
        VersionedPackage a1 = new VersionedPackage("pkg1", 243);
        NotificationListenerFilter nlf2 =
                new NotificationListenerFilter(4, new ArraySet<>(new VersionedPackage[] {a1}));
        mListeners.setNotificationListenerFilter(Pair.create(mCn1, 0), nlf);
        mListeners.setNotificationListenerFilter(Pair.create(mCn2, 10), nlf2);

        mListeners.onUserRemoved(0);

        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn1, 0))).isNull();
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn2, 10)).getTypes())
                .isEqualTo(4);
    }

    @Test
    public void testEnsureFilters_newServiceNoMetadata() {
        ServiceInfo si = new ServiceInfo();
        si.packageName = "new2";
        si.name = "comp2";

        mListeners.ensureFilters(si, 0);

        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn2, 0))).isNull();
    }

    @Test
    public void testEnsureFilters_preExisting() {
        // one exists already, say from xml
        VersionedPackage a1 = new VersionedPackage("pkg1", 243);
        NotificationListenerFilter nlf =
                new NotificationListenerFilter(4, new ArraySet<>(new VersionedPackage[] {a1}));
        mListeners.setNotificationListenerFilter(Pair.create(mCn2, 0), nlf);
        ServiceInfo siOld = new ServiceInfo();
        siOld.packageName = mCn2.getPackageName();
        siOld.name = mCn2.getClassName();

        mListeners.ensureFilters(siOld, 0);

        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn2, 0))).isEqualTo(nlf);
    }

    @Test
    public void testEnsureFilters_newServiceWithMetadata() {
        ServiceInfo si = new ServiceInfo();
        si.packageName = "new";
        si.name = "comp";
        si.metaData = new Bundle();
        si.metaData.putString(NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES, "1|2");

        mListeners.ensureFilters(si, 0);

        assertThat(mListeners.getNotificationListenerFilter(
                Pair.create(si.getComponentName(), 0)).getTypes())
                .isEqualTo(FLAG_FILTER_TYPE_CONVERSATIONS | FLAG_FILTER_TYPE_ALERTING);
    }

    @Test
    public void testEnsureFilters_newServiceWithMetadata_namesNotNumbers() {
        ServiceInfo si = new ServiceInfo();
        si.packageName = "new";
        si.name = "comp";
        si.metaData = new Bundle();
        si.metaData.putString(NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES,
                "conversations|ALERTING");

        mListeners.ensureFilters(si, 0);

        assertThat(mListeners.getNotificationListenerFilter(
                Pair.create(si.getComponentName(), 0)).getTypes())
                .isEqualTo(FLAG_FILTER_TYPE_CONVERSATIONS | FLAG_FILTER_TYPE_ALERTING);
    }

    @Test
    public void testEnsureFilters_newServiceWithMetadata_onlyOneListed() {
        ServiceInfo si = new ServiceInfo();
        si.packageName = "new";
        si.name = "comp";
        si.metaData = new Bundle();
        si.metaData.putInt(NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES, 2);

        mListeners.ensureFilters(si, 0);

        assertThat(mListeners.getNotificationListenerFilter(
                Pair.create(si.getComponentName(), 0)).getTypes())
                .isEqualTo(FLAG_FILTER_TYPE_ALERTING);
    }

    @Test
    public void testEnsureFilters_newServiceWithMetadata_disabledTypes() {
        ServiceInfo si = new ServiceInfo();
        si.packageName = "new";
        si.name = "comp";
        si.metaData = new Bundle();
        si.metaData.putString(NotificationListenerService.META_DATA_DISABLED_FILTER_TYPES, "1|2");

        mListeners.ensureFilters(si, 0);

        assertThat(mListeners.getNotificationListenerFilter(
                Pair.create(si.getComponentName(), 0)).getTypes())
                .isEqualTo(FLAG_FILTER_TYPE_SILENT | FLAG_FILTER_TYPE_ONGOING);
    }

    @Test
    public void testEnsureFilters_newServiceWithMetadata_disabledTypes_mixedText() {
        ServiceInfo si = new ServiceInfo();
        si.packageName = "new";
        si.name = "comp";
        si.metaData = new Bundle();
        si.metaData.putString(NotificationListenerService.META_DATA_DISABLED_FILTER_TYPES,
                "1|alerting");

        mListeners.ensureFilters(si, 0);

        assertThat(mListeners.getNotificationListenerFilter(
                Pair.create(si.getComponentName(), 0)).getTypes())
                .isEqualTo(FLAG_FILTER_TYPE_SILENT | FLAG_FILTER_TYPE_ONGOING);
    }

    @Test
    public void testEnsureFilters_newServiceWithMetadata_metaDataDisagrees() {
        ServiceInfo si = new ServiceInfo();
        si.packageName = "new";
        si.name = "comp";
        si.metaData = new Bundle();
        si.metaData.putString(NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES, "1|2");
        si.metaData.putInt(NotificationListenerService.META_DATA_DISABLED_FILTER_TYPES, 1);

        mListeners.ensureFilters(si, 0);

        assertThat(mListeners.getNotificationListenerFilter(
                Pair.create(si.getComponentName(), 0)).getTypes())
                .isEqualTo(FLAG_FILTER_TYPE_ALERTING);
    }

    @Test
    public void testEnsureFilters_newServiceWithEmptyMetadata() {
        ServiceInfo si = new ServiceInfo();
        si.packageName = "new";
        si.name = "comp";
        si.metaData = new Bundle();
        si.metaData.putString(NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES, "");

        mListeners.ensureFilters(si, 0);

        assertThat(mListeners.getNotificationListenerFilter(
                Pair.create(si.getComponentName(), 0)).getTypes())
                .isEqualTo(0);
    }

    @Test
    public void testOnPackageChanged() {
        NotificationListenerFilter nlf = new NotificationListenerFilter(7, new ArraySet<>());
        VersionedPackage a1 = new VersionedPackage("pkg1", 243);
        NotificationListenerFilter nlf2 =
                new NotificationListenerFilter(4, new ArraySet<>(new VersionedPackage[] {a1}));
        mListeners.setNotificationListenerFilter(Pair.create(mCn1, 0), nlf);
        mListeners.setNotificationListenerFilter(Pair.create(mCn2, 10), nlf2);

        String[] pkgs = new String[] {mCn1.getPackageName()};
        int[] uids = new int[] {1};
        mListeners.onPackagesChanged(false, pkgs, uids);

        // not removing; no change
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn1, 0)).getTypes())
                .isEqualTo(7);
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn2, 10)).getTypes())
                .isEqualTo(4);
    }

    @Test
    public void testOnPackageChanged_removing() {
        NotificationListenerFilter nlf = new NotificationListenerFilter(7, new ArraySet<>());
        VersionedPackage a1 = new VersionedPackage("pkg1", 243);
        NotificationListenerFilter nlf2 =
                new NotificationListenerFilter(4, new ArraySet<>(new VersionedPackage[] {a1}));
        mListeners.setNotificationListenerFilter(Pair.create(mCn1, 0), nlf);
        mListeners.setNotificationListenerFilter(Pair.create(mCn2, 0), nlf2);

        String[] pkgs = new String[] {mCn1.getPackageName()};
        int[] uids = new int[] {1};
        mListeners.onPackagesChanged(true, pkgs, uids);

        // only mCn1 removed
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn1, 0))).isNull();
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn2, 0)).getTypes())
                .isEqualTo(4);
    }

    @Test
    public void testOnPackageChanged_removingDisallowedPackage() {
        NotificationListenerFilter nlf = new NotificationListenerFilter(7, new ArraySet<>());
        VersionedPackage a1 = new VersionedPackage("pkg1", 243);
        NotificationListenerFilter nlf2 =
                new NotificationListenerFilter(4, new ArraySet<>(new VersionedPackage[] {a1}));
        mListeners.setNotificationListenerFilter(Pair.create(mCn1, 0), nlf);
        mListeners.setNotificationListenerFilter(Pair.create(mCn2, 0), nlf2);

        String[] pkgs = new String[] {"pkg1"};
        int[] uids = new int[] {243};
        mListeners.onPackagesChanged(true, pkgs, uids);

        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn1, 0))
                .getDisallowedPackages()).isEmpty();
    }

    @Test
    public void testBroadcastUsers() {
        int userId = 0;
        mListeners.setPackageOrComponentEnabled(mCn1.flattenToString(), userId, true, false, true);

        verify(mContext).sendBroadcastAsUser(
                any(), eq(UserHandle.of(userId)), nullable(String.class));
    }

    @Test
    public void testNotifyPostedLockedInLockdownMode() {
        NotificationRecord r = mock(NotificationRecord.class);
        NotificationRecord old = mock(NotificationRecord.class);

        // before the lockdown mode
        when(mNm.isInLockDownMode()).thenReturn(false);
        mListeners.notifyPostedLocked(r, old, true);
        mListeners.notifyPostedLocked(r, old, false);
        verify(r, atLeast(2)).getSbn();

        // in the lockdown mode
        reset(r);
        reset(old);
        when(mNm.isInLockDownMode()).thenReturn(true);
        mListeners.notifyPostedLocked(r, old, true);
        mListeners.notifyPostedLocked(r, old, false);
        verify(r, never()).getSbn();
    }

    @Test
    public void testnotifyRankingUpdateLockedInLockdownMode() {
        List chn = mock(List.class);

        // before the lockdown mode
        when(mNm.isInLockDownMode()).thenReturn(false);
        mListeners.notifyRankingUpdateLocked(chn);
        verify(chn, atLeast(1)).size();

        // in the lockdown mode
        reset(chn);
        when(mNm.isInLockDownMode()).thenReturn(true);
        mListeners.notifyRankingUpdateLocked(chn);
        verify(chn, never()).size();
    }

    @Test
    public void testNotifyRemovedLockedInLockdownMode() throws NoSuchFieldException {
        NotificationRecord r = mock(NotificationRecord.class);
        NotificationStats rs = mock(NotificationStats.class);
        StatusBarNotification sbn = mock(StatusBarNotification.class);
        FieldSetter.setField(mNm,
                NotificationManagerService.class.getDeclaredField("mHandler"),
                mock(NotificationManagerService.WorkerHandler.class));

        // before the lockdown mode
        when(mNm.isInLockDownMode()).thenReturn(false);
        when(r.getSbn()).thenReturn(sbn);
        mListeners.notifyRemovedLocked(r, 0, rs);
        mListeners.notifyRemovedLocked(r, 0, rs);
        verify(r, atLeast(2)).getSbn();

        // in the lockdown mode
        reset(r);
        reset(rs);
        when(mNm.isInLockDownMode()).thenReturn(true);
        when(r.getSbn()).thenReturn(sbn);
        mListeners.notifyRemovedLocked(r, 0, rs);
        mListeners.notifyRemovedLocked(r, 0, rs);
        verify(r, never()).getSbn();
    }
}
