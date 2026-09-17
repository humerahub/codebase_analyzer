package org.example.model;

import java.util.Set;

/** Annotations that mark a class as container-managed (Spring or Jakarta EE/CDI), for injection-point detection. */
public final class SpringStereotypes {

    public static final Set<String> STEREOTYPE_ANNOTATIONS = Set.of(
            // Spring
            "Component", "Service", "Repository", "Controller", "RestController", "Configuration",
            // Jakarta EE / CDI
            "Stateless", "Stateful", "Singleton", "ManagedBean",
            "ApplicationScoped", "RequestScoped", "SessionScoped", "ConversationScoped", "Named"
    );

    /** Annotations that mark a field/constructor as an explicit injection point. */
    public static final Set<String> INJECTION_ANNOTATIONS = Set.of(
            "Autowired", "Inject", "EJB", "Resource"
    );

    /** Annotations that mark a method/field as a factory that provides a bean instance. */
    public static final Set<String> PROVIDER_ANNOTATIONS = Set.of("Bean", "Produces");

    /** The qualifier annotation used to disambiguate between multiple implementations by name. */
    public static final String QUALIFIER_ANNOTATION = "Named";

    private SpringStereotypes() {
    }
}
