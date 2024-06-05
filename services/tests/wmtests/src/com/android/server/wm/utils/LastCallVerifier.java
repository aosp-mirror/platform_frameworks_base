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

package com.android.server.wm.utils;

import org.mockito.internal.verification.VerificationModeFactory;
import org.mockito.internal.verification.api.VerificationData;
import org.mockito.invocation.Invocation;
import org.mockito.invocation.MatchableInvocation;
import org.mockito.verification.VerificationMode;

import java.util.List;

/**
 * Verifier to check that the last call of a method received the expected argument
 */
public class LastCallVerifier implements VerificationMode {

    /**
     * Allows comparing the expected invocation with the last invocation on the same method
     */
    public static LastCallVerifier lastCall() {
        return new LastCallVerifier();
    }

    @Override
    public void verify(VerificationData data) {
        List<Invocation> invocations = data.getAllInvocations();
        MatchableInvocation target = data.getTarget();
        for (int i = invocations.size() - 1; i >= 0; i--) {
            final Invocation invocation = invocations.get(i);
            if (target.hasSameMethod(invocation)) {
                if (target.matches(invocation)) {
                    return;
                } else {
                    throw new LastCallMismatch(target.getInvocation(), invocation, invocations);
                }
            }
        }
        throw new RuntimeException(target + " never invoked");
    }

    @Override
    public VerificationMode description(String description) {
        return VerificationModeFactory.description(this, description);
    }

    static class LastCallMismatch extends RuntimeException {
        LastCallMismatch(
                Invocation expected, Invocation received, List<Invocation> allInvocations) {
            super("Expected invocation " + expected + " but received " + received
                    + " as the last invocation.\nAll registered invocations:\n" + allInvocations);
        }
    }
}
