/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.processor.unsupportedappusage;

import static javax.lang.model.element.ElementKind.PACKAGE;
import static javax.tools.Diagnostic.Kind.ERROR;
import static javax.tools.Diagnostic.Kind.WARNING;

import android.annotation.UnsupportedAppUsage;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.sun.tools.javac.code.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Builds a dex signature for a given method or field.
 */
public class SignatureBuilder {

    private static final Map<TypeKind, String> TYPE_MAP = ImmutableMap.<TypeKind, String>builder()
            .put(TypeKind.BOOLEAN, "Z")
            .put(TypeKind.BYTE, "B")
            .put(TypeKind.CHAR, "C")
            .put(TypeKind.DOUBLE, "D")
            .put(TypeKind.FLOAT, "F")
            .put(TypeKind.INT, "I")
            .put(TypeKind.LONG, "J")
            .put(TypeKind.SHORT, "S")
            .put(TypeKind.VOID, "V")
            .build();

    private final Messager mMessager;

    /**
     * Exception used internally when we can't build a signature. Whenever this is thrown, an error
     * will also be written to the Messager.
     */
    private class SignatureBuilderException extends Exception {
        public SignatureBuilderException(String message) {
            super(message);
        }
        public void report(Element offendingElement) {
            mMessager.printMessage(ERROR, getMessage(), offendingElement);
        }
    }

    public SignatureBuilder(Messager messager) {
        mMessager = messager;
    }

    /**
     * Returns a list of enclosing elements for the given element, with the package first, and
     * excluding the element itself.
     */
    private List<Element> getEnclosingElements(Element e) {
        List<Element> enclosing = new ArrayList<>();
        e = e.getEnclosingElement(); // don't include the element itself.
        while (e != null) {
            enclosing.add(e);
            e = e.getEnclosingElement();
        }
        Collections.reverse(enclosing);
        return enclosing;
    }

    /**
     * Get the dex signature for a clazz, in format "Lpackage/name/Outer$Inner;"
     */
    private String getClassSignature(TypeElement clazz) {
        StringBuilder sb = new StringBuilder("L");
        for (Element enclosing : getEnclosingElements(clazz)) {
            if (enclosing.getKind() == PACKAGE) {
                sb.append(((PackageElement) enclosing)
                        .getQualifiedName()
                        .toString()
                        .replace('.', '/'));
                sb.append('/');
            } else {
                sb.append(enclosing.getSimpleName()).append('$');
            }

        }
        return sb
                .append(clazz.getSimpleName())
                .append(";")
                .toString();
    }

    /**
     * Returns the type signature for a given type. For primitive types, a single character.
     * For classes, the class signature. For arrays, a "[" preceeding the component type.
     */
    private String getTypeSignature(TypeMirror type) throws SignatureBuilderException {
        String sig = TYPE_MAP.get(type.getKind());
        if (sig != null) {
            return sig;
        }
        switch (type.getKind()) {
            case ARRAY:
                return "[" + getTypeSignature(((ArrayType) type).getComponentType());
            case DECLARED:
                Element declaring = ((DeclaredType) type).asElement();
                if (!(declaring instanceof TypeElement)) {
                    throw new SignatureBuilderException(
                            "Can't handle declared type of kind " + declaring.getKind());
                }
                return getClassSignature((TypeElement) declaring);
            case TYPEVAR:
                Type.TypeVar typeVar = (Type.TypeVar) type;
                if (typeVar.getLowerBound().getKind() != TypeKind.NULL) {
                    return getTypeSignature(typeVar.getLowerBound());
                } else if (typeVar.getUpperBound().getKind() != TypeKind.NULL) {
                    return getTypeSignature(typeVar.getUpperBound());
                } else {
                    throw new SignatureBuilderException("Can't handle typevar with no bound");
                }

            default:
                throw new SignatureBuilderException("Can't handle type of kind " + type.getKind());
        }
    }

    /**
     * Get the signature for an executable, either a method or a constructor.
     *
     * @param name "<init>" for  constructor, else the method name
     * @param method The executable element in question.
     */
    private String getExecutableSignature(CharSequence name, ExecutableElement method)
            throws SignatureBuilderException {
        StringBuilder sig = new StringBuilder();
        sig.append(getClassSignature((TypeElement) method.getEnclosingElement()))
                .append("->")
                .append(name)
                .append("(");
        for (VariableElement param : method.getParameters()) {
            sig.append(getTypeSignature(param.asType()));
        }
        sig.append(")")
                .append(getTypeSignature(method.getReturnType()));
        return sig.toString();
    }

    private String buildMethodSignature(ExecutableElement method) throws SignatureBuilderException {
        return getExecutableSignature(method.getSimpleName(), method);
    }

    private String buildConstructorSignature(ExecutableElement cons)
            throws SignatureBuilderException {
        return getExecutableSignature("<init>", cons);
    }

    private String buildFieldSignature(VariableElement field) throws SignatureBuilderException {
        StringBuilder sig = new StringBuilder();
        sig.append(getClassSignature((TypeElement) field.getEnclosingElement()))
                .append("->")
                .append(field.getSimpleName())
                .append(":")
                .append(getTypeSignature(field.asType()))
        ;
        return sig.toString();
    }

    public String buildSignature(Element element) {
        UnsupportedAppUsage uba = element.getAnnotation(UnsupportedAppUsage.class);
        try {
            String signature;
            switch (element.getKind()) {
                case METHOD:
                    signature = buildMethodSignature((ExecutableElement) element);
                    break;
                case CONSTRUCTOR:
                    signature = buildConstructorSignature((ExecutableElement) element);
                    break;
                case FIELD:
                    signature = buildFieldSignature((VariableElement) element);
                    break;
                default:
                    return null;
            }
            // if we have an expected signature on the annotation, warn if it doesn't match.
            if (!Strings.isNullOrEmpty(uba.expectedSignature())) {
                if (!signature.equals(uba.expectedSignature())) {
                    mMessager.printMessage(
                            WARNING,
                            String.format("Expected signature doesn't match generated signature.\n"
                                            + " Expected:  %s\n Generated: %s",
                                    uba.expectedSignature(), signature),
                            element);
                }
            }
            return signature;
        } catch (SignatureBuilderException problem) {
            problem.report(element);
            return null;
        }
    }
}
