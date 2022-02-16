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
import static com.google.errorprone.matchers.Matchers.enclosingClass;
import static com.google.errorprone.matchers.Matchers.enclosingMethod;
import static com.google.errorprone.matchers.Matchers.instanceMethod;
import static com.google.errorprone.matchers.Matchers.isSubtypeOf;
import static com.google.errorprone.matchers.Matchers.methodInvocation;
import static com.google.errorprone.matchers.Matchers.methodIsNamed;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.MethodInvocationTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;

/**
 * Parcelable data can be transported in many ways (some of which can be very
 * inefficient) so this checker guides developers towards using high-performance
 * best-practices.
 */
@AutoService(BugChecker.class)
@BugPattern(
    name = "AndroidFrameworkEfficientParcelable",
    summary = "Verifies Parcelable performance best-practices",
    severity = WARNING)
public final class EfficientParcelableChecker extends BugChecker
        implements MethodInvocationTreeMatcher {
    private static final Matcher<Tree> INSIDE_WRITE_TO_PARCEL = allOf(
            enclosingClass(isSubtypeOf("android.os.Parcelable")),
            enclosingMethod(methodIsNamed("writeToParcel")));

    private static final Matcher<ExpressionTree> WRITE_STRING = methodInvocation(
            instanceMethod().onExactClass("android.os.Parcel").named("writeString"));
    private static final Matcher<ExpressionTree> WRITE_STRING_ARRAY = methodInvocation(
            instanceMethod().onExactClass("android.os.Parcel").named("writeStringArray"));

    private static final Matcher<ExpressionTree> WRITE_VALUE = methodInvocation(
            instanceMethod().onExactClass("android.os.Parcel").named("writeValue"));
    private static final Matcher<ExpressionTree> WRITE_PARCELABLE = methodInvocation(
            instanceMethod().onExactClass("android.os.Parcel").named("writeParcelable"));

    private static final Matcher<ExpressionTree> WRITE_LIST = methodInvocation(
            instanceMethod().onExactClass("android.os.Parcel").named("writeList"));
    private static final Matcher<ExpressionTree> WRITE_PARCELABLE_LIST = methodInvocation(
            instanceMethod().onExactClass("android.os.Parcel").named("writeParcelableList"));
    private static final Matcher<ExpressionTree> WRITE_PARCELABLE_ARRAY = methodInvocation(
            instanceMethod().onExactClass("android.os.Parcel").named("writeParcelableArray"));

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        if (INSIDE_WRITE_TO_PARCEL.matches(tree, state)) {
            if (WRITE_STRING.matches(tree, state)) {
                return buildDescription(tree)
                        .setMessage("Recommended to use 'writeString8()' to improve "
                                + "efficiency; sending as UTF-8 can double throughput")
                        .build();
            }
            if (WRITE_STRING_ARRAY.matches(tree, state)) {
                return buildDescription(tree)
                        .setMessage("Recommended to use 'writeString8Array()' to improve "
                                + "efficiency; sending as UTF-8 can double throughput")
                        .build();
            }

            if (WRITE_VALUE.matches(tree, state)) {
                return buildDescription(tree)
                        .setMessage("Recommended to use strongly-typed methods to improve "
                                + "efficiency; saves 4 bytes for type and overhead of "
                                + "Parcelable class name")
                        .build();
            }
            if (WRITE_PARCELABLE.matches(tree, state)) {
                return buildDescription(tree)
                        .setMessage("Recommended to use 'item.writeToParcel()' to improve "
                                + "efficiency; saves overhead of Parcelable class name")
                        .build();
            }

            if (WRITE_LIST.matches(tree, state)) {
                return buildDescription(tree)
                        .setMessage("Recommended to use 'writeTypedList()' to improve "
                                + "efficiency; saves overhead of repeated Parcelable class name")
                        .build();
            }
            if (WRITE_PARCELABLE_LIST.matches(tree, state)) {
                return buildDescription(tree)
                        .setMessage("Recommended to use 'writeTypedList()' to improve "
                                + "efficiency; saves overhead of repeated Parcelable class name")
                        .build();
            }
            if (WRITE_PARCELABLE_ARRAY.matches(tree, state)) {
                return buildDescription(tree)
                        .setMessage("Recommended to use 'writeTypedArray()' to improve "
                                + "efficiency; saves overhead of repeated Parcelable class name")
                        .build();
            }
        }
        return Description.NO_MATCH;
    }
}
