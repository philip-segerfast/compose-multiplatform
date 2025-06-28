import org.lwjgl.opengl.GL33C
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.io.readBytes

class ComposeShaderManager private constructor(
    private val shaderCompiler: ShaderCompiler,
) {
    private var _programId: Int? = null
    val programId: Int get() = _programId ?: error("Program is not compiled. Invoke compileComposeShaderProgram() to compile.")

    fun compileComposeShaderProgram() {
        this._programId = shaderCompiler.compileShaderProgram(
            fragmentShaderFilePath = "/assets/shaders/ui_quad.frag",
            vertexShaderFilePath = "/assets/shaders/ui_quad.vert"
        )
    }

    fun deleteShaderProgram() {
        assertInitialized()
        GL33C.glDeleteProgram(programId)
    }

    fun assertInitialized() {
        _programId ?: error("Program is not compiled. Invoke compileComposeShaderProgram() before using.")
    }

    companion object {
        operator fun invoke(): ComposeShaderManager {
            return ComposeShaderManager(ShaderCompiler(ResourceLoader()))
        }
    }

}

private class ResourceLoader {
    /** @return file contents */
    fun readFile(resourcePath: String): String {
        return try {
            // Get the resource as a stream from the classpath
            val inputStream: InputStream? = this::class.java.getResourceAsStream(resourcePath)

            if (inputStream == null) {
                throw IOException("Shader resource not found: $resourcePath")
            }

            // Read the stream into a string and ensure it's closed
            inputStream.use { stream ->
                stream.readBytes().toString(StandardCharsets.UTF_8)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            // In a real app, you'd want more robust error handling
            "Shader failed to load: $resourcePath"
        }
    }
}

private class ShaderCompiler(private val resourceLoader: ResourceLoader) {
    fun compileShaderProgram(fragmentShaderFilePath: String, vertexShaderFilePath: String): Int {
        val fragmentShaderId = compileFragmentShader(fragmentShaderFilePath)
        val vertexShaderId = compileVertexShader(vertexShaderFilePath)

        val shaderProgramId = GL33C.glCreateProgram()
        GL33C.glAttachShader(shaderProgramId, vertexShaderId)
        GL33C.glAttachShader(shaderProgramId, fragmentShaderId)
        GL33C.glLinkProgram(shaderProgramId)

        checkProgramLinkingResult(shaderProgramId)

        // Delete shaders when fully compiled and linked
        GL33C.glDeleteShader(vertexShaderId)
        GL33C.glDeleteShader(fragmentShaderId)

        return shaderProgramId
    }

    private fun compileFragmentShader(filePath: String): Int {
        val strShader = resourceLoader.readFile(filePath)

        val fragmentShaderId = GL33C.glCreateShader(GL33C.GL_FRAGMENT_SHADER)
        GL33C.glShaderSource(fragmentShaderId, strShader)
        GL33C.glCompileShader(fragmentShaderId)
        checkShaderCompilationResult(fragmentShaderId)

        return fragmentShaderId
    }

    private fun compileVertexShader(filePath: String): Int {
        val strShader = resourceLoader.readFile(filePath)

        val vertexShaderId = GL33C.glCreateShader(GL33C.GL_VERTEX_SHADER)
        GL33C.glShaderSource(vertexShaderId, strShader)
        GL33C.glCompileShader(vertexShaderId)
        checkShaderCompilationResult(vertexShaderId)

        return vertexShaderId
    }

    private fun checkShaderCompilationResult(shaderId: Int) {
        val success = IntArray(1)
        GL33C.glGetShaderiv(shaderId, GL33C.GL_COMPILE_STATUS, success)
        if (success[0] == GL33C.GL_FALSE) {
            val infoLog = GL33C.glGetShaderInfoLog(shaderId)
            throw RuntimeException("ERROR: shader compilation failed.\n$infoLog")
        }
    }

    private fun checkProgramLinkingResult(programId: Int) {
        val success = IntArray(1)
        GL33C.glGetProgramiv(programId, GL33C.GL_LINK_STATUS, success)
        if (success[0] == GL33C.GL_FALSE) {
            val infoLog = GL33C.glGetProgramInfoLog(programId)
            throw RuntimeException("ERROR: Shader program linking failed.\n$infoLog")
        }
    }
}