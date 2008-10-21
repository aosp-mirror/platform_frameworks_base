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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.apache.harmony.beans.internal.nls.Messages;

public class IndexedPropertyDescriptor extends PropertyDescriptor {
    private Method indexedGetter;

    private Method indexedSetter;

    public IndexedPropertyDescriptor(String propertyName, Class<?> beanClass,
            String getterName, String setterName, String indexedGetterName,
            String indexedSetterName) throws IntrospectionException {
        super(propertyName, beanClass, getterName, setterName);

        // RI behaves like this
        if (indexedGetterName == null && indexedSetterName == null &&
                (getterName != null || setterName != null)) {
            throw new IntrospectionException(Messages.getString("beans.50"));
        }
        setIndexedReadMethod(beanClass, indexedGetterName);
        setIndexedWriteMethod(beanClass, indexedSetterName);
    }

    public IndexedPropertyDescriptor(String propertyName, Method getter, Method setter,
            Method indexedGetter, Method indexedSetter) throws IntrospectionException {
        super(propertyName, getter, setter);
        
        // we need this in order to be compatible with RI
        if (indexedGetter == null && indexedSetter == null &&
                (getter != null || setter != null)) {
            throw new IntrospectionException(Messages.getString("beans.50"));
        }
        setIndexedReadMethod(indexedGetter);
        setIndexedWriteMethod(indexedSetter);
    }

    public IndexedPropertyDescriptor(String propertyName, Class<?> beanClass)
            throws IntrospectionException {
        super(propertyName, beanClass, null, null);
        String getterName;
        String setterName;
        String indexedGetterName;
        String indexedSetterName;

        // array getter
        getterName = createDefaultMethodName(propertyName, "get"); //$NON-NLS-1$
        if (hasMethod(beanClass, getterName)) {
            setReadMethod(beanClass, getterName);
        }
        // array setter
        setterName = createDefaultMethodName(propertyName, "set"); //$NON-NLS-1$
        if (hasMethod(beanClass, setterName)) {
            setWriteMethod(beanClass, setterName);
        }
        // indexed getter
        indexedGetterName = createDefaultMethodName(propertyName, "get"); //$NON-NLS-1$
        if (hasMethod(beanClass, indexedGetterName)) {
            setIndexedReadMethod(beanClass, indexedGetterName);
        }
        // indexed setter
        indexedSetterName = createDefaultMethodName(propertyName, "set"); //$NON-NLS-1$
        if (hasMethod(beanClass, indexedSetterName)) {
            setIndexedWriteMethod(beanClass, indexedSetterName);
        }
        // RI seems to behave a bit differently
        if (indexedGetter == null && indexedSetter == null &&
                getReadMethod() == null && getWriteMethod() == null) {
            throw new IntrospectionException(
                    Messages.getString("beans.01", propertyName)); //$NON-NLS-1$
        }
        if (indexedGetter == null && indexedSetter == null) {
            // not an indexed property indeed
            throw new IntrospectionException(Messages.getString("beans.50"));
        }
    }

    public void setIndexedReadMethod(Method indexedGetter) throws IntrospectionException {
        if (indexedGetter != null) {
            int modifiers = indexedGetter.getModifiers();
            Class<?>[] parameterTypes;
            Class<?> returnType;
            Class<?> indexedPropertyType;

            if (!Modifier.isPublic(modifiers)) {
                throw new IntrospectionException(Messages.getString("beans.21")); //$NON-NLS-1$
            }
            parameterTypes = indexedGetter.getParameterTypes();
            if (parameterTypes.length != 1) {
                throw new IntrospectionException(Messages.getString("beans.22")); //$NON-NLS-1$
            }
            if (!parameterTypes[0].equals(int.class)) {
                throw new IntrospectionException(Messages.getString("beans.23")); //$NON-NLS-1$
            }
            returnType = indexedGetter.getReturnType();
            indexedPropertyType = getIndexedPropertyType();
            if ((indexedPropertyType != null) && !returnType.equals(indexedPropertyType)) {
                throw new IntrospectionException(Messages.getString("beans.24")); //$NON-NLS-1$
            }
        }
        this.indexedGetter = indexedGetter;
    }

    public void setIndexedWriteMethod(Method indexedSetter) throws IntrospectionException {
        if (indexedSetter != null) {
            int modifiers = indexedSetter.getModifiers();
            Class<?>[] parameterTypes;
            Class<?> firstParameterType;
            Class<?> secondParameterType;
            Class<?> propType;

            if (!Modifier.isPublic(modifiers)) {
                throw new IntrospectionException(Messages.getString("beans.25")); //$NON-NLS-1$
            }
            parameterTypes = indexedSetter.getParameterTypes();
            if (parameterTypes.length != 2) {
                throw new IntrospectionException(Messages.getString("beans.26")); //$NON-NLS-1$
            }
            firstParameterType = parameterTypes[0];
            if (!firstParameterType.equals(int.class)) {
                throw new IntrospectionException(Messages.getString("beans.27")); //$NON-NLS-1$
            }
            secondParameterType = parameterTypes[1];
            propType = getIndexedPropertyType();
            if (propType != null && !secondParameterType.equals(propType)) {
                throw new IntrospectionException(Messages.getString("beans.28")); //$NON-NLS-1$
            }
        }
        this.indexedSetter = indexedSetter;
    }

    public Method getIndexedWriteMethod() {
        return indexedSetter;
    }

    public Method getIndexedReadMethod() {
        return indexedGetter;
    }

    @Override
    public boolean equals(Object obj) {
        boolean result = super.equals(obj);
        
        if (result) {
            IndexedPropertyDescriptor pd = (IndexedPropertyDescriptor) obj;
    
            if (indexedGetter != null) {
                result = indexedGetter.equals(pd.getIndexedReadMethod());
            } else if (result && indexedGetter == null) {
                result = pd.getIndexedReadMethod() == null;
            }
                
            if (result) {
                if (indexedSetter != null) {
                    result = indexedSetter.equals(pd.getIndexedWriteMethod());
                } else if (indexedSetter == null) {
                    result = pd.getIndexedWriteMethod() == null;
                }
            }
        }
            
        return result;
    }

    public Class<?> getIndexedPropertyType() {
        Class<?> result = null;

        if (indexedGetter != null) {
            result = indexedGetter.getReturnType();
        } else if (indexedSetter != null) {
            Class<?>[] parameterTypes = indexedSetter.getParameterTypes();

            result = parameterTypes[1];
        }
        return result;
    }

    private void setIndexedReadMethod(Class<?> beanClass, String indexedGetterName) {
        Method[] getters = findMethods(beanClass, indexedGetterName);
        boolean result = false;

        for (Method element : getters) {
            try {
                setIndexedReadMethod(element);
                result = true;
            } catch (IntrospectionException ie) {}

            if (result) {
                break;
            }
        }
    }

    private void setIndexedWriteMethod(Class<?> beanClass, String indexedSetterName) {
        Method[] setters = findMethods(beanClass, indexedSetterName);
        boolean result = false;

        for (Method element : setters) {
            try {
                setIndexedWriteMethod(element);
                result = true;
            } catch (IntrospectionException ie) {}

            if (result) {
                break;
            }
        }
    }
}
