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
import static com.google.errorprone.matchers.Matchers.allOf;
import static com.google.errorprone.matchers.Matchers.contains;
import static com.google.errorprone.matchers.Matchers.enclosingClass;
import static com.google.errorprone.matchers.Matchers.hasAnnotation;
import static com.google.errorprone.matchers.Matchers.instanceMethod;
import static com.google.errorprone.matchers.Matchers.isSameType;
import static com.google.errorprone.matchers.Matchers.methodInvocation;
import static com.google.errorprone.matchers.Matchers.not;
import static com.google.errorprone.matchers.Matchers.throwStatement;
import static com.google.errorprone.matchers.Matchers.variableType;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.TryTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.predicates.TypePredicate;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Type;

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
    linkType = NONE,
    severity = WARNING)
public final class RethrowFromSystemChecker extends BugChecker implements TryTreeMatcher {
    private static final Matcher<Tree> INSIDE_MANAGER =
            enclosingClass(hasAnnotation("android.annotation.SystemService"));

    // Purposefully exclude telephony Binder interfaces, since we know they
    // always run under the separate AID_RADIO
    private static final Matcher<ExpressionTree> SYSTEM_BINDER_CALL = methodInvocation(allOf(
            instanceMethod().onDescendantOf("android.os.IInterface").withAnyName(),
            not(instanceMethod().onClass(inPackage("com.android.internal.telephony"))),
            not(instanceMethod().onClass(inPackage("com.android.internal.telecom")))));

    private static final Matcher<VariableTree> REMOTE_EXCEPTION = variableType(
            isSameType("android.os.RemoteException"));
    private static final Matcher<StatementTree> RETHROW_FROM_SYSTEM = throwStatement(
            methodInvocation(instanceMethod().onExactClass("android.os.RemoteException")
                    .named("rethrowFromSystemServer")));

    @Override
    public Description matchTry(TryTree tree, VisitorState state) {
        if (INSIDE_MANAGER.matches(tree, state)
                && contains(ExpressionTree.class, SYSTEM_BINDER_CALL)
                        .matches(tree.getBlock(), state)) {
            for (CatchTree catchTree : tree.getCatches()) {
                if (REMOTE_EXCEPTION.matches(catchTree.getParameter(), state)) {
                    final List<? extends StatementTree> statements = catchTree.getBlock()
                            .getStatements();
                    if (statements.size() != 1
                            || !RETHROW_FROM_SYSTEM.matches(statements.get(0), state)) {
                        return buildDescription(catchTree)
                                .setMessage("Must contain single "
                                        + "'throw e.rethrowFromSystemServer()' statement")
                                .build();
                    }
                }
            }
        }
        return Description.NO_MATCH;
    }

    private static TypePredicate inPackage(final String filter) {
        return new TypePredicate() {
            @Override
            public boolean apply(Type type, VisitorState state) {
                return type.tsym.packge().fullname.toString().startsWith(filter);
            }
        };
    }
}
