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

package com.android.server.textclassifier;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class FixedSizeQueueTest {

    @Test
    public void add_belowMaxCapacity() {
        FixedSizeQueue<Integer> queue = new FixedSizeQueue<>(1, /* onEntryEvictedListener= */ null);
        assertThat(queue.size()).isEqualTo(0);

        queue.add(1);

        assertThat(queue.size()).isEqualTo(1);
        assertThat(queue.poll()).isEqualTo(1);
    }

    @Test
    public void add_exceedMaxCapacity() {
        FixedSizeQueue<Integer> queue = new FixedSizeQueue<>(2, /* onEntryEvictedListener= */ null);

        queue.add(1);
        queue.add(2);
        queue.add(3);

        assertThat(queue.size()).isEqualTo(2);
        assertThat(queue.poll()).isEqualTo(2);
        assertThat(queue.poll()).isEqualTo(3);
    }

    @Test
    public void poll() {
        FixedSizeQueue<Integer> queue = new FixedSizeQueue<>(1, /* onEntryEvictedListener= */ null);

        queue.add(1);

        assertThat(queue.poll()).isEqualTo(1);
        assertThat(queue.poll()).isNull();
    }

    @Test
    public void remove() {
        FixedSizeQueue<Integer> queue = new FixedSizeQueue<>(1, /* onEntryEvictedListener= */ null);

        queue.add(1);

        assertThat(queue.remove(1)).isTrue();
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    public void remove_noSuchElement() {
        FixedSizeQueue<Integer> queue = new FixedSizeQueue<>(1, /* onEntryEvictedListener= */ null);

        queue.add(1);

        assertThat(queue.remove(2)).isFalse();
    }

    @Test
    public void isEmpty_true() {
        FixedSizeQueue<Integer> queue = new FixedSizeQueue<>(1, /* onEntryEvictedListener= */ null);

        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    public void isEmpty_false() {
        FixedSizeQueue<Integer> queue = new FixedSizeQueue<>(1, /* onEntryEvictedListener= */ null);

        queue.add(1);

        assertThat(queue.isEmpty()).isFalse();
    }

    @Test
    public void onEntryEvicted() {
        List<Integer> onEntryEvictedElements = new ArrayList<>();
        FixedSizeQueue<Integer> queue =
                new FixedSizeQueue<>(1, onEntryEvictedElements::add);

        queue.add(1);
        queue.add(2);
        queue.add(3);

        assertThat(onEntryEvictedElements).containsExactly(1, 2).inOrder();
    }
}
