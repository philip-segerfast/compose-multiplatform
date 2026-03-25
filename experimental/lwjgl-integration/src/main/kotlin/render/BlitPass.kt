package render

import org.lwjgl.opengl.GL33C.*
import org.lwjgl.system.MemoryStack

/**
 * Encapsulates the "blit" pass that draws an FBO's color texture onto the
 * default framebuffer using a fullscreen quad.
 *
 * On construction, this class:
 *  - Compiles the blit shader program from classpath resources
 *  - Creates a fullscreen quad VAO (vertices in NDC, so no projection matrix needed)
 *
 * The [blit] method restores the GL state that Skia may have dirtied, then
 * draws the quad with the given texture. This is designed to be called
 * immediately after Skia's flush/submit.
 */
class BlitPass {

    private val programId: Int
    private val quadVaoId: Int
    private val quadVboId: Int
    private val quadEboId: Int

    init {
        programId = ShaderProgram.fromResources(
            vertexPath = "/assets/shaders/ui_quad.vert",
            fragmentPath = "/assets/shaders/ui_quad.frag",
        )

        // -- Create fullscreen quad VAO --
        // Vertices are in Normalized Device Coordinates (-1 to +1), so the quad
        // covers the entire viewport regardless of window size.
        //
        //   Position (x,y)  TexCoord (u,v)
        //   -1, +1          0, 0    (top-left)
        //   -1, -1          0, 1    (bottom-left)
        //   +1, -1          1, 1    (bottom-right)
        //   +1, +1          1, 0    (top-right)

        MemoryStack.stackPush().use { stack ->
            val vertices = stack.mallocFloat(4 * 4).apply {
                // Top-left
                put(-1f); put(1f); put(0f); put(0f)
                // Bottom-left
                put(-1f); put(-1f); put(0f); put(1f)
                // Bottom-right
                put(1f); put(-1f); put(1f); put(1f)
                // Top-right
                put(1f); put(1f); put(1f); put(0f)
            }.flip()

            val indices = stack.mallocInt(6).apply {
                put(0); put(1); put(2) // First triangle
                put(2); put(3); put(0) // Second triangle
            }.flip()

            quadVaoId = glGenVertexArrays()
            quadVboId = glGenBuffers()
            quadEboId = glGenBuffers()

            glBindVertexArray(quadVaoId)

            glBindBuffer(GL_ARRAY_BUFFER, quadVboId)
            glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)

            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, quadEboId)
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW)

            // layout(location = 0) in vec2 aPos
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.SIZE_BYTES, 0L)
            // layout(location = 1) in vec2 aTexCoords
            glEnableVertexAttribArray(1)
            glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.SIZE_BYTES, 2L * Float.SIZE_BYTES)

            glBindVertexArray(0)
        }
    }

    /**
     * Blits the given texture onto the default framebuffer.
     *
     * This method:
     *  1. Binds the default framebuffer and sets the viewport
     *  2. Restores GL state that Skia may have changed (scissor, stencil, depth, blend)
     *  3. Clears the framebuffer with [clearColor] (simulates game world background)
     *  4. Draws a fullscreen textured quad with premultiplied alpha blending
     *
     * @param textureId       the color texture to sample (typically [FramebufferObject.colorTextureId])
     * @param viewportWidth   framebuffer width in pixels
     * @param viewportHeight  framebuffer height in pixels
     * @param clearColor      RGBA clear color for the background (null = don't clear,
     *                        used when blitting on top of existing content like a game world)
     */
    fun blit(
        textureId: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        clearColor: FloatArray? = null,
    ) {
        // -- Restore GL state for blitting --
        // Skia leaves GL state dirty after rendering. We reset only the
        // specific states our blit pass depends on.
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, viewportWidth, viewportHeight)
        glDisable(GL_SCISSOR_TEST)
        glDisable(GL_STENCIL_TEST)
        glDisable(GL_DEPTH_TEST)

        // -- Clear the background if requested --
        if (clearColor != null) {
            glDisable(GL_BLEND)
            glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3])
            glClear(GL_COLOR_BUFFER_BIT)
        }

        // -- Composite the UI overlay --
        glEnable(GL_BLEND)
        // Skia renders with premultiplied alpha — the RGB channels are already
        // multiplied by A in the texture. Using GL_SRC_ALPHA here would multiply
        // by alpha a second time, making semi-transparent pixels too dark.
        //
        //   result = src * ONE + dst * (1 - srcAlpha)
        //          = premultiplied_color + background * transparency
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)

        // -- Draw the fullscreen quad --
        glUseProgram(programId)
        glBindVertexArray(quadVaoId)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, textureId)
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0)
    }

    /**
     * Releases all GL resources owned by this blit pass.
     */
    fun delete() {
        glDeleteVertexArrays(quadVaoId)
        glDeleteBuffers(quadVboId)
        glDeleteBuffers(quadEboId)
        glDeleteProgram(programId)
    }
}
