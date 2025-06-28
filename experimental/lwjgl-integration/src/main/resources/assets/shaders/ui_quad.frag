#version 330 core
out vec4 FragColor;

in vec2 TexCoords;

uniform sampler2D uiTexture; // This is your Compose UI texture

void main() {
    FragColor = texture(uiTexture, TexCoords);
}
