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

import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;
import static com.google.errorprone.matchers.Matchers.enclosingClass;
import static com.google.errorprone.matchers.Matchers.hasAnnotation;
import static com.google.errorprone.matchers.Matchers.instanceMethod;
import static com.google.errorprone.matchers.Matchers.isSameType;
import static com.google.errorprone.matchers.Matchers.methodInvocation;
import static com.google.errorprone.matchers.Matchers.throwStatement;
import static com.google.errorprone.matchers.Matchers.variableType;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.CatchTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;

import java.util.List;

/**
 * Apps making calls into the system server may end up persisting internal state
 * or making security decisions based on the perceived success or failure of a
 * call, or any default values returned. For this reason, we want to strongly
 * throw when there was trouble with the transaction.
 * <p>
 * The rethrowFromSystemServer() method is the best-practice way of doing this
 * correctly, so that we don't clutter logs with misleading stack traces, and
 * this checker verifies that best-practice is used.
 */
@AutoService(BugChecker.class)
@BugPattern(
    name = "AndroidFrameworkRethrowFromSystem",
    summary = "Verifies that system_server calls use rethrowFromSystemServer()",
    severity = WARNING)
public final class RethrowFromSystemChecker extends BugChecker implements CatchTreeMatcher {
    private static final Matcher<Tree> INSIDE_MANAGER =
            enclosingClass(hasAnnotation("android.annotation.SystemService"));
    private static final Matcher<VariableTree> REMOTE_EXCEPTION = variableType(
            isSameType("android.os.RemoteException"));
    private static final Matcher<StatementTree> RETHROW_FROM_SYSTEM = throwStatement(
            methodInvocation(instanceMethod().onExactClass("android.os.RemoteException")
                    .named("rethrowFromSystemServer")));

    @Override
    public Description matchCatch(CatchTree tree, VisitorState state) {
        if (INSIDE_MANAGER.matches(tree, state)
                && REMOTE_EXCEPTION.matches(tree.getParameter(), state)) {
            final List<? extends StatementTree> statements = tree.getBlock().getStatements();
            if (statements.size() != 1 || !RETHROW_FROM_SYSTEM.matches(statements.get(0), state)) {
                return buildDescription(tree)
                        .setMessage("Must contain single "
                                + "'throw e.rethrowFromSystemServer()' statement")
                        .build();
            }
        }
        return Description.NO_MATCH;
    }
}
