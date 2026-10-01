package com.pocketgit.services;

import com.pocketgit.model.Index;
import com.pocketgit.model.IndexEntry;
import java.util.List;

public record CheckoutPlan(
        List<IndexEntry> filesToWrite,
        List<String> filesToDelete,
        List<String> conflicts,
        Index targetIndex) {
    public CheckoutPlan {
        filesToWrite = List.copyOf(filesToWrite);
        filesToDelete = List.copyOf(filesToDelete);
        conflicts = List.copyOf(conflicts);
    }
}
