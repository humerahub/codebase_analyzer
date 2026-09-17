package org.example.analysis;

import org.example.model.DependencyEdge;
import org.example.model.Layer;
import org.example.model.SpringStereotypes;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
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
        // Fully-qualified names, not simple names: this codebase has genuine, unrelated
        // classes sharing a simple name in different packages (e.g. two distinct
        // InterestRateDaoImpl classes under dao.accounts and dao.setup). Matching on
        // simple name alone would silently merge them into one node.
        Set<String> ownTypeNames = allTypes.stream().map(CtType::getQualifiedName).collect(Collectors.toSet());

        List<DependencyEdge> edges = new ArrayList<>();
        Map<String, List<String>> interfaceToImpls = new HashMap<>();
        Map<String, Map<String, String>> qualifiedImpls = new HashMap<>();
        buildLayer2(allTypes, edges, interfaceToImpls, qualifiedImpls);

        Map<String, String> beanTypeToImpl = new HashMap<>();
        Map<String, Map<String, String>> qualifiedProviders = new HashMap<>();
        buildProviderRegistry(allTypes, edges, beanTypeToImpl, qualifiedProviders);

        buildLayer3(allTypes, interfaceToImpls, qualifiedImpls, beanTypeToImpl, qualifiedProviders, edges);
        buildLayer1(allTypes, ownTypeNames, edges);
        return edges;
    }

    // ---------- Layer 2: interface -> implementation registry, class inheritance ----------

    private void buildLayer2(List<CtType<?>> allTypes, List<DependencyEdge> edges,
                              Map<String, List<String>> interfaceToImpls,
                              Map<String, Map<String, String>> qualifiedImpls) {
        for (CtType<?> type : allTypes) {
            if (type instanceof CtClass<?> clazz) {
                String qualifiedClassName = clazz.getQualifiedName();
                String qualifier = extractQualifier(clazz);
                for (CtTypeReference<?> superInterface : clazz.getSuperInterfaces()) {
                    String ifaceName = superInterface.getQualifiedName();
                    interfaceToImpls
                            .computeIfAbsent(ifaceName, k -> new ArrayList<>())
                            .add(qualifiedClassName);
                    if (qualifier != null) {
                        qualifiedImpls
                                .computeIfAbsent(ifaceName, k -> new HashMap<>())
                                .put(qualifier, qualifiedClassName);
                    }
                }
                CtTypeReference<?> superclass = clazz.getSuperclass();
                if (superclass != null && !superclass.getQualifiedName().equals("java.lang.Object")) {
                    edges.add(new DependencyEdge(qualifiedClassName, superclass.getQualifiedName(),
                            Layer.LAYER2_EXTENDS, "class inheritance"));
                }
            }
        }
        interfaceToImpls.forEach((iface, impls) ->
                impls.forEach(impl -> edges.add(new DependencyEdge(iface, impl,
                        Layer.LAYER2_IMPLEMENTED_BY, "interface -> implementation"))));
    }

    // ---------- Layer 3b: @Bean / @Produces provider registry ----------
    // Handles cases interfaceToImpls can't: the impl isn't "implements X" in our
    // source, it's constructed inside a factory method or field, e.g.
    //   @Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    //   @Produces public PaymentService paymentService() { return new StripePaymentService(); }
    // Only resolves the simple, unambiguous case — see resolveProviderReturnType().
    // Providers additionally qualified with @Named("x") are recorded separately so
    // injection points asking for that exact qualifier can resolve unambiguously.

    private void buildProviderRegistry(List<CtType<?>> allTypes, List<DependencyEdge> edges,
                                        Map<String, String> beanTypeToImpl,
                                        Map<String, Map<String, String>> qualifiedProviders) {
        for (CtType<?> type : allTypes) {
            for (CtMethod<?> method : type.getMethods()) {
                boolean isProvider = method.getAnnotations().stream()
                        .map(a -> a.getAnnotationType().getSimpleName())
                        .anyMatch(SpringStereotypes.PROVIDER_ANNOTATIONS::contains);
                if (!isProvider) continue;

                String declaredType = method.getType() != null ? method.getType().getQualifiedName() : null;
                String resolvedType = resolveProviderReturnType(method);
                recordProvider(edges, beanTypeToImpl, qualifiedProviders, declaredType, resolvedType,
                        extractQualifier(method), "\"" + method.getSimpleName() + "()\" returns concrete type");
            }

            for (CtField<?> field : type.getFields()) {
                boolean isProvider = field.getAnnotations().stream()
                        .map(a -> a.getAnnotationType().getSimpleName())
                        .anyMatch(SpringStereotypes.PROVIDER_ANNOTATIONS::contains);
                if (!isProvider) continue;

                String declaredType = field.getType() != null ? field.getType().getQualifiedName() : null;
                String resolvedType = field.getDefaultExpression() instanceof CtConstructorCall<?> ctorCall
                        && ctorCall.getType() != null ? ctorCall.getType().getQualifiedName() : null;
                recordProvider(edges, beanTypeToImpl, qualifiedProviders, declaredType, resolvedType,
                        extractQualifier(field), "field \"" + field.getSimpleName() + "\" initialized to concrete type");
            }
        }
    }

    private void recordProvider(List<DependencyEdge> edges, Map<String, String> beanTypeToImpl,
                                 Map<String, Map<String, String>> qualifiedProviders,
                                 String declaredType, String resolvedType, String qualifier, String detailSuffix) {
        if (resolvedType == null || resolvedType.equals(declaredType)) return;

        beanTypeToImpl.put(declaredType, resolvedType);
        if (qualifier != null) {
            qualifiedProviders.computeIfAbsent(declaredType, k -> new HashMap<>()).put(qualifier, resolvedType);
        }
        edges.add(new DependencyEdge(declaredType, resolvedType,
                Layer.LAYER3_BEAN_PROVIDES, "@Bean/@Produces " + detailSuffix));
    }

    // Reads a @Named("value") annotation's string literal off a class, method, field, or
    // parameter — the qualifier used to pick between multiple candidates for the same type.
    // Returns null when there's no @Named annotation, or its value isn't a plain string literal.
    private String extractQualifier(CtElement element) {
        for (CtAnnotation<?> annotation : element.getAnnotations()) {
            if (!annotation.getAnnotationType().getSimpleName().equals(SpringStereotypes.QUALIFIER_ANNOTATION)) {
                continue;
            }
            String value = annotation.getValueAsString("value");
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    // Reads @EJB(mappedName = SOME_PREFIX + "TargetBeanClassName") off a field/parameter —
    // a second, distinct disambiguation mechanism from @Named. The value is usually a string
    // concatenation (a shared JNDI prefix + the literal bean name), not a plain literal, so this
    // walks to the rightmost operand of any concatenation chain to find that trailing literal.
    // Returns the simple class name the mappedName is pointing at, or null if @EJB has no
    // mappedName, or its value isn't ultimately a string literal we can read statically.
    private String extractEjbMappedNameTarget(CtElement element) {
        for (CtAnnotation<?> annotation : element.getAnnotations()) {
            if (!annotation.getAnnotationType().getSimpleName().equals("EJB")) continue;
            String target = trailingStringLiteral(annotation.getValue("mappedName"));
            if (target != null && !target.isBlank()) {
                return target;
            }
        }
        return null;
    }

    private String trailingStringLiteral(CtExpression<?> expr) {
        if (expr instanceof CtLiteral<?> literal && literal.getValue() instanceof String s) {
            return s;
        }
        if (expr instanceof CtBinaryOperator<?> binaryOperator) {
            return trailingStringLiteral(binaryOperator.getRightHandOperand());
        }
        return null;
    }

    // Resolves what a @Bean/@Produces method actually constructs, for the simple/unambiguous case only.
    // Returns null if the method doesn't have exactly one return statement, or that return
    // isn't a direct/one-hop-via-local-variable "new X()" — e.g. it delegates to another
    // method or object (authenticationManager() calling authenticationConfiguration.get...()),
    // which is a real "can't resolve statically" case, not a bug in this scan.
    private String resolveProviderReturnType(CtMethod<?> method) {
        if (method.getBody() == null) return null;

        List<CtReturn<?>> returns = method.getBody().getElements(new TypeFilter<>(CtReturn.class));
        if (returns.size() != 1) return null;

        CtExpression<?> returned = returns.get(0).getReturnedExpression();
        if (returned == null) return null;

        // Case 1: return new X(...);
        if (returned instanceof CtConstructorCall<?> ctorCall) {
            return ctorCall.getType() != null ? ctorCall.getType().getQualifiedName() : null;
        }

        // Case 2: X x = new X(...); ... return x;
        if (returned instanceof CtVariableRead<?> varRead) {
            String varName = varRead.getVariable().getSimpleName();
            List<CtLocalVariable<?>> locals = method.getBody().getElements(new TypeFilter<>(CtLocalVariable.class));
            for (CtLocalVariable<?> local : locals) {
                if (local.getSimpleName().equals(varName)
                        && local.getDefaultExpression() instanceof CtConstructorCall<?> ctorCall) {
                    return ctorCall.getType() != null ? ctorCall.getType().getQualifiedName() : null;
                }
            }
        }

        // Anything else (a delegated call, a conditional, a builder chain) — leave unresolved.
        return null;
    }

    // An injection point: the type asked for, plus its @Named qualifier and/or
    // @EJB(mappedName=...) target, if it has either.
    private record InjectionPoint(String typeName, String namedQualifier, String ejbMappedNameTarget) {
    }

    // ---------- Layer 3: DI wiring, resolved through the Layer 2 + provider registries ----------

    private void buildLayer3(List<CtType<?>> allTypes, Map<String, List<String>> interfaceToImpls,
                              Map<String, Map<String, String>> qualifiedImpls, Map<String, String> beanTypeToImpl,
                              Map<String, Map<String, String>> qualifiedProviders, List<DependencyEdge> edges) {
        for (CtType<?> type : allTypes) {
            String owner = type.getQualifiedName();
            boolean isManaged = type.getAnnotations().stream()
                    .map(a -> a.getAnnotationType().getSimpleName())
                    .anyMatch(SpringStereotypes.STEREOTYPE_ANNOTATIONS::contains);

            List<InjectionPoint> injectionPoints = new ArrayList<>();

            for (CtField<?> field : type.getFields()) {
                boolean isStatic = field.hasModifier(ModifierKind.STATIC);
                if (isStatic) continue;

                boolean hasInjectionAnnotation = field.getAnnotations().stream()
                        .map(a -> a.getAnnotationType().getSimpleName())
                        .anyMatch(SpringStereotypes.INJECTION_ANNOTATIONS::contains);

                boolean isFinal = field.hasModifier(ModifierKind.FINAL);
                boolean hasInitializer = field.getDefaultExpression() != null;
                boolean looksLikeConstructorInjection = isFinal && !hasInitializer
                        && type instanceof CtClass<?> ownerClass
                        && isAssignedFromMatchingConstructorParam(field, ownerClass);

                if (hasInjectionAnnotation || looksLikeConstructorInjection) {
                    injectionPoints.add(new InjectionPoint(field.getType().getQualifiedName(),
                            extractQualifier(field), extractEjbMappedNameTarget(field)));
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
                            injectionPoints.add(new InjectionPoint(param.getType().getQualifiedName(),
                                    extractQualifier(param), extractEjbMappedNameTarget(param)));
                        }
                    }
                }
            }

            for (InjectionPoint point : injectionPoints) {
                String injectedType = point.typeName();
                edges.add(new DependencyEdge(owner, injectedType, Layer.LAYER3_INJECTS, "field/constructor injection"));

                // Try qualifier-based resolution first, when the injection point named one
                // (e.g. @Named("stripe")) — this is what lets a genuinely multi-implementation
                // type resolve unambiguously instead of falling through to LAYER3_AMBIGUOUS.
                String qualifiedImpl = point.namedQualifier() == null ? null
                        : qualifiedProviders.getOrDefault(injectedType, Map.of()).get(point.namedQualifier());
                if (qualifiedImpl == null && point.namedQualifier() != null) {
                    qualifiedImpl = qualifiedImpls.getOrDefault(injectedType, Map.of()).get(point.namedQualifier());
                }

                List<String> impls = interfaceToImpls.get(injectedType);

                // Second disambiguation path: @EJB(mappedName = ... + "TargetBeanClassName")
                // names the target implementation's simple class name directly, rather than
                // via a shared qualifier value — match it against the candidate implementations.
                if (qualifiedImpl == null && point.ejbMappedNameTarget() != null && impls != null) {
                    qualifiedImpl = impls.stream()
                            .filter(impl -> impl.equals(point.ejbMappedNameTarget())
                                    || impl.endsWith("." + point.ejbMappedNameTarget()))
                            .findFirst()
                            .orElse(null);
                }

                if (qualifiedImpl != null) {
                    String via = point.namedQualifier() != null
                            ? "@Named(\"" + point.namedQualifier() + "\") qualifier match"
                            : "@EJB(mappedName=\"" + point.ejbMappedNameTarget() + "\") match";
                    edges.add(new DependencyEdge(owner, qualifiedImpl, Layer.LAYER3_RESOLVED, "DI resolved via " + via));
                } else if (impls != null) {
                    if (impls.size() == 1) {
                        // OrderService -> StripePaymentService: resolve the interface
                        // injection to the real concrete class.
                        edges.add(new DependencyEdge(owner, impls.get(0),
                                Layer.LAYER3_RESOLVED, "DI resolved via unique implementation"));
                    } else {
                        // Multiple implementations and nothing resolved one — flag it, don't guess.
                        edges.add(new DependencyEdge(owner, injectedType, Layer.LAYER3_AMBIGUOUS,
                                "multiple implementations (" + String.join(", ", impls) + ") - review recommended"));
                    }
                }

                // Same idea, but resolved via a @Bean/@Produces factory instead of "implements X"
                // — e.g. PasswordEncoder -> BCryptPasswordEncoder from SecurityFilter.passwordEncoder().
                String beanImpl = beanTypeToImpl.get(injectedType);
                if (beanImpl != null) {
                    edges.add(new DependencyEdge(owner, beanImpl,
                            Layer.LAYER3_BEAN_RESOLVED, "DI resolved via @Bean/@Produces method"));
                }
            }
        }
    }

    // Constructor-injection heuristic for a final field with no inline initializer: only
    // count it as injected if some constructor (a) takes a parameter of the same type as
    // the field, and (b) actually assigns that exact parameter to the field in its body
    // (e.g. "this.paymentService = paymentService;"). Just being final+uninitialized isn't
    // enough on its own — that also matches fields set to a locally computed value
    // (e.g. "this.id = UUID.randomUUID();"), which isn't a dependency at all.
    private boolean isAssignedFromMatchingConstructorParam(CtField<?> field, CtClass<?> clazz) {
        String fieldName = field.getSimpleName();
        String fieldType = field.getType().getQualifiedName();

        for (CtConstructor<?> ctor : clazz.getConstructors()) {
            if (ctor.getBody() == null) continue;

            Set<String> matchingParamNames = ctor.getParameters().stream()
                    .filter(p -> p.getType().getQualifiedName().equals(fieldType))
                    .map(CtParameter::getSimpleName)
                    .collect(Collectors.toSet());
            if (matchingParamNames.isEmpty()) continue;

            List<CtAssignment<?, ?>> assignments = ctor.getBody().getElements(new TypeFilter<>(CtAssignment.class));
            for (CtAssignment<?, ?> assignment : assignments) {
                boolean assignsThisField = assignment.getAssigned() instanceof CtFieldWrite<?> fw
                        && fw.getVariable().getSimpleName().equals(fieldName);
                boolean fromMatchingParam = assignment.getAssignment() instanceof CtVariableRead<?> vr
                        && matchingParamNames.contains(vr.getVariable().getSimpleName());
                if (assignsThisField && fromMatchingParam) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------- Layer 1: direct method call graph, filtered to our own code ----------

    private void buildLayer1(List<CtType<?>> allTypes, Set<String> ownTypeNames, List<DependencyEdge> edges) {
        for (CtType<?> type : allTypes) {
            for (CtMethod<?> method : type.getMethods()) {
                String caller = type.getQualifiedName() + "." + method.getSimpleName() + "()";
                List<CtInvocation<?>> invocations = method.getElements(new TypeFilter<>(CtInvocation.class));
                for (CtInvocation<?> invocation : invocations) {
                    CtExecutableReference<?> exec = invocation.getExecutable();
                    if (exec == null) continue;
                    CtTypeReference<?> declaringType = exec.getDeclaringType();
                    if (declaringType == null) continue;
                    String calleeType = declaringType.getQualifiedName();
                    if (!ownTypeNames.contains(calleeType)) continue; // skip java.*, Spring internals, etc. — noise
                    String callee = calleeType + "." + exec.getSimpleName() + "()";
                    edges.add(new DependencyEdge(caller, callee, Layer.LAYER1_CALLS, "direct method call"));
                }
            }
        }
    }
}
