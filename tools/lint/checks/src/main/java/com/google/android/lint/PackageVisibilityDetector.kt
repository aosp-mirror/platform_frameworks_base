/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.google.android.lint

import com.android.tools.lint.client.api.UastParser
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.interprocedural.CallGraph
import com.android.tools.lint.detector.api.interprocedural.CallGraphResult
import com.android.tools.lint.detector.api.interprocedural.searchForPaths
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.visitor.AbstractUastVisitor
import java.util.LinkedList

/**
 * A lint checker to detect potential package visibility issues for system's APIs. APIs working
 * in the system_server and taking the package name as a parameter may have chance to reveal
 * package existence status on the device, and break the
 * <a href="https://developer.android.com/about/versions/11/privacy/package-visibility">
 * Package Visibility</a> that we introduced in Android 11.
 * <p>
 * Take an example of the API `boolean setFoo(String packageName)`, a malicious app may have chance
 * to detect package existence state on the device from the result of the API, if there is no
 * package visibility filtering rule or uid identify checks applying to the parameter of the
 * package name.
 */
class PackageVisibilityDetector : Detector(), SourceCodeScanner {

    // Enables call graph analysis
    override fun isCallGraphRequired(): Boolean = true

    override fun analyzeCallGraph(
        context: Context,
        callGraph: CallGraphResult
    ) {
        val systemServerApiNodes = callGraph.callGraph.nodes.filter(::isSystemServerApi)
        val sinkMethodNodes = callGraph.callGraph.nodes.filter {
            // TODO(b/228285232): Remove enforce permission sink methods
            isNodeInList(it, ENFORCE_PERMISSION_METHODS) || isNodeInList(it, APPOPS_METHODS)
        }
        val parser = context.client.getUastParser(context.project)
        analyzeApisContainPackageNameParameters(
            context, parser, systemServerApiNodes, sinkMethodNodes)
    }

    /**
     * Looking for API contains package name parameters, report the lint issue if the API does not
     * invoke any sink methods.
     */
    private fun analyzeApisContainPackageNameParameters(
        context: Context,
        parser: UastParser,
        systemServerApiNodes: List<CallGraph.Node>,
        sinkMethodNodes: List<CallGraph.Node>
    ) {
        for (apiNode in systemServerApiNodes) {
            val apiMethod = apiNode.getUMethod() ?: continue
            val pkgNameParamIndexes = apiMethod.uastParameters.mapIndexedNotNull { index, param ->
                if (Parameter(param) in PACKAGE_NAME_PATTERNS && apiNode.isArgumentInUse(index)) {
                    index
                } else {
                    null
                }
            }.takeIf(List<Int>::isNotEmpty) ?: continue

            for (pkgNameParamIndex in pkgNameParamIndexes) {
                // Trace the call path of the method's argument, pass the lint checks if a sink
                // method is found
                if (traceArgumentCallPath(
                        apiNode, pkgNameParamIndex, PACKAGE_NAME_SINK_METHOD_LIST)) {
                    continue
                }
                // Pass the check if one of the sink methods is invoked
                if (hasValidPath(
                        searchForPaths(
                            sources = listOf(apiNode),
                            isSink = { it in sinkMethodNodes },
                            getNeighbors = { node -> node.edges.map { it.node!! } }
                        )
                    )
                ) continue

                // Report issue
                val reportElement = apiMethod.uastParameters[pkgNameParamIndex] as UElement
                val location = parser.createLocation(reportElement)
                context.report(
                    ISSUE_PACKAGE_NAME_NO_PACKAGE_VISIBILITY_FILTERS,
                    location,
                    getMsgPackageNameNoPackageVisibilityFilters(apiMethod, pkgNameParamIndex)
                )
            }
        }
    }

    /**
     * Returns {@code true} if the method associated with the given node is a system server's
     * public API that extends from Stub class.
     */
    private fun isSystemServerApi(
        node: CallGraph.Node
    ): Boolean {
        val method = node.getUMethod() ?: return false
        if (!method.hasModifierProperty("public") ||
            method.uastBody == null ||
            method.containingClass is PsiAnonymousClass) {
            return false
        }
        val className = method.containingClass?.qualifiedName ?: return false
        if (!className.startsWith(SYSTEM_PACKAGE_PREFIX)) {
            return false
        }
        return (method.containingClass ?: return false).supers
            .filter { it.name == CLASS_STUB }
            .filter { it.qualifiedName !in BYPASS_STUBS }
            .any { it.findMethodBySignature(method, /* checkBases */ true) != null }
    }

    /**
     * Returns {@code true} if the list contains the node of the call graph.
     */
    private fun isNodeInList(
        node: CallGraph.Node,
        filters: List<Method>
    ): Boolean {
        val method = node.getUMethod() ?: return false
        return Method(method) in filters
    }

