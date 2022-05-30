package dev.mccue.async;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Simple wrapper over an AtomicReference to provide an API for doing compare and swap operations.
 *
 * Modeled after the atom primitive in clojure.
 * @param <T> The type of data stored in the atom. This is assumed to be an immutable object.
 */
public final class Atom<T> {
    private final AtomicReference<T> ref;

    private Atom(T data) {
        this.ref = new AtomicReference<>(data);
    }

    /**
     * Creates an atom wrapping the given data.
     * @param data The data to be stored in the atom.
     * @param <T> The type of data stored in the atom. This is assumed to be an immutable object.
     * @return An atom containing the given data.
     */
    public static <T> Atom<T> of(T data) {
        return new Atom<>(data);
    }

    /**
     * Swaps the current value in the atom for the value returned by the function.
     * @param f The function to apply to the current value. It is expected that this
     *          will be a "pure" function and thus may be run multiple times.
     * @return The value in the Atom after the function is applied.
     */
    public T swap(Function<? super T, ? extends T> f) {
        while (true) {
            final var start = ref.get();
            final var res = f.apply(start);
            if (this.ref.compareAndSet(start, res)) {
                return res;
            }
        }
    }

    /**
     * Pair of the new value swapped into an atom and some value that was
     * derived in the course of calculating that new value.
     * @param <T> Type of the new value.
     * @param <R> Type of the derived value.
     */
    public record SwapResult<T, R>(T newValue, R derivedValue) {}

    /**
     * Performs a swap that carries over some context from the computation to the caller.
     *
     * For example, a basic usage would be to return some whether a value was inserted into a map.
     *
     * <pre>{@code
     * sealed interface PlayerJoinResult permits AlreadyInGame, Success {}
     * record AlreadyInGame() implements PlayerJoinResult{}
     * record Success(String playerId) implements PlayerJoinResult {}
     * // ...
     * final var playerId = UUID.randomUUID().toString();
     * final var gameAtom = Atom.of(Map.empty());
     * final var swapResult = gameAtom.complexSwap(game -> {
     *    if (game.contains(playerId)) {
     *        return new ComplexSwapResult<>(game, new AlreadyInGame());
     *    }
     *    else {
     *        return new ComplexSwapResult<>(game.put(playerId, new Object()), new Success(playerId));
     *    }
     * });
     *
     * if (swapResult.derivedValue() instanceof AlreadyInGame) {
     *     return "Oh no!";
     * }
     * else {
     *     return "hooray";
     * }
     * }</pre>
     *
     * @param f The function to apply to the current value. It is expected that this
     *          will be a "pure" function and thus may be run multiple times.
     * @param <R> The type of the context attached to the final result.
     * @return A pair of the new value put into the atom and the derived value from the
     * computation of that new value.
     */
    public <R> SwapResult<T, R> complexSwap(
            Function<? super T, SwapResult<? extends  T, ? extends R>> f
    ) {
        while (true) {
            final var start = ref.get();
            final var res = f.apply(start);
            if (this.ref.compareAndSet(start, res.newValue())) {
                return new SwapResult<>(res.newValue(), res.derivedValue());
            }
        }
    }

    /**
     * Resets the value in the atom to the given value.
     * @param data The new value to be stored in the atom.
     * @return The new value stored in the atom.
     */
    public T reset(T data) {
        this.ref.set(data);
        return data;
    }

    /**
     * Gets the atom's current value.
     * @return The atom's current value.
     */
    public T get() {
        return this.ref.get();
    }

    @Override
    public String toString() {
        return "Atom[value=" + this.get() + "]";
    }
}
