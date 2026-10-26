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

// 4 x uint32 per quad, see Mesher
uniform usamplerBuffer UnoQuads;
uniform sampler2D Sampler2;

// xyz = section origin in blocks, w = LOD level
layout(location = 0) in ivec4 SectionInfo;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec3 cameraOffset;
// texture coordinates in blocks: each whole number is one repeat of the block texture
layout(location = 4) out vec2 texCoord;
layout(location = 5) flat out int materialIndex;
layout(location = 6) flat out vec3 tint;
layout(location = 7) out vec3 maskPos;

// Unit-cube corners per face, same order and winding as the vanilla cloud mesh:
// down, up, north (-z), south (+z), west (-x), east (+x); 4 corners each, bit i of the mask = corner i.
const int packedX = 0xF03CC3;
const int packedY = 0x6666F0;
const int packedZ = 0xC3F066;

const float faceShade[6] = float[](0.5, 1.0, 0.8, 0.8, 0.6, 0.6);
const vec3 faceNormal[6] = vec3[](vec3(0, -1, 0), vec3(0, 1, 0), vec3(0, 0, -1), vec3(0, 0, 1), vec3(-1, 0, 0), vec3(1, 0, 0));

void main() {
    int quadIndex = gl_VertexIndex >> 2;
    int corner = gl_VertexIndex & 3;
    uvec4 quad = texelFetch(UnoQuads, quadIndex);

    int x = int(quad.x & 31u);
    int y = int((quad.x >> 5) & 31u);
    int z = int((quad.x >> 10) & 31u);
    int w = int((quad.x >> 15) & 31u) + 1;
    int h = int((quad.x >> 20) & 31u) + 1;
    int dir = int((quad.x >> 25) & 7u);
    int sky = int(quad.x >> 28);

    int bit = dir * 4 + corner;
    vec3 unit = vec3(float((packedX >> bit) & 1), float((packedY >> bit) & 1), float((packedZ >> bit) & 1));

    // width runs along u and height along v: Y faces u=x v=z, Z faces u=x v=y, X faces u=z v=y
    vec3 size;
    if (dir < 2) {
        size = vec3(w, 1, h);
    } else if (dir < 4) {
        size = vec3(w, h, 1);
    } else {
        size = vec3(1, h, w);
    }

    float scale = float(1 << SectionInfo.w);
    vec3 local = (vec3(x, y, z) + unit * size) * scale;
    vec3 pos = local + vec3(SectionInfo.xyz - CameraBlockPos) + CameraOffset;
    // Push LODs 0.2% farther along the view ray: same pixel, but where they coincide with vanilla terrain
    // at the edge of the render distance, vanilla wins the depth test instead of z-fighting.
    gl_Position = ProjMat * ModelViewMat * vec4(pos * 1.002, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    cameraOffset = pos;
    // half a block into the voxel the face belongs to, so a face on a section border tests its own section
    maskPos = local + vec3(SectionInfo.xyz - MaskOrigin.xyz) - faceNormal[dir] * 0.5;

    // Texture runs along the face's (u, v); on side faces v points down like vanilla block textures.
    // Horizontally it repeats every block; vertically on side faces it stretches over the whole voxel, so the
    // wall of a coarse voxel shows one grass fringe on top instead of a stack of them.
    if (dir < 2) {
        texCoord = local.xz;
    } else if (dir < 4) {
        texCoord = vec2(local.x, -local.y / scale);
    } else {
        texCoord = vec2(local.z, -local.y / scale);
    }

    // second word: material index (low 16 bits), biome tint as RGB565 (high 16 bits)
    materialIndex = int(quad.y & 0xFFFFu);
    uint packedTint = quad.y >> 16;
    tint = vec3(float((packedTint >> 11) & 31u) / 31.0, float((packedTint >> 5) & 63u) / 63.0, float(packedTint & 31u) / 31.0);
    // third word: block light (bits 0-3), ambient occlusion per corner (2 bits each from bit 4, 3 = open)
    int blockLight = int(quad.z & 15u);
    vec2 cornerUv = dir < 2 ? unit.xz : (dir < 4 ? unit.xy : unit.zy);
    int aoCorner = int(cornerUv.x) + 2 * int(cornerUv.y);
    float occlusion = float((quad.z >> (4u + 2u * uint(aoCorner))) & 3u);
    float ao = 0.55 + 0.15 * occlusion;
    vec4 light = sample_lightmap(Sampler2, ivec2(blockLight * 16, sky * 16));
    vertexColor = vec4(vec3(faceShade[dir] * ao), 1.0) * light;
}
