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
import static com.google.errorprone.matchers.Matchers.allOf;
import static com.google.errorprone.matchers.Matchers.anyOf;
import static com.google.errorprone.matchers.Matchers.enclosingClass;
import static com.google.errorprone.matchers.Matchers.instanceMethod;
import static com.google.errorprone.matchers.Matchers.isSubtypeOf;
import static com.google.errorprone.matchers.Matchers.methodInvocation;
import static com.google.errorprone.matchers.Matchers.methodIsNamed;
import static com.google.errorprone.matchers.Matchers.staticMethod;

import android.annotation.SuppressLint;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.MethodTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ClassType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;

/**
 * Inspects both the client and server side of AIDL interfaces to ensure that
 * any {@code RequiresPermission} annotations are consistently declared and
 * enforced.
 */
@AutoService(BugChecker.class)
@BugPattern(
    name = "AndroidFrameworkRequiresPermission",
    summary = "Verifies that @RequiresPermission annotations are consistent across AIDL",
    severity = WARNING)
public final class RequiresPermissionChecker extends BugChecker implements MethodTreeMatcher {
    private static final String ANNOTATION_REQUIRES_PERMISSION = "RequiresPermission";

    private static final Matcher<ExpressionTree> ENFORCE_VIA_CONTEXT = methodInvocation(
            instanceMethod()
                    .onDescendantOf("android.content.Context")
                    .withNameMatching(
                            Pattern.compile("^(enforce|check)(Calling)?(OrSelf)?Permission$")));
    private static final Matcher<ExpressionTree> ENFORCE_VIA_CHECKER = methodInvocation(
            staticMethod()
                    .onClass("android.content.PermissionChecker")
                    .withNameMatching(Pattern.compile("^check.*")));

    private static final Matcher<MethodTree> BINDER_INTERNALS = allOf(
            enclosingClass(isSubtypeOf("android.os.IInterface")),
            anyOf(
                    methodIsNamed("onTransact"),
                    methodIsNamed("dump"),
                    enclosingClass(simpleNameMatches(Pattern.compile("^(Stub|Default|Proxy)$")))));
    private static final Matcher<MethodTree> LOCAL_INTERNALS = anyOf(
            methodIsNamed("finalize"),
            allOf(
                    enclosingClass(isSubtypeOf("android.content.BroadcastReceiver")),
                    methodIsNamed("onReceive")),
            allOf(
                    enclosingClass(isSubtypeOf("android.database.ContentObserver")),
                    methodIsNamed("onChange")),
            allOf(
                    enclosingClass(isSubtypeOf("android.os.Handler")),
                    methodIsNamed("handleMessage")),
            allOf(
                    enclosingClass(isSubtypeOf("android.os.IBinder.DeathRecipient")),
                    methodIsNamed("binderDied")));

    private static final Matcher<ExpressionTree> CLEAR_CALL = methodInvocation(staticMethod()
            .onClass("android.os.Binder").withSignature("clearCallingIdentity()"));
    private static final Matcher<ExpressionTree> RESTORE_CALL = methodInvocation(staticMethod()
            .onClass("android.os.Binder").withSignature("restoreCallingIdentity(long)"));

    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
        // Ignore methods without an implementation
        if (tree.getBody() == null) return Description.NO_MATCH;

        // Ignore certain types of Binder generated code
        if (BINDER_INTERNALS.matches(tree, state)) return Description.NO_MATCH;

        // Ignore known-local methods which don't need to propagate
        if (LOCAL_INTERNALS.matches(tree, state)) return Description.NO_MATCH;

        // Ignore when suppressed via superclass
        final MethodSymbol method = ASTHelpers.getSymbol(tree);
        if (isSuppressedRecursively(method, state)) return Description.NO_MATCH;

        // First, look at all outgoing method invocations to ensure that we
        // carry those annotations forward; yell if we're too narrow
        final ParsedRequiresPermission expectedPerm = parseRequiresPermissionRecursively(
                method, state);
        final ParsedRequiresPermission actualPerm = new ParsedRequiresPermission();
        final Description desc = tree.accept(new TreeScanner<Description, Void>() {
            private boolean clearedCallingIdentity = false;

            @Override
            public Description visitMethodInvocation(MethodInvocationTree node, Void param) {
                if (CLEAR_CALL.matches(node, state)) {
                    clearedCallingIdentity = true;
                } else if (RESTORE_CALL.matches(node, state)) {
                    clearedCallingIdentity = false;
                } else if (!clearedCallingIdentity) {
                    final ParsedRequiresPermission nodePerm = parseRequiresPermissionRecursively(
                            node, state);
                    if (!expectedPerm.containsAll(nodePerm)) {
                        return buildDescription(node).setMessage("Annotated " + expectedPerm
                                + " but too narrow; invokes method requiring " + nodePerm).build();
                    } else {
                        actualPerm.addAll(nodePerm);
                    }
                }
                return super.visitMethodInvocation(node, param);
            }

            @Override
            public Description reduce(Description r1, Description r2) {
                return (r1 != null) ? r1 : r2;
            }
        }, null);
        if (desc != null) return desc;

        // Second, determine if we actually used all permissions that we claim
        // to require; yell if we're too broad
        if (!actualPerm.containsAll(expectedPerm)) {
            return buildDescription(tree).setMessage("Annotated " + expectedPerm
                    + " but too wide; only invokes methods requiring " + actualPerm).build();
        }

