package io.quarkus.qute;

/**
 * Value resolvers are used when evaluating expressions.
 * <p>
 * First the resolvers that apply to the given {@link EvalContext} are filtered. Then the resolver with highest priority is used
 * to resolve the data. If {@link Results#isNotFound(Object)} is returned the next available resolver is tried.
 *
 * @see EvalContext
 * @see EngineBuilder#addValueResolver(ValueResolver)
 * @see EngineConfiguration
 */
public interface ValueResolver extends Resolver, WithPriority {

    /**
     *
     * @param context
     * @return {@code true} if this resolver applies to the given context
     */
    default boolean appliesTo(EvalContext context) {
        return true;
    }

    /**
     * When {@link #appliesTo(EvalContext)} returns {@code true} for a specific {@link EvalContext} and the subsequent
     * invocation of {@link #resolve(EvalContext)} does not return {@link Results#NotFound} the value resolver returned from
     * this method is cached for the specific part of an expression.
     * <p>
     * By default, the resolver itself is cached. However, it is also possible to return an optimized version.
     *
     * @param context
     * @return the resolver that should be cached
     */
    default ValueResolver getCachedResolver(EvalContext context) {
        return this;
    }

    /**
     *
     * @return a new builder
     */
    static ValueResolverBuilder builder() {
        return new ValueResolverBuilder();
    }

    // Utility methods

    static boolean matchClass(EvalContext ctx, Class<?> clazz) {
        return ctx.getBase() != null && clazz.isAssignableFrom(ctx.getBase().getClass());
    }

}