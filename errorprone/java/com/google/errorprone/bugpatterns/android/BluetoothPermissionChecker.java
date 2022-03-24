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

import static com.google.errorprone.BugPattern.LinkType.NONE;
import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;
import static com.google.errorprone.bugpatterns.android.RequiresPermissionChecker.simpleNameMatches;
import static com.google.errorprone.matchers.Matchers.allOf;
import static com.google.errorprone.matchers.Matchers.anyOf;
import static com.google.errorprone.matchers.Matchers.enclosingClass;
import static com.google.errorprone.matchers.Matchers.isStatic;
import static com.google.errorprone.matchers.Matchers.isSubtypeOf;
import static com.google.errorprone.matchers.Matchers.methodHasVisibility;
import static com.google.errorprone.matchers.Matchers.methodIsConstructor;
import static com.google.errorprone.matchers.Matchers.methodIsNamed;
import static com.google.errorprone.matchers.Matchers.not;
import static com.google.errorprone.matchers.Matchers.packageStartsWith;

import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.MethodTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.MethodVisibility.Visibility;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;

import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Pattern;

/**
 * Verifies that all Bluetooth APIs have consistent permissions.
 */
@AutoService(BugChecker.class)
@BugPattern(
    name = "AndroidFrameworkBluetoothPermission",
    summary = "Verifies that all Bluetooth APIs have consistent permissions",
    linkType = NONE,
    severity = WARNING)
public final class BluetoothPermissionChecker extends BugChecker implements MethodTreeMatcher {
    private static final Matcher<MethodTree> BLUETOOTH_API = allOf(
            packageStartsWith("android.bluetooth"),
            methodHasVisibility(Visibility.PUBLIC),
            not(isStatic()),
            not(methodIsConstructor()),
            not(enclosingClass(isInsideParcelable())),
            not(enclosingClass(simpleNameMatches(Pattern.compile(".+Callback$")))),
            not(enclosingClass(isSubtypeOf("android.bluetooth.BluetoothProfileConnector"))),
            not(enclosingClass(isSubtypeOf("android.app.PropertyInvalidatedCache"))));

    private static final Matcher<ClassTree> PARCELABLE_CLASS =
            isSubtypeOf("android.os.Parcelable");
    private static final Matcher<MethodTree> BINDER_METHOD = enclosingClass(
            isSubtypeOf("android.os.IInterface"));

    private static final Matcher<MethodTree> BINDER_INTERNALS = allOf(
            enclosingClass(isSubtypeOf("android.os.IInterface")),
            anyOf(
                    methodIsNamed("onTransact"),
                    methodIsNamed("dump"),
                    enclosingClass(simpleNameMatches(Pattern.compile("^(Stub|Default|Proxy)$")))));

    private static final Matcher<MethodTree> GENERIC_INTERNALS = anyOf(
            methodIsNamed("close"),
            methodIsNamed("finalize"),
            methodIsNamed("equals"),
            methodIsNamed("hashCode"),
            methodIsNamed("toString"));

    private static final String PERMISSION_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE";
    private static final String PERMISSION_CONNECT = "android.permission.BLUETOOTH_CONNECT";
    private static final String PERMISSION_SCAN = "android.permission.BLUETOOTH_SCAN";

    private static final String ANNOTATION_ADVERTISE =
            "android.bluetooth.annotations.RequiresBluetoothAdvertisePermission";
    private static final String ANNOTATION_CONNECT =
            "android.bluetooth.annotations.RequiresBluetoothConnectPermission";
    private static final String ANNOTATION_SCAN =
            "android.bluetooth.annotations.RequiresBluetoothScanPermission";

    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
        // Ignore methods outside Bluetooth area
        if (!BLUETOOTH_API.matches(tree, state)) return Description.NO_MATCH;

        // Ignore certain types of generated or internal code
        if (BINDER_INTERNALS.matches(tree, state)) return Description.NO_MATCH;
        if (GENERIC_INTERNALS.matches(tree, state)) return Description.NO_MATCH;

