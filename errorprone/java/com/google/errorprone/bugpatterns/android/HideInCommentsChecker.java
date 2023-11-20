/*
 * Copyright (C) 2022 The Android Open Source Project
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
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static com.google.errorprone.util.ASTHelpers.getStartPosition;
import static com.google.errorprone.util.ASTHelpers.getSymbol;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.google.errorprone.util.ErrorProneToken;
import com.google.errorprone.util.ErrorProneTokens;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.parser.Tokens;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.lang.model.element.ElementKind;

/**
 * Bug checker to warn about {@code @hide} directives in comments.
 *
 * {@code @hide} tags are only meaningful inside of Javadoc comments. Errorprone has checks for
 * standard Javadoc tags but doesn't know anything about {@code @hide} since it's an Android
 * specific tag.
 */
@AutoService(BugChecker.class)
@BugPattern(
        name = "AndroidHideInComments",
        summary = "Warns when there are @hide declarations in comments rather than javadoc",
        linkType = NONE,
        severity = WARNING)
public class HideInCommentsChecker extends BugChecker implements
        BugChecker.CompilationUnitTreeMatcher {

    @Override
    public Description matchCompilationUnit(CompilationUnitTree tree, VisitorState state) {
        final Map<Integer, Tree> javadocableTrees = findJavadocableTrees(tree, state);
        final String sourceCode = state.getSourceCode().toString();
        for (ErrorProneToken token : ErrorProneTokens.getTokens(sourceCode, state.context)) {
            for (Tokens.Comment comment : token.comments()) {
                if (!javadocableTrees.containsKey(token.pos())) {
                    continue;
                }
                generateFix(comment).ifPresent(fix -> {
                    final Tree javadocableTree = javadocableTrees.get(token.pos());
                    state.reportMatch(describeMatch(javadocableTree, fix));
                });
            }
        }
        // We might have multiple matches, so report them via VisitorState rather than the return
        // value from the match function.
        return NO_MATCH;
    }

    private static Optional<SuggestedFix> generateFix(Tokens.Comment comment) {
        final String text = comment.getText();
        if (text.startsWith("/**")) {
            return Optional.empty();
        }

        if (!text.contains("@hide")) {
            return Optional.empty();
        }

        if (text.startsWith("/*")) {
            final int pos = comment.getSourcePos(1);
            return Optional.of(SuggestedFix.replace(pos, pos, "*"));
        } else if (text.startsWith("//")) {
            final int endPos = comment.getSourcePos(text.length() - 1);
            final char endChar = text.charAt(text.length() - 1);
            String javadocClose = " */";
            if (endChar != ' ') {
                javadocClose = endChar + javadocClose;
            }
            final SuggestedFix fix = SuggestedFix.builder()
                    .replace(comment.getSourcePos(1), comment.getSourcePos(2), "**")
                    .replace(endPos, endPos + 1, javadocClose)
                    .build();
            return Optional.of(fix);
        }

        return Optional.empty();
    }


    private Map<Integer, Tree> findJavadocableTrees(CompilationUnitTree tree, VisitorState state) {
        Map<Integer, Tree> javadoccableTrees = new HashMap<>();
        new SuppressibleTreePathScanner<Void, Void>(state) {
            @Override
            public Void visitClass(ClassTree classTree, Void unused) {
                javadoccableTrees.put(getStartPosition(classTree), classTree);
                return super.visitClass(classTree, null);
            }

            @Override
            public Void visitMethod(MethodTree methodTree, Void unused) {
                // Generated constructors never have comments
                if (!ASTHelpers.isGeneratedConstructor(methodTree)) {
                    javadoccableTrees.put(getStartPosition(methodTree), methodTree);
                }
                return super.visitMethod(methodTree, null);
            }

            @Override
            public Void visitVariable(VariableTree variableTree, Void unused) {
                ElementKind kind = getSymbol(variableTree).getKind();
                if (kind == ElementKind.FIELD) {
                    javadoccableTrees.put(getStartPosition(variableTree), variableTree);
                }
                if (kind == ElementKind.ENUM_CONSTANT) {
                    javadoccableTrees.put(getStartPosition(variableTree), variableTree);
                    if (variableTree.getInitializer() instanceof NewClassTree) {
                        // Skip the generated class definition
                        ClassTree classBody =
                                ((NewClassTree) variableTree.getInitializer()).getClassBody();
                        if (classBody != null) {
                            scan(classBody.getMembers(), null);
                        }
                        return null;
                    }
                }
                return super.visitVariable(variableTree, null);
            }

        }.scan(tree, null);
        return javadoccableTrees;
    }

}
