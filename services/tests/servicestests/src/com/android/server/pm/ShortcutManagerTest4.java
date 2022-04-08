/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.server.pm;

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertBundlesEqual;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertWith;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.list;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.makeBundle;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.makePersistableBundle;

import android.content.Intent;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.test.suitebuilder.annotation.SmallTest;

@SmallTest
public class ShortcutManagerTest4 extends BaseShortcutManagerTest {

    private static Bundle sIntentExtras = makeBundle(
            "key{\u0000}", "value{\u0000}",
            "key{\u0001}", "value{\u0001}",
            "key{\u001f}", "value{\u001f}",
            "key{\u007f}", "value{\u007f}",

            "key{\ud800\udc00}", "value{\ud800\udc00}",
            "key{\ud801\udc01}", "value{\ud801\udc01}",
            "key{\udbff\udfff}", "value{\udbff\udfff}",

            "key{\ud801}x", 1, // broken surrogate pair
            "key{\uDC01}\"x", 2, // broken surrogate pair

            "x1", "value{\ud801}x", // broken surrogate pair
            "x2", "value{\uDC01}\"x" // broken surrogate pair
    );

    // Same as above, except broken surrogate pairs are replaced with '?'s.
    private static Bundle sIntentExtrasDecoded = makeBundle(
            "key{\u0000}", "value{\u0000}",
            "key{\u0001}", "value{\u0001}",
            "key{\u001f}", "value{\u001f}",
            "key{\u007f}", "value{\u007f}",

            "key{\ud800\udc00}", "value{\ud800\udc00}",
            "key{\ud801\udc01}", "value{\ud801\udc01}",
            "key{\udbff\udfff}", "value{\udbff\udfff}",

            "key{?}x", 1,
            "key{?}\"x", 2,

            "x1", "value{?}x",
            "x2", "value{?}\"x"
    );

    private static PersistableBundle sShortcutExtras = makePersistableBundle(
            "key{\u0000}", "value{\u0000}",
            "key{\u0001}", "value{\u0001}",
            "key{\u001f}", "value{\u001f}",
            "key{\u007f}", "value{\u007f}",

            "key{\ud800\udc00}", "value{\ud800\udc00}",
            "key{\ud801\udc01}", "value{\ud801\udc01}",
            "key{\udbff\udfff}", "value{\udbff\udfff}",

            "key{\ud801}", 1, // broken surrogate pair
            "key{\uDC01}", 2, // broken surrogate pair

            "x1", "value{\ud801}", // broken surrogate pair
            "x2", "value{\uDC01}" // broken surrogate pair
    );

    // Same as above, except broken surrogate pairs are replaced with '?'s.
    private static PersistableBundle sShortcutExtrasDecoded = makePersistableBundle(
            "key{\u0000}", "value{\u0000}",
            "key{\u0001}", "value{\u0001}",
            "key{\u001f}", "value{\u001f}",
            "key{\u007f}", "value{\u007f}",

            "key{\ud800\udc00}", "value{\ud800\udc00}",
            "key{\ud801\udc01}", "value{\ud801\udc01}",
            "key{\udbff\udfff}", "value{\udbff\udfff}",

            "key{?}", 1,
            "key{?}", 2,

            "x1", "value{?}",
            "x2", "value{?}"
    );

    public void testPersistingWeirdCharacters() {
        final Intent intent = new Intent(Intent.ACTION_MAIN)
                .putExtras(sIntentExtras);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcutWithExtras("s1", intent, sShortcutExtras),
                    makeShortcut("s{\u0000}{\u0001}{\uD800\uDC00}x[\uD801][\uDC01]")
            )));
        });

        // Make sure save & load works fine. (i.e. shouldn't crash even with invalid characters.)
        initService();
        mService.handleUnlockUser(USER_0);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1", "s{\u0000}{\u0001}{\uD800\uDC00}x[?][?]")
                    .forShortcutWithId("s1", si -> {
                        assertBundlesEqual(si.getIntent().getExtras(), sIntentExtrasDecoded);
                        assertBundlesEqual(si.getExtras(), sShortcutExtrasDecoded);
                    });
        });
    }
}