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
import static com.google.errorprone.matchers.Matchers.allOf;
import static com.google.errorprone.matchers.Matchers.contains;
import static com.google.errorprone.matchers.Matchers.kindIs;
import static com.google.errorprone.matchers.Matchers.methodInvocation;
import static com.google.errorprone.matchers.Matchers.not;
import static com.google.errorprone.matchers.Matchers.staticMethod;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.MethodInvocationTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.predicates.TypePredicate;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree.Kind;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Type;

import java.util.List;

/**
 * Android offers several efficient alternatives to some upstream {@link String}
 * operations.
 */
@AutoService(BugChecker.class)
@BugPattern(
    name = "AndroidFrameworkEfficientStrings",
    summary = "Verifies efficient Strings best-practices",
    severity = WARNING)
public final class EfficientStringsChecker extends BugChecker
        implements MethodInvocationTreeMatcher {

    private static final Matcher<ExpressionTree> FORMAT_CALL = methodInvocation(
            staticMethod().onClass("java.lang.String").named("format"));
    private static final Matcher<ExpressionTree> PRECONDITIONS_CALL = methodInvocation(
            staticMethod().onClass(withSimpleName("Preconditions")).withAnyName());
    private static final Matcher<ExpressionTree> OBJECTS_CALL = methodInvocation(
            staticMethod().onClass("java.util.Objects").named("requireNonNull"));

    /**
     * Match an expression which combines both string literals any other dynamic
     * values, since these allocate a transparent StringBuilder.
     * <p>
     * This won't match a single isolated string literal, or a chain consisting
     * of only string literals, since those don't require dynamic construction.
     */
    private static final Matcher<ExpressionTree> CONTAINS_DYNAMIC_STRING = allOf(
            contains(ExpressionTree.class, kindIs(Kind.STRING_LITERAL)),
            contains(ExpressionTree.class, not(kindIs(Kind.STRING_LITERAL))));

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        if (FORMAT_CALL.matches(tree, state)) {
            // Skip over possible locale to find format string
            final List<? extends ExpressionTree> args = tree.getArguments();
            final ExpressionTree formatArg;
            final List<VarSymbol> vars = ASTHelpers.getSymbol(tree).params();
            if (vars.get(0).type.toString().equals("java.util.Locale")) {
                formatArg = args.get(1);
            } else {
                formatArg = args.get(0);
            }

            // Determine if format string is "simple" enough to replace
            if (formatArg.getKind() == Kind.STRING_LITERAL) {
                final String format = String.valueOf(((LiteralTree) formatArg).getValue());
                if (isSimple(format)) {
                    return buildDescription(formatArg)
                            .setMessage("Simple format strings can be replaced with "
                                    + "TextUtils.formatSimple() for a 6x performance improvement")
                            .build();
                }
            }
        } else if (PRECONDITIONS_CALL.matches(tree, state)
                || OBJECTS_CALL.matches(tree, state)) {
            final List<? extends ExpressionTree> args = tree.getArguments();
            for (int i = 1 ; i < args.size(); i++) {
                final ExpressionTree arg = args.get(i);
                if (CONTAINS_DYNAMIC_STRING.matches(arg, state)) {
                    return buildDescription(arg)
                            .setMessage("Building dynamic messages is discouraged, since they "
                                    + "always allocate a transparent StringBuilder, even in "
                                    + "the successful case")
                            .build();
                }
            }
        }
        return Description.NO_MATCH;
    }

    static boolean isSimple(String format) {
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c == '%') {
                i++;
                c = format.charAt(i);
                switch (c) {
                    case 'b':
                    case 'c':
                    case 'd':
                    case 'f':
                    case 's':
                    case 'x':
                    case '%':
                        break;
                    default:
                        return false;
                }
            }
        }
        return true;
    }

    static TypePredicate withSimpleName(final String filter) {
        return new TypePredicate() {
            @Override
            public boolean apply(Type type, VisitorState state) {
                return type.tsym.getSimpleName().toString().equals(filter);
            }
        };
    }
}
