/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm.test.parsing.parcelling

import android.os.Parcel
import android.os.Parcelable
import com.android.server.pm.test.util.IgnoreableExpect
import com.google.common.truth.Expect
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.util.Objects
import kotlin.contracts.ExperimentalContracts
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KFunction1
import kotlin.reflect.KFunction2
import kotlin.reflect.KFunction3
import kotlin.reflect.KVisibility
import kotlin.reflect.full.allSuperclasses
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.staticProperties
import kotlin.reflect.jvm.jvmErasure


@ExperimentalContracts
abstract class ParcelableComponentTest(
    private val getterType: KClass<*>,
    private val setterType: KClass<out Parcelable>
) {

    companion object {
        private val DEFAULT_EXCLUDED = listOf(
            // Java
            "toString",
            "equals",
            "hashCode",
            // Parcelable
            "getStability",
            "describeContents",
            "writeToParcel",
            // @DataClass
            "__metadata"
        )
    }

    internal val ignoreableExpect = IgnoreableExpect()

    // Hides internal type
    @get:Rule
    val ignoreableAsTestRule: TestRule = ignoreableExpect

    val expect: Expect
        get() = ignoreableExpect.expect

    protected var testCounter = 1

    protected abstract val defaultImpl: Any
    protected abstract val creator: Parcelable.Creator<out Parcelable>

    protected open val excludedMethods: Collection<String> = emptyList()

    protected abstract val baseParams: Collection<KFunction1<*, Any?>>

    private val getters = getterType.memberFunctions
        .filterNot { DEFAULT_EXCLUDED.contains(it.name) }

    private val setters = setterType.memberFunctions
        .filterNot { DEFAULT_EXCLUDED.contains(it.name) }

    constructor(kClass: KClass<out Parcelable>) : this(kClass, kClass)

    @Before
    fun checkNoPublicFields() {
        // Fields are not currently testable, and the idea is to enforce interface access for
        // immutability purposes, so disallow any public fields from existing.
        expect.that(getterType.memberProperties.filter { it.visibility == KVisibility.PUBLIC }
            .filterNot { DEFAULT_EXCLUDED.contains(it.name) })
            .isEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <ObjectType, ReturnType> buildParams(
        getFunction: KFunction1<ObjectType, ReturnType>,
    ): Param? {
        return buildParams<ObjectType, ReturnType, ReturnType, ReturnType>(
            getFunction,
            autoValue(getFunction) as ReturnType ?: return null
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <ObjectType, ReturnType, SetType : Any?, CompareType : Any?> buildParams(
        getFunction: KFunction1<ObjectType, ReturnType>,
        value: SetType,
    ): Param? {
        return getSetByValue<ObjectType, ReturnType, SetType, Any?>(
            getFunction,
            findSetFunction(getFunction) ?: return null,
            value
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <ObjectType, ReturnType> findSetFunction(
        getFunction: KFunction1<ObjectType, ReturnType>
    ): KFunction2<ObjectType, ReturnType, Any?>? {
        val getFunctionName = getFunction.name
        val prefix = when {
            getFunctionName.startsWith("get") -> "get"
            getFunctionName.startsWith("is") -> "is"
            getFunctionName.startsWith("has") -> "has"
            else -> throw IllegalArgumentException("Unsupported method name $getFunctionName")
        }
        val setFunctionName = "set" + getFunctionName.removePrefix(prefix)
        val setFunction = setters.filter { it.name == setFunctionName }
            .minByOrNull { it.parameters.size }

        if (setFunction == null) {
            expect.withMessage("$getFunctionName does not have corresponding $setFunctionName")
                .fail()
            return null
        }

        return setFunction as KFunction2<ObjectType, ReturnType, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun <ObjectType, ReturnType, SetType> findAddFunction(
        getFunction: KFunction1<ObjectType, ReturnType>
    ): KFunction2<ObjectType, SetType, Any?>? {
        val getFunctionName = getFunction.name
        if (!getFunctionName.startsWith("get")) {
            throw IllegalArgumentException("Unsupported method name $getFunctionName")
        }

        val setFunctionName = "add" + getFunctionName.removePrefix("get").run {
            // Remove plurality
            when {
                endsWith("ies") -> "${removeSuffix("ies")}y"
                endsWith("s") -> removeSuffix("s")
                else -> this
            }
        }

        val setFunction = setters.filter { it.name == setFunctionName }
            .minByOrNull { it.parameters.size }

        if (setFunction == null) {
            expect.withMessage("$getFunctionName does not have corresponding $setFunctionName")
                .fail()
            return null
        }

        return setFunction as KFunction2<ObjectType, SetType, Any?>
    }

    protected fun <ObjectType, ReturnType> getter(
        getFunction: KFunction1<ObjectType, ReturnType>,
        valueToSet: ReturnType
    ) = buildParams<ObjectType, ReturnType, ReturnType, ReturnType>(getFunction, valueToSet)

    protected fun <ObjectType, ReturnType, SetType : Any?, CompareType : Any?> getter(
        getFunction: KFunction1<ObjectType, ReturnType>,
        expectedValue: CompareType,
        valueToSet: SetType
    ): Param? {
        return getSetByValue(
            getFunction,
            findSetFunction(getFunction) ?: return null,
            value = expectedValue,
            transformSet = { valueToSet }
        )
    }

    @Suppress("UNCHECKED_CAST")
    protected fun <ObjectType, ReturnType> adder(
        getFunction: KFunction1<ObjectType, ReturnType>,
        value: ReturnType,
    ): Param? {
        return getSetByValue(
            getFunction,
            findAddFunction<ObjectType, Any?, ReturnType>(getFunction) ?: return null,
            value,
            transformGet = {
                // Primitive arrays don't implement Iterable, so cast manually
                when (it) {
                    is BooleanArray -> it.singleOrNull()
                    is IntArray -> it.singleOrNull()
                    is LongArray -> it.singleOrNull()
                    is Iterable<*> -> it.singleOrNull()
                    else -> null
                }
            },
        )
    }

    /**
     * Method to provide custom getter and setter logic for values which are not simple primitives
     * or cannot be directly compared using [Objects.equals].
     *
     * @param getFunction the getter function which will be called and marked as tested
     * @param setFunction the setter function which will be called and marked as tested
     * @param value the value for comparison through the parcel-unparcel cycle, which can be
     *          anything, like the [String] ID of an inner object
     * @param transformGet the function to transform the result of [getFunction] into [value]
     * @param transformSet the function to transform [value] into an input for [setFunction]
     * @param compare the function that compares the pre/post-parcel [value] objects
     */
    @Suppress("UNCHECKED_CAST")
    protected fun <ObjectType, ReturnType, SetType : Any?, CompareType : Any?> getSetByValue(
        getFunction: KFunction1<ObjectType, ReturnType>,
        setFunction: KFunction2<ObjectType, SetType, Any?>,
        value: CompareType,
        transformGet: (ReturnType) -> CompareType = { it as CompareType },
        transformSet: (CompareType) -> SetType = { it as SetType },
        compare: (CompareType, CompareType) -> Boolean? = Objects::equals
    ) = Param(
        getFunction.name,
        { transformGet(getFunction.call(it as ObjectType)) },
        setFunction.name,
        { setFunction.call(it.first() as ObjectType, transformSet(it[1] as CompareType)) },
        { value },
        { first, second -> compare(first as CompareType, second as CompareType) == true }
    )

    /**
     * Variant of [getSetByValue] that allows specifying a [setFunction] with 2 inputs.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun <ObjectType, ReturnType, SetType1 : Any?, SetType2 : Any?, CompareType : Any?>
            getSetByValue2(
        getFunction: KFunction1<ObjectType, ReturnType>,
        setFunction: KFunction3<ObjectType, SetType1, SetType2, Any>,
        value: CompareType,
        transformGet: (ReturnType) -> CompareType = { it as CompareType },
        transformSet: (CompareType) -> Pair<SetType1, SetType2> =
            { it as Pair<SetType1, SetType2> },
        compare: (CompareType, CompareType) -> Boolean = Objects::equals
    ) = Param(
        getFunction.name,
        { transformGet(getFunction.call(it as ObjectType)) },
        setFunction.name,
        {
            val pair = transformSet(it[1] as CompareType)
            setFunction.call(it.first() as ObjectType, pair.first, pair.second)
        },
        { value },
        { first, second -> compare(first as CompareType, second as CompareType) }
    )

    protected fun autoValue(getFunction: KFunction<*>) = when (getFunction.returnType.jvmErasure) {
        Boolean::class -> (getFunction.call(defaultImpl) as Boolean?)?.not() ?: true
        CharSequence::class,
        String::class -> getFunction.name + "TEST"
        Int::class -> testCounter++
        Long::class -> (testCounter++).toLong()
        Float::class -> (testCounter++).toFloat()
        else -> {
            expect.withMessage("${getFunction.name} needs to provide value").fail()
            null
        }
    }

    /**
     * Verifies two instances are equivalent via a series of properties. For use when a public API
     * class has not implemented equals.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun <T : Any> equalBy(
        first: T?,
        second: T?,
        vararg properties: (T) -> Any?
    ) = properties.all { property ->
        first?.let { property(it) } == second?.let { property(it) }
    }

    @Test
    fun valueComparison() {
        val params = baseParams.mapNotNull(::buildParams) + extraParams().filterNotNull()
        val before = initialObject()

        params.forEach { it.setFunction(arrayOf(before, it.value())) }

        val parcel = Parcel.obtain()
        writeToParcel(parcel, before)

        val dataSize = parcel.dataSize()

        parcel.setDataPosition(0)

        val after = creator.createFromParcel(parcel)

        expect.withMessage("Mismatched write and read data sizes")
            .that(parcel.dataPosition())
            .isEqualTo(dataSize)

        parcel.recycle()

        runAssertions(params, before, after)
    }

    @Test
    open fun parcellingSize() {
        val parcelOne = Parcel.obtain()
        writeToParcel(parcelOne, initialObject())

        val parcelTwo = Parcel.obtain()
        initialObject().writeToParcel(parcelTwo, 0)

        val superDataSizes = setterType.allSuperclasses
            .filter { it.isSubclassOf(Parcelable::class) }
            .mapNotNull { it.memberFunctions.find { it.name == "writeToParcel" } }
            .filter { it.isFinal }
            .map {
                val parcel = Parcel.obtain()
                initialObject().writeToParcel(parcel, 0)
                parcel.dataSize().also { parcel.recycle() }
            }

        if ((superDataSizes + parcelOne.dataSize() + parcelTwo.dataSize()).distinct().size != 1) {
            listOf(getterType, setterType).distinct().forEach {
                val creatorProperties = it.staticProperties.filter { it.name == "CREATOR" }
                if (creatorProperties.size > 1) {
                    expect.withMessage(
                        "Multiple matching CREATOR fields found for" +
                                it.qualifiedName
                    )
                        .that(creatorProperties)
                        .hasSize(1)
                } else {
                    val creator = creatorProperties.single().get()
                    if (creator !is Parcelable.Creator<*>) {
                        expect.that(creator).isInstanceOf(Parcelable.Creator::class.java)
                        return
                    }

                    parcelTwo.setDataPosition(0)
                    val parcelable = creator.createFromParcel(parcelTwo)
                    if (parcelable::class.isSubclassOf(setterType)) {
                        expect.withMessage(
                            "${it.qualifiedName} which does not safely override writeToParcel " +
                                    "cannot contain a subclass CREATOR field"
                        )
                            .fail()
                    }
                }
            }
        }

        parcelOne.recycle()
        parcelTwo.recycle()
    }

    private fun runAssertions(params: List<Param>, before: Parcelable, after: Parcelable) {
        params.forEach {
            val actual = it.getFunction(after)
            val expected = it.value()
            val equal = it.compare(actual, expected)
            expect.withMessage("${it.getFunctionName} was $actual, expected $expected")
                .that(equal)
                .isTrue()
        }

        extraAssertions(before, after)

        // TODO: Handle method overloads?
        val expectedFunctions = (getters.map { it.name }
                + setters.map { it.name }
                - excludedMethods)
            .distinct()

        val allTestedFunctions = params.flatMap {
            listOfNotNull(it.getFunctionName, it.setFunctionName)
        }
        expect.that(allTestedFunctions).containsExactlyElementsIn(expectedFunctions)
    }

    open fun extraParams(): Collection<Param?> = emptyList()

    open fun initialObject(): Parcelable = setterType.createInstance()

    open fun extraAssertions(before: Parcelable, after: Parcelable) {}

    open fun writeToParcel(parcel: Parcel, value: Parcelable) = value.writeToParcel(parcel, 0)

    data class Param(
        val getFunctionName: String,
        val getFunction: (Any?) -> Any?,
        val setFunctionName: String?,
        val setFunction: (Array<Any?>) -> Unit,
        val value: () -> Any?,
        val compare: (Any?, Any?) -> Boolean = Objects::equals
    )
}
