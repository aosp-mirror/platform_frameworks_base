/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.layoutlib.bridge.impl;

import java.util.ArrayList;

/**
 * Custom Stack implementation on top of an {@link ArrayList} instead of
 * using {@link java.util.Stack} which is on top of a vector.
 *
 * @param <T>
 */
public class Stack<T> extends ArrayList<T> {

    private static final long serialVersionUID = 1L;

    public Stack() {
        super();
    }

    public Stack(int size) {
        super(size);
    }

    /**
     * Pushes the given object to the stack
     * @param object the object to push
     */
    public void push(T object) {
        add(object);
    }

    /**
     * Remove the object at the top of the stack and returns it.
     * @return the removed object or null if the stack was empty.
     */
    public T pop() {
        if (size() > 0) {
            return remove(size() - 1);
        }

        return null;
    }

    /**
     * Returns the object at the top of the stack.
     * @return the object at the top or null if the stack is empty.
     */
    public T peek() {
        if (size() > 0) {
            return get(size() - 1);
        }

        return null;
    }
}
