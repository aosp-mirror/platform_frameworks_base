attribute vec4 aPosition;
attribute vec2 aTextureCoordinates;
varying vec2 vTextureCoordinates;

void main() {
    vTextureCoordinates = aTextureCoordinates;
    gl_Position = aPosition;
}