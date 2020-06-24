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
import static com.google.errorprone.matchers.Matchers.methodInvocation;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.MethodInvocationTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol.VarSymbol;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * To avoid an explosion of {@code startActivityForUser} style methods, we've
 * converged on recommending the use of {@code Context.createContextAsUser()},
 * and then ensuring that all system services pass {@link Context.getUserId()}
 * for any {@code int userId} arguments across Binder interfaces.
 * <p>
 * This design allows developers to easily redirect all services obtained from a
 * specific {@code Context} to a different user with no additional API surface.
 */
@AutoService(BugChecker.class)
@BugPattern(
    name = "AndroidFrameworkContextUserId",
    summary = "Verifies that system_server calls use Context.getUserId()",
    severity = WARNING)
public final class ContextUserIdChecker extends BugChecker implements MethodInvocationTreeMatcher {
    private static final Matcher<Tree> INSIDE_MANAGER =
            enclosingClass(hasAnnotation("android.annotation.SystemService"));

    private static final Matcher<ExpressionTree> BINDER_CALL = methodInvocation(
            instanceMethod().onDescendantOf("android.os.IInterface").withAnyName());
    private static final Matcher<ExpressionTree> GET_USER_ID_CALL = methodInvocation(
            instanceMethod().onDescendantOf("android.content.Context").named("getUserId"));

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        if (INSIDE_MANAGER.matches(tree, state)
                && BINDER_CALL.matches(tree, state)) {
            final List<VarSymbol> vars = ASTHelpers.getSymbol(tree).params();
            for (int i = 0; i < vars.size(); i++) {
                if (USER_ID_VAR.test(vars.get(i)) &&
                        !GET_USER_ID_CALL.matches(tree.getArguments().get(i), state)) {
                    return buildDescription(tree)
                            .setMessage("Must pass Context.getUserId() as user ID"
                                    + "to enable createContextAsUser()")
                            .build();
                }
            }
        }
        return Description.NO_MATCH;
    }

    private static final UserIdMatcher USER_ID_VAR = new UserIdMatcher();

    private static class UserIdMatcher implements Predicate<VarSymbol> {
        @Override
        public boolean test(VarSymbol t) {
            if ("int".equals(t.type.toString())) {
                switch (t.name.toString().toLowerCase(Locale.ROOT)) {
                    case "user":
                    case "userid":
                    case "userhandle":
                    case "user_id":
                        return true;
                }
            }
            return false;
        }
    }
}
