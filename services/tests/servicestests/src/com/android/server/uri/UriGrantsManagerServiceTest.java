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
 * limitations under the License
 */

package com.android.server.uri;

import static com.android.server.uri.UriGrantsMockContext.FLAG_PERSISTABLE;
import static com.android.server.uri.UriGrantsMockContext.FLAG_PREFIX;
import static com.android.server.uri.UriGrantsMockContext.FLAG_READ;
import static com.android.server.uri.UriGrantsMockContext.PKG_CAMERA;
import static com.android.server.uri.UriGrantsMockContext.PKG_COMPLEX;
import static com.android.server.uri.UriGrantsMockContext.PKG_FORCE;
import static com.android.server.uri.UriGrantsMockContext.PKG_SOCIAL;
import static com.android.server.uri.UriGrantsMockContext.UID_PRIMARY_CAMERA;
import static com.android.server.uri.UriGrantsMockContext.UID_PRIMARY_COMPLEX;
import static com.android.server.uri.UriGrantsMockContext.UID_PRIMARY_FORCE;
import static com.android.server.uri.UriGrantsMockContext.UID_PRIMARY_PRIVATE;
import static com.android.server.uri.UriGrantsMockContext.UID_PRIMARY_PUBLIC;
import static com.android.server.uri.UriGrantsMockContext.UID_PRIMARY_SOCIAL;
import static com.android.server.uri.UriGrantsMockContext.UID_SECONDARY_CAMERA;
import static com.android.server.uri.UriGrantsMockContext.UID_SECONDARY_SOCIAL;
import static com.android.server.uri.UriGrantsMockContext.URI_FORCE;
import static com.android.server.uri.UriGrantsMockContext.URI_PHOTO_1;
import static com.android.server.uri.UriGrantsMockContext.URI_PRIVATE;
import static com.android.server.uri.UriGrantsMockContext.URI_PUBLIC;
import static com.android.server.uri.UriGrantsMockContext.USER_PRIMARY;
import static com.android.server.uri.UriGrantsMockContext.USER_SECONDARY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.UserHandle;
import android.util.ArraySet;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Set;

public class UriGrantsManagerServiceTest {
    private UriGrantsMockContext mContext;
    private UriGrantsManagerInternal mService;

    // we expect the following only during grant if a grant is expected
    private void verifyNoVisibilityGrant() {
        verify(mContext.mPmInternal, never())
                .grantImplicitAccess(anyInt(), any(), anyInt(), anyInt(), anyBoolean());
    }

    @Before
    public void setUp() throws Exception {
        mContext = new UriGrantsMockContext(InstrumentationRegistry.getContext());
        mService = UriGrantsManagerService.createForTest(mContext.getFilesDir()).getLocalService();
    }

    /**
     * Verify that a camera sharing a normally-private photo with a social media
     * app in the same user issues a grant.
     */
    @Test
    public void testNeeded_normal_sameUser() {
        final Intent intent = new Intent(Intent.ACTION_VIEW, URI_PHOTO_1).addFlags(FLAG_READ);
        final GrantUri expectedGrant = new GrantUri(USER_PRIMARY, URI_PHOTO_1, FLAG_READ);

        final NeededUriGrants needed = mService.checkGrantUriPermissionFromIntent(
                intent, UID_PRIMARY_CAMERA, PKG_SOCIAL, USER_PRIMARY);
        assertEquals(PKG_SOCIAL, needed.targetPkg);
        assertEquals(UID_PRIMARY_SOCIAL, needed.targetUid);
        assertEquals(FLAG_READ, needed.flags);
        assertEquals(asSet(expectedGrant), needed.uris);
        verifyNoVisibilityGrant();
    }

    /**
     * Verify that a camera sharing a normally-private photo with a social media
     * app in a different user issues a grant.
     */
    @Test
    public void testNeeded_normal_differentUser() {
        final Intent intent = new Intent(Intent.ACTION_VIEW, URI_PHOTO_1).addFlags(FLAG_READ);
        final GrantUri expectedGrant = new GrantUri(USER_PRIMARY, URI_PHOTO_1, FLAG_READ);

        final NeededUriGrants needed = mService.checkGrantUriPermissionFromIntent(
                intent, UID_PRIMARY_CAMERA, PKG_SOCIAL, USER_SECONDARY);
        assertEquals(PKG_SOCIAL, needed.targetPkg);
        assertEquals(UID_SECONDARY_SOCIAL, needed.targetUid);
        assertEquals(FLAG_READ, needed.flags);
        assertEquals(asSet(expectedGrant), needed.uris);
        verifyNoVisibilityGrant();
    }

