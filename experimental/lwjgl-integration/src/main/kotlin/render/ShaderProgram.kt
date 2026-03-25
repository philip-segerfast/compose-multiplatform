package render

import org.lwjgl.opengl.GL33C.*
import java.nio.charset.StandardCharsets

/**
 * Utility for compiling and linking OpenGL shader programs from classpath resources.
 *
 * This is a stateless helper — it compiles shaders, links them into a program,
 * deletes the intermediate shader objects, and returns the program ID.
 * The caller is responsible for calling [glDeleteProgram] when done.
 */
object ShaderProgram {

    /**
     * Compiles a vertex + fragment shader from classpath resource paths and links
     * them into a shader program.
     *
     * @param vertexPath   classpath resource path, e.g. "/assets/shaders/ui_quad.vert"
     * @param fragmentPath classpath resource path, e.g. "/assets/shaders/ui_quad.frag"
     * @return the linked program ID
     * @throws RuntimeException if a shader fails to compile or the program fails to link
     */
    fun fromResources(vertexPath: String, fragmentPath: String): Int {
        val vertexSource = readResource(vertexPath)
        val fragmentSource = readResource(fragmentPath)

        val vertexId = compileShader(GL_VERTEX_SHADER, vertexSource, vertexPath)
        val fragmentId = compileShader(GL_FRAGMENT_SHADER, fragmentSource, fragmentPath)

        val programId = glCreateProgram()
        glAttachShader(programId, vertexId)
        glAttachShader(programId, fragmentId)
        glLinkProgram(programId)
        checkLinkStatus(programId)

        // Shader objects are no longer needed after linking
        glDeleteShader(vertexId)
        glDeleteShader(fragmentId)

        return programId
    }

    private fun compileShader(type: Int, source: String, label: String): Int {
        val id = glCreateShader(type)
        glShaderSource(id, source)
        glCompileShader(id)

        val success = IntArray(1)
        glGetShaderiv(id, GL_COMPILE_STATUS, success)
        if (success[0] == GL_FALSE) {
            val log = glGetShaderInfoLog(id)
            glDeleteShader(id)
            error("Shader compilation failed ($label):\n$log")
        }
        return id
    }

    private fun checkLinkStatus(programId: Int) {
        val success = IntArray(1)
        glGetProgramiv(programId, GL_LINK_STATUS, success)
        if (success[0] == GL_FALSE) {
            val log = glGetProgramInfoLog(programId)
            glDeleteProgram(programId)
            error("Shader program linking failed:\n$log")
        }
    }

    private fun readResource(path: String): String {
        val stream = ShaderProgram::class.java.getResourceAsStream(path)
            ?: error("Shader resource not found: $path")
        return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }
}
