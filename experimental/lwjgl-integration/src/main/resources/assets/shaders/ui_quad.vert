#version 150 core
in vec2 aPos;
in vec2 aTexCoords;

out vec2 TexCoords;

void main() {
    // Positions are already in Normalized Device Coordinates, so just pass them through
    gl_Position = vec4(aPos.x, aPos.y, 0.0, 1.0);
    TexCoords = aTexCoords;
}