    /**
     * No need to issue grants for public authorities.
     */
    @Test
    public void testNeeded_public() {
        final Intent intent = new Intent(Intent.ACTION_VIEW, URI_PUBLIC).addFlags(FLAG_READ);
        final NeededUriGrants needed = mService.checkGrantUriPermissionFromIntent(
                intent, UID_PRIMARY_PUBLIC, PKG_SOCIAL, USER_PRIMARY);
        assertNull(needed);
        verify(mContext.mPmInternal).grantImplicitAccess(eq(USER_PRIMARY), isNull(), eq(
                UserHandle.getAppId(UID_PRIMARY_SOCIAL)), eq(UID_PRIMARY_PUBLIC), eq(false));
    }

    /**
     * But we're willing to issue grants to public authorities when crossing
     * user boundaries.
     */
    @Test
    public void testNeeded_public_differentUser() {
        final Intent intent = new Intent(Intent.ACTION_VIEW, URI_PUBLIC).addFlags(FLAG_READ);
        final GrantUri expectedGrant = new GrantUri(USER_PRIMARY, URI_PUBLIC, FLAG_READ);

        final NeededUriGrants needed = mService.checkGrantUriPermissionFromIntent(
                intent, UID_PRIMARY_PUBLIC, PKG_SOCIAL, USER_SECONDARY);
        assertEquals(PKG_SOCIAL, needed.targetPkg);
        assertEquals(UID_SECONDARY_SOCIAL, needed.targetUid);
        assertEquals(FLAG_READ, needed.flags);
        assertEquals(asSet(expectedGrant), needed.uris);
        verifyNoVisibilityGrant();
    }

    /**
     * Refuse to issue grants for private authorities.
     */
    @Test
    public void testNeeded_private() {
        final Intent intent = new Intent(Intent.ACTION_VIEW, URI_PRIVATE).addFlags(FLAG_READ);
        try {
            mService.checkGrantUriPermissionFromIntent(
                    intent, UID_PRIMARY_PRIVATE, PKG_SOCIAL, USER_PRIMARY);
            fail();
        } catch (SecurityException expected) {
        }
    }

    /**
     * Verify that {@link ProviderInfo#forceUriPermissions} forces permission
     * grants, even when receiver already holds permission.
     */
    @Test
    public void testNeeded_force() {
        final Intent intent = new Intent(Intent.ACTION_VIEW, URI_FORCE)
                .addFlags(FLAG_READ);
        final NeededUriGrants needed = mService.checkGrantUriPermissionFromIntent(
                intent, UID_PRIMARY_FORCE, PKG_FORCE, USER_PRIMARY);
        assertEquals(asSet(new GrantUri(USER_PRIMARY, URI_FORCE, 0)), needed.uris);
    }

