# Example: Order Processing Pipeline

A realistic e-commerce order workflow demonstrating how capabilities, effects, and
the `0.2.0` combinators compose into a complete, testable application.

---

## Domain Model

```java
record User(String id, String email, boolean verified) {}
record Item(String sku, int quantity) {}
record InventoryResult(String sku, boolean available, int stock) {}
record PaymentResult(String txId, boolean success) {}
record Order(String orderId, String userId, List<Item> items, PaymentResult payment) {}
```

---

## Capability Definitions

All I/O is described as pure data. No implementation leaks into business logic.

```java
sealed interface OrderCapability<R> extends Capability<R> {

    // User
    record FetchUser(String userId) implements OrderCapability<User> {}

    // Inventory — one capability per SKU so we can check them in parallel
    record CheckInventory(String sku, int qty) implements OrderCapability<InventoryResult> {}

    // Payment
    record ChargeCard(String userId, double amount) implements OrderCapability<PaymentResult> {}
    record RefundCard(String txId) implements OrderCapability<Void> {}

    // Notifications
    record SendConfirmation(String email, Order order) implements OrderCapability<Void> {}

    // Audit
    record AuditLog(String event, Object payload) implements OrderCapability<Void> {}
}
```

---

## Business Logic (pure, no handler needed at definition time)

```java
import static com.cajunsystems.roux.Effects.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

public class OrderService {

    /**
     * Place an order for a user.
     *
     * Steps:
     *  1. Fetch user profile and check all inventory concurrently.
     *  2. Fail fast if user is unverified or any item is out of stock.
     *  3. Charge the card — retry up to 2 times on transient failure.
     *  4. Persist the order and send a confirmation email in parallel.
     *  5. Emit an audit log regardless of outcome (via tapError).
     */
    public static Effect<Throwable, Order> placeOrder(
            String userId,
            List<Item> items,
            double total
    ) {
        // 1. Fetch user + check all inventory concurrently
        Effect<Throwable, User> fetchUser =
                new OrderCapability.FetchUser(userId).toEffect();

        Effect<Throwable, List<InventoryResult>> checkInventory =
                parAll(items.stream()
                        .map(item -> new OrderCapability.CheckInventory(item.sku(), item.quantity())
                                .<Throwable, InventoryResult>toEffect())
                        .toList());

        return fetchUser
                .zipPar(checkInventory, (user, inventory) -> {

                    // 2. Validate in one place, keep it readable
                    if (!user.verified()) {
                        throw new IllegalStateException("User %s is not verified".formatted(userId));
                    }
                    List<String> outOfStock = inventory.stream()
                            .filter(r -> !r.available())
                            .map(InventoryResult::sku)
                            .toList();
                    if (!outOfStock.isEmpty()) {
                        throw new IllegalStateException("Out of stock: " + outOfStock);
                    }
                    return user; // pass user forward
                })

                // 3. Charge — retry 2 extra times, each attempt must finish in 5 s
                .flatMap(user ->
                        new OrderCapability.ChargeCard(userId, total)
                                .toEffect()
                                .timeout(Duration.ofSeconds(5))
                                .retry(2)
                                .tap(payment -> {
                                    // only runs on success — great for metrics
                                    if (!payment.success()) {
                                        throw new IllegalStateException("Payment declined: " + payment.txId());
                                    }
                                })
                                .map(payment -> new Order(
                                        UUID.randomUUID().toString(),
                                        user.id(),
                                        items,
                                        payment
                                ))
                )

                // 4. Persist + notify in parallel (fire-and-forget pattern)
                .flatMap(order -> {
                    Effect<Throwable, Void> notify =
                            new OrderCapability.SendConfirmation(order.userId(), order).toEffect();

                    Effect<Throwable, Void> audit =
                            new OrderCapability.AuditLog("order.placed", order).toEffect();

                    // Run both without blocking the caller on either
                    return notify.zipPar(audit, (__, ___) -> order);
                })

                // 5. Always audit failures too — tapError doesn't change the error
                .tapError(err ->
                        System.err.printf("[audit] order failed for user=%s reason=%s%n",
                                userId, err.getMessage())
                );
    }
}
```

---

## Production Handler

