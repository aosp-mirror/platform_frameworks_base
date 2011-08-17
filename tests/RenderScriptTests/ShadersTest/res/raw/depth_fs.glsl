void main() {
    // Non-linear depth value
    float z = gl_FragCoord.z;
    // Near and far planes from the projection
    // In practice, these values can be used to tweak
    // the focus range
    float n = UNI_near;
    float f = UNI_far;
    // Linear depth value
    z = (2.0 * n) / (f + n - z * (f - n));

    gl_FragColor = vec4(z, z, z, 1.0);
}
