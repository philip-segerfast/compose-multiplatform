import org.lwjgl.opengl.GL30C.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import java.nio.FloatBuffer
import kotlin.use

class ComposeFBO(
    private val shaderManager: ComposeShaderManager,
) {
    // Framebuffer
    var fboId: Int = 0
        private set
    var rboId: Int = 0
        private set
    var colorTextureId: Int = 0
        private set

    // Quad buffers
    var quadVaoId: Int = 0
        private set
    var quadVboId: Int = 0
        private set
    var quadEboId: Int = 0
        private set

    fun create(
        width: Int,
        height: Int,
    ) {
        NodeLogger.withGroup("ComposeFBO.create($width, $height)") {
            log("ensuring that shaderManager is initialized...")
            shaderManager.assertInitialized()

            // CREATE FRAMEBUFFER
            log("Creating framebuffer!")
            createAndConfigureFramebuffer(width, height)

            // CREATE VAO. This will set the buffer pointers
            log("Creating quad VAO!")
            createQuadVao()
        }
    }

    fun resize(
        width: Int,
        height: Int,
    ): Unit = NodeLogger.withGroup("ComposeFBO.resize(width=$width, height=$height)") {
        createAndConfigureFramebuffer(width, height)
        createQuadVao()
    }

    fun delete() {
        deleteFramebuffers()
        deleteQuadBuffers()
    }

    private fun createAndConfigureFramebuffer(
        width: Int,
        height: Int,
    ): Unit = NodeLogger.withGroup("ComposeFBO.createAndConfigureFramebuffer($width, $height)") {
        if (fboId != 0) {
            log("Deleting old framebuffer (it was $fboId)")
            deleteFramebuffers()
        }

        log("Setting new framebuffer!")
        fboId = glGenFramebuffers()
        log("New fboId: $fboId")

        // Bind it so we can make changes to it
        bindFramebuffer(GL_FRAMEBUFFER, fboId)

        // -- GENERATE COLOR BUFFER. This will be populated when rendering!

        colorTextureId = glGenTextures()
        colorTextureId.also {
            // Initialize the texture target
            bindTexture(GL_TEXTURE_2D, colorTextureId)
            glTexImage2D(
                GL_TEXTURE_2D,
                0,
                GL_RGBA,
                width,
                height,
                0,
                GL_RGBA,
                GL_UNSIGNED_BYTE,
                NULL, // Color buffer will be empty
            )

            // Set texture filters
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)

            // Set texture wrapping
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

            // Unbind texture
            bindTexture(GL_TEXTURE_2D, 0)
        }

        // ATTACH COLOR BUFFER TO FRAMEBUFFER
        glFramebufferTexture2D(
            GL_FRAMEBUFFER,
            GL_COLOR_ATTACHMENT0,
            GL_TEXTURE_2D,
            colorTextureId,
            0,
        )

        // GENERATE RENDERBUFFER that holds depth and stencil data
        rboId = glGenRenderbuffers()
        rboId.also {
            bindRenderbuffer(GL_RENDERBUFFER, rboId)
            // Specify that it will be storing depth and stencil data
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height)
            // Once set-up we can unbind it
            bindRenderbuffer(GL_RENDERBUFFER, 0)
        }

        // -- ATTACH RENDERBUFFER object to the framebuffer as depth and stencil attachment
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, rboId)

        // Ensure that the framebuffer is completed
        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            error("Framebuffer not completed. Error: $status")
        }

        // Unbind the framebuffer because we're done with it. Bind to it again when we want to write to it again.
        log("Binding default framebuffer")
        bindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private fun createQuadVao() = NodeLogger.withGroup("createQuadVao") {
        if (quadVaoId != 0) {
            NodeLogger.log("Deleting old VAO (it was $quadVaoId)")
            deleteQuadBuffers()
        }

        // Allocate and push data into memory
        MemoryStack.stackPush().use { stack ->
            // Allocated 16 floats in memory to put vertex and texture coords.
            val vertices =
                stack
                    .mallocFloat(4 * 4)
                    .apply {
                        // 4 vertices, 4 attributes (x, y, u, v)

                        // [0] Top-left
                        posCoords(-1f, 1f)
                        texCoords(0f, 0f)
                        // [1] Bottom-left
                        posCoords(-1f, -1f)
                        texCoords(0f, 1f)
                        // [2] Bottom-right
                        posCoords(1f, -1f)
                        texCoords(1f, 1f)
                        // [3] Top-right
                        posCoords(1f, 1f)
                        texCoords(1f, 0f)
                    }.flip()

            // Map the vertex indices above into two triangles.
            val indices =
                stack
                    .mallocInt(6)
                    .apply {
                        put(0)
                        put(1)
                        put(2) // First triangle
                        put(2)
                        put(3)
                        put(0) // Second triangle
                    }.flip()

            quadVaoId = glGenVertexArrays()
            quadVboId = glGenBuffers()
            quadEboId = glGenBuffers()
            NodeLogger.log("Created new objects: quadVaoId=$quadVaoId, quadVboId=$quadVboId, quadEboId=$quadEboId")

            bindVertexArray(quadVaoId)

            bindBuffer(GL_ARRAY_BUFFER, quadVboId)
            glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)

            bindBuffer(GL_ELEMENT_ARRAY_BUFFER, quadEboId)
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW)

            // Vertex positions
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * 4, 0)
            // Texture coordinates
            glEnableVertexAttribArray(1)
            glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * 4, 2 * 4)

            bindVertexArray(0)
        }
    }

    private fun deleteFramebuffers(): Unit = NodeLogger.withGroup("deleteFramebuffers") {
        glDeleteTextures(colorTextureId)
        glDeleteRenderbuffers(rboId)
        glDeleteFramebuffers(fboId)
        fboId = 0
        rboId = 0
        colorTextureId = 0
    }

    private fun deleteQuadBuffers(): Unit = NodeLogger.withGroup("deleteQuadBuffers") {
        // Delete quad buffers when we're done with them
        glDeleteVertexArrays(quadVaoId)
        glDeleteBuffers(quadVboId)
        glDeleteBuffers(quadEboId)
        quadVaoId = 0
        quadVboId = 0
        quadEboId = 0
    }
}

/** Put position coordinates. -1f to 1f */
fun FloatBuffer.posCoords(
    left: Float,
    bottom: Float,
) {
    put(left).put(bottom)
}

/** Put texture coordinate. 0f to 1f */
fun FloatBuffer.texCoords(
    left: Float,
    top: Float,
) {
    put(left).put(top)
}

// So, to draw the scene to a single texture we'll have to take the following steps:
// Render the scene as usual with the new framebuffer bound as the active framebuffer.
// Bind to the default framebuffer.
// Draw a quad that spans the entire screen with the new framebuffer's color buffer as its texture.
