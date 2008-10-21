/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package java.beans;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Vector;
import org.apache.harmony.beans.internal.nls.Messages;

public class PropertyDescriptor extends FeatureDescriptor {
    private Method getter;

    private Method setter;

    private Class<?> propertyEditorClass;

    private boolean constrained;

    private boolean bound;

    public PropertyDescriptor(String propertyName, Class<?> beanClass, String getterName,
            String setterName) throws IntrospectionException {
        super();
        if (beanClass == null) {
            throw new IntrospectionException(Messages.getString("beans.03")); //$NON-NLS-1$
        }
        if (propertyName == null || propertyName.length() == 0) {
            throw new IntrospectionException(Messages.getString("beans.04")); //$NON-NLS-1$
        }
        this.setName(propertyName);
        this.setDisplayName(propertyName);
        if (setterName != null) {
            if (hasMethod(beanClass, setterName)) {
                setWriteMethod(beanClass, setterName);
            } else {
                throw new IntrospectionException(Messages.getString("beans.20")); //$NON-NLS-1$
            }
        }
        if (getterName != null) {
            if (hasMethod(beanClass, getterName)) {
                setReadMethod(beanClass, getterName);
            } else {
                throw new IntrospectionException(Messages.getString("beans.1F")); //$NON-NLS-1$
            }
        }
    }

    public PropertyDescriptor(String propertyName, Method getter, Method setter)
            throws IntrospectionException {
        super();
        if (propertyName == null || propertyName.length() == 0) {
            throw new IntrospectionException(Messages.getString("beans.04")); //$NON-NLS-1$
        }
        this.setName(propertyName);
        this.setDisplayName(propertyName);
        setWriteMethod(setter);
        setReadMethod(getter);
    }

    public PropertyDescriptor(String propertyName, Class<?> beanClass)
            throws IntrospectionException {
        String getterName;
        String setterName;
        if (beanClass == null) {
            throw new IntrospectionException(Messages.getString("beans.03")); //$NON-NLS-1$
        }
        if (propertyName == null || propertyName.length() == 0) {
            throw new IntrospectionException(Messages.getString("beans.04")); //$NON-NLS-1$
        }
        this.setName(propertyName);
        this.setDisplayName(propertyName);
        getterName = createDefaultMethodName(propertyName, "is"); //$NON-NLS-1$
        if (hasMethod(beanClass, getterName)) {
            setReadMethod(beanClass, getterName);
        } else {
            getterName = createDefaultMethodName(propertyName, "get"); //$NON-NLS-1$
            if (hasMethod(beanClass, getterName)) {
                setReadMethod(beanClass, getterName);
            }
        }
        setterName = createDefaultMethodName(propertyName, "set"); //$NON-NLS-1$
        if (hasMethod(beanClass, setterName)) {
            setWriteMethod(beanClass, setterName);
        }
        if (getter == null && setter == null) {
            throw new IntrospectionException(Messages.getString("beans.01", propertyName)); //$NON-NLS-1$
        }
    }

    public void setWriteMethod(Method setter) throws IntrospectionException {
        if (setter != null) {
            int modifiers = setter.getModifiers();
            if (!Modifier.isPublic(modifiers)) {
                throw new IntrospectionException(Messages.getString("beans.05")); //$NON-NLS-1$
            }
            Class<?>[] parameterTypes = setter.getParameterTypes();
            if (parameterTypes.length != 1) {
                throw new IntrospectionException(Messages.getString("beans.06")); //$NON-NLS-1$
            }
            Class<?> parameterType = parameterTypes[0];
            Class<?> propertyType = getPropertyType();
            if (propertyType != null && !propertyType.equals(parameterType)) {
                throw new IntrospectionException(Messages.getString("beans.07")); //$NON-NLS-1$
            }
        }
        this.setter = setter;
    }

    public void setReadMethod(Method getter) throws IntrospectionException {
        if (getter != null) {
            int modifiers = getter.getModifiers();
            if (!Modifier.isPublic(modifiers)) {
                throw new IntrospectionException(Messages.getString("beans.0A")); //$NON-NLS-1$
            }
            Class<?>[] parameterTypes = getter.getParameterTypes();
            if (parameterTypes.length != 0) {
                throw new IntrospectionException(Messages.getString("beans.08")); //$NON-NLS-1$
            }
            Class<?> returnType = getter.getReturnType();
            if (returnType.equals(Void.TYPE)) {
                throw new IntrospectionException(Messages.getString("beans.33")); //$NON-NLS-1$
            }
            Class<?> propertyType = getPropertyType();
            if ((propertyType != null) && !returnType.equals(propertyType)) {
                throw new IntrospectionException(Messages.getString("beans.09")); //$NON-NLS-1$
            }
        }
        this.getter = getter;
    }

