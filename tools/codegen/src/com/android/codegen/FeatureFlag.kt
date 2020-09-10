package com.android.codegen


/**
 * See also [ClassPrinter.invoke] for more default flag values resolution rules
 */
enum class FeatureFlag(val onByDefault: Boolean, val desc: String = "") {
    PARCELABLE(false, "implement Parcelable contract"),
    AIDL(false, "generate a 'parcelable declaration' .aidl file alongside"),
    CONSTRUCTOR(true, "an all-argument constructor"),
    BUILDER(false, "e.g. MyClass.builder().setFoo(..).build();"),
    GETTERS(true, "getters, e.g. getFoo()"),
    SETTERS(false, "chainable/fluent setters, e.g. setFoo(..).setBar(..)"),
    WITHERS(false, "'immutable setters' returning a new instance, " +
            "e.g. newFoo = foo.withBar(barValue)"),
    EQUALS_HASH_CODE(false, "equals + hashCode based on fields"),
    TO_STRING(false, "toString based on fields"),
    BUILD_UPON(false, "builder factory from existing instance, " +
            "e.g. instance.buildUpon().setFoo(..).build()"),
    IMPLICIT_NONNULL(true, "treat lack of @Nullable as @NonNull for Object fields"),
    COPY_CONSTRUCTOR(false, "a constructor for an instance identical to the given one"),
    CONST_DEFS(true, "@Int/StringDef's based on declared static constants"),
    FOR_EACH_FIELD(false, "forEachField((name, value) -> ...)");

    val kebabCase = name.toLowerCase().replace("_", "-")
    val upperCamelCase = name.split("_").map { it.toLowerCase().capitalize() }.joinToString("")
}
