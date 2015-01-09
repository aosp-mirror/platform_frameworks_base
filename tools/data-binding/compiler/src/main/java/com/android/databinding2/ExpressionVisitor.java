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

package com.android.databinding2;

import com.google.common.base.Preconditions;

import com.android.databinding.BindingExpressionBaseVisitor;
import com.android.databinding.BindingExpressionParser;
import com.android.databinding2.expr.Expr;
import com.android.databinding2.expr.ExprModel;

import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ExpressionVisitor extends BindingExpressionBaseVisitor<Expr> {
    private final ExprModel mModel;
    public ExpressionVisitor(ExprModel model) {
        mModel = model;
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

        return mModel.symbol(javaString, String.class);
    }

    @Override
    public Expr visitGrouping(@NotNull BindingExpressionParser.GroupingContext ctx) {
        Preconditions.checkArgument(ctx.children.size() == 1, "Grouping expression should have"
                + " only 1 child");
        return mModel.group(ctx.children.get(0).accept(this));
    }

    @Override
    public Expr visitBindingSyntax(@NotNull BindingExpressionParser.BindingSyntaxContext ctx) {
        try {
            // TODO handle defaults
            return mModel.bindingExpr(ctx.expression().accept(this));
        } catch (Exception e) {
            System.out.println("Error while parsing! " + ctx.getText());
            e.printStackTrace();
            throw e;
        }
    }

    @Override
    public Expr visitDotOp(@NotNull BindingExpressionParser.DotOpContext ctx) {
        return mModel.field(ctx.expression().accept(this),
                ctx.Identifier().getSymbol().getText());
    }

    @Override
    public Expr visitQuestionQuestionOp(@NotNull BindingExpressionParser.QuestionQuestionOpContext ctx) {
        final Expr left = ctx.left.accept(this);
        return mModel.ternary(
                mModel.comparison("==", left, mModel.symbol("null", Object.class)),
                left, ctx.right.accept(this));
    }

    @Override
    public Expr visitTerminal(@NotNull TerminalNode node) {
        final int type = node.getSymbol().getType();
        switch (type) {
            case BindingExpressionParser.IntegerLiteral:
                return mModel.symbol(node.getText(), Integer.class);
            case BindingExpressionParser.FloatingPointLiteral:
                return mModel.symbol(node.getText(), Float.class);
            case BindingExpressionParser.BooleanLiteral:
                return mModel.symbol(node.getText(), Boolean.class);
            case BindingExpressionParser.CharacterLiteral:
                return mModel.symbol(node.getText(), Character.class);
            case BindingExpressionParser.SingleQuoteString:
                return mModel.symbol(node.getText(), String.class);
            case BindingExpressionParser.DoubleQuoteString:
                return mModel.symbol(node.getText(), String.class);
            case BindingExpressionParser.NullLiteral:
                return mModel.symbol(node.getText(), Object.class);
            default:
                throw new RuntimeException("cannot create expression from terminal node " + node.toString());
        }
    }

    @Override
    public Expr visitComparisonOp(@NotNull BindingExpressionParser.ComparisonOpContext ctx) {
        return mModel.comparison(ctx.op.getText(), ctx.left.accept(this), ctx.right.accept(this));
    }

    @Override
    public Expr visitIdentifier(@NotNull BindingExpressionParser.IdentifierContext ctx) {
        return mModel.identifier(ctx.getText());
    }

    @Override
    public Expr visitTernaryOp(@NotNull BindingExpressionParser.TernaryOpContext ctx) {
        return mModel.ternary(ctx.left.accept(this), ctx.iftrue.accept(this),
                ctx.iffalse.accept(this));
    }

    @Override
    public Expr visitMethodInvocation(
            @NotNull BindingExpressionParser.MethodInvocationContext ctx) {
        List<Expr> args = new ArrayList<>();
        if (ctx.args != null) {
            for (ParseTree item : ctx.args.children) {
                if (Objects.equals(item.getText(), ",")) {
                    continue;
                }
                args.add(item.accept(this));
            }
        }
        return mModel.methodCall(ctx.target.accept(this),
                ctx.Identifier().getText(), args);
    }

    @Override
    public Expr visitMathOp(@NotNull BindingExpressionParser.MathOpContext ctx) {
        return mModel.math(ctx.left.accept(this), ctx.op.getText(), ctx.right.accept(this));
    }

    //    @Override
//    public Expr visitIdentifier(@NotNull BindingExpressionParser.IdentifierContext ctx) {
//        final String identifier = ctx.Identifier().getText();
//        final VariableRef variableRef = mModel.getOrCreateVariable(identifier, null);
//        mAccessedVariables.add(variableRef);
//
//        return new FieldExpr(variableRef, new ArrayList<VariableRef>(0));
//    }
//
//    @Override
//    public Expr visit(@NotNull ParseTree tree) {
//        if (tree == null) {
//            return null;
//        }
//        return super.visit(tree);
//    }
//
//    @Override
//    public Expr visitTernaryOp(@NotNull BindingExpressionParser.TernaryOpContext ctx) {
//        return new TernaryExpr(ctx.left.accept(this), ctx.iftrue.accept(this), ctx.iffalse.accept(this));
//    }
//
//    @Override
//    public Expr visitTerminal(@NotNull TerminalNode node) {
//
//        final int type = node.getSymbol().getType();
//        switch (type) {
//            case IntegerLiteral:
//                return new SymbolExpr(node.getText(), Integer.class);
//            case FloatingPointLiteral:
//                return new SymbolExpr(node.getText(), Float.class);
//            case BooleanLiteral:
//                return new SymbolExpr(node.getText(), Boolean.class);
//            case CharacterLiteral:
//                return new SymbolExpr(node.getText(), Character.class);
//            case SingleQuoteString:
//                return new SymbolExpr(node.getText(), String.class);
//            case DoubleQuoteString:
//                return new SymbolExpr(node.getText(), String.class);
//            case NullLiteral:
//                return new SymbolExpr(node.getText(), Object.class);
//            default:
//                throw new RuntimeException("cannot create expression from terminal node " + node.toString());
//        }
//    }
//
//    @Override
//    public Expr visitMathOp(@NotNull BindingExpressionParser.MathOpContext ctx) {
//        // TODO must support upper cast
//        return new OpExpr(ctx.left.accept(this), ctx.op.getText(), ctx.right.accept(this));
//    }
//
//    @Override
//    public Expr visitBitShiftOp(@NotNull BindingExpressionParser.BitShiftOpContext ctx) {
//        return new BinaryOpExpr(ctx.left.accept(this), ctx.op.getText(), ctx.right.accept(this));
//    }
//
//    @Override
//    public Expr visitComparisonOp(@NotNull BindingExpressionParser.ComparisonOpContext ctx) {
//        return new ComparisonOpExpr(ctx.left.accept(this), ctx.op.getText(), ctx.right.accept(this));
//    }
//
//    @Override
//    public Expr visitBinaryOp(@NotNull BindingExpressionParser.BinaryOpContext ctx) {
//        return new BinaryOpExpr(ctx.left.accept(this), ctx.op.getText(), ctx.right.accept(this));
//    }
//
//    @Override
//    public Expr visitAndOrOp(@NotNull BindingExpressionParser.AndOrOpContext ctx) {
//        return new AndOrOpExpr(ctx.left.accept(this), ctx.op.getText(), ctx.right.accept(this));
//    }
//
//    @Override
//    protected Expr aggregateResult(final Expr aggregate, final Expr nextResult) {
//        if (aggregate == null) {
//            return nextResult;
//        } else {
//            return new Expr() {
//                @org.jetbrains.annotations.NotNull
//                @Override
//                public Class<? extends Object> resolveValueType(
//                        @org.jetbrains.annotations.NotNull ClassAnalyzer classAnalyzer) {
//                    return classAnalyzer.commonParentOf(aggregate.getResolvedClass(), nextResult.getResolvedClass());
//                }
//
//                @org.jetbrains.annotations.NotNull
//                @Override
//                public String toReadableString() {
//                    return aggregate.toReadableString() + ' ' + nextResult.toReadableString();
//                }
//
//                @org.jetbrains.annotations.NotNull
//                @Override
//                public String toJava() {
//                    return aggregate.toJava() + ' ' + nextResult.toJava();
//                }
//            };
//        }
//    }
//
//    @Override
//    public Expr visitDefaults(@NotNull BindingExpressionParser.DefaultsContext ctx) {
//        return visit(ctx.constantValue());
//    }
//
//    @Override
//    public Expr visitMethodInvocation(
//            @NotNull BindingExpressionParser.MethodInvocationContext ctx) {
//        final Expr expression = visit(ctx.expression());
//        final String methodName = ctx.Identifier().getText();
//        final ArrayList<Expr> parameters = new ArrayList<>();
//        if (ctx.expressionList() != null) {
//            for (BindingExpressionParser.ExpressionContext parameter : ctx.expressionList()
//                    .expression()) {
//                parameters.add(visit(parameter));
//            }
//        }
//        return new MethodCallExpr(expression, methodName, parameters);
//    }
}