        return Description.NO_MATCH;
    }

    static class ParsedRequiresPermission {
        final Set<String> allOf = new HashSet<>();
        final Set<String> anyOf = new HashSet<>();

        public boolean isEmpty() {
            return allOf.isEmpty() && anyOf.isEmpty();
        }

        /**
         * Validate that this annotation effectively "contains" the given
         * annotation. This is typically used to ensure that a method carries
         * along all relevant annotations for the methods it invokes.
         */
        public boolean containsAll(ParsedRequiresPermission perm) {
            boolean allMet = allOf.containsAll(perm.allOf);
            boolean anyMet = false;
            if (perm.anyOf.isEmpty()) {
                anyMet = true;
            } else {
                for (String anyPerm : perm.anyOf) {
                    if (allOf.contains(anyPerm) || anyOf.contains(anyPerm)) {
                        anyMet = true;
                    }
                }
            }
            return allMet && anyMet;
        }

        @Override
        public String toString() {
            if (isEmpty()) {
                return "[none]";
            }
            String res = "{allOf=" + allOf;
            if (!anyOf.isEmpty()) {
                res += " anyOf=" + anyOf;
            }
            res += "}";
            return res;
        }

        public void addAll(ParsedRequiresPermission perm) {
            this.allOf.addAll(perm.allOf);
            this.anyOf.addAll(perm.anyOf);
        }

        public void addAll(AnnotationMirror a) {
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : a
                    .getElementValues().entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals("value")) {
                    maybeAdd(allOf, entry.getValue());
                } else if (entry.getKey().getSimpleName().contentEquals("allOf")) {
                    maybeAdd(allOf, entry.getValue());
                } else if (entry.getKey().getSimpleName().contentEquals("anyOf")) {
                    maybeAdd(anyOf, entry.getValue());
                }
            }
        }

        private static void maybeAdd(Set<String> set, Object value) {
            if (value instanceof AnnotationValue) {
                maybeAdd(set, ((AnnotationValue) value).getValue());
            } else if (value instanceof String) {
                set.add((String) value);
            } else if (value instanceof Collection) {
                for (Object o : (Collection) value) {
                    maybeAdd(set, o);
                }
            } else {
                throw new RuntimeException(String.valueOf(value.getClass()));
            }
        }
    }

    private static ParsedRequiresPermission parseRequiresPermissionRecursively(
            MethodInvocationTree tree, VisitorState state) {
        if (ENFORCE_VIA_CONTEXT.matches(tree, state)) {
            final ParsedRequiresPermission res = new ParsedRequiresPermission();
            res.allOf.add(String.valueOf(ASTHelpers.constValue(tree.getArguments().get(0))));
            return res;
        } else if (ENFORCE_VIA_CHECKER.matches(tree, state)) {
            final ParsedRequiresPermission res = new ParsedRequiresPermission();
            res.allOf.add(String.valueOf(ASTHelpers.constValue(tree.getArguments().get(1))));
            return res;
        } else {
            final MethodSymbol method = ASTHelpers.getSymbol(tree);
            return parseRequiresPermissionRecursively(method, state);
        }
    }

    /**
     * Parse any {@code RequiresPermission} annotations associated with the
     * given method, defined either directly on the method or by any superclass.
     */
    private static ParsedRequiresPermission parseRequiresPermissionRecursively(
            MethodSymbol method, VisitorState state) {
        final List<MethodSymbol> symbols = new ArrayList<>();
        symbols.add(method);
        symbols.addAll(ASTHelpers.findSuperMethods(method, state.getTypes()));

        final ParsedRequiresPermission res = new ParsedRequiresPermission();
        for (MethodSymbol symbol : symbols) {
            for (AnnotationMirror a : symbol.getAnnotationMirrors()) {
                if (a.getAnnotationType().asElement().getSimpleName()
                        .contentEquals(ANNOTATION_REQUIRES_PERMISSION)) {
                    res.addAll(a);
                }
            }
        }
        return res;
    }

    private boolean isSuppressedRecursively(MethodSymbol method, VisitorState state) {
        // Is method suppressed anywhere?
        if (isSuppressed(method)) return true;
        for (MethodSymbol symbol : ASTHelpers.findSuperMethods(method, state.getTypes())) {
            if (isSuppressed(symbol)) return true;
        }

        // Is class suppressed anywhere?
        final ClassSymbol clazz = ASTHelpers.enclosingClass(method);
        if (isSuppressed(clazz)) return true;
        Type type = clazz.getSuperclass();
        while (type != null) {
            if (isSuppressed(type.tsym)) return true;
            if (type instanceof ClassType) {
                type = ((ClassType) type).supertype_field;
            } else {
                type = null;
            }
        }
        return false;
    }

    public boolean isSuppressed(Symbol symbol) {
        return isSuppressed(ASTHelpers.getAnnotation(symbol, SuppressWarnings.class))
                || isSuppressed(ASTHelpers.getAnnotation(symbol, SuppressLint.class));
    }

    private boolean isSuppressed(SuppressWarnings anno) {
        return (anno != null) && !Collections.disjoint(Arrays.asList(anno.value()), allNames());
    }

    private boolean isSuppressed(SuppressLint anno) {
        return (anno != null) && !Collections.disjoint(Arrays.asList(anno.value()), allNames());
    }

    private static Matcher<ClassTree> simpleNameMatches(Pattern pattern) {
        return new Matcher<ClassTree>() {
            @Override
            public boolean matches(ClassTree tree, VisitorState state) {
                final CharSequence name = tree.getSimpleName().toString();
                return pattern.matcher(name).matches();
            }
        };
    }
}
