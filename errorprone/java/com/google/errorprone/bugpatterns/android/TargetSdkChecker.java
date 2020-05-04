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
import static com.google.errorprone.matchers.Matchers.anything;
import static com.google.errorprone.matchers.Matchers.kindIs;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.BinaryTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.FieldMatchers;
import com.google.errorprone.matchers.Matcher;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree.Kind;

@AutoService(BugChecker.class)
@BugPattern(
    name = "AndroidFrameworkTargetSdk",
    summary = "Verifies that all target SDK comparisons are sane",
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

    private static Matcher<BinaryTree> binaryTreeExact(Matcher<ExpressionTree> left,
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
