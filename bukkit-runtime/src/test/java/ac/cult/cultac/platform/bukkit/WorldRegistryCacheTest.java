package ac.cult.cultac.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorldRegistryCacheTest {
    private final WorldRegistryCache<Object, String, List<Object>> cache = new WorldRegistryCache<>();
    private final AtomicInteger encodes = new AtomicInteger();
    private final AtomicInteger builds = new AtomicInteger();

    private List<Object> read(Object registries, Object... tagMembers) {
        return cache.get(registries, tagMembers, access -> "values#" + encodes.incrementAndGet(), (values, members) -> {
            builds.incrementAndGet();
            return List.of(values, List.of(members));
        });
    }

    @Test
    void aLoginReusesTheDataWhileNothingChanged() {
        Object registries = new Object();
        Object registry = new Object();
        Object tag = new Object();
        Object member = new Object();
        var first = read(registries, registry, tag, member);
        var second = read(registries, registry, tag, member);
        assertSame(first, second);
        assertEquals(1, encodes.get());
        assertEquals(1, builds.get());
    }

    @Test
    void aReboundTagRebuildsTheTagsButKeepsTheEncodedValues() {
        Object registries = new Object();
        Object registry = new Object();
        Object tag = new Object();
        Object member = new Object();
        var first = read(registries, registry, tag, member);
        // A reload binds the same tag to other members, adds members, or adds a tag.
        var rebound = read(registries, registry, tag, new Object());
        var grown = read(registries, registry, tag, member, new Object());
        var added = read(registries, registry, tag, member, new Object(), new Object());
        assertNotSame(first, rebound);
        assertNotSame(rebound, grown);
        assertNotSame(grown, added);
        assertEquals(1, encodes.get());
        assertEquals(4, builds.get());
        assertSame(first.get(0), added.get(0));
    }

    @Test
    void equalButDistinctMembersAreAChange() {
        Object registries = new Object();
        var first = read(registries, "minecraft:worldgen/biome");
        var second = read(registries, new String("minecraft:worldgen/biome"));
        assertNotSame(first, second);
        assertEquals(2, builds.get());
    }

    @Test
    void otherRegistriesEncodeAgain() {
        Object registry = new Object();
        var first = read(new Object(), registry);
        var second = read(new Object(), registry);
        assertNotSame(first, second);
        assertEquals(2, encodes.get());
    }
}
