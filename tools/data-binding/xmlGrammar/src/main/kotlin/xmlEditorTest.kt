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
package com.android.databinding

import java.io.File
import org.antlr.v4.runtime.ANTLRInputStream
import org.antlr.v4.runtime.CommonTokenStream
import java.io.FileReader
import org.antlr.v4.runtime.Token
import java.util.Comparator
import kotlin.properties.Delegates

fun main(vararg args : String) {
    val f = File("/Volumes/ssd/src/data-binding/KDataBinder/samples/BindingDemo/app/src/main/res/layout/main_activity.xml")
    antlrTest(f);
}

fun log(f : () -> String) {
    System.out.println("LOG: ${f()}");
}

fun antlrTest(f: File) : String? {
    val inputStream = ANTLRInputStream(FileReader(f))
    val lexer = XMLLexer(inputStream)
    val tokenStream = CommonTokenStream(lexer)
    val parser = XMLParser(tokenStream)
    val expr = parser.document()
    log{"exp tree: ${expr.toStringTree(parser)}"}
    val reservedElementNames = arrayListOf("variable", "import")
    val visitor = object : XMLParserBaseVisitor<MutableList<Pair<Position, Position>>>() {
        override fun visitAttribute(ctx: XMLParser.AttributeContext): MutableList<Pair<Position, Position>>? {
            log{"attr:${ctx.attrName.getText()} ${ctx.attrValue.getText()}"}
            if (ctx.attrName.getText().startsWith("bind:")) {

                return arrayListOf(Pair(ctx.getStart().toPosition(), ctx.getStop().toEndPosition()))
            } else if (ctx.attrValue.getText().startsWith("\"@{") && ctx.attrValue.getText().endsWith("}\"")) {
                return arrayListOf(Pair(ctx.getStart().toPosition(), ctx.getStop().toEndPosition()))
            }

            //log{"visiting attr: ${ctx.getText()} at location ${ctx.getStart().toS()} ${ctx.getStop().toS()}"}
            return super<XMLParserBaseVisitor>.visitAttribute(ctx)
        }

        override fun visitElement(ctx: XMLParser.ElementContext): MutableList<Pair<Position, Position>>? {
            log{"elm ${ctx.elmName.getText()} || ${ctx.Name()}"}
            if (reservedElementNames.contains(ctx.elmName?.getText()) || ctx.elmName.getText().startsWith("bind:")) {
                return arrayListOf(Pair(ctx.getStart().toPosition(), ctx.getStop().toEndPosition()))
            }
            return super< XMLParserBaseVisitor>.visitElement(ctx)
        }

        override fun defaultResult(): MutableList<Pair<Position, Position>>? = arrayListOf()

        override fun aggregateResult(aggregate: MutableList<Pair<Position, Position>>?, nextResult: MutableList<Pair<Position, Position>>?): MutableList<Pair<Position, Position>>? {
            return if (aggregate == null) {
                return nextResult 
            } else if (nextResult == null) {
                return aggregate
            } else {
                aggregate.addAll(nextResult)
                return aggregate
            }
        }
    }
    val parsedExpr = expr.accept(visitor)
    if (parsedExpr.size() == 0) {
        return null//nothing to strip
    }
    log {"result ${parsedExpr.joinToString("\n-> ")}"}
    parsedExpr.forEach {
        log {"${it.first.line} ${it.first.charIndex}"}
    }
    val out = StringBuilder()
    val lines = f.readLines("utf-8")
    lines.forEach { out.appendln(it) }

    val sorted = parsedExpr.sortBy(object : Comparator<Pair<Position, Position>> {
        override fun compare(o1: Pair<Position, Position>, o2: Pair<Position, Position>): Int {
            val lineCmp = o1.first.line.compareTo(o2.first.charIndex)
            if (lineCmp != 0) {
                return lineCmp
            }
            return o1.first.line.compareTo(o2.first.charIndex)
        }
    })

    var lineStarts = arrayListOf(0)

    lines.withIndices().forEach {
        if (it.first > 0) {
            lineStarts.add(lineStarts[it.first - 1] + lines[it.first - 1].length() + 1)
        }
    }

    val seperator = System.lineSeparator().charAt(0)

    sorted.forEach {
        val posStart = lineStarts[it.first.line] + it.first.charIndex
        val posEnd = lineStarts[it.second.line] + it.second.charIndex
        for( i in posStart..(posEnd - 1)) {
            if (out.charAt(i) != seperator) {
                out.setCharAt(i, ' ')
            }
        }
    }

    return out.toString()
}


fun org.antlr.v4.runtime.Token.toS() : String = "[L:${getLine()} CH:${getCharPositionInLine()}]"

fun org.antlr.v4.runtime.Token.toPosition() : Position = Position(getLine() -1 , getCharPositionInLine())

fun org.antlr.v4.runtime.Token.toEndPosition() : Position = Position(getLine() - 1 , getCharPositionInLine() + getText().size)

data class Position(var line : Int, var charIndex : Int) {
}