    /**
     * Trace the call paths of the argument of the method in the start entry. Return {@code true}
     * if one of methods in the sink call list is invoked.
     * Take an example of the call path:
     * foo(packageName) -> a(packageName) -> b(packageName) -> filterAppAccess()
     * It returns {@code true} if the filterAppAccess() is in the sink call list.
     */
    private fun traceArgumentCallPath(
        apiNode: CallGraph.Node,
        pkgNameParamIndex: Int,
        sinkList: List<Method>
    ): Boolean {
        val startEntry = TraceEntry(apiNode, pkgNameParamIndex)
        val traceQueue = LinkedList<TraceEntry>().apply { add(startEntry) }
        val allVisits = mutableSetOf<TraceEntry>().apply { add(startEntry) }
        while (!traceQueue.isEmpty()) {
            val entry = traceQueue.poll()
            val entryNode = entry.node
            val entryMethod = entryNode.getUMethod() ?: continue
            val entryArgumentName = entryMethod.uastParameters[entry.argumentIndex].name
            for (outEdge in entryNode.edges) {
                val outNode = outEdge.node ?: continue
                val outMethod = outNode.getUMethod() ?: continue
                val outArgumentIndex =
                    outEdge.call?.findArgumentIndex(
                        entryArgumentName, outMethod.uastParameters.size)
                val sinkMethod = findInSinkList(outMethod, sinkList)
                if (sinkMethod == null) {
                    if (outArgumentIndex == null) {
                        // Path is not relevant to the sink method and argument
                        continue
                    }
                    // Path is relevant to the argument, add a new trace entry if never visit before
                    val newEntry = TraceEntry(outNode, outArgumentIndex)
                    if (newEntry !in allVisits) {
                        traceQueue.add(newEntry)
                        allVisits.add(newEntry)
                    }
                    continue
                }
                if (sinkMethod.matchArgument && outArgumentIndex == null) {
                    // The sink call is required to match the argument, but not found
                    continue
                }
                if (sinkMethod.checkCaller &&
                    entryMethod.isInClearCallingIdentityScope(outEdge.call!!)) {
                    // The sink call is in the scope of Binder.clearCallingIdentify
                    continue
                }
                // A sink method is matched
                return true
            }
        }
        return false
    }

    /**
     * Returns the UMethod associated with the given node of call graph.
     */
    private fun CallGraph.Node.getUMethod(): UMethod? = this.target.element as? UMethod

    /**
     * Returns the system module name (e.g. com.android.server.pm) of the method of the
     * call graph node.
     */
    private fun CallGraph.Node.getModuleName(): String? {
        val method = getUMethod() ?: return null
        val className = method.containingClass?.qualifiedName ?: return null
        if (!className.startsWith(SYSTEM_PACKAGE_PREFIX)) {
            return null
        }
        val dotPos = className.indexOf(".", SYSTEM_PACKAGE_PREFIX.length)
        if (dotPos == -1) {
            return SYSTEM_PACKAGE_PREFIX
        }
        return className.substring(0, dotPos)
    }

    /**
     * Return {@code true} if the argument in the method's body is in-use.
     */
    private fun CallGraph.Node.isArgumentInUse(argIndex: Int): Boolean {
        val method = getUMethod() ?: return false
        val argumentName = method.uastParameters[argIndex].name
        var foundArg = false
        val methodVisitor = object : AbstractUastVisitor() {
            override fun visitSimpleNameReferenceExpression(
                node: USimpleNameReferenceExpression
            ): Boolean {
                if (node.identifier == argumentName) {
                    foundArg = true
                }
                return true
            }
        }
        method.uastBody?.accept(methodVisitor)
        return foundArg
    }

    /**
     * Given an argument name, returns the index of argument in the call expression.
     */
    private fun UCallExpression.findArgumentIndex(
        argumentName: String,
        parameterSize: Int
    ): Int? {
        if (valueArgumentCount == 0 || parameterSize == 0) {
            return null
        }
        var match = false
        val argVisitor = object : AbstractUastVisitor() {
            override fun visitSimpleNameReferenceExpression(
                node: USimpleNameReferenceExpression
            ): Boolean {
                if (node.identifier == argumentName) {
                    match = true
                }
                return true
            }
            override fun visitCallExpression(node: UCallExpression): Boolean {
                return true
            }
        }
        valueArguments.take(parameterSize).forEachIndexed { index, argument ->
            argument.accept(argVisitor)
            if (match) {
                return index
            }
        }
        return null
    }

    /**
     * Given a UMethod, returns a method from the sink method list.
     */
    private fun findInSinkList(
        uMethod: UMethod,
        sinkCallList: List<Method>
    ): Method? {
        return sinkCallList.find {
            it == Method(uMethod) ||
                    it == Method(uMethod.containingClass?.qualifiedName ?: "", "*")
        }
    }

