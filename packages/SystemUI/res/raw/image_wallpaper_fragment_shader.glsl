precision mediump float;

// The actual wallpaper texture.
uniform sampler2D uTexture;

varying vec2 vTextureCoordinates;

void main() {
    // gets the pixel value of the wallpaper for this uv coordinates on screen.
    gl_FragColor = texture2D(uTexture, vTextureCoordinates);
}