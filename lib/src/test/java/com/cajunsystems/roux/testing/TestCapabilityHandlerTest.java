package com.cajunsystems.roux.testing;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.Capability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.cajunsystems.roux.testing.EffectAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class TestCapabilityHandlerTest {

    // Test capability domain
    sealed interface UserCap<R> extends Capability<R> {
        record FetchUser(String userId) implements UserCap<String> {}
        record DeleteUser(String userId) implements UserCap<Boolean> {}
    }

    private TestRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = TestRuntime.create();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    // -----------------------------------------------------------------------
    // Stub basics
    // -----------------------------------------------------------------------

    @Test
    void stub_returnsConfiguredValue() {
        TestCapabilityHandler handler = TestCapabilityHandler.builder()
                .stub(UserCap.FetchUser.class, req -> "Alice")
                .build();

        assertThat(new UserCap.FetchUser("u1").toEffect(), handler)
                .succeedsWith("Alice");
    }

    @Test
    void stub_canReadCapabilityFields() {
        TestCapabilityHandler handler = TestCapabilityHandler.builder()
                .stub(UserCap.FetchUser.class, req -> "Hello, " + req.userId())
                .build();

        assertThat(new UserCap.FetchUser("alice").toEffect(), handler)
                .succeedsWith("Hello, alice");
    }

    @Test
    void stub_unregisteredCapability_fails() {
        TestCapabilityHandler handler = TestCapabilityHandler.builder().build();

        // No stub for FetchUser — should fail
        assertThat(new UserCap.FetchUser("u1").toEffect(), handler).fails();
    }

    // -----------------------------------------------------------------------
    // neverCalled
    // -----------------------------------------------------------------------

    @Test
    void verify_neverCalled_passesWhenNoCalls() {
        TestCapabilityHandler handler = TestCapabilityHandler.builder()
                .stub(UserCap.FetchUser.class, req -> "Alice")
                .build();

        // No effects run — FetchUser was never invoked
        handler.verify(UserCap.FetchUser.class).neverCalled();
    }

    @Test
    void verify_neverCalled_failsWhenCalled() throws Throwable {
        TestCapabilityHandler handler = TestCapabilityHandler.builder()
                .stub(UserCap.FetchUser.class, req -> "Alice")
                .build();

        runtime.unsafeRunWithHandler(new UserCap.FetchUser("u1").toEffect(), handler);

        assertThrows(AssertionError.class, () ->
                handler.verify(UserCap.FetchUser.class).neverCalled());
    }

    // -----------------------------------------------------------------------
    // calledOnce / calledTimes
    // -----------------------------------------------------------------------

    @Test
    void verify_calledOnce_passesAfterOneCall() throws Throwable {
        TestCapabilityHandler handler = TestCapabilityHandler.builder()
                .stub(UserCap.FetchUser.class, req -> "Alice")
                .build();

        runtime.unsafeRunWithHandler(new UserCap.FetchUser("u1").toEffect(), handler);

        handler.verify(UserCap.FetchUser.class).calledOnce();
    }

    @Test
    void verify_calledTimes_tracksMultipleCalls() throws Throwable {
        TestCapabilityHandler handler = TestCapabilityHandler.builder()
                .stub(UserCap.FetchUser.class, req -> "Alice-" + req.userId())
                .build();

        Effect<Throwable, String> effect = new UserCap.FetchUser("u1").<Throwable>toEffect()
                .flatMap(__ -> new UserCap.FetchUser("u2").toEffect());
        runtime.unsafeRunWithHandler(effect, handler);

        handler.verify(UserCap.FetchUser.class).calledTimes(2);
    }

    // -----------------------------------------------------------------------
    // calledWith
    // -----------------------------------------------------------------------

    @Test
    void verify_calledWith_matchesArgument() throws Throwable {
        TestCapabilityHandler handler = TestCapabilityHandler.builder()
                .stub(UserCap.FetchUser.class, req -> req.userId().toUpperCase())
                .build();

        runtime.unsafeRunWithHandler(new UserCap.FetchUser("alice").toEffect(), handler);

        handler.verify(UserCap.FetchUser.class)
                .calledOnce()
                .calledWith(req -> req.userId().equals("alice"));
    }

    @Test
    void verify_calledWith_failsWhenNoMatchingArgument() throws Throwable {
        TestCapabilityHandler handler = TestCapabilityHandler.builder()
                .stub(UserCap.FetchUser.class, req -> "Alice")
                .build();

        runtime.unsafeRunWithHandler(new UserCap.FetchUser("alice").toEffect(), handler);

        assertThrows(AssertionError.class, () ->
                handler.verify(UserCap.FetchUser.class)
                        .calledWith(req -> req.userId().equals("bob")));
    }

    // -----------------------------------------------------------------------
    // End-to-end: multiple capabilities, stubs + verification
    // -----------------------------------------------------------------------

    @Test
    void endToEnd_multipleCapabilities_stubsAndVerifiesCorrectly() throws Throwable {
        TestCapabilityHandler handler = TestCapabilityHandler.builder()
                .stub(UserCap.FetchUser.class, req -> "Alice-" + req.userId())
                .stub(UserCap.DeleteUser.class, req -> true)
                .build();

        Effect<Throwable, Boolean> effect = new UserCap.FetchUser("u1").<Throwable>toEffect()
                .flatMap(__ -> new UserCap.DeleteUser("u1").toEffect());

        Boolean result = runtime.unsafeRunWithHandler(effect, handler);

        assertTrue(result);
        handler.verify(UserCap.FetchUser.class)
                .calledOnce()
                .calledWith(req -> req.userId().equals("u1"));
        handler.verify(UserCap.DeleteUser.class)
                .calledOnce()
                .calledWith(req -> req.userId().equals("u1"));
    }
}