    /**
     * Returns {@code true} if the call expression is in the scope of the
     * Binder.clearCallingIdentify.
     */
    private fun UMethod.isInClearCallingIdentityScope(call: UCallExpression): Boolean {
        var isInScope = false
        val methodVisitor = object : AbstractUastVisitor() {
            private var clearCallingIdentity = 0
            override fun visitCallExpression(node: UCallExpression): Boolean {
                if (call == node && clearCallingIdentity != 0) {
                    isInScope = true
                    return true
                }
                val visitMethod = Method(node.resolve() ?: return false)
                if (visitMethod == METHOD_CLEAR_CALLING_IDENTITY) {
                    clearCallingIdentity++
                } else if (visitMethod == METHOD_RESTORE_CALLING_IDENTITY) {
                    clearCallingIdentity--
                }
                return false
            }
        }
        accept(methodVisitor)
        return isInScope
    }

    /**
     * Checks the module name of the start node and the last node that invokes the sink method
     * (e.g. checkPermission) in a path, returns {@code true} if one of the paths has the same
     * module name for both nodes.
     */
    private fun hasValidPath(paths: Collection<List<CallGraph.Node>>): Boolean {
        for (pathNodes in paths) {
            if (pathNodes.size < VALID_CALL_PATH_NODES_SIZE) {
                continue
            }
            val startModule = pathNodes[0].getModuleName() ?: continue
            val lastCallModule = pathNodes[pathNodes.size - 2].getModuleName() ?: continue
            if (startModule == lastCallModule) {
                return true
            }
        }
        return false
    }

    /**
     * A data class to represent the method.
     */
    private data class Method(
        val clazz: String,
        val name: String
    ) {
        // Used by traceArgumentCallPath to indicate that the method is required to match the
        // argument name
        var matchArgument = true

        // Used by traceArgumentCallPath to indicate that the method is required to check whether
        // the Binder.clearCallingIdentity is invoked.
        var checkCaller = false

        constructor(
            clazz: String,
            name: String,
            matchArgument: Boolean = true,
            checkCaller: Boolean = false
        ): this(clazz, name) {
            this.matchArgument = matchArgument
            this.checkCaller = checkCaller
        }

        constructor(
            method: PsiMethod
        ): this(method.containingClass?.qualifiedName ?: "", method.name)
    }

    /**
     * A data class to represent the parameter of the method. The parameter name is converted to
     * lower case letters for comparison.
     */
    private data class Parameter private constructor(
        val typeName: String,
        val parameterName: String
    ) {
        constructor(uParameter: UParameter): this(
            uParameter.type.canonicalText,
            uParameter.name.lowercase()
        )

        companion object {
            fun create(typeName: String, parameterName: String) =
                Parameter(typeName, parameterName.lowercase())
        }
    }

    /**
     * A data class wraps a method node of the call graph and an index that indicates an
     * argument of the method to record call trace information.
     */
    private data class TraceEntry(
        val node: CallGraph.Node,
        val argumentIndex: Int
    )

