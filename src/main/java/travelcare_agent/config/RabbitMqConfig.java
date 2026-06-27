package travelcare_agent.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE_WORKFLOW = "workflow.exchange";
    public static final String QUEUE_WORKFLOW_TASKS = "workflow.tasks.reliable.queue";
    public static final String ROUTING_KEY_WORKFLOW_TASKS = "workflow.tasks.routing.key";
    public static final String EXCHANGE_WORKFLOW_DLX = "workflow.dlx";
    public static final String QUEUE_WORKFLOW_TASKS_DLQ = "workflow.tasks.dlq";
    public static final String ROUTING_KEY_WORKFLOW_TASKS_DLQ = "workflow.tasks.dlq.routing.key";

    @Bean
    public DirectExchange workflowExchange() {
        return new DirectExchange(EXCHANGE_WORKFLOW);
    }

    @Bean
    public DirectExchange workflowDeadLetterExchange() {
        return new DirectExchange(EXCHANGE_WORKFLOW_DLX);
    }

    @Bean
    public Queue workflowTasksQueue() {
        return QueueBuilder.durable(QUEUE_WORKFLOW_TASKS)
                .deadLetterExchange(EXCHANGE_WORKFLOW_DLX)
                .deadLetterRoutingKey(ROUTING_KEY_WORKFLOW_TASKS_DLQ)
                .build();
    }

    @Bean
    public Queue workflowTasksDeadLetterQueue() {
        return QueueBuilder.durable(QUEUE_WORKFLOW_TASKS_DLQ).build();
    }

    @Bean
    public Binding bindingWorkflowTasks(Queue workflowTasksQueue, DirectExchange workflowExchange) {
        return BindingBuilder.bind(workflowTasksQueue).to(workflowExchange).with(ROUTING_KEY_WORKFLOW_TASKS);
    }

    @Bean
    public Binding bindingWorkflowTasksDeadLetter(
            Queue workflowTasksDeadLetterQueue,
            DirectExchange workflowDeadLetterExchange) {
        return BindingBuilder.bind(workflowTasksDeadLetterQueue)
                .to(workflowDeadLetterExchange)
                .with(ROUTING_KEY_WORKFLOW_TASKS_DLQ);
    }
}
