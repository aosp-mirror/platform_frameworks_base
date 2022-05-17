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
    private static final Matcher<ExpressionTree> UNATTRIBUTED_NOTEOPNOTHROW_CALL_1 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("noteOpNoThrow(int,int,java.lang.String)"));
    private static final Matcher<ExpressionTree> UNATTRIBUTED_NOTEOPNOTHROW_CALL_2 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("noteOpNoThrow(java.lang.String,int,java.lang.String)"));
    private static final Matcher<ExpressionTree> UNATTRIBUTED_STARTOP_CALL_1 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("startOp(java.lang.String,int,java.lang.String)"));
    private static final Matcher<ExpressionTree> UNATTRIBUTED_STARTOP_CALL_2 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("startOp(int,int,java.lang.String)"));
    private static final Matcher<ExpressionTree> UNATTRIBUTED_STARTOP_CALL_3 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("startOp(int)"));
    private static final Matcher<ExpressionTree> UNATTRIBUTED_STARTOP_CALL_4 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("startOp(int,int,java.lang.String,boolean)"));
    private static final Matcher<ExpressionTree> UNATTRIBUTED_STARTOPNOTHROW_CALL_1 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("startOpNoThrow(java.lang.String,int,java.lang.String)"));
    private static final Matcher<ExpressionTree> UNATTRIBUTED_STARTOPNOTHROW_CALL_2 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("startOpNoThrow(int,int,java.lang.String)"));
    private static final Matcher<ExpressionTree> UNATTRIBUTED_STARTOPNOTHROW_CALL_3 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("startOpNoThrow(int,int,java.lang.String,boolean)"));
    private static final Matcher<ExpressionTree> UNATTRIBUTED_NOTEPROXYOP_CALL_1 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("noteProxyOp(java.lang.String,java.lang.String)"));
    private static final Matcher<ExpressionTree> UNATTRIBUTED_NOTEPROXYOP_CALL_2 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("noteProxyOp(int,java.lang.String)"));
    private static final Matcher<ExpressionTree> UNATTRIBUTED_NOTEPROXYOPNOTHROW_CALL_1 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("noteProxyOpNoThrow(java.lang.String,java.lang.String)"));
    private static final Matcher<ExpressionTree> UNATTRIBUTED_NOTEPROXYOPNOTHROW_CALL_2 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("noteProxyOpNoThrow(java.lang.String,java.lang.String,int)"));
    private static final Matcher<ExpressionTree> UNATTRIBUTED_FINISHOP_CALL_1 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("finishOp(int)"));
    private static final Matcher<ExpressionTree> UNATTRIBUTED_FINISHOP_CALL_2 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("finishOp(java.lang.String,int,java.lang.String)"));
    private static final Matcher<ExpressionTree> UNATTRIBUTED_FINISHOP_CALL_3 = methodInvocation(
            instanceMethod().onExactClass("android.app.AppOpsManager")
                    .withSignature("finishOp(int,int,java.lang.String)"));

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        if (UNATTRIBUTED_NOTEOP_CALL_1.matches(tree, state)
            || UNATTRIBUTED_NOTEOP_CALL_2.matches(tree, state)
            || UNATTRIBUTED_NOTEOP_CALL_3.matches(tree, state)) {
            return buildDescription(tree)
                .setMessage("Unattributed noteOp call! Please use noteOp(int, String, String, String) or noteOp(int, CallerIdentity)")
                .build();
        }
        if (UNATTRIBUTED_NOTEOPNOTHROW_CALL_1.matches(tree, state)
            || UNATTRIBUTED_NOTEOPNOTHROW_CALL_2.matches(tree, state)) {
            return buildDescription(tree)
                .setMessage("Unattributed noteOpNoThrow call! Please use noteOpNoThrow(String, int, String, String, String)")
                .build();
        }
        if (UNATTRIBUTED_STARTOP_CALL_1.matches(tree, state)
            || UNATTRIBUTED_STARTOP_CALL_2.matches(tree, state)
            || UNATTRIBUTED_STARTOP_CALL_3.matches(tree, state)
            || UNATTRIBUTED_STARTOP_CALL_4.matches(tree, state)) {
            return buildDescription(tree)
                .setMessage("Unattributed startOp call! Please use startOp(int, int, String, boolean, String, String)")
                .build();
        }
        if (UNATTRIBUTED_STARTOPNOTHROW_CALL_1.matches(tree, state)
            || UNATTRIBUTED_STARTOPNOTHROW_CALL_2.matches(tree, state)
            || UNATTRIBUTED_STARTOPNOTHROW_CALL_3.matches(tree, state)) {
            return buildDescription(tree)
                .setMessage("Unattributed startOpNoThrow call!")
                .build();
        }
        if (UNATTRIBUTED_NOTEPROXYOP_CALL_1.matches(tree, state)
            || UNATTRIBUTED_NOTEPROXYOP_CALL_2.matches(tree, state)) {
            return buildDescription(tree)
                .setMessage("Unattributed noteProxyOp call!")
                .build();
        }
        if (UNATTRIBUTED_NOTEPROXYOPNOTHROW_CALL_1.matches(tree, state)
            || UNATTRIBUTED_NOTEPROXYOPNOTHROW_CALL_2.matches(tree, state)) {
            return buildDescription(tree)
                .setMessage("Unattributed noteProxyOpNoThrow call!")
                .build();
        }
        if (UNATTRIBUTED_FINISHOP_CALL_1.matches(tree, state)
            || UNATTRIBUTED_FINISHOP_CALL_2.matches(tree, state)
            || UNATTRIBUTED_FINISHOP_CALL_3.matches(tree, state)) {
            return buildDescription(tree)
                .setMessage("Unattributed finishOp call!")
                .build();
        }



        return Description.NO_MATCH;
    }
}
