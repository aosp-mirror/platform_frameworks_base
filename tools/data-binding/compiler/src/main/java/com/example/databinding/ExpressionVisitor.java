/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.example.databinding;

import com.android.databinding.BindingExpressionBaseVisitor;
import com.android.databinding.BindingExpressionParser;
import com.android.databinding.parser.Expr;
import com.android.databinding.parser.ExprModel;
import com.android.databinding.parser.VariableRef;
import com.android.databinding.util.Log;

import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.HashSet;

public class ExpressionVisitor extends BindingExpressionBaseVisitor<Expr> {
    private final ExprModel mModel;
    private final HashSet<VariableRef> mAccessedVariables = new HashSet<>();

    public ExpressionVisitor(ExprModel model) {
        mModel = model;
    }

    public HashSet<VariableRef> getAccessedVariables() {
        return mAccessedVariables;
    }

    @Override
    public Expr visitStringLiteral(@NotNull BindingExpressionParser.StringLiteralContext ctx) {
        final String javaString;
        if (ctx.SingleQuoteString() != null) {
            String str = ctx.SingleQuoteString().getText();
            String contents = str.substring(1, str.length() - 1);
            contents = contents.replace("\"", "\\\"").replace("\\`", "`");
            javaString = '"' + contents + '"';
        } else {
            javaString = ctx.DoubleQuoteString().getText();
        }

        return new Expr() {
            @org.jetbrains.annotations.NotNull
            @Override
            public String toJava() {
                return javaString;
            }

            @org.jetbrains.annotations.NotNull
            @Override
            public String toReadableString() {
                return javaString;
            }
        };
    }

    @Override
    public Expr visitBindingSyntax(@NotNull BindingExpressionParser.BindingSyntaxContext ctx) {
        try {
            final Expr expression = visit(ctx.expression());
            final Expr defaults = visit(ctx.defaults());
            return new Expr() {
                @org.jetbrains.annotations.NotNull
                @Override
                public String toJava() {
                    return expression.toJava();
                }

                @org.jetbrains.annotations.NotNull
                @Override
                public String toReadableString() {
                    return expression.toReadableString();
                }
            };
        } catch (Exception e) {
            System.out.println("Error while parsing! " + ctx.getText());
            e.printStackTrace();
            throw e;
        }
    }

    @Override
    public Expr visitDotOp(@NotNull BindingExpressionParser.DotOpContext ctx) {
        final Expr expression = visit(ctx.expression());
        final String field = ctx.Identifier().getText();
        final String expressionString = expression.toReadableString();
        final VariableRef expressionVariableRef = mModel.getVariable(expressionString);
        final String name = expressionString + "." + field;
        final VariableRef variableRef = mModel.getOrCreateVariable(name, expressionVariableRef);
        mAccessedVariables.add(variableRef);

        return new Expr() {
            @org.jetbrains.annotations.NotNull
            @Override
            public String toReadableString() {
                return variableRef.getFullName();
            }

            @org.jetbrains.annotations.NotNull
            @Override
            public String toJava() {
                return variableRef.getVariable().getLocalName();
            }
        };
    }

    @Override
    public Expr visitQuestionQuestionOp(@NotNull BindingExpressionParser.QuestionQuestionOpContext ctx) {
        final Expr nullCheckExpression = visit(ctx.expression(0));
        final Expr isNullExpression = visit(ctx.expression(1));

        return new Expr() {
            @org.jetbrains.annotations.NotNull
            @Override
            public String toJava() {
                return "((" + nullCheckExpression.toJava() + " != null) ? (" +
                        nullCheckExpression.toJava() + ") : (" + isNullExpression.toJava() + "))";
            }

            @org.jetbrains.annotations.NotNull
            @Override
            public String toReadableString() {
                return nullCheckExpression.toReadableString() + " ?? " + isNullExpression.toReadableString();
            }
        };
    }

    @Override
    public Expr visitIdentifier(@NotNull BindingExpressionParser.IdentifierContext ctx) {
        final String identifier = ctx.Identifier().getText();
        final VariableRef variableRef = mModel.getOrCreateVariable(identifier, null);
        mAccessedVariables.add(variableRef);

        return new Expr() {
            @org.jetbrains.annotations.NotNull
            @Override
            public String toJava() {
                return identifier;
            }

            @org.jetbrains.annotations.NotNull
            @Override
            public String toReadableString() {
                return identifier;
            }
        };
    }

    @Override
    public Expr visit(@NotNull ParseTree tree) {
        if (tree == null) {
            return null;
        }
        return super.visit(tree);
    }

    @Override
    public Expr visitTerminal(@NotNull TerminalNode node) {
        final String text = " " + node.getText() + " ";
        return new Expr() {
            @org.jetbrains.annotations.NotNull
            @Override
            public String toReadableString() {
                return text;
            }

            @org.jetbrains.annotations.NotNull
            @Override
            public String toJava() {
                return text;
            }
        };
    }

    @Override
    protected Expr aggregateResult(final Expr aggregate, final Expr nextResult) {
        if (aggregate == null) {
            return nextResult;
        } else {
            return new Expr() {
                @org.jetbrains.annotations.NotNull
                @Override
                public String toReadableString() {
                    return aggregate.toReadableString() + ' ' + nextResult.toReadableString();
                }

                @org.jetbrains.annotations.NotNull
                @Override
                public String toJava() {
                    return aggregate.toJava() + ' ' + nextResult.toJava();
                }
            };
        }
    }

    @Override
    public Expr visitDefaults(@NotNull BindingExpressionParser.DefaultsContext ctx) {
        return visit(ctx.constantValue());
    }

    @Override
    public Expr visitMethodInvocation(
            @NotNull BindingExpressionParser.MethodInvocationContext ctx) {
        final Expr expression = visit(ctx.expression());
        final String methodName = ctx.Identifier().getText();
        final ArrayList<Expr> parameters = new ArrayList<>();
        if (ctx.expressionList() != null) {
            for (BindingExpressionParser.ExpressionContext parameter : ctx.expressionList()
                    .expression()) {
                parameters.add(visit(parameter));
            }
        }
        return new Expr() {
            @org.jetbrains.annotations.NotNull
            @Override
            public String toJava() {
                StringBuilder sb = new StringBuilder();
                sb.append(expression.toJava())
                        .append('.')
                        .append(methodName)
                        .append('(');
                for (int i = 0; i < parameters.size(); i++) {
                    if (i != 0) {
                        sb.append(", ");
                    }
                    sb.append(parameters.get(i).toJava());
                }
                sb.append(')');
                return sb.toString();
            }

            @org.jetbrains.annotations.NotNull
            @Override
            public String toReadableString() {
                StringBuilder sb = new StringBuilder();
                sb.append(expression.toReadableString())
                        .append('(');
                for (int i = 0; i < parameters.size(); i++) {
                    if (i != 0) {
                        sb.append(", ");
                    }
                    sb.append(parameters.get(i).toReadableString());
                }
                sb.append(')');
                return sb.toString();
            }
        };
    }
}
