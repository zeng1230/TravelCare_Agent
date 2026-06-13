package travelcare_agent.dryrun;

public class DryRunSideEffectBlockedException extends RuntimeException {
    public DryRunSideEffectBlockedException(SideEffectOperation operation) {
        super("DRY_RUN_SIDE_EFFECT_BLOCKED:" + operation.name());
    }
}
