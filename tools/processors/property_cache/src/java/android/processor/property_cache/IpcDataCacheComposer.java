/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.processor.property_cache;

import java.io.IOException;
import java.io.Writer;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

public class IpcDataCacheComposer {

    private static final String PROPERTY_DEFINITION_LINE = "private %s%s %s;\n";
    private static final String METHOD_NAME_LINE = "\npublic %s%s %s(%s%s%s\n) {\n";
    private static final String RETURN_IF_NOT_NULL_LINE =
            "if (%s != null) {\n   return %s.%s;\n  }";

    private CacheConfig mCacheConfig;

    /**
     * Generates code for property cache.
     *
     * @param writer       writer to write code to.
     * @param classElement class element to generate code for.
     * @param method       method element to generate code for.
     * @throws IOException if writer throws IOException.
     */
    public void generatePropertyCache(Writer writer, TypeElement classElement,
            ExecutableElement method) throws IOException {

        mCacheConfig = new CacheConfig(classElement, method);

        ParamComposer inputParam = new ParamComposer(null, null);
        ParamComposer binderParam = new ParamComposer(
                String.format("IpcDataCache.RemoteCall<%s, %s>", mCacheConfig.getInputType(),
                        mCacheConfig.getResultType()), "binderCall");

        ParamComposer bypassParam = new ParamComposer(null, null); // empty if method have no params
        String queryCall = "query(null)";
        if (mCacheConfig.getNumberOfParams() > 0) {
            bypassParam = new ParamComposer(
                    String.format("IpcDataCache.BypassCall<%s> ", mCacheConfig.getInputType()),
                    "bypassPredicate");
            inputParam = new ParamComposer(mCacheConfig.getInputType(), "query");
            queryCall = "query(query)";
        }
        String propertyClass =
                "IpcDataCache<" + mCacheConfig.getInputType() + ", " + mCacheConfig.getResultType()
                        + ">";
        String invalidateName = "invalidate" + mCacheConfig.getPropertyName();
        String lockObject = mCacheConfig.getPropertyVariable() + "Lock";
        writer.write("private " + mCacheConfig.getModifiers().getStaticModifier() + "final Object "
                + lockObject + " = new Object();\n");
        writer.write(String.format(PROPERTY_DEFINITION_LINE,
                mCacheConfig.getModifiers().getStaticModifier(), propertyClass,
                mCacheConfig.getPropertyVariable()));

        writer.write(propertyInvalidatedCacheMethod(binderParam, bypassParam, inputParam, queryCall,
                lockObject));

        // If binder param is not empty then generate getter without binder param to be called
        if (!bypassParam.getParam().isEmpty()) {
            writer.write(propertyInvalidatedCacheMethod(binderParam, new ParamComposer(null, null),
                    inputParam, queryCall, lockObject));
        }
        writer.write(String.format(Constants.METHOD_COMMENT,
                "- invalidate cache for {@link  " + mCacheConfig.getQualifiedName() + "#"
                        + mCacheConfig.getMethodName() + "}"));
        writer.write("\n public static final void " + invalidateName + "() {");
        writer.write(
                "\n     IpcDataCache.invalidateCache(\"" + mCacheConfig.getModuleName() + "\", \""
                        + mCacheConfig.getApiName() + "\");");
        writer.write("\n }");
        writer.write("\n");
        writer.write("\n");
    }

    /**
     * Generates code to call cache invalidation.
     *
     * @return code string calling cache invalidation.
     */
    public String generateInvalidatePropertyCall() {
        String invalidateName = "invalidate" + mCacheConfig.getPropertyName();
        return mCacheConfig.getClassName() + "Cache." + invalidateName + "();";
    }

    /**
     * Generates code for getter that returns cached value or calls binder and caches result.
     *
     * @param binderParam parameter for binder call.
     * @param bypassParam parameter for bypass predicate.
     * @param inputParam  parameter for input value.
     * @param queryCall   cache query call syntax.
     * @param lockObject  object to synchronize on.
     * @return String with code for method.
     */
    private String propertyInvalidatedCacheMethod(ParamComposer binderParam,
            ParamComposer bypassParam, ParamComposer inputParam, String queryCall,
            String lockObject) {
        String result = "\n";
        CacheModifiers modifiers = mCacheConfig.getModifiers();
        String paramsComments = binderParam.getParamComment(
                "lambda for remote call" + " {@link  " + mCacheConfig.getQualifiedName() + "#"
                        + mCacheConfig.getMethodName() + " }") + bypassParam.getParamComment(
                "lambda to bypass remote call") + inputParam.getParamComment(
                "parameter to call remote lambda");
        result += String.format(Constants.METHOD_COMMENT, paramsComments);
        result += String.format(METHOD_NAME_LINE, modifiers.getStaticModifier(),
                mCacheConfig.getResultType(), mCacheConfig.getMethodName(),
                binderParam.getParam(), bypassParam.getNextParam(),
                inputParam.getNextParam());
        result += String.format(RETURN_IF_NOT_NULL_LINE, mCacheConfig.getPropertyVariable(),
                mCacheConfig.getPropertyVariable(), queryCall);
        result += "\n  synchronized (" + lockObject + " ) {";
        result += "\n    if (" + mCacheConfig.getPropertyVariable() + " == null) {";
        result += "\n      " + mCacheConfig.getPropertyVariable() + " = new IpcDataCache" + "("
                + generateCreateIpcConfig() + ", " + binderParam.getName()
                + bypassParam.getNextName() + ");\n";
        result += "\n   }";
        result += "\n  }";
        result += "\n  return " + mCacheConfig.getPropertyVariable() + "." + queryCall + ";";
        result += "\n }";
        result += "\n";
        return result;
    }

    /**
     * Generates code for new IpcDataCache.Config object for given configuration.
     *
     * @return String with code for new IpcDataCache.Config object.
     */
    public String generateCreateIpcConfig() {
        return "new IpcDataCache.Config(" + mCacheConfig.getMaxSize() + ", " + "\""
                + mCacheConfig.getModuleName() + "\"" + ", " + "\"" + mCacheConfig.getApiName()
                + "\"" + ", " + "\"" + mCacheConfig.getPropertyName() + "\"" + ")";
    }
}
