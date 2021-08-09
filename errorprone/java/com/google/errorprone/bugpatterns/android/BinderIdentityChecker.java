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
import static com.google.errorprone.matchers.Matchers.contains;
import static com.google.errorprone.matchers.Matchers.methodInvocation;
import static com.google.errorprone.matchers.Matchers.staticMethod;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.MethodInvocationTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;

import java.util.List;

import javax.lang.model.element.Modifier;

/**
 * Binder maintains thread-local identity information about any remote caller,
 * which can be temporarily cleared while performing operations that need to be
 * handled as the current process. However, it's important to restore the
 * original remote calling identity after carefully scoping this work inside a
 * try/finally block, to avoid obscure security vulnerabilities.
 */
@AutoService(BugChecker.class)
@BugPattern(
    name = "AndroidFrameworkBinderIdentity",
    summary = "Verifies that Binder.clearCallingIdentity() is always restored",
    linkType = NONE,
    severity = WARNING)
public final class BinderIdentityChecker extends BugChecker implements MethodInvocationTreeMatcher {
    private static final Matcher<ExpressionTree> CLEAR_CALL = methodInvocation(staticMethod()
            .onClass("android.os.Binder").withSignature("clearCallingIdentity()"));
    private static final Matcher<ExpressionTree> RESTORE_CALL = methodInvocation(staticMethod()
            .onClass("android.os.Binder").withSignature("restoreCallingIdentity(long)"));

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        if (CLEAR_CALL.matches(tree, state)) {
            // First, make sure we're recording the token for later
            final VariableTree token = state.findEnclosing(VariableTree.class);
            if (token == null || !token.getModifiers().getFlags().contains(Modifier.FINAL)) {
                return buildDescription(tree)
                        .setMessage("Must store Binder.clearCallingIdentity() token as final"
                                + " variable to support safe restore")
                        .build();
            }

            // Next, verify the very next block is try-finally; any other calls
            // between the clearing and try risk throwing an exception without
            // doing a safe restore
            final Tree next = nextStatement(token, state);
            if (next == null || next.getKind() != Kind.TRY) {
                return buildDescription(tree)
                        .setMessage("Must immediately define a try-finally block after"
                                + " Binder.clearCallingIdentity() to support safe restore")
                        .build();
            }

            // Finally, verify that we restore inside the finally block
            final TryTree tryTree = (TryTree) next;
            final BlockTree finallyTree = tryTree.getFinallyBlock();
            if (finallyTree == null
                    || !contains(ExpressionTree.class, RESTORE_CALL).matches(finallyTree, state)) {
                return buildDescription(tree)
                        .setMessage("Must call Binder.restoreCallingIdentity() in finally"
                                + "  block to support safe restore")
                        .build();
            }
        }
        return Description.NO_MATCH;
    }

    private static Tree nextStatement(Tree tree, VisitorState state) {
        final BlockTree block = state.findEnclosing(BlockTree.class);
        if (block == null) return null;
        final List<? extends StatementTree> siblings = block.getStatements();
        if (siblings == null) return null;
        final int index = siblings.indexOf(tree);
        if (index == -1 || index + 1 >= siblings.size()) return null;
        return siblings.get(index + 1);
    }
}
