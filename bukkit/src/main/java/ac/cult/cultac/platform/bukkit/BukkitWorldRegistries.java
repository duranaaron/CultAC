package ac.cult.cultac.platform.bukkit;

import net.minecraft.core.RegistryAccess;

/** Host conversion only; normal received registry updates use the owned packet producer. */
final class BukkitWorldRegistries {
    // Every login converts the same host registries on its netty loop; encoding each element is the costly part.
    private static final WorldRegistryCache<
                    RegistryAccess,
                    java.util.Map<
                            String, java.util.List<ac.cult.cultac.utils.latency.ClientWorldRegistries.Definition>>,
                    ac.cult.cultac.utils.latency.ClientWorldRegistries.Data>
            CACHE = new WorldRegistryCache<>();
    private static final java.util.List<net.minecraft.resources.RegistryDataLoader.RegistryData<?>> SYNCED =
            net.minecraft.resources.RegistryDataLoader.SYNCHRONIZED_REGISTRIES.stream()
                    .filter(definition -> ac.cult.cultac.utils.latency.ClientWorldRegistries.REGISTRIES.contains(
                            definition.key().identifier().toString()))
                    .toList();

    private BukkitWorldRegistries() {}
    /** Converts the host's initial world registries at the Bukkit boundary. */
    static ac.cult.cultac.utils.latency.ClientWorldRegistries.Data read(RegistryAccess registries) {
        return CACHE.get(
                registries, tagMembers(registries), BukkitWorldRegistries::values, BukkitWorldRegistries::data);
    }

    private static java.util.Map<String, java.util.List<ac.cult.cultac.utils.latency.ClientWorldRegistries.Definition>>
            values(RegistryAccess registries) {
        var builtins =
                RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var codecRegistries = new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(
                        builtins.registries()
                                .filter(entry -> registries.lookup(entry.key()).isEmpty()),
                        registries.registries()))
                .freeze();
        var values = new java.util.HashMap<
                String, java.util.List<ac.cult.cultac.utils.latency.ClientWorldRegistries.Definition>>();
        for (var definition : SYNCED) worldData(registries, codecRegistries, definition, values);
        return values;
    }

    private static <T> void worldData(
            RegistryAccess access,
            RegistryAccess codecs,
            net.minecraft.resources.RegistryDataLoader.RegistryData<T> definition,
            java.util.Map<String, java.util.List<ac.cult.cultac.utils.latency.ClientWorldRegistries.Definition>>
                    values) {
        String name = definition.key().identifier().toString();
        var registry = access.lookupOrThrow(definition.key());
        var nbtOps = net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, codecs);
        var jsonOps = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, codecs);
        values.put(
                name,
                registry.listElements()
                        .sorted(java.util.Comparator.comparingInt(holder -> registry.getId(holder.value())))
                        .map(holder -> {
                            var data = definition
                                    .elementCodec()
                                    .encodeStart(nbtOps, holder.value())
                                    .getOrThrow();
                            String json = definition
                                    .elementCodec()
                                    .encodeStart(jsonOps, holder.value())
                                    .getOrThrow()
                                    .toString();
                            return new ac.cult.cultac.utils.latency.ClientWorldRegistries.Definition(
                                    holder.key().identifier().toString(),
                                    new ac.cult.blocksim.environment.ClientWorldDefaults.Entry(
                                            (ac.cult.blocksim.data.nbt.NbtValue.Compound)
                                                    ac.cult.blocksim.data.nbt.CanonicalSnbt.parse(data.toString()),
                                            json));
                        })
                        .toList());
    }

    /** Each synchronized registry, then each of its tags followed by that tag's members. */
    private static Object[] tagMembers(RegistryAccess registries) {
        var members = new java.util.ArrayList<Object>();
        for (var definition : SYNCED) {
            var registry = registries.lookupOrThrow(definition.key());
            members.add(registry);
            registry.getTags().forEach(tag -> {
                members.add(tag);
                tag.forEach(members::add);
            });
        }
        return members.toArray();
    }

    private static ac.cult.cultac.utils.latency.ClientWorldRegistries.Data data(
            java.util.Map<String, java.util.List<ac.cult.cultac.utils.latency.ClientWorldRegistries.Definition>> values,
            Object[] tagMembers) {
        var tags = new java.util.HashMap<String, java.util.Map<String, java.util.List<String>>>();
        java.util.Map<String, java.util.List<String>> namedTags = null;
        java.util.List<String> tagged = null;
        for (Object member : tagMembers) {
            if (member instanceof net.minecraft.core.Registry<?> registry) {
                namedTags = new java.util.HashMap<>();
                tags.put(registry.key().identifier().toString(), namedTags);
            } else if (member instanceof net.minecraft.core.HolderSet.Named<?> tag) {
                tagged = new java.util.ArrayList<>();
                namedTags.put(tag.key().location().toString(), tagged);
            } else {
                tagged.add(((net.minecraft.core.Holder<?>) member)
                        .unwrapKey()
                        .orElseThrow()
                        .identifier()
                        .toString());
            }
        }
        return new ac.cult.cultac.utils.latency.ClientWorldRegistries.Data(values, tags);
    }
}
