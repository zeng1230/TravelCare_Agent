package travelcare_agent.trace;

public enum TraceSnapshotType {
    USER_INPUT,
    CONTEXT_SUMMARY,
    RETRIEVAL_SUMMARY,
    MODEL_INPUT,
    MODEL_OUTPUT,
    TOOL_REQUEST,
    TOOL_RESULT,
    POLICY_INPUT,
    POLICY_DECISION,
    WORKFLOW_PATH,
    FINAL_OUTPUT
}
