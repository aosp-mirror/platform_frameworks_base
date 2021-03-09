/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;
import static com.google.errorprone.matchers.Matchers.instanceMethod;
import static com.google.errorprone.matchers.Matchers.methodInvocation;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.MethodInvocationTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;

@AutoService(BugChecker.class)
@BugPattern(
    name = "AndroidFrameworkUnattributedNoteOpCall",
    summary = "Verifies that a noteOp() call is attributed",
    severity = WARNING)
public final class UnattributedNoteOpCallChecker extends BugChecker
        implements MethodInvocationTreeMatcher {

    private static final Matcher<ExpressionTree> UNATTRIBUTED_NOTEOP_CALL_1 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("noteOp(int,int,java.lang.String)"));
    private static final Matcher<ExpressionTree> UNATTRIBUTED_NOTEOP_CALL_2 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("noteOp(java.lang.String,int,java.lang.String)"));
    private static final Matcher<ExpressionTree> UNATTRIBUTED_NOTEOP_CALL_3 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("noteOp(int)"));

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        if (UNATTRIBUTED_NOTEOP_CALL_1.matches(tree, state)
            || UNATTRIBUTED_NOTEOP_CALL_2.matches(tree, state)
            || UNATTRIBUTED_NOTEOP_CALL_3.matches(tree, state)) {
            return buildDescription(tree)
                .setMessage("Unattributed noteOp call! Please use noteOp(int, String, String, String) or noteOp(int, CallerIdentity)")
                .build();
        }
        return Description.NO_MATCH;
    }
}
