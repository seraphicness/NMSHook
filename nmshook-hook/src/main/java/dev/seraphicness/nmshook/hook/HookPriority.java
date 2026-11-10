package dev.seraphicness.nmshook.hook;

/**
 * Hook priority Value Object.
 * A lower value indicates a higher priority.
 */
public record HookPriority(int value) implements Comparable<HookPriority> {

    public static final HookPriority HIGHEST = new HookPriority(0);
    public static final HookPriority HIGH = new HookPriority(250);
    public static final HookPriority NORMAL = new HookPriority(1000);
    public static final HookPriority LOW = new HookPriority(1750);
    public static final HookPriority LOWEST = new HookPriority(2000);

    @Override
    public int compareTo(HookPriority other) {
        return Integer.compare(this.value, other.value);
    }
}
