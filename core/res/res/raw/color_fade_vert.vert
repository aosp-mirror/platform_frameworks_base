uniform mat4 proj_matrix;
uniform mat4 tex_matrix;
attribute vec2 position;
attribute vec2 uv;
varying vec2 UV;

void main()
{
    vec4 transformed_uv = tex_matrix * vec4(uv.x, uv.y, 1.0, 1.0);
    UV = transformed_uv.st / transformed_uv.q;
    gl_Position = proj_matrix * vec4(position.x, position.y, 0.0, 1.0);
}
