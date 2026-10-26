# Uno

Distant terrain (LOD) renderer for NeoForge 26.3, independent implementation.
Renders through Blaze3D only (OpenGL and Vulkan), compatible with Sodium 0.9.3+. No shader support yet.

Design document: https://claude.ai/artifact/MuWxjQaKbMZqo5PKDJreCR

- `world/` — chunk capture, voxel storage (`LodKey`, `Voxel`, region files), mips (`Ingester`), world thread (`LodWorld`)
- `mesh/` — greedy mesher, block colours
- `render/` — octree selection, GPU quad buffer, drawing in `AfterOpaqueBlocks`
- `mixin/CameraMixin` — pushes the far plane out to the LOD distance

Dev: `./gradlew runClientTest` joins `run/saves/UnoTest` with Sodium.
