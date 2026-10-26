#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>

layout(std140) uniform UnoFrame {
    mat4 ModelViewMat;
    // x = render distance in blocks, y = distance up to which block textures are drawn
    vec4 HoleParams;
    // section mask: origin in blocks (xyz), side length in sections (w)
    ivec4 MaskOrigin;
    // x = sections in height, y = 1 when the mask is valid
    ivec4 MaskInfo;
};

uniform sampler2D Sampler0;
// 1 per 16^3 section that vanilla / Sodium draw, see SectionMask
uniform usamplerBuffer UnoMask;

// LODs are cut away where vanilla terrain is drawn: exactly per section once the mask exists, a render-distance
// cylinder before that. `maskPos` is the position relative to the mask origin, `offset` relative to the camera.
bool coveredByVanilla(vec3 maskPos, vec3 offset) {
    if (MaskInfo.y == 0) {
        return length(offset.xz) < HoleParams.x && abs(offset.y) < HoleParams.x;
    }
    ivec3 section = ivec3(floor(maskPos / 16.0));
    if (section.x < 0 || section.y < 0 || section.z < 0 || section.x >= MaskOrigin.w || section.z >= MaskOrigin.w || section.y >= MaskInfo.x) {
        return false;
    }
    return texelFetch(UnoMask, (section.y * MaskOrigin.w + section.z) * MaskOrigin.w + section.x).r != 0u;
}


layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec3 cameraOffset;
layout(location = 4) in vec2 texCoord0;
layout(location = 5) in vec3 maskPos;

layout(location = 0) out vec4 fragColor;

void main() {
    if (coveredByVanilla(maskPos, cameraOffset)) {
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
