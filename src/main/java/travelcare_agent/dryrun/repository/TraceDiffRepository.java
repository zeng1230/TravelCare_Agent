package travelcare_agent.dryrun.repository;

import travelcare_agent.dryrun.entity.TraceDiff;

import java.util.Optional;

public interface TraceDiffRepository {
    TraceDiff save(TraceDiff value);
    Optional<TraceDiff> find(String originalTraceId, String dryRunTraceId);
}
