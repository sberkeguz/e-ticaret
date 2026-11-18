package com.sarikaya.e_ticaret.payment.events;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

import java.net.ConnectException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentBrokerTest {
    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

    @Test
    void reachableBrokerIsUp() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        when(connection.isOpen()).thenReturn(true);
        when(factory.createConnection()).thenReturn(connection);
        PaymentBroker broker = new PaymentBroker(factory, Clock.fixed(NOW, ZoneOffset.UTC));

        broker.check();

        assertTrue(broker.isUp());
        assertNull(broker.downSince());
    }

    @Test
    void unreachableBrokerIsDownSinceFirstFailureUntilItReturns() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.createConnection()).thenThrow(new AmqpConnectException(new ConnectException("refused")));
        PaymentBroker broker = new PaymentBroker(factory, Clock.fixed(NOW, ZoneOffset.UTC));

        broker.check();
        broker.check();

        assertFalse(broker.isUp());
        assertEquals(NOW, broker.downSince(), "ilk başarısız denemeden beri kapalı");

        broker.record(true);
        assertTrue(broker.isUp());
    }

    @Test
    void hangingConnectionAttemptCountsAsDown() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.createConnection()).thenAnswer(invocation -> {
            Thread.sleep(PaymentBroker.CHECK_TIMEOUT.toMillis() + 1_000);
            return mock(Connection.class);
        });
        PaymentBroker broker = new PaymentBroker(factory, Clock.fixed(NOW, ZoneOffset.UTC));

        broker.check();

        assertFalse(broker.isUp());
    }
}
