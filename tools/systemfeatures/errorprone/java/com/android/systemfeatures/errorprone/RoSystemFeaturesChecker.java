/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemfeatures.errorprone;

import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;

import com.android.systemfeatures.RoSystemFeaturesMetadata;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.tools.javac.code.Symbol;

@AutoService(BugChecker.class)
@BugPattern(
        name = "RoSystemFeaturesChecker",
        summary = "Use RoSystemFeature instead of PackageManager.hasSystemFeature",
        explanation =
                "Directly invoking `PackageManager.hasSystemFeature` is less efficient than using"
                    + " the `RoSystemFeatures` helper class. This check flags invocations like"
                    + " `context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FOO)`"
                    + " and suggests replacing them with"
                    + " `com.android.internal.pm.RoSystemFeatures.hasFeatureFoo(context)`.",
        severity = WARNING)
public class RoSystemFeaturesChecker extends BugChecker
        implements BugChecker.MethodInvocationTreeMatcher {

    private static final String PACKAGE_MANAGER_CLASS = "android.content.pm.PackageManager";
    private static final String CONTEXT_CLASS = "android.content.Context";
    private static final String RO_SYSTEM_FEATURE_SIMPLE_CLASS = "RoSystemFeatures";
    private static final String RO_SYSTEM_FEATURE_CLASS =
            "com.android.internal.pm." + RO_SYSTEM_FEATURE_SIMPLE_CLASS;
    private static final String GET_PACKAGE_MANAGER_METHOD = "getPackageManager";
    private static final String HAS_SYSTEM_FEATURE_METHOD = "hasSystemFeature";
    private static final String FEATURE_PREFIX = "FEATURE_";

    private static final Matcher<ExpressionTree> HAS_SYSTEM_FEATURE_MATCHER =
            Matchers.instanceMethod()
                    .onDescendantOf(PACKAGE_MANAGER_CLASS)
                    .named(HAS_SYSTEM_FEATURE_METHOD)
                    .withParameters(String.class.getName());

    private static final Matcher<ExpressionTree> GET_PACKAGE_MANAGER_MATCHER =
            Matchers.instanceMethod()
                    .onDescendantOf(CONTEXT_CLASS)
                    .named(GET_PACKAGE_MANAGER_METHOD);

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        if (!HAS_SYSTEM_FEATURE_MATCHER.matches(tree, state)) {
            return Description.NO_MATCH;
        }

        // Check if the PackageManager was obtained from a Context instance.
        ExpressionTree packageManager = ASTHelpers.getReceiver(tree);
        if (!GET_PACKAGE_MANAGER_MATCHER.matches(packageManager, state)) {
            return Description.NO_MATCH;
        }

        // Get the feature argument and check if it's a PackageManager.FEATURE_X constant.
        ExpressionTree feature = tree.getArguments().isEmpty() ? null : tree.getArguments().get(0);
        Symbol featureSymbol = ASTHelpers.getSymbol(feature);
        if (featureSymbol == null
                || !featureSymbol.isStatic()
                || !featureSymbol.getSimpleName().toString().startsWith(FEATURE_PREFIX)
                || ASTHelpers.enclosingClass(featureSymbol) == null
                || !ASTHelpers.enclosingClass(featureSymbol)
                        .getQualifiedName()
                        .contentEquals(PACKAGE_MANAGER_CLASS)) {
            return Description.NO_MATCH;
        }

        // Check if the feature argument is part of the RoSystemFeatures API surface.
        String featureName = featureSymbol.getSimpleName().toString();
        String methodName = RoSystemFeaturesMetadata.getMethodNameForFeatureName(featureName);
        if (methodName == null) {
            return Description.NO_MATCH;
        }

        // Generate the appropriate fix.
        String replacement =
                String.format(
                        "%s.%s(%s)",
                        RO_SYSTEM_FEATURE_SIMPLE_CLASS,
                        methodName,
                        state.getSourceForNode(ASTHelpers.getReceiver(packageManager)));
        // Note that ErrorProne doesn't offer a seamless way of removing the `PackageManager` import
        // if unused after fix application, so for now we only offer best effort import suggestions.
        SuggestedFix fix =
                SuggestedFix.builder()
                        .replace(tree, replacement)
                        .addImport(RO_SYSTEM_FEATURE_CLASS)
                        .removeStaticImport(PACKAGE_MANAGER_CLASS + "." + featureName)
                        .build();
        return describeMatch(tree, fix);
    }
}
