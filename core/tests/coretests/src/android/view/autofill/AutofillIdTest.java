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

import static android.view.autofill.AutofillId.NO_SESSION;

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
        assertNonVirtual(id, 42, NO_SESSION);

        final AutofillId clone = cloneThroughParcel(id);
        assertNonVirtual(clone, 42, NO_SESSION);
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
    public void testFactoryMethod_withoutSession() {
        final AutofillId id = new AutofillId(42);
        id.setSessionId(108);
        assertNonVirtual(id, 42, 108);
        final AutofillId idWithoutSession = AutofillId.withoutSession(id);
        assertNonVirtual(idWithoutSession, 42, NO_SESSION);
    }

    @Test
    public void testSetResetSession() {
        final AutofillId id = new AutofillId(42);
        assertNonVirtual(id, 42, NO_SESSION);
        id.setSessionId(108);
        assertNonVirtual(id, 42, 108);

        final AutofillId clone1 = cloneThroughParcel(id);
        assertNonVirtual(clone1, 42, 108);

        id.resetSessionId();
        assertThat(id.getSessionId()).isEqualTo(NO_SESSION);
        final AutofillId clone2 = cloneThroughParcel(id);
        assertNonVirtual(clone2, 42, NO_SESSION);
    }

    @Test
    public void testEqualsHashCode_nonVirtual_same() {
        final AutofillId id = new AutofillId(42);
        final AutofillId sameId = new AutofillId(42);

        assertThat(id).isEqualTo(sameId);
        assertThat(sameId).isEqualTo(id);
        assertEqualsIgnoreSession(id, sameId);
        assertEqualsIgnoreSession(sameId, id);
        assertThat(id.hashCode()).isEqualTo(sameId.hashCode());
    }

    @Test
    public void testEqualsHashCode_nonVirtual_other() {
        final AutofillId id = new AutofillId(42);
        final AutofillId otherId = new AutofillId(108);

        assertThat(id).isNotEqualTo(otherId);
        assertThat(otherId).isNotEqualTo(id);
        assertNotEqualsIgnoreSession(id, otherId);
        assertNotEqualsIgnoreSession(otherId, id);
        assertThat(id.hashCode()).isNotEqualTo(otherId.hashCode());
    }

    @Test
    public void testEqualsHashCode_virtual_same() {
        final AutofillId id = new AutofillId(42);
        final AutofillId virtual = new AutofillId(42, 1);
        final AutofillId sameVirtual = new AutofillId(42, 1);

        assertThat(virtual).isEqualTo(sameVirtual);
        assertThat(sameVirtual).isEqualTo(virtual);
        assertEqualsIgnoreSession(virtual, sameVirtual);
        assertEqualsIgnoreSession(sameVirtual, virtual);
        assertThat(virtual.hashCode()).isEqualTo(sameVirtual.hashCode());
        assertThat(virtual).isNotEqualTo(id);
        assertThat(id).isNotEqualTo(virtual);
        assertNotEqualsIgnoreSession(id, virtual);
        assertNotEqualsIgnoreSession(virtual, id);
    }

    @Test
    public void testEqualsHashCode_virtual_otherChild() {
        final AutofillId id = new AutofillId(42);
        final AutofillId virtual = new AutofillId(42, 1);
        final AutofillId virtualOtherChild = new AutofillId(42, 2);

        assertThat(virtualOtherChild).isNotEqualTo(virtual);
        assertThat(virtual).isNotEqualTo(virtualOtherChild);
        assertNotEqualsIgnoreSession(virtualOtherChild, virtual);
        assertNotEqualsIgnoreSession(virtual, virtualOtherChild);
        assertThat(virtualOtherChild).isNotEqualTo(id);
        assertThat(id).isNotEqualTo(virtualOtherChild);
        assertNotEqualsIgnoreSession(virtualOtherChild, id);
        assertNotEqualsIgnoreSession(id, virtualOtherChild);
    }

    @Test
    public void testEqualsHashCode_virtual_otherParent() {
        final AutofillId virtual = new AutofillId(42, 1);
        final AutofillId virtualOtherParent = new AutofillId(108, 1);
        final AutofillId virtualOtherChild = new AutofillId(42, 2);

        assertThat(virtualOtherParent).isNotEqualTo(virtual);
        assertThat(virtual).isNotEqualTo(virtualOtherParent);
        assertNotEqualsIgnoreSession(virtualOtherParent, virtual);
        assertNotEqualsIgnoreSession(virtual, virtualOtherParent);
        assertThat(virtualOtherParent).isNotEqualTo(virtualOtherChild);
        assertThat(virtualOtherChild).isNotEqualTo(virtualOtherParent);
        assertNotEqualsIgnoreSession(virtualOtherParent, virtualOtherChild);
        assertNotEqualsIgnoreSession(virtualOtherChild, virtualOtherParent);
    }

    @Test
    public void testEqualsHashCode_virtual_otherSession() {
        final AutofillId virtual = new AutofillId(42, 1);
        final AutofillId virtualOtherSession = new AutofillId(42, 1);
        virtualOtherSession.setSessionId(666);

        assertThat(virtualOtherSession).isNotEqualTo(virtual);
        assertThat(virtual).isNotEqualTo(virtualOtherSession);
        assertEqualsIgnoreSession(virtualOtherSession, virtual);
        assertEqualsIgnoreSession(virtual, virtualOtherSession);
    }

    @Test
    public void testEqualsHashCode_virtual_longId_same() {
        final AutofillId hostId = new AutofillId(42);
        final AutofillId virtual = new AutofillId(hostId, 1L, 108);
        final AutofillId sameVirtual = new AutofillId(hostId, 1L, 108);

        assertThat(sameVirtual).isEqualTo(virtual);
        assertThat(virtual).isEqualTo(sameVirtual);
        assertEqualsIgnoreSession(sameVirtual, virtual);
        assertEqualsIgnoreSession(virtual, sameVirtual);
        assertThat(sameVirtual).isNotEqualTo(hostId);
        assertThat(hostId).isNotEqualTo(sameVirtual);
        assertNotEqualsIgnoreSession(sameVirtual, hostId);
        assertNotEqualsIgnoreSession(hostId, sameVirtual);
    }

    @Test
    public void testEqualsHashCode_virtual_longId_otherChild() {
        final AutofillId hostId = new AutofillId(42);
        final AutofillId virtual = new AutofillId(hostId, 1L, 108);
        final AutofillId virtualOtherChild = new AutofillId(hostId, 2L, 108);

        assertThat(virtualOtherChild).isNotEqualTo(virtual);
        assertThat(virtual).isNotEqualTo(virtualOtherChild);
        assertNotEqualsIgnoreSession(virtualOtherChild, virtual);
        assertNotEqualsIgnoreSession(virtual, virtualOtherChild);
        assertThat(virtualOtherChild).isNotEqualTo(hostId);
        assertThat(hostId).isNotEqualTo(virtualOtherChild);
        assertNotEqualsIgnoreSession(virtualOtherChild, hostId);
        assertNotEqualsIgnoreSession(hostId, virtualOtherChild);
    }

    @Test
    public void testEqualsHashCode_virtual_longId_otherParent() {
        final AutofillId hostId = new AutofillId(42);
        final AutofillId virtual = new AutofillId(hostId, 1L, 108);
        final AutofillId virtualOtherParent = new AutofillId(new AutofillId(666), 1L, 108);
        final AutofillId virtualOtherChild = new AutofillId(hostId, 2L, 108);

        assertThat(virtualOtherParent).isNotEqualTo(virtual);
        assertThat(virtual).isNotEqualTo(virtualOtherParent);
        assertNotEqualsIgnoreSession(virtualOtherParent, virtual);
        assertNotEqualsIgnoreSession(virtual, virtualOtherParent);
        assertThat(virtualOtherParent).isNotEqualTo(virtualOtherChild);
        assertThat(virtualOtherChild).isNotEqualTo(virtualOtherParent);
        assertNotEqualsIgnoreSession(virtualOtherParent, virtualOtherChild);
        assertNotEqualsIgnoreSession(virtualOtherChild, virtualOtherParent);
    }

    @Test
    public void testEqualsHashCode_virtual_longId_otherSession() {
        final AutofillId hostId = new AutofillId(42);
        final AutofillId virtual = new AutofillId(hostId, 1L, 108);
        final AutofillId virtualOtherSession = new AutofillId(hostId, 1L, 666);

        assertThat(virtualOtherSession).isNotEqualTo(virtual);
        assertThat(virtual).isNotEqualTo(virtualOtherSession);
        assertEqualsIgnoreSession(virtualOtherSession, virtual);
        assertEqualsIgnoreSession(virtual, virtualOtherSession);
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

    private void assertEqualsIgnoreSession(AutofillId id1, AutofillId id2) {
        assertWithMessage("id1 is null").that(id1).isNotNull();
        assertWithMessage("id2 is null").that(id2).isNotNull();
        assertWithMessage("%s is not equal to %s", id1, id2).that(id1.equalsIgnoreSession(id2))
                .isTrue();
    }

    private void assertNotEqualsIgnoreSession(AutofillId id1, AutofillId id2) {
        assertWithMessage("id1 is null").that(id1).isNotNull();
        assertWithMessage("id2 is null").that(id2).isNotNull();
        assertWithMessage("%s is not equal to %s", id1, id2).that(id1.equalsIgnoreSession(id2))
                .isFalse();
    }

    private void assertNonVirtual(AutofillId id, int expectedId, int expectSessionId) {
        assertThat(id.getViewId()).isEqualTo(expectedId);
        assertThat(id.isNonVirtual()).isTrue();
        assertThat(id.isVirtualInt()).isFalse();
        assertThat(id.isVirtualLong()).isFalse();
        assertThat(id.getVirtualChildIntId()).isEqualTo(View.NO_ID);
        assertThat(id.getVirtualChildLongId()).isEqualTo(View.NO_ID);
        assertThat(id.getSessionId()).isEqualTo(expectSessionId);
    }
}