    companion object {
        private const val SYSTEM_PACKAGE_PREFIX = "com.android.server."
        // A valid call path list needs to contain a start node and a sink node
        private const val VALID_CALL_PATH_NODES_SIZE = 2

        private const val CLASS_STUB = "Stub"
        private const val CLASS_STRING = "java.lang.String"
        private const val CLASS_PACKAGE_MANAGER = "android.content.pm.PackageManager"
        private const val CLASS_IPACKAGE_MANAGER = "android.content.pm.IPackageManager"
        private const val CLASS_APPOPS_MANAGER = "android.app.AppOpsManager"
        private const val CLASS_CONTEXT = "android.content.Context"
        private const val CLASS_BINDER = "android.os.Binder"
        private const val CLASS_PACKAGE_MANAGER_INTERNAL =
            "android.content.pm.PackageManagerInternal"
        private const val CLASS_ACTIVITY_MANAGER_SERVICE =
            "com.android.server.am.ActivityManagerService"
        private const val CLASS_ACTIVITY_MANAGER_INTERNAL =
            "android.app.ActivityManagerInternal"

        // Patterns of package name parameter
        private val PACKAGE_NAME_PATTERNS = setOf(
            Parameter.create(CLASS_STRING, "packageName"),
            Parameter.create(CLASS_STRING, "callingPackage"),
            Parameter.create(CLASS_STRING, "callingPackageName"),
            Parameter.create(CLASS_STRING, "pkgName"),
            Parameter.create(CLASS_STRING, "callingPkg"),
            Parameter.create(CLASS_STRING, "pkg")
        )

        // Package manager APIs
        private val PACKAGE_NAME_SINK_METHOD_LIST = listOf(
            Method(CLASS_PACKAGE_MANAGER_INTERNAL, "filterAppAccess", matchArgument = false),
            Method(CLASS_PACKAGE_MANAGER_INTERNAL, "canQueryPackage"),
            Method(CLASS_PACKAGE_MANAGER_INTERNAL, "isSameApp"),
            Method(CLASS_PACKAGE_MANAGER, "*", checkCaller = true),
            Method(CLASS_IPACKAGE_MANAGER, "*", checkCaller = true),
            Method(CLASS_PACKAGE_MANAGER, "getPackagesForUid", matchArgument = false),
            Method(CLASS_IPACKAGE_MANAGER, "getPackagesForUid", matchArgument = false)
        )

        // AppOps APIs which include uid and package visibility filters checks
        private val APPOPS_METHODS = listOf(
            Method(CLASS_APPOPS_MANAGER, "noteOp"),
            Method(CLASS_APPOPS_MANAGER, "noteOpNoThrow"),
            Method(CLASS_APPOPS_MANAGER, "noteOperation"),
            Method(CLASS_APPOPS_MANAGER, "noteProxyOp"),
            Method(CLASS_APPOPS_MANAGER, "noteProxyOpNoThrow"),
            Method(CLASS_APPOPS_MANAGER, "startOp"),
            Method(CLASS_APPOPS_MANAGER, "startOpNoThrow"),
            Method(CLASS_APPOPS_MANAGER, "FinishOp"),
            Method(CLASS_APPOPS_MANAGER, "finishProxyOp"),
            Method(CLASS_APPOPS_MANAGER, "checkPackage")
        )

        // Enforce permission APIs
        private val ENFORCE_PERMISSION_METHODS = listOf(
            Method(CLASS_CONTEXT, "checkPermission"),
            Method(CLASS_CONTEXT, "checkCallingPermission"),
            Method(CLASS_CONTEXT, "checkCallingOrSelfPermission"),
            Method(CLASS_CONTEXT, "enforcePermission"),
            Method(CLASS_CONTEXT, "enforceCallingPermission"),
            Method(CLASS_CONTEXT, "enforceCallingOrSelfPermission"),
            Method(CLASS_ACTIVITY_MANAGER_SERVICE, "checkPermission"),
            Method(CLASS_ACTIVITY_MANAGER_INTERNAL, "enforceCallingPermission")
        )

        private val BYPASS_STUBS = listOf(
            "android.content.pm.IPackageDataObserver.Stub",
            "android.content.pm.IPackageDeleteObserver.Stub",
            "android.content.pm.IPackageDeleteObserver2.Stub",
            "android.content.pm.IPackageInstallObserver2.Stub",
            "com.android.internal.app.IAppOpsCallback.Stub",

            // TODO(b/228285637): Do not bypass PackageManagerService API
            "android.content.pm.IPackageManager.Stub",
            "android.content.pm.IPackageManagerNative.Stub"
        )

        private val METHOD_CLEAR_CALLING_IDENTITY =
            Method(CLASS_BINDER, "clearCallingIdentity")
        private val METHOD_RESTORE_CALLING_IDENTITY =
            Method(CLASS_BINDER, "restoreCallingIdentity")

        private fun getMsgPackageNameNoPackageVisibilityFilters(
            method: UMethod,
            argumentIndex: Int
        ): String = "Api: ${method.name} contains a package name parameter: " +
                "${method.uastParameters[argumentIndex].name} does not apply " +
                "package visibility filtering rules."

        private val EXPLANATION = """
            APIs working in the system_server and taking the package name as a parameter may have
            chance to reveal package existence status on the device, and break the package
            visibility that we introduced in Android 11.
            (https://developer.android.com/about/versions/11/privacy/package-visibility)

            Take an example of the API `boolean setFoo(String packageName)`, a malicious app may
            have chance to get package existence state on the device from the result of the API,
            if there is no package visibility filtering rule or uid identify checks applying to
            the parameter of the package name.

            To resolve it, you could apply package visibility filtering rules to the package name
            via PackageManagerInternal.filterAppAccess API, before starting to use the package name.
            If the parameter is a calling package name, use the PackageManager API such as
            PackageManager.getPackagesForUid to verify the calling identify.
            """

        val ISSUE_PACKAGE_NAME_NO_PACKAGE_VISIBILITY_FILTERS = Issue.create(
            id = "ApiMightLeakAppVisibility",
            briefDescription = "Api takes package name parameter doesn't apply " +
                    "package visibility filters",
            explanation = EXPLANATION,
            category = Category.SECURITY,
            priority = 1,
            severity = Severity.WARNING,
            implementation = Implementation(
                PackageVisibilityDetector::class.java,
                Scope.JAVA_FILE_SCOPE
            )
        )
    }
}
