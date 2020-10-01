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
import static com.google.errorprone.matchers.FieldMatchers.anyFieldInClass;
import static com.google.errorprone.matchers.FieldMatchers.staticField;
import static com.google.errorprone.matchers.Matchers.allOf;
import static com.google.errorprone.matchers.Matchers.anyOf;
import static com.google.errorprone.matchers.Matchers.anything;
import static com.google.errorprone.matchers.Matchers.binaryTree;
import static com.google.errorprone.matchers.Matchers.not;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.BinaryTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ExpressionTree;

/**
 * Each SDK level often has dozens of different behavior changes, which can be
 * difficult for large app developers to adjust to during preview or beta
 * releases. For this reason, {@code android.app.compat.CompatChanges} was
 * introduced as a new best-practice for adding behavior changes.
 * <p>
 * During a preview or beta release, developers can temporarily opt-out of each
 * individual change to aid debugging. This opt-out is only available during
 * preview of beta releases, and cannot be adjusted on finalized builds.
 */
@AutoService(BugChecker.class)
@BugPattern(
    name = "AndroidFrameworkCompatChange",
    summary = "Verifies that behavior changes use the modern compatibility framework",
    severity = WARNING)
public final class CompatChangeChecker extends BugChecker implements BinaryTreeMatcher {
    private static final Matcher<ExpressionTree> VERSION_CODE =
            anyFieldInClass("android.os.Build.VERSION_CODES");

    // Ship has already sailed on these SDK levels; not worth fixing
    private static final Matcher<ExpressionTree> LEGACY_VERSION_CODE = anyOf(
            staticField("android.os.Build.VERSION_CODES", "BASE"),
            staticField("android.os.Build.VERSION_CODES", "BASE_1_1"),
            staticField("android.os.Build.VERSION_CODES", "CUPCAKE"),
            staticField("android.os.Build.VERSION_CODES", "DONUT"),
            staticField("android.os.Build.VERSION_CODES", "ECLAIR"),
            staticField("android.os.Build.VERSION_CODES", "ECLAIR_0_1"),
            staticField("android.os.Build.VERSION_CODES", "ECLAIR_MR1"),
            staticField("android.os.Build.VERSION_CODES", "FROYO"),
            staticField("android.os.Build.VERSION_CODES", "GINGERBREAD"),
            staticField("android.os.Build.VERSION_CODES", "GINGERBREAD_MR1"),
            staticField("android.os.Build.VERSION_CODES", "HONEYCOMB"),
            staticField("android.os.Build.VERSION_CODES", "HONEYCOMB_MR1"),
            staticField("android.os.Build.VERSION_CODES", "HONEYCOMB_MR2"),
            staticField("android.os.Build.VERSION_CODES", "ICE_CREAM_SANDWICH"),
            staticField("android.os.Build.VERSION_CODES", "ICE_CREAM_SANDWICH_MR1"),
            staticField("android.os.Build.VERSION_CODES", "JELLY_BEAN"),
            staticField("android.os.Build.VERSION_CODES", "JELLY_BEAN_MR1"),
            staticField("android.os.Build.VERSION_CODES", "JELLY_BEAN_MR2"),
            staticField("android.os.Build.VERSION_CODES", "KITKAT"),
            staticField("android.os.Build.VERSION_CODES", "KITKAT_WATCH"),
            staticField("android.os.Build.VERSION_CODES", "L"),
            staticField("android.os.Build.VERSION_CODES", "LOLLIPOP"),
            staticField("android.os.Build.VERSION_CODES", "LOLLIPOP_MR1"),
            staticField("android.os.Build.VERSION_CODES", "M"),
            staticField("android.os.Build.VERSION_CODES", "N"),
            staticField("android.os.Build.VERSION_CODES", "N_MR1"),
            staticField("android.os.Build.VERSION_CODES", "O"),
            staticField("android.os.Build.VERSION_CODES", "O_MR1"),
            staticField("android.os.Build.VERSION_CODES", "P"),
            staticField("android.os.Build.VERSION_CODES", "Q"));

    private static final Matcher<ExpressionTree> MODERN_VERSION_CODE =
            allOf(VERSION_CODE, not(LEGACY_VERSION_CODE));

    private static final Matcher<BinaryTree> INVALID = anyOf(
            binaryTree(MODERN_VERSION_CODE, anything()),
            binaryTree(anything(), MODERN_VERSION_CODE));

    @Override
    public Description matchBinary(BinaryTree tree, VisitorState state) {
        if (INVALID.matches(tree, state)) {
            return buildDescription(tree)
                    .setMessage("Behavior changes should use CompatChanges.isChangeEnabled() "
                            + "instead of direct SDK checks to ease developer transitions; "
                            + "see go/compat-framework for more details")
                    .build();

        }
        return Description.NO_MATCH;
    }
}
