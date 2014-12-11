package com.android.databinding.ext

import com.android.databinding.ext.toCamelCase
import com.android.databinding.ext.toCamelCaseAsVar
import com.android.databinding.ext.times
import com.android.databinding.ext.getIndentation

/**
 * Created by yboyar on 11/11/14.
 */
public fun List<String>.joinToCamelCase(): String = when(size()) {
    0 -> throw IllegalArgumentException("invalid section size, cannot be zero")
    1 -> this.get(0).toCamelCase()
    else -> this.map {it.toCamelCase()}.joinToString("")
}

public fun List<String>.joinToCamelCaseAsVar(): String = when(size()) {
    0 -> throw IllegalArgumentException("invalid section size, cannot be zero")
    1 -> this.get(0).toCamelCaseAsVar()
    else -> get(0).toCamelCaseAsVar() + drop(1).joinToCamelCase()
}

public fun Array<String>.joinToCamelCase(): String = when(size) {
    0 -> throw IllegalArgumentException("invalid section size, cannot be zero")
    1 -> this.get(0).toCamelCase()
    else -> this.map {it.toCamelCase()}.joinToString("")
}

public fun Array<String>.joinToCamelCaseAsVar(): String = when(size) {
    0 -> throw IllegalArgumentException("invalid section size, cannot be zero")
    1 -> this.get(0).toCamelCaseAsVar()
    else -> get(0).toCamelCaseAsVar() + drop(1).joinToCamelCase()
}

public fun List<String>.joinIndented(indentation : Int) : String {
    val sb = StringBuilder()
    val indent = "    ".times(indentation)
    for (line in this) {
        if (line.trim() == "") continue;
        sb.append(indent).append(line).append("\n")
    }
    return sb.toString()
}

public fun List<String>.joinIndentedExceptFirst(indentation : Int) : String {
    if (size == 0) return ""
    if (size == 1) return this.get(0)
    val sb = StringBuilder()
    val indent = "    ".times(indentation)
    sb.append(this.get(0))
    for (i in 1..size - 1) {
        if (this[i].trim() == "") continue;
        sb.append("\n").append(indent).append(this.get(i))
    }
    return sb.toString()
}

public fun List<String>.joinIndented() : String {
    if (size < 2) return this.get(0)
    return this.get(0) + "\n" + drop(1).joinIndented(this.get(0).getIndentation())
}