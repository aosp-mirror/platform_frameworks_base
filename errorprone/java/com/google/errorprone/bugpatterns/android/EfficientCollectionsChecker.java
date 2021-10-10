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
import static com.google.errorprone.matchers.Matchers.isSubtypeOf;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.NewClassTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Type;

import java.util.Collections;
import java.util.List;

/**
 * Android offers several efficient alternatives to some upstream
 * {@link Collections} containers, such as {@code SparseIntArray} instead of
 * {@code Map<Integer, Integer>}.
 */
@AutoService(BugChecker.class)
@BugPattern(
    name = "AndroidFrameworkEfficientCollections",
    summary = "Verifies efficient collections best-practices",
    severity = WARNING)
public final class EfficientCollectionsChecker extends BugChecker implements NewClassTreeMatcher {
    private static final Matcher<Tree> IS_LIST = isSubtypeOf("java.util.List");
    private static final Matcher<Tree> IS_MAP = isSubtypeOf("java.util.Map");

    private static final String INTEGER = "java.lang.Integer";
    private static final String LONG = "java.lang.Long";
    private static final String BOOLEAN = "java.lang.Boolean";

    @Override
    public Description matchNewClass(NewClassTree tree, VisitorState state) {
        final List<Type> types = ASTHelpers.getType(tree).getTypeArguments();
        if (IS_LIST.matches(tree, state) && types != null && types.size() == 1) {
            final Type first = types.get(0);
            if (ASTHelpers.isSameType(first, state.getTypeFromString(INTEGER), state)) {
                return buildDescription(tree)
                        .setMessage("Consider replacing with IntArray for efficiency")
                        .build();
            } else if (ASTHelpers.isSameType(first, state.getTypeFromString(LONG), state))  {
                return buildDescription(tree)
                        .setMessage("Consider replacing with LongArray for efficiency")
                        .build();
            }
        } else if (IS_MAP.matches(tree, state) && types != null && types.size() == 2) {
            final Type first = types.get(0);
            final Type second = types.get(1);
            if (ASTHelpers.isSameType(first, state.getTypeFromString(INTEGER), state)) {
                if (ASTHelpers.isSameType(second, state.getTypeFromString(INTEGER), state)) {
                    return buildDescription(tree)
                            .setMessage("Consider replacing with SparseIntArray for efficiency")
                            .build();
                } else if (ASTHelpers.isSameType(second, state.getTypeFromString(LONG), state)) {
                    return buildDescription(tree)
                            .setMessage("Consider replacing with SparseLongArray for efficiency")
                            .build();
                } else if (ASTHelpers.isSameType(second, state.getTypeFromString(BOOLEAN), state)) {
                    return buildDescription(tree)
                            .setMessage("Consider replacing with SparseBooleanArray for efficiency")
                            .build();
                } else {
                    return buildDescription(tree)
                            .setMessage("Consider replacing with SparseArray for efficiency")
                            .build();
                }
            } else if (ASTHelpers.isSameType(first, state.getTypeFromString(LONG), state)) {
                return buildDescription(tree)
                        .setMessage("Consider replacing with LongSparseArray for efficiency")
                        .build();
            }
        }
        return Description.NO_MATCH;
    }
}
