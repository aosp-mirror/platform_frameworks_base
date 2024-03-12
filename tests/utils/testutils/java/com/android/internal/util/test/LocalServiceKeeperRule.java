/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.util.test;

import com.android.server.LocalServices;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JUnit Rule helps override /restore {@link LocalServices} state.
 */
public class LocalServiceKeeperRule implements TestRule {

    private final Map<Class<?>, Object> mOverriddenServices = new HashMap<>();
    private final List<Class<?>> mAddedServices = new ArrayList<>();

    private volatile boolean mRuleApplied = false;

    /**
     * Overrides service in LocalServices. Service will be restored to original after test run.
     */
    public <T> void overrideLocalService(Class<T> type, T service) {
        if (!mRuleApplied) {
            throw new IllegalStateException("Can't override service without applying rule");
        }
        if (mOverriddenServices.containsKey(type) || mAddedServices.contains(type)) {
            throw new IllegalArgumentException("Type already overridden: " + type);
        }

        T currentService = LocalServices.getService(type);
        if (currentService != null) {
            mOverriddenServices.put(type, currentService);
            LocalServices.removeServiceForTest(type);
        } else {
            mAddedServices.add(type);
        }
        LocalServices.addService(type, service);
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            public void evaluate() throws Throwable {
                try {
                    mRuleApplied = true;
                    base.evaluate();
                } finally {
                    mRuleApplied = false;
                    mAddedServices.forEach(LocalServices::removeServiceForTest);
                    mOverriddenServices.forEach((clazz, service) -> {
                        LocalServices.removeServiceForTest(clazz);
                        LocalServices.addService((Class) clazz, service);
                    });
                    mAddedServices.clear();
                    mOverriddenServices.clear();
                }
            }
        };
    }
}
