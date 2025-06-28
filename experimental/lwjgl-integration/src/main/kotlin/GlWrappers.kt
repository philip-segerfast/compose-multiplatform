import org.lwjgl.opengl.GL33C.*


fun useProgram(programId: Int) {
    NodeLogger.log("glUseProgram($programId)")
    glUseProgram(programId)
}

fun bindVertexArray(array: Int) {
    NodeLogger.log("glBindVertexArray($array)")
    glBindVertexArray(array)
}

fun bindTexture(target: Int, texture: Int) {
    NodeLogger.log("glBindTexture($target, $texture)")
    glBindTexture(target, texture)
}

fun bindBuffer(target: Int, buffer: Int) {
    NodeLogger.log("glBindBuffer($target, $buffer)")
    glBindBuffer(target, buffer)
}

fun bindRenderbuffer(target: Int, renderbuffer: Int) {
    NodeLogger.log("glBindRenderbuffer($target, $renderbuffer)")
    glBindRenderbuffer(target, renderbuffer)
}

fun bindFramebuffer(target: Int, framebuffer: Int) {
    NodeLogger.log("glBindFramebuffer($target, $framebuffer)")
    glBindFramebuffer(target, framebuffer)
}

fun enable(target: Int) {
    NodeLogger.log("glEnable($target)")
    glEnable(target)
}

fun disable(target: Int) {
    NodeLogger.log("glDisable($target)")
    glDisable(target)
}

fun activeTexture(texture: Int) {
    NodeLogger.log("glActiveTexture($texture)")
    glActiveTexture(texture)
}
