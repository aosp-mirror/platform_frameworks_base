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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.content.ComponentName;
import android.content.pm.VersionedPackage;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.service.notification.NotificationListenerFilter;
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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
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

    NotificationManagerService.NotificationListeners mListeners;

    private ComponentName mCn1 = new ComponentName("pkg", "pkg.cmp");
    private ComponentName mCn2 = new ComponentName("pkg2", "pkg2.cmp2");


    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        getContext().setMockPackageManager(mPm);

        mListeners = spy(mNm.new NotificationListeners(
                mContext, new Object(), mock(ManagedServices.UserProfiles.class), miPm));
        when(mNm.getBinderService()).thenReturn(mINm);
    }

    @Test
    public void testReadExtraTag() throws Exception {
        String xml = "<req_listeners>"
                + "<listener component=\"" + mCn1.flattenToString() + "\" user=\"0\">"
                + "<allowed types=\"7\" />"
                + "</listener>"
                + "<listener component=\"" + mCn2.flattenToString() + "\" user=\"10\">"
                + "<allowed types=\"4\" />"
                + "<disallowed pkg=\"pkg1\" uid=\"243\"/>"
                + "</listener>"
                + "</req_listeners>";

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.getBytes())), null);
        parser.nextTag();
        mListeners.readExtraTag("req_listeners", parser);

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
    public void testOnUserUnlocked() {
        // one exists already, say from xml
        VersionedPackage a1 = new VersionedPackage("pkg1", 243);
        NotificationListenerFilter nlf =
                new NotificationListenerFilter(4, new ArraySet<>(new VersionedPackage[] {a1}));
        mListeners.setNotificationListenerFilter(Pair.create(mCn2, 0), nlf);

        // new service exists or backfilling on upgrade to S
        ServiceInfo si = new ServiceInfo();
        si.permission = mListeners.getConfig().bindPermission;
        si.packageName = "new";
        si.name = "comp";
        ResolveInfo ri = new ResolveInfo();
        ri.serviceInfo = si;

        // incorrect service
        ServiceInfo si2 = new ServiceInfo();
        ResolveInfo ri2 = new ResolveInfo();
        ri2.serviceInfo = si2;
        si2.packageName = "new2";
        si2.name = "comp2";

        List<ResolveInfo> ris = new ArrayList<>();
        ris.add(ri);
        ris.add(ri2);

        when(mPm.queryIntentServicesAsUser(any(), anyInt(), anyInt())).thenReturn(ris);

        mListeners.onUserUnlocked(0);

        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn2, 0)).getTypes())
                .isEqualTo(4);
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn2, 0))
                .getDisallowedPackages())
                .contains(a1);

        assertThat(mListeners.getNotificationListenerFilter(
                Pair.create(si.getComponentName(), 0)).getTypes())
                .isEqualTo(15);
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(si.getComponentName(), 0))
                .getDisallowedPackages())
                .isEmpty();

        assertThat(mListeners.getNotificationListenerFilter(Pair.create(si2.getComponentName(), 0)))
                .isNull();

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

}
