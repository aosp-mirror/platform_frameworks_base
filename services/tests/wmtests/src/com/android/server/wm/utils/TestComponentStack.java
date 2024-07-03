/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.wm.utils;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Contains all the component created in the test.
 */
public class TestComponentStack<T> {

    @NonNull
    private final List<T> mItems = new ArrayList<>();

    /**
     * Adds an item to the stack.
     *
     * @param item The item to add.
     */
    public void push(@NonNull T item) {
        mItems.add(item);
    }

    /**
     * Consumes the top element of the stack.
     *
     * @param consumer Consumer for the optional top element.
     * @throws IndexOutOfBoundsException In case that stack is empty.
     */
    public void applyToTop(@NonNull Consumer<T> consumer) {
        consumer.accept(top());
    }

    /**
     * Returns the item at fromTop position from the top one if present or it throws an
     * exception if not present.
     *
     * @param fromTop The position from the top of the item to return.
     * @return The returned item.
     * @throws IndexOutOfBoundsException In case that position doesn't exist.
     */
    @NonNull
    public T getFromTop(int fromTop) {
        return mItems.get(mItems.size() - fromTop - 1);
    }

    /**
     * @return The item at the base of the stack if present.
     * @throws IndexOutOfBoundsException In case that stack is empty.
     */
    @NonNull
    public T base() {
        return mItems.get(0);
    }

    /**
     * @return The item at the top of the stack if present.
     * @throws IndexOutOfBoundsException In case that stack is empty.
     */
    @NonNull
    public T top() {
        return mItems.get(mItems.size() - 1);
    }

    /**
     * @return {@code true} if the stack is empty.
     */
    public boolean isEmpty() {
        return mItems.isEmpty();
    }

    /**
     * Allows access to the item at position beforeLast from the top.
     *
     * @param fromTop  The position from the top of the item to return.
     * @param consumer Consumer for the optional returned element.
     */
    public void applyTo(int fromTop, @NonNull Consumer<T> consumer) {
        consumer.accept(getFromTop(fromTop));
    }

    /**
     * Invoked the consumer iterating over all the elements in the stack.
     *
     * @param consumer Consumer for the elements.
     */
    public void applyToAll(@NonNull Consumer<T> consumer) {
        for (int i = mItems.size() - 1; i >= 0; i--) {
            consumer.accept(mItems.get(i));
        }
    }
}
