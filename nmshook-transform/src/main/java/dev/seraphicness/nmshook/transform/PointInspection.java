package dev.seraphicness.nmshook.transform;

import java.util.List;

/**
 * Result of scanning the original bytecode of a target method for a {@link PointSpec}.
 *
 * @param methods    number of methods matched by the selector
 * @param candidates instructions of the right kind (before ordinal filtering)
 * @param matches    instructions that would be rewritten
 * @param seenCalls  INVOKE kinds: every call found in the method(s), for error messages
 */
public record PointInspection(int methods, int candidates, int matches, List<String> seenCalls) {
}
