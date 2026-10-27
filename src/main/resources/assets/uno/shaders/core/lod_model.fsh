#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>

layout(std140) uniform UnoFrame {
    mat4 ModelViewMat;
    // x = render distance in blocks, y = distance up to which block textures are drawn
    vec4 HoleParams;
    // section mask: origin in blocks (xyz), side length in sections (w)
    ivec4 MaskOrigin;
    // x = sections in height, y = 1 when the mask is valid, z = 1 when nothing is cut (shader pack)
    ivec4 MaskInfo;
};

uniform sampler2D Sampler0;
// 1 per 16^3 section that vanilla / Sodium draw, see SectionMask
uniform usamplerBuffer UnoMask;

// LODs are cut away where vanilla terrain is drawn: exactly per section once the mask exists, a render-distance
// cylinder before that. `maskPos` is the position relative to the mask origin, `offset` relative to the camera.
bool coveredByVanilla(vec3 maskPos, vec3 offset) {
    if (MaskInfo.z != 0 && length(offset.xz) < HoleParams.x && length(offset.xz) > HoleParams.x * 0.6) {
        // a shader pack is drawing: in the outer ring of the render distance the pack dissolves the vanilla edge
        // into its own far terrain (fully there past 0.6 of it), so Uno leaves that ring to the pack
        return true;
    }
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
layout(location = 6) flat in int sectionFade;

// Level changes cross-fade through a screen-space dither: a section fading in (mode 1) keeps the pixels below t,
// the one it replaces (mode 2) keeps the rest, so the two never overlap and never leave a hole.
bool fadeHidden(int fade) {
    int mode = (fade >> 4) & 3;
    if (mode == 0) {
        return false;
    }
    float t = float(fade >> 8) / 255.0;
    float d = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    return mode == 1 ? d >= t : d < t;
}


layout(location = 0) out vec4 fragColor;

void main() {
    if (fadeHidden(sectionFade)) {
        discard;
    }
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
