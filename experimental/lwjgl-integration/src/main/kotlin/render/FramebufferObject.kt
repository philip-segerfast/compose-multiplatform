package render

import org.lwjgl.opengl.GL33C.*
import org.lwjgl.system.MemoryUtil.NULL

/**
 * Manages an OpenGL framebuffer object (FBO) with a color texture attachment
 * and a depth/stencil renderbuffer attachment.
 *
 * The color texture can be sampled by a shader to blit the FBO contents
 * onto another render target (e.g. the default framebuffer).
 *
 * Lifecycle:
 *  1. [create] — allocates the GL objects and configures them at the given size
 *  2. [resize] — re-allocates storage for an existing FBO at a new size
 *  3. [delete] — releases all GL objects
 */
class FramebufferObject {

    /** The framebuffer object ID. */
    var fboId: Int = 0
        private set

    /** The color texture attached to [GL_COLOR_ATTACHMENT0]. Sample this to read the FBO contents. */
    var colorTextureId: Int = 0
        private set

    /** The renderbuffer used for depth + stencil (Skia needs a stencil buffer). */
    var rboId: Int = 0
        private set

    val isCreated: Boolean get() = fboId != 0

    /**
     * Allocates the FBO, color texture, and depth/stencil renderbuffer.
     * Must only be called once. Use [resize] to change dimensions afterwards.
     */
    fun create(width: Int, height: Int) {
        check(!isCreated) { "FBO already created. Call resize() to change dimensions, or delete() first." }

        fboId = glGenFramebuffers()
        colorTextureId = glGenTextures()
        rboId = glGenRenderbuffers()

        configureAttachments(width, height)
    }

    /**
     * Re-allocates storage for the existing texture and renderbuffer at a new size.
     * The GL object names remain the same — only their backing storage changes.
     */
    fun resize(width: Int, height: Int) {
        check(isCreated) { "FBO not yet created. Call create() first." }
        configureAttachments(width, height)
    }

    /**
     * Deletes all GL objects. After this call, [create] may be called again.
     */
    fun delete() {
        if (!isCreated) return
        glDeleteTextures(colorTextureId)
        glDeleteRenderbuffers(rboId)
        glDeleteFramebuffers(fboId)
        fboId = 0
        colorTextureId = 0
        rboId = 0
    }

    /**
     * Binds the FBO, (re-)configures the color texture and depth/stencil renderbuffer,
     * attaches them, verifies completeness, and unbinds.
     */
    private fun configureAttachments(width: Int, height: Int) {
        glBindFramebuffer(GL_FRAMEBUFFER, fboId)

        // -- Color texture (RGBA8, linear filtering, clamp-to-edge) --
        glBindTexture(GL_TEXTURE_2D, colorTextureId)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTextureId, 0)
        glBindTexture(GL_TEXTURE_2D, 0)

        // -- Depth/stencil renderbuffer (DEPTH24_STENCIL8) --
        // Skia requires a stencil buffer for clipping and path rendering.
        glBindRenderbuffer(GL_RENDERBUFFER, rboId)
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, rboId)
        glBindRenderbuffer(GL_RENDERBUFFER, 0)

        // -- Verify completeness --
        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            error("Framebuffer incomplete (status=$status). Cannot render Compose UI.")
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }
}
