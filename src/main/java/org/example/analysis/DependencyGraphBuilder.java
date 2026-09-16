package org.example.analysis;

import org.example.model.DependencyEdge;
import org.example.model.Layer;
import org.example.model.SpringStereotypes;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the 3-layer dependency graph from a Spoon model:
 *   1. LAYER2_*        interface -> implementation, class inheritance
 *   2. LAYER3_BEAN_*   what a @Bean method actually constructs
 *   3. LAYER3_*        Spring DI wiring, resolved through steps 1 and 2
 *   4. LAYER1_CALLS    direct method calls, filtered to our own code
 *
 * Each layer can depend on the ones built before it, so buildEdges() runs them
 * in that fixed order.
 */
public final class DependencyGraphBuilder {

    public List<DependencyEdge> buildEdges(List<CtType<?>> allTypes) {
        Set<String> ownTypeNames = allTypes.stream().map(CtType::getSimpleName).collect(Collectors.toSet());

        List<DependencyEdge> edges = new ArrayList<>();
        Map<String, List<String>> interfaceToImpls = buildLayer2(allTypes, edges);
        Map<String, String> beanTypeToImpl = buildBeanRegistry(allTypes, edges);
        buildLayer3(allTypes, interfaceToImpls, beanTypeToImpl, edges);
        buildLayer1(allTypes, ownTypeNames, edges);
        return edges;
    }

    // ---------- Layer 2: interface -> implementation registry, class inheritance ----------

    private Map<String, List<String>> buildLayer2(List<CtType<?>> allTypes, List<DependencyEdge> edges) {
        Map<String, List<String>> interfaceToImpls = new HashMap<>();
        for (CtType<?> type : allTypes) {
            if (type instanceof CtClass<?> clazz) {
                for (CtTypeReference<?> superInterface : clazz.getSuperInterfaces()) {
                    interfaceToImpls
                            .computeIfAbsent(superInterface.getSimpleName(), k -> new ArrayList<>())
                            .add(clazz.getSimpleName());
                }
                CtTypeReference<?> superclass = clazz.getSuperclass();
                if (superclass != null && !superclass.getSimpleName().equals("Object")) {
                    edges.add(new DependencyEdge(clazz.getSimpleName(), superclass.getSimpleName(),
                            Layer.LAYER2_EXTENDS, "class inheritance"));
                }
            }
        }
        interfaceToImpls.forEach((iface, impls) ->
                impls.forEach(impl -> edges.add(new DependencyEdge(iface, impl,
                        Layer.LAYER2_IMPLEMENTED_BY, "interface -> implementation"))));
        return interfaceToImpls;
    }

    // ---------- Layer 3b: @Bean method registry ----------
    // Handles cases interfaceToImpls can't: the impl isn't "implements X" in our
    // source, it's constructed inside a @Bean factory method, e.g.
    //   @Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    // Only resolves the simple, unambiguous case — see resolveBeanReturnType().

    private Map<String, String> buildBeanRegistry(List<CtType<?>> allTypes, List<DependencyEdge> edges) {
        Map<String, String> beanTypeToImpl = new HashMap<>();
        for (CtType<?> type : allTypes) {
            for (CtMethod<?> method : type.getMethods()) {
                boolean isBean = method.getAnnotations().stream()
                        .anyMatch(a -> a.getAnnotationType().getSimpleName().equals("Bean"));
                if (!isBean) continue;

                String declaredType = method.getType() != null ? method.getType().getSimpleName() : null;
                String resolvedType = resolveBeanReturnType(method);

                if (resolvedType != null && !resolvedType.equals(declaredType)) {
                    beanTypeToImpl.put(declaredType, resolvedType);
                    edges.add(new DependencyEdge(declaredType, resolvedType,
                            Layer.LAYER3_BEAN_PROVIDES,
                            "@Bean method \"" + method.getSimpleName() + "()\" returns concrete type"));
                }
            }
        }
        return beanTypeToImpl;
    }

    // Resolves what a @Bean method actually constructs, for the simple/unambiguous case only.
    // Returns null if the method doesn't have exactly one return statement, or that return
    // isn't a direct/one-hop-via-local-variable "new X()" — e.g. it delegates to another
    // method or object (authenticationManager() calling authenticationConfiguration.get...()),
    // which is a real "can't resolve statically" case, not a bug in this scan.
    private String resolveBeanReturnType(CtMethod<?> method) {
        if (method.getBody() == null) return null;

        List<CtReturn<?>> returns = method.getBody().getElements(new TypeFilter<>(CtReturn.class));
        if (returns.size() != 1) return null;

        CtExpression<?> returned = returns.get(0).getReturnedExpression();
        if (returned == null) return null;

        // Case 1: return new X(...);
        if (returned instanceof CtConstructorCall<?> ctorCall) {
            return ctorCall.getType() != null ? ctorCall.getType().getSimpleName() : null;
        }

        // Case 2: X x = new X(...); ... return x;
        if (returned instanceof CtVariableRead<?> varRead) {
            String varName = varRead.getVariable().getSimpleName();
            List<CtLocalVariable<?>> locals = method.getBody().getElements(new TypeFilter<>(CtLocalVariable.class));
            for (CtLocalVariable<?> local : locals) {
                if (local.getSimpleName().equals(varName)
                        && local.getDefaultExpression() instanceof CtConstructorCall<?> ctorCall) {
                    return ctorCall.getType() != null ? ctorCall.getType().getSimpleName() : null;
                }
            }
        }

        // Anything else (a delegated call, a conditional, a builder chain) — leave unresolved.
        return null;
    }

