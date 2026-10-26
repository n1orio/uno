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

// per material, 4 texels: base atlas rect, overlay atlas rect,
// (base average rgb, flags: 1 = base tinted, 2 = overlay tinted), (overlay average rgb, overlay coverage)
uniform samplerBuffer UnoMaterials;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec3 cameraOffset;
layout(location = 4) in vec2 texCoord;
layout(location = 5) flat in int materialIndex;
layout(location = 6) flat in vec3 tint;
layout(location = 7) in vec3 maskPos;

layout(location = 0) out vec4 fragColor;

vec4 sampleLayer(vec4 rect, vec2 tile, vec2 dx, vec2 dy) {
    vec2 size = rect.zw - rect.xy;
    // gradients come from the continuous coordinate, so mip selection has no seams where the sprite repeats
    return textureGrad(Sampler0, rect.xy + tile * size, dx * size, dy * size);
}

void main() {
    if (coveredByVanilla(maskPos, cameraOffset)) {
        discard;
    }

    int m = materialIndex * 4;
    vec4 baseRect = texelFetch(UnoMaterials, m);
    vec4 overlayRect = texelFetch(UnoMaterials, m + 1);
    vec4 baseInfo = texelFetch(UnoMaterials, m + 2);
    vec4 overlayInfo = texelFetch(UnoMaterials, m + 3);
    int flags = int(baseInfo.a + 0.5);
    vec3 baseTint = (flags & 1) != 0 ? tint : vec3(1.0);
    vec3 overlayTint = (flags & 2) != 0 ? tint : vec3(1.0);

    // far away: the average colours of both layers
    vec3 far = mix(baseInfo.rgb * baseTint, overlayInfo.rgb * overlayTint, overlayInfo.a);

    // block textures only up close, where single blocks can still be told apart (fading over the last 15%)
    float textured = materialIndex == 0 ? 0.0 : 1.0 - smoothstep(HoleParams.y * 0.85, HoleParams.y, sphericalVertexDistance);
    vec3 base = materialIndex == 0 ? vec3(0.5) : far;
    float alpha = 0.75;
    if (textured > 0.0) {
        vec2 tile = fract(texCoord);
        vec2 dx = dFdx(texCoord);
        vec2 dy = dFdy(texCoord);
        vec4 texel = sampleLayer(baseRect, tile, dx, dy);
        vec3 near = texel.rgb * baseTint;
        if (overlayInfo.a > 0.0) {
            vec4 overlay = sampleLayer(overlayRect, tile, dx, dy);
            near = mix(near, overlay.rgb * overlayTint, overlay.a);
        }
#ifdef TRANSLUCENT
        alpha = mix(alpha, texel.a, textured);
#else
        // see-through pixels (leaves, glass) are cut out while textures are shown, like vanilla cutout blocks;
        // further away the voxel turns into a solid cube of the average colour
        if (textured > 0.5 && texel.a < 0.5) {
            discard;
        }
#endif
        base = mix(far, near, textured);
    }

#ifdef TRANSLUCENT
    vec4 color = vec4(base, alpha) * vertexColor;
#else
    vec4 color = vec4(base, 1.0) * vertexColor;
#endif
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
