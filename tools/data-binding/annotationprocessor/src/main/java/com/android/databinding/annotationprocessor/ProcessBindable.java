package com.android.databinding.annotationprocessor;

import com.android.databinding.reflection.ModelAnalyzer;

import org.apache.commons.io.IOUtils;

import android.binding.Bindable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.Writer;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

@SupportedAnnotationTypes({"android.binding.Bindable"})
@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class ProcessBindable extends AbstractProcessor {

    private boolean mFileGenerated;

    public ProcessBindable() {
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        ModelAnalyzer.setProcessingEnvironment(processingEnv);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (mFileGenerated) {
            return false;
        }
        Intermediate properties = readIntermediateFile();
        for (Element element : roundEnv.getElementsAnnotatedWith(Bindable.class)) {
            TypeElement enclosing = (TypeElement) element.getEnclosingElement();
            properties.cleanProperties(enclosing.getQualifiedName().toString());
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(Bindable.class)) {
            TypeElement enclosing = (TypeElement) element.getEnclosingElement();
            String name = getPropertyName(element);
            if (name != null) {
                properties.addProperty(enclosing.getQualifiedName().toString(), name);
            }
        }
        writeIntermediateFile(properties);
        generateBR(properties);
        mFileGenerated = true;
        return true;
    }

    private void generateBR(Intermediate intermediate) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "************* Generating BR file from Bindable attributes");
        HashSet<String> properties = new HashSet<>();
        intermediate.captureProperties(properties);
        mergeClassPathResources(properties);
        try {
            ArrayList<String> sortedProperties = new ArrayList<String>();
            sortedProperties.addAll(properties);
            Collections.sort(sortedProperties);

            JavaFileObject fileObject = processingEnv.getFiler()
                    .createSourceFile("android.binding.BR");
            Writer writer = fileObject.openWriter();
            writer.write("package android.binding;\n\n" +
                            "public final class BR {\n" +
                            "    public static final int _all = 0;\n"
            );
            int id = 0;
            for (String property : sortedProperties) {
                id++;
                writer.write("    public static final int " + property + " = " + id + ";\n");
            }
            writer.write("    public static int getId(String key) {\n");
            writer.write("        switch(key) {\n");
            id = 0;
            for (String property : sortedProperties) {
                id++;
                writer.write("            case \"" + property + "\": return " + id + ";\n");
            }
            writer.write("        }\n");
            writer.write("        return -1;\n");
            writer.write("    }");
            writer.write("}\n");

            writer.close();
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Could not generate BR file " + e.getLocalizedMessage());
        }
    }

    private String getPropertyName(Element element) {
        switch (element.getKind()) {
            case FIELD:
                return stripPrefixFromField((VariableElement) element);
            case METHOD:
                return stripPrefixFromMethod((ExecutableElement) element);
            default:
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@Bindable is not allowed on " + element.getKind(), element);
                return null;
        }
    }

    private static String stripPrefixFromField(VariableElement element) {
        Name name = element.getSimpleName();
        if (name.length() >= 2) {
            char firstChar = name.charAt(0);
            char secondChar = name.charAt(1);
            if (name.length() > 2 && firstChar == 'm' && secondChar == '_') {
                char thirdChar = name.charAt(2);
                if (Character.isJavaIdentifierStart(thirdChar)) {
                    return "" + Character.toLowerCase(thirdChar) +
                            name.subSequence(3, name.length());
                }
            } else if ((firstChar == 'm' && Character.isUpperCase(secondChar)) ||
                    (firstChar == '_' && Character.isJavaIdentifierStart(secondChar))) {
                return "" + Character.toLowerCase(secondChar) + name.subSequence(2, name.length());
            }
        }
        return name.toString();
    }

    private String stripPrefixFromMethod(ExecutableElement element) {
        Name name = element.getSimpleName();
        CharSequence propertyName;
        if (isGetter(element) || isSetter(element)) {
            propertyName = name.subSequence(3, name.length());
        } else if (isBooleanGetter(element)) {
            propertyName = name.subSequence(2, name.length());
        } else {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@Bindable associated with method must follow JavaBeans convention", element);
            return null;
        }
        char firstChar = propertyName.charAt(0);
        return "" + Character.toLowerCase(firstChar) +
                propertyName.subSequence(1, propertyName.length());
    }

    private static boolean prefixes(CharSequence sequence, String prefix) {
        boolean prefixes = false;
        if (sequence.length() > prefix.length()) {
            int count = prefix.length();
            prefixes = true;
            for (int i = 0; i < count; i++) {
                if (sequence.charAt(i) != prefix.charAt(i)) {
                    prefixes = false;
                    break;
                }
            }
        }
        return prefixes;
    }

    private static boolean isGetter(ExecutableElement element) {
        Name name = element.getSimpleName();
        return prefixes(name, "get") &&
                Character.isJavaIdentifierStart(name.charAt(3)) &&
                element.getParameters().isEmpty() &&
                element.getReturnType().getKind() != TypeKind.VOID;
    }

    private static boolean isSetter(ExecutableElement element) {
        Name name = element.getSimpleName();
        return prefixes(name, "set") &&
                Character.isJavaIdentifierStart(name.charAt(3)) &&
                element.getParameters().size() == 1 &&
                element.getReturnType().getKind() == TypeKind.VOID;
    }

    private static boolean isBooleanGetter(ExecutableElement element) {
        Name name = element.getSimpleName();
        return prefixes(name, "is") &&
                Character.isJavaIdentifierStart(name.charAt(2)) &&
                element.getParameters().isEmpty() &&
                element.getReturnType().getKind() == TypeKind.BOOLEAN;
    }

    private Intermediate readIntermediateFile() {
        Intermediate properties = null;
        ObjectInputStream in = null;
        try {
            FileObject intermediate = processingEnv.getFiler()
                    .getResource(StandardLocation.CLASS_OUTPUT,
                            ProcessBindable.class.getPackage().getName(), "binding_properties.bin");
            if (new File(intermediate.getName()).exists()) {
                in = new ObjectInputStream(intermediate.openInputStream());
                properties = (Intermediate) in.readObject();
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Could not read Binding properties intermediate file: " +
                    e.getLocalizedMessage());
        } catch (ClassNotFoundException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Could not read Binding properties intermediate file: " +
                            e.getLocalizedMessage());
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (properties == null) {
            properties = new IntermediateV1();
        }
        return properties;
    }

    private void mergeClassPathResources(HashSet<String> intermediateProperties) {
        try {
            String resourcePath = ProcessBindable.class.getPackage().getName()
                    .replace('.', '/') + "/binding_properties.bin";
            Enumeration<URL> resources = getClass().getClassLoader()
                    .getResources(resourcePath);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        "Merging binding adapters from " + url);
                InputStream inputStream = null;
                try {
                    inputStream = url.openStream();
                    ObjectInputStream in = new ObjectInputStream(inputStream);
                    Intermediate properties = (Intermediate) in.readObject();
                    if (properties != null) {
                        properties.captureProperties(intermediateProperties);
                    }
                } catch (IOException e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Could not merge in Bindables from " + url + ": " +
                                    e.getLocalizedMessage());
                } catch (ClassNotFoundException e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Could not read Binding properties intermediate file: " +
                                    e.getLocalizedMessage());
                } finally {
                    IOUtils.closeQuietly(inputStream);
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Could not read Binding properties intermediate file: " +
                            e.getLocalizedMessage());
        }
    }

    private void writeIntermediateFile(Intermediate properties) {
        try {
            FileObject intermediate = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, ProcessBindable.class.getPackage().getName(),
                    "binding_properties.bin");
            ObjectOutputStream out = new ObjectOutputStream(intermediate.openOutputStream());
            out.writeObject(properties);
            out.close();
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Could not write to intermediate file: " + e.getLocalizedMessage());
        }
    }

    private interface Intermediate {
        void captureProperties(Set<String> properties);

        void cleanProperties(String className);

        void addProperty(String className, String propertyName);
    }

    private static class IntermediateV1 implements Serializable, Intermediate {
        private static final long serialVersionUID = 1L;

        private final HashMap<String, HashSet<String>> mProperties = new HashMap<>();

        @Override
        public void captureProperties(Set<String> properties) {
            for (HashSet<String> propertySet : mProperties.values()) {
                properties.addAll(propertySet);
            }
        }

        @Override
        public void cleanProperties(String className) {
            mProperties.remove(className);
        }

        @Override
        public void addProperty(String className, String propertyName) {
            HashSet<String> properties = mProperties.get(className);
            if (properties == null) {
                properties = new HashSet<>();
                mProperties.put(className, properties);
            }
            properties.add(propertyName);
        }
    }
}
