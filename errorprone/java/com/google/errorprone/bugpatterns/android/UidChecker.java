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

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.MethodInvocationTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.NewClassTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol.VarSymbol;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Many system internals pass around PID, UID and user ID arguments as a single
 * weakly-typed {@code int} value, which developers can accidentally cross in
 * method argument lists, resulting in obscure bugs.
 */
@AutoService(BugChecker.class)
@BugPattern(
    name = "AndroidFrameworkUid",
    summary = "Verifies that PID, UID and user ID arguments aren't crossed",
    linkType = NONE,
    severity = WARNING)
public final class UidChecker extends BugChecker implements MethodInvocationTreeMatcher,
        NewClassTreeMatcher {
    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        return matchArguments(ASTHelpers.getSymbol(tree).params(), tree.getArguments(), tree);
    }

    @Override
    public Description matchNewClass(NewClassTree tree, VisitorState state) {
        return matchArguments(ASTHelpers.getSymbol(tree).params(), tree.getArguments(), tree);
    }

    private Description matchArguments(List<VarSymbol> vars,
            List<? extends ExpressionTree> args, Tree tree) {
        for (int i = 0; i < Math.min(vars.size(), args.size()); i++) {
            final Flavor varFlavor = getFlavor(vars.get(i));
            final Flavor argFlavor = getFlavor(args.get(i));
            if (varFlavor == Flavor.UNKNOWN || argFlavor == Flavor.UNKNOWN) {
                continue;
            }
            if (varFlavor != argFlavor) {
                return buildDescription(tree).setMessage("Argument #" + (i + 1) + " expected "
                        + varFlavor + " but passed " + argFlavor).build();
            }
        }
        return Description.NO_MATCH;
    }

    public static enum Flavor {
        UNKNOWN(null),
        PID(Pattern.compile("(^pid$|Pid$)")),
        UID(Pattern.compile("(^uid$|Uid$)")),
        USER_ID(Pattern.compile("(^userId$|UserId$|^userHandle$|UserHandle$)"));

        private Pattern pattern;
        private Flavor(Pattern pattern) {
            this.pattern = pattern;
        }
        public boolean matches(CharSequence input) {
            return (pattern != null) && pattern.matcher(input).find();
        }
    }

    public static Flavor getFlavor(String name) {
        for (Flavor f : Flavor.values()) {
            if (f.matches(name)) {
                return f;
            }
        }
        return Flavor.UNKNOWN;
    }

    public static Flavor getFlavor(VarSymbol symbol) {
        final String type = symbol.type.toString();
        if ("int".equals(type)) {
            return getFlavor(symbol.name.toString());
        }
        return Flavor.UNKNOWN;
    }

    public static Flavor getFlavor(ExpressionTree tree) {
        if (tree instanceof IdentifierTree) {
            return getFlavor(((IdentifierTree) tree).getName().toString());
        } else if (tree instanceof MemberSelectTree) {
            return getFlavor(((MemberSelectTree) tree).getIdentifier().toString());
        } else if (tree instanceof MethodInvocationTree) {
            return getFlavor(((MethodInvocationTree) tree).getMethodSelect());
        }
        return Flavor.UNKNOWN;
    }
}
