/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.internal.util;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.os.Parcelable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface DataClass {

    /**
     * Generates {@link Parcelable#writeToParcel}, {@link Parcelable#describeContents} and a
     * {@link Parcelable.Creator}.
     *
     * Can be implicitly requested by adding "implements Parcelable" to class signature
     *
     * You can provide custom parceling logic by using a {@link ParcelWith} annotation with a
     * custom {@link Parcelling} subclass.
     *
     * Alternatively, for one-off customizations you can declare methods like:
     * {@code void parcelFieldName(Parcel dest, int flags)}
     * {@code static FieldType unparcelFieldName(Parcel in)}
     */
    boolean genParcelable() default false;

    /**
     * Generates a simple "parcelable" .aidl file alongside the original .java file
     *
     * If not explicitly requested/suppressed, is on iff {@link #genParcelable} is on
     */
    boolean genAidl() default false;

    /**
     * Generates getters for each field.
     *
     * You can request for getter to lazily initialize your field by declaring a method like:
     * {@code FieldType lazyInitFieldName()}
     *
     * You can request for the lazy initialization to be thread safe my marking the field volatile.
     */
    boolean genGetters() default true;

    /**
     * {@link #genGetters} with @hide
     */
    boolean genHiddenGetters() default false;

    /**
     * Generates setters for each field.
     */
    boolean genSetters() default false;

    /**
     * {@link #genSetters} with @hide
     */
    boolean genHiddenSetters() default false;

    /**
     * Generates a public constructor with each field initialized from a parameter and optionally
     * some user-defined state validation at the end.
     *
     * Uses field {@link Nullable nullability}/default value presence to determine optional
     * parameters.
     *
     * Requesting a {@link #genBuilder} suppresses public constructor generation by default.
     *
     * You receive a callback at the end of constructor call by declaring the method:
     * {@code void onConstructed()}
     * This is the place to put any custom validation logic.
     */
    boolean genConstructor() default true;

    /**
     * {@link #genConstructor} with @hide
     */
    boolean genHiddenConstructor() default false;

    /**
     * Generates a Builder for your class.
     *
     * Uses a package-private constructor under the hood, so same rules hold as for
     * {@link #genConstructor()}
     */
    boolean genBuilder() default false;

    /**
     * {@link #genBuilder} with @hide
     */
    boolean genHiddenBuilder() default false;

    /**
     * Generates a structural {@link Object#equals} + {@link Object#hashCode}.
     *
     * You can customize individual fields' logic by declaring methods like:
     * {@link boolean fieldNameEquals(ClassName otherInstance)}
     * {@link boolean fieldNameEquals(FieldType otherValue)}
     * {@link int fieldNameHashCode()}
     */
    boolean genEqualsHashCode() default false;

    /**
     * Generates a structural {@link Object#toString}.
     *
     * You can customize individual fields' logic by declaring methods like:
     * {@link String fieldNameToString()}
     */
    boolean genToString() default false;

    /**
     * Generates a utility method that takes a {@link PerObjectFieldAction per-field callback}
     * and calls it once for each field with its name and value.
     *
     * If some fields are of primitive types, and additional overload is generated that takes
     * multiple callbacks, specialized for used primitive types to avoid auto-boxing, e.g.
     * {@link PerIntFieldAction}.
     */
    boolean genForEachField() default false;

    /**
     * Generates a constructor that copies the given instance of the same class.
     */
    boolean genCopyConstructor() default false;

    /**
     * {@link #genCopyConstructor} with @hide
     */
    boolean genHiddenCopyConstructor() default false;

    /**
     * Generates constant annotations({@link IntDef}/{@link StringDef}) for any constant groups
     * with common prefix.
     * The annotation names are based on the common prefix.
     *
     * For int constants this additionally generates the corresponding static *ToString method and
     * uses it in {@link Object#toString}.
     *
     * Additionally, any fields you annotate with the generated constants will be automatically
     * validated in constructor.
     *
     * Int constants specified as hex(0x..) are considered to be flags, which is taken into account
     * for in their *ToString and validation.
     *
     * You can optionally override the name of the generated annotation by annotating each constant
     * with the desired annotation name.
     *
     * Unless suppressed, is implied by presence of constants with common prefix.
     */
    boolean genConstDefs() default true;

    /**
     * {@link #genConstDefs} with @hide
     */
    boolean genHiddenConstDefs() default false;


    /**
     * Allows specifying custom parcelling logic based on reusable
     * {@link Parcelling} implementations
     */
    @Retention(RetentionPolicy.SOURCE)
    @Target(FIELD)
    @interface ParcelWith {
        Class<? extends Parcelling> value();
    }

    /**
     * Allows specifying a singular name for a builder's plural field name e.g. 'name' for 'mNames'
     * Used for Builder's {@code addName(String name)} methods
     */
    @Retention(RetentionPolicy.SOURCE)
    @Target(FIELD)
    @interface PluralOf {
        String value();
    }

    /**
     * Marks that any annotations following it are applicable to each element of the
     * collection/array, as opposed to itself.
     */
    @Retention(RetentionPolicy.SOURCE)
    @Target({FIELD, METHOD, PARAMETER, LOCAL_VARIABLE})
    @interface Each {}

    /**
     * @deprecated to be used by code generator exclusively
     */
    @Deprecated
    @Retention(RetentionPolicy.SOURCE)
    @Target({METHOD})
    @interface Generated {
        long time();
        String codegenVersion();
        String sourceFile();
        String inputSignatures() default "";

        /**
         * @deprecated to be used by code generator exclusively
         */
        @Deprecated
        @Retention(RetentionPolicy.SOURCE)
        @Target({FIELD, METHOD, ANNOTATION_TYPE, CONSTRUCTOR, TYPE})
        @interface Member {}
    }

    /**
     * Opt out of generating {@link #genConstDefs IntDef/StringDef}s for annotated constant
     */
    @Retention(RetentionPolicy.SOURCE)
    @Target({FIELD})
    @interface SuppressConstDefsGeneration {}

    /**
     * A class-level annotation to suppress methods' generation by name
     */
    @Retention(RetentionPolicy.SOURCE)
    @Target({TYPE})
    @interface Suppress {
        String[] value();
    }

    /**
     * Mark that the field should have a {@link Nullable} argument for its setter.
     */
    @Retention(RetentionPolicy.SOURCE)
    @Target({FIELD})
    @interface MaySetToNull {}

    /**
     * Callback used by {@link #genForEachField}.
     *
     * @param <THIS> The enclosing data class instance.
     *              Can be used to try and avoid capturing values from outside of the lambda,
     *              minimizing allocations.
     */
    interface PerObjectFieldAction<THIS> {
        void acceptObject(THIS self, String fieldName, Object fieldValue);
    }

    /**
     * A specialization of {@link PerObjectFieldAction} called exclusively for int fields to avoid
     * boxing.
     */
    interface PerIntFieldAction<THIS> {
        void acceptInt(THIS self, String fieldName, int fieldValue);
    }
}
