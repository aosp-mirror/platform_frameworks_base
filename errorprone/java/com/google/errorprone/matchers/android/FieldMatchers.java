/*
 * Copyright 2018 The Error Prone Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.errorprone.matchers.android;

import com.google.errorprone.VisitorState;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ImportTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import javax.annotation.Nullable;

// TODO(glorioso): this likely wants to be a fluent interface like MethodMatchers.
// Ex: [staticField()|instanceField()]
//         .[onClass(String)|onAnyClass|onClassMatching]
//         .[named(String)|withAnyName|withNameMatching]
/** Static utility methods for creating {@link Matcher}s for detecting references to fields. */
public final class FieldMatchers {
  private FieldMatchers() {}

  public static Matcher<ExpressionTree> anyFieldInClass(String className) {
    return new FieldReferenceMatcher() {
      @Override
      boolean classIsAppropriate(ClassSymbol classSymbol) {
        return classSymbol != null
                && classSymbol.getQualifiedName().contentEquals(className);
      }

      @Override
      boolean fieldSymbolIsAppropriate(Symbol symbol) {
        return true;
      }
    };
  }

  public static Matcher<ExpressionTree> staticField(String className, String fieldName) {
    return new FieldReferenceMatcher() {
      @Override
      boolean classIsAppropriate(ClassSymbol classSymbol) {
        return classSymbol != null
                && classSymbol.getQualifiedName().contentEquals(className);
      }

      @Override
      boolean fieldSymbolIsAppropriate(Symbol symbol) {
        return symbol != null
                && symbol.isStatic() && symbol.getSimpleName().contentEquals(fieldName);
      }
    };
  }

  public static Matcher<ExpressionTree> instanceField(String className, String fieldName) {
    return new FieldReferenceMatcher() {
      @Override
      boolean classIsAppropriate(ClassSymbol classSymbol) {
        return classSymbol != null
                && classSymbol.getQualifiedName().contentEquals(className);
      }

      @Override
      boolean fieldSymbolIsAppropriate(Symbol symbol) {
        return symbol != null
                && !symbol.isStatic() && symbol.getSimpleName().contentEquals(fieldName);
      }
    };
  }

  private abstract static class FieldReferenceMatcher implements Matcher<ExpressionTree> {
    @Override
    public boolean matches(ExpressionTree expressionTree, VisitorState state) {
      return isSymbolFieldInAppropriateClass(ASTHelpers.getSymbol(expressionTree))
          // Don't match if this is part of a static import tree, since they will get the finding
          // on any usage of the field in their source.
          && ASTHelpers.findEnclosingNode(state.getPath(), ImportTree.class) == null;
    }

    private boolean isSymbolFieldInAppropriateClass(@Nullable Symbol symbol) {
      if (symbol == null) {
        return false;
      }
      return symbol.getKind().isField()
          && fieldSymbolIsAppropriate(symbol)
          && classIsAppropriate(symbol.owner.enclClass());
    }

    abstract boolean fieldSymbolIsAppropriate(Symbol symbol);

    abstract boolean classIsAppropriate(ClassSymbol classSymbol);
  }
}
