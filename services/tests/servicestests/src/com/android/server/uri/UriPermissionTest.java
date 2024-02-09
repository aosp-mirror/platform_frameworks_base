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
import static com.android.server.uri.UriGrantsMockContext.FLAG_READ;
import static com.android.server.uri.UriGrantsMockContext.FLAG_WRITE;
import static com.android.server.uri.UriGrantsMockContext.PKG_CAMERA;
import static com.android.server.uri.UriGrantsMockContext.PKG_SOCIAL;
import static com.android.server.uri.UriGrantsMockContext.UID_PRIMARY_SOCIAL;
import static com.android.server.uri.UriGrantsMockContext.URI_PHOTO_1;
import static com.android.server.uri.UriGrantsMockContext.URI_PHOTO_2;
import static com.android.server.uri.UriGrantsMockContext.USER_PRIMARY;
import static com.android.server.uri.UriPermission.INVALID_TIME;
import static com.android.server.uri.UriPermission.STRENGTH_GLOBAL;
import static com.android.server.uri.UriPermission.STRENGTH_NONE;
import static com.android.server.uri.UriPermission.STRENGTH_OWNED;
import static com.android.server.uri.UriPermission.STRENGTH_PERSISTABLE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.platform.test.ravenwood.RavenwoodRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class UriPermissionTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Mock
    private UriGrantsManagerInternal mService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testNone() {
        final GrantUri grant = new GrantUri(USER_PRIMARY, URI_PHOTO_1, FLAG_READ);
        final UriPermission perm = new UriPermission(PKG_CAMERA,
                PKG_SOCIAL, UID_PRIMARY_SOCIAL, grant);
        assertEquals(STRENGTH_NONE, perm.getStrength(FLAG_READ));
    }

    @Test
    public void testGlobal() {
        final GrantUri grant = new GrantUri(USER_PRIMARY, URI_PHOTO_1, FLAG_READ);
        final UriPermission perm = new UriPermission(PKG_CAMERA,
                PKG_SOCIAL, UID_PRIMARY_SOCIAL, grant);

        assertFalse(perm.grantModes(FLAG_READ, null));
        assertEquals(STRENGTH_GLOBAL, perm.getStrength(FLAG_READ));

        assertFalse(perm.revokeModes(FLAG_READ, true));
        assertEquals(STRENGTH_NONE, perm.getStrength(FLAG_READ));
    }

    @Test
    public void testOwned() {
        final GrantUri grant = new GrantUri(USER_PRIMARY, URI_PHOTO_1, FLAG_READ);
        final UriPermission perm = new UriPermission(PKG_CAMERA,
                PKG_SOCIAL, UID_PRIMARY_SOCIAL, grant);
        final UriPermissionOwner owner = new UriPermissionOwner(mService, "test");

        assertFalse(perm.grantModes(FLAG_READ, owner));
        assertEquals(STRENGTH_OWNED, perm.getStrength(FLAG_READ));

        assertFalse(perm.revokeModes(FLAG_READ, false));
        assertEquals(STRENGTH_OWNED, perm.getStrength(FLAG_READ));

        assertFalse(perm.revokeModes(FLAG_READ, true));
        assertEquals(STRENGTH_NONE, perm.getStrength(FLAG_READ));
    }

    @Test
    public void testOverlap() {
        final GrantUri grant1 = new GrantUri(USER_PRIMARY, URI_PHOTO_1, FLAG_READ);
        final GrantUri grant2 = new GrantUri(USER_PRIMARY, URI_PHOTO_2, FLAG_READ);

        final UriPermission photo1 = new UriPermission(PKG_CAMERA,
                PKG_SOCIAL, UID_PRIMARY_SOCIAL, grant1);
        final UriPermission photo2 = new UriPermission(PKG_CAMERA,
                PKG_SOCIAL, UID_PRIMARY_SOCIAL, grant2);

        // Verify behavior when we have multiple owners within the same app
        final UriPermissionOwner activity = new UriPermissionOwner(mService, "activity");
        final UriPermissionOwner service = new UriPermissionOwner(mService, "service");

        photo1.grantModes(FLAG_READ | FLAG_WRITE, activity);
        photo1.grantModes(FLAG_READ, service);
        photo2.grantModes(FLAG_READ, activity);
        photo2.grantModes(FLAG_WRITE, null);

        assertEquals(FLAG_READ | FLAG_WRITE, photo1.modeFlags);
        assertEquals(FLAG_READ | FLAG_WRITE, photo2.modeFlags);

        // Shutting down activity should only trim away write access
        activity.removeUriPermissions();
        assertEquals(FLAG_READ, photo1.modeFlags);
        assertEquals(FLAG_WRITE, photo2.modeFlags);

        // Shutting down service should bring everything else down
        service.removeUriPermissions();
        assertEquals(0, photo1.modeFlags);
        assertEquals(FLAG_WRITE, photo2.modeFlags);
    }

    @Test
    public void testPersist() {
        final GrantUri grant = new GrantUri(USER_PRIMARY, URI_PHOTO_1, FLAG_READ);
        final UriPermission perm = new UriPermission(PKG_CAMERA,
                PKG_SOCIAL, UID_PRIMARY_SOCIAL, grant);

        assertFalse(perm.grantModes(FLAG_READ, null));
        assertFalse(perm.grantModes(FLAG_WRITE | FLAG_PERSISTABLE, null));
        assertEquals(STRENGTH_GLOBAL, perm.getStrength(FLAG_READ));
        assertEquals(STRENGTH_PERSISTABLE, perm.getStrength(FLAG_WRITE));

        // Verify behavior of non-persistable mode; nothing happens
        {
            assertFalse(perm.takePersistableModes(FLAG_READ));
            assertEquals(0, perm.persistedModeFlags);

            assertFalse(perm.releasePersistableModes(FLAG_READ));
            assertEquals(0, perm.persistedModeFlags);
        }

        // Verify behavior of persistable mode
        {
            assertEquals(FLAG_WRITE, perm.persistableModeFlags);
            assertEquals(0, perm.persistedModeFlags);
            assertTrue(perm.takePersistableModes(FLAG_WRITE));
            assertEquals(FLAG_WRITE, perm.persistableModeFlags);
            assertEquals(FLAG_WRITE, perm.persistedModeFlags);

            // Attempting to take a second time should "touch" timestamp, per public API
            // docs on ContentResolver.takePersistableUriPermission()
            final long createTime = perm.persistedCreateTime;
            SystemClock.sleep(10);
            assertFalse(perm.takePersistableModes(FLAG_WRITE));
            assertNotEquals(createTime, perm.persistedCreateTime);

            assertTrue(perm.releasePersistableModes(FLAG_WRITE));
            assertEquals(FLAG_WRITE, perm.persistableModeFlags);
            assertEquals(0, perm.persistedModeFlags);
            assertEquals(INVALID_TIME, perm.persistedCreateTime);

            // Attempting to release a second time should be a no-op
            assertFalse(perm.releasePersistableModes(FLAG_WRITE));

            // We should still be able to take again
            assertTrue(perm.takePersistableModes(FLAG_WRITE));
            assertEquals(FLAG_WRITE, perm.persistableModeFlags);
            assertEquals(FLAG_WRITE, perm.persistedModeFlags);
        }
    }
}
