package com.android.databinding.annotationprocessor;

import com.android.databinding.CompilerChef;
import com.android.databinding.writer.AnnotationJavaFileWriter;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;

import android.binding.BinderBundle;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

@SupportedAnnotationTypes({"android.binding.BinderBundle"})
@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class ProcessExpressions extends AbstractProcessor {
    private boolean mGenerationComplete;
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (mGenerationComplete) {
            return false;
        }

        String binderBundle64 = null;
        for (Element element : roundEnv.getElementsAnnotatedWith(BinderBundle.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "BinderBundle associated with wrong type. Should be a class.", element);
                continue;
            }
            TypeElement binderBundleClass = (TypeElement) element;
            if (!"BinderInfo".equals(binderBundleClass.getQualifiedName().toString())) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Only a generated class may use @BinderBundle attribute.", element);
                continue;
            }
            BinderBundle binderBundle = binderBundleClass.getAnnotation(BinderBundle.class);
            binderBundle64 = binderBundle.value();
        }

        if (binderBundle64 != null) {
            ByteArrayInputStream in = null;
            try {
                byte[] buf = Base64.decodeBase64(binderBundle64);
                in = new ByteArrayInputStream(buf);
                AnnotationJavaFileWriter annotationJavaFileWriter =
                        new AnnotationJavaFileWriter(processingEnv);
                CompilerChef compilerChef = CompilerChef.createChef(in, annotationJavaFileWriter);
                if (compilerChef.hasAnythingToGenerate()) {
                    compilerChef.writeViewBinders();
                    compilerChef.writeDbrFile();
                }
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Could not generate Binders from binder data store. " +
                                e.getLocalizedMessage());
            } catch (ClassNotFoundException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Error generating Binders from binder data store. " +
                                e.getLocalizedMessage());
            } finally {
                if (in != null) {
                    IOUtils.closeQuietly(in);
                }
            }
        }
        mGenerationComplete = true;
        return true;
    }
}
