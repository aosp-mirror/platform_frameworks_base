/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.utils.leaks;

import android.content.Context;
import android.testing.LeakCheck;
import android.testing.LeakCheck.Tracker;

import com.android.systemui.statusbar.policy.ExtensionController;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class FakeExtensionController implements ExtensionController {

    private final Tracker mTracker;

    public FakeExtensionController(LeakCheck test) {
        mTracker = test.getTracker("extension");
    }

    @Override
    public <T> ExtensionBuilder<T> newExtension(Class<T> cls) {
        final Object o = new Object();
        mTracker.getLeakInfo(o).addAllocation(new Throwable());
        return new FakeExtensionBuilder(o);
    }

    private class FakeExtensionBuilder<T> implements ExtensionBuilder<T> {
        private final Object mAllocation;

        public FakeExtensionBuilder(Object o) {
            mAllocation = o;
        }

        @Override
        public ExtensionBuilder<T> withTunerFactory(TunerFactory<T> factory) {
            return this;
        }

        @Override
        public <P extends T> ExtensionBuilder<T> withPlugin(Class<P> cls) {
            return this;
        }

        @Override
        public <P extends T> ExtensionBuilder<T> withPlugin(Class<P> cls, String action) {
            return this;
        }

        @Override
        public <P> ExtensionBuilder<T> withPlugin(Class<P> cls, String action, PluginConverter<T, P> converter) {
            return this;
        }

        @Override
        public ExtensionBuilder<T> withDefault(Supplier<T> def) {
            return this;
        }

        @Override
        public ExtensionBuilder<T> withCallback(Consumer<T> callback) {
            return this;
        }

        @Override
        public ExtensionBuilder<T> withUiMode(int mode, Supplier<T> def) {
            return null;
        }

        @Override
        public ExtensionBuilder<T> withFeature(String feature, Supplier<T> def) {
            return null;
        }

        @Override
        public Extension build() {
            return new FakeExtension(mAllocation);
        }
    }

    private class FakeExtension<T> implements Extension<T> {
        private final Object mAllocation;

        public FakeExtension(Object allocation) {
            mAllocation = allocation;
        }

        @Override
        public T get() {
            // TODO: Support defaults or things.
            return null;
        }

        @Override
        public void clearItem(boolean isDestroyed) {

        }

        @Override
        public Context getContext() {
            return null;
        }

        @Override
        public void destroy() {
            mTracker.getLeakInfo(mAllocation).clearAllocations();
        }

        @Override
        public void addCallback(Consumer<T> callback) {
        }

        public T reload() {
            return null;
        }
    }
}
