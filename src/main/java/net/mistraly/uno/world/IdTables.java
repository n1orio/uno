package net.mistraly.uno.world;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.mistraly.uno.Uno;

/// World-local ids for block states and biomes, persisted as `states.txt` / `biomes.txt` (line N = id N, id 0 = air).
/// Runtime ids change whenever the mod set changes, so they are never written to disk.
///
/// Writes happen on the world thread only; the arrays are republished on growth so mesher threads can read without locks.
public final class IdTables {
    private static final int MAX_STATES = 1 << 16;
    private static final int MAX_BIOMES = 256;

    private final Path statesFile;
    private final Path biomesFile;
    private final Registry<Biome> biomeRegistry;

    private final Object2IntOpenHashMap<String> stateIds = new Object2IntOpenHashMap<>();
    private final List<String> stateNames = new ArrayList<>();
    private volatile BlockState[] states = new BlockState[256];
    private volatile byte[] kinds = new byte[256];
    private volatile int stateCount;
    private int savedStates;
    private int[] runtimeToLocal = new int[0];

    private final Object2IntOpenHashMap<String> biomeIds = new Object2IntOpenHashMap<>();
    private final List<String> biomeNames = new ArrayList<>();
    private volatile Biome[] biomes = new Biome[MAX_BIOMES];
    private volatile int biomeCount;
    private int savedBiomes;
    private final Biome fallbackBiome;

    public IdTables(Path dir, Registry<Biome> biomeRegistry) {
        this.statesFile = dir.resolve("states.txt");
        this.biomesFile = dir.resolve("biomes.txt");
        this.biomeRegistry = biomeRegistry;
        this.fallbackBiome = biomeRegistry.getValue(Biomes.PLAINS);
        this.stateIds.defaultReturnValue(-1);
        this.biomeIds.defaultReturnValue(-1);

        this.addState("minecraft:air", Blocks.AIR.defaultBlockState());
        this.addBiome("minecraft:plains", this.fallbackBiome);
        this.load();
        this.savedStates = this.stateCount;
        this.savedBiomes = this.biomeCount;
    }

    private void load() {
        for (String line : readLines(this.statesFile)) {
            this.addState(line, parseState(line));
        }
        for (String line : readLines(this.biomesFile)) {
            Identifier id = Identifier.tryParse(line);
            Biome biome = id == null ? null : this.biomeRegistry.getValue(id);
            this.addBiome(line, biome != null ? biome : this.fallbackBiome);
        }
    }

    private static List<String> readLines(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            // line 0 is the implicit air / plains entry
            return lines.isEmpty() ? List.of() : lines.subList(1, lines.size());
        } catch (IOException e) {
            Uno.LOGGER.error("Failed to read {}", file, e);
            return List.of();
        }
    }

    private static BlockState parseState(String name) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, name, false).blockState();
        } catch (CommandSyntaxException | RuntimeException e) {
            return Blocks.STONE.defaultBlockState();
        }
    }

    private int addState(String name, BlockState state) {
        int id = this.stateNames.size();
        this.stateNames.add(name);
        this.stateIds.put(name, id);
        BlockState[] statesArray = this.states;
        byte[] kindsArray = this.kinds;
        if (id >= statesArray.length) {
            statesArray = Arrays.copyOf(statesArray, statesArray.length * 2);
            kindsArray = Arrays.copyOf(kindsArray, kindsArray.length * 2);
        }
        statesArray[id] = state;
        kindsArray[id] = BlockKind.classify(state);
        this.states = statesArray;
        this.kinds = kindsArray;
        this.stateCount = id + 1;
        return id;
    }

    private int addBiome(String name, Biome biome) {
        int id = this.biomeNames.size();
        this.biomeNames.add(name);
        this.biomeIds.put(name, id);
        Biome[] array = this.biomes;
        array[id] = biome;
        this.biomes = array;
        this.biomeCount = id + 1;
        return id;
    }

    /// World thread only. Air-like states (plants, torches) map to 0 so they never take storage.
    public int stateId(BlockState state) {
        int runtime = Block.getId(state);
        if (runtime >= this.runtimeToLocal.length) {
            int oldLength = this.runtimeToLocal.length;
            this.runtimeToLocal = Arrays.copyOf(this.runtimeToLocal, Math.max(runtime + 1, Block.BLOCK_STATE_REGISTRY.size()));
            Arrays.fill(this.runtimeToLocal, oldLength, this.runtimeToLocal.length, -1);
        }
        int local = this.runtimeToLocal[runtime];
        if (local >= 0) {
            return local;
        }
        if (BlockKind.classify(state) == BlockKind.AIR) {
            local = 0;
        } else {
            String name = BlockStateParser.serialize(state);
            local = this.stateIds.getInt(name);
            if (local < 0) {
                local = this.stateCount < MAX_STATES ? this.addState(name, state) : 0;
            }
        }
        this.runtimeToLocal[runtime] = local;
        return local;
    }

    /// World thread only.
    public int biomeId(Holder<Biome> holder) {
        String name = holder.unwrapKey().map(key -> key.identifier().toString()).orElse("minecraft:plains");
        int id = this.biomeIds.getInt(name);
        if (id < 0) {
            id = this.biomeCount < MAX_BIOMES ? this.addBiome(name, holder.value()) : 0;
        }
        return id;
    }

    public BlockState state(int id) {
        BlockState[] array = this.states;
        return id < this.stateCount && array[id] != null ? array[id] : Blocks.AIR.defaultBlockState();
    }

    public byte kind(int id) {
        byte[] array = this.kinds;
        return id < this.stateCount ? array[id] : BlockKind.AIR;
    }

    public Biome biome(int id) {
        Biome biome = id < this.biomeCount ? this.biomes[id] : null;
        return biome != null ? biome : this.fallbackBiome;
    }

    public void save() {
        try {
            this.savedStates = append(this.statesFile, this.stateNames, this.savedStates);
            this.savedBiomes = append(this.biomesFile, this.biomeNames, this.savedBiomes);
        } catch (IOException e) {
            Uno.LOGGER.error("Failed to save id tables", e);
        }
    }

    private static int append(Path file, List<String> names, int saved) throws IOException {
        if (!Files.exists(file)) {
            saved = 0;
        }
        if (saved >= names.size()) {
            return saved;
        }
        StringBuilder out = new StringBuilder();
        for (int i = saved; i < names.size(); i++) {
            out.append(names.get(i)).append('\n');
        }
        Files.writeString(file, out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return names.size();
    }
}
