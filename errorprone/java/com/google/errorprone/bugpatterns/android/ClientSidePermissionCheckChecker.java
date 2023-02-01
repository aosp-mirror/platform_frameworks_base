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
import static com.google.errorprone.matchers.Matchers.enclosingClass;
import static com.google.errorprone.matchers.Matchers.hasAnnotation;
import static com.google.errorprone.matchers.Matchers.instanceMethod;
import static com.google.errorprone.matchers.Matchers.methodInvocation;

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
 * Often a permission check in the app's process is an indicative of a security issue since the app
 * could work around it. Permission checks should be done on system_server.
 */
@AutoService(BugChecker.class)
@BugPattern(
        name = "AndroidFrameworkClientSidePermissionCheck",
        summary = "Verifies that permission checks aren't done in the app's process",
        linkType = NONE,
        severity = WARNING)
public final class ClientSidePermissionCheckChecker
        extends BugChecker implements MethodInvocationTreeMatcher {
    private static final Matcher<Tree> INSIDE_MANAGER =
            enclosingClass(hasAnnotation("android.annotation.SystemService"));
    private static final Matcher<ExpressionTree> PERMISSION_CHECK_METHOD =
            anyOf(
                    methodInvocation(
                            instanceMethod()
                                    .onDescendantOf("android.content.Context")
                                    .named("checkPermission")),
                    methodInvocation(
                            instanceMethod()
                                    .onDescendantOf("android.content.Context")
                                    .named("enforceCallingPermission")),
                    methodInvocation(
                            instanceMethod()
                                    .onDescendantOf("android.content.Context")
                                    .named("enforceCallingOrSelfPermission")),
                    methodInvocation(
                            instanceMethod()
                                    .onDescendantOf("android.content.pm.PackageManager")
                                    .named("checkPermission")));
    private static final String ERROR_MESSAGE =
            "Permission checks should be made in system_server, not in the app's process, since "
                    + "they could be easily bypassed";

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        if (INSIDE_MANAGER.matches(tree, state)
                && PERMISSION_CHECK_METHOD.matches(tree, state)) {
            return buildDescription(tree)
                    .setMessage(ERROR_MESSAGE)
                    .build();
        }
        return Description.NO_MATCH;
    }
}
