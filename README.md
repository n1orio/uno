# Uno

Distant terrain (LOD) renderer for NeoForge 26.3, independent implementation.
Renders through Blaze3D only (OpenGL and Vulkan), compatible with Sodium 0.9.3+.
Shader packs: through [Vitrail](https://modrinth.com/mod/vitrail-shaders) (unmodified), where Uno stands in for Distant Horizons; near LODs keep Uno's textures, the pack fills in behind the vanilla chunks.

Design document: https://claude.ai/artifact/MuWxjQaKbMZqo5PKDJreCR

- `world/` — chunk capture, voxel storage (`LodKey`, `Voxel`, region files), mips (`Ingester`), world thread (`LodWorld`)
- `mesh/` — greedy mesher, block colours
- `render/` — octree selection, GPU quad buffer, drawing in `AfterOpaqueBlocks`
- `compat/VitrailBridge`, `mixin/vitrail/` — far terrain for Vitrail shader packs
- `mixin/CameraMixin` — pushes the far plane out to the LOD distance

Dev: `./gradlew runClientTest` joins `run/saves/UnoTest` with Sodium; add `-PwithVitrail` to run with Vitrail.

## License

[Uno License](LICENSE): free to use, modify, fork and redistribute; not for sale; the "Uno <version> by Niorio" line on the title screen must stay.
