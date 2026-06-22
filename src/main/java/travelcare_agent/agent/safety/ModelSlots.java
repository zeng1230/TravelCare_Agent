package travelcare_agent.agent.safety;

public record ModelSlots(String orderNo, Long orderId) {
    public static ModelSlots empty() {
        return new ModelSlots(null, null);
    }
}
