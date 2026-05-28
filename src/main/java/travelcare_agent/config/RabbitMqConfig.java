package travelcare_agent.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE_WORKFLOW = "workflow.exchange";
    public static final String QUEUE_WORKFLOW_TASKS = "workflow.tasks.queue";
    public static final String ROUTING_KEY_WORKFLOW_TASKS = "workflow.tasks.routing.key";

    @Bean
    public DirectExchange workflowExchange() {
        return new DirectExchange(EXCHANGE_WORKFLOW);
    }

    @Bean
    public Queue workflowTasksQueue() {
        return new Queue(QUEUE_WORKFLOW_TASKS, true);
    }

    @Bean
    public Binding bindingWorkflowTasks(Queue workflowTasksQueue, DirectExchange workflowExchange) {
        return BindingBuilder.bind(workflowTasksQueue).to(workflowExchange).with(ROUTING_KEY_WORKFLOW_TASKS);
    }
}