    public Method getWriteMethod() {
        return setter;
    }

    public Method getReadMethod() {
        return getter;
    }

    @Override
    public boolean equals(Object object) {
        boolean result = (object != null && object instanceof PropertyDescriptor);
        if (result) {
            PropertyDescriptor pd = (PropertyDescriptor) object;
            boolean gettersAreEqual = (this.getter == null) && (pd.getReadMethod() == null)
                    || (this.getter != null) && (this.getter.equals(pd.getReadMethod()));
            boolean settersAreEqual = (this.setter == null) && (pd.getWriteMethod() == null)
                    || (this.setter != null) && (this.setter.equals(pd.getWriteMethod()));
            boolean propertyTypesAreEqual = this.getPropertyType() == pd.getPropertyType();
            boolean propertyEditorClassesAreEqual = this.getPropertyEditorClass() == pd
                    .getPropertyEditorClass();
            boolean boundPropertyAreEqual = this.isBound() == pd.isBound();
            boolean constrainedPropertyAreEqual = this.isConstrained() == pd.isConstrained();
            result = gettersAreEqual && settersAreEqual && propertyTypesAreEqual
                    && propertyEditorClassesAreEqual && boundPropertyAreEqual
                    && constrainedPropertyAreEqual;
        }
        return result;
    }

    public void setPropertyEditorClass(Class<?> propertyEditorClass) {
        this.propertyEditorClass = propertyEditorClass;
    }

    public Class<?> getPropertyType() {
        Class<?> result = null;
        if (getter != null) {
            result = getter.getReturnType();
        } else if (setter != null) {
            Class<?>[] parameterTypes = setter.getParameterTypes();
            result = parameterTypes[0];
        }
        return result;
    }

    public Class<?> getPropertyEditorClass() {
        return propertyEditorClass;
    }

    public void setConstrained(boolean constrained) {
        this.constrained = constrained;
    }

    public void setBound(boolean bound) {
        this.bound = bound;
    }

    public boolean isConstrained() {
        return constrained;
    }

    public boolean isBound() {
        return bound;
    }

    boolean hasMethod(Class<?> beanClass, String methodName) {
        Method[] methods = findMethods(beanClass, methodName);
        return (methods.length > 0);
    }

    String createDefaultMethodName(String propertyName, String prefix) {
        String result = null;
        if (propertyName != null) {
            String bos = propertyName.substring(0, 1).toUpperCase();
            String eos = propertyName.substring(1, propertyName.length());
            result = prefix + bos + eos;
        }
        return result;
    }

    Method[] findMethods(Class<?> aClass, String methodName) {
        Method[] allMethods = aClass.getMethods();
        Vector<Method> matchedMethods = new Vector<Method>();
        Method[] result;
        for (Method method : allMethods) {
            if (method.getName().equals(methodName)) {
                matchedMethods.add(method);
            }
        }
        result = new Method[matchedMethods.size()];
        for (int j = 0; j < matchedMethods.size(); ++j) {
            result[j] = matchedMethods.elementAt(j);
        }
        return result;
    }

    void setReadMethod(Class<?> beanClass, String getterName) {
        boolean result = false;
        Method[] getters = findMethods(beanClass, getterName);
        for (Method element : getters) {
            try {
                setReadMethod(element);
                result = true;
            } catch (IntrospectionException ie) {
            }
            if (result) {
                break;
            }
        }
    }

    void setWriteMethod(Class<?> beanClass, String setterName) throws IntrospectionException {
        boolean result = false;
        Method[] setters = findMethods(beanClass, setterName);
        for (Method element : setters) {
            try {
                setWriteMethod(element);
                result = true;
            } catch (IntrospectionException ie) {
            }
            if (result) {
                break;
            }
        }
    }

    public PropertyEditor createPropertyEditor(Object bean) {
        PropertyEditor editor;
        if (propertyEditorClass == null) {
            return null;
        }
        if (!PropertyEditor.class.isAssignableFrom(propertyEditorClass)) {
            // beans.48=Property editor is not assignable from the
            // PropertyEditor interface
            throw new ClassCastException(Messages.getString("beans.48")); //$NON-NLS-1$
        }
        try {
            Constructor<?> constr;
            try {
                // try to look for the constructor with single Object argument
                constr = propertyEditorClass.getConstructor(Object.class);
                editor = (PropertyEditor) constr.newInstance(bean);
            } catch (NoSuchMethodException e) {
                // try no-argument constructor
                constr = propertyEditorClass.getConstructor();
                editor = (PropertyEditor) constr.newInstance();
            }
        } catch (Exception e) {
            // beans.47=Unable to instantiate property editor
            RuntimeException re = new RuntimeException(Messages.getString("beans.47"), e); //$NON-NLS-1$
            throw re;
        }
        return editor;
    }
}
