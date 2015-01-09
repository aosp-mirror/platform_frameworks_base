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

package com.android.databinding.parser

import org.antlr.v4.runtime.ANTLRInputStream
import com.android.databinding.DataBinderLexer
import org.antlr.v4.runtime.CommonTokenStream
import com.android.databinding.DataBinderParser
import com.android.databinding.parser.ExprModel
import com.android.databinding.DataBinderBaseVisitor
import com.android.databinding.parser.Expr
import com.android.databinding.parser.TernaryExpr
import com.android.databinding.parser.OpExpr
import com.android.databinding.parser.GlobalMethodCallExpr
import com.android.databinding.parser.MethodCallExpr
import com.android.databinding.parser.FieldExpr
import com.android.databinding.parser.VariableRef
import com.android.databinding.parser.SymbolExpr
import com.android.databinding.util.Log
import com.example.databinding.ExpressionVisitor
import com.android.databinding.BindingExpressionLexer
import com.android.databinding.BindingExpressionParser

class ExpressionParser {
    val model = ExprModel()
    val visitor = ExpressionVisitor(model)
    public fun parse(input : String) : Expr {
        val inputStream = ANTLRInputStream(input)
        val lexer = BindingExpressionLexer(inputStream)
        val tokenStream = CommonTokenStream(lexer)
        val parser = BindingExpressionParser(tokenStream)
        val expr = parser.bindingSyntax()
        visitor.getAccessedVariables().clear()
        Log.d("exp tree: ${expr.toStringTree(parser)}")
        val parsedExpr = expr.accept(visitor)
        parsedExpr.setReferencedVariables(visitor.getAccessedVariables())
        return parsedExpr
    }

    fun log(s: String) {
        System.out.println("[parser]: $s");
    }
}

class ExprVisitor(val model : ExprModel) : DataBinderBaseVisitor<Expr>() {
    val accessedVariables = hashSetOf<VariableRef>()
    //MUST VISIT EVERYTHING IN THE GRAMMAR
    override fun visitInnerExpr(ctx: DataBinderParser.InnerExprContext): Expr {
        return ctx.inner.accept(this)
    }

    override fun visitNilExpr(ctx: DataBinderParser.NilExprContext?): Expr? {
        return SymbolExpr("null", javaClass<Any>())
    }

    override fun visitTernaryExpr(ctx: DataBinderParser.TernaryExprContext): Expr {
        return TernaryExpr(ctx.pred.accept(this), ctx.t.accept(this), ctx.f.accept(this))
    }

    override fun visitOpExpr(ctx: DataBinderParser.OpExprContext): Expr {
        return OpExpr(ctx.left.accept(this), ctx.op.getText(), ctx.right.accept(this))
    }

    override fun visitEqExpr(ctx: DataBinderParser.EqExprContext): Expr {
        return OpExpr(ctx.left.accept(this), "==", ctx.right.accept(this))
    }

    fun parseExprList(expList : DataBinderParser.ExprListContext?) : List<Expr> =
            if (expList == null) {
                arrayListOf()
            }
            else {
                expList.children.filter { it.getText() != "," }.map { it.accept(this) }
            }

    override fun visitGlobalMethodCallExpr(ctx: DataBinderParser.GlobalMethodCallExprContext): Expr {
        return GlobalMethodCallExpr(ctx.methodName.getText(), parseExprList(ctx.arguments))
    }

    override fun visitMethodCallExpr(ctx: DataBinderParser.MethodCallExprContext): Expr {
        return MethodCallExpr(ctx.ownerObj.accept(this), ctx.methodName.getText(), parseExprList(ctx.arguments))
    }

    override fun visitField(ctx: DataBinderParser.FieldContext): Expr {
        return toField(ctx)
    }

    public fun toField(ctx : DataBinderParser.FieldContext) : FieldExpr {
        val fieldVars = arrayListOf<VariableRef>()
        ctx.children.filterNot{it.getText() == "."}.fold("") { prev, next ->
            val name = if (prev == "") next.getText() else "$prev.$next"
            val variableRef = model.getOrCreateVariable(name, model.getVariable(prev))
            accessedVariables.add(variableRef)
            fieldVars.add(variableRef)
            name
        }
        return FieldExpr(fieldVars[0], fieldVars.drop(1))
    }

    override fun visitAtomExpr(ctx: DataBinderParser.AtomExprContext): Expr? {
        return ctx.atom.accept(this)
    }

    override fun visitHackyStringExpr(ctx: DataBinderParser.HackyStringExprContext): Expr? {
        val s = ctx.getText()
        return SymbolExpr("\"${s.substring(1, s.size - 1)}\"", javaClass<String>());
    }

    override fun visitSymbol(ctx: DataBinderParser.SymbolContext): Expr? {
        // TODO need to know what this is.
        return SymbolExpr(ctx.getText(), javaClass<String>())
    }
}