        // Skip abstract methods, except for binder interfaces
        if (tree.getBody() == null && !BINDER_METHOD.matches(tree, state)) {
            return Description.NO_MATCH;
        }

        // Ignore callbacks which don't need permission enforcement
        final MethodSymbol symbol = ASTHelpers.getSymbol(tree);
        if (isCallbackOrWrapper(symbol)) return Description.NO_MATCH;

        // Ignore when suppressed
        if (isSuppressed(symbol)) return Description.NO_MATCH;

        final RequiresPermission requiresPerm = ASTHelpers.getAnnotation(tree,
                RequiresPermission.class);
        final RequiresNoPermission requiresNoPerm = ASTHelpers.getAnnotation(tree,
                RequiresNoPermission.class);

        final boolean requiresValid = requiresPerm != null
                && (requiresPerm.value() != null || requiresPerm.allOf() != null);
        final boolean requiresNoValid = requiresNoPerm != null;
        if (!requiresValid && !requiresNoValid) {
            return buildDescription(tree)
                    .setMessage("Method " + symbol.name.toString()
                            + "() must be protected by at least one permission")
                    .build();
        }

        // No additional checks needed for Binder generated code
        if (BINDER_METHOD.matches(tree, state)) return Description.NO_MATCH;

        if (ASTHelpers.hasAnnotation(tree, ANNOTATION_ADVERTISE,
                state) != isPermissionReferenced(requiresPerm, PERMISSION_ADVERTISE)) {
            return buildDescription(tree)
                    .setMessage("Method " + symbol.name.toString()
                            + "() has inconsistent annotations for " + PERMISSION_ADVERTISE)
                    .build();
        }
        if (ASTHelpers.hasAnnotation(tree, ANNOTATION_CONNECT,
                state) != isPermissionReferenced(requiresPerm, PERMISSION_CONNECT)) {
            return buildDescription(tree)
                    .setMessage("Method " + symbol.name.toString()
                            + "() has inconsistent annotations for " + PERMISSION_CONNECT)
                    .build();
        }
        if (ASTHelpers.hasAnnotation(tree, ANNOTATION_SCAN,
                state) != isPermissionReferenced(requiresPerm, PERMISSION_SCAN)) {
            return buildDescription(tree)
                    .setMessage("Method " + symbol.name.toString()
                            + "() has inconsistent annotations for " + PERMISSION_SCAN)
                    .build();
        }

        return Description.NO_MATCH;
    }

    private static boolean isPermissionReferenced(RequiresPermission anno, String perm) {
        if (anno == null) return false;
        if (perm.equals(anno.value())) return true;
        return anno.allOf() != null && Arrays.asList(anno.allOf()).contains(perm);
    }

    private static boolean isCallbackOrWrapper(Symbol symbol) {
        if (symbol == null) return false;
        final String name = symbol.name.toString();
        return isCallbackOrWrapper(ASTHelpers.enclosingClass(symbol))
                || name.endsWith("Callback")
                || name.endsWith("Wrapper");
    }

    public boolean isSuppressed(Symbol symbol) {
        if (symbol == null) return false;
        return isSuppressed(ASTHelpers.enclosingClass(symbol))
                || isSuppressed(ASTHelpers.getAnnotation(symbol, SuppressWarnings.class))
                || isSuppressed(ASTHelpers.getAnnotation(symbol, SuppressLint.class));
    }

    private boolean isSuppressed(SuppressWarnings anno) {
        return (anno != null) && !Collections.disjoint(Arrays.asList(anno.value()), allNames());
    }

    private boolean isSuppressed(SuppressLint anno) {
        return (anno != null) && !Collections.disjoint(Arrays.asList(anno.value()), allNames());
    }

    private static Matcher<ClassTree> isInsideParcelable() {
        return new Matcher<ClassTree>() {
            @Override
            public boolean matches(ClassTree tree, VisitorState state) {
                final TreePath path = state.getPath();
                for (Tree node : path) {
                    if (node instanceof ClassTree
                            && PARCELABLE_CLASS.matches((ClassTree) node, state)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }
}
