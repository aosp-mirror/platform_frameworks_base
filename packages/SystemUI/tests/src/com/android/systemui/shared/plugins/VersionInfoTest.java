/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.shared.plugins;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.OverlayPlugin;
import com.android.systemui.plugins.annotations.Requires;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.qs.QS.HeightListener;
import com.android.systemui.shared.plugins.VersionInfo.InvalidVersionException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

@SmallTest
public class VersionInfoTest extends SysuiTestCase {

    @Rule
    public ExpectedException mThrown = ExpectedException.none();

    @Test
    public void testHasInfo() {
        VersionInfo info = new VersionInfo();
        info.addClass(VersionInfoTest.class); // Has no annotations.
        assertFalse(info.hasVersionInfo());

        info.addClass(OverlayPlugin.class);
        assertTrue(info.hasVersionInfo());
    }

    @Test
    public void testSingleProvides() {
        VersionInfo overlay = new VersionInfo().addClass(OverlayPlugin.class);
        VersionInfo impl = new VersionInfo().addClass(OverlayImpl.class);
        overlay.checkVersion(impl);
    }

    @Test
    public void testIncorrectVersion() {
        VersionInfo overlay = new VersionInfo().addClass(OverlayPlugin.class);
        VersionInfo impl = new VersionInfo().addClass(OverlayImplIncorrectVersion.class);
        mThrown.expect(InvalidVersionException.class);
        overlay.checkVersion(impl);
    }

    @Test
    public void testMissingRequired() {
        VersionInfo overlay = new VersionInfo().addClass(OverlayPlugin.class);
        VersionInfo impl = new VersionInfo();
        mThrown.expect(InvalidVersionException.class);
        overlay.checkVersion(impl);
    }

    @Test
    public void testMissingDependencies() {
        VersionInfo overlay = new VersionInfo().addClass(QS.class);
        VersionInfo impl = new VersionInfo().addClass(QSImplNoDeps.class);
        mThrown.expect(InvalidVersionException.class);
        overlay.checkVersion(impl);
    }

    @Test
    public void testHasDependencies() {
        VersionInfo overlay = new VersionInfo().addClass(QS.class);
        VersionInfo impl = new VersionInfo().addClass(QSImpl.class);
        overlay.checkVersion(impl);
    }

    @Requires(target = OverlayPlugin.class, version = OverlayPlugin.VERSION)
    public static class OverlayImpl {
    }

    @Requires(target = OverlayPlugin.class, version = 0)
    public static class OverlayImplIncorrectVersion {
    }

    @Requires(target = QS.class, version = QS.VERSION)
    public static class QSImplNoDeps {
    }

    @Requires(target = QS.class, version = QS.VERSION)
    @Requires(target = HeightListener.class, version = HeightListener.VERSION)
    @Requires(target = DetailAdapter.class, version = DetailAdapter.VERSION)
    public static class QSImpl {
    }
}
