import java.lang.Exception
import kotlin.text.appendLine

typealias LogNodeName = String

object NodeLogger {
    private val nodes = mutableSetOf(LogNode("root", 0))

    fun group(name: String) {
        val current = current()
        current.log("$name {")
        val node = try {
            current.group(name)
        } catch (e: Exception) {
            buildString {
                appendLine("Caught exception: $e")
                appendLine("--- NODE TREE DUMP ---")
                appendLine(nodes.first().dump())
                appendLine("--- END OF DUMP ---")
            }.let { error(it) }
        }
        nodes.add(node)
    }

    inline fun withGroup(name: String, block: LogNode.() -> Unit) {
        group(name)
        current().block()
        popGroup()
    }

    fun log(text: String) {
        current().log(text)
    }

    fun popGroup() {
        val toRemove = current()
        nodes.remove(toRemove)
        val current = current()
        current.log("}")
        current.removeChildNode(toRemove.name)
    }

    fun current(): LogNode = nodes.last()
}

class LogNode(val name: LogNodeName, private val depth: Int) {

    private val childNodes = mutableMapOf<LogNodeName, LogNode>()

    fun group(name: LogNodeName): LogNode {
        val childNode = LogNode(name, depth + 1)
        if(childNodes[name] != null) {
            error("Duplicate node name: $name")
        }
        childNodes[name] = childNode
        return childNode
    }

    fun removeChildNode(name: LogNodeName) {
        childNodes.remove(name)
    }

    fun log(text: String) {
        val indentation = getIndentation()
        println("$indentation$text")
    }

    private fun getIndentation(): String = getIndentation(depth)

    fun dump(): String {
        // First, print this node
        // Second, loop through all child nodes and print their names (with indentation)
        return dumpNode(this)
    }

    private fun dumpNode(node: LogNode): String {
        val indent = node.getIndentation()
        return buildString {
            appendLine("$indent${node.name}")
            // Dump all children
            node.childNodes.values.forEach {
                append(it.dump())
            }
        }
    }
}

fun getIndentation(indentLevel: Int): String {
    return "  ".repeat(indentLevel)
}