    // ---------- Layer 3: Spring DI wiring, resolved through the Layer 2 + bean registries ----------

    private void buildLayer3(List<CtType<?>> allTypes, Map<String, List<String>> interfaceToImpls,
                              Map<String, String> beanTypeToImpl, List<DependencyEdge> edges) {
        for (CtType<?> type : allTypes) {
            String owner = type.getSimpleName();
            boolean isManaged = type.getAnnotations().stream()
                    .map(a -> a.getAnnotationType().getSimpleName())
                    .anyMatch(SpringStereotypes.STEREOTYPE_ANNOTATIONS::contains);

            List<String> injectedTypeNames = new ArrayList<>();

            for (CtField<?> field : type.getFields()) {
                boolean isStatic = field.hasModifier(ModifierKind.STATIC);
                if (isStatic) continue;

                boolean hasInjectionAnnotation = field.getAnnotations().stream()
                        .map(a -> a.getAnnotationType().getSimpleName())
                        .anyMatch(SpringStereotypes.INJECTION_ANNOTATIONS::contains);

                boolean isFinal = field.hasModifier(ModifierKind.FINAL);
                boolean hasInitializer = field.getDefaultExpression() != null;
                boolean looksLikeConstructorInjection = isFinal && !hasInitializer;

                if (hasInjectionAnnotation || looksLikeConstructorInjection) {
                    injectedTypeNames.add(field.getType().getSimpleName());
                }
            }

            if (type instanceof CtClass<?> clazz) {
                var constructors = clazz.getConstructors();
                boolean singleConstructor = constructors.size() == 1;
                for (CtConstructor<?> ctor : constructors) {
                    boolean hasInjectionAnnotation = ctor.getAnnotations().stream()
                            .map(a -> a.getAnnotationType().getSimpleName())
                            .anyMatch(SpringStereotypes.INJECTION_ANNOTATIONS::contains);
                    boolean isInjectionConstructor = hasInjectionAnnotation || (isManaged && singleConstructor);
                    if (isInjectionConstructor) {
                        for (CtParameter<?> param : ctor.getParameters()) {
                            injectedTypeNames.add(param.getType().getSimpleName());
                        }
                    }
                }
            }

            for (String injectedType : injectedTypeNames) {
                edges.add(new DependencyEdge(owner, injectedType, Layer.LAYER3_INJECTS, "field/constructor injection"));

                List<String> impls = interfaceToImpls.get(injectedType);
                if (impls != null) {
                    if (impls.size() == 1) {
                        // OrderService -> StripePaymentService: resolve the interface
                        // injection to the real concrete class.
                        edges.add(new DependencyEdge(owner, impls.get(0),
                                Layer.LAYER3_RESOLVED, "DI resolved via unique implementation"));
                    } else {
                        // Multiple implementations, no @Qualifier logic built yet — flag it, don't guess.
                        edges.add(new DependencyEdge(owner, injectedType, Layer.LAYER3_AMBIGUOUS,
                                "multiple implementations (" + String.join(", ", impls) + ") - review recommended"));
                    }
                }

                // Same idea, but resolved via a @Bean factory method instead of "implements X"
                // — e.g. PasswordEncoder -> BCryptPasswordEncoder from SecurityFilter.passwordEncoder().
                String beanImpl = beanTypeToImpl.get(injectedType);
                if (beanImpl != null) {
                    edges.add(new DependencyEdge(owner, beanImpl,
                            Layer.LAYER3_BEAN_RESOLVED, "DI resolved via @Bean method"));
                }
            }
        }
    }

    // ---------- Layer 1: direct method call graph, filtered to our own code ----------

    private void buildLayer1(List<CtType<?>> allTypes, Set<String> ownTypeNames, List<DependencyEdge> edges) {
        for (CtType<?> type : allTypes) {
            for (CtMethod<?> method : type.getMethods()) {
                String caller = type.getSimpleName() + "." + method.getSimpleName() + "()";
                List<CtInvocation<?>> invocations = method.getElements(new TypeFilter<>(CtInvocation.class));
                for (CtInvocation<?> invocation : invocations) {
                    CtExecutableReference<?> exec = invocation.getExecutable();
                    if (exec == null) continue;
                    CtTypeReference<?> declaringType = exec.getDeclaringType();
                    if (declaringType == null) continue;
                    String calleeType = declaringType.getSimpleName();
                    if (!ownTypeNames.contains(calleeType)) continue; // skip java.*, Spring internals, etc. — noise
                    String callee = calleeType + "." + exec.getSimpleName() + "()";
                    edges.add(new DependencyEdge(caller, callee, Layer.LAYER1_CALLS, "direct method call"));
                }
            }
        }
    }
}
