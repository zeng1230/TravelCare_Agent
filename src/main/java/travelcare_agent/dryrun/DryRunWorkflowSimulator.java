package travelcare_agent.dryrun;

import org.springframework.stereotype.Component;
import travelcare_agent.adapter.order.OrderSnapshot;
import travelcare_agent.enums.RefundCaseStatus;
import travelcare_agent.policy.RefundEligibilityDecision;

import java.util.List;
import java.util.Map;

@Component
public class DryRunWorkflowSimulator {
    public Simulation simulate(OrderSnapshot order, RefundEligibilityDecision decision){
        String answer=decision.status()==RefundCaseStatus.ELIGIBLE
                ? "Order "+order.orderNo()+" is eligible for refund inquiry. Refund amount can be reviewed up to "+order.paidAmount()+"."
                : "Order "+order.orderNo()+" is not eligible for refund inquiry because "+decision.reason()+".";
        List<Map<String,String>> path=List.of(
                step("COLLECTING_ORDER_REFERENCE"),step("QUERYING_ORDER"),step("CHECKING_REFUND_RULES"),step("RESPONDED"));
        return new Simulation("RESPONDED",path,answer);
    }
    private static Map<String,String> step(String name){return Map.of("name",name,"status","SUCCEEDED","errorCode","");}
    public record Simulation(String status,List<Map<String,String>> steps,String deterministicAnswer){}
}
