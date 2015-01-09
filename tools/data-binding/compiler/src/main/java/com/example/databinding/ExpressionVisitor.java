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
import com.android.databinding.parser.AndOrOpExpr;
import com.android.databinding.parser.BinaryOpExpr;
import com.android.databinding.parser.ComparisonOpExpr;
import com.android.databinding.parser.Expr;
import com.android.databinding.parser.ExprModel;
import com.android.databinding.parser.FieldExpr;
import com.android.databinding.parser.MethodCallExpr;
import com.android.databinding.parser.OpExpr;
import com.android.databinding.parser.SymbolExpr;
import com.android.databinding.parser.TernaryExpr;
import com.android.databinding.parser.VariableRef;
import com.android.databinding.util.ClassAnalyzer;
import static com.android.databinding.BindingExpressionParser.*;

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

        return new SymbolExpr(javaString, String.class);
    }

    @Override
    public Expr visitBindingSyntax(@NotNull BindingExpressionParser.BindingSyntaxContext ctx) {
        try {
            final Expr expression = visit(ctx.expression());
            final Expr defaults = visit(ctx.defaults());
            return new Expr() {
                @org.jetbrains.annotations.NotNull
                @Override
                public Class<? extends Object> resolveValueType(
                        @org.jetbrains.annotations.NotNull ClassAnalyzer classAnalyzer) {
                    return expression.getResolvedClass();
                }

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
            public Class<? extends Object> resolveValueType(
                    @org.jetbrains.annotations.NotNull ClassAnalyzer classAnalyzer) {
                return variableRef.getVariable().getResolvedClass();
            }

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
            public Class<? extends Object> resolveValueType(
                    @org.jetbrains.annotations.NotNull ClassAnalyzer classAnalyzer) {
                return classAnalyzer.commonParentOf(nullCheckExpression.getResolvedClass(),
                        isNullExpression.getResolvedClass());
            }

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

        return new FieldExpr(variableRef, new ArrayList<VariableRef>(0));
    }

    @Override
    public Expr visit(@NotNull ParseTree tree) {
        if (tree == null) {
            return null;
        }
        return super.visit(tree);
    }

    @Override
    public Expr visitTernaryOp(@NotNull TernaryOpContext ctx) {
        return new TernaryExpr(ctx.left.accept(this), ctx.iftrue.accept(this), ctx.iffalse.accept(this));
    }

    @Override
    public Expr visitTerminal(@NotNull TerminalNode node) {

        final int type = node.getSymbol().getType();
        switch (type) {
            case IntegerLiteral:
                return new SymbolExpr(node.getText(), Integer.class);
            case FloatingPointLiteral:
                return new SymbolExpr(node.getText(), Float.class);
            case BooleanLiteral:
                return new SymbolExpr(node.getText(), Boolean.class);
            case CharacterLiteral:
                return new SymbolExpr(node.getText(), Character.class);
            case SingleQuoteString:
                return new SymbolExpr(node.getText(), String.class);
            case DoubleQuoteString:
                return new SymbolExpr(node.getText(), String.class);
            case NullLiteral:
                return new SymbolExpr(node.getText(), Object.class);
            default:
                throw new RuntimeException("cannot create expression from terminal node " + node.toString());
        }
    }

    @Override
    public Expr visitMathOp(@NotNull MathOpContext ctx) {
        // TODO must support upper cast
        return new OpExpr(ctx.left.accept(this), ctx.op.getText(), ctx.right.accept(this));
    }

    @Override
    public Expr visitBitShiftOp(@NotNull BitShiftOpContext ctx) {
        return new BinaryOpExpr(ctx.left.accept(this), ctx.op.getText(), ctx.right.accept(this));
    }

    @Override
    public Expr visitComparisonOp(@NotNull ComparisonOpContext ctx) {
        return new ComparisonOpExpr(ctx.left.accept(this), ctx.op.getText(), ctx.right.accept(this));
    }

    @Override
    public Expr visitBinaryOp(@NotNull BinaryOpContext ctx) {
        return new BinaryOpExpr(ctx.left.accept(this), ctx.op.getText(), ctx.right.accept(this));
    }

    @Override
    public Expr visitAndOrOp(@NotNull AndOrOpContext ctx) {
        return new AndOrOpExpr(ctx.left.accept(this), ctx.op.getText(), ctx.right.accept(this));
    }

    @Override
    protected Expr aggregateResult(final Expr aggregate, final Expr nextResult) {
        if (aggregate == null) {
            return nextResult;
        } else {
            return new Expr() {
                @org.jetbrains.annotations.NotNull
                @Override
                public Class<? extends Object> resolveValueType(
                        @org.jetbrains.annotations.NotNull ClassAnalyzer classAnalyzer) {
                    return classAnalyzer.commonParentOf(aggregate.getResolvedClass(), nextResult.getResolvedClass());
                }

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
        return new MethodCallExpr(expression, methodName, parameters);
    }
}
