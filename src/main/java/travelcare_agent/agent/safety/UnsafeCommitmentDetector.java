package travelcare_agent.agent.safety;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class UnsafeCommitmentDetector {
    private static final List<Pattern> BLOCKED = List.of(
            Pattern.compile("已经.*退款|退款已到账|取消成功|支付.*完成|联系供应商.*改签"),
            Pattern.compile("一定.*全额退款|无需审核.*(可以退|肯定.*退)|忽略之前.*退款|绕过.*确认"),
            Pattern.compile("调用.*refund.*tool|直接.*退款", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(refund(ed)?|payment|cancellation) (has been|is) (completed|successful)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcontacted (the )?supplier.*(change|rebook)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(ignore previous|bypass.*confirmation|guaranteed full refund)\\b", Pattern.CASE_INSENSITIVE)
    );

    public boolean containsUnsafeCommitment(String text) {
        if (text == null || text.isBlank()) return false;
        return BLOCKED.stream().anyMatch(pattern -> pattern.matcher(text).find());
    }

    public boolean conflictsWithAuthority(String text, String authoritativeDecision) {
        if (text == null || authoritativeDecision == null) return false;
        String normalized = text.toLowerCase(Locale.ROOT);
        if ("INELIGIBLE".equals(authoritativeDecision)) {
            return normalized.contains("is eligible")
                    || normalized.contains("can definitely be refunded")
                    || normalized.contains("可以退")
                    || normalized.contains("能够退款")
                    || normalized.contains("全额退款");
        }
        if ("ELIGIBLE".equals(authoritativeDecision)) {
            return normalized.contains("not eligible") || normalized.contains("不能退款");
        }
        return false;
    }

    public boolean treatsRagAsOrderFact(String text, boolean knowledgeOperation) {
        if (knowledgeOperation || text == null) return false;
        String normalized = text.toLowerCase(Locale.ROOT);
        boolean citesRag = normalized.contains("rag") || normalized.contains("chunk")
                || normalized.contains("retrieved context") || normalized.contains("检索内容");
        boolean claimsOrderFact = normalized.contains("order status") || normalized.contains("订单状态")
                || normalized.contains("payment status") || normalized.contains("支付状态")
                || normalized.contains("refund status") || normalized.contains("退款状态");
        return citesRag && claimsOrderFact;
    }
}
