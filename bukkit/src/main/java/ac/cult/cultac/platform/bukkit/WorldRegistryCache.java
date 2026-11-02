package ac.cult.cultac.platform.bukkit;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Reuses the world registry data built for one login on the next. Values are keyed by the registry access: the host's
 * synchronized registries are frozen once the server has loaded, so their elements never change under the same
 * access. Tags are keyed by the identities of every registry, tag and member read from it, because a resource reload
 * rebinds tag contents inside the same registries.
 */
final class WorldRegistryCache<A, V, D> {
    private volatile Entry<A, V, D> entry;

    /**
     * {@code tagMembers} is read from {@code registries} for this call, and {@code data} builds from it alone, so a
     * reload that lands part way through is caught by the next call's snapshot instead of being cached as current.
     */
    D get(A registries, Object[] tagMembers, Function<A, V> values, BiFunction<V, Object[], D> data) {
        Entry<A, V, D> current = entry;
        V built;
        if (current != null && current.registries == registries) {
            if (sameIdentities(current.tagMembers, tagMembers)) return current.data;
            built = current.values;
        } else {
            built = values.apply(registries);
        }
        D result = data.apply(built, tagMembers);
        entry = new Entry<>(registries, built, tagMembers, result);
        return result;
    }

    private static boolean sameIdentities(Object[] cached, Object[] read) {
        if (cached.length != read.length) return false;
        for (int index = 0; index < cached.length; index++) if (cached[index] != read[index]) return false;
        return true;
    }

    private record Entry<A, V, D>(A registries, V values, Object[] tagMembers, D data) {}
}
