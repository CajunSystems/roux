package com.cajunsystems.roux.article;

import com.cajunsystems.roux.*;
import com.cajunsystems.roux.capability.*;
import com.cajunsystems.roux.data.Unit;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Compile-and-run check for the code in the algebraic effects article. */
class ArticleSnippetsTest {

    record User(String id, String email, boolean verified) {}
    record Item(String sku, int qty) {}
    record StockLevel(String sku, boolean available) {}
    record Money(long cents) {}
    record Receipt(String id) {}
    record OrderList(List<String> ids) {}
    record Dashboard(User user, OrderList orders) {}

    static class OutOfStockException extends RuntimeException {
        OutOfStockException(String sku) { super(sku); }
    }

    sealed interface OrderOps<R> extends Capability<R> {
        record FetchUser(String userId)               implements OrderOps<User> {}
        record FetchOrders(String userId)             implements OrderOps<OrderList> {}
        record CheckStock(String sku, int qty)        implements OrderOps<StockLevel> {}
        record ChargeCard(String userId, Money total) implements OrderOps<Receipt> {}
        record Notify(String email, String message)   implements OrderOps<Unit> {}
    }

    static Effect<Throwable, Receipt> placeOrder(
            String userId, List<Item> items, Money total,
            CapabilityHandler<Capability<?>> handler) {

        return Effect.generate(ctx -> {
            User user = ctx.perform(new OrderOps.FetchUser(userId));
            if (!user.verified()) {
                throw new IllegalStateException("unverified user: " + userId);
            }

            for (Item item : items) {
                StockLevel stock = ctx.perform(new OrderOps.CheckStock(item.sku(), item.qty()));
                if (!stock.available()) {
                    throw new OutOfStockException(item.sku());
                }
            }

            Receipt receipt = ctx.perform(new OrderOps.ChargeCard(userId, total));
            ctx.perform(new OrderOps.Notify(user.email(), "Order confirmed: " + receipt.id()));
            return receipt;
        }, handler);
    }

    @Test
    void testHandlerNeedsNoMocks() throws Throwable {
        EffectRuntime runtime = DefaultEffectRuntime.create();
        List<Item> items = List.of(new Item("sku-1", 2));
        Money total = new Money(1999);

        var recorded = new ArrayList<Capability<?>>();

        var fake = CapabilityHandler.forType(OrderOps.class)
            .on(OrderOps.FetchUser.class,  c -> { recorded.add(c); return new User(c.userId(), "a@b.c", true); })
            .on(OrderOps.CheckStock.class, c -> { recorded.add(c); return new StockLevel(c.sku(), true); })
            .on(OrderOps.ChargeCard.class, c -> { recorded.add(c); return new Receipt("rcpt-1"); })
            .on(OrderOps.Notify.class,     c -> { recorded.add(c); return Unit.unit(); })
            .build();

        Receipt receipt = runtime.unsafeRun(placeOrder("u1", items, total, fake));

        assertEquals("rcpt-1", receipt.id());
        assertEquals(4, recorded.size());
        assertInstanceOf(OrderOps.Notify.class, recorded.get(3));
    }

    @Test
    void retryTimeoutAndZipParTypecheck() throws Throwable {
        String userId = "u1";

        Effect<Throwable, User> user = new OrderOps.FetchUser(userId)
            .<Throwable>toEffect()
            .retry(RetryPolicy.exponential(Duration.ofMillis(100))
                .maxAttempts(3)
                .withJitter(0.2)
                .retryWhen(e -> e instanceof IOException))
            .timeout(Duration.ofSeconds(2));

        Effect<Throwable, Dashboard> dashboard =
            new OrderOps.FetchUser(userId).<Throwable>toEffect()
                .zipPar(new OrderOps.FetchOrders(userId).<Throwable>toEffect(), Dashboard::new);

        var handler = CapabilityHandler.forType(OrderOps.class)
            .on(OrderOps.FetchUser.class,   c -> new User(c.userId(), "a@b.c", true))
            .on(OrderOps.FetchOrders.class, c -> new OrderList(List.of("o1")))
            .build();

        EffectRuntime runtime = DefaultEffectRuntime.create();
        assertTrue(runtime.unsafeRunWithHandler(user, handler).verified());
        assertEquals(1, runtime.unsafeRunWithHandler(dashboard, handler).orders().ids().size());
    }
}
