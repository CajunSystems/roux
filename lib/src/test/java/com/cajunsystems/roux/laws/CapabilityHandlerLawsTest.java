package com.cajunsystems.roux.laws;

import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies algebraic laws for CapabilityHandler composition.
 *
 * <p>Laws verified:
 * <ol>
 *   <li>orElse identity:       {@code handler.orElse(neverHandles)} ≡ {@code handler}
 *   <li>compose associativity: {@code compose(h1, compose(h2, h3))} ≡ {@code compose(h1, h2, h3)}
 * </ol>
 *
 * <p>These laws test {@code handle()} dispatch directly — no Effect runtime needed.
 */
class CapabilityHandlerLawsTest {

    // -- Test capability domain --

    sealed interface TestCapability<R> extends Capability<R> {
        record Alpha(String value)  implements TestCapability<String>  {}
        record Beta(int value)      implements TestCapability<Integer> {}
        record Gamma(boolean value) implements TestCapability<Boolean> {}
    }

    private static CapabilityHandler<Capability<?>> alphaHandler() {
        return CapabilityHandler.builder()
                .on(TestCapability.Alpha.class, c -> "alpha:" + c.value())
                .build();
    }

    private static CapabilityHandler<Capability<?>> betaHandler() {
        return CapabilityHandler.builder()
                .on(TestCapability.Beta.class, c -> c.value() * 2)
                .build();
    }

    private static CapabilityHandler<Capability<?>> gammaHandler() {
        return CapabilityHandler.builder()
                .on(TestCapability.Gamma.class, c -> !c.value())
                .build();
    }

    /** A handler that always signals "not handled". */
    private static final CapabilityHandler<Capability<?>> neverHandles =
            new CapabilityHandler<>() {
                @Override
                public <R> R handle(Capability<?> capability) {
                    throw new UnsupportedOperationException("never handles");
                }
            };

    // -----------------------------------------------------------------------
    // C1: orElse identity — handler.orElse(neverHandles) ≡ handler
    // -----------------------------------------------------------------------

    @Test
    void orElseIdentity_handledCapability() throws Exception {
        CapabilityHandler<Capability<?>> h = alphaHandler();
        CapabilityHandler<Capability<?>> composed = h.orElse(neverHandles);

        TestCapability.Alpha cap = new TestCapability.Alpha("test");
        assertEquals(
                (String) h.handle(cap),
                (String) composed.handle(cap),
                "orElse identity: result should match original handler");
    }

    @Test
    void orElseIdentity_unhandledCapabilityThrowsSameException() {
        CapabilityHandler<Capability<?>> h = alphaHandler();
        CapabilityHandler<Capability<?>> composed = h.orElse(neverHandles);

        // Both should throw UnsupportedOperationException for an unregistered capability
        TestCapability.Beta cap = new TestCapability.Beta(5);
        assertThrows(UnsupportedOperationException.class, () -> h.handle(cap));
        assertThrows(UnsupportedOperationException.class, () -> composed.handle(cap));
    }

    // -----------------------------------------------------------------------
    // C2: compose associativity — compose(h1, compose(h2, h3)) ≡ compose(h1, h2, h3)
    // -----------------------------------------------------------------------

    @Test
    void composeAssociativity_alphaCapability() throws Exception {
        CapabilityHandler<Capability<?>> flat   = CapabilityHandler.compose(alphaHandler(), betaHandler(), gammaHandler());
        CapabilityHandler<Capability<?>> nested = CapabilityHandler.compose(alphaHandler(), CapabilityHandler.compose(betaHandler(), gammaHandler()));

        TestCapability.Alpha cap = new TestCapability.Alpha("hello");
        assertEquals(
                (String) flat.handle(cap),
                (String) nested.handle(cap),
                "compose associativity: alpha capability");
    }

    @Test
    void composeAssociativity_betaCapability() throws Exception {
        CapabilityHandler<Capability<?>> flat   = CapabilityHandler.compose(alphaHandler(), betaHandler(), gammaHandler());
        CapabilityHandler<Capability<?>> nested = CapabilityHandler.compose(alphaHandler(), CapabilityHandler.compose(betaHandler(), gammaHandler()));

        TestCapability.Beta cap = new TestCapability.Beta(7);
        assertEquals(
                (Integer) flat.handle(cap),
                (Integer) nested.handle(cap),
                "compose associativity: beta capability");
    }

    @Test
    void composeAssociativity_gammaCapability() throws Exception {
        CapabilityHandler<Capability<?>> flat   = CapabilityHandler.compose(alphaHandler(), betaHandler(), gammaHandler());
        CapabilityHandler<Capability<?>> nested = CapabilityHandler.compose(alphaHandler(), CapabilityHandler.compose(betaHandler(), gammaHandler()));

        TestCapability.Gamma cap = new TestCapability.Gamma(true);
        assertEquals(
                (Boolean) flat.handle(cap),
                (Boolean) nested.handle(cap),
                "compose associativity: gamma capability");
    }

    @Test
    void composeAssociativity_unhandledCapabilityThrowsSameException() {
        CapabilityHandler<Capability<?>> flat   = CapabilityHandler.compose(alphaHandler(), betaHandler());
        CapabilityHandler<Capability<?>> nested = CapabilityHandler.compose(alphaHandler(), CapabilityHandler.compose(betaHandler()));

        // Gamma not registered in either — both should throw
        assertThrows(UnsupportedOperationException.class, () -> flat.handle(new TestCapability.Gamma(false)));
        assertThrows(UnsupportedOperationException.class, () -> nested.handle(new TestCapability.Gamma(false)));
    }
}
