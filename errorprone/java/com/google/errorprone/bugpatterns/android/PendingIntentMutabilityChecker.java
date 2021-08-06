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

package com.google.errorprone.bugpatterns.android;

import static com.google.errorprone.BugPattern.LinkType.NONE;
import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;
import static com.google.errorprone.matchers.Matchers.anyOf;
import static com.google.errorprone.matchers.Matchers.contains;
import static com.google.errorprone.matchers.Matchers.methodInvocation;
import static com.google.errorprone.matchers.Matchers.staticMethod;
import static com.google.errorprone.matchers.android.FieldMatchers.staticField;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.MethodInvocationTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;

import java.util.regex.Pattern;

/**
 * Any method calls to create a PendingIntent require that one of the
 * mutability flags, FLAG_MUTABLE or FLAG_IMMUTABLE, be explicitly specified.
 * This checker verifies that one of these mutability flags are used when
 * creating PendingIntents.
 */
@AutoService(BugChecker.class)
@BugPattern(
        name = "AndroidFrameworkPendingIntentMutability",
        summary = "Verifies that FLAG_MUTABLE or FLAG_IMMUTABLE is always set",
        linkType = NONE,
        severity = WARNING)
public final class PendingIntentMutabilityChecker extends BugChecker
        implements MethodInvocationTreeMatcher {

    private static final Matcher<ExpressionTree> PENDING_INTENT_METHOD = methodInvocation(
            staticMethod()
            .onClass("android.app.PendingIntent")
            .withNameMatching(Pattern.compile(
                    "^(getActivity|getActivityAsUser|getActivities|getActivitiesAsUser|"
                    + "getBroadcast|getBroadcastAsUser|getService|getForegroundService).*")));

    private static final Matcher<ExpressionTree> VALID_FLAGS = anyOf(
            staticField("android.app.PendingIntent", "FLAG_MUTABLE"),
            staticField("android.app.PendingIntent", "FLAG_IMMUTABLE"));

    private static final Matcher<ExpressionTree> CONTAINS_VALID_FLAGS = contains(
            ExpressionTree.class, VALID_FLAGS);

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        if (PENDING_INTENT_METHOD.matches(tree, state)) {
            final ExpressionTree arg = tree.getArguments().get(3);
            if (!(VALID_FLAGS.matches(arg, state) || CONTAINS_VALID_FLAGS.matches(arg, state))) {
                return buildDescription(arg)
                        .setMessage("To improve security, PendingIntents must declare one of"
                                + " FLAG_MUTABLE or FLAG_IMMUTABLE explicitly; see"
                                + " go/immutable-pendingintents for more details")
                        .build();
            }
        }
        return Description.NO_MATCH;
    }
}
