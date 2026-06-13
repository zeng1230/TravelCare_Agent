package travelcare_agent.dryrun;

public final class DryRunContextHolder {
    private static final ThreadLocal<DryRunContext> CURRENT = new ThreadLocal<>();

    private DryRunContextHolder() {
    }

    public static DryRunContext current() {
        return CURRENT.get();
    }

    public static Scope attach(DryRunContext context) {
        DryRunContext previous = CURRENT.get();
        CURRENT.set(context);
        return () -> {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        };
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
