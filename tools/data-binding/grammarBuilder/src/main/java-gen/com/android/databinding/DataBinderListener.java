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
import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link DataBinderParser}.
 */
public interface DataBinderListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by {@link DataBinderParser#symbol}.
	 * @param ctx the parse tree
	 */
	void enterSymbol(@NotNull DataBinderParser.SymbolContext ctx);
	/**
	 * Exit a parse tree produced by {@link DataBinderParser#symbol}.
	 * @param ctx the parse tree
	 */
	void exitSymbol(@NotNull DataBinderParser.SymbolContext ctx);

	/**
	 * Enter a parse tree produced by the {@code idExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void enterIdExpr(@NotNull DataBinderParser.IdExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code idExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void exitIdExpr(@NotNull DataBinderParser.IdExprContext ctx);

	/**
	 * Enter a parse tree produced by the {@code atomExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void enterAtomExpr(@NotNull DataBinderParser.AtomExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code atomExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void exitAtomExpr(@NotNull DataBinderParser.AtomExprContext ctx);

	/**
	 * Enter a parse tree produced by {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void enterExpr(@NotNull DataBinderParser.ExprContext ctx);
	/**
	 * Exit a parse tree produced by {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void exitExpr(@NotNull DataBinderParser.ExprContext ctx);

	/**
	 * Enter a parse tree produced by the {@code ternaryExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void enterTernaryExpr(@NotNull DataBinderParser.TernaryExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ternaryExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void exitTernaryExpr(@NotNull DataBinderParser.TernaryExprContext ctx);

	/**
	 * Enter a parse tree produced by the {@code globalMethodCallExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void enterGlobalMethodCallExpr(@NotNull DataBinderParser.GlobalMethodCallExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code globalMethodCallExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void exitGlobalMethodCallExpr(@NotNull DataBinderParser.GlobalMethodCallExprContext ctx);

	/**
	 * Enter a parse tree produced by the {@code innerExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void enterInnerExpr(@NotNull DataBinderParser.InnerExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code innerExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void exitInnerExpr(@NotNull DataBinderParser.InnerExprContext ctx);

	/**
	 * Enter a parse tree produced by {@link DataBinderParser#field}.
	 * @param ctx the parse tree
	 */
	void enterField(@NotNull DataBinderParser.FieldContext ctx);
	/**
	 * Exit a parse tree produced by {@link DataBinderParser#field}.
	 * @param ctx the parse tree
	 */
	void exitField(@NotNull DataBinderParser.FieldContext ctx);

	/**
	 * Enter a parse tree produced by the {@code opExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void enterOpExpr(@NotNull DataBinderParser.OpExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code opExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void exitOpExpr(@NotNull DataBinderParser.OpExprContext ctx);

	/**
	 * Enter a parse tree produced by the {@code nilExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void enterNilExpr(@NotNull DataBinderParser.NilExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code nilExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void exitNilExpr(@NotNull DataBinderParser.NilExprContext ctx);

	/**
	 * Enter a parse tree produced by {@link DataBinderParser#start}.
	 * @param ctx the parse tree
	 */
	void enterStart(@NotNull DataBinderParser.StartContext ctx);
	/**
	 * Exit a parse tree produced by {@link DataBinderParser#start}.
	 * @param ctx the parse tree
	 */
	void exitStart(@NotNull DataBinderParser.StartContext ctx);

	/**
	 * Enter a parse tree produced by {@link DataBinderParser#hackyStringSymbol}.
	 * @param ctx the parse tree
	 */
	void enterHackyStringSymbol(@NotNull DataBinderParser.HackyStringSymbolContext ctx);
	/**
	 * Exit a parse tree produced by {@link DataBinderParser#hackyStringSymbol}.
	 * @param ctx the parse tree
	 */
	void exitHackyStringSymbol(@NotNull DataBinderParser.HackyStringSymbolContext ctx);

	/**
	 * Enter a parse tree produced by the {@code hackyStringExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void enterHackyStringExpr(@NotNull DataBinderParser.HackyStringExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code hackyStringExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void exitHackyStringExpr(@NotNull DataBinderParser.HackyStringExprContext ctx);

	/**
	 * Enter a parse tree produced by the {@code methodCallExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void enterMethodCallExpr(@NotNull DataBinderParser.MethodCallExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code methodCallExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void exitMethodCallExpr(@NotNull DataBinderParser.MethodCallExprContext ctx);

	/**
	 * Enter a parse tree produced by the {@code eqExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void enterEqExpr(@NotNull DataBinderParser.EqExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code eqExpr}
	 * labeled alternative in {@link DataBinderParser#expr}.
	 * @param ctx the parse tree
	 */
	void exitEqExpr(@NotNull DataBinderParser.EqExprContext ctx);

	/**
	 * Enter a parse tree produced by {@link DataBinderParser#nil}.
	 * @param ctx the parse tree
	 */
	void enterNil(@NotNull DataBinderParser.NilContext ctx);
	/**
	 * Exit a parse tree produced by {@link DataBinderParser#nil}.
	 * @param ctx the parse tree
	 */
	void exitNil(@NotNull DataBinderParser.NilContext ctx);

	/**
	 * Enter a parse tree produced by {@link DataBinderParser#exprList}.
	 * @param ctx the parse tree
	 */
	void enterExprList(@NotNull DataBinderParser.ExprListContext ctx);
	/**
	 * Exit a parse tree produced by {@link DataBinderParser#exprList}.
	 * @param ctx the parse tree
	 */
	void exitExprList(@NotNull DataBinderParser.ExprListContext ctx);
}