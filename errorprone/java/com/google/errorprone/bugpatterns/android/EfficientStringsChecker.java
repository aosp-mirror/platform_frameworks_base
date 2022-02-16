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
import static com.google.errorprone.matchers.Matchers.anyOf;
import static com.google.errorprone.matchers.Matchers.contains;
import static com.google.errorprone.matchers.Matchers.hasModifier;
import static com.google.errorprone.matchers.Matchers.instanceMethod;
import static com.google.errorprone.matchers.Matchers.isSubtypeOf;
import static com.google.errorprone.matchers.Matchers.kindIs;
import static com.google.errorprone.matchers.Matchers.methodInvocation;
import static com.google.errorprone.matchers.Matchers.not;
import static com.google.errorprone.matchers.Matchers.staticMethod;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.CompoundAssignmentTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.MethodInvocationTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.NewClassTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.predicates.TypePredicate;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Type;

import java.util.List;
import java.util.Objects;

import javax.lang.model.element.Modifier;

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
        implements MethodInvocationTreeMatcher, NewClassTreeMatcher, CompoundAssignmentTreeMatcher {

    private static final Matcher<ExpressionTree> FORMAT_CALL = methodInvocation(
            staticMethod().onClass("java.lang.String").named("format"));
    private static final Matcher<ExpressionTree> PRECONDITIONS_CALL = methodInvocation(
            staticMethod().onClass(withSimpleName("Preconditions")).withAnyName());
    private static final Matcher<ExpressionTree> OBJECTS_CALL = methodInvocation(
            staticMethod().onClass("java.util.Objects").named("requireNonNull"));
    private static final Matcher<ExpressionTree> APPEND_CALL = methodInvocation(
            instanceMethod().onExactClass("java.lang.StringBuilder")
                    .withSignature("append(java.lang.String)"));

    /**
     * Identify any dynamic values that will likely cause us to allocate a
     * transparent StringBuilder.
     */
    private static final Matcher<ExpressionTree> DYNAMIC_VALUE = anyOf(
            allOf(kindIs(Kind.MEMBER_SELECT),
                    not(allOf(hasModifier(Modifier.STATIC), hasModifier(Modifier.FINAL)))),
            allOf(kindIs(Kind.IDENTIFIER),
                    not(allOf(hasModifier(Modifier.STATIC), hasModifier(Modifier.FINAL)))),
            kindIs(Kind.METHOD_INVOCATION));

    /**
     * Identify an expression that is either a direct "+" binary operator, or
     * that contains a "+" binary operator nested deep inside.
     */
    private static final Matcher<Tree> PLUS = anyOf(kindIs(Kind.PLUS),
            contains(BinaryTree.class, kindIs(Kind.PLUS)));

    /**
     * Identify an expression that is using a "+" binary operator to combine
     * dynamic values, which will likely end up allocating a transparent
     * {@link StringBuilder}.
     */
    private static final Matcher<Tree> PLUS_DYNAMIC_VALUE = allOf(
            PLUS, contains(ExpressionTree.class, DYNAMIC_VALUE));

    private static final Matcher<Tree> IS_STRING_BUFFER = isSubtypeOf("java.lang.StringBuffer");

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
            if (args.size() > 1) {
                final ExpressionTree arg = args.get(1);
                if (PLUS_DYNAMIC_VALUE.matches(arg, state)) {
                    return buildDescription(arg)
                            .setMessage("Building dynamic messages is discouraged, since they "
                                    + "always allocate a transparent StringBuilder, even in "
                                    + "the successful case")
                            .build();
                }
            }
        } else if (APPEND_CALL.matches(tree, state)) {
            final ExpressionTree arg = tree.getArguments().get(0);
            if (PLUS_DYNAMIC_VALUE.matches(arg, state)) {
                return buildDescription(arg)
                        .setMessage("Call append() directly for each argument instead of "
                                + "allocating a transparent StringBuilder")
                        .build();
            }
        }
        return Description.NO_MATCH;
    }

    @Override
    public Description matchNewClass(NewClassTree tree, VisitorState state) {
        if (IS_STRING_BUFFER.matches(tree, state)) {
            return buildDescription(tree)
                    .setMessage("Strongly encouraged to replace with StringBuilder "
                            + "which avoids synchronization overhead")
                    .build();
        }
        return Description.NO_MATCH;
    }

    @Override
    public Description matchCompoundAssignment(CompoundAssignmentTree tree, VisitorState state) {
        if (tree.getKind() == Kind.PLUS_ASSIGNMENT && "java.lang.String"
                .equals(String.valueOf(ASTHelpers.getType(tree.getVariable())))) {
            return buildDescription(tree)
                    .setMessage("Strongly encouraged to replace with StringBuilder "
                            + "which avoids transparent StringBuilder allocations")
                    .build();
        }
        return Description.NO_MATCH;
    }

    static boolean isSimple(String format) {
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c == '%') {
                c = format.charAt(++i);
                while ('0' <= c && c <= '9') {
                    c = format.charAt(++i);
                }
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
