package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.model.Commit;
import com.pocketgit.services.LogService;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

class HistoryForestTest {
    private String hash(int number) {
        return "%064x".formatted(number);
    }

    private Commit commit(List<String> parents) {
        return new Commit(
                "a".repeat(64),
                parents,
                "Reviewer",
                "reviewer@example.com",
                Instant.EPOCH,
                "fixture");
    }

    @Test
    void thousandsOfBranchTipsReadSharedHistoryOnce() throws Exception {
        var graph = new HashMap<String, Commit>();
        var roots = new ArrayList<String>();
        var reads = new HashMap<String, Integer>();
        for (int i = 1; i <= 2_000; i++)
            graph.put(hash(i), commit(i == 1 ? List.of() : List.of(hash(i - 1))));
        for (int i = 2_001; i <= 4_000; i++) {
            graph.put(hash(i), commit(List.of(hash(2_000))));
            roots.add(hash(i));
        }
        var entries =
                new LogService()
                        .traverseRoots(
                                roots,
                                id -> {
                                    reads.merge(id, 1, Integer::sum);
                                    return graph.get(id);
                                });
        assertEquals(4_000, entries.size());
        assertEquals(4_000, reads.size());
        assertTrue(reads.values().stream().allMatch(count -> count == 1));
    }

    @Test
    void cyclesAndMissingParentsInLaterRootsAreStillDetected() throws Exception {
        var graph = new HashMap<String, Commit>();
        graph.put(hash(1), commit(List.of()));
        graph.put(hash(2), commit(List.of(hash(3))));
        graph.put(hash(3), commit(List.of(hash(2))));
        var service = new LogService();
        assertTrue(
                assertThrows(
                                IOException.class,
                                () -> service.traverseRoots(List.of(hash(1), hash(2)), graph::get))
                        .getMessage()
                        .contains("cycle"));
        graph.remove(hash(3));
        assertTrue(
                assertThrows(
                                IOException.class,
                                () ->
                                        service.traverseRoots(
                                                List.of(hash(1), hash(2)),
                                                id -> {
                                                    if (!graph.containsKey(id))
                                                        throw new IOException("missing parent");
                                                    return graph.get(id);
                                                }))
                        .getMessage()
                        .contains("missing parent"));
    }

    @Test
    void emptyAndDuplicateRootsAreHarmlessAndPreserveStoredParentOrder() throws Exception {
        var graph =
                java.util.Map.of(
                        hash(1),
                        commit(List.of()),
                        hash(2),
                        commit(List.of(hash(1))),
                        hash(3),
                        commit(List.of(hash(2), hash(1))));
        var service = new LogService();
        assertEquals(List.of(), service.traverseRoots(List.of(), graph::get));
        assertEquals(
                List.of(hash(3), hash(2), hash(1)),
                service.traverseRoots(List.of(hash(3), hash(2), hash(3)), graph::get).stream()
                        .map(LogService.Entry::hash)
                        .toList());
    }
}
