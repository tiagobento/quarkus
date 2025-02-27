package io.quarkus.qute;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * This value resolver can be used to access public members of classes via reflection.
 */
public class ReflectionValueResolver implements ValueResolver {

    /**
     * Lazy loading cache of lookup attempts (contains both hits and misses)
     */
    private final ConcurrentMap<MemberKey, Optional<AccessorCandidate>> candidates = new ConcurrentHashMap<>();

    private static final AccessorCandidate ARRAY_GET_LENGTH = ec -> instance -> CompletedStage.of(Array.getLength(instance));

    public static final String GET_PREFIX = "get";
    public static final String IS_PREFIX = "is";
    public static final String HAS_PREFIX = "has";

    @Override
    public int getPriority() {
        return -1;
    }

    @Override
    public boolean appliesTo(EvalContext context) {
        Object base = context.getBase();
        if (base == null) {
            return false;
        }
        // Check if there is a member with the given name and number of params
        return candidates.computeIfAbsent(MemberKey.from(context), this::findCandidate).isPresent();
    }

    @Override
    public CompletionStage<Object> resolve(EvalContext context) {
        Object base = context.getBase();
        // At this point the candidate for the given key should be already computed
        AccessorCandidate candidate = candidates.get(MemberKey.from(context)).orElse(null);
        if (candidate == null) {
            return Results.notFound(context);
        }
        ValueAccessor accessor = candidate.getAccessor(context);
        if (accessor == null) {
            return Results.notFound(context);
        }
        return accessor.getValue(base);
    }

    @Override
    public ValueResolver getCachedResolver(EvalContext context) {
        // The value must be computed and the accessor must exist
        AccessorCandidate candidate = candidates.get(MemberKey.from(context)).orElseThrow();
        return candidate.isShared(context)
                ? new AccessorResolver(context.getBase().getClass(), candidate.getAccessor(context))
                : new CandidateResolver(context.getBase().getClass(), candidate);
    }

    public void clearCache() {
        candidates.clear();
    }

    private Optional<AccessorCandidate> findCandidate(MemberKey key) {
        if (key.clazz.isArray()) {
            if (key.name.equals("length") && key.numberOfParams == 0) {
                return Optional.of(ARRAY_GET_LENGTH);
            } else {
                return Optional.empty();
            }
        }
        if (key.numberOfParams > 0) {
            List<Method> methods = findMethods(key.clazz, key.name, key.numberOfParams);
            return methods.isEmpty() ? Optional.empty() : Optional.of(new MethodsCandidate(methods));
        } else {
            Method foundMethod = findMethodNoArgs(key.clazz, key.name);
            if (foundMethod != null) {
                foundMethod.trySetAccessible();
                return Optional.of(new GetterAccessor(foundMethod));
            }
            Field foundField = findField(key.clazz, key.name);
            if (foundField != null) {
                foundField.trySetAccessible();
                return Optional.of(new FieldAccessor(foundField));
            }
        }
        // Member not found
        return Optional.empty();
    }

    private Method findMethodNoArgs(Class<?> clazz, String name) {
        Method foundMatch = null;
        Method foundGetterMatch = null;
        Method foundBooleanMatch = null;

        // Explore interface methods first...
        List<Class<?>> classes = new ArrayList<>();
        Collections.addAll(classes, clazz.getInterfaces());
        Class<?> superClass = clazz.getSuperclass();
        while (superClass != null) {
            Collections.addAll(classes, superClass.getInterfaces());
            superClass = superClass.getSuperclass();
        }
        classes.add(clazz);

        for (Class<?> clazzToTest : classes) {
            for (Method method : clazzToTest.getMethods()) {
                if (!isMethodProperty(method)) {
                    continue;
                }
                if (name.equals(method.getName())) {
                    foundMatch = method;
                } else if (matchesPrefix(name, method.getName(),
                        GET_PREFIX)) {
                    foundGetterMatch = method;
                } else if (isBoolean(method.getReturnType()) && (matchesPrefix(name, method.getName(),
                        IS_PREFIX) || matchesPrefix(name, method.getName(), HAS_PREFIX))) {
                    foundBooleanMatch = method;
                }
            }
            if (foundMatch == null) {
                foundMatch = (foundGetterMatch != null ? foundGetterMatch : foundBooleanMatch);
            }
            if (foundMatch != null) {
                break;
            }
        }
        return foundMatch;
    }

