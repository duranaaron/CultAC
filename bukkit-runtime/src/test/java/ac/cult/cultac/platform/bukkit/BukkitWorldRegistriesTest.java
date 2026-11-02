package ac.cult.cultac.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import ac.cult.cultac.utils.latency.ClientWorldRegistries;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The cached conversion against the uncached one on the vanilla data pack, before and after a tag reload. */
class BukkitWorldRegistriesTest {
    private static RegistryAccess.Frozen registries;

    @BeforeAll
    static void loadVanilla() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
        var packs = ServerPacksSource.createVanillaTrustedRepository();
        packs.reload();
        packs.setSelected(List.of("vanilla"), false);
        var resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs.openAllSelected());
        var pendingTags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
        var lookups = TagLoader.buildUpdatedLookups(layers.getAccessForLoading(RegistryLayer.WORLD), pendingTags);
        var world = RegistryDataLoader.load(resources, lookups, RegistryDataLoader.WORLD_REGISTRIES, Runnable::run)
                .join();
        registries = layers.replaceFrom(RegistryLayer.WORLD, world).compositeAccess();
    }

    @Test
    void aLoginGetsTheUncachedDataAndTheNextReusesIt() {
        var cached = BukkitWorldRegistries.read(registries);
        assertEquals(UncachedWorldRegistries.read(registries), cached);
        assertEquals(
                Set.copyOf(ClientWorldRegistries.REGISTRIES),
                cached.registries().keySet());
        assertSame(cached, BukkitWorldRegistries.read(registries));
    }

    @Test
    void aTagReloadIsSeenByTheNextLogin() {
        var before = BukkitWorldRegistries.read(registries);
        var biomes = registries.lookupOrThrow(Registries.BIOME);
        var tags = new HashMap<TagKey<Biome>, List<Holder<Biome>>>();
        biomes.getTags().forEach(tag -> tags.put(tag.key(), tag.stream().toList()));
        var reloaded = TagKey.create(Registries.BIOME, net.minecraft.resources.Identifier.parse("cult:reloaded"));
        tags.put(reloaded, List.of(biomes.listElements().findFirst().orElseThrow()));
        biomes.prepareTagReload(new TagLoader.LoadResult<>(Registries.BIOME, Map.copyOf(tags)))
                .apply();

        var after = BukkitWorldRegistries.read(registries);
        assertNotEquals(before, after);
        assertEquals(UncachedWorldRegistries.read(registries), after);
        assertEquals(
                List.of(biomes.listElements()
                        .findFirst()
                        .orElseThrow()
                        .key()
                        .identifier()
                        .toString()),
                after.tags().get(ClientWorldRegistries.BIOMES).get("cult:reloaded"));
    }
}
