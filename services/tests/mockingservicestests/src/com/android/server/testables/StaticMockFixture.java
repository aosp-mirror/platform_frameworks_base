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

package com.android.server.testables;


import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;

/**
 * Provides support for a set of static mocks for use within a single shared
 * {@link StaticMockitoSession}.
 */
public interface StaticMockFixture {
    /**
     * Adds any required mock or spy classes managed by this {@link StaticMockFixture} to the
     * {@link StaticMockitoSessionBuilder} provided.
     *
     * Call this to set up the classes that this expects to be mocked, by adding them to the
     * {@link StaticMockitoSessionBuilder} using
     * {@link StaticMockitoSessionBuilder#mockStatic(Class)},
     * {@link StaticMockitoSessionBuilder#spyStatic(Class)} or similar as appropriate.
     *
     * @param sessionBuilder the {@link StaticMockitoSessionBuilder} to which the classes should be
     *                       added to mock, spy, or otherwise as required
     * @return sessionBuilder, to allow for fluent programming
     */
    StaticMockitoSessionBuilder setUpMockedClasses(StaticMockitoSessionBuilder sessionBuilder);

    /**
     * Configures the behaviours of any mock or spy classes managed by this
     * {@link StaticMockFixture}.
     *
     * Call this after {@link StaticMockitoSessionBuilder#startMocking()} has been called.
     * This sets up any default behaviors for the mocks, spys, etc.
     */
    void setUpMockBehaviors();

    /**
     * Tear everything down.
     */
    void tearDown();
}
