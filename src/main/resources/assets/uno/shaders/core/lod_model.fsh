#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>

layout(std140) uniform UnoFrame {
    mat4 ModelViewMat;
    vec4 HoleParams;
};

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec3 cameraOffset;
layout(location = 4) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;

void main() {
    if (length(cameraOffset.xz) < HoleParams.x && abs(cameraOffset.y) < HoleParams.x) {
        discard;
    }
    vec4 texel = texture(Sampler0, texCoord0);
    // cutout, like vanilla plants and lanterns
    if (texel.a < 0.1) {
        discard;
    }
    vec4 color = vec4(texel.rgb, 1.0) * vertexColor;
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
