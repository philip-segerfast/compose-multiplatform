import org.lwjgl.opengl.GL33C.*
import org.lwjgl.system.MemoryStack

fun printGeneralGLState() {
    println("--- General GL State ---")
    // Which FBO is bound for drawing operations?
    println("Bound FBO (DRAW): ${glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)}")
    // Which FBO is bound for reading operations (like glReadPixels)?
    println("Bound FBO (READ): ${glGetInteger(GL_READ_FRAMEBUFFER_BINDING)}")
    // Which shader program is active?
    println("Current Program: ${glGetInteger(GL_CURRENT_PROGRAM)}")
    // Which texture unit is active?
    println("Active Texture Unit: GL_TEXTURE${glGetInteger(GL_ACTIVE_TEXTURE) - GL_TEXTURE0}")
    // Which texture is bound to texture unit 0?
    // Note: You must activate the unit before querying its binding.
    glActiveTexture(GL_TEXTURE0)
    println("Bound Texture (Unit 0): ${glGetInteger(GL_TEXTURE_BINDING_2D)}")
    println("------------------------")
}

fun printFboAttachmentInfo(fboId: Int) {
    MemoryStack.stackPush().use { stack ->
        val params = stack.mallocInt(1)

        // Bind the FBO to make it the active query target
        bindFramebuffer(GL_FRAMEBUFFER, fboId)

        // Query the COLOR_ATTACHMENT0
        glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE, params)
        val type = params.get(0)
        val typeStr = when(type) {
            GL_TEXTURE -> "GL_TEXTURE"
            GL_RENDERBUFFER -> "GL_RENDERBUFFER"
            GL_NONE -> "GL_NONE"
            else -> "UNKNOWN"
        }
        println("FBO $fboId Color Attachment Type: $typeStr")

        if (type == GL_TEXTURE) {
            glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, params)
            println("FBO $fboId Attached Texture ID: ${params.get(0)}")
        }

        // Unbind to be safe
        bindFramebuffer(GL_FRAMEBUFFER, 0)
    }
}

fun printTextureInfo(textureId: Int) {
    if (!glIsTexture(textureId)) {
        println("Error: ID $textureId is NOT a valid texture.")
        return
    }
    MemoryStack.stackPush().use { stack ->
        val width = stack.mallocInt(1)
        val height = stack.mallocInt(1)
        val format = stack.mallocInt(1)

        // Bind the texture to make it active
        bindTexture(GL_TEXTURE_2D, textureId)

        // Get level 0 parameters (the main mipmap level)
        glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH, width)
        glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT, height)
        glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT, format)

        val formatStr = when(val f = format.get(0)) {
            GL_RGB -> "GL_RGB"
            GL_RGBA -> "GL_RGBA"
            GL_RGBA8 -> "GL_RGBA8"
            else -> "Unknown format ($f)"
        }

        println("Texture $textureId Info: Size=(${width.get(0)}, ${height.get(0)}), Format=$formatStr")

        // Unbind to be safe
        bindTexture(GL_TEXTURE_2D, 0)
    }
}

fun debugPrintComposeFboState(fbo: ComposeFBO, functionName: String) {
    println("\n============== DEBUG DUMP from $functionName ==============")
    val boundDrawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)
    println("Currently bound FBO for drawing: $boundDrawFbo (Expected: ${fbo.fboId})")

    if (boundDrawFbo != fbo.fboId) {
        println("!! WARNING: The bound FBO does not match the expected Compose FBO.")
    }

    if (!glIsFramebuffer(fbo.fboId)) {
        println("!! FATAL: ComposeFBO ID ${fbo.fboId} is NOT a valid framebuffer.")
        println("========================================================\n")
        return
    }

    // Get info about the attachment
    MemoryStack.stackPush().use { stack ->
        val params = stack.mallocInt(1)
        bindFramebuffer(GL_FRAMEBUFFER, fbo.fboId)
        glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE, params)
        val type = params.get(0)

        if (type == GL_TEXTURE) {
            glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, params)
            val attachedTextureId = params.get(0)
            println("ComposeFBO's attached Texture ID: $attachedTextureId (Expected: ${fbo.colorTextureId})")
            printTextureInfo(attachedTextureId)
        } else {
            println("ComposeFBO has no texture attached to color attachment 0.")
        }
        bindFramebuffer(GL_FRAMEBUFFER, 0)
    }
    println("========================================================\n")
}