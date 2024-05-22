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

import java.util.function.Predicate;

/** A simple Predicate to avoid synthetic allocation of lambda expression "o -> true". */
public class AlwaysTruePredicate implements Predicate<Object> {

    public static final AlwaysTruePredicate INSTANCE = new AlwaysTruePredicate();

    private AlwaysTruePredicate() {
    }

    @Override
    public boolean test(Object o) {
        return true;
    }
}
