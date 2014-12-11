package com.android.databinding

import org.w3c.dom.Document
import org.w3c.dom.NamedNodeMap
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.SAXException

import java.io.File
import java.io.FilenameFilter
import java.io.IOException
import java.util.ArrayList

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathExpression
import javax.xml.xpath.XPathExpressionException
import javax.xml.xpath.XPathFactory
import kotlin.properties.Delegates
import java.util.HashMap

import com.android.databinding.parser.ExpressionParser
import com.android.databinding.renderer.BrRenderer
import com.android.databinding.renderer.DataBinderRenderer
import com.android.databinding.renderer.AttrRenderer
import com.android.databinding.renderer.ViewExprBinderRenderer
import com.android.databinding.util.ClassAnalyzer
import com.android.databinding.vo.LayoutExprBinding
import com.android.databinding.vo.VariableScope
import com.android.databinding.vo.VariableDefinition
import com.android.databinding.vo.BindingTarget



public class KLayoutParser(val appPkg : String, val resourceFolders : kotlin.Iterable<File>,
        val outputBaseDir : File, val outputResBaseDir : File) {
    var br : BrRenderer by Delegates.notNull()
    var dbr : DataBinderRenderer by Delegates.notNull()
    var styler : AttrRenderer by Delegates.notNull()
    val viewExprBinderRenderers = arrayListOf<ViewExprBinderRenderer>()
    public var classAnalyzer : ClassAnalyzer by Delegates.notNull()

    val outputResDir by Delegates.lazy { File(outputResBaseDir, "values") }

    val outputDir by Delegates.lazy {
        File(outputBaseDir.getAbsolutePath() + "/" + appPkg.replace('.','/') + "/generated")
    }

    val dbrOutputDir by Delegates.lazy {
        File(outputBaseDir.getAbsolutePath() + "/" + dbr.pkg.replace('.','/'))
    }

    public fun analyzeClasses() {
        viewExprBinderRenderers.forEach {
            it.lb.analyzeVariables()
        }
    }

    fun log (s : String) = System.out.println("LOG:$s");

    public fun process() {
        val xmlFiles = ArrayList<File>()
        resourceFolders.filter({it.exists()}).forEach {
            findLayoutFolders(it).forEach {
                findXmlFiles(it, xmlFiles)
            }
        }
        //viewBinderRenderers.clear()
        for (xml in xmlFiles) {
            log("xmlFile $xml")
            val exprBinding = parseXml3(xml)
            if (exprBinding == null) {
                log("no bindings in $xml, skipping")
                continue
            }
            val vebr = ViewExprBinderRenderer("$appPkg.generated", appPkg, "${toClassName(xml.name)}Binder", toLayoutId(xml.name), exprBinding)
            viewExprBinderRenderers.add(vebr)
        }
        br = BrRenderer("$appPkg.generated", "BR" ,viewExprBinderRenderers)
        dbr = DataBinderRenderer("com.android.databinding.library", appPkg,
                "GeneratedDataBinderRenderer", viewExprBinderRenderers)
        styler = AttrRenderer(viewExprBinderRenderers)
    }

    public fun writeAttrFile() {
        outputResDir.mkdirs()
        writeToFile(File(outputResDir, "bindingattrs.xml"), styler.render())
    }

    public fun writeDbrFile() : Unit = writeDbrFile(dbrOutputDir)
    public fun writeDbrFile(dir : File) : Unit {
        dir.mkdirs()
        writeToFile(File(dir, "${dbr.className}.java"), dbr.render(br))
    }

    public fun writeViewBinderInterfaces() : Unit = writeViewBinderInterfaces(outputDir)

    public fun writeViewBinderInterfaces(dir : File) : Unit {
        dir.mkdirs()
        viewExprBinderRenderers.forEach({
            writeToFile(File(dir, "${it.interfaceName}.java"), it.renderInterface(br))
        })
    }

    public fun writeBrFile() : Unit = writeBrFile(outputDir)

    public fun writeBrFile(dir : File) : Unit {
        dir.mkdirs()
        writeToFile(File(dir, "${br.className}.java"), br.render())
    }
    public fun writeViewBinders() : Unit = writeViewBinders(outputDir)
    public fun writeViewBinders(dir : File) : Unit {
        dir.mkdirs()
//        viewBinderRenderers.forEach({
//            writeToFile(File(dir, "${it.className}.java"), it.render(br))
//        })
        viewExprBinderRenderers.forEach({
            writeToFile(File(dir, "${it.className}.java"), it.render(br))
        })
    }

    private fun writeToFile(file : File, contents : String) {
        System.out.println("output file: ${file.getAbsolutePath()}")
        file.writeText(contents)
    }

    private fun toClassName(name:String) : String =
        name.substring(0, name.indexOf(".")).split("_").map { "${it.substring(0,1).toUpperCase()}${it.substring(1)}" }.join("")

    private fun toLayoutId(name:String) : String =
            name.substring(0, name.indexOf("."))

    private fun parseXml3(xml: File) : LayoutExprBinding? {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(xml)
        val xPathFactory = XPathFactory.newInstance()
        val xPath = xPathFactory.newXPath()
        //
        val layoutBinding = LayoutExprBinding(doc)
        val exprParser = ExpressionParser()
        val varsExprs = xPath.compile("//@*[starts-with(name(), 'bind_var')]/..")
        val varNodes = varsExprs.evaluate(doc, XPathConstants.NODESET) as NodeList

        System.out.println("var node count" + varNodes.getLength())
        for (i in 0..varNodes.getLength() - 1) {
            val item = varNodes.item(i)
            System.out.println("checking variable node $item")
            val attributes = item.getAttributes()
            val variableScope = VariableScope(item)
            val attrCount = attributes.getLength()
            for (j in 0..(attrCount - 1)) {
                val attr = attributes.item(j)
                val name = attr.getNodeName()
                if (name.startsWith("bind_var:")) {
                    variableScope.variables.add(VariableDefinition(name.substring("bind_var:".length), attr.getNodeValue()))
                }
            }
            layoutBinding.addVariableScope(variableScope)
        }

        val expr = xPath.compile("//@*[starts-with(name(), 'bind')]/..")
        val nodes = expr.evaluate(doc, XPathConstants.NODESET) as NodeList
        System.out.println("binding node count " + nodes.getLength())
        for (i in 0..nodes.getLength() - 1) {
            val item = nodes.item(i)
            System.out.println("checking node $item")
            val attributes = item.getAttributes()
            val id = attributes.getNamedItem("android:id")
            if (id == null) {
                continue
            }
            val bindingTarget = BindingTarget(item, id.getNodeValue(), getFullViewClassName(item.getNodeName()))
            val attrCount = attributes.getLength()
            for (j in 0..(attrCount - 1)) {
                val attr = attributes.item(j)
                val name = attr.getNodeName()
                if (name.startsWith("bind:")) {
                    bindingTarget.addBinding(name.substring("bind:".length), exprParser.parse(attr.getNodeValue()))
                }
            }
            layoutBinding.bindingTargets.add(bindingTarget)
        }

        val convExpr = xPath.compile("//@*[starts-with(name(), 'bind_static')]/..")
        val convNodes = convExpr.evaluate(doc, XPathConstants.NODESET) as NodeList
        System.out.println("converter node count " + nodes.getLength())
        for (i in 0..convNodes.getLength() - 1) {
            val item = convNodes.item(i)
            System.out.println("checking conv node $item")
            val attributes = item.getAttributes()
            val attrCount = attributes.getLength()
            for (j in 0..(attrCount - 1)) {
                val attr = attributes.item(j)
                val name = attr.getNodeName()
                if (name.startsWith("bind_static:")) {
                    layoutBinding.addStaticClass(name.substring("bind_static:".length), attr.getNodeValue())
                }
            }
        }
        if (exprParser.model.variables.size == 0) {
            return null
        }
        layoutBinding.exprModel = exprParser.model
        layoutBinding.pack()
        return layoutBinding
    }

    private fun findLayoutFolders(resources: File): Array<File> {
        val filenameFilter = object : FilenameFilter {
            override fun accept(dir: File, name: String): Boolean {
                return name.startsWith("layout")
            }
        }
        return resources.listFiles(filenameFilter)
    }

    var xmlFilter: FilenameFilter = object : FilenameFilter {
        override fun accept(dir: File, name: String): Boolean {
            return name.toLowerCase().endsWith(".xml")
        }
    }

    private fun findXmlFiles(root: File, out: MutableList<File>) {
        if (!root.exists()) {
            return
        }
        if (root.isDirectory()) {
            for (file in root.listFiles(xmlFilter)) {
                if ("." == file.getName() || ".." == file.getName()) {
                    continue
                }
                if (file.isDirectory()) {
                    findXmlFiles(file, out)
                } else if (xmlFilter.accept(file, file.getName())) {
                    out.add(file)
                }
            }
        }
    }

    fun getFullViewClassName(viewName: String): String {
        if (viewName.indexOf('.') == -1) {
            if (viewName == "View"  || viewName == "ViewGroup") {
                return "android.view.$viewName"
            }
            return "android.widget.$viewName"
        }
        return viewName
    }
}
