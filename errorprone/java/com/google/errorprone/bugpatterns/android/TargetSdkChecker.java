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
import static com.google.errorprone.matchers.Matchers.allOf;
import static com.google.errorprone.matchers.Matchers.anyOf;
import static com.google.errorprone.matchers.Matchers.anything;
import static com.google.errorprone.matchers.Matchers.kindIs;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.BinaryTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.android.FieldMatchers;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree.Kind;

/**
 * Over the years we've had several obscure bugs related to how SDK level
 * comparisons are performed, specifically during the window of time where we've
 * started distributing the "frankenbuild" to developers.
 * <p>
 * Consider the case where a framework developer shipping release "R" wants to
 * only grant a specific behavior to modern apps; they could write this in two
 * different ways:
 * <ol>
 * <li>if (targetSdkVersion > Build.VERSION_CODES.Q) {
 * <li>if (targetSdkVersion >= Build.VERSION_CODES.R) {
 * </ol>
 * The safer of these two options is (2), which will ensure that developers only
 * get the behavior when <em>both</em> the app and the platform agree on the
 * specific SDK level having shipped.
 * <p>
 * Consider the breakage that would happen with option (1) if we started
 * shipping APKs that are based on the final R SDK, but are then installed on
 * earlier preview releases which still consider R to be CUR_DEVELOPMENT; they'd
 * risk crashing due to behaviors that were never part of the official R SDK.
 */
@AutoService(BugChecker.class)
@BugPattern(
    name = "AndroidFrameworkTargetSdk",
    summary = "Verifies that all target SDK comparisons are sane",
    linkType = NONE,
    severity = WARNING)
public final class TargetSdkChecker extends BugChecker implements BinaryTreeMatcher {
    private static final Matcher<ExpressionTree> VERSION_CODE = FieldMatchers
            .anyFieldInClass("android.os.Build.VERSION_CODES");

    private static final Matcher<BinaryTree> INVALID_OLD_BEHAVIOR = anyOf(
            allOf(kindIs(Kind.LESS_THAN_EQUAL), binaryTreeExact(anything(), VERSION_CODE)),
            allOf(kindIs(Kind.GREATER_THAN_EQUAL), binaryTreeExact(VERSION_CODE, anything())));

    private static final Matcher<BinaryTree> INVALID_NEW_BEHAVIOR = anyOf(
            allOf(kindIs(Kind.GREATER_THAN), binaryTreeExact(anything(), VERSION_CODE)),
            allOf(kindIs(Kind.LESS_THAN), binaryTreeExact(VERSION_CODE, anything())));

    @Override
    public Description matchBinary(BinaryTree tree, VisitorState state) {
        if (INVALID_OLD_BEHAVIOR.matches(tree, state)) {
            return buildDescription(tree)
                    .setMessage("Legacy behaviors must be written in style "
                            + "'targetSdk < Build.VERSION_CODES.Z'")
                    .build();
        }
        if (INVALID_NEW_BEHAVIOR.matches(tree, state)) {
            return buildDescription(tree)
                    .setMessage("Modern behaviors must be written in style "
                            + "'targetSdk >= Build.VERSION_CODES.Z'")
                    .build();
        }
        return Description.NO_MATCH;
    }

    static Matcher<BinaryTree> binaryTreeExact(Matcher<ExpressionTree> left,
            Matcher<ExpressionTree> right) {
        return new Matcher<BinaryTree>() {
            @Override
            public boolean matches(BinaryTree tree, VisitorState state) {
                return left.matches(tree.getLeftOperand(), state)
                        && right.matches(tree.getRightOperand(), state);
            }
        };
    }
}
