/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view.autofill;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.testng.Assert.assertThrows;

import android.os.Parcel;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AutofillIdTest {

    @Test
    public void testNonVirtual() {
        final AutofillId id = new AutofillId(42);
        assertThat(id.getViewId()).isEqualTo(42);
        assertThat(id.isNonVirtual()).isTrue();
        assertThat(id.isVirtualInt()).isFalse();
        assertThat(id.isVirtualLong()).isFalse();
        assertThat(id.getVirtualChildIntId()).isEqualTo(View.NO_ID);
        assertThat(id.getVirtualChildLongId()).isEqualTo(View.NO_ID);

        final AutofillId clone = cloneThroughParcel(id);
        assertThat(clone.getViewId()).isEqualTo(42);
        assertThat(clone.isNonVirtual()).isTrue();
        assertThat(clone.isVirtualInt()).isFalse();
        assertThat(clone.isVirtualLong()).isFalse();
        assertThat(clone.getVirtualChildIntId()).isEqualTo(View.NO_ID);
        assertThat(clone.getVirtualChildLongId()).isEqualTo(View.NO_ID);
    }

    @Test
    public void testVirtual_int() {
        final AutofillId id = new AutofillId(42, 108);
        assertThat(id.getViewId()).isEqualTo(42);
        assertThat(id.isVirtualInt()).isTrue();
        assertThat(id.isVirtualLong()).isFalse();
        assertThat(id.isNonVirtual()).isFalse();
        assertThat(id.getVirtualChildIntId()).isEqualTo(108);
        assertThat(id.getVirtualChildLongId()).isEqualTo(View.NO_ID);

        final AutofillId clone = cloneThroughParcel(id);
        assertThat(clone.getViewId()).isEqualTo(42);
        assertThat(clone.isVirtualLong()).isFalse();
        assertThat(clone.isVirtualInt()).isTrue();
        assertThat(clone.isNonVirtual()).isFalse();
        assertThat(clone.getVirtualChildIntId()).isEqualTo(108);
        assertThat(clone.getVirtualChildLongId()).isEqualTo(View.NO_ID);
    }

    @Test
    public void testVirtual_long() {
        final AutofillId id = new AutofillId(new AutofillId(42), 4815162342L, 108);
        assertThat(id.getViewId()).isEqualTo(42);
        assertThat(id.isVirtualLong()).isTrue();
        assertThat(id.isVirtualInt()).isFalse();
        assertThat(id.isNonVirtual()).isFalse();
        assertThat(id.getVirtualChildIntId()).isEqualTo(View.NO_ID);
        assertThat(id.getVirtualChildLongId()).isEqualTo(4815162342L);

        final AutofillId clone = cloneThroughParcel(id);
        assertThat(clone.getViewId()).isEqualTo(42);
        assertThat(clone.isVirtualLong()).isTrue();
        assertThat(clone.isVirtualInt()).isFalse();
        assertThat(clone.isNonVirtual()).isFalse();
        assertThat(clone.getVirtualChildIntId()).isEqualTo(View.NO_ID);
        assertThat(clone.getVirtualChildLongId()).isEqualTo(4815162342L);
    }

    @Test
    public void testVirtual_parentObjectConstructor() {
        assertThrows(NullPointerException.class, () -> new AutofillId(null, 108));

        final AutofillId id = new AutofillId(new AutofillId(42), 108);
        assertThat(id.getViewId()).isEqualTo(42);
        assertThat(id.isVirtualInt()).isTrue();
        assertThat(id.getVirtualChildIntId()).isEqualTo(108);

        final AutofillId clone = cloneThroughParcel(id);
        assertThat(clone.getViewId()).isEqualTo(42);
        assertThat(clone.isVirtualInt()).isTrue();
        assertThat(clone.getVirtualChildIntId()).isEqualTo(108);
    }

    @Test
    public void testVirtual_withSession() {
        final AutofillId id = new AutofillId(new AutofillId(42), 108L, 666);
        assertThat(id.getViewId()).isEqualTo(42);
        assertThat(id.isVirtualLong()).isTrue();
        assertThat(id.isVirtualInt()).isFalse();
        assertThat(id.isNonVirtual()).isFalse();
        assertThat(id.getVirtualChildLongId()).isEqualTo(108L);
        assertThat(id.getVirtualChildIntId()).isEqualTo(View.NO_ID);
        assertThat(id.getSessionId()).isEqualTo(666);

        final AutofillId clone = cloneThroughParcel(id);
        assertThat(clone.getViewId()).isEqualTo(42);
        assertThat(clone.isVirtualLong()).isTrue();
        assertThat(clone.isVirtualInt()).isFalse();
        assertThat(clone.isNonVirtual()).isFalse();
        assertThat(clone.getVirtualChildLongId()).isEqualTo(108L);
        assertThat(clone.getVirtualChildIntId()).isEqualTo(View.NO_ID);
        assertThat(clone.getSessionId()).isEqualTo(666);
    }

    @Test
    public void testEqualsHashCode() {
        final AutofillId realId = new AutofillId(42);
        final AutofillId realIdSame = new AutofillId(42);
        assertThat(realId).isEqualTo(realIdSame);
        assertThat(realIdSame).isEqualTo(realId);
        assertEqualsIgnoreSession(realId, realIdSame);
        assertEqualsIgnoreSession(realIdSame, realId);
        assertThat(realId.hashCode()).isEqualTo(realIdSame.hashCode());

        final AutofillId realIdDifferent = new AutofillId(108);
        assertThat(realId).isNotEqualTo(realIdDifferent);
        assertThat(realIdDifferent).isNotEqualTo(realId);
        assertNotEqualsIgnoreSession(realId, realIdDifferent);
        assertNotEqualsIgnoreSession(realIdDifferent, realId);
        assertThat(realId.hashCode()).isNotEqualTo(realIdDifferent.hashCode());

        final AutofillId virtualId = new AutofillId(42, 1);
        final AutofillId virtualIdSame = new AutofillId(42, 1);
        assertThat(virtualId).isEqualTo(virtualIdSame);
        assertThat(virtualIdSame).isEqualTo(virtualId);
        assertEqualsIgnoreSession(virtualId, virtualIdSame);
        assertEqualsIgnoreSession(virtualIdSame, virtualId);
        assertThat(virtualId.hashCode()).isEqualTo(virtualIdSame.hashCode());
        assertThat(virtualId).isNotEqualTo(realId);
        assertThat(realId).isNotEqualTo(virtualId);
        assertNotEqualsIgnoreSession(realId, virtualId);
        assertNotEqualsIgnoreSession(virtualId, realId);

        final AutofillId virtualIdDifferentChild = new AutofillId(42, 2);
        assertThat(virtualIdDifferentChild).isNotEqualTo(virtualId);
        assertThat(virtualId).isNotEqualTo(virtualIdDifferentChild);
        assertNotEqualsIgnoreSession(virtualIdDifferentChild, virtualId);
        assertNotEqualsIgnoreSession(virtualId, virtualIdDifferentChild);
        assertThat(virtualIdDifferentChild).isNotEqualTo(realId);
        assertThat(realId).isNotEqualTo(virtualIdDifferentChild);
        assertNotEqualsIgnoreSession(virtualIdDifferentChild, realId);
        assertNotEqualsIgnoreSession(realId, virtualIdDifferentChild);

        final AutofillId virtualIdDifferentParent = new AutofillId(108, 1);
        assertThat(virtualIdDifferentParent).isNotEqualTo(virtualId);
        assertThat(virtualId).isNotEqualTo(virtualIdDifferentParent);
        assertNotEqualsIgnoreSession(virtualIdDifferentParent, virtualId);
        assertNotEqualsIgnoreSession(virtualId, virtualIdDifferentParent);
        assertThat(virtualIdDifferentParent).isNotEqualTo(virtualIdDifferentChild);
        assertThat(virtualIdDifferentChild).isNotEqualTo(virtualIdDifferentParent);
        assertNotEqualsIgnoreSession(virtualIdDifferentParent, virtualIdDifferentChild);
        assertNotEqualsIgnoreSession(virtualIdDifferentChild, virtualIdDifferentParent);

        final AutofillId virtualIdDifferentSession = new AutofillId(new AutofillId(42), 1L, 108);
        assertThat(virtualIdDifferentSession).isNotEqualTo(virtualId);
        assertThat(virtualId).isNotEqualTo(virtualIdDifferentSession);
        if (false) { // TODO: doesn't work because one object uses int virtual ids, other uses long
            assertEqualsIgnoreSession(virtualIdDifferentSession, virtualId);
            assertEqualsIgnoreSession(virtualId, virtualIdDifferentSession);
        }
        assertThat(virtualIdDifferentSession).isNotEqualTo(realId);
        assertThat(realId).isNotEqualTo(virtualIdDifferentSession);
        assertNotEqualsIgnoreSession(virtualIdDifferentSession, realId);
        assertNotEqualsIgnoreSession(realId, virtualIdDifferentSession);

        final AutofillId sameVirtualIdDifferentSession =
                new AutofillId(new AutofillId(42), 1L, 108);
        assertThat(sameVirtualIdDifferentSession).isEqualTo(virtualIdDifferentSession);
        assertThat(virtualIdDifferentSession).isEqualTo(sameVirtualIdDifferentSession);
        assertEqualsIgnoreSession(sameVirtualIdDifferentSession, virtualIdDifferentSession);
        assertEqualsIgnoreSession(virtualIdDifferentSession, sameVirtualIdDifferentSession);
        assertThat(sameVirtualIdDifferentSession.hashCode())
                .isEqualTo(virtualIdDifferentSession.hashCode());
    }

    @Test
    public void testEqualsIgnoreSession() {
        final AutofillId id1 = new AutofillId(new AutofillId(42), 1L, 108);
        final AutofillId id2 = new AutofillId(new AutofillId(42), 1L, 666);
        assertThat(id1).isNotEqualTo(id2);
        assertThat(id2).isNotEqualTo(id1);
        assertEqualsIgnoreSession(id1, id2);
        assertEqualsIgnoreSession(id2, id1);
    }

    private AutofillId cloneThroughParcel(AutofillId id) {
        Parcel parcel = Parcel.obtain();

        try {
            // Write to parcel
            parcel.setDataPosition(0); // Sanity / paranoid check
            id.writeToParcel(parcel, 0);

            // Read from parcel
            parcel.setDataPosition(0);
            AutofillId clone = AutofillId.CREATOR.createFromParcel(parcel);
            assertThat(clone).isNotNull();
            return clone;
        } finally {
            parcel.recycle();
        }
    }

    public static void assertEqualsIgnoreSession(AutofillId id1, AutofillId id2) {
        assertWithMessage("id1 is null").that(id1).isNotNull();
        assertWithMessage("id2 is null").that(id2).isNotNull();
        assertWithMessage("%s is not equal to %s", id1, id2).that(id1.equalsIgnoreSession(id2))
                .isTrue();
    }

    public static void assertNotEqualsIgnoreSession(AutofillId id1, AutofillId id2) {
        assertWithMessage("id1 is null").that(id1).isNotNull();
        assertWithMessage("id2 is null").that(id2).isNotNull();
        assertWithMessage("%s is not equal to %s", id1, id2).that(id1.equalsIgnoreSession(id2))
                .isFalse();
    }
}
