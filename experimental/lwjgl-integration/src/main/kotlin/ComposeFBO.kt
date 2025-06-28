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

    /**
     * Creates the FBO, attachments, and VAO for the first time.
     * This should be called once during initialization.
     */
    fun create(
        width: Int,
        height: Int,
    ) {
        NodeLogger.withGroup("ComposeFBO.create($width, $height)") {
            shaderManager.assertInitialized()

            // CREATE FRAMEBUFFER, TEXTURE, RENDERBUFFER
            log("Generating framebuffer objects...")
            fboId = glGenFramebuffers()
            colorTextureId = glGenTextures()
            rboId = glGenRenderbuffers()
            log("New fboId: $fboId, colorTextureId: $colorTextureId, rboId: $rboId")

            // Initial configuration of the attachments' storage and parameters
            configureFramebufferAttachments(width, height)

            // CREATE VAO. This only needs to be done once.
            log("Creating quad VAO!")
            createQuadVao()
        }
    }

    /**
     * Resizes the existing framebuffer attachments.
     * This should be called when the window size changes.
     */
    fun resize(
        width: Int,
        height: Int,
    ): Unit = NodeLogger.withGroup("ComposeFBO.resize(width=$width, height=$height)") {
        // Just re-configure the storage of existing texture and renderbuffer
        configureFramebufferAttachments(width, height)
        // The quad VAO's vertices are in normalized device coordinates (-1 to 1)
        // and do not depend on the window size, so it does not need to be recreated.
    }

    fun delete() {
        deleteFramebuffers()
        deleteQuadBuffers()
    }

    /**
     * Configures the storage and parameters for the color texture and renderbuffer attachments.
     * This function assumes the OpenGL objects (fbo, texture, rbo) have already been generated.
     */
    private fun configureFramebufferAttachments(
        width: Int,
        height: Int,
    ) = NodeLogger.withGroup("ComposeFBO.configureFramebufferAttachments($width, $height)") {
        bindFramebuffer(GL_FRAMEBUFFER, fboId)

        // -- CONFIGURE AND ATTACH COLOR TEXTURE
        bindTexture(GL_TEXTURE_2D, colorTextureId)
        // Re-specify the texture's image data with the new dimensions. This resizes the texture.
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

        // Attach the texture to the FBO
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTextureId, 0)
        bindTexture(GL_TEXTURE_2D, 0) // Unbind texture

        // -- CONFIGURE AND ATTACH RENDERBUFFER
        bindRenderbuffer(GL_RENDERBUFFER, rboId)
        // Re-specify the renderbuffer's storage with the new dimensions
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height)

        // Attach the renderbuffer to the FBO
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, rboId)
        bindRenderbuffer(GL_RENDERBUFFER, 0) // Unbind renderbuffer

        // -- ENSURE COMPLETENESS
        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            error("Framebuffer not completed. Error: $status")
        }

        // Unbind the framebuffer to return to the default framebuffer
        log("Binding default framebuffer")
        bindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private fun createQuadVao() = NodeLogger.withGroup("createQuadVao") {
        if (quadVaoId != 0) {
            // This should not happen with the new logic, but as a safeguard:
            NodeLogger.log("Deleting old VAO (it was $quadVaoId)")
            deleteQuadBuffers()
        }

        MemoryStack.stackPush().use { stack ->
            val vertices =
                stack
                    .mallocFloat(4 * 4)
                    .apply {
                        posCoords(-1f, 1f); texCoords(0f, 0f) // Top-left
                        posCoords(-1f, -1f); texCoords(0f, 1f) // Bottom-left
                        posCoords(1f, -1f); texCoords(1f, 1f) // Bottom-right
                        posCoords(1f, 1f); texCoords(1f, 0f) // Top-right
                    }.flip()

            val indices =
                stack
                    .mallocInt(6)
                    .apply {
                        put(0); put(1); put(2) // First triangle
                        put(2); put(3); put(0) // Second triangle
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

            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * 4, 0)
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
        glDeleteVertexArrays(quadVaoId)
        glDeleteBuffers(quadVboId)
        glDeleteBuffers(quadEboId)
        quadVaoId = 0
        quadVboId = 0
        quadEboId = 0
    }
}

fun FloatBuffer.posCoords(left: Float, bottom: Float) {
    put(left).put(bottom)
}

fun FloatBuffer.texCoords(left: Float, top: Float) {
    put(left).put(top)
}