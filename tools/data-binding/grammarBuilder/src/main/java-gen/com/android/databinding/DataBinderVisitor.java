/*
 * Copyright (C) 2014 The Android Open Source Project
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

// Generated from DataBinder.g4 by ANTLR 4.4
package com.android.databinding;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link DataBinderParser}.
 *
 * @param <Result> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface DataBinderVisitor<Result> extends ParseTreeVisitor<Result> {
	/**
	 * Visit a parse tree produced by {@link DataBinderParser#symbol}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitSymbol(@NotNull DataBinderParser.SymbolContext ctx);

	/**
	 * Visit a parse tree produced by the {@code idExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitIdExpr(@NotNull DataBinderParser.IdExprContext ctx);

	/**
	 * Visit a parse tree produced by the {@code atomExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitAtomExpr(@NotNull DataBinderParser.AtomExprContext ctx);

	/**
	 * Visit a parse tree produced by {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitExpr(@NotNull DataBinderParser.ExprContext ctx);

	/**
	 * Visit a parse tree produced by the {@code ternaryExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitTernaryExpr(@NotNull DataBinderParser.TernaryExprContext ctx);

	/**
	 * Visit a parse tree produced by the {@code globalMethodCallExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitGlobalMethodCallExpr(@NotNull DataBinderParser.GlobalMethodCallExprContext ctx);

	/**
	 * Visit a parse tree produced by the {@code innerExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitInnerExpr(@NotNull DataBinderParser.InnerExprContext ctx);

	/**
	 * Visit a parse tree produced by {@link DataBinderParser#field}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitField(@NotNull DataBinderParser.FieldContext ctx);

	/**
	 * Visit a parse tree produced by the {@code opExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitOpExpr(@NotNull DataBinderParser.OpExprContext ctx);

	/**
	 * Visit a parse tree produced by the {@code nilExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitNilExpr(@NotNull DataBinderParser.NilExprContext ctx);

	/**
	 * Visit a parse tree produced by {@link DataBinderParser#start}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitStart(@NotNull DataBinderParser.StartContext ctx);

	/**
	 * Visit a parse tree produced by {@link DataBinderParser#hackyStringSymbol}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitHackyStringSymbol(@NotNull DataBinderParser.HackyStringSymbolContext ctx);

	/**
	 * Visit a parse tree produced by the {@code hackyStringExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitHackyStringExpr(@NotNull DataBinderParser.HackyStringExprContext ctx);

	/**
	 * Visit a parse tree produced by the {@code methodCallExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitMethodCallExpr(@NotNull DataBinderParser.MethodCallExprContext ctx);

	/**
	 * Visit a parse tree produced by the {@code eqExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitEqExpr(@NotNull DataBinderParser.EqExprContext ctx);

	/**
	 * Visit a parse tree produced by {@link DataBinderParser#nil}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitNil(@NotNull DataBinderParser.NilContext ctx);

	/**
	 * Visit a parse tree produced by {@link DataBinderParser#exprList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitExprList(@NotNull DataBinderParser.ExprListContext ctx);
}