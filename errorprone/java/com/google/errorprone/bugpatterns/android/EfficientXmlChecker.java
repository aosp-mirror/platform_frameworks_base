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
import static com.google.errorprone.matchers.Matchers.instanceMethod;
import static com.google.errorprone.matchers.Matchers.isSubtypeOf;
import static com.google.errorprone.matchers.Matchers.methodInvocation;
import static com.google.errorprone.matchers.Matchers.staticMethod;
import static com.google.errorprone.matchers.Matchers.stringLiteral;

import com.google.auto.service.AutoService;
import com.google.common.base.Objects;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.MethodInvocationTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.NewClassTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;
import com.sun.tools.javac.code.Type;

import java.util.List;
import java.util.regex.Pattern;

import javax.lang.model.element.Name;

/**
 * Android offers {@code TypedXmlSerializer} and {@code TypedXmlPullParser} to
 * more efficiently store primitive values.
 * <p>
 * This checker identifies callers that are manually converting strings instead
 * of relying on efficient strongly-typed methods.
 */
@AutoService(BugChecker.class)
@BugPattern(
    name = "AndroidFrameworkEfficientXml",
    summary = "Verifies efficient XML best-practices",
    linkType = NONE,
    severity = WARNING)
public final class EfficientXmlChecker extends BugChecker
        implements MethodInvocationTreeMatcher, NewClassTreeMatcher {
    private static final String STRING = "java.lang.String";
    private static final String INTEGER = "java.lang.Integer";
    private static final String LONG = "java.lang.Long";
    private static final String FLOAT = "java.lang.Float";
    private static final String DOUBLE = "java.lang.Double";
    private static final String BOOLEAN = "java.lang.Boolean";

    private static final Matcher<ExpressionTree> BOOLEAN_STRING_LITERAL = stringLiteral(
            Pattern.compile("(true|false)"));

    private static final Matcher<ExpressionTree> PRIMITIVE_TO_STRING = anyOf(
            methodInvocation(staticMethod().onClass(INTEGER).named("toString")),
            methodInvocation(staticMethod().onClass(INTEGER).named("toHexString")),
            methodInvocation(staticMethod().onClass(LONG).named("toString")),
            methodInvocation(staticMethod().onClass(LONG).named("toHexString")),
            methodInvocation(staticMethod().onClass(FLOAT).named("toString")),
            methodInvocation(staticMethod().onClass(DOUBLE).named("toString")),
            methodInvocation(staticMethod().onClass(BOOLEAN).named("toString")),
            methodInvocation(instanceMethod().onExactClass(INTEGER).named("toString")),
            methodInvocation(instanceMethod().onExactClass(LONG).named("toString")),
            methodInvocation(instanceMethod().onExactClass(FLOAT).named("toString")),
            methodInvocation(instanceMethod().onExactClass(DOUBLE).named("toString")),
            methodInvocation(instanceMethod().onExactClass(BOOLEAN).named("toString")));

    private static final Matcher<ExpressionTree> VALUE_OF_PRIMITIVE = anyOf(
            methodInvocation(staticMethod().onClass(STRING).withSignature("valueOf(int)")),
            methodInvocation(staticMethod().onClass(STRING).withSignature("valueOf(long)")),
            methodInvocation(staticMethod().onClass(STRING).withSignature("valueOf(float)")),
            methodInvocation(staticMethod().onClass(STRING).withSignature("valueOf(double)")),
            methodInvocation(staticMethod().onClass(STRING).withSignature("valueOf(boolean)")));

    private static final Matcher<ExpressionTree> VALUE_OF_OBJECT = methodInvocation(
            staticMethod().onClass(STRING).withSignature("valueOf(java.lang.Object)"));

    private static final Matcher<ExpressionTree> PRIMITIVE_PARSE = anyOf(
            methodInvocation(staticMethod().onClass(INTEGER).named("parseInt")),
            methodInvocation(staticMethod().onClass(INTEGER)
                    .withSignature("valueOf(java.lang.String)")),
            methodInvocation(staticMethod().onClass(INTEGER)
                    .withSignature("valueOf(java.lang.String,int)")),
            methodInvocation(staticMethod().onClass(LONG).named("parseLong")),
            methodInvocation(staticMethod().onClass(LONG)
                    .withSignature("valueOf(java.lang.String)")),
            methodInvocation(staticMethod().onClass(LONG)
                    .withSignature("valueOf(java.lang.String,int)")),
            methodInvocation(staticMethod().onClass(FLOAT).named("parseFloat")),
            methodInvocation(staticMethod().onClass(FLOAT)
                    .withSignature("valueOf(java.lang.String)")),
            methodInvocation(staticMethod().onClass(DOUBLE).named("parseDouble")),
            methodInvocation(staticMethod().onClass(DOUBLE)
                    .withSignature("valueOf(java.lang.String)")),
            methodInvocation(staticMethod().onClass(BOOLEAN).named("parseBoolean")),
            methodInvocation(staticMethod().onClass(BOOLEAN)
                    .withSignature("valueOf(java.lang.String)")));

    private static final Matcher<Tree> IS_FAST_XML_SERIALIZER =
            isSubtypeOf("com.android.internal.util.FastXmlSerializer");

    private static final Matcher<ExpressionTree> WRITE_ATTRIBUTE = methodInvocation(
            instanceMethod().onDescendantOf("org.xmlpull.v1.XmlSerializer")
                    .named("attribute"));

    private static final Matcher<ExpressionTree> READ_ATTRIBUTE = methodInvocation(
            instanceMethod().onDescendantOf("org.xmlpull.v1.XmlPullParser")
                    .named("getAttributeValue"));

    private static final Matcher<ExpressionTree> XML_FACTORY = methodInvocation(staticMethod()
            .onClass("android.util.Xml").namedAnyOf("newSerializer", "newPullParser"));

    private static final Matcher<ExpressionTree> BYTES_TO_STRING = anyOf(
            methodInvocation(staticMethod().onClass("android.util.Base64")
                    .named("encodeToString")),
            methodInvocation(instanceMethod().onDescendantOf("java.util.Base64.Encoder")
                    .named("encodeToString")),
            methodInvocation(staticMethod().onClass("libcore.util.HexEncoding")
                    .named("encodeToString")),
            methodInvocation(staticMethod().onClass("com.android.internal.util.HexDump")
                    .named("toHexString")));

    private static final Matcher<ExpressionTree> BYTES_FROM_STRING = anyOf(
            methodInvocation(staticMethod().onClass("android.util.Base64")
                    .named("decode")),
            methodInvocation(instanceMethod().onDescendantOf("java.util.Base64.Decoder")
                    .named("decode")),
            methodInvocation(staticMethod().onClass("libcore.util.HexEncoding")
                    .named("decode")),
            methodInvocation(staticMethod().onClass("com.android.internal.util.HexDump")
                    .named("hexStringToByteArray")));

    private static final String MESSAGE_WRITE = "Primitive values can be written more "
            + "efficiently by using TypedXmlSerializer overloads";
    private static final String MESSAGE_READ = "Primitive values can be parsed more "
            + "efficiently by using TypedXmlPullParser overloads";
    private static final String MESSAGE_CTOR = "Primitive values can be parsed more "
            + "efficiently by using Xml.resolveSerializer() and/or Xml.resolvePullParser()";

    /**
     * Determine if the given expression is attempting to convert a primitive to
     * a {@link String}.
     */
    private static final Matcher<ExpressionTree> CONVERT_PRIMITIVE_TO_STRING =
            new Matcher<ExpressionTree>() {
        @Override
        public boolean matches(ExpressionTree tree, VisitorState state) {
            if (PRIMITIVE_TO_STRING.matches(tree, state)) {
                final List<? extends ExpressionTree> args = ((MethodInvocationTree) tree)
                        .getArguments();
                // We're only interested in base-10 or base-16 numerical conversions
                if (args.size() <= 1 || String.valueOf(args.get(1)).equals("10")
                        || String.valueOf(args.get(1)).equals("16")) {
                    return true;
                }
            } else if (VALUE_OF_PRIMITIVE.matches(tree, state)) {
                return true;
            } else if (VALUE_OF_OBJECT.matches(tree, state)) {
                final Type type = ASTHelpers.getResultType(((MethodInvocationTree) tree)
                        .getArguments().get(0));
                if (ASTHelpers.isSameType(type, state.getTypeFromString(INTEGER), state)
                        || ASTHelpers.isSameType(type, state.getTypeFromString(LONG), state)
                        || ASTHelpers.isSameType(type, state.getTypeFromString(FLOAT), state)
                        || ASTHelpers.isSameType(type, state.getTypeFromString(DOUBLE), state)
                        || ASTHelpers.isSameType(type, state.getTypeFromString(BOOLEAN), state)) {
                    return true;
                }
            } else if (BOOLEAN_STRING_LITERAL.matches(tree, state)) {
                return true;
            } else if (BYTES_TO_STRING.matches(tree, state)) {
                return true;
            }
            return false;
        }
    };

    /**
     * Determine if the given expression is attempting to convert a
     * {@link String} to a primitive.
     */
    private static final Matcher<ExpressionTree> CONVERT_STRING_TO_PRIMITIVE =
            new Matcher<ExpressionTree>() {
        @Override
        public boolean matches(ExpressionTree tree, VisitorState state) {
            if (PRIMITIVE_PARSE.matches(tree, state)) {
                final List<? extends ExpressionTree> args = ((MethodInvocationTree) tree)
                        .getArguments();
                // We're only interested in base-10 or base-16 numerical conversions
                if (args.size() <= 1 || String.valueOf(args.get(1)).equals("10")
                        || String.valueOf(args.get(1)).equals("16")) {
                    return true;
                }
            } else if (BYTES_FROM_STRING.matches(tree, state)) {
                return true;
            }
            return false;
        }
    };

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        if (WRITE_ATTRIBUTE.matches(tree, state)) {
            final List<? extends ExpressionTree> args = tree.getArguments();
            final ExpressionTree writeSource = args.get(2);
            if (CONVERT_PRIMITIVE_TO_STRING.matches(writeSource, state)) {
                return buildDescription(tree).setMessage(MESSAGE_WRITE).build();
            }

            // Hunt around for related conversions
            if (writeSource instanceof IdentifierTree) {
                final Name name = ((IdentifierTree) writeSource).getName();
                final Description res = new TreePathScanner<Description, Void>() {
                    @Override
                    public Description reduce(Description r1, Description r2) {
                        return (r1 != null) ? r1 : r2;
                    }

                    @Override
                    public Description visitVariable(VariableTree node, Void param) {
                        return visitWriteSource(node.getName(), node.getInitializer());
                    }

                    @Override
                    public Description visitAssignment(AssignmentTree node, Void param) {
                        final ExpressionTree variable = node.getVariable();
                        if (variable instanceof IdentifierTree) {
                            return visitWriteSource(((IdentifierTree) variable).getName(),
                                    node.getExpression());
                        } else {
                            return super.visitAssignment(node, param);
                        }
                    }

                    private Description visitWriteSource(Name target, ExpressionTree source) {
                        if (CONVERT_PRIMITIVE_TO_STRING.matches(source, state)
                                && Objects.equal(name, target)) {
                            return buildDescription(source).setMessage(MESSAGE_WRITE).build();
                        } else {
                            return null;
                        }
                    }
                }.scan(state.findPathToEnclosing(MethodTree.class), null);
                if (res != null) {
                    return res;
                }
            }
        } else if (READ_ATTRIBUTE.matches(tree, state)) {
            final Tree readDest = state.getPath().getParentPath().getLeaf();
            if (readDest instanceof ExpressionTree
                    && CONVERT_STRING_TO_PRIMITIVE.matches((ExpressionTree) readDest, state)) {
                return buildDescription(tree).setMessage(MESSAGE_READ).build();
            }

            // Hunt around for related conversions
            Name name = null;
            if (readDest instanceof VariableTree) {
                name = ((VariableTree) readDest).getName();
            } else if (readDest instanceof AssignmentTree) {
                final ExpressionTree variable = ((AssignmentTree) readDest).getVariable();
                if (variable instanceof IdentifierTree) {
                    name = ((IdentifierTree) variable).getName();
                }
            }
            if (name != null) {
                final Name fName = name;
                final Description res = new TreePathScanner<Description, Void>() {
                    @Override
                    public Description reduce(Description r1, Description r2) {
                        return (r1 != null) ? r1 : r2;
                    }

                    @Override
                    public Description visitMethodInvocation(MethodInvocationTree node,
                            Void param) {
                        if (CONVERT_STRING_TO_PRIMITIVE.matches(node, state)) {
                            final ExpressionTree arg = node.getArguments().get(0);
                            if (arg instanceof IdentifierTree
                                    && Objects.equal(((IdentifierTree) arg).getName(), fName)) {
                                return buildDescription(node).setMessage(MESSAGE_READ).build();
                            }
                        }
                        return super.visitMethodInvocation(node, param);
                    }
                }.scan(state.findPathToEnclosing(MethodTree.class), null);
                if (res != null) {
                    return res;
                }
            }
        } else if (XML_FACTORY.matches(tree, state)) {
            return buildDescription(tree).setMessage(MESSAGE_CTOR).build();
        }
        return Description.NO_MATCH;
    }

    @Override
    public Description matchNewClass(NewClassTree tree, VisitorState state) {
        if (IS_FAST_XML_SERIALIZER.matches(tree, state)) {
            return buildDescription(tree).setMessage(MESSAGE_CTOR).build();
        }
        return Description.NO_MATCH;
    }
}