    private Field findField(Class<?> clazz, String name) {
        Field found = null;
        for (Field field : clazz.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    && field.getName().equals(name)) {
                found = field;
            }
        }
        return found;
    }

    private static List<Method> findMethods(Class<?> clazz, String name, int numberOfParams) {
        List<Method> foundMatch = new ArrayList<>();

        List<Class<?>> hierarchy = new ArrayList<>();
        Collections.addAll(hierarchy, clazz.getInterfaces());
        Class<?> superClass = clazz.getSuperclass();
        while (superClass != null) {
            Collections.addAll(hierarchy, superClass.getInterfaces());
            superClass = superClass.getSuperclass();
        }
        hierarchy.add(clazz);

        for (Class<?> clazzToTest : hierarchy) {
            for (Method method : clazzToTest.getMethods()) {
                if (isMethodCandidate(method)
                        && name.equals(method.getName())) {
                    foundMatch.add(method);
                    method.trySetAccessible();
                }
            }
        }
        return foundMatch.size() == 1 ? List.of(foundMatch.get(0)) : foundMatch;
    }

    private static boolean isMethodCandidate(Method method) {
        return method != null
                && Modifier.isPublic(method.getModifiers())
                && !Modifier.isStatic(method.getModifiers())
                && !method.getReturnType().equals(Void.TYPE)
                && !method.isBridge()
                && !Object.class.equals(method.getDeclaringClass());
    }

    private static boolean isMethodProperty(Method method) {
        return isMethodCandidate(method)
                && method.getParameterCount() == 0;
    }

    private static boolean matchesPrefix(String name, String methodName,
            String prefix) {
        return methodName.startsWith(prefix)
                && decapitalize(methodName.substring(prefix.length(), methodName.length())).equals(name);
    }

    private static boolean isBoolean(Class<?> type) {
        return type.equals(Boolean.class) || type.equals(boolean.class);
    }

    static String decapitalize(String name) {
        if (name == null || name.length() == 0) {
            return name;
        }
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1)) &&
                Character.isUpperCase(name.charAt(0))) {
            return name;
        }
        char chars[] = name.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }

    static class AccessorResolver implements ValueResolver {

        private final Class<?> matchedClass;
        private final ValueAccessor accessor;

        private AccessorResolver(Class<?> matchedClass, ValueAccessor accessor) {
            this.matchedClass = Objects.requireNonNull(matchedClass);
            this.accessor = Objects.requireNonNull(accessor);
        }

        @Override
        public boolean appliesTo(EvalContext context) {
            return ValueResolver.matchClass(context, matchedClass);
        }

        @Override
        public CompletionStage<Object> resolve(EvalContext context) {
            return accessor.getValue(context.getBase());
        }

    }

    static class CandidateResolver implements ValueResolver {

        private final Class<?> matchedClass;
        private final AccessorCandidate candidate;

        private CandidateResolver(Class<?> matchedClass, AccessorCandidate candidate) {
            this.matchedClass = Objects.requireNonNull(matchedClass);
            this.candidate = Objects.requireNonNull(candidate);
            ;
        }

        @Override
        public boolean appliesTo(EvalContext context) {
            return ValueResolver.matchClass(context, matchedClass);
        }

        @Override
        public CompletionStage<Object> resolve(EvalContext context) {
            return candidate.getAccessor(context).getValue(context.getBase());
        }

    }

}
