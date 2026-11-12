package com.sarikaya.e_ticaret.payment.events;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageRetriesTest {
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final RabbitOperations operations = mock(RabbitOperations.class);
    private final MessageRetries retries = new MessageRetries(rabbit);

    MessageRetriesTest() {
        when(rabbit.invoke(any())).thenAnswer(invocation -> {
            RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(operations);
        });
    }

    @Test
    void firstFailureWaitsFiveSecondsThenThirtyThenTwoMinutes() {
        assertEquals("backend.payment-events.retry.1", retryAfter(0));
        assertEquals("backend.payment-events.retry.2", retryAfter(1));
        assertEquals("backend.payment-events.retry.3", retryAfter(2));
    }

    @Test
    void afterThreeRetriesMessageGoesToDeadLetters() {
        assertEquals("backend.payment-events.dlq", retryAfter(3));
    }

    @Test
    void permanentFailureGoesStraightToDeadLetters() {
        Message message = message(0);

        retries.handleFailure(message, PaymentEventException.deadLetter("ikinci ödeme"), false);

        verify(operations).send(eq(""), eq("backend.payment-events.dlq"), any(Message.class));
        assertEquals("ikinci ödeme", message.getMessageProperties().getHeaders().get(MessageRetries.ERROR));
    }

    @Test
    void messageIsLetGoOnlyAfterRabbitConfirms() {
        retries.handleFailure(message(0), new RuntimeException("db yok"), true);

        verify(operations).waitForConfirmsOrDie(anyLong());
    }

    private String retryAfter(int retriesSoFar) {
        org.mockito.Mockito.clearInvocations(operations);
        retries.handleFailure(message(retriesSoFar), new RuntimeException("geçici"), true);
        ArgumentCaptor<String> queue = ArgumentCaptor.forClass(String.class);
        verify(operations).send(eq(""), queue.capture(), any(Message.class));
        return queue.getValue();
    }

    private static Message message(int retriesSoFar) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("m-1");
        if (retriesSoFar > 0) {
            properties.setHeader(MessageRetries.RETRY_COUNT, retriesSoFar);
        }
        return new Message("{}".getBytes(), properties);
    }
}
