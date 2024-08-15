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

package android.content.pm;

import static org.junit.Assert.assertEquals;

import android.content.ComponentName;
import android.content.LocusId;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@Presubmit
public class ShortcutQueryWrapperTest {

    private static final long CHANGED_SINCE = TimeUnit.SECONDS.toMillis(1);
    private static final String PACKAGE_NAME = "com.android.test";
    private static final List<String> SHORTCUT_IDS = Lists.newArrayList("s1", "s2", "s3");
    private static final List<LocusId> LOCUS_IDS = Lists.newArrayList(
            new LocusId("id1"), new LocusId("id2"), new LocusId("id3"));
    private static final ComponentName COMPONENT_NAME = new ComponentName(
            PACKAGE_NAME, "ShortcutQueryTest");
    private static final int QUERY_FLAG = LauncherApps.ShortcutQuery.FLAG_MATCH_ALL_KINDS;

    private ShortcutQueryWrapper mShortcutQuery;

    @Before
    public void setUp() throws Exception {
        mShortcutQuery = new ShortcutQueryWrapper(new LauncherApps.ShortcutQuery()
                .setChangedSince(CHANGED_SINCE)
                .setPackage(PACKAGE_NAME)
                .setShortcutIds(SHORTCUT_IDS)
                .setLocusIds(LOCUS_IDS)
                .setActivity(COMPONENT_NAME)
                .setQueryFlags(QUERY_FLAG));
    }

    @Test
    public void testWriteAndReadFromParcel() {
        Parcel p = Parcel.obtain();
        mShortcutQuery.writeToParcel(p, 0);
        p.setDataPosition(0);
        ShortcutQueryWrapper q = ShortcutQueryWrapper.CREATOR.createFromParcel(p);
        assertEquals("Changed since doesn't match!", CHANGED_SINCE, q.getChangedSince());
        assertEquals("Package name doesn't match!", PACKAGE_NAME, q.getPackage());
        assertEquals("Shortcut ids doesn't match", SHORTCUT_IDS, q.getShortcutIds());
        assertEquals("Locus ids doesn't match", LOCUS_IDS, q.getLocusIds());
        assertEquals("Component name doesn't match", COMPONENT_NAME, q.getActivity());
        assertEquals("Query flag doesn't match", QUERY_FLAG, q.getQueryFlags());
        p.recycle();
    }
}
