package com.android.databinding.ext

import com.android.databinding.ext.joinToCamelCase
import com.android.databinding.ext.joinToCamelCaseAsVar
import com.android.databinding.ext.joinIndented
import com.android.databinding.ext.joinIndentedExceptFirst

/**
 * Created by yboyar on 11/11/14.
 */
public fun String.extractAndroidId() : String = this.split("/")[1]

public fun String.times(x : Int) : String = 0.rangeTo(x-1).map { this }.join("")

public fun String.indent(x : Int) : String = split("\n").filterNot{it.trim() == ""}.joinIndented(x)

public fun String.indentExceptFirst(x : Int) : String = split("\n").filterNot{it.trim() == ""}.joinIndentedExceptFirst(x)

public fun String.getIndentation() : Int {
    var count = 0
    while ((count < this.length) && (this[count] <= ' ')) {
        count++
    }
    return count
}

public fun String.toCamelCase() : String {
    val split = this.split("_")
    if (split.size == 0) return ""
    if (split.size == 1) return split[0].capitalize()
    return split.joinToCamelCase()
}

public fun String.toCamelCaseAsVar() : String {
    val split = this.split("_")
    if (split.size == 0) return ""
    if (split.size == 1) return split[0]
    return split.joinToCamelCaseAsVar()
}