#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(std140) uniform UnoFrame {
    mat4 ModelViewMat;
    // x = render distance in blocks, y = distance up to which block textures are drawn
    vec4 HoleParams;
    // section mask: origin in blocks (xyz), side length in sections (w)
    ivec4 MaskOrigin;
    // x = sections in height, y = 1 when the mask is valid
    ivec4 MaskInfo;
};

uniform sampler2D Sampler2;

// position relative to the section origin (level 0, so in blocks), atlas UV, tint x shade, (block, sky) light
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in ivec2 UV2;
// xyz = section origin in blocks, w = LOD level (always 0 here)
layout(location = 4) in ivec4 SectionInfo;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec3 cameraOffset;
layout(location = 4) out vec2 texCoord0;
layout(location = 5) out vec3 maskPos;
layout(location = 6) flat out int sectionFade;

void main() {
    sectionFade = SectionInfo.w;
    vec3 pos = Position + vec3(SectionInfo.xyz - CameraBlockPos) + CameraOffset;
    // same depth bias as the cube LODs, so vanilla wins where both draw the same block
    gl_Position = ProjMat * ModelViewMat * vec4(pos * 1.002, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    cameraOffset = pos;
    maskPos = Position + vec3(SectionInfo.xyz - MaskOrigin.xyz);
    texCoord0 = UV0;
    vertexColor = vec4(Color.rgb, 1.0) * sample_lightmap(Sampler2, UV2);
}