```java
public class ProductionOrderHandler implements CapabilityHandler<OrderCapability<?>> {

    private final UserRepository users;
    private final InventoryService inventory;
    private final PaymentGateway payments;
    private final EmailService email;
    private final AuditLogger auditLogger;

    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(OrderCapability<?> cap) throws Exception {
        return switch (cap) {
            case OrderCapability.FetchUser f ->
                    (R) users.findById(f.userId());

            case OrderCapability.CheckInventory c ->
                    (R) inventory.check(c.sku(), c.qty());

            case OrderCapability.ChargeCard cc ->
                    (R) payments.charge(cc.userId(), cc.amount());

            case OrderCapability.RefundCard rf -> {
                payments.refund(rf.txId());
                yield (R) null;
            }

            case OrderCapability.SendConfirmation sc -> {
                email.send(sc.email(), "Order confirmed", renderTemplate(sc.order()));
                yield (R) null;
            }

            case OrderCapability.AuditLog al -> {
                auditLogger.log(al.event(), al.payload());
                yield (R) null;
            }
        };
    }
}
```

---

## Test Handler (using `CapabilityHandler.builder()`)

The `builder()` API lets you define in-line test doubles without subclassing:

```java
@Test
void placeOrder_successPath() throws Throwable {
    // ---- test doubles ----
    var users     = new HashMap<String, User>();
    var inventory = new HashMap<String, Integer>();
    var payments  = new ArrayList<String>();          // txIds charged
    var emails    = new ArrayList<String>();          // emails sent

    users.put("u1", new User("u1", "alice@example.com", true));
    inventory.put("SKU-A", 10);
    inventory.put("SKU-B", 5);

    CapabilityHandler<Capability<?>> testHandler = CapabilityHandler.builder()

        .on(OrderCapability.FetchUser.class, cap ->
                Optional.ofNullable(users.get(cap.userId()))
                        .orElseThrow(() -> new RuntimeException("user not found")))

        .on(OrderCapability.CheckInventory.class, cap -> {
            int stock = inventory.getOrDefault(cap.sku(), 0);
            return new InventoryResult(cap.sku(), stock >= cap.qty(), stock);
        })

        .on(OrderCapability.ChargeCard.class, cap -> {
            String txId = "TX-" + UUID.randomUUID();
            payments.add(txId);
            return new PaymentResult(txId, true);
        })

        .on(OrderCapability.SendConfirmation.class, cap -> {
            emails.add(cap.email());
            return null;
        })

        .on(OrderCapability.AuditLog.class, cap -> null)   // swallow in tests

        .build();

    // ---- run ----
    try (var runtime = DefaultEffectRuntime.create()) {
        Order order = runtime.unsafeRunWithHandler(
                OrderService.placeOrder("u1", List.of(new Item("SKU-A", 2)), 49.99),
                testHandler
        );

        assertNotNull(order.orderId());
        assertEquals("u1", order.userId());
        assertEquals(1, payments.size());
        assertEquals(List.of("alice@example.com"), emails);
    }
}

@Test
void placeOrder_outOfStock_failsFast() {
    var users = Map.of("u1", new User("u1", "alice@example.com", true));

    CapabilityHandler<Capability<?>> testHandler = CapabilityHandler.builder()
        .on(OrderCapability.FetchUser.class,     cap -> users.get(cap.userId()))
        .on(OrderCapability.CheckInventory.class, cap ->
                new InventoryResult(cap.sku(), false, 0)) // always out of stock
        .on(OrderCapability.AuditLog.class, cap -> null)
        .build();

    try (var runtime = DefaultEffectRuntime.create()) {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                runtime.unsafeRunWithHandler(
                        OrderService.placeOrder("u1", List.of(new Item("SKU-X", 1)), 9.99),
                        testHandler
                )
        );
        assertTrue(ex.getMessage().contains("Out of stock"));
    }
}
```

---

## Key Patterns Demonstrated

| Pattern | Where |
|---------|-------|
| Parallel fetch (`zipPar` / `parAll`) | Fetch user + all inventory at once |
| `timeout` per external call | Payment charge capped at 5 s |
| `retry` on transient failure | Payment retried up to 2 extra times |
| `tap` for inline validation | Declined payment throws without breaking the chain type |
| `tapError` for observability | Audit log always emitted on failure |
| `CapabilityHandler.builder()` | Clean test handler with no boilerplate |
| Type-safe pattern matching in handler | `switch` on sealed `OrderCapability` |