    /**
     * Verify that we can't grant permissions to top level of a provider with
     * complex permission model.
     */
    @Test
    public void testNeeded_complex_top() {
        final Uri uri = Uri.parse("content://" + PKG_COMPLEX + "/");
        {
            final Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(FLAG_READ | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            assertNull(mService.checkGrantUriPermissionFromIntent(
                    intent, UID_PRIMARY_COMPLEX, PKG_SOCIAL, USER_PRIMARY));
        }
        {
            final Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(FLAG_READ | FLAG_PREFIX);
            try {
                mService.checkGrantUriPermissionFromIntent(
                        intent, UID_PRIMARY_COMPLEX, PKG_SOCIAL, USER_PRIMARY);
                fail();
            } catch (SecurityException expected) {
            }
        }
        {
            final Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(FLAG_READ | FLAG_PERSISTABLE);
            try {
                mService.checkGrantUriPermissionFromIntent(
                        intent, UID_PRIMARY_COMPLEX, PKG_SOCIAL, USER_PRIMARY);
                fail();
            } catch (SecurityException expected) {
            }
        }
    }

    /**
     * Verify that we allow special cross-user grants to top level of a provider
     * that normally wouldn't allow it. Only basic permission modes are allowed;
     * advanced modes throw.
     */
    @Test
    public void testNeeded_complex_top_differentUser() {
        final Uri uri = Uri.parse("content://" + PKG_COMPLEX + "/");
        {
            final Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(FLAG_READ);
            final NeededUriGrants needed = mService.checkGrantUriPermissionFromIntent(
                    intent, UID_PRIMARY_COMPLEX, PKG_SOCIAL, USER_SECONDARY);
            assertEquals(FLAG_READ, needed.flags);
        }
        {
            final Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(FLAG_READ | FLAG_PREFIX);
            try {
                mService.checkGrantUriPermissionFromIntent(
                        intent, UID_PRIMARY_COMPLEX, PKG_SOCIAL, USER_SECONDARY);
                fail();
            } catch (SecurityException expected) {
            }
        }
        {
            final Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(FLAG_READ | FLAG_PERSISTABLE);
            try {
                mService.checkGrantUriPermissionFromIntent(
                        intent, UID_PRIMARY_COMPLEX, PKG_SOCIAL, USER_SECONDARY);
                fail();
            } catch (SecurityException expected) {
            }
        }
    }

    /**
     * Verify that we can grant permissions to middle level of a provider with
     * complex permission model.
     */
    @Test
    public void testNeeded_complex_middle() {
        final Uri uri = Uri.parse("content://" + PKG_COMPLEX + "/secure/12");
        {
            final Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(FLAG_READ);
            final NeededUriGrants needed = mService.checkGrantUriPermissionFromIntent(
                    intent, UID_PRIMARY_COMPLEX, PKG_SOCIAL, USER_PRIMARY);
            assertEquals(asSet(new GrantUri(USER_PRIMARY, uri, 0)), needed.uris);
        }
        {
            final Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(FLAG_READ | FLAG_PREFIX);
            final NeededUriGrants needed = mService.checkGrantUriPermissionFromIntent(
                    intent, UID_PRIMARY_COMPLEX, PKG_SOCIAL, USER_PRIMARY);
            assertEquals(asSet(new GrantUri(USER_PRIMARY, uri, FLAG_PREFIX)), needed.uris);
        }
        {
            final Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(FLAG_READ | FLAG_PERSISTABLE);
            final NeededUriGrants needed = mService.checkGrantUriPermissionFromIntent(
                    intent, UID_PRIMARY_COMPLEX, PKG_SOCIAL, USER_PRIMARY);
            assertEquals(asSet(new GrantUri(USER_PRIMARY, uri, 0)), needed.uris);
        }
    }

    /**
     * Verify that when we try sending a list of mixed items that the actual
     * grants are verified based on the capabilities of the caller.
     */
    @Test
    public void testNeeded_mixedPersistable() {
        final ClipData clip = ClipData.newRawUri("test", URI_PHOTO_1);
        clip.addItem(new ClipData.Item(URI_PUBLIC));

        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(FLAG_READ | FLAG_PERSISTABLE);
        intent.setClipData(clip);

        {
            // The camera package shouldn't be able to see other packages or their providers,
            // so make sure the grant only succeeds for the camera's URIs.
            final NeededUriGrants nug = mService.checkGrantUriPermissionFromIntent(
                    intent, UID_PRIMARY_CAMERA, PKG_SOCIAL, USER_PRIMARY);
            if (nug != null && nug.uris != null) {
                for (GrantUri gu : nug.uris) {
                    if (!gu.uri.getAuthority().equals(PKG_CAMERA)) {
                        fail();
                    }
                }
            }
        }
        {
            // The camera package shouldn't be able to see other packages or their providers,
            // so make sure the grant only succeeds for the camera's URIs.
            final NeededUriGrants nug = mService.checkGrantUriPermissionFromIntent(
                    intent, UID_PRIMARY_CAMERA, PKG_SOCIAL, USER_SECONDARY);
            if (nug != null && nug.uris != null) {
                for (GrantUri gu : nug.uris) {
                    if (!gu.uri.getAuthority().equals(PKG_CAMERA)) {
                        fail();
                    }
                }
            }
        }
    }

    /**
     * Verify that two overlapping owners require separate grants and that they
     * don't interfere with each other.
     */
    @Test
    public void testGrant_overlap() {
        final Intent intent = new Intent(Intent.ACTION_VIEW, URI_PHOTO_1).addFlags(FLAG_READ);

        final UriPermissionOwner activity = new UriPermissionOwner(mService, "activity");
        final UriPermissionOwner service = new UriPermissionOwner(mService, "service");

        final GrantUri expectedGrant = new GrantUri(USER_PRIMARY, URI_PHOTO_1, FLAG_READ);

        // Grant read via activity and write via service
        mService.grantUriPermissionUncheckedFromIntent(mService.checkGrantUriPermissionFromIntent(
                intent, UID_PRIMARY_CAMERA, PKG_SOCIAL, USER_PRIMARY), activity);
        mService.grantUriPermissionUncheckedFromIntent(mService.checkGrantUriPermissionFromIntent(
                intent, UID_PRIMARY_CAMERA, PKG_SOCIAL, USER_PRIMARY), service);

        // Verify that everything is good with the world
        assertTrue(mService.checkUriPermission(expectedGrant, UID_PRIMARY_SOCIAL, FLAG_READ));

        // Finish activity; service should hold permission
        activity.removeUriPermissions();
        assertTrue(mService.checkUriPermission(expectedGrant, UID_PRIMARY_SOCIAL, FLAG_READ));

        // And finishing service should wrap things up
        service.removeUriPermissions();
        assertFalse(mService.checkUriPermission(expectedGrant, UID_PRIMARY_SOCIAL, FLAG_READ));
    }

    @Test
    public void testCheckAuthorityGrants() {
        final Intent intent = new Intent(Intent.ACTION_VIEW, URI_PHOTO_1).addFlags(FLAG_READ);
        final UriPermissionOwner owner = new UriPermissionOwner(mService, "primary");

        final ProviderInfo cameraInfo = mContext.mPmInternal.resolveContentProvider(
                PKG_CAMERA, 0, USER_PRIMARY);

        // By default no social can see any camera
        assertFalse(mService.checkAuthorityGrants(UID_PRIMARY_SOCIAL,
                cameraInfo, USER_PRIMARY, true));
        assertFalse(mService.checkAuthorityGrants(UID_PRIMARY_SOCIAL,
                cameraInfo, USER_SECONDARY, true));
        assertFalse(mService.checkAuthorityGrants(UID_SECONDARY_SOCIAL,
                cameraInfo, USER_PRIMARY, true));
        assertFalse(mService.checkAuthorityGrants(UID_SECONDARY_SOCIAL,
                cameraInfo, USER_SECONDARY, true));

        // Granting primary camera to primary social
        mService.grantUriPermissionUncheckedFromIntent(mService.checkGrantUriPermissionFromIntent(
                intent, UID_PRIMARY_CAMERA, PKG_SOCIAL, USER_PRIMARY), owner);
        assertTrue(mService.checkAuthorityGrants(UID_PRIMARY_SOCIAL,
                cameraInfo, USER_PRIMARY, true));
        assertFalse(mService.checkAuthorityGrants(UID_PRIMARY_SOCIAL,
                cameraInfo, USER_SECONDARY, true));
        assertFalse(mService.checkAuthorityGrants(UID_SECONDARY_SOCIAL,
                cameraInfo, USER_PRIMARY, true));
        assertFalse(mService.checkAuthorityGrants(UID_SECONDARY_SOCIAL,
                cameraInfo, USER_SECONDARY, true));

        // Granting secondary camera to primary social
        mService.grantUriPermissionUncheckedFromIntent(mService.checkGrantUriPermissionFromIntent(
                intent, UID_SECONDARY_CAMERA, PKG_SOCIAL, USER_PRIMARY), owner);
        assertTrue(mService.checkAuthorityGrants(UID_PRIMARY_SOCIAL,
                cameraInfo, USER_PRIMARY, true));
        assertTrue(mService.checkAuthorityGrants(UID_PRIMARY_SOCIAL,
                cameraInfo, USER_SECONDARY, true));
        assertFalse(mService.checkAuthorityGrants(UID_SECONDARY_SOCIAL,
                cameraInfo, USER_PRIMARY, true));
        assertFalse(mService.checkAuthorityGrants(UID_SECONDARY_SOCIAL,
                cameraInfo, USER_SECONDARY, true));

        // And releasing the grant means we lose access
        owner.removeUriPermissions();
        assertFalse(mService.checkAuthorityGrants(UID_PRIMARY_SOCIAL,
                cameraInfo, USER_PRIMARY, true));
        assertFalse(mService.checkAuthorityGrants(UID_PRIMARY_SOCIAL,
                cameraInfo, USER_SECONDARY, true));
    }

    private static <T> Set<T> asSet(T... values) {
        return new ArraySet<T>(Arrays.asList(values));
    }
}
