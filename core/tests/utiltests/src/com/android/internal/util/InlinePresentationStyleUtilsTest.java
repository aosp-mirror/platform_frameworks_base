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

package com.android.internal.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Binder;
import android.os.Bundle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.InlinePresentationStyleUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class InlinePresentationStyleUtilsTest {
    @Test
    public void testBundleEquals_empty() {
        Bundle bundle1 = new Bundle();
        Bundle bundle2 = new Bundle();

        assertTrue(InlinePresentationStyleUtils.bundleEquals(bundle1, bundle2));

        bundle1 = Bundle.EMPTY;
        assertTrue(InlinePresentationStyleUtils.bundleEquals(bundle1, bundle2));

        bundle2 = Bundle.EMPTY;
        assertTrue(InlinePresentationStyleUtils.bundleEquals(bundle1, bundle2));
    }

    @Test
    public void testBundleEquals_oneIsEmpty() {
        Bundle bundle1 = Bundle.EMPTY;
        Bundle bundle2 = new Bundle();
        bundle2.putString("KEY", "value");
        assertFalse(InlinePresentationStyleUtils.bundleEquals(bundle1, bundle2));
    }

    @Test
    public void testBundleEquals_nestedBundle_equal() {
        Bundle bundle1 = new Bundle();
        Bundle bundle11 = new Bundle();
        bundle11.putString("KEY", "VALUE");
        bundle1.putBundle("KEY_B", bundle11);

        Bundle bundle2 = new Bundle();
        Bundle bundle21 = new Bundle();
        bundle21.putString("KEY", "VALUE");
        bundle2.putBundle("KEY_B", bundle21);

        assertTrue(InlinePresentationStyleUtils.bundleEquals(bundle1, bundle2));
    }

    @Test
    public void testBundleEquals_nestedBundle_unequal() {
        Bundle bundle1 = new Bundle();
        Bundle bundle11 = new Bundle();
        bundle11.putString("KEY", "VALUE");
        bundle1.putBundle("KEY_B", bundle11);

        Bundle bundle2 = new Bundle();
        bundle2.putBundle("KEY_B", new Bundle());

        assertFalse(InlinePresentationStyleUtils.bundleEquals(bundle1, bundle2));
    }

    @Test
    public void testBundleEquals_sameKeyDifferentType() {
        Bundle bundle1 = new Bundle();
        bundle1.putBundle("KEY_B", new Bundle());

        Bundle bundle2 = new Bundle();
        bundle2.putInt("KEY_B", 12);

        assertFalse(InlinePresentationStyleUtils.bundleEquals(bundle1, bundle2));
    }

    @Test
    public void testBundleEquals_primitiveValue_equal() {
        Bundle bundle1 = new Bundle();
        bundle1.putInt("KEY", 11);
        Bundle bundle2 = new Bundle();
        bundle2.putInt("KEY", 11);
        assertTrue(InlinePresentationStyleUtils.bundleEquals(bundle1, bundle2));
    }

    @Test
    public void testBundleEquals_primitiveValue_unequal() {
        Bundle bundle1 = new Bundle();
        bundle1.putInt("KEY", 11);
        Bundle bundle2 = new Bundle();
        bundle2.putInt("KEY", 22);
        assertFalse(InlinePresentationStyleUtils.bundleEquals(bundle1, bundle2));
    }

    @Test
    public void testFilterContentTypes_nullOrEmpty() {
        InlinePresentationStyleUtils.filterContentTypes(null);
        InlinePresentationStyleUtils.filterContentTypes(new Bundle());
    }

    @Test
    public void testFilterContentTypes_basic() {
        Bundle bundle = new Bundle();
        bundle.putInt("int", 11);
        bundle.putString("str", "test");
        bundle.putString("null", null);

        InlinePresentationStyleUtils.filterContentTypes(bundle);

        assertEquals(11, bundle.getInt("int"));
        assertEquals("test", bundle.getString("str"));
        assertTrue(bundle.keySet().contains("null"));
    }

    @Test
    public void testFilterContentTypes_binder_removedBinder() {
        Bundle bundle = new Bundle();
        bundle.putInt("int", 11);
        bundle.putString("str", "test");
        bundle.putString("null", null);
        bundle.putBinder("binder", new Binder());

        InlinePresentationStyleUtils.filterContentTypes(bundle);

        assertEquals(11, bundle.getInt("int"));
        assertEquals("test", bundle.getString("str"));
        assertTrue(bundle.keySet().contains("null"));
        assertNull(bundle.getBinder("binder"));
    }

    @Test
    public void testFilterContentTypes_binderInChild_removedBinder() {
        Bundle child = new Bundle();
        child.putBinder("binder", new Binder());
        Bundle bundle = new Bundle();
        bundle.putBundle("child", child);

        InlinePresentationStyleUtils.filterContentTypes(bundle);

        Bundle child2 = bundle.getBundle("child");
        assertNotNull(child2);
        assertNull(child2.getBinder("binder"));
    }
}
