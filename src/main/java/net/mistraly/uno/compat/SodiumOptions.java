package net.mistraly.uno.compat;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.mistraly.uno.Uno;
import net.mistraly.uno.UnoConfig;

/// Uno's page in Sodium's video settings. Only Sodium loads this class, so Uno still runs without Sodium;
/// without it the same options are in the NeoForge mod config screen.
@ConfigEntryPointForge(Uno.MOD_ID)
public final class SodiumOptions implements ConfigEntryPoint {
    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Uno.MOD_ID, path);
    }

    private static Component blocks(int value) {
        return Component.translatable("uno.options.blocks", value);
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        Runnable save = UnoConfig.SPEC::save;
        builder.registerOwnModOptions()
            .addPage(builder.createOptionPage()
                .setName(Component.translatable("uno.options.page"))
                .addOptionGroup(builder.createOptionGroup()
                    .addOption(builder.createBooleanOption(id("enabled"))
                        .setName(Component.translatable("uno.configuration.enabled"))
                        .setTooltip(Component.translatable("uno.options.enabled.tooltip"))
                        .setStorageHandler(save::run)
                        .setBinding(UnoConfig.ENABLED::set, UnoConfig.ENABLED::get)
                        .setDefaultValue(true))
                    .addOption(builder.createIntegerOption(id("lod_distance"))
                        .setName(Component.translatable("uno.configuration.lodDistance"))
                        .setTooltip(Component.translatable("uno.options.lodDistance.tooltip"))
                        .setStorageHandler(save::run)
                        .setImpact(OptionImpact.MEDIUM)
                        .setRange(256, 16384, 256)
                        .setValueFormatter(SodiumOptions::blocks)
                        .setBinding(UnoConfig.LOD_DISTANCE::set, UnoConfig.LOD_DISTANCE::get)
                        .setDefaultValue(2048))
                    .addOption(builder.createIntegerOption(id("detail_distance"))
                        .setName(Component.translatable("uno.configuration.detailDistance"))
                        .setTooltip(Component.translatable("uno.options.detailDistance.tooltip"))
                        .setStorageHandler(save::run)
                        .setImpact(OptionImpact.HIGH)
                        .setRange(32, 2048, 32)
                        .setValueFormatter(SodiumOptions::blocks)
                        .setBinding(UnoConfig.DETAIL_DISTANCE::set, UnoConfig.DETAIL_DISTANCE::get)
                        .setDefaultValue(256))
                    .addOption(builder.createIntegerOption(id("texture_distance"))
                        .setName(Component.translatable("uno.configuration.textureDistance"))
                        .setTooltip(Component.translatable("uno.options.textureDistance.tooltip"))
                        .setStorageHandler(save::run)
                        .setRange(0, 4096, 128)
                        .setValueFormatter(value -> Component.translatable("uno.options.chunks", value / 16))
                        .setBinding(UnoConfig.TEXTURE_DISTANCE::set, UnoConfig.TEXTURE_DISTANCE::get)
                        .setDefaultValue(1024)))
                .addOptionGroup(builder.createOptionGroup()
                    .addOption(builder.createBooleanOption(id("occlusion_culling"))
                        .setName(Component.translatable("uno.configuration.occlusionCulling"))
                        .setTooltip(Component.translatable("uno.options.occlusionCulling.tooltip"))
                        .setStorageHandler(save::run)
                        .setImpact(OptionImpact.LOW)
                        .setBinding(UnoConfig.OCCLUSION_CULLING::set, UnoConfig.OCCLUSION_CULLING::get)
                        .setDefaultValue(true)))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.translatable("uno.options.memory"))
                    .addOption(builder.createIntegerOption(id("gpu_memory"))
                        .setName(Component.translatable("uno.configuration.gpuMemoryMb"))
                        .setTooltip(Component.translatable("uno.options.gpuMemoryMb.tooltip"))
                        .setStorageHandler(save::run)
                        .setRange(32, 2048, 32)
                        .setValueFormatter(value -> Component.translatable("uno.options.megabytes", value))
                        .setBinding(UnoConfig.GPU_MEMORY_MB::set, UnoConfig.GPU_MEMORY_MB::get)
                        .setDefaultValue(256))
                    .addOption(builder.createIntegerOption(id("section_cache"))
                        .setName(Component.translatable("uno.configuration.sectionCache"))
                        .setTooltip(Component.translatable("uno.options.sectionCache.tooltip"))
                        .setStorageHandler(save::run)
                        .setRange(256, 65536, 256)
                        .setValueFormatter(value -> Component.literal(String.valueOf(value)))
                        .setBinding(UnoConfig.SECTION_CACHE::set, UnoConfig.SECTION_CACHE::get)
                        .setDefaultValue(4096))));
    }
}